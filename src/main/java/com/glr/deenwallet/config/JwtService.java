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
    public String generateRefreshToken(String userId) { return generateRefreshToken(userId, "USER"); }
    public String generateRefreshToken(String userId, String role) { return create(userId, "refresh", role, refreshDays * 86_400_000L); }

    private String create(String userId, String type, String role, long ttl) {
        Date now = new Date();
        return Jwts.builder().id(UUID.randomUUID().toString()).claim("type", type).claim("role", role)
                .subject(userId).issuedAt(now).expiration(new Date(now.getTime() + ttl)).signWith(key()).compact();
    }

    public String extractUserId(String token) { return extractClaim(token, Claims::getSubject); }
    public String extractJti(String token) { return extractClaim(token, Claims::getId); }
    public String extractRole(String token) { return extractClaim(token, c -> c.get("role", String.class)); }
    public Date extractExpiration(String token) { return extractClaim(token, Claims::getExpiration); }
    public <T> T extractClaim(String token, Function<Claims, T> resolver) { return resolver.apply(parse(token)); }
    private Claims parse(String token) { return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload(); }
    public boolean isValid(String token) { try { parse(token); return !extractExpiration(token).before(new Date()); } catch (Exception e) { return false; } }
    public boolean isAccessToken(String token) { try { return "access".equals(parse(token).get("type", String.class)) && !extractExpiration(token).before(new Date()); } catch (Exception e) { return false; } }
    public boolean isRefreshToken(String token) { try { return "refresh".equals(parse(token).get("type", String.class)) && !extractExpiration(token).before(new Date()); } catch (Exception e) { return false; } }
    public Boolean validateToken(String token, String userId) { return isAccessToken(token) && userId.equals(extractUserId(token)); }
    public String issueToken(String userId) { return generateAccessToken(userId); }
}
