package com.glr.deenwallet.transaction;

import com.glr.deenwallet.monime.BankResult;
import com.glr.deenwallet.monime.MonimeBankListResponse;
import com.glr.deenwallet.monime.MonimeClient;
import com.glr.deenwallet.monime.ProviderKycResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankTransferService {

    private static final String SIERRA_LEONE = "SL";
    private static final Duration BANK_CACHE_TTL = Duration.ofMinutes(10);

    private final MonimeClient monimeClient;

    private volatile BankCache cache;
    private final Map<String, VerificationTicket> verificationTickets = new ConcurrentHashMap<>();
    private static final Duration VERIFICATION_TICKET_TTL = Duration.ofMinutes(5);

    /** Recently verified accounts, so re-checking the same account does not call Monime again. */
    private static final Duration VERIFIED_HOLDER_TTL = Duration.ofMinutes(10);
    private final Map<String, CachedHolder> verifiedHolders = new ConcurrentHashMap<>();

    private record CachedHolder(String holderName, Instant expiresAt) {}

    /**
     * Reads Monime's bank directory only when the cache is stale. The UI therefore
     * does not trigger a Monime read on every render or every keystroke.
     */
    public List<BankOptionResponse> listAvailableBanks() {
        List<BankResult> banks = getCachedBanks();
        return banks.stream()
                .map(bank -> new BankOptionResponse(
                        bank.providerId(),
                        bank.name(),
                        true,                          // available banks always get the name lookup
                        canReceiveTransfers(bank),     // active AND canPayTo -> selectable, else "Unavailable"
                        BankLogos.findLogoUrl(bank.name())))
                .toList();
    }

    public BankAccountVerificationResponse verifyAccount(UUID userId, String providerId, String accountNumber) {
        String normalizedProvider = normalizeProvider(providerId);
        String normalizedAccount = normalizeAccountNumber(accountNumber);
        BankResult bank = findAvailableBank(normalizedProvider);

        // Reject numbers that cannot be valid for this bank before spending a Monime call.
        BankAccountFormat.validate(bank.name(), normalizedAccount);

        String cacheKey = userId + "|" + normalizedProvider + "|" + normalizedAccount;
        String holderName = null;
        CachedHolder cached = verifiedHolders.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            holderName = cached.holderName();
        } else {
            ProviderKycResult kyc = lookupKyc(normalizedProvider, bank.name(), normalizedAccount);
            if (kyc == null || kyc.account() == null) {
                throw new IllegalArgumentException("The bank account could not be verified.");
            }
            holderName = kyc.account().holderName();
            if (holderName == null || holderName.isBlank()) {
                throw new IllegalArgumentException("The bank account was found, but no account holder name was returned.");
            }
            verifiedHolders.put(cacheKey, new CachedHolder(holderName.trim().replaceAll("\\s+", " "),
                    Instant.now().plus(VERIFIED_HOLDER_TTL)));
        }

        String cleanHolderName = holderName.trim().replaceAll("\\s+", " ");
        purgeExpiredTickets();
        String token = UUID.randomUUID().toString();
        verificationTickets.put(token, new VerificationTicket(
                userId, bank.providerId(), bank.name(), normalizedAccount, cleanHolderName, Instant.now().plus(VERIFICATION_TICKET_TTL)));

        return new BankAccountVerificationResponse(
                true,
                true,
                bank.providerId(),
                bank.name(),
                normalizedAccount,
                cleanHolderName,
                "Bank account verified successfully.",
                token
        );
    }

    /**
     * Calls Monime's KYC lookup once, with a short timeout, so the customer is never left
     * waiting on a slow bank. A slow or failing bank is reported as "unavailable, check
     * back later".
     *
     * Only 400/404/422 mean "this account number is not valid for this bank". Anything
     * else (401/403 bad credentials, 429 rate limit, 5xx, timeouts) is OUR or Monime's
     * problem and must not be reported to the customer as a wrong account number.
     */
    private ProviderKycResult lookupKyc(String providerId, String bankName, String accountNumber) {
        try {
            return monimeClient.getProviderKyc(providerId, accountNumber);
        } catch (HttpClientErrorException ex) {
            int status = ex.getStatusCode().value();
            if (status == 400 || status == 404 || status == 422) {
                log.warn("Monime KYC lookup rejected for provider={} status={}",
                        providerId, status);
                throw new IllegalArgumentException(
                        "The selected bank could not verify this account number. Check the account number and selected bank, then try again.");
            }
            log.error("Monime KYC lookup failed (not an account problem) provider={} status={}",
                    providerId, status);
            throw new BankKycUnavailableException(unavailableMessage(bankName), ex);
        } catch (RestClientResponseException | ResourceAccessException ex) {
            // 5xx from Monime (e.g. upstream_timeout) or our short timeout expiring.
            log.warn("Monime bank KYC unavailable for provider={} ({})",
                    providerId, ex.getMessage());
            throw new BankKycUnavailableException(unavailableMessage(bankName), ex);
        }
    }

    private void purgeExpiredTickets() {
        Instant now = Instant.now();
        verificationTickets.values().removeIf(t -> t.expiresAt().isBefore(now));
        verifiedHolders.values().removeIf(h -> h.expiresAt().isBefore(now));
    }

    public BankAccountVerificationResponse consumeVerification(UUID userId, String token, String providerId, String accountNumber) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Bank account verification is required.");
        }

        VerificationTicket ticket = verificationTickets.remove(token);
        if (ticket == null || ticket.expiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Bank account verification has expired. Please verify the account again.");
        }

        String normalizedAccount = normalizeAccountNumber(accountNumber);
        String normalizedProvider = normalizeProvider(providerId);
        if (!ticket.userId().equals(userId)
                || !ticket.providerId().equals(normalizedProvider)
                || !ticket.accountNumber().equals(normalizedAccount)) {
            throw new IllegalArgumentException("Bank account verification does not match the selected account.");
        }

        return new BankAccountVerificationResponse(
                true,
                true,
                ticket.providerId(),
                ticket.bankName(),
                ticket.accountNumber(),
                ticket.holderName(),
                "Bank account verification confirmed.",
                null
        );
    }

    /** Any bank Monime lists. Used by the name lookup, so every bank is checked the same way. */
    public BankResult findListedBank(String providerId) {
        String normalized = normalizeProvider(providerId);
        return getCachedBanks().stream()
                .filter(b -> normalized.equals(b.providerId()))
                .findFirst()
                .orElseThrow(() -> new BankKycUnavailableException(unavailableMessage(null)));
    }

    /**
     * The bank, only if Monime reports it as active AND able to receive payouts. Used by the
     * name lookup and when a transfer is confirmed, so a bank shown as "Unavailable" in the
     * picker is also refused here, with the friendly "check back later" message.
     */
    public BankResult findAvailableBank(String providerId) {
        BankResult bank = findListedBank(providerId);
        if (!canReceiveTransfers(bank)) {
            throw new BankKycUnavailableException(unavailableMessage(bank.name()));
        }
        return bank;
    }

    static String unavailableMessage(String bankName) {
        String who = (bankName == null || bankName.isBlank()) ? "This bank" : bankName;
        return who + " is currently unavailable. Please check back later or choose a different bank.";
    }

    public static String normalizeAccountNumber(String accountNumber) {
        String normalized = BankAccountFormat.normalize(accountNumber);
        if (!BankAccountFormat.isDigitsOnly(normalized) || normalized.length() > BankAccountFormat.MAX_DIGITS) {
            throw new IllegalArgumentException(
                    "Account numbers have " + BankAccountFormat.MIN_DIGITS + " to "
                            + BankAccountFormat.MAX_DIGITS + " digits (numbers only).");
        }
        return normalized;
    }

    private String normalizeProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Bank provider is required.");
        }
        return providerId.trim();
    }

    /** Available = Monime says the bank is active AND canPayTo. Name lookup support is not required. */
    private boolean canReceiveTransfers(BankResult bank) {
        return bank != null
                && bank.status() != null && bank.status().active()
                && bank.featureSet() != null
                && bank.featureSet().payout() != null
                && bank.featureSet().payout().canPayTo();
    }

    private boolean canVerify(BankResult bank) {
        return bank != null
                && bank.featureSet() != null
                && bank.featureSet().kycVerification() != null
                && bank.featureSet().kycVerification().canVerifyAccount();
    }

    private List<BankResult> getCachedBanks() {
        BankCache current = cache;
        if (current != null && current.isFresh()) {
            return current.banks();
        }

        synchronized (this) {
            current = cache;
            if (current != null && current.isFresh()) {
                return current.banks();
            }

            List<BankResult> loaded = new ArrayList<>();
            String after = null;
            do {
                MonimeBankListResponse page = monimeClient.listBanks(SIERRA_LEONE, after);
                if (page == null || !page.success()) {
                    throw new IllegalStateException("Could not load the bank list from Monime.");
                }
                if (page.result() != null) loaded.addAll(page.result());
                after = page.pagination() == null ? null : page.pagination().next();
            } while (after != null && !after.isBlank());

            // Keep EVERY Sierra Leone bank Monime lists. Availability is decided per bank
            // (canReceiveTransfers is checked later, when the customer confirms a transfer).
            List<BankResult> available = loaded.stream()
                    .filter(bank -> SIERRA_LEONE.equalsIgnoreCase(bank.country()))
                    .sorted(Comparator.comparing(BankResult::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();

            available.forEach(b -> log.info("Monime bank {} ({}): active={} canPayTo={} canVerifyAccount={}",
                    b.providerId(), b.name(),
                    b.status() != null && b.status().active(),
                    b.featureSet() != null && b.featureSet().payout() != null && b.featureSet().payout().canPayTo(),
                    canVerify(b)));
            cache = new BankCache(available, Instant.now());
            return available;
        }
    }

    private record VerificationTicket(
            UUID userId,
            String providerId,
            String bankName,
            String accountNumber,
            String holderName,
            Instant expiresAt
    ) {}

    private record BankCache(List<BankResult> banks, Instant loadedAt) {
        boolean isFresh() {
            return loadedAt.plus(BANK_CACHE_TTL).isAfter(Instant.now());
        }
    }
}