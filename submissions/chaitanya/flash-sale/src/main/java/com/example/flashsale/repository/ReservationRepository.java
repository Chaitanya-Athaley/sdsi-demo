package com.example.flashsale.repository;

import com.example.flashsale.domain.entity.Reservation;
import com.example.flashsale.domain.enums.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, String> {

    /**
     * Sum of active (RESERVED) quantity for a given user and SKU.
     */
    @Query("SELECT COALESCE(SUM(r.quantity), 0) FROM Reservation r "
            + "WHERE r.userId = :userId AND r.sku = :sku AND r.status = 'RESERVED'")
    int sumActiveQuantityByUserAndSku(@Param("userId") String userId, @Param("sku") String sku);

    /**
     * Sum of confirmed quantity for a given user and SKU.
     */
    @Query("SELECT COALESCE(SUM(r.quantity), 0) FROM Reservation r "
            + "WHERE r.userId = :userId AND r.sku = :sku AND r.status = 'CONFIRMED'")
    int sumConfirmedQuantityByUserAndSku(@Param("userId") String userId, @Param("sku") String sku);

    /**
     * Finds reserved reservations that have passed their expiration time,
     * returning a page for batch processing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.status = 'RESERVED' AND r.expiresAt <= :now")
    List<Reservation> findExpiredReservations(@Param("now") Instant now, Pageable pageable);

    /**
     * Finds a reservation by ID with a pessimistic write lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") String id);
}