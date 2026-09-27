package com.glr.deenwallet.auth;
import lombok.*;
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class LoginResponse { private String accessToken; private String refreshToken; private String firstName; private String accountNumber; private String role; }

