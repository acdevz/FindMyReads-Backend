package org.fmr.findmyreads.dtos;

public record GenreScore(
        String genre,
        String slug,
        double score
) {}
