package com.example.flashsale.controller;

import com.example.flashsale.dto.response.InventoryResponse;
import com.example.flashsale.service.InventoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public ResponseEntity<List<InventoryResponse>> listInventory() {
        List<InventoryResponse> responses = inventoryService.listInventory().stream()
                .map(InventoryResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{sku}")
    public ResponseEntity<InventoryResponse> getInventory(@PathVariable String sku) {
        InventoryResponse response = InventoryResponse.from(inventoryService.getInventory(sku));
        return ResponseEntity.ok(response);
    }
}