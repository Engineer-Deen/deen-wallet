package com.glr.deenwallet.config;

import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest req,
            @NonNull HttpServletResponse res,
            @NonNull FilterChain chain
    ) throws ServletException, IOException {

        String header = req.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);

            // 1. Verify token structure & expiration
            if (!jwtService.isAccessToken(token)) {
                sendErrorResponse(res, HttpServletResponse.SC_UNAUTHORIZED,
                        "Access token expired or invalid. Please refresh your session.");
                return;
            }

            try {
                String uid = jwtService.extractUserId(token);
                String role = jwtService.extractRole(token);

                if (role != null && !role.isBlank()) {
                    // 2. Real-time DB check for privileged Admin sessions
                    if ("ADMIN".equals(role) || "SUPER_ADMIN".equals(role)) {
                        User current = userRepository.findById(UUID.fromString(uid)).orElse(null);

                        if (current == null
                                || !role.equals(current.getRole())
                                || !current.isActive()
                                || current.isLocked()
                                || current.getPinAttempts() >= 2
                                || ("SUPER_ADMIN".equals(role) && current.isAdminRecoveryRequired())) {

                            SecurityContextHolder.clearContext();
                            sendErrorResponse(res, HttpServletResponse.SC_FORBIDDEN,
                                    "Admin session is no longer authorized. Please log in or complete recovery again.");
                            return;
                        }
                    }

                    // 3. Populate Spring Security Context
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            uid,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    );
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            } catch (IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
                sendErrorResponse(res, HttpServletResponse.SC_UNAUTHORIZED, "Invalid session token format.");
                return;
            } catch (Exception e) {
                log.error("Unexpected error validating session in JwtAuthFilter", e);
                SecurityContextHolder.clearContext();
                sendErrorResponse(res, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "Something went wrong verifying your session. Please try again.");
                return;
            }
        }

        chain.doFilter(req, res);
    }

    private void sendErrorResponse(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(String.format("{\"message\":\"%s\"}", message));
    }
}