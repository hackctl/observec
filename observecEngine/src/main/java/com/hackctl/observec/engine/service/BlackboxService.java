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
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hackctl.observec.engine.component.BlackBoxConfigLoder;
import com.hackctl.observec.engine.component.GitService;

@Service
public class BlackboxService {

    private static final String DEFAULT_BLACKBOX_MODULE = "observecBlackboxModuleDefault";
    private static final String BLACKBOX_EXPORTER_BASE_URL = "http://localhost:9115";
    private static final String PROMETHEUS_QUERY_URL = "http://localhost:9090/api/v1/query?query=";
    private static final java.time.format.DateTimeFormatter PROBE_TIME_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String BLACKBOX_EXPORTER_HEALTH_URL = BLACKBOX_EXPORTER_BASE_URL + "/-/healthy";
    private static final java.util.regex.Pattern RECENT_PROBE_ROW = java.util.regex.Pattern.compile(
            "<tr><td>(.*?)</td><td>(.*?)</td><td>(.*?)</td><td><a href='logs\\?id=(\\d+)'>", java.util.regex.Pattern.DOTALL);

    private final BlackBoxConfigLoder blackBoxConfigLoder;
    private final ObjectMapper objectMapper;
    private final GitService gitService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public BlackboxService(BlackBoxConfigLoder blackBoxConfigLoder, ObjectMapper objectMapper,
            GitService gitService) {
        this.blackBoxConfigLoder = blackBoxConfigLoder;
        this.objectMapper = objectMapper;
        this.gitService = gitService;
    }

    public String getBlackboxModules() {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(blackBoxConfigLoder.getBlackboxModules());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to serialize blackbox modules", exception);
        }
    }

    public String postBlackboxModules(String modules) {
        ObjectNode incomingBlackboxModules = parseBlackboxModules(modules);

        ObjectNode existingBlackboxModules = blackBoxConfigLoder.getBlackboxModules();
        List<String> duplicateBlackboxModules = findExistingBlackboxModules(existingBlackboxModules, incomingBlackboxModules);
        if (!duplicateBlackboxModules.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "blackbox modules already exist : " + duplicateBlackboxModules);
        }

        gitService.beginBatch();
        try {
            incomingBlackboxModules.fieldNames()
                    .forEachRemaining(name -> existingBlackboxModules.set(name, incomingBlackboxModules.get(name)));
            boolean reloaded = persistBlackboxConfig();
            gitService.endBatch("chore(blackbox): modules added : " + namesOf(incomingBlackboxModules));
            return blackboxResponse("blackbox modules added : " + namesOf(incomingBlackboxModules) + reloadSuffix(reloaded));
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String putBlackboxModules(String modules) {
        ObjectNode incomingBlackboxModules = parseBlackboxModules(modules);

        ObjectNode existingBlackboxModules = blackBoxConfigLoder.getBlackboxModules();
        List<String> missingBlackboxModules = findMissingBlackboxModules(existingBlackboxModules, incomingBlackboxModules);
        if (!missingBlackboxModules.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "blackbox modules do not exist : " + missingBlackboxModules);
        }

        gitService.beginBatch();
        try {
            incomingBlackboxModules.fieldNames()
                    .forEachRemaining(name -> existingBlackboxModules.set(name, incomingBlackboxModules.get(name)));
            boolean reloaded = persistBlackboxConfig();
            gitService.endBatch("chore(blackbox): modules updated : " + namesOf(incomingBlackboxModules));
            return blackboxResponse("blackbox modules updated : " + namesOf(incomingBlackboxModules) + reloadSuffix(reloaded));
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String deleteBlackboxModules(String modules) {
        List<String> blackboxModuleNames = parseBlackboxModuleNames(modules);
        if (blackboxModuleNames.contains(DEFAULT_BLACKBOX_MODULE)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "cannot delete the default blackbox module : " + DEFAULT_BLACKBOX_MODULE);
        }

        ObjectNode existingBlackboxModules = blackBoxConfigLoder.getBlackboxModules();
        List<String> missingBlackboxModules = findMissingBlackboxModulesByName(existingBlackboxModules, blackboxModuleNames);
        if (!missingBlackboxModules.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "blackbox modules do not exist : " + missingBlackboxModules);
        }

        gitService.beginBatch();
        try {
            blackboxModuleNames.forEach(name -> cleanupTlsMaterial(name));
            blackboxModuleNames.forEach(existingBlackboxModules::remove);
            boolean reloaded = persistBlackboxConfig();
            gitService.endBatch("chore(blackbox): modules deleted : " + blackboxModuleNames);
            return blackboxResponse("blackbox modules deleted : " + blackboxModuleNames + reloadSuffix(reloaded));
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String uploadTlsMaterial(String moduleName,
            org.springframework.web.multipart.MultipartFile caFile,
            org.springframework.web.multipart.MultipartFile certFile,
            org.springframework.web.multipart.MultipartFile keyFile) {

        if (moduleName == null || moduleName.isBlank()
                || !moduleName.matches("[A-Za-z0-9_-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid module name for tls upload");
        }

        gitService.beginBatch();
        try {
            ObjectNode response = objectMapper.createObjectNode();
            int stored = 0;
            stored += storeTlsPart(response, moduleName, "ca_file", "ca.crt", caFile);
            stored += storeTlsPart(response, moduleName, "cert_file", "server.crt", certFile);
            stored += storeTlsPart(response, moduleName, "key_file", "server.key", keyFile);

            if (stored == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "no tls files provided - pick at least one file");
            }
            gitService.endBatch("chore(blackbox): update tls material for " + moduleName);
            return response.toString();
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    private int storeTlsPart(ObjectNode response, String moduleName, String field,
            String materialName, org.springframework.web.multipart.MultipartFile file) {
        if (file == null) {
            return 0;
        }
        try {
            String path = blackBoxConfigLoder.saveTlsMaterial(moduleName, materialName, file.getBytes());
            response.put(field, path);
            response.put(field.replace("_file", "_name"), file.getOriginalFilename());
            return 1;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to store tls material", exception);
        }
    }

    private void cleanupTlsMaterial(String moduleName) {
        try {
            blackBoxConfigLoder.deleteTlsMaterial(moduleName);
        } catch (RuntimeException ignored) {

        }
    }

    public String getBlackboxConfig() {
        try {
            return blackBoxConfigLoder.getBlackboxConfigAsYaml();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to serialize blackbox config", exception);
        }
    }

    public String getBlackboxLogs() {
        try {
            java.util.List<String> logs = blackBoxConfigLoder.getBlackboxLogTail(2000);
            ObjectNode response = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode lines = response.putArray("lines");
            logs.forEach(lines::add);
            return response.toString();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to read blackbox logs", exception);
        }
    }

    public String reloadBlackbox() {
        try {
            if (!blackBoxConfigLoder.reloadBlackboxExporter()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "blackbox exporter is not running");
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to reload blackbox exporter", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to reload blackbox exporter", exception);
        }
        return blackboxResponse("blackbox exporter reloaded");
    }

    public String enableBlackbox() {
        try {
            if (isBlackboxHealthy()) {
                return blackboxResponse("blackbox exporter already running", "healthy");
            }
            if (blackBoxConfigLoder.isBlackboxExporterRunning()) {
                if (waitForBlackboxHealthy(20, 500)) {
                    return blackboxResponse("blackbox exporter started", "healthy");
                }
            }
            blackBoxConfigLoder.startBlackboxExporter();
            if (!waitForBlackboxHealthy(20, 500)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "blackbox exporter failed to start");
            }
            return blackboxResponse("blackbox exporter started", "healthy");
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to start blackbox exporter", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to start blackbox exporter", exception);
        }
    }

    private boolean waitForBlackboxHealthy(int attempts, long delayMillis) throws InterruptedException {
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (isBlackboxHealthy()) return true;
            Thread.sleep(delayMillis);
        }
        return false;
    }

    public String disableBlackbox() {
        try {
            if (!blackBoxConfigLoder.isBlackboxExporterRunning()) {
                return blackboxResponse("blackbox exporter already stopped", "stopped");
            }
            if (!blackBoxConfigLoder.stopBlackboxExporter()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "blackbox exporter failed to stop");
            }
            return blackboxResponse("blackbox exporter stopped", "stopped");
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to stop blackbox exporter", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to stop blackbox exporter", exception);
        }
    }

    public String getRecentProbes() {
        ObjectNode response = objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode probes = response.putArray("probes");
        try {
            HttpResponse<String> homeResponse = httpClient.send(
                    HttpRequest.newBuilder(URI.create(BLACKBOX_EXPORTER_BASE_URL + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (homeResponse.statusCode() == 200) {
                java.util.Map<String, String[]> timings = fetchProbeTimings();
                java.util.regex.Matcher matcher = RECENT_PROBE_ROW.matcher(homeResponse.body());
                while (matcher.find()) {
                    ObjectNode probe = probes.addObject();
                    String target = decodeHtmlEntities(matcher.group(2));
                    probe.put("module", decodeHtmlEntities(matcher.group(1)));
                    probe.put("target", target);
                    probe.put("result", decodeHtmlEntities(matcher.group(3)));
                    probe.put("logId", matcher.group(4));

                    String[] timing = timings.get(target);
                    if (timing != null) {
                        probe.put("duration", timing[0]);
                        probe.put("time", timing[1]);
                    }
                }
            }
            response.put("available", homeResponse.statusCode() == 200);
        } catch (IOException exception) {
            response.put("available", false);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            response.put("available", false);
        }
        return response.toString();
    }

    public String getProbeLog(String id) {
        if (!id.matches("\\d+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid probe log id");
        }
        try {
            HttpResponse<String> logResponse = httpClient.send(
                    HttpRequest.newBuilder(URI.create(BLACKBOX_EXPORTER_BASE_URL + "/logs?id=" + id)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (logResponse.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "probe log not found : " + id);
            }
            return logResponse.body();
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to fetch probe log", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to fetch probe log", exception);
        }
    }

    private java.util.Map<String, String[]> fetchProbeTimings() {
        java.util.Map<String, String[]> timings = new java.util.HashMap<>();
        try {
            HttpResponse<String> queryResponse = httpClient.send(
                    HttpRequest.newBuilder(URI.create(PROMETHEUS_QUERY_URL
                            + java.net.URLEncoder.encode("probe_duration_seconds",
                                    java.nio.charset.StandardCharsets.UTF_8))).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            if (queryResponse.statusCode() != 200) {
                return timings;
            }

            JsonNode results = objectMapper.readTree(queryResponse.body()).path("data").path("result");
            if (!results.isArray()) {
                return timings;
            }
            for (JsonNode item : results) {
                String instance = item.path("metric").path("instance").asText();
                JsonNode value = item.path("value");
                if (instance.isEmpty() || !value.isArray() || value.size() < 2) {
                    continue;
                }
                long scrapedAt = (long) Double.parseDouble(value.get(0).asText());
                String time = java.time.Instant.ofEpochSecond(scrapedAt)
                        .atZone(java.time.ZoneId.systemDefault()).format(PROBE_TIME_FORMAT);
                timings.put(instance, new String[] { value.get(1).asText(), time });
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException exception) {

        }
        return timings;
    }

    private String decodeHtmlEntities(String value) {
        return value.replaceAll("<[^>]*>", "").replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    public String getBlackboxStatus() {
        if (isBlackboxHealthy()) {
            return blackboxResponse("blackbox exporter is healthy", "healthy");
        }
        try {
            if (blackBoxConfigLoder.isBlackboxExporterRunning()) {
                return blackboxResponse("blackbox exporter is starting", "starting");
            }
        } catch (IOException exception) {
            return blackboxResponse("blackbox exporter is unreachable", "stopped");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return blackboxResponse("blackbox exporter is unreachable", "stopped");
        }
        return blackboxResponse("blackbox exporter is not running", "stopped");
    }

    private boolean isBlackboxHealthy() {
        try {
            HttpResponse<String> healthResponse = httpClient.send(
                    HttpRequest.newBuilder(URI.create(BLACKBOX_EXPORTER_HEALTH_URL))
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

    private ObjectNode parseBlackboxModules(String modules) {
        JsonNode parsedBlackboxModules = parseJsonBody(modules);

        if (!(parsedBlackboxModules instanceof ObjectNode incomingBlackboxModules) || incomingBlackboxModules.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "expected body { \"<moduleName>\": { <moduleConfig> } }");
        }

        incomingBlackboxModules.fieldNames().forEachRemaining(name -> {
            JsonNode moduleConfig = incomingBlackboxModules.get(name);
            if (!(moduleConfig.isObject() && moduleConfig.has("prober"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "invalid blackbox module config for : " + name);
            }
        });

        return incomingBlackboxModules;
    }

    private List<String> parseBlackboxModuleNames(String modules) {
        JsonNode parsedBlackboxModules = parseJsonBody(modules);

        if (!parsedBlackboxModules.isArray() || parsedBlackboxModules.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "expected body [ \"<moduleName>\", ... ]");
        }

        List<String> blackboxModuleNames = new ArrayList<>();
        for (JsonNode moduleName : parsedBlackboxModules) {
            if (!moduleName.isTextual() || moduleName.asText().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "expected body [ \"<moduleName>\", ... ]");
            }
            blackboxModuleNames.add(moduleName.asText());
        }
        return blackboxModuleNames;
    }

    private JsonNode parseJsonBody(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid json body", exception);
        }
    }

    private List<String> findExistingBlackboxModules(ObjectNode existingBlackboxModules, ObjectNode incomingBlackboxModules) {
        List<String> existingBlackboxModuleNames = new ArrayList<>();
        incomingBlackboxModules.fieldNames().forEachRemaining(name -> {
            if (existingBlackboxModules.has(name)) {
                existingBlackboxModuleNames.add(name);
            }
        });
        return existingBlackboxModuleNames;
    }

    private List<String> findMissingBlackboxModules(ObjectNode existingBlackboxModules, ObjectNode incomingBlackboxModules) {
        List<String> missingBlackboxModuleNames = new ArrayList<>();
        incomingBlackboxModules.fieldNames().forEachRemaining(name -> {
            if (!existingBlackboxModules.has(name)) {
                missingBlackboxModuleNames.add(name);
            }
        });
        return missingBlackboxModuleNames;
    }

    private List<String> findMissingBlackboxModulesByName(ObjectNode existingBlackboxModules, List<String> blackboxModuleNames) {
        List<String> missingBlackboxModuleNames = new ArrayList<>();
        for (String name : blackboxModuleNames) {
            if (!existingBlackboxModules.has(name)) {
                missingBlackboxModuleNames.add(name);
            }
        }
        return missingBlackboxModuleNames;
    }

    private List<String> namesOf(ObjectNode blackboxModules) {
        List<String> blackboxModuleNames = new ArrayList<>();
        blackboxModules.fieldNames().forEachRemaining(blackboxModuleNames::add);
        return blackboxModuleNames;
    }

    private boolean persistBlackboxConfig() {
        try {
            blackBoxConfigLoder.saveBlackboxConfig();
            return blackBoxConfigLoder.reloadBlackboxExporter();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to save blackbox config", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to reload blackbox exporter", exception);
        }
    }

    private String reloadSuffix(boolean reloaded) {
        return reloaded ? ", blackbox exporter reloaded" : ", warning: blackbox exporter not running, restart it to apply";
    }

    private String blackboxResponse(String message) {
        return blackboxResponse(message, "stopped");
    }

    private String blackboxResponse(String message, String state) {
        ObjectNode blackboxResponse = objectMapper.createObjectNode();
        blackboxResponse.put("status", "success");
        blackboxResponse.put("message", message);
        blackboxResponse.put("state", state);
        return blackboxResponse.toString();
    }
}
