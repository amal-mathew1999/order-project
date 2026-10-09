package com.example.order.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically retries failed releases and resolves PAYMENT_UNKNOWN and stale PENDING orders. */
@Component
public class OrderReconciler {

    private final OrderService service;
    private final Duration pendingTimeout;

    public OrderReconciler(OrderService service,
            @Value("${order.reconcile.pending-timeout:PT2M}") Duration pendingTimeout) {
        this.service = service;
        this.pendingTimeout = pendingTimeout;
    }

    @Scheduled(fixedDelayString = "${order.reconcile.fixed-delay:30000}")
    public void run() {
        service.retryPendingReleases();
        service.reconcileUnknownPayments();
        service.reconcileStalePending(pendingTimeout);
    }
}
