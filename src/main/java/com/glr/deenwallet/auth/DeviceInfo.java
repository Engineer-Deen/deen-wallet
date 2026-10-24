package com.glr.deenwallet.auth;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns the raw request details of a login into something a person recognises ("DeenWallet app on SM-A137F"). */
public final class DeviceInfo {

    public record Parsed(String name, String platform) { }

    private static final Pattern ANDROID_MODEL =
            Pattern.compile("Android[^;)]*;\\s*([^;)]+?)(?:\\s+Build/[^;)]*)?\\s*[;)]");
    private static final Pattern DEVICE_KEY = Pattern.compile("^[A-Za-z0-9-]{16,64}$");

    private DeviceInfo() { }

    public static Parsed parse(String userAgent) {
        String ua = userAgent == null ? "" : userAgent.trim();
        if (ua.isEmpty()) {
            return new Parsed("Unknown device", "unknown");
        }

        // The DeenWallet Android app is a WebView ("; wv)" in the user agent).
        boolean app = ua.contains("; wv)") || ua.contains("DeenWallet");
        String browser = browserName(ua);
        String platform;
        String where;

        if (ua.contains("Android")) {
            platform = "android";
            String model = null;
            Matcher m = ANDROID_MODEL.matcher(ua);
            if (m.find()) {
                String found = m.group(1).trim();
                // Recent Chrome hides the model and reports just "K".
                if (!found.isEmpty() && !"K".equals(found) && !found.toLowerCase().startsWith("linux")) {
                    model = found;
                }
            }
            where = model != null ? model : "Android";
        } else if (ua.contains("iPhone")) {
            platform = "ios";
            where = "iPhone";
        } else if (ua.contains("iPad")) {
            platform = "ios";
            where = "iPad";
        } else if (ua.contains("Windows")) {
            platform = "windows";
            where = "Windows";
        } else if (ua.contains("Macintosh") || ua.contains("Mac OS X")) {
            platform = "mac";
            where = "Mac";
        } else if (ua.contains("CrOS")) {
            platform = "chromeos";
            where = "Chromebook";
        } else if (ua.contains("Linux") || ua.contains("X11")) {
            platform = "linux";
            where = "Linux";
        } else {
            platform = "unknown";
            where = "unknown device";
        }

        String name = (app ? "DeenWallet app" : browser) + " on " + where;
        if (name.length() > 120) {
            name = name.substring(0, 120);
        }
        return new Parsed(name, platform);
    }

    private static String browserName(String ua) {
        if (ua.contains("Edg/") || ua.contains("EdgA/") || ua.contains("EdgiOS/")) return "Microsoft Edge";
        if (ua.contains("OPR/") || ua.contains("Opera")) return "Opera";
        if (ua.contains("SamsungBrowser/")) return "Samsung Internet";
        if (ua.contains("Firefox/") || ua.contains("FxiOS/")) return "Firefox";
        if (ua.contains("Chrome/") || ua.contains("CriOS/")) return "Chrome";
        if (ua.contains("Safari/")) return "Safari";
        return "Browser";
    }

    /** Shows only the start of an address (41.20.*.*), enough to recognise a place without exposing it. */
    public static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) return "";
        String v = ip.trim();
        if (v.contains(".") && !v.contains(":")) {
            String[] p = v.split("\\.");
            if (p.length == 4) return p[0] + "." + p[1] + ".*.*";
            return "";
        }
        if (v.contains(":")) {
            String[] p = v.split(":");
            if (p.length >= 2) return p[0] + ":" + p[1] + "::*";
        }
        return "";
    }

    /** The id the app generates for itself. Anything that does not look like one is ignored. */
    public static String cleanDeviceKey(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        return DEVICE_KEY.matcher(v).matches() ? v : null;
    }
}
