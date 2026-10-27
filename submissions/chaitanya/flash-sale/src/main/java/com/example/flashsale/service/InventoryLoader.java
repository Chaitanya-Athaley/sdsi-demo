package com.example.flashsale.service;

import com.example.flashsale.domain.entity.Product;
import com.example.flashsale.repository.ProductRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class InventoryLoader {

    private static final Logger log = LoggerFactory.getLogger(InventoryLoader.class);

    private final ProductRepository productRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public InventoryLoader(ProductRepository productRepository, ObjectMapper objectMapper, Clock clock) {
        this.productRepository = productRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Loads sample inventory from inventory.json on startup if the products table is empty.
     * This is idempotent — restarts will not re-insert or reset inventory.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void loadInitialInventory() {
        if (productRepository.count() > 0) {
            log.info("Inventory already initialized, skipping load.");
            return;
        }

        try {
            ClassPathResource resource = new ClassPathResource("inventory.json");
            if (!resource.exists()) {
                log.warn("inventory.json not found on classpath, skipping inventory initialization.");
                return;
            }

            try (InputStream is = resource.getInputStream()) {
                List<Map<String, Object>> items = objectMapper.readValue(is,
                        new TypeReference<List<Map<String, Object>>>() {});

                Instant now = clock.instant();
                int loaded = 0;
                for (Map<String, Object> item : items) {
                    String sku = (String) item.get("sku");
                    String name = (String) item.get("name");
                    int stock = ((Number) item.get("initialStock")).intValue();
                    int limit = ((Number) item.get("purchaseLimit")).intValue();

                    if (productRepository.existsBySku(sku)) {
                        log.warn("Duplicate SKU {} found in inventory.json, skipping.", sku);
                        continue;
                    }
                    if (stock < 0 || limit < 0) {
                        log.warn("Invalid stock/limit for SKU {}, skipping.", sku);
                        continue;
                    }

                    Product product = new Product(sku, name, stock, limit, now);
                    productRepository.save(product);
                    loaded++;
                }
                log.info("Loaded {} products from inventory.json.", loaded);
            }
        } catch (Exception e) {
            log.error("Failed to load inventory from inventory.json: {}", e.getMessage(), e);
            throw new RuntimeException("Inventory initialization failed", e);
        }
    }
}