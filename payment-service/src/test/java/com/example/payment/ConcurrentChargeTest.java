package com.example.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payment.dto.ChargeRequest;
import com.example.payment.repository.PaymentRepository;
import com.example.payment.service.PaymentService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Real threads, real commits (deliberately not @Transactional): the DB constraint must hold under a race. */
@SpringBootTest
class ConcurrentChargeTest {

    private static final int THREADS = 30;

    @Autowired PaymentService service;
    @Autowired PaymentRepository repository;

    @Test
    void parallelChargesForSameOrderYieldSingleSuccessAndSameId() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.charge(new ChargeRequest(777L, new BigDecimal("5.00"))).id();
            }));
        }
        start.countDown();
        Set<Long> ids = new HashSet<>();
        for (Future<Long> f : futures) {
            ids.add(f.get());
        }
        pool.shutdown();

        long successRows = repository.findAll().stream()
                .filter(p -> p.getOrderId() == 777L && "SUCCESS".equals(p.getStatus()))
                .count();
        assertThat(successRows).isEqualTo(1);
        assertThat(ids).hasSize(1);
    }
}
