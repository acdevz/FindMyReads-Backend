package org.fmr.findmyreads.clients;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.dtos.BookDto;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Client for the Open Library REST API.
 *
 * Endpoints used:
 * Search  : GET https://openlibrary.org/search.json?q=...&limit=3&fields=...
 * By ISBN : GET https://openlibrary.org/api/books?bibkeys=ISBN:{isbn}&format=json&jscmd=details
 * Works   : GET https://openlibrary.org/works/{workId}.json  (for description)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenLibraryClient {

    private static final String BASE_URL = "https://openlibrary.org";

    private final RestClient restClient;

    // Type reference for safe JSON map deserialization, to handle generics type erasure
    private final ParameterizedTypeReference<Map<String, Object>> mapTypeRef = new ParameterizedTypeReference<>() {};

    // -------------------------------------------------------------------------
    // Primary: search by title (+optional author)
    // -------------------------------------------------------------------------

//    @Cacheable(value = "bookLookups", key = "#title.toLowerCase() + '|' + (#author != null ? #author.toLowerCase() : '')")
    public Optional<BookDto> searchByTitle(String title, String author) {
        log.debug("Open Library search: title='{}' author='{}'", title, author);

        try {
            String query = buildSearchQuery(title, author);
            // Requesting only the fields we actually need to keep the payload tiny
            String fields = "key,title,author_name,isbn,first_publish_year,number_of_pages_median,language,subject,cover_i";

            Map<String, Object> response = restClient.get()
                    .uri(BASE_URL + "/search.json?q={q}&limit=3&fields={fields}", query, fields)
                    .retrieve()
                    .body(mapTypeRef);

            if (response == null) return Optional.empty();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> docs = (List<Map<String, Object>>) response.get("docs");
            if (docs == null || docs.isEmpty()) return Optional.empty();

            // Take first result and enrich with work description
            Map<String, Object> best = docs.get(0);
            return Optional.of(mapToBookDto(best));

        } catch (RestClientResponseException e) {
            log.warn("Open Library search failed for title='{}': HTTP {}", title, e.getStatusCode());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Open Library search error for title='{}': {}", title, e.getMessage());
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Secondary: lookup by ISBN (higher confidence match)
    // -------------------------------------------------------------------------

//    @Cacheable(value = "bookLookups", key = "'isbn:' + #isbn")
    public Optional<BookDto> findByIsbn(String isbn) {
        log.debug("Open Library ISBN lookup: {}", isbn);

        try {
            String bibKey = "ISBN:" + isbn;
            Map<String, Object> response = restClient.get()
                    .uri(BASE_URL + "/api/books?bibkeys={bibKey}&format=json&jscmd=details", bibKey)
                    .retrieve()
                    .body(mapTypeRef);

            if (response == null || !response.containsKey(bibKey)) return Optional.empty();

            @SuppressWarnings("unchecked")
            Map<String, Object> bookData = (Map<String, Object>) response.get(bibKey);
            return Optional.of(mapIsbnResponseToBookDto(bookData, isbn));

        } catch (RestClientResponseException e) {
            log.warn("Open Library ISBN lookup failed for {}: HTTP {}", isbn, e.getStatusCode());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Open Library ISBN error for {}: {}", isbn, e.getMessage());
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Description enrichment via Works API
    // -------------------------------------------------------------------------

    public Optional<String> fetchDescription(String workKey) {
        if (workKey == null || workKey.isBlank()) return Optional.empty();

        try {
            Map<String, Object> work = restClient.get()
                    .uri(BASE_URL + workKey + ".json")
                    .retrieve()
                    .body(mapTypeRef);

            if (work == null) return Optional.empty();

            Object desc = work.get("description");
            if (desc instanceof String s) return Optional.of(s);
            if (desc instanceof Map<?,?> m) return Optional.ofNullable((String) m.get("value"));

            return Optional.empty();

        } catch (Exception e) {
            log.debug("Could not fetch description for work {}: {}", workKey, e.getMessage());
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private BookDto mapToBookDto(Map<String, Object> doc) {
        String workKey = (String) doc.get("key");

        List<String> isbns = (List<String>) doc.getOrDefault("isbn", Collections.emptyList());
        String isbn13 = isbns.stream().filter(i -> i != null && i.length() == 13).findFirst().orElse(null);
        String isbn10 = isbns.stream().filter(i -> i != null && i.length() == 10).findFirst().orElse(null);

        List<String> authors = (List<String>) doc.getOrDefault("author_name", Collections.emptyList());
        String author = authors.isEmpty() ? "Unknown" : authors.get(0);

        List<String> langs = (List<String>) doc.getOrDefault("language", Collections.emptyList());
        String language = langs.isEmpty() ? "en" : langs.get(0);

        List<String> subjects = (List<String>) doc.getOrDefault("subject", Collections.emptyList());
        String description = fetchDescription(workKey).orElse(null);

        Object coverId = doc.get("cover_i");
        String coverUrl = coverId != null
                ? "https://covers.openlibrary.org/b/id/" + coverId + "-M.jpg"
                : null;

        Integer pageCount = null;
        Object pagesObj = doc.get("number_of_pages_median");
        if (pagesObj instanceof Number n) pageCount = n.intValue();

        return BookDto.builder()
                .isbn(isbn13)
                .isbn10(isbn10)
                .title((String) doc.getOrDefault("title", "Unknown"))
                .author(author)
                .description(description)
                .coverUrl(coverUrl)
                .pageCount(pageCount)
                .language(language)
                .subjects(subjects)
                .source("open_library")
                .build();
    }

    @SuppressWarnings("unchecked")
    private BookDto mapIsbnResponseToBookDto(Map<String, Object> bookNode, String isbn) {
        // The jscmd=details endpoint nests the actual book data inside a "details" object
        Map<String, Object> details = (Map<String, Object>) bookNode.get("details");
        if (details == null) details = bookNode;

        String title = (String) details.getOrDefault("title", "Unknown");

        // We can now extract the author name directly instead of leaving it as "Unknown"!
        String author = "Unknown";
        List<Map<String, Object>> authors = (List<Map<String, Object>>) details.get("authors");
        if (authors != null && !authors.isEmpty()) {
            author = (String) authors.get(0).get("name");
        }

        // Grab the cover directly from the node
        String coverUrl = (String) bookNode.get("thumbnail_url");
        if (coverUrl != null) {
            // thumbnail_url defaults to -S.jpg (Small). We swap it to Medium for better UI quality.
            coverUrl = coverUrl.replace("-S.jpg", "-M.jpg");
        }

        String workKey = null;
        List<Map<String, Object>> works = (List<Map<String, Object>>) details.get("works");
        if (works != null && !works.isEmpty()) {
            workKey = (String) works.get(0).get("key");
        }
        String description = fetchDescription(workKey).orElse(null);

        Integer pageCount = null;
        Object pagesObj = details.get("number_of_pages");
        if (pagesObj instanceof Number n) {
            pageCount = n.intValue();
        } else if (pagesObj instanceof String s) {
            try { pageCount = Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }

        return BookDto.builder()
                .isbn(isbn.length() == 13 ? isbn : null)
                .isbn10(isbn.length() == 10 ? isbn : null)
                .title(title)
                .author(author)
                .description(description)
                .coverUrl(coverUrl)
                .pageCount(pageCount)
                .source("open_library")
                .build();
    }

    private String buildSearchQuery(String title, String author) {
        if (author != null && !author.isBlank()) {
            return title + " " + author;
        }
        return title;
    }
}