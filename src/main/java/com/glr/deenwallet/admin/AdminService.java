package com.glr.deenwallet.admin;

import com.glr.deenwallet.email.EmailService;
import com.glr.deenwallet.transaction.TransactionRepository;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final EmailService emailService;

    @Transactional
    public User toggleUserActive(UUID userId, boolean active) {
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        boolean wasActive = user.isActive();
        boolean wasLocked = user.isLocked();
        user.setActive(active);
        if (active && wasLocked) {
            user.setLocked(false);
            user.setPinAttempts(0);
            user.setLockedAt(null);
            user.setLockedBy(null);
            user.setLoginFailedAttempts(0);
            user.setLoginLockedUntil(null);
            user.setLastLoginFailedAt(null);
            user.setAdminRecoveryRequired(false);
        }
        User saved = userRepository.save(user);
        if (active && !wasActive) emailService.sendAccountActivatedEmail(saved);
        else if (!active && wasActive) emailService.sendAccountDeactivatedEmail(saved);
        return saved;
    }

    @Transactional
    public User lockUser(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        if ("SUPER_ADMIN".equals(user.getRole())) throw new SecurityException("Cannot lock super admin.");
        userRepository.lockUserFast(userId, Instant.now(), null);
        User locked = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        emailService.sendAccountLockedEmail(locked);
        return locked;
    }

    @Transactional
    public User unlockUser(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        if ("SUPER_ADMIN".equals(user.getRole())) throw new SecurityException("Super admin cannot be locked/unlocked.");
        int changed = userRepository.unlockUserFast(userId);
        User unlocked = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (changed > 0) {
            emailService.sendAccountActivatedEmail(unlocked);
        }
        return unlocked;
    }

    @Transactional
    public User resetLoginProtection(UUID userId) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        int changed = userRepository.resetLoginProtection(userId);
        if (changed == 0) {
            return target;
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    public AdminStatsResponse getStats() {
        long totalUsers = userRepository.count();
        long verifiedUsers = userRepository.countByEmailVerifiedTrue();
        long totalTransactions = transactionRepository.count();
        long lockedUsers = userRepository.countByLockedTrue();
        long activeUsers = userRepository.countByActiveTrue();
        Long totalVolumeMinor = transactionRepository.sumAmountValue();
        double totalVolume = totalVolumeMinor != null ? totalVolumeMinor / 100.0 : 0.0;
        return new AdminStatsResponse(totalUsers, verifiedUsers, totalTransactions, totalVolume, lockedUsers, activeUsers);
    }
}
