package com.example.payment.repository;

import com.example.payment.domain.Payment;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Page<Payment> findByOrderId(Long orderId, Pageable pageable);

    Optional<Payment> findFirstByOrderIdAndStatus(Long orderId, String status);
}
