package com.growercare.edge.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** SPEC-EDGE-01 D4, D5, D7 tunables ({@code gc.edge.*}). */
@ConfigurationProperties("gc.edge")
public record EdgeProperties(String corsAllowedOrigins, long maxBodyBytes, Limits limits) {

    /** A token bucket: {@code capacity} requests at once, refilled by {@code refill} per {@code period}. */
    public record Bucket(int capacity, int refill, Duration period) {

        public double refillPerMilli() {
            return (double) refill / period.toMillis();
        }
    }

    public record Limits(Bucket user, Bucket ip, Bucket otpUser, Bucket otpIp) { }

    public List<String> allowedOrigins() {
        if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
            return List.of();
        }
        return Arrays.stream(corsAllowedOrigins.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
