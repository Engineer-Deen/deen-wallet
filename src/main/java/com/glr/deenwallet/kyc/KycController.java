package com.glr.deenwallet.kyc;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Customer side of identity verification. Every call acts on the signed-in user only. */
@RestController
@RequestMapping("/api/users/kyc")
public class KycController {

    private final KycService kyc;

    public KycController(KycService kyc) {
        this.kyc = kyc;
    }

    @GetMapping("/status")
    public ResponseEntity<KycDtos.StatusResponse> status() {
        return ResponseEntity.ok(kyc.status(currentUserId()));
    }

    @PutMapping("/draft")
    public ResponseEntity<KycDtos.StatusResponse> saveDraft(@RequestBody KycDtos.DraftRequest body) {
        return ResponseEntity.ok(kyc.saveDraft(currentUserId(), body));
    }

    @PutMapping("/documents/{kind}")
    public ResponseEntity<KycDtos.UploadResponse> upload(@PathVariable String kind,
                                                         @RequestBody KycDtos.DocumentUpload body) {
        return ResponseEntity.ok(kyc.putDocument(currentUserId(), kind, body));
    }

    @PostMapping("/submit")
    public ResponseEntity<KycDtos.StatusResponse> submit() {
        return ResponseEntity.ok(kyc.submit(currentUserId()));
    }

    @DeleteMapping("/draft")
    public ResponseEntity<KycDtos.StatusResponse> discard() {
        return ResponseEntity.ok(kyc.discardDraft(currentUserId()));
    }

    private UUID currentUserId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated()) throw new SecurityException("Not authenticated");
        return UUID.fromString((String) a.getPrincipal());
    }
}
