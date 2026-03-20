package org.fmr.findmyreads.models;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "books")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** ISBN-13 preferred. Nullable — Open Library has books without ISBNs. */
    @Column(name = "isbn", unique = true)
    private String isbn;

    @Column(name = "isbn10", unique = true)
    private String isbn10;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "author", nullable = false)
    private String author;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "cover_url")
    private String coverUrl;

    @Column(name = "published_at")
    private LocalDate publishedAt;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "language", nullable = false)
    @Builder.Default
    private String language = "en";

    /**
     * Where the metadata came from.
     * Values: "open_library" | "manual"
     */
    @Column(name = "source", nullable = false)
    @Builder.Default
    private String source = "open_library";

    /**
     * 768-dim embedding of: title + author + description + genres
     * Null until the async embedding job runs after book creation.
     */

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(name = "book_vector", columnDefinition = "vector(768)")
    private float[] bookVector;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // --- relationships ---

    @OneToMany(mappedBy = "book", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<BookGenre> bookGenres = new ArrayList<>();

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