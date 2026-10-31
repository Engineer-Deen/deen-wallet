package com.glr.deenwallet.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /.well-known/assetlinks.json: lets Android trust that the DeenWallet apps may use passkeys for this website.
 * Put the apps' signing-certificate fingerprints in the env file: ANDROID_APP_SHA256 (comma list, AA:BB:... form)
 * and optionally ANDROID_APP_PACKAGES (default com.deenwallapp.admin).
 */
@RestController
public class AssetLinksController {

    @Value("${android.app.sha256:}")
    private String sha256;

    @Value("${android.app.packages:com.deenwallapp.admin}")
    private String packages;

    @GetMapping(value = "/.well-known/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> assetLinks() {
        List<String> prints = new ArrayList<>();
        for (String s : sha256.split(",")) {
            String t = s.trim().toUpperCase();
            if (!t.isEmpty()) prints.add(t);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (!prints.isEmpty()) {
            for (String pkg : packages.split(",")) {
                String name = pkg.trim();
                if (name.isEmpty()) continue;
                out.add(Map.of(
                        "relation", List.of("delegate_permission/common.get_login_creds", "delegate_permission/common.handle_all_urls"),
                        "target", Map.of("namespace", "android_app", "package_name", name, "sha256_cert_fingerprints", prints)));
            }
        }
        return ResponseEntity.ok(out);
    }
}
