package com.example.flashsale.domain.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true, length = 50)
    private String sku;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "initial_stock", nullable = false)
    private int initialStock;

    @Column(name = "available_stock", nullable = false)
    private int availableStock;

    @Column(name = "reserved_stock", nullable = false)
    private int reservedStock;

    @Column(name = "sold_stock", nullable = false)
    private int soldStock;

    @Column(name = "purchase_limit", nullable = false)
    private int purchaseLimit;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Product() {
    }

    public Product(String sku, String name, int initialStock, int purchaseLimit, Instant now) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("SKU must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name must not be blank");
        }
        if (initialStock < 0) {
            throw new IllegalArgumentException("Initial stock must not be negative");
        }
        if (purchaseLimit < 0) {
            throw new IllegalArgumentException("Purchase limit must not be negative");
        }
        this.sku = sku;
        this.name = name;
        this.initialStock = initialStock;
        this.availableStock = initialStock;
        this.reservedStock = 0;
        this.soldStock = 0;
        this.purchaseLimit = purchaseLimit;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Attempts to reserve {@code quantity} units.
     * Must be called within a transaction that holds a pessimistic write lock on this row.
     *
     * @return true if the reservation reduces available stock, false if insufficient stock
     */
    public boolean reserve(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (availableStock < quantity) {
            return false;
        }
        availableStock -= quantity;
        reservedStock += quantity;
        return true;
    }

    /**
     * Moves reserved stock to sold stock on confirmation.
     * Must be called within a transaction that holds a pessimistic write lock on this row.
     *
     * @throws IllegalStateException if reserved stock is insufficient (should never happen
     *                               when called correctly within the service layer)
     */
    public void confirm(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (reservedStock < quantity) {
            throw new IllegalStateException(
                    "Insufficient reserved stock: have " + reservedStock + ", need " + quantity);
        }
        reservedStock -= quantity;
        soldStock += quantity;
    }

    /**
     * Releases reserved stock back to available stock.
     * Must be called within a transaction that holds a pessimistic write lock on this row.
     */
    public void releaseReservation(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (reservedStock < quantity) {
            throw new IllegalStateException(
                    "Insufficient reserved stock to release: have " + reservedStock + ", need " + quantity);
        }
        reservedStock -= quantity;
        availableStock += quantity;
    }

    /**
     * Stock accounting invariant: initialStock == availableStock + reservedStock + soldStock
     */
    public boolean isStockInvariantSatisfied() {
        return initialStock == availableStock + reservedStock + soldStock;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    // ---------- Getters ----------

    public String getId() { return id; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public int getInitialStock() { return initialStock; }
    public int getAvailableStock() { return availableStock; }
    public int getReservedStock() { return reservedStock; }
    public int getSoldStock() { return soldStock; }
    public int getPurchaseLimit() { return purchaseLimit; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}