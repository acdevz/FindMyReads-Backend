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
            You are a book title extractor. Examine this image of a bookshelf or pile of books carefully.
            
            ### INSTRUCTIONS:
            1. Identify books where the spine or cover is clearly readable.
            2. If you are less than 70% confident due to blur or obstruction, skip the book.
            3. If no titles are readable, return only the word: NONE.
            
            ### OUTPUT FORMAT:
            Return one book per line using exactly this plain text format:
            Title - Author
            
            ### CONSTRAINTS:
            - Do NOT include brackets, angle brackets (<< >>), or quotes in the output.
            - No numbering, no bullets, and no conversational filler.
            
            ### EXAMPLE:
            The Great Gatsby - F. Scott Fitzgerald
            1984 - George Orwell
            
            ### EXTRACTED BOOKS:
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