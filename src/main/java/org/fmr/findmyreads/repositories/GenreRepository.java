package org.fmr.findmyreads.repositories;

import org.fmr.findmyreads.models.Genre;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GenreRepository extends JpaRepository<Genre, UUID> {

    Optional<Genre> findBySlug(String slug);

    Optional<Genre> findByName(String name);

    boolean existsBySlug(String slug);

    @Query("SELECT g FROM Genre g ORDER BY g.parentId NULLS FIRST, g.name ASC")
    List<Genre> findAllSorted();
}
