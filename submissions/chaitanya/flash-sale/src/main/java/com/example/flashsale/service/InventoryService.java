package com.example.flashsale.service;

import com.example.flashsale.domain.entity.Product;
import com.example.flashsale.domain.exception.ProductNotFoundException;
import com.example.flashsale.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class InventoryService {

    private final ProductRepository productRepository;

    public InventoryService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Lists all products with current stock levels.
     *
     * Consistency guarantee: each Product row is read atomically (single row read),
     * but there is no snapshot isolation across products. Reserved/sold/available
     * counts for a given SKU are always consistent with the product row, but the
     * listing is a point-in-time read that may not reflect in-flight mutations
     * across different transactions.
     */
    public List<Product> listInventory() {
        return productRepository.findAll();
    }

    /**
     * Gets stock details for a single SKU.
     *
     * This reads the Product row with default isolation, so it reflects the
     * committed state at the time of the read. For a fully consistent snapshot
     * under heavy write load, a REPEATABLE_READ isolation read would be preferred,
     * but that would hold snapshot pages — unnecessary for an inventory listing.
     */
    public Product getInventory(String sku) {
        return productRepository.findBySku(sku)
                .orElseThrow(() -> new ProductNotFoundException(sku));
    }
}