package com.glr.deenwallet.kyc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycDocumentRepository extends JpaRepository<KycDocument, UUID> {

    Optional<KycDocument> findBySubmissionIdAndKind(UUID submissionId, KycDocumentKind kind);

    /** Which photos exist, WITHOUT loading the image bytes. */
    @Query("SELECT d.kind FROM KycDocument d WHERE d.submissionId = :submissionId")
    List<KycDocumentKind> kindsFor(@Param("submissionId") UUID submissionId);

    @Modifying
    @Query("DELETE FROM KycDocument d WHERE d.submissionId = :submissionId")
    int deleteAllFor(@Param("submissionId") UUID submissionId);

    @Modifying
    @Query("DELETE FROM KycDocument d WHERE d.submissionId = :submissionId AND d.kind = :kind")
    int deleteOne(@Param("submissionId") UUID submissionId, @Param("kind") KycDocumentKind kind);
}
