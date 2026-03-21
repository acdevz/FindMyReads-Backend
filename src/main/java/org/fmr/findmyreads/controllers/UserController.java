package org.fmr.findmyreads.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.fmr.findmyreads.models.User;
import org.fmr.findmyreads.repositories.UserRepository;
import org.fmr.findmyreads.utils.SecurityUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    // ── GET /api/me ───────────────────────────────────────────────────────────
    /**
     * Returns the current user's profile — used by the frontend to:
     *   - Check onboarding_done and redirect to wizard if false
     *   - Display current deviation alpha knob value
     *   - Show whether a taste profile exists yet
     */
    @GetMapping
    public ResponseEntity<UserProfileDto> getProfile(HttpServletRequest request) {
        UUID userId = SecurityUtil.getCurrentUserId();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        return ResponseEntity.ok(UserProfileDto.from(user));
    }

    // ── PATCH /api/me/deviation ───────────────────────────────────────────────
    /**
     * Update the user's deviation alpha knob.
     * 0.0 = pure taste match, 1.0 = full exploration.
     * Takes effect on the next scan immediately — no recompute needed.
     */
    @PatchMapping("/deviation")
    public ResponseEntity<UserProfileDto> updateDeviation(
            @RequestBody @Valid DeviationUpdateRequest body,
            HttpServletRequest request) {

        UUID userId = SecurityUtil.getCurrentUserId();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        user.setDeviationAlpha(body.alpha());
        userRepository.save(user);

        return ResponseEntity.ok(UserProfileDto.from(user));
    }

    // ── Request / Response records ────────────────────────────────────────────

    public record DeviationUpdateRequest(
            @NotNull
            @DecimalMin("0.0") @DecimalMax("1.0")
            float alpha
    ) {}

    public record UserProfileDto(
            UUID id,
            String email,
            String username,
            String avatarUrl,
            boolean onboardingDone,
            boolean hasProfileVector,
            int booksRatedCount,
            float deviationAlpha,
            OffsetDateTime createdAt
    ) {
        public static UserProfileDto from(User user) {
            return new UserProfileDto(
                    user.getId(),
                    user.getEmail(),
                    user.getUsername(),
                    user.getAvatarUrl(),
                    user.isOnboardingDone(),
                    user.getProfileVector() != null,
                    user.getBooksRatedCount(),
                    user.getDeviationAlpha(),
                    user.getCreatedAt()
            );
        }
    }
}