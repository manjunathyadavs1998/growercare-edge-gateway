package com.growercare.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.edge.support.GatewayTestBase;
import com.growercare.edge.support.StubService;
import com.growercare.testsupport.TestJwts;
import java.io.ByteArrayInputStream;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-EDGE-01 R1–R7, R12 (EDGE-E1…E7, E12): routing, token checks, header hygiene, body limit, CORS. */
class GatewayRoutingIT extends GatewayTestBase {

    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry r) {
        configure(r, java.util.Map.of());
    }

    @Test
    void EDGE_E1_aRequestIsForwardedAndTheAnswerPassedBack() throws Exception {
        CASES.answer(200, "{\"items\":[]}", Map.of("ETag", "\"7\"", "Location", "/cases/42"));
        var token = TestJwts.user(TestJwts.CARE_IN).bearer();
        var response = send(request("/api/cases/cases?limit=1").header("Authorization", token));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"items\":[]}");
        assertThat(response.headers().firstValue("ETag")).hasValue("\"7\"");
        assertThat(response.headers().firstValue("Location")).hasValue("/cases/42");
        var seen = CASES.last();
        assertThat(seen.method()).isEqualTo("GET");
        assertThat(seen.path()).isEqualTo("/cases");
        assertThat(seen.query()).isEqualTo("limit=1");
        assertThat(seen.header("Authorization")).isEqualTo(token);
    }

    static Stream<Arguments> prefixes() {
        return Stream.of(
                argumentSet("EDGE-E2 identity-access", "/api/iam/me", IAM, "/me"),
                argumentSet("EDGE-E2 grower", "/api/grower/growers/me", GROWER, "/growers/me"),
                argumentSet("EDGE-E2 catalog", "/api/catalog/products?country=IN", CATALOG, "/products"),
                argumentSet("EDGE-E2 case (singular prefix)", "/api/case/cases/1", CASES, "/cases/1"));
    }

    @ParameterizedTest
    @MethodSource("prefixes")
    void eachPrefixReachesItsServiceWithoutThePrefix(String path, StubService service, String expectedPath) throws Exception {
        var response = send(as(TestJwts.CARE_IN, path));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(service.seen()).hasSize(1);
        assertThat(service.last().path()).isEqualTo(expectedPath);
        assertThat(hits()).isEqualTo(1);
    }

    static Stream<Arguments> neverRouted() {
        return Stream.of(
                argumentSet("EDGE-E3 an internal API", "/api/grower/internal/growers/1"),
                argumentSet("EDGE-E3 a dev endpoint of an unrouted service", "/api/notify/dev/mailbox"),
                argumentSet("EDGE-E3 an unknown prefix", "/api/unknown/x"),
                argumentSet("EDGE-E3 an internal API in other case", "/api/iam/Internal/principals"),
                argumentSet("EDGE-E3 an encoded dot segment", "/api/iam/%2e%2e/internal/principals"),
                argumentSet("EDGE-E3 outside /api", "/cases"));
    }

    @ParameterizedTest
    @MethodSource("neverRouted")
    void internalDevAndUnknownPathsAre404(String path) throws Exception {
        var response = send(as(TestJwts.CARE_IN, path));
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"");
        assertThat(hits()).isZero();
    }

    static Stream<Arguments> badCredentials() {
        return Stream.of(
                argumentSet("EDGE-E4 no token", (Supplier<String>) () -> null),
                argumentSet("EDGE-E4 expired", (Supplier<String>) () -> TestJwts.user(TestJwts.CARE_IN).expiresAt(Instant.now().minusSeconds(120)).bearer()),
                argumentSet("EDGE-E4 forged signature", (Supplier<String>) () -> TestJwts.user(TestJwts.CARE_IN).forgedSignature().bearer()),
                argumentSet("EDGE-E4 a service token (wrong audience)", (Supplier<String>) () -> TestJwts.service("case-service").bearer()));
    }

    @ParameterizedTest
    @MethodSource("badCredentials")
    void invalidOrMissingTokensAre401(Supplier<String> authorization) throws Exception {
        var request = request("/api/iam/me");
        var header = authorization.get();
        if (header != null) {
            request.header("Authorization", header);
        }
        var response = send(request);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"UNAUTHENTICATED\"").doesNotContain("Exception");
        assertThat(hits()).isZero();
    }

    @Test
    void EDGE_E5_healthNeedsNoToken() throws Exception {
        assertThat(send(request("/actuator/health")).statusCode()).isEqualTo(200);
    }

    @Test
    void EDGE_E6_routingAndIdentityHeadersAreRemovedAndTheRequestIdKept() throws Exception {
        var requestId = UUID.randomUUID().toString();
        var traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        var response = send(as(TestJwts.CARE_IN, "/api/iam/me")
                .header("X-Country", "BR").header("X-Shard", "7").header("X-User-Id", "1").header("X-Forwarded-User", "admin")
                .header("X-Internal-Caller", "case-service").header("X-Request-Id", requestId).header("traceparent", traceparent));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Request-Id")).hasValue(requestId);
        var seen = IAM.last();
        assertThat(seen.headers().keySet()).doesNotContain("x-country", "x-shard", "x-user-id", "x-forwarded-user", "x-internal-caller");
        assertThat(seen.header("X-Request-Id")).isEqualTo(requestId);
        assertThat(seen.header("traceparent")).isEqualTo(traceparent);
    }

    @Test
    void EDGE_E6_aRequestIdThatIsNotAUuidIsReplaced() throws Exception {
        var response = send(as(TestJwts.CARE_IN, "/api/iam/me").header("X-Request-Id", "<script>"));
        var issued = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(UUID.fromString(issued)).isNotNull();
        assertThat(IAM.last().header("X-Request-Id")).isEqualTo(issued);
    }

    static Stream<Arguments> largeBodies() {
        var twoMb = new byte[2 * 1024 * 1024];
        return Stream.of(
                argumentSet("EDGE-E7 2 MB with Content-Length", (Supplier<HttpRequest.BodyPublisher>) () -> HttpRequest.BodyPublishers.ofByteArray(twoMb)),
                argumentSet("EDGE-E7 2 MB chunked (no Content-Length)",
                        (Supplier<HttpRequest.BodyPublisher>) () -> HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(twoMb))));
    }

    @ParameterizedTest
    @MethodSource("largeBodies")
    void bodiesOverOneMegabyteAre413(Supplier<HttpRequest.BodyPublisher> body) throws Exception {
        var response = send(as(TestJwts.CARE_IN, "/api/cases/cases").header("Content-Type", "application/json").POST(body.get()));
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"code\":\"PAYLOAD_TOO_LARGE\"");
        assertThat(CASES.seen()).allSatisfy(s -> assertThat(s.bodyBytes()).isLessThanOrEqualTo(1024 * 1024));
    }

    @Test
    void EDGE_E12_aPreflightFromTheWebAppIsAllowed() throws Exception {
        var response = send(request("/api/cases/cases").method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", WEB_APP).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue(WEB_APP);
        assertThat(hits()).isZero();
    }

    @Test
    void EDGE_E12_anotherOriginIsRefused() throws Exception {
        var response = send(request("/api/cases/cases").method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://evil.test").header("Access-Control-Request-Method", "POST"));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void EDGE_E12_theOriginIsNotForwardedSoOnlyTheGatewayAnswersCors() throws Exception {
        IAM.answer(200, "{}", Map.of("Access-Control-Allow-Origin", "http://service-chose-this.test"));
        var response = send(as(TestJwts.CARE_IN, "/api/iam/me").header("Origin", WEB_APP));
        assertThat(IAM.last().header("Origin")).isNull();
        assertThat(response.headers().allValues("Access-Control-Allow-Origin")).contains(WEB_APP);
    }
}
