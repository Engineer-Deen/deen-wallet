package com.glr.deenwallet.monime;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class MonimeClient {

    private final RestClient monimeRestClient;

    /**
     * Creates a one-time payment code so the sender can pay into
     * Monime via USSD. The returned result includes the USSD string
     * to show the user and the payment code's ID, which is saved on
     * the transaction so the later webhook can be matched back to it.
     */
    public PaymentCodeResult createPaymentCode(CreatePaymentCodeRequest request) {
        MonimeEnvelope<PaymentCodeResult> response = monimeRestClient.post()
                .uri("/v1/payment-codes")
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
        MonimeEnvelope<ProviderKycResult> response = monimeRestClient.get()
                .uri("/v1/provider-kyc/{providerId}?accountId={accountId}", providerId, accountId)
                .retrieve()
                .body(new ParameterizedTypeReference<MonimeEnvelope<ProviderKycResult>>() {
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
