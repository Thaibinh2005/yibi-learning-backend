package com.yibi.backend.config;

import com.yibi.backend.security.CustomUserDetailsService;
import com.yibi.backend.security.JwtAuthEntryPoint;
import com.yibi.backend.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security Configuration — Stateless JWT-based API security.
 *
 * RBAC Rules:
 * - /api/auth/**          → Public (login, register, refresh)
 * - /api/admin/**         → ROLE_ADMIN only (@PreAuthorize enforces per-method)
 * - /api/learning/**      → ROLE_USER + ROLE_ADMIN
 * - /api/exam/**          → ROLE_USER + ROLE_ADMIN
 * - /api/speech/**        → ROLE_USER + ROLE_ADMIN
 * - Everything else       → Authenticated
 *
 * @EnableMethodSecurity enables @PreAuthorize on controller methods.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)  // Enables @PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final CustomUserDetailsService userDetailsService;
    private final JwtAuthEntryPoint authEntryPoint;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // Disable CSRF (stateless API, JWT-based)
                .csrf(AbstractHttpConfigurer::disable)

                // Configure CORS
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // Custom 401 handler (returns JSON)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authEntryPoint))

                // Stateless session (no HttpSession)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Authorization rules
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/courses/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/lessons/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/vocabularies/**").permitAll()

                        // Admin-only: base-level block (per-method @PreAuthorize adds fine-grained control)
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // User endpoints (both roles can access)
                        .requestMatchers("/api/learning/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/exam/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/speech/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/srs/**").hasAnyRole("USER", "ADMIN")

                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )

                // Register DaoAuthenticationProvider
                .authenticationProvider(authenticationProvider())

                // JWT filter runs before UsernamePasswordAuthenticationFilter
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                .build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);  // Strength 12 (good balance for Java 21)
    }

    /**
     * CORS configuration:
     * - Allows React dev server (localhost:5173 for Vite, :3000 for CRA)
     * - Allows all standard HTTP methods and headers
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
                "http://localhost:3000",    // Create React App
                "http://localhost:5173"     // Vite dev server
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
