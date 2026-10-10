package com.cooker.t18;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 仅进程内替身服务，记录实际接收的POST；不持久化请求文本或鉴权。 */
public final class LocalSupplier implements AutoCloseable {
    final HttpServer server;
    final ExecutorService executor = Executors.newCachedThreadPool();
    public final AtomicInteger received = new AtomicInteger();
    public volatile int status = 200;
    public volatile boolean drop;
    public volatile int delay;
    public volatile String lastTextType;
    public LocalSupplier() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            received.incrementAndGet();
            var payload = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
            lastTextType = payload.path("parameters").path("text_type").asText();
            if (drop) { exchange.close(); return; }
            if (delay > 0) try { Thread.sleep(delay); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            String vector = "[1," + "0,".repeat(1022) + "0]";
            String body = exchange.getRequestURI().getPath().equals("/query")
                ? "{\"output\":{\"embeddings\":[{\"text_index\":0,\"embedding\":" + vector + "}]}}"
                : "{\"id\":\"local\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"finish_reason\":\"stop\"}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status >= 300 && status < 400) exchange.getResponseHeaders().set("Location", uri("/unexpected-redirect").toString());
            try { exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
            catch (java.io.IOException ignored) { /* 超时后替身响应不再被客户端接收。 */ }
            finally { exchange.close(); }
        });
        server.start();
    }
    public URI uri(String path) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
