package com.example.payment.dto;

import com.example.payment.domain.Payment;
import java.math.BigDecimal;

public record PaymentResponse(Long id, Long orderId, BigDecimal amount, String status) {
    public static PaymentResponse from(Payment e) {
        return new PaymentResponse(e.getId(), e.getOrderId(), e.getAmount(), e.getStatus());
    }
}
