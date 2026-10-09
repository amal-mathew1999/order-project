package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.order.client.InventoryClient;
import com.example.order.client.PaymentClient;
import com.example.order.common.BadGatewayException;
import com.example.order.common.ServiceUnavailableException;
import com.example.order.domain.OrderStatus;
import com.example.order.dto.ChargeRequest;
import com.example.order.dto.ChargeResponse;
import com.example.order.dto.OrderRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.dto.ReleaseRequest;
import com.example.order.dto.ReservationResponse;
import com.example.order.dto.ReserveRequest;
import com.example.order.service.OrderService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Contract test: real Feign clients and the Spring-configured circuit breaker registry against a JDK HttpServer
 * stub that plays inventory-service and payment-service (contract v2). Discovery is the simple in-memory one.
 */
@SpringBootTest(properties = "order.reconcile.fixed-delay=3600000")
class FeignContractTest {

    private static final String PROBLEM = "application/problem+json";
    private static final Pattern ORDER_ID = Pattern.compile("\"orderId\":(\\d+)");

    static HttpServer server;
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static final AtomicInteger reserveStatus = new AtomicInteger();
    static final AtomicInteger reserveDelayMs = new AtomicInteger();
    static final AtomicInteger chargeStatus = new AtomicInteger();
    static final AtomicInteger chargeDelayMs = new AtomicInteger();
    static final AtomicReference<Function<String, String>> chargeBody = new AtomicReference<>();
    static final AtomicReference<Function<String, String>> lookupBody = new AtomicReference<>();

    static {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/api/v1/inventory/reservations", ex -> {
                String body = record(ex);
                sleep(reserveDelayMs.get());
                int st = reserveStatus.get();
                if (st == 200) {
                    reply(ex, 200, "application/json",
                            "{\"orderId\":" + orderId(body) + ",\"productId\":1,\"quantity\":2,\"unitPrice\":10.50}");
                } else {
                    reply(ex, st, PROBLEM, "{\"type\":\"about:blank\",\"title\":\"t\",\"status\":" + st + ",\"detail\":\"d\"}");
                }
            });
            server.createContext("/api/v1/inventory/releases", ex -> {
                record(ex);
                ex.sendResponseHeaders(204, -1);
                ex.close();
            });
            server.createContext("/api/v1/payments/charges", ex -> {
                String body = record(ex);
                sleep(chargeDelayMs.get());
                int st = chargeStatus.get();
                if (st == 201) {
                    reply(ex, 201, "application/json", chargeBody.get().apply(body));
                } else {
                    reply(ex, st, PROBLEM, "{\"type\":\"about:blank\",\"title\":\"t\",\"status\":" + st + ",\"detail\":\"d\"}");
                }
            });
            server.createContext("/api/v1/payments", ex -> {
                record(ex);
                String q = ex.getRequestURI().getRawQuery();
                reply(ex, 200, "application/json", lookupBody.get().apply(q.substring(q.indexOf('=') + 1)));
            });
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String record(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String uri = ex.getRequestURI().getRawPath()
                + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
        calls.add(ex.getRequestMethod() + " " + uri + " " + body);
        return body;
    }

    private static void reply(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String orderId(String body) {
        Matcher m = ORDER_ID.matcher(body);
        return m.find() ? m.group(1) : "0";
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String base = "http://localhost:" + server.getAddress().getPort();
        r.add("spring.cloud.discovery.client.simple.instances.inventory-service[0].uri", () -> base);
        r.add("spring.cloud.discovery.client.simple.instances.payment-service[0].uri", () -> base);
    }

    @Autowired OrderService orders;
    @Autowired InventoryClient inventory;
    @Autowired PaymentClient payment;
    @Autowired CircuitBreakerRegistry registry;

    @BeforeEach
    void reset() {
        calls.clear();
        reserveStatus.set(200);
        reserveDelayMs.set(0);
        chargeStatus.set(201);
        chargeDelayMs.set(0);
        chargeBody.set(body -> "{\"id\":5,\"orderId\":" + orderId(body) + ",\"amount\":21.00,\"status\":\"SUCCESS\"}");
        lookupBody.set(id -> "[]");
        registry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    private static OrderRequest order() {
        return new OrderRequest(7L, 1L, 2);
    }

    @Test
    void reserveSendsAndDecodesV2Contract() {
        ReservationResponse r = inventory.reserve(new ReserveRequest(7L, 1L, 2));

        assertThat(r).isEqualTo(new ReservationResponse(7L, 1L, 2, new BigDecimal("10.50")));
        assertThat(calls).containsExactly(
                "POST /api/v1/inventory/reservations {\"orderId\":7,\"productId\":1,\"quantity\":2}");
    }

    @Test
    void releaseSendsOrderIdAndAccepts204() {
        inventory.release(new ReleaseRequest(7L));

        assertThat(calls).containsExactly("POST /api/v1/inventory/releases {\"orderId\":7}");
    }

    @Test
    void chargeSendsAndDecodes201() {
        ChargeResponse c = payment.charge(new ChargeRequest(7L, new BigDecimal("21.00")));

        assertThat(c.id()).isEqualTo(5L);
        assertThat(c.orderId()).isEqualTo(7L);
        assertThat(c.amount()).isEqualByComparingTo("21.00");
        assertThat(c.status()).isEqualTo("SUCCESS");
        assertThat(calls).containsExactly("POST /api/v1/payments/charges {\"orderId\":7,\"amount\":21.00}");
    }

    @Test
    void lookupUsesGetWithQueryAndDecodesList() {
        lookupBody.set(id -> "[{\"id\":5,\"orderId\":" + id + ",\"amount\":21.00,\"status\":\"SUCCESS\"}]");

        List<ChargeResponse> found = payment.findByOrderId(7L);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).status()).isEqualTo("SUCCESS");
        assertThat(found.get(0).orderId()).isEqualTo(7L);
        assertThat(calls).containsExactly("GET /api/v1/payments?orderId=7 ");
    }

    @Test
    void problemJson409And404AreMappedToFeignExceptions() {
        reserveStatus.set(409);
        assertThatThrownBy(() -> inventory.reserve(new ReserveRequest(7L, 1L, 2)))
                .isInstanceOf(FeignException.Conflict.class)
                .hasMessageContaining("409");
        reserveStatus.set(404);
        assertThatThrownBy(() -> inventory.reserve(new ReserveRequest(7L, 1L, 2)))
                .isInstanceOf(FeignException.NotFound.class);
    }

    @Test
    void readTimeoutSurfacesAsFeignException() {
        reserveDelayMs.set(6500);
        long start = System.nanoTime();

        assertThatThrownBy(() -> inventory.reserve(new ReserveRequest(7L, 1L, 2))).isInstanceOf(FeignException.class);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isBetween(4_500L, 6_400L);
    }

    @Test
    void manyConflictsNeverOpenTheBreaker() {
        reserveStatus.set(409);
        for (int i = 0; i < 12; i++) {
            assertThat(orders.create(order()).status()).isEqualTo(OrderStatus.REJECTED);
        }
        CircuitBreaker cb = registry.circuitBreaker("inventory-service");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void placeOrderEndToEndConfirms() {
        OrderResponse o = orders.create(order());

        assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(o.totalAmount()).isEqualByComparingTo("21.00");
        assertThat(o.paymentId()).isEqualTo(5L);
        assertThat(calls).hasSize(2);
    }

    @Test
    void chargeForAnotherOrderIsDeclinedAndReleased() {
        chargeBody.set(body -> "{\"id\":5,\"orderId\":999999,\"amount\":21.00,\"status\":\"SUCCESS\"}");

        OrderResponse o = orders.create(order());

        assertThat(o.status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(calls.get(calls.size() - 1)).startsWith("POST /api/v1/inventory/releases {\"orderId\":");
    }

    @Test
    void payment400ReleasesAndRaisesBadGateway() {
        chargeStatus.set(400);

        assertThatThrownBy(() -> orders.create(order())).isInstanceOf(BadGatewayException.class);

        assertThat(calls.get(calls.size() - 1)).startsWith("POST /api/v1/inventory/releases");
    }

    @Test
    void payment5xxWithEmptyLookupReleasesAndRaisesUnavailable() {
        chargeStatus.set(500);

        assertThatThrownBy(() -> orders.create(order())).isInstanceOf(ServiceUnavailableException.class);

        assertThat(calls).anyMatch(c -> c.startsWith("GET /api/v1/payments?orderId="));
        assertThat(calls.get(calls.size() - 1)).startsWith("POST /api/v1/inventory/releases");
    }

    @Test
    void paymentReadTimeoutWithSuccessfulLookupConfirms() {
        chargeDelayMs.set(6500);
        lookupBody.set(id -> "[{\"id\":5,\"orderId\":" + id + ",\"amount\":21.00,\"status\":\"SUCCESS\"}]");

        OrderResponse o = orders.create(order());

        assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(o.paymentId()).isEqualTo(5L);
        assertThat(calls).noneMatch(c -> c.contains("/releases"));
    }
}
