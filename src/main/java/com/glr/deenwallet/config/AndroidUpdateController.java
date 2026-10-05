package com.glr.deenwallet.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/app")
public class AndroidUpdateController {

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

    @GetMapping("/android-update")
    public ResponseEntity<Map<String, Object>> androidUpdate() {
        List<String> notes = Arrays.stream(releaseNotes.split("\\|"))
                .map(String::trim)
                .filter(note -> !note.isEmpty())
                .toList();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of(
                        "versionCode", versionCode,
                        "versionName", versionName,
                        "downloadUrl", downloadUrl,
                        "forceUpdate", forceUpdate,
                        "releaseNotes", notes
                ));
    }
}
