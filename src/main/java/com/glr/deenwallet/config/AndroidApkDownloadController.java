package com.glr.deenwallet.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

@RestController
public class AndroidApkDownloadController {

    @Value("${app.android-update.apk-path:downloads/DeenWallet-1.1.apk}")
    private String apkPath;

    @GetMapping("/downloads/DeenWallet-1.1.apk")
    public ResponseEntity<Resource> downloadApk() throws Exception {
        Path path = Path.of(apkPath).toAbsolutePath().normalize();

        Resource resource = new UrlResource(path.toUri());

        if (!resource.exists() || !resource.isReadable()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.android.package-archive"))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"DeenWallet-1.1.apk\""
                )
                .contentLength(resource.contentLength())
                .body(resource);
    }
}