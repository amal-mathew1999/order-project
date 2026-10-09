package com.example.payment.service;

import com.example.payment.common.NotFoundException;
import com.example.payment.domain.Payment;
import com.example.payment.domain.PaymentStatus;
import com.example.payment.dto.ChargeRequest;
import com.example.payment.dto.PaymentRequest;
import com.example.payment.dto.PaymentResponse;
import com.example.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PaymentService {

    static final BigDecimal CHARGE_LIMIT = new BigDecimal("10000.00");

    private final PaymentRepository repository;

    public PaymentService(PaymentRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public PaymentResponse create(PaymentRequest r) {
        Payment e = new Payment();
        apply(e, r);
        return PaymentResponse.from(repository.save(e));
    }

    @Transactional
    public PaymentResponse charge(ChargeRequest r) {
        var existing = repository.findFirstByOrderIdAndStatus(r.orderId(), PaymentStatus.SUCCESS.name());
        if (existing.isPresent()) {
            return PaymentResponse.from(existing.get());
        }
        Payment e = new Payment();
        e.setOrderId(r.orderId());
        e.setAmount(r.amount());
        e.setStatus((r.amount().compareTo(CHARGE_LIMIT) <= 0 ? PaymentStatus.SUCCESS : PaymentStatus.FAILED).name());
        return PaymentResponse.from(repository.save(e));
    }

    public PaymentResponse get(Long id) {
        return PaymentResponse.from(find(id));
    }

    public List<PaymentResponse> list(int page, int size) {
        return repository.findAll(PageRequest.of(page, size)).map(PaymentResponse::from).getContent();
    }

    @Transactional
    public PaymentResponse update(Long id, PaymentRequest r) {
        Payment e = find(id);
        apply(e, r);
        return PaymentResponse.from(e);
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(find(id));
    }

    private Payment find(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Payment", id));
    }

    private static void apply(Payment e, PaymentRequest r) {
        e.setOrderId(r.orderId());
        e.setAmount(r.amount());
        e.setStatus(r.status());
    }
}
