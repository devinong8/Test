package com.hackathon.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Stock of one SKU at one shop from one production batch. Batches are tracked separately because
 * depreciation risk depends on each batch's production date.
 */
@Entity
@Table(name = "inventory_batch", indexes = {
        @Index(name = "ix_batch_shop_sku", columnList = "shop_id,sku_id"),
        @Index(name = "ix_batch_prod", columnList = "batchProductionDate")})
public class InventoryBatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "shop_id")
    private Shop shop;
    @ManyToOne(optional = false) @JoinColumn(name = "sku_id")
    private Sku sku;
    @Column(nullable = false, length = 60)
    private String batchNo;
    @Column(nullable = false)
    private LocalDate batchProductionDate;
    @Column(nullable = false)
    private long onHandQty;
    @Column(nullable = false)
    private Instant receivedAt;
    @Version
    private long version;

    public InventoryBatch() {}
    public InventoryBatch(Shop shop, Sku sku, String batchNo, LocalDate batchProductionDate, Instant receivedAt) {
        this.shop = shop; this.sku = sku; this.batchNo = batchNo;
        this.batchProductionDate = batchProductionDate; this.receivedAt = receivedAt;
    }

    public Long getId() { return id; }
    public Shop getShop() { return shop; }
    public Sku getSku() { return sku; }
    public String getBatchNo() { return batchNo; }
    public LocalDate getBatchProductionDate() { return batchProductionDate; }
    public long getOnHandQty() { return onHandQty; }
    public void setOnHandQty(long onHandQty) { this.onHandQty = onHandQty; }
    public Instant getReceivedAt() { return receivedAt; }
    public long getVersion() { return version; }
}
