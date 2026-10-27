package com.example.flashsale.domain.entity;

import com.example.flashsale.domain.enums.ReservationStatus;
import com.example.flashsale.domain.exception.InvalidReservationStateException;
import com.example.flashsale.domain.exception.ReservationExpiredException;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "reservations", indexes = {
        @Index(name = "idx_reservations_sku", columnList = "sku"),
        @Index(name = "idx_reservations_user_sku", columnList = "user_id, sku"),
        @Index(name = "idx_reservations_expires_at_status", columnList = "expires_at, status")
})
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(nullable = false, length = 50)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    private Long version;

    protected Reservation() {
    }

    public Reservation(String userId, String sku, int quantity, Instant createdAt, Instant expiresAt) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID must not be blank");
        }
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("SKU must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("Created timestamp must not be null");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("Expiration timestamp must not be null");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Expiration must be after creation");
        }
        this.userId = userId;
        this.sku = sku;
        this.quantity = quantity;
        this.status = ReservationStatus.RESERVED;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public void confirm(Instant now) {
        if (status != ReservationStatus.RESERVED) {
            throw new InvalidReservationStateException(
                    "Cannot confirm reservation in state " + status + "; must be RESERVED");
        }
        if (isExpired(now)) {
            throw new ReservationExpiredException(id);
        }
        this.status = ReservationStatus.CONFIRMED;
        this.confirmedAt = now;
    }

    public void cancel(Instant now) {
        if (status != ReservationStatus.RESERVED) {
            throw new InvalidReservationStateException(
                    "Cannot cancel reservation in state " + status + "; must be RESERVED");
        }
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = now;
    }

    public boolean expire(Instant now) {
        if (status != ReservationStatus.RESERVED) {
            return false;
        }
        this.status = ReservationStatus.EXPIRED;
        return true;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isTerminal() {
        return status != ReservationStatus.RESERVED;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
    public ReservationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
}