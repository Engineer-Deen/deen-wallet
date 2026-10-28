package com.glr.deenwallet.admin;

import com.glr.deenwallet.monitoring.ErrorLog;
import com.glr.deenwallet.monitoring.ErrorLogRepository;
import com.glr.deenwallet.monitoring.ErrorReportRequest;
import com.glr.deenwallet.notification.NotificationResponse;
import com.glr.deenwallet.notification.NotificationService;
import com.glr.deenwallet.notification.PushNotificationService;
import com.glr.deenwallet.transaction.Transaction;
import com.glr.deenwallet.transaction.TransactionRepository;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import com.glr.deenwallet.email.EmailService;
import com.glr.deenwallet.otp.OtpService;
import lombok.Data;
import jakarta.validation.Valid; import jakarta.validation.constraints.Email; import jakarta.validation.constraints.NotBlank; import jakarta.validation.constraints.NotEmpty; import jakarta.validation.constraints.Pattern; import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final com.glr.deenwallet.transaction.TransactionArchiveRepository transactionArchiveRepository;
    private final ErrorLogRepository errorLogRepository;
    private final AdminService adminService;
    private final PasswordEncoder passwordEncoder;
    private final com.glr.deenwallet.user.AccountNumberGenerator accountNumberGenerator;
    private final EmailService emailService;
    private final OtpService otpService;
    private final NotificationService notificationService;
    private final PushNotificationService pushNotificationService;

    @Value("${app.support.whatsapp:+23280613600}")
    private String supportWhatsApp;

    @GetMapping("/me")
    public ResponseEntity<AdminUserResponse> me() {
        User user = getCurrentUser();
        validateAdmin();
        return ResponseEntity.ok(AdminUserResponse.from(user));
    }

    @PutMapping("/profile/verify-email")
    public ResponseEntity<AdminUserResponse> verifyOwnAdminEmail() {
        User user = getCurrentUser();
        validateAdmin();
        user.setEmailVerified(true);
        userRepository.save(user);
        return ResponseEntity.ok(AdminUserResponse.from(user));
    }

    // ===== ADMIN IN-APP NOTIFICATIONS =====
    @PostMapping("/notifications")
    public ResponseEntity<List<NotificationResponse>> sendInAppNotification(
            @Valid @RequestBody AdminNotificationRequest request) {
        validateAdmin();

        List<UUID> userIds = request.getUserIds().stream().distinct().toList();
        if (userIds.isEmpty()) {
            throw new IllegalArgumentException("At least one user is required.");
        }

        List<User> users = userRepository.findAllById(userIds);
        if (users.size() != userIds.size()) {
            throw new IllegalArgumentException("One or more users could not be found.");
        }

        for (User user : users) {
            if (!"USER".equals(user.getRole())) {
                throw new IllegalArgumentException("In-app notifications can only be sent to customer users.");
            }
        }

        List<NotificationResponse> notifications = userIds.stream()
                .map(userId -> {
                    NotificationResponse notification = notificationService.create(
                            userId,
                            "ADMIN_MESSAGE",
                            request.getTitle().trim(),
                            request.getMessage().trim(),
                            null,
                            null
                    );

                    pushNotificationService.sendToUser(
                            userId,
                            notification.title(),
                            notification.message(),
                            Map.of(
                                    "type", "ADMIN_MESSAGE",
                                    "notificationId", notification.id().toString()
                            )
                    );

                    return notification;
                })
                .toList();

        log.info("Admin {} sent an in-app notification to {} user(s)",
                getCurrentUser().getEmail(), notifications.size());

        return ResponseEntity.ok(notifications);
    }

    // ===== USERS =====
    // Same JSON shape as before. The role filter and ordering now happen in SQL
    // (idx_users_role_created_at) instead of loading every account and filtering in Java.
    // Optional ?limit= (default 5000, max 10000) and ?page= (0-based) for larger user bases.
    @GetMapping("/users")
    public ResponseEntity<List<AdminUserResponse>> listUsers(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer page) {
        validateAdmin();
        int size = limit == null ? 5000 : Math.max(1, Math.min(limit, 10000));
        int pageIndex = page == null ? 0 : Math.max(0, page);
        List<User> users = userRepository.findByRole("USER",
                PageRequest.of(pageIndex, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(users.stream().map(AdminUserResponse::from).toList());
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<AdminUserResponse> getUser(@PathVariable UUID id) {
        validateAdmin();
        User user = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));
        return ResponseEntity.ok(AdminUserResponse.from(user));
    }

    @PutMapping("/users/{id}/toggle")
    public ResponseEntity<AdminUserResponse> toggleUser(@PathVariable UUID id, @RequestBody ToggleUserRequest request) {
        User currentUser = getCurrentUser();
        validateAdmin();
        User targetUser = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));
        requireManage(currentUser, targetUser);
        User updated = adminService.toggleUserActive(id, request.isActive());
        return ResponseEntity.ok(AdminUserResponse.from(updated));
    }

    @PutMapping("/users/{userId}/lock")
    public ResponseEntity<Void> lockUser(@PathVariable UUID userId) {
        User currentUser = getCurrentUser();
        validateAdmin();
        User targetUser = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        requireManage(currentUser, targetUser);
        adminService.lockUser(userId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{userId}/unlock")
    public ResponseEntity<AdminUserResponse> unlockUser(@PathVariable UUID userId) {
        validateAdmin();
        User targetUser = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        requireManage(getCurrentUser(), targetUser);
        User unlocked = adminService.unlockUser(userId);
        return ResponseEntity.ok(AdminUserResponse.from(unlocked));
    }

    @PutMapping("/users/{userId}/reset-login-protection")
    public ResponseEntity<AdminUserResponse> resetLoginProtection(@PathVariable UUID userId) {
        User current = getCurrentUser();
        validateAdmin();
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        requireManage(current, target);

        User updated = adminService.resetLoginProtection(userId);
        return ResponseEntity.ok(AdminUserResponse.from(updated));
    }

    // ===== ADMIN MANAGEMENT =====
    @GetMapping("/admins")
    public ResponseEntity<List<AdminUserResponse>> listAdmins() {
        User currentUser = getCurrentUser();
        validateSuperAdmin(currentUser);
        // Only the admin rows are read (the old findAll() loaded every customer into memory).
        PageRequest firstPage = PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "createdAt"));
        List<User> admins = new java.util.ArrayList<>(userRepository.findByRole("SUPER_ADMIN", firstPage));
        admins.addAll(userRepository.findByRole("ADMIN", firstPage));
        return ResponseEntity.ok(admins.stream().map(AdminUserResponse::from).toList());
    }

    @PostMapping("/admins")
    public ResponseEntity<AdminUserResponse> createAdmin(@Valid @RequestBody CreateAdminRequest request) {
        User currentUser = getCurrentUser();
        validateSuperAdmin(currentUser);
        return ResponseEntity.ok(AdminUserResponse.from(createAccount(request, "ADMIN", currentUser)));
    }

    /** Only the main super admin can create another super admin. */
    @PostMapping("/super-admins")
    public ResponseEntity<AdminUserResponse> createSuperAdmin(@Valid @RequestBody CreateAdminRequest request) {
        User currentUser = getCurrentUser();
        validateMainAdmin(currentUser);
        return ResponseEntity.ok(AdminUserResponse.from(createAccount(request, "SUPER_ADMIN", currentUser)));
    }

    private User createAccount(CreateAdminRequest request, String role, User creator) {
        String email = request.getEmail().trim().toLowerCase(java.util.Locale.ROOT);

        boolean adminEmailExists =
                userRepository.findByEmailAndRole(email, "ADMIN").isPresent()
                        || userRepository.findByEmailAndRole(email, "SUPER_ADMIN").isPresent();

        if (adminEmailExists) {
            throw new IllegalArgumentException("Email already registered");
        }

        String phone = request.getPhone().trim();

        boolean adminPhoneExists =
                userRepository.findByPhoneAndRole(phone, "ADMIN").isPresent()
                        || userRepository.findByPhoneAndRole(phone, "SUPER_ADMIN").isPresent();

        if (adminPhoneExists) {
            throw new IllegalArgumentException("Phone number already registered");
        }
        if (request.getPin() == null || !request.getPin().matches("^\\d{4}$")) {
            throw new IllegalArgumentException("PIN must be exactly 4 digits.");
        }

        User newAdmin = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .username(request.getUsername())
                .accountNumber(accountNumberGenerator.generate())
                .active(true)
                .emailVerified(false)
                .locked(false)
                .phone(phone)
                .pinHash(passwordEncoder.encode(request.getPin()))
                .phoneVerified(false)
                .role(role)
                .mainAdmin(false)
                .createdBy(creator.getId())
                .build();
        userRepository.save(newAdmin);
        return newAdmin;
    }

    /** Edit an admin's (or, for the main super admin, a super admin's) details. */
    @PutMapping("/admins/{id}")
    public ResponseEntity<AdminUserResponse> editAdmin(@PathVariable UUID id, @Valid @RequestBody EditAdminRequest request) {
        User current = getCurrentUser();
        validateSuperAdmin(current);
        User target = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Admin not found"));
        if (!"ADMIN".equals(target.getRole()) && !"SUPER_ADMIN".equals(target.getRole())) {
            throw new IllegalArgumentException("That account is not an admin.");
        }
        requireManage(current, target);

        String email = request.getEmail().trim().toLowerCase(java.util.Locale.ROOT);
        String phone = request.getPhone().trim();
        String username = request.getUsername().trim();
        boolean emailTaken = userRepository.findByEmailAndRole(email, "ADMIN").filter(x -> !x.getId().equals(target.getId())).isPresent()
                || userRepository.findByEmailAndRole(email, "SUPER_ADMIN").filter(x -> !x.getId().equals(target.getId())).isPresent();
        if (emailTaken) throw new IllegalArgumentException("Email already registered");
        boolean phoneTaken = userRepository.findByPhoneAndRole(phone, "ADMIN").filter(x -> !x.getId().equals(target.getId())).isPresent()
                || userRepository.findByPhoneAndRole(phone, "SUPER_ADMIN").filter(x -> !x.getId().equals(target.getId())).isPresent();
        if (phoneTaken) throw new IllegalArgumentException("Phone number already registered");
        if (userRepository.findByUsername(username).filter(x -> !x.getId().equals(target.getId())).isPresent()) {
            throw new IllegalArgumentException("Username already in use");
        }
        String newPin = request.getNewPin() == null ? "" : request.getNewPin().trim();
        if (!newPin.isEmpty() && !newPin.matches("^\\d{4}$")) throw new IllegalArgumentException("PIN must be exactly 4 digits.");
        String newPassword = request.getNewPassword() == null ? "" : request.getNewPassword();
        if (!newPassword.isEmpty() && newPassword.length() < 8) throw new IllegalArgumentException("Password must be at least 8 characters.");

        boolean emailChanged = !email.equalsIgnoreCase(target.getEmail());
        boolean phoneChanged = !phone.equals(target.getPhone());
        target.setFullName(request.getFullName().trim());
        target.setUsername(username);
        target.setEmail(email);
        target.setPhone(phone);
        if (emailChanged) target.setEmailVerified(false);
        if (phoneChanged) target.setPhoneVerified(false);
        if (!newPin.isEmpty()) {
            target.setPinHash(passwordEncoder.encode(newPin));
            target.setPinAttempts(0);
        }
        if (!newPassword.isEmpty()) target.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(target);
        if (emailChanged) {
            try {
                otpService.generateAndSend(target.getEmail());
            } catch (Exception e) {
                log.warn("Admin email changed for {} but the verification email could not be sent: {}", target.getId(), e.toString());
            }
        }
        log.info("Admin {} edited the details of admin {}", current.getEmail(), target.getId());
        return ResponseEntity.ok(AdminUserResponse.from(target));
    }

    /** Switch sections on or off for one admin (super admins are never limited). */
    @PutMapping("/admins/{id}/access")
    public ResponseEntity<AdminUserResponse> setAdminAccess(@PathVariable UUID id, @RequestBody AccessRequest request) {
        User current = getCurrentUser();
        validateSuperAdmin(current);
        User target = userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Admin not found"));
        if (!"ADMIN".equals(target.getRole())) {
            throw new IllegalArgumentException("Access can only be limited for admins. Super admins can open everything.");
        }
        requireManage(current, target);
        List<String> denied = AdminSections.parse(request.getDenied() == null ? "" : String.join(",", request.getDenied()));
        target.setDeniedSections(AdminSections.join(denied));
        userRepository.save(target);
        return ResponseEntity.ok(AdminUserResponse.from(target));
    }

    @PutMapping("/users/{userId}/contact")
    public ResponseEntity<AdminUserResponse> updateUserContact(@PathVariable UUID userId, @Valid @RequestBody UpdateUserContactRequest request) {
        User admin = getCurrentUser();
        validateAdmin();
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (("ADMIN".equals(target.getRole()) || "SUPER_ADMIN".equals(target.getRole())) && !canManage(admin, target) && !isOwnSuperAdminAccount(admin, target)) {
            throw new AdminAccessException("Only a super admin can change an admin's details, and only the main super admin can change a super admin's.");
        }
        String email = request.getEmail().trim().toLowerCase(java.util.Locale.ROOT);
        String phone = request.getPhone().trim();

        if ("USER".equals(target.getRole())) {
            userRepository.findByEmailAndRole(email, "USER").ifPresent(existing -> {
                if (!existing.getId().equals(target.getId())) {
                    throw new IllegalArgumentException("Email already registered");
                }
            });
        } else if ("ADMIN".equals(target.getRole()) || "SUPER_ADMIN".equals(target.getRole())) {
            boolean adminEmailExists =
                    userRepository.findByEmailAndRole(email, "ADMIN")
                            .filter(existing -> !existing.getId().equals(target.getId()))
                            .isPresent()
                            || userRepository.findByEmailAndRole(email, "SUPER_ADMIN")
                            .filter(existing -> !existing.getId().equals(target.getId()))
                            .isPresent();

            if (adminEmailExists) {
                throw new IllegalArgumentException("Email already registered");
            }
        }
        if ("USER".equals(target.getRole())) {
            userRepository.findByPhoneAndRole(phone, "USER").ifPresent(existing -> {
                if (!existing.getId().equals(target.getId())) {
                    throw new IllegalArgumentException("Phone number already registered");
                }
            });
        } else if ("ADMIN".equals(target.getRole()) || "SUPER_ADMIN".equals(target.getRole())) {
            boolean adminPhoneExists =
                    userRepository.findByPhoneAndRole(phone, "ADMIN")
                            .filter(existing -> !existing.getId().equals(target.getId()))
                            .isPresent()
                            || userRepository.findByPhoneAndRole(phone, "SUPER_ADMIN")
                            .filter(existing -> !existing.getId().equals(target.getId()))
                            .isPresent();

            if (adminPhoneExists) {
                throw new IllegalArgumentException("Phone number already registered");
            }
        }

        boolean emailChanged = !email.equalsIgnoreCase(target.getEmail());
        boolean phoneChanged = !phone.equals(target.getPhone());
        target.setEmail(email);
        target.setPhone(phone);
        if (emailChanged) target.setEmailVerified(false);
        if (phoneChanged) target.setPhoneVerified(false);
        userRepository.save(target);
        if (emailChanged) {
            try {
                otpService.generateAndSend(target.getEmail());
                log.info("Verification OTP sent after admin email change for user {}", target.getId());
            } catch (Exception e) {
                log.error("Email changed but verification OTP could not be sent for user {}", target.getId(), e);
                throw new IllegalStateException("Email was changed, but the verification email could not be sent. Please use Resend Verification from the login screen.");
            }
        }
        log.info("Admin {} updated contact details for user {} (emailChanged={}, phoneChanged={})", admin.getEmail(), target.getId(), emailChanged, phoneChanged);
        return ResponseEntity.ok(AdminUserResponse.from(target));
    }

    // ===== EMAIL VERIFICATION =====
    @PutMapping("/users/{userId}/verify-email")
    public ResponseEntity<AdminUserResponse> verifyUserEmail(@PathVariable UUID userId) {
        User currentUser = getCurrentUser();
        validateAdmin();
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (("ADMIN".equals(target.getRole()) || "SUPER_ADMIN".equals(target.getRole())) && !canManage(currentUser, target) && !isOwnSuperAdminAccount(currentUser, target)) {
            throw new AdminAccessException("Only a super admin can verify an admin's email, and only the main super admin a super admin's.");
        }
        if (!target.isEmailVerified()) {
            target.setEmailVerified(true);
            userRepository.save(target);
            log.info("Admin {} manually verified email for {}", currentUser.getEmail(), target.getEmail());
            // Same welcome email a user gets from verifying themselves via OTP
            // (AuthService.confirmEmail) - only for regular users, since an admin
            // account being verified isn't a new-customer "welcome" moment.
            if ("USER".equals(target.getRole())) {
                emailService.sendWelcomeEmail(target);
            }
        }
        return ResponseEntity.ok(AdminUserResponse.from(target));
    }

    // ===== PIN MANAGEMENT =====
    @PostMapping("/pin")
    public ResponseEntity<Void> setAdminPin(@RequestBody SetPinRequest request) {
        User currentUser = getCurrentUser();
        validateAdmin();
        if (request.getNewPin() == null || !request.getNewPin().matches("^\\d{4}$")) {
            throw new IllegalArgumentException("PIN must be exactly 4 digits.");
        }
        if (request.getCurrentPassword() == null || request.getCurrentPassword().isBlank()
                || !passwordEncoder.matches(request.getCurrentPassword(), currentUser.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }
        String hashedPin = passwordEncoder.encode(request.getNewPin());
        currentUser.setPinHash(hashedPin);
        currentUser.setPinAttempts(0);
        userRepository.save(currentUser);
        log.info("✅ PIN updated for admin: {}", currentUser.getEmail());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify-pin")
    public ResponseEntity<Void> verifyAdminPin(@RequestBody VerifyPinRequest request) {
        User currentUser = getCurrentUser();
        validateAdmin();
        currentUser = userRepository.findById(currentUser.getId()).orElseThrow(() -> new IllegalArgumentException("Admin user not found"));
        String enteredPin = request.getPin();
        if (enteredPin == null || !enteredPin.matches("^\\d{4}$")) {
            throw new IllegalArgumentException("PIN must be exactly 4 digits.");
        }
        if (currentUser.getPinHash() == null || currentUser.getPinHash().isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).header("X-Reason", "PIN_NOT_SET").build();
        }

        if (passwordEncoder.matches(enteredPin, currentUser.getPinHash())) {
            userRepository.clearFailedPinAttempts(currentUser.getId());
            return ResponseEntity.ok().build();
        }

        userRepository.recordFailedPinAttempt(currentUser.getId(), Instant.now());
        User state = userRepository.findById(currentUser.getId()).orElse(currentUser);
        int attempts = state.getPinAttempts();
        if (attempts >= 2) {
            return ResponseEntity.status(429).header("X-Reason", "PIN_DISABLED").build();
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).header("X-Reason", "WRONG_PIN").build();
    }

    // ===== TRANSACTIONS =====
    // Same JSON shape as before, but bounded: the old version loaded EVERY transaction ever
    // made (DISTINCT + full sort, ~580 ms and 20 MB of temp disk at 200k rows) on each call.
    // Newest 1000 by default; ?limit= (max 5000) and ?page= (0-based) to go further back.
    // Older records remain reachable through /transactions/lookup/{idOrCode} and, once
    // archived, through /transactions/archive/{code} (see TransactionArchiveJob).
    @GetMapping("/transactions")
    public ResponseEntity<List<AdminTransactionResponse>> listTransactions(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer page) {
        validateAdmin();
        int size = limit == null ? 1000 : Math.max(1, Math.min(limit, 5000));
        int pageIndex = page == null ? 0 : Math.max(0, page);
        List<Transaction> transactions = transactionRepository.findAllBy(
                PageRequest.of(pageIndex, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(transactions.stream().map(AdminTransactionResponse::from).toList());
    }

    // A transaction the "normal" list no longer shows because TransactionArchiveJob moved
    // it out. Nothing is ever deleted, so this always finds it if the code is correct.
    @GetMapping("/transactions/archive/{code}")
    public ResponseEntity<AdminTransactionResponse> findArchivedTransaction(@PathVariable String code) {
        validateAdmin();
        com.glr.deenwallet.transaction.TransactionArchive archived = transactionArchiveRepository
                .findByTransactionCode(code.toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("No transaction found with that code, archived or not"));
        User user = userRepository.findById(archived.getUserId()).orElse(null);
        return ResponseEntity.ok(AdminTransactionResponse.from(archived, user));
    }

    @GetMapping("/transactions/{id}")
    public ResponseEntity<AdminTransactionResponse> getTransaction(@PathVariable UUID id) {
        validateAdmin();
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));
        return ResponseEntity.ok(AdminTransactionResponse.from(transaction));
    }

    @GetMapping("/transactions/lookup/{identifier}")
    public ResponseEntity<AdminTransactionResponse> lookupTransaction(@PathVariable String identifier) {
        validateAdmin();
        String value = identifier == null ? "" : identifier.trim();
        if (value.isBlank()) throw new IllegalArgumentException("Transaction ID or code is required");

        Transaction transaction = null;
        try {
            transaction = transactionRepository.findById(UUID.fromString(value)).orElse(null);
        } catch (IllegalArgumentException ignored) { }

        if (transaction == null) {
            transaction = transactionRepository.findByTransactionCodeIgnoreCase(value).orElse(null);
        }
        if (transaction == null) throw new IllegalArgumentException("Transaction not found. Use the transaction ID or transaction code shown in the transaction record.");
        User owner = userRepository.findById(transaction.getUserId()).orElse(null);
        return ResponseEntity.ok(AdminTransactionResponse.from(transaction, owner));
    }

    // ===== ERROR LOGS (with POST) =====
    // ?since=<ISO instant> switches this into a live-poll query: only errors newer than
    // that timestamp are returned, so the admin Errors tab can refresh every few seconds
    // without re-downloading everything it already has. Without ?since, behaves as before.
    @GetMapping("/errors")
    public ResponseEntity<List<AdminErrorLogResponse>> listErrors(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Instant since) {
        validateAdmin();
        int maxLimit = limit != null && limit > 0 ? Math.min(limit, 200) : 100;
        List<ErrorLog> errorLogs = since != null
                ? errorLogRepository.findSince(since, PageRequest.of(0, maxLimit))
                : errorLogRepository.findRecent(PageRequest.of(0, maxLimit));
        return ResponseEntity.ok(errorLogs.stream().map(AdminErrorLogResponse::from).toList());
    }

    @GetMapping("/errors/stats")
    public ResponseEntity<AdminErrorStatsResponse> getErrorStats() {
        validateAdmin();
        Instant twentyFourHoursAgo = Instant.now().minus(24, ChronoUnit.HOURS);
        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        long totalErrors = errorLogRepository.count();
        long errorsLast24h = errorLogRepository.countByCreatedAtAfter(twentyFourHoursAgo);
        long errorsLast7d = errorLogRepository.countByCreatedAtAfter(sevenDaysAgo);
        long userAppErrorsLast24h = errorLogRepository.countBySourceAppAndCreatedAtAfter("user", twentyFourHoursAgo);
        long adminAppErrorsLast24h = errorLogRepository.countBySourceAppAndCreatedAtAfter("admin", twentyFourHoursAgo);
        List<Object[]> typeCounts = errorLogRepository.countGroupByErrorType();
        return ResponseEntity.ok(new AdminErrorStatsResponse(
                totalErrors,
                errorsLast24h,
                errorsLast7d,
                userAppErrorsLast24h,
                adminAppErrorsLast24h,
                typeCounts.stream()
                        .map(row -> new AdminErrorStatsResponse.ErrorTypeCount((String) row[0], ((Number) row[1]).longValue()))
                        .toList()
        ));
    }

    @PostMapping("/errors")
    public ResponseEntity<Void> reportError(@RequestBody ErrorReportRequest request) {
        validateAdmin();
        // This endpoint IS the admin app's own error channel, so sourceApp is always
        // "admin" here regardless of what the request body says.
        ErrorLog errorLog = ErrorLog.builder()
                .userId(getCurrentUser().getId())
                .errorType(request.getErrorType())
                .message(request.getMessage())
                .statusCode(request.getStatusCode())
                .url(request.getUrl())
                .sourceApp("admin")
                .endpointPath(request.getEndpointPath())
                .httpMethod(request.getHttpMethod())
                .userAgent(request.getUserAgent())
                .actionBuffer(request.getActionBuffer() != null ? String.join(" | ", request.getActionBuffer()) : null)
                .build();
        errorLogRepository.save(errorLog);
        return ResponseEntity.noContent().build();
    }

    @Transactional
    @DeleteMapping("/errors")
    public ResponseEntity<Void> clearErrors() {
        validateAdmin();
        errorLogRepository.deleteAllErrors();
        return ResponseEntity.noContent().build();
    }

    @Transactional
    @DeleteMapping("/errors/old")
    public ResponseEntity<Void> clearOldErrors(@RequestParam int days) {
        validateAdmin();
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        errorLogRepository.deleteByCreatedAtBefore(cutoff);
        return ResponseEntity.noContent().build();
    }

    // ===== SUPPORT =====
    @GetMapping("/support/whatsapp")
    public ResponseEntity<SupportWhatsAppResponse> getSupportWhatsApp() {
        validateAdmin();
        return ResponseEntity.ok(new SupportWhatsAppResponse(supportWhatsApp));
    }

    @PutMapping("/support/whatsapp")
    public ResponseEntity<Void> updateSupportWhatsApp(@RequestBody SupportWhatsAppRequest request) {
        validateAdmin();
        log.info("WhatsApp number updated to: {}", request.getWhatsappNumber());
        return ResponseEntity.noContent().build();
    }

    // ===== STATS =====
    @GetMapping("/stats")
    public ResponseEntity<AdminStatsResponse> getStats() {
        validateAdmin();
        return ResponseEntity.ok(adminService.getStats());
    }

    // ===== DTOs =====
    @Data
    public static class AdminNotificationRequest {
        @NotEmpty(message = "At least one user is required")
        private List<UUID> userIds;

        @NotBlank(message = "Title is required")
        @Size(max = 200, message = "Title must be at most 200 characters")
        private String title;

        @NotBlank(message = "Message is required")
        @Size(max = 2000, message = "Message must be at most 2000 characters")
        private String message;
    }

    @Data
    public static class SupportWhatsAppResponse {
        private String whatsappNumber;
        public SupportWhatsAppResponse() {}
        public SupportWhatsAppResponse(String whatsappNumber) { this.whatsappNumber = whatsappNumber; }
    }

    @Data
    public static class SupportWhatsAppRequest {
        private String whatsappNumber;
        public SupportWhatsAppRequest() {}
        public SupportWhatsAppRequest(String whatsappNumber) { this.whatsappNumber = whatsappNumber; }
    }

    @Data
    public static class CreateAdminRequest {
        @NotBlank @Email private String email;
        @NotBlank private String password;
        @NotBlank
        @jakarta.validation.constraints.Size(max = 100, message = "Full name must be at most 100 characters")
        @Pattern(regexp = "^[^<>]*$", message = "Full name cannot contain '<' or '>'")
        private String fullName;
        @NotBlank
        @jakarta.validation.constraints.Size(max = 40, message = "Username must be at most 40 characters")
        @Pattern(regexp = "^[^<>]*$", message = "Username cannot contain '<' or '>'")
        private String username;
        @NotBlank @Pattern(regexp = "^\\+232\\d{8}$", message = "Phone must be in format +232XXXXXXXX") private String phone;
        private String pin;
    }

    @Data
    public static class EditAdminRequest {
        @NotBlank
        @jakarta.validation.constraints.Size(max = 100, message = "Full name must be at most 100 characters")
        @Pattern(regexp = "^[^<>]*$", message = "Full name cannot contain '<' or '>'")
        private String fullName;
        @NotBlank
        @jakarta.validation.constraints.Size(max = 40, message = "Username must be at most 40 characters")
        @Pattern(regexp = "^[^<>]*$", message = "Username cannot contain '<' or '>'")
        private String username;
        @NotBlank @Email private String email;
        @NotBlank @Pattern(regexp = "^\\+232\\d{8}$", message = "Phone must be in format +232XXXXXXXX") private String phone;
        private String newPin;
        private String newPassword;
    }

    @Data
    public static class AccessRequest {
        private List<String> denied;
    }

    @Data
    public static class UpdateUserContactRequest {
        @NotBlank @Email private String email;
        @NotBlank @Pattern(regexp = "^\\+232\\d{8}$", message = "Phone must be in format +232XXXXXXXX") private String phone;
    }

    @Data
    public static class SetPinRequest {
        private String newPin;
        private String currentPassword;
    }

    @Data
    public static class VerifyPinRequest {
        private String pin;
    }

    // ===== VALIDATION =====
    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new SecurityException("Not authenticated");
        }
        String userId = (String) auth.getPrincipal();
        return userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new SecurityException("User not found"));
    }

    private void validateAdmin() {
        User user = getCurrentUser();
        String role = user.getRole();
        if (!"SUPER_ADMIN".equals(role) && !"ADMIN".equals(role)) {
            throw new SecurityException("Access denied: admin only");
        }
        if (!user.isActive()) throw new SecurityException("Admin account is inactive.");
        if (user.isLocked() && !"SUPER_ADMIN".equals(role)) throw new SecurityException("Admin account is locked. Complete recovery or ask a super admin to unlock it.");
    }

    /** Can this signed-in admin control (lock, deactivate, edit, reset) the target account? Never the account itself. */
    private boolean canManage(User current, User target) {
        if (current == null || target == null || current.getId().equals(target.getId())) return false;
        if ("USER".equals(target.getRole())) return true;
        if ("ADMIN".equals(target.getRole())) return "SUPER_ADMIN".equals(current.getRole());
        if ("SUPER_ADMIN".equals(target.getRole())) {
            return "SUPER_ADMIN".equals(current.getRole()) && current.isMainAdmin() && !target.isMainAdmin();
        }
        return false;
    }

    private boolean isOwnSuperAdminAccount(User current, User target) {
        return current != null && target != null && current.getId().equals(target.getId()) && "SUPER_ADMIN".equals(current.getRole());
    }

    private void requireManage(User current, User target) {
        if (canManage(current, target)) return;
        if ("SUPER_ADMIN".equals(target.getRole()) && target.isMainAdmin()) {
            throw new AdminAccessException("The main super admin cannot be changed by anyone else.");
        }
        if ("SUPER_ADMIN".equals(target.getRole())) {
            throw new AdminAccessException("Only the main super admin can manage a super admin.");
        }
        throw new AdminAccessException("Only a super admin can manage an admin account.");
    }

    private void validateMainAdmin(User user) {
        if (!"SUPER_ADMIN".equals(user.getRole()) || !user.isMainAdmin()) {
            throw new AdminAccessException("Only the main super admin can do this.");
        }
    }

    private void validateSuperAdmin(User user) {
        if (!"SUPER_ADMIN".equals(user.getRole())) {
            throw new SecurityException("Access denied: super admin only");
        }
    }
}