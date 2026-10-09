package com.example.order.dto;

import java.math.BigDecimal;

public record ChargeResponse(Long id, Long orderId, BigDecimal amount, String status) {
}
