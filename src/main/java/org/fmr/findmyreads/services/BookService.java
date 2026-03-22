package org.fmr.findmyreads.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.clients.GoogleBooksClient;
import org.fmr.findmyreads.dtos.BookDto;
import org.fmr.findmyreads.models.Book;
import org.fmr.findmyreads.models.BookGenre;
import org.fmr.findmyreads.models.Genre;
import org.fmr.findmyreads.repositories.BookGenreRepository;
import org.fmr.findmyreads.repositories.BookRepository;
import org.fmr.findmyreads.repositories.GenreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookService {

    private final BookRepository       bookRepository;
    private final GenreRepository      genreRepository;
    private final BookGenreRepository  bookGenreRepository;
    private final GoogleBooksClient    googleBooksClient;
    private final EmbeddingService     embeddingService;

    // ── Resolve or create ─────────────────────────────────────────────────────

    /**
     * Four-step dedup strategy — each step catches a different failure mode:
     *
     * 1. OCR title+author  → fast path, no API call
     * 2. ISBN              → exact match after API call
     * 3. API title+author  → catches swapped/normalized data (the OL bug)
     * 4. INSERT            → genuinely new book
     */
    @Transactional
    public Optional<Book> resolveOrCreate(String title, String author) {

        // 1. Check by OCR title+author (no API call)
        Optional<Book> existing = bookRepository
                .findByTitleAndAuthorIgnoreCaseWithGenres(title, author != null ? author : "");
        if (existing.isPresent()) {
            log.debug("Book cache hit (OCR match): '{}'", title);
            ensureEmbedded(existing.get());
            return existing;
        }

        // 2. Call Google Books API
        Optional<BookDto> dto = googleBooksClient.searchByTitle(title, author);
        if (dto.isEmpty()) {
            log.warn("Google Books: no match for title='{}'", title);
            return Optional.empty();
        }

        BookDto data = dto.get();

        // 3a. Check by ISBN (exact match, highest confidence)
        if (data.getIsbn() != null) {
            Optional<Book> byIsbn = bookRepository.findByIsbn(data.getIsbn());
            if (byIsbn.isPresent()) {
                log.debug("Book cache hit (ISBN match): {}", data.getIsbn());
                ensureEmbedded(byIsbn.get());
                return byIsbn;
            }
        }

        // 3b. Check by API-normalized title+author
        // Critical: API may return different title/author strings than OCR.
        // Without this check we INSERT a duplicate and hit the unique constraint.
        if (data.getTitle() != null && data.getAuthor() != null) {
            Optional<Book> byApiTitle = bookRepository
                    .findByTitleAndAuthorIgnoreCaseWithGenres(data.getTitle(), data.getAuthor());
            if (byApiTitle.isPresent()) {
                log.debug("Book cache hit (API title match): '{}'", data.getTitle());
                ensureEmbedded(byApiTitle.get());
                return byApiTitle;
            }
        }

        // 4. Genuinely new — persist + embed
        Book book = persistFromDto(data);
        embedAndSave(book);
        return Optional.of(book);
    }

    // ── Ensure embedded ───────────────────────────────────────────────────────

    @Transactional
    public void ensureEmbedded(Book book) {
        if (book.getBookVector() != null) return;
        log.info("On-the-fly embedding for book '{}'", book.getTitle());
        embedAndSave(book);
    }

    // ── Entity → DTO ──────────────────────────────────────────────────────────

    public BookDto toDto(Book book) {
        List<String> genreNames = book.getBookGenres() == null
                ? List.of()
                : book.getBookGenres().stream()
                .map(bg -> bg.getGenre().getName())
                .toList();

        return BookDto.builder()
                .id(book.getId())
                .isbn(book.getIsbn())
                .isbn10(book.getIsbn10())
                .title(book.getTitle())
                .author(book.getAuthor())
                .description(book.getDescription())
                .coverUrl(book.getCoverUrl())
                .publishedAt(book.getPublishedAt())
                .pageCount(book.getPageCount())
                .language(book.getLanguage())
                .source(book.getSource())
                .genres(genreNames)
                .build();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private Book persistFromDto(BookDto dto) {
        Book book = Book.builder()
                .isbn(dto.getIsbn())
                .isbn10(dto.getIsbn10())
                .title(dto.getTitle() != null ? dto.getTitle() : "Unknown")
                .author(dto.getAuthor() != null ? dto.getAuthor() : "Unknown")
                .description(dto.getDescription())
                .coverUrl(dto.getCoverUrl())
                .publishedAt(dto.getPublishedAt())
                .pageCount(dto.getPageCount())
                .language(dto.getLanguage() != null ? dto.getLanguage() : "en")
                .source(dto.getSource() != null ? dto.getSource() : "google_books")
                .build();

        book = bookRepository.save(book);
        linkGenres(book, dto.getSubjects());
        return book;
    }

    /**
     * Maps Google Books categories to Genre entities.
     *
     * Loads all genres ONCE outside the loop (fixes N+1).
     * Tracks linked genre IDs in a Set (fixes NonUniqueObjectException).
     */
    private void linkGenres(Book book, List<String> subjects) {
        if (subjects == null || subjects.isEmpty()) return;

        // Load ALL genres once — never inside the loop
        List<Genre> allGenres = genreRepository.findAll();

        Set<UUID> linkedGenreIds = new HashSet<>();
        List<BookGenre> links = new ArrayList<>();

        for (String subject : subjects) {
            allGenres.stream()
                    .filter(g -> !linkedGenreIds.contains(g.getId()))
                    .filter(g -> subject.toLowerCase().contains(g.getName().toLowerCase())
                            || g.getName().toLowerCase().contains(subject.toLowerCase()))
                    .findFirst()
                    .ifPresent(genre -> {
                        linkedGenreIds.add(genre.getId());
                        links.add(BookGenre.builder()
                                .id(new BookGenre.BookGenreId(book.getId(), genre.getId()))
                                .book(book)
                                .genre(genre)
                                .build());
                    });
        }

        if (!links.isEmpty()) {
            bookGenreRepository.saveAll(links);
            book.getBookGenres().addAll(links);
        }
    }

    private void embedAndSave(Book book) {
        try {
            float[] vector = embeddingService.embedBook(book);
            book.setBookVector(vector);
            bookRepository.save(book);
        } catch (Exception e) {
            log.error("Embedding failed for book '{}': {}", book.getTitle(), e.getMessage());
            // don't rethrow — book saved without vector, ensureEmbedded() retries on next scan
        }
    }
}