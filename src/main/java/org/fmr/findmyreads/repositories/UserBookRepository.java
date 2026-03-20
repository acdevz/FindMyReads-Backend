package org.fmr.findmyreads.repositories;

import org.fmr.findmyreads.models.UserBook;
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

    Optional<UserBook> findByUserIdAndBookId(UUID userId, UUID bookId);

    boolean existsByUserIdAndBookId(UUID userId, UUID bookId);

    /**
     * All rated books for a user, with Book eagerly fetched.
     * Used by UserService to recompute the centroid from scratch if needed.
     */
    @Query("""
        SELECT ub FROM UserBook ub
        JOIN FETCH ub.book
        WHERE ub.user.id = :userId
          AND ub.rating IS NOT NULL
        """)
    List<UserBook> findRatedBooksWithBookByUserId(@Param("userId") UUID userId);

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
        WHERE ub.user.id = :userId
          AND ub.rating  >= 3
        ORDER BY ub.rating DESC, ub.updatedAt DESC
        LIMIT :limit
        """)
    List<UserBook> findTopRatedWithBook(
            @Param("userId") UUID userId,
            @Param("limit")  int limit);
}

