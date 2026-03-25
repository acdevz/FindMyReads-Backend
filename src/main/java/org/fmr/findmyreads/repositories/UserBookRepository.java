package org.fmr.findmyreads.repositories;

import org.fmr.findmyreads.models.UserBook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface UserBookRepository extends JpaRepository<UserBook, UUID> {

    @Query("""
        SELECT ub FROM UserBook ub
        JOIN FETCH ub.book b
        LEFT JOIN FETCH b.bookGenres bg
        LEFT JOIN FETCH bg.genre
        WHERE ub.user.id = :userId
          AND ub.book.id = :bookId
    """)
    Optional<UserBook> findByUserIdAndBookId(UUID userId, UUID bookId);

    boolean existsByUserIdAndBookId(UUID userId, UUID bookId);

    /**
     * All rated books for a user, with Book eagerly fetched.
     * Used by UserService to recompute the centroid from scratch if needed.
     */
    @Query("""
        SELECT ub FROM UserBook ub
        JOIN FETCH ub.book b
        LEFT JOIN FETCH b.bookGenres bg
        LEFT JOIN FETCH bg.genre
        WHERE ub.user.id = :userId
          AND ub.rating  IS NOT NULL
    """)
    List<UserBook> findRatedBooksWithBookByUserId(@Param("userId") UUID userId);

    /**
     * All books for a user, with Book eagerly fetched.
     * Used by UserBookController to display the user's library, which includes both rated and unrated books.
     */
    @Query("""
        SELECT ub FROM UserBook ub
        JOIN FETCH ub.book b
        LEFT JOIN FETCH b.bookGenres bg
        LEFT JOIN FETCH bg.genre
        WHERE ub.user.id = :userId
        """)
    List<UserBook> findAllBooksWithBookByUserId(@Param("userId") UUID userId);

    /**
     * Returns just the book IDs the user has already interacted with
     * (any status). Used during scan to mark "already in your library" books.
     * Returns a Set for O(1) membership checks.
     */
    @Query("""
        SELECT ub.book.id FROM UserBook ub
        WHERE ub.user.id = :userId
        """)
    Set<UUID> findBookIdsByUserId(@Param("userId") UUID userId);

    /**
     * All UserBook rows for a user with a specific status.
     * e.g. findByUserIdAndStatus(userId, "want_to_read")
     */
    @Query("""
        SELECT ub FROM UserBook ub
        JOIN FETCH ub.book b
        LEFT JOIN FETCH b.bookGenres bg
        LEFT JOIN FETCH bg.genre
        WHERE ub.user.id = :userId
          AND ub.status = :status
        """)
    List<UserBook> findByUserIdAndStatus(UUID userId, String status);

    /**
     * Top-rated books (rating >= 4) for a user, capped at limit.
     * Used by WhyThisBookService as RAG context — these are the books
     * the LLM references when explaining why a new book suits the user.
     * Ordered by rating DESC so the strongest preferences come first.
     */
    @Query("""
        SELECT ub FROM UserBook ub
        JOIN FETCH ub.book b
        LEFT JOIN FETCH b.bookGenres bg
        LEFT JOIN FETCH bg.genre
        WHERE ub.user.id = :userId
          AND ub.rating  >= 3
    """)
    List<UserBook> findTopRatedWithBook(
            @Param("userId") UUID userId,
            Pageable pageable);

    /**
     * Total count of all the books the user has interacted with (any status).
     * Used by UserController to show the total library size in the profile.
     */
    int countByUserId(UUID userId);
}

