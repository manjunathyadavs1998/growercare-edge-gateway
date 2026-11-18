package com.growercare.edge.support;

import com.growercare.testsupport.RedisTestContainer;
import com.growercare.testsupport.TestJwts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * The gateway on a random port in front of four stub services, with a real Redis (Testcontainers) for the buckets.
 * Tokens come from {@link TestJwts}; its decoder applies the same checks as production (issuer, expiry, gc-api).
 * Every test starts with empty buckets.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayTestBase.Tokens.class)
public abstract class GatewayTestBase {

    public static final StubService IAM = new StubService();
    public static final StubService CASES = new StubService();
    public static final StubService GROWER = new StubService();
    public static final StubService CATALOG = new StubService();
    public static final String WEB_APP = "http://localhost:5173";

    protected static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * Each test class registers its configuration through this, from its own {@code @DynamicPropertySource}: Spring
     * would otherwise let the base class' values win over a subclass' for the same key.
     */
    protected static void configure(DynamicPropertyRegistry r, java.util.Map<String, String> overrides) {
        overrides.forEach((k, v) -> r.add(k, () -> v));
        java.util.function.BiConsumer<String, java.util.function.Supplier<Object>> add = (k, v) -> {
            if (!overrides.containsKey(k)) {
                r.add(k, v);
            }
        };
        add.accept("OIDC_ISSUER_URI", () -> TestJwts.ISSUER);
        add.accept("OIDC_JWKS_URI", () -> "http://127.0.0.1:9/unused-jwks");
        add.accept("IAM_BASE_URL", IAM::url);
        add.accept("CASE_BASE_URL", CASES::url);
        add.accept("GROWER_BASE_URL", GROWER::url);
        add.accept("CATALOG_BASE_URL", CATALOG::url);
        add.accept("REDIS_HOST", RedisTestContainer::host);
        add.accept("REDIS_PORT", RedisTestContainer::port);
        add.accept("REDIS_PASSWORD", () -> RedisTestContainer.PASSWORD);
        add.accept("GC_CORS_ALLOWED_ORIGINS", () -> WEB_APP);
    }

    @TestConfiguration
    static class Tokens {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwts.decoder(List.of("gc-api"));
        }
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected StringRedisTemplate redis;

    @BeforeEach
    protected void freshState() {
        List.of(IAM, CASES, GROWER, CATALOG).forEach(StubService::reset);
        var keys = redis.keys("rl:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    protected HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
    }

    protected HttpRequest.Builder as(UUID user, String path) {
        return request(path).header("Authorization", TestJwts.user(user).bearer());
    }

    protected HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected static int hits() {
        return IAM.seen().size() + CASES.seen().size() + GROWER.seen().size() + CATALOG.seen().size();
    }
}
