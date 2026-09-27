package com.glr.deenwallet.notification;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The app calls POST on login/app-start to register (or refresh) its FCM
 * token, and DELETE on logout so a signed-out device stops receiving
 * notifications meant for that account.
 */
@RestController
@RequestMapping("/api/users/me/device-token")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenRepository deviceTokenRepository;

    @PostMapping
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterDeviceTokenRequest request) {
        UUID userId = currentUserId();

        DeviceToken deviceToken = deviceTokenRepository.findByFcmToken(request.fcmToken())
                .orElseGet(DeviceToken::new);
        deviceToken.setUserId(userId);
        deviceToken.setFcmToken(request.fcmToken());
        deviceToken.setPlatform(request.platform());
        deviceTokenRepository.save(deviceToken);

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> unregister(@Valid @RequestBody RegisterDeviceTokenRequest request) {
        UUID userId = currentUserId();
        deviceTokenRepository.deleteByUserIdAndFcmToken(userId, request.fcmToken());
        return ResponseEntity.noContent().build();
    }

    private UUID currentUserId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new SecurityException("Not authenticated");
        }
        return UUID.fromString((String) authentication.getPrincipal());
    }
}
