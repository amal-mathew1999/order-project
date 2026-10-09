package com.example.payment.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record ChargeRequest(@NotNull Long orderId, @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount) {
}
