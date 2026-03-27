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
import java.util.stream.Collectors;

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
     * Maps Google Books categories to Genre entities using keyword heuristics.
     * Automatically links parent genres if a sub-genre is matched.
     *
     * Loads all genres ONCE outside the loop (fixes N+1).
     * Tracks linked genre IDs in a Set (fixes NonUniqueObjectException).
     */
    private void linkGenres(Book book, List<String> subjects) {
        if (subjects == null || subjects.isEmpty()) return;
        List<Genre> allGenres = genreRepository.findAll();

        Map<String, Genre> genreBySlug = allGenres.stream()
                .collect(Collectors.toMap(Genre::getSlug, g -> g));

        Set<UUID> linkedGenreIds = new HashSet<>();
        List<BookGenre> links = new ArrayList<>();

        for (String subject : subjects) {
            String slug = mapSubjectToSlug(subject);
            if (slug == null) continue;

            Genre matchedGenre = genreBySlug.get(slug);
            if (matchedGenre != null) {
                // 1. Link the matched genre (if not already linked)
                if (linkedGenreIds.add(matchedGenre.getId())) {
                    links.add(createBookGenreLink(book, matchedGenre));
                }
            }
        }

        if (!links.isEmpty()) {
            bookGenreRepository.saveAll(links);
            book.getBookGenres().addAll(links);
        }
    }

    private BookGenre createBookGenreLink(Book book, Genre genre) {
        return BookGenre.builder()
                .id(new BookGenre.BookGenreId(book.getId(), genre.getId()))
                .book(book)
                .genre(genre)
                .build();
    }

    /**
     * Heuristic mapping from ugly BISAC subject strings to our beautiful UI slugs.
     * Order matters! We check for specific sub-genres before broad parent categories.
     */
    private String mapSubjectToSlug(String subject) {
        if (subject == null) return null;
        String s = subject.toLowerCase();

        // --- 1. SCI-FI & FANTASY ---
        if (s.contains("dystopian") || s.contains("cyberpunk") || s.contains("post-apocalyptic")) return "dystopian";
        if (s.contains("epic fantasy") || s.contains("high fantasy") || s.contains("sword & sorcery")) return "epic-fantasy";
        if (s.contains("paranormal") || s.contains("urban fantasy") || s.contains("vampire")) return "paranormal";
        if (s.contains("science fiction") || s.contains("sci-fi") || s.contains("space opera")) return "science-fiction";
        if (s.contains("fantasy") || s.contains("magic")) return "sci-fi-fantasy"; // Parent fallback

        // --- 2. MYSTERY & THRILLER ---
        if (s.contains("cozy")) return "cozy-mystery";
        if (s.contains("espionage") || s.contains("spy") || s.contains("political thriller")) return "suspense-espionage";
        if (s.contains("psychological thriller") || s.contains("psychological suspense")) return "psychological-thriller";
        if (s.contains("crime") || s.contains("detective") || s.contains("police") || s.contains("murder")) return "crime-detective";
        if (s.contains("mystery") || s.contains("thriller") || s.contains("suspense")) return "mystery-thriller"; // Parent fallback

        // --- 3. BUSINESS & ECONOMICS ---
        if (s.contains("entrepreneur") || s.contains("startup") || s.contains("venture")) return "entrepreneurship";
        if (s.contains("finance") || s.contains("investing") || s.contains("wealth") || s.contains("budgeting")) return "personal-finance";
        if (s.contains("management") || s.contains("leadership") || s.contains("organizational")) return "management-leadership";
        if (s.contains("economic") || s.contains("macroeconomics")) return "economics-finance";
        if (s.contains("business") || s.contains("commerce")) return "business-economics"; // Parent fallback

        // --- 4. HISTORY & BIOGRAPHY ---
        if (s.contains("military") || s.contains("war") || s.contains("combat")) return "military-history";
        if (s.contains("politics") || s.contains("government") || s.contains("political")) return "politics";
        if (s.contains("biography") || s.contains("memoir") || s.contains("autobiography")) return "memoir-biography";
        if (s.contains("ancient") || s.contains("world history") || s.contains("civilization")) return "world-history";
        if (s.contains("history")) return "history-biography"; // Parent fallback

        // --- 5. SCIENCE, MIND & BODY ---
        if (s.contains("computer") || s.contains("technology") || s.contains("software") || s.contains("artificial intelligence")) return "tech-computers";
        if (s.contains("psychology") || s.contains("self-help") || s.contains("personal growth") || s.contains("mental health")) return "psychology-self-help";
        if (s.contains("philosophy") || s.contains("sociology") || s.contains("ethics")) return "philosophy-sociology";
        if (s.contains("physics") || s.contains("science") || s.contains("biology") || s.contains("astronomy") || s.contains("nature")) return "hard-science";
        if (s.contains("mind & body") || s.contains("lifestyle")) return "science-lifestyle"; // Parent fallback

        // --- 6. FICTION & LITERATURE ---
        if (s.contains("historical fiction")) return "historical-fiction";
        if (s.contains("romance") || s.contains("love") || s.contains("dating")) return "romance";
        if (s.contains("young adult") || s.contains("juvenile") || s.contains("teen")) return "young-adult";
        if (s.contains("literary") || s.contains("classics")) return "literary-fiction";
        if (s.contains("fiction") || s.contains("literature") || s.contains("novel")) return "fiction"; // Ultimate fallback

        // --- 7. POETRY & VERSE ---
        if (s.contains("epic poetry") || s.contains("mythology") && s.contains("poetry")) return "epic-poetry";
        if (s.contains("spoken word") || s.contains("slam poetry") || s.contains("performance poetry")) return "spoken-word";
        if (s.contains("contemporary poetry") || s.contains("women's poetry") || s.contains("modern poetry")) return "contemporary-poetry";
        if (s.contains("classic poetry") || s.contains("ancient poetry") || s.contains("medieval poetry")) return "classic-poetry";
        if (s.contains("poetry") || s.contains("poetics")) return "poetry"; // Parent fallback
        return null;
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