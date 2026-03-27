package org.fmr.findmyreads.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.models.ScanBook;
import org.fmr.findmyreads.models.User;
import org.fmr.findmyreads.repositories.ScanBookRepository;
import org.fmr.findmyreads.utils.VectorMathUtil;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Ranks books found in a specific scan against the user's profile vector.
 *
 * Scope is ALWAYS a single scan session — never the global books table.
 * pgvector owns the cosine math via ScanBookRepository.findRankedByScanAndVector().
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final ScanBookRepository scanBookRepository;

    private static final int EMBEDDING_DIM = 768;

    /**
     * Rank all embedded books in the given scan against the user's taste.
     *
     * Flow:
     *   1. Build query vector (profile + deviation blend)
     *   2. Delegate cosine ranking to pgvector via ScanBookRepository
     *   3. Assign rank positions — skipping books the user already read
     *   4. Return ordered RankedBook list
     *
     * Books with no vector are excluded by the SQL query (WHERE book_vector IS NOT NULL).
     * They still appear in the scan_books table — just without a rank.
     *
     * @param scanId         the persisted scan to rank within
     * @param user           provides profileVector + deviationAlpha
     * @param alreadyReadIds book IDs already in user's library — marked, not ranked
     */
    public List<RankedBook> rank(
            UUID scanId,
            User user,
            Set<UUID> alreadyReadIds) {

        // 1. Build query vector
        float[] queryVector = buildQueryVector(user);

        if (queryVector == null) {
            // cold start — user has no profile vector yet
            // return all scan books unranked so UI can still show them
            log.debug("User {} has no profile vector — returning scan books unranked", user.getId());
            return scanBookRepository.findByScanIdWithBook(scanId)
                    .stream()
                    .map(sb -> new RankedBook(sb, null, alreadyReadIds.contains(sb.getBook().getId()), null))
                    .toList();
        }

        // 2. pgvector ranks books within this scan — DB does the cosine math
        String pgLiteral = VectorMathUtil.toPgLiteral(queryVector);
        List<ScanBook> ranked = scanBookRepository.findRankedByScanAndVector(scanId, pgLiteral);

        if (ranked.isEmpty()) {
            log.warn("No embedded books to rank in scan {} — all books may lack vectors", scanId);
            // fall back: return unranked list including unembedded books
            return scanBookRepository.findByScanIdWithBook(scanId)
                    .stream()
                    .map(sb -> new RankedBook(sb, null, alreadyReadIds.contains(sb.getBook().getId()), null))
                    .toList();
        }

        // 3. Assign rank positions — already-read books get null rank
        int rankCounter = 1;
        List<RankedBook> result = new java.util.ArrayList<>();

        for (ScanBook sb : ranked) {
            boolean alreadyRead = alreadyReadIds.contains(sb.getBook().getId());
            Integer rank = alreadyRead ? null : rankCounter++;
            float similarity = VectorMathUtil.cosineSimilarity(sb.getBook().getBookVector(), queryVector);
            result.add(new RankedBook(sb, rank, alreadyRead, similarity));
        }

        log.debug("Ranked {} books in scan {} for user {}", result.size(), scanId, user.getId());
        return result;
    }

    // -------------------------------------------------------------------------

    /**
     * Build the query vector from the user's profile, with deviation blend applied.
     * Returns null if user has no profile vector (cold start).
     */
    private float[] buildQueryVector(User user) {
        float[] profile = user.getProfileVector();
        if (profile == null) return null;

        float alpha = user.getDeviationAlpha();
        if (alpha <= 0f) return profile;

        float[] randomVec = VectorMathUtil.randomUnitVector(EMBEDDING_DIM);
        return VectorMathUtil.deviationBlend(profile, randomVec, alpha);
    }

    // ── Result record ─────────────────────────────────────────────────────────

    public record RankedBook(
            ScanBook scanBook,
            Integer rank,            // null = already read, not ranked
            boolean alreadyRead,
            Float similarityScore    // null if user has no profile vector
    ) {}
}
