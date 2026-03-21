package org.fmr.findmyreads.services;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
public class JwtService {

    private final SecretKey  signingKey;
    @Getter
    private final long       accessTokenExpiryMs;
    private final long       refreshTokenExpiryMs = 30L * 24 * 60 * 60 * 1000;

    public JwtService(
            @Value("${jwt.secret}")     String secret,
            @Value("${jwt.expiry-ms}")  long expiryMs) {
        this.signingKey          = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiryMs = expiryMs;
    }

    // ── Access token ──────────────────────────────────────────────────────────

    /**
     * Issue a signed JWT access token.
     * Subject = userId (UUID string).
     * Expiry = jwt.expiry-ms from config (24h default).
     */
    public String generateAccessToken(UUID userId, String email) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpiryMs);

        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /**
     * Validate and parse a JWT access token.
     * Returns the Claims (payload) on success.
     * Throws JwtException subtypes on failure — caught in JwtFilter.
     */
    public Claims validateAndParse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    // ── Refresh token ─────────────────────────────────────────────────────────

    /**
     * Generate a cryptographically random refresh token.
     * Returns the raw token — this is what gets sent to the client.
     * The hash is stored in DB, never the raw value.
     */
    public String generateRawRefreshToken() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 hash of a raw refresh token.
     * Store this in refresh_tokens.token_hash.
     */
    public String hashRefreshToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public OffsetDateTime refreshTokenExpiry() {
        return OffsetDateTime.now().plusNanos(refreshTokenExpiryMs * 1_000_000L);
    }
}