package com.glr.deenwallet.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.Instant;

@RestController
@RequestMapping("/api/app")
public class AdminAndroidUpdateController {
    private static final String SECURE_DOWNLOAD_URL =
            "https://api.deenwallapp.com/api/app/admin-android-update/download";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    private static final String AUTH_FAILED = "Invalid or unavailable Admin account";

    private final String dummyHash;

    public AdminAndroidUpdateController(UserRepository users,
                                        PasswordEncoder passwordEncoder,
                                        JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }



    @Value("${app.admin-android-update.metadata-path:/var/www/deenwallet/admin-downloads/admin-android-update.json}")
    private String metadataPath;
    @Value("${app.admin-android-update.apk-path:/var/www/deenwallet/admin-downloads/DeenWallet-Admin.apk}")
    private String apkPath;
    @Value("${app.admin-android-update.version-code:1}")
    private int versionCode;
    @Value("${app.admin-android-update.version-name:1.0}")
    private String versionName;
    @Value("${app.admin-android-update.download-url:https://api.deenwallapp.com/api/app/admin-android-update/download}")
    private String downloadUrl;
    @Value("${app.admin-android-update.force-update:false}")
    private boolean forceUpdate;
    @Value("${app.admin-android-update.release-notes:Admin app improvements and fixes}")
    private String releaseNotes;

    @GetMapping("/admin-android-update")
    public ResponseEntity<Map<String, Object>> adminAndroidUpdate() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(readMetadata());
    }

    /** Validate an existing active Admin account and issue a two-minute, APK-only token. */
    @PostMapping("/admin-android-update/authorize")
    public ResponseEntity<Map<String, Object>> authorizeDownload(@RequestBody DownloadAuthorization request) {
        if (request == null || request.getEmail() == null || request.getPassword() == null
                || request.getEmail().isBlank() || request.getPassword().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Admin email and password are required");
        }

        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);

        User user = users.findByEmailAndRole(email, "SUPER_ADMIN")
                .or(() -> users.findByEmailAndRole(email, "ADMIN"))
                .orElse(null);

        if (user == null) {
            // Same bcrypt cost as a real check so timing does not reveal whether the email exists.
            passwordEncoder.matches(request.getPassword(), dummyHash);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, AUTH_FAILED);
        }

        boolean passwordOk = passwordEncoder.matches(request.getPassword(), user.getPasswordHash());

        if (!passwordOk) {
            boolean recoveryBlocked = "SUPER_ADMIN".equals(user.getRole()) && user.isAdminRecoveryRequired();
            if (user.isActive() && !user.isLocked() && !recoveryBlocked) {
                users.recordAdminPasswordFailure(user.getId(), Instant.now());
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, AUTH_FAILED);
        }

        if (!isEligibleAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, AUTH_FAILED);
        }

        users.clearLoginFailures(user.getId());

        String token = jwtService.generateAdminApkDownloadToken(user.getId().toString());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("downloadToken", token, "expiresInSeconds", 120));
    }


    /** The custom header is deliberately separate from normal Bearer access tokens. */
    @GetMapping("/admin-android-update/download")
    public ResponseEntity<org.springframework.core.io.Resource> downloadAdminApk(
            @RequestHeader(value = "X-Admin-Download-Token", required = false) String token) {
        if (token == null || !jwtService.isAdminApkDownloadToken(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Download authorization expired or invalid");
        }

        User user;
        try {
            user = users.findById(java.util.UUID.fromString(jwtService.extractUserId(token)))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Admin account unavailable"));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid download authorization");
        }
        if (!isEligibleAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin account is no longer authorized");
        }

        Path path = Path.of(apkPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Admin APK is not available");
        }
        try {
            org.springframework.core.io.Resource resource = new org.springframework.core.io.FileSystemResource(path);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.parseMediaType("application/vnd.android.package-archive"))
                    .contentLength(Files.size(path))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename("DeenWallet-Admin.apk").build().toString())
                    .body(resource);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read Admin APK");
        }
    }

    private boolean isEligibleAdmin(User user) {
        return ("ADMIN".equals(user.getRole()) || "SUPER_ADMIN".equals(user.getRole()))
                && user.isActive() && user.isEmailVerified() && !user.isLocked()
                && !("SUPER_ADMIN".equals(user.getRole()) && user.isAdminRecoveryRequired());
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
                "downloadUrl", SECURE_DOWNLOAD_URL, "forceUpdate", forceUpdate,
                "releaseNotes", parseNotes(releaseNotes));
    }

    private Map<String, Object> normalizeMetadata(Map<String, Object> metadata) {
        int code = numberValue(metadata.get("versionCode"), versionCode);
        String name = stringValue(metadata.get("versionName"), versionName);
        // Always use the API download endpoint; do not trust a stale metadata URL that points to Nginx Basic Auth.
        boolean force = metadata.get("forceUpdate") == null ? forceUpdate : Boolean.parseBoolean(String.valueOf(metadata.get("forceUpdate")));
        Object rawNotes = metadata.get("releaseNotes");
        List<String> notes = rawNotes instanceof List<?> list
                ? list.stream().map(String::valueOf).map(String::trim).filter(v -> !v.isEmpty()).toList()
                : parseNotes(stringValue(rawNotes, releaseNotes));
        return Map.of("versionCode", code, "versionName", name,
                "downloadUrl", SECURE_DOWNLOAD_URL, "forceUpdate", force,
                "releaseNotes", notes);
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

    @Getter @Setter
    public static class DownloadAuthorization {
        private String email;
        private String password;
    }
}
