package com.example.flashsale.integration;

import com.example.flashsale.config.TestConfig;
import com.example.flashsale.domain.entity.Product;
import com.example.flashsale.domain.entity.Reservation;
import com.example.flashsale.domain.enums.ReservationStatus;
import com.example.flashsale.repository.ProductRepository;
import com.example.flashsale.repository.ReservationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
class ReservationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        productRepository.deleteAll();
        Instant now = Instant.parse("2026-10-27T10:00:00Z");
        productRepository.save(new Product("SKU-A", "Product A", 100, 2, now));
    }

    @Test
    void shouldCreateReservationAndReturn201() throws Exception {
        String requestBody = objectMapper.writeValueAsString(
                Map.of("userId", "user1", "sku", "SKU-A", "quantity", 2));

        String response = mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.userId").value("user1"))
                .andExpect(jsonPath("$.sku").value("SKU-A"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Verify stock was deducted
        Product product = productRepository.findBySku("SKU-A").orElseThrow();
        assertThat(product.getAvailableStock()).isEqualTo(98);
        assertThat(product.getReservedStock()).isEqualTo(2);
    }

    @Test
    void shouldRejectReservationWithInsufficientStock() throws Exception {
        String requestBody = objectMapper.writeValueAsString(
                Map.of("userId", "user1", "sku", "SKU-A", "quantity", 200));

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_STOCK"));

        // Verify stock was NOT changed
        Product product = productRepository.findBySku("SKU-A").orElseThrow();
        assertThat(product.getAvailableStock()).isEqualTo(100);
    }

    @Test
    void shouldRejectReservationForUnknownSku() throws Exception {
        String requestBody = objectMapper.writeValueAsString(
                Map.of("userId", "user1", "sku", "UNKNOWN-SKU", "quantity", 1));

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void shouldRejectReservationWithNegativeQuantity() throws Exception {
        String requestBody = objectMapper.writeValueAsString(
                Map.of("userId", "user1", "sku", "SKU-A", "quantity", -1));

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectReservationExceedingPurchaseLimit() throws Exception {
        // First, create reservations up to the limit of 2
        String req1 = objectMapper.writeValueAsString(
                Map.of("userId", "user1", "sku", "SKU-A", "quantity", 1));
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(req1))
                .andExpect(status().isCreated());

        String req2 = objectMapper.writeValueAsString(
                Map.of("userId", "user1", "sku", "SKU-A", "quantity", 1));
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(req2))
                .andExpect(status().isCreated());

        // Third should fail — limit reached (active reservations = 2)
        String req3 = objectMapper.writeValueAsString(
                Map.of("userId", "user1", "sku", "SKU-A", "quantity", 1));
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(req3))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PURCHASE_LIMIT_EXCEEDED"));
    }

    @Test
    void shouldRetrieveReservationById() throws Exception {
        // Create a reservation
        String req = objectMapper.writeValueAsString(
                Map.of("userId", "user1", "sku", "SKU-A", "quantity", 1));
        String resp = mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservationId = objectMapper.readTree(resp).get("id").asText();

        // Retrieve it
        mockMvc.perform(get("/api/v1/reservations/" + reservationId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reservationId))
                .andExpect(jsonPath("$.status").value("RESERVED"));
    }

    @Test
    void shouldReturn404ForUnknownReservation() throws Exception {
        mockMvc.perform(get("/api/v1/reservations/nonexistent-id")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESERVATION_NOT_FOUND"));
    }

    @Test
    void shouldConfirmReservationAndUpdateStock() throws Exception {
        // Create
        String req = objectMapper.writeValueAsString(
                Map.of("userId", "user1", "sku", "SKU-A", "quantity", 2));
        String resp = mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservationId = objectMapper.readTree(resp).get("id").asText();

        // Confirm
        mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty());

        // Verify stock moved: reserved → sold
        Product product = productRepository.findBySku("SKU-A").orElseThrow();
        assertThat(product.getReservedStock()).isEqualTo(0);
        assertThat(product.getSoldStock()).isEqualTo(2);
        assertThat(product.getAvailableStock()).isEqualTo(98);
        assertThat(product.isStockInvariantSatisfied()).isTrue();
    }

    @Test
    void shouldCancelReservationAndReleaseStock() throws Exception {
        // Create
        String req = objectMapper.writeValueAsString(
                Map.of("userId", "user1", "sku", "SKU-A", "quantity", 2));
        String resp = mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservationId = objectMapper.readTree(resp).get("id").asText();

        // Cancel
        mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        // Verify stock released
        Product product = productRepository.findBySku("SKU-A").orElseThrow();
        assertThat(product.getAvailableStock()).isEqualTo(100);
        assertThat(product.getReservedStock()).isEqualTo(0);
        assertThat(product.isStockInvariantSatisfied()).isTrue();
    }

    @Test
    void shouldNotConfirmCancelledReservation() throws Exception {
        String req = objectMapper.writeValueAsString(
                Map.of("userId", "user1", "sku", "SKU-A", "quantity", 1));
        String resp = mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservationId = objectMapper.readTree(resp).get("id").asText();

        // Cancel first
        mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/cancel"))
                .andExpect(status().isOk());

        // Try to confirm
        mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_RESERVATION_STATE"));
    }

    @Test
    void shouldNotConfirmAlreadyConfirmedReservation() throws Exception {
        String req = objectMapper.writeValueAsString(
                Map.of("userId", "user1", "sku", "SKU-A", "quantity", 1));
        String resp = mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservationId = objectMapper.readTree(resp).get("id").asText();

        mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/confirm"))
                .andExpect(status().isOk());

        // Second confirm should fail
        mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_RESERVATION_STATE"));

        // Stock should only be deducted once
        Product product = productRepository.findBySku("SKU-A").orElseThrow();
        assertThat(product.getSoldStock()).isEqualTo(1);
    }

    @Test
    void shouldNotCancelAlreadyConfirmedReservation() throws Exception {
        String req = objectMapper.writeValueAsString(
                Map.of("userId", "user1", "sku", "SKU-A", "quantity", 1));
        String resp = mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservationId = objectMapper.readTree(resp).get("id").asText();

        mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/confirm"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/reservations/" + reservationId + "/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_RESERVATION_STATE"));
    }

    @Test
    void shouldRejectEmptyUserId() throws Exception {
        String req = objectMapper.writeValueAsString(
                Map.of("userId", "", "sku", "SKU-A", "quantity", 1));

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectZeroQuantity() throws Exception {
        String req = objectMapper.writeValueAsString(
                Map.of("userId", "user1", "sku", "SKU-A", "quantity", 0));

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isBadRequest());
    }
}