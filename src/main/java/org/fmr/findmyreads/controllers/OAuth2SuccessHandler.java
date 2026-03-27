package org.fmr.findmyreads.controllers;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.services.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Called by Spring Security after a successful OAuth2 login.
 *
 * Flow:
 *   1. Extract email, name, avatar, provider from the OAuth2 principal
 *   2. Upsert user in DB via AuthService.loginOrRegisterOAuth()
 *   3. Issue JWT pair
 *   4. Redirect to frontend with access + refresh tokens in query params
 *      (frontend extracts them and stores in memory / httpOnly cookie)
 *
 * Redirect URL: {frontendUrl}/auth/callback?token=...&refresh=...&onboarding=...
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final AuthService authService;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest  request,
            HttpServletResponse response,
            Authentication      authentication) throws IOException {

        Object principal = authentication.getPrincipal();

        String email    = null;
        String name     = null;
        String avatar   = null;
        String provider = null;
        String oauthId  = null;

        if (principal instanceof OidcUser oidcUser) {
            // Google return OIDC tokens — richest data
            email    = oidcUser.getEmail();
            name     = oidcUser.getFullName();
            avatar   = oidcUser.getPicture();
            oauthId  = oidcUser.getSubject();
            provider = extractProvider(authentication);

        } else if (principal instanceof OAuth2User oauth2User) {
            email    = oauth2User.getAttribute("email");
            name     = oauth2User.getAttribute("name");
            avatar   = oauth2User.getAttribute("picture");
            oauthId  = oauth2User.getName();
            provider = extractProvider(authentication);
        }

        if (email == null) {
            log.error("OAuth2 login succeeded but no email in principal — aborting");
            response.sendRedirect(frontendUrl + "/auth/error?reason=no_email");
            return;
        }

        try {
            AuthService.TokenPairDto tokens = authService.loginOrRegisterOAuth(
                    email, name, avatar, provider, oauthId);

            String redirectUrl = UriComponentsBuilder
                    .fromUriString(frontendUrl + "/auth/callback")
                    .queryParam("onboarding",  tokens.onboardingDone())
                    .build().toUriString();

            AuthController.addTokenCookies(response, tokens.accessToken(), tokens.refreshToken());

            /* Workaround for Oauth flow in React App -
             * Spring Security keeps the user authenticated in the session after successful OAuth login,
             * which causes issues when the frontend tries to exchange the access token for user info.
             * To fix this, we clear the security context and invalidate the session immediately after issuing tokens.
             * The frontend can then use the access token without interference from Spring Security's session management.
             */
            SecurityContextHolder.clearContext();

            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }

            Cookie cookie = new Cookie("JSESSIONID", null);
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            cookie.setMaxAge(0);
            response.addCookie(cookie);

            response.sendRedirect(redirectUrl);

        } catch (Exception e) {
            log.error("OAuth2 user upsert failed: {}", e.getMessage());
            response.sendRedirect(frontendUrl + "/auth/error?reason=server_error");
        }
    }

    private String extractProvider(Authentication authentication) {
        // Spring stores the provider registration ID in the authority or client name
        if (authentication instanceof
                org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken token) {
            return token.getAuthorizedClientRegistrationId(); // "google" or "apple"
        }
        return "unknown";
    }
}