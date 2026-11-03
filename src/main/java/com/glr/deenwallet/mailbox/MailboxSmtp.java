package com.glr.deenwallet.mailbox;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Sends the support mailbox's emails (replies and emails an admin writes by hand) through the same Gmail account that
 * receives the support mail, so QueenSMTP is left for the automatic emails only (codes, receipts, alerts).
 *
 * Settings (server environment): it uses INBOX_IMAP_USER and INBOX_IMAP_PASSWORD (the Gmail app password) unless you set
 * INBOX_SMTP_USER / INBOX_SMTP_PASSWORD. Optional: INBOX_SMTP_HOST (default smtp.gmail.com), INBOX_SMTP_PORT (587),
 * INBOX_SMTP_FROM (the visible sender address, default the Gmail address itself), INBOX_SMTP_ENABLED=false to switch it off
 * (the mailbox then goes back to QueenSMTP).
 * The mail is sent FROM the Gmail address with the name "DeenWallet Transfer Support" and Reply-To the support address, so a
 * customer's answer reaches the support inbox, and your domain's own mail settings are never involved.
 */
@Component
public class MailboxSmtp {

    private static final Logger log = LoggerFactory.getLogger(MailboxSmtp.class);

    @Value("${INBOX_SMTP_ENABLED:true}")
    private boolean enabled;

    @Value("${INBOX_SMTP_HOST:smtp.gmail.com}")
    private String host;

    @Value("${INBOX_SMTP_PORT:587}")
    private int port;

    @Value("${INBOX_SMTP_USER:${INBOX_IMAP_USER:}}")
    private String user;

    @Value("${INBOX_SMTP_PASSWORD:${INBOX_IMAP_PASSWORD:}}")
    private String password;

    @Value("${INBOX_SMTP_FROM:}")
    private String fromOverride;

    public boolean configured() {
        return enabled && user != null && !user.isBlank() && password != null && !password.isBlank();
    }

    /** Same contract as QueenSmtpClient.send: a readable exception when nothing was sent, Uncertain when it may have been. */
    public QueenSmtpClient.Sent send(String fromName, String replyTo, String to, String subject, String html, String text, String inReplyTo) {
        if (!configured()) throw new IllegalStateException("Email sending is not connected yet. Add INBOX_IMAP_USER and INBOX_IMAP_PASSWORD to the server settings.");
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "20000");
        props.put("mail.smtp.writetimeout", "20000");
        Session session = Session.getInstance(props);
        MimeMessage msg;
        try {
            String from = fromOverride != null && !fromOverride.isBlank() ? fromOverride.trim() : user.trim();
            msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(from, fromName, StandardCharsets.UTF_8.name()));
            if (replyTo != null && !replyTo.isBlank()) msg.setReplyTo(new InternetAddress[]{new InternetAddress(replyTo, fromName, StandardCharsets.UTF_8.name())});
            msg.setRecipient(Message.RecipientType.TO, new InternetAddress(to, true));
            msg.setSubject(subject, StandardCharsets.UTF_8.name());
            if (inReplyTo != null && inReplyTo.matches("^<[^<>\\s]{1,300}>$")) {
                msg.setHeader("In-Reply-To", inReplyTo);
                msg.setHeader("References", inReplyTo);
            }
            MimeMultipart alt = new MimeMultipart("alternative");
            MimeBodyPart plain = new MimeBodyPart();
            plain.setText(text, StandardCharsets.UTF_8.name());
            MimeBodyPart rich = new MimeBodyPart();
            rich.setContent(html, "text/html; charset=UTF-8");
            alt.addBodyPart(plain);
            alt.addBodyPart(rich);
            msg.setContent(alt);
            msg.saveChanges();
        } catch (jakarta.mail.internet.AddressException e) {
            throw new IllegalArgumentException("The email address or the message is not valid. Please check them.");
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            throw new IllegalArgumentException("The email address or the message is not valid. Please check them.");
        }

        Transport transport = null;
        try {
            try {
                transport = session.getTransport("smtp");
                transport.connect(host, port, user.trim(), password);
            } catch (AuthenticationFailedException e) {
                log.warn("Mailbox: Gmail refused the sending login: {}", e.getMessage());
                throw new IllegalStateException("Gmail refused the login. Check INBOX_IMAP_USER and the app password.");
            } catch (MessagingException e) {
                log.warn("Mailbox: could not reach the sending server: {}", e.toString());
                throw new IllegalStateException("The email service could not be reached. Please try again.");
            }
            try {
                transport.sendMessage(msg, msg.getAllRecipients());
            } catch (SendFailedException e) {
                // the server answered and refused: nothing was sent
                String why = String.valueOf(e.getMessage()).toLowerCase();
                if (e.getInvalidAddresses() != null && e.getInvalidAddresses().length > 0) {
                    throw new IllegalArgumentException("This address cannot receive mail from us. Please check it.");
                }
                if (why.contains("daily") || why.contains("quota") || why.contains("limit") || why.contains("rate")) {
                    throw new IllegalStateException("Gmail's sending limit was reached. Please try again later.");
                }
                log.warn("Mailbox: the sending server refused a message: {}", e.getMessage());
                throw new IllegalStateException("The email could not be sent right now.");
            } catch (MessagingException e) {
                // the connection broke around the data: it may or may not have been accepted
                log.warn("Mailbox: unclear result while sending: {}", e.toString());
                throw new QueenSmtpClient.Uncertain();
            }
        } finally {
            if (transport != null) {
                try { transport.close(); } catch (MessagingException ignore) { /* nothing to do */ }
            }
        }
        return new QueenSmtpClient.Sent("", "sent");
    }
}
