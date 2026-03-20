package org.fmr.findmyreads.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Sends a bookshelf image to Gemini Flash and extracts visible book titles.
 * Prompt is engineered to return ONLY a newline-separated list of titles —
 * no extra commentary — so parsing is deterministic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiVisionService {

    private final ChatModel chatModel;

    private static final String EXTRACTION_PROMPT = """
            You are a book title extractor. Examine this image of a bookshelf or
            pile of books carefully.

            Return ONLY the title & author of books whose spines or covers are clearly
            readable. One title & author per line. No numbering, no bullets, no extra commentary.

            If a title is partially obscured and you are less than 70%% confident,
            skip it. If no titles are readable, return the single word: NONE
            """;

    /**
     * Extract book titles from a shelf image.
     *
     * @param imageFile multipart image uploaded by the user
     * @return list of raw title strings as returned by Gemini — may be empty
     * @throws IOException if the image bytes cannot be read
     */
    public List<String> extractTitles(MultipartFile imageFile) throws IOException {
        log.debug("Sending image to Gemini Vision: {} bytes", imageFile.getSize());

        byte[] imageBytes = imageFile.getBytes();
        String mimeType = resolveMimeType(imageFile.getContentType());

        Media imageMedia = new Media(
                MimeTypeUtils.parseMimeType(mimeType),
                imageFile.getResource()
        );

        UserMessage message = new UserMessage.Builder()
                .text(EXTRACTION_PROMPT)
                .media(List.of(imageMedia))
                .build();
        Prompt prompt = new Prompt(List.of(message));

        String response = chatModel.call(prompt)
                .getResult()
                .getOutput()
                .getText();

        return parseResponse(response);
    }

    // -------------------------------------------------------------------------

    private List<String> parseResponse(String raw) {
        if (raw == null || raw.isBlank()) return List.of();

        String trimmed = raw.strip();
        if (trimmed.equalsIgnoreCase("NONE")) return List.of();

        List<String> titles = new ArrayList<>();
        for (String line : trimmed.split("\\r?\\n")) {
            String clean = line.strip();
            // skip empty lines and any line that looks like a preamble sentence
            if (!clean.isEmpty() && clean.split("\\s+").length <= 12) {
                titles.add(clean);
            }
        }

        log.debug("Gemini extracted {} titles", titles.size());
        return titles;
    }

    private String resolveMimeType(String contentType) {
        if (contentType == null) return "image/jpeg";
        return switch (contentType.toLowerCase()) {
            case "image/png"  -> "image/png";
            case "image/webp" -> "image/webp";
            case "image/gif"  -> "image/gif";
            default           -> "image/jpeg";
        };
    }
}