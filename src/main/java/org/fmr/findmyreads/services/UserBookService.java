package org.fmr.findmyreads.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.fmr.findmyreads.models.Book;
import org.fmr.findmyreads.models.User;
import org.fmr.findmyreads.models.UserBook;
import org.fmr.findmyreads.repositories.BookRepository;
import org.fmr.findmyreads.repositories.UserBookRepository;
import org.fmr.findmyreads.repositories.UserRepository;
import org.fmr.findmyreads.utils.VectorMathUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserBookService {

    private final UserBookRepository userBookRepository;
    private final UserRepository userRepository;
    private final BookRepository bookRepository;
    private final BookService bookService;

    /**
     * Add a book to the user's library with an optional rating.
     * If rating is provided, triggers centroid update immediately.
     *
     * @param userId  requesting user
     * @param bookId  book to add
     * @param rating  1–5 or null (can be rated later via rateBook)
     * @param status  "read" | "want_to_read" | "scanning"
     * @param source  "manual" | "scan" | "search" | "onboarding"
     */
    @Transactional
    public UserBook addBook(UUID userId, UUID bookId, Short rating, String status, String source) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
        Book book = bookRepository.findByIdWithGenres(bookId)
                .orElseThrow(() -> new IllegalArgumentException("Book not found: " + bookId));

        // ensure book has a vector before potentially updating centroid
        bookService.ensureEmbedded(book);

        // upsert — user may already have this book from a scan
        UserBook userBook = userBookRepository.findByUserIdAndBookId(userId, bookId)
                .orElseGet(() -> UserBook.builder()
                        .user(user)
                        .book(book)
                        .build());

        userBook.setStatus(status != null ? status : "read");
        userBook.setSource(source != null ? source : "manual");

        if (rating != null) {
            Short previousRating = userBook.getRating();
            userBook.setRating(rating);
            userBook.setReadAt(OffsetDateTime.now());
            userBook = userBookRepository.save(userBook);

            // only update centroid if this is a new rating (not an update to existing)
            if (previousRating == null) {
                updateCentroid(user, book, rating);
            } else {
                // rating changed — full recompute from scratch to stay accurate
                recomputeCentroid(userId);
            }
        } else {
            userBook = userBookRepository.save(userBook);
        }

        return userBook;
    }

    /**
     * Rate a book the user already has in their library.
     * Updates centroid accordingly.
     */
    @Transactional
    public UserBook rateBook(UUID userId, UUID bookId, short rating) {
        UserBook userBook = userBookRepository.findByUserIdAndBookId(userId, bookId)
                .orElseThrow(() -> new IllegalStateException(
                        "Book not in user's library. Add it first."));

        Short previousRating = userBook.getRating();
        userBook.setRating(rating);
        userBook.setStatus("read");
        userBook.setReadAt(OffsetDateTime.now());
        userBook = userBookRepository.save(userBook);

        if (previousRating == null) {
            // first rating — incremental update is safe
            User user = userRepository.findById(userId).orElseThrow();
            Book book = userBook.getBook();
            updateCentroid(user, book, rating);
        } else {
            // changed existing rating — must recompute fully
            recomputeCentroid(userId);
        }

        return userBook;
    }

    // -------------------------------------------------------------------------

    /**
     * Incremental centroid update — called when a NEW rating is added.
     * Single UPDATE query — no read-modify-write on the profile vector.
     */
    private void updateCentroid(User user, Book book, short rating) {
        if (book.getBookVector() == null) {
            log.warn("Cannot update centroid: book '{}' has no vector", book.getTitle());
            return;
        }

        float[] newCentroid = VectorMathUtil.updateCentroid(
                user.getProfileVector(),
                user.getBooksRatedCount(),
                book.getBookVector(),
                rating
        );

        int newCount = user.getBooksRatedCount() + rating;
        String pgLiteral = VectorMathUtil.toPgLiteral(newCentroid);

        userRepository.updateProfileVector(user.getId(), pgLiteral, newCount);
        log.debug("Centroid updated for user {} — new weight sum: {}", user.getId(), newCount);
    }

    /**
     * Full centroid recompute from all rated books.
     * Used when an existing rating changes — incremental update would drift.
     */
    private void recomputeCentroid(UUID userId) {
        User user = userRepository.findById(userId).orElseThrow();

        var ratedBooks = userBookRepository.findRatedBooksWithBookByUserId(userId);
        if (ratedBooks.isEmpty()) return;

        float[][] vectors = ratedBooks.stream()
                .filter(ub -> ub.getBook().getBookVector() != null)
                .map(ub -> ub.getBook().getBookVector())
                .toArray(float[][]::new);

        int[] weights = ratedBooks.stream()
                .filter(ub -> ub.getBook().getBookVector() != null)
                .mapToInt(ub -> ub.getRating())
                .toArray();

        if (vectors.length == 0) return;

        float[] newCentroid = VectorMathUtil.weightedAverage(vectors, weights);
        int newCount = java.util.Arrays.stream(weights).sum();

        userRepository.updateProfileVector(
                userId,
                VectorMathUtil.toPgLiteral(newCentroid),
                newCount
        );
        log.info("Full centroid recompute done for user {} — {} books, weight sum {}",
                userId, vectors.length, newCount);
    }
}