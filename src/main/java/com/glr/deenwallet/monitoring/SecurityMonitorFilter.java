package com.glr.deenwallet.monitoring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Watches every request from the outside. It never blocks or changes anything: after the request is finished it
 * (1) times it for the Metrics tab and (2) notes anything that looks like an attack - blocked by the rate limit,
 * wrong passwords / PINs, and scanners looking for files like .env or wp-login.php.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityMonitorFilter extends OncePerRequestFilter {

    private static final Pattern SCAN = Pattern.compile(
            "(?i)(\\.env|\\.git|wp-(admin|login|content|includes)|xmlrpc|phpmyadmin|phpinfo|/cgi-bin|\\.php|\\.asp|/actuator|/vendor/|"
                    + "/boaform|/shell|\\.\\./|%2e%2e|/etc/passwd|union(\\s|%20|\\+)select|<script|%3cscript|/solr|/jenkins|/manager/html|/\\.aws|/HNAP1)");

    private final SecurityEventService events;
    private final ServerMetricsService metrics;
    private final BlockedIpService blockedIps;

    public SecurityMonitorFilter(SecurityEventService events, ServerMetricsService metrics, BlockedIpService blockedIps) {
        this.events = events;
        this.metrics = metrics;
        this.blockedIps = blockedIps;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        if (blockedIps.isBlocked(ip)) {
            blockedIps.countHit(ip);
            response.setStatus(403);
            response.setContentType("application/json");
            response.getOutputStream().write("{\"message\":\"Access denied.\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        long start = System.nanoTime();
        HttpServletRequest forward = request;
        ContentCachingRequestWrapper cached = null;
        String type = request.getContentType();
        String method = request.getMethod();
        if (("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) && type != null
                && type.toLowerCase().contains("json") && request.getContentLength() <= 20_000) {
            cached = new ContentCachingRequestWrapper(request, 8192); // remembers what the app reads, so it can be checked afterwards
            forward = cached;
        }
        try {
            chain.doFilter(forward, response);
        } finally {
            try {
                String body = cached == null ? null : new String(cached.getContentAsByteArray(), StandardCharsets.UTF_8);
                observe(request, response.getStatus(), (System.nanoTime() - start) / 1_000_000L, body);
            } catch (RuntimeException ignored) {
                // monitoring must never break a request
            }
        }
    }

    private static String decode(String s) {
        if (s == null) return null;
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private void observe(HttpServletRequest req, int status, long ms, String body) {
        String path = req.getRequestURI() == null ? "" : req.getRequestURI();
        String method = req.getMethod();
        if (path.startsWith("/api/")) metrics.recordRequest(path, ms, status);

        String kind = null;
        String detail = null;
        String q = req.getQueryString();
        String sqli = SqlInjectionDetector.detect(decode(path));
        String where = "in the address";
        if (sqli == null && q != null) { sqli = SqlInjectionDetector.detect(decode(q)); where = "in the web address"; }
        if (sqli == null && body != null) { sqli = SqlInjectionDetector.detect(body); where = "in the request body"; }
        String tool = SqlInjectionDetector.tool(req.getHeader("User-Agent"));
        if (sqli != null) {
            kind = "SQLI";
            detail = sqli + " " + where;
        } else if (tool != null) {
            kind = "SCAN";
            detail = "Attack tool: " + tool;
        } else if (status == 429) {
            kind = "RATE_LIMIT";
        } else if (status >= 400) {
            String lower = path.toLowerCase();
            if (SCAN.matcher(q == null ? path : path + "?" + q).find()) {
                kind = "SCAN";
            } else if ("POST".equals(method) && lower.contains("/login") && status != 404) {
                kind = "LOGIN_FAILED";
            } else if ("POST".equals(method) && lower.contains("pin") && (status == 400 || status == 401 || status == 403 || status == 423)) {
                kind = "PIN_FAILED";
            } else if ((status == 401 || status == 403) && lower.startsWith("/api/admin/") && req.getHeader("Authorization") == null) {
                kind = "ADMIN_PROBE";
            }
        }
        if (kind != null) {
            events.record(req.getRemoteAddr(), kind, method, path, status, req.getHeader("User-Agent"), detail);
        }
    }
}
