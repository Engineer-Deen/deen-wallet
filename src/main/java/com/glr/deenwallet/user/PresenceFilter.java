package com.glr.deenwallet.user;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Notes (at most once a minute per customer) that a signed-in customer is using the app. Never blocks a request. */
@Component
public class PresenceFilter extends OncePerRequestFilter {

    private static final long EVERY_MS = 60_000;
    private final PresenceService presence;
    private final ConcurrentHashMap<String, Long> last = new ConcurrentHashMap<>();

    public PresenceFilter(PresenceService presence) {
        this.presence = presence;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a != null && a.isAuthenticated() && a.getPrincipal() instanceof String uid
                    && a.getAuthorities().stream().anyMatch(g -> "ROLE_USER".equals(g.getAuthority()))) {
                long now = System.currentTimeMillis();
                Long prev = last.get(uid);
                if (prev == null || now - prev >= EVERY_MS) {
                    if (last.size() > 50_000) last.clear();
                    last.put(uid, now);
                    presence.touch(UUID.fromString(uid));
                }
            }
        } catch (RuntimeException ignored) {
            // presence is a nice-to-have; it must never break a request
        }
        chain.doFilter(request, response);
    }
}
