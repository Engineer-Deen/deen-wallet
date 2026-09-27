package com.glr.deenwallet.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

@RestController
@RequestMapping("/api/app")
@RequiredArgsConstructor
public class AppVersionController {

    @Value("${app.release-id}")
    private String releaseId;

    private final String buildCommit = loadBuildCommit();

    @GetMapping("/version")
    public ResponseEntity<Map<String, String>> version() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Map.of(
                        "releaseId", releaseId,
                        // Set at CI build time from the git commit that produced this jar.
                        // "unknown" means this jar was built locally, not by the pipeline.
                        "buildCommit", buildCommit
                ));
    }

    private static String loadBuildCommit() {
        try {
            ClassPathResource resource = new ClassPathResource("build-commit.txt");
            if (!resource.exists()) return "unknown";
            return Files.readString(resource.getFile().toPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "unknown";
        }
    }
}