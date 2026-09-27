package com.glr.deenwallet.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@Slf4j @RestController @RequestMapping("/api/auth") @RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    @PostMapping("/register") public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request){ authService.register(request); return ResponseEntity.ok().build(); }
    @PostMapping("/confirm-email") public ResponseEntity<Void> confirmEmail(@Valid @RequestBody ConfirmEmailRequest r){ authService.confirmEmail(r.email,r.code); return ResponseEntity.noContent().build(); }
    @PostMapping("/resend-otp") public ResponseEntity<Void> resendOtp(@Valid @RequestBody ResendOtpRequest r){ authService.resendOtp(r.email); return ResponseEntity.noContent().build(); }
    @PostMapping("/login") public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest r){ return ResponseEntity.ok(authService.login(r)); }
    @PostMapping("/refresh") public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest r){ return ResponseEntity.ok(authService.refreshToken(r.refreshToken)); }
    @PostMapping("/logout") public ResponseEntity<Void> logout(@RequestBody(required=false) RefreshTokenRequest r){ if(r!=null) authService.logout(r.refreshToken); return ResponseEntity.noContent().build(); }
    @Getter @Setter public static class ConfirmEmailRequest { @NotBlank @Email private String email; @NotBlank private String code; }
    @Getter @Setter public static class ResendOtpRequest { @NotBlank @Email private String email; }
    @Getter @Setter public static class RefreshTokenRequest { @NotBlank private String refreshToken; }
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor public static class LoginResponse { private String accessToken; private String refreshToken; private String firstName; private String accountNumber; private String role; }
}

