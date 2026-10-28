package com.glr.deenwallet.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The parts of the admin app a super admin can switch on or off for an individual admin. */
public final class AdminSections {

    private AdminSections() { }

    /** key -> plain name shown to people. */
    public static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("users", "Users");
        LABELS.put("kyc", "KYC Verification");
        LABELS.put("failed-payouts", "Failed Payouts");
        LABELS.put("waec", "WAEC Pins");
        LABELS.put("wallet", "Wallet");
        LABELS.put("messaging", "Messaging");
        LABELS.put("errors", "Error Logs");
    }

    /** Which section a request belongs to, or null when it is open to every admin. */
    public static String keyForPath(String method, String path) {
        if (path == null) return null;
        if (path.startsWith("/api/admin/kyc")) return "kyc";
        if (path.startsWith("/api/admin/failed-payouts")) return "failed-payouts";
        if (path.startsWith("/api/admin/waec")) return "waec";
        if (path.startsWith("/api/admin/wallet")) return "wallet";
        if (path.startsWith("/api/admin/mail") || path.equals("/api/admin/notifications")) return "messaging";
        if (path.startsWith("/api/admin/errors") && !"POST".equals(method)) return "errors";
        if (path.equals("/api/admin/users") || path.startsWith("/api/admin/users/") || path.startsWith("/api/support/")) return "users";
        return null;
    }

    public static List<String> parse(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split(",")) {
            String k = s.trim();
            if (!k.isEmpty() && LABELS.containsKey(k) && !out.contains(k)) out.add(k);
        }
        return out;
    }

    public static String join(List<String> keys) {
        return String.join(",", keys);
    }
}
