package com.glr.deenwallet.kyc;

import com.glr.deenwallet.email.EmailService;
import com.glr.deenwallet.notification.AdminAlertService;
import com.glr.deenwallet.notification.NotificationService;
import com.glr.deenwallet.notification.PushNotificationService;
import com.glr.deenwallet.transaction.TooManyRequestsException;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Identity verification: the customer's draft / photos / submission, and the admin's review.
 * Every change by one customer first takes a lock on that customer's user row, so a double tap or two
 * devices can never create two open submissions or race each other.
 */
@Service
public class KycService {

    private static final Logger log = LoggerFactory.getLogger(KycService.class);

    static final int MAX_IMAGE_BYTES = 700_000;
    static final int MIN_IMAGE_BYTES = 3_000;
    private static final int MAX_SUBMISSIONS_PER_DAY = 3;
    private static final Pattern NAME = Pattern.compile("^[\\p{L}][\\p{L} .'’-]{2,99}$");
    private static final Pattern NUMBER = Pattern.compile("^[A-Z0-9/]{5,24}$");

    private final KycSubmissionRepository submissions;
    private final KycDocumentRepository documents;
    private final KycAuditRepository audit;
    private final KycCrypto crypto;
    private final KycLimitService limits;
    private final UserRepository users;
    private final NotificationService notifications;
    private final PushNotificationService push;
    private final EmailService emails;
    private final AdminAlertService adminAlerts;

    public KycService(KycSubmissionRepository submissions, KycDocumentRepository documents,
                      KycAuditRepository audit, KycCrypto crypto, KycLimitService limits,
                      UserRepository users, NotificationService notifications,
                      PushNotificationService push, EmailService emails, AdminAlertService adminAlerts) {
        this.submissions = submissions;
        this.documents = documents;
        this.audit = audit;
        this.crypto = crypto;
        this.limits = limits;
        this.users = users;
        this.notifications = notifications;
        this.push = push;
        this.emails = emails;
        this.adminAlerts = adminAlerts;
    }

    // =====================================================================
    // CUSTOMER
    // =====================================================================

    @Transactional(readOnly = true)
    public KycDtos.StatusResponse status(UUID userId) {
        return buildStatus(userId);
    }

    @Transactional
    public KycDtos.StatusResponse saveDraft(UUID userId, KycDtos.DraftRequest req) {
        requireAvailable();
        lockEligibleUser(userId);
        if (submissions.existsByUserIdAndStatus(userId, KycStatus.APPROVED)) {
            throw new IllegalStateException("Your identity is already verified.");
        }
        Details d = validate(req);

        KycSubmission s = submissions.findOpenByUserIdForUpdate(userId).orElse(null);
        if (s != null && s.getStatus() == KycStatus.PENDING) {
            throw new IllegalStateException("Your documents are already being reviewed.");
        }
        if (s == null) {
            long recent = submissions.countByUserIdAndSubmittedAtAfter(userId, Instant.now().minus(Duration.ofHours(24)));
            if (recent >= MAX_SUBMISSIONS_PER_DAY) {
                throw new TooManyRequestsException(
                        "You have sent several verification requests today. Please try again tomorrow or contact support.");
            }
            s = submissions.save(KycSubmission.draftFor(userId));
        }

        String hash = crypto.hash(d.type().name() + "|" + d.number());
        if (submissions.existsByDocumentNumberHashAndStatusAndUserIdNot(hash, KycStatus.APPROVED, userId)) {
            throw new IllegalArgumentException(
                    "This document is already linked to another DeenWallet Transfer account. Contact support if you think this is a mistake.");
        }

        KycDocumentType previousType = s.getDocumentType();
        s.setDocumentType(d.type());
        s.setFullNameOnDocument(d.fullName());
        s.setDocumentNumberEnc(crypto.encryptText(d.number(), s.getId() + ":NUMBER"));
        s.setDocumentNumberHash(hash);
        s.setDocumentLast4(d.number().substring(d.number().length() - 4));
        s.setDateOfBirth(d.dateOfBirth());
        s.setDocumentExpiryDate(d.expiry());
        submissions.save(s);

        if (previousType != null && previousType != d.type() && !d.type().needsBackPhoto()) {
            documents.deleteOne(s.getId(), KycDocumentKind.BACK);
        }
        return buildStatus(userId);
    }

    @Transactional
    public KycDtos.UploadResponse putDocument(UUID userId, String kindText, KycDtos.DocumentUpload upload) {
        requireAvailable();
        lockEligibleUser(userId);
        KycDocumentKind kind = parseKind(kindText);

        KycSubmission s = submissions.findOpenByUserIdForUpdate(userId).orElse(null);
        if (s != null && s.getStatus() == KycStatus.PENDING) {
            throw new IllegalStateException("Your documents are already being reviewed.");
        }
        if (s == null || s.getDocumentType() == null) {
            throw new IllegalStateException("Please save your document details first.");
        }
        if (kind == KycDocumentKind.BACK && !s.getDocumentType().needsBackPhoto()) {
            throw new IllegalArgumentException("A " + s.getDocumentType().label().toLowerCase(Locale.ROOT) + " does not need a back photo.");
        }

        byte[] image = decodeImage(upload);
        byte[] sealed = crypto.encrypt(image, s.getId() + ":" + kind.name());
        documents.deleteOne(s.getId(), kind);
        documents.save(new KycDocument(s.getId(), kind, contentTypeOf(image), image.length, sealed));

        List<KycDocumentKind> have = documents.kindsFor(s.getId());
        // the row we just saved is not visible to the query until flush; make sure it is counted
        Set<KycDocumentKind> haveSet = new HashSet<>(have);
        haveSet.add(kind);
        return new KycDtos.UploadResponse(kind.name(), names(haveSet), names(requiredKinds(s.getDocumentType())),
                complete(s, haveSet));
    }

    @Transactional
    public KycDtos.StatusResponse submit(UUID userId) {
        requireAvailable();
        lockEligibleUser(userId);
        KycSubmission s = submissions.findOpenByUserIdForUpdate(userId).orElse(null);
        if (s == null) throw new IllegalStateException("Start your verification first.");
        if (s.getStatus() == KycStatus.PENDING) {
            return buildStatus(userId); // double tap: already submitted
        }
        Set<KycDocumentKind> have = new HashSet<>(documents.kindsFor(s.getId()));
        if (!complete(s, have)) {
            throw new IllegalArgumentException("Please finish your details and upload all the photos first.");
        }
        if (submissions.existsByDocumentNumberHashAndStatusAndUserIdNot(s.getDocumentNumberHash(), KycStatus.APPROVED, userId)) {
            throw new IllegalArgumentException(
                    "This document is already linked to another DeenWallet Transfer account. Contact support if you think this is a mistake.");
        }
        s.setStatus(KycStatus.PENDING);
        s.setSubmittedAt(Instant.now());
        s.setRejectionReason(null);
        submissions.save(s);
        audit.save(new KycAudit(s.getId(), userId, "SUBMITTED", userId, null,
                s.getDocumentType().name() + " ending " + s.getDocumentLast4()));
        // tell every admin there is a new submission to review (after the save is final; a problem here never blocks the customer)
        runAfterCommit(() -> {
            User submitter = users.findById(userId).orElse(null);
            String who = submitter == null || submitter.getFullName() == null || submitter.getFullName().isBlank() ? "A customer" : submitter.getFullName().trim();
            adminAlerts.notifyAdmins(null, "KYC_SUBMITTED", "New KYC submission", who + " submitted ID documents for verification. Open KYC Verification to review.");
        });
        return buildStatus(userId);
    }

    @Transactional
    public KycDtos.StatusResponse discardDraft(UUID userId) {
        lockEligibleUser(userId);
        KycSubmission s = submissions.findOpenByUserIdForUpdate(userId).orElse(null);
        if (s != null && s.getStatus() == KycStatus.DRAFT) {
            documents.deleteAllFor(s.getId());
            submissions.delete(s);
            submissions.flush();
        }
        return buildStatus(userId);
    }

    // =====================================================================
    // ADMIN
    // =====================================================================

    @Transactional(readOnly = true)
    public KycDtos.AdminSummary summary() {
        return new KycDtos.AdminSummary(
                submissions.countByStatus(KycStatus.PENDING),
                submissions.countByStatus(KycStatus.APPROVED),
                submissions.countByStatus(KycStatus.REJECTED) + submissions.countByStatus(KycStatus.REVOKED));
    }

    @Transactional(readOnly = true)
    public List<KycDtos.AdminCard> list(String filter, String query) {
        String f = filter == null ? "PENDING" : filter.trim().toUpperCase(Locale.ROOT);
        PageRequest page = PageRequest.of(0, 300);
        List<KycSubmission> rows;
        switch (f) {
            case "APPROVED" -> rows = submissions.findByStatusInOrderByUpdatedAtDesc(List.of(KycStatus.APPROVED), page);
            case "REJECTED" -> rows = submissions.findByStatusInOrderByUpdatedAtDesc(List.of(KycStatus.REJECTED, KycStatus.REVOKED), page);
            case "ALL" -> rows = submissions.findByStatusInOrderByUpdatedAtDesc(
                    List.of(KycStatus.PENDING, KycStatus.APPROVED, KycStatus.REJECTED, KycStatus.REVOKED), page);
            default -> rows = submissions.findByStatusOrderBySubmittedAtAsc(KycStatus.PENDING, page);
        }
        Map<UUID, User> byId = new HashMap<>();
        users.findAllById(rows.stream().map(KycSubmission::getUserId).distinct().toList())
                .forEach(u -> byId.put(u.getId(), u));

        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<KycDtos.AdminCard> out = new ArrayList<>();
        for (KycSubmission s : rows) {
            User u = byId.get(s.getUserId());
            if (!needle.isEmpty() && !matches(needle, s, u)) continue;
            out.add(new KycDtos.AdminCard(
                    s.getId().toString(), s.getUserId().toString(),
                    u == null ? "Unknown customer" : u.getFullName(),
                    u == null ? null : u.getEmail(), u == null ? null : u.getPhone(),
                    s.getDocumentType() == null ? null : s.getDocumentType().name(),
                    s.getDocumentType() == null ? null : s.getDocumentType().label(),
                    s.getStatus().name(), s.getSubmittedAt(), s.getReviewedAt(),
                    u == null ? "DIFFERENT" : nameMatch(u.getFullName(), s.getFullNameOnDocument())));
        }
        return out;
    }

    @Transactional
    public KycDtos.AdminDetail detail(UUID id, User admin) {
        KycSubmission s = requireReviewable(submissions.findById(id).orElse(null));
        User u = users.findById(s.getUserId()).orElse(null);
        audit.save(new KycAudit(s.getId(), s.getUserId(), "DETAILS_VIEWED", admin.getId(), admin.getEmail(), null));
        return buildDetail(s, u);
    }

    @Transactional
    public KycDtos.AdminPhoto photo(UUID id, String kindText, User admin) {
        requireAvailable();
        KycSubmission s = requireReviewable(submissions.findById(id).orElse(null));
        KycDocumentKind kind = parseKind(kindText);
        KycDocument doc = documents.findBySubmissionIdAndKind(s.getId(), kind)
                .orElseThrow(() -> new IllegalArgumentException("That photo is not available."));
        byte[] plain = crypto.decrypt(doc.getData(), s.getId() + ":" + kind.name());
        audit.save(new KycAudit(s.getId(), s.getUserId(), "VIEWED_PHOTO", admin.getId(), admin.getEmail(), kind.name()));
        return new KycDtos.AdminPhoto(kind.name(), doc.getContentType(), Base64.getEncoder().encodeToString(plain));
    }

    @Transactional
    public KycDtos.AdminDetail approve(UUID id, User admin) {
        requireAvailable();
        KycSubmission s = requireReviewable(submissions.findByIdForUpdate(id).orElse(null));
        if (s.getStatus() != KycStatus.PENDING) {
            throw new IllegalStateException("This verification was already handled (status: " + s.getStatus() + ").");
        }
        LocalDate today = LocalDate.now(KycLimitService.ZONE);
        if (s.getDocumentExpiryDate() != null && !s.getDocumentExpiryDate().isAfter(today)) {
            throw new IllegalArgumentException("This document has expired. Reject it and ask the customer for a valid one.");
        }
        if (s.getDateOfBirth() == null || s.getDateOfBirth().isAfter(today.minusYears(18))) {
            throw new IllegalArgumentException("The customer is under 18. Reject this verification.");
        }
        if (!complete(s, new HashSet<>(documents.kindsFor(s.getId())))) {
            throw new IllegalStateException("Some photos are missing. Reject it so the customer can send them again.");
        }
        if (submissions.existsByDocumentNumberHashAndStatusAndUserIdNot(s.getDocumentNumberHash(), KycStatus.APPROVED, s.getUserId())) {
            throw new IllegalStateException("This document is already verified on another account.");
        }
        s.setStatus(KycStatus.APPROVED);
        s.setReviewedAt(Instant.now());
        s.setReviewedBy(admin.getId());
        s.setReviewedByEmail(admin.getEmail());
        s.setRejectionReason(null);
        try {
            submissions.saveAndFlush(s);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("This customer or document is already verified.");
        }
        audit.save(new KycAudit(s.getId(), s.getUserId(), "APPROVED", admin.getId(), admin.getEmail(), null));
        log.info("Admin {} approved KYC {} for user {}", admin.getEmail(), s.getId(), s.getUserId());

        String msg = "Your identity is verified. Your daily limit is now SLE "
                + KycLimitService.sle(limits.verifiedDailyLimitMinor()) + " for mobile money and bank transfers.";
        notifyUser(s.getUserId(), "KYC_APPROVED", "Identity verified", msg);
        emailUser(s.getUserId(), "APPROVED", null);
        return buildDetail(s, users.findById(s.getUserId()).orElse(null));
    }

    @Transactional
    public KycDtos.AdminDetail reject(UUID id, String reason, User admin) {
        String clean = requireReason(reason, "Tell the customer why it was not approved (at least 5 characters).");
        KycSubmission s = requireReviewable(submissions.findByIdForUpdate(id).orElse(null));
        if (s.getStatus() != KycStatus.PENDING) {
            throw new IllegalStateException("This verification was already handled (status: " + s.getStatus() + ").");
        }
        s.setStatus(KycStatus.REJECTED);
        s.setReviewedAt(Instant.now());
        s.setReviewedBy(admin.getId());
        s.setReviewedByEmail(admin.getEmail());
        s.setRejectionReason(clean);
        submissions.save(s);
        // A rejected identity document is not kept: the customer sends new photos if they try again.
        documents.deleteAllFor(s.getId());
        audit.save(new KycAudit(s.getId(), s.getUserId(), "REJECTED", admin.getId(), admin.getEmail(), clean));
        log.info("Admin {} rejected KYC {} for user {}", admin.getEmail(), s.getId(), s.getUserId());

        notifyUser(s.getUserId(), "KYC_REJECTED", "Verification not approved",
                "We could not verify your identity: " + clean + " You can send new photos from \"Verify your KYC\".");
        emailUser(s.getUserId(), "REJECTED", clean);
        return buildDetail(s, users.findById(s.getUserId()).orElse(null));
    }

    @Transactional
    public KycDtos.AdminDetail revoke(UUID id, String reason, User admin) {
        String clean = requireReason(reason, "Write why the verification is being withdrawn (at least 5 characters).");
        KycSubmission s = requireReviewable(submissions.findByIdForUpdate(id).orElse(null));
        if (s.getStatus() != KycStatus.APPROVED) {
            throw new IllegalStateException("Only a verified customer can be withdrawn (status: " + s.getStatus() + ").");
        }
        s.setStatus(KycStatus.REVOKED);
        s.setReviewedAt(Instant.now());
        s.setReviewedBy(admin.getId());
        s.setReviewedByEmail(admin.getEmail());
        s.setRejectionReason(clean);
        submissions.save(s);
        audit.save(new KycAudit(s.getId(), s.getUserId(), "REVOKED", admin.getId(), admin.getEmail(), clean));
        log.info("Admin {} revoked KYC {} for user {}", admin.getEmail(), s.getId(), s.getUserId());

        notifyUser(s.getUserId(), "KYC_REVOKED", "Verification withdrawn",
                "Your identity verification was withdrawn, so the standard daily limits apply again. Contact support for help.");
        emailUser(s.getUserId(), "REVOKED", clean);
        return buildDetail(s, users.findById(s.getUserId()).orElse(null));
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private record Details(KycDocumentType type, String fullName, String number, LocalDate dateOfBirth, LocalDate expiry) { }

    private void requireAvailable() {
        if (!crypto.isConfigured()) {
            throw new IllegalStateException("Identity verification is temporarily unavailable. Please try again later.");
        }
    }

    /** Locks the customer's row (serialises their KYC changes) and checks the account may use the app. */
    private User lockEligibleUser(UUID userId) {
        User u = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (!u.isActive()) throw new IllegalStateException("Account is inactive. Please contact support.");
        if (u.isLocked()) throw new IllegalStateException("Account is locked. Please contact support.");
        if (!u.isEmailVerified()) throw new IllegalStateException("Please verify your email first.");
        return u;
    }

    private KycSubmission requireReviewable(KycSubmission s) {
        if (s == null || s.getStatus() == KycStatus.DRAFT) {
            throw new IllegalArgumentException("Verification request not found.");
        }
        return s;
    }

    private static String requireReason(String reason, String message) {
        String clean = reason == null ? "" : reason.trim().replaceAll("\\s+", " ");
        if (clean.length() < 5) throw new IllegalArgumentException(message);
        return clean.length() > 300 ? clean.substring(0, 300) : clean;
    }

    private Details validate(KycDtos.DraftRequest r) {
        if (r == null) throw new IllegalArgumentException("Please fill in your document details.");
        KycDocumentType type;
        try {
            type = KycDocumentType.valueOf(r.documentType() == null ? "" : r.documentType().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Please choose the type of document.");
        }
        String name = r.fullNameOnDocument() == null ? "" : r.fullNameOnDocument().trim().replaceAll("\\s+", " ");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Enter your full name exactly as it appears on the document (letters only).");
        }
        String number = r.documentNumber() == null ? "" : r.documentNumber().toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        if (!NUMBER.matcher(number).matches()) {
            throw new IllegalArgumentException("The document number looks wrong. Enter it exactly as printed (letters and numbers only).");
        }
        LocalDate today = LocalDate.now(KycLimitService.ZONE);
        LocalDate dob = parseDate(r.dateOfBirth(), "date of birth");
        if (dob == null) throw new IllegalArgumentException("Enter your date of birth.");
        if (dob.isAfter(today.minusYears(18))) {
            throw new IllegalArgumentException("You must be at least 18 years old to verify your identity.");
        }
        if (dob.isBefore(today.minusYears(110))) {
            throw new IllegalArgumentException("The date of birth looks wrong. Please check it.");
        }
        LocalDate expiry = parseDate(r.documentExpiryDate(), "expiry date");
        if (expiry == null && type.needsExpiryDate()) {
            throw new IllegalArgumentException("Enter the expiry date shown on your " + type.label().toLowerCase(Locale.ROOT) + ".");
        }
        if (expiry != null) {
            if (!expiry.isAfter(today)) {
                throw new IllegalArgumentException("This document has expired. Please use a valid document.");
            }
            if (expiry.isAfter(today.plusYears(25))) {
                throw new IllegalArgumentException("The expiry date looks wrong. Please check it.");
            }
        }
        return new Details(type, name, number, dob, expiry);
    }

    private static LocalDate parseDate(String text, String what) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("The " + what + " is not a valid date.");
        }
    }

    private static KycDocumentKind parseKind(String text) {
        try {
            return KycDocumentKind.valueOf(text == null ? "" : text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown photo type.");
        }
    }

    /** Accepts base64 (optionally a data: URL), checks size and that it really is a JPEG / PNG / WebP. */
    private static byte[] decodeImage(KycDtos.DocumentUpload upload) {
        if (upload == null || upload.data() == null || upload.data().isBlank()) {
            throw new IllegalArgumentException("No photo received. Please try again.");
        }
        String data = upload.data().trim();
        int comma = data.indexOf(',');
        if (data.startsWith("data:") && comma > 0) data = data.substring(comma + 1);
        if (data.length() > MAX_IMAGE_BYTES * 4L / 3 + 16) {
            throw new IllegalArgumentException("The photo is too large. Please take it again.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The photo could not be read. Please try again.");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("The photo is too large. Please take it again.");
        }
        if (bytes.length < MIN_IMAGE_BYTES || contentTypeOf(bytes) == null) {
            throw new IllegalArgumentException("That file is not a usable photo. Please use a clear JPEG or PNG picture.");
        }
        return bytes;
    }

    /** From the first bytes, not from what the client claims. Null if it is not an accepted image. */
    static String contentTypeOf(byte[] b) {
        if (b == null || b.length < 12) return null;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static Set<KycDocumentKind> requiredKinds(KycDocumentType type) {
        Set<KycDocumentKind> set = EnumSet.of(KycDocumentKind.FRONT, KycDocumentKind.SELFIE);
        if (type != null && type.needsBackPhoto()) set.add(KycDocumentKind.BACK);
        return set;
    }

    private static boolean complete(KycSubmission s, Set<KycDocumentKind> have) {
        if (s.getDocumentType() == null || s.getFullNameOnDocument() == null || s.getDocumentNumberEnc() == null
                || s.getDateOfBirth() == null) {
            return false;
        }
        if (s.getDocumentType().needsExpiryDate() && s.getDocumentExpiryDate() == null) return false;
        return have.containsAll(requiredKinds(s.getDocumentType()));
    }

    private static List<String> names(Set<KycDocumentKind> kinds) {
        List<String> out = new ArrayList<>();
        for (KycDocumentKind k : KycDocumentKind.values()) {
            if (kinds.contains(k)) out.add(k.name());
        }
        return out;
    }

    private KycDtos.StatusResponse buildStatus(UUID userId) {
        KycSubmission open = submissions.findOpenByUserId(userId).orElse(null);
        KycSubmission approved = open != null ? null : submissions.findByUserIdAndStatus(userId, KycStatus.APPROVED).orElse(null);
        KycSubmission shown = open != null ? open : approved;
        if (shown == null) {
            List<KycSubmission> latest = submissions.findTop3ByUserIdOrderByCreatedAtDesc(userId);
            if (!latest.isEmpty()) shown = latest.get(0);
        }

        KycLimitService.Snapshot snap = limits.snapshot(userId);
        String status = shown == null ? "NONE" : shown.getStatus().name();

        Set<KycDocumentKind> have = new HashSet<>();
        if (open != null) have.addAll(documents.kindsFor(open.getId()));
        KycDocumentType type = shown == null ? null : shown.getDocumentType();

        KycDtos.DraftDetails draft = null;
        if (open != null && open.getStatus() == KycStatus.DRAFT && open.getDocumentType() != null
                && open.getDocumentNumberEnc() != null && crypto.isConfigured()) {
            draft = new KycDtos.DraftDetails(open.getDocumentType().name(), open.getFullNameOnDocument(),
                    crypto.decryptText(open.getDocumentNumberEnc(), open.getId() + ":NUMBER"),
                    open.getDateOfBirth() == null ? null : open.getDateOfBirth().toString(),
                    open.getDocumentExpiryDate() == null ? null : open.getDocumentExpiryDate().toString());
        }

        boolean canStart = crypto.isConfigured() && !"APPROVED".equals(status) && !"PENDING".equals(status);
        boolean canSubmit = open != null && open.getStatus() == KycStatus.DRAFT && complete(open, have);
        boolean showReason = "REJECTED".equals(status) || "REVOKED".equals(status);

        List<KycDtos.DocumentOption> options = Arrays.stream(KycDocumentType.values())
                .map(t -> new KycDtos.DocumentOption(t.name(), t.label(), t.needsBackPhoto(), t.needsExpiryDate()))
                .toList();

        return new KycDtos.StatusResponse(
                crypto.isConfigured(),
                snap.verified() ? "VERIFIED" : "BASIC",
                status,
                showReason && shown != null ? shown.getRejectionReason() : null,
                shown == null ? null : shown.getSubmittedAt(),
                shown == null ? null : shown.getReviewedAt(),
                type == null ? null : type.name(),
                type == null ? null : type.label(),
                shown == null ? null : shown.getDocumentLast4(),
                draft,
                names(have),
                names(requiredKinds(type)),
                canStart, canSubmit,
                snap.mobileMoney(), snap.bank(),
                limits.verifiedDailyLimitMinor(),
                options);
    }

    private KycDtos.AdminDetail buildDetail(KycSubmission s, User u) {
        String number = null;
        if (s.getDocumentNumberEnc() != null && crypto.isConfigured()) {
            number = crypto.decryptText(s.getDocumentNumberEnc(), s.getId() + ":NUMBER");
        }
        long others = s.getDocumentNumberHash() == null ? 0
                : submissions.countByDocumentNumberHashAndUserIdNotAndStatusIn(s.getDocumentNumberHash(), s.getUserId(),
                List.of(KycStatus.PENDING, KycStatus.APPROVED));

        List<KycDtos.AdminHistoryItem> history = submissions.findTop10ByUserIdOrderByCreatedAtDesc(s.getUserId()).stream()
                .filter(h -> h.getStatus() != KycStatus.DRAFT && !h.getId().equals(s.getId()))
                .map(h -> new KycDtos.AdminHistoryItem(h.getId().toString(), h.getStatus().name(),
                        h.getSubmittedAt(), h.getReviewedAt(), h.getRejectionReason()))
                .toList();
        List<KycDtos.AdminAuditItem> trail = audit.findTop40ByUserIdOrderByCreatedAtDesc(s.getUserId()).stream()
                .map(a -> new KycDtos.AdminAuditItem(a.getAction(), a.getActorEmail(), a.getDetail(), a.getCreatedAt()))
                .toList();

        KycDtos.AdminCustomer customer = u == null ? null : new KycDtos.AdminCustomer(
                u.getId().toString(), u.getFullName(), u.getUsername(), u.getEmail(), u.getPhone(),
                u.getAccountNumber(), u.isEmailVerified(), u.isActive(), u.isLocked(), u.getCreatedAt());

        boolean pending = s.getStatus() == KycStatus.PENDING;
        return new KycDtos.AdminDetail(
                s.getId().toString(), s.getStatus().name(), s.getRejectionReason(),
                s.getSubmittedAt(), s.getReviewedAt(), s.getReviewedByEmail(),
                customer,
                new KycDtos.AdminDocument(
                        s.getDocumentType() == null ? null : s.getDocumentType().name(),
                        s.getDocumentType() == null ? null : s.getDocumentType().label(),
                        s.getFullNameOnDocument(), number,
                        s.getDateOfBirth() == null ? null : s.getDateOfBirth().toString(),
                        s.getDocumentExpiryDate() == null ? null : s.getDocumentExpiryDate().toString()),
                u == null ? "DIFFERENT" : nameMatch(u.getFullName(), s.getFullNameOnDocument()),
                others,
                names(new HashSet<>(documents.kindsFor(s.getId()))),
                history, trail,
                pending, pending, s.getStatus() == KycStatus.APPROVED,
                limits.verifiedDailyLimitMinor());
    }

    private static boolean matches(String needle, KycSubmission s, User u) {
        if (u != null) {
            if (contains(u.getFullName(), needle) || contains(u.getEmail(), needle)
                    || contains(u.getPhone(), needle) || contains(u.getUsername(), needle)
                    || contains(u.getAccountNumber(), needle)) {
                return true;
            }
        }
        return contains(s.getFullNameOnDocument(), needle) || contains(s.getDocumentLast4(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** MATCH when every word of one name is in the other, PARTIAL when some words are shared, else DIFFERENT. A hint only. */
    static String nameMatch(String accountName, String documentName) {
        Set<String> a = words(accountName);
        Set<String> b = words(documentName);
        if (a.isEmpty() || b.isEmpty()) return "DIFFERENT";
        Set<String> small = a.size() <= b.size() ? a : b;
        Set<String> large = small == a ? b : a;
        long shared = small.stream().filter(large::contains).count();
        if (shared == small.size()) return "MATCH";
        return shared > 0 ? "PARTIAL" : "DIFFERENT";
    }

    private static Set<String> words(String name) {
        Set<String> out = new HashSet<>();
        if (name == null) return out;
        for (String w : name.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
            if (w.length() > 1) out.add(w);
        }
        return out;
    }

    private void notifyUser(UUID userId, String type, String title, String message) {
        notifications.create(userId, type, title, message, null, null);
        runAfterCommit(() -> push.sendToUser(userId, title, message, Map.of("type", type)));
    }

    /** Emails the customer the result as soon as the decision is saved. A mail problem never undoes the decision. */
    private void emailUser(UUID userId, String outcome, String detail) {
        runAfterCommit(() -> {
            User u = users.findById(userId).orElse(null);
            if (u == null || u.getEmail() == null || u.getEmail().isBlank()) return;
            emails.sendKycDecisionEmail(u, outcome, detail, KycLimitService.sle(limits.verifiedDailyLimitMinor()));
        });
    }

    private void runAfterCommit(Runnable task) {
        Runnable safe = () -> {
            try {
                task.run();
            } catch (Exception e) {
                log.warn("KYC notification failed: {}", e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }
}
