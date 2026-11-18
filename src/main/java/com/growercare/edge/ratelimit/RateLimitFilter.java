package com.growercare.edge.ratelimit;

import com.growercare.edge.config.EdgeProperties;
import com.growercare.edge.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SPEC-EDGE-01 R8–R10. Runs after the bearer token was validated and before authorisation: a valid token takes from its
 * user's bucket, a request without a token from the client address' bucket (an invalid token was already answered 401
 * by the bearer filter). Starting a
 * phone verification also takes from the stricter OTP buckets (user, then address). Redis down: OTP starts fail closed
 * (503), everything else fails open (D6).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** {@code POST /api/grower/growers/{id}/verifications} (SPEC-GRW-03). */
    static final Pattern OTP_START = Pattern.compile("^/api/grower/growers/[^/]+/verifications/?$");

    private final TokenBuckets buckets;
    private final EdgeProperties.Limits limits;

    public RateLimitFilter(TokenBuckets buckets, EdgeProperties.Limits limits) {
        this.buckets = buckets;
        this.limits = limits;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String user = auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getSubject() : null;
        String ip = request.getRemoteAddr();
        // without a valid token the request ends in 401 anyway; only the address bucket applies to it
        boolean otp = user != null && "POST".equals(request.getMethod()) && OTP_START.matcher(request.getRequestURI()).matches();

        TokenBuckets.Decision decision;
        try {
            decision = user != null ? buckets.take("user", user, limits.user()) : buckets.take("ip", ip, limits.ip());
            if (decision.allowed() && otp) {
                decision = buckets.take("otp-user", user, limits.otpUser());
                if (decision.allowed()) {
                    decision = buckets.take("otp-ip", ip, limits.otpIp());
                }
            }
        } catch (RuntimeException redisDown) {
            if (otp) {
                log.warn("rate-limit store unavailable; refusing a phone verification start (fail closed)");
                Problems.write(response, 503, "Service Unavailable", "DEPENDENCY_UNAVAILABLE");
                return;
            }
            log.warn("rate-limit store unavailable; request allowed (fail open)");
            chain.doFilter(request, response);
            return;
        }
        if (!decision.allowed()) {
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            Problems.write(response, 429, "Too Many Requests", "RATE_LIMITED");
            return;
        }
        chain.doFilter(request, response);
    }
}
