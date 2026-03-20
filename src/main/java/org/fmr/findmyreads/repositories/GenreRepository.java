package org.fmr.findmyreads.repositories;

import org.fmr.findmyreads.models.Genre;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GenreRepository extends JpaRepository<Genre, UUID> {

    Optional<Genre> findBySlug(String slug);

    Optional<Genre> findByName(String name);

    boolean existsBySlug(String slug);
}
