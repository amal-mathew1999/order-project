package com.example.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.order.domain.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** Real schema round-trip: catches DDL problems (reserved words, column types) that mocks cannot. */
@SpringBootTest
@Transactional
class OrderRepositoryTest {

    @Autowired OrderRepository repository;

    @Test
    void savesAndReloads() {
        Order e = new Order();
        e.setCustomerId(1L);
        e.setProductId(1L);
        e.setQuantity(1);
        Long id = repository.saveAndFlush(e).getId();
        assertThat(repository.findById(id)).isPresent();
    }
}
