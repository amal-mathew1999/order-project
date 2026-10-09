package com.example.order.dto;

import java.math.BigDecimal;

public record ReservationResponse(Long orderId, Long productId, Integer quantity, BigDecimal unitPrice) {
}
