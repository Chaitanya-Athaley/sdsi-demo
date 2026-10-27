package com.example.flashsale.scheduler;

import com.example.flashsale.domain.entity.Reservation;
import com.example.flashsale.repository.ReservationRepository;
import com.example.flashsale.service.ReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Component
public class ExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpirationScheduler.class);
    private static final int BATCH_SIZE = 100;

    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final Clock clock;

    @Value("${flash-sale.expiration.batch-size:100}")
    private int configuredBatchSize;

    public ExpirationScheduler(ReservationRepository reservationRepository,
                                ReservationService reservationService,
                                Clock clock) {
        this.reservationRepository = reservationRepository;
        this.reservationService = reservationService;
        this.clock = clock;
    }

    /**
     * Periodically scans for overdue RESERVED reservations and expires them.
     *
     * Processes in bounded batches to avoid long transactions and excessive lock holding.
     * Each reservation is expired in its own transaction (via ReservationService)
     * so that a single failure does not roll back the entire batch.
     *
     * Idempotent: expireReservation() checks the current status before mutating,
     * so multiple scheduler instances or repeated runs are safe.
     *
     * The query SELECT ... WHERE status='RESERVED' AND expires_at <= :now
     * uses the index on (expires_at, status) for efficient scanning.
     */
    @Scheduled(fixedDelayString = "${flash-sale.expiration.interval-ms:5000}")
    @Transactional(readOnly = true)
    public void expireOverdueReservations() {
        Instant now = clock.instant();
        int batchSize = Math.max(1, configuredBatchSize);

        List<Reservation> overdue = reservationRepository.findExpiredReservations(
                now, PageRequest.of(0, batchSize));

        if (overdue.isEmpty()) {
            return;
        }

        log.debug("Found {} overdue reservations to expire.", overdue.size());
        int expired = 0;
        for (Reservation r : overdue) {
            try {
                if (reservationService.expireReservation(r.getId(), now)) {
                    expired++;
                }
            } catch (Exception e) {
                log.error("Failed to expire reservation {}: {}", r.getId(), e.getMessage());
                // Continue with the next reservation; don't let one failure block the batch
            }
        }

        if (expired > 0) {
            log.info("Expired {} overdue reservations ({} total found).", expired, overdue.size());
        }
    }
}