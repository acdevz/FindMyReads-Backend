package org.fmr.findmyreads.utils;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves the current authenticated user's ID from the SecurityContext.
 *
 * JwtAuthFilter sets the principal as the userId string (UUID).
 * This utility extracts and parses it — single source of truth for all controllers.
 *
 * Controllers call: SecurityUtil.getCurrentUserId()
 * No HttpServletRequest parameter needed anymore — auth is handled.
 */
@Component
public class SecurityUtil {

    /**
     * Extract the current user's UUID from the SecurityContext.
     * Throws if no authentication is present (should never happen on protected routes
     * since SecurityConfig rejects unauthenticated requests before controllers run).
     */
    public static UUID getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalStateException("No authenticated user in SecurityContext.");
        }

        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "SecurityContext principal is not a valid UUID: " + auth.getName());
        }
    }
}