package com.example.inventory.dto;

import java.math.BigDecimal;

public record ReservationResponse(Long productId, int quantity, BigDecimal unitPrice) {
}
