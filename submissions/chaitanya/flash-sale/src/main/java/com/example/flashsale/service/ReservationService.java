package com.example.flashsale.service;

import com.example.flashsale.domain.entity.Product;
import com.example.flashsale.domain.entity.Reservation;
import com.example.flashsale.domain.exception.*;
import com.example.flashsale.repository.ProductRepository;
import com.example.flashsale.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ProductRepository productRepository;
    private final ReservationRepository reservationRepository;
    private final Clock clock;

    @Value("${flash-sale.reservation.ttl-seconds:120}")
    private int defaultTtlSeconds;

    public ReservationService(ProductRepository productRepository,
                              ReservationRepository reservationRepository,
                              Clock clock) {
        this.productRepository = productRepository;
        this.reservationRepository = reservationRepository;
        this.clock = clock;
    }

    /**
     * Creates a reservation for the given user, SKU, and quantity.
     *
     * Concurrency strategy:
     * - Acquires a pessimistic write lock on the product row (PESSIMISTIC_WRITE)
     *   via findBySkuForUpdate. This serialises all stock mutations for the same SKU.
     * - Within the same transaction, validates stock availability and purchase limit,
     *   creates the reservation entity, and updates the product's reserved/available stock.
     * - If any check fails, the transaction rolls back; no partial stock mutation persists.
     *
     * Lock ordering: product row locked first, then reservation rows (user/SKU queries
     * for purchase-limit checks read without explicit lock, but they're inside the same
     * transaction that holds the product write lock — serialising at the product level
     * is sufficient for correctness).
     */
    @Transactional
    public Reservation createReservation(String userId, String sku, int quantity) {
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(defaultTtlSeconds);

        // 1. Acquire pessimistic write lock on the product row
        Product product = productRepository.findBySkuForUpdate(sku)
                .orElseThrow(() -> new ProductNotFoundException(sku));

        // 2. Check stock availability
        if (product.getAvailableStock() < quantity) {
            throw new InsufficientStockException(sku, quantity, product.getAvailableStock());
        }

        // 3. Enforce per-user purchase limit
        enforcePurchaseLimit(userId, sku, quantity, product.getPurchaseLimit());

        // 4. Deduct stock
        if (!product.reserve(quantity)) {
            throw new InsufficientStockException(sku, quantity, product.getAvailableStock());
        }
        product.touch(now);

        // 5. Create reservation
        Reservation reservation = new Reservation(userId, sku, quantity, now, expiresAt);
        reservationRepository.save(reservation);

        log.info("Reservation {} created: user={}, sku={}, qty={}, expiresAt={}",
                reservation.getId(), userId, sku, quantity, expiresAt);

        return reservation;
    }

    /**
     * Confirms a reservation, permanently moving reserved stock to sold stock.
     *
     * Concurrency strategy:
     * - Locks the product row for the reservation's SKU with a pessimistic write lock
     *   BEFORE confirming the reservation entity. This avoids race conditions where
     *   expiration and confirmation try to mutate stock for the same reservation concurrently.
     * - The lifecycle transition on the Reservation entity checks state and expiry
     *   (using the Reservation's own confirm() method), ensuring that an expired or
     *   already-terminal reservation cannot be confirmed.
     * - Stock mutation on the Product entity is guarded by the write lock, so two
     *   concurrent confirmations cannot double-deduct stock.
     */
    @Transactional
    public Reservation confirmReservation(String reservationId) {
        Instant now = clock.instant();

        // 1. Load reservation (no lock yet — just to get the SKU)
        final Reservation preReservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        final String sku = preReservation.getSku();

        // 2. Lock the product row first (consistent lock ordering: product before reservation)
        Product product = productRepository.findBySkuForUpdate(sku)
                .orElseThrow(() -> {
                    log.error("Product {} not found while confirming reservation {} — data inconsistency",
                            sku, reservationId);
                    return new RuntimeException("Data inconsistency: product not found for reservation");
                });

        // 3. Re-read reservation with a write lock now that we hold the product lock
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        // 4. Perform lifecycle transition (checks state and expiry inside confirm())
        reservation.confirm(now);

        // 5. Move reserved → sold stock on the product
        product.confirm(reservation.getQuantity());
        product.touch(now);

        log.info("Reservation {} confirmed: user={}, sku={}, qty={}",
                reservationId, reservation.getUserId(), reservation.getSku(), reservation.getQuantity());

        return reservation;
    }

    /**
     * Cancels a reservation, releasing reserved stock back to available stock.
     *
     * Same locking strategy as confirmReservation.
     */
    @Transactional
    public Reservation cancelReservation(String reservationId) {
        Instant now = clock.instant();

        final Reservation preRes = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        final String sku = preRes.getSku();

        Product product = productRepository.findBySkuForUpdate(sku)
                .orElseThrow(() -> new RuntimeException("Data inconsistency: product not found for reservation"));

        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        reservation.cancel(now);

        product.releaseReservation(reservation.getQuantity());
        product.touch(now);

        log.info("Reservation {} cancelled: user={}, sku={}, qty={}",
                reservationId, reservation.getUserId(), reservation.getSku(), reservation.getQuantity());

        return reservation;
    }

    /**
     * Expires a single overdue reservation. Called both by the scheduled job
     * and by transactional operations that encounter an expired reservation.
     *
     * Idempotent: if the reservation is already terminal, returns false.
     * Concurrency-safe: locks both product and reservation rows with
     * pessimistic write locks to prevent races with confirm/cancel.
     *
     * @return true if the reservation was expired (state changed), false if already terminal
     */
    @Transactional
    public boolean expireReservation(String reservationId, Instant now) {
        Reservation reservation = reservationRepository.findByIdForUpdate(reservationId)
                .orElse(null);
        if (reservation == null) {
            return false;
        }
        if (reservation.isTerminal()) {
            return false;
        }

        Product product = productRepository.findBySkuForUpdate(reservation.getSku())
                .orElse(null);
        if (product == null) {
            log.error("Product {} not found for reservation {} — data inconsistency",
                    reservation.getSku(), reservationId);
            return false;
        }

        if (!reservation.expire(now)) {
            return false; // already terminal (double-check after locking)
        }

        product.releaseReservation(reservation.getQuantity());
        product.touch(now);

        log.info("Reservation {} expired: user={}, sku={}, qty={}",
                reservationId, reservation.getUserId(), reservation.getSku(), reservation.getQuantity());
        return true;
    }

    @Transactional(readOnly = true)
    public Reservation getReservation(String reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    }

    // ---------- Private helpers ----------

    /**
     * Enforces the per-user purchase limit for a given SKU.
     * Rule: confirmedQuantity + activeReservedQuantity + requestedQuantity <= purchaseLimit
     */
    private void enforcePurchaseLimit(String userId, String sku, int requestedQuantity, int purchaseLimit) {
        int confirmedQty = reservationRepository.sumConfirmedQuantityByUserAndSku(userId, sku);
        int reservedQty = reservationRepository.sumActiveQuantityByUserAndSku(userId, sku);
        int totalAfterRequest = confirmedQty + reservedQty + requestedQuantity;

        if (totalAfterRequest > purchaseLimit) {
            throw new PurchaseLimitExceededException(userId, sku, purchaseLimit,
                    confirmedQty + reservedQty, requestedQuantity);
        }
    }
}