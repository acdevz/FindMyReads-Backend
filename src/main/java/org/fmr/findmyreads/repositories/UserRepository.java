package org.fmr.findmyreads.repositories;

import org.fmr.findmyreads.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByUsername(String username);

    boolean existsByEmail(String email);

    /**
     * Incremental centroid update — called every time a user rates a book.
     * Formula: new_vector = (old_vector * old_count + book_vector * rating) / (old_count + rating)
     * Done in a single UPDATE to avoid a read-modify-write race condition.
     * :newVector and :newCount are computed in Java (UserService) before calling this.
     */
    @Modifying
    @Query(value = """
        UPDATE users
        SET profile_vector    = CAST(:newVector AS vector),
            books_rated_count = :newCount,
            updated_at        = NOW()
        WHERE id = :userId
        """, nativeQuery = true)
    void updateProfileVector(
            @Param("userId") UUID userId,
            @Param("newVector") String newVector,
            @Param("newCount") int newCount);
}

