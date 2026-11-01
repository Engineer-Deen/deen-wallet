package com.glr.deenwallet.settings;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** The support WhatsApp number: saved in the database when an admin changes it, the configured default until then. */
@Service
@RequiredArgsConstructor
public class SupportContactService {

    private static final String KEY = "support_whatsapp";

    private final AppSettingRepository settings;

    @Value("${app.support.whatsapp:+23280613600}")
    private String defaultNumber;

    @Transactional(readOnly = true)
    public String getWhatsapp() {
        return settings.findById(KEY).map(AppSetting::getValue).filter(v -> !v.isBlank()).orElse(defaultNumber);
    }

    /** Returns the cleaned number that was saved (digits with a leading +). */
    @Transactional
    public String setWhatsapp(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        if (digits.length() < 8 || digits.length() > 15) {
            throw new IllegalArgumentException("Enter a valid WhatsApp number with the country code, for example +23280613600.");
        }
        String number = "+" + digits;
        AppSetting s = settings.findById(KEY).orElseGet(() -> AppSetting.builder().key(KEY).build());
        s.setValue(number);
        s.setUpdatedAt(Instant.now());
        settings.save(s);
        return number;
    }
}
