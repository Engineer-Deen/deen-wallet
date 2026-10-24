package com.glr.deenwallet.email;

import com.glr.deenwallet.transaction.Transaction;
import com.glr.deenwallet.user.User;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    @Value("${app.mail.from-email}")
    private String senderEmail;

    @Value("${app.mail.from-name}")
    private String senderName;

    @Value("${app.mail.support-email:support@deenwallapp.com}")
    private String supportEmail;

    @Value("${app.mail.feedback-email:ceofeedback@deenwallapp.com}")
    private String feedbackEmail;

    @Value("${app.frontend.base-url:https://deenwallapp.com}")
    private String frontendBaseUrl;

    private final JavaMailSender mailSender;

    // Remembers what was just sent so the same message cannot reach one inbox twice.
    private final java.util.concurrent.ConcurrentHashMap<String, Long> recentlySent = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DUPLICATE_WINDOW_MS = 90_000L;

    public void sendOtpEmail(String toEmail, String code, int expiryMinutes) {
        sendRequired(toEmail, "Verification Code",
                EmailTemplates.otpEmail(code, expiryMinutes));
    }

    public void sendWelcomeEmail(User user) {
        send(user.getEmail(), "Welcome to DeenWallet Transfer",
                EmailTemplates.welcomeEmail(
                        user.getFirstName(),
                        user.getAccountNumber(),
                        feedbackEmail
                ));
    }

    /** Admin "forgot password": emails a one-time link to the admin reset page. Throws if the mail cannot be sent. */
    public void sendAdminPasswordResetEmail(User admin, String rawToken) {
        if (admin == null || admin.getEmail() == null || admin.getEmail().isBlank()) {
            throw new IllegalArgumentException("Admin email is required");
        }
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Password reset token is required");
        }
        String baseUrl = frontendBaseUrl == null ? "" : frontendBaseUrl.trim().replaceAll("/+$", "");
        String resetLink = baseUrl + "/admin-reset-password.html?token=" + rawToken.trim();
        sendRequired(admin.getEmail(), "Reset Your DeenWallet Transfer Admin Password",
                EmailTemplates.adminPasswordResetEmail(admin.getFirstName(), resetLink));
    }

    /** Security notice after an admin password or PIN was changed through recovery. */
    @Async("emailTaskExecutor")
    public void sendAdminSecurityChangeEmail(User admin, String what) {
        send(admin.getEmail(), "Your DeenWallet Transfer admin " + what + " was changed",
                EmailTemplates.adminSecurityChangeEmail(admin.getFirstName(), what, supportEmail));
    }

    public void sendPasswordResetEmail(User user, String rawToken) {
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            throw new IllegalArgumentException("User email is required");
        }

        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Password reset token is required");
        }

        String baseUrl = frontendBaseUrl == null
                ? ""
                : frontendBaseUrl.trim().replaceAll("/+$", "");

        String resetLink = baseUrl + "/reset-password.html?token=" + rawToken.trim();

        sendRequired(
                user.getEmail(),
                "Reset Your DeenWallet Transfer Password",
                EmailTemplates.passwordResetEmail(user.getFirstName(), resetLink)
        );
    }

    @Async("emailTaskExecutor")
    public void sendAccountActivatedEmail(User user) {
        sendAlways(user.getEmail(), "DeenWallet Transfer Account Profile Notice",
                EmailTemplates.accountActivatedEmail(user.getFirstName()));
        log.info("✅ Account activation email sent to: {}", user.getEmail());
    }

    @Async("emailTaskExecutor")
    public void sendAccountDeactivatedEmail(User user) {
        sendAlways(user.getEmail(), "DeenWallet Transfer Account Service Summary",
                EmailTemplates.accountDeactivatedEmail(
                        user.getFirstName(),
                        supportEmail
                ));
        log.info("✅ Account update email sent to: {}", user.getEmail());
    }

    @Async("emailTaskExecutor")
    public void sendAccountLockedEmail(User user) {
        sendAccountLockedEmail(user, "security");
    }

    @Async("emailTaskExecutor")
    public void sendAccountLockedEmail(User user, String lockReason) {
        log.info(
                "Preparing account lock email ({}) for: {}",
                lockReason,
                user.getEmail()
        );

        sendAlways(
                user.getEmail(),
                "DeenWallet Transfer Service Activity Summary",
                EmailTemplates.accountLockedEmail(
                        user.getFirstName(),
                        lockReason,
                        supportEmail
                )
        );
    }

    @Async("emailTaskExecutor")
    public void sendAdminLoginBlockedEmail(User user, boolean superAdmin) {
        sendAlways(
                user.getEmail(),
                "DeenWallet Transfer Administrative Activity Notice",
                EmailTemplates.adminLoginBlockedEmail(
                        user.getFirstName(),
                        superAdmin,
                        supportEmail
                )
        );
        log.info("Admin status email queued for: {}", user.getEmail());
    }

    public void sendTransactionCompletedEmail(
            String toEmail,
            Transaction transaction
    ) {
        String transactionCode = transaction.getTransactionCode() != null
                ? transaction.getTransactionCode()
                : transaction.getId().toString();

        send(
                toEmail,
                "Transfer Receipt " + transactionCode,
                EmailTemplates.transactionCompletedEmail(
                        transactionCode,
                        toDisplayAmount(transaction.getAmountValue()),
                        toDisplayAmount(transaction.getTotalChargedValue()),
                        transaction.getDestinationHolderName(),
                        transaction.getDestinationPhone(),
                        transaction.getDestinationProviderId(),
                        transaction.getServiceType() == com.glr.deenwallet.transaction.TransactionServiceType.BANK_TRANSFER,
                        transaction.getDestinationBankName(),
                        transaction.getDestinationBankAccountNumber()
                )
        );
    }

    public void sendTransactionFailedEmail(
            String toEmail,
            Transaction transaction
    ) {
        String transactionCode = transaction.getTransactionCode() != null
                ? transaction.getTransactionCode()
                : transaction.getId().toString();

        send(
                toEmail,
                "Transfer Status Update " + transactionCode,
                EmailTemplates.transactionFailedEmail(
                        transactionCode,
                        toDisplayAmount(transaction.getAmountValue()),
                        transaction.getDestinationPhone(),
                        transaction.getFailureReason(),
                        transaction.getServiceType() == com.glr.deenwallet.transaction.TransactionServiceType.BANK_TRANSFER,
                        transaction.getDestinationBankName(),
                        transaction.getDestinationBankAccountNumber(),
                        transaction.getDestinationBankHolderName()
                )
        );
    }

    /**
     * Emails the pins of a WAEC purchase. Unlike the other receipts this reports success, so the
     * caller can record delivered / failed and retry. Pins are never put in the subject line.
     */
    public boolean sendWaecPinsEmail(String toEmail, String transactionCode, String totalCharged,
                                     java.util.List<String> pins, java.util.List<String> serials) {
        return sendTracked(toEmail, "Your WAEC Result Pin " + transactionCode,
                EmailTemplates.waecPinsEmail(transactionCode, totalCharged, pins, serials));
    }

    @Async("emailTaskExecutor")
    public void sendPayoutFailedEmail(String toEmail, String transactionCode, String amount,
                                      String totalCharged, String recipientLabel, String reason, String payerPhone) {
        send(toEmail, "We couldn't deliver your transfer " + transactionCode,
                EmailTemplates.payoutFailedEmail(transactionCode, amount, totalCharged, recipientLabel, reason, payerPhone));
    }

    @Async("emailTaskExecutor")
    public void sendKycDecisionEmail(User user, String outcome, String detail, String dailyLimit) {
        String subject = switch (outcome) {
            case "APPROVED" -> "Your identity is verified";
            case "REVOKED" -> "Your verification was withdrawn";
            default -> "Your verification was not approved";
        };
        send(user.getEmail(), subject,
                EmailTemplates.kycDecisionEmail(user.getFirstName(), outcome, detail, dailyLimit));
    }

    @Async("emailTaskExecutor")
    public void sendRefundInitiatedEmail(String toEmail, String transactionCode, String totalCharged, String payerPhone) {
        send(toEmail, "Your refund is on its way " + transactionCode,
                EmailTemplates.refundInitiatedEmail(transactionCode, totalCharged, payerPhone));
    }

    @Async("emailTaskExecutor")
    public void sendRefundCompletedEmail(String toEmail, String transactionCode, String totalCharged, String payerPhone) {
        send(toEmail, "Refund sent " + transactionCode,
                EmailTemplates.refundCompletedEmail(transactionCode, totalCharged, payerPhone));
    }

    private boolean sendTracked(String toEmail, String subject, String htmlBody) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Skipping email '{}': no recipient address available", subject);
            return false;
        }
        final int maxAttempts = 2;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                transmit(toEmail, subject, htmlBody);
                log.info("Email '{}' sent to {}", subject, toEmail);
                return true;
            } catch (Exception e) {
                boolean retry = attempt < maxAttempts && isSafeToRetry(e);
                log.error("Failed to send email '{}' to {} (attempt {}/{}, {}): {}",
                        subject, toEmail, attempt, maxAttempts,
                        retry ? "will retry" : "giving up", rootMessage(e));
                if (!retry) {
                    return false;
                }
                if (!pause(2000L)) {
                    return false;
                }
            }
        }
        return false;
    }

    private void sendRequired(
            String toEmail,
            String subject,
            String htmlBody
    ) {
        if (toEmail == null || toEmail.isBlank()) {
            throw new IllegalArgumentException("Recipient email is required");
        }

        // Only a clear "not accepted" answer (a 4xx reply such as "451 Deferred", or no connection at all)
        // is tried again. A timeout is NOT retried: the server may already have taken the message,
        // and sending it again would put two copies in the inbox.
        final int maxAttempts = 3;
        Exception lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                transmit(toEmail, subject, htmlBody);
                return;

            } catch (Exception e) {
                lastError = e;
                boolean retry = attempt < maxAttempts && isSafeToRetry(e);
                log.warn("Required email '{}' to {} failed (attempt {}/{}, {}): {}",
                        subject, toEmail, attempt, maxAttempts,
                        retry ? "will retry" : "giving up", rootMessage(e));
                if (!retry || !pause(attempt == 1 ? 1000L : 3000L)) {
                    break;
                }
            }
        }

        log.error("Failed to send required email '{}' to {}", subject, toEmail, lastError);
        throw new IllegalStateException(
                "Unable to send verification email. Please try again.",
                lastError
        );
    }

    private static String rootMessage(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m.strip();
    }

    /** Sends a notice once. The same message to the same address is skipped if it went out a moment ago. */
    private void send(String toEmail, String subject, String htmlBody) {
        deliver(toEmail, subject, htmlBody, true);
    }

    /** For notices that may legitimately repeat in quick succession (account paused, then active again). */
    private void sendAlways(String toEmail, String subject, String htmlBody) {
        deliver(toEmail, subject, htmlBody, false);
    }

    private void deliver(String toEmail, String subject, String htmlBody, boolean once) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn(
                    "Skipping email '{}': no recipient address available",
                    subject
            );
            return;
        }

        String key = null;
        if (once) {
            key = toEmail.trim().toLowerCase() + "|" + subject + "|" + htmlBody.hashCode();
            if (!claimOnce(key)) {
                log.warn("Skipping duplicate email '{}' to {}: the same message was just sent", subject, toEmail);
                return;
            }
        }

        final int maxAttempts = 3;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info(
                        "Sending email to: {} - Subject: {} (attempt {}/{})",
                        toEmail,
                        subject,
                        attempt,
                        maxAttempts
                );

                transmit(toEmail, subject, htmlBody);

                log.info("Email sent successfully to: {}", toEmail);
                return;

            } catch (Exception e) {
                boolean retry = attempt < maxAttempts && isSafeToRetry(e);
                log.error(
                        "Failed to send email '{}' to {} (attempt {}/{}, {}): {}",
                        subject,
                        toEmail,
                        attempt,
                        maxAttempts,
                        retry ? "will retry" : "giving up",
                        rootMessage(e)
                );

                if (!retry) {
                    // The server clearly refused it (or never connected): a later request may send it again.
                    // After a timeout the message may already be delivered, so the duplicate guard stays on.
                    if (key != null && (isPermanentRejection(e) || isSafeToRetry(e))) {
                        recentlySent.remove(key);
                    }
                    return;
                }

                if (!pause(2000L * attempt)) {
                    return;
                }
            }
        }

        if (key != null) {
            recentlySent.remove(key);
        }
        log.error(
                "Giving up on email '{}' to {} after {} attempts",
                subject,
                toEmail,
                maxAttempts
        );
    }

    /** Builds and hands one message to the mail server. */
    private void transmit(String toEmail, String subject, String htmlBody) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(
                message,
                true,
                StandardCharsets.UTF_8.name()
        );

        helper.setFrom(senderEmail, senderName);
        helper.setReplyTo(supportEmail, "DeenWallet Transfer Support");
        helper.setTo(toEmail);
        helper.setSubject(subject);
        helper.setText(plainText(htmlBody), htmlBody);

        mailSender.send(message);
    }

    /** Waits before a retry. Returns false if the thread was interrupted. */
    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** True when this exact message was not sent within the last minute and a half; records it as sent. */
    private boolean claimOnce(String key) {
        long now = System.currentTimeMillis();
        if (recentlySent.size() > 500) {
            recentlySent.entrySet().removeIf(e -> now - e.getValue() > DUPLICATE_WINDOW_MS);
        }
        boolean[] fresh = {false};
        recentlySent.compute(key, (k, sentAt) -> {
            if (sentAt == null || now - sentAt > DUPLICATE_WINDOW_MS) {
                fresh[0] = true;
                return now;
            }
            return sentAt;
        });
        return fresh[0];
    }

    /**
     * The mail error plus everything inside it. Spring keeps the real SMTP errors in
     * MailSendException.getMessageExceptions(), not in getCause(), so both are followed.
     */
    private static java.util.List<Throwable> errorTree(Throwable error) {
        java.util.List<Throwable> seen = new java.util.ArrayList<>();
        java.util.ArrayDeque<Throwable> todo = new java.util.ArrayDeque<>();
        if (error != null) {
            todo.add(error);
        }
        while (!todo.isEmpty() && seen.size() < 32) {
            Throwable t = todo.poll();
            boolean known = false;
            for (Throwable s : seen) {
                if (s == t) {
                    known = true;
                    break;
                }
            }
            if (known) {
                continue;
            }
            seen.add(t);
            if (t.getCause() != null) {
                todo.add(t.getCause());
            }
            if (t instanceof org.springframework.mail.MailSendException m && m.getMessageExceptions() != null) {
                for (Exception inner : m.getMessageExceptions()) {
                    if (inner != null) {
                        todo.add(inner);
                    }
                }
            }
        }
        return seen;
    }

    /** The numeric SMTP reply (250, 451, 550 ...) carried by an SMTP exception, or null. Read by reflection: the SMTP classes are only on the runtime classpath. */
    private static Integer smtpReturnCode(Throwable t) {
        if (!t.getClass().getName().contains("SMTP")) {
            return null;
        }
        try {
            Object code = t.getClass().getMethod("getReturnCode").invoke(t);
            return code instanceof Integer c ? c : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * True only when the message was certainly NOT accepted, so sending it again cannot create a duplicate:
     * the server answered with a 4xx reply (for example "451 Deferred"), or the connection was never made.
     * A timeout or a dropped connection is unknown (the server may have taken the message), so it is not retried.
     */
    private static boolean isSafeToRetry(Throwable error) {
        for (Throwable t : errorTree(error)) {
            if (t.getClass().getName().endsWith("MailConnectException")) {
                return true;
            }
            Integer code = smtpReturnCode(t);
            if (code != null) {
                return code >= 400 && code < 500;
            }
        }
        return false;
    }

    /** A 5xx answer from the mail server means "refused for good": sending the identical message again only hurts the sender's reputation. */
    private static boolean isPermanentRejection(Throwable error) {
        for (Throwable t : errorTree(error)) {
            Integer code = smtpReturnCode(t);
            if (code != null) {
                return code >= 500 && code < 600;
            }
        }
        return false;
    }

    /** Plain-text version of an HTML e-mail. Messages with both parts are filtered as spam far less often than HTML-only ones. */
    private static String plainText(String html) {
        if (html == null) return "";
        String text = html
                .replaceAll("(?is)<(style|script|head)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>|</p>|</h[1-6]>|</tr>|</div>", "\n")
                .replaceAll("(?i)</td>", "  ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll(" ?\n ?", "\n").replaceAll("\n{3,}", "\n\n").trim();
    }

    private String toDisplayAmount(Long minorUnits) {
        if (minorUnits == null) {
            return "0.00";
        }

        return BigDecimal.valueOf(minorUnits)
                .movePointLeft(2)
                .toPlainString();
    }
}