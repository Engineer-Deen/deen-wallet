package com.glr.deenwallet.pawapay;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrossBorderCorridorRepository extends JpaRepository<CrossBorderCorridor, Long> {

    List<CrossBorderCorridor> findAllByOrderBySortOrderAscCountryNameAscProviderNameAsc();

    List<CrossBorderCorridor> findByEnabledTrueOrderBySortOrderAscCountryNameAscProviderNameAsc();

    Optional<CrossBorderCorridor> findByCountryAndProvider(String country, String provider);
}
