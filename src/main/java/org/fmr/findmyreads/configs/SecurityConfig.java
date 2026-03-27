package org.fmr.findmyreads.configs;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fmr.findmyreads.controllers.OAuth2SuccessHandler;
import org.fmr.findmyreads.services.AuthService;
import org.fmr.findmyreads.utils.SecurityUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.UUID;

@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final OAuth2SuccessHandler oauth2SuccessHandler;
    private final AuthService authService;
    @Value("${app.frontend-url}")
    private String frontendUrl;

    // =========================================================================
    // CHAIN 1: OAUTH2 LOGIN (Stateful - Needs Session)
    // =========================================================================
    @Bean
    @Order(1)
    public SecurityFilterChain oauth2SecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/oauth2/**", "/login/**")
                .csrf(AbstractHttpConfigurer::disable)

                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())

                .oauth2Login(oauth -> oauth
                        .loginPage(frontendUrl + "/login")
                        .successHandler(oauth2SuccessHandler)
                );

        return http.build();
    }

    // =========================================================================
    // CHAIN 2: REST API (Strictly Stateless JWT)
    // =========================================================================
    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**", "/actuator/health")
                .csrf(AbstractHttpConfigurer::disable)

                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/actuator/health").permitAll()
                        .anyRequest().authenticated()
                )

                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .addLogoutHandler((request, response, authentication) -> {
                            if (authentication != null) {
                                try {
                                    UUID userId = SecurityUtil.getCurrentUserId();
                                    authService.logout(userId);
                                    log.info("Successfully logged out user: {}", userId);
                                } catch (Exception e) {
                                    log.error("Error during business logic logout", e);
                                }
                            }
                            Cookie refreshCookie = new Cookie("refreshToken", null);
                            refreshCookie.setPath("/api/auth/refresh");
                            refreshCookie.setHttpOnly(true);
                            refreshCookie.setMaxAge(0);
                            response.addCookie(refreshCookie);
                        })
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID", "accessToken")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                        })
                );

        return http.build();
    }
}