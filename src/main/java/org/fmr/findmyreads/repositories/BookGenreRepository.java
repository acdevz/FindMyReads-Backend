package org.fmr.findmyreads.repositories;

import org.fmr.findmyreads.models.BookGenre;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BookGenreRepository extends JpaRepository<BookGenre, BookGenre.BookGenreId> {

    List<BookGenre> findByBookId(UUID bookId);

    void deleteByBookId(UUID bookId);
}