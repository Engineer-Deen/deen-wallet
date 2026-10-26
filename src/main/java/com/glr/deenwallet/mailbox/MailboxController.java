package com.glr.deenwallet.mailbox;

import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Support mailbox for admins (one shared inbox). /api/admin/** is already limited to ADMIN / SUPER_ADMIN;
 * the role is checked again here. Rule violations answer 400/409, never 401/403 (the admin app treats those as "session expired").
 */
@RestController
@RequestMapping("/api/admin/mail")
public class MailboxController {

    public record ReplyRequest(String body) { }

    public record SendRequest(String to, String subject, String body) { }

    private final MailboxService mail;
    private final MailboxImap imap;
    private final UserRepository users;

    public MailboxController(MailboxService mail, MailboxImap imap, UserRepository users) {
        this.mail = mail;
        this.imap = imap;
        this.users = users;
    }

    @GetMapping("/summary")
    public ResponseEntity<MailboxService.Summary> summary() {
        admin();
        return ResponseEntity.ok(mail.summary());
    }

    /** Checks the inbox right now (the Refresh button). */
    @PostMapping("/refresh")
    public ResponseEntity<MailboxService.Summary> refresh() {
        admin();
        imap.poll();
        return ResponseEntity.ok(mail.summary());
    }

    @GetMapping("/inbox")
    public ResponseEntity<List<MailboxService.ThreadRow>> inbox(@RequestParam(required = false) String q,
                                                                @RequestParam(required = false) String before,
                                                                @RequestParam(required = false, defaultValue = "30") int limit) {
        admin();
        return ResponseEntity.ok(mail.inbox(q, cursor(before), limit));
    }

    @GetMapping("/sent")
    public ResponseEntity<List<MailboxService.SentRow>> sent(@RequestParam(required = false) String q,
                                                             @RequestParam(required = false) String before,
                                                             @RequestParam(required = false, defaultValue = "30") int limit) {
        admin();
        return ResponseEntity.ok(mail.sent(q, cursor(before), limit));
    }

    @GetMapping("/threads/{id}")
    public ResponseEntity<MailboxService.Conversation> thread(@PathVariable UUID id) {
        admin();
        return ResponseEntity.ok(mail.thread(id));
    }

    @PostMapping("/threads/{id}/read")
    public ResponseEntity<MailboxService.Summary> read(@PathVariable UUID id) {
        admin();
        mail.markRead(id);
        return ResponseEntity.ok(mail.summary());
    }

    @PostMapping("/threads/{id}/reply")
    public ResponseEntity<MailboxService.Msg> reply(@PathVariable UUID id, @RequestBody ReplyRequest r) {
        return ResponseEntity.ok(mail.reply(admin(), id, r.body()));
    }

    @PostMapping("/send")
    public ResponseEntity<MailboxService.Msg> send(@RequestBody SendRequest r) {
        return ResponseEntity.ok(mail.compose(admin(), r.to(), r.subject(), r.body()));
    }

    private static Instant cursor(String before) {
        if (before == null || before.isBlank()) return null;
        try {
            return Instant.parse(before.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date.");
        }
    }

    private User admin() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated()) throw new SecurityException("Not authenticated");
        User u = users.findById(UUID.fromString((String) a.getPrincipal()))
                .orElseThrow(() -> new SecurityException("User not found"));
        if (!"ADMIN".equals(u.getRole()) && !"SUPER_ADMIN".equals(u.getRole()))
            throw new SecurityException("Access denied: admin only");
        if (!u.isActive()) throw new SecurityException("Admin account is inactive.");
        return u;
    }
}
