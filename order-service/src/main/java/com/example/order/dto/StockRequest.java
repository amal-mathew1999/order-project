package com.example.order.dto;

/** Body for inventory reservations and releases. */
public record StockRequest(Long productId, Integer quantity) {
}
