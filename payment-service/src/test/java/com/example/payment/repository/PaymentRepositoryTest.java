package com.example.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payment.domain.Payment;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** Real schema round-trip: catches DDL problems (reserved words, column types) that mocks cannot. */
@SpringBootTest
@Transactional
class PaymentRepositoryTest {

    @Autowired PaymentRepository repository;

    @Test
    void savesAndReloads() {
        Payment e = new Payment();
        e.setOrderId(1L);
        e.setAmount(new BigDecimal("10.50"));
        e.setStatus("sample");
        Long id = repository.saveAndFlush(e).getId();
        assertThat(repository.findById(id)).isPresent();
    }
}
