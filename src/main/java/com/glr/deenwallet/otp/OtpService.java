package com.glr.deenwallet.otp;

import com.glr.deenwallet.email.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class OtpService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final EmailOtpRepository repo;
    private final EmailService email;
    private final PasswordEncoder encoder;
    private final ConcurrentHashMap<String, Object> emailLocks = new ConcurrentHashMap<>();

    @Value("${app.otp.length:6}") int length;
    @Value("${app.otp.expiry-minutes:10}") int expiryMinutes;
    @Value("${app.otp.resend-cooldown-seconds:60}") int resendCooldownSeconds;

    @Transactional
    public void generateAndSend(String rawEmail) {
        String emailAddr = rawEmail.trim().toLowerCase(Locale.ROOT);
        Object lock = emailLocks.computeIfAbsent(emailAddr, k -> new Object());
        try {
            synchronized (lock) {
                generateAndSendLocked(emailAddr);
            }
        } finally {
            emailLocks.remove(emailAddr, lock);
        }
    }

    protected void generateAndSendLocked(String emailAddr) {
        Instant now = Instant.now();
        Optional<EmailOtp> latest = repo.findTopByEmailAndUsedFalseOrderByCreatedAtDesc(emailAddr);
        if (latest.isPresent()) {
            Instant nextAllowed = latest.get().getCreatedAt().plusSeconds(resendCooldownSeconds);
            if (nextAllowed.isAfter(now)) {
                long remaining = Math.max(1, Duration.between(now, nextAllowed).getSeconds() + 1);
                throw new IllegalArgumentException("Please wait " + remaining + " seconds before requesting another code.");
            }
        }
        if (repo.countByEmailAndCreatedAtAfter(emailAddr, now.minus(Duration.ofMinutes(15))) >= 5) {
            throw new IllegalStateException("Too many OTP requests. Please try again later.");
        }

        String code = randomDigits(length);
        latest.ifPresent(x -> { x.setUsed(true); repo.save(x); });
        repo.save(EmailOtp.builder()
                .email(emailAddr)
                .code(encoder.encode(code))
                .expiresAt(now.plus(expiryMinutes, ChronoUnit.MINUTES))
                .used(false)
                .attempts(0)
                .build());
        email.sendOtpEmail(emailAddr, code, expiryMinutes);
    }

    @Transactional
    public boolean verify(String rawEmail, String submitted) {
        String emailAddr = rawEmail.trim().toLowerCase(Locale.ROOT);
        EmailOtp otp = repo.findTopByEmailAndUsedFalseOrderByCreatedAtDesc(emailAddr).orElse(null);
        if (otp == null || otp.isExpired() || otp.getAttempts() >= 5) return false;
        if (!encoder.matches(submitted, otp.getCode())) {
            otp.setAttempts(otp.getAttempts() + 1);
            repo.save(otp);
            return false;
        }
        otp.setUsed(true);
        repo.save(otp);
        return true;
    }

    private String randomDigits(int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(RANDOM.nextInt(10));
        return b.toString();
    }
}
