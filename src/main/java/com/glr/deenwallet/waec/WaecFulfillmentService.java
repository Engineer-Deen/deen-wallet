package com.glr.deenwallet.waec;

import com.glr.deenwallet.notification.NotificationService;
import com.glr.deenwallet.notification.PushNotificationService;
import com.glr.deenwallet.transaction.Transaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs when the customer's payment is confirmed. Marks the reserved pins SOLD, writes the in-app
 * notification with the pin details, and (after commit) emails them. Safe to call twice: a second
 * call for an already fulfilled order does nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaecFulfillmentService {

    public enum Outcome { FULFILLED, ALREADY_FULFILLED, OUT_OF_STOCK }

    private final WaecOrderRepository orderRepository;
    private final WaecPinRepository pinRepository;
    private final WaecPinService pinService;
    private final WaecDeliveryService deliveryService;
    private final NotificationService notificationService;
    private final PushNotificationService pushNotificationService;

    @Transactional
    public Outcome fulfill(Transaction transaction) {
        WaecOrder order = orderRepository.findByTransactionIdForUpdate(transaction.getId())
                .orElseThrow(() -> new IllegalStateException("WAEC order missing for transaction " + transaction.getTransactionCode()));
        if (order.getFulfilledAt() != null) return Outcome.ALREADY_FULFILLED;

        List<WaecPin> pins = new java.util.ArrayList<>(pinRepository.findByOrderId(order.getId()));
        int missing = order.getQuantity() - pins.size();
        if (missing > 0) {
            // Late payment after the reservation was released: take fresh pins if there are any.
            List<WaecPin> more = pinRepository.lockAvailable(missing);
            if (more.size() < missing) {
                log.error("ALERT WAEC out of stock while fulfilling paid transaction {}", transaction.getTransactionCode());
                return Outcome.OUT_OF_STOCK;
            }
            pins.addAll(more);
        }

        Instant now = Instant.now();
        for (WaecPin p : pins) {
            p.setStatus(WaecPinStatus.SOLD);
            p.setOrderId(order.getId());
            p.setSoldAt(now);
        }
        pinRepository.saveAll(pins);
        order.setFulfilledAt(now);
        order.setDeliveryStatus(WaecDeliveryStatus.PENDING);
        orderRepository.save(order);

        // In-app notification carries the full pin details (the customer's own, authenticated inbox).
        // The push alert shown on a lock screen deliberately does NOT contain them.
        List<WaecPinService.PinView> views = pinService.pinsForOrder(order.getId());
        notificationService.create(order.getUserId(), "WAEC_PIN_DELIVERED", "WAEC Result Pin ready",
                buildNotificationMessage(order, views), transaction.getId(), transaction.getTransactionCode());
        pushNotificationService.sendToUser(order.getUserId(), "WAEC Result Pin ready",
                "Your WAEC Result Pin purchase was successful. Open Notifications to view it.",
                Map.of("type", "WAEC_PIN_DELIVERED", "transactionId", transaction.getId().toString()));

        final UUID orderId = order.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliveryService.deliverAsync(orderId);
                }
            });
        } else {
            deliveryService.deliverAsync(orderId);
        }
        log.info("WAEC order {} fulfilled ({} pins)", order.getTransactionCode(), pins.size());
        return Outcome.FULFILLED;
    }

    static String buildNotificationMessage(WaecOrder order, List<WaecPinService.PinView> pins) {
        StringBuilder sb = new StringBuilder();
        sb.append("WAEC payment successful. Trans. ID: ").append(order.getTransactionCode()).append('\n');
        sb.append("Type: WAEC Result Pin x").append(order.getQuantity()).append('\n');
        sb.append("Amount: ").append(BigDecimal.valueOf(order.getTotalChargedValue()).movePointLeft(2).toPlainString()).append(" SLE\n");
        for (int i = 0; i < pins.size(); i++) {
            String n = pins.size() > 1 ? " " + (i + 1) : "";
            sb.append("PIN").append(n).append(": ").append(pins.get(i).pin()).append('\n');
            sb.append("Serial").append(n).append(": ").append(pins.get(i).serial()).append('\n');
        }
        sb.append("Keep these codes private. Thank you for using DeenWallet Transfer.");
        return sb.toString();
    }
}
