package com.glr.deenwallet.pawapay;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** One route a customer can send to: a country + mobile money provider, with the price set by the admin. */
@Entity
@Table(name = "cross_border_corridors")
@Getter
@Setter
@NoArgsConstructor
public class CrossBorderCorridor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 3)
    private String country;

    @Column(name = "country_name", nullable = false, length = 60)
    private String countryName;

    @Column(name = "dial_code", nullable = false, length = 5)
    private String dialCode;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(name = "provider_name", nullable = false, length = 60)
    private String providerName;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "receive_decimals", nullable = false)
    private int receiveDecimals = 2;

    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal rate = BigDecimal.ZERO;

    @Column(name = "fee_percent", nullable = false, precision = 6, scale = 3)
    private BigDecimal feePercent = BigDecimal.ZERO;

    @Column(name = "fee_flat", nullable = false, precision = 20, scale = 2)
    private BigDecimal feeFlat = BigDecimal.ZERO;

    @Column(name = "min_send", nullable = false, precision = 20, scale = 2)
    private BigDecimal minSend = BigDecimal.TEN;

    @Column(name = "max_send", nullable = false, precision = 20, scale = 2)
    private BigDecimal maxSend = BigDecimal.valueOf(5000);

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by", length = 254)
    private String updatedBy;
}
