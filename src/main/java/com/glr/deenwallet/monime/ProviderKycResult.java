package com.glr.deenwallet.monime;

public record ProviderKycResult(
        Account account,
        Provider provider
) {
    public record Account(String id, String name, String holderName) {
    }

    public record Provider(String id, String type, String name) {
    }
}
