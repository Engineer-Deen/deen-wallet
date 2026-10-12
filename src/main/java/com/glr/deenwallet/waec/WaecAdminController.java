package com.glr.deenwallet.waec;

import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Admin "WAEC Pins" tab: stock summary, add pins, list of purchased pins (traceable to the buyer),
 * and the two sensitive actions (reveal, resend) which need the admin's own 4-digit PIN and are logged.
 * Access is limited to ADMIN / SUPER_ADMIN by SecurityConfig (/api/admin/**).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/waec")
@RequiredArgsConstructor
public class WaecAdminController {

    private final WaecPinService pinService;
    private final WaecOrderRepository orderRepository;
    private final WaecDeliveryService deliveryService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final WaecPricing pricing;
    private final WaecCrypto crypto;

    @Getter @Setter public static class AddPinsRequest { private String text; }
    @Getter @Setter public static class PinRequest { private String pin; }
    @Getter @Setter public static class ResendRequest { private String pin; private String email; }

    public record Summary(long available, long reserved, long sold, long total, long purchases,
                          long deliveryPending, long deliveryFailed, BigDecimal unitPrice, int maxQuantity,
                          boolean operational) {}

    public record MaskedPin(UUID id, String pinMasked, String serialMasked) {}

    public record Purchase(UUID orderId, UUID transactionId, String transactionCode, int quantity,
                           BigDecimal amount, BigDecimal fee, BigDecimal totalCharged, Instant purchasedAt,
                           String deliveryChannel, String deliveryEmail, String deliveryStatus,
                           int deliveryAttempts, String lastDeliveryError, Instant deliveredAt,
                           UUID userId, String userName, String userEmail, String userAccountNumber,
                           List<MaskedPin> pins) {}

    public record RevealedPin(String pin, String serial) {}

    @GetMapping("/summary")
    public ResponseEntity<Summary> summary() {
        currentAdmin();
        WaecPinService.StockSummary s = pinService.summary();
        return ResponseEntity.ok(new Summary(
                s.available(), s.reserved(), s.sold(), s.total(),
                orderRepository.countByFulfilledAtIsNotNull(),
                orderRepository.countByFulfilledAtIsNotNullAndDeliveryStatus(WaecDeliveryStatus.PENDING),
                orderRepository.countByFulfilledAtIsNotNullAndDeliveryStatus(WaecDeliveryStatus.FAILED),
                BigDecimal.valueOf(pricing.quote(1).unitPriceMinor()).movePointLeft(2),
                pricing.maxQuantity(),
                pinService.isOperational()));
    }

    @PostMapping("/pins")
    public ResponseEntity<WaecPinService.AddResult> addPins(@RequestBody AddPinsRequest request) {
        User admin = currentAdmin();
        return ResponseEntity.ok(pinService.addPins(request.getText(), admin.getId(), admin.getEmail()));
    }

    @GetMapping("/purchases")
    public ResponseEntity<List<Purchase>> purchases(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        currentAdmin();

        String term = q == null ? "" : q.trim().toLowerCase(Locale.ROOT).replaceAll("[%_\\\\]", "");
        if (term.length() > 100) term = term.substring(0, 100);
        String like = term.isEmpty() ? "%" : "%" + term + "%";

        Collection<UUID> pinOrderIds = List.of(new UUID(0L, 0L));
        String code = q == null ? "" : q.trim().toUpperCase(Locale.ROOT);
        if (crypto.isConfigured() && WaecPinService.CODE.matcher(code).matches()) {
            List<UUID> found = pinService.orderIdsByPinOrSerial(code);
            if (!found.isEmpty()) pinOrderIds = found;
        }

        List<WaecDeliveryStatus> statuses;
        try {
            statuses = "ALL".equalsIgnoreCase(status)
                    ? List.of(WaecDeliveryStatus.values())
                    : List.of(WaecDeliveryStatus.valueOf(status.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown delivery status filter.");
        }

        List<WaecOrder> orders = orderRepository.search(like, statuses, pinOrderIds,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)));

        Map<UUID, User> users = userRepository.findAllById(
                        orders.stream().map(WaecOrder::getUserId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));

        List<Purchase> result = orders.stream().map(o -> {
            User u = users.get(o.getUserId());
            List<MaskedPin> pins = pinService.pinRowsForOrder(o.getId()).stream()
                    .filter(p -> p.getStatus() == WaecPinStatus.SOLD)
                    .map(p -> new MaskedPin(p.getId(), "********" + p.getPinLast4(), "*****" + p.getSerialLast4()))
                    .toList();
            return new Purchase(o.getId(), o.getTransactionId(), o.getTransactionCode(), o.getQuantity(),
                    toDecimal(o.getAmountValue()), toDecimal(o.getFeeValue()), toDecimal(o.getTotalChargedValue()),
                    o.getFulfilledAt(), o.getDeliveryChannel(), o.getDeliveryEmail(), o.getDeliveryStatus().name(),
                    o.getDeliveryAttempts(), o.getLastDeliveryError(), o.getDeliveredAt(),
                    o.getUserId(), u == null ? null : u.getFullName(), u == null ? null : u.getEmail(),
                    u == null ? null : u.getAccountNumber(), pins);
        }).toList();
        return ResponseEntity.ok(result);
    }

    /** Shows the exact PIN and SERIAL of one purchase. Needs the admin's PIN. Logged. */
    @PostMapping("/purchases/{orderId}/reveal")
    public ResponseEntity<?> reveal(@PathVariable UUID orderId, @RequestBody PinRequest request) {
        User admin = currentAdmin();
        ResponseEntity<?> denied = checkAdminPin(admin, request.getPin());
        if (denied != null) return denied;
        WaecOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase not found."));
        if (order.getFulfilledAt() == null) throw new IllegalStateException("This purchase has not been paid.");
        List<RevealedPin> pins = pinService.pinsForOrder(orderId).stream()
                .map(p -> new RevealedPin(p.pin(), p.serial())).toList();
        pinService.audit(orderId, "REVEAL", admin.getId(), admin.getEmail(), "Revealed " + pins.size() + " pin(s)");
        log.info("Admin {} revealed WAEC pins of {}", admin.getEmail(), order.getTransactionCode());
        return ResponseEntity.ok(pins);
    }

    /** Sends the pins again, to the saved email or to a new one. Needs the admin's PIN. Logged. */
    @PostMapping("/purchases/{orderId}/resend")
    public ResponseEntity<?> resend(@PathVariable UUID orderId, @RequestBody ResendRequest request) {
        User admin = currentAdmin();
        ResponseEntity<?> denied = checkAdminPin(admin, request.getPin());
        if (denied != null) return denied;
        WaecOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase not found."));
        if (order.getFulfilledAt() == null) throw new IllegalStateException("This purchase has not been paid.");

        String target = pricing.resolveDeliveryEmail(request.getEmail(), order.getDeliveryEmail());
        boolean changed = !target.equalsIgnoreCase(order.getDeliveryEmail() == null ? "" : order.getDeliveryEmail());
        boolean sent = deliveryService.deliver(orderId, changed ? target : null);
        pinService.audit(orderId, changed ? "RESEND_NEW_EMAIL" : "RESEND", admin.getId(), admin.getEmail(),
                (sent ? "Sent" : "FAILED") + " to " + target);
        WaecOrder fresh = orderRepository.findById(orderId).orElse(order);
        return ResponseEntity.ok(Map.of("sent", sent, "deliveryStatus", fresh.getDeliveryStatus().name(),
                "deliveryEmail", fresh.getDeliveryEmail() == null ? "" : fresh.getDeliveryEmail()));
    }

    // ---------- helpers ----------

    private User currentAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new SecurityException("Not authenticated");
        User user = userRepository.findById(UUID.fromString((String) auth.getPrincipal()))
                .orElseThrow(() -> new SecurityException("User not found"));
        String role = user.getRole();
        if (!"SUPER_ADMIN".equals(role) && !"ADMIN".equals(role)) throw new SecurityException("Access denied: admin only");
        if (!user.isActive()) throw new SecurityException("Admin account is inactive.");
        if (user.isLocked() && !"SUPER_ADMIN".equals(role)) throw new SecurityException("Admin account is locked.");
        return user;
    }

    /** Same rules as /api/admin/verify-pin. Returns null when the PIN is right, otherwise the error response. */
    private ResponseEntity<?> checkAdminPin(User admin, String pin) {
        if (pin == null || !pin.matches("^\\d{4}$")) {
            throw new IllegalArgumentException("Enter your 4-digit admin PIN.");
        }
        User fresh = userRepository.findById(admin.getId())
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found"));
        if (fresh.getPinHash() == null || fresh.getPinHash().isEmpty()) {
            // 400, not 403: the admin page treats any 401/403 as an expired session and logs out.
            return ResponseEntity.status(400).header("X-Reason", "PIN_NOT_SET")
                    .body(Map.of("message", "Set your admin PIN first (menu > Change PIN)."));
        }
        // Revealing/resending pins is more sensitive than a normal PIN check: once the admin's PIN is
        // locked (2 wrong tries) it stays refused here, even for the right PIN, until a super admin unlocks it.
        if (fresh.getPinAttempts() >= 2) {
            return ResponseEntity.status(429).header("X-Reason", "PIN_DISABLED")
                    .body(Map.of("message", "Admin PIN is disabled after too many wrong attempts. Ask a super admin to unlock it."));
        }
        if (passwordEncoder.matches(pin, fresh.getPinHash())) {
            userRepository.clearFailedPinAttempts(fresh.getId());
            return null;
        }
        userRepository.recordFailedPinAttempt(fresh.getId(), Instant.now());
        User state = userRepository.findById(fresh.getId()).orElse(fresh);
        if (state.getPinAttempts() >= 2) {
            return ResponseEntity.status(429).header("X-Reason", "PIN_DISABLED")
                    .body(Map.of("message", "Too many wrong PIN attempts."));
        }
        return ResponseEntity.status(400).header("X-Reason", "WRONG_PIN")
                .body(Map.of("message", "Wrong PIN. Try again."));
    }

    private BigDecimal toDecimal(long minorUnits) {
        return BigDecimal.valueOf(minorUnits).movePointLeft(2);
    }
}
