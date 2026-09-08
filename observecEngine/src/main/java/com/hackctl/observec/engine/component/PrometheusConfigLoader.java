package com.hackctl.observec.engine.component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

@Component
public class PrometheusConfigLoader {

    public static final String SELF_SCRAPE_JOB_NAME = "observecPrometheusJobSelf";

    private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern FILE_SEGMENT = Pattern.compile("[A-Za-z0-9_.-]+\\.ya?ml");

    private static final Pattern FILE_SEGMENT_NO_EXT = Pattern.compile("[A-Za-z0-9_.-]+");

    private final YAMLMapper yamlMapper = new YAMLMapper();
    private final String prometheusConfigPath;
    private final String prometheusLogPath;
    private final String prometheusDataDir;
    private final String prometheusRuleFilesDir;
    private final String prometheusServiceDiscoveryDir;
    private final String prometheusKubeConfigsDir;
    private ObjectNode prometheusConfig;

    @Autowired(required = false)
    @Lazy
    private GitService gitService;

    public PrometheusConfigLoader(
            @Value("${observec.prometheus.config-path:/observec/observecData/prometheus/prometheusConfig.yml}") String prometheusConfigPath,
            @Value("${observec.prometheus.log-path:/observec/observecData/prometheus/prometheus.log}") String prometheusLogPath,
            @Value("${observec.prometheus.data-dir:/observec/observecData/prometheus/prometheusData}") String prometheusDataDir,
            @Value("${observec.prometheus.rule-files-dir:/observec/observecData/prometheus/ruleFiles}") String prometheusRuleFilesDir,
            @Value("${observec.prometheus.service-discovery-dir:/observec/observecData/prometheus/serviceDiscoveryFiles}") String prometheusServiceDiscoveryDir,
            @Value("${observec.prometheus.kube-configs-dir:/observec/observecData/prometheus/kubeConfigs}") String prometheusKubeConfigsDir) {
        this.prometheusConfigPath = prometheusConfigPath;
        this.prometheusLogPath = prometheusLogPath;
        this.prometheusDataDir = prometheusDataDir;
        this.prometheusRuleFilesDir = prometheusRuleFilesDir;
        this.prometheusServiceDiscoveryDir = prometheusServiceDiscoveryDir;
        this.prometheusKubeConfigsDir = prometheusKubeConfigsDir;
    }

    @PostConstruct
    public void loadPrometheusConfig() {
        File prometheusConfigFile = new File(prometheusConfigPath);

        try {
            if (!prometheusConfigFile.exists()) {
                createDefaultPrometheusConfig(prometheusConfigFile);
            }

            JsonNode loadedPrometheusConfig = yamlMapper.readTree(prometheusConfigFile);
            if (!(loadedPrometheusConfig instanceof ObjectNode)) {
                throw new IllegalStateException("invalid prometheus config structure at " + prometheusConfigPath);
            }

            this.prometheusConfig = (ObjectNode) loadedPrometheusConfig;
            if (!(prometheusConfig.get("global") instanceof ObjectNode)) {
                prometheusConfig.set("global", yamlMapper.createObjectNode());
            }
            if (!(prometheusConfig.get("rule_files") instanceof ArrayNode)) {
                prometheusConfig.set("rule_files", yamlMapper.createArrayNode());
            }
            if (!(prometheusConfig.get("scrape_configs") instanceof ArrayNode)) {
                prometheusConfig.set("scrape_configs", yamlMapper.createArrayNode());
            }
            syncRuleFilesConfig();
            ensureSelfScrapeJob();
            savePrometheusConfig();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to load prometheus config from " + prometheusConfigPath, exception);
        }
    }

    public ObjectNode getPrometheusConfig() {
        return prometheusConfig;
    }

    public ArrayNode getScrapeConfigs() {
        return (ArrayNode) prometheusConfig.get("scrape_configs");
    }

    public boolean isSelfScrapeJob(String jobName) {
        return SELF_SCRAPE_JOB_NAME.equals(jobName);
    }

    public ObjectNode createSelfScrapeJobConfig() {
        ObjectNode selfJob = yamlMapper.createObjectNode();
        selfJob.put("job_name", SELF_SCRAPE_JOB_NAME);
        selfJob.put("scrape_interval", "1h");
        selfJob.put("scrape_timeout", "10s");

        ObjectNode staticConfig = yamlMapper.createObjectNode();
        staticConfig.set("targets", yamlMapper.createArrayNode().add("localhost:9090"));
        selfJob.set("static_configs", yamlMapper.createArrayNode().add(staticConfig));

        ObjectNode dropAllMetrics = yamlMapper.createObjectNode();
        dropAllMetrics.set("source_labels", yamlMapper.createArrayNode().add("__name__"));
        dropAllMetrics.put("regex", ".+");
        dropAllMetrics.put("action", "drop");
        selfJob.set("metric_relabel_configs", yamlMapper.createArrayNode().add(dropAllMetrics));

        return selfJob;
    }

    public boolean ensureSelfScrapeJob() {
        ArrayNode scrapeConfigs = getScrapeConfigs();
        if (!scrapeConfigs.isEmpty()) {
            return false;
        }
        scrapeConfigs.add(createSelfScrapeJobConfig());
        return true;
    }

    public String getPrometheusConfigAsYaml() throws IOException {
        return yamlMapper.writeValueAsString(prometheusConfig);
    }

    public void savePrometheusConfig() throws IOException {
        yamlMapper.writerWithDefaultPrettyPrinter().writeValue(new File(prometheusConfigPath), prometheusConfig);
        if (gitService != null) gitService.autoCommit("chore(prometheus): update prometheusConfig.yml");
    }

    public List<String> listRuleFiles() {
        return listYamlFilesRecursive(prometheusRuleFilesDir);
    }

    public List<String> listRuleDirectories() {
        return listEmptyManagedDirectories(prometheusRuleFilesDir);
    }

    public boolean hasRuleFile(String ruleFileName) {
        return managedFile(prometheusRuleFilesDir, ruleFileName).exists();
    }

    public JsonNode getRuleFile(String ruleFileName) throws IOException {
        return readYamlFile(managedFile(prometheusRuleFilesDir, ruleFileName));
    }

    public void saveRuleFile(String ruleFileName, JsonNode contents) throws IOException {
        writeYamlFile(managedFile(prometheusRuleFilesDir, ruleFileName), contents);
        syncRuleFilesConfig();
        if (gitService != null) gitService.autoCommit("chore(prometheus): update rule file " + ruleFileName);
    }

    public void deleteRuleFile(String ruleFileName) throws IOException {
        deleteYamlFile(managedFile(prometheusRuleFilesDir, ruleFileName));
        syncRuleFilesConfig();
        if (gitService != null) gitService.autoCommit("chore(prometheus): delete rule file " + ruleFileName);
    }

    public void createRuleDirectory(String directoryPath) throws IOException {
        createManagedDirectory(prometheusRuleFilesDir, directoryPath);
        if (gitService != null) gitService.autoCommit("chore(prometheus): create rule dir " + directoryPath);
    }

    public void deleteRuleDirectory(String directoryPath) throws IOException {
        deleteManagedDirectory(prometheusRuleFilesDir, directoryPath);
        if (gitService != null) gitService.autoCommit("chore(prometheus): delete rule dir " + directoryPath);
    }

    public List<String> listServiceDiscoveryFiles() {
        return listYamlFilesRecursive(prometheusServiceDiscoveryDir);
    }

    public List<String> listServiceDiscoveryDirectories() {
        return listEmptyManagedDirectories(prometheusServiceDiscoveryDir);
    }

    public boolean hasServiceDiscoveryFile(String serviceDiscoveryFileName) {
        return managedFile(prometheusServiceDiscoveryDir, serviceDiscoveryFileName).exists();
    }

    public JsonNode getServiceDiscoveryFile(String serviceDiscoveryFileName) throws IOException {
        return readYamlFile(managedFile(prometheusServiceDiscoveryDir, serviceDiscoveryFileName));
    }

    public void saveServiceDiscoveryFile(String serviceDiscoveryFileName, JsonNode contents) throws IOException {
        writeYamlFile(managedFile(prometheusServiceDiscoveryDir, serviceDiscoveryFileName), contents);
        if (gitService != null) gitService.autoCommit("chore(prometheus): update sd file " + serviceDiscoveryFileName);
    }

    public void deleteServiceDiscoveryFile(String serviceDiscoveryFileName) throws IOException {
        deleteYamlFile(managedFile(prometheusServiceDiscoveryDir, serviceDiscoveryFileName));
        if (gitService != null) gitService.autoCommit("chore(prometheus): delete sd file " + serviceDiscoveryFileName);
    }

    public void createServiceDiscoveryDirectory(String directoryPath) throws IOException {
        createManagedDirectory(prometheusServiceDiscoveryDir, directoryPath);
        if (gitService != null) gitService.autoCommit("chore(prometheus): create sd dir " + directoryPath);
    }

    public void deleteServiceDiscoveryDirectory(String directoryPath) throws IOException {
        deleteManagedDirectory(prometheusServiceDiscoveryDir, directoryPath);
        if (gitService != null) gitService.autoCommit("chore(prometheus): delete sd dir " + directoryPath);
    }

    public String getKubeConfigsDir() {
        return prometheusKubeConfigsDir;
    }

    public List<String> listKubeConfigs() {
        List<String> names = new ArrayList<>();
        collectKubeConfigs(new File(prometheusKubeConfigsDir), new File(prometheusKubeConfigsDir), names);
        java.util.Collections.sort(names);
        return names;
    }

    private void collectKubeConfigs(File baseDir, File currentDir, List<String> results) {
        File[] entries = currentDir.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (entry.isDirectory()) {
                collectKubeConfigs(baseDir, entry, results);
            } else if (entry.isFile()) {
                String relativePath = baseDir.toPath().relativize(entry.toPath()).toString().replace('\\', '/');
                results.add(relativePath);
            }
        }
    }

    public boolean hasKubeConfig(String kubeConfigName) {
        return kubeConfigFile(kubeConfigName).exists();
    }

    public File kubeConfigFile(String kubeConfigName) {
        return resolvePathUnderBase(new File(prometheusKubeConfigsDir), normalizeKubeConfigName(kubeConfigName));
    }

    public String saveKubeConfig(String kubeConfigName, byte[] contents) throws IOException {
        String normalized = normalizeKubeConfigName(kubeConfigName);
        File target = resolvePathUnderBase(new File(prometheusKubeConfigsDir), normalized);
        target.getParentFile().mkdirs();
        java.nio.file.Files.write(target.toPath(), contents);
        if (gitService != null) gitService.autoCommit("chore(prometheus): update kubeconfig " + normalized);
        return target.getAbsolutePath();
    }

    public void deleteKubeConfig(String kubeConfigName) throws IOException {
        String normalized = normalizeKubeConfigName(kubeConfigName);
        File target = resolvePathUnderBase(new File(prometheusKubeConfigsDir), normalized);
        if (!target.exists() || !target.isFile()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "kubeconfig does not exist : " + normalized);
        }
        if (!target.delete()) {
            throw new IOException("could not delete kubeconfig : " + target.getAbsolutePath());
        }
        if (gitService != null) gitService.autoCommit("chore(prometheus): delete kubeconfig " + normalized);
    }

    public static String normalizeKubeConfigName(String kubeConfigName) {
        if (kubeConfigName == null || kubeConfigName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid kubeconfig name : " + kubeConfigName);
        }
        String normalized = kubeConfigName.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid kubeconfig name : " + kubeConfigName);
        }
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.startsWith(".") || !PATH_SEGMENT.matcher(segment).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid kubeconfig name : " + kubeConfigName);
            }
        }
        return normalized;
    }

    public static String normalizeManagedFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid file name : " + fileName);
        }

        String normalizedPath = normalizeManagedFilePath(fileName);
        int lastSlash = normalizedPath.lastIndexOf('/');
        String directory = lastSlash >= 0 ? normalizedPath.substring(0, lastSlash + 1) : "";
        String baseName = lastSlash >= 0 ? normalizedPath.substring(lastSlash + 1) : normalizedPath;

        if (baseName.endsWith(".yaml")) {
            baseName = baseName.substring(0, baseName.length() - ".yaml".length()) + ".yml";
        } else if (!baseName.endsWith(".yml")) {
            baseName = baseName + ".yml";
        }
        return directory + baseName;
    }

    public static String normalizeManagedDirectoryPath(String directoryPath) {
        return normalizeManagedPath(directoryPath, false);
    }

    private static String normalizeManagedFilePath(String filePath) {
        return normalizeManagedPath(filePath, true);
    }

    public void syncRuleFilesConfig() {
        ArrayNode ruleFiles = yamlMapper.createArrayNode();
        File baseDir = new File(prometheusRuleFilesDir);
        for (String relativePath : listYamlFilesRecursive(prometheusRuleFilesDir)) {
            ruleFiles.add(new File(baseDir, relativePath).getAbsolutePath());
        }
        prometheusConfig.set("rule_files", ruleFiles);
    }

    public java.util.List<String> getPrometheusLogTail(int maxLines) throws IOException {
        File prometheusLogFile = new File(prometheusLogPath);
        if (!prometheusLogFile.exists()) {
            return java.util.Collections.emptyList();
        }

        java.util.Deque<String> tail = new java.util.ArrayDeque<>(maxLines);
        try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(prometheusLogFile.toPath())) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == maxLines) {
                    tail.pollFirst();
                }
                tail.addLast(line);
            }
        }
        return new ArrayList<>(tail);
    }

    public boolean isPrometheusRunning() throws IOException, InterruptedException {
        Process checkProcess = new ProcessBuilder("pgrep", "-f", "prometheus --config.file").start();
        return checkProcess.waitFor() == 0;
    }

    public void startPrometheus() throws IOException, InterruptedException {
        if (isPrometheusRunning()) {
            return;
        }

        File logFile = new File(prometheusLogPath);
        logFile.getParentFile().mkdirs();
        new File(prometheusDataDir).mkdirs();

        ProcessBuilder processBuilder = new ProcessBuilder(
                "prometheus",
                "--config.file=" + prometheusConfigPath,
                "--web.listen-address=:9090",
                "--storage.tsdb.path=" + prometheusDataDir,
                "--storage.tsdb.retention.time=15d",
                "--web.enable-lifecycle");
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(logFile));
        processBuilder.start();
    }

    public boolean reloadPrometheus() throws IOException, InterruptedException {
        if (!isPrometheusRunning()) {
            return false;
        }

        Process reloadProcess = new ProcessBuilder("pkill", "-HUP", "-f", "prometheus --config.file").start();
        return reloadProcess.waitFor() == 0;
    }

    public boolean stopPrometheus() throws IOException, InterruptedException {
        if (!isPrometheusRunning()) {
            return true;
        }
        new ProcessBuilder("pkill", "-f", "prometheus --config.file").start().waitFor();
        for (int i = 0; i < 20; i++) {
            if (!isPrometheusRunning()) return true;
            Thread.sleep(200);
        }
        return !isPrometheusRunning();
    }

    private void createDefaultPrometheusConfig(File prometheusConfigFile) throws IOException {
        ObjectNode global = yamlMapper.createObjectNode();
        global.put("scrape_interval", "15s");
        global.put("evaluation_interval", "15s");

        ObjectNode alertmanagerStaticConfig = yamlMapper.createObjectNode();
        alertmanagerStaticConfig.set("targets", yamlMapper.createArrayNode().add("localhost:9093"));
        ObjectNode alertmanager = yamlMapper.createObjectNode();
        alertmanager.set("static_configs", yamlMapper.createArrayNode().add(alertmanagerStaticConfig));
        ObjectNode alerting = yamlMapper.createObjectNode();
        alerting.set("alertmanagers", yamlMapper.createArrayNode().add(alertmanager));

        ArrayNode ruleFiles = yamlMapper.createArrayNode();

        ObjectNode prometheusConfig = yamlMapper.createObjectNode();
        prometheusConfig.set("global", global);
        prometheusConfig.set("rule_files", ruleFiles);
        prometheusConfig.set("alerting", alerting);
        prometheusConfig.set("scrape_configs", yamlMapper.createArrayNode().add(createSelfScrapeJobConfig()));

        prometheusConfigFile.getParentFile().mkdirs();
        yamlMapper.writerWithDefaultPrettyPrinter().writeValue(prometheusConfigFile, prometheusConfig);
    }

    private static String normalizeManagedPath(String path, boolean filePath) {
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid path : " + path);
        }
        String[] segments = normalized.split("/");
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];

            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.startsWith(".")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid path : " + path);
            }
            boolean isLast = index == segments.length - 1;
            if (filePath && isLast) {
                if (!FILE_SEGMENT.matcher(segment).matches()
                        && !FILE_SEGMENT_NO_EXT.matcher(segment).matches()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid path : " + path);
                }
            } else if (!PATH_SEGMENT.matcher(segment).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid path : " + path);
            }
        }
        return normalized;
    }

    private File managedFile(String directory, String fileName) {
        return resolvePathUnderBase(new File(directory), normalizeManagedFileName(fileName));
    }

    private File managedDirectory(String directory, String directoryPath) {
        return resolvePathUnderBase(new File(directory), normalizeManagedDirectoryPath(directoryPath));
    }

    private File resolvePathUnderBase(File baseDir, String relativePath) {
        try {
            File base = baseDir.getCanonicalFile();
            File target = new File(base, relativePath).getCanonicalFile();
            String basePath = base.getPath();
            if (!target.getPath().equals(basePath) && !target.getPath().startsWith(basePath + File.separator)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid path : " + relativePath);
            }
            return target;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid path : " + relativePath, exception);
        }
    }

    private List<String> listYamlFilesRecursive(String directory) {
        List<String> fileNames = new ArrayList<>();
        collectYamlFiles(new File(directory), new File(directory), fileNames);
        java.util.Collections.sort(fileNames);
        return fileNames;
    }

    private void collectYamlFiles(File baseDir, File currentDir, List<String> results) {
        File[] entries = currentDir.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (entry.isDirectory()) {
                collectYamlFiles(baseDir, entry, results);
            } else if (entry.getName().endsWith(".yml") || entry.getName().endsWith(".yaml")) {
                String relativePath = baseDir.toPath().relativize(entry.toPath()).toString().replace('\\', '/');
                results.add(relativePath);
            }
        }
    }

    private List<String> listEmptyManagedDirectories(String directory) {
        List<String> directories = new ArrayList<>();
        collectEmptyDirectories(new File(directory), new File(directory), directories);
        java.util.Collections.sort(directories);
        return directories;
    }

    private void collectEmptyDirectories(File baseDir, File currentDir, List<String> results) {
        File[] entries = currentDir.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (!entry.isDirectory()) {
                continue;
            }
            collectEmptyDirectories(baseDir, entry, results);
            File[] children = entry.listFiles();
            if (children == null || children.length == 0) {
                String relativePath = baseDir.toPath().relativize(entry.toPath()).toString().replace('\\', '/');
                results.add(relativePath);
            }
        }
    }

    private void createManagedDirectory(String directory, String directoryPath) throws IOException {
        File managedDirectory = managedDirectory(directory, directoryPath);
        if (managedDirectory.exists() && !managedDirectory.isDirectory()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "path already exists : " + directoryPath);
        }
        if (!managedDirectory.exists() && !managedDirectory.mkdirs()) {
            throw new IOException("could not create directory : " + managedDirectory.getAbsolutePath());
        }
    }

    private void deleteManagedDirectory(String directory, String directoryPath) throws IOException {
        File managedDirectory = managedDirectory(directory, directoryPath);
        if (!managedDirectory.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "directory does not exist : " + directoryPath);
        }
        if (!managedDirectory.isDirectory()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not a directory : " + directoryPath);
        }
        File[] children = managedDirectory.listFiles();
        if (children != null && children.length > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "directory is not empty : " + directoryPath);
        }
        if (!managedDirectory.delete()) {
            throw new IOException("could not delete directory : " + managedDirectory.getAbsolutePath());
        }
    }

    private JsonNode readYamlFile(File file) throws IOException {
        if (!file.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "file does not exist : " + file.getName());
        }
        return yamlMapper.readTree(file);
    }

    private void writeYamlFile(File file, JsonNode contents) throws IOException {
        file.getParentFile().mkdirs();
        yamlMapper.writerWithDefaultPrettyPrinter().writeValue(file, contents);
    }

    private void deleteYamlFile(File file) throws IOException {
        if (!file.exists() || !file.delete()) {
            throw new IOException("could not delete file : " + file.getAbsolutePath());
        }
    }
}
