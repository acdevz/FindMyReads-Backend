package org.fmr.findmyreads.controllers;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.services.AuthService;
import org.fmr.findmyreads.utils.SecurityUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    // ── POST /api/auth/register ───────────────────────────────────────────────
    @PostMapping("/register")
    public ResponseEntity<AuthDto> register(
            @RequestBody @Valid RegisterRequest body,
            HttpServletResponse response
    ) {

        AuthService.TokenPairDto tokens = authService.register(
                body.email(),
                body.username(),
                body.password()
        );
        addTokenCookies(response, tokens.accessToken(), tokens.refreshToken());
        return ResponseEntity.status(HttpStatus.CREATED).body(
                new AuthDto(tokens.userId(), tokens.onboardingDone())
        );
    }

    // ── POST /api/auth/login ──────────────────────────────────────────────────
    @PostMapping("/login")
    public ResponseEntity<AuthDto> login(
            @RequestBody @Valid LoginRequest body,
            HttpServletResponse response
    ) {

        AuthService.TokenPairDto tokens = authService.login(body.email(), body.password());
        addTokenCookies(response, tokens.accessToken(), tokens.refreshToken());
        return ResponseEntity.ok(
                new AuthDto(tokens.userId(), tokens.onboardingDone())
        );
    }

    // ── POST /api/auth/refresh ────────────────────────────────────────────────
    /**
     * Exchange a valid refresh token for a new access + refresh token pair.
     * Old refresh token is invalidated (rotation).
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthDto> refresh(
            @RequestBody @Valid RefreshRequest body,
            HttpServletResponse response
    ) {

        AuthService.TokenPairDto tokens = authService.refresh(body.refreshToken());
        addTokenCookies(response, tokens.accessToken(), tokens.refreshToken());
        return ResponseEntity.ok(
                new AuthDto(tokens.userId(), tokens.onboardingDone())
        );
    }

    public static void addTokenCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        Cookie accessCookie = new Cookie("accessToken", accessToken);
        accessCookie.setHttpOnly(true);
        accessCookie.setSecure(true);
        accessCookie.setPath("/");
        accessCookie.setMaxAge(15 * 60);
        accessCookie.setAttribute("SameSite", "Strict");

        Cookie refreshCookie = new Cookie("refreshToken", refreshToken);
        refreshCookie.setHttpOnly(true);
        refreshCookie.setSecure(true);
        refreshCookie.setPath("/api/auth/refresh");
        refreshCookie.setMaxAge(7 * 24 * 60 * 60);
        refreshCookie.setAttribute("SameSite", "Strict");

        response.addCookie(accessCookie);
        response.addCookie(refreshCookie);
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

    public record AuthDto(
            UUID userId,
            boolean onboardingDone
    ) {}
}