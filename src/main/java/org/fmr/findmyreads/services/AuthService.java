package org.fmr.findmyreads.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.models.RefreshToken;
import org.fmr.findmyreads.models.User;
import org.fmr.findmyreads.repositories.RefreshTokenRepository;
import org.fmr.findmyreads.repositories.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService             jwtService;
    private final PasswordEncoder        passwordEncoder;

    // ── Register ──────────────────────────────────────────────────────────────

    @Transactional
    public TokenPairDto register(String email, String username, String password) {
        if (userRepository.existsByEmail(email)) {
            throw new IllegalStateException("Email already registered: " + email);
        }
        if (userRepository.existsByUsername(username)) {
            throw new IllegalStateException("Username already taken: " + username);
        }

        User user = User.builder()
                .email(email.toLowerCase().strip())
                .username(username.strip())
                .passwordHash(passwordEncoder.encode(password))
                .emailVerified(false)
                .build();

        user = userRepository.save(user);
        log.info("New user registered: {}", user.getId());

        return issueTokenPair(user);
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Transactional
    public TokenPairDto login(String email, String password) {
        User user = userRepository.findByEmail(email.toLowerCase().strip())
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password."));

        if (user.getPasswordHash() == null) {
            throw new IllegalStateException(
                    "This account uses social login. Please sign in with " + user.getOauthProvider() + ".");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password.");
        }

        log.info("User logged in: {}", user.getId());
        return issueTokenPair(user);
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    /**
     * Validate the incoming refresh token, revoke it, issue a new pair.
     * Rotation: every refresh invalidates the old token and issues a new one.
     *
     * If a revoked token is presented — someone is replaying a stolen token.
     * We revoke the entire family (all sessions for that user) as a safety measure.
     */
    @Transactional
    public TokenPairDto refresh(String rawRefreshToken) {
        String hash = jwtService.hashRefreshToken(rawRefreshToken);

        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token."));

        if (stored.isRevoked()) {
            // token reuse detected — revoke all sessions for this user
            log.warn("Refresh token reuse detected for user {}. Revoking all sessions.",
                    stored.getUser().getId());
            refreshTokenRepository.revokeAllByUserId(stored.getUser().getId());
            throw new IllegalArgumentException("Refresh token already used. Please log in again.");
        }

        if (stored.isExpired()) {
            stored.setRevoked(true);
            refreshTokenRepository.save(stored);
            throw new IllegalArgumentException("Refresh token expired. Please log in again.");
        }

        // revoke old token
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        // issue new pair
        return issueTokenPair(stored.getUser());
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Transactional
    public void logout(UUID userId) {
        refreshTokenRepository.revokeAllByUserId(userId);
        log.info("User logged out, all sessions revoked: {}", userId);
    }

    // ── OAuth upsert ──────────────────────────────────────────────────────────

    /**
     * Called by OAuth2SuccessHandler after a successful OAuth2 callback.
     * Finds existing user by email or creates a new one.
     * Updates OAuth fields if provider changed or first OAuth login.
     */
    @Transactional
    public TokenPairDto loginOrRegisterOAuth(
            String email,
            String name,
            String avatarUrl,
            String provider,
            String oauthId) {

        User user = userRepository.findByEmail(email.toLowerCase().strip())
                .orElseGet(() -> {
                    String username = email.split("@")[0];

                    return User.builder()
                            .email(email.toLowerCase().strip())
                            .username(username)
                            .oauthProvider(provider)
                            .oauthId(oauthId)
                            .avatarUrl(avatarUrl)
                            .emailVerified(true)   // OAuth providers verify email
                            .build();
                });

        // update OAuth fields if this is first OAuth login for existing email user
        if (user.getOauthProvider() == null) {
            user.setOauthProvider(provider);
            user.setOauthId(oauthId);
            user.setEmailVerified(true);
        }
        if (avatarUrl != null && user.getAvatarUrl() == null) {
            user.setAvatarUrl(avatarUrl);
        }

        user = userRepository.save(user);
        log.info("OAuth login: provider={} userId={}", provider, user.getId());

        return issueTokenPair(user);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private TokenPairDto issueTokenPair(User user) {
        // access token
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail());

        // refresh token
        String rawRefresh = jwtService.generateRawRefreshToken();
        String hashRefresh = jwtService.hashRefreshToken(rawRefresh);

        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(hashRefresh)
                .expiresAt(jwtService.refreshTokenExpiry())
                .build();

        refreshTokenRepository.save(refreshToken);

        return new TokenPairDto(
                accessToken,
                rawRefresh,
                jwtService.getAccessTokenExpiryMs(),
                user.getId(),
                user.isOnboardingDone()
        );
    }

    private String ensureUniqueUsername(String base) {
        String candidate = base;
        int suffix = 1;
        while (userRepository.existsByUsername(candidate)) {
            candidate = base + suffix++;
        }
        return candidate;
    }

    // ── Token response record ─────────────────────────────────────────────────

    public record TokenPairDto(
            String accessToken,
            String refreshToken,
            long   expiresInMs,
            UUID   userId,
            boolean onboardingDone
    ) {}
}
