package com.glr.deenwallet.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The customer's trusted devices. A device is a login session: it keeps the same id while its
 * refresh token rotates. Removing it ends the session at once.
 */
@Service
public class DeviceSessionService {

    /** An access token lives a few minutes, so a removed session is remembered for longer than that. */
    private static final long REMEMBER_ENDED_MS = 15L * 60L * 1000L;

    public record DeviceView(String id, String name, String platform, boolean current,
                             Instant lastActiveAt, Instant firstLoginAt, String ipMasked) { }

    private final RefreshTokenRepository tokens;
    private final ConcurrentHashMap<String, Long> endedSessions = new ConcurrentHashMap<>();

    public DeviceSessionService(RefreshTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Transactional(readOnly = true)
    public List<DeviceView> list(UUID userId, String currentDeviceKey) {
        Map<UUID, List<RefreshToken>> bySession = activeBySession(userId);
        List<DeviceView> out = new ArrayList<>();
        for (Map.Entry<UUID, List<RefreshToken>> e : bySession.entrySet()) {
            List<RefreshToken> rows = e.getValue();
            RefreshToken latest = rows.stream().max(Comparator.comparing(DeviceSessionService::activity)).orElse(rows.get(0));
            Instant first = rows.stream().map(DeviceSessionService::started).min(Comparator.naturalOrder()).orElse(null);
            boolean current = currentDeviceKey != null && currentDeviceKey.equals(latest.getDeviceKey());
            out.add(new DeviceView(
                    e.getKey().toString(),
                    latest.getDeviceName() == null || latest.getDeviceName().isBlank() ? "Unknown device" : latest.getDeviceName(),
                    latest.getDevicePlatform() == null ? "unknown" : latest.getDevicePlatform(),
                    current,
                    activity(latest),
                    first,
                    DeviceInfo.maskIp(latest.getLastIp())));
        }
        // This device first, then the most recently used.
        out.sort(Comparator.comparing(DeviceView::current).reversed()
                .thenComparing(DeviceView::lastActiveAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /** Ends one session of this customer. Returns false when there was nothing to end. */
    @Transactional
    public boolean remove(UUID userId, UUID sessionId) {
        int changed = tokens.revokeSession(userId, sessionId);
        if (changed > 0) {
            rememberEnded(sessionId);
        }
        return changed > 0;
    }

    /** Ends every session except the ones that belong to the device making the request. */
    @Transactional
    public int removeOthers(UUID userId, String currentDeviceKey) {
        if (currentDeviceKey == null) {
            throw new IllegalArgumentException("This device could not be identified. Please log in again and retry.");
        }
        Map<UUID, List<RefreshToken>> bySession = activeBySession(userId);
        Set<UUID> keep = new HashSet<>();
        for (Map.Entry<UUID, List<RefreshToken>> e : bySession.entrySet()) {
            for (RefreshToken t : e.getValue()) {
                if (currentDeviceKey.equals(t.getDeviceKey())) {
                    keep.add(e.getKey());
                }
            }
        }
        int removed = 0;
        for (UUID sessionId : bySession.keySet()) {
            if (!keep.contains(sessionId) && remove(userId, sessionId)) {
                removed++;
            }
        }
        return removed;
    }

    /** A new login on a device replaces that device's older login instead of listing it twice. */
    @Transactional
    public void endSessionsOfDevice(UUID userId, String deviceKey) {
        if (deviceKey == null) return;
        for (UUID sessionId : tokens.activeSessionIdsForDevice(userId, deviceKey, Instant.now())) {
            if (sessionId != null) {
                remove(userId, sessionId);
            }
        }
    }

    public boolean isEnded(String sessionId) {
        if (sessionId == null || endedSessions.isEmpty()) return false;
        Long until = endedSessions.get(sessionId);
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            endedSessions.remove(sessionId);
            return false;
        }
        return true;
    }

    private void rememberEnded(UUID sessionId) {
        long now = System.currentTimeMillis();
        if (endedSessions.size() > 1000) {
            endedSessions.entrySet().removeIf(e -> e.getValue() < now);
        }
        endedSessions.put(sessionId.toString(), now + REMEMBER_ENDED_MS);
    }

    private Map<UUID, List<RefreshToken>> activeBySession(UUID userId) {
        Map<UUID, List<RefreshToken>> bySession = new HashMap<>();
        for (RefreshToken t : tokens.findActiveForUser(userId, Instant.now())) {
            if (t.getSessionId() == null) continue;
            bySession.computeIfAbsent(t.getSessionId(), k -> new ArrayList<>()).add(t);
        }
        return bySession;
    }

    private static Instant activity(RefreshToken t) {
        return t.getLastUsedAt() != null ? t.getLastUsedAt() : t.getCreatedAt();
    }

    private static Instant started(RefreshToken t) {
        return t.getFirstLoginAt() != null ? t.getFirstLoginAt() : t.getCreatedAt();
    }
}
