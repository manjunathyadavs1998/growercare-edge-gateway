package com.growercare.edge.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The gateway's own errors as RFC 9457 Problem Details with a stable {@code code} (SPEC-EDGE-01 §3), no internals. */
public final class Problems {

    private Problems() { }

    public static void write(HttpServletResponse response, int status, String title, String code) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"code\":\"" + code + "\"}");
        response.flushBuffer();
    }
}
