package com.glr.deenwallet.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final RateLimitFilter rateLimitFilter;

    @Value("${app.cors.allowed-origins:http://localhost:8081,http://127.0.0.1:8081,https://deenwallapp.com,https://api.deenwallapp.com}")
    private String origins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // Split by comma, trim whitespace, and strip trailing slashes
        // for clean Origin header matching.
        List<String> allowedList = Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(x -> !x.isBlank())
                .map(url -> url.endsWith("/") ? url.substring(0, url.length() - 1) : url)
                .toList();

        config.setAllowedOriginPatterns(allowedList);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Reason", "X-Recovery-Required", "Retry-After"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        .requestMatchers(
                                "/api/auth/biometric/registration-challenge",
                                "/api/auth/biometric/register",
                                "/api/auth/biometric/credentials/**"
                        ).hasRole("USER")

                        .requestMatchers(
                                "/",
                                "/welcome.html",
                                "/privacy.html",
                                "/terms.html",
                                "/index.html",
                                "/auth.html",
                                "/reset-password.html",
                                "/reset-pin.html",
                                "/admin.html",
                                "/transactions.html",
                                "/config.js",
                                "/deenwallet-client.js",
                                "/firebase-messaging-sw.js",
                                "/deenwallet-logo.png",
                                "/favicon.ico",
                                "/error",
                                "/assets/**",
                                "/downloads/**",
                                "/api/auth/**",
                                "/api/admin/auth/**",
                                "/api/v1/webhooks/monime",
                                "/api/errors",
                                "/api/app/version",
                                "/api/app/android-update",
                                "/api/providers/prefixes",
                                "/api/config/firebase-web",
                                "/api/auth/biometric/challenge",
                                "/api/auth/biometric/login",
                                "/icon-192.png"
                        ).permitAll()

                        // User-protected endpoints
                        .requestMatchers(
                                "/api/users/**",
                                "/api/transactions/**",
                                "/api/recipients/**",
                                "/api/accounts/**",
                                "/api/bank-transfers/**",
                                "/api/notifications/**"
                        ).hasRole("USER")

                        // Admin-protected endpoints
                        .requestMatchers(
                                "/api/admin/**",
                                "/api/support/**"
                        ).hasAnyRole("ADMIN", "SUPER_ADMIN")

                        .anyRequest().authenticated()
                )
                .addFilterBefore(
                        jwtAuthFilter,
                        UsernamePasswordAuthenticationFilter.class
                )
                .addFilterAfter(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.reportOnly().policyDirectives(
                                "default-src 'self'; script-src 'self' 'unsafe-inline' https://www.gstatic.com https://cdnjs.cloudflare.com; "
                                        + "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com data:; "
                                        + "img-src 'self' data: https:; connect-src 'self' https://api.deenwallapp.com https://*.googleapis.com https://*.firebaseio.com; "
                                        + "frame-ancestors 'none'; base-uri 'self'; form-action 'self'"))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .referrerPolicy(rp -> rp.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));

        return http.build();
    }
}
