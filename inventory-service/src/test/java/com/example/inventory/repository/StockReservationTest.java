package com.example.inventory.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.inventory.domain.InventoryItem;
import com.example.inventory.service.StockService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class StockReservationTest {

    private final InventoryItemRepository repo;
    private final StockService service;

    @Autowired
    StockReservationTest(InventoryItemRepository repo, StockService service) {
        this.repo = repo;
        this.service = service;
    }

    private void seed(long productId, int qty) {
        InventoryItem i = new InventoryItem();
        i.setProductId(productId);
        i.setProductName("p" + productId);
        i.setAvailableQuantity(qty);
        i.setUnitPrice(new BigDecimal("10.50"));
        repo.save(i);
    }

    private int stock(long productId) {
        return repo.findByProductId(productId).orElseThrow().getAvailableQuantity();
    }

    @Test
    void conditionalUpdateNeverDrivesStockNegative() {
        seed(100L, 3);
        assertThat(repo.reserve(100L, 5)).isZero();
        assertThat(stock(100L)).isEqualTo(3);
        assertThat(repo.reserve(100L, 3)).isEqualTo(1);
        assertThat(stock(100L)).isZero();
        assertThat(repo.reserve(100L, 1)).isZero();
        assertThat(stock(100L)).isZero();
    }

    @Test
    void releaseAddsStockBack() {
        seed(101L, 1);
        service.release(101L, 4);
        assertThat(stock(101L)).isEqualTo(5);
    }
}
