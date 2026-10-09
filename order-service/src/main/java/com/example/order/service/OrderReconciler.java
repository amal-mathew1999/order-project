package com.example.order.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically retries failed releases and resolves PAYMENT_UNKNOWN orders. */
@Component
public class OrderReconciler {

    private final OrderService service;

    public OrderReconciler(OrderService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${order.reconcile.fixed-delay:30000}")
    public void run() {
        service.retryPendingReleases();
        service.reconcileUnknownPayments();
    }
}
