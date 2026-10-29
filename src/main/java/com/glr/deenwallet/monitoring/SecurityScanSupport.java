package com.glr.deenwallet.monitoring;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Small read-only helpers for the security scan. */
final class SecurityScanSupport {

    private SecurityScanSupport() { }

    static List<String> readAll(String file) {
        try {
            Path p = Path.of(file);
            if (!Files.isReadable(p)) return new ArrayList<>();
            return Files.readAllLines(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** TCP ports this machine is listening on for ALL addresses (so reachable from outside), read from /proc. */
    static TreeMap<Integer, Boolean> publicPorts() {
        TreeMap<Integer, Boolean> ports = new TreeMap<>();
        for (String file : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            boolean v6 = file.endsWith("6");
            for (String line : readAll(file)) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 4 || !"0A".equals(f[3])) continue; // 0A = listening
                String[] local = f[1].split(":");
                if (local.length != 2) continue;
                boolean any = v6 ? local[0].equals("00000000000000000000000000000000") : local[0].equals("00000000");
                if (!any) continue;
                try { ports.put(Integer.parseInt(local[1], 16), Boolean.TRUE); } catch (NumberFormatException ignored) { }
            }
        }
        return ports;
    }
}
