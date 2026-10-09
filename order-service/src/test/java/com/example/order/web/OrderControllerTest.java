package com.example.order.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.order.common.NotFoundException;
import com.example.order.common.ServiceUnavailableException;
import com.example.order.domain.OrderStatus;
import com.example.order.dto.OrderRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.service.OrderService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final String BODY = "{\"customerId\":1,\"productId\":1,\"quantity\":2}";

    @Autowired MockMvc mvc;
    @MockitoBean OrderService service;

    @Test
    void createReturns201WithStatus() throws Exception {
        when(service.create(any(OrderRequest.class))).thenReturn(new OrderResponse(1L, 1L, 1L, 2,
                new BigDecimal("10.50"), new BigDecimal("21.00"), OrderStatus.CONFIRMED, 5L, null));
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/orders/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentId").value(5))
                .andExpect(jsonPath("$.totalAmount").value(21.00));
    }

    @Test
    void createWithEmptyBodyReturns400() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").exists());
    }

    @Test
    void createWithZeroQuantityReturns400() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":1,\"productId\":1,\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.quantity").exists());
    }

    @Test
    void createWhenDownstreamUnavailableReturns503ProblemDetail() throws Exception {
        when(service.create(any(OrderRequest.class)))
                .thenThrow(new ServiceUnavailableException("Inventory service unavailable", null));
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    void putAndDeleteAreNotSupported() throws Exception {
        mvc.perform(put("/api/v1/orders/1").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/v1/orders/1")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void getMissingReturns404() throws Exception {
        when(service.get(99L)).thenThrow(new NotFoundException("Order", 99L));
        mvc.perform(get("/api/v1/orders/99"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void badRequestsReturnProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/orders/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/v1/orders").param("size", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
