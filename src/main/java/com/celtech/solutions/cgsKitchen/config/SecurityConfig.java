package com.celtech.solutions.cgsKitchen.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Two filter chains:
 *
 * <ol>
 *   <li><b>API chain</b> (matches {@code /api/**}, {@code /webhooks/**},
 *   {@code /actuator/**}). Stateless, API-key authenticated, CSRF disabled.</li>
 *
 *   <li><b>Storefront chain</b> (everything else). Cookie-session, form
 *   login, CSRF enabled.</li>
 * </ol>
 *
 * <p>Login outcome handling lives in {@link AuthHandlers}, which is a
 * standalone {@code @Component} so that its {@link com.celtech.solutions.cgsKitchen.services.user.UserService}
 * dependency doesn't cycle back through this config (UserService needs
 * the PasswordEncoder bean defined here).
 */
@Configuration
public class SecurityConfig {

    private final AppProperties props;

    public SecurityConfig(AppProperties props) {
        this.props = props;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        int strength = props.security().bcrypt().strength();
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(strength);

        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put("bcrypt", bcrypt);

        DelegatingPasswordEncoder delegating = new DelegatingPasswordEncoder("bcrypt", encoders);
        delegating.setDefaultPasswordEncoderForMatches(bcrypt);
        return delegating;
    }

    /**
     * ADMIN inherits CUSTOMER (and any future roles below it). One admin
     * doc with role {@code ADMIN} can hit anything a CUSTOMER can.
     */
    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("ADMIN").implies("CUSTOMER")
                .build();
    }

    // ----------------------------------------------------------------
    // 1) API filter chain — server-to-server, API key authenticated.
    // ----------------------------------------------------------------
    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**", "/webhooks/**", "/actuator/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(corsSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/webhooks/**").permitAll()
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll()
                )
                .addFilterBefore(new ApiKeyFilter(props.apiKey()),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    // ----------------------------------------------------------------
    // 2) Storefront filter chain — browser, session + form login.
    //    AuthHandlers is auto-wired here by Spring (it's a @Component).
    // ----------------------------------------------------------------
    @Bean
    @Order(2)
    public SecurityFilterChain storefrontFilterChain(HttpSecurity http, AuthHandlers handlers) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/menu", "/menu/**", "/about", "/contact", "/events").permitAll()
                        .requestMatchers("/login", "/register", "/logout").permitAll()
                        .requestMatchers("/css/**", "/scripts/**", "/images/**", "/favicon.ico").permitAll()
                        .requestMatchers("/cart/**").permitAll()
                        .requestMatchers("/checkout", "/checkout/**", "/order/**").permitAll()
                        .requestMatchers("/account/**").hasRole("CUSTOMER")
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .successHandler(handlers.success())
                        .failureHandler(handlers.failure())
                        .usernameParameter("email")
                        .passwordParameter("password")
                        .permitAll()
                )
                .requestCache(cache -> cache.requestCache(
                        new org.springframework.security.web.savedrequest.NullRequestCache()))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/?loggedOut")
                        .permitAll()
                )
                .anonymous(anon -> {});
        return http.build();
    }

    private UrlBasedCorsConfigurationSource corsSource() {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(props.corsAllowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of(
                "Content-Type", "X-API-Key", "Authorization", "X-CSRF-TOKEN", "X-Requested-With", "Stripe-Signature"));
        cors.setExposedHeaders(List.of("Location"));
        cors.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    /**
     * Validates X-API-Key header against the configured key. Only registered
     * on the API filter chain.
     */
    static class ApiKeyFilter extends OncePerRequestFilter {
        private final String expectedKey;

        ApiKeyFilter(String expectedKey) {
            this.expectedKey = expectedKey;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {

            String header = request.getHeader("X-API-Key");
            if (header == null) {
                String authHeader = request.getHeader("Authorization");
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    header = authHeader.substring(7);
                }
            }
            if (header != null && header.equals(expectedKey)) {
                var auth = new UsernamePasswordAuthenticationToken(
                        "client",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_CLIENT"))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            chain.doFilter(request, response);
        }
    }
}