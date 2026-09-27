package com.glr.deenwallet.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    List<DeviceToken> findByUserId(UUID userId);

    Optional<DeviceToken> findByFcmToken(String fcmToken);

    @Modifying
    @Query("DELETE FROM DeviceToken d WHERE d.fcmToken = :fcmToken")
    void deleteByFcmToken(@Param("fcmToken") String fcmToken);

    @Modifying
    @Query("DELETE FROM DeviceToken d WHERE d.userId = :userId AND d.fcmToken = :fcmToken")
    void deleteByUserIdAndFcmToken(@Param("userId") UUID userId, @Param("fcmToken") String fcmToken);
}
