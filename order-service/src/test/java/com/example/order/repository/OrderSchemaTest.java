package com.example.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Real-schema checks that mocks cannot catch. */
@SpringBootTest
@Transactional
class OrderSchemaTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired OrderRepository repository;

    /**
     * Regression: a native ENUM column (Hibernate's default for @Enumerated on MySQL/H2) is never altered by
     * ddl-auto, so adding a status (PAYMENT_UNKNOWN) broke existing databases with "Data truncated".
     */
    @Test
    void statusIsStoredAsVarchar() {
        String type = jdbc.queryForObject(
                "select data_type from information_schema.columns where table_name = 'ORDERS' and column_name = 'STATUS'",
                String.class);
        assertThat(type).isEqualTo("CHARACTER VARYING");
    }

    @Test
    void stalePendingQueryFindsOldAndUntimestampedPendingOrdersOnly() {
        Order old = pending(Instant.now().minusSeconds(600));
        Order fresh = pending(Instant.now());
        Order confirmed = pending(Instant.now().minusSeconds(600));
        confirmed.setStatus(OrderStatus.CONFIRMED);
        repository.saveAllAndFlush(java.util.List.of(old, fresh, confirmed));
        jdbc.update("update orders set created_at = null where id = ?", fresh.getId()); // pre-migration row

        assertThat(repository.findStalePending(Instant.now().minusSeconds(120)))
                .extracting(Order::getId).containsExactlyInAnyOrder(old.getId(), fresh.getId());
    }

    private static Order pending(Instant createdAt) {
        Order o = new Order();
        o.setCustomerId(1L);
        o.setProductId(1L);
        o.setQuantity(1);
        o.setStatus(OrderStatus.PENDING);
        o.setCreatedAt(createdAt);
        return o;
    }
}
