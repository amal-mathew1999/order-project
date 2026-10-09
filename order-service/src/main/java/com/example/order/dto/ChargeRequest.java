package com.example.order.dto;

import java.math.BigDecimal;

public record ChargeRequest(Long orderId, BigDecimal amount) {
}
