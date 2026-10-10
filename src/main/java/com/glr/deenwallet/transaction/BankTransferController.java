package com.glr.deenwallet.transaction;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/bank-transfers")
@RequiredArgsConstructor
public class BankTransferController {

    private final BankTransferService bankTransferService;
    private final TransactionService transactionService;

    @GetMapping("/banks")
    public ResponseEntity<List<BankOptionResponse>> banks() {
        return ResponseEntity.ok(bankTransferService.listAvailableBanks());
    }

    @GetMapping("/verify-account")
    public ResponseEntity<BankAccountVerificationResponse> verifyAccount(
            @RequestParam String providerId,
            @RequestParam String accountNumber) {
        return ResponseEntity.ok(
                bankTransferService.verifyAccount(currentUserId(), providerId, accountNumber));
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> initiate(@Valid @RequestBody BankTransferRequest request) {
        return ResponseEntity.ok(transactionService.initiateBankTransfer(currentUserId(), request));
    }

    private UUID currentUserId() {
        String userId = (String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return UUID.fromString(userId);
    }
}