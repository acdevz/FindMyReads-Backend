package org.fmr.findmyreads.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.models.Book;
import org.fmr.findmyreads.models.User;
import org.fmr.findmyreads.models.UserBook;
import org.fmr.findmyreads.repositories.BookRepository;
import org.fmr.findmyreads.repositories.UserBookRepository;
import org.fmr.findmyreads.repositories.UserGenrePreferenceRepository;
import org.fmr.findmyreads.repositories.UserRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.awt.print.Pageable;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Generates a 1-2 sentence explanation of why a specific book suits a specific user.
 *
 * This is RAG over the user's own data:
 *   Retrieve  — top-rated books + genre preferences from DB (structured SQL, no vector store)
 *   Augment   — inject retrieved context into a focused prompt
 *   Generate  — Gemini produces a short, personalised explanation
 *
 * Called only on demand (user taps a book). Never called during the scan pipeline.
 * Result is not persisted — generated fresh each time so it reflects the
 * latest state of the user's profile.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhyThisBookService {

    private final ChatClient.Builder chatClientBuilder;
    private final BookRepository bookRepository;
    private final UserRepository userRepository;
    private final UserBookRepository userBookRepository;
    private final UserGenrePreferenceRepository genrePreferenceRepository;

    private static final int TOP_RATED_LIMIT = 5;

    /**
     * Generate a personalised "why this book" explanation.
     *
     * @param userId the requesting user
     * @param bookId the book to explain
     * @return 1-2 sentence explanation, or a sensible fallback if generation fails
     */
    public String explain(UUID userId, UUID bookId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        Book book = bookRepository.findByIdWithGenres(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book not found: " + bookId));

        // ── Retrieval step ────────────────────────────────────────────────────
        List<UserBook> topRated = userBookRepository.findTopRatedWithBook(userId, PageRequest.of(0, TOP_RATED_LIMIT, Sort.by("rating", "updatedAt").descending()));
        List<String> genrePreferences = buildGenreContext(userId);

        // ── Prompt construction ───────────────────────────────────────────────
        String prompt = buildPrompt(user, book, topRated, genrePreferences);

        log.debug("Generating 'why this book' for user={} book='{}'", userId, book.getTitle());

        // ── Generation step ───────────────────────────────────────────────────
        try {
            ChatClient chatClient = chatClientBuilder.build();

            String response = chatClient
                    .prompt()
                    .user(prompt)
                    .call()
                    .content();

            return sanitise(response);

        } catch (Exception e) {
            log.warn("WhyThisBook generation failed for book '{}': {}", book.getTitle(), e.getMessage());
            return buildFallback(book, genrePreferences);
        }
    }

    // -------------------------------------------------------------------------

    private String buildPrompt(
            User user,
            Book book,
            List<UserBook> topRated,
            List<String> genrePreferences) {

        StringBuilder sb = new StringBuilder();

        sb.append("You are a knowledgeable book recommender. ");
        sb.append("Write exactly 1-2 sentences explaining why a reader would enjoy a book, ");
        sb.append("based on their reading history and genre preferences. ");
        sb.append("Be specific and personal. Do not use filler phrases like 'this book is perfect for you'.\n\n");

        // Book being explained
        sb.append("BOOK: \"").append(book.getTitle()).append("\"");
        sb.append(" by ").append(book.getAuthor()).append("\n");

        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            String desc = book.getDescription().length() > 300
                    ? book.getDescription().substring(0, 300) + "..."
                    : book.getDescription();
            sb.append("Description: ").append(desc).append("\n");
        }

        if (book.getBookGenres() != null && !book.getBookGenres().isEmpty()) {
            String genres = book.getBookGenres().stream()
                    .map(bg -> bg.getGenre().getName())
                    .collect(Collectors.joining(", "));
            sb.append("Genres: ").append(genres).append("\n");
        }

        sb.append("\n");

        // User context — genre preferences
        if (!genrePreferences.isEmpty()) {
            sb.append("READER'S FAVOURITE GENRES: ")
                    .append(String.join(", ", genrePreferences))
                    .append("\n");
        }

        // User context — top rated books (the richest signal)
        if (!topRated.isEmpty()) {
            sb.append("BOOKS THIS READER LOVED (rated 4-5 stars):\n");
            for (UserBook ub : topRated) {
                sb.append("  - \"")
                        .append(ub.getBook().getTitle())
                        .append("\" by ")
                        .append(ub.getBook().getAuthor())
                        .append(" (").append(ub.getRating()).append(" stars)\n");
            }
        } else {
            sb.append("READER'S HISTORY: No books rated yet — base explanation on genre preferences only.\n");
        }

        sb.append("\nNow write 2-3 sentences explaining why this reader would enjoy \"")
                .append(book.getTitle())
                .append("\". Reference their specific reading history or genre preferences.");

        return sb.toString();
    }

    private List<String> buildGenreContext(UUID userId) {
        return genrePreferenceRepository
                .findByUserIdWithGenre(userId)
                .stream()
                .filter(p -> p.getWeight() >= 3)          // only meaningful preferences
                .sorted((a, b) -> b.getWeight() - a.getWeight()) // strongest first
                .map(p -> p.getGenre().getName())
                .toList();
    }

    /**
     * Strip any leading/trailing whitespace and ensure the response
     * doesn't bleed past 2 sentences if the LLM got carried away.
     */
    private String sanitise(String raw) {
        if (raw == null || raw.isBlank()) return null;

        String trimmed = raw.strip();

        // split on sentence boundaries and cap at 2
        String[] sentences = trimmed.split("(?<=[.!?])\\s+");
        if (sentences.length <= 3) return trimmed;

        return String.join(" ", sentences[0], sentences[1]);
    }

    /**
     * Fallback explanation when LLM call fails.
     * Constructed from available metadata — always something sensible.
     */
    private String buildFallback(Book book, List<String> genrePreferences) {
        if (!genrePreferences.isEmpty() && book.getBookGenres() != null
                && !book.getBookGenres().isEmpty()) {
            String bookGenre = book.getBookGenres().getFirst().getGenre().getName();
            return String.format(
                    "\"%s\" by %s matches your interest in %s.",
                    book.getTitle(), book.getAuthor(), bookGenre);
        }
        return String.format(
                "\"%s\" by %s was found on your shelf and aligns with your reading taste.",
                book.getTitle(), book.getAuthor());
    }
}