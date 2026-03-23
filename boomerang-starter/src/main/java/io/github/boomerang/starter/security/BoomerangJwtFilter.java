package io.github.boomerang.starter.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Servlet filter that validates a JWT Bearer token on every request to {@code /sync/**}.
 * On success, the JWT {@code sub} claim is set as the principal in the
 * {@link org.springframework.security.core.context.SecurityContext} so that controllers
 * can retrieve it via {@code @AuthenticationPrincipal String callerId}.
 *
 * <p>On failure (missing header, malformed token, expired token) the filter writes a JSON
 * error body and short-circuits the filter chain with {@code 401 Unauthorized}.
 */
@Slf4j
public class BoomerangJwtFilter extends OncePerRequestFilter {

    private final byte[] secretBytes;

    public BoomerangJwtFilter(String jwtSecret) {
        this.secretBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeUnauthorized(response, "Authentication required");
            return;
        }

        String token = header.substring(7);
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(Keys.hmacShaKeyFor(secretBytes))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String callerId = claims.getSubject();
            if (callerId == null || callerId.isBlank()) {
                writeUnauthorized(response, "JWT sub claim is missing");
                return;
            }

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(callerId, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth);
            filterChain.doFilter(request, response);

        } catch (JwtException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            writeUnauthorized(response, "Invalid or expired token");
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
