package com.example.order.dto;

import com.example.order.domain.Order;
import com.example.order.domain.OrderStatus;
import java.math.BigDecimal;

public record OrderResponse(Long id, Long customerId, Long productId, Integer quantity, BigDecimal unitPrice,
        BigDecimal totalAmount, OrderStatus status, Long paymentId, String failureReason) {
    public static OrderResponse from(Order e) {
        return new OrderResponse(e.getId(), e.getCustomerId(), e.getProductId(), e.getQuantity(), e.getUnitPrice(),
                e.getTotalAmount(), e.getStatus(), e.getPaymentId(), e.getFailureReason());
    }
}
