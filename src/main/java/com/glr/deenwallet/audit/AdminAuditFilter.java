package com.glr.deenwallet.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes an audit line for every successful admin action that changes something (POST / PUT / PATCH / DELETE under
 * /api/admin and /api/support), without touching the controllers. Passwords, PINs and tokens are never read:
 * only a few harmless body fields are used, and only for the rules that name them.
 *
 * Not logged here because they already have their own trail or are noise: KYC and WAEC (own audit tables),
 * wallet (logged by the wallet service with amounts), failed payouts (own table), token refresh, PIN checks,
 * browser error reports, recovery code requests.
 */
@Component
public class AdminAuditFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditFilter.class);
    private static final String UUID_RE = "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})";

    private record Rule(String method, Pattern path, String category, String action, String summary) { }

    private static Rule rule(String method, String regex, String category, String action, String summary) {
        return new Rule(method, Pattern.compile(regex), category, action, summary);
    }

    private static final List<Rule> RULES = List.of(
            rule("PUT", "^/api/admin/users/" + UUID_RE + "/toggle$", "USERS", "USER_TOGGLE", "Changed a user's active status"),
            rule("PUT", "^/api/admin/users/" + UUID_RE + "/lock$", "USERS", "USER_LOCK", "Locked a user account"),
            rule("PUT", "^/api/admin/users/" + UUID_RE + "/unlock$", "USERS", "USER_UNLOCK", "Unlocked a user account"),
            rule("PUT", "^/api/admin/users/" + UUID_RE + "/reset-login-protection$", "USERS", "USER_RESET_PROTECTION", "Reset a user's login protection"),
            rule("PUT", "^/api/admin/users/" + UUID_RE + "/contact$", "USERS", "USER_CONTACT", "Changed a user's contact details"),
            rule("PUT", "^/api/admin/users/" + UUID_RE + "/verify-email$", "USERS", "USER_VERIFY_EMAIL", "Verified a user's email"),
            rule("PUT", "^/api/support/users/" + UUID_RE + "/lock$", "USERS", "USER_LOCK", "Locked a user account (support)"),
            rule("PUT", "^/api/support/users/" + UUID_RE + "/unlock$", "USERS", "USER_UNLOCK", "Unlocked a user account (support)"),
            rule("POST", "^/api/admin/admins$", "ADMINS", "ADMIN_CREATE", "Created an admin account"),
            rule("POST", "^/api/admin/pin$", "SECURITY", "PIN_CHANGE", "Set or changed their admin PIN"),
            rule("PUT", "^/api/admin/profile/verify-email$", "SECURITY", "VERIFY_OWN_EMAIL", "Verified their own email"),
            rule("POST", "^/api/admin/notifications$", "MESSAGING", "NOTIFICATION", "Sent an in-app notification"),
            rule("DELETE", "^/api/admin/errors(/old)?$", "SYSTEM", "ERRORS_CLEARED", "Cleared error logs"),
            rule("PUT", "^/api/admin/support/whatsapp$", "SYSTEM", "WHATSAPP", "Changed the support WhatsApp number"),
            rule("POST", "^/api/admin/auth/login$", "SECURITY", "LOGIN", "Signed in"),
            rule("POST", "^/api/admin/auth/recovery/verify-otp$", "SECURITY", "RECOVERY", "Recovered access with an email code")
    );

    private static final List<String> SKIP_PREFIXES = List.of(
            "/api/admin/kyc", "/api/admin/waec", "/api/admin/wallet", "/api/admin/audit", "/api/admin/failed-payouts",
            "/api/admin/auth/refresh", "/api/admin/auth/recovery/send-otp", "/api/admin/auth/forgot",
            "/api/admin/auth/reset", "/api/admin/verify-pin");

    private final AuditService audit;
    private final UserRepository users;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdminAuditFilter(AuditService audit, UserRepository users) {
        this.audit = audit;
        this.users = users;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String m = request.getMethod();
        if (!("POST".equals(m) || "PUT".equals(m) || "PATCH".equals(m) || "DELETE".equals(m))) return true;
        String p = request.getRequestURI();
        if (!(p.startsWith("/api/admin/") || p.startsWith("/api/support/"))) return true;
        for (String s : SKIP_PREFIXES) {
            if (p.startsWith(s)) return true;
        }
        // Browser error reports are noise; clearing the log (DELETE) is not.
        return "POST".equals(m) && p.startsWith("/api/admin/errors");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request, 16384);
        chain.doFilter(wrapped, response);
        try {
            int status = response.getStatus();
            if (status >= 200 && status < 300) after(wrapped, status);
        } catch (RuntimeException e) {
            log.warn("Audit filter could not record {} {}: {}", request.getMethod(), request.getRequestURI(), e.toString());
        }
    }

    private void after(ContentCachingRequestWrapper req, int status) {
        String method = req.getMethod();
        String path = req.getRequestURI();
        JsonNode body = readBody(req);

        Rule matched = null;
        Matcher m = null;
        for (Rule r : RULES) {
            if (!r.method().equals(method)) continue;
            Matcher mm = r.path().matcher(path);
            if (mm.matches()) { matched = r; m = mm; break; }
        }

        User actor = currentActor();
        if (actor == null && matched != null && body != null
                && ("LOGIN".equals(matched.action()) || "RECOVERY".equals(matched.action()))) {
            actor = userByEmail(text(body, "email"));
        }
        if (actor == null) return;                       // nobody to attribute it to
        if (!"ADMIN".equals(actor.getRole()) && !"SUPER_ADMIN".equals(actor.getRole())) return;

        String category = "SYSTEM";
        String action = "OTHER";
        String summary = "Changed something";
        String detail = method + " " + path.replaceAll(UUID_RE, "{id}");
        UUID targetId = null;
        String targetName = null;

        if (matched != null) {
            category = matched.category();
            action = matched.action();
            summary = matched.summary();
            detail = null;
            if (m != null && m.groupCount() >= 1 && m.group(1) != null && m.group(1).length() == 36) {
                targetId = UUID.fromString(m.group(1));
                User t = users.findById(targetId).orElse(null);
                if (t != null) targetName = t.getFullName();
            }
            switch (action) {
                case "USER_TOGGLE" -> {
                    if (body != null && body.has("active")) {
                        summary = body.path("active").asBoolean() ? "Activated a user account" : "Deactivated a user account";
                    }
                }
                case "ADMIN_CREATE" -> {
                    targetName = firstNonBlank(text(body, "fullName"), text(body, "email"));
                    detail = text(body, "email");
                }
                case "NOTIFICATION" -> {
                    String title = text(body, "title");
                    int n = body != null && body.path("userIds").isArray() ? body.path("userIds").size() : 0;
                    detail = (title == null ? "" : "\"" + title + "\"") + (n > 0 ? " to " + n + " user(s)" : "");
                }
                case "WHATSAPP" -> detail = text(body, "whatsappNumber");
                default -> { }
            }
        }
        audit.record(actor.getId(), actor.getFullName(), actor.getEmail(), category, action, summary, targetId,
                targetName, detail, clientIp(req), status);
    }

    private User currentActor() {
        try {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a == null || !a.isAuthenticated() || !(a.getPrincipal() instanceof String s)) return null;
            return users.findById(UUID.fromString(s)).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private User userByEmail(String email) {
        if (email == null || email.isBlank()) return null;
        String e = email.trim().toLowerCase();
        User u = users.findByEmailAndRole(e, "SUPER_ADMIN").orElse(null);
        return u != null ? u : users.findByEmailAndRole(e, "ADMIN").orElse(null);
    }

    private JsonNode readBody(ContentCachingRequestWrapper req) {
        byte[] bytes = req.getContentAsByteArray();
        if (bytes == null || bytes.length == 0) return null;
        try {
            return mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText(null);
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
