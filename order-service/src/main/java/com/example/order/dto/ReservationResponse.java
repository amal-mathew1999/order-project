package com.example.order.dto;

import java.math.BigDecimal;

public record ReservationResponse(Long productId, Integer quantity, BigDecimal unitPrice) {
}
