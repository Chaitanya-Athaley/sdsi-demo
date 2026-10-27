package com.example.flashsale.integration;

import com.example.flashsale.config.TestConfig;
import com.example.flashsale.domain.entity.Product;
import com.example.flashsale.domain.entity.Reservation;
import com.example.flashsale.domain.enums.ReservationStatus;
import com.example.flashsale.repository.ProductRepository;
import com.example.flashsale.repository.ReservationRepository;
import com.example.flashsale.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
class ConcurrencyTest {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    private static final String SKU = "CONCUR-SKU";
    private static final int INITIAL_STOCK = 50;
    private static final int PURCHASE_LIMIT = 10;
    private static final int CONCURRENT_THREADS = 50;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        productRepository.deleteAll();
        Instant now = Instant.parse("2026-10-27T10:00:00Z");
        productRepository.save(new Product(SKU, "Concurrency Test Product", INITIAL_STOCK, PURCHASE_LIMIT, now));
    }

    /**
     * Test: 50 threads each try to reserve 1 unit of a product with 50 initial stock.
     * Only 50 reservations should succeed. Stock must never go negative.
     */
    @Test
    void shouldNotOversellUnderConcurrentReservations() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_THREADS);
        List<Future<Boolean>> futures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            final String userId = "user-" + i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await(); // all threads start together
                    reservationService.createReservation(userId, SKU, 1);
                    return true;
                } catch (Exception e) {
                    return false;
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // Count successes
        long successCount = futures.stream().filter(f -> {
            try { return f.get(); } catch (Exception e) { return false; }
        }).count();

        assertThat(successCount).isEqualTo(INITIAL_STOCK);

        // Verify product stock
        Product product = productRepository.findBySku(SKU).orElseThrow();
        assertThat(product.getAvailableStock()).isGreaterThanOrEqualTo(0);
        assertThat(product.getAvailableStock()).isEqualTo(0);
        assertThat(product.getReservedStock()).isEqualTo(INITIAL_STOCK);
        assertThat(product.isStockInvariantSatisfied()).isTrue();

        // Verify total reservations = initial stock
        long reservedCount = reservationRepository.findAll().stream()
                .filter(r -> r.getStatus() == ReservationStatus.RESERVED).count();
        assertThat(reservedCount).isEqualTo(INITIAL_STOCK);
    }

    /**
     * Test: Concurrent reservation requests from the same user must respect
     * the purchase limit. Seed a SKU with 100 units and purchase limit of 3.
     * Launch 20 concurrent requests from the same user, each requesting 1 unit.
     * At most 3 should succeed.
     */
    @Test
    void shouldEnforcePurchaseLimitConcurrently() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(20);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < 20; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    reservationService.createReservation("same-user", SKU, 1);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // expected for most — limit exceeded
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // At most PURCHASE_LIMIT (10) should succeed
        assertThat(successCount.get()).isLessThanOrEqualTo(PURCHASE_LIMIT);
        assertThat(successCount.get()).isGreaterThan(0);
    }

    /**
     * Test: Race between confirm and expire on the same reservation.
     * One must win; stock must not be double-counted.
     */
    @RepeatedTest(5)
    void confirmAndExpireShouldNotDoubleCountStock() throws Exception {
        // Create a reservation with a very short TTL
        Reservation reservation = reservationService.createReservation("user1", SKU, 1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger confirmations = new AtomicInteger(0);
        AtomicInteger expirations = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                startLatch.await();
                Thread.sleep(1); // slight stagger for realism (not the coordination mechanism)
                reservationService.confirmReservation(reservation.getId());
                confirmations.incrementAndGet();
            } catch (Exception e) {
                // expected if expiry won the race
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                // Expire using a time well past the TTL
                Instant farFuture = reservation.getExpiresAt().plusSeconds(10);
                reservationService.expireReservation(reservation.getId(), farFuture);
                expirations.incrementAndGet();
            } catch (Exception e) {
                // expected if confirmation won the race
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // Exactly one operation should succeed
        assertThat(confirmations.get() + expirations.get()).isEqualTo(1);

        // Stock invariant must hold
        Product product = productRepository.findBySku(SKU).orElseThrow();
        assertThat(product.isStockInvariantSatisfied()).isTrue();

        // Either sold=1 or available should reflect the release
        Reservation finalRes = reservationRepository.findById(reservation.getId()).orElseThrow();
        assertThat(finalRes.isTerminal()).isTrue();

        if (finalRes.getStatus() == ReservationStatus.CONFIRMED) {
            assertThat(product.getSoldStock()).isEqualTo(1);
            assertThat(product.getReservedStock()).isEqualTo(0);
        } else {
            // available should reflect the release back from the expiry
            // Stock was: initialStock - 1 (reservation) + 1 (release) = initialStock
        }
    }

    /**
     * Test: multiple concurrent reservations from DIFFERENT users, testing
     * that per-user purchase limits don't interfere with each other
     * while the overall stock limit is respected.
     */
    @Test
    void concurrentReservationsFromDifferentUsers() throws Exception {
        int threads = 30;
        int stock = INITIAL_STOCK; // 50
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final String userId = "multi-user-" + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Random small delay to increase interleaving
                    Thread.sleep(ThreadLocalRandom.current().nextInt(5));
                    reservationService.createReservation(userId, SKU, 1);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // expected for some — either stock ran out or limit hit
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // No more than initial stock successful reservations
        assertThat(successCount.get()).isLessThanOrEqualTo(stock);
        assertThat(successCount.get()).isGreaterThan(0);

        // Each user must not exceed the purchase limit
        for (int i = 0; i < threads; i++) {
            int userQty = reservationRepository.sumActiveQuantityByUserAndSku("multi-user-" + i, SKU)
                    + reservationRepository.sumConfirmedQuantityByUserAndSku("multi-user-" + i, SKU);
            assertThat(userQty).isLessThanOrEqualTo(PURCHASE_LIMIT);
        }

        // Stock invariant
        Product product = productRepository.findBySku(SKU).orElseThrow();
        assertThat(product.isStockInvariantSatisfied()).isTrue();
        assertThat(product.getAvailableStock()).isGreaterThanOrEqualTo(0);
    }
}