package org.fmr.findmyreads.repositories;

import org.fmr.findmyreads.models.Scan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ScanRepository extends JpaRepository<Scan, UUID> {

    /** Paginated scan history for a user — most recent first via Pageable sort */
    Page<Scan> findByUserId(UUID userId, Pageable pageable);
}
