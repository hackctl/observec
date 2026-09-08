package com.hackctl.observec.engine.component;

import java.io.File;
import java.io.IOException;

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
public class AlertmanagerConfigLoader {

    public static final String DEFAULT_RECEIVER = "observecAlertmanagerReceiverDefault";
    public static final String WEBHOOK_URL = "http://127.0.0.1:8080/alertmanager/webhook";

    private final YAMLMapper yamlMapper = new YAMLMapper();
    private final String alertmanagerConfigPath;
    private final String alertmanagerLogPath;
    private ObjectNode alertmanagerConfig;

    @Autowired(required = false)
    @Lazy
    private GitService gitService;

    public AlertmanagerConfigLoader(
            @Value("${observec.alertmanager.config-path:/observec/observecData/alertmanager/alertmanagerConfig.yml}") String alertmanagerConfigPath,
            @Value("${observec.alertmanager.log-path:/observec/observecData/alertmanager/alertmanager.log}") String alertmanagerLogPath) {
        this.alertmanagerConfigPath = alertmanagerConfigPath;
        this.alertmanagerLogPath = alertmanagerLogPath;
    }

    @PostConstruct
    public void loadAlertmanagerConfig() {
        File alertmanagerConfigFile = new File(alertmanagerConfigPath);
        try {
            if (!alertmanagerConfigFile.exists()) {
                createDefaultAlertmanagerConfig(alertmanagerConfigFile);
            }

            JsonNode loadedAlertmanagerConfig = yamlMapper.readTree(alertmanagerConfigFile);
            if (!(loadedAlertmanagerConfig instanceof ObjectNode)) {
                throw new IllegalStateException("invalid alertmanager config structure at " + alertmanagerConfigPath);
            }

            this.alertmanagerConfig = (ObjectNode) loadedAlertmanagerConfig;
            if (ensureWebhookReceiver()) {
                saveAlertmanagerConfig();
                // jar-first architecture: do not auto-start alertmanager on init;
                // user starts it via Enable button (POST /alertmanager/enable)
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to load alertmanager config from " + alertmanagerConfigPath, exception);
        }
    }

    public boolean isAlertmanagerRunning() throws IOException, InterruptedException {
        Process checkProcess = new ProcessBuilder("pgrep", "-f", "alertmanager --config.file").start();
        return checkProcess.waitFor() == 0;
    }

    public void startAlertmanager() throws IOException, InterruptedException {
        if (isAlertmanagerRunning()) {
            return;
        }
        File logFile = new File(alertmanagerLogPath);
        logFile.getParentFile().mkdirs();
        new File(alertmanagerConfigPath).getParentFile().mkdirs();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "alertmanager",
                "--config.file=" + alertmanagerConfigPath,
                "--web.listen-address=:9093");
        processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(logFile));
        processBuilder.start();
    }

    public java.util.List<String> getAlertmanagerLogTail(int maxLines) throws IOException {
        File alertmanagerLogFile = new File(alertmanagerLogPath);
        if (!alertmanagerLogFile.exists()) {
            return java.util.Collections.emptyList();
        }

        java.util.Deque<String> tail = new java.util.ArrayDeque<>(maxLines);
        try (java.io.BufferedReader reader = java.nio.file.Files.newBufferedReader(alertmanagerLogFile.toPath())) {
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

    public boolean reloadAlertmanager() throws IOException, InterruptedException {
        if (!isAlertmanagerRunning()) {
            startAlertmanager();
            return true;
        }
        Process reloadProcess = new ProcessBuilder("pkill", "-HUP", "-f", "alertmanager --config.file").start();
        return reloadProcess.waitFor() == 0;
    }

    public boolean stopAlertmanager() throws IOException, InterruptedException {
        if (!isAlertmanagerRunning()) {
            return true;
        }
        new ProcessBuilder("pkill", "-f", "alertmanager --config.file").start().waitFor();
        for (int i = 0; i < 20; i++) {
            if (!isAlertmanagerRunning()) return true;
            Thread.sleep(200);
        }
        return !isAlertmanagerRunning();
    }

    public String getAlertmanagerConfigAsYaml() throws IOException {
        return yamlMapper.writeValueAsString(alertmanagerConfig);
    }

    public ArrayNode getReceivers() {
        return alertmanagerConfig.withArray("receivers");
    }

    public String getRouteReceiverName() {
        return alertmanagerConfig.path("route").path("receiver").asText(DEFAULT_RECEIVER);
    }

    public ObjectNode getRoute() {
        return alertmanagerConfig.withObject("route");
    }

    public void setRoute(ObjectNode route) {
        alertmanagerConfig.set("route", route);
    }

    public ArrayNode getInhibitRules() {
        return alertmanagerConfig.withArray("inhibit_rules");
    }

    public void setInhibitRules(ArrayNode inhibitRules) {
        alertmanagerConfig.set("inhibit_rules", inhibitRules);
    }

    public void setRouteReceiverName(String receiverName) {
        ObjectNode route = alertmanagerConfig.withObject("route");
        route.put("receiver", receiverName);
    }

    public void saveAlertmanagerConfig() throws IOException {
        yamlMapper.writerWithDefaultPrettyPrinter()
                .writeValue(new File(alertmanagerConfigPath), alertmanagerConfig);
        if (gitService != null) gitService.autoCommit("chore(alertmanager): update alertmanagerConfig.yml");
    }

    public boolean applyAlertmanagerRuntime() throws IOException, InterruptedException {
        saveAlertmanagerConfig();
        return reloadAlertmanager();
    }

    public ObjectNode findReceiverByName(String receiverName) {
        return findReceiver(getReceivers(), receiverName);
    }

    public int indexOfReceiver(String receiverName) {
        ArrayNode receivers = getReceivers();
        for (int index = 0; index < receivers.size(); index++) {
            if (receiverName.equals(receivers.get(index).path("name").asText())) {
                return index;
            }
        }
        return -1;
    }

    public boolean isProtectedReceiver(String receiverName) {
        return DEFAULT_RECEIVER.equals(receiverName);
    }

    private boolean ensureWebhookReceiver() {
        ArrayNode receivers = alertmanagerConfig.withArray("receivers");
        ObjectNode receiver = findReceiver(receivers, DEFAULT_RECEIVER);
        if (receiver == null) {
            receiver = yamlMapper.createObjectNode();
            receiver.put("name", DEFAULT_RECEIVER);
            receivers.add(receiver);
        }

        ArrayNode webhookConfigs = receiver.withArray("webhook_configs");
        for (JsonNode webhookConfig : webhookConfigs) {
            if (WEBHOOK_URL.equals(webhookConfig.path("url").asText())) {
                return false;
            }
        }

        ObjectNode webhookConfig = yamlMapper.createObjectNode();
        webhookConfig.put("url", WEBHOOK_URL);
        webhookConfig.put("send_resolved", true);
        webhookConfigs.add(webhookConfig);
        return true;
    }

    private ObjectNode findReceiver(ArrayNode receivers, String receiverName) {
        for (JsonNode receiver : receivers) {
            if (receiver instanceof ObjectNode receiverNode
                    && receiverName.equals(receiverNode.path("name").asText())) {
                return receiverNode;
            }
        }
        return null;
    }

    private void createDefaultAlertmanagerConfig(File alertmanagerConfigFile) throws IOException {
        ObjectNode route = yamlMapper.createObjectNode();
        route.put("receiver", DEFAULT_RECEIVER);
        route.set("group_by", yamlMapper.createArrayNode().add("alertname").add("instance"));
        route.put("group_wait", "30s");
        route.put("group_interval", "5m");
        route.put("repeat_interval", "4h");

        ObjectNode receiver = yamlMapper.createObjectNode();
        receiver.put("name", DEFAULT_RECEIVER);
        ArrayNode webhookConfigs = yamlMapper.createArrayNode();
        ObjectNode webhookConfig = yamlMapper.createObjectNode();
        webhookConfig.put("url", WEBHOOK_URL);
        webhookConfig.put("send_resolved", true);
        webhookConfigs.add(webhookConfig);
        receiver.set("webhook_configs", webhookConfigs);

        ObjectNode defaultAlertmanagerConfig = yamlMapper.createObjectNode();
        defaultAlertmanagerConfig.putObject("global").put("resolve_timeout", "5m");
        defaultAlertmanagerConfig.set("route", route);
        defaultAlertmanagerConfig.set("receivers", yamlMapper.createArrayNode().add(receiver));

        alertmanagerConfigFile.getParentFile().mkdirs();
        yamlMapper.writerWithDefaultPrettyPrinter().writeValue(alertmanagerConfigFile, defaultAlertmanagerConfig);
    }
}
