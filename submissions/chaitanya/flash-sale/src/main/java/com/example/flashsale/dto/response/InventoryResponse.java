package com.example.flashsale.dto.response;

import com.example.flashsale.domain.entity.Product;

public class InventoryResponse {

    private String sku;
    private String name;
    private int initialStock;
    private int availableStock;
    private int reservedStock;
    private int soldStock;
    private int purchaseLimit;

    public InventoryResponse() {
    }

    public static InventoryResponse from(Product product) {
        InventoryResponse r = new InventoryResponse();
        r.sku = product.getSku();
        r.name = product.getName();
        r.initialStock = product.getInitialStock();
        r.availableStock = product.getAvailableStock();
        r.reservedStock = product.getReservedStock();
        r.soldStock = product.getSoldStock();
        r.purchaseLimit = product.getPurchaseLimit();
        return r;
    }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getInitialStock() { return initialStock; }
    public void setInitialStock(int initialStock) { this.initialStock = initialStock; }
    public int getAvailableStock() { return availableStock; }
    public void setAvailableStock(int availableStock) { this.availableStock = availableStock; }
    public int getReservedStock() { return reservedStock; }
    public void setReservedStock(int reservedStock) { this.reservedStock = reservedStock; }
    public int getSoldStock() { return soldStock; }
    public void setSoldStock(int soldStock) { this.soldStock = soldStock; }
    public int getPurchaseLimit() { return purchaseLimit; }
    public void setPurchaseLimit(int purchaseLimit) { this.purchaseLimit = purchaseLimit; }
}