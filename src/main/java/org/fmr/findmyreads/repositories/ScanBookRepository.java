package org.fmr.findmyreads.repositories;

import org.fmr.findmyreads.models.ScanBook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ScanBookRepository extends JpaRepository<ScanBook, UUID> {

    List<ScanBook> findByScanId(UUID scanId);

    /**
     * Fetch all ScanBook rows for a scan, with Book eagerly loaded.
     * Avoids N+1 when building the recommendation response.
     */
    @Query("""
        SELECT sb FROM ScanBook sb
        JOIN FETCH sb.book b
        WHERE sb.scan.id = :scanId
        ORDER BY sb.recommendationRank ASC NULLS LAST
        """)
    List<ScanBook> findByScanIdWithBook(@Param("scanId") UUID scanId);

    /**
     * Core recommendation query — scan-scoped cosine ranking.
     *
     * Ranks only books found in THIS scan against the user's query vector.
     * Never touches books outside the current scan session.
     *
     * pgvector <=> = cosine distance (lower = more similar).
     * ORDER BY ASC so the closest match (lowest distance) comes first.
     *
     * :queryVector — PG vector literal "[0.1,0.2,...]" built by VectorMathUtil.toPgLiteral()
     * :scanId      — restricts ranking to current scan only
     */
    @Query(value = """
        SELECT sb.*
        FROM scan_books sb
        JOIN books b ON b.id = sb.book_id
        WHERE sb.scan_id = :scanId
          AND b.book_vector IS NOT NULL
        ORDER BY b.book_vector <=> CAST(:queryVector AS vector) ASC
        """, nativeQuery = true)
    List<ScanBook> findRankedByScanAndVector(
            @Param("scanId")      UUID scanId,
            @Param("queryVector") String queryVector);

    /**
     * Bulk update recommendation_rank for all rows in a scan.
     * Called after ranking is computed — sets rank on each ScanBook row.
     */
    @Modifying
    @Query(value = """
        UPDATE scan_books
        SET recommendation_rank = :rank,
            match_score = :matchScore
        WHERE id = :scanBookId
        """, nativeQuery = true)
    void updateRankAndSimilarityScore(
            @Param("scanBookId") UUID scanBookId,
            @Param("rank")       Short rank,
            @Param("matchScore") Float matchScore);

}