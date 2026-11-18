package com.growercare.edge.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SPEC-EDGE-01 R3: internal and dev paths, unknown prefixes and anything outside {@code /api/**} and health are 404 and
 * never forwarded. Runs before authentication, so a probe learns nothing more with a token than without one.
 */
public class RouteGuardFilter extends OncePerRequestFilter {

    static final Set<String> PREFIXES = Set.of("iam", "cases", "case", "grower", "catalog");
    private static final Set<String> NEVER_ROUTED = Set.of("internal", "dev");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (allowed(request.getRequestURI())) {
            chain.doFilter(request, response);
        } else {
            Problems.write(response, 404, "Not Found", "NOT_FOUND");
        }
    }

    static boolean allowed(String uri) {
        if (uri.equals("/actuator/health") || uri.startsWith("/actuator/health/")) {
            return true;
        }
        var segments = Arrays.stream(uri.split("/")).filter(s -> !s.isEmpty()).toList();
        if (segments.size() < 2 || !segments.get(0).equals("api") || !PREFIXES.contains(segments.get(1))) {
            return false;
        }
        // encoded separators or dot segments would let a path reach something other than what it looks like
        if (uri.contains("%") || uri.contains(";") || segments.contains("..") || segments.contains(".")) {
            return false;
        }
        return segments.stream().skip(2).map(s -> s.toLowerCase(java.util.Locale.ROOT)).noneMatch(NEVER_ROUTED::contains);
    }
}
