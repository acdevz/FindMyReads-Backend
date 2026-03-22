package org.fmr.findmyreads.repositories;

import org.fmr.findmyreads.models.Book;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookRepository extends JpaRepository<Book, UUID> {

    Optional<Book> findByIsbn(String isbn);

    Optional<Book> findByIsbn10(String isbn10);

    /**
     * Case-insensitive exact title + author lookup.
     * Used as fallback when no ISBN is available.
     */
    @Query("""
        SELECT b FROM Book b
        LEFT JOIN FETCH b.bookGenres bg
        LEFT JOIN FETCH bg.genre
        WHERE LOWER(TRIM(b.title))  = LOWER(TRIM(:title))
          AND LOWER(TRIM(b.author)) = LOWER(TRIM(:author))
    """)
    Optional<Book> findByTitleAndAuthorIgnoreCaseWithGenres(
            @Param("title") String title,
            @Param("author") String author);


    @Query("""
        SELECT b FROM Book b
        LEFT JOIN FETCH b.bookGenres bg
        LEFT JOIN FETCH bg.genre
        WHERE b.id = :id
    """)
    Optional<Book> findByIdWithGenres(@Param("id") UUID id);

    /**
     * Cosine similarity search against all embedded books.
     * Returns the top-K closest books to the given query vector.
     *
     * Native query — pgvector <=> operator (cosine distance, lower = more similar).
     * Used for general "find books like my taste" when not constrained to a scan.
     *
     * :queryVector must be passed as the PG vector literal string "[0.1,0.2,...]"
     */
    @Query(value = """
        SELECT * FROM books
        WHERE book_vector IS NOT NULL
        ORDER BY book_vector <=> CAST(:queryVector AS vector)
        LIMIT :topK
        """, nativeQuery = true)
    List<Book> findTopKByVector(
            @Param("queryVector") String queryVector,
            @Param("topK") int topK);
}