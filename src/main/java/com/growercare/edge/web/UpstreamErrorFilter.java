package com.growercare.edge.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SPEC-EDGE-01 R11: an unreachable service is 503 and a slow one 504, as Problem Details naming no internals (no host,
 * port or exception text reaches the client).
 */
public class UpstreamErrorFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            if (RequestHygieneFilter.causedBy(e, RequestHygieneFilter.BodyTooLarge.class)) {
                throw e;                                   // answered by RequestHygieneFilter (413)
            }
            if (timedOut(e)) {
                Problems.write(response, 504, "Gateway Timeout", "DEPENDENCY_TIMEOUT");
            } else if (unreachable(e)) {
                Problems.write(response, 503, "Service Unavailable", "DEPENDENCY_UNAVAILABLE");
            } else {
                throw e;
            }
        }
    }

    static boolean timedOut(Throwable e) {
        return RequestHygieneFilter.causedBy(e, SocketTimeoutException.class)
                || (RequestHygieneFilter.causedBy(e, HttpTimeoutException.class)
                    && !RequestHygieneFilter.causedBy(e, HttpConnectTimeoutException.class));
    }

    static boolean unreachable(Throwable e) {
        return RequestHygieneFilter.causedBy(e, ConnectException.class)
                || RequestHygieneFilter.causedBy(e, UnknownHostException.class)
                || RequestHygieneFilter.causedBy(e, HttpConnectTimeoutException.class)
                || RequestHygieneFilter.causedBy(e, ResourceAccessException.class);
    }
}
