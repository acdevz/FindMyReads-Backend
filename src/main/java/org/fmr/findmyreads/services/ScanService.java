package org.fmr.findmyreads.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.dtos.BookDto;
import org.fmr.findmyreads.dtos.ScanResultDto;
import org.fmr.findmyreads.models.Book;
import org.fmr.findmyreads.models.Scan;
import org.fmr.findmyreads.models.ScanBook;
import org.fmr.findmyreads.models.User;
import org.fmr.findmyreads.repositories.ScanBookRepository;
import org.fmr.findmyreads.repositories.ScanRepository;
import org.fmr.findmyreads.repositories.UserBookRepository;
import org.fmr.findmyreads.repositories.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates the full bookshelf scan pipeline.
 *
 * Corrected flow:
 *   1.  OCR          — Gemini extracts raw titles from image
 *   2.  Resolve      — each title → Open Library → Book entity (with embedding)
 *   3.  Persist scan — save Scan + unranked ScanBook rows (scan_id is needed for ranking query)
 *   4.  Rank         — ScanBookRepository runs pgvector cosine query scoped to this scan
 *   5.  Write ranks  — update recommendation_rank on each ScanBook row
 *   6.  Return       — build response from ranked ScanBook rows
 *
 * Ranking is ALWAYS scoped to books found in the current scan.
 * Global book table is never used for ranking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanService {

    private final GeminiVisionService geminiVisionService;
    private final BookService bookService;
    private final RecommendationService recommendationService;
    private final UserRepository userRepository;
    private final UserBookRepository userBookRepository;
    private final ScanRepository scanRepository;
    private final ScanBookRepository scanBookRepository;

    @Transactional
    public ScanResultDto processScan(MultipartFile imageFile, UUID userId) throws IOException {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // ── Step 1: OCR ───────────────────────────────────────────────────────
        List<String> rawTitles = geminiVisionService.extractTitles(imageFile);
        log.info("OCR extracted {} titles for user {}", rawTitles.size(), userId);

        if (rawTitles.isEmpty()) {
            return ScanResultDto.empty(rawTitles);
        }

        // ── Step 2: Resolve books (Open Library + embed) ──────────────────────
        List<Book> resolvedBooks = new ArrayList<>();
        for (String title : rawTitles) {
            String[] parts = splitTitleAuthor(title);
            bookService.resolveOrCreate(parts[0], parts[1]) // <- don't break! long wait
                    .ifPresentOrElse(
                            resolvedBooks::add,
                            () -> log.debug("No match for title: '{}'", title)
                    );
        }
        log.info("Resolved {}/{} titles to books", resolvedBooks.size(), rawTitles.size());

        // ── Step 3: Persist Scan + unranked ScanBook rows ─────────────────────
        // scan_id must exist before we can run the ranking query against scan_books
        Scan scan = persistScanWithBooks(user, rawTitles, resolvedBooks);

        // ── Step 4: Get already-read book IDs ─────────────────────────────────
        Set<UUID> alreadyReadIds = userBookRepository.findBookIdsByUserId(userId);

        // ── Step 5: Rank within this scan via pgvector ────────────────────────
        List<RecommendationService.RankedBook> ranked = recommendationService.rank(scan.getId(), user, alreadyReadIds);

        // ── Step 6: Write ranks back to scan_books rows ───────────────────────
        writeRanksBack(ranked);

        // ── Step 7: Build and return response ────────────────────────────────
        return buildResult(scan, ranked, rawTitles);
    }

    // -------------------------------------------------------------------------

    private Scan persistScanWithBooks(User user, List<String> rawTitles, List<Book> resolvedBooks) {
        Scan scan = Scan.builder()
                .user(user)
                .rawOcrTitles(rawTitles.toArray(new String[0]))
                .matchedCount(resolvedBooks.size())
                .build();

        scan = scanRepository.save(scan);

        // persist all resolved books as ScanBook rows — rank is null at this point
        Scan finalScan = scan;
        List<ScanBook> scanBooks = resolvedBooks.stream()
                .map(book -> ScanBook.builder()
                        .scan(finalScan)
                        .book(book)
                        .matchScore(1.0f)
                        .recommendationRank(null)
                        .build())
                .toList();

        scanBookRepository.saveAll(scanBooks);
        return scan;
    }

    /**
     * Write computed ranks back to scan_books rows.
     * Already-read books keep recommendation_rank = null.
     */
    private void writeRanksBack(List<RecommendationService.RankedBook> ranked) {
        for (RecommendationService.RankedBook rb : ranked) {
            if (rb.rank() != null) {
                scanBookRepository.updateRank(
                        rb.scanBook().getId(),
                        rb.rank().shortValue()
                );
            }
        }
    }

    private ScanResultDto buildResult(Scan scan, List<RecommendationService.RankedBook> ranked, List<String> rawTitles) {
        List<BookDto> recommendations = new ArrayList<>();
        List<BookDto> alreadyRead     = new ArrayList<>();

        for (RecommendationService.RankedBook rb : ranked) {
            BookDto dto = bookService.toDto(rb.scanBook().getBook());
            dto.setAlreadyRead(rb.alreadyRead());
            dto.setRecommendationRank(rb.rank());
            dto.setSimilarityScore(rb.similarityScore());

            if (rb.alreadyRead()) {
                alreadyRead.add(dto);
            } else {
                recommendations.add(dto);
            }
        }

        return new ScanResultDto(
                scan.getId(),
                rawTitles,
                rawTitles.size(),
                scan.getMatchedCount(),
                recommendations,
                alreadyRead
        );
    }

    /**
     * Best-effort split of OCR title string into title + author.
     * OCR may return "Dune - Frank Herbert" or just "Dune".
     * Returns [title, null] when no separator is found.
     */
    private String[] splitTitleAuthor(String raw) {
        for (String sep : List.of(" - ", " by ", " | ")) {
            int idx = raw.indexOf(sep);
            if (idx > 0) {
                return new String[]{
                        raw.substring(0, idx).strip(),
                        raw.substring(idx + sep.length()).strip()
                };
            }
        }
        return new String[]{raw.strip(), null};
    }
}