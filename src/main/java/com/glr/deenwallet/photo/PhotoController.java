package com.glr.deenwallet.photo;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Profile photos. A customer or an admin sets and removes their own photo; any admin can read the photos
 * shown next to accounts in the admin app. Paths are already protected by role in SecurityConfig
 * (/api/users/** = customers, /api/admin/** = admins).
 */
@RestController
@RequiredArgsConstructor
public class PhotoController {

    private final UserPhotoService photos;

    @Data
    public static class PhotoRequest {
        private String image;
    }

    // ---- the signed-in customer
    @GetMapping("/api/users/me/photo")
    public ResponseEntity<Map<String, Object>> userGet() {
        return ResponseEntity.ok(photos.get(meId()));
    }

    @PostMapping("/api/users/me/photo")
    public ResponseEntity<Map<String, Object>> userSet(@RequestBody PhotoRequest r) {
        return ResponseEntity.ok(saved(photos.save(meId(), r.getImage()).toEpochMilli()));
    }

    @DeleteMapping("/api/users/me/photo")
    public ResponseEntity<Void> userDelete() {
        photos.remove(meId());
        return ResponseEntity.noContent().build();
    }

    // ---- the signed-in admin
    @GetMapping("/api/admin/me/photo")
    public ResponseEntity<Map<String, Object>> adminGet() {
        return ResponseEntity.ok(photos.get(meId()));
    }

    @PostMapping("/api/admin/me/photo")
    public ResponseEntity<Map<String, Object>> adminSet(@RequestBody PhotoRequest r) {
        return ResponseEntity.ok(saved(photos.save(meId(), r.getImage()).toEpochMilli()));
    }

    @DeleteMapping("/api/admin/me/photo")
    public ResponseEntity<Void> adminDelete() {
        photos.remove(meId());
        return ResponseEntity.noContent().build();
    }

    // ---- admins viewing other accounts
    @GetMapping("/api/admin/photos/index")
    public ResponseEntity<Map<String, Object>> index() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versions", photos.versions());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/api/admin/photos")
    public ResponseEntity<Map<String, Object>> many(@RequestParam("ids") String ids) {
        List<UUID> list = new ArrayList<>();
        for (String s : ids.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            try {
                list.add(UUID.fromString(t));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid id.");
            }
            if (list.size() > 40) throw new IllegalArgumentException("Too many photos requested at once.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("photos", photos.getMany(list));
        return ResponseEntity.ok(out);
    }

    private static Map<String, Object> saved(long version) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", version);
        return out;
    }

    private static UUID meId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new SecurityException("Not authenticated");
        return UUID.fromString((String) auth.getPrincipal());
    }
}
