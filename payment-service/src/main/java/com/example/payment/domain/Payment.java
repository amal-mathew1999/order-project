package com.example.payment.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String status;

    /** Set to orderId only when status is SUCCESS; the unique constraint allows one SUCCESS per order. */
    @Column(unique = true)
    private Long successOrderId;

    public Long getId() { return id; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getSuccessOrderId() { return successOrderId; }
    public void setSuccessOrderId(Long successOrderId) { this.successOrderId = successOrderId; }
}
