package com.glr.deenwallet.monitoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks this server for weak spots a person would otherwise have to look for by hand (waiting updates, open ports,
 * SSH settings, certificate expiry...) and reads the SSH log for people guessing passwords.
 * Every check is read-only, plain-worded, and says how to fix it. Runs in the background every 5 minutes.
 */
@Service
public class SecurityScanService {

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${app.public-host:api.deenwallapp.com}")
    private String publicHost;

    private volatile Map<String, Object> scan = new LinkedHashMap<>();
    private volatile Map<String, Object> ssh = new LinkedHashMap<>();

    public Map<String, Object> vulnerabilities() { return scan; }
    public Map<String, Object> sshAttempts() { return ssh; }

    @Scheduled(fixedDelay = 300_000, initialDelay = 8_000)
    public void refresh() {
        try { scan = runChecks(); } catch (RuntimeException e) { Map<String, Object> m = new LinkedHashMap<>(); m.put("items", List.of()); m.put("error", "The check could not run."); scan = m; }
        try { ssh = readSsh(); } catch (RuntimeException e) { Map<String, Object> m = new LinkedHashMap<>(); m.put("available", false); m.put("hint", "The SSH log could not be read."); ssh = m; }
    }

    private static Map<String, Object> item(String level, String title, String detail, String fix) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);   // bad, warn, info, ok
        m.put("title", title);
        m.put("detail", detail);
        m.put("fix", fix);
        return m;
    }

    private Map<String, Object> runChecks() {
        List<Map<String, Object>> items = new ArrayList<>();

        // 1) waiting system updates
        try {
            Process pr = new ProcessBuilder("apt", "list", "--upgradable").redirectErrorStream(false).start();
            boolean done = pr.waitFor(20, TimeUnit.SECONDS);
            if (!done) { pr.destroyForcibly(); throw new IllegalStateException("timeout"); }
            int total = 0, sec = 0;
            try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.contains("/") || line.startsWith("Listing")) continue;
                    total++;
                    if (line.contains("-security")) sec++;
                }
            }
            if (sec > 0) items.add(item("bad", sec + " security update(s) are waiting", total + " package update(s) are waiting in total. Security updates close holes attackers already know about.",
                    "Run on the server: sudo apt update && sudo apt upgrade -y   (then restart the app if it asks)"));
            else if (total > 0) items.add(item("warn", total + " system update(s) are waiting", "None of them is marked as a security update.", "Run on the server: sudo apt update && sudo apt upgrade -y"));
            else items.add(item("ok", "System packages are up to date", "No updates are waiting (based on the server's last package list refresh).", ""));
        } catch (Exception e) {
            items.add(item("info", "Waiting updates could not be checked", "This server does not use apt or the app is not allowed to run it.", "Check by hand: sudo apt update && apt list --upgradable"));
        }

        if (new File("/var/run/reboot-required").exists() || new File("/run/reboot-required").exists()) {
            items.add(item("warn", "The server needs a restart", "Some updates only take effect after a restart.", "Run on the server: sudo reboot   (do it when few people are using the app)"));
        }

        // 2) automatic security updates
        String auto = String.join("\n", SecurityScanSupport.readAll("/etc/apt/apt.conf.d/20auto-upgrades"));
        if (auto.contains("Unattended-Upgrade \"1\"")) items.add(item("ok", "Automatic security updates are on", "The server installs important security fixes by itself.", ""));
        else if (new File("/etc/apt/apt.conf.d").exists()) items.add(item("warn", "Automatic security updates are not on", "Nobody installs security fixes unless you do it by hand.", "Run on the server: sudo apt install unattended-upgrades && sudo dpkg-reconfigure -plow unattended-upgrades"));

        // 3) SSH settings
        List<String> sshFiles = new ArrayList<>();
        File dir = new File("/etc/ssh/sshd_config.d");
        File[] extra = dir.listFiles((d, n) -> n.endsWith(".conf"));
        if (extra != null) { java.util.Arrays.sort(extra); for (File f : extra) sshFiles.add(f.getPath()); }
        sshFiles.add("/etc/ssh/sshd_config");
        String pwAuth = null, rootLogin = null;
        boolean readable = false;
        for (String f : sshFiles) {
            List<String> lines = SecurityScanSupport.readAll(f);
            if (!lines.isEmpty()) readable = true;
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith("#") || t.isEmpty()) continue;
                String[] p = t.split("\\s+");
                if (p.length < 2) continue;
                if (pwAuth == null && p[0].equalsIgnoreCase("PasswordAuthentication")) pwAuth = p[1].toLowerCase();
                if (rootLogin == null && p[0].equalsIgnoreCase("PermitRootLogin")) rootLogin = p[1].toLowerCase();
            }
        }
        if (readable) {
            if ("yes".equals(pwAuth)) items.add(item("bad", "SSH accepts passwords", "Anyone on the internet can keep guessing your server password. Keys cannot be guessed.",
                    "Use SSH keys only: set PasswordAuthentication no in /etc/ssh/sshd_config, then sudo systemctl restart ssh (keep your key login working first)."));
            else if (pwAuth == null) items.add(item("warn", "SSH password setting is not set", "The server's default allows password logins unless a setting turns them off.",
                    "Add PasswordAuthentication no to /etc/ssh/sshd_config and run sudo systemctl restart ssh (keep your key login working first)."));
            else items.add(item("ok", "SSH uses keys only", "Password guessing against SSH cannot succeed.", ""));
            if ("yes".equals(rootLogin)) items.add(item("bad", "SSH allows logging in as root", "Attackers always try the root account first.", "Set PermitRootLogin no in /etc/ssh/sshd_config and restart ssh."));
        }

        // 4) brute-force protection and firewall
        if (new File("/etc/fail2ban").exists() || new File("/run/fail2ban/fail2ban.sock").exists())
            items.add(item("ok", "fail2ban is installed", "Addresses that keep failing to log in are blocked automatically.", ""));
        else items.add(item("warn", "No automatic blocking of attackers (fail2ban)", "People who keep guessing SSH passwords are never blocked.", "Run on the server: sudo apt install fail2ban -y"));
        List<String> ufw = SecurityScanSupport.readAll("/etc/ufw/ufw.conf");
        if (!ufw.isEmpty()) {
            if (ufw.stream().anyMatch(s -> s.trim().equalsIgnoreCase("ENABLED=yes"))) items.add(item("ok", "Firewall (ufw) is on", "", ""));
            else items.add(item("info", "Firewall (ufw) is off", "On Oracle Cloud the cloud firewall (Security List) may already protect the server. Check it in the Oracle console.", "Optional: sudo ufw allow 22,80,443/tcp && sudo ufw enable"));
        }

        // 5) ports open to the whole internet
        try {
            TreeMap<Integer, Boolean> open = SecurityScanSupport.publicPorts();
            Set<Integer> risky = Set.of(5432, 3306, 6379, 27017, 9200, 11211, 2375, 5984);
            List<Integer> riskyOpen = new ArrayList<>(), otherOpen = new ArrayList<>(), normal = new ArrayList<>();
            for (int p : open.keySet()) {
                if (p == 22 || p == 80 || p == 443) normal.add(p);
                else if (risky.contains(p)) riskyOpen.add(p);
                else otherOpen.add(p);
            }
            if (!riskyOpen.isEmpty()) items.add(item("bad", "Database / internal port open to everyone: " + riskyOpen,
                    "These ports should only be reachable from this server itself.", "Make the service listen on 127.0.0.1 only (for PostgreSQL: listen_addresses = 'localhost'), and close the port in the Oracle Security List."));
            if (!otherOpen.isEmpty()) items.add(item(otherOpen.contains(serverPort) ? "warn" : "info", "Other ports listening on all addresses: " + otherOpen,
                    otherOpen.contains(serverPort) ? "The app's own port " + serverPort + " is open to everyone, so people can bypass the web server in front of it (if any)." : "Make sure you recognise each of these.",
                    "If a service does not need the internet, make it listen on 127.0.0.1 and close the port in the Oracle Security List."));
            if (riskyOpen.isEmpty() && otherOpen.isEmpty()) items.add(item("ok", "Only the normal ports are open", "Open to the internet from this server: " + normal + ". (Oracle's cloud firewall is checked separately in the Oracle console.)", ""));
        } catch (RuntimeException ignored) { }

        // 6) certificate expiry
        try {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (Socket raw = new Socket()) {
                raw.connect(new InetSocketAddress(publicHost, 443), 4000);
                raw.setSoTimeout(4000);
                try (SSLSocket s = (SSLSocket) f.createSocket(raw, publicHost, 443, true)) {
                    s.startHandshake();
                    X509Certificate c = (X509Certificate) s.getSession().getPeerCertificates()[0];
                    long days = Duration.between(Instant.now(), c.getNotAfter().toInstant()).toDays();
                    if (days < 7) items.add(item("bad", "The HTTPS certificate expires in " + Math.max(days, 0) + " day(s)", "When it expires the apps stop working for everybody.", "Renew now: sudo certbot renew   (and check that auto-renewal is working)"));
                    else if (days < 21) items.add(item("warn", "The HTTPS certificate expires in " + days + " days", "It should have renewed by itself already.", "Run: sudo certbot renew --dry-run and look for errors."));
                    else items.add(item("ok", "HTTPS certificate is fine", "Valid for another " + days + " days (" + publicHost + ").", ""));
                }
            }
        } catch (Exception e) {
            items.add(item("info", "HTTPS certificate could not be checked", "This server could not open " + publicHost + ":443 to look.", ""));
        }

        // 7) Java version
        int feature = Runtime.version().feature();
        if (feature < 21) items.add(item("warn", "Old Java version (" + feature + ")", "Old Java versions stop receiving security fixes.", "Install a newer Java (21 or later) and restart the app."));

        items.sort(Comparator.comparingInt(m -> rank(String.valueOf(m.get("level")))));
        int bad = 0, warn = 0;
        for (Map<String, Object> m : items) {
            if ("bad".equals(m.get("level"))) bad++;
            else if ("warn".equals(m.get("level"))) warn++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("bad", bad);
        out.put("warn", warn);
        out.put("scannedAt", Instant.now().toString());
        return out;
    }

    private static int rank(String level) {
        return switch (level) { case "bad" -> 0; case "warn" -> 1; case "info" -> 2; default -> 3; };
    }

    // ---- SSH log ----
    private static final Pattern FAILED = Pattern.compile("Failed password for (?:invalid user )?(\\S+) from (\\S+) port");
    private static final Pattern ACCEPTED = Pattern.compile("Accepted (\\S+) for (\\S+) from (\\S+) port");

    private Map<String, Object> readSsh() {
        Map<String, Object> out = new LinkedHashMap<>();
        String file = new File("/var/log/auth.log").exists() ? "/var/log/auth.log" : "/var/log/secure";
        Path p = Path.of(file);
        if (!Files.exists(p)) {
            out.put("available", false);
            out.put("hint", "This server has no SSH log file (/var/log/auth.log).");
            return out;
        }
        if (!Files.isReadable(p)) {
            out.put("available", false);
            out.put("hint", "The app is not allowed to read the SSH log. On the server run: sudo usermod -aG adm <the user that runs the app>  then restart the app.");
            return out;
        }
        Map<String, Integer> perIp = new LinkedHashMap<>();
        Map<String, Set<String>> users = new LinkedHashMap<>();
        List<Map<String, Object>> accepted = new ArrayList<>();
        int attempts = 0;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            long from = Math.max(0, len - 4_000_000L);
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            String[] lines = new String(buf, StandardCharsets.UTF_8).split("\n");
            for (String line : lines) {
                if (!line.contains("sshd")) continue;
                Matcher m = FAILED.matcher(line);
                if (m.find()) {
                    attempts++;
                    perIp.merge(m.group(2), 1, Integer::sum);
                    users.computeIfAbsent(m.group(2), k -> new LinkedHashSet<>()).add(m.group(1));
                    continue;
                }
                Matcher a = ACCEPTED.matcher(line);
                if (a.find()) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("time", line.matches("^\\d{4}-.*") ? line.split("\\s+")[0] : (line.length() > 15 ? line.substring(0, 15) : line));
                    e.put("how", a.group(1));
                    e.put("user", a.group(2));
                    e.put("ip", a.group(3));
                    accepted.add(e);
                    if (accepted.size() > 50) accepted.remove(0);
                }
            }
        } catch (Exception e) {
            out.put("available", false);
            out.put("hint", "The SSH log could not be read: " + e.getClass().getSimpleName());
            return out;
        }
        List<Map<String, Object>> top = new ArrayList<>();
        perIp.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(8).forEach(en -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ip", en.getKey());
            m.put("attempts", en.getValue());
            Set<String> u = users.getOrDefault(en.getKey(), Set.of());
            m.put("usernames", u.stream().limit(4).toList());
            m.put("level", en.getValue() >= 50 ? "high" : en.getValue() >= 10 ? "medium" : "low");
            top.add(m);
        });
        out.put("available", true);
        out.put("attempts", attempts);
        out.put("uniqueIps", perIp.size());
        out.put("topIps", top);
        int from = Math.max(0, accepted.size() - 5);
        List<Map<String, Object>> last = new ArrayList<>(accepted.subList(from, accepted.size()));
        java.util.Collections.reverse(last);
        out.put("recentLogins", last);
        return out;
    }
}
