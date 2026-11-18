package com.growercare.edge.config;

import com.growercare.edge.ratelimit.RateLimitFilter;
import com.growercare.edge.ratelimit.TokenBuckets;
import com.growercare.edge.web.Problems;
import com.growercare.edge.web.RequestHygieneFilter;
import com.growercare.edge.web.RouteGuardFilter;
import com.growercare.edge.web.UpstreamErrorFilter;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * SPEC-EDGE-01 D3/D4/D5/D7. Filter order, outermost first: request hygiene (413, headers, request id) → upstream errors
 * (503/504) → route guard (404) → Spring Security (CORS, bearer token, rate limits, authenticated) → gateway routes.
 */
@Configuration
public class EdgeSecurity {

    /** The audience every user token for the platform's APIs carries (service tokens carry gc-internal). */
    public static final String API_AUDIENCE = "gc-api";

    @Bean
    static RequiredEnvironment requiredEnvironment() {
        return new RequiredEnvironment();
    }

    /** D3: signature (JWKS, cached by Nimbus), expiry, issuer and the API audience. */
    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwksUri,
                          @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(validator(issuer));
        return decoder;
    }

    public static OAuth2TokenValidator<Jwt> validator(String issuer) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(API_AUDIENCE)));
    }

    @Bean
    SecurityFilterChain edge(HttpSecurity http, EdgeProperties props, TokenBuckets buckets) throws Exception {
        AuthenticationEntryPoint unauthenticated = (request, response, e) ->
                Problems.write(response, 401, "Unauthorized", "UNAUTHENTICATED");
        http.csrf(c -> c.ignoringRequestMatchers("/api/**"))           // bearer tokens only, no cookies or sessions
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(c -> c.configurationSource(cors(props)))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> { }).authenticationEntryPoint(unauthenticated))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated))
                .addFilterAfter(new RateLimitFilter(buckets, props.limits()), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** D7: the web apps' origins only; the headers the APIs use; tokens in headers, never credentials (cookies). */
    static CorsConfigurationSource cors(EdgeProperties props) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(props.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match", "Idempotency-Key",
                RequestHygieneFilter.REQUEST_ID, "traceparent", "Accept-Language"));
        config.setExposedHeaders(List.of("ETag", "Location", "Retry-After", RequestHygieneFilter.REQUEST_ID));
        config.setAllowCredentials(false);
        config.setMaxAge(Duration.ofHours(1));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    FilterRegistrationBean<RequestHygieneFilter> requestHygiene(EdgeProperties props) {
        var registration = new FilterRegistrationBean<>(new RequestHygieneFilter(props.maxBodyBytes()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    FilterRegistrationBean<UpstreamErrorFilter> upstreamErrors() {
        var registration = new FilterRegistrationBean<>(new UpstreamErrorFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RouteGuardFilter> routeGuard() {
        var registration = new FilterRegistrationBean<>(new RouteGuardFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }
}
