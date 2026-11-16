package com.glr.deenwallet.pawapay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.glr.deenwallet.kyc.KycCrypto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Cross-border transfer: the sender pays in Sierra Leone (deposit) and the recipient receives in another country
 * (remittance). If the transfer abroad cannot be completed, the sender's deposit is refunded.
 *
 * Money rules (same as the rest of DeenWallet Transfer):
 *  - ids are created and saved BEFORE any pawaPay call, so every call can be repeated without charging or paying twice;
 *  - a callback is only a nudge: the real status is always read from pawaPay;
 *  - unknown outcome (timeout, HTTP 5xx) is never treated as failure: the status is checked again;
 *  - no pawaPay call runs inside a database transaction;
 *  - nothing is refunded while pawaPay says IN_RECONCILIATION, and never on the clock alone.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrossBorderTransferService {

    private static final int MAX_DEPOSIT_ATTEMPTS = 5;
    private static final int MAX_REMIT_ATTEMPTS = 10;     // about 5 minutes of retries, then the sender is refunded
    private static final int MAX_REFUND_ATTEMPTS = 5;

    /** pawaPay answers that mean "not now" (our setup or their provider), not "this transfer is wrong". */
    private static final Set<String> RETRY_LATER = Set.of(
            "PAWAPAY_WALLET_OUT_OF_FUNDS", "PROVIDER_TEMPORARILY_UNAVAILABLE", "UNKNOWN_ERROR",
            "NO_AUTHENTICATION", "AUTHENTICATION_ERROR", "AUTHORISATION_ERROR", "HTTP_SIGNATURE_ERROR");

    private static final Set<String> PURPOSES = Set.of("FAMILY_SUPPORT", "MEDICAL_EXPENSES", "TUITION_FEES", "EDUCATION_SUPPORT",
            "GIFT_AND_OTHER_DONATIONS", "HOME_IMPROVEMENT", "DEBT_SETTLEMENT", "REAL_ESTATE", "TAXES", "SALARY", "SAVINGS",
            "PERSONAL_TRANSFER", "OTHER");
    private static final Set<String> SOURCES = Set.of("SALARY", "SAVINGS", "LOTTERY", "LOAN", "BUSINESS_INCOME", "GIFT", "OTHER");
    private static final Set<String> ID_TYPES = Set.of("NATIONAL_ID", "PASSPORT", "DRIVING_LICENSE", "SOCIAL_SECURITY_ID", "RESIDENCE_PERMIT");

    private static final Pattern MSISDN = Pattern.compile("^[1-9][0-9]{7,14}$");
    private static final Pattern PROVIDER = Pattern.compile("^[A-Z0-9_]{3,40}$");
    private static final Pattern ISO3 = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern NAME = Pattern.compile("^[\\p{L} .'-]{1,64}$");

    private final CrossBorderTransferRepository repo;
    private final PawaPayEventRepository events;
    private final PawaPayClient client;
    private final PawaPayProperties props;
    private final KycCrypto crypto;
    private final PlatformTransactionManager txManager;
    private final CrossBorderNotifier notifier;
    private final ObjectMapper mapper = new ObjectMapper();

    private TransactionTemplate tt;

    private TransactionTemplate tt() {
        if (tt == null) tt = new TransactionTemplate(txManager);
        return tt;
    }

    // =============================================================== start

    /**
     * What the caller provides. The amounts are the QUOTE: they must come from the server's own pricing, never from the
     * app. senderDetails = the "senderDetails" object of pawaPay's remittance API (name, nationality, phone, address,
     * identification ...).
     */
    public record StartCommand(UUID userId,
                               String payerProvider, String payerPhone,
                               BigDecimal sendAmount, String sendCurrency, BigDecimal feeAmount,
                               BigDecimal receiveAmount, String receiveCurrency, BigDecimal fxRate,
                               String destCountry, String destProvider, String recipientPhone,
                               String recipientFirstName, String recipientLastName,
                               JsonNode senderDetails, String purposeOfFunds, String sourceOfFunds) { }

    public CrossBorderTransfer start(StartCommand c) {
        return start(c, null);
    }

    public CrossBorderTransfer start(StartCommand c, UUID clientRequestId) {
        if (!props.enabled()) throw new IllegalStateException("International transfers are not available yet.");
        if (!crypto.isConfigured()) throw new IllegalStateException("International transfers are temporarily unavailable.");
        validate(c);

        ObjectNode stored = mapper.createObjectNode();
        stored.set("senderDetails", c.senderDetails());
        stored.put("purposeOfFunds", c.purposeOfFunds());
        stored.put("sourceOfFunds", c.sourceOfFunds());

        CrossBorderTransfer t = new CrossBorderTransfer();
        t.setId(UUID.randomUUID());
        t.setUserId(c.userId());
        t.setStatus(CrossBorderStatus.CREATED);
        t.setSendAmount(c.sendAmount());
        t.setSendCurrency(c.sendCurrency());
        t.setFeeAmount(c.feeAmount() == null ? BigDecimal.ZERO : c.feeAmount());
        t.setReceiveAmount(c.receiveAmount());
        t.setReceiveCurrency(c.receiveCurrency());
        t.setFxRate(c.fxRate());
        t.setPayerProvider(c.payerProvider());
        t.setPayerPhone(c.payerPhone());
        t.setDestCountry(c.destCountry());
        t.setDestProvider(c.destProvider());
        t.setRecipientPhone(c.recipientPhone());
        t.setRecipientFirstName(c.recipientFirstName().trim());
        t.setRecipientLastName(c.recipientLastName().trim());
        t.setDepositId(UUID.randomUUID());
        t.setRemittanceId(UUID.randomUUID());
        t.setRefundId(UUID.randomUUID());
        t.setSenderEnc(crypto.encryptText(stored.toString(), "xb:" + t.getId()));
        t.setClientRequestId(clientRequestId);
        try {
            repo.save(t);   // saved BEFORE the first call to pawaPay
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            if (clientRequestId != null) {   // the same request arrived twice at once: use the first one
                Optional<CrossBorderTransfer> same = repo.findByUserIdAndClientRequestId(c.userId(), clientRequestId);
                if (same.isPresent()) return same.get();
            }
            throw dup;
        }

        return advance(t.getId());
    }

    private static void validate(StartCommand c) {
        require(c.userId() != null, "Missing user.");
        require(c.payerProvider() != null && PROVIDER.matcher(c.payerProvider()).matches(), "Invalid payer provider.");
        require(c.payerPhone() != null && MSISDN.matcher(c.payerPhone()).matches(), "Invalid payer phone number.");
        require(c.destProvider() != null && PROVIDER.matcher(c.destProvider()).matches(), "Invalid destination provider.");
        require(c.recipientPhone() != null && MSISDN.matcher(c.recipientPhone()).matches(), "Invalid recipient phone number.");
        require(c.destCountry() != null && ISO3.matcher(c.destCountry()).matches(), "Invalid destination country.");
        require(c.sendCurrency() != null && ISO3.matcher(c.sendCurrency()).matches(), "Invalid send currency.");
        require(c.receiveCurrency() != null && ISO3.matcher(c.receiveCurrency()).matches(), "Invalid receive currency.");
        require(c.sendAmount() != null && c.sendAmount().signum() > 0 && c.sendAmount().scale() <= 2, "Invalid send amount.");
        require(c.receiveAmount() != null && c.receiveAmount().signum() > 0 && c.receiveAmount().scale() <= 3, "Invalid receive amount.");
        require(c.feeAmount() == null || (c.feeAmount().signum() >= 0 && c.feeAmount().scale() <= 2), "Invalid fee.");
        require(c.fxRate() != null && c.fxRate().signum() > 0, "Invalid exchange rate.");
        require(c.recipientFirstName() != null && NAME.matcher(c.recipientFirstName().trim()).matches(), "Invalid recipient first name.");
        require(c.recipientLastName() != null && NAME.matcher(c.recipientLastName().trim()).matches(), "Invalid recipient last name.");
        require(PURPOSES.contains(c.purposeOfFunds()), "Invalid purpose of funds.");
        require(SOURCES.contains(c.sourceOfFunds()), "Invalid source of funds.");
        JsonNode s = c.senderDetails();
        require(s != null && s.isObject(), "Sender details are required.");
        for (String f : new String[]{"firstName", "lastName", "nationality", "phoneNumber"}) {
            require(!s.path(f).asText("").isBlank(), "Sender " + f + " is required.");
        }
        require(ISO3.matcher(s.path("nationality").asText("")).matches(), "Sender nationality must be a 3-letter country code.");
        JsonNode a = s.path("address");
        for (String f : new String[]{"addressLine", "postalCode", "city", "country"}) {
            require(!a.path(f).asText("").isBlank(), "Sender address " + f + " is required.");
        }
        JsonNode id = s.path("identification");
        require(ID_TYPES.contains(id.path("type").asText("")) && !id.path("number").asText("").isBlank(), "Sender identification is required.");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    // =============================================================== the state machine

    /** Moves one transfer as far as it can right now. Safe to call any number of times, from callbacks and from the job. */
    public CrossBorderTransfer advance(UUID id) {
        for (int step = 0; step < 4; step++) {   // a few steps in one go (collected -> remitting), never an endless loop
            CrossBorderTransfer t = repo.findById(id).orElseThrow();
            CrossBorderStatus before = t.getStatus();
            try {
                switch (before) {
                    case CREATED -> startDeposit(t);
                    case COLLECTING -> pollDeposit(t);
                    case COLLECTED -> sendRemittance(t);
                    case REMITTING -> pollRemittance(t);
                    case REFUNDING -> driveRefund(t);
                    default -> { return t; }
                }
            } catch (PawaPayUnavailableException e) {
                log.warn("pawaPay did not answer for transfer {} ({}): {} - will check again", id, before, e.getMessage());
                return repo.findById(id).orElseThrow();
            }
            CrossBorderTransfer after = repo.findById(id).orElseThrow();
            if (after.getStatus() == before) return after;   // waiting for pawaPay / the sender / the recipient
        }
        return repo.findById(id).orElseThrow();
    }

    private void startDeposit(CrossBorderTransfer t) {
        PawaPayResponse r = client.initiateDeposit(t.getDepositId(), t.getPayerProvider(), t.getPayerPhone(),
                amount(paid(t)), t.getSendCurrency(), t.getId().toString(), "DeenWallet Transfer");
        if (r.accepted()) {
            apply(t.getId(), EnumSet.of(CrossBorderStatus.CREATED), x -> {
                x.setStatus(CrossBorderStatus.COLLECTING);
                x.setDepositStatus("ACCEPTED");
                x.setAttempts(0);
            });
            return;
        }
        if (isRetryable(r)) {
            boolean giveUp = t.getAttempts() + 1 >= MAX_DEPOSIT_ATTEMPTS;
            apply(t.getId(), EnumSet.of(CrossBorderStatus.CREATED), x -> {
                x.setAttempts(x.getAttempts() + 1);
                if (giveUp) fail(x, CrossBorderStatus.FAILED, r);
            });
            return;
        }
        apply(t.getId(), EnumSet.of(CrossBorderStatus.CREATED), x -> fail(x, CrossBorderStatus.FAILED, r));   // sender was never charged
    }

    private void pollDeposit(CrossBorderTransfer t) {
        PawaPayResponse r = client.checkDeposit(t.getDepositId());
        if (r.notFound()) {   // pawaPay never saw it: asking again with the same id is safe
            apply(t.getId(), EnumSet.of(CrossBorderStatus.COLLECTING), x -> { x.setStatus(CrossBorderStatus.CREATED); x.setDepositStatus(null); });
            return;
        }
        if (!r.found()) return;
        String s = r.dataStatus();
        String txn = r.data().path("providerTransactionId").asText(null);
        switch (s) {
            case "COMPLETED" -> apply(t.getId(), EnumSet.of(CrossBorderStatus.COLLECTING), x -> {
                x.setStatus(CrossBorderStatus.COLLECTED);
                x.setDepositStatus(s);
                x.setDepositProviderTxnId(txn);
                x.setAttempts(0);
            });
            case "FAILED" -> apply(t.getId(), EnumSet.of(CrossBorderStatus.COLLECTING), x -> {
                x.setDepositStatus(s);
                fail(x, CrossBorderStatus.FAILED, r);   // the sender was not charged
            });
            default -> apply(t.getId(), EnumSet.of(CrossBorderStatus.COLLECTING), x -> x.setDepositStatus(s));
        }
    }

    private void sendRemittance(CrossBorderTransfer t) {
        PawaPayResponse r = client.initiateRemittance(buildRemittance(t));
        if (r.accepted()) {
            apply(t.getId(), EnumSet.of(CrossBorderStatus.COLLECTED), x -> {
                x.setStatus(CrossBorderStatus.REMITTING);
                x.setRemittanceStatus("ACCEPTED");
                x.setAttempts(0);
            });
            return;
        }
        String code = r.failureCode();
        if (code.equals("PAWAPAY_WALLET_OUT_OF_FUNDS")) {
            log.error("pawaPay wallet has no money for transfer {} ({} {}): top up the wallet - the sender is refunded if it stays empty",
                    t.getId(), t.getReceiveAmount(), t.getReceiveCurrency());
        }
        if (isRetryable(r)) {
            boolean giveUp = t.getAttempts() + 1 >= MAX_REMIT_ATTEMPTS;
            apply(t.getId(), EnumSet.of(CrossBorderStatus.COLLECTED), x -> {
                x.setAttempts(x.getAttempts() + 1);
                if (giveUp) refundNow(x, r);
            });
            return;
        }
        apply(t.getId(), EnumSet.of(CrossBorderStatus.COLLECTED), x -> refundNow(x, r));   // this transfer cannot be sent: give the money back
    }

    private void pollRemittance(CrossBorderTransfer t) {
        PawaPayResponse r = client.checkRemittance(t.getRemittanceId());
        if (r.notFound()) {   // asking again with the same id is safe
            apply(t.getId(), EnumSet.of(CrossBorderStatus.REMITTING), x -> { x.setStatus(CrossBorderStatus.COLLECTED); x.setRemittanceStatus(null); });
            return;
        }
        if (!r.found()) return;
        String s = r.dataStatus();
        String txn = r.data().path("providerTransactionId").asText(null);
        switch (s) {
            case "COMPLETED" -> apply(t.getId(), EnumSet.of(CrossBorderStatus.REMITTING), x -> {
                x.setStatus(CrossBorderStatus.COMPLETED);
                x.setRemittanceStatus(s);
                x.setRemittanceProviderTxnId(txn);
                x.setCompletedAt(Instant.now());
            });
            case "FAILED" -> apply(t.getId(), EnumSet.of(CrossBorderStatus.REMITTING), x -> {
                x.setRemittanceStatus(s);
                refundNow(x, r);   // final: the recipient did not receive it
            });
            default -> apply(t.getId(), EnumSet.of(CrossBorderStatus.REMITTING), x -> x.setRemittanceStatus(s));   // incl. IN_RECONCILIATION: wait
        }
    }

    private void driveRefund(CrossBorderTransfer t) {
        if (t.getRefundStatus() == null) {
            PawaPayResponse r = client.initiateRefund(t.getRefundId(), t.getDepositId(), amount(paid(t)), t.getSendCurrency());
            if (r.accepted()) {
                apply(t.getId(), EnumSet.of(CrossBorderStatus.REFUNDING), x -> { x.setRefundStatus("ACCEPTED"); x.setAttempts(0); });
            } else if (isRetryable(r)) {
                boolean giveUp = t.getAttempts() + 1 >= MAX_REFUND_ATTEMPTS;
                apply(t.getId(), EnumSet.of(CrossBorderStatus.REFUNDING), x -> {
                    x.setAttempts(x.getAttempts() + 1);
                    if (giveUp) review(x, "The automatic refund could not be started: " + r.failureCode());
                });
            } else {
                apply(t.getId(), EnumSet.of(CrossBorderStatus.REFUNDING), x -> review(x, "The automatic refund was refused: " + r.failureCode()));
            }
            return;
        }
        PawaPayResponse r = client.checkRefund(t.getRefundId());
        if (r.notFound()) {
            apply(t.getId(), EnumSet.of(CrossBorderStatus.REFUNDING), x -> x.setRefundStatus(null));   // same id again is safe
            return;
        }
        if (!r.found()) return;
        String s = r.dataStatus();
        switch (s) {
            case "COMPLETED" -> apply(t.getId(), EnumSet.of(CrossBorderStatus.REFUNDING), x -> {
                x.setStatus(CrossBorderStatus.REFUNDED);
                x.setRefundStatus(s);
                x.setCompletedAt(Instant.now());
            });
            case "FAILED" -> apply(t.getId(), EnumSet.of(CrossBorderStatus.REFUNDING), x -> {
                x.setRefundStatus(s);
                review(x, "The refund failed: " + r.failureCode());
            });
            default -> apply(t.getId(), EnumSet.of(CrossBorderStatus.REFUNDING), x -> x.setRefundStatus(s));
        }
    }

    // =============================================================== helpers

    /** Everything the sender paid: the amount sent plus our fee. A refund gives all of it back. */
    private static BigDecimal paid(CrossBorderTransfer t) {
        return t.getSendAmount().add(t.getFeeAmount());
    }

    private static boolean isRetryable(PawaPayResponse r) {
        return r.http() == 401 || r.http() == 403 || RETRY_LATER.contains(r.failureCode());
    }

    private static void fail(CrossBorderTransfer x, CrossBorderStatus to, PawaPayResponse r) {
        x.setStatus(to);
        x.setFailureCode(cut(r.failureCode().isBlank() ? "REJECTED" : r.failureCode(), 64));
        x.setFailureMessage(cut(r.failureMessage(), 500));
        x.setCompletedAt(Instant.now());
    }

    /** The money was taken but the transfer abroad did not work: give the sender's money back. */
    private static void refundNow(CrossBorderTransfer x, PawaPayResponse r) {
        x.setStatus(CrossBorderStatus.REFUNDING);
        x.setFailureCode(cut(r.failureCode().isBlank() ? "REMITTANCE_FAILED" : r.failureCode(), 64));
        x.setFailureMessage(cut(r.failureMessage(), 500));
        x.setAttempts(0);
    }

    private static void review(CrossBorderTransfer x, String why) {
        x.setStatus(CrossBorderStatus.REVIEW);
        x.setFailureMessage(cut(why, 500));
        log.error("Cross-border transfer {} needs a person: {}", x.getId(), why);
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** The short transaction ID customers and admins can search for, e.g. XB-1A2B3C4D. */
    static String reference(CrossBorderTransfer t) {
        return "XB-" + t.getId().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    /** pawaPay wants plain numbers without trailing zeros, e.g. "15" or "15.5". */
    static String amount(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    /** Short database transaction with the row locked; nothing happens if the transfer already moved on. */
    private boolean apply(UUID id, Set<CrossBorderStatus> expected, Consumer<CrossBorderTransfer> change) {
        final CrossBorderTransfer[] changed = new CrossBorderTransfer[1];
        Boolean ok = tt().execute(s -> {
            CrossBorderTransfer t = repo.findByIdForUpdate(id).orElseThrow();
            if (!expected.contains(t.getStatus())) return false;
            CrossBorderStatus before = t.getStatus();
            change.accept(t);
            repo.save(t);
            if (t.getStatus() != before) changed[0] = t;
            return true;
        });
        if (changed[0] != null) notifier.statusChanged(changed[0]);   // after the database change is saved; never throws
        return Boolean.TRUE.equals(ok);
    }

    private ObjectNode buildRemittance(CrossBorderTransfer t) {
        JsonNode stored;
        try {
            stored = mapper.readTree(crypto.decryptText(t.getSenderEnc(), "xb:" + t.getId()));
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the sender details of transfer " + t.getId(), e);
        }
        ObjectNode b = mapper.createObjectNode();
        b.put("remittanceId", t.getRemittanceId().toString());
        b.put("amount", amount(t.getReceiveAmount()));
        b.put("currency", t.getReceiveCurrency());
        b.put("customerMessage", "DeenWallet Transfer");
        ObjectNode rec = b.putObject("recipient");
        rec.put("type", "MMO");
        ObjectNode acc = rec.putObject("accountDetails");
        acc.put("provider", t.getDestProvider());
        acc.put("phoneNumber", t.getRecipientPhone());
        ObjectNode rd = rec.putObject("recipientDetails");
        rd.put("firstName", t.getRecipientFirstName());
        rd.put("lastName", t.getRecipientLastName());
        ObjectNode sender = b.putObject("sender");
        ObjectNode td = sender.putObject("transactionDetails");
        td.put("transactionReference", t.getId().toString());
        td.put("originalAmount", amount(t.getSendAmount()));
        td.put("originalCurrency", t.getSendCurrency());
        td.put("buyFxRate", t.getFxRate().stripTrailingZeros().toPlainString());
        td.put("senderFees", amount(t.getFeeAmount()));
        td.put("purposeOfFunds", stored.path("purposeOfFunds").asText());
        td.put("sourceOfFunds", stored.path("sourceOfFunds").asText());
        sender.set("senderDetails", stored.path("senderDetails"));
        return b;
    }

    // =============================================================== callbacks + background check

    /** A callback only says "look at this one": the real status is read from pawaPay by advance(). */
    public void onCallback(String kind, UUID resourceId, String claimedStatus, String eventKey) {
        if (!events.existsByEventKey(eventKey)) {
            PawaPayEvent e = new PawaPayEvent();
            e.setEventKey(eventKey);
            e.setKind(kind);
            e.setResourceId(resourceId.toString());
            e.setClaimed(cut(claimedStatus, 24));
            try {
                events.save(e);
            } catch (org.springframework.dao.DataIntegrityViolationException dup) {
                // the same callback arrived twice at once: fine
            }
        }
        Optional<CrossBorderTransfer> t = switch (kind) {
            case "deposits" -> repo.findByDepositId(resourceId);
            case "remittances" -> repo.findByRemittanceId(resourceId);
            case "refunds" -> repo.findByRefundId(resourceId);
            default -> Optional.empty();
        };
        t.ifPresent(x -> advance(x.getId()));
    }

    /** Safety net: callbacks can be late or lost, so every open transfer is checked again every 30 seconds. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void checkOpenTransfers() {
        if (!props.enabled()) return;
        List<CrossBorderStatus> open = Arrays.stream(CrossBorderStatus.values()).filter(CrossBorderStatus::open).toList();
        for (CrossBorderTransfer t : repo.findOpenOlderThan(open, Instant.now().minusSeconds(15), PageRequest.of(0, 50))) {
            try {
                advance(t.getId());
            } catch (Exception e) {
                log.warn("Could not check cross-border transfer {}: {}", t.getId(), e.toString());
            }
        }
    }

    // =============================================================== read

    public Map<String, Object> view(CrossBorderTransfer t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("reference", reference(t));
        m.put("status", t.getStatus());
        m.put("sendAmount", t.getSendAmount());
        m.put("sendCurrency", t.getSendCurrency());
        m.put("feeAmount", t.getFeeAmount());
        m.put("receiveAmount", t.getReceiveAmount());
        m.put("receiveCurrency", t.getReceiveCurrency());
        m.put("destCountry", t.getDestCountry());
        m.put("depositStatus", t.getDepositStatus());
        m.put("remittanceStatus", t.getRemittanceStatus());
        m.put("refundStatus", t.getRefundStatus());
        m.put("failureCode", t.getFailureCode());
        m.put("failureMessage", t.getFailureMessage());
        m.put("createdAt", t.getCreatedAt());
        m.put("completedAt", t.getCompletedAt());
        return m;
    }

    public Optional<CrossBorderTransfer> find(UUID id) {
        return repo.findById(id);
    }
}
