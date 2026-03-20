package org.fmr.findmyreads.models;

import jakarta.persistence.*;
import lombok.*;
import org.fmr.findmyreads.models.Book;

import java.util.UUID;

/**
 * Junction: scans ↔ books.
 * Records which books were found and resolved in a scan session,
 * their match confidence, and the rank shown to the user.
 */
@Entity
@Table(name = "scan_books")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanBook {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_id", nullable = false)
    private Scan scan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "book_id", nullable = false)
    private Book book;

    /**
     * Confidence that this Book row is the correct match for the OCR title.
     * 1.0 = exact ISBN match, < 1.0 = fuzzy title match.
     */
    @Column(name = "match_score", nullable = false)
    @Builder.Default
    private float matchScore = 1.0f;

    /**
     * Position in the ranked recommendation list returned to the user.
     * Null if the user had already read this book (shown as "already read").
     */
    @Column(name = "recommendation_rank")
    private Short recommendationRank;
}
