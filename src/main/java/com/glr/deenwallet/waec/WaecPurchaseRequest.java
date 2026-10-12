package com.glr.deenwallet.waec;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WaecPurchaseRequest {

    @NotBlank(message = "Source provider is required")
    @Pattern(regexp = "m17|m18", message = "Only Orange Money or Africell is supported as the funding provider")
    private String sourceProviderId;

    @NotBlank(message = "Source phone number is required")
    @Pattern(regexp = "^\\+232\\d{8}$", message = "Phone must be in format +232XXXXXXXX")
    private String sourcePhone;

    @NotNull(message = "Choose how many pins you want to buy")
    @Min(value = 1, message = "Choose at least 1 pin")
    @Max(value = 20, message = "Too many pins in one purchase")
    private Integer quantity;

    /** Optional. Empty means "use my registered email". */
    @Size(max = 254, message = "Email is too long")
    private String deliveryEmail;

    /** Only EMAIL is available for now; phone delivery is coming soon. */
    @Size(max = 10)
    private String deliveryChannel;
}
