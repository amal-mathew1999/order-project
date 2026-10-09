package com.example.inventory.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record InventoryItemRequest(@NotNull Long productId, @NotBlank String productName, @NotNull @PositiveOrZero Integer availableQuantity, @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal unitPrice) {
}
