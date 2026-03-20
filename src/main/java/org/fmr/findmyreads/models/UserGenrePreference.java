package org.fmr.findmyreads.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Records which genres a user selected during onboarding and how strongly
 * they weighted each one (1–5, mirrors the rating scale).
 *
 * Used by OnboardingService to seed the initial profile_vector by computing:
 *   profile_vector = weighted average of genre.prototype_vectors
 *
 * Once the user has rated real books this table becomes secondary —
 * the profile_vector on the User row supersedes it — but it stays
 * as a UI record of stated preferences and a fallback if vectors are missing.
 */
@Entity
@Table(
        name = "user_genre_preferences",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_ugp_user_genre",
                columnNames = {"user_id", "genre_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserGenrePreference {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "genre_id", nullable = false)
    private Genre genre;

    /**
     * How strongly the user wants this genre represented in their profile.
     * 1 = mild interest, 5 = strong preference.
     * Stored as Short to match DB int2.
     */
    @Column(name = "weight", nullable = false)
    @Builder.Default
    private Short weight = 3;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = OffsetDateTime.now();
    }
}