package org.fmr.findmyreads.models;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "genres")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Genre {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    /**
     * Centroid embedding of all books in this genre.
     * Null until first batch of books is embedded.
     * Used during cold-start onboarding to seed a new user's profile_vector.
     */
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(name = "prototype_vector", columnDefinition = "vector(768)")
    private float[] prototypeVector;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = OffsetDateTime.now();
    }
}