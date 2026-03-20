package org.fmr.findmyreads.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.services.OnboardingService;
import org.fmr.findmyreads.utils.SecurityUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    // ── POST /api/onboarding/complete ─────────────────────────────────────────
    /**
     * Submit genre preferences and complete onboarding.
     * Seeds the user's profile_vector from the selected genre prototype vectors.
     * Sets onboarding_done = true on the user record.
     *
     * Request body:
     * {
     *   "preferences": {
     *     "<genreId>": 5,
     *     "<genreId>": 3
     *   }
     * }
     */
    @PostMapping("/complete")
    public ResponseEntity<OnboardingResultDto> complete(
            @RequestBody @Valid CompleteOnboardingRequest body,
            HttpServletRequest request) {

        UUID userId = SecurityUtil.getCurrentUserId(request);

        // convert string keys to UUID keys
        Map<UUID, Integer> preferences = body.preferences().entrySet().stream()
                .collect(Collectors.toMap(
                        e -> UUID.fromString(e.getKey()),
                        Map.Entry::getValue
                ));

        onboardingService.completeOnboarding(userId, preferences);

        log.info("Onboarding completed for user={}", userId);
        return ResponseEntity.ok(new OnboardingResultDto(
                true,
                "Onboarding complete. Your taste profile has been seeded."
        ));
    }

    // ── Request / Response records ────────────────────────────────────────────

    public record CompleteOnboardingRequest(
            @NotNull @Size(min = 1, max = 20)
            Map<String, @Min(1) @Max(5) Integer> preferences
    ) {}

    public record OnboardingResultDto(boolean success, String message) {}
}