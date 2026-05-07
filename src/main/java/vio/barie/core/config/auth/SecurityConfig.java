package vio.barie.core.config.auth;

import vio.barie.core.config.filter.UserContextFilter;
import vio.barie.core.dto.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static vio.barie.core.utils.StringCommon.writeAsString;

@Configuration
@EnableMethodSecurity
@EnableWebSecurity
@Slf4j
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserContextFilter userContextFilter;
    private final JwtAuthConverter jwtAuthConverter;


    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.cors(cors -> {
                })
                .csrf(csrf -> csrf.disable())

                .authorizeHttpRequests(auth -> {
                    auth
                        // keep public endpoints
                        .requestMatchers("/core/public/**").permitAll()
                        .requestMatchers("/api/ingest/**",
                            "/webhook/stringee/**").permitAll()
                        // allow OpenAPI/Swagger UI and JSON (both with and without context-path)
                        .requestMatchers("/admin/bootstrap/**",
                            "/v3/api-docs/**",
                            "/swagger-ui/**",
                            "/swagger-ui.html",
                            "/swagger-ui/index.html",
                            "/core/v3/api-docs/**",
                            "/core/swagger-ui/**",
                            "/core/swagger-ui.html",
                            "/core/swagger-ui/index.html"
                        ).permitAll()
                        .requestMatchers("/api/payment/callback", "/api/payment/ipn").permitAll()
                    ;


                    auth.anyRequest().authenticated();
                })

                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(customAuthEntryPoint())
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(jwtAuthConverter)
                        )
                )

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(customAuthEntryPoint())
                        .accessDeniedHandler(customAccessDeniedHandler())
                );

        http.addFilterAfter(userContextFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationEntryPoint customAuthEntryPoint() {
        return (request, response, authException) -> {

            String path = request.getRequestURI();
            String method = request.getMethod();
            String ip = request.getRemoteAddr();

            log.error("Unauthorized - method: {}, path: {}, ip: {}, error: {}",
                    method, path, ip, authException.getMessage());

            String message = "Unauthorized";

            Throwable cause = authException.getCause();

            if (cause instanceof JwtValidationException jwtEx) {

                message = jwtEx.getErrors().stream()
                        .map(err -> switch (err.getErrorCode()) {
                            case "invalid_token" -> "Invalid token";
                            case "expired_token" -> "Token expired";
                            case "invalid_signature" -> "Invalid token signature";
                            default -> "JWT validation failed";
                        })
                        .findFirst()
                        .orElse("JWT validation failed");

            } else if (cause instanceof JwtException) {
                message = "Invalid token";

            } else if (cause instanceof AccessDeniedException) {
                message = authException.getMessage();
            } else if
            (authException instanceof InsufficientAuthenticationException) {
                message = "Missing authentication token";
            }

            Result<Object> result =
                    Result.error(HttpStatus.UNAUTHORIZED.value(), message);

            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write(writeAsString(result));
        };
    }

    @Bean
    public AccessDeniedHandler customAccessDeniedHandler() {
        return (request, response, accessDeniedException) -> {

            String path = request.getRequestURI();
            String method = request.getMethod();
            String ip = request.getRemoteAddr();

            log.error("Forbidden - method: {}, path: {}, ip: {}, error: {}",
                    method,
                    path,
                    ip,
                    accessDeniedException.getMessage(),
                    accessDeniedException
            );

            Result<Object> result = Result.error(
                    HttpStatus.FORBIDDEN.value(),
                    HttpStatus.FORBIDDEN.getReasonPhrase()
            );

            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.getWriter().write(writeAsString(result));
        };
    }

}
