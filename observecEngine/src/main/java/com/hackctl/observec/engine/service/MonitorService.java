package com.hackctl.observec.engine.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hackctl.observec.engine.component.AlertmanagerConfigLoader;
import com.hackctl.observec.engine.component.BlackBoxConfigLoder;
import com.hackctl.observec.engine.component.GitService;
import com.hackctl.observec.engine.component.MonitorStore;
import com.hackctl.observec.engine.component.PrometheusConfigLoader;
import com.hackctl.observec.engine.model.Monitor;

import jakarta.annotation.PostConstruct;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MonitorService {

    private static final String JOB_PREFIX = "observecMonitorJob_";
    private static final String MODULE_PREFIX = "observecMonitorModule_";
    private static final String SLACK_RECEIVER_PREFIX = "observecMonitorSlack_";
    private static final String RULE_DIR = "monitors";
    private static final String DEFAULT_INTERVAL = "60s";
    private static final String DEFAULT_TIMEOUT = "10s";
    private static final String PROMETHEUS_QUERY_URL = "http://localhost:9090/api/v1/query?query=";
    private static final String PROMETHEUS_QUERY_RANGE_URL = "http://localhost:9090/api/v1/query_range?query=";
    private static final String PROMETHEUS_HEALTH_URL = "http://localhost:9090/-/healthy";

    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern DURATION_PATTERN = Pattern.compile("^\\d+(ms|s|m|h|d|w|y)$");

    private final MonitorStore monitorStore;
    private final BlackBoxConfigLoder blackBoxConfigLoder;
    private final PrometheusConfigLoader prometheusConfigLoader;
    private final AlertmanagerConfigLoader alertmanagerConfigLoader;
    private final ObjectMapper objectMapper;
    private final GitService gitService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public MonitorService(MonitorStore monitorStore,
                          BlackBoxConfigLoder blackBoxConfigLoder,
                          PrometheusConfigLoader prometheusConfigLoader,
                          AlertmanagerConfigLoader alertmanagerConfigLoader,
                          ObjectMapper objectMapper,
                          GitService gitService) {
        this.monitorStore = monitorStore;
        this.blackBoxConfigLoder = blackBoxConfigLoder;
        this.prometheusConfigLoader = prometheusConfigLoader;
        this.alertmanagerConfigLoader = alertmanagerConfigLoader;
        this.objectMapper = objectMapper;
        this.gitService = gitService;
    }

    @PostConstruct
    public void reconcileOnStart() {
        // ensure all enabled monitors have their artifacts; disabled ones are cleaned
        // single commit for the whole boot reconcile
        gitService.beginBatch();
        try {
            Map<String, Monitor> all = monitorStore.list();
            for (Monitor monitor : all.values()) {
                try {
                    if (Boolean.FALSE.equals(monitor.getEnabled())) {
                        removeArtifacts(monitor.getId(), false);
                    } else {
                        ensureArtifacts(monitor);
                    }
                } catch (Exception e) {
                    // log but continue - startup should not fail because of one monitor
                    System.err.println("failed to reconcile monitor " + monitor.getId() + ": " + e.getMessage());
                }
            }
            gitService.endBatch("chore(monitor): reconcile on start");
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    // ---------- CRUD ----------

    public String listMonitors() {
        Map<String, Monitor> all = monitorStore.list();
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(all.values());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to serialize monitors", e);
        }
    }

    public String getMonitor(String id) {
        validateId(id);
        Monitor monitor = monitorStore.get(id);
        if (monitor == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor does not exist : " + id);
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(monitor);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to serialize monitor", e);
        }
    }

    public String createMonitor(String body) {
        JsonNode payload = parseJsonBody(body);
        if (!payload.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expected monitor object");
        }
        ObjectNode obj = (ObjectNode) payload;
        Monitor monitor = mapToMonitor(obj);
        // generate id if missing
        String id = monitor.getId();
        if (id == null || id.isBlank()) {
            String base = slugify(monitor.getName() != null ? monitor.getName() : monitor.getTarget());
            if (base.isBlank()) base = "monitor";
            id = uniqueId(base);
            monitor.setId(id);
        } else {
            validateId(id);
            if (monitorStore.contains(id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "monitor already exists : " + id);
            }
        }
        validateMonitor(monitor, true);
        Instant now = Instant.now();
        monitor.setCreatedAt(now);
        monitor.setUpdatedAt(now);
        applyDefaults(monitor);
        gitService.beginBatch();
        try {
            monitorStore.put(monitor);
            try {
                ensureArtifacts(monitor);
            } catch (Exception e) {
                monitorStore.remove(monitor.getId());
                // cleanup any partial artifacts
                try { removeArtifacts(monitor.getId(), true); } catch (Exception ignored) {}
                if (e instanceof ResponseStatusException) throw (ResponseStatusException) e;
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to create monitor artifacts: " + e.getMessage(), e);
            }
            gitService.endBatch("chore(monitor): created : " + monitor.getId());
            return successResponse("monitor created : " + monitor.getId());
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String updateMonitor(String id, String body) {
        validateId(id);
        Monitor existing = monitorStore.get(id);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor does not exist : " + id);
        }
        JsonNode payload = parseJsonBody(body);
        if (!payload.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expected monitor object");
        }
        ObjectNode updates = (ObjectNode) payload;
        // merge updates onto existing (preserve id and createdAt)
        Monitor merged = mergeMonitor(existing, updates);
        merged.setId(id);
        merged.setCreatedAt(existing.getCreatedAt());
        merged.setUpdatedAt(Instant.now());
        validateMonitor(merged, false);
        applyDefaults(merged);
        gitService.beginBatch();
        try {
            monitorStore.put(merged);
            try {
                ensureArtifacts(merged);
            } catch (Exception e) {
                if (e instanceof ResponseStatusException) throw (ResponseStatusException) e;
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to update monitor artifacts: " + e.getMessage(), e);
            }
            gitService.endBatch("chore(monitor): updated : " + id);
            return successResponse("monitor updated : " + id);
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String deleteMonitor(String id) {
        validateId(id);
        Monitor existing = monitorStore.get(id);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor does not exist : " + id);
        }
        gitService.beginBatch();
        try {
            monitorStore.remove(id);
            try {
                removeArtifacts(id, true);
            } catch (Exception e) {
                if (e instanceof ResponseStatusException) throw (ResponseStatusException) e;
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to delete monitor artifacts: " + e.getMessage(), e);
            }
            gitService.endBatch("chore(monitor): deleted : " + id);
            return successResponse("monitor deleted : " + id);
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String deleteMonitorsBulk(String body) {
        JsonNode parsed = parseJsonBody(body);
        if (!parsed.isArray() || parsed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expected body [ \"<monitorId>\", ... ]");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode n : parsed) {
            if (!n.isTextual() || n.asText().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expected body [ \"<monitorId>\", ... ]");
            }
            ids.add(n.asText());
        }
        List<String> missing = new ArrayList<>();
        for (String mid : ids) {
            if (!monitorStore.contains(mid)) missing.add(mid);
        }
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitors do not exist : " + missing);
        }
        gitService.beginBatch();
        try {
            for (String mid : ids) {
                monitorStore.remove(mid);
                try { removeArtifacts(mid, true); } catch (Exception e) {
                    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to delete monitor artifacts: " + e.getMessage(), e);
                }
            }
            gitService.endBatch("chore(monitor): deleted : " + ids);
            return successResponse("monitors deleted : " + ids);
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String pauseMonitor(String id) {
        validateId(id);
        Monitor m = monitorStore.get(id);
        if (m == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor does not exist : " + id);
        if (Boolean.FALSE.equals(m.getEnabled())) return successResponse("monitor already paused : " + id);
        m.setEnabled(false);
        m.setUpdatedAt(Instant.now());
        gitService.beginBatch();
        try {
            monitorStore.put(m);
            try { removeArtifacts(id, true); } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to pause monitor: " + e.getMessage(), e);
            }
            gitService.endBatch("chore(monitor): paused : " + id);
            return successResponse("monitor paused : " + id);
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String resumeMonitor(String id) {
        validateId(id);
        Monitor m = monitorStore.get(id);
        if (m == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor does not exist : " + id);
        if (Boolean.TRUE.equals(m.getEnabled())) return successResponse("monitor already enabled : " + id);
        m.setEnabled(true);
        m.setUpdatedAt(Instant.now());
        gitService.beginBatch();
        try {
            monitorStore.put(m);
            try { ensureArtifacts(m); } catch (Exception e) {
                if (e instanceof ResponseStatusException) throw (ResponseStatusException) e;
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to resume monitor: " + e.getMessage(), e);
            }
            gitService.endBatch("chore(monitor): resumed : " + id);
            return successResponse("monitor resumed : " + id);
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    public String uploadMonitorTls(String monitorId, MultipartFile caFile, MultipartFile certFile, MultipartFile keyFile) {
        validateId(monitorId);
        Monitor monitor = monitorStore.get(monitorId);
        if (monitor == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor does not exist : " + monitorId);
        if (monitorId == null || monitorId.isBlank() || !monitorId.matches("[A-Za-z0-9_-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid monitor id for tls upload");
        }
        String module = moduleName(monitorId);
        gitService.beginBatch();
        try {
            ObjectNode response = objectMapper.createObjectNode();
            int stored = 0;
            stored += storeMonitorTlsPart(response, module, "ca_file", "ca.crt", caFile, monitor);
            stored += storeMonitorTlsPart(response, module, "cert_file", "server.crt", certFile, monitor);
            stored += storeMonitorTlsPart(response, module, "key_file", "server.key", keyFile, monitor);
            if (stored == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no tls files provided - pick at least one file");
            }
            monitor.setTlsEnabled(true);
            monitor.setUpdatedAt(Instant.now());
            monitorStore.put(monitor);
            try {
                ensureArtifacts(monitor);
            } catch (Exception e) {
                if (e instanceof ResponseStatusException) throw (ResponseStatusException) e;
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to apply monitor tls: " + e.getMessage(), e);
            }
            gitService.endBatch("chore(monitor): tls updated : " + monitorId);
            return response.toString();
        } catch (RuntimeException | Error e) {
            gitService.abortBatch();
            throw e;
        }
    }

    private int storeMonitorTlsPart(ObjectNode response, String moduleName, String field, String materialName, MultipartFile file, Monitor monitor) {
        if (file == null || file.isEmpty()) return 0;
        try {
            String path = blackBoxConfigLoder.saveTlsMaterial(moduleName, materialName, file.getBytes());
            response.put(field, path);
            response.put(field.replace("_file", "_name"), file.getOriginalFilename());
            // store path into monitor model
            if ("ca_file".equals(field)) monitor.setTlsCaFile(path);
            else if ("cert_file".equals(field)) monitor.setTlsCertFile(path);
            else if ("key_file".equals(field)) monitor.setTlsKeyFile(path);
            return 1;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to store tls material", e);
        }
    }

    // ---------- Status & Heartbeats ----------

    public String getMonitorsStatus() {
        Map<String, Monitor> all = monitorStore.list();
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("monitors");
        if (all.isEmpty()) {
            response.put("overall", "empty");
            return response.toString();
        }

        // batch query probe_success for all monitor jobs
        String jobRegex = JOB_PREFIX + ".*";
        Map<String, Double> successMap = queryProbeSuccess(jobRegex);
        Map<String, Double> durationMap = queryProbeDuration(jobRegex);

        // also fetch up per monitor
        // compute status
        int upCount = 0, downCount = 0, pausedCount = 0, pendingCount = 0;
        for (Monitor m : all.values()) {
            ObjectNode entry = items.addObject();
            entry.put("id", m.getId());
            entry.put("name", m.getName() != null ? m.getName() : m.getId());
            entry.put("target", m.getTarget());
            entry.put("type", m.getType());
            entry.put("interval", m.getInterval() != null ? m.getInterval() : DEFAULT_INTERVAL);
            entry.put("enabled", !Boolean.FALSE.equals(m.getEnabled()));

            String job = jobName(m.getId());
            if (Boolean.FALSE.equals(m.getEnabled())) {
                entry.put("status", "paused");
                entry.put("statusText", "Paused");
                pausedCount++;
                continue;
            }
            Double success = successMap.get(job);
            Double duration = durationMap.get(job);
            if (success == null) {
                entry.put("status", "pending");
                entry.put("statusText", "Pending");
                pendingCount++;
            } else if (success == 1.0) {
                entry.put("status", "up");
                entry.put("statusText", "Up");
                upCount++;
                if (duration != null) entry.put("duration", duration);
            } else {
                entry.put("status", "down");
                entry.put("statusText", "Down");
                downCount++;
                if (duration != null) entry.put("duration", duration);
            }
            // last check time not available from instant query; client can treat now
            entry.put("lastCheck", Instant.now().toString());
        }
        String overall;
        if (downCount > 0) overall = "down";
        else if (pendingCount > 0) overall = "pending";
        else if (upCount > 0) overall = "up";
        else overall = "paused";
        response.put("overall", overall);
        ObjectNode summary = response.putObject("summary");
        summary.put("total", all.size());
        summary.put("up", upCount);
        summary.put("down", downCount);
        summary.put("paused", pausedCount);
        summary.put("pending", pendingCount);
        return response.toString();
    }

    public String getMonitorStatus(String id) {
        validateId(id);
        Monitor m = monitorStore.get(id);
        if (m == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor does not exist : " + id);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("id", m.getId());
        response.put("name", m.getName() != null ? m.getName() : m.getId());
        response.put("target", m.getTarget());
        response.put("type", m.getType());
        response.put("enabled", !Boolean.FALSE.equals(m.getEnabled()));
        if (Boolean.FALSE.equals(m.getEnabled())) {
            response.put("status", "paused");
            return response.toString();
        }
        String job = jobName(m.getId());
        Map<String, Double> successMap = queryProbeSuccess(job);
        Map<String, Double> durationMap = queryProbeDuration(job);
        Double success = successMap.get(job);
        Double duration = durationMap.get(job);
        if (success == null) {
            response.put("status", "pending");
        } else if (success == 1.0) {
            response.put("status", "up");
            if (duration != null) response.put("duration", duration);
        } else {
            response.put("status", "down");
            if (duration != null) response.put("duration", duration);
        }
        response.put("lastCheck", Instant.now().toString());
        // fetch heartbeat history limited to last 50 points
        try {
            ArrayNode beats = fetchHeartbeats(m, 50);
            response.set("heartbeats", beats);
            // compute uptime percent
            if (beats.size() > 0) {
                long up = 0;
                for (JsonNode b : beats) if (b.path("value").asDouble(0) == 1.0) up++;
                double uptime = (up * 100.0) / beats.size();
                response.put("uptime", Math.round(uptime * 100.0) / 100.0);
            }
        } catch (Exception ignored) {}
        return response.toString();
    }

    public String getMonitorHeartbeats(String id) {
        validateId(id);
        Monitor m = monitorStore.get(id);
        if (m == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "monitor does not exist : " + id);
        int limit = 50;
        ArrayNode beats = fetchHeartbeats(m, limit);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("id", id);
        resp.set("heartbeats", beats);
        return resp.toString();
    }

    public String getAllHeartbeats() {
        Map<String, Monitor> all = monitorStore.list();
        ObjectNode resp = objectMapper.createObjectNode();
        for (Monitor m : all.values()) {
            if (Boolean.FALSE.equals(m.getEnabled())) {
                resp.set(m.getId(), objectMapper.createArrayNode());
                continue;
            }
            try {
                ArrayNode beats = fetchHeartbeats(m, 50);
                resp.set(m.getId(), beats);
            } catch (Exception e) {
                resp.set(m.getId(), objectMapper.createArrayNode());
            }
        }
        return resp.toString();
    }

    public String getMonitorsTable(String range) {
        if (range == null || range.isBlank() || !DURATION_PATTERN.matcher(range).matches()) {
            range = "24h";
        }
        long rangeSeconds = parseDurationSeconds(range);
        if (rangeSeconds <= 0) rangeSeconds = 86400;
        long now = Instant.now().getEpochSecond();
        long start = now - rangeSeconds;
        String step = computeStep(rangeSeconds);

        Map<String, Monitor> all = monitorStore.list();
        String jobRegex = JOB_PREFIX + ".*";

        // batch instant queries
        Map<String, Double> successMap = queryProbeSuccess(jobRegex);
        Map<String, Double> durationMap = queryProbeDuration(jobRegex);
        Map<String, Double> sslExpiryMap = querySslExpiry(jobRegex);
        Map<String, Double> uptimeMap = queryUptime(jobRegex, range);

        // range query for last fail/success
        Map<String, List<ValuePoint>> historyMap = queryRange("probe_success{job=~\"" + jobRegex + "\"}", start, now, step);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("range", range);
        response.put("rangeSeconds", rangeSeconds);
        response.put("generatedAt", Instant.now().toString());
        ArrayNode monitorsArray = response.putArray("monitors");

        for (Monitor m : all.values()) {
            ObjectNode entry = monitorsArray.addObject();
            String id = m.getId();
            entry.put("id", id);
            entry.put("name", m.getName() != null ? m.getName() : id);
            entry.put("endpoint", m.getTarget() != null ? m.getTarget() : "");
            // handle multiple targets: show as comma
            if (m.getTargets() != null && !m.getTargets().isBlank()) {
                entry.put("endpoint", m.getTargets());
            }
            entry.put("type", m.getType() != null ? m.getType() : "http");
            entry.put("interval", m.getInterval() != null ? m.getInterval() : DEFAULT_INTERVAL);
            entry.put("enabled", !Boolean.FALSE.equals(m.getEnabled()));
            String job = jobName(id);
            // status
            if (Boolean.FALSE.equals(m.getEnabled())) {
                entry.put("status", "paused");
            } else {
                Double success = successMap.get(job);
                if (success == null) entry.put("status", "pending");
                else if (success == 1.0) entry.put("status", "up");
                else entry.put("status", "down");
            }
            // response time
            Double duration = durationMap.get(job);
            if (duration != null) entry.put("responseTime", duration);
            else entry.putNull("responseTime");
            // uptime
            Double uptime = uptimeMap.get(job);
            if (uptime != null && !uptime.isNaN()) {
                double pct = uptime * 100.0;
                entry.put("uptime", Math.round(pct * 100.0) / 100.0);
            } else if (Boolean.FALSE.equals(m.getEnabled())) {
                entry.putNull("uptime");
            } else {
                // fallback compute from history if avg_over_time not available
                List<ValuePoint> pts = historyMap.get(job);
                if (pts != null && !pts.isEmpty()) {
                    long up = pts.stream().filter(p -> p.value() == 1.0).count();
                    double pct = (up * 100.0) / pts.size();
                    entry.put("uptime", Math.round(pct * 100.0) / 100.0);
                } else {
                    entry.putNull("uptime");
                }
            }
            // last fail / success from history
            List<ValuePoint> history = historyMap.get(job);
            Long lastFail = null, lastSuccess = null;
            if (history != null) {
                for (int i = history.size() - 1; i >= 0; i--) {
                    ValuePoint p = history.get(i);
                    if (lastFail == null && p.value() == 0.0) lastFail = p.timestamp();
                    if (lastSuccess == null && p.value() == 1.0) lastSuccess = p.timestamp();
                    if (lastFail != null && lastSuccess != null) break;
                }
            }
            if (lastFail != null) entry.put("lastFail", lastFail);
            else entry.putNull("lastFail");
            if (lastSuccess != null) entry.put("lastSuccess", lastSuccess);
            else entry.putNull("lastSuccess");
            // ssl expiry
            Double sslExpiry = sslExpiryMap.get(job);
            if (sslExpiry != null && !sslExpiry.isNaN() && sslExpiry > 0) {
                entry.put("sslExpiresAt", (long) Math.floor(sslExpiry));
                double days = (sslExpiry - now) / 86400.0;
                entry.put("sslExpiresInDays", Math.round(days * 10.0) / 10.0);
                entry.put("sslExpiresIn", formatSslExpiry(days));
            } else {
                entry.putNull("sslExpiresAt");
                entry.putNull("sslExpiresInDays");
                entry.putNull("sslExpiresIn");
            }
        }
        return response.toString();
    }

    private String computeStep(long rangeSeconds) {
        long step = Math.max(15, rangeSeconds / 50);
        if (step < 60) return step + "s";
        if (step % 3600 == 0) return (step / 3600) + "h";
        if (step % 60 == 0) return (step / 60) + "m";
        return step + "s";
    }

    private String formatSslExpiry(double days) {
        if (days < 0) return "expired";
        if (days < 1) return Math.round(days * 24) + "h";
        if (days < 30) return Math.round(days) + "d";
        if (days < 365) return Math.round(days / 30) + "mo";
        return Math.round(days / 365) + "y";
    }

    private Map<String, Double> querySslExpiry(String jobRegex) {
        String q = "probe_ssl_earliest_cert_expiry{job=~\"" + jobRegex + "\"}";
        return queryInstantVector(q);
    }

    private Map<String, Double> queryUptime(String jobRegex, String range) {
        String q = "avg_over_time(probe_success{job=~\"" + jobRegex + "\"}[" + range + "])";
        return queryInstantVector(q);
    }

    private Map<String, List<ValuePoint>> queryRange(String promQL, long startSec, long endSec, String step) {
        Map<String, List<ValuePoint>> result = new LinkedHashMap<>();
        try {
            String url = PROMETHEUS_QUERY_RANGE_URL + URLEncoder.encode(promQL, StandardCharsets.UTF_8)
                    + "&start=" + startSec + "&end=" + endSec + "&step=" + step;
            HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return result;
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode results = root.path("data").path("result");
            if (!results.isArray()) return result;
            for (JsonNode series : results) {
                String job = series.path("metric").path("job").asText();
                if (job.isEmpty()) continue;
                JsonNode values = series.path("values");
                if (!values.isArray()) continue;
                List<ValuePoint> points = new ArrayList<>();
                for (JsonNode v : values) {
                    if (!v.isArray() || v.size() < 2) continue;
                    long ts = (long) Double.parseDouble(v.get(0).asText());
                    double val;
                    try {
                        val = Double.parseDouble(v.get(1).asText());
                    } catch (NumberFormatException ex) {
                        continue;
                    }
                    points.add(new ValuePoint(ts, val));
                }
                result.put(job, points);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static record ValuePoint(long timestamp, double value) {}

    // ---------- Internals ----------

    private void ensureArtifacts(Monitor monitor) throws IOException, InterruptedException {
        if (Boolean.FALSE.equals(monitor.getEnabled())) {
            removeArtifacts(monitor.getId(), true);
            return;
        }
        // 1. blackbox module
        String module = moduleName(monitor.getId());
        ObjectNode modules = blackBoxConfigLoder.getBlackboxModules();
        ObjectNode moduleConfig = buildModuleConfig(monitor);
        modules.set(module, moduleConfig);
        blackBoxConfigLoder.saveBlackboxConfig();
        // reload blackbox but don't fail hard if not running - we continue to prometheus steps
        try { blackBoxConfigLoder.reloadBlackboxExporter(); } catch (Exception ignored) {}

        // 2. prometheus job
        String job = jobName(monitor.getId());
        ArrayNode scrapeConfigs = prometheusConfigLoader.getScrapeConfigs();
        ObjectNode jobConfig = buildJobConfig(monitor, module, job);
        int idx = indexOfJob(scrapeConfigs, job);
        if (idx >= 0) {
            scrapeConfigs.set(idx, jobConfig);
        } else {
            scrapeConfigs.add(jobConfig);
        }

        // 3. rule file
        String ruleFile = ruleFileName(monitor.getId());
        ObjectNode ruleContent = buildRuleFileContent(monitor, job);
        prometheusConfigLoader.saveRuleFile(ruleFile, ruleContent);

        // 4. persist prometheus config and reload/start
        applyPrometheusRuntime();

        // 5. alertmanager slack webhook (simple alerting: slack after n retries)
        String slackUrl = monitor.getSlackWebhookUrl();
        if (slackUrl != null && !slackUrl.isBlank()) {
            ensureSlackReceiverAndRoute(monitor);
        } else {
            removeSlackReceiverAndRoute(monitor.getId());
        }
    }

    private void removeArtifacts(String monitorId, boolean reload) throws IOException, InterruptedException {
        String module = moduleName(monitorId);
        String job = jobName(monitorId);
        String ruleFile = ruleFileName(monitorId);

        // remove module if exists and not default
        try {
            ObjectNode modules = blackBoxConfigLoder.getBlackboxModules();
            if (modules.has(module)) {
                modules.remove(module);
                blackBoxConfigLoder.saveBlackboxConfig();
                if (reload) blackBoxConfigLoder.reloadBlackboxExporter();
            }
            // cleanup TLS material for monitor module
            try { blackBoxConfigLoder.deleteTlsMaterial(module); } catch (Exception ignored) {}
        } catch (Exception ignored) {}

        // remove job
        try {
            ArrayNode scrapeConfigs = prometheusConfigLoader.getScrapeConfigs();
            int idx = indexOfJob(scrapeConfigs, job);
            if (idx >= 0) {
                // ensure at least one job remains - if this is last job, we will keep self job
                // removing monitor job never leaves zero because self job exists; but if user removed self? Ensure not zero.
                // we allow removal; ensureSelf will recreate self if needed after removal.
                scrapeConfigs.remove(idx);
            }
        } catch (Exception ignored) {}

        // remove rule file if exists
        try {
            if (prometheusConfigLoader.hasRuleFile(ruleFile)) {
                prometheusConfigLoader.deleteRuleFile(ruleFile);
            }
        } catch (Exception ignored) {}

        // remove slack alerting if any
        try {
            removeSlackReceiverAndRoute(monitorId);
        } catch (Exception ignored) {}

        if (reload) {
            applyPrometheusRuntime();
        }
    }

    private ObjectNode buildModuleConfig(Monitor m) {
        ObjectNode mod = objectMapper.createObjectNode();
        String type = m.getType() != null ? m.getType().toLowerCase() : "http";
        String timeout = m.getTimeout() != null ? m.getTimeout() : DEFAULT_TIMEOUT;
        if (!DURATION_PATTERN.matcher(timeout).matches()) timeout = DEFAULT_TIMEOUT;

        String preferredIp = m.getPreferredIpProtocol() != null ? m.getPreferredIpProtocol() : "ip4";
        if (!"ip4".equals(preferredIp) && !"ip6".equals(preferredIp)) preferredIp = "ip4";
        boolean fallback = m.getIpProtocolFallback() == null ? true : m.getIpProtocolFallback();

        java.util.List<String> httpVersions = m.getValidHttpVersions();
        if (httpVersions == null || httpVersions.isEmpty()) {
            httpVersions = java.util.List.of("HTTP/1.1", "HTTP/2.0");
        }

        boolean useTls = Boolean.TRUE.equals(m.getTlsEnabled());
        String tlsServerName = m.getTlsServerName();

        if ("keyword".equals(type)) {
            mod.put("prober", "http");
            mod.put("timeout", timeout);
            ObjectNode http = objectMapper.createObjectNode();
            http.put("method", m.getMethod() != null ? m.getMethod() : "GET");
            http.put("preferred_ip_protocol", preferredIp);
            http.put("ip_protocol_fallback", fallback);
            if (m.getExpectedStatusCodes() != null && !m.getExpectedStatusCodes().isEmpty()) {
                http.set("valid_status_codes", objectMapper.valueToTree(m.getExpectedStatusCodes()));
            }
            ArrayNode versions = http.putArray("valid_http_versions");
            httpVersions.forEach(versions::add);
            if (Boolean.FALSE.equals(m.getFollowRedirects())) http.put("follow_redirects", false);
            else if (m.getFollowRedirects() != null) http.put("follow_redirects", true);
            if (m.getBody() != null && !m.getBody().isBlank()) {
                http.put("body", m.getBody());
                if (m.getContentType() != null) {
                    ObjectNode headers = objectMapper.createObjectNode();
                    headers.put("Content-Type", m.getContentType());
                    http.set("headers", headers);
                }
            }
            String kw = m.getKeyword();
            if (kw != null && !kw.isBlank()) {
                if (Boolean.TRUE.equals(m.getKeywordInverted())) {
                    http.set("fail_if_body_matches_regexp", objectMapper.createArrayNode().add(kw));
                } else {
                    http.set("fail_if_body_not_matches_regexp", objectMapper.createArrayNode().add(kw));
                }
            }
            if (useTls) {
                ObjectNode tls = http.putObject("tls_config");
                if (tlsServerName != null && !tlsServerName.isBlank()) tls.put("server_name", tlsServerName);
                if (m.getTlsCaFile() != null && !m.getTlsCaFile().isBlank()) tls.put("ca_file", m.getTlsCaFile());
                if (m.getTlsCertFile() != null && !m.getTlsCertFile().isBlank()) tls.put("cert_file", m.getTlsCertFile());
                if (m.getTlsKeyFile() != null && !m.getTlsKeyFile().isBlank()) tls.put("key_file", m.getTlsKeyFile());
                if (tls.isEmpty()) http.remove("tls_config");
            }
            mod.set("http", http);
        } else if ("http".equals(type) || "https".equals(type)) {
            mod.put("prober", "http");
            mod.put("timeout", timeout);
            ObjectNode http = objectMapper.createObjectNode();
            http.put("method", m.getMethod() != null ? m.getMethod() : "GET");
            http.put("preferred_ip_protocol", preferredIp);
            http.put("ip_protocol_fallback", fallback);
            if (m.getExpectedStatusCodes() != null && !m.getExpectedStatusCodes().isEmpty()) {
                http.set("valid_status_codes", objectMapper.valueToTree(m.getExpectedStatusCodes()));
            }
            ArrayNode versions = http.putArray("valid_http_versions");
            httpVersions.forEach(versions::add);
            if (Boolean.FALSE.equals(m.getFollowRedirects())) http.put("follow_redirects", false);
            else if (m.getFollowRedirects() != null) http.put("follow_redirects", true);
            if (m.getBody() != null && !m.getBody().isBlank()) {
                http.put("body", m.getBody());
                if (m.getContentType() != null) {
                    ObjectNode headers = objectMapper.createObjectNode();
                    headers.put("Content-Type", m.getContentType());
                    http.set("headers", headers);
                }
            }
            if (useTls) {
                ObjectNode tls = http.putObject("tls_config");
                if (tlsServerName != null && !tlsServerName.isBlank()) tls.put("server_name", tlsServerName);
                if (m.getTlsCaFile() != null && !m.getTlsCaFile().isBlank()) tls.put("ca_file", m.getTlsCaFile());
                if (m.getTlsCertFile() != null && !m.getTlsCertFile().isBlank()) tls.put("cert_file", m.getTlsCertFile());
                if (m.getTlsKeyFile() != null && !m.getTlsKeyFile().isBlank()) tls.put("key_file", m.getTlsKeyFile());
                if (tls.isEmpty()) http.remove("tls_config");
            }
            mod.set("http", http);
        } else if ("tcp".equals(type)) {
            mod.put("prober", "tcp");
            mod.put("timeout", timeout);
            ObjectNode tcp = objectMapper.createObjectNode();
            if (useTls) {
                ObjectNode tls = tcp.putObject("tls_config");
                if (tlsServerName != null && !tlsServerName.isBlank()) tls.put("server_name", tlsServerName);
                if (m.getTlsCaFile() != null && !m.getTlsCaFile().isBlank()) tls.put("ca_file", m.getTlsCaFile());
                if (m.getTlsCertFile() != null && !m.getTlsCertFile().isBlank()) tls.put("cert_file", m.getTlsCertFile());
                if (m.getTlsKeyFile() != null && !m.getTlsKeyFile().isBlank()) tls.put("key_file", m.getTlsKeyFile());
                if (tls.isEmpty()) tcp.remove("tls_config");
            }
            mod.set("tcp", tcp);
        } else if ("icmp".equals(type) || "ping".equals(type)) {
            mod.put("prober", "icmp");
            mod.put("timeout", timeout);
            ObjectNode icmp = objectMapper.createObjectNode();
            icmp.put("dont_fragment", true);
            mod.set("icmp", icmp);
        } else {
            mod.put("prober", "http");
            mod.put("timeout", timeout);
            ObjectNode http = objectMapper.createObjectNode();
            http.put("method", "GET");
            http.put("preferred_ip_protocol", preferredIp);
            http.put("ip_protocol_fallback", fallback);
            mod.set("http", http);
        }
        return mod;
    }

    private ObjectNode buildJobConfig(Monitor m, String moduleName, String jobNameValue) {
        ObjectNode job = objectMapper.createObjectNode();
        job.put("job_name", jobNameValue);
        String interval = m.getInterval() != null ? m.getInterval() : DEFAULT_INTERVAL;
        if (!DURATION_PATTERN.matcher(interval).matches()) interval = DEFAULT_INTERVAL;
        job.put("scrape_interval", interval);
        String timeout = m.getTimeout() != null ? m.getTimeout() : DEFAULT_TIMEOUT;
        if (DURATION_PATTERN.matcher(timeout).matches()) job.put("scrape_timeout", timeout);
        job.put("metrics_path", "/probe");
        ObjectNode params = job.putObject("params");
        params.set("module", objectMapper.createArrayNode().add(moduleName));
        ArrayNode staticConfigs = job.putArray("static_configs");
        ObjectNode sc = objectMapper.createObjectNode();
        java.util.List<String> targets = parseTargets(m);
        ArrayNode targetArray = objectMapper.createArrayNode();
        for (String t : targets) targetArray.add(t);
        if (targetArray.isEmpty()) targetArray.add(m.getTarget() != null ? m.getTarget() : "");
        sc.set("targets", targetArray);
        ObjectNode labels = sc.putObject("labels");
        labels.put("monitor", m.getId());
        if (m.getLabels() != null) {
            for (Map.Entry<String, String> e : m.getLabels().entrySet()) {
                labels.put(e.getKey(), e.getValue());
            }
        }
        staticConfigs.add(sc);
        ArrayNode relabel = job.putArray("relabel_configs");
        ObjectNode r1 = objectMapper.createObjectNode();
        r1.set("source_labels", objectMapper.createArrayNode().add("__address__"));
        r1.put("target_label", "__param_target");
        relabel.add(r1);
        ObjectNode r2 = objectMapper.createObjectNode();
        r2.set("source_labels", objectMapper.createArrayNode().add("__param_target"));
        r2.put("target_label", "instance");
        relabel.add(r2);
        ObjectNode r3 = objectMapper.createObjectNode();
        r3.put("target_label", "__address__");
        r3.put("replacement", "localhost:9115");
        relabel.add(r3);
        return job;
    }

    private java.util.List<String> parseTargets(Monitor m) {
        String raw = m.getTargets() != null && !m.getTargets().isBlank() ? m.getTargets() : m.getTarget();
        if (raw == null || raw.isBlank()) return java.util.Collections.emptyList();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private ObjectNode buildRuleFileContent(Monitor m, String jobNameValue) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode groups = root.putArray("groups");
        ObjectNode group = groups.addObject();
        group.put("name", "observecMonitor_" + sanitize(m.getId()));
        ArrayNode rules = group.putArray("rules");
        ObjectNode rule = rules.addObject();
        rule.put("alert", "MonitorDown_" + sanitize(m.getId()));
        rule.put("expr", "probe_success{job=\"" + jobNameValue + "\"} == 0");
        String forDuration = computeForDuration(m);
        rule.put("for", forDuration);
        ObjectNode labels = rule.putObject("labels");
        labels.put("severity", "critical");
        labels.put("monitor", m.getId());
        if (m.getReceiver() != null && !m.getReceiver().isBlank()) {
            // store receiver as label for routing; user can create route matching monitor label
            labels.put("receiver", m.getReceiver());
        }
        ObjectNode annotations = rule.putObject("annotations");
        String name = m.getName() != null ? m.getName() : m.getId();
        annotations.put("summary", "Monitor " + name + " down");
        annotations.put("description", "Target " + m.getTarget() + " (" + m.getType() + ") is down");
        return root;
    }

    private String computeForDuration(Monitor m) {
        int retries = m.getRetries() != null ? m.getRetries() : 1;
        if (retries < 0) retries = 0;
        String interval = m.getInterval() != null ? m.getInterval() : DEFAULT_INTERVAL;
        long sec = parseDurationSeconds(interval);
        if (sec <= 0) sec = 60;
        long forSec = sec * retries;
        if (forSec == 0) return "0s";
        // pretty: if divisible by 60, use m
        if (forSec % 3600 == 0) return (forSec / 3600) + "h";
        if (forSec % 60 == 0) return (forSec / 60) + "m";
        return forSec + "s";
    }

    private long parseDurationSeconds(String duration) {
        if (duration == null || duration.isBlank()) return 60;
        duration = duration.trim();
        try {
            if (duration.endsWith("ms")) {
                return Long.parseLong(duration.substring(0, duration.length() - 2)) / 1000;
            } else if (duration.endsWith("s")) {
                return Long.parseLong(duration.substring(0, duration.length() - 1));
            } else if (duration.endsWith("m")) {
                return Long.parseLong(duration.substring(0, duration.length() - 1)) * 60;
            } else if (duration.endsWith("h")) {
                return Long.parseLong(duration.substring(0, duration.length() - 1)) * 3600;
            } else if (duration.endsWith("d")) {
                return Long.parseLong(duration.substring(0, duration.length() - 1)) * 86400;
            } else if (duration.endsWith("w")) {
                return Long.parseLong(duration.substring(0, duration.length() - 1)) * 604800;
            }
        } catch (NumberFormatException ignored) {}
        return 60;
    }

    private void applyPrometheusRuntime() throws IOException, InterruptedException {
        prometheusConfigLoader.ensureSelfScrapeJob();
        prometheusConfigLoader.syncRuleFilesConfig();
        prometheusConfigLoader.savePrometheusConfig();
        if (isPrometheusHealthy()) {
            prometheusConfigLoader.reloadPrometheus();
        } else {
            prometheusConfigLoader.startPrometheus();
            waitForHealthy(20, 500);
        }
    }

    private boolean isPrometheusHealthy() {
        try {
            HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder(URI.create(PROMETHEUS_HEALTH_URL))
                            .timeout(Duration.ofSeconds(3))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void waitForHealthy(int attempts, long delayMillis) throws InterruptedException {
        for (int i = 0; i < attempts; i++) {
            if (isPrometheusHealthy()) return;
            Thread.sleep(delayMillis);
        }
    }

    private Map<String, Double> queryProbeSuccess(String jobMatcher) {
        String query = "probe_success{job=~\"" + jobMatcher + "\"}";
        return queryInstantVector(query);
    }

    private Map<String, Double> queryProbeDuration(String jobMatcher) {
        String query = "probe_duration_seconds{job=~\"" + jobMatcher + "\"}";
        return queryInstantVector(query);
    }

    private Map<String, Double> queryInstantVector(String promQL) {
        Map<String, Double> result = new LinkedHashMap<>();
        try {
            String url = PROMETHEUS_QUERY_URL + URLEncoder.encode(promQL, StandardCharsets.UTF_8);
            HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return result;
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode results = root.path("data").path("result");
            if (!results.isArray()) return result;
            for (JsonNode item : results) {
                String job = item.path("metric").path("job").asText();
                JsonNode value = item.path("value");
                if (job.isEmpty() || !value.isArray() || value.size() < 2) continue;
                double v = Double.parseDouble(value.get(1).asText());
                result.put(job, v);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private ArrayNode fetchHeartbeats(Monitor monitor, int limit) {
        int intervalSec = (int) parseDurationSeconds(monitor.getInterval() != null ? monitor.getInterval() : DEFAULT_INTERVAL);
        if (intervalSec <= 0) intervalSec = 60;
        long end = Instant.now().getEpochSecond();
        long start = end - (long) intervalSec * limit;
        String step = intervalSec + "s";
        String job = jobName(monitor.getId());
        String query = "probe_success{job=\"" + job + "\"}";
        ArrayNode beats = objectMapper.createArrayNode();
        try {
            String url = PROMETHEUS_QUERY_RANGE_URL + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&start=" + start + "&end=" + end + "&step=" + step;
            HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return beats;
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode results = root.path("data").path("result");
            if (!results.isArray() || results.size() == 0) return beats;
            JsonNode values = results.get(0).path("values");
            if (!values.isArray()) return beats;
            // values is [[timestamp, "1"], ...]
            for (JsonNode pair : values) {
                if (!pair.isArray() || pair.size() < 2) continue;
                long ts = (long) Double.parseDouble(pair.get(0).asText());
                double val = Double.parseDouble(pair.get(1).asText());
                ObjectNode beat = beats.addObject();
                beat.put("time", ts);
                beat.put("value", val);
            }
            // if fewer than limit and prometheus has sparse data, we already limited; if more, trim to limit newest
            if (beats.size() > limit) {
                ArrayNode trimmed = objectMapper.createArrayNode();
                for (int i = beats.size() - limit; i < beats.size(); i++) trimmed.add(beats.get(i));
                return trimmed;
            }
        } catch (Exception ignored) {}
        return beats;
    }

    private String jobName(String id) { return JOB_PREFIX + sanitize(id); }
    private String moduleName(String id) { return MODULE_PREFIX + sanitize(id); }
    private String ruleFileName(String id) { return RULE_DIR + "/" + sanitize(id) + ".yml"; }

    private String sanitize(String id) {
        if (id == null) return "unknown";
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private String slugify(String value) {
        if (value == null) return "";
        String s = value.toLowerCase().trim()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.length() > 50) s = s.substring(0, 50);
        return s;
    }

    private String uniqueId(String base) {
        String cand = base;
        int n = 1;
        while (monitorStore.contains(cand)) {
            cand = base + "-" + n;
            n++;
            if (n > 100) cand = base + "-" + System.currentTimeMillis();
        }
        return cand;
    }

    private void validateId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid monitor id : " + id);
        }
    }

    private void validateMonitor(Monitor m, boolean isCreate) {
        String rawTarget = (m.getTargets() != null && !m.getTargets().isBlank()) ? m.getTargets() : m.getTarget();
        if (rawTarget == null || rawTarget.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "monitor target is required");
        }
        java.util.List<String> targetList = parseTargets(m);
        if (targetList.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "monitor target is required");
        }
        for (String t : targetList) {
            if (t.contains(" ")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "monitor target must not contain spaces : " + t);
            }
        }
        String type = m.getType() != null ? m.getType().toLowerCase() : "http";
        if (!List.of("http", "https", "tcp", "icmp", "ping", "keyword").contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid monitor type : " + type + " (use http, tcp, icmp, keyword)");
        }
        if (m.getInterval() != null && !DURATION_PATTERN.matcher(m.getInterval()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid interval : " + m.getInterval());
        }
        if (m.getTimeout() != null && !DURATION_PATTERN.matcher(m.getTimeout()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid timeout : " + m.getTimeout());
        }
        if (m.getRetries() != null && (m.getRetries() < 0 || m.getRetries() > 10)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "retries must be 0-10");
        }
        if (m.getMethod() != null && !List.of("GET","POST","PUT","HEAD","DELETE","OPTIONS","PATCH").contains(m.getMethod().toUpperCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid method : " + m.getMethod());
        }
        if (m.getExpectedStatusCodes() != null) {
            for (Integer c : m.getExpectedStatusCodes()) {
                if (c < 100 || c > 599) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status code : " + c);
            }
        }
        if ("keyword".equals(type) && (m.getKeyword() == null || m.getKeyword().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "keyword is required for keyword monitors");
        }
        if (m.getReceiver() != null && !m.getReceiver().isBlank()) {
            if (alertmanagerConfigLoader.indexOfReceiver(m.getReceiver()) < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "receiver does not exist : " + m.getReceiver());
            }
        }
        if (m.getSlackWebhookUrl() != null && !m.getSlackWebhookUrl().isBlank()) {
            String url = m.getSlackWebhookUrl().trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "slack webhook must be http(s) url");
            }
        }
        if (m.getPreferredIpProtocol() != null && !"ip4".equals(m.getPreferredIpProtocol()) && !"ip6".equals(m.getPreferredIpProtocol())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "preferred_ip_protocol must be ip4 or ip6");
        }
        if (m.getName() != null && m.getName().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name too long (max 100)");
        }
    }

    private void applyDefaults(Monitor m) {
        if (m.getType() == null || m.getType().isBlank()) m.setType("http");
        else m.setType(m.getType().toLowerCase());
        if (m.getName() == null || m.getName().isBlank()) m.setName(m.getId());
        if (m.getInterval() == null || m.getInterval().isBlank()) m.setInterval(DEFAULT_INTERVAL);
        if (m.getTimeout() == null || m.getTimeout().isBlank()) m.setTimeout(DEFAULT_TIMEOUT);
        if (m.getRetries() == null) m.setRetries(1);
        if (m.getEnabled() == null) m.setEnabled(true);
        if (m.getMethod() == null && ("http".equals(m.getType()) || "keyword".equals(m.getType()))) m.setMethod("GET");
        if (m.getFollowRedirects() == null) m.setFollowRedirects(true);
        if (m.getKeywordInverted() == null) m.setKeywordInverted(false);
        if (m.getPreferredIpProtocol() == null || m.getPreferredIpProtocol().isBlank()) m.setPreferredIpProtocol("ip4");
        if (m.getIpProtocolFallback() == null) m.setIpProtocolFallback(true);
        if (m.getTlsEnabled() == null) m.setTlsEnabled(false);
        if (m.getReceiver() == null || m.getReceiver().isBlank()) m.setReceiver(AlertmanagerConfigLoader.DEFAULT_RECEIVER);
        // normalize empty slack url to null
        if (m.getSlackWebhookUrl() != null && m.getSlackWebhookUrl().isBlank()) m.setSlackWebhookUrl(null);
        if (m.getSlackChannel() != null && m.getSlackChannel().isBlank()) m.setSlackChannel(null);
    }

    private Monitor mapToMonitor(ObjectNode obj) {
        try {
            // use ObjectMapper to convert
            Monitor m = objectMapper.treeToValue(obj, Monitor.class);
            // handle alias: if payload has friendly fields like url instead of target
            if (m.getTarget() == null && obj.has("url")) m.setTarget(obj.get("url").asText());
            if (m.getTarget() == null && obj.has("host")) m.setTarget(obj.get("host").asText());
            if (obj.has("notificationReceiver") && m.getReceiver() == null) m.setReceiver(obj.get("notificationReceiver").asText());
            return m;
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid monitor payload", e);
        }
    }

    private Monitor mergeMonitor(Monitor existing, ObjectNode updates) {
        try {
            // serialize existing to node then merge updates
            ObjectNode base = objectMapper.valueToTree(existing);
            // remove id/createdAt from updates if present (we handle separately)
            ObjectNode copy = updates.deepCopy();
            copy.remove("id");
            copy.remove("createdAt");
            // handle url alias
            if (copy.has("url") && !copy.has("target")) {
                copy.set("target", copy.get("url"));
                copy.remove("url");
            }
            base.setAll(copy);
            return objectMapper.treeToValue(base, Monitor.class);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid monitor update payload", e);
        }
    }

    private JsonNode parseJsonBody(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid json body", e);
        }
    }

    private int indexOfJob(ArrayNode scrapeConfigs, String jobName) {
        for (int i = 0; i < scrapeConfigs.size(); i++) {
            if (jobName.equals(scrapeConfigs.get(i).path("job_name").asText())) return i;
        }
        return -1;
    }

    private String slackReceiverName(String monitorId) {
        return SLACK_RECEIVER_PREFIX + sanitize(monitorId);
    }

    private void ensureSlackReceiverAndRoute(Monitor monitor) throws IOException, InterruptedException {
        String monitorId = monitor.getId();
        String slackUrl = monitor.getSlackWebhookUrl();
        if (slackUrl == null || slackUrl.isBlank()) return;
        String receiverName = slackReceiverName(monitorId);
        ArrayNode receivers = alertmanagerConfigLoader.getReceivers();
        int idx = alertmanagerConfigLoader.indexOfReceiver(receiverName);
        ObjectNode receiver;
        if (idx < 0) {
            receiver = objectMapper.createObjectNode();
            receiver.put("name", receiverName);
            receivers.add(receiver);
            idx = receivers.size() - 1;
        } else {
            receiver = (ObjectNode) receivers.get(idx);
        }
        ArrayNode slackConfigs = receiver.withArray("slack_configs");
        ObjectNode slackConfig;
        if (slackConfigs.isEmpty()) {
            slackConfig = objectMapper.createObjectNode();
            slackConfigs.add(slackConfig);
        } else {
            slackConfig = (ObjectNode) slackConfigs.get(0);
        }
        slackConfig.put("api_url", slackUrl.trim());
        slackConfig.put("send_resolved", true);
        if (monitor.getSlackChannel() != null && !monitor.getSlackChannel().isBlank()) {
            slackConfig.put("channel", monitor.getSlackChannel().trim());
        } else {
            slackConfig.remove("channel");
        }
        // optional: set title/text to include monitor name
        // keep existing if present, else set defaults
        if (!slackConfig.has("title")) {
            slackConfig.put("title", "{{ .GroupLabels.alertname }} - " + (monitor.getName() != null ? monitor.getName() : monitorId));
        }
        if (!slackConfig.has("text")) {
            slackConfig.put("text", "{{ range .Alerts }}{{ .Annotations.summary }} - {{ .Annotations.description }}{{ end }}");
        }

        // ensure route for this monitor
        ObjectNode rootRoute = alertmanagerConfigLoader.getRoute();
        ArrayNode childRoutes = rootRoute.withArray("routes");
        String matcher = "monitor = \"" + monitorId + "\"";
        boolean found = false;
        for (JsonNode child : childRoutes) {
            JsonNode matchers = child.path("matchers");
            if (matchers.isArray()) {
                for (JsonNode m : matchers) {
                    if (matcher.equals(m.asText())) {
                        ((ObjectNode) child).put("receiver", receiverName);
                        found = true;
                        break;
                    }
                }
            }
            if (found) break;
        }
        if (!found) {
            ObjectNode child = objectMapper.createObjectNode();
            child.put("receiver", receiverName);
            ArrayNode matchers = child.putArray("matchers");
            matchers.add(matcher);
            // continue false so slack is exclusive? but allow other routes? Keep continue true so default also fires if needed
            child.put("continue", true);
            childRoutes.add(child);
        }
        alertmanagerConfigLoader.applyAlertmanagerRuntime();
    }

    private void removeSlackReceiverAndRoute(String monitorId) throws IOException, InterruptedException {
        String receiverName = slackReceiverName(monitorId);
        ArrayNode receivers = alertmanagerConfigLoader.getReceivers();
        int idx = alertmanagerConfigLoader.indexOfReceiver(receiverName);
        boolean changed = false;
        if (idx >= 0 && !alertmanagerConfigLoader.isProtectedReceiver(receiverName)) {
            receivers.remove(idx);
            changed = true;
        }
        ObjectNode rootRoute = alertmanagerConfigLoader.getRoute();
        ArrayNode childRoutes = rootRoute.withArray("routes");
        String matcher = "monitor = \"" + monitorId + "\"";
        for (int i = childRoutes.size() - 1; i >= 0; i--) {
            JsonNode child = childRoutes.get(i);
            JsonNode matchers = child.path("matchers");
            if (matchers.isArray()) {
                for (JsonNode m : matchers) {
                    if (matcher.equals(m.asText())) {
                        childRoutes.remove(i);
                        changed = true;
                        break;
                    }
                }
            }
            // also handle legacy match/match_re
            JsonNode match = child.path("match");
            if (match.isObject() && monitorId.equals(match.path("monitor").asText())) {
                childRoutes.remove(i);
                changed = true;
            }
        }
        if (changed) {
            alertmanagerConfigLoader.applyAlertmanagerRuntime();
        }
    }

    private String successResponse(String message) {
        ObjectNode r = objectMapper.createObjectNode();
        r.put("status", "success");
        r.put("message", message);
        return r.toString();
    }
}
