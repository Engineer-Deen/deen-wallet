package com.glr.deenwallet.safety;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MoneySafetyRepository extends JpaRepository<MoneySafety, Short> {
}
