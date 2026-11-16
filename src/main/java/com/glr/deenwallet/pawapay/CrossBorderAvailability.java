package com.glr.deenwallet.pawapay;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Which providers pawaPay can really use for us right now (GET /v2/availability), per operation: DEPOSIT (collect from the sender)
 * and REMITTANCE (pay the recipient abroad). Cached for a minute. If pawaPay cannot be asked, nothing is blocked (UNKNOWN) -
 * the transfer itself is still protected by the automatic refund.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrossBorderAvailability {

    private static final long TTL_MS = 60_000;
    private static final long RETRY_MS = 15_000;
    private static final long STALE_MS = 10 * 60_000;

    private final PawaPayClient client;
    private final PawaPayProperties props;

    record Snapshot(boolean known, Map<String, String> remittance, Map<String, String> deposit, long at) { }

    private volatile Snapshot snap = new Snapshot(false, Map.of(), Map.of(), 0L);
    private volatile long retryAfter = 0L;

    private Snapshot snapshot() {
        if (!props.enabled()) return snap;
        long now = System.currentTimeMillis();
        Snapshot s = snap;
        if (now - s.at() < TTL_MS || now < retryAfter) return s;
        synchronized (this) {
            s = snap;
            if (now - s.at() < TTL_MS || now < retryAfter) return s;
            try {
                PawaPayResponse r = client.availability();
                JsonNode root = r.json();
                if (r.http() == 200 && root != null && root.isArray() && root.size() > 0) {
                    Map<String, String> rem = new HashMap<>();
                    Map<String, String> dep = new HashMap<>();
                    for (JsonNode c : root) {
                        for (JsonNode p : c.path("providers")) {
                            String provider = p.path("provider").asText("");
                            JsonNode ops = p.path("operationTypes");
                            if (ops.isArray()) {
                                for (JsonNode o : ops) put(rem, dep, provider, o.path("operationType").asText(""), o.path("status").asText(""));
                            } else if (ops.isObject()) {
                                ops.fieldNames().forEachRemaining(n -> put(rem, dep, provider, n, ops.path(n).path("status").asText("")));
                            }
                        }
                    }
                    snap = new Snapshot(true, Map.copyOf(rem), Map.copyOf(dep), System.currentTimeMillis());
                } else {
                    failed(s, "HTTP " + r.http());
                }
            } catch (RuntimeException e) {
                failed(s, e.toString());
            }
            return snap;
        }
    }

    private void failed(Snapshot old, String why) {
        retryAfter = System.currentTimeMillis() + RETRY_MS;
        log.warn("Could not read pawaPay availability: {}", why);
        if (System.currentTimeMillis() - old.at() > STALE_MS) snap = new Snapshot(false, Map.of(), Map.of(), old.at());
    }

    private static void put(Map<String, String> rem, Map<String, String> dep, String provider, String op, String status) {
        if (provider.isBlank() || status.isBlank()) return;
        if ("REMITTANCE".equals(op)) rem.put(provider, status);
        else if ("DEPOSIT".equals(op)) dep.put(provider, status);
    }

    /** OPERATIONAL, DELAYED, CLOSED, NOT_ENABLED (pawaPay does not offer it on our account) or UNKNOWN (pawaPay did not answer). */
    public String remittance(String provider) {
        Snapshot s = snapshot();
        if (!s.known()) return "UNKNOWN";
        return s.remittance().getOrDefault(provider, "NOT_ENABLED");
    }

    public String deposit(String provider) {
        Snapshot s = snapshot();
        if (!s.known()) return "UNKNOWN";
        return s.deposit().getOrDefault(provider, "NOT_ENABLED");
    }

    public static boolean blocks(String status) {
        return "CLOSED".equals(status) || "NOT_ENABLED".equals(status);
    }
}
