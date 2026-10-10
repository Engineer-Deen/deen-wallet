package com.glr.deenwallet.transaction;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping
    public ResponseEntity<TransactionResponse> initiate(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody InitiateTransactionRequest request) {
        return ResponseEntity.ok(transactionService.initiate(currentUserId(), request, idempotencyKey));
    }

    // ✅ FIXED: Return TransactionResponse (which includes transactionCode)
    @GetMapping
    public ResponseEntity<List<TransactionResponse>> list() {
        List<Transaction> transactions = transactionService.listForUser(currentUserId());
        List<TransactionResponse> responses = transactions.stream()
                .map(TransactionResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    // ✅ FIXED: Return TransactionResponse (which includes transactionCode)
    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponse> get(@PathVariable UUID id) {
        Transaction transaction = transactionService.getForUser(currentUserId(), id);
        return ResponseEntity.ok(TransactionResponse.from(transaction));
    }

    /**
     * Lets the frontend show amount / fee / debited total live as the
     * user types an amount, before they commit to actually initiating
     * a transfer. Purely a local calculation — reuses the same
     * ConversionFee.calculate() the real transaction creation path
     * uses, so the numbers shown here always match what
     * POST /api/transactions will actually charge. No Monime call, no
     * persistence.
     */
    @GetMapping("/preview-fee")
    public ResponseEntity<FeePreviewResponse> previewFee(@RequestParam BigDecimal amount) {
        if (amount == null
                || amount.compareTo(BigDecimal.ONE) < 0
                || amount.compareTo(new BigDecimal("50000.00")) > 0
                || amount.scale() > 2) {
            throw new IllegalArgumentException("Amount must be between 1.00 and 50000.00 SLE and have at most 2 decimal places");
        }
        long minorUnits = amount.movePointRight(2).longValueExact();
        ConversionFee fee = ConversionFee.calculate(minorUnits);

        return ResponseEntity.ok(new FeePreviewResponse(
                toDecimal(minorUnits),
                toDecimal(fee.feeValue()),
                toDecimal(fee.totalChargedValue())
        ));
    }

    private BigDecimal toDecimal(long minorUnits) {
        return BigDecimal.valueOf(minorUnits).movePointLeft(2);
    }

    private UUID currentUserId() {
        String userId = (String) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();
        return UUID.fromString(userId);
    }
}
