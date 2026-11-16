package com.glr.deenwallet.pawapay;

import com.glr.deenwallet.email.EmailService;
import com.glr.deenwallet.notification.AdminAlertService;
import com.glr.deenwallet.notification.NotificationService;
import com.glr.deenwallet.notification.PushNotificationService;
import com.glr.deenwallet.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Tells the customer (in-app notification, push pop-up and email) when a transfer abroad is sent, returned, not completed or being
 * checked, and tells the admins when something needs them. Never throws: a notification problem must not touch the money flow.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrossBorderNotifier {

    private final NotificationService notifications;
    private final PushNotificationService push;
    private final EmailService email;
    private final AdminAlertService adminAlerts;
    private final UserRepository users;

    public void statusChanged(CrossBorderTransfer t) {
        try {
            String ref = CrossBorderTransferService.reference(t);
            String to = t.getRecipientFirstName() + " " + t.getRecipientLastName();
            String sent = CrossBorderTransferService.amount(t.getSendAmount());
            String total = CrossBorderTransferService.amount(t.getSendAmount().add(t.getFeeAmount()));
            String gets = CrossBorderTransferService.amount(t.getReceiveAmount()) + " " + t.getReceiveCurrency();
            switch (t.getStatus()) {
                case COMPLETED -> tell(t, ref, "CROSS_BORDER_SENT", "Money sent",
                        "Your transfer of SLE " + sent + " to " + to + " was sent. They receive " + gets + ".",
                        "Money sent to " + to, "Your money has been sent",
                        "Good news: " + to + " has received " + gets + ".", to, "SLE " + sent, "SLE " + total, gets);
                case REFUNDED -> {
                    tell(t, ref, "CROSS_BORDER_REFUNDED", "Payment returned",
                            "We could not send your money to " + to + ", so SLE " + total + " was returned to your Orange Money.",
                            "Your payment was returned " + ref, "Your payment was returned",
                            "We could not deliver the money to " + to + ". SLE " + total + " has been returned to your Orange Money.",
                            to, "SLE " + sent, "SLE " + total, null);
                    admins(t, ref, "CROSS_BORDER_REFUNDED", "Send Abroad transfer refunded",
                            ref + " to " + to + " was refunded to the customer (" + nz(t.getFailureCode()) + "). " + nz(t.getFailureMessage()));
                }
                case FAILED -> tell(t, ref, "CROSS_BORDER_FAILED", "Transfer not completed",
                        "Your transfer to " + to + " was not completed. No money was taken from you.",
                        "Your transfer was not completed " + ref, "Transfer not completed",
                        "Your transfer to " + to + " could not be started, so no money was taken from you. You can try again.",
                        to, "SLE " + sent, null, null);
                case REVIEW -> {
                    tell(t, ref, "CROSS_BORDER_REVIEW", "Your transfer is being checked",
                            "We are checking your transfer to " + to + ". Your money is safe and we will tell you the result.",
                            "We are checking your transfer " + ref, "Your transfer is being checked",
                            "Our team is checking your transfer to " + to + ". Your money is safe and we will tell you the result.",
                            to, "SLE " + sent, "SLE " + total, null);
                    admins(t, ref, "CROSS_BORDER_REVIEW", "Send Abroad transfer needs you",
                            ref + " to " + to + " needs a person: " + nz(t.getFailureMessage()));
                }
                default -> { }
            }
        } catch (RuntimeException e) {
            log.warn("Could not send the notifications for transfer {}: {}", t.getId(), e.toString());
        }
    }

    private void tell(CrossBorderTransfer t, String ref, String type, String title, String message,
                      String subject, String headline, String lead, String to, String sent, String total, String gets) {
        try {
            notifications.create(t.getUserId(), type, title, message, null, ref);
            push.sendToUser(t.getUserId(), title, message, Map.of("type", type, "transferId", t.getId().toString()));
        } catch (RuntimeException e) {
            log.warn("Could not notify {} about transfer {}: {}", t.getUserId(), ref, e.toString());
        }
        try {
            users.findById(t.getUserId()).ifPresent(u -> email.sendCrossBorderEmail(u.getEmail(), subject, ref, headline, lead, to, sent, total, gets));
        } catch (RuntimeException e) {
            log.warn("Could not email {} about transfer {}: {}", t.getUserId(), ref, e.toString());
        }
    }

    private void admins(CrossBorderTransfer t, String ref, String type, String title, String message) {
        try {
            adminAlerts.notifyAdmins(null, "failed-payouts", type, title, message);
        } catch (RuntimeException e) {
            log.warn("Could not alert the admins about transfer {}: {}", ref, e.toString());
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
