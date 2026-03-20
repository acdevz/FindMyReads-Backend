package org.fmr.findmyreads.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.fmr.findmyreads.clients.OpenLibraryClient;
import org.fmr.findmyreads.dtos.BookDto;
import org.fmr.findmyreads.models.Book;
import org.fmr.findmyreads.models.BookGenre;
import org.fmr.findmyreads.repositories.BookGenreRepository;
import org.fmr.findmyreads.repositories.BookRepository;
import org.fmr.findmyreads.repositories.GenreRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Owns the full lifecycle of a Book entity:
 *   1. Resolve by ISBN or title+author (avoid duplicates)
 *   2. If not found, fetch metadata from Open Library
 *   3. Persist with genres
 *   4. Embed synchronously — book_vector set before method returns
 *
 * This is the single entry point for creating Book records.
 * Nothing else should call BookRepository.save() directly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookService {

    private final BookRepository bookRepository;
    private final GenreRepository genreRepository;
    private final BookGenreRepository bookGenreRepository;
    private final OpenLibraryClient openLibraryClient;
    private final EmbeddingService embeddingService;

    // -------------------------------------------------------------------------
    // Primary resolve-or-create entry point
    // -------------------------------------------------------------------------

    /**
     * Given a title (and optional author from OCR), either:
     *   - Return existing Book from DB (no API call, no embedding)
     *   - Fetch from Open Library, persist, embed, and return
     * Returns empty if Open Library has no match.
     *
     * @param title  OCR-extracted title
     * @param author OCR-extracted author — may be null
     */
    @Transactional
    public Optional<Book> resolveOrCreate(String title, String author) {
        // 1. Try to find by exact title + author first (fastest, no API - local db)
        Optional<Book> existing = bookRepository.findByTitleAndAuthorIgnoreCase(title, author != null ? author : "");
        if (existing.isPresent()) {
            log.debug("Book cache hit: '{}'", title);
            ensureEmbedded(existing.get());
            return existing;
        }

        // 2. Hit Open Library
        Optional<BookDto> dto = openLibraryClient.searchByTitle(title, author);
        if (dto.isEmpty()) {
            log.warn("Open Library: no match for title='{}'", title);
            return Optional.empty();
        }

        // 3. Check again by ISBN to prevent race-condition duplicates (local db)
        BookDto data = dto.get();
        if (data.getIsbn() != null) {
            Optional<Book> byIsbn = bookRepository.findByIsbn(data.getIsbn());
            if (byIsbn.isPresent()) {
                ensureEmbedded(byIsbn.get());
                return byIsbn;
            }
        }

        // 4. Persist new book
        Book book = persistFromDto(data);

        // 5. Embed synchronously
        embedAndSave(book);

        return Optional.of(book);
    }

    // -------------------------------------------------------------------------
    // Ensure embedded (lenient path — triggered at scan time if vector missing)
    // -------------------------------------------------------------------------

    /**
     * If book already has a vector, no-op.
     * If not, embed now and save — called when a book is found at scan time
     * without a vector (e.g. was created before embedding was wired up).
     */
    @Transactional
    public void ensureEmbedded(Book book) {
        if (book.getBookVector() != null) return;
        log.info("On-the-fly embedding for book '{}'", book.getTitle());
        embedAndSave(book);
    }

    // -------------------------------------------------------------------------
    // Entity → DTO mapping (for API responses)
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private Book persistFromDto(BookDto dto) {
        Book book = Book.builder()
                .isbn(dto.getIsbn())
                .isbn10(dto.getIsbn10())
                .title(dto.getTitle())
                .author(dto.getAuthor() != null ? dto.getAuthor() : "Unknown")
                .description(dto.getDescription())
                .coverUrl(dto.getCoverUrl())
                .pageCount(dto.getPageCount())
                .language(dto.getLanguage() != null ? dto.getLanguage() : "en")
                .source(dto.getSource() != null ? dto.getSource() : "open_library")
                .build();

        book = bookRepository.save(book);

        // Link genres
        if (dto.getSubjects() != null && !dto.getSubjects().isEmpty()) {
            linkGenres(book, dto.getSubjects());
        }

        return book;
    }

    /**
     * Maps Open Library subject strings to Genre entities.
     * Only links genres that already exist in our genres table (seeded via Flyway).
     * No new genres are created here — keeps the genre list controlled.
     */
    private void linkGenres(Book book, List<String> subjects) {
        List<BookGenre> links = new ArrayList<>();

        for (String subject : subjects) {
            // fuzzy: check if any genre name is contained in the subject string
            genreRepository.findAll().stream()
                    .filter(g -> subject.toLowerCase().contains(g.getName().toLowerCase())
                            || g.getName().toLowerCase().contains(subject.toLowerCase()))
                    .findFirst()
                    .ifPresent(genre -> {
                        BookGenre.BookGenreId id = new BookGenre.BookGenreId(book.getId(), genre.getId());
                        if (!bookGenreRepository.existsById(id)) {
                            links.add(BookGenre.builder()
                                    .id(id)
                                    .book(book)
                                    .genre(genre)
                                    .build());
                        }
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
            // don't rethrow — book is still saved without vector
            // RecommendationService will skip unembedded books
        }
    }
}