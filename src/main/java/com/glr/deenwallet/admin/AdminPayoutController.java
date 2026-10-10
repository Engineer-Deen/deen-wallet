package com.glr.deenwallet.admin;

import com.glr.deenwallet.transaction.*;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * "Failed Payouts" admin section: payments that were received from the customer but whose payout to the
 * recipient failed or was rejected. Admins verify the transaction number with the customer, then either
 * send the payout again or refund the money to the number that paid. Everything is recorded.
 * Access: /api/admin/** is already restricted to ADMIN / SUPER_ADMIN by SecurityConfig.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/failed-payouts")
@RequiredArgsConstructor
public class AdminPayoutController {

    private static final List<TransactionStatus> OPEN = List.of(
            TransactionStatus.PAYOUT_FAILED, TransactionStatus.REFUND_PENDING, TransactionStatus.NEEDS_REVIEW);
    private static final List<TransactionStatus> ALL = List.of(
            TransactionStatus.PAYOUT_FAILED, TransactionStatus.REFUND_PENDING, TransactionStatus.NEEDS_REVIEW,
            TransactionStatus.REFUNDED);

    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final UserRepository userRepository;

    // ---------- list / search (cards) ----------
    @GetMapping
    public ResponseEntity<List<Card>> list(@RequestParam(required = false) String q,
                                           @RequestParam(required = false, defaultValue = "false") boolean includeResolved) {
        admin();
        List<Transaction> rows = transactionRepository.findQueue(includeResolved ? ALL : OPEN, PageRequest.of(0, 300));
        String needle = q == null ? "" : q.trim().toUpperCase(Locale.ROOT);
        return ResponseEntity.ok(rows.stream()
                .filter(t -> needle.isEmpty() || (t.getTransactionCode() != null && t.getTransactionCode().contains(needle)))
                .map(t -> Card.from(t, userRepository.findById(t.getUserId()).orElse(null)))
                .toList());
    }

    // ---------- details (opened when a card is clicked) ----------
    @GetMapping("/{id}")
    public ResponseEntity<Detail> detail(@PathVariable UUID id) {
        admin();
        return ResponseEntity.ok(buildDetail(requireRecoverable(id)));
    }

    // ---------- actions ----------
    @PostMapping("/{id}/resend")
    public ResponseEntity<Detail> resend(@PathVariable UUID id, @RequestBody ActionRequest body) {
        User admin = admin();
        Transaction t = requireRecoverable(id);
        requireVerifiedCode(t, body);
        Transaction updated = transactionService.adminResendPayout(id, admin.getId(), admin.getEmail());
        log.info("Admin {} resent payout for {}", admin.getEmail(), updated.getTransactionCode());
        return ResponseEntity.ok(buildDetail(updated));
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<Detail> refund(@PathVariable UUID id, @RequestBody ActionRequest body) {
        User admin = admin();
        Transaction t = requireRecoverable(id);
        requireVerifiedCode(t, body);
        Transaction updated = transactionService.adminRequestRefund(id, admin.getId(), admin.getEmail(), body.note());
        log.info("Admin {} refunded {}", admin.getEmail(), updated.getTransactionCode());
        return ResponseEntity.ok(buildDetail(updated));
    }

    // ---------- helpers ----------
    /** The admin must type the transaction number the CUSTOMER gave them. This proves it was verified. */
    private void requireVerifiedCode(Transaction t, ActionRequest body) {
        String typed = body == null || body.verifiedTransactionCode() == null ? "" : body.verifiedTransactionCode().trim();
        if (typed.isEmpty() || !typed.equalsIgnoreCase(t.getTransactionCode())) {
            throw new IllegalArgumentException(
                    "The transaction number does not match. Ask the customer for the number on their receipt or email and enter it exactly.");
        }
    }

    private Transaction requireRecoverable(UUID id) {
        Transaction t = transactionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));
        if (!ALL.contains(t.getStatus()) && t.getPayoutFailedAt() == null) {
            throw new IllegalArgumentException("This transaction is not a failed payout.");
        }
        return t;
    }

    private User admin() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated()) throw new SecurityException("Not authenticated");
        User u = userRepository.findById(UUID.fromString((String) a.getPrincipal()))
                .orElseThrow(() -> new SecurityException("User not found"));
        if (!"ADMIN".equals(u.getRole()) && !"SUPER_ADMIN".equals(u.getRole())) throw new SecurityException("Access denied: admin only");
        if (!u.isActive()) throw new SecurityException("Admin account is inactive.");
        return u;
    }

    private Detail buildDetail(Transaction t) {
        User u = userRepository.findById(t.getUserId()).orElse(null);
        List<Event> history = transactionService.recoveryHistory(t.getId()).stream()
                .map(e -> new Event(e.getAction(), e.getActorEmail(), e.getDetail(), e.getCreatedAt())).toList();
        boolean actionable = t.getStatus() == TransactionStatus.PAYOUT_FAILED;
        return new Detail(
                t.getId(), t.getTransactionCode(), t.getStatus().name(), t.getServiceType().name(),
                dec(t.getAmountValue()), dec(t.getFeeValue()), dec(t.getTotalChargedValue()),
                t.getFailureReason(), t.getPayoutAttempt(),
                t.getCreatedAt(), t.getPaidInAt(), t.getPayoutFailedAt(),
                t.getSourceProviderId(), t.getSourcePhone(),
                t.getDestinationProviderId(), t.getDestinationPhone(), t.getDestinationHolderName(),
                t.getDestinationBankName(), t.getDestinationBankAccountNumber(), t.getDestinationBankHolderName(),
                t.getMonimePaymentCodeId(), t.getMonimePayoutId(), t.getRefundPayoutId(),
                u == null ? null : new Customer(u.getId(), u.getFullName(), u.getUsername(), u.getEmail(), u.getPhone(),
                        u.getAccountNumber(), u.isActive(), u.isLocked(), u.isEmailVerified(), u.getCreatedAt()),
                actionable, actionable, history);
    }

    private static BigDecimal dec(Long minor) {
        return BigDecimal.valueOf(minor == null ? 0 : minor).movePointLeft(2);
    }

    // ---------- DTOs ----------
    public record ActionRequest(String verifiedTransactionCode, String note) {}

    public record Card(UUID id, String transactionCode, String status, BigDecimal amount, BigDecimal totalCharged,
                       String customerName, String customerEmail, String sourcePhone, String recipient,
                       String failureReason, Instant failedAt, Instant createdAt) {
        static Card from(Transaction t, User u) {
            String recipient = t.getServiceType() == TransactionServiceType.BANK_TRANSFER
                    ? (t.getDestinationBankName() == null ? "Bank" : t.getDestinationBankName()) + " " + t.getDestinationBankAccountNumber()
                    : t.getDestinationPhone();
            return new Card(t.getId(), t.getTransactionCode(), t.getStatus().name(), dec(t.getAmountValue()),
                    dec(t.getTotalChargedValue()), u == null ? null : u.getFullName(), u == null ? null : u.getEmail(),
                    t.getSourcePhone(), recipient, t.getFailureReason(),
                    t.getPayoutFailedAt() != null ? t.getPayoutFailedAt() : t.getUpdatedAt(), t.getCreatedAt());
        }
    }

    public record Customer(UUID id, String fullName, String username, String email, String phone, String accountNumber,
                           boolean active, boolean locked, boolean emailVerified, Instant joinedAt) {}

    public record Event(String action, String actor, String detail, Instant at) {}

    public record Detail(UUID id, String transactionCode, String status, String serviceType,
                         BigDecimal amount, BigDecimal fee, BigDecimal totalCharged,
                         String failureReason, int payoutAttempts,
                         Instant createdAt, Instant paidInAt, Instant failedAt,
                         String sourceProviderId, String sourcePhone,
                         String destinationProviderId, String destinationPhone, String destinationHolderName,
                         String bankName, String bankAccountNumber, String bankHolderName,
                         String paymentCodeId, String payoutId, String refundPayoutId,
                         Customer customer, boolean canResend, boolean canRefund, List<Event> history) {}
}
