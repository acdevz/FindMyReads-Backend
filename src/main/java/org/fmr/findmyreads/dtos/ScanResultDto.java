package org.fmr.findmyreads.dtos;

import java.util.List;
import java.util.UUID;

public record ScanResultDto(
        UUID scanId,
        List<String> rawOcrTitles,
        int totalExtracted,
        int totalMatched,
        List<BookDto> recommendations,   // ranked, unread books — top pick first
        List<BookDto> alreadyRead        // books already in user's library
) {
    public static ScanResultDto empty(List<String> rawTitles) {
        return new ScanResultDto(null, rawTitles, rawTitles.size(), 0, List.of(), List.of());
    }
}