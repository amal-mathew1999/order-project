package com.example.inventory.dto;

import java.math.BigDecimal;

public record ReservationResponse(Long orderId, Long productId, int quantity, BigDecimal unitPrice) {
}
