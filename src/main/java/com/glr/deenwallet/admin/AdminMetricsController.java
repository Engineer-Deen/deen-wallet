package com.glr.deenwallet.admin;

import com.glr.deenwallet.monitoring.BlockedIpService;
import com.glr.deenwallet.monitoring.DatabaseMetricsService;
import com.glr.deenwallet.monitoring.SecurityEventService;
import com.glr.deenwallet.monitoring.SecurityScanService;
import com.glr.deenwallet.monitoring.ServerMetricsService;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Live health of the server, the app and the database, plus attacks and weak spots. Super admins only. */
@RestController
@RequestMapping("/api/admin/metrics")
public class AdminMetricsController {

    private static final long CACHE_MS = 2500;

    private final ServerMetricsService serverMetrics;
    private final DatabaseMetricsService databaseMetrics;
    private final SecurityEventService securityEvents;
    private final SecurityScanService securityScan;
    private final UserRepository users;
    private final BlockedIpService blockedIps;

    @Value("${server.port:8080}")
    private int serverPort;

    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    public AdminMetricsController(ServerMetricsService serverMetrics, DatabaseMetricsService databaseMetrics,
                                  SecurityEventService securityEvents, SecurityScanService securityScan, UserRepository users,
                                  BlockedIpService blockedIps) {
        this.blockedIps = blockedIps;
        this.serverMetrics = serverMetrics;
        this.databaseMetrics = databaseMetrics;
        this.securityEvents = securityEvents;
        this.securityScan = securityScan;
        this.users = users;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> metrics() {
        requireSuperAdmin();
        long now = System.currentTimeMillis();
        Map<String, Object> c = cached;
        if (c == null || now - cachedAt >= CACHE_MS) {
            c = build();
            cached = c;
            cachedAt = now;
        }
        return ResponseEntity.ok(c);
    }

    private User requireSuperAdmin() {
        User u = null;
        try {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a != null && a.isAuthenticated() && a.getPrincipal() instanceof String s) {
                u = users.findById(UUID.fromString(s)).orElse(null);
            }
        } catch (RuntimeException ignored) { }
        if (u == null || !"SUPER_ADMIN".equals(u.getRole())) {
            throw new AdminAccessException("Server metrics are for super admins only.");
        }
        return u;
    }

    public record BlockRequest(List<String> ips, Integer hours, String reason) { }

    public record UnblockRequest(List<String> ips) { }

    private static String audited(List<String> ips) {
        if (ips == null || ips.isEmpty()) return "no addresses";
        String s = ips.size() + " address(es): " + String.join(", ", ips.stream().limit(5).toList()) + (ips.size() > 5 ? ", ..." : "");
        return s.length() > 140 ? s.substring(0, 140) : s;
    }

    /** Block the chosen addresses (hours = how long; empty = until unblocked). Never your own address. */
    @PostMapping("/block")
    public ResponseEntity<Map<String, Object>> block(@RequestBody BlockRequest body, HttpServletRequest http) {
        User me = requireSuperAdmin();
        http.setAttribute("auditTargetName", audited(body.ips()));
        Map<String, Object> result = blockedIps.block(body.ips(), body.hours(), body.reason() == null || body.reason().isBlank() ? "Blocked from Server Metrics" : body.reason(),
                me.getFullName(), http.getRemoteAddr());
        cached = null;
        return ResponseEntity.ok(result);
    }

    @PostMapping("/unblock")
    public ResponseEntity<Map<String, Object>> unblock(@RequestBody UnblockRequest body, HttpServletRequest http) {
        requireSuperAdmin();
        http.setAttribute("auditTargetName", audited(body.ips()));
        int n = blockedIps.unblock(body.ips());
        cached = null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unblocked", n);
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> build() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> server = serverMetrics.server();
        Map<String, Object> app = serverMetrics.app(serverPort);
        Map<String, Object> db = databaseMetrics.collect();
        Map<String, Object> attacks = securityEvents.overview();
        Map<String, Object> vulns = securityScan.vulnerabilities();
        Map<String, Object> ssh = securityScan.sshAttempts();
        out.put("time", Instant.now().toString());
        out.put("server", server);
        out.put("app", app);
        out.put("database", db);
        out.put("attacks", attacks);
        out.put("ssh", ssh);
        out.put("vulnerabilities", vulns);
        out.put("blockedIps", blockedIps.list());
        out.put("health", health(server, app, db, attacks, vulns));
        return out;
    }

    private static double n(Object o) { return o instanceof Number x ? x.doubleValue() : 0.0; }

    private static void issue(List<Map<String, Object>> list, String level, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("text", text);
        list.add(m);
    }

    /** One plain verdict plus the reasons behind it. */
    private static Map<String, Object> health(Map<String, Object> server, Map<String, Object> app, Map<String, Object> db,
                                              Map<String, Object> attacks, Map<String, Object> vulns) {
        List<Map<String, Object>> issues = new ArrayList<>();
        double cpu = n(server.get("cpuPercent")), ram = n(server.get("ramPercent")), disk = n(server.get("diskPercent"));
        double cores = Math.max(1, n(server.get("cores"))), load1 = n(server.get("load1"));
        if (cpu >= 90) issue(issues, "bad", "The processor is almost fully used (" + Math.round(cpu) + "%).");
        else if (cpu >= 75) issue(issues, "warn", "The processor is busy (" + Math.round(cpu) + "%).");
        if (load1 / cores >= 2) issue(issues, "bad", "The server is overloaded: more work is queued than it can do.");
        else if (load1 / cores >= 1) issue(issues, "warn", "The server load is high for its size.");
        if (ram >= 92) issue(issues, "bad", "Memory (RAM) is almost full (" + Math.round(ram) + "%).");
        else if (ram >= 80) issue(issues, "warn", "Memory (RAM) is getting full (" + Math.round(ram) + "%).");
        if (disk >= 90) issue(issues, "bad", "Storage is almost full (" + Math.round(disk) + "%). The app and database can stop working when it fills.");
        else if (disk >= 80) issue(issues, "warn", "Storage is filling up (" + Math.round(disk) + "%).");
        if (n(app.get("heapPercent")) >= 90) issue(issues, "warn", "The app's own Java memory is nearly full (" + Math.round(n(app.get("heapPercent"))) + "%).");
        Object reqs = app.get("requests");
        if (reqs instanceof Map<?, ?> r && n(r.get("serverErrors5Min")) >= 5) issue(issues, "warn", "The app returned " + Math.round(n(r.get("serverErrors5Min"))) + " server errors in the last 5 minutes.");
        if (!Boolean.TRUE.equals(db.get("up"))) {
            issue(issues, "bad", "The database is not answering.");
        } else {
            double lat = n(db.get("latencyMs"));
            if (lat >= 1000) issue(issues, "bad", "The database is very slow (" + Math.round(lat) + " ms to answer).");
            else if (lat >= 200) issue(issues, "warn", "The database is slow (" + Math.round(lat) + " ms to answer).");
            double conns = n(db.get("connections")), max = n(db.get("maxConnections"));
            if (max > 0 && conns / max >= 0.8) issue(issues, "warn", "The database has almost used all its connections (" + Math.round(conns) + " of " + Math.round(max) + ").");
            Object hit = db.get("cacheHitPercent");
            if (hit != null && n(hit) < 90) issue(issues, "warn", "The database is reading from disk more than usual (cache hit " + n(hit) + "%).");
            if (n(db.get("longestQuerySeconds")) >= 30) issue(issues, "warn", "A database query has been running for " + Math.round(n(db.get("longestQuerySeconds"))) + " seconds.");
        }
        if (n(vulns.get("bad")) > 0) issue(issues, "bad", Math.round(n(vulns.get("bad"))) + " serious security weak spot(s) found. See Weak spots below.");
        else if (n(vulns.get("warn")) > 0) issue(issues, "warn", Math.round(n(vulns.get("warn"))) + " security item(s) need attention. See Weak spots below.");
        Object topIps = attacks.get("topIps");
        if (topIps instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m && "high".equals(m.get("level"))) { issue(issues, "warn", "Heavy attack activity from " + m.get("ip") + " in the last 24 hours."); break; }
            }
        }
        Object kinds = attacks.get("byKind");
        if (kinds instanceof Map<?, ?> k && n(k.get("SQLI")) > 0) {
            issue(issues, "warn", "SQL injection attempts were detected (" + Math.round(n(k.get("SQLI"))) + " in 24 hours). The app passes typed text to the database safely, but block the sender(s) below.");
        }
        String level = "ok";
        for (Map<String, Object> m : issues) {
            if ("bad".equals(m.get("level"))) { level = "bad"; break; }
            level = "warn";
        }
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("level", level);
        h.put("headline", "ok".equals(level) ? "Everything looks healthy" : "bad".equals(level) ? "Needs attention now" : "Working, but watch these");
        h.put("issues", issues);
        return h;
    }
}
