package org.fmr.findmyreads.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.dtos.BookDto;
import org.fmr.findmyreads.models.UserBook;
import org.fmr.findmyreads.repositories.UserBookRepository;
import org.fmr.findmyreads.services.BookService;
import org.fmr.findmyreads.services.UserBookService;
import org.fmr.findmyreads.utils.SecurityUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/me/books")
@RequiredArgsConstructor
public class UserBookController {

    private final UserBookService userBookService;
    private final UserBookRepository userBookRepository;
    private final BookService bookService;

    // ── POST /api/me/books ────────────────────────────────────────────────────
    /**
     * Add a book to the user's library.
     * Used from: manual search, onboarding cold-start, scan "save" action.
     *
     * If rating is provided, triggers centroid update immediately.
     * If not, book is saved with given status and can be rated later.
     */
    @PostMapping
    public ResponseEntity<UserBookResponseDto> addBook(
            @RequestBody @Valid AddBookRequest body,
            HttpServletRequest request) {

        UUID userId = SecurityUtil.getCurrentUserId(request);

        UserBook userBook = userBookService.addBook(
                userId,
                body.bookId(),
                body.rating(),
                body.status(),
                body.source()
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(UserBookResponseDto.from(userBook, bookService));
    }

    // ── GET /api/me/books?status= ─────────────────────────────────────────────
    /**
     * List the user's library, optionally filtered by status.
     * status values: "read" | "want_to_read" | "scanning"
     * Omit status param to get all books.
     */
    @GetMapping
    public ResponseEntity<List<UserBookResponseDto>> getLibrary(
            @RequestParam(required = false) String status,
            HttpServletRequest request) {

        UUID userId = SecurityUtil.getCurrentUserId(request);

        List<UserBook> books = status != null
                ? userBookRepository.findByUserIdAndStatus(userId, status)
                : userBookRepository.findRatedBooksWithBookByUserId(userId);

        List<UserBookResponseDto> response = books.stream()
                .map(ub -> UserBookResponseDto.from(ub, bookService))
                .toList();

        return ResponseEntity.ok(response);
    }

    // ── PATCH /api/me/books/{bookId}/rate ─────────────────────────────────────
    /**
     * Rate a book already in the user's library.
     * Triggers incremental (new rating) or full (changed rating) centroid update.
     */
    @PatchMapping("/{bookId}/rate")
    public ResponseEntity<UserBookResponseDto> rateBook(
            @PathVariable UUID bookId,
            @RequestBody @Valid RateBookRequest body,
            HttpServletRequest request) {

        UUID userId = SecurityUtil.getCurrentUserId(request);

        UserBook userBook = userBookService.rateBook(userId, bookId, body.rating());
        return ResponseEntity.ok(UserBookResponseDto.from(userBook, bookService));
    }

    // ── Request records ───────────────────────────────────────────────────────

    public record AddBookRequest(
            @NotNull UUID bookId,

            @Min(1) @Max(5)
            Short rating,                // nullable — rate later

            @Pattern(regexp = "read|want_to_read|scanning")
            String status,               // defaults to "read" in service

            @Pattern(regexp = "manual|scan|search|onboarding")
            String source                // defaults to "manual" in service
    ) {}

    public record RateBookRequest(
            @NotNull @Min(1) @Max(5)
            short rating
    ) {}

    // ── Response DTO ──────────────────────────────────────────────────────────

    public record UserBookResponseDto(
            UUID userBookId,
            BookDto book,
            Short rating,
            String status,
            String source,
            java.time.OffsetDateTime readAt,
            java.time.OffsetDateTime updatedAt
    ) {
        public static UserBookResponseDto from(UserBook ub, BookService bookService) {
            return new UserBookResponseDto(
                    ub.getId(),
                    bookService.toDto(ub.getBook()),
                    ub.getRating(),
                    ub.getStatus(),
                    ub.getSource(),
                    ub.getReadAt(),
                    ub.getUpdatedAt()
            );
        }
    }
}