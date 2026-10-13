package com.glr.deenwallet.waec;

import com.glr.deenwallet.transaction.TransactionResponse;
import com.glr.deenwallet.transaction.TransactionService;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/waec")
@RequiredArgsConstructor
public class WaecController {

    private final TransactionService transactionService;
    private final WaecPricing pricing;
    private final WaecPinService pinService;
    private final WaecOrderRepository orderRepository;
    private final UserRepository userRepository;

    public record QuoteResponse(String currency, BigDecimal unitPrice, int quantity, BigDecimal subtotal,
                                BigDecimal fee, BigDecimal total, boolean inStock, int maxQuantity,
                                String registeredEmail, boolean phoneDeliveryAvailable) {}

    public record PinPair(String pin, String serial) {}

    public record OrderSummary(UUID transactionId, String transactionCode, int quantity, String deliveryChannel,
                               String deliveryEmail, String deliveryEmailMasked, String deliveryStatus, Instant deliveredAt,
                               boolean fulfilled, List<PinPair> pins) {}

    /** Price preview for the cost screen and the confirmation screen. Purely a calculation. */
    @GetMapping("/quote")
    public ResponseEntity<QuoteResponse> quote(@RequestParam(defaultValue = "1") int quantity) {
        int q = pricing.requireQuantity(quantity);
        WaecPricing.Quote quote = pricing.quote(q);
        long available = pinService.isOperational() ? pinService.availableCount() : 0;
        User user = userRepository.findById(currentUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return ResponseEntity.ok(new QuoteResponse(
                "SLE",
                toDecimal(quote.unitPriceMinor()),
                q,
                toDecimal(quote.amountMinor()),
                toDecimal(quote.feeMinor()),
                toDecimal(quote.totalMinor()),
                available >= q,
                (int) Math.min(pricing.maxQuantity(), available),
                user.getEmail(),
                false));
    }

    /** Starts a purchase: reserves the pins and returns the payment (USSD) details, like a bank transfer does. */
    @PostMapping("/purchases")
    public ResponseEntity<TransactionResponse> purchase(@Valid @RequestBody WaecPurchaseRequest request) {
        return ResponseEntity.ok(transactionService.initiateWaecPurchase(currentUserId(), request));
    }

    /** The buyer's own purchase: delivery state, and the pins once the payment has been confirmed. */
    @GetMapping("/purchases/{transactionId}")
    public ResponseEntity<OrderSummary> mine(@PathVariable UUID transactionId) {
        WaecOrder order = orderRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Purchase not found."));
        if (!order.getUserId().equals(currentUserId())) {
            throw new IllegalArgumentException("Purchase not found.");
        }
        boolean fulfilled = order.getFulfilledAt() != null;
        List<PinPair> pins = fulfilled
                ? pinService.pinsForOrder(order.getId()).stream().map(p -> new PinPair(p.pin(), p.serial())).toList()
                : List.of();
        return ResponseEntity.ok(new OrderSummary(
                order.getTransactionId(), order.getTransactionCode(), order.getQuantity(), order.getDeliveryChannel(),
                order.getDeliveryEmail(), maskEmail(order.getDeliveryEmail()), order.getDeliveryStatus().name(), order.getDeliveredAt(),
                fulfilled, pins));
    }

    static String maskEmail(String email) {
        if (email == null || !email.contains("@")) return email;
        int at = email.indexOf('@');
        String local = email.substring(0, at);
        String shown = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return shown + "***" + email.substring(at);
    }

    private BigDecimal toDecimal(long minorUnits) {
        return BigDecimal.valueOf(minorUnits).movePointLeft(2);
    }

    private UUID currentUserId() {
        String userId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return UUID.fromString(userId);
    }
}