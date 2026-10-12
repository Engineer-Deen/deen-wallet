package com.glr.deenwallet.waec;

import com.glr.deenwallet.transaction.Transaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Stock handling: adding pins, reserving them for a purchase, and giving them back. */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaecPinService {

    static final Pattern CODE = Pattern.compile("^[A-Z0-9]{6,32}$");
    private static final int MAX_LINES_PER_UPLOAD = 500;

    private final WaecPinRepository pinRepository;
    private final WaecOrderRepository orderRepository;
    private final WaecAuditRepository auditRepository;
    private final WaecCrypto crypto;

    public record StockSummary(long available, long reserved, long sold, long total) {}

    public record AddResult(int added, int duplicates, int invalid, List<String> problems) {}

    public record PinView(UUID id, String pin, String serial) {}

    public boolean isOperational() {
        return crypto.isConfigured();
    }

    @Transactional(readOnly = true)
    public long availableCount() {
        return pinRepository.countByStatus(WaecPinStatus.AVAILABLE);
    }

    @Transactional(readOnly = true)
    public StockSummary summary() {
        long available = pinRepository.countByStatus(WaecPinStatus.AVAILABLE);
        long reserved = pinRepository.countByStatus(WaecPinStatus.RESERVED);
        long sold = pinRepository.countByStatus(WaecPinStatus.SOLD);
        return new StockSummary(available, reserved, sold, available + reserved + sold);
    }

    /**
     * Adds pins from pasted text, one pair per line: PIN,SERIAL (comma, space, tab, ; or | all work).
     * Duplicates (inside the upload or already stored) and badly formed lines are skipped and reported.
     * Everything valid is saved in one transaction.
     */
    @Transactional
    public AddResult addPins(String text, UUID adminId, String adminEmail) {
        if (!crypto.isConfigured()) {
            throw new IllegalStateException("WAEC pin storage is not configured (missing encryption key).");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Paste at least one PIN and SERIAL.");
        }
        String[] lines = text.split("\\R");
        if (lines.length > MAX_LINES_PER_UPLOAD) {
            throw new IllegalArgumentException("Upload at most " + MAX_LINES_PER_UPLOAD + " lines at a time.");
        }

        List<String> problems = new ArrayList<>();
        int invalid = 0;
        int duplicates = 0;
        Map<String, String[]> byPinHash = new LinkedHashMap<>(); // pinHash -> {pin, serial}
        Set<String> serialHashesInBatch = new HashSet<>();

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i].trim();
            if (raw.isEmpty() || raw.startsWith("#")) continue;
            String[] parts = raw.toUpperCase(Locale.ROOT).split("[\\s,;|]+");
            if (parts.length == 2 && parts[0].equals("PIN") && parts[1].startsWith("SERIAL")) continue; // header row
            int lineNo = i + 1;
            if (parts.length != 2 || !CODE.matcher(parts[0]).matches() || !CODE.matcher(parts[1]).matches()) {
                invalid++;
                addProblem(problems, "Line " + lineNo + ": expected PIN and SERIAL (letters/digits, 6-32 characters each).");
                continue;
            }
            if (parts[0].matches(".*[A-Z].*") && parts[1].matches("[0-9]+")) {
                invalid++;
                addProblem(problems, "Line " + lineNo + ": PIN and SERIAL look swapped. Put the PIN first.");
                continue;
            }
            String pinHash = crypto.hash(parts[0]);
            String serialHash = crypto.hash(parts[1]);
            if (byPinHash.containsKey(pinHash) || !serialHashesInBatch.add(serialHash)) {
                duplicates++;
                addProblem(problems, "Line " + lineNo + ": duplicate inside this upload.");
                continue;
            }
            byPinHash.put(pinHash, new String[]{parts[0], parts[1], serialHash});
        }

        if (byPinHash.isEmpty()) {
            return new AddResult(0, duplicates, invalid, problems);
        }

        Set<String> existingPins = new HashSet<>(pinRepository.existingPinHashes(byPinHash.keySet()));
        Set<String> serialHashes = new HashSet<>();
        byPinHash.values().forEach(v -> serialHashes.add(v[2]));
        Set<String> existingSerials = new HashSet<>(pinRepository.existingSerialHashes(serialHashes));

        List<WaecPin> toSave = new ArrayList<>();
        for (Map.Entry<String, String[]> e : byPinHash.entrySet()) {
            String[] v = e.getValue();
            if (existingPins.contains(e.getKey()) || existingSerials.contains(v[2])) {
                duplicates++;
                addProblem(problems, "A pin ending " + last4(v[0]) + " already exists. Skipped.");
                continue;
            }
            toSave.add(WaecPin.builder()
                    .pinEncrypted(crypto.encrypt(v[0]))
                    .serialEncrypted(crypto.encrypt(v[1]))
                    .pinHash(e.getKey())
                    .serialHash(v[2])
                    .pinLast4(last4(v[0]))
                    .serialLast4(last4(v[1]))
                    .status(WaecPinStatus.AVAILABLE)
                    .addedBy(adminId)
                    .addedByEmail(adminEmail)
                    .build());
        }
        pinRepository.saveAll(toSave);
        audit(null, "PINS_ADDED", adminId, adminEmail, toSave.size() + " added, " + duplicates + " duplicates, " + invalid + " invalid");
        log.info("Admin {} added {} WAEC pins ({} duplicates, {} invalid)", adminEmail, toSave.size(), duplicates, invalid);
        return new AddResult(toSave.size(), duplicates, invalid, problems);
    }

    /**
     * Creates the order and reserves pins for it. Called right after the transaction row is saved and
     * BEFORE the payment code is requested, so a buyer is never asked to pay for stock that is not there.
     */
    @Transactional
    public WaecOrder reserveForTransaction(Transaction t, int quantity, String deliveryEmail, WaecPricing.Quote quote) {
        if (!crypto.isConfigured()) {
            throw new IllegalStateException("WAEC pin sales are temporarily unavailable.");
        }
        // Idempotent: a repeat call for the same transaction returns the order it already made
        // instead of reserving a second set of pins.
        java.util.Optional<WaecOrder> already = orderRepository.findByTransactionId(t.getId());
        if (already.isPresent()) return already.get();
        List<WaecPin> pins = pinRepository.lockAvailable(quantity);
        if (pins.size() < quantity) {
            throw new IllegalStateException("Not enough WAEC pins in stock right now. Please try again later.");
        }
        WaecOrder order = orderRepository.save(WaecOrder.builder()
                .transactionId(t.getId())
                .transactionCode(t.getTransactionCode())
                .userId(t.getUserId())
                .quantity(quantity)
                .unitPriceValue(quote.unitPriceMinor())
                .amountValue(quote.amountMinor())
                .feeValue(quote.feeMinor())
                .totalChargedValue(quote.totalMinor())
                .deliveryChannel("EMAIL")
                .deliveryEmail(deliveryEmail)
                .deliveryStatus(WaecDeliveryStatus.PENDING)
                .build());
        Instant now = Instant.now();
        for (WaecPin p : pins) {
            p.setStatus(WaecPinStatus.RESERVED);
            p.setOrderId(order.getId());
            p.setReservedAt(now);
        }
        pinRepository.saveAll(pins);
        return order;
    }

    @Transactional(readOnly = true)
    public List<UUID> orderIdsByPinOrSerial(String normalizedCode) {
        if (!crypto.isConfigured()) return List.of();
        return pinRepository.orderIdsByPinOrSerialHash(crypto.hash(normalizedCode));
    }

    /** The purchase was cancelled or failed before payment: give the reserved pins back. */
    @Transactional
    public void releaseForTransaction(UUID transactionId) {
        orderRepository.findByTransactionId(transactionId).ifPresent(order -> {
            if (order.getFulfilledAt() != null) return; // already sold, never un-sell
            int released = pinRepository.releaseByOrder(order.getId());
            if (released > 0) log.info("Released {} WAEC pins for transaction {}", released, order.getTransactionCode());
        });
    }

    /** Initiation failed before the customer was asked to pay: remove the order and free its pins. */
    @Transactional
    public void deleteOrderAndRelease(UUID transactionId) {
        orderRepository.findByTransactionId(transactionId).ifPresent(order -> {
            if (order.getFulfilledAt() != null) return;
            pinRepository.releaseByOrder(order.getId());
            orderRepository.delete(order);
        });
    }

    @Transactional
    public int releaseStaleReservations(Instant cutoff) {
        return pinRepository.releaseStale(cutoff);
    }

    /** Decrypted pins of one order. Only for the buyer, delivery, or an admin who passed PIN verification. */
    @Transactional(readOnly = true)
    public List<PinView> pinsForOrder(UUID orderId) {
        return pinRepository.findByOrderId(orderId).stream()
                .filter(p -> p.getStatus() == WaecPinStatus.SOLD)
                .sorted(Comparator.comparing(WaecPin::getSoldAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(p -> new PinView(p.getId(), crypto.decrypt(p.getPinEncrypted()), crypto.decrypt(p.getSerialEncrypted())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<WaecPin> pinRowsForOrder(UUID orderId) {
        return pinRepository.findByOrderId(orderId);
    }

    @Transactional
    public void audit(UUID orderId, String action, UUID actorId, String actorEmail, String detail) {
        auditRepository.save(WaecAudit.builder()
                .orderId(orderId).action(action).actorId(actorId).actorEmail(actorEmail)
                .detail(detail == null ? null : (detail.length() > 500 ? detail.substring(0, 500) : detail))
                .build());
    }

    private static void addProblem(List<String> problems, String message) {
        if (problems.size() < 50) problems.add(message);
    }

    private static String last4(String value) {
        return value.length() <= 4 ? value : value.substring(value.length() - 4);
    }
}
