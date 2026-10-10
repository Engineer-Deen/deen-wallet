package com.glr.deenwallet.monime;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class MonimeClient {

    private final RestClient monimeRestClient;

    /** Name lookups should fail fast; a slow bank must not keep the customer waiting. */
    private static final int KYC_READ_TIMEOUT_SECONDS = 8;

    private volatile RestClient kycRestClient;

    /** Same base URL, auth headers and interceptors as the main client, but a shorter read timeout. */
    private RestClient kycClient() {
        RestClient client = kycRestClient;
        if (client == null) {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(Duration.ofSeconds(KYC_READ_TIMEOUT_SECONDS));
            client = monimeRestClient.mutate().requestFactory(factory).build();
            kycRestClient = client;
        }
        return client;
    }

    /**
     * Creates a one-time payment code so the sender can pay into
     * Monime via USSD. The returned result includes the USSD string
     * to show the user and the payment code's ID, which is saved on
     * the transaction so the later webhook can be matched back to it.
     */
    public PaymentCodeResult createPaymentCode(CreatePaymentCodeRequest request) {
        return createPaymentCode(request, java.util.UUID.randomUUID().toString());
    }

    /** Deterministic idempotency key (e.g. "paycode-{transactionId}") makes a retry after a timeout safe. */
    public PaymentCodeResult createPaymentCode(CreatePaymentCodeRequest request, String idempotencyKey) {
        MonimeEnvelope<PaymentCodeResult> response = monimeRestClient.post()
                .uri("/v1/payment-codes")
                .header("Idempotency-Key", idempotencyKey)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<MonimeEnvelope<PaymentCodeResult>>() {
                });

        return response != null ? response.result() : null;
    }

    /**
     Looks up the account holder's name for a given phone number and
     provider, used to show the sender who they're about to send
     money to before they confirm.
     */
    public ProviderKycResult getProviderKyc(String providerId, String accountId) {
        MonimeEnvelope<ProviderKycResult> response = kycClient().get()
                .uri("/v1/provider-kyc/{providerId}?accountId={accountId}", providerId, accountId)
                .retrieve()
                .body(new ParameterizedTypeReference<MonimeEnvelope<ProviderKycResult>>() {
                });

        return response != null ? response.result() : null;
    }

    /**
     * Authoritative payment-code state, used when a webhook may have been missed.
     * VERIFY against Monime's docs / a real call that GET /v1/payment-codes/{id} and the
     * status value "processed" match your account before go-live.
     */
    public PaymentCodeResult getPaymentCode(String paymentCodeId) {
        MonimeEnvelope<PaymentCodeResult> response = monimeRestClient.get()
                .uri("/v1/payment-codes/{id}", paymentCodeId)
                .retrieve()
                .body(new ParameterizedTypeReference<MonimeEnvelope<PaymentCodeResult>>() {});
        return response != null ? response.result() : null;
    }

    /** Retrieves the current payout state for reconciliation after a missed webhook. */
    public PayoutResult getPayout(String payoutId) {
        MonimeEnvelope<PayoutResult> response = monimeRestClient.get()
                .uri("/v1/payouts/{id}", payoutId)
                .retrieve()
                .body(new ParameterizedTypeReference<MonimeEnvelope<PayoutResult>>() {});
        return response != null ? response.result() : null;
    }

    /** Retrieves a page of supported banks for a country. */
    public MonimeBankListResponse listBanks(String country, String after) {
        return monimeRestClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/v1/banks")
                            .queryParam("country", country)
                            .queryParam("limit", 50);
                    if (after != null && !after.isBlank()) {
                        uriBuilder.queryParam("after", after);
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .body(MonimeBankListResponse.class);
    }

    /** Retrieves a single Monime bank object by provider ID. */
    public BankResult getBank(String providerId) {
        MonimeEnvelope<BankResult> response = monimeRestClient.get()
                .uri("/v1/banks/{providerId}", providerId)
                .retrieve()
                .body(new ParameterizedTypeReference<MonimeEnvelope<BankResult>>() {
                });
        return response != null ? response.result() : null;
    }

    /**
     * Sends the actual payout to the destination mobile money account,
     * once the sender's payment code has been paid.
     */
    public PayoutResult createPayout(CreatePayoutRequest request, String idempotencyKey) {
        MonimeEnvelope<PayoutResult> response = monimeRestClient.post()
                .uri("/v1/payouts")
                .header("Idempotency-Key", idempotencyKey)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<MonimeEnvelope<PayoutResult>>() {
                });

        return response != null ? response.result() : null;
    }
}