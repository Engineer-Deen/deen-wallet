package com.glr.deenwallet.config;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Country flags for Send Money Abroad, served from our own address (/flags/gh.png).
 * The first request for a flag fetches it once from flagcdn.com and the server keeps it in memory, so the customer's phone only
 * talks to api.deenwallapp.com and a blocked outside site can no longer break the flags. Public (flags are not secret).
 */
@RestController
public class FlagController {

    private static final Pattern FILE = Pattern.compile("^([a-z]{2})\\.png$");
    private static final int MAX_CACHED = 400;

    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    @GetMapping("/flags/{file}")
    public ResponseEntity<byte[]> flag(@PathVariable String file) {
        java.util.regex.Matcher m = FILE.matcher(file == null ? "" : file);
        if (!m.matches()) return ResponseEntity.notFound().build();
        String code = m.group(1);
        byte[] png = cache.get(code);
        if (png == null) {
            png = fetch(code);
            if (png == null) return ResponseEntity.notFound().build();
            if (cache.size() < MAX_CACHED) cache.put(code, png);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(png);
    }

    private byte[] fetch(String code) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://flagcdn.com/w80/" + code + ".png"))
                    .timeout(Duration.ofSeconds(6)).GET().build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = res.body();
            if (res.statusCode() == 200 && body != null && body.length > 0 && body.length < 100_000) return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // the page falls back to the flag emoji
        }
        return null;
    }
}
