package com.example.order.service;

import com.example.order.common.NotFoundException;
import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import com.example.order.dto.OrderRequest;
import com.example.order.repository.OrderRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Short, separate transactions for order state changes so remote calls never run inside a transaction. */
@Component
public class OrderStatusUpdater {

    private final OrderRepository repository;

    public OrderStatusUpdater(OrderRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Order createPending(OrderRequest r) {
        Order e = new Order();
        e.setCustomerId(r.customerId());
        e.setProductId(r.productId());
        e.setQuantity(r.quantity());
        e.setStatus(OrderStatus.PENDING);
        return repository.save(e);
    }

    @Transactional
    public Order markRejected(Long id, String reason) {
        Order e = find(id);
        e.setStatus(OrderStatus.REJECTED);
        e.setFailureReason(reason);
        return repository.save(e);
    }

    @Transactional
    public Order markConfirmed(Long id, BigDecimal unitPrice, BigDecimal total, Long paymentId) {
        Order e = find(id);
        e.setUnitPrice(unitPrice);
        e.setTotalAmount(total);
        e.setPaymentId(paymentId);
        e.setStatus(OrderStatus.CONFIRMED);
        return repository.save(e);
    }

    @Transactional
    public Order markPaymentFailed(Long id, BigDecimal unitPrice, BigDecimal total, String reason) {
        Order e = find(id);
        e.setUnitPrice(unitPrice);
        e.setTotalAmount(total);
        e.setStatus(OrderStatus.PAYMENT_FAILED);
        e.setFailureReason(reason);
        return repository.save(e);
    }

    @Transactional
    public Order markFailed(Long id, String reason) {
        Order e = find(id);
        e.setStatus(OrderStatus.FAILED);
        e.setFailureReason(reason);
        return repository.save(e);
    }

    private Order find(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Order", id));
    }
}
