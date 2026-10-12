package com.glr.deenwallet.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Admin-only app release metadata. It deliberately uses a separate file and
 * endpoint so User app release metadata is never affected by Admin releases.
 * The APK itself remains protected by the Nginx Basic Auth route. */
@RestController
@RequestMapping("/api/app")
public class AdminAndroidUpdateController {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.admin-android-update.metadata-path:/var/www/deenwallet/admin-downloads/admin-android-update.json}")
    private String metadataPath;
    @Value("${app.admin-android-update.version-code:1}")
    private int versionCode;
    @Value("${app.admin-android-update.version-name:1.0}")
    private String versionName;
    @Value("${app.admin-android-update.download-url:https://deenwallapp.com/admin-downloads/DeenWallet-Admin.apk}")
    private String downloadUrl;
    @Value("${app.admin-android-update.force-update:false}")
    private boolean forceUpdate;
    @Value("${app.admin-android-update.release-notes:Admin app improvements and fixes}")
    private String releaseNotes;

    @GetMapping("/admin-android-update")
    public ResponseEntity<Map<String, Object>> adminAndroidUpdate() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(readMetadata());
    }

    private Map<String, Object> readMetadata() {
        Path path = Path.of(metadataPath).toAbsolutePath().normalize();
        if (Files.isRegularFile(path) && Files.isReadable(path)) {
            try {
                Map<String, Object> metadata = objectMapper.readValue(path.toFile(), new TypeReference<Map<String, Object>>() {});
                return normalizeMetadata(metadata);
            } catch (IOException | RuntimeException ignored) {
                // Fall back to safe configured defaults if a release file is being replaced.
            }
        }
        return Map.of("versionCode", versionCode, "versionName", versionName,
                "downloadUrl", downloadUrl, "forceUpdate", forceUpdate,
                "releaseNotes", parseNotes(releaseNotes));
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        int code = numberValue(metadata.get("versionCode"), versionCode);
        String name = stringValue(metadata.get("versionName"), versionName);
        String url = stringValue(metadata.get("downloadUrl"), downloadUrl);
        boolean force = metadata.get("forceUpdate") == null ? forceUpdate : Boolean.parseBoolean(String.valueOf(metadata.get("forceUpdate")));
        Object rawNotes = metadata.get("releaseNotes");
        List<String> notes = rawNotes instanceof List<?> list
                ? list.stream().map(String::valueOf).map(String::trim).filter(v -> !v.isEmpty()).toList()
                : parseNotes(stringValue(rawNotes, releaseNotes));
        return Map.of("versionCode", code, "versionName", name, "downloadUrl", url,
                "forceUpdate", force, "releaseNotes", notes);
    }

    private List<String> parseNotes(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("\\|" )).map(String::trim).filter(v -> !v.isEmpty()).toList();
    }
    private int numberValue(Object value, int fallback) {
        try { return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return fallback; }
    }
    private String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }
}
