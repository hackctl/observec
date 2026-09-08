package com.hackctl.observec.engine.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hackctl.observec.engine.component.GitService;
import com.hackctl.observec.engine.component.PrometheusConfigLoader;

@Service
public class PrometheusService {

    private static final String PROMETHEUS_HEALTH_URL = "http://localhost:9090/-/healthy";

    private final PrometheusConfigLoader prometheusConfigLoader;
    private final ObjectMapper objectMapper;
    private final GitService gitService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public PrometheusService(PrometheusConfigLoader prometheusConfigLoader, ObjectMapper objectMapper,
            GitService gitService) {
        this.prometheusConfigLoader = prometheusConfigLoader;
        this.objectMapper = objectMapper;
        this.gitService = gitService;
    }

    public String getPrometheusJobs() {
        try {
            if (prometheusConfigLoader.ensureSelfScrapeJob()) {
                prometheusConfigLoader.savePrometheusConfig();
            }
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(prometheusConfigLoader.getScrapeConfigs());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to serialize prometheus jobs", exception);
        }
    }

    public String postPrometheusJobs(String jobs) {
        ObjectNode incomingPrometheusJobs = parsePrometheusJobs(jobs);
        validateKubernetesSdConfigs(incomingPrometheusJobs);

        ArrayNode scrapeConfigs = prometheusConfigLoader.getScrapeConfigs();
        List<String> duplicatePrometheusJobs = findExistingPrometheusJobs(scrapeConfigs, incomingPrometheusJobs);
        if (!duplicatePrometheusJobs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "prometheus jobs already exist : " + duplicatePrometheusJobs);
        }

        gitService.beginBatch();
        try {
            incomingPrometheusJobs.fieldNames().forEachRemaining(name ->
                    scrapeConfigs.add(withJobName(incomingPrometheusJobs.get(name), name)));
            boolean reloaded = persistPrometheusConfig();
            gitService.endBatch("chore(prometheus): jobs added : " + namesOf(incomingPrometheusJobs));
            return prometheusResponse("prometheus jobs added : " + namesOf(incomingPrometheusJobs) + reloadSuffix(reloaded));
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String putPrometheusJobs(String jobs) {
        ObjectNode incomingPrometheusJobs = parsePrometheusJobs(jobs);
        validateKubernetesSdConfigs(incomingPrometheusJobs);

        ArrayNode scrapeConfigs = prometheusConfigLoader.getScrapeConfigs();
        List<String> missingPrometheusJobs = findMissingPrometheusJobs(scrapeConfigs, incomingPrometheusJobs);
        if (!missingPrometheusJobs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "prometheus jobs do not exist : " + missingPrometheusJobs);
        }

        gitService.beginBatch();
        try {
            incomingPrometheusJobs.fieldNames().forEachRemaining(name ->
                    scrapeConfigs.set(indexOfPrometheusJob(scrapeConfigs, name),
                            withJobName(incomingPrometheusJobs.get(name), name)));
            boolean reloaded = persistPrometheusConfig();
            gitService.endBatch("chore(prometheus): jobs updated : " + namesOf(incomingPrometheusJobs));
            return prometheusResponse("prometheus jobs updated : " + namesOf(incomingPrometheusJobs) + reloadSuffix(reloaded));
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String deletePrometheusJobs(String jobs) {
        List<String> prometheusJobNames = parseNames(jobs, "expected body [ \"<jobName>\", ... ]");

        ArrayNode scrapeConfigs = prometheusConfigLoader.getScrapeConfigs();
        List<String> missingPrometheusJobs = findMissingPrometheusJobsByName(scrapeConfigs, prometheusJobNames);
        if (!missingPrometheusJobs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "prometheus jobs do not exist : " + missingPrometheusJobs);
        }

        int remainingJobCount = 0;
        for (JsonNode scrapeConfig : scrapeConfigs) {
            if (!prometheusJobNames.contains(scrapeConfig.path("job_name").asText())) {
                remainingJobCount++;
            }
        }
        if (remainingJobCount == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "at least one scrape job must remain");
        }

        gitService.beginBatch();
        try {
            for (int index = scrapeConfigs.size() - 1; index >= 0; index--) {
                if (prometheusJobNames.contains(scrapeConfigs.get(index).path("job_name").asText())) {
                    scrapeConfigs.remove(index);
                }
            }
            boolean reloaded = persistPrometheusConfig();
            gitService.endBatch("chore(prometheus): jobs deleted : " + prometheusJobNames);
            return prometheusResponse("prometheus jobs deleted : " + prometheusJobNames + reloadSuffix(reloaded));
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String getPrometheusRuleFiles() {
        ObjectNode response = objectMapper.createObjectNode();
        for (String ruleFileName : prometheusConfigLoader.listRuleFiles()) {
            response.set(ruleFileName, readRuleFile(ruleFileName));
        }
        return writePrettyJson(response);
    }

    public String postPrometheusRuleFiles(String ruleFiles) {
        ObjectNode incomingPrometheusRuleFiles = parsePrometheusRuleFiles(ruleFiles);

        List<String> duplicatePrometheusRuleFiles = findExistingPrometheusRuleFiles(incomingPrometheusRuleFiles);
        if (!duplicatePrometheusRuleFiles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "prometheus rule files already exist : " + duplicatePrometheusRuleFiles);
        }

        gitService.beginBatch();
        try {
            savePrometheusRuleFiles(incomingPrometheusRuleFiles);
            boolean reloaded = persistPrometheusConfig();
            gitService.endBatch("chore(prometheus): rule files added : " + namesOf(incomingPrometheusRuleFiles));
            return prometheusResponse("prometheus rule files added : "
                    + namesOf(incomingPrometheusRuleFiles) + reloadSuffix(reloaded));
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String putPrometheusRuleFiles(String ruleFiles) {
        ObjectNode incomingPrometheusRuleFiles = parsePrometheusRuleFiles(ruleFiles);

        List<String> missingPrometheusRuleFiles = findMissingPrometheusRuleFiles(incomingPrometheusRuleFiles);
        if (!missingPrometheusRuleFiles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "prometheus rule files do not exist : " + missingPrometheusRuleFiles);
        }

        gitService.beginBatch();
        try {
            savePrometheusRuleFiles(incomingPrometheusRuleFiles);
            boolean reloaded = persistPrometheusConfig();
            gitService.endBatch("chore(prometheus): rule files updated : " + namesOf(incomingPrometheusRuleFiles));
            return prometheusResponse("prometheus rule files updated : "
                    + namesOf(incomingPrometheusRuleFiles) + reloadSuffix(reloaded));
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String deletePrometheusRuleFiles(String ruleFiles) {
        List<String> prometheusRuleFileNames = parseNames(ruleFiles, "expected body [ \"<ruleFileName>\", ... ]");

        List<String> missingPrometheusRuleFiles = findMissingPrometheusRuleFilesByName(prometheusRuleFileNames);
        if (!missingPrometheusRuleFiles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "prometheus rule files do not exist : " + missingPrometheusRuleFiles);
        }

        gitService.beginBatch();
        try {
            deleteManagedFiles(prometheusRuleFileNames, true);
            boolean reloaded = persistPrometheusConfig();
            gitService.endBatch("chore(prometheus): rule files deleted : " + prometheusRuleFileNames);
            return prometheusResponse("prometheus rule files deleted : "
                    + prometheusRuleFileNames + reloadSuffix(reloaded));
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String getPrometheusServiceDiscoveryFiles() {
        ObjectNode response = objectMapper.createObjectNode();
        for (String serviceDiscoveryFileName : prometheusConfigLoader.listServiceDiscoveryFiles()) {
            response.set(serviceDiscoveryFileName, readServiceDiscoveryFile(serviceDiscoveryFileName));
        }
        return writePrettyJson(response);
    }

    public String postPrometheusServiceDiscoveryFiles(String serviceDiscoveryFiles) {
        ObjectNode incomingPrometheusServiceDiscoveryFiles =
                parsePrometheusServiceDiscoveryFiles(serviceDiscoveryFiles);

        List<String> duplicatePrometheusServiceDiscoveryFiles =
                findExistingPrometheusServiceDiscoveryFiles(incomingPrometheusServiceDiscoveryFiles);
        if (!duplicatePrometheusServiceDiscoveryFiles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "prometheus service discovery files already exist : " + duplicatePrometheusServiceDiscoveryFiles);
        }

        gitService.beginBatch();
        try {
            savePrometheusServiceDiscoveryFiles(incomingPrometheusServiceDiscoveryFiles);
            boolean reloaded = persistPrometheusConfig();
            gitService.endBatch("chore(prometheus): service discovery files added : "
                    + namesOf(incomingPrometheusServiceDiscoveryFiles));
            return prometheusResponse("prometheus service discovery files added : "
                    + namesOf(incomingPrometheusServiceDiscoveryFiles) + reloadSuffix(reloaded));
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String putPrometheusServiceDiscoveryFiles(String serviceDiscoveryFiles) {
        ObjectNode incomingPrometheusServiceDiscoveryFiles =
                parsePrometheusServiceDiscoveryFiles(serviceDiscoveryFiles);

        List<String> missingPrometheusServiceDiscoveryFiles =
                findMissingPrometheusServiceDiscoveryFiles(incomingPrometheusServiceDiscoveryFiles);
        if (!missingPrometheusServiceDiscoveryFiles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "prometheus service discovery files do not exist : " + missingPrometheusServiceDiscoveryFiles);
        }

        gitService.beginBatch();
        try {
            savePrometheusServiceDiscoveryFiles(incomingPrometheusServiceDiscoveryFiles);
            boolean reloaded = persistPrometheusConfig();
            gitService.endBatch("chore(prometheus): service discovery files updated : "
                    + namesOf(incomingPrometheusServiceDiscoveryFiles));
            return prometheusResponse("prometheus service discovery files updated : "
                    + namesOf(incomingPrometheusServiceDiscoveryFiles) + reloadSuffix(reloaded));
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String deletePrometheusServiceDiscoveryFiles(String serviceDiscoveryFiles) {
        List<String> prometheusServiceDiscoveryFileNames =
                parseNames(serviceDiscoveryFiles, "expected body [ \"<serviceDiscoveryFileName>\", ... ]");

        List<String> missingPrometheusServiceDiscoveryFiles =
                findMissingPrometheusServiceDiscoveryFilesByName(prometheusServiceDiscoveryFileNames);
        if (!missingPrometheusServiceDiscoveryFiles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "prometheus service discovery files do not exist : " + missingPrometheusServiceDiscoveryFiles);
        }

        gitService.beginBatch();
        try {
            deleteManagedFiles(prometheusServiceDiscoveryFileNames, false);
            boolean reloaded = persistPrometheusConfig();
            gitService.endBatch("chore(prometheus): service discovery files deleted : "
                    + prometheusServiceDiscoveryFileNames);
            return prometheusResponse("prometheus service discovery files deleted : "
                    + prometheusServiceDiscoveryFileNames + reloadSuffix(reloaded));
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String getPrometheusRuleDirectories() {
        return writePrettyJson(objectMapper.valueToTree(prometheusConfigLoader.listRuleDirectories()));
    }

    public String postPrometheusRuleDirectory(String directoryPath) {
        String normalizedDirectoryPath = parseDirectoryPath(directoryPath);
        gitService.beginBatch();
        try {
            prometheusConfigLoader.createRuleDirectory(normalizedDirectoryPath);
        } catch (IOException exception) {
            gitService.abortBatch();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to create prometheus rule directory : " + normalizedDirectoryPath, exception);
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
        gitService.endBatch("chore(prometheus): rule directory created : " + normalizedDirectoryPath);
        return prometheusResponse("prometheus rule directory created : " + normalizedDirectoryPath);
    }

    public String deletePrometheusRuleDirectory(String directoryPath) {
        String normalizedDirectoryPath = parseDirectoryPath(directoryPath);
        gitService.beginBatch();
        try {
            prometheusConfigLoader.deleteRuleDirectory(normalizedDirectoryPath);
        } catch (IOException exception) {
            gitService.abortBatch();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to delete prometheus rule directory : " + normalizedDirectoryPath, exception);
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
        gitService.endBatch("chore(prometheus): rule directory deleted : " + normalizedDirectoryPath);
        return prometheusResponse("prometheus rule directory deleted : " + normalizedDirectoryPath);
    }

    public String getPrometheusServiceDiscoveryDirectories() {
        return writePrettyJson(objectMapper.valueToTree(prometheusConfigLoader.listServiceDiscoveryDirectories()));
    }

    public String postPrometheusServiceDiscoveryDirectory(String directoryPath) {
        String normalizedDirectoryPath = parseDirectoryPath(directoryPath);
        gitService.beginBatch();
        try {
            prometheusConfigLoader.createServiceDiscoveryDirectory(normalizedDirectoryPath);
        } catch (IOException exception) {
            gitService.abortBatch();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to create prometheus service discovery directory : " + normalizedDirectoryPath,
                    exception);
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
        gitService.endBatch("chore(prometheus): service discovery directory created : " + normalizedDirectoryPath);
        return prometheusResponse("prometheus service discovery directory created : " + normalizedDirectoryPath);
    }

    public String deletePrometheusServiceDiscoveryDirectory(String directoryPath) {
        String normalizedDirectoryPath = parseDirectoryPath(directoryPath);
        gitService.beginBatch();
        try {
            prometheusConfigLoader.deleteServiceDiscoveryDirectory(normalizedDirectoryPath);
        } catch (IOException exception) {
            gitService.abortBatch();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to delete prometheus service discovery directory : " + normalizedDirectoryPath,
                    exception);
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
        gitService.endBatch("chore(prometheus): service discovery directory deleted : " + normalizedDirectoryPath);
        return prometheusResponse("prometheus service discovery directory deleted : " + normalizedDirectoryPath);
    }

    public String getKubeConfigs() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode names = response.putArray("kubeconfigs");
        prometheusConfigLoader.listKubeConfigs().forEach(names::add);
        return writePrettyJson(response);
    }

    public String uploadKubeConfig(String kubeConfigName, byte[] contents) {
        if (contents == null || contents.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kubeconfig file is empty");
        }
        if (contents.length > 2 * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kubeconfig file too large (max 2MB)");
        }
        String normalized = PrometheusConfigLoader.normalizeKubeConfigName(kubeConfigName);
        gitService.beginBatch();
        try {
            String absolutePath = prometheusConfigLoader.saveKubeConfig(normalized, contents);
            gitService.endBatch("chore(prometheus): kubeconfig uploaded : " + normalized);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("status", "success");
            response.put("message", "kubeconfig uploaded : " + normalized);
            response.put("name", normalized);
            response.put("path", absolutePath);
            return response.toString();
        } catch (IOException exception) {
            gitService.abortBatch();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to store kubeconfig : " + normalized, exception);
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String deleteKubeConfigs(String kubeConfigNames) {
        List<String> names = parseNames(kubeConfigNames, "expected body [ \"<kubeConfigName>\", ... ]");
        List<String> normalized = new ArrayList<>();
        for (String name : names) {
            normalized.add(PrometheusConfigLoader.normalizeKubeConfigName(name));
        }
        List<String> missing = new ArrayList<>();
        for (String name : normalized) {
            if (!prometheusConfigLoader.hasKubeConfig(name)) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "kubeconfigs do not exist : " + missing);
        }
        gitService.beginBatch();
        try {
            for (String name : normalized) {
                try {
                    prometheusConfigLoader.deleteKubeConfig(name);
                } catch (IOException exception) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "failed to delete kubeconfig : " + name, exception);
                }
            }
            gitService.endBatch("chore(prometheus): kubeconfigs deleted : " + normalized);
            return prometheusResponse("prometheus kubeconfigs deleted : " + normalized);
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    private void validateKubernetesSdConfigs(ObjectNode incomingPrometheusJobs) {
        incomingPrometheusJobs.fieldNames().forEachRemaining(name -> {
            JsonNode jobConfig = incomingPrometheusJobs.get(name);
            JsonNode kubeSd = jobConfig.get("kubernetes_sd_configs");
            if (kubeSd == null || kubeSd.isNull()) {
                return;
            }
            if (!kubeSd.isArray() || kubeSd.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "invalid kubernetes_sd_configs for job : " + name);
            }
            for (JsonNode entry : kubeSd) {
                if (!entry.isObject()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "invalid kubernetes_sd_configs entry for job : " + name);
                }
                String role = entry.path("role").asText("").trim();
                if (role.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "kubernetes_sd_configs role is required for job : " + name);
                }
                String kubeconfigFile = entry.path("kubeconfig_file").asText("").trim();
                if (!kubeconfigFile.isEmpty() && !kubeconfigFileExists(kubeconfigFile)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "kubeconfig_file does not exist in kubeConfigs dir for job : " + name
                                    + " : " + kubeconfigFile);
                }
            }
        });
    }

    private boolean kubeconfigFileExists(String kubeconfigFile) {
        try {
            java.io.File base = new java.io.File(prometheusConfigLoader.getKubeConfigsDir()).getCanonicalFile();
            java.io.File target;
            java.io.File asGiven = new java.io.File(kubeconfigFile);
            if (asGiven.isAbsolute()) {
                target = asGiven.getCanonicalFile();
            } else {
                String normalized = PrometheusConfigLoader.normalizeKubeConfigName(kubeconfigFile);
                target = new java.io.File(base, normalized).getCanonicalFile();
            }
            String basePath = base.getPath();
            if (!target.getPath().equals(basePath) && !target.getPath().startsWith(basePath + java.io.File.separator)) {
                return false;
            }
            return target.isFile();
        } catch (Exception exception) {
            return false;
        }
    }

    public String getPrometheusConfig() {
        try {
            return prometheusConfigLoader.getPrometheusConfigAsYaml();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to serialize prometheus config", exception);
        }
    }

    public String getPrometheusLogs() {
        try {
            List<String> logs = prometheusConfigLoader.getPrometheusLogTail(2000);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode lines = response.putArray("lines");
            logs.forEach(lines::add);
            return response.toString();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to read prometheus logs", exception);
        }
    }

    public String reloadPrometheus() {
        try {
            return prometheusResponse(applyPrometheusRuntime(false));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to reload prometheus", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to reload prometheus", exception);
        }
    }

    public String enablePrometheus() {
        try {
            if (isPrometheusHealthy()) {
                return prometheusResponse("prometheus already running", "healthy");
            }
            try {
                if (prometheusConfigLoader.isPrometheusRunning()) {
                    if (waitForPrometheusHealthy(20, 500)) {
                        return prometheusResponse("prometheus started", "healthy");
                    }
                }
            } catch (IOException | InterruptedException ignored) {}

            prometheusConfigLoader.syncRuleFilesConfig();
            prometheusConfigLoader.savePrometheusConfig();
            prometheusConfigLoader.startPrometheus();
            if (!waitForPrometheusHealthy(20, 500)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "prometheus failed to start");
            }
            return prometheusResponse("prometheus started", "healthy");
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to start prometheus", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to start prometheus", exception);
        }
    }

    public String disablePrometheus() {
        try {
            if (!prometheusConfigLoader.isPrometheusRunning()) {
                return prometheusResponse("prometheus already stopped", "stopped");
            }
            if (!prometheusConfigLoader.stopPrometheus()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "prometheus failed to stop");
            }
            return prometheusResponse("prometheus stopped", "stopped");
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to stop prometheus", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to stop prometheus", exception);
        }
    }

    public String getPrometheusStatus() {
        if (isPrometheusHealthy()) {
            return prometheusResponse("prometheus is healthy", "healthy");
        }
        try {
            if (prometheusConfigLoader.isPrometheusRunning()) {
                return prometheusResponse("prometheus is starting", "starting");
            }
        } catch (IOException exception) {
            return prometheusResponse("prometheus is unreachable", "stopped");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return prometheusResponse("prometheus is unreachable", "stopped");
        }
        return prometheusResponse("prometheus is not running", "stopped");
    }

    private boolean isPrometheusHealthy() {
        try {
            HttpResponse<String> healthResponse = httpClient.send(
                    HttpRequest.newBuilder(URI.create(PROMETHEUS_HEALTH_URL))
                            .timeout(Duration.ofSeconds(3))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return healthResponse.statusCode() == 200;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean waitForPrometheusHealthy(int attempts, long delayMillis)
            throws InterruptedException {
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (isPrometheusHealthy()) {
                return true;
            }
            Thread.sleep(delayMillis);
        }
        return false;
    }

    private String applyPrometheusRuntime(boolean saveConfig) throws IOException, InterruptedException {
        if (saveConfig) {
            prometheusConfigLoader.ensureSelfScrapeJob();
        }
        prometheusConfigLoader.syncRuleFilesConfig();
        prometheusConfigLoader.savePrometheusConfig();

        if (isPrometheusHealthy()) {
            if (prometheusConfigLoader.reloadPrometheus()) {
                return "prometheus reloaded";
            }
            return "prometheus config saved but reload failed";
        }

        prometheusConfigLoader.startPrometheus();
        if (!waitForPrometheusHealthy(20, 500)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "prometheus failed to start");
        }
        return "prometheus started";
    }

    private JsonNode readRuleFile(String ruleFileName) {
        try {
            return prometheusConfigLoader.getRuleFile(ruleFileName);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to read prometheus rule file : " + ruleFileName, exception);
        }
    }

    private JsonNode readServiceDiscoveryFile(String serviceDiscoveryFileName) {
        try {
            return prometheusConfigLoader.getServiceDiscoveryFile(serviceDiscoveryFileName);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to read prometheus service discovery file : " + serviceDiscoveryFileName, exception);
        }
    }

    private void savePrometheusRuleFiles(ObjectNode incomingPrometheusRuleFiles) {
        incomingPrometheusRuleFiles.fieldNames().forEachRemaining(name -> {
            try {
                prometheusConfigLoader.saveRuleFile(name, incomingPrometheusRuleFiles.get(name));
            } catch (IOException exception) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "failed to store prometheus rule file : " + name, exception);
            }
        });
    }

    private void savePrometheusServiceDiscoveryFiles(ObjectNode incomingPrometheusServiceDiscoveryFiles) {
        incomingPrometheusServiceDiscoveryFiles.fieldNames().forEachRemaining(name -> {
            try {
                prometheusConfigLoader.saveServiceDiscoveryFile(
                        name, incomingPrometheusServiceDiscoveryFiles.get(name));
            } catch (IOException exception) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "failed to store prometheus service discovery file : " + name, exception);
            }
        });
    }

    private void deleteManagedFiles(List<String> managedFileNames, boolean ruleFiles) {
        for (String managedFileName : managedFileNames) {
            try {
                if (ruleFiles) {
                    prometheusConfigLoader.deleteRuleFile(managedFileName);
                } else {
                    prometheusConfigLoader.deleteServiceDiscoveryFile(managedFileName);
                }
            } catch (IOException exception) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "failed to delete prometheus file : " + managedFileName, exception);
            }
        }
    }

    private ObjectNode parsePrometheusJobs(String jobs) {
        JsonNode parsedPrometheusJobs = parseJsonBody(jobs);

        if (!(parsedPrometheusJobs instanceof ObjectNode incomingPrometheusJobs) || incomingPrometheusJobs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "expected body { \"<jobName>\": { <scrapeConfig> } }");
        }

        incomingPrometheusJobs.fieldNames().forEachRemaining(name -> {
            if (!(incomingPrometheusJobs.get(name).isObject())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "invalid prometheus job config for : " + name);
            }
        });

        return incomingPrometheusJobs;
    }

    private ObjectNode parsePrometheusRuleFiles(String ruleFiles) {
        JsonNode parsedPrometheusRuleFiles = parseJsonBody(ruleFiles);

        if (!(parsedPrometheusRuleFiles instanceof ObjectNode incomingPrometheusRuleFiles)
                || incomingPrometheusRuleFiles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "expected body { \"<ruleFileName>\": { \"groups\": [ ... ] } }");
        }

        incomingPrometheusRuleFiles.fieldNames().forEachRemaining(name -> {
            JsonNode ruleFileContents = incomingPrometheusRuleFiles.get(name);
            if (!(ruleFileContents.isObject() && ruleFileContents.has("groups")
                    && ruleFileContents.get("groups").isArray())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "invalid prometheus rule file content for : " + name);
            }
        });

        return incomingPrometheusRuleFiles;
    }

    private ObjectNode parsePrometheusServiceDiscoveryFiles(String serviceDiscoveryFiles) {
        JsonNode parsedPrometheusServiceDiscoveryFiles = parseJsonBody(serviceDiscoveryFiles);

        if (!(parsedPrometheusServiceDiscoveryFiles instanceof ObjectNode incomingPrometheusServiceDiscoveryFiles)
                || incomingPrometheusServiceDiscoveryFiles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "expected body { \"<serviceDiscoveryFileName>\": [ { \"targets\": [...], \"labels\": {...} } ] }");
        }

        incomingPrometheusServiceDiscoveryFiles.fieldNames().forEachRemaining(name -> {
            JsonNode serviceDiscoveryFileContents = incomingPrometheusServiceDiscoveryFiles.get(name);
            if (!serviceDiscoveryFileContents.isArray() || serviceDiscoveryFileContents.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "invalid prometheus service discovery file content for : " + name);
            }
            for (JsonNode targetGroup : serviceDiscoveryFileContents) {
                if (!(targetGroup.isObject() && targetGroup.has("targets")
                        && targetGroup.get("targets").isArray())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "invalid prometheus service discovery file content for : " + name);
                }
            }
        });

        return incomingPrometheusServiceDiscoveryFiles;
    }

    private List<String> parseNames(String names, String expectedBodyMessage) {
        JsonNode parsedNames = parseJsonBody(names);

        if (!parsedNames.isArray() || parsedNames.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, expectedBodyMessage);
        }

        List<String> parsedNameList = new ArrayList<>();
        for (JsonNode parsedName : parsedNames) {
            if (!parsedName.isTextual() || parsedName.asText().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, expectedBodyMessage);
            }
            parsedNameList.add(parsedName.asText());
        }
        return parsedNameList;
    }

    private JsonNode parseJsonBody(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid json body", exception);
        }
    }

    private ObjectNode withJobName(JsonNode jobConfig, String jobName) {
        ObjectNode prometheusJob = ((ObjectNode) jobConfig).deepCopy();
        prometheusJob.put("job_name", jobName);
        return prometheusJob;
    }

    private int indexOfPrometheusJob(ArrayNode scrapeConfigs, String jobName) {
        for (int index = 0; index < scrapeConfigs.size(); index++) {
            if (jobName.equals(scrapeConfigs.get(index).path("job_name").asText())) {
                return index;
            }
        }
        return -1;
    }

    private List<String> findExistingPrometheusJobs(ArrayNode scrapeConfigs, ObjectNode incomingPrometheusJobs) {
        List<String> existingPrometheusJobNames = new ArrayList<>();
        incomingPrometheusJobs.fieldNames().forEachRemaining(name -> {
            if (indexOfPrometheusJob(scrapeConfigs, name) >= 0) {
                existingPrometheusJobNames.add(name);
            }
        });
        return existingPrometheusJobNames;
    }

    private List<String> findMissingPrometheusJobs(ArrayNode scrapeConfigs, ObjectNode incomingPrometheusJobs) {
        List<String> missingPrometheusJobNames = new ArrayList<>();
        incomingPrometheusJobs.fieldNames().forEachRemaining(name -> {
            if (indexOfPrometheusJob(scrapeConfigs, name) < 0) {
                missingPrometheusJobNames.add(name);
            }
        });
        return missingPrometheusJobNames;
    }

    private List<String> findMissingPrometheusJobsByName(ArrayNode scrapeConfigs, List<String> prometheusJobNames) {
        List<String> missingPrometheusJobNames = new ArrayList<>();
        for (String name : prometheusJobNames) {
            if (indexOfPrometheusJob(scrapeConfigs, name) < 0) {
                missingPrometheusJobNames.add(name);
            }
        }
        return missingPrometheusJobNames;
    }

    private List<String> findExistingPrometheusRuleFiles(ObjectNode incomingPrometheusRuleFiles) {
        List<String> existingPrometheusRuleFileNames = new ArrayList<>();
        incomingPrometheusRuleFiles.fieldNames().forEachRemaining(name -> {
            if (prometheusConfigLoader.hasRuleFile(name)) {
                existingPrometheusRuleFileNames.add(name);
            }
        });
        return existingPrometheusRuleFileNames;
    }

    private List<String> findMissingPrometheusRuleFiles(ObjectNode incomingPrometheusRuleFiles) {
        List<String> missingPrometheusRuleFileNames = new ArrayList<>();
        incomingPrometheusRuleFiles.fieldNames().forEachRemaining(name -> {
            if (!prometheusConfigLoader.hasRuleFile(name)) {
                missingPrometheusRuleFileNames.add(name);
            }
        });
        return missingPrometheusRuleFileNames;
    }

    private List<String> findMissingPrometheusRuleFilesByName(List<String> prometheusRuleFileNames) {
        List<String> missingPrometheusRuleFileNames = new ArrayList<>();
        for (String name : prometheusRuleFileNames) {
            if (!prometheusConfigLoader.hasRuleFile(name)) {
                missingPrometheusRuleFileNames.add(name);
            }
        }
        return missingPrometheusRuleFileNames;
    }

    private List<String> findExistingPrometheusServiceDiscoveryFiles(
            ObjectNode incomingPrometheusServiceDiscoveryFiles) {
        List<String> existingPrometheusServiceDiscoveryFileNames = new ArrayList<>();
        incomingPrometheusServiceDiscoveryFiles.fieldNames().forEachRemaining(name -> {
            if (prometheusConfigLoader.hasServiceDiscoveryFile(name)) {
                existingPrometheusServiceDiscoveryFileNames.add(name);
            }
        });
        return existingPrometheusServiceDiscoveryFileNames;
    }

    private List<String> findMissingPrometheusServiceDiscoveryFiles(
            ObjectNode incomingPrometheusServiceDiscoveryFiles) {
        List<String> missingPrometheusServiceDiscoveryFileNames = new ArrayList<>();
        incomingPrometheusServiceDiscoveryFiles.fieldNames().forEachRemaining(name -> {
            if (!prometheusConfigLoader.hasServiceDiscoveryFile(name)) {
                missingPrometheusServiceDiscoveryFileNames.add(name);
            }
        });
        return missingPrometheusServiceDiscoveryFileNames;
    }

    private List<String> findMissingPrometheusServiceDiscoveryFilesByName(
            List<String> prometheusServiceDiscoveryFileNames) {
        List<String> missingPrometheusServiceDiscoveryFileNames = new ArrayList<>();
        for (String name : prometheusServiceDiscoveryFileNames) {
            if (!prometheusConfigLoader.hasServiceDiscoveryFile(name)) {
                missingPrometheusServiceDiscoveryFileNames.add(name);
            }
        }
        return missingPrometheusServiceDiscoveryFileNames;
    }

    private String parseDirectoryPath(String directoryPath) {
        JsonNode parsedDirectoryPath = parseJsonBody(directoryPath);
        if (!parsedDirectoryPath.isTextual() || parsedDirectoryPath.asText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "expected body \"<directoryPath>\"");
        }
        return PrometheusConfigLoader.normalizeManagedDirectoryPath(parsedDirectoryPath.asText());
    }

    private List<String> namesOf(ObjectNode prometheusNodes) {
        List<String> prometheusNodeNames = new ArrayList<>();
        prometheusNodes.fieldNames().forEachRemaining(prometheusNodeNames::add);
        return prometheusNodeNames;
    }

    private String writePrettyJson(JsonNode jsonNode) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to serialize response", exception);
        }
    }

    private boolean persistPrometheusConfig() {
        try {
            applyPrometheusRuntime(true);
            return true;
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.CONFLICT) {
                return false;
            }
            throw exception;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to save prometheus config", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to reload prometheus", exception);
        }
    }

    private String reloadSuffix(boolean reloaded) {
        return reloaded ? ", prometheus applied" : ", warning: prometheus could not be reloaded or started";
    }

    private String prometheusResponse(String message) {
        return prometheusResponse(message, "stopped");
    }

    private String prometheusResponse(String message, String state) {
        ObjectNode prometheusResponse = objectMapper.createObjectNode();
        prometheusResponse.put("status", "success");
        prometheusResponse.put("message", message);
        prometheusResponse.put("state", state);
        return prometheusResponse.toString();
    }
}
