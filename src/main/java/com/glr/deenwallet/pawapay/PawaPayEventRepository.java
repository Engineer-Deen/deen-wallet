package com.glr.deenwallet.pawapay;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PawaPayEventRepository extends JpaRepository<PawaPayEvent, UUID> {
    boolean existsByEventKey(String eventKey);
}
