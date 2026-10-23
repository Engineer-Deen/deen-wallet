package com.glr.deenwallet.kyc;

import java.time.Instant;
import java.util.List;

/** Request / response shapes of the KYC endpoints. Dates are sent as ISO strings (yyyy-MM-dd). */
public final class KycDtos {

    private KycDtos() { }

    // ---------------- customer ----------------

    /** One daily limit, in minor units (SLE x 100). */
    public record LimitView(long dailyLimitMinor, long usedTodayMinor, long remainingMinor) { }

    /** What the app needs to draw the document picker. */
    public record DocumentOption(String code, String label, boolean needsBackPhoto, boolean needsExpiryDate) { }

    /** The details saved so far in a draft (so the customer can resume where they stopped). */
    public record DraftDetails(String documentType, String fullNameOnDocument, String documentNumber,
                               String dateOfBirth, String documentExpiryDate) { }

    public record StatusResponse(
            boolean available,
            String level,
            String status,
            String rejectionReason,
            Instant submittedAt,
            Instant reviewedAt,
            String documentType,
            String documentTypeLabel,
            String documentLast4,
            DraftDetails draft,
            List<String> uploaded,
            List<String> required,
            boolean canStart,
            boolean canSubmit,
            LimitView mobileMoney,
            LimitView bank,
            long verifiedDailyLimitMinor,
            List<DocumentOption> documentTypes) { }

    public record DraftRequest(String documentType, String fullNameOnDocument, String documentNumber,
                               String dateOfBirth, String documentExpiryDate) { }

    public record DocumentUpload(String contentType, String data) { }

    public record UploadResponse(String kind, List<String> uploaded, List<String> required, boolean canSubmit) { }

    // ---------------- admin ----------------

    public record AdminCard(String id, String userId, String customerName, String email, String phone,
                            String documentType, String documentTypeLabel, String status,
                            Instant submittedAt, Instant reviewedAt, String nameMatch) { }

    public record AdminSummary(long pending, long approved, long rejected) { }

    public record AdminCustomer(String id, String fullName, String username, String email, String phone,
                                String accountNumber, boolean emailVerified, boolean active, boolean locked,
                                Instant joinedAt) { }

    public record AdminDocument(String type, String typeLabel, String fullNameOnDocument, String documentNumber,
                                String dateOfBirth, String expiryDate) { }

    public record AdminHistoryItem(String id, String status, Instant submittedAt, Instant reviewedAt, String reason) { }

    public record AdminAuditItem(String action, String actor, String detail, Instant at) { }

    public record AdminDetail(
            String id,
            String status,
            String rejectionReason,
            Instant submittedAt,
            Instant reviewedAt,
            String reviewedBy,
            AdminCustomer customer,
            AdminDocument document,
            String nameMatch,
            long sameDocumentOnOtherAccounts,
            List<String> photos,
            List<AdminHistoryItem> history,
            List<AdminAuditItem> audit,
            boolean canApprove,
            boolean canReject,
            boolean canRevoke,
            long verifiedDailyLimitMinor) { }

    public record AdminPhoto(String kind, String contentType, String data) { }

    public record DecisionRequest(String reason) { }
}
