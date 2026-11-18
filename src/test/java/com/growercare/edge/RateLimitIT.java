package com.growercare.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.growercare.edge.support.GatewayTestBase;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SPEC-EDGE-01 R8, R9 (EDGE-E8, E9): token buckets per user, per address, and for phone verification starts. */
class RateLimitIT extends GatewayTestBase {

    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry r) {
        configure(r, java.util.Map.of());
    }

    static final String OTP_START = "/api/grower/growers/110165911145454593/verifications";

    List<HttpResponse<String>> burst(int count, java.util.function.Supplier<HttpRequest.Builder> request) throws Exception {
        var responses = new ArrayList<HttpResponse<String>>();
        for (int i = 0; i < count; i++) {
            responses.add(send(request.get()));
        }
        return responses;
    }

    /** All at once, so the burst is not spread over enough time to refill the bucket. */
    List<HttpResponse<String>> concurrently(int count, java.util.function.Supplier<HttpRequest.Builder> request) {
        var futures = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> HTTP.sendAsync(request.get().build(), HttpResponse.BodyHandlers.ofString())).toList();
        return futures.stream().map(java.util.concurrent.CompletableFuture::join).toList();
    }

    @Test
    void EDGE_E8_oneUserAboveTheBurstIsLimited() throws Exception {
        var user = UUID.randomUUID();
        long start = System.nanoTime();
        var responses = concurrently(60, () -> as(user, "/api/iam/me"));
        double seconds = (System.nanoTime() - start) / 1e9;

        var limited = responses.stream().filter(r -> r.statusCode() == 429).toList();
        long passed = responses.stream().filter(r -> r.statusCode() == 200).count();
        assertThat(limited).isNotEmpty().allSatisfy(r -> {
            assertThat(r.headers().firstValue("Retry-After")).isPresent();
            assertThat(r.body()).contains("\"code\":\"RATE_LIMITED\"");
        });
        assertThat(passed).isLessThanOrEqualTo((long) (40 + 20 * seconds) + 1);
        assertThat(IAM.seen()).hasSize((int) passed);
    }

    @Test
    void EDGE_E8_anotherUserIsNotAffected() throws Exception {
        var flooder = UUID.randomUUID();
        concurrently(60, () -> as(flooder, "/api/iam/me"));
        assertThat(send(as(UUID.randomUUID(), "/api/iam/me")).statusCode()).isEqualTo(200);
    }

    @Test
    void EDGE_E8_requestsWithoutATokenAreLimitedPerAddress() throws Exception {
        var responses = concurrently(30, () -> request("/api/iam/me"));
        assertThat(responses).extracting(HttpResponse::statusCode).contains(401, 429).containsOnly(401, 429);
        assertThat(hits()).isZero();
    }

    HttpRequest.Builder otpStart(UUID user) {
        return as(user, OTP_START).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
    }

    @Test
    void EDGE_E9_theSixthVerificationStartOfAUserIsRefused() throws Exception {
        GROWER.answer(202, "{}", java.util.Map.of());
        var user = UUID.randomUUID();
        var responses = burst(6, () -> otpStart(user));
        assertThat(responses.subList(0, 5)).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(202));
        assertThat(responses.get(5).statusCode()).isEqualTo(429);
        assertThat(responses.get(5).headers().firstValue("Retry-After").map(Long::parseLong).orElseThrow()).isGreaterThan(60);
        assertThat(GROWER.seen()).hasSize(5);
    }

    @Test
    void EDGE_E9_theTwentyFirstStartFromOneAddressWithinAnHourIsRefused() throws Exception {
        GROWER.answer(202, "{}", java.util.Map.of());
        var responses = burst(21, () -> otpStart(UUID.randomUUID()));
        assertThat(responses.subList(0, 20)).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(202));
        assertThat(responses.get(20).statusCode()).isEqualTo(429);
        assertThat(GROWER.seen()).hasSize(20);
    }

    @Test
    void EDGE_E9_otherCallsOfTheUserAreNotCountedAsStarts() throws Exception {
        var user = UUID.randomUUID();
        burst(10, () -> as(user, "/api/grower/growers/me"));
        GROWER.answer(202, "{}", java.util.Map.of());
        assertThat(send(otpStart(user)).statusCode()).isEqualTo(202);
    }
}
