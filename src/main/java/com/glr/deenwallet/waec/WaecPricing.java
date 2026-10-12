package com.glr.deenwallet.waec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Pattern;

/** Price, 2% service charge and purchase rules for WAEC Result Pins. All money is in minor units (1 SLE = 100). */
@Component
public class WaecPricing {

    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9._%+\\-]{1,64}@[A-Za-z0-9.\\-]{1,190}\\.[A-Za-z]{2,24}$");

    /** 35.00 SLE per pin. */
    @Value("${app.waec.unit-price-minor:3500}")
    private long unitPriceMinor;

    /** 2% service charge on the pin total. */
    @Value("${app.waec.fee-rate:0.02}")
    private BigDecimal feeRate;

    @Value("${app.waec.max-quantity:5}")
    private int configuredMaxQuantity;

    public record Quote(int quantity, long unitPriceMinor, long amountMinor, long feeMinor, long totalMinor) {}

    public int maxQuantity() {
        return Math.max(1, Math.min(configuredMaxQuantity, 20));
    }

    public int requireQuantity(Integer quantity) {
        if (quantity == null || quantity < 1) {
            throw new IllegalArgumentException("Choose how many pins you want to buy.");
        }
        if (quantity > maxQuantity()) {
            throw new IllegalArgumentException("You can buy at most " + maxQuantity() + " pins at a time.");
        }
        return quantity;
    }

    public Quote quote(int quantity) {
        long amount = Math.multiplyExact(unitPriceMinor, (long) quantity);
        long fee = BigDecimal.valueOf(amount).multiply(feeRate).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return new Quote(quantity, unitPriceMinor, amount, fee, amount + fee);
    }

    /** Uses the registered email unless the customer typed another one, which must look valid. */
    public String resolveDeliveryEmail(String requested, String registeredEmail) {
        String chosen = (requested == null || requested.isBlank()) ? registeredEmail : requested;
        if (chosen == null || chosen.isBlank()) {
            throw new IllegalArgumentException("An email address is required to receive your pin.");
        }
        chosen = chosen.trim().toLowerCase(Locale.ROOT);
        if (chosen.length() > 254 || !EMAIL.matcher(chosen).matches()) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        return chosen;
    }
}
