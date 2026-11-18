package com.growercare.edge.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SPEC-EDGE-01 D4 / R6 / R7: removes client headers that could steer routing or identity, gives every request an
 * {@code X-Request-Id} (the client's when it is a UUID) and refuses bodies over the limit with 413.
 */
public class RequestHygieneFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID = "X-Request-Id";
    private static final Pattern UUID_TEXT = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final long maxBodyBytes;

    public RequestHygieneFilter(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    /** Headers no client may send through the edge (lower case; {@code *} = prefix). */
    static boolean stripped(String name) {
        var n = name.toLowerCase(Locale.ROOT);
        return n.equals("x-country") || n.equals("x-shard") || n.equals("x-forwarded-user")
                || n.startsWith("x-user-") || n.startsWith("x-internal-");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            Problems.write(response, 413, "Payload Too Large", "PAYLOAD_TOO_LARGE");
            return;
        }
        var clientId = request.getHeader(REQUEST_ID);
        var requestId = clientId != null && UUID_TEXT.matcher(clientId).matches() ? clientId : UUID.randomUUID().toString();
        response.setHeader(REQUEST_ID, requestId);
        try {
            chain.doFilter(new Sanitized(request, requestId, maxBodyBytes), response);
        } catch (IOException | ServletException | RuntimeException e) {
            if (causedBy(e, BodyTooLarge.class)) {
                Problems.write(response, 413, "Payload Too Large", "PAYLOAD_TOO_LARGE");
                return;
            }
            throw e;
        }
    }

    static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (var t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    /** Thrown while reading a body without a (truthful) Content-Length once it passes the limit. */
    static final class BodyTooLarge extends IOException {
        BodyTooLarge() {
            super("request body over the limit");
        }
    }

    private static final class Sanitized extends HttpServletRequestWrapper {

        private final String requestId;
        private final long maxBodyBytes;

        Sanitized(HttpServletRequest request, String requestId, long maxBodyBytes) {
            super(request);
            this.requestId = requestId;
            this.maxBodyBytes = maxBodyBytes;
        }

        @Override
        public String getHeader(String name) {
            if (REQUEST_ID.equalsIgnoreCase(name)) {
                return requestId;
            }
            return stripped(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (REQUEST_ID.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(requestId));
            }
            return stripped(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            var names = Collections.list(super.getHeaderNames()).stream()
                    .filter(n -> !stripped(n) && !REQUEST_ID.equalsIgnoreCase(n)).collect(java.util.stream.Collectors.toList());
            names.add(REQUEST_ID);
            return Collections.enumeration(names);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new Limited(super.getInputStream(), maxBodyBytes);
        }
    }

    private static final class Limited extends ServletInputStream {

        private final ServletInputStream in;
        private final long max;
        private long read;

        Limited(ServletInputStream in, long max) {
            this.in = in;
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws BodyTooLarge {
            read += n;
            if (read > max) {
                throw new BodyTooLarge();
            }
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
