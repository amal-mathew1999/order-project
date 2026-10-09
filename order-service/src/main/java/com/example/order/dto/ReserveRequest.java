package com.example.order.dto;

public record ReserveRequest(Long orderId, Long productId, Integer quantity) {
}
