package com.glr.deenwallet.monitoring;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Lets the login / PIN code tell the security monitor WHAT really happened to a request, so the admin
 * dashboard can tell apart "the password was really wrong" from "the request never reached the password check".
 * Only a label is stored (never a password, PIN or token).
 */
public final class SecurityTag {

    private static final String ATTR = "dw.sec.kind";
    private static final String ACCT = "dw.sec.acct";
    private static final String GOOD = "dw.sec.good";

    private SecurityTag() {}

    /** Call right where the thing happens: LOGIN_FAILED, LOGIN_UNKNOWN, HUMAN_FAILED or PIN_FAILED. */
    public static void tag(String kind) {
        try {
            RequestAttributes a = RequestContextHolder.getRequestAttributes();
            if (a != null) a.setAttribute(ATTR, kind, RequestAttributes.SCOPE_REQUEST);
        } catch (RuntimeException ignored) {
            // a label must never break a login
        }
    }

    /** Like tag(), and also remembers which account was tried (kept in memory for the auto-ban only, never stored or logged). */
    public static void tag(String kind, String account) {
        tag(kind);
        try {
            RequestAttributes a = RequestContextHolder.getRequestAttributes();
            if (a != null && account != null) a.setAttribute(ACCT, account, RequestAttributes.SCOPE_REQUEST);
        } catch (RuntimeException ignored) {
            // a label must never break a login
        }
    }

    public static String accountOf(HttpServletRequest req) {
        Object v = req.getAttribute(ACCT);
        return v instanceof String s ? s : null;
    }

    /** This request was made with a valid sign-in (a real customer or admin). Kept only for the current request. */
    public static void good() {
        try {
            RequestAttributes a = RequestContextHolder.getRequestAttributes();
            if (a != null) a.setAttribute(GOOD, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
        } catch (RuntimeException ignored) {
            // a label must never break a login
        }
    }

    public static void good(HttpServletRequest req) {
        req.setAttribute(GOOD, Boolean.TRUE);
    }

    public static boolean isGood(HttpServletRequest req) {
        return Boolean.TRUE.equals(req.getAttribute(GOOD));
    }

    public static String of(HttpServletRequest req) {
        Object v = req.getAttribute(ATTR);
        return v instanceof String s ? s : null;
    }

    /**
     * The kind of attack record for a request that ended with status 400 or more, or null when it is not one.
     *  - a label set by the code that really checked the password / PIN / human check wins;
     *  - a login or PIN request that got no label was turned away BEFORE any such check
     *    (no token, wrong path, locked account, bad body): REJECTED.
     */
    public static String kind(boolean scan, String method, String lowerPath, int status, String tag, boolean adminWithoutToken) {
        if (scan) return "SCAN";
        boolean post = "POST".equals(method);
        if (post && tag != null) return tag;
        if (post && lowerPath.contains("/login") && status != 404) return "REJECTED";
        if (adminWithoutToken) return "ADMIN_PROBE";
        if (post && lowerPath.contains("pin") && (status == 401 || status == 403)) return "REJECTED";
        return null;
    }
}
