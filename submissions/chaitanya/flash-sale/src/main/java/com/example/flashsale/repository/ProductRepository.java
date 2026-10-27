package com.example.flashsale.repository;

import com.example.flashsale.domain.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, String> {

    Optional<Product> findBySku(String sku);

    /**
     * Finds a product by SKU with a pessimistic write lock.
     * The lock is held until the transaction commits/rolls back,
     * serializing concurrent stock mutations for the same SKU.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.sku = :sku")
    Optional<Product> findBySkuForUpdate(@Param("sku") String sku);

    boolean existsBySku(String sku);
}