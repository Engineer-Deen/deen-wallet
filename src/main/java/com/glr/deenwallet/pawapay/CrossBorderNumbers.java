package com.glr.deenwallet.pawapay;

import java.util.Map;

/**
 * The exact mobile number every destination country accepts: the calling code is added by us, the customer types only the
 * national digits (no leading 0). {shortest, longest} = how many digits come AFTER the calling code.
 */
final class CrossBorderNumbers {

    private CrossBorderNumbers() { }

    private static final Map<String, int[]> RULES = Map.ofEntries(
            Map.entry("232", new int[]{8, 8}),    // Sierra Leone
            Map.entry("233", new int[]{9, 9}),    // Ghana
            Map.entry("234", new int[]{10, 10}),  // Nigeria
            Map.entry("231", new int[]{7, 9}),    // Liberia
            Map.entry("224", new int[]{9, 9}),    // Guinea
            Map.entry("221", new int[]{9, 9}),    // Senegal
            Map.entry("220", new int[]{7, 7}),    // Gambia
            Map.entry("223", new int[]{8, 8}),    // Mali
            Map.entry("225", new int[]{8, 10}),   // Cote d'Ivoire
            Map.entry("226", new int[]{8, 8}),    // Burkina Faso
            Map.entry("227", new int[]{8, 8}),    // Niger
            Map.entry("228", new int[]{8, 8}),    // Togo
            Map.entry("229", new int[]{8, 10}),   // Benin
            Map.entry("222", new int[]{8, 8}),    // Mauritania
            Map.entry("235", new int[]{8, 8}),    // Chad
            Map.entry("236", new int[]{8, 8}),    // Central African Republic
            Map.entry("237", new int[]{9, 9}),    // Cameroon
            Map.entry("238", new int[]{7, 7}),    // Cape Verde
            Map.entry("240", new int[]{9, 9}),    // Equatorial Guinea
            Map.entry("241", new int[]{7, 8}),    // Gabon
            Map.entry("242", new int[]{9, 9}),    // Congo
            Map.entry("243", new int[]{9, 9}),    // DR Congo
            Map.entry("244", new int[]{9, 9}),    // Angola
            Map.entry("245", new int[]{7, 7}),    // Guinea-Bissau
            Map.entry("248", new int[]{7, 7}),    // Seychelles
            Map.entry("249", new int[]{9, 9}),    // Sudan
            Map.entry("250", new int[]{9, 9}),    // Rwanda
            Map.entry("251", new int[]{9, 9}),    // Ethiopia
            Map.entry("252", new int[]{8, 9}),    // Somalia
            Map.entry("253", new int[]{8, 8}),    // Djibouti
            Map.entry("254", new int[]{9, 9}),    // Kenya
            Map.entry("255", new int[]{9, 9}),    // Tanzania
            Map.entry("256", new int[]{9, 9}),    // Uganda
            Map.entry("257", new int[]{8, 8}),    // Burundi
            Map.entry("258", new int[]{9, 9}),    // Mozambique
            Map.entry("260", new int[]{9, 9}),    // Zambia
            Map.entry("261", new int[]{9, 9}),    // Madagascar
            Map.entry("263", new int[]{9, 9}),    // Zimbabwe
            Map.entry("264", new int[]{9, 9}),    // Namibia
            Map.entry("265", new int[]{8, 9}),    // Malawi
            Map.entry("266", new int[]{8, 8}),    // Lesotho
            Map.entry("267", new int[]{7, 8}),    // Botswana
            Map.entry("268", new int[]{8, 8}),    // Eswatini
            Map.entry("230", new int[]{8, 8}),    // Mauritius
            Map.entry("212", new int[]{9, 9}),    // Morocco
            Map.entry("213", new int[]{9, 9}),    // Algeria
            Map.entry("216", new int[]{8, 8}),    // Tunisia
            Map.entry("20", new int[]{10, 10}),   // Egypt
            Map.entry("27", new int[]{9, 9}),     // South Africa
            Map.entry("961", new int[]{7, 8})     // Lebanon
    );

    /** Countries whose local numbers really start with 0 inside the international number (Cote d'Ivoire, Benin): the 0 is kept. */
    static boolean keepsZero(String dial) {
        return "225".equals(dial) || "229".equals(dial);
    }

    /** {shortest, longest} number of digits after the calling code. Unknown countries: a safe 7 to 12. */
    static int[] length(String dial) {
        int[] r = RULES.get(dial);
        return r != null ? r : new int[]{7, 12};
    }

    /** Digits only (+ spaces, brackets and dashes are ignored); no letters. Returns the full number: calling code + national digits. */
    static String normalize(String dial, String raw) {
        String in = raw == null ? "" : raw.trim();
        if (!in.matches("^[0-9 +()-]*$")) throw new IllegalArgumentException("The recipient number can only contain digits.");
        String d = in.replaceAll("[^0-9]", "");
        int[] len = length(dial);
        if (d.startsWith(dial) && d.length() > len[1]) d = d.substring(dial.length());   // typed with the calling code
        if (!keepsZero(dial)) while (d.startsWith("0")) d = d.substring(1);               // the leading 0 is never part of it
        if (d.length() < len[0] || d.length() > len[1]) {
            String need = len[0] == len[1] ? String.valueOf(len[0]) : len[0] + " to " + len[1];
            throw new IllegalArgumentException("A number for +" + dial + " has " + need + " digits after the code, without the leading 0.");
        }
        return dial + d;
    }
}
