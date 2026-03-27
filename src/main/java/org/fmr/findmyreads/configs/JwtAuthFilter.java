package org.fmr.findmyreads.configs;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.services.JwtService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Runs once per request. Extracts the Bearer JWT from Authorization header,
 * validates it, and sets the Authentication in SecurityContextHolder.
 *
 * Principal = userId (UUID string) — extracted by SecurityUtils.getCurrentUserId()
 * downstream in controllers and services.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException
    {
        String token = extractJwtFromRequest(request);

        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            Claims claims = jwtService.validateAndParse(token);
            UUID userId = jwtService.extractUserId(claims);

            // only set if not already authenticated (e.g. OAuth2 set it already)
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                var auth = new UsernamePasswordAuthenticationToken(
                        userId.toString(),   // principal — SecurityUtils reads this
                        null,
                        List.of()            // authorities — empty until roles are needed
                );
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }

        } catch (JwtException e) {
            log.debug("Invalid JWT: {}", e.getMessage());
            // don't set authentication — request proceeds as anonymous
            // SecurityConfig will reject it if the endpoint requires auth
        }

        filterChain.doFilter(request, response);
    }

    private String extractJwtFromRequest(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("accessToken".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}