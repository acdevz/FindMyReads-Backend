package org.fmr.findmyreads.controllers;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.clients.OpenLibraryClient;
import org.fmr.findmyreads.dtos.BookDto;
import org.fmr.findmyreads.repositories.BookRepository;
import org.fmr.findmyreads.services.BookService;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookController {

    private final BookRepository bookRepository;
    private final BookService bookService;
    private final OpenLibraryClient openLibraryClient;

    // ── GET /api/books/search?q= ──────────────────────────────────────────────
    /**
     * Search for a book by title (and optional author).
     * Used during cold-start manual search and "add book" flows.
     *
     * First checks local DB — returns instantly if already stored.
     * Falls back to Open Library if not found locally.
     * Persists and embeds the book if it's new.
     */
    @GetMapping("/search")
    public ResponseEntity<BookDto> search(
            @RequestParam @NotBlank String q,
            @RequestParam(required = false) String author) {

        log.debug("Book search: q='{}' author='{}'", q, author);
        // TODO: fast search and list, before actually embedding it.
        // resolve via Open Library — creates + embeds if new
        return bookService.resolveOrCreate(q, author)
                .map(book -> ResponseEntity.ok(bookService.toDto(book)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── GET /api/books/{bookId} ───────────────────────────────────────────────
    /**
     * Fetch a single book by its internal UUID.
     * Used when the frontend needs full book details after a scan.
     */
    @GetMapping("/{bookId}")
    public ResponseEntity<BookDto> getBook(@PathVariable UUID bookId) {
        return bookRepository.findById(bookId)
                .map(book -> ResponseEntity.ok(bookService.toDto(book)))
                .orElseThrow(() -> new IllegalArgumentException("Book not found: " + bookId));
    }
}