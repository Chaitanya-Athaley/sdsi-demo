package com.example.flashsale.domain.entity;

import com.example.flashsale.domain.enums.ReservationStatus;
import com.example.flashsale.domain.exception.InvalidReservationStateException;
import com.example.flashsale.domain.exception.ReservationExpiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {

    private static final Instant BASE = Instant.parse("2026-10-27T10:00:00Z");
    private static final Instant EXPIRES = BASE.plusSeconds(120);

    @Test
    void shouldCreateReservationInReservedState() {
        Reservation r = new Reservation("user1", "SKU-1", 1, BASE, EXPIRES);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(r.getUserId()).isEqualTo("user1");
        assertThat(r.getSku()).isEqualTo("SKU-1");
        assertThat(r.getQuantity()).isEqualTo(1);
        assertThat(r.isTerminal()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -5})
    void shouldRejectNonPositiveQuantity(int quantity) {
        assertThatThrownBy(() -> new Reservation("user1", "SKU-1", quantity, BASE, EXPIRES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBlankUserId() {
        assertThatThrownBy(() -> new Reservation("", "SKU-1", 1, BASE, EXPIRES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullUserId() {
        assertThatThrownBy(() -> new Reservation(null, "SKU-1", 1, BASE, EXPIRES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBlankSku() {
        assertThatThrownBy(() -> new Reservation("user1", "", 1, BASE, EXPIRES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectExpirationNotAfterCreation() {
        assertThatThrownBy(() -> new Reservation("user1", "SKU-1", 1, BASE, BASE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Reservation("user1", "SKU-1", 1, BASE, BASE.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldConfirmSuccessfully() {
        Reservation r = new Reservation("user1", "SKU-1", 1, BASE, EXPIRES);
        Instant confirmTime = BASE.plusSeconds(30);
        r.confirm(confirmTime);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(r.getConfirmedAt()).isEqualTo(confirmTime);
    }

    @Test
    void shouldNotConfirmIfAlreadyCancelled() {
        Reservation r = new Reservation("user1", "SKU-1", 1, BASE, EXPIRES);
        r.cancel(BASE.plusSeconds(10));
        assertThatThrownBy(() -> r.confirm(BASE.plusSeconds(20)))
                .isInstanceOf(InvalidReservationStateException.class)
                .hasMessageContaining("CANCELLED");
    }

    @Test
    void shouldNotConfirmIfAlreadyConfirmed() {
        Reservation r = new Reservation("user1", "SKU-1", 1, BASE, EXPIRES);
        r.confirm(BASE.plusSeconds(10));
        assertThatThrownBy(() -> r.confirm(BASE.plusSeconds(20)))
                .isInstanceOf(InvalidReservationStateException.class)
                .hasMessageContaining("CONFIRMED");
    }

    @Test
    void shouldNotConfirmIfExpired() {
        Reservation r = new Reservation("user1", "SKU-1", 1, BASE, EXPIRES);
        // exactly at expiration = expired
        assertThatThrownBy(() -> r.confirm(EXPIRES))
                .isInstanceOf(ReservationExpiredException.class);
    }

    @Test
    void shouldNotConfirmAfterExpiration() {
        Reservation r = new Reservation("user1", "SKU-1", 1, BASE, EXPIRES);
        assertThatThrownBy(() -> r.confirm(EXPIRES.plusSeconds(1)))
                .isInstanceOf(ReservationExpiredException.class);
    }

    @Test
    void shouldCancelSuccessfully() {
        Reservation r = new Reservation("user1", "SKU-1", 1, BASE, EXPIRES);
        Instant cancelTime = BASE.plusSeconds(30);
        r.cancel(cancelTime);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r.getCancelledAt()).isEqualTo(cancelTime);
    }

    @Test
    void shouldNotCancelIfAlreadyConfirmed() {
        Reservation r = new Reservation("user1", "SKU-1", 1, BASE, EXPIRES);
        r.confirm(BASE.plusSeconds(10));
        assertThatThrownBy(() -> r.cancel(BASE.plusSeconds(20)))
                .isInstanceOf(InvalidReservationStateException.class);
    }

    @Test
    void shouldExpireSuccessfully() {
        Reservation r = new Reservation("user1", "SKU-1", 1, BASE, EXPIRES);
        boolean result = r.expire(EXPIRES.plusSeconds(1));
        assertThat(result).isTrue();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void shouldNotExpireTwice() {
        Reservation r = new Reservation("user1", "SKU-1", 1, BASE, EXPIRES);
        r.expire(EXPIRES.plusSeconds(1));
        boolean second = r.expire(EXPIRES.plusSeconds(2));
        assertThat(second).isFalse();
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void shouldConfirmBeforeExactExpiration() {
        Reservation r = new Reservation("user1", "SKU-1", 1, BASE, EXPIRES);
        // one nanosecond before expiration — still valid
        r.confirm(EXPIRES.minusNanos(1));
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void shouldReportExpiredCorrectly() {
        Reservation r = new Reservation("user1", "SKU-1", 1, BASE, EXPIRES);
        assertThat(r.isExpired(BASE)).isFalse();
        assertThat(r.isExpired(BASE.plusSeconds(60))).isFalse();
        assertThat(r.isExpired(EXPIRES.minusNanos(1))).isFalse();
        assertThat(r.isExpired(EXPIRES)).isTrue();
        assertThat(r.isExpired(EXPIRES.plusSeconds(1))).isTrue();
    }
}