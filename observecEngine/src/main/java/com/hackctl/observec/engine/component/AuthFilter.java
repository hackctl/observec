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

        if (isPublicPath(path, method)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isStaticAllowed(path, method)) {
            filterChain.doFilter(request, response);
            return;
        }

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

                        request.setAttribute("currentUser", current);
                    }
                }
            }
        }

        if (current == null) {

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

            filterChain.doFilter(request, response);
            return;
        }

        boolean isWrite = "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method);

        if (isWrite) {

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

        if (path.startsWith("/styles.css") || path.startsWith("/app.js") || path.startsWith("/probe-log.html") || path.startsWith("/observec.png")) return true;

        if (path.contains("styles.css") || path.contains("app.js") || path.contains("probe-log.html") || path.contains("observec.png")) return true;
        return false;
    }

    private boolean isWriteProtected(String path) {

        if (path.startsWith("/blackbox") || path.startsWith("/prometheus") || path.startsWith("/alertmanager")
                || path.startsWith("/monitors") || path.startsWith("/api/users")) {
            return true;
        }

        return false;
    }
}
