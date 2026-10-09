package com.example.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record StockRequest(@NotNull Long productId, @NotNull @Min(1) Integer quantity) {
}
