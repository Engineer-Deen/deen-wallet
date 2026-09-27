package com.glr.deenwallet.otp;
import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param; import java.time.Instant; import java.util.*;
public interface EmailOtpRepository extends JpaRepository<EmailOtp,UUID>{ Optional<EmailOtp> findTopByEmailAndUsedFalseOrderByCreatedAtDesc(String email); long countByEmailAndCreatedAtAfter(String email,Instant after); }

