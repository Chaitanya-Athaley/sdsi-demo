package com.example.flashsale.integration;

import com.example.flashsale.config.TestConfig;
import com.example.flashsale.domain.entity.Product;
import com.example.flashsale.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
class InventoryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        Instant now = Instant.parse("2026-10-27T10:00:00Z");
        productRepository.save(new Product("SKU-A", "Product A", 100, 2, now));
        productRepository.save(new Product("SKU-B", "Product B", 50, 5, now));
        productRepository.save(new Product("SKU-ZERO", "Zero Stock", 0, 1, now));
    }

    @Test
    void shouldListAllInventory() throws Exception {
        mockMvc.perform(get("/api/v1/inventory")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[?(@.sku=='SKU-A')].availableStock", hasItem(100)))
                .andExpect(jsonPath("$[?(@.sku=='SKU-B')].availableStock", hasItem(50)))
                .andExpect(jsonPath("$[?(@.sku=='SKU-ZERO')].availableStock", hasItem(0)));
    }

    @Test
    void shouldGetInventoryBySku() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/SKU-A")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("SKU-A"))
                .andExpect(jsonPath("$.name").value("Product A"))
                .andExpect(jsonPath("$.initialStock").value(100))
                .andExpect(jsonPath("$.availableStock").value(100))
                .andExpect(jsonPath("$.reservedStock").value(0))
                .andExpect(jsonPath("$.soldStock").value(0))
                .andExpect(jsonPath("$.purchaseLimit").value(2));
    }

    @Test
    void shouldReturn404ForUnknownSku() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/UNKNOWN")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void inventoryReportShouldHaveAllFields() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/SKU-B")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("SKU-B"))
                .andExpect(jsonPath("$.name").isNotEmpty())
                .andExpect(jsonPath("$.initialStock").value(50))
                .andExpect(jsonPath("$.availableStock").isNumber())
                .andExpect(jsonPath("$.reservedStock").isNumber())
                .andExpect(jsonPath("$.soldStock").isNumber())
                .andExpect(jsonPath("$.purchaseLimit").value(5));
    }
}