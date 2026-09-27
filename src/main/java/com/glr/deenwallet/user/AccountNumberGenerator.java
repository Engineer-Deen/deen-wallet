package com.glr.deenwallet.user;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class AccountNumberGenerator {

    private static final int LENGTH = 9;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;

    public AccountNumberGenerator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Generates a 9-digit account number and checks it against the
     * database, retrying if it happens to already be taken.
     */
    public String generate() {
        String candidate;
        do {
            candidate = randomDigits(LENGTH);
        } while (userRepository.existsByAccountNumber(candidate));
        return candidate;
    }

    private String randomDigits(int length) {
        StringBuilder builder = new StringBuilder(length);
        // First digit is never zero, so the account number always reads as a full-length number.
        builder.append(1 + RANDOM.nextInt(9));
        for (int i = 1; i < length; i++) {
            builder.append(RANDOM.nextInt(10));
        }
        return builder.toString();
    }
}
