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

@RestController
@RequestMapping("/api/app")
public class AndroidUpdateController {

    private final ObjectMapper objectMapper;

    @Value("${app.android-update.metadata-path:/var/www/deenwallet/android-update.json}")
    private String metadataPath;

    @Value("${app.android-update.version-code:1}")
    private int versionCode;

    @Value("${app.android-update.version-name:1.0}")
    private String versionName;

    @Value("${app.android-update.download-url:}")
    private String downloadUrl;

    @Value("${app.android-update.force-update:false}")
    private boolean forceUpdate;

    @Value("${app.android-update.release-notes:}")
    private String releaseNotes;

    public AndroidUpdateController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @GetMapping("/android-update")
    public ResponseEntity<Map<String, Object>> androidUpdate() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(readMetadata());
    }

    private Map<String, Object> readMetadata() {
        Path path = Path.of(metadataPath).toAbsolutePath().normalize();

        if (Files.isRegularFile(path) && Files.isReadable(path)) {
            try {
                Map<String, Object> metadata = objectMapper.readValue(
                        path.toFile(),
                        new TypeReference<Map<String, Object>>() {}
                );

                return normalizeMetadata(metadata);
            } catch (IOException | RuntimeException ignored) {
                // Fall back to application configuration if the metadata file
                // is temporarily unavailable or malformed.
            }
        }

        return fallbackMetadata();
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        int currentVersionCode = numberValue(metadata.get("versionCode"), versionCode);
        String currentVersionName = stringValue(metadata.get("versionName"), versionName);
        String currentDownloadUrl = stringValue(metadata.get("downloadUrl"), downloadUrl);
        boolean currentForceUpdate = booleanValue(metadata.get("forceUpdate"), forceUpdate);

        Object rawNotes = metadata.get("releaseNotes");
        List<String> notes;

        if (rawNotes instanceof List<?> list) {
            notes = list.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(note -> !note.isEmpty())
                    .toList();
        } else {
            notes = parseReleaseNotes(stringValue(rawNotes, releaseNotes));
        }

        return Map.of(
                "versionCode", currentVersionCode,
                "versionName", currentVersionName,
                "downloadUrl", currentDownloadUrl,
                "forceUpdate", currentForceUpdate,
                "releaseNotes", notes
        );
    }

    private Map<String, Object> fallbackMetadata() {
        return Map.of(
                "versionCode", versionCode,
                "versionName", versionName,
                "downloadUrl", downloadUrl,
                "forceUpdate", forceUpdate,
                "releaseNotes", parseReleaseNotes(releaseNotes)
        );
    }

    private List<String> parseReleaseNotes(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        return Arrays.stream(value.split("\\|"))
                .map(String::trim)
                .filter(note -> !note.isEmpty())
                .toList();
    }

    private int numberValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }

        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }

        String result = String.valueOf(value).trim();
        return result.isEmpty() ? fallback : result;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }

        if (value == null) {
            return fallback;
        }

        return Boolean.parseBoolean(String.valueOf(value));
    }
}
