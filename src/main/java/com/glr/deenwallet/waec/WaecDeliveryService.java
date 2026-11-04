package com.glr.deenwallet.waec;

import com.glr.deenwallet.email.EmailService;
import com.glr.deenwallet.notification.AdminAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Emails the pins after the sale is committed. The sale never depends on the email: the pins are
 * already assigned to the buyer and visible in their app notification. Failed emails are retried by
 * WaecMaintenanceJob and can be resent by an admin.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaecDeliveryService {

    private final WaecOrderRepository orderRepository;
    private final WaecPinService pinService;
    private final EmailService emailService;
    private final TransactionTemplate transactionTemplate;
    private final AdminAlertService adminAlerts;

    @Async("emailTaskExecutor")
    public void deliverAsync(UUID orderId) {
        try {
            deliver(orderId, null);
        } catch (Exception e) {
            log.error("WAEC pin delivery failed unexpectedly for order {}", orderId, e);
        }
    }

    /**
     * Sends the order's pins once. overrideEmail (admin resend to a new address) replaces the stored
     * address only when the send succeeds.
     *
     * @return true if the email was accepted by the mail server
     */
    public boolean deliver(UUID orderId, String overrideEmail) {
        WaecOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("WAEC order not found."));
        if (order.getFulfilledAt() == null) {
            throw new IllegalStateException("This purchase has not been paid yet.");
        }
        String to = (overrideEmail != null && !overrideEmail.isBlank()) ? overrideEmail : order.getDeliveryEmail();

        List<WaecPinService.PinView> pins = pinService.pinsForOrder(orderId);
        boolean ok = false;
        String error = null;
        if (pins.isEmpty()) {
            error = "No sold pins found for this order.";
        } else if (to == null || to.isBlank()) {
            error = "No delivery email on this order.";
        } else {
            ok = emailService.sendWaecPinsEmail(
                    to, order.getTransactionCode(),
                    BigDecimal.valueOf(order.getTotalChargedValue()).movePointLeft(2).toPlainString(),
                    pins.stream().map(WaecPinService.PinView::pin).toList(),
                    pins.stream().map(WaecPinService.PinView::serial).toList());
            if (!ok) error = "The email could not be sent.";
        }

        final boolean sent = ok;
        final int[] attempts = new int[1];
        final String failure = error;
        final String newEmail = (sent && overrideEmail != null && !overrideEmail.isBlank()) ? overrideEmail : null;
        transactionTemplate.executeWithoutResult(st -> {
            WaecOrder o = orderRepository.findById(orderId).orElseThrow();
            o.setDeliveryAttempts(o.getDeliveryAttempts() + 1);
            attempts[0] = o.getDeliveryAttempts();
            if (sent) {
                o.setDeliveryStatus(WaecDeliveryStatus.SENT);
                o.setDeliveredAt(Instant.now());
                o.setLastDeliveryError(null);
                if (newEmail != null) o.setDeliveryEmail(newEmail);
            } else {
                o.setDeliveryStatus(WaecDeliveryStatus.FAILED);
                o.setLastDeliveryError(failure);
            }
            orderRepository.save(o);
        });
        if (!sent) {
            log.warn("WAEC pin email not delivered for {} ({})", order.getTransactionCode(), failure);
            // Tell the admins on the first failure and again when the automatic retries have run out (not on every retry).
            if (attempts[0] == 1 || attempts[0] == 5) {
                adminAlerts.notifyAdmins(null, "waec", "WAEC_EMAIL_FAILED", "WAEC pin email not delivered",
                        "The WAEC pin email for " + order.getTransactionCode() + " could not be sent to " + (to == null || to.isBlank() ? "(no email)" : to)
                                + (attempts[0] >= 5 ? " after several tries." : ".")
                                + " The customer still has the pin in their app. Open WAEC Pins to resend it to a correct address.");
            }
        }
        return sent;
    }
}
