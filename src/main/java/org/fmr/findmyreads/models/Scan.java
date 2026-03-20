package org.fmr.findmyreads.models;

import jakarta.persistence.*;
import lombok.*;
import org.fmr.findmyreads.models.User;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "scans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Scan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Raw title strings returned by Gemini OCR before any lookup.
     * Kept for debugging failed matches and improving OCR prompts later.
     */
    @Column(name = "raw_ocr_titles", columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] rawOcrTitles;

    /** Optional: path/URL if you store the original image */
    @Column(name = "image_url")
    private String imageUrl;

    /** How many titles were successfully resolved to a Book row */
    @Column(name = "matched_count", nullable = false)
    @Builder.Default
    private int matchedCount = 0;

    @Column(name = "scanned_at", nullable = false, updatable = false)
    private OffsetDateTime scannedAt;

    @OneToMany(mappedBy = "scan", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ScanBook> scanBooks = new ArrayList<>();

    @PrePersist
    void prePersist() {
        this.scannedAt = OffsetDateTime.now();
    }
}