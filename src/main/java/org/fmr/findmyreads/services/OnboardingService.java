package org.fmr.findmyreads.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.models.Genre;
import org.fmr.findmyreads.models.User;
import org.fmr.findmyreads.models.UserGenrePreference;
import org.fmr.findmyreads.repositories.GenreRepository;
import org.fmr.findmyreads.repositories.UserGenrePreferenceRepository;
import org.fmr.findmyreads.repositories.UserRepository;
import org.fmr.findmyreads.utils.VectorMathUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles the cold-start onboarding flow:
 *
 *   1. User selects genres + weights in the wizard
 *   2. We compute a weighted average of those genres' prototype vectors
 *   3. That becomes the user's initial profile_vector
 *   4. onboarding_done = true — user proceeds to the main app
 *
 * Once real book ratings accumulate, the centroid in UserBookService
 * supersedes this initial seed naturally.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final UserRepository userRepository;
    private final GenreRepository genreRepository;
    private final UserGenrePreferenceRepository preferenceRepository;

    /**
     * Fetch all available genres for the onboarding UI.
     * Returns only genres that have a prototype_vector — selecting a genre
     * without a vector contributes nothing to the seed.
     *
     * Note: initially after deployment no genres have prototype vectors.
     * We return all genres anyway so the UI isn't empty. The seed step
     * gracefully handles missing vectors.
     */
    public List<Genre> getAvailableGenres() {
        return genreRepository.findAll();
    }

    /**
     * Save the user's genre preferences and seed their profile vector.
     *
     * @param userId      the user completing onboarding
     * @param preferences map of genreId → weight (1–5)
     */
    @Transactional
    public void completeOnboarding(UUID userId, Map<UUID, Integer> preferences) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        if (preferences == null || preferences.isEmpty()) {
            markOnboardingDone(userId);
            return;
        }

        // clear any previous preferences (re-onboarding scenario)
        preferenceRepository.deleteByUserId(userId);

        // persist new preferences
        List<UserGenrePreference> prefs = preferences.entrySet().stream()
                .map(entry -> {
                    Genre genre = genreRepository.findById(entry.getKey())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Genre not found: " + entry.getKey()));

                    return UserGenrePreference.builder()
                            .user(user)
                            .genre(genre)
                            .weight(entry.getValue().shortValue())
                            .build();
                })
                .toList();

        preferenceRepository.saveAll(prefs);

        seedProfileVector(user, userId);

        markOnboardingDone(userId);
        log.info("Onboarding complete for user {}", userId);
    }

    // -------------------------------------------------------------------------

    private void seedProfileVector(User user, UUID userId) {
        // fetch prefs with genre eagerly loaded, null prototype vectors filtered in Java
        List<UserGenrePreference> prefsWithVector =
                preferenceRepository.findByUserIdWithGenre(userId)
                        .stream()
                        .filter(p -> p.getGenre().getPrototypeVector() != null)
                        .toList();

        if (prefsWithVector.isEmpty()) {
            log.warn("No genre prototype vectors available for seeding user {}. " +
                    "Profile vector will remain null until first book is rated.", userId);
            return;
        }

        float[][] vectors = prefsWithVector.stream()
                .map(p -> p.getGenre().getPrototypeVector())
                .toArray(float[][]::new);

        int[] weights = prefsWithVector.stream()
                .mapToInt(UserGenrePreference::getWeight)
                .toArray();

        float[] seedVector = VectorMathUtil.weightedAverage(vectors, weights);

        if (seedVector != null) {
            userRepository.updateProfileVector(
                    userId,
                    VectorMathUtil.toPgLiteral(seedVector),
                    0
            );
            log.info("Profile vector seeded for user {} from {} genres", userId, vectors.length);
        }
    }

    private void markOnboardingDone(UUID userId) {
        userRepository.markOnboardingDone(userId, OffsetDateTime.now());
    }
}