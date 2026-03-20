package org.fmr.findmyreads.dtos;

import lombok.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Used in two directions:
 *  1. Inbound  — OpenLibraryClient maps API response → BookDto
 *  2. Outbound — BookService maps Book entity → BookDto for API responses
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookDto {

    private UUID id;                 // null on inbound (not yet persisted)
    private String isbn;
    private String isbn10;
    private String title;
    private String author;
    private String description;
    private String coverUrl;
    private LocalDate publishedAt;
    private Integer pageCount;
    private String language;
    private String source;

    /** Raw subject strings from Open Library — mapped to Genre entities by BookService */
    private List<String> subjects;

    /** Resolved genre names after persistence — returned in API responses */
    private List<String> genres;

    /** True if the current user has already read this book — set at scan time */
    private boolean alreadyRead;

    /** Position in the ranked recommendation list — null if already read */
    private Integer recommendationRank;

    /** Cosine similarity score against user profile — for debug/display */
    private Float similarityScore;

    /**
     * LLM-generated 1-2 sentence explanation of why this book suits this user.
     * Null until the user explicitly requests it (on-demand via /why endpoint).
     * Never persisted — generated fresh on each request.
     */
    private String reason;
}
