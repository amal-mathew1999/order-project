package com.example.inventory.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.inventory.common.InsufficientStockException;
import com.example.inventory.common.NotFoundException;
import com.example.inventory.dto.AvailabilityResponse;
import com.example.inventory.dto.ReservationResponse;
import com.example.inventory.service.StockService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StockController.class)
class StockControllerTest {

    private static final String BODY = "{\"productId\":1,\"quantity\":2}";
    private static final String ZERO = "{\"productId\":1,\"quantity\":0}";

    private final MockMvc mvc;
    @MockitoBean StockService service;

    @Autowired
    StockControllerTest(MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void availabilityReturns200() throws Exception {
        when(service.checkAvailability(1L, 2)).thenReturn(new AvailabilityResponse(1L, 2, true, new BigDecimal("10.50")));
        mvc.perform(get("/api/v1/inventory/1/availability").param("quantity", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.requestedQuantity").value(2))
                .andExpect(jsonPath("$.unitPrice").value(10.50));
    }

    @Test
    void availabilityUnknownReturns404() throws Exception {
        when(service.checkAvailability(9L, 2)).thenThrow(new NotFoundException("Product", 9L));
        mvc.perform(get("/api/v1/inventory/9/availability").param("quantity", "2"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reserveReturns200() throws Exception {
        when(service.reserve(1L, 2)).thenReturn(new ReservationResponse(1L, 2, new BigDecimal("10.50")));
        mvc.perform(post("/api/v1/inventory/reservations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.unitPrice").value(10.50));
    }

    @Test
    void reserveInsufficientReturns409ProblemJson() throws Exception {
        when(service.reserve(1L, 2)).thenThrow(new InsufficientStockException(1L, 2));
        mvc.perform(post("/api/v1/inventory/reservations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void reserveUnknownReturns404() throws Exception {
        when(service.reserve(1L, 2)).thenThrow(new NotFoundException("Product", 1L));
        mvc.perform(post("/api/v1/inventory/reservations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void reserveQuantityZeroReturns400() throws Exception {
        mvc.perform(post("/api/v1/inventory/reservations").contentType(MediaType.APPLICATION_JSON).content(ZERO))
                .andExpect(status().isBadRequest());
    }

    @Test
    void releaseReturns204() throws Exception {
        mvc.perform(post("/api/v1/inventory/releases").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent());
    }

    @Test
    void releaseUnknownReturns404() throws Exception {
        doThrow(new NotFoundException("Product", 1L)).when(service).release(1L, 2);
        mvc.perform(post("/api/v1/inventory/releases").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void releaseQuantityZeroReturns400() throws Exception {
        mvc.perform(post("/api/v1/inventory/releases").contentType(MediaType.APPLICATION_JSON).content(ZERO))
                .andExpect(status().isBadRequest());
    }
}
