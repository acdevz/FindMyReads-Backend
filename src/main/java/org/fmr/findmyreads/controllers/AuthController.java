package org.fmr.findmyreads.controllers;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.services.AuthService;
import org.fmr.findmyreads.utils.SecurityUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // ── POST /api/auth/register ───────────────────────────────────────────────
    @PostMapping("/register")
    public ResponseEntity<AuthService.TokenPairDto> register(
            @RequestBody @Valid RegisterRequest body) {

        AuthService.TokenPairDto tokens = authService.register(
                body.email(),
                body.username(),
                body.password()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(tokens);
    }

    // ── POST /api/auth/login ──────────────────────────────────────────────────
    @PostMapping("/login")
    public ResponseEntity<AuthService.TokenPairDto> login(
            @RequestBody @Valid LoginRequest body) {

        AuthService.TokenPairDto tokens = authService.login(body.email(), body.password());
        return ResponseEntity.ok(tokens);
    }

    // ── POST /api/auth/refresh ────────────────────────────────────────────────
    /**
     * Exchange a valid refresh token for a new access + refresh token pair.
     * Old refresh token is invalidated (rotation).
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthService.TokenPairDto> refresh(
            @RequestBody @Valid RefreshRequest body) {

        AuthService.TokenPairDto tokens = authService.refresh(body.refreshToken());
        return ResponseEntity.ok(tokens);
    }

    // ── POST /api/auth/logout ─────────────────────────────────────────────────
    /**
     * Revoke all refresh tokens for the current user.
     * Access token remains valid until expiry (stateless — can't revoke JWT).
     * Client must discard the access token locally on logout.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        authService.logout(SecurityUtil.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    // ── Request records ───────────────────────────────────────────────────────

    public record RegisterRequest(
            @NotBlank @Email
            String email,

            @NotBlank @Size(min = 3, max = 30)
            @Pattern(regexp = "^[a-zA-Z0-9_]+$",
                    message = "Username may only contain letters, numbers, and underscores")
            String username,

            @NotBlank @Size(min = 8, max = 100)
            String password
    ) {}

    public record LoginRequest(
            @NotBlank @Email    String email,
            @NotBlank           String password
    ) {}

    public record RefreshRequest(
            @NotBlank String refreshToken
    ) {}
}