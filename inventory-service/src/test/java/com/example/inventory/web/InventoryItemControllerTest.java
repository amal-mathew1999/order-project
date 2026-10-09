package com.example.inventory.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.inventory.common.NotFoundException;
import com.example.inventory.dto.InventoryItemRequest;
import com.example.inventory.dto.InventoryItemResponse;
import com.example.inventory.service.InventoryItemService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InventoryItemController.class)
class InventoryItemControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean InventoryItemService service;

    @Test
    void createReturns201() throws Exception {
        when(service.create(any(InventoryItemRequest.class))).thenReturn(new InventoryItemResponse(1L, 1L, "sample", 1, new BigDecimal("10.50")));
        mvc.perform(post("/api/v1/inventory-items").contentType(MediaType.APPLICATION_JSON).content("{\"productId\":1,\"productName\":\"sample\",\"availableQuantity\":1,\"unitPrice\":10.50}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/inventory-items/1"))
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void createWithEmptyBodyReturns400() throws Exception {
        mvc.perform(post("/api/v1/inventory-items").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").exists());
    }

    @Test
    void getMissingReturns404() throws Exception {
        when(service.get(99L)).thenThrow(new NotFoundException("InventoryItem", 99L));
        mvc.perform(get("/api/v1/inventory-items/99")).andExpect(status().isNotFound());
    }
}
