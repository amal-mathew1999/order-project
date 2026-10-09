package com.example.payment.web;

import com.example.payment.dto.ChargeRequest;
import com.example.payment.dto.PaymentRequest;
import com.example.payment.dto.PaymentResponse;
import com.example.payment.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody PaymentRequest request) {
        PaymentResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/payments/" + created.id())).body(created);
    }

    @PostMapping("/charges")
    public ResponseEntity<PaymentResponse> charge(@Valid @RequestBody ChargeRequest request) {
        PaymentResponse charged = service.charge(request);
        return ResponseEntity.created(URI.create("/api/v1/payments/" + charged.id())).body(charged);
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @GetMapping
    public List<PaymentResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(page, size);
    }

    @PutMapping("/{id}")
    public PaymentResponse update(@PathVariable Long id, @Valid @RequestBody PaymentRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
