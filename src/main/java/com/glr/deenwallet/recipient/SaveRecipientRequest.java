package com.glr.deenwallet.recipient;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SaveRecipientRequest {

    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    @NotBlank(message = "Provider is required")
    private String providerId;

    @Size(max = 100, message = "Holder name must be at most 100 characters")
    @Pattern(regexp = "^[^<>]*$", message = "Holder name cannot contain '<' or '>'")
    private String holderName;

    @Size(max = 50, message = "Label must be at most 50 characters")
    @Pattern(regexp = "^[^<>]*$", message = "Label cannot contain '<' or '>'")
    private String label;
}
