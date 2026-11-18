package com.growercare.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.growercare.edge.support.GatewayTestBase;
import com.growercare.testsupport.TestJwts;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** SPEC-EDGE-01 R10 (EDGE-E10): without the bucket store, verification starts fail closed and the rest fails open. */
class RedisDownIT extends GatewayTestBase {

    @DynamicPropertySource
    static void redisUnreachable(DynamicPropertyRegistry r) {
        configure(r, Map.of("REDIS_HOST", "127.0.0.1", "REDIS_PORT", "9"));            // nothing listens on port 9
    }

    @BeforeEach
    @Override
    protected void freshState() {
        java.util.List.of(IAM, CASES, GROWER, CATALOG).forEach(com.growercare.edge.support.StubService::reset);
    }

    @Test
    void EDGE_E10_aVerificationStartIs503() throws Exception {
        GROWER.answer(202, "{}", Map.of());
        var response = send(as(UUID.randomUUID(), "/api/grower/growers/110165911145454593/verifications")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")));
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(GROWER.seen()).isEmpty();
    }

    @Test
    void EDGE_E10_otherRequestsAreForwarded() throws Exception {
        assertThat(send(as(TestJwts.CARE_IN, "/api/iam/me")).statusCode()).isEqualTo(200);
    }
}
