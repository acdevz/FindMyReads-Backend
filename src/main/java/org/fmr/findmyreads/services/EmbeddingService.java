package org.fmr.findmyreads.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.models.Book;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;


/**
 * Thin wrapper around Spring AI's EmbeddingModel.
 * Produces 768-dimensional float[] vectors using Google's text-embedding-004.
 * Results are cached by input text — same book description never hits the
 * API twice within the cache TTL (24h, configured in application.yml).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    /**
     * Embed a raw text string.
     * Cached by the text content itself — cache key is the full string.
     *
     * @param text any string, typically a book's composite description
     * @return 768-dim float[] embedding
     */
//    @Cacheable(value = "embeddings", key = "#text.hashCode()")
    public float[] embed(String text) {
        log.debug("Generating embedding for text of length {}", text.length());
        return embeddingModel.embed(text);
    }

    /**
     * Builds the canonical embedding input for a Book.
     *
     * Format: "{title} {author} {description} {genre1} {genre2} ..."
     *
     * All fields are concatenated into one string so the model captures
     * the semantic relationship between all metadata at once.
     * Null fields are skipped gracefully.
     *
     * @param book fully loaded Book entity with genres
     * @return composite text ready for embedding
     */
    public String buildBookEmbedInput(Book book) {
        StringBuilder sb = new StringBuilder();

        sb.append(book.getTitle()).append(" ");
        sb.append(book.getAuthor()).append(" ");

        if (book.getDescription() != null && !book.getDescription().isBlank()) {
            sb.append(book.getDescription()).append(" ");
        }

        if (book.getBookGenres() != null) {
            book.getBookGenres().forEach(bg ->
                    sb.append(bg.getGenre().getName()).append(" ")
            );
        }

        return sb.toString().strip();
    }

    /**
     * Convenience method: build embed input for a Book then embed it.
     * This is the primary call used by BookService.
     */
    public float[] embedBook(Book book) {
        String input = buildBookEmbedInput(book);
        return embed(input);
    }
}