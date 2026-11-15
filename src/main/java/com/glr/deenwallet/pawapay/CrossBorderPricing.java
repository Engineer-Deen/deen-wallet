package com.glr.deenwallet.pawapay;

import com.glr.deenwallet.settings.AppSetting;
import com.glr.deenwallet.settings.AppSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Prices a transfer from the admin's corridor settings. Every amount a customer sees AND every amount sent to pawaPay is
 * calculated here on the server - the app only displays it.
 *
 *   fee      = amount x fee% + flat fee            (rounded to 0.01 SLE)
 *   customer pays   = amount + fee                 (collected from the customer's Orange Money)
 *   recipient gets  = amount x rate                (rounded DOWN to the currency's decimals)
 */
@Service
@RequiredArgsConstructor
public class CrossBorderPricing {

    static final String SWITCH_KEY = "cross_border_enabled";

    private final AppSettingRepository settings;
    private final PawaPayProperties props;

    public record Quote(BigDecimal amount, BigDecimal fee, BigDecimal total, BigDecimal rate, BigDecimal receiveAmount, String receiveCurrency) { }

    /** The customer service is available only when pawaPay is configured on the server AND the admin switched it on. */
    public boolean serviceOn() {
        return props.enabled() && switchOn();
    }

    public boolean switchOn() {
        return settings.findById(SWITCH_KEY).map(s -> "true".equals(s.getValue())).orElse(false);
    }

    public void setSwitch(boolean on) {
        AppSetting s = settings.findById(SWITCH_KEY).orElseGet(() -> AppSetting.builder().key(SWITCH_KEY).build());
        s.setValue(on ? "true" : "false");
        s.setUpdatedAt(Instant.now());
        settings.save(s);
    }

    public Quote quote(CrossBorderCorridor c, BigDecimal amount) {
        if (c == null || !c.isEnabled() || c.getRate() == null || c.getRate().signum() <= 0) {
            throw new IllegalArgumentException("This destination is not available right now.");
        }
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2) {
            throw new IllegalArgumentException("Enter a valid amount (up to 2 decimals).");
        }
        if (amount.compareTo(c.getMinSend()) < 0) {
            throw new IllegalArgumentException("The minimum you can send to " + c.getCountryName() + " is " + plain(c.getMinSend()) + " SLE.");
        }
        if (amount.compareTo(c.getMaxSend()) > 0) {
            throw new IllegalArgumentException("The maximum you can send to " + c.getCountryName() + " is " + plain(c.getMaxSend()) + " SLE.");
        }
        BigDecimal fee = amount.multiply(c.getFeePercent()).divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP)
                .add(c.getFeeFlat()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal receive = amount.multiply(c.getRate()).setScale(c.getReceiveDecimals(), RoundingMode.DOWN);
        if (receive.signum() <= 0) throw new IllegalArgumentException("The amount is too small to send.");
        return new Quote(amount, fee, amount.add(fee), c.getRate().stripTrailingZeros(), receive, c.getCurrency());
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
