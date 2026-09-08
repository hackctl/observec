// loads the blackbox exporter configuration frrom path and creates a objectNode tree the path is /observec/observecData/blackbox/blackboxConfig.yml

package com.hackctl.observec.engine.component;

import java.io.File;
import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

@Component
public class BlackBoxConfigLoder {

    private final YAMLMapper yamlMapper = new YAMLMapper();
    private final String blackboxConfigPath;
    private final String blackboxLogPath;
    private final String blackboxTlsDataDir;
    private ObjectNode blackboxConfig;

    @Autowired(required = false)
    @Lazy
    private GitService gitService;

    public BlackBoxConfigLoder(
            @Value("${observec.blackbox.config-path:/observec/observecData/blackbox/blackboxConfig.yml}") String blackboxConfigPath,
            @Value("${observec.blackbox.log-path:/observec/observecData/blackbox/blackbox.log}") String blackboxLogPath,
            @Value("${observec.blackbox.tls-data-dir:/observec/observecData/blackbox/tls}") String blackboxTlsDataDir) {
        this.blackboxConfigPath = blackboxConfigPath;
        this.blackboxLogPath = blackboxLogPath;
        this.blackboxTlsDataDir = blackboxTlsDataDir;
    }

    @PostConstruct
    public void loadBlackboxConfig() {
        File blackboxConfigFile = new File(blackboxConfigPath);

        try {
            if (!blackboxConfigFile.exists()) {
                createDefaultBlackboxConfig(blackboxConfigFile);
            }

            JsonNode loadedBlackboxConfig = yamlMapper.readTree(blackboxConfigFile);
            if (!(loadedBlackboxConfig instanceof ObjectNode)) {
                throw new IllegalStateException("invalid blackbox config structure at " + blackboxConfigPath);
            }

            this.blackboxConfig = (ObjectNode) loadedBlackboxConfig;
            if (!(blackboxConfig.get("modules") instanceof ObjectNode)) {
                blackboxConfig.set("modules", yamlMapper.createObjectNode());
            }
            if (migrateLegacyTlsPaths()) {
                saveBlackboxConfig();
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to load blackbox config from " + blackboxConfigPath, exception);
        }
    }

    public ObjectNode getBlackboxConfig() {
        return blackboxConfig;
    }

    public ObjectNode getBlackboxModules() {
        return (ObjectNode) blackboxConfig.get("modules");
    }

    public String getBlackboxConfigAsYaml() throws IOException {
        return yamlMapper.writeValueAsString(blackboxConfig);
    }

    // stores tls material under blackbox/tls/<module>/tls/<material> and returns the absolute path
    public String saveTlsMaterial(String moduleName, String materialName, byte[] contents) throws IOException {
        File materialDir = new File(blackboxTlsDataDir,
                moduleName.replaceAll("[^A-Za-z0-9_-]", "_") + File.separator + "tls");
        if (!materialDir.exists() && !materialDir.mkdirs()) {
            throw new IOException("could not create tls directory : " + materialDir.getAbsolutePath());
        }
        File target = new File(materialDir, materialName);
        java.nio.file.Files.write(target.toPath(), contents);
        if (gitService != null) gitService.autoCommit("chore(blackbox): update tls material for " + moduleName + " " + materialName);
        return target.getAbsolutePath();
    }

    public void deleteTlsMaterial(String moduleName) {
        File moduleDir = new File(blackboxTlsDataDir,
                moduleName.replaceAll("[^A-Za-z0-9_-]", "_"));
        File[] files = moduleDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                for (File inner : file.listFiles() == null ? new File[0] : file.listFiles()) {
                    inner.delete();
                }
            }
            file.delete();
        }
        moduleDir.delete();
    }

    public java.util.List<String> getBlackboxLogTail(int maxLines) throws IOException {
        File blackboxLogFile = new File(blackboxLogPath);
        if (!blackboxLogFile.exists()) {
            return java.util.Collections.emptyList();
        }

        java.util.Deque<String> tail = new java.util.ArrayDeque<>(maxLines);
        try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(blackboxLogFile.toPath())) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.size() == maxLines) {
                    tail.pollFirst();
                }
                tail.addLast(line);
            }
        }
        return new java.util.ArrayList<>(tail);
    }

    public void saveBlackboxConfig() throws IOException {
        yamlMapper.writerWithDefaultPrettyPrinter().writeValue(new File(blackboxConfigPath), blackboxConfig);
        if (gitService != null) gitService.autoCommit("chore(blackbox): update blackboxConfig.yml");
    }

    public boolean reloadBlackboxExporter() throws IOException, InterruptedException {
        // -f matches the full command line since process names are truncated to 15 chars
        Process reloadProcess = new ProcessBuilder("pkill", "-HUP", "-f", "blackbox_exporter").start();
        return reloadProcess.waitFor() == 0;
    }

    public boolean isBlackboxExporterRunning() throws IOException, InterruptedException {
        Process checkProcess = new ProcessBuilder("pgrep", "-f", "blackbox_exporter").start();
        return checkProcess.waitFor() == 0;
    }

    public void startBlackboxExporter() throws IOException, InterruptedException {
        if (isBlackboxExporterRunning()) {
            return;
        }
        File logFile = new File(blackboxLogPath);
        File parent = logFile.getParentFile();
        if (parent != null) parent.mkdirs();
        new File(blackboxConfigPath).getParentFile().mkdirs();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "blackbox_exporter",
                "--config.file=" + blackboxConfigPath);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(logFile));
        processBuilder.start();
    }

    public boolean stopBlackboxExporter() throws IOException, InterruptedException {
        if (!isBlackboxExporterRunning()) {
            return true;
        }
        new ProcessBuilder("pkill", "-f", "blackbox_exporter").start().waitFor();
        for (int i = 0; i < 20; i++) {
            if (!isBlackboxExporterRunning()) return true;
            Thread.sleep(200);
        }
        return !isBlackboxExporterRunning();
    }

    // one-time move for installs created before tls moved from
    // blackbox/blackboxData/tls to blackbox/tls. Moves files on disk and rewrites
    // absolute tls paths already stored in the loaded config. Returns true when the
    // in-memory config was changed and should be saved.
    private boolean migrateLegacyTlsPaths() {
        try {
            java.nio.file.Path newDir = java.nio.file.Paths.get(blackboxTlsDataDir);
            java.nio.file.Path blackboxDir = newDir.getParent();
            if (blackboxDir == null || !blackboxDir.getFileName().toString().equals("blackbox")) {
                return false;
            }
            java.nio.file.Path legacyDir = blackboxDir.resolve("blackboxData/tls");
            boolean moved = false;
            if (java.nio.file.Files.isDirectory(legacyDir)) {
                try (var stream = java.nio.file.Files.walk(legacyDir)) {
                    for (java.nio.file.Path src : (Iterable<java.nio.file.Path>) stream::iterator) {
                        if (java.nio.file.Files.isDirectory(src)) continue;
                        java.nio.file.Path dst = newDir.resolve(legacyDir.relativize(src));
                        if (java.nio.file.Files.exists(dst)) continue;
                        java.nio.file.Files.createDirectories(dst.getParent());
                        java.nio.file.Files.move(src, dst);
                        moved = true;
                    }
                } catch (Exception e) {
                    System.err.println("[blackbox] legacy tls move failed: " + e.getMessage());
                }
            }
            String legacySeg = "blackbox/blackboxData/tls";
            String newSeg = "blackbox/tls";
            boolean rewritten = rewriteTlsPathSegment(blackboxConfig, legacySeg, newSeg);
            if (moved && gitService != null && rewritten) {
                gitService.autoCommit("chore(blackbox): move tls to blackbox/tls");
            }
            return rewritten;
        } catch (Exception e) {
            System.err.println("[blackbox] legacy tls migrate failed: " + e.getMessage());
            return false;
        }
    }

    private boolean rewriteTlsPathSegment(JsonNode node, String oldSeg, String newSeg) {
        boolean changed = false;
        if (node instanceof ObjectNode object) {
            var fields = object.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode child = entry.getValue();
                if (child.isTextual() && child.asText().contains(oldSeg)) {
                    object.put(entry.getKey(), child.asText().replace(oldSeg, newSeg));
                    changed = true;
                } else if (child.isObject() || child.isArray()) {
                    if (rewriteTlsPathSegment(child, oldSeg, newSeg)) changed = true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (rewriteTlsPathSegment(child, oldSeg, newSeg)) changed = true;
            }
        }
        return changed;
    }

    // creates the default blackbox config matching the init.sh defaults
    private void createDefaultBlackboxConfig(File blackboxConfigFile) throws IOException {
        ObjectNode http = yamlMapper.createObjectNode();
        http.put("method", "GET");
        http.set("valid_status_codes", yamlMapper.createArrayNode().add(200));
        http.set("valid_http_versions", yamlMapper.createArrayNode()
                .add("HTTP/1.0")
                .add("HTTP/1.1")
                .add("HTTP/2.0"));
        http.put("preferred_ip_protocol", "ip4");
        http.put("ip_protocol_fallback", true);

        ObjectNode observecBlackboxModuleDefault = yamlMapper.createObjectNode();
        observecBlackboxModuleDefault.put("prober", "http");
        observecBlackboxModuleDefault.put("timeout", "5s");
        observecBlackboxModuleDefault.set("http", http);

        ObjectNode modules = yamlMapper.createObjectNode();
        modules.set("observecBlackboxModuleDefault", observecBlackboxModuleDefault);

        ObjectNode defaultBlackboxConfig = yamlMapper.createObjectNode();
        defaultBlackboxConfig.set("modules", modules);

        blackboxConfigFile.getParentFile().mkdirs();
        yamlMapper.writerWithDefaultPrettyPrinter().writeValue(blackboxConfigFile, defaultBlackboxConfig);
    }
}
