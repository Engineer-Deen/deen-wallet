package com.glr.deenwallet.transaction;

import java.util.List;
import java.util.Locale;

/**
 * Finds a locally supplied bank logo. Drop the file into
 * src/main/resources/static/assets/bank-logos/ named after the bank
 * ("Union Trust Bank" -> union-trust-bank.png). png, svg, webp, jpg and jpeg are accepted.
 */
public final class BankLogos {

    private static final List<String> EXTENSIONS = List.of("png", "svg", "webp", "jpg", "jpeg");

    private BankLogos() {
    }

    /** Must produce the same result as bankLogoSlug() in index.html. */
    public static String slug(String bankName) {
        if (bankName == null) {
            return "";
        }
        return bankName.toLowerCase(Locale.ROOT)
                .replaceAll("\\(.*?\\)", " ")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    /** Returns e.g. "/assets/bank-logos/rokel-commercial-bank.png", or null if no file exists. */
    public static String findLogoUrl(String bankName) {
        String slug = slug(bankName);
        if (slug.isEmpty()) {
            return null;
        }
        for (String ext : EXTENSIONS) {
            String file = slug + "." + ext;
            if (BankLogos.class.getResource("/static/assets/bank-logos/" + file) != null) {
                return "/assets/bank-logos/" + file;
            }
        }
        return null;
    }
}