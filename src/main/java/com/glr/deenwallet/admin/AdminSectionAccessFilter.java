package com.glr.deenwallet.admin;

import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Stops an ADMIN from using a section a super admin has switched off for them (KYC, Wallet, ...).
 * Super admins are never limited. The admin app also hides those sections; this is the real lock.
 */
@Component
public class AdminSectionAccessFilter extends OncePerRequestFilter {

    private final UserRepository users;

    public AdminSectionAccessFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return !(p.startsWith("/api/admin/") || p.startsWith("/api/support/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = AdminSections.keyForPath(request.getMethod(), request.getRequestURI());
        if (key != null) {
            User actor = currentUser();
            if (actor != null && "ADMIN".equals(actor.getRole())
                    && AdminSections.parse(actor.getDeniedSections()).contains(key)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setHeader("X-Reason", "ACCESS_DENIED");
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getOutputStream().write(
                        ("{\"message\":\"You do not have access to " + AdminSections.LABELS.get(key)
                                + ". Ask a super admin if you need it.\"}").getBytes(StandardCharsets.UTF_8));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private User currentUser() {
        try {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a == null || !a.isAuthenticated() || !(a.getPrincipal() instanceof String s)) return null;
            return users.findById(UUID.fromString(s)).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
