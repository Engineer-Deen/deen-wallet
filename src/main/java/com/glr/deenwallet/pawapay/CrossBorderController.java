package com.glr.deenwallet.pawapay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.glr.deenwallet.kyc.KycCrypto;
import com.glr.deenwallet.kyc.KycLimitService;
import com.glr.deenwallet.kyc.KycStatus;
import com.glr.deenwallet.kyc.KycSubmission;
import com.glr.deenwallet.kyc.KycSubmissionRepository;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * "Send abroad" for customers. The customer pays from their Orange Money (pawaPay deposit); the recipient is paid on a mobile
 * money wallet in another country. Customers must have completed KYC. Prices come from the admin's corridor settings.
 *
 * The sender's name and ID come from their approved KYC; the address and nationality are asked in the app and saved (encrypted).
 */
@RestController
@RequestMapping("/api/cross-border")
@RequiredArgsConstructor
public class CrossBorderController {

    private static final String PAYER_PROVIDER = "ORANGE_SLE";
    private static final int MAX_OPEN_PER_USER = 3;
    private static final Pattern ISO3 = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern TEXT = Pattern.compile("^[\\p{L}0-9 .,'#/()-]{2,120}$");

    static final List<String> PURPOSES = List.of("FAMILY_SUPPORT", "MEDICAL_EXPENSES", "TUITION_FEES", "EDUCATION_SUPPORT",
            "GIFT_AND_OTHER_DONATIONS", "HOME_IMPROVEMENT", "DEBT_SETTLEMENT", "REAL_ESTATE", "SAVINGS", "PERSONAL_TRANSFER", "OTHER");
    static final List<String> SOURCES = List.of("SALARY", "SAVINGS", "BUSINESS_INCOME", "GIFT", "LOAN", "OTHER");

    private final CrossBorderPricing pricing;
    private final CrossBorderCorridorRepository corridors;
    private final CrossBorderSenderProfileRepository profiles;
    private final CrossBorderRecipientRepository recipients;
    private final CrossBorderTransferRepository transfers;
    private final CrossBorderTransferService service;
    private final KycLimitService kycLimits;
    private final KycSubmissionRepository submissions;
    private final KycCrypto crypto;
    private final UserRepository users;
    private final CrossBorderAvailability availability;
    // Created here on purpose: this app has no ObjectMapper bean to inject (that is what stopped it starting).
    private final ObjectMapper mapper = new ObjectMapper();
    // (the design preview switch has been removed: Send Money Abroad only appears when pawaPay is live)

    // ------------------------------------------------------------------ config for the screens
    @GetMapping("/config")
    public ResponseEntity<?> config() {
        UUID uid = currentUserId();
        Map<String, Object> m = new LinkedHashMap<>();
        boolean on = pricing.serviceOn();
        m.put("enabled", on);
        m.put("verified", kycLimits.isVerified(uid));
        if (!on) return ResponseEntity.ok(m);
        List<Map<String, Object>> list = new ArrayList<>();
        for (CrossBorderCorridor c : corridors.findByEnabledTrueOrderBySortOrderAscCountryNameAscProviderNameAsc()) {
            if (c.getRate().signum() <= 0) continue;
            String live = availability.remittance(c.getProvider());
            if (CrossBorderAvailability.blocks(live)) continue;   // pawaPay cannot pay this route right now: do not offer it
            int[] len = CrossBorderNumbers.length(c.getDialCode());
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", c.getId());
            o.put("country", c.getCountry());
            o.put("countryName", c.getCountryName());
            o.put("dialCode", c.getDialCode());
            o.put("provider", c.getProvider());
            o.put("providerName", c.getProviderName());
            o.put("currency", c.getCurrency());
            o.put("minSend", c.getMinSend());
            o.put("maxSend", c.getMaxSend());
            o.put("numberMin", len[0]);
            o.put("numberMax", len[1]);
            o.put("keepZero", CrossBorderNumbers.keepsZero(c.getDialCode()));
            o.put("rate", c.getRate().stripTrailingZeros().toPlainString());
            o.put("delayed", "DELAYED".equals(live));
            list.add(o);
        }
        m.put("corridors", list);
        m.put("purposes", PURPOSES);
        m.put("sources", SOURCES);
        users.findById(uid).ifPresent(u -> m.put("phone", u.getPhone()));
        submissions.findByUserIdAndStatus(uid, KycStatus.APPROVED).ifPresent(s -> {
            m.put("senderName", s.getFullNameOnDocument());
            // Nationality and address now come from the KYC form. Older approved customers who never gave them are asked once here.
            boolean has = notBlank(s.getNationality()) && notBlank(s.getAddressLine()) && notBlank(s.getCity());
            m.put("kycHasAddress", has);
            if (has) {
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("nationality", s.getNationality());
                k.put("addressLine", s.getAddressLine());
                k.put("city", s.getCity());
                k.put("postalCode", s.getPostalCode());
                m.put("kycProfile", k);
            }
        });
        m.put("profile", loadProfile(uid));
        return ResponseEntity.ok(m);
    }

    public record QuoteRequest(Long corridorId, BigDecimal amount) { }

    @PostMapping("/quote")
    public ResponseEntity<?> quote(@RequestBody QuoteRequest r) {
        if (!pricing.serviceOn()) return unavailable();
        try {
            CrossBorderCorridor c = corridors.findById(r.corridorId() == null ? -1L : r.corridorId()).orElse(null);
            CrossBorderPricing.Quote q = pricing.quote(c, r.amount());
            kycLimits.enforceGeneral(currentUserId(), q.amount().multiply(BigDecimal.valueOf(100)).longValue());   // the daily transaction limit
            return ResponseEntity.ok(quoteView(q));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ start
    public record StartRequest(UUID requestId, Long corridorId, BigDecimal amount, String payerPhone,
                               String recipientPhone, String recipientFirstName, String recipientLastName,
                               String nationality, String addressLine, String postalCode, String city,
                               String purposeOfFunds, String sourceOfFunds, BigDecimal expectedTotal) { }

    @PostMapping
    public ResponseEntity<?> start(@RequestBody StartRequest r) {
        if (!pricing.serviceOn()) return unavailable();
        UUID uid = currentUserId();
        try {
            if (r.requestId() == null) throw new IllegalArgumentException("Please try again.");
            if (!kycLimits.isVerified(uid)) throw new IllegalArgumentException("Verify your identity (KYC) first.");
            Optional<CrossBorderTransfer> same = transfers.findByUserIdAndClientRequestId(uid, r.requestId());
            if (same.isPresent()) return ResponseEntity.ok(userView(same.get()));   // a second tap: the same transfer

            User user = users.findById(uid).orElseThrow(() -> new IllegalArgumentException("Account not found."));
            if (!user.isActive() || user.isLocked()) throw new IllegalArgumentException("Your account cannot send money right now.");
            KycSubmission kyc = submissions.findByUserIdAndStatus(uid, KycStatus.APPROVED)
                    .orElseThrow(() -> new IllegalArgumentException("Verify your identity (KYC) first."));

            CrossBorderCorridor c = corridors.findById(r.corridorId() == null ? -1L : r.corridorId()).orElse(null);
            CrossBorderPricing.Quote q = pricing.quote(c, r.amount());
            if (r.expectedTotal() != null && r.expectedTotal().compareTo(q.total()) != 0) {
                throw new IllegalArgumentException("The price changed. Go back and check the new amount.");
            }
            if (CrossBorderAvailability.blocks(availability.remittance(c.getProvider()))
                    || CrossBorderAvailability.blocks(availability.deposit(PAYER_PROVIDER))) {
                throw new IllegalArgumentException("Sending to this country is not available right now. Please try again later.");
            }
            long open = transfers.countByUserIdAndStatusIn(uid, EnumSet.of(CrossBorderStatus.CREATED, CrossBorderStatus.COLLECTING,
                    CrossBorderStatus.COLLECTED, CrossBorderStatus.REMITTING, CrossBorderStatus.REFUNDING));
            if (open >= MAX_OPEN_PER_USER) throw new IllegalArgumentException("You have transfers still in progress. Wait for them to finish.");
            kycLimits.enforceGeneral(uid, r.amount().multiply(BigDecimal.valueOf(100)).longValue());

            String payer = payerMsisdn(r.payerPhone());
            String recipient = recipientMsisdn(c.getDialCode(), r.recipientPhone());
            boolean fromKyc = notBlank(kyc.getNationality()) && notBlank(kyc.getAddressLine()) && notBlank(kyc.getCity());
            String nat = fromKyc ? upper(kyc.getNationality()) : upper(r.nationality());
            if (!ISO3.matcher(nat).matches()) throw new IllegalArgumentException("Choose your nationality.");
            String line = fromKyc ? trim(kyc.getAddressLine()) : trim(r.addressLine());
            String city = fromKyc ? trim(kyc.getCity()) : trim(r.city());
            if (!TEXT.matcher(line).matches()) throw new IllegalArgumentException("Enter your home address.");
            if (!TEXT.matcher(city).matches()) throw new IllegalArgumentException("Enter your city or town.");
            String postal = fromKyc ? trim(kyc.getPostalCode()) : trim(r.postalCode());
            if (postal.isEmpty()) postal = "00000";   // Sierra Leone has no postal codes
            if (!Pattern.matches("^[A-Za-z0-9 -]{2,12}$", postal)) throw new IllegalArgumentException("The postal code is not valid.");

            String[] name = splitName(kyc.getFullNameOnDocument());
            ObjectNode sender = mapper.createObjectNode();
            sender.put("firstName", name[0]);
            sender.put("lastName", name[1]);
            sender.put("nationality", nat);
            sender.put("phoneNumber", payer);
            ObjectNode addr = sender.putObject("address");
            addr.put("addressLine", line);
            addr.put("postalCode", postal);
            addr.put("city", city);
            addr.put("country", "SLE");
            ObjectNode id = sender.putObject("identification");
            id.put("type", switch (kyc.getDocumentType()) {
                case NATIONAL_ID -> "NATIONAL_ID";
                case DRIVERS_LICENSE -> "DRIVING_LICENSE";
                case PASSPORT -> "PASSPORT";
            });
            id.put("number", crypto.decryptText(kyc.getDocumentNumberEnc(), kyc.getId() + ":NUMBER"));

            CrossBorderTransferService.StartCommand cmd = new CrossBorderTransferService.StartCommand(
                    uid, PAYER_PROVIDER, payer,
                    q.amount(), "SLE", q.fee(),
                    q.receiveAmount(), q.receiveCurrency(), q.rate(),
                    c.getCountry(), c.getProvider(), recipient,
                    trim(r.recipientFirstName()), trim(r.recipientLastName()), sender,
                    r.purposeOfFunds(), r.sourceOfFunds());
            CrossBorderTransfer t = service.start(cmd, r.requestId());
            if (!fromKyc) saveProfile(uid, nat, line, postal, city);   // only after the transfer exists
            return ResponseEntity.ok(userView(t));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (PawaPayUnavailableException e) {
            // the transfer is saved; the 30-second job continues it. Tell the app to look at its status.
            return ResponseEntity.status(202).body(Map.of("message", "We are connecting to the payment network. Check your transfer status in a moment."));
        }
    }

    // ------------------------------------------------------------------ saved recipients
    private static final Pattern PERSON_NAME = Pattern.compile("^[\\p{L} .'-]{1,64}$");
    private static final int MAX_RECIPIENTS = 100;

    public record RecipientRequest(Long corridorId, String phone, String firstName, String lastName) { }

    @GetMapping("/recipients")
    public ResponseEntity<?> listRecipients() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CrossBorderRecipient r : recipients.findByUserIdOrderByCreatedAtDesc(currentUserId())) out.add(recipientView(r));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/recipients")
    public ResponseEntity<?> saveRecipient(@RequestBody RecipientRequest r) {
        UUID uid = currentUserId();
        try {
            CrossBorderCorridor c = corridors.findById(r.corridorId() == null ? -1L : r.corridorId()).orElse(null);
            if (c == null || !c.isEnabled()) throw new IllegalArgumentException("Choose a country and a mobile money provider.");
            String first = trim(r.firstName());
            String last = trim(r.lastName());
            if (!PERSON_NAME.matcher(first).matches() || !PERSON_NAME.matcher(last).matches()) {
                throw new IllegalArgumentException("Enter the recipient's first name and last name.");
            }
            String msisdn = recipientMsisdn(c.getDialCode(), r.phone());
            CrossBorderRecipient rec = recipients.findByUserIdAndProviderAndPhone(uid, c.getProvider(), msisdn).orElse(null);
            if (rec == null) {
                if (recipients.countByUserId(uid) >= MAX_RECIPIENTS) throw new IllegalArgumentException("You have saved too many recipients. Remove one first.");
                rec = new CrossBorderRecipient();
                rec.setId(UUID.randomUUID());
                rec.setUserId(uid);
                rec.setCountry(c.getCountry());
                rec.setProvider(c.getProvider());
                rec.setPhone(msisdn);
                rec.setCreatedAt(Instant.now());
            }
            rec.setFirstName(first);
            rec.setLastName(last);
            return ResponseEntity.ok(recipientView(recipients.save(rec)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    public record RecipientEdit(String phone, String firstName, String lastName) { }

    @PutMapping("/recipients/{id}")
    public ResponseEntity<?> editRecipient(@PathVariable UUID id, @RequestBody RecipientEdit r) {
        UUID uid = currentUserId();
        try {
            CrossBorderRecipient rec = recipients.findById(id).filter(x -> x.getUserId().equals(uid))
                    .orElseThrow(() -> new IllegalArgumentException("Recipient not found."));
            CrossBorderCorridor c = corridors.findByCountryAndProvider(rec.getCountry(), rec.getProvider())
                    .orElseThrow(() -> new IllegalArgumentException("This destination is not available right now."));
            String first = trim(r.firstName());
            String last = trim(r.lastName());
            if (!PERSON_NAME.matcher(first).matches() || !PERSON_NAME.matcher(last).matches()) {
                throw new IllegalArgumentException("Enter the recipient's first name and last name.");
            }
            String msisdn = recipientMsisdn(c.getDialCode(), r.phone());
            if (!msisdn.equals(rec.getPhone())) {
                if (recipients.findByUserIdAndProviderAndPhone(uid, rec.getProvider(), msisdn).isPresent()) {
                    throw new IllegalArgumentException("You already saved this number.");
                }
                rec.setPhone(msisdn);
            }
            rec.setFirstName(first);
            rec.setLastName(last);
            return ResponseEntity.ok(recipientView(recipients.save(rec)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/recipients/{id}")
    public ResponseEntity<?> deleteRecipient(@PathVariable UUID id) {
        UUID uid = currentUserId();
        recipients.findById(id).filter(x -> x.getUserId().equals(uid)).ifPresent(recipients::delete);
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Object> recipientView(CrossBorderRecipient r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("country", r.getCountry());
        m.put("provider", r.getProvider());
        m.put("phone", r.getPhone());          // calling code + national digits
        m.put("firstName", r.getFirstName());
        m.put("lastName", r.getLastName());
        return m;
    }

    // ------------------------------------------------------------------ status
    @GetMapping("/{id}")
    public ResponseEntity<?> one(@PathVariable UUID id) {
        return service.find(id).filter(t -> t.getUserId().equals(currentUserId()))
                .<ResponseEntity<?>>map(t -> ResponseEntity.ok(userView(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<?> recent() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CrossBorderTransfer t : transfers.findByUserIdOrderByCreatedAtDesc(currentUserId(), PageRequest.of(0, 100))) {
            out.add(userView(t));
        }
        return ResponseEntity.ok(out);
    }

    /** Nudges one of the customer's own transfers (the app calls this while the screen is open). */
    @PostMapping("/{id}/refresh")
    public ResponseEntity<?> refresh(@PathVariable UUID id) {
        Optional<CrossBorderTransfer> t = service.find(id).filter(x -> x.getUserId().equals(currentUserId()));
        if (t.isEmpty()) return ResponseEntity.notFound().build();
        try {
            return ResponseEntity.ok(userView(service.advance(id)));
        } catch (PawaPayUnavailableException e) {
            return ResponseEntity.ok(userView(t.get()));
        }
    }

    // ------------------------------------------------------------------ helpers
    /** What the customer may see: no internal ids, no pawaPay wording. */
    private Map<String, Object> userView(CrossBorderTransfer t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("status", t.getStatus());
        m.put("amount", t.getSendAmount());
        m.put("fee", t.getFeeAmount());
        m.put("total", t.getSendAmount().add(t.getFeeAmount()));
        m.put("currency", t.getSendCurrency());
        m.put("receiveAmount", t.getReceiveAmount());
        m.put("receiveCurrency", t.getReceiveCurrency());
        m.put("destCountry", t.getDestCountry());
        m.put("recipientName", t.getRecipientFirstName() + " " + t.getRecipientLastName());
        m.put("recipientPhone", t.getRecipientPhone());
        m.put("createdAt", t.getCreatedAt());
        m.put("completedAt", t.getCompletedAt());
        return m;
    }

    private static Map<String, Object> quoteView(CrossBorderPricing.Quote q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amount", q.amount());
        m.put("fee", q.fee());
        m.put("total", q.total());
        m.put("rate", q.rate());
        m.put("receiveAmount", q.receiveAmount());
        m.put("receiveCurrency", q.receiveCurrency());
        return m;
    }

    private static ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body(Map.of("message", "Sending abroad is not available yet."));
    }

    private Map<String, Object> loadProfile(UUID uid) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!crypto.isConfigured()) return out;
        profiles.findById(uid).ifPresent(p -> {
            try {
                JsonNode n = mapper.readTree(crypto.decryptText(p.getDataEnc(), "xp:" + uid));
                out.put("nationality", n.path("nationality").asText(""));
                out.put("addressLine", n.path("addressLine").asText(""));
                out.put("postalCode", "00000".equals(n.path("postalCode").asText("")) ? "" : n.path("postalCode").asText(""));
                out.put("city", n.path("city").asText(""));
            } catch (Exception ignored) { }
        });
        return out;
    }

    private void saveProfile(UUID uid, String nat, String line, String postal, String city) {
        try {
            ObjectNode n = mapper.createObjectNode();
            n.put("nationality", nat);
            n.put("addressLine", line);
            n.put("postalCode", postal);
            n.put("city", city);
            CrossBorderSenderProfile p = profiles.findById(uid).orElseGet(CrossBorderSenderProfile::new);
            p.setUserId(uid);
            p.setDataEnc(crypto.encryptText(n.toString(), "xp:" + uid));
            p.setUpdatedAt(Instant.now());
            profiles.save(p);
        } catch (Exception ignored) {
            // remembering the address is a convenience; the transfer must not fail because of it
        }
    }

    /** Sierra Leone Orange number: accepts 076123456, 76123456, +23276123456 ... and returns 23276123456. */
    static String payerMsisdn(String raw) {
        String d = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        if (d.startsWith("232")) d = d.substring(3);
        if (d.startsWith("0")) d = d.substring(1);
        if (!d.matches("^[0-9]{8}$")) throw new IllegalArgumentException("Enter your Orange Money number (8 digits).");
        return "232" + d;
    }

    static String recipientMsisdn(String dial, String raw) {
        return CrossBorderNumbers.normalize(dial, raw);   // digits only, the exact length of that country, no leading 0
    }

    /** pawaPay needs a first and a last name; the KYC name is split at its last word. */
    static String[] splitName(String full) {
        String n = full == null ? "" : full.trim().replaceAll("\\s+", " ");
        int i = n.lastIndexOf(' ');
        if (i <= 0) throw new IllegalArgumentException("Your KYC name needs a first and a last name. Contact support.");
        return new String[]{n.substring(0, i), n.substring(i + 1)};
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String trim(String s) { return s == null ? "" : s.trim().replaceAll("\\s+", " "); }

    private static String upper(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT); }

    private UUID currentUserId() {
        return UUID.fromString((String) SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }
}
