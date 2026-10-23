package com.glr.deenwallet.kyc;

/** The identity documents we accept. */
public enum KycDocumentType {
    NATIONAL_ID("National ID card", true, false),
    DRIVERS_LICENSE("Driver's licence", true, true),
    PASSPORT("Passport", false, true);

    private final String label;
    private final boolean needsBackPhoto;
    private final boolean needsExpiryDate;

    KycDocumentType(String label, boolean needsBackPhoto, boolean needsExpiryDate) {
        this.label = label;
        this.needsBackPhoto = needsBackPhoto;
        this.needsExpiryDate = needsExpiryDate;
    }

    public String label() { return label; }

    /** A passport has everything on its photo page; cards have details on both sides. */
    public boolean needsBackPhoto() { return needsBackPhoto; }

    /** National ID cards may not show an expiry date; the other two always do. */
    public boolean needsExpiryDate() { return needsExpiryDate; }
}
