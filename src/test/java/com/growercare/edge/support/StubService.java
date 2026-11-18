package com.growercare.edge.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** A downstream service for gateway tests (JDK HTTP server): records every request, answers as configured. */
public final class StubService {

    /** One request as the service saw it; header names lower-cased. */
    public record Seen(String method, String path, String query, Map<String, List<String>> headers, int bodyBytes) {

        public String header(String name) {
            var values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? null : values.getFirst();
        }
    }

    private final HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile int status;
    private volatile String body;
    private volatile Map<String, String> responseHeaders;
    private volatile long delayMillis;

    public StubService() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            var headers = new TreeMap<String, List<String>>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), List.copyOf(v)));
            int bytes = exchange.getRequestBody().readAllBytes().length;
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), headers, bytes));
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            var out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            responseHeaders.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        server.start();
        reset();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        seen.clear();
        status = 200;
        body = "{\"ok\":true}";
        responseHeaders = Map.of();
        delayMillis = 0;
    }

    public StubService answer(int status, String body, Map<String, String> headers) {
        this.status = status;
        this.body = body;
        this.responseHeaders = Map.copyOf(headers);
        return this;
    }

    public void delay(long millis) {
        this.delayMillis = millis;
    }

    public List<Seen> seen() {
        return List.copyOf(seen);
    }

    public Seen last() {
        return seen.getLast();
    }
}
