package com.glr.deenwallet.mailbox;

import com.glr.deenwallet.audit.AuditService;
import com.glr.deenwallet.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Support mailbox: customer emails (read from the support inbox by MailboxImap) and the replies admins send
 * through QueenSMTP. One shared inbox for all admins. Messages are grouped into conversations by customer + subject.
 */
@Service
public class MailboxService {

    private static final Logger log = LoggerFactory.getLogger(MailboxService.class);
    private static final String SIGNATURE_NAME = "DeenWallet Transfer Support";
    private static final int MAX_BODY = 10_000;

    @Value("${app.mail.support-email:support@deenwallapp.com}")
    private String supportEmail;

    @Value("${app.mail.from-email:no-reply@deenwallapp.com}")
    private String systemEmail;

    @Value("${INBOX_IMAP_USER:}")
    private String imapUser;

    @Value("${INBOX_IMAP_PASSWORD:}")
    private String imapPassword;

    @PersistenceContext
    private EntityManager em;

    private final TransactionTemplate tx;
    private final QueenSmtpClient smtp;
    private final AuditService audit;

    public MailboxService(TransactionTemplate tx, QueenSmtpClient smtp, AuditService audit) {
        this.tx = tx;
        this.smtp = smtp;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ shapes

    public record Summary(boolean canSend, boolean canReceive, long unread, String lastPoll, String lastError, String supportEmail) { }

    public record ThreadRow(String id, String subject, String name, String email, String preview, Instant at, long unread, long count, boolean lastOut) { }

    public record SentRow(String id, String threadId, String to, String toName, String subject, String preview, Instant at, String status, String sentBy) { }

    public record Msg(String id, String direction, String name, String email, String subject, String body, int attachments,
                      Instant at, String sentBy, String status, String error) { }

    public record Conversation(String id, String subject, String name, String email, List<Msg> messages) { }

    /** One email read from the inbox. */
    public record Incoming(long uid, String rfcId, String fromEmail, String fromName, String subject, Instant at, String text, int attachments) { }

    // ------------------------------------------------------------------ settings / state

    public boolean canReceive() {
        return imapUser != null && !imapUser.isBlank() && imapPassword != null && !imapPassword.isBlank();
    }

    public Summary summary() {
        Object[] st = (Object[]) em.createNativeQuery("SELECT last_poll, last_error FROM mail_state WHERE id = 1").getSingleResult();
        Instant poll = instant(st[0]);
        return new Summary(smtp.configured(), canReceive(), unread(), poll == null ? null : poll.toString(),
                st[1] == null ? "" : (String) st[1], supportEmail);
    }

    public long unread() {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(DISTINCT thread_id) FROM mail_messages WHERE direction = 'IN' AND is_read = FALSE").getSingleResult()).longValue();
    }

    /** {imap_validity, last_uid} */
    long[] state() {
        Object[] r = (Object[]) em.createNativeQuery("SELECT imap_validity, last_uid FROM mail_state WHERE id = 1").getSingleResult();
        return new long[]{((Number) r[0]).longValue(), ((Number) r[1]).longValue()};
    }

    void saveState(long validity, long lastUid) {
        tx.executeWithoutResult(st -> em.createNativeQuery(
                        "UPDATE mail_state SET imap_validity = :v, last_uid = :u, last_poll = now(), last_error = '' WHERE id = 1")
                .setParameter("v", validity).setParameter("u", lastUid).executeUpdate());
    }

    void saveError(String error) {
        String e = error == null ? "" : (error.length() > 280 ? error.substring(0, 280) : error);
        tx.executeWithoutResult(st -> em.createNativeQuery("UPDATE mail_state SET last_poll = now(), last_error = :e WHERE id = 1")
                .setParameter("e", e).executeUpdate());
    }

    // ------------------------------------------------------------------ incoming

    /** Stores one email from the inbox. Returns false when it was skipped (own address) or already stored. */
    boolean importIncoming(Incoming m) {
        String email = m.fromEmail() == null ? "" : m.fromEmail().trim();
        if (email.isEmpty() || email.length() > 254) return false;
        // never store our own mail (support address or the system sender) - protects against forwarding loops
        if (email.equalsIgnoreCase(supportEmail) || email.equalsIgnoreCase(systemEmail)) return false;
        String subject = normSubject(m.subject());
        UUID thread = threadOf(email, subject);
        String rfc = m.rfcId() == null || m.rfcId().isBlank() ? "<uid-" + m.uid() + "@imap.local>" : cut(m.rfcId().trim(), 300);
        String name = cut(m.fromName() == null ? "" : m.fromName().trim(), 200);
        int saved = tx.execute(st -> em.createNativeQuery(
                        "INSERT INTO mail_messages (id, thread_id, direction, rfc_message_id, imap_uid, counterparty_email, counterparty_name, "
                                + "subject, body_text, attachments, created_at, status) "
                                + "VALUES (:id, :t, 'IN', :rfc, :uid, :em, :nm, :sub, :body, :att, :at, 'RECEIVED') "
                                + "ON CONFLICT (rfc_message_id) DO NOTHING")
                .setParameter("id", UUID.randomUUID()).setParameter("t", thread).setParameter("rfc", rfc)
                .setParameter("uid", m.uid()).setParameter("em", email).setParameter("nm", name)
                .setParameter("sub", subject).setParameter("body", m.text() == null ? "" : m.text())
                .setParameter("att", m.attachments()).setParameter("at", m.at() == null ? Instant.now() : m.at())
                .executeUpdate());
        return saved > 0;
    }

    void purge(int days) {
        if (days <= 0) return;
        tx.executeWithoutResult(st -> em.createNativeQuery("DELETE FROM mail_messages WHERE created_at < now() - (:d * interval '1 day')")
                .setParameter("d", days).executeUpdate());
    }

    // ------------------------------------------------------------------ lists

    @SuppressWarnings("unchecked")
    public List<ThreadRow> inbox(String search, Instant before, int limit) {
        int size = Math.max(1, Math.min(limit, 50));
        StringBuilder sql = new StringBuilder(
                "SELECT CAST(t.thread_id AS text), t.subject, t.counterparty_name, t.counterparty_email, LEFT(t.body_text, 160), t.created_at, t.direction, "
                        + "(SELECT COUNT(*) FROM mail_messages u WHERE u.thread_id = t.thread_id AND u.direction = 'IN' AND u.is_read = FALSE), "
                        + "(SELECT COUNT(*) FROM mail_messages c WHERE c.thread_id = t.thread_id) "
                        + "FROM (SELECT DISTINCT ON (m.thread_id) m.* FROM mail_messages m "
                        + "WHERE EXISTS (SELECT 1 FROM mail_messages i WHERE i.thread_id = m.thread_id AND i.direction = 'IN') "
                        + "ORDER BY m.thread_id, m.created_at DESC) t WHERE 1 = 1 ");
        Map<String, Object> params = new LinkedHashMap<>();
        if (before != null) {
            sql.append("AND t.created_at < :before ");
            params.put("before", before);
        }
        String needle = like(search);
        if (needle != null) {
            sql.append("AND EXISTS (SELECT 1 FROM mail_messages s WHERE s.thread_id = t.thread_id AND (LOWER(s.subject) LIKE :q "
                    + "OR LOWER(s.counterparty_email) LIKE :q OR LOWER(s.counterparty_name) LIKE :q OR LOWER(s.body_text) LIKE :q)) ");
            params.put("q", needle);
        }
        sql.append("ORDER BY t.created_at DESC LIMIT :lim");
        params.put("lim", size);
        var q = em.createNativeQuery(sql.toString());
        params.forEach(q::setParameter);
        List<ThreadRow> out = new ArrayList<>();
        for (Object[] r : (List<Object[]>) q.getResultList()) {
            out.add(new ThreadRow((String) r[0], (String) r[1], (String) r[2], (String) r[3], oneLine((String) r[4]), instant(r[5]),
                    ((Number) r[7]).longValue(), ((Number) r[8]).longValue(), "OUT".equals(r[6])));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public List<SentRow> sent(String search, Instant before, int limit) {
        int size = Math.max(1, Math.min(limit, 50));
        StringBuilder sql = new StringBuilder(
                "SELECT CAST(m.id AS text), CAST(m.thread_id AS text), m.counterparty_email, m.counterparty_name, m.subject, LEFT(m.body_text, 160), "
                        + "m.created_at, m.status, m.sent_by_name FROM mail_messages m WHERE m.direction = 'OUT' ");
        Map<String, Object> params = new LinkedHashMap<>();
        if (before != null) {
            sql.append("AND m.created_at < :before ");
            params.put("before", before);
        }
        String needle = like(search);
        if (needle != null) {
            sql.append("AND (LOWER(m.subject) LIKE :q OR LOWER(m.counterparty_email) LIKE :q OR LOWER(m.counterparty_name) LIKE :q "
                    + "OR LOWER(m.body_text) LIKE :q OR LOWER(m.sent_by_name) LIKE :q) ");
            params.put("q", needle);
        }
        sql.append("ORDER BY m.created_at DESC LIMIT :lim");
        params.put("lim", size);
        var q = em.createNativeQuery(sql.toString());
        params.forEach(q::setParameter);
        List<SentRow> out = new ArrayList<>();
        for (Object[] r : (List<Object[]>) q.getResultList()) {
            out.add(new SentRow((String) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4], oneLine((String) r[5]),
                    instant(r[6]), (String) r[7], (String) r[8]));
        }
        return out;
    }

    // ------------------------------------------------------------------ one conversation

    @SuppressWarnings("unchecked")
    public Conversation thread(UUID threadId) {
        refreshQueued(threadId);
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT CAST(id AS text), direction, counterparty_name, counterparty_email, subject, body_text, attachments, created_at, "
                                + "sent_by_name, status, error FROM mail_messages WHERE thread_id = :t ORDER BY created_at ASC, direction DESC LIMIT 200")
                .setParameter("t", threadId).getResultList();
        if (rows.isEmpty()) throw new IllegalArgumentException("That conversation was not found.");
        List<Msg> msgs = new ArrayList<>();
        for (Object[] r : rows) {
            msgs.add(new Msg((String) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4], (String) r[5],
                    ((Number) r[6]).intValue(), instant(r[7]), (String) r[8], (String) r[9], (String) r[10]));
        }
        Msg last = msgs.get(msgs.size() - 1);
        String name = "";
        for (Msg m : msgs) if (m.name() != null && !m.name().isBlank()) name = m.name();
        return new Conversation(threadId.toString(), last.subject(), name, last.email(), msgs);
    }

    public void markRead(UUID threadId) {
        tx.executeWithoutResult(st -> em.createNativeQuery(
                        "UPDATE mail_messages SET is_read = TRUE WHERE thread_id = :t AND direction = 'IN' AND is_read = FALSE")
                .setParameter("t", threadId).executeUpdate());
    }

    /** Looks at the delivery state of recent messages that were only queued, so the screen can show Sent or Failed. */
    @SuppressWarnings("unchecked")
    private void refreshQueued(UUID threadId) {
        if (!smtp.configured()) return;
        try {
            List<Object[]> rows = em.createNativeQuery(
                            "SELECT CAST(id AS text), provider_message_id FROM mail_messages WHERE thread_id = :t AND direction = 'OUT' "
                                    + "AND status = 'QUEUED' AND provider_message_id IS NOT NULL ORDER BY created_at DESC LIMIT 5")
                    .setParameter("t", threadId).getResultList();
            for (Object[] r : rows) {
                QueenSmtpClient.Status s = smtp.status((String) r[1]);
                if (s == null) continue;
                String status = "sent".equals(s.status()) ? "SENT" : ("failed".equals(s.status()) ? "FAILED" : null);
                if (status == null) continue;
                String err = cut(s.error() == null ? "" : s.error(), 280);
                UUID id = UUID.fromString((String) r[0]);
                tx.executeWithoutResult(st -> em.createNativeQuery("UPDATE mail_messages SET status = :s, error = :e WHERE id = :i")
                        .setParameter("s", status).setParameter("e", err).setParameter("i", id).executeUpdate());
            }
        } catch (RuntimeException e) {
            log.warn("Mailbox: could not refresh delivery states: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ sending

    @SuppressWarnings("unchecked")
    public Msg reply(User me, UUID threadId, String body) {
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT counterparty_email, counterparty_name, subject, rfc_message_id FROM mail_messages WHERE thread_id = :t "
                                + "ORDER BY created_at DESC LIMIT 20")
                .setParameter("t", threadId).getResultList();
        if (rows.isEmpty()) throw new IllegalArgumentException("That conversation was not found.");
        String email = (String) rows.get(0)[0];
        String name = "";
        String subject = (String) rows.get(0)[2];
        String inReplyTo = null;
        for (Object[] r : rows) {
            if (name.isEmpty() && r[1] != null && !((String) r[1]).isBlank()) name = (String) r[1];
            if (inReplyTo == null && r[3] != null) inReplyTo = (String) r[3];
        }
        return deliver(me, threadId, email, name, "Re: " + normSubject(subject), body, inReplyTo, "EMAIL_REPLY", "Replied to a customer email");
    }

    public Msg compose(User me, String to, String subject, String body) {
        String email = to == null ? "" : to.trim();
        if (!email.matches("^[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$") || email.length() > 254) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        String subj = subject == null ? "" : subject.trim().replaceAll("[\\r\\n]+", " ");
        if (subj.isEmpty()) throw new IllegalArgumentException("Enter a subject.");
        if (subj.length() > 200) throw new IllegalArgumentException("The subject is too long (200 characters at most).");
        if (email.equalsIgnoreCase(supportEmail) || email.equalsIgnoreCase(systemEmail)) {
            throw new IllegalArgumentException("Choose a customer's address, not our own.");
        }
        return deliver(me, threadOf(email, normSubject(subj)), email, "", subj, body, null, "EMAIL_SENT", "Sent an email to a customer");
    }

    private Msg deliver(User me, UUID threadId, String toEmail, String toName, String subject, String body, String inReplyTo,
                        String action, String summary) {
        String text = body == null ? "" : body.replace("\r\n", "\n").trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Write your message first.");
        if (text.length() > MAX_BODY) throw new IllegalArgumentException("The message is too long (" + MAX_BODY + " characters at most).");
        String first = me.getFirstName() == null || me.getFirstName().isBlank() ? "" : me.getFirstName().trim();
        String full = text + "\n\n" + (first.isEmpty() ? "" : first + "\n") + SIGNATURE_NAME;
        String html = "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:15px;line-height:1.55;color:#1F2937;\">"
                + escape(full).replace("\n", "<br>") + "</div>";
        QueenSmtpClient.Sent sent = smtp.send(supportEmail, SIGNATURE_NAME, supportEmail, toEmail, subject, html, full, inReplyTo);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String status = "sent".equals(sent.status()) ? "SENT" : "QUEUED";
        try {
            tx.executeWithoutResult(st -> em.createNativeQuery(
                            "INSERT INTO mail_messages (id, thread_id, direction, counterparty_email, counterparty_name, subject, body_text, created_at, "
                                    + "is_read, sent_by_id, sent_by_name, provider_message_id, status) "
                                    + "VALUES (:id, :t, 'OUT', :em, :nm, :sub, :body, :at, TRUE, :by, :byn, :pid, :st)")
                    .setParameter("id", id).setParameter("t", threadId).setParameter("em", toEmail).setParameter("nm", cut(toName, 200))
                    .setParameter("sub", cut(subject, 300)).setParameter("body", text).setParameter("at", now)
                    .setParameter("by", me.getId()).setParameter("byn", cut(me.getFullName() == null ? "" : me.getFullName(), 200))
                    .setParameter("pid", cut(sent.id() == null ? "" : sent.id(), 80)).setParameter("st", status).executeUpdate());
        } catch (RuntimeException e) {
            // the email already left; a saving problem must not look like a failed send
            log.error("Mailbox: email to {} was sent but could not be saved: {}", toEmail, e.toString());
        }
        // a reply means the customer's messages were dealt with
        markRead(threadId);
        audit.record(me, "MESSAGING", action, summary, null, toName == null || toName.isBlank() ? toEmail : toName, cut(subject, 200), null);
        return new Msg(id.toString(), "OUT", toName, toEmail, subject, text, 0, now, me.getFullName(), status, "");
    }

    // ------------------------------------------------------------------ helpers

    static String normSubject(String raw) {
        String s = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
        String prev;
        do {
            prev = s;
            s = s.replaceFirst("(?i)^(re|fw|fwd|aw|sv)\\s*:\\s*", "").trim();
        } while (!s.equals(prev));
        if (s.isEmpty()) s = "(no subject)";
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    static UUID threadOf(String email, String normalizedSubject) {
        String key = email.trim().toLowerCase() + "|" + normalizedSubject.toLowerCase();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String like(String search) {
        String s = search == null ? "" : search.trim().toLowerCase();
        if (s.isEmpty()) return null;
        return "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static Instant instant(Object o) {
        if (o instanceof Instant i) return i;
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (o instanceof java.util.Date dt) return dt.toInstant();
        return null;
    }
}
