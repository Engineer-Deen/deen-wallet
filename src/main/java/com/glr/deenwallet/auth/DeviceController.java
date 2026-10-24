package com.glr.deenwallet.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** "Trusted Devices": the phones and browsers that are signed in to the customer's account. */
@RestController
@RequestMapping("/api/users/me/devices")
public class DeviceController {

    private final DeviceSessionService devices;

    public DeviceController(DeviceSessionService devices) {
        this.devices = devices;
    }

    @GetMapping
    public ResponseEntity<List<DeviceSessionService.DeviceView>> list(
            Authentication authentication,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        UUID userId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(devices.list(userId, DeviceInfo.cleanDeviceKey(deviceId)));
    }

    @DeleteMapping("/others")
    public ResponseEntity<Map<String, Integer>> removeOthers(
            Authentication authentication,
            @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        UUID userId = UUID.fromString(authentication.getName());
        int removed = devices.removeOthers(userId, DeviceInfo.cleanDeviceKey(deviceId));
        return ResponseEntity.ok(Map.of("removed", removed));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(Authentication authentication, @PathVariable("id") String id) {
        UUID sessionId;
        try {
            sessionId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown device.");
        }
        UUID userId = UUID.fromString(authentication.getName());
        if (!devices.remove(userId, sessionId)) {
            throw new IllegalArgumentException("This device is no longer signed in.");
        }
        return ResponseEntity.noContent().build();
    }
}
