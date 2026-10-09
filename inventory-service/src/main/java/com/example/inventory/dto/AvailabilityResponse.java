package com.example.inventory.dto;

import java.math.BigDecimal;

public record AvailabilityResponse(Long productId, int requestedQuantity, boolean available, BigDecimal unitPrice) {
}
