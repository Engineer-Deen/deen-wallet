package com.glr.deenwallet.recipient;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateRecipientRequest {

    /**
     * Optional label - can be null or empty string
     */
    @Size(max = 50, message = "Label must be at most 50 characters")
    @Pattern(regexp = "^[^<>]*$", message = "Label cannot contain '<' or '>'")
    private String label;

    /**
     * Optional - if provided, this will update the phone number too
     * Note: Changing phone number will trigger a new provider validation
     */
    private String phoneNumber;

    /**
     * Optional - if provided with phoneNumber, validates the provider
     */
    private String providerId;

    /**
     * Optional - if provided with phoneNumber, updates the holder name
     */
    @Size(max = 100, message = "Holder name must be at most 100 characters")
    @Pattern(regexp = "^[^<>]*$", message = "Holder name cannot contain '<' or '>'")
    private String holderName;
}
