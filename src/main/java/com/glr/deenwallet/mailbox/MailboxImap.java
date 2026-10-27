package com.glr.deenwallet.mailbox;

import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import com.glr.deenwallet.notification.AdminAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the support inbox over IMAP (the Gmail that support@ forwards to) and stores new emails.
 * Read-only: nothing in the mailbox is changed, marked as read or deleted.
 * Settings (server environment): INBOX_IMAP_USER, INBOX_IMAP_PASSWORD (a Gmail app password),
 * optional INBOX_IMAP_HOST (default imap.gmail.com) and INBOX_IMAP_PORT (default 993).
 */
@Component
public class MailboxImap {

    private static final Logger log = LoggerFactory.getLogger(MailboxImap.class);
    private static final int FIRST_RUN_MESSAGES = 200;
    private static final int PER_POLL = 100;
    private static final int MAX_TEXT = 60_000;
    private static final int MAX_STREAM = 300_000;

    @Value("${INBOX_IMAP_HOST:imap.gmail.com}")
    private String host;

    @Value("${INBOX_IMAP_PORT:993}")
    private int port;

    @Value("${INBOX_IMAP_USER:}")
    private String user;

    @Value("${INBOX_IMAP_PASSWORD:}")
    private String password;

    private final MailboxService service;
    private final AdminAlertService alerts;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MailboxImap(MailboxService service, AdminAlertService alerts) {
        this.service = service;
        this.alerts = alerts;
    }

    public boolean configured() {
        return user != null && !user.isBlank() && password != null && !password.isBlank();
    }

    /** poll() answers: how many new emails were stored, or one of these. */
    public static final int BUSY = -1;
    public static final int NOT_CONFIGURED = -2;
    public static final int FAILED = -3;

    private volatile String lastFailure = "";

    /** Readable reason for the last failed check ("" when the last check worked). */
    public String lastFailure() {
        return lastFailure;
    }

    /**
     * Checks the inbox once. Safe to call from the schedule and from the Refresh button at the same time:
     * a second caller gets BUSY instead of starting another check. A login/network hiccup is retried once.
     */
    public int poll() {
        if (!configured()) return NOT_CONFIGURED;
        if (!running.compareAndSet(false, true)) return BUSY;
        try {
            int stored;
            try {
                stored = doPoll();
            } catch (Exception first) {
                log.info("Mailbox: first attempt failed ({}), trying once more", first.toString());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw first;
                }
                stored = doPoll();
            }
            lastFailure = "";
            return stored;
        } catch (AuthenticationFailedException e) {
            log.warn("Mailbox: the mail server refused the login: {}", e.getMessage());
            lastFailure = "The mail server refused the login. Check INBOX_IMAP_USER and the app password.";
            service.saveError(lastFailure);
            return FAILED;
        } catch (Exception e) {
            log.warn("Mailbox: could not read the inbox: {}", e.toString());
            lastFailure = "Could not read the inbox: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            service.saveError(lastFailure);
            return FAILED;
        } finally {
            running.set(false);
        }
    }

    private int doPoll() throws Exception {
        Properties props = new Properties();
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.connectiontimeout", "15000");
        props.put("mail.imaps.timeout", "30000");
        Session session = Session.getInstance(props);
        Store store = session.getStore("imaps");
        try {
            store.connect(host, port, user, password);
            Folder folder = store.getFolder("INBOX");
            try {
                folder.open(Folder.READ_ONLY);
                UIDFolder uids = (UIDFolder) folder;
                long validity = uids.getUIDValidity();
                long[] state = service.state();
                long lastUid = state[0] == validity ? state[1] : 0L;

                Message[] found;
                if (lastUid > 0) {
                    found = uids.getMessagesByUID(lastUid + 1, -1L);   // -1 = the newest UID
                } else {
                    int count = folder.getMessageCount();
                    found = count <= 0 ? new Message[0] : folder.getMessages(Math.max(1, count - FIRST_RUN_MESSAGES + 1), count);
                }
                List<Message> fresh = new ArrayList<>();
                for (Message m : found) {
                    if (m != null && uids.getUID(m) > lastUid) fresh.add(m);
                }
                fresh.sort(Comparator.comparingLong(m -> {
                    try { return uids.getUID(m); } catch (Exception e) { return Long.MAX_VALUE; }
                }));
                long maxUid = lastUid;
                int stored = 0;
                int handled = 0;
                List<String> arrived = new ArrayList<>();
                for (Message m : fresh) {
                    if (handled >= PER_POLL) break;
                    handled++;
                    long uid = uids.getUID(m);
                    try {
                        MailboxService.Incoming in = read(m, uid);
                        if (service.importIncoming(in)) {
                            stored++;
                            // only mail that really just arrived is announced (never the old backlog of a first run)
                            if (lastUid > 0 && (in.at() == null || in.at().isAfter(Instant.now().minus(1, ChronoUnit.DAYS)))) {
                                String who = in.fromName() == null || in.fromName().isBlank() ? in.fromEmail() : in.fromName().trim();
                                String subj = in.subject() == null || in.subject().isBlank() ? "(no subject)" : in.subject().trim();
                                arrived.add(who + ": " + (subj.length() > 100 ? subj.substring(0, 100) : subj));
                            }
                        }
                    } catch (Exception e) {
                        // an unreadable email must not block the ones behind it
                        log.warn("Mailbox: could not read the email with UID {}: {}", uid, e.toString());
                    }
                    if (uid > maxUid) maxUid = uid;
                }
                service.saveState(validity, maxUid);
                try {
                    if (arrived.size() == 1) {
                        alerts.notifyAdmins(null, "EMAIL_RECEIVED", "New customer email", arrived.get(0));
                    } else if (arrived.size() > 1) {
                        alerts.notifyAdmins(null, "EMAIL_RECEIVED", "New customer emails", arrived.size() + " new emails in the support inbox, latest from " + arrived.get(arrived.size() - 1));
                    }
                } catch (RuntimeException e) {
                    log.warn("Mailbox: could not announce new emails: {}", e.toString());
                }
                if (stored > 0) log.info("Mailbox: {} new email(s) stored", stored);
                return stored;
            } finally {
                try { if (folder.isOpen()) folder.close(false); } catch (Exception ignored) { }
            }
        } finally {
            try { store.close(); } catch (Exception ignored) { }
        }
    }

    private MailboxService.Incoming read(Message m, long uid) throws Exception {
        String[] ids = m.getHeader("Message-ID");
        String rfcId = ids == null || ids.length == 0 ? null : ids[0].trim();
        String email = "";
        String name = "";
        Address[] who = m.getReplyTo();
        if (who == null || who.length == 0) who = m.getFrom();
        if (who != null && who.length > 0 && who[0] instanceof InternetAddress ia) {
            email = ia.getAddress() == null ? "" : ia.getAddress().trim();
            name = ia.getPersonal() == null ? "" : ia.getPersonal().trim();
        }
        java.util.Date sent = m.getSentDate() != null ? m.getSentDate() : m.getReceivedDate();
        Instant at = sent == null ? Instant.now() : sent.toInstant();
        if (at.isAfter(Instant.now().plus(1, ChronoUnit.DAYS))) at = Instant.now();
        String subject = m.getSubject();

        Body body = new Body();
        if (m.getSize() > 15_000_000) {
            body.text.append("(This email is too large to show here. Open it in the support mailbox.)");
        } else {
            try {
                walk(m, body);
            } catch (Exception e) {
                body.text.append("(This email could not be displayed.)");
            }
        }
        String text = body.text.toString().trim();
        if (text.isEmpty() && body.html != null) text = htmlToText(body.html);
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT) + "\n...";
        return new MailboxService.Incoming(uid, rfcId, email, name, subject, at, text, body.attachments);
    }

    private static final class Body {
        final StringBuilder text = new StringBuilder();
        String html;
        int attachments;
    }

    private void walk(Part p, Body b) throws Exception {
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) walk(mp.getBodyPart(i), b);
            return;
        }
        String disposition = p.getDisposition();
        if (Part.ATTACHMENT.equalsIgnoreCase(disposition) || (p.getFileName() != null && !p.isMimeType("text/plain") && !p.isMimeType("text/html"))) {
            b.attachments++;
            return;
        }
        if (p.isMimeType("text/plain")) {
            String s = textOf(p);
            if (!s.isBlank()) {
                if (b.text.length() > 0) b.text.append("\n\n");
                b.text.append(s);
            }
        } else if (p.isMimeType("text/html")) {
            if (b.html == null) b.html = textOf(p);
        }
    }

    private String textOf(Part p) throws Exception {
        try {
            Object c = p.getContent();
            if (c instanceof String s) return s;
        } catch (IOException | RuntimeException e) {
            // unknown character set etc.: fall through to the raw bytes
        }
        try (InputStream in = p.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < MAX_STREAM) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static String htmlToText(String html) {
        String s = html == null ? "" : html;
        s = s.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n");
        s = s.replaceAll("(?s)<[^>]*>", "");
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        s = s.replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n");
        return s.trim();
    }

}
