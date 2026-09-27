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

    private final JavaMailSender mailSender;

    public void sendOtpEmail(String toEmail, String code, int expiryMinutes) {
        sendRequired(toEmail, "Verification Code",
                EmailTemplates.otpEmail(code, expiryMinutes));
    }

    public void sendWelcomeEmail(User user) {
        send(user.getEmail(), "Welcome to Deen Wallet",
                EmailTemplates.welcomeEmail(user.getFirstName(), user.getAccountNumber()));
    }

    @Async("emailTaskExecutor")
    public void sendAccountActivatedEmail(User user) {
        send(user.getEmail(), "Deen Wallet Account Profile Notice",
                EmailTemplates.accountActivatedEmail(user.getFirstName()));
        log.info("✅ Account activation email sent to: {}", user.getEmail());
    }

    @Async("emailTaskExecutor")
    public void sendAccountDeactivatedEmail(User user) {
        send(user.getEmail(), "Deen Wallet Account Service Summary",
                EmailTemplates.accountDeactivatedEmail(user.getFirstName(), supportEmail));
        log.info("✅ Account update email sent to: {}", user.getEmail());
    }

    @Async("emailTaskExecutor")
    public void sendAccountLockedEmail(User user) {
        sendAccountLockedEmail(user, "security");
    }

    @Async("emailTaskExecutor")
    public void sendAccountLockedEmail(User user, String lockReason) {
        send(user.getEmail(), "Deen Wallet Service Activity Summary",
                EmailTemplates.accountLockedEmail(user.getFirstName(), lockReason, supportEmail));
        log.info("✅ Account activity email ({}) sent to: {}", lockReason, user.getEmail());
    }

    @Async("emailTaskExecutor")
    public void sendAdminLoginBlockedEmail(User user, boolean superAdmin) {
        send(user.getEmail(), "Deen Wallet Administrative Activity Notice",
                EmailTemplates.adminLoginBlockedEmail(user.getFirstName(), superAdmin, supportEmail));
        log.info("Admin status email queued for: {}", user.getEmail());
    }

    public void sendTransactionCompletedEmail(String toEmail, Transaction transaction) {
        String transactionCode = transaction.getTransactionCode() != null ?
                transaction.getTransactionCode() : transaction.getId().toString();

        send(toEmail, "Transfer Receipt",
                EmailTemplates.transactionCompletedEmail(
                        transactionCode,
                        toDisplayAmount(transaction.getAmountValue()),
                        toDisplayAmount(transaction.getTotalChargedValue()),
                        transaction.getDestinationHolderName(),
                        transaction.getDestinationPhone(),
                        transaction.getDestinationProviderId()
                ));
    }

    public void sendTransactionFailedEmail(String toEmail, Transaction transaction) {
        String transactionCode = transaction.getTransactionCode() != null ?
                transaction.getTransactionCode() : transaction.getId().toString();

        send(toEmail, "Transfer Status Update",
                EmailTemplates.transactionFailedEmail(
                        transactionCode,
                        toDisplayAmount(transaction.getAmountValue()),
                        transaction.getDestinationPhone(),
                        transaction.getFailureReason()
                ));
    }

    private void sendRequired(String toEmail, String subject, String htmlBody) {
        if (toEmail == null || toEmail.isBlank()) throw new IllegalArgumentException("Recipient email is required");
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

            helper.setFrom(senderEmail, senderName);
            helper.setReplyTo(supportEmail, "Deen Wallet Support");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);

            mailSender.send(message);
        } catch (Exception e) {
            log.error("Failed to send required email to {}", toEmail, e);
            throw new IllegalStateException("Unable to send verification email. Please try again.", e);
        }
    }

    private void send(String toEmail, String subject, String htmlBody) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Skipping email '{}': no recipient address available", subject);
            return;
        }
        try {
            log.info("📧 Sending email to: {} - Subject: {}", toEmail, subject);
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

            helper.setFrom(senderEmail, senderName);
            helper.setReplyTo(supportEmail, "Deen Wallet Support");
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);

            mailSender.send(message);
            log.info("✅ Email sent successfully to: {}", toEmail);
        } catch (Exception e) {
            log.error("❌ Failed to send email '{}' to {}: {}", subject, toEmail, e.getMessage(), e);
        }
    }

    private String toDisplayAmount(Long minorUnits) {
        if (minorUnits == null) {
            return "0.00";
        }
        return BigDecimal.valueOf(minorUnits).movePointLeft(2).toPlainString();
    }
}