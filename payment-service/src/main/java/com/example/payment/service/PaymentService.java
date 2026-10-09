package com.example.payment.service;

import com.example.payment.common.NotFoundException;
import com.example.payment.domain.PaymentStatus;
import com.example.payment.dto.ChargeRequest;
import com.example.payment.dto.PaymentResponse;
import com.example.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    static final BigDecimal CHARGE_LIMIT = new BigDecimal("10000.00");

    private final PaymentRepository repository;
    private final PaymentWriter writer;

    public PaymentService(PaymentRepository repository, PaymentWriter writer) {
        this.repository = repository;
        this.writer = writer;
    }

    /** Deliberately no transaction here: the insert runs in the writer's transaction so a unique violation can be handled here. */
    public PaymentResponse charge(ChargeRequest r) {
        String success = PaymentStatus.SUCCESS.name();
        var existing = repository.findFirstByOrderIdAndStatus(r.orderId(), success);
        if (existing.isPresent()) {
            return PaymentResponse.from(existing.get());
        }
        try {
            return PaymentResponse.from(writer.insert(r));
        } catch (DataIntegrityViolationException ex) {
            return repository.findFirstByOrderIdAndStatus(r.orderId(), success)
                    .map(PaymentResponse::from)
                    .orElseThrow(() -> ex);
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse get(Long id) {
        return repository.findById(id).map(PaymentResponse::from).orElseThrow(() -> new NotFoundException("Payment", id));
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> list(Long orderId, int page, int size) {
        var pageable = PageRequest.of(page, size);
        var result = orderId == null ? repository.findAll(pageable) : repository.findByOrderId(orderId, pageable);
        return result.map(PaymentResponse::from).getContent();
    }
}
