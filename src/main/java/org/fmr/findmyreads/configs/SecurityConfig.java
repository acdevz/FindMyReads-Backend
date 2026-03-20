package org.fmr.findmyreads.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration.
 *
 * CURRENT STATE: all endpoints permitted — auth handled via X-User-Id header.
 * WHEN AUTH IS ADDED:
 *   1. Add JwtFilter bean
 *   2. Replace permitAll() with authenticated()
 *   3. Add: .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
 *   4. Add public routes: /api/auth/register, /api/auth/login
 *   5. Replace SecurityUtils.getCurrentUserId(request) in controllers
 *      with SecurityUtils.getCurrentUserId() reading from SecurityContext
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                );

        return http.build();
    }
}