package com.example.payment.service;

import com.example.payment.domain.Payment;
import com.example.payment.domain.PaymentStatus;
import com.example.payment.dto.ChargeRequest;
import com.example.payment.repository.PaymentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Own transaction boundary so a constraint violation surfaces to the caller, outside the failed transaction. */
@Component
public class PaymentWriter {

    private final PaymentRepository repository;

    public PaymentWriter(PaymentRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Payment insert(ChargeRequest r) {
        boolean success = r.amount().compareTo(PaymentService.CHARGE_LIMIT) <= 0;
        Payment e = new Payment();
        e.setOrderId(r.orderId());
        e.setAmount(r.amount());
        e.setStatus((success ? PaymentStatus.SUCCESS : PaymentStatus.FAILED).name());
        e.setSuccessOrderId(success ? r.orderId() : null);
        return repository.saveAndFlush(e);
    }
}
