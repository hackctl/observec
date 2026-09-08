package com.hackctl.observec.engine.component;

import java.io.IOException;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hackctl.observec.engine.model.User;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@Component
public class AuthFilter extends OncePerRequestFilter {

    private final UserStore userStore;
    private final ObjectMapper objectMapper;

    private static final Set<String> PUBLIC_PATHS = Set.of(
        "/api/auth/login",
        "/api/auth/logout"
    );

    private static final Set<String> PUBLIC_PREFIXES = Set.of(
        "/styles.css",
        "/app.js",
        "/probe-log.html",
        "/observec.png"
    );

    public AuthFilter(UserStore userStore, ObjectMapper objectMapper) {
        this.userStore = userStore;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        // Allow public paths
        if (isPublicPath(path, method)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Allow static resources and root
        if (isStaticAllowed(path, method)) {
            filterChain.doFilter(request, response);
            return;
        }

        // For /api/auth/me we allow to reach controller (it will return 401 if not authed, but we don't block here)
        if ("/api/auth/me".equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        HttpSession session = request.getSession(false);
        User current = null;
        if (session != null) {
            Object idObj = session.getAttribute("userId");
            if (idObj != null) {
                Long id = null;
                if (idObj instanceof Long) id = (Long) idObj;
                else if (idObj instanceof Integer) id = ((Integer) idObj).longValue();
                else if (idObj instanceof String) {
                    try { id = Long.parseLong((String) idObj); } catch (NumberFormatException e) {}
                }
                if (id != null) {
                    current = userStore.findById(id).orElse(null);
                    if (current != null) {
                        // refresh session role if changed
                        request.setAttribute("currentUser", current);
                    }
                }
            }
        }

        if (current == null) {
            // not authenticated
            // For API requests, return 401 JSON
            if (path.startsWith("/api/") || path.startsWith("/blackbox") || path.startsWith("/prometheus")
                    || path.startsWith("/alertmanager") || path.startsWith("/monitors")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                ObjectNode node = objectMapper.createObjectNode();
                node.put("status", "error");
                node.put("message", "not authenticated");
                response.getWriter().write(node.toString());
                return;
            }
            // For other pages, still allow (login screen is in index.html)
            // But we still want to block direct access to main UI? The frontend will handle via JS.
            // Allow through to serve index.html, then frontend will show login overlay.
            filterChain.doFilter(request, response);
            return;
        }

        // authenticated - check write permission for mutating methods
        boolean isWrite = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);

        // Allow read for all authenticated, but write only for ADMIN
        // Exceptions: POST /api/auth/logout is allowed for any, and POST /api/auth/login already handled
        if (isWrite) {
            // Some POST endpoints are read-like? No, all POST in our app are writes except login/logout
            // Allow logout for any role (already handled as public? we handle logout as public prefix? Actually we allowed logout as public, but if we reach here, it's authenticated logout)
            // Check if path is write-protected
            if (isWriteProtected(path)) {
                if (!current.isAdmin()) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    ObjectNode node = objectMapper.createObjectNode();
                    node.put("status", "error");
                    node.put("message", "read-only user cannot modify");
                    response.getWriter().write(node.toString());
                    return;
                }
            }
        }

        // Also protect /api/users endpoints to ADMIN only even for GET
        if (path.startsWith("/api/users")) {
            if (!current.isAdmin()) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                ObjectNode node = objectMapper.createObjectNode();
                node.put("status", "error");
                node.put("message", "admin only");
                response.getWriter().write(node.toString());
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isPublicPath(String path, String method) {
        if (PUBLIC_PATHS.contains(path)) return true;
        // Allow GET for login related? login is POST, but we allow it
        if ("/api/auth/login".equals(path) && "POST".equalsIgnoreCase(method)) return true;
        if ("/api/auth/logout".equals(path) && "POST".equalsIgnoreCase(method)) return true;
        return false;
    }

    private boolean isStaticAllowed(String path, String method) {
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) return false;
        if ("/".equals(path) || "/index.html".equals(path) || path.equals("/favicon.ico")) return true;
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "?") || path.startsWith(prefix + "#")) return true;
        }
        // allow any request with query string for those
        if (path.startsWith("/styles.css") || path.startsWith("/app.js") || path.startsWith("/probe-log.html") || path.startsWith("/observec.png")) return true;
        // allow versioned static like /styles.css?v=...
        if (path.contains("styles.css") || path.contains("app.js") || path.contains("probe-log.html") || path.contains("observec.png")) return true;
        return false;
    }

    private boolean isWriteProtected(String path) {
        // All our write APIs are protected
        if (path.startsWith("/blackbox") || path.startsWith("/prometheus") || path.startsWith("/alertmanager")
                || path.startsWith("/monitors") || path.startsWith("/api/users")) {
            return true;
        }
        // Also protect /api/auth for user creation? that's /api/users already
        return false;
    }
}
