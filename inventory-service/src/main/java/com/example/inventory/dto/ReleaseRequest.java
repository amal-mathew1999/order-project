package com.example.inventory.dto;

import jakarta.validation.constraints.NotNull;

public record ReleaseRequest(@NotNull Long orderId) {
}
