package org.fmr.findmyreads.controllers;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.models.Genre;
import org.fmr.findmyreads.services.OnboardingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/genres")
@RequiredArgsConstructor
public class GenreController {

    private final OnboardingService onboardingService;

    // ── GET /api/genres ────────────────────────────────────────────
    /**
     * List all available genres for the onboarding wizard UI.
     * Frontend renders these as selectable tiles with a weight slider each.
     */
    @GetMapping()
    public ResponseEntity<List<GenreController.GenreDto>> getGenres() {
        List<GenreController.GenreDto> genres = onboardingService.getAvailableGenres()
                .stream()
                .map(GenreController.GenreDto::from)
                .toList();
        return ResponseEntity.ok(genres);
    }

    public record GenreDto(
            UUID id,
            UUID parentId,
            String name,
            String slug,
            String description,
            boolean hasPrototypeVector
    ) {
        public static GenreDto from(Genre genre) {
            return new GenreDto(
                    genre.getId(),
                    genre.getParentId(),
                    genre.getName(),
                    genre.getSlug(),
                    genre.getDescription(),
                    genre.getPrototypeVector() != null
            );
        }
    }
}
