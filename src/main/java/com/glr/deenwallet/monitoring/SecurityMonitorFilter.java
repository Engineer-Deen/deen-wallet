package com.glr.deenwallet.monitoring;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
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

    public SecurityMonitorFilter(SecurityEventService events, ServerMetricsService metrics) {
        this.events = events;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            try {
                observe(request, response.getStatus(), (System.nanoTime() - start) / 1_000_000L);
            } catch (RuntimeException ignored) {
                // monitoring must never break a request
            }
        }
    }

    private void observe(HttpServletRequest req, int status, long ms) {
        String path = req.getRequestURI() == null ? "" : req.getRequestURI();
        String method = req.getMethod();
        if (path.startsWith("/api/")) metrics.recordRequest(path, ms, status);

        String kind = null;
        if (status == 429) {
            kind = "RATE_LIMIT";
        } else if (status >= 400) {
            String q = req.getQueryString();
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
            events.record(req.getRemoteAddr(), kind, method, path, status, req.getHeader("User-Agent"));
        }
    }
}
