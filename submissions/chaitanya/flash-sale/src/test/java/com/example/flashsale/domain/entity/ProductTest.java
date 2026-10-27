package com.example.flashsale.domain.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    @Test
    void shouldCreateProductWithDefaultStockState() {
        Product p = new Product("SKU-1", "Test Product", 100, 2, NOW);
        assertThat(p.getAvailableStock()).isEqualTo(100);
        assertThat(p.getReservedStock()).isEqualTo(0);
        assertThat(p.getSoldStock()).isEqualTo(0);
        assertThat(p.getInitialStock()).isEqualTo(100);
        assertThat(p.isStockInvariantSatisfied()).isTrue();
    }

    @Test
    void shouldReserveStockAndReduceAvailable() {
        Product p = new Product("SKU-1", "Test", 10, 5, NOW);
        boolean result = p.reserve(3);
        assertThat(result).isTrue();
        assertThat(p.getAvailableStock()).isEqualTo(7);
        assertThat(p.getReservedStock()).isEqualTo(3);
        assertThat(p.isStockInvariantSatisfied()).isTrue();
    }

    @Test
    void shouldRejectReservationWhenInsufficientStock() {
        Product p = new Product("SKU-1", "Test", 5, 5, NOW);
        boolean result = p.reserve(10);
        assertThat(result).isFalse();
        assertThat(p.getAvailableStock()).isEqualTo(5);
        assertThat(p.getReservedStock()).isEqualTo(0);
        assertThat(p.isStockInvariantSatisfied()).isTrue();
    }

    @Test
    void shouldRejectReservationWhenNoStock() {
        Product p = new Product("SKU-1", "Test", 0, 5, NOW);
        boolean result = p.reserve(1);
        assertThat(result).isFalse();
        assertThat(p.isStockInvariantSatisfied()).isTrue();
    }

    @Test
    void shouldConfirmReservationAndMoveToSold() {
        Product p = new Product("SKU-1", "Test", 10, 5, NOW);
        p.reserve(3);
        p.confirm(3);
        assertThat(p.getReservedStock()).isEqualTo(0);
        assertThat(p.getSoldStock()).isEqualTo(3);
        assertThat(p.getAvailableStock()).isEqualTo(7);
        assertThat(p.isStockInvariantSatisfied()).isTrue();
    }

    @Test
    void shouldReleaseReservationBackToAvailable() {
        Product p = new Product("SKU-1", "Test", 10, 5, NOW);
        p.reserve(3);
        p.releaseReservation(3);
        assertThat(p.getReservedStock()).isEqualTo(0);
        assertThat(p.getAvailableStock()).isEqualTo(10);
        assertThat(p.isStockInvariantSatisfied()).isTrue();
    }

    @Test
    void shouldRejectReleasingMoreThanReserved() {
        Product p = new Product("SKU-1", "Test", 10, 5, NOW);
        p.reserve(2);
        assertThatThrownBy(() -> p.releaseReservation(3))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectConfirmingMoreThanReserved() {
        Product p = new Product("SKU-1", "Test", 10, 5, NOW);
        p.reserve(2);
        assertThatThrownBy(() -> p.confirm(3))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectNegativeQuantity() {
        Product p = new Product("SKU-1", "Test", 10, 5, NOW);
        assertThatThrownBy(() -> p.reserve(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.reserve(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldMaintainInvariantThroughFullLifecycle() {
        Product p = new Product("SKU-1", "Test", 10, 5, NOW);

        // Reserve 3
        assertThat(p.reserve(3)).isTrue();
        assertThat(p.isStockInvariantSatisfied()).isTrue();
        // (10 == 7 + 3 + 0)

        // Reserve 2 more
        assertThat(p.reserve(2)).isTrue();
        assertThat(p.isStockInvariantSatisfied()).isTrue();
        // (10 == 5 + 5 + 0)

        // Confirm 3
        p.confirm(3);
        assertThat(p.isStockInvariantSatisfied()).isTrue();
        // (10 == 5 + 2 + 3)

        // Release 2
        p.releaseReservation(2);
        assertThat(p.isStockInvariantSatisfied()).isTrue();
        // (10 == 7 + 0 + 3)
    }

    @Test
    void shouldRejectNegativeInitialStock() {
        assertThatThrownBy(() -> new Product("SKU", "Name", -1, 5, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAcceptZeroInitialStock() {
        Product p = new Product("SKU", "Name", 0, 5, NOW);
        assertThat(p.getInitialStock()).isEqualTo(0);
        assertThat(p.isStockInvariantSatisfied()).isTrue();
    }
}