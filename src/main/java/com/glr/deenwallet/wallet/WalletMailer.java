package com.glr.deenwallet.wallet;

import com.glr.deenwallet.user.User;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Emails for the admin wallet: withdrawal notices and transfer notices.
 * Self-contained (own look and sending) so it does not depend on the shared email classes.
 * Mail problems are logged and never break a withdrawal or a transfer.
 */
@Component
public class WalletMailer {

    private static final Logger log = LoggerFactory.getLogger(WalletMailer.class);
    private static final String BRAND = "#0F766E";
    private static final String TEXT = "#1F2937";
    private static final String MUTED = "#6B7280";
    private static final String BORDER = "#E5E7EB";

    @Value("${app.mail.from-email}")
    private String senderEmail;

    @Value("${app.mail.from-name}")
    private String senderName;

    @Value("${app.mail.support-email:support@deenwallapp.com}")
    private String supportEmail;

    private final JavaMailSender mailSender;

    public WalletMailer(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** Withdrawal notice (stage: started, completed or failed) to the owner's registered email. */
    @Async("emailTaskExecutor")
    public void sendWalletWithdrawalEmail(User admin, String stage, String amount, String fee,
                                          String destination, String failureReason, String reference) {
        boolean done = "completed".equals(stage);
        boolean failed = "failed".equals(stage);
        String title = done ? "Withdrawal completed" : (failed ? "Withdrawal failed" : "Withdrawal requested");
        String intro = done
                ? "Your withdrawal has been paid out."
                : (failed ? "Your withdrawal could not be completed. If it was taken from your admin wallet, the money has been returned to it."
                          : "We received your withdrawal request and it is being processed.");
        StringBuilder rows = new StringBuilder();
        rows.append(row("Reference", reference));
        rows.append(row("Amount", amount));
        rows.append(row("Monime fee", fee));
        rows.append(row("Sent to", destination));
        if (failed && failureReason != null && !failureReason.isBlank()) rows.append(row("Reason", failureReason));
        String body = "<h2 style=\"margin:0 0 14px 0;color:" + BRAND + ";font-size:22px;text-align:center;\">" + esc(title) + "</h2>"
                + "<p style=\"margin:0 0 16px 0;color:" + MUTED + ";font-size:15px;line-height:1.6;\">Hello " + esc(admin.getFirstName()) + ", " + esc(intro) + "</p>"
                + table(rows)
                + "<p style=\"margin:0;color:" + MUTED + ";font-size:13px;text-align:center;\">If you did not make this withdrawal, contact support immediately.</p>";
        send(admin.getEmail(), title + " " + reference, wrap(title, body));
    }

    /** Transfer notice: to the receiving admin (received = true) and a receipt to the super admin. */
    @Async("emailTaskExecutor")
    public void sendWalletTransferEmail(User to, boolean received, String otherName, String amount,
                                        String note, String newBalance) {
        String title = received ? "Money added to your wallet" : "Transfer sent";
        String intro = received
                ? otherName + " sent money to your admin wallet. Internal transfers are free."
                : "You sent money to " + otherName + "'s admin wallet. Internal transfers are free.";
        StringBuilder rows = new StringBuilder();
        rows.append(row("Amount", amount));
        rows.append(row(received ? "From" : "To", otherName));
        if (note != null && !note.isBlank()) rows.append(row("Note", note));
        if (received && newBalance != null) rows.append(row("Your wallet balance", newBalance));
        String body = "<h2 style=\"margin:0 0 14px 0;color:" + BRAND + ";font-size:22px;text-align:center;\">" + esc(title) + "</h2>"
                + "<p style=\"margin:0 0 16px 0;color:" + MUTED + ";font-size:15px;line-height:1.6;\">Hello " + esc(to.getFirstName()) + ", " + esc(intro) + "</p>"
                + table(rows)
                + "<p style=\"margin:0;color:" + MUTED + ";font-size:13px;text-align:center;\">If you do not recognise this transfer, contact support immediately.</p>";
        send(to.getEmail(), received ? "You received " + amount + " in your wallet" : "Transfer sent " + amount, wrap(title, body));
    }

    private static String table(StringBuilder rows) {
        return "<div style=\"border:1px solid " + BORDER + ";border-radius:12px;overflow:hidden;margin-bottom:24px;\">"
                + "<table cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;border-collapse:collapse;\">" + rows + "</table></div>";
    }

    private static String row(String label, String value) {
        return "<tr style=\"border-bottom:1px solid " + BORDER + ";\">"
                + "<td style=\"padding:14px 16px;font-size:14px;color:" + MUTED + ";\">" + esc(label) + "</td>"
                + "<td style=\"padding:14px 16px;font-size:14px;font-weight:600;color:" + TEXT + ";text-align:right;\">" + esc(value) + "</td></tr>";
    }

    private static String esc(String v) {
        return v == null ? "" : v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String wrap(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"><title>" + esc(title) + "</title></head>"
                + "<body style=\"margin:0;padding:0;background:#F3F4F6;font-family:'Helvetica Neue',Helvetica,Arial,sans-serif;\">"
                + "<table cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;background:#F3F4F6;padding:32px 16px;\"><tr><td align=\"center\">"
                + "<table cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:540px;width:100%;background:#FFFFFF;border-radius:16px;overflow:hidden;border:1px solid " + BORDER + ";\">"
                + "<tr><td style=\"background:" + BRAND + ";padding:22px 24px;text-align:center;font-size:22px;font-weight:800;letter-spacing:1px;color:#FFFFFF;\">DEENWALLET TRANSFER</td></tr>"
                + "<tr><td style=\"padding:32px 24px;background:#FFFFFF;\">" + body + "</td></tr>"
                + "<tr><td style=\"padding:20px 24px;background:#F9FAFB;border-top:1px solid " + BORDER + ";text-align:center;color:" + MUTED + ";font-size:11px;\">"
                + "DEEN WALLET LTD &middot; Need help? support@deenwallapp.com<br>This is an automated message, please do not reply.</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    private void send(String toEmail, String subject, String html) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Wallet email '{}' skipped: no recipient address", subject);
            return;
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
                helper.setFrom(senderEmail, senderName);
                helper.setReplyTo(supportEmail, "DeenWallet Transfer Support");
                helper.setTo(toEmail);
                helper.setSubject(subject);
                helper.setText(html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim(), html);
                mailSender.send(message);
                log.info("Wallet email sent to {} - {}", toEmail, subject);
                return;
            } catch (Exception e) {
                log.warn("Wallet email to {} failed (attempt {}/3): {}", toEmail, attempt, e.toString());
                try { Thread.sleep(1500L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }
}
