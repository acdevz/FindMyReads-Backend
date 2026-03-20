package org.fmr.findmyreads.utils;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class SecurityUtil {

    public static final String USER_ID_HEADER = "X-User-Id";

    /**
     * Extract userId from the X-User-Id header.
     * Throws IllegalArgumentException (→ 404) if header is missing or malformed.
     */
    public static UUID getCurrentUserId(HttpServletRequest request) {
        String header = request.getHeader(USER_ID_HEADER);
        if (header == null || header.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required header: " + USER_ID_HEADER);
        }
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid UUID in header " + USER_ID_HEADER + ": " + header);
        }
    }
}
