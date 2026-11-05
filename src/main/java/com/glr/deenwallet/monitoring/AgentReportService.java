package com.glr.deenwallet.monitoring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Keeps the latest report from each extra machine that runs the DeenWallet monitoring agent (dw-agent.py). */
@Service
public class AgentReportService {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9 ._-]{1,40}$");
    private static final int MAX_MACHINES = 10;

    @Value("${MONITOR_AGENT_TOKEN:}")
    private String token;

    private static final class Entry {
        Map<String, Object> report;
        long at;
        String ip;
    }

    private final ConcurrentHashMap<String, Entry> machines = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private com.glr.deenwallet.notification.AdminAlertService alerts;

    /** Security-update count last alerted for each machine (absent = nothing sent yet). */
    private final ConcurrentHashMap<String, Integer> secAlerted = new ConcurrentHashMap<>();

    private static int securityCount(Object tasks) {
        try {
            if (tasks instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m && String.valueOf(m.get("title")).contains("security update")) {
                        java.util.regex.Matcher x = Pattern.compile("^(\\d+)").matcher(String.valueOf(m.get("title")));
                        if (x.find()) return Integer.parseInt(x.group(1));
                    }
                }
            }
        } catch (RuntimeException e) { /* ignore */ }
        return 0;
    }

    private void updateAlert(String name, Object tasks) {
        try {
            if (alerts == null) return;
            int sec = securityCount(tasks);
            Integer prev = secAlerted.get(name);
            if (sec > 0 && (prev == null || prev != sec)) {
                secAlerted.put(name, sec);
                alerts.notifyAdmins(null, "errors", "SERVER_UPDATES", "Security updates are waiting",
                        sec + " security update(s) are waiting on " + name + ". Open Server Metrics for the command to run.");
            } else if (sec == 0 && prev != null && prev > 0) {
                secAlerted.put(name, 0);
                alerts.notifyAdmins(null, "errors", "SERVER_UPDATES_OK", "Server is up to date", name + " has no security updates waiting now.");
            } else if (sec == 0 && prev == null) {
                secAlerted.put(name, 0);
            }
        } catch (RuntimeException e) { /* an alert problem must never break reporting */ }
    }

    /** Receiving is only on when a long secret token is set in the server's environment file. */
    public boolean enabled() {
        return token != null && token.trim().length() >= 16;
    }

    public boolean tokenOk(String given) {
        if (!enabled() || given == null) return false;
        return MessageDigest.isEqual(token.trim().getBytes(StandardCharsets.UTF_8), given.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** Returns null when accepted, or the reason it was refused. */
    public String accept(String ip, Map<String, Object> report) {
        Object n = report.get("name");
        if (!(n instanceof String name) || !NAME.matcher(name).matches()) return "Invalid machine name";
        if (!machines.containsKey(name) && machines.size() >= MAX_MACHINES) return "Too many machines";
        Entry e = new Entry();
        e.report = report;
        e.at = System.currentTimeMillis();
        e.ip = ip;
        machines.put(name, e);
        updateAlert(name, report.get("tasks"));
        long cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        machines.values().removeIf(x -> x.at < cutoff);
        return null;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Entry> en : machines.entrySet()) {
            Entry e = en.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", en.getKey());
            long age = Math.max(0, (now - e.at) / 1000L);
            m.put("ageSeconds", age);
            m.put("online", age < 150);
            m.put("lastReport", Instant.ofEpochMilli(e.at).toString());
            m.put("fromIp", e.ip);
            m.put("server", e.report.get("server"));
            m.put("tasks", e.report.get("tasks"));
            m.put("ssh", e.report.get("ssh"));
            m.put("agentVersion", e.report.get("agentVersion"));
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
        return out;
    }
}
