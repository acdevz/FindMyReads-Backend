package org.fmr.findmyreads.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "user_books",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_user_books_user_book",
                columnNames = {"user_id", "book_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserBook {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    /**
     * 1–5 stars. Nullable — user may add a book without rating it yet.
     * Rating being non-null is what triggers the centroid update in UserService.
     */
    @Column(name = "rating")
    private Short rating;

    /**
     * Lifecycle status of this book for the user.
     *
     * read         — finished reading, may or may not have a rating
     * want_to_read — saved from a recommendation, not yet read
     * scanning     — found on a shelf scan, user hasn't actioned it yet
     */
    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "read";

    /**
     * How this entry was created.
     *
     * manual      — user searched and added manually
     * scan        — came in through a shelf scan
     * search      — added via book search during cold start
     * onboarding  — added during the onboarding wizard flow
     */
    @Column(name = "source", nullable = false)
    @Builder.Default
    private String source = "manual";

    /** When the user actually finished the book. Null if status != read. */
    @Column(name = "read_at")
    private OffsetDateTime readAt;

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