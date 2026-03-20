package org.fmr.findmyreads.repositories;

import org.fmr.findmyreads.models.UserGenrePreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserGenrePreferenceRepository extends JpaRepository<UserGenrePreference, UUID> {

    List<UserGenrePreference> findByUserId(UUID userId);

    /**
     * Fetch all preferences for a user with Genre (and its prototype_vector) eagerly loaded.
     * Used by OnboardingService.seedProfileVector() to compute the initial centroid.
     */
    @Query("""
        SELECT ugp FROM UserGenrePreference ugp
        JOIN FETCH ugp.genre g
        WHERE ugp.user.id = :userId
        """)
    List<UserGenrePreference> findByUserIdWithGenre(@Param("userId") UUID userId);

    void deleteByUserId(UUID userId);
}