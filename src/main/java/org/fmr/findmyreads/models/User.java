package org.fmr.findmyreads.models;

import jakarta.persistence.*;
import lombok.*;
import org.fmr.findmyreads.converters.VectorConverter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Stub entity — enough for FK references from Scan and UserBook.
 * Full auth fields (password_hash, roles) added when security module is built.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "username", nullable = false, unique = true)
    private String username;

    // placeholder — BCrypt hash stored here when auth module is added
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /**
     * Weighted centroid of all rated books.
     * Null until user rates at least one book.
     */
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(name = "profile_vector", columnDefinition = "vector(768)")
    private float[] profileVector;

    /**
     * Sum of all ratings submitted so far — denominator for centroid update.
     * NOT a simple count of rows: a 5-star book contributes 5, a 1-star contributes 1.
     */
    @Column(name = "books_rated_count", nullable = false)
    @Builder.Default
    private int booksRatedCount = 0;

    /**
     * 0.0 = pure taste match, 1.0 = full exploration.
     * Stored per-user so they can tune it independently.
     */
    @Column(name = "deviation_alpha", nullable = false)
    @Builder.Default
    private float deviationAlpha = 0.2f;

    @Column(name = "onboarding_done", nullable = false)
    @Builder.Default
    private boolean onboardingDone = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
