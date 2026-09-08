package com.hackctl.observec.engine.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hackctl.observec.engine.component.UserStore;
import com.hackctl.observec.engine.model.User;
import com.hackctl.observec.engine.service.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;
    private final UserStore userStore;
    private final ObjectMapper objectMapper;

    public AuthController(AuthService authService, UserStore userStore, ObjectMapper objectMapper) {
        this.authService = authService;
        this.userStore = userStore;
        this.objectMapper = objectMapper;
    }

    private ObjectNode toSafeUserNode(User u) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("id", u.getId());
        n.put("username", u.getUsername());
        n.put("email", u.getEmail() != null ? u.getEmail() : "");
        n.put("role", u.getRole());
        n.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : "");
        return n;
    }

    private User currentUser(HttpSession session) {
        if (session == null) return null;
        Object idObj = session.getAttribute("userId");
        if (idObj instanceof Long) {
            return authService.getUserById((Long) idObj);
        } else if (idObj instanceof Integer) {
            return authService.getUserById(((Integer) idObj).longValue());
        } else if (idObj instanceof String) {
            try {
                return authService.getUserById(Long.parseLong((String) idObj));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    @PostMapping("/auth/login")
    public String login(@RequestBody String body, HttpServletRequest request) {
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid json");
        }
        String username = node.path("username").asText("").trim();
        String password = node.path("password").asText("");
        if (username.isEmpty() || password.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username and password required");
        }
        Optional<User> opt = authService.authenticate(username, password);
        if (opt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        User u = opt.get();
        HttpSession session = request.getSession(true);
        session.setAttribute("userId", u.getId());
        session.setAttribute("username", u.getUsername());
        session.setAttribute("role", u.getRole());
        // set max inactive 24h
        session.setMaxInactiveInterval(86400);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("status", "success");
        resp.set("user", toSafeUserNode(u));
        return resp.toString();
    }

    @PostMapping("/auth/logout")
    public String logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("status", "success");
        resp.put("message", "logged out");
        return resp.toString();
    }

    @GetMapping("/auth/me")
    public String me(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        User u = currentUser(session);
        if (u == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("status", "success");
        resp.set("user", toSafeUserNode(u));
        return resp.toString();
    }

    // ---- user management (admin only) ----

    @GetMapping("/users")
    public String listUsers(HttpServletRequest request) {
        User cur = currentUser(request.getSession(false));
        if (cur == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        if (!cur.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin only");
        List<User> users = userStore.listAll();
        ArrayNode arr = objectMapper.createArrayNode();
        for (User u : users) {
            arr.add(toSafeUserNode(u));
        }
        ObjectNode resp = objectMapper.createObjectNode();
        resp.set("users", arr);
        return resp.toString();
    }

    @PostMapping("/users")
    public String createUser(@RequestBody String body, HttpServletRequest request) {
        User cur = currentUser(request.getSession(false));
        if (cur == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        if (!cur.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin only");
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid json");
        }
        String username = node.path("username").asText("").trim();
        String email = node.path("email").asText("").trim();
        String password = node.path("password").asText("");
        String role = node.path("role").asText("READER").trim();
        // map simple read/write to ADMIN/READER if needed
        if ("write".equalsIgnoreCase(role) || "read/write".equalsIgnoreCase(role) || "write".equalsIgnoreCase(role.replace(" ", ""))) role = "ADMIN";
        if ("read".equalsIgnoreCase(role)) role = "READER";
        if (username.isEmpty() || password.isEmpty() || email.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username, email and password required");
        }
        if (username.length() < 3 || username.length() > 32 || !username.matches("[A-Za-z0-9._-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid username (3-32 chars, alphanumeric ._- )");
        }
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid email");
        }
        if (password.length() < 4) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password too short (min 4)");
        }
        if (userStore.findByUsername(username).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "username already exists");
        }
        if (userStore.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already exists");
        }
        User created = userStore.createUser(username, email, password, role);
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("status", "success");
        resp.set("user", toSafeUserNode(created));
        return resp.toString();
    }

    @DeleteMapping("/users/{id}")
    public String deleteUser(@PathVariable("id") Long id, HttpServletRequest request) {
        User cur = currentUser(request.getSession(false));
        if (cur == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        if (!cur.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin only");
        if (cur.getId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot delete yourself");
        }
        var target = userStore.findById(id).orElse(null);
        if (target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        if ("ADMIN".equalsIgnoreCase(target.getRole()) && userStore.countAdmins() <= 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot delete last admin");
        }
        boolean ok = userStore.deleteUser(id);
        if (!ok) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "delete failed");
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("status", "success");
        resp.put("message", "user deleted");
        return resp.toString();
    }

    @PutMapping("/users/{id}")
    public String updateUser(@PathVariable("id") Long id, @RequestBody String body, HttpServletRequest request) {
        User cur = currentUser(request.getSession(false));
        if (cur == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        if (!cur.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin only");
        var target = userStore.findById(id).orElse(null);
        if (target == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid json");
        }
        boolean changed = false;
        if (node.has("role")) {
            String newRole = node.path("role").asText("").trim();
            if ("write".equalsIgnoreCase(newRole) || "read/write".equalsIgnoreCase(newRole)) newRole = "ADMIN";
            if ("read".equalsIgnoreCase(newRole)) newRole = "READER";
            if (!"ADMIN".equalsIgnoreCase(newRole) && !"READER".equalsIgnoreCase(newRole)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role must be ADMIN or READER");
            }
            if (!newRole.equalsIgnoreCase(target.getRole())) {
                // prevent demoting last admin
                if ("ADMIN".equalsIgnoreCase(target.getRole()) && "READER".equalsIgnoreCase(newRole) && userStore.countAdmins() <= 1) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot demote last admin");
                }
                userStore.updateRole(id, newRole);
                changed = true;
            }
        }
        if (node.has("email")) {
            String newEmail = node.path("email").asText("").trim();
            if (!newEmail.isEmpty() && !newEmail.equals(target.getEmail())) {
                if (!newEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid email");
                }
                if (userStore.findByEmail(newEmail).isPresent()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "email already exists");
                }
                userStore.updateEmail(id, newEmail);
                changed = true;
            }
        }
        if (node.has("password")) {
            String newPass = node.path("password").asText("");
            if (!newPass.isEmpty()) {
                if (newPass.length() < 4) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password too short");
                userStore.updatePassword(id, newPass);
                changed = true;
            }
        }
        ObjectNode resp = objectMapper.createObjectNode();
        resp.put("status", "success");
        resp.put("message", changed ? "user updated" : "no changes");
        var updated = userStore.findById(id).orElse(target);
        resp.set("user", toSafeUserNode(updated));
        return resp.toString();
    }
}
