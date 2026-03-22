package org.fmr.findmyreads.controllers;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.dtos.ScanResultDto;
import org.fmr.findmyreads.models.Scan;
import org.fmr.findmyreads.models.ScanBook;
import org.fmr.findmyreads.repositories.ScanBookRepository;
import org.fmr.findmyreads.repositories.ScanRepository;
import org.fmr.findmyreads.services.ScanService;
import org.fmr.findmyreads.services.WhyThisBookService;
import org.fmr.findmyreads.utils.SecurityUtil;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/scans")
@RequiredArgsConstructor
public class ScanController {

    private final ScanService scanService;
    private final WhyThisBookService whyThisBookService;
    private final ScanRepository scanRepository;
    private final ScanBookRepository scanBookRepository;

    // ── POST /api/scans ───────────────────────────────────────────────────────
    /**
     * Submit a shelf image for scanning.
     * Runs the full pipeline: OCR → lookup → embed → rank.
     * Returns ranked recommendations scoped to this scan session.
     *
     * Multipart form field name: "image"
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ScanResultDto> scan(
            @RequestPart("image") MultipartFile image,
            HttpServletRequest request) throws IOException {

        UUID userId = SecurityUtil.getCurrentUserId();

        if (image.isEmpty()) {
            throw new IllegalArgumentException("Image file must not be empty.");
        }

        log.info("Scan request from user={} imageSize={}KB",
                userId, image.getSize() / 1024);

        ScanResultDto result = scanService.processScan(image, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping
    public ResponseEntity<List<ScanResponseDto>> getUserScans() {
        UUID userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(
                scanRepository.findByUserId(userId, PageRequest.of(0, 20, Sort.by("scannedAt").descending()))
                        .map(scan -> ScanResponseDto.from(scan, scanBookRepository.findByScanIdWithBook(scan.getId())))
                        .toList()
        );
    }

    // ── GET /api/scans/{scanId} ───────────────────────────────────────────────
    /**
     * Retrieve a past scan with its ranked book results.
     * Useful for the user revisiting scan history.
     */
    @GetMapping("/{scanId}")
    public ResponseEntity<ScanResponseDto> getScan(
            @PathVariable UUID scanId,
            HttpServletRequest request) {

        UUID userId = SecurityUtil.getCurrentUserId();

        Scan scan = scanRepository.findById(scanId)
                .orElseThrow(() -> new IllegalArgumentException("Scan not found: " + scanId));

        // ensure the scan belongs to the requesting user
        if (!scan.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("Scan not found: " + scanId);
        }

        var scanBooks = scanBookRepository.findByScanIdWithBook(scanId);

        return ResponseEntity.ok(ScanResponseDto.from(scan, scanBooks));
    }

    // ── GET /api/scans/{scanId}/books/{bookId}/why ────────────────────────────
    /**
     * On-demand LLM explanation: why does this book suit this user?
     * Called when the user taps a recommendation to learn more.
     * Returns a single short explanation string.
     */
    @GetMapping("/{scanId}/books/{bookId}/why")
    public ResponseEntity<WhyResponseDto> whyThisBook(
            @PathVariable UUID scanId,
            @PathVariable UUID bookId,
            HttpServletRequest request) {

        UUID userId = SecurityUtil.getCurrentUserId();

        // verify the book is actually in this scan (not a random bookId)
        boolean bookInScan = scanBookRepository.findByScanId(scanId)
                .stream()
                .anyMatch(sb -> sb.getBook().getId().equals(bookId));

        if (!bookInScan) {
            throw new IllegalArgumentException(
                    "Book " + bookId + " was not found in scan " + scanId);
        }

        String reason = whyThisBookService.explain(userId, bookId);
        return ResponseEntity.ok(new WhyResponseDto(bookId, reason));
    }

    // ── Response DTOs ─────────────────────────────────────────────────────────

    public record WhyResponseDto(UUID bookId, String reason) {}

    public record ScanResponseDto(
            UUID scanId,
            String[] rawOcrTitles,
            int totalExtracted,
            int totalMatched,
            List<ScanBookDto> books
    ) {
        public static ScanResponseDto from(Scan scan, List<ScanBook> scanBooks) {
            var books = scanBooks.stream().map(ScanBookDto::from).toList();
            return new ScanResponseDto(
                    scan.getId(),
                    scan.getRawOcrTitles(),
                    scan.getRawOcrTitles().length,
                    scan.getMatchedCount(),
                    books
            );
        }
    }

    public record ScanBookDto(
            UUID bookId,
            String title,
            String author,
            String coverUrl,
            String description,
            Integer recommendationRank,
            float matchScore,
            boolean alreadyRead
    ) {
        public static ScanBookDto from(ScanBook sb) {
            return new ScanBookDto(
                    sb.getBook().getId(),
                    sb.getBook().getTitle(),
                    sb.getBook().getAuthor(),
                    sb.getBook().getCoverUrl(),
                    sb.getBook().getDescription(),
                    sb.getRecommendationRank() != null ? (int) sb.getRecommendationRank() : null,
                    sb.getMatchScore(),
                    sb.getRecommendationRank() == null
            );
        }
    }
}