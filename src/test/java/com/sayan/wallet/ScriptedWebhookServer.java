package com.sayan.wallet;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;

// A local merchant endpoint whose answer for attempt N is scripted by the test.
final class ScriptedWebhookServer implements AutoCloseable {

    record Attempt(long receivedAtNanos, String body, String signature, int answeredWith) {}

    // how to answer one attempt: an HTTP status, optionally after a delay
    record Answer(int status, Duration delay) {
        static Answer status(int status) { return new Answer(status, Duration.ZERO); }
        static Answer hang(Duration delay) { return new Answer(200, delay); }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Attempt> attempts = new CopyOnWriteArrayList<>();

    ScriptedWebhookServer(IntFunction<Answer> script) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(executor);
        server.createContext("/hook", exchange -> {
            long now = System.nanoTime();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Answer answer;
            synchronized (attempts) {
                answer = script.apply(attempts.size() + 1);
                attempts.add(new Attempt(now, body,
                        exchange.getRequestHeaders().getFirst("X-Wallet-Signature"), answer.status()));
            }
            try {
                Thread.sleep(answer.delay().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(answer.status(), -1);
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/hook";
    }

    List<Attempt> attempts() {
        return List.copyOf(attempts);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
