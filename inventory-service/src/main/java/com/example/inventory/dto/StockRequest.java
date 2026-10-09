package com.example.inventory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record StockRequest(@NotNull Long orderId, @NotNull Long productId,
        @NotNull @Min(1) @Max(10000) Integer quantity) {
}
