package com.glr.deenwallet.monime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a Monime error body into a short, safe log fragment such as
 * "reason=upstream_timeout message=Timeout on processing request".
 *
 * Only the error reason and message are kept. The rest of the body (which can contain
 * account numbers, names or other payment details) is never written to the logs.
 */
public final class MonimeErrorSummary {

    private static final Pattern REASON = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]{0,80})\"");
    private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"]{0,200})\"");

    private MonimeErrorSummary() {
    }

    public static String of(String body) {
        if (body == null || body.isBlank()) {
            return "reason=none";
        }
        return "reason=" + first(REASON, body) + " message=" + first(MESSAGE, body);
    }

    private static String first(Pattern pattern, String body) {
        Matcher m = pattern.matcher(body);
        return m.find() ? m.group(1) : "unknown";
    }
}
