package com.hackctl.observec.engine.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.hackctl.observec.engine.component.AlertmanagerConfigLoader;
import com.hackctl.observec.engine.component.GitService;

@Service
public class AlertmanagerService {

    private static final String ALERTMANAGER_HEALTH_URL = "http://localhost:9093/-/healthy";

    private final AlertmanagerNotificationStore notificationStore;
    private final AlertmanagerConfigLoader alertmanagerConfigLoader;
    private final ObjectMapper objectMapper;
    private final GitService gitService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public AlertmanagerService(
            AlertmanagerNotificationStore notificationStore,
            AlertmanagerConfigLoader alertmanagerConfigLoader,
            ObjectMapper objectMapper,
            GitService gitService) {
        this.notificationStore = notificationStore;
        this.alertmanagerConfigLoader = alertmanagerConfigLoader;
        this.objectMapper = objectMapper;
        this.gitService = gitService;
    }

    public void handleWebhook(String payload) {
        JsonNode parsedPayload = parseWebhookPayload(payload);
        if (!(parsedPayload instanceof ObjectNode webhookPayload)) {
            return;
        }

        String status = webhookPayload.path("status").asText("unknown");
        JsonNode alerts = webhookPayload.get("alerts");
        if (!(alerts instanceof ArrayNode alertItems) || alertItems.isEmpty()) {
            notificationStore.add(new AlertmanagerNotificationStore.AlertmanagerNotification(
                    Instant.now(), status, "", "", "", "Alertmanager notification received"));
            return;
        }

        for (JsonNode alert : alertItems) {
            JsonNode labels = alert.path("labels");
            JsonNode annotations = alert.path("annotations");
            notificationStore.add(new AlertmanagerNotificationStore.AlertmanagerNotification(
                    Instant.now(),
                    alert.path("status").asText(status),
                    labels.path("alertname").asText(""),
                    labels.path("instance").asText(""),
                    labels.path("severity").asText(""),
                    annotations.path("summary").asText(annotations.path("description").asText(""))));
        }
    }

    public String getRecentNotifications() {
        List<AlertmanagerNotificationStore.AlertmanagerNotification> notifications =
                notificationStore.listRecent(100);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("notifications");
        notifications.forEach(notification -> items.add(objectMapper.valueToTree(notification)));
        return response.toString();
    }

    public String getAlertmanagerStatus() {
        if (isAlertmanagerHealthy()) {
            return alertmanagerResponse("alertmanager is healthy", "healthy");
        }
        try {
            if (alertmanagerConfigLoader.isAlertmanagerRunning()) {
                return alertmanagerResponse("alertmanager is starting", "starting");
            }
        } catch (IOException exception) {
            return alertmanagerResponse("alertmanager is unreachable", "stopped");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return alertmanagerResponse("alertmanager is unreachable", "stopped");
        }
        return alertmanagerResponse("alertmanager is not running", "stopped");
    }

    public String getAlertmanagerConfig() {
        try {
            return alertmanagerConfigLoader.getAlertmanagerConfigAsYaml();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to serialize alertmanager config", exception);
        }
    }

    public String getAlertmanagerLogs() {
        try {
            List<String> logs = alertmanagerConfigLoader.getAlertmanagerLogTail(2000);
            ObjectNode response = objectMapper.createObjectNode();
            ArrayNode lines = response.putArray("lines");
            logs.forEach(lines::add);
            return response.toString();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to read alertmanager logs", exception);
        }
    }

    public String getReceivers() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("route_receiver", alertmanagerConfigLoader.getRouteReceiverName());
        response.set("receivers", alertmanagerConfigLoader.getReceivers().deepCopy());
        return response.toString();
    }

    public String getRoutes() {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("route", alertmanagerConfigLoader.getRoute().deepCopy());
        ArrayNode receiverNames = response.putArray("receiver_names");
        alertmanagerConfigLoader.getReceivers().forEach(receiver ->
                receiverNames.add(receiver.path("name").asText()));
        return response.toString();
    }

    public String putRoutes(String routesPayload) {
        ObjectNode payload = parseRoutePayload(routesPayload);
        ObjectNode route = (ObjectNode) payload.get("route");
        Set<String> receiverNames = listReceiverNames();
        validateRoute(route, receiverNames);
        gitService.beginBatch();
        try {
            alertmanagerConfigLoader.setRoute(route.deepCopy());
            boolean reloaded = persistAlertmanagerConfig();
            gitService.endBatch("chore(alertmanager): routes updated");
            return alertmanagerActionResponse("alertmanager routes updated" + reloadSuffix(reloaded));
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String getInhibitRules() {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("inhibit_rules", alertmanagerConfigLoader.getInhibitRules().deepCopy());
        return response.toString();
    }

    public String putInhibitRules(String inhibitRulesPayload) {
        ObjectNode payload = parseInhibitRulesPayload(inhibitRulesPayload);
        ArrayNode inhibitRules = (ArrayNode) payload.get("inhibit_rules");
        validateInhibitRules(inhibitRules);
        gitService.beginBatch();
        try {
            alertmanagerConfigLoader.setInhibitRules(inhibitRules.deepCopy());
            boolean reloaded = persistAlertmanagerConfig();
            gitService.endBatch("chore(alertmanager): inhibit rules updated");
            return alertmanagerActionResponse("alertmanager inhibit rules updated" + reloadSuffix(reloaded));
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String postReceivers(String receiversPayload) {
        ObjectNode incomingReceivers = parseReceivers(receiversPayload);
        ArrayNode receivers = alertmanagerConfigLoader.getReceivers();
        List<String> duplicates = findExistingReceivers(receivers, incomingReceivers);
        if (!duplicates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "alertmanager receivers already exist : " + duplicates);
        }

        gitService.beginBatch();
        try {
            incomingReceivers.fieldNames().forEachRemaining(name -> {
                ObjectNode receiver = withReceiverName(incomingReceivers.get(name), name);
                validateReceiver(receiver);
                receivers.add(receiver);
            });

            boolean reloaded = persistAlertmanagerConfig();
            gitService.endBatch("chore(alertmanager): receivers added : " + namesOf(incomingReceivers));
            return alertmanagerActionResponse("alertmanager receivers added : "
                    + namesOf(incomingReceivers) + reloadSuffix(reloaded));
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String putReceiver(String receiverPayload) {
        ObjectNode incomingReceivers = parseReceivers(receiverPayload);
        if (incomingReceivers.size() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "expected body { \"<receiverName>\" : { <receiverConfig> } }");
        }

        String receiverName = incomingReceivers.fieldNames().next();
        JsonNode receiverConfig = incomingReceivers.get(receiverName);
        int receiverIndex = alertmanagerConfigLoader.indexOfReceiver(receiverName);
        if (receiverIndex < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "alertmanager receiver does not exist : " + receiverName);
        }

        ObjectNode receiver = withReceiverName(receiverConfig, receiverName);
        validateReceiver(receiver);
        gitService.beginBatch();
        try {
            alertmanagerConfigLoader.getReceivers().set(receiverIndex, receiver);

            boolean reloaded = persistAlertmanagerConfig();
            gitService.endBatch("chore(alertmanager): receiver updated : " + receiverName);
            return alertmanagerActionResponse("alertmanager receiver updated : "
                    + receiverName + reloadSuffix(reloaded));
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String deleteReceivers(String receiverNamesPayload) {
        List<String> receiverNames = parseReceiverNames(receiverNamesPayload);
        ArrayNode receivers = alertmanagerConfigLoader.getReceivers();
        List<String> missing = findMissingReceivers(receivers, receiverNames);
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "alertmanager receivers do not exist : " + missing);
        }

        List<String> protectedReceivers = receiverNames.stream()
                .filter(alertmanagerConfigLoader::isProtectedReceiver)
                .collect(Collectors.toList());
        if (!protectedReceivers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "protected alertmanager receivers cannot be deleted : " + protectedReceivers);
        }

        int remaining = receivers.size() - receiverNames.size();
        if (remaining <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "at least one alertmanager receiver must remain");
        }

        gitService.beginBatch();
        try {
            String routeReceiver = alertmanagerConfigLoader.getRouteReceiverName();
            if (receiverNames.contains(routeReceiver)) {
                alertmanagerConfigLoader.setRouteReceiverName(AlertmanagerConfigLoader.DEFAULT_RECEIVER);
            }

            for (int index = receivers.size() - 1; index >= 0; index--) {
                String name = receivers.get(index).path("name").asText();
                if (receiverNames.contains(name)) {
                    receivers.remove(index);
                }
            }

            boolean reloaded = persistAlertmanagerConfig();
            gitService.endBatch("chore(alertmanager): receivers deleted : " + receiverNames);
            return alertmanagerActionResponse("alertmanager receivers deleted : "
                    + receiverNames + reloadSuffix(reloaded));
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    private boolean persistAlertmanagerConfig() {
        try {
            return alertmanagerConfigLoader.applyAlertmanagerRuntime();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to save alertmanager config", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to reload alertmanager", exception);
        }
    }

    private ObjectNode parseRoutePayload(String routesPayload) {
        try {
            JsonNode parsed = objectMapper.readTree(routesPayload);
            if (!(parsed instanceof ObjectNode payload) || !payload.has("route")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "expected body { \"route\" : { <routeConfig> } }");
            }
            if (!(payload.get("route") instanceof ObjectNode)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "route must be an object");
            }
            return payload;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid alertmanager route payload", exception);
        }
    }

    private Set<String> listReceiverNames() {
        Set<String> receiverNames = new HashSet<>();
        alertmanagerConfigLoader.getReceivers().forEach(receiver ->
                receiverNames.add(receiver.path("name").asText()));
        return receiverNames;
    }

    private void validateRoute(ObjectNode route, Set<String> receiverNames) {
        String receiver = route.path("receiver").asText("").trim();
        if (receiver.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "route receiver is required");
        }
        if (!receiverNames.contains(receiver)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "route receiver does not exist : " + receiver);
        }

        JsonNode childRoutes = route.get("routes");
        if (childRoutes instanceof ArrayNode childRouteItems) {
            for (JsonNode childRoute : childRouteItems) {
                if (childRoute instanceof ObjectNode childRouteNode) {
                    validateRoute(childRouteNode, receiverNames);
                } else {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "child route must be an object");
                }
            }
        }
    }

    private ObjectNode parseInhibitRulesPayload(String inhibitRulesPayload) {
        try {
            JsonNode parsed = objectMapper.readTree(inhibitRulesPayload);
            if (!(parsed instanceof ObjectNode payload) || !payload.has("inhibit_rules")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "expected body { \"inhibit_rules\" : [ ... ] }");
            }
            if (!(payload.get("inhibit_rules") instanceof ArrayNode)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "inhibit_rules must be an array");
            }
            return payload;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid alertmanager inhibit rules payload", exception);
        }
    }

    private void validateInhibitRules(ArrayNode inhibitRules) {
        for (JsonNode inhibitRule : inhibitRules) {
            if (!(inhibitRule instanceof ObjectNode inhibitRuleNode)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "inhibit rule must be an object");
            }
            validateInhibitRule(inhibitRuleNode);
        }
    }

    private void validateInhibitRule(ObjectNode inhibitRule) {
        if (!hasInhibitMatchers(inhibitRule, "source")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "inhibit rule source matchers are required");
        }
        if (!hasInhibitMatchers(inhibitRule, "target")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "inhibit rule target matchers are required");
        }

        JsonNode equal = inhibitRule.get("equal");
        if (equal != null && !equal.isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "inhibit rule equal must be an array");
        }
    }

    private boolean hasInhibitMatchers(ObjectNode inhibitRule, String side) {
        String matchersKey = side + "_matchers";
        if (inhibitRule.has(matchersKey)
                && inhibitRule.get(matchersKey).isArray()
                && !inhibitRule.get(matchersKey).isEmpty()) {
            return true;
        }

        String matchKey = side + "_match";
        if (inhibitRule.has(matchKey)
                && inhibitRule.get(matchKey).isObject()
                && !inhibitRule.get(matchKey).isEmpty()) {
            return true;
        }

        String matchReKey = side + "_match_re";
        return inhibitRule.has(matchReKey)
                && inhibitRule.get(matchReKey).isObject()
                && !inhibitRule.get(matchReKey).isEmpty();
    }

    private ObjectNode parseReceivers(String receiversPayload) {
        try {
            JsonNode parsed = objectMapper.readTree(receiversPayload);
            if (!(parsed instanceof ObjectNode receivers)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "expected body { \"<receiverName>\" : { <receiverConfig> } }");
            }
            if (receivers.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "at least one receiver is required");
            }
            return receivers;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid alertmanager receiver payload", exception);
        }
    }

    private List<String> parseReceiverNames(String receiverNamesPayload) {
        try {
            JsonNode parsed = objectMapper.readTree(receiverNamesPayload);
            if (!(parsed instanceof ArrayNode receiverNames) || receiverNames.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "expected body [ \"<receiverName>\", ... ]");
            }
            List<String> names = new ArrayList<>();
            receiverNames.forEach(node -> {
                String name = node.asText("").trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            });
            if (names.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "expected body [ \"<receiverName>\", ... ]");
            }
            return names;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid alertmanager receiver names payload", exception);
        }
    }

    private ObjectNode withReceiverName(JsonNode receiverConfig, String receiverName) {
        if (!(receiverConfig instanceof ObjectNode receiver)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "receiver \"" + receiverName + "\" must be an object");
        }
        ObjectNode receiverCopy = receiver.deepCopy();
        receiverCopy.put("name", receiverName);
        return receiverCopy;
    }

    private void validateReceiver(ObjectNode receiver) {
        String name = receiver.path("name").asText("").trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "receiver name is required");
        }

        boolean hasWebhook = receiver.has("webhook_configs")
                && receiver.get("webhook_configs").isArray()
                && !receiver.get("webhook_configs").isEmpty();
        boolean hasEmail = receiver.has("email_configs")
                && receiver.get("email_configs").isArray()
                && !receiver.get("email_configs").isEmpty();
        boolean hasSlack = receiver.has("slack_configs")
                && receiver.get("slack_configs").isArray()
                && !receiver.get("slack_configs").isEmpty();

        if (!hasWebhook && !hasEmail && !hasSlack) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "receiver must include webhook_configs, email_configs, or slack_configs");
        }

        if (hasWebhook) {
            for (JsonNode webhookConfig : receiver.get("webhook_configs")) {
                String url = webhookConfig.path("url").asText("").trim();
                if (url.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "webhook url is required for receiver \"" + name + "\"");
                }
            }
        }

        if (hasEmail) {
            for (JsonNode emailConfig : receiver.get("email_configs")) {
                if (emailConfig.path("to").asText("").trim().isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "email to address is required for receiver \"" + name + "\"");
                }
                if (emailConfig.path("from").asText("").trim().isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "email from address is required for receiver \"" + name + "\"");
                }
                if (emailConfig.path("smarthost").asText("").trim().isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "email smarthost is required for receiver \"" + name + "\"");
                }
            }
        }

        if (hasSlack) {
            for (JsonNode slackConfig : receiver.get("slack_configs")) {
                if (slackConfig.path("api_url").asText("").trim().isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "slack api_url is required for receiver \"" + name + "\"");
                }
            }
        }
    }

    private List<String> findExistingReceivers(ArrayNode receivers, ObjectNode incomingReceivers) {
        List<String> existing = new ArrayList<>();
        incomingReceivers.fieldNames().forEachRemaining(name -> {
            if (alertmanagerConfigLoader.indexOfReceiver(name) >= 0) {
                existing.add(name);
            }
        });
        return existing;
    }

    private List<String> findMissingReceivers(ArrayNode receivers, List<String> receiverNames) {
        return receiverNames.stream()
                .filter(name -> alertmanagerConfigLoader.indexOfReceiver(name) < 0)
                .collect(Collectors.toList());
    }

    private String namesOf(ObjectNode receivers) {
        return StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(receivers.fieldNames(), Spliterator.ORDERED), false)
                .collect(Collectors.joining(", "));
    }

    private String reloadSuffix(boolean reloaded) {
        return reloaded ? " and alertmanager reloaded" : "";
    }

    private String alertmanagerActionResponse(String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", "success");
        response.put("message", message);
        return response.toString();
    }

    // enable/start alertmanager on demand — jar-first architecture
    public String enableAlertmanager() {
        try {
            if (isAlertmanagerHealthy()) {
                return alertmanagerResponse("alertmanager already running", "healthy");
            }
            try {
                if (alertmanagerConfigLoader.isAlertmanagerRunning()) {
                    if (waitForAlertmanagerHealthy(20, 500)) {
                        return alertmanagerResponse("alertmanager started", "healthy");
                    }
                }
            } catch (IOException | InterruptedException ignored) {}
            alertmanagerConfigLoader.startAlertmanager();
            if (!waitForAlertmanagerHealthy(20, 500)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "alertmanager failed to start");
            }
            return alertmanagerResponse("alertmanager started", "healthy");
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to start alertmanager", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to start alertmanager", exception);
        }
    }

    private boolean waitForAlertmanagerHealthy(int attempts, long delayMillis) throws InterruptedException {
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (isAlertmanagerHealthy()) return true;
            Thread.sleep(delayMillis);
        }
        return false;
    }

    // disable/stop alertmanager on demand — jar-first
    public String disableAlertmanager() {
        try {
            if (!alertmanagerConfigLoader.isAlertmanagerRunning()) {
                return alertmanagerResponse("alertmanager already stopped", "stopped");
            }
            if (!alertmanagerConfigLoader.stopAlertmanager()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "alertmanager failed to stop");
            }
            return alertmanagerResponse("alertmanager stopped", "stopped");
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to stop alertmanager", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to stop alertmanager", exception);
        }
    }

    private boolean isAlertmanagerHealthy() {
        try {
            HttpResponse<String> healthResponse = httpClient.send(
                    HttpRequest.newBuilder(URI.create(ALERTMANAGER_HEALTH_URL))
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

    private String alertmanagerResponse(String message, String state) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", "success");
        response.put("message", message);
        response.put("state", state);
        return response.toString();
    }

    private JsonNode parseWebhookPayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }
}
