package com.example.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real routing through the gateway: lb:// URIs resolve via the simple discovery client to stub backends
 * that echo which service answered.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutesTest {

    private static final Map<String, HttpServer> BACKENDS = Map.of(
            "order-service", stub("order-service"),
            "inventory-service", stub("inventory-service"),
            "payment-service", stub("payment-service"));

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void discovery(DynamicPropertyRegistry registry) {
        BACKENDS.forEach((service, server) -> registry.add(
                "spring.cloud.discovery.client.simple.instances." + service + "[0].uri",
                () -> "http://localhost:" + server.getAddress().getPort()));
    }

    @AfterAll
    static void stopBackends() {
        BACKENDS.values().forEach(s -> s.stop(0));
    }

    @ParameterizedTest
    @CsvSource({
            "GET,  /api/v1/orders,                    order-service",
            "POST, /api/v1/orders,                    order-service",
            "GET,  /api/v1/orders/7,                  order-service",
            "GET,  /api/v1/inventory/1/availability,  inventory-service",
            "GET,  /api/v1/inventory-items,           inventory-service",
            "GET,  /api/v1/inventory-items/3,         inventory-service",
            "GET,  /api/v1/payments/5,                payment-service",
    })
    void routesPublicPathsToTheRightService(String method, String path, String service) throws Exception {
        HttpResponse<String> response = send(method, path);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(service + " " + method + " " + path);
    }

    @ParameterizedTest
    @CsvSource({"/api/v1/inventory/reservations", "/api/v1/inventory/releases"})
    void internalInventoryEndpointsAreNotRouted(String path) throws Exception {
        assertThat(send("POST", path).statusCode()).isEqualTo(404);
    }

    @Test
    void unknownPathsAreNotRouted() throws Exception {
        assertThat(send("GET", "/api/v1/unknown").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> send(String method, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer stub(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = (name + " " + exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath())
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
