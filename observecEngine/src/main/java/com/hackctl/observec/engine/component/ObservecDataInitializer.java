package com.hackctl.observec.engine.component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ObservecDataInitializer {

    public static String resolveBaseDir() {
        String env = System.getenv("OBSERVEC_DATA_DIR");
        if (env != null && !env.isBlank()) return env.trim();
        String prop = System.getProperty("observec.data-dir");
        if (prop != null && !prop.isBlank()) return prop.trim();
        return "/observec/observecData";
    }

    public static void ensureLayout() {
        ensureLayout(resolveBaseDir());
    }

    public static void ensureLayout(String base) {
        try {
            mkdirs(base);
            mkdirs(base + "/blackbox/blackboxData");
            mkdirs(base + "/blackbox/tls");
            mkdirs(base + "/prometheus/prometheusData");
            mkdirs(base + "/prometheus/serviceDiscoveryFiles");
            mkdirs(base + "/prometheus/ruleFiles");
            mkdirs(base + "/prometheus/kubeConfigs");
            mkdirs(base + "/alertmanager/alertmanagerData");

            removeDirIfEmpty(base + "/alertmanager/ruleFiles");
            mkdirs(base + "/monitors");
            mkdirs(base + "/prometheus/ruleFiles/monitors");
            mkdirs(base + "/thanos");

            Path db = Paths.get(base, "users.db");
            if (!Files.exists(db)) {
                Files.createFile(db);
            }

            ensureFile(base + "/blackbox/blackboxConfig.yml", defaultBlackboxConfig());
            ensureFile(base + "/prometheus/prometheusConfig.yml", defaultPrometheusConfig());
            ensureFile(base + "/alertmanager/alertmanagerConfig.yml", defaultAlertmanagerConfig());

            migrateBlackboxTls(base);
        } catch (Exception e) {
            System.err.println("[observec-init] ensureLayout failed: " + e.getMessage());
        }
    }

    private static void migrateBlackboxTls(String base) {
        try {
            Path oldDir = Paths.get(base, "blackbox/blackboxData/tls");
            Path newDir = Paths.get(base, "blackbox/tls");
            if (Files.isDirectory(oldDir)) {
                try (var stream = Files.walk(oldDir)) {
                    for (Path src : (Iterable<Path>) stream::iterator) {
                        if (Files.isDirectory(src)) continue;
                        Path rel = oldDir.relativize(src);
                        Path dst = newDir.resolve(rel);
                        if (Files.exists(dst)) continue;
                        Files.createDirectories(dst.getParent());
                        Files.move(src, dst);
                    }
                } catch (Exception e) {
                    System.err.println("[observec-init] tls move failed: " + e.getMessage());
                }
            }
            String oldSeg = "blackbox/blackboxData/tls";
            String newSeg = "blackbox/tls";
            rewritePathSegment(Paths.get(base, "blackbox/blackboxConfig.yml"), oldSeg, newSeg);
            rewritePathSegment(Paths.get(base, "monitors/monitors.json"), oldSeg, newSeg);
        } catch (Exception e) {
            System.err.println("[observec-init] tls migrate failed: " + e.getMessage());
        }
    }

    private static void rewritePathSegment(Path file, String oldSeg, String newSeg) {
        try {
            if (!Files.isRegularFile(file)) return;
            String content = Files.readString(file);
            if (!content.contains(oldSeg)) return;
            Files.writeString(file, content.replace(oldSeg, newSeg));
        } catch (Exception e) {
            System.err.println("[observec-init] rewrite " + file + " failed: " + e.getMessage());
        }
    }

    private static void removeDirIfEmpty(String dir) {
        try {
            Path p = Paths.get(dir);
            if (!Files.isDirectory(p)) return;
            try (var stream = Files.list(p)) {
                if (stream.findAny().isEmpty()) {
                    Files.delete(p);
                }
            }
        } catch (Exception e) {
            System.err.println("[observec-init] removeDirIfEmpty " + dir + " failed: " + e.getMessage());
        }
    }

    private static void mkdirs(String dir) throws IOException {
        Files.createDirectories(Paths.get(dir));
    }

    private static void ensureFile(String path, String content) throws IOException {
        File f = new File(path);
        if (f.exists()) return;
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
    }

    private static String defaultBlackboxConfig() {
        return "modules:\n"
                + "  observecBlackboxModuleDefault:\n"
                + "    prober: http\n"
                + "    timeout: 5s\n"
                + "    http:\n"
                + "      method: GET\n"
                + "      valid_status_codes:\n"
                + "        - 200\n"
                + "      valid_http_versions:\n"
                + "        - HTTP/1.0\n"
                + "        - HTTP/1.1\n"
                + "        - HTTP/2.0\n"
                + "      preferred_ip_protocol: ip4\n"
                + "      ip_protocol_fallback: true\n";
    }

    private static String defaultPrometheusConfig() {
        return "global:\n"
                + "  scrape_interval: 15s\n"
                + "  evaluation_interval: 15s\n"
                + "\n"
                + "rule_files: []\n"
                + "\n"
                + "alerting:\n"
                + "  alertmanagers:\n"
                + "    - static_configs:\n"
                + "        - targets:\n"
                + "          - localhost:9093\n"
                + "\n"
                + "scrape_configs:\n"
                + "\n"
                + "  - job_name: 'observecPrometheusJobSelf'\n"
                + "    scrape_interval: 1h\n"
                + "    scrape_timeout: 10s\n"
                + "    static_configs:\n"
                + "      - targets:\n"
                + "          - localhost:9090\n"
                + "    metric_relabel_configs:\n"
                + "      - source_labels: [__name__]\n"
                + "        regex: .+\n"
                + "        action: drop\n";
    }

    private static String defaultAlertmanagerConfig() {
        return "global:\n"
                + "  resolve_timeout: 5m\n"
                + "\n"
                + "route:\n"
                + "  receiver: observecAlertmanagerReceiverDefault\n"
                + "  group_by: ['alertname', 'instance']\n"
                + "  group_wait: 30s\n"
                + "  group_interval: 5m\n"
                + "  repeat_interval: 4h\n"
                + "  routes:\n"
                + "    - matchers:\n"
                + "        - severity = critical\n"
                + "      receiver: observecAlertmanagerReceiverDefault\n"
                + "      continue: true\n"
                + "    - matchers:\n"
                + "        - severity = warning\n"
                + "      receiver: observecAlertmanagerReceiverDefault\n"
                + "\n"
                + "inhibit_rules:\n"
                + "  - source_matchers:\n"
                + "      - severity = critical\n"
                + "    target_matchers:\n"
                + "      - severity = warning\n"
                + "    equal:\n"
                + "      - alertname\n"
                + "      - instance\n"
                + "\n"
                + "receivers:\n"
                + "  - name: observecAlertmanagerReceiverDefault\n"
                + "    webhook_configs:\n"
                + "      - url: 'http://127.0.0.1:8080/alertmanager/webhook'\n"
                + "        send_resolved: true\n";
    }
}
