package org.fmr.findmyreads.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.fmr.findmyreads.dtos.GenreScore;
import org.fmr.findmyreads.models.Genre;
import org.fmr.findmyreads.models.User;
import org.fmr.findmyreads.repositories.GenreRepository;
import org.fmr.findmyreads.repositories.ScanRepository;
import org.fmr.findmyreads.repositories.UserBookRepository;
import org.fmr.findmyreads.repositories.UserRepository;
import org.fmr.findmyreads.utils.SecurityUtil;
import org.fmr.findmyreads.utils.VectorMathUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final UserBookRepository userBookRepository;
    private final ScanRepository scanRepository;
    private final GenreRepository genreRepository;

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

        return ResponseEntity.ok(UserProfileDto.from(user,
                userBookRepository.countByUserId(userId),
                scanRepository.countByUserId(userId))
        );
    }

    // ── PATCH /api/me/deviation ───────────────────────────────────────────────
    /**
     * Update the user's deviation alpha knob.
     * 0.0 = pure taste match, 1.0 = full exploration.
     * Takes effect on the next scan immediately — no recompute needed.
     */
    @PatchMapping("/deviation")
    public ResponseEntity<Void> updateDeviation(
            @RequestBody @Valid DeviationUpdateRequest body,
            HttpServletRequest request) {

        UUID userId = SecurityUtil.getCurrentUserId();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        user.setDeviationAlpha(body.alpha());
        userRepository.save(user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/data/genre_scores")
    public List<GenreScore> computeTasteFingerprint() {
        UUID userId = SecurityUtil.getCurrentUserId();

        User user = userRepository.findById(userId).orElseThrow();
        if (user.getProfileVector() == null) return List.of();

        List<Genre> genres = genreRepository.findAll();

        return genres.stream()
                .filter(g -> g.getPrototypeVector() != null && g.getParentId() == null)
                .map(g -> new GenreScore(
                        g.getName(),
                        g.getSlug(),
                        VectorMathUtil.cosineSimilarity(
                                user.getProfileVector(),
                                g.getPrototypeVector()
                        )
                ))
                .sorted(Comparator.comparingDouble(GenreScore::score).reversed())
                .toList();
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
            int booksCount,
            int scansCount,
            float deviationAlpha,
            OffsetDateTime createdAt
    ) {

        public static UserProfileDto from(User user, int booksCount, int scansCount) {
            return new UserProfileDto(
                    user.getId(),
                    user.getEmail(),
                    user.getUsername(),
                    user.getAvatarUrl(),
                    user.isOnboardingDone(),
                    user.getProfileVector() != null,
                    user.getBooksRatedCount(),
                    booksCount,
                    scansCount,
                    user.getDeviationAlpha(),
                    user.getCreatedAt()
            );
        }
    }
}