package org.fmr.findmyreads.clients;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.dtos.BookDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client for the Google Books API v1.
 *
 * Endpoint used:
 *   Search : GET https://www.googleapis.com/books/v1/volumes?q=...&key=...
 *
 * Free tier limit: 1,000 req/day.
 * Caffeine cache (24h TTL) ensures the same book is never fetched twice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleBooksClient {

    private static final String BASE_URL = "https://www.googleapis.com/books/v1";

    // Only request fields we actually use — reduces payload size significantly
    private static final String FIELDS =
            "items(volumeInfo(title,authors,description,categories," +
                    "industryIdentifiers,publishedDate,pageCount,language,imageLinks))";

    private final RestClient restClient;

    @Value("${google.books.api-key}")
    private String apiKey;

    private final ParameterizedTypeReference<Map<String, Object>> mapTypeRef =
            new ParameterizedTypeReference<>() {};

    // ── Search by title + optional author ─────────────────────────────────────

    /**
     * Search Google Books by title and optional author.
     * Uses intitle: and inauthor: operators for precision — avoids the
     * "title contains author name" ambiguity that plagued Open Library results.
     *
     * Cached by "title|author" key — same OCR output never re-hits the API.
     */
    @Cacheable(
            value = "bookLookups",
            key = "#title.toLowerCase() + '|' + (#author != null ? #author.toLowerCase() : '')"
    )
    public Optional<BookDto> searchByTitle(String title, String author) {
        log.debug("Google Books search: title='{}' author='{}'", title, author);

        try {
            String query = buildQuery(title, author);

            Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("www.googleapis.com")
                            .path("/books/v1/volumes")
                            .queryParam("q", query)
                            .queryParam("maxResults", 1)
                            .queryParam("printType", "books")
                            .queryParam("fields", FIELDS)
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .body(mapTypeRef);

            return extractFirstVolume(response);

        } catch (RestClientResponseException e) {
            log.warn("Google Books search failed for title='{}': HTTP {}",
                    title, e.getStatusCode());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Google Books search error for title='{}': {}", title, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Search by ISBN ─────────────────────────────────────────────────────────

    /**
     * Lookup by ISBN-13 or ISBN-10.
     * ISBN lookup via isbn: operator gives exact match — highest confidence.
     */
    @Cacheable(value = "bookLookups", key = "'isbn:' + #isbn")
    public Optional<BookDto> findByIsbn(String isbn) {
        log.debug("Google Books ISBN lookup: {}", isbn);

        try {
            Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("www.googleapis.com")
                            .path("/books/v1/volumes")
                            .queryParam("q", "isbn:" + isbn)
                            .queryParam("maxResults", 1)
                            .queryParam("fields", FIELDS)
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .body(mapTypeRef);

            return extractFirstVolume(response);

        } catch (RestClientResponseException e) {
            log.warn("Google Books ISBN lookup failed for {}: HTTP {}",
                    isbn, e.getStatusCode());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Google Books ISBN error for {}: {}", isbn, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Optional<BookDto> extractFirstVolume(Map<String, Object> response) {
        if (response == null) return Optional.empty();

        List<Map<String, Object>> items =
                (List<Map<String, Object>>) response.get("items");

        if (items == null || items.isEmpty()) return Optional.empty();

        Map<String, Object> volumeInfo =
                (Map<String, Object>) items.get(0).get("volumeInfo");

        if (volumeInfo == null) return Optional.empty();

        return Optional.of(mapVolumeInfo(volumeInfo));
    }

    @SuppressWarnings("unchecked")
    private BookDto mapVolumeInfo(Map<String, Object> v) {

        // ── Title ─────────────────────────────────────────────────────────────
        String title = (String) v.getOrDefault("title", "Unknown");

        // ── Authors — always a separate array, never mixed with title ─────────
        List<String> authorList =
                (List<String>) v.getOrDefault("authors", Collections.emptyList());
        String author = authorList.isEmpty() ? "Unknown" : authorList.get(0);

        // ── Description — inline, no second API call needed ───────────────────
        String description = (String) v.get("description");

        // ── ISBNs — explicitly typed ──────────────────────────────────────────
        String isbn13 = null;
        String isbn10 = null;
        List<Map<String, Object>> identifiers =
                (List<Map<String, Object>>) v.getOrDefault(
                        "industryIdentifiers", Collections.emptyList());
        for (Map<String, Object> id : identifiers) {
            String type = (String) id.get("type");
            String value = (String) id.get("identifier");
            if ("ISBN_13".equals(type)) isbn13 = value;
            else if ("ISBN_10".equals(type)) isbn10 = value;
        }

        // ── Categories → genres ───────────────────────────────────────────────
        List<String> categories =
                (List<String>) v.getOrDefault("categories", Collections.emptyList());

        // ── Cover image ───────────────────────────────────────────────────────
        String coverUrl = null;
        Map<String, Object> imageLinks = (Map<String, Object>) v.get("imageLinks");
        if (imageLinks != null) {
            // prefer thumbnail (128px), fall back to smallThumbnail
            coverUrl = (String) imageLinks.getOrDefault(
                    "thumbnail", imageLinks.get("smallThumbnail"));

            // Google Books thumbnails often have http — force https
            if (coverUrl != null) {
                coverUrl = coverUrl.replace("http://", "https://");
                // remove zoom and edge parameters for cleaner image
                coverUrl = coverUrl.replaceAll("&?(zoom=\\d+|edge=curl)", "");
            }
        }

        // ── Page count ────────────────────────────────────────────────────────
        Integer pageCount = null;
        Object pagesObj = v.get("pageCount");
        if (pagesObj instanceof Number n) pageCount = n.intValue();

        // ── Published date ────────────────────────────────────────────────────
        // Google Books returns dates as "2023", "2023-05", or "2023-05-15"
        LocalDate publishedAt = parsePublishedDate((String) v.get("publishedDate"));

        // ── Language ─────────────────────────────────────────────────────────
        String language = (String) v.getOrDefault("language", "en");

        return BookDto.builder()
                .isbn(isbn13)
                .isbn10(isbn10)
                .title(title)
                .author(author)
                .description(description)
                .coverUrl(coverUrl)
                .publishedAt(publishedAt)
                .pageCount(pageCount)
                .language(language)
                .subjects(categories)        // categories feed into linkGenres()
                .source("google_books")
                .build();
    }

    /**
     * Google Books publishedDate comes in three formats:
     *   "2023"        → year only
     *   "2023-05"     → year + month
     *   "2023-05-15"  → full date
     *
     * We parse what we can and default the rest to Jan 1.
     */
    private LocalDate parsePublishedDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            if (raw.length() == 4) {
                return LocalDate.of(Integer.parseInt(raw), 1, 1);
            } else if (raw.length() == 7) {
                return LocalDate.parse(raw + "-01", DateTimeFormatter.ISO_LOCAL_DATE);
            } else {
                return LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
            }
        } catch (DateTimeParseException | NumberFormatException e) {
            log.debug("Could not parse publishedDate '{}': {}", raw, e.getMessage());
            return null;
        }
    }

    /**
     * Builds a precise Google Books query.
     *
     * intitle: restricts match to title field only
     * inauthor: restricts match to author field only
     *
     * This prevents "Selected Poems" from matching books where those words
     * appear in the description rather than the title.
     */
    private String buildQuery(String title, String author) {
        String q = "intitle:" + title.strip();
        if (author != null && !author.isBlank()) {
            q += "+inauthor:" + author.strip();
        }
        return q;
    }
}