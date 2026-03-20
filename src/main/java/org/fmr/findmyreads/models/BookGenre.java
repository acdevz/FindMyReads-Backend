package org.fmr.findmyreads.models;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Junction table: books ↔ genres (many-to-many with full @Entity
 * so we can add extra columns later without a schema migration headache).
 *
 * PK is composite (book_id, genre_id) — mapped via @IdClass or @EmbeddedId.
 * Using @EmbeddedId here for cleaner equals/hashCode.
 */
@Entity
@Table(name = "book_genres")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookGenre {

    @EmbeddedId
    private BookGenreId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("bookId")
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("genreId")
    @JoinColumn(name = "genre_id", nullable = false)
    private Genre genre;

    // -----------------------------------------------------------------------
    // Composite PK
    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class BookGenreId implements java.io.Serializable {

        @Column(name = "book_id")
        private UUID bookId;

        @Column(name = "genre_id")
        private UUID genreId;
    }
}