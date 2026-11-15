package com.glr.deenwallet.pawapay;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CrossBorderSenderProfileRepository extends JpaRepository<CrossBorderSenderProfile, UUID> {
}
