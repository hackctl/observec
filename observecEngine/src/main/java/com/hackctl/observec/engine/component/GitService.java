package com.hackctl.observec.engine.component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hackctl.observec.engine.model.User;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class GitService {

    private final String repoPath;
    private final ObjectMapper objectMapper;

    private final File remoteConfigFile;

    @Autowired(required = false)
    @Lazy
    private UserStore userStore;

    public GitService(
            @Value("${observec.git.repo-path:/observec/observecData}") String repoPath,
            ObjectMapper objectMapper) {
        this.repoPath = repoPath;
        this.objectMapper = objectMapper;
        this.remoteConfigFile = new File(repoPath, ".git-remote.json");
    }

    private String[] currentUserInfo() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                HttpServletRequest req = sra.getRequest();
                HttpSession session = req.getSession(false);
                if (session != null) {
                    Object idObj = session.getAttribute("userId");
                    Long id = null;
                    if (idObj instanceof Long) id = (Long) idObj;
                    else if (idObj instanceof Integer) id = ((Integer) idObj).longValue();
                    else if (idObj instanceof String) {
                        try { id = Long.parseLong((String) idObj); } catch (NumberFormatException ignored) {}
                    }
                    if (id != null && userStore != null) {
                        User u = userStore.findById(id).orElse(null);
                        if (u != null) {
                            String name = u.getUsername();
                            String email = u.getEmail() != null && !u.getEmail().isBlank() ? u.getEmail() : name + "@observec.local";
                            return new String[]{name, email};
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        try {
            String n = runGitOutput("config", "--get", "user.name");
            String e = runGitOutput("config", "--get", "user.email");
            if (n != null && !n.isBlank() && e != null && !e.isBlank()) return new String[]{n.trim(), e.trim()};
        } catch (Exception ignored) {}
        return new String[]{"observec", "observec@local"};
    }

    @PostConstruct
    public void initRepo() {
        File repo = new File(repoPath);
        repo.mkdirs();
        try {
            if (!new File(repo, ".git").exists()) {
                runGit("init");
                ensureGitIgnore();
                ensureGitConfig();

                runGit("add", "-A");

                if (hasChangesToCommit()) {
                    runGit("commit", "-m", "chore: initial observec config " + Instant.now().toString());
                }
            } else {
                ensureGitIgnore();
                ensureGitConfig();
            }
        } catch (Exception e) {

            System.err.println("[GitService] init failed: " + e.getMessage());
        }
    }

    private void ensureGitIgnore() throws IOException {
        File gitignore = new File(repoPath, ".gitignore");
        String desired = ""
                + "# observec — only configs, tls, rule/sd files are tracked\n"
                + "# ignore prometheus metrics / binary data\n"
                + "prometheus/prometheusData/\n"
                + "alertmanager/alertmanagerData/\n"
                + "# logs\n"
                + "blackbox/blackbox.log\n"
                + "prometheus/prometheus.log\n"
                + "alertmanager/alertmanager.log\n"
                + "*.log\n"
                + "# users db (sqlite) — not config\n"
                + "users.db\n"
                + "users.db-journal\n"
                + "users.db-wal\n"
                + "users.db-shm\n"
                + "# git remote cache (contains token if stored)\n"
                + ".git-remote.json\n"
                + "# keep tls material but ignore empty dirs? track tls files\n"
                + "# allow all else (configs, ruleFiles, serviceDiscoveryFiles, tls, monitors)\n";
        if (!gitignore.exists()) {
            Files.writeString(gitignore.toPath(), desired);
        } else {
            String existing = Files.readString(gitignore.toPath());
            if (!existing.contains("prometheus/prometheusData/")) {
                Files.writeString(gitignore.toPath(), desired);
            }
        }

    }

    private void ensureGitConfig() throws IOException, InterruptedException {

        String name = runGitOutput("config", "--get", "user.name");
        if (name == null || name.isBlank()) {
            runGit("config", "user.name", "observec");
        }
        String email = runGitOutput("config", "--get", "user.email");
        if (email == null || email.isBlank()) {
            runGit("config", "user.email", "observec@local");
        }
    }

    private static final ThreadLocal<Integer> batchDepth = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Boolean> batchDirty = ThreadLocal.withInitial(() -> false);

    public synchronized void beginBatch() {
        batchDepth.set(batchDepth.get() + 1);
    }

    public synchronized void endBatch(String message) {
        int depth = batchDepth.get() - 1;
        if (depth < 0) depth = 0;
        batchDepth.set(depth);
        if (depth == 0) {
            boolean dirty = batchDirty.get();
            batchDirty.set(false);
            if (dirty) {
                doCommit(message);
            }
        }
    }

    public synchronized void abortBatch() {
        batchDepth.set(0);
        batchDirty.set(false);
    }

    public synchronized void autoCommit(String message) {
        if (batchDepth.get() > 0) {
            batchDirty.set(true);
            return;
        }
        doCommit(message);
    }

    private void doCommit(String message) {
        try {

            runGit("add", "-A");
            if (!hasChangesToCommit()) return;
            String safeMsg = (message == null || message.isBlank()) ? "chore: update observec config " + Instant.now() : message;

            String[] user = currentUserInfo();
            if (!safeMsg.contains("by ") && user[0] != null && !user[0].equals("observec")) {
                safeMsg = safeMsg + " (by " + user[0] + ")";
            }
            runGit("-c", "user.name=" + user[0], "-c", "user.email=" + user[1], "commit", "-m", safeMsg);
        } catch (Exception e) {
            System.err.println("[GitService] autoCommit failed: " + e.getMessage());
        }
    }

    public synchronized String applyCommit(String hash) {
        try {
            if (hash == null || hash.isBlank() || !hash.matches("[0-9a-fA-F]{7,40}")) {
                throw new IllegalArgumentException("invalid commit hash");
            }
            String originalHead = runGitOutput("rev-parse", "HEAD");
            if (originalHead == null || originalHead.isBlank()) throw new IOException("no HEAD");
            originalHead = originalHead.trim();
            if (originalHead.startsWith(hash) || hash.startsWith(originalHead)) {
                ObjectNode resp = objectMapper.createObjectNode();
                resp.put("status", "success");
                resp.put("message", "commit already at top: " + hash.substring(0,7));
                return resp.toString();
            }
            String msg = runGitOutput("log", "-1", "--pretty=format:%s", hash);
            if (msg == null || msg.isBlank()) msg = hash.substring(0,7);

            runGit("reset", "--hard", hash);
            runGit("reset", "--soft", originalHead);
            if (!hasChangesToCommit()) {

                ObjectNode resp = objectMapper.createObjectNode();
                resp.put("status", "success");
                resp.put("message", "no changes to apply from " + hash.substring(0,7));
                return resp.toString();
            }
            String[] user = currentUserInfo();
            String commitMsg = "chore: apply commit " + hash.substring(0,7) + " to top - " + msg + " (by " + user[0] + ")";
            runGit("-c", "user.name=" + user[0], "-c", "user.email=" + user[1], "commit", "-m", commitMsg);
            ObjectNode resp = objectMapper.createObjectNode();
            resp.put("status", "success");
            resp.put("message", "applied commit " + hash.substring(0,7) + " to top");
            return resp.toString();
        } catch (Exception e) {

            try { String orig = runGitOutput("rev-parse", "ORIG_HEAD"); if (orig != null && !orig.isBlank()) runGit("reset", "--hard", orig.trim()); } catch (Exception ignored) {}
            ObjectNode err = objectMapper.createObjectNode();
            err.put("status", "error");
            err.put("message", "apply failed: " + e.getMessage());
            return err.toString();
        }
    }

    private boolean hasChangesToCommit() throws IOException, InterruptedException {

        Process p = gitProcess("diff", "--cached", "--quiet");
        int code = p.waitFor();
        return code != 0;
    }

    public String log() {
        try {

            String out = runGitOutput("log", "--pretty=format:%H%x1f%an%x1f%ae%x1f%aI%x1f%s%x1e", "-n", "100");
            if (out == null || out.isBlank()) {
                ObjectNode empty = objectMapper.createObjectNode();
                empty.putArray("commits");
                return empty.toString();
            }
            List<ObjectNode> commits = new ArrayList<>();
            String[] entries = out.split("\u001e");
            for (String entry : entries) {
                if (entry == null || entry.isBlank()) continue;
                String[] parts = entry.split("\u001f", -1);
                if (parts.length < 5) continue;
                ObjectNode c = objectMapper.createObjectNode();
                c.put("hash", parts[0].trim());
                c.put("shortHash", parts[0].trim().length() >= 7 ? parts[0].trim().substring(0,7) : parts[0].trim());
                c.put("author", parts[1].trim());
                c.put("email", parts[2].trim());
                c.put("date", parts[3].trim());
                c.put("message", parts[4].trim().replace("\n", " ").trim());
                commits.add(c);
            }
            ObjectNode resp = objectMapper.createObjectNode();
            ArrayNode arr = resp.putArray("commits");
            commits.forEach(arr::add);
            return resp.toString();
        } catch (Exception e) {
            ObjectNode err = objectMapper.createObjectNode();
            err.put("status", "error");
            err.put("message", "failed to read git log: " + e.getMessage());
            return err.toString();
        }
    }

    public String status() {
        try {
            String porcelain = runGitOutput("status", "--porcelain");
            String branch = runGitOutput("rev-parse", "--abbrev-ref", "HEAD");
            if (branch == null || branch.isBlank()) branch = "main";
            String remote = runGitOutput("remote", "get-url", "origin");
            ObjectNode resp = objectMapper.createObjectNode();
            resp.put("branch", branch.trim());
            resp.put("remote", remote != null ? remote.trim() : "");
            resp.put("porcelain", porcelain != null ? porcelain : "");
            resp.put("hasChanges", porcelain != null && !porcelain.isBlank());

            String last = runGitOutput("log", "-1", "--pretty=format:%H");
            resp.put("lastCommit", last != null ? last.trim() : "");
            return resp.toString();
        } catch (Exception e) {
            ObjectNode err = objectMapper.createObjectNode();
            err.put("status", "error");
            err.put("message", e.getMessage());
            return err.toString();
        }
    }

    public synchronized String push(String remoteUrl, String token, String username, String branch) {
        try {
            if (remoteUrl == null || remoteUrl.isBlank()) {
                throw new IllegalArgumentException("remoteUrl required");
            }
            String br = (branch == null || branch.isBlank()) ? "main" : branch.trim();
            String authUrl = buildAuthUrl(remoteUrl.trim(), token, username);

            String existing = runGitOutput("remote", "get-url", "origin");
            if (existing == null || existing.isBlank()) {
                runGit("remote", "add", "origin", authUrl);
            } else {
                runGit("remote", "set-url", "origin", authUrl);
            }

            saveRemoteConfig(remoteUrl.trim(), username, br);

            runGit("add", "-A");
            if (hasChangesToCommit()) {
                runGit("commit", "-m", "chore: auto-commit before push " + Instant.now());
            }

            String pushOut = runGitOutputWithError("push", "-u", "origin", br);

            if (token != null && !token.isBlank()) {
                pushOut = pushOut.replace(token, "***");
            }
            ObjectNode resp = objectMapper.createObjectNode();
            resp.put("status", "success");
            resp.put("message", "pushed to " + sanitizeUrl(remoteUrl) + " branch " + br);
            resp.put("output", pushOut);
            return resp.toString();
        } catch (Exception e) {
            ObjectNode err = objectMapper.createObjectNode();
            err.put("status", "error");
            err.put("message", "push failed: " + e.getMessage());
            return err.toString();
        }
    }

    public synchronized String pull(String remoteUrl, String token, String username, String branch) {
        try {
            String br = (branch == null || branch.isBlank()) ? "main" : branch.trim();
            if (remoteUrl != null && !remoteUrl.isBlank()) {
                String authUrl = buildAuthUrl(remoteUrl.trim(), token, username);
                String existing = runGitOutput("remote", "get-url", "origin");
                if (existing == null || existing.isBlank()) {
                    runGit("remote", "add", "origin", authUrl);
                } else {
                    runGit("remote", "set-url", "origin", authUrl);
                }
                saveRemoteConfig(remoteUrl.trim(), username, br);
            }
            String out = runGitOutputWithError("pull", "origin", br, "--allow-unrelated-histories");

            if (out != null && out.toLowerCase().contains("unknown option")) {
                out = runGitOutputWithError("pull", "origin", br);
            }
            ObjectNode resp = objectMapper.createObjectNode();
            resp.put("status", "success");
            resp.put("message", "pulled from origin/" + br);
            resp.put("output", out != null ? out : "");
            return resp.toString();
        } catch (Exception e) {
            ObjectNode err = objectMapper.createObjectNode();
            err.put("status", "error");
            err.put("message", "pull failed: " + e.getMessage());
            return err.toString();
        }
    }

    public String getRemoteConfig() {
        try {
            if (remoteConfigFile.exists()) {
                String json = Files.readString(remoteConfigFile.toPath());
                if (json != null && !json.isBlank()) {

                    objectMapper.readTree(json);
                    return json;
                }
            }

            String remote = runGitOutput("remote", "get-url", "origin");
            String branch = runGitOutput("rev-parse", "--abbrev-ref", "HEAD");
            ObjectNode n = objectMapper.createObjectNode();
            n.put("remoteUrl", remote != null ? sanitizeUrl(remote.trim()) : "");
            n.put("branch", branch != null && !branch.isBlank() ? branch.trim() : "main");
            n.put("username", "");

            n.put("hasToken", false);
            return n.toString();
        } catch (Exception e) {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("remoteUrl", "");
            n.put("branch", "main");
            return n.toString();
        }
    }

    private void saveRemoteConfig(String remoteUrl, String username, String branch) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("remoteUrl", remoteUrl != null ? remoteUrl : "");
            n.put("username", username != null ? username : "");
            n.put("branch", branch != null ? branch : "main");
            n.put("updatedAt", Instant.now().toString());
            Files.writeString(remoteConfigFile.toPath(), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(n));
        } catch (Exception ignored) {}
    }

    private String buildAuthUrl(String remoteUrl, String token, String username) {
        if (token == null || token.isBlank()) return remoteUrl;

        try {
            java.net.URI uri = new java.net.URI(remoteUrl);
            String host = uri.getHost();
            String path = uri.getRawPath();
            String query = uri.getRawQuery();
            String scheme = uri.getScheme();
            if (host == null) return remoteUrl;
            String userInfo;
            if (username != null && !username.isBlank()) {
                userInfo = username + ":" + token;
            } else {

                userInfo = token;
            }

            userInfo = java.net.URLEncoder.encode(username != null && !username.isBlank() ? username : "oauth2", java.nio.charset.StandardCharsets.UTF_8)
                    + ":" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);

            if (username == null || username.isBlank()) {
                userInfo = "oauth2:" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
            }
            String newAuthority = userInfo + "@" + host + (uri.getPort() != -1 ? ":" + uri.getPort() : "");
            java.net.URI newUri = new java.net.URI(scheme, newAuthority, path, query, uri.getFragment());
            return newUri.toString();
        } catch (Exception e) {

            if (remoteUrl.startsWith("https://")) {
                String withoutScheme = remoteUrl.substring("https://".length());
                String cred = (username != null && !username.isBlank()) ? username + ":" + token + "@" : token + "@";
                return "https://" + cred + withoutScheme;
            }
            return remoteUrl;
        }
    }

    private String sanitizeUrl(String url) {
        if (url == null) return "";

        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            String path = uri.getRawPath();
            String scheme = uri.getScheme();
            if (host == null) return url;
            String portPart = uri.getPort() != -1 ? ":" + uri.getPort() : "";
            return scheme + "://" + host + portPart + (path != null ? path : "");
        } catch (Exception e) {
            return url.replaceAll("https://[^@]+@", "https://***@");
        }
    }

    private Process gitProcess(String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(repoPath));
        pb.redirectErrorStream(false);
        return pb.start();
    }

    private void runGit(String... args) throws IOException, InterruptedException {
        Process p = gitProcess(args);
        String err = new String(p.getErrorStream().readAllBytes());
        String out = new String(p.getInputStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0 && !isIgnorable(args, code, err, out)) {
            throw new IOException("git " + String.join(" ", args) + " failed (" + code + "): " + err + out);
        }
    }

    private String runGitOutput(String... args) throws IOException, InterruptedException {
        Process p = gitProcess(args);
        String out = new String(p.getInputStream().readAllBytes());
        String err = new String(p.getErrorStream().readAllBytes());
        int code = p.waitFor();
        if (code != 0) {

            if (isIgnorableOutput(args)) return null;

            if (code == 128 && out.isBlank() && err.contains("unknown revision")) return null;
            if (args.length > 0 && "rev-parse".equals(args[0]) && code != 0) return null;
            if (args.length >0 && "remote".equals(args[0]) && out.isBlank()) return null;

            if (out.isBlank() && !err.isBlank()) {

                return "";
            }
        }
        return out != null ? out.trim() : null;
    }

    private String runGitOutputWithError(String... args) throws IOException, InterruptedException {
        Process p = gitProcess(args);
        String out = new String(p.getInputStream().readAllBytes());
        String err = new String(p.getErrorStream().readAllBytes());
        int code = p.waitFor();
        String combined = out + (err.isBlank() ? "" : "\n" + err);
        if (code != 0) {
            throw new IOException(combined.trim());
        }
        return combined.trim();
    }

    private boolean isIgnorable(String[] args, int code, String err, String out) {
        if (args.length == 0) return false;

        if ("config".equals(args[0]) && code == 1) return true;

        return false;
    }

    private boolean isIgnorableOutput(String[] args) {
        if (args.length == 0) return false;
        if ("config".equals(args[0])) return true;
        if ("remote".equals(args[0])) return true;
        if ("rev-parse".equals(args[0])) return true;
        return false;
    }
}
