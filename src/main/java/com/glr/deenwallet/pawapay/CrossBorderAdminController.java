package com.glr.deenwallet.pawapay;

import com.fasterxml.jackson.databind.JsonNode;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Super admin: switch "Send abroad" on/off, set the rate / fees / limits of every route, and watch the transfers.
 * (/api/admin/** is already limited to ADMIN / SUPER_ADMIN; changing prices is limited to SUPER_ADMIN here.)
 */
@RestController
@RequestMapping("/api/admin/cross-border")
@RequiredArgsConstructor
public class CrossBorderAdminController {

    private static final Pattern ISO3 = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern PROVIDER = Pattern.compile("^[A-Z0-9_]{3,40}$");
    private static final Pattern DIAL = Pattern.compile("^[1-9][0-9]{0,4}$");

    private final CrossBorderPricing pricing;
    private final CrossBorderCorridorRepository corridors;
    private final CrossBorderTransferRepository transfers;
    private final CrossBorderTransferService service;
    private final PawaPayProperties props;
    private final PawaPayClient client;
    private final UserRepository users;
    private final CrossBorderAvailability availability;

    @GetMapping("/overview")
    public ResponseEntity<?> overview() {
        superAdmin();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pawapayConfigured", props.enabled());
        m.put("sandbox", props.sandbox());
        m.put("switchOn", pricing.switchOn());
        m.put("live", pricing.serviceOn());
        List<Map<String, Object>> cs = new ArrayList<>();
        for (CrossBorderCorridor c : corridors.findAllByOrderBySortOrderAscCountryNameAscProviderNameAsc()) {
            Map<String, Object> v = corridorView(c);
            v.put("live", availability.remittance(c.getProvider()));   // what pawaPay says right now
            int[] len = CrossBorderNumbers.length(c.getDialCode());
            v.put("numberMin", len[0]);
            v.put("numberMax", len[1]);
            cs.add(v);
        }
        m.put("corridors", cs);
        List<Map<String, Object>> ts = new ArrayList<>();
        for (CrossBorderTransfer t : transfers.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 40))) ts.add(transferView(t));
        m.put("transfers", ts);
        return ResponseEntity.ok(m);
    }

    /** Find transfers by transaction ID (XB-...), the full id, recipient name or number, or the customer's Orange number. */
    @GetMapping("/transfers")
    public ResponseEntity<?> search(@RequestParam(defaultValue = "") String q) {
        superAdmin();
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        List<Map<String, Object>> out = new ArrayList<>();
        for (CrossBorderTransfer t : transfers.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 1000))) {
            if (!needle.isEmpty()) {
                String hay = (CrossBorderTransferService.reference(t) + " " + t.getId() + " " + t.getRecipientFirstName() + t.getRecipientLastName()
                        + " " + t.getRecipientPhone() + " " + t.getPayerPhone() + " " + t.getDestProvider() + " " + t.getStatus())
                        .toLowerCase(Locale.ROOT).replace(" ", "");
                if (!hay.contains(needle)) continue;
            }
            out.add(transferView(t));
            if (out.size() >= 60) break;
        }
        return ResponseEntity.ok(out);
    }

    public record SwitchRequest(boolean on) { }

    @PostMapping("/switch")
    public ResponseEntity<?> toggle(@RequestBody SwitchRequest r) {
        superAdmin();
        if (r.on() && !props.enabled()) {
            return ResponseEntity.badRequest().body(Map.of("message", "pawaPay is not switched on in the server settings (PAWAPAY_ENABLED)."));
        }
        pricing.setSwitch(r.on());
        return ResponseEntity.ok(Map.of("switchOn", pricing.switchOn(), "live", pricing.serviceOn()));
    }

    public record CorridorRequest(Long id, String country, String countryName, String dialCode, String provider, String providerName,
                                  String currency, Integer receiveDecimals, BigDecimal rate, BigDecimal feePercent, BigDecimal feeFlat,
                                  BigDecimal minSend, BigDecimal maxSend, Boolean enabled, Integer sortOrder) { }

    @PostMapping("/corridors")
    public ResponseEntity<?> save(@RequestBody CorridorRequest r) {
        User me = superAdmin();
        try {
            String country = up(r.country());
            String provider = up(r.provider());
            require(ISO3.matcher(country).matches(), "The country code must be 3 letters, for example GHA.");
            require(PROVIDER.matcher(provider).matches(), "The provider code looks like MTN_MOMO_GHA.");
            require(ISO3.matcher(up(r.currency())).matches(), "The currency code must be 3 letters, for example GHS.");
            require(r.dialCode() != null && DIAL.matcher(r.dialCode().trim()).matches(), "Enter the country calling code, for example 233.");
            require(r.countryName() != null && !r.countryName().isBlank() && r.countryName().trim().length() <= 60, "Enter the country name.");
            require(r.providerName() != null && !r.providerName().isBlank() && r.providerName().trim().length() <= 60, "Enter the provider name.");
            int dec = r.receiveDecimals() == null ? 2 : r.receiveDecimals();
            require(dec >= 0 && dec <= 3, "Decimals must be 0 to 3.");
            BigDecimal rate = r.rate() == null ? BigDecimal.ZERO : r.rate();
            require(rate.signum() >= 0 && rate.compareTo(new BigDecimal("1000000000")) < 0, "The rate is not valid.");
            BigDecimal pct = r.feePercent() == null ? BigDecimal.ZERO : r.feePercent();
            require(pct.signum() >= 0 && pct.compareTo(BigDecimal.valueOf(30)) <= 0, "The percentage fee must be between 0 and 30.");
            BigDecimal flat = r.feeFlat() == null ? BigDecimal.ZERO : r.feeFlat();
            require(flat.signum() >= 0 && flat.scale() <= 2 && flat.compareTo(BigDecimal.valueOf(100000)) <= 0, "The flat fee is not valid.");
            BigDecimal min = r.minSend() == null ? BigDecimal.TEN : r.minSend();
            BigDecimal max = r.maxSend() == null ? BigDecimal.valueOf(5000) : r.maxSend();
            require(min.signum() > 0 && min.scale() <= 2 && max.scale() <= 2 && max.compareTo(min) >= 0, "Check the minimum and maximum amounts.");
            boolean enabled = Boolean.TRUE.equals(r.enabled());
            require(!enabled || rate.signum() > 0, "Set an exchange rate before turning this route on.");

            CrossBorderCorridor c = r.id() == null ? new CrossBorderCorridor() : corridors.findById(r.id()).orElseThrow(() -> new IllegalArgumentException("Route not found."));
            Optional<CrossBorderCorridor> clash = corridors.findByCountryAndProvider(country, provider);
            if (clash.isPresent() && !clash.get().getId().equals(c.getId())) throw new IllegalArgumentException("That country and provider already exist.");
            c.setCountry(country);
            c.setCountryName(r.countryName().trim());
            c.setDialCode(r.dialCode().trim());
            c.setProvider(provider);
            c.setProviderName(r.providerName().trim());
            c.setCurrency(up(r.currency()));
            c.setReceiveDecimals(dec);
            c.setRate(rate);
            c.setFeePercent(pct);
            c.setFeeFlat(flat);
            c.setMinSend(min);
            c.setMaxSend(max);
            c.setEnabled(enabled);
            c.setSortOrder(r.sortOrder() == null ? 0 : r.sortOrder());
            c.setUpdatedAt(Instant.now());
            c.setUpdatedBy(me.getEmail());
            return ResponseEntity.ok(corridorView(corridors.save(c)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/corridors/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        superAdmin();
        corridors.deleteById(id);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    /** Routes pawaPay says are open for this account (best effort - you can always type a route by hand). */
    @GetMapping("/catalog")
    public ResponseEntity<?> catalog() {
        superAdmin();
        if (!props.enabled()) return ResponseEntity.badRequest().body(Map.of("message", "pawaPay is not switched on in the server settings."));
        try {
            PawaPayResponse r = client.activeConfiguration("REMITTANCE");   // only what pawaPay really lets us send to
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode c : r.json().path("countries")) {
                for (JsonNode p : c.path("providers")) {
                    for (JsonNode cur : p.path("currencies")) {
                        JsonNode ops = cur.path("operationTypes");
                        if (ops.isObject() && !ops.has("REMITTANCE")) continue;
                        Map<String, Object> o = new LinkedHashMap<>();
                        o.put("country", c.path("country").asText());
                        JsonNode dn = c.path("displayName");
                        o.put("countryName", dn.isObject() ? dn.path("en").asText(c.path("country").asText()) : dn.asText(c.path("country").asText()));
                        o.put("dialCode", c.path("prefix").asText(""));
                        o.put("provider", p.path("provider").asText());
                        o.put("providerName", p.path("displayName").asText(p.path("provider").asText()));
                        o.put("currency", cur.path("currency").asText());
                        JsonNode rem = ops.path("REMITTANCE");
                        String dec = rem.path("decimalsInAmount").asText("");
                        o.put("decimals", dec.equals("NONE") ? 0 : dec.equals("ONE_PLACE") ? 1 : dec.equals("THREE_PLACES") ? 3 : 2);
                        o.put("live", availability.remittance(p.path("provider").asText()));
                        int[] len = CrossBorderNumbers.length(c.path("prefix").asText(""));
                        o.put("numberMin", len[0]);
                        o.put("numberMax", len[1]);
                        out.add(o);
                    }
                }
            }
            return ResponseEntity.ok(out);
        } catch (PawaPayUnavailableException e) {
            return ResponseEntity.status(503).body(Map.of("message", "pawaPay could not be reached."));
        }
    }

    @PostMapping("/transfers/{id}/check")
    public ResponseEntity<?> check(@PathVariable UUID id) {
        superAdmin();
        if (!props.enabled()) return ResponseEntity.badRequest().body(Map.of("message", "pawaPay is off."));
        try {
            return ResponseEntity.ok(transferView(service.advance(id)));
        } catch (PawaPayUnavailableException e) {
            return ResponseEntity.status(503).body(Map.of("message", "pawaPay could not be reached."));
        }
    }

    // ------------------------------------------------------------------ views
    private static Map<String, Object> corridorView(CrossBorderCorridor c) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", c.getId());
        o.put("country", c.getCountry());
        o.put("countryName", c.getCountryName());
        o.put("dialCode", c.getDialCode());
        o.put("provider", c.getProvider());
        o.put("providerName", c.getProviderName());
        o.put("currency", c.getCurrency());
        o.put("receiveDecimals", c.getReceiveDecimals());
        o.put("rate", c.getRate().stripTrailingZeros().toPlainString());
        o.put("feePercent", c.getFeePercent().stripTrailingZeros().toPlainString());
        o.put("feeFlat", c.getFeeFlat());
        o.put("minSend", c.getMinSend());
        o.put("maxSend", c.getMaxSend());
        o.put("enabled", c.isEnabled());
        o.put("sortOrder", c.getSortOrder());
        o.put("updatedAt", c.getUpdatedAt());
        o.put("updatedBy", c.getUpdatedBy());
        return o;
    }

    private Map<String, Object> transferView(CrossBorderTransfer t) {
        Map<String, Object> m = new LinkedHashMap<>(service.view(t));
        m.put("recipientName", t.getRecipientFirstName() + " " + t.getRecipientLastName());
        m.put("recipientPhone", t.getRecipientPhone());
        m.put("destProvider", t.getDestProvider());
        m.put("payerPhone", t.getPayerPhone());
        m.put("fxRate", t.getFxRate());
        m.put("total", t.getSendAmount().add(t.getFeeAmount()));
        m.put("customer", users.findById(t.getUserId()).map(u -> u.getFullName() + " · " + u.getPhone()).orElse(null));
        return m;
    }

    private User superAdmin() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated()) throw new SecurityException("Not authenticated");
        User u = users.findById(UUID.fromString((String) a.getPrincipal())).orElseThrow(() -> new SecurityException("User not found"));
        if (!"SUPER_ADMIN".equals(u.getRole()) || !u.isActive()) throw new SecurityException("Only a super admin can do this.");
        return u;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    private static String up(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }
}
