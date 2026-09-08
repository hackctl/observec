package com.hackctl.observec.engine.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hackctl.observec.engine.component.GitService;

@RestController
@RequestMapping("/git")
public class GitController {

    private final GitService gitService;
    private final ObjectMapper objectMapper;

    public GitController(GitService gitService, ObjectMapper objectMapper) {
        this.gitService = gitService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/log")
    public String log() {
        return gitService.log();
    }

    @GetMapping("/status")
    public String status() {
        return gitService.status();
    }

    @GetMapping("/remote")
    public String getRemote() {
        return gitService.getRemoteConfig();
    }

    @PostMapping("/push")
    public String push(@RequestBody String body) {
        try {
            JsonNode n = objectMapper.readTree(body);
            String remoteUrl = n.path("remoteUrl").asText("");
            String token = n.path("token").asText("");
            String username = n.path("username").asText("");
            String branch = n.path("branch").asText("main");
            return gitService.push(remoteUrl, token, username, branch);
        } catch (Exception e) {
            try {
                com.fasterxml.jackson.databind.node.ObjectNode err = objectMapper.createObjectNode();
                err.put("status", "error");
                err.put("message", e.getMessage());
                return err.toString();
            } catch (Exception ex) { return "{\"status\":\"error\"}"; }
        }
    }

    @PostMapping("/pull")
    public String pull(@RequestBody String body) {
        try {
            JsonNode n = objectMapper.readTree(body);
            String remoteUrl = n.path("remoteUrl").asText("");
            String token = n.path("token").asText("");
            String username = n.path("username").asText("");
            String branch = n.path("branch").asText("main");
            return gitService.pull(remoteUrl, token, username, branch);
        } catch (Exception e) {
            try {
                com.fasterxml.jackson.databind.node.ObjectNode err = objectMapper.createObjectNode();
                err.put("status", "error");
                err.put("message", e.getMessage());
                return err.toString();
            } catch (Exception ex) { return "{\"status\":\"error\"}"; }
        }
    }

    @PostMapping("/apply")
    public String apply(@RequestBody String body) {
        try {
            JsonNode n = objectMapper.readTree(body);
            String hash = n.path("hash").asText("");
            if (hash.isBlank()) hash = n.path("commit").asText("");
            return gitService.applyCommit(hash);
        } catch (Exception e) {
            try {
                com.fasterxml.jackson.databind.node.ObjectNode err = objectMapper.createObjectNode();
                err.put("status", "error");
                err.put("message", e.getMessage());
                return err.toString();
            } catch (Exception ex) { return "{\"status\":\"error\"}"; }
        }
    }
}
