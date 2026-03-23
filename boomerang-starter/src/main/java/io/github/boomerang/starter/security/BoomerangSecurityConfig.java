package io.github.boomerang.starter.security;

import io.github.boomerang.starter.config.BoomerangProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for Boomerang. Registers a stateless {@link SecurityFilterChain}
 * that protects all endpoints under {@code boomerang.base-path} with JWT authentication.
 * CSRF is disabled because the API is stateless and consumed by machine clients, not browsers.
 *
 * <p>If the consuming application defines its own {@link SecurityFilterChain} bean named
 * {@code boomerangSecurityFilterChain} (e.g. to customise request matching), this
 * configuration will be skipped via {@code @ConditionalOnMissingBean} in the
 * auto-configuration.
 */
@Configuration
public class BoomerangSecurityConfig {

    private final BoomerangProperties properties;

    public BoomerangSecurityConfig(BoomerangProperties properties) {
        this.properties = properties;
    }

    @Bean("boomerangSecurityFilterChain")
    public SecurityFilterChain boomerangSecurityFilterChain(HttpSecurity http) throws Exception {
        BoomerangJwtFilter jwtFilter = new BoomerangJwtFilter(properties.getAuth().getJwtSecret());
        String pathPattern = properties.getBasePath() + "/**";

        http
            .securityMatcher(pathPattern)
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(pathPattern).authenticated()
            );

        return http.build();
    }
}
