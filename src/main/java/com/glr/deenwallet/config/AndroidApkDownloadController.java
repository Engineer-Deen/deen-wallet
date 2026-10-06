package com.glr.deenwallet.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
public class AndroidApkDownloadController {

    @Value("${app.android-update.apk-directory:/var/www/deenwallet/downloads}")
    private String apkDirectory;

    @GetMapping("/downloads/{filename:.+}")
    public ResponseEntity<Resource> downloadApk(@PathVariable String filename) throws Exception {
        if (!filename.matches("DeenWallet-[A-Za-z0-9._-]+\\.apk")) {
            return ResponseEntity.notFound().build();
        }

        Path directory = Path.of(apkDirectory).toAbsolutePath().normalize();
        Path path = directory.resolve(filename).normalize();

        if (!path.startsWith(directory) || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new UrlResource(path.toUri());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.android.package-archive"))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\""
                )
                .contentLength(resource.contentLength())
                .body(resource);
    }
}
