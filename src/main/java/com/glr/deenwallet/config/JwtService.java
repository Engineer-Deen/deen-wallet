package com.glr.deenwallet.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import java.util.function.Function;

@Service
public class JwtService {
    @Value("${app.jwt.secret}") private String secret;
    @Value("${app.jwt.access-token-expiration-minutes:5}") private long accessMinutes;
    @Value("${app.jwt.refresh-token-expiration-days:7}") private long refreshDays;

    private SecretKey key() {
        if (secret == null || secret.length() < 64) throw new IllegalStateException("JWT_SECRET must be at least 64 characters");
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(String userId) { return generateAccessToken(userId, "USER"); }
    public String generateAccessToken(String userId, String role) { return create(userId, "access", role, accessMinutes * 60_000L); }
    /** Access token that remembers which login session ("trusted device") it belongs to. */
    public String generateAccessToken(String userId, String role, String sessionId) { return create(userId, "access", role, accessMinutes * 60_000L, sessionId); }
    public String generateRefreshToken(String userId) { return generateRefreshToken(userId, "USER"); }
    public String generateRefreshToken(String userId, String role) { return create(userId, "refresh", role, refreshDays * 86_400_000L); }

    /** A short-lived token that can only authorize the Admin APK download endpoint. */
    public String generateAdminApkDownloadToken(String userId) {
        return create(userId, "admin-apk-download", "ADMIN_APK_DOWNLOAD", 120_000L);
    }

    public boolean isAdminApkDownloadToken(String token) {
        try {
            Claims claims = parse(token);
            return "admin-apk-download".equals(claims.get("type", String.class))
                    && "ADMIN_APK_DOWNLOAD".equals(claims.get("role", String.class))
                    && claims.getExpiration() != null
                    && !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    private String create(String userId, String type, String role, long ttl) {
        return create(userId, type, role, ttl, null);
    }

    private String create(String userId, String type, String role, long ttl, String sessionId) {
        Date now = new Date();
        JwtBuilder builder = Jwts.builder().id(UUID.randomUUID().toString()).claim("type", type).claim("role", role);
        if (sessionId != null) builder.claim("sid", sessionId);
        return builder.subject(userId).issuedAt(now).expiration(new Date(now.getTime() + ttl)).signWith(key()).compact();
    }

    public String extractUserId(String token) { return extractClaim(token, Claims::getSubject); }
    public String extractJti(String token) { return extractClaim(token, Claims::getId); }
    public String extractRole(String token) { return extractClaim(token, c -> c.get("role", String.class)); }
    /** The login session this access token belongs to, or null for tokens issued before sessions existed. */
    public String extractSessionId(String token) { return extractClaim(token, c -> c.get("sid", String.class)); }
    public Date extractExpiration(String token) { return extractClaim(token, Claims::getExpiration); }
    public <T> T extractClaim(String token, Function<Claims, T> resolver) { return resolver.apply(parse(token)); }
    private Claims parse(String token) { return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload(); }
    public boolean isValid(String token) { try { parse(token); return !extractExpiration(token).before(new Date()); } catch (Exception e) { return false; } }
    public boolean isAccessToken(String token) { try { return "access".equals(parse(token).get("type", String.class)) && !extractExpiration(token).before(new Date()); } catch (Exception e) { return false; } }
    public boolean isRefreshToken(String token) { try { return "refresh".equals(parse(token).get("type", String.class)) && !extractExpiration(token).before(new Date()); } catch (Exception e) { return false; } }
    public Boolean validateToken(String token, String userId) { return isAccessToken(token) && userId.equals(extractUserId(token)); }
    public String issueToken(String userId) { return generateAccessToken(userId); }
}
