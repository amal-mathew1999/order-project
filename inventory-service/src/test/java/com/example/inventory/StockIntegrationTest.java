package com.example.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.inventory.domain.InventoryItem;
import com.example.inventory.repository.InventoryItemRepository;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Real HTTP, real threads, no test-managed transaction. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StockIntegrationTest {

    @LocalServerPort int port;
    @Autowired InventoryItemRepository repo;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private void seed(long productId, int qty) {
        InventoryItem i = new InventoryItem();
        i.setProductId(productId);
        i.setProductName("p" + productId);
        i.setAvailableQuantity(qty);
        i.setUnitPrice(new BigDecimal("1.00"));
        repo.save(i);
    }

    private int stock(long productId) {
        return repo.findByProductId(productId).orElseThrow().getAvailableQuantity();
    }

    private String reserveBody(long orderId, long productId) {
        return "{\"orderId\":" + orderId + ",\"productId\":" + productId + ",\"quantity\":1}";
    }

    private List<Integer> parallel(int n, java.util.function.IntFunction<String> bodyFor) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                String body = bodyFor.apply(k);
                fs.add(pool.submit(() -> post("/api/v1/inventory/reservations", body).statusCode()));
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : fs) {
                codes.add(f.get());
            }
            return codes;
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void fiftyParallelReservesAgainstStockTenGiveExactlyTenSuccesses() throws Exception {
        seed(700L, 10);
        List<Integer> codes = parallel(50, k -> reserveBody(1000 + k, 700L));
        assertThat(codes.stream().filter(c -> c == 200).count()).isEqualTo(10);
        assertThat(codes.stream().filter(c -> c == 409).count()).isEqualTo(40);
        assertThat(stock(700L)).isZero();
    }

    @Test
    void sameOrderRepeatedInParallelDecrementsOnce() throws Exception {
        seed(701L, 10);
        List<Integer> codes = parallel(20, k -> reserveBody(5000, 701L));
        assertThat(codes).allMatch(c -> c == 200);
        assertThat(stock(701L)).isEqualTo(9);
    }

    @Test
    void releaseTwiceRestoresOnceAndUnknownOrderIsNoOp() throws Exception {
        seed(702L, 5);
        assertThat(post("/api/v1/inventory/reservations", reserveBody(6000, 702L)).statusCode()).isEqualTo(200);
        assertThat(stock(702L)).isEqualTo(4);
        assertThat(post("/api/v1/inventory/releases", "{\"orderId\":6000}").statusCode()).isEqualTo(204);
        assertThat(post("/api/v1/inventory/releases", "{\"orderId\":6000}").statusCode()).isEqualTo(204);
        assertThat(stock(702L)).isEqualTo(5);
        assertThat(post("/api/v1/inventory/releases", "{\"orderId\":424242}").statusCode()).isEqualTo(204);
        assertThat(stock(702L)).isEqualTo(5);
    }

    @Test
    void duplicateProductIdReturns409ProblemJson() throws Exception {
        String item = "{\"productId\":703,\"productName\":\"x\",\"availableQuantity\":1,\"unitPrice\":1.00}";
        assertThat(post("/api/v1/inventory-items", item).statusCode()).isEqualTo(201);
        HttpResponse<String> dup = post("/api/v1/inventory-items", item);
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(dup.headers().firstValue("Content-Type").orElse("")).contains("problem+json");
    }
}
