package com.growercare.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.growercare.edge.support.GatewayTestBase;
import com.growercare.testsupport.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** SPEC-EDGE-01 R11 (EDGE-E11): a stopped service is 503, a slow one 504; other routes are unaffected. */
class UpstreamFailureIT extends GatewayTestBase {

    @DynamicPropertySource
    static void catalogStopped(DynamicPropertyRegistry r) {
        configure(r, java.util.Map.of("CATALOG_BASE_URL", "http://127.0.0.1:9"));     // nothing listens on port 9
    }

    @Test
    void EDGE_E11_aStoppedServiceIs503AndOtherRoutesStillWork() throws Exception {
        var down = send(as(TestJwts.CARE_IN, "/api/catalog/products?country=IN"));
        assertThat(down.statusCode()).isEqualTo(503);
        assertThat(down.body()).contains("\"code\":\"DEPENDENCY_UNAVAILABLE\"").doesNotContain("127.0.0.1").doesNotContain("Exception");
        assertThat(send(as(TestJwts.CARE_IN, "/api/iam/me")).statusCode()).isEqualTo(200);
    }

    @Test
    void EDGE_E11_aServiceSlowerThanThreeSecondsIs504() throws Exception {
        GROWER.delay(5_000);
        long start = System.nanoTime();
        var slow = send(as(TestJwts.CARE_IN, "/api/grower/growers/me"));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(slow.statusCode()).isEqualTo(504);
        assertThat(slow.body()).contains("\"code\":\"DEPENDENCY_TIMEOUT\"");
        assertThat(millis).isLessThan(4_500);
    }
}
