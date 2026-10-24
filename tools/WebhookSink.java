import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public class WebhookSink {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(9999), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            System.out.println(Instant.now() + " " + exchange.getRequestMethod() + " " + path);
            System.out.println("  signature: " + exchange.getRequestHeaders().getFirst("X-Wallet-Signature"));
            System.out.println("  body: " + body);

            // any path starting with /fail answers 500 so we can test retries
            int status = path.startsWith("/fail") ? 500 : 200;
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        System.out.println("webhook sink listening on :9999");
    }
}