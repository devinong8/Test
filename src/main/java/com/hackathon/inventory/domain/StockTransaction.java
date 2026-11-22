package com.hackathon.inventory.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable ledger entry. quantityDelta is signed (+in / -out), so the on-hand quantity of any batch at
 * any point in time can be rebuilt by summing its entries - this powers historical monthly reports.
 */
@Entity
@Table(name = "stock_transaction", indexes = {
        @Index(name = "ix_txn_time", columnList = "occurredAt"),
        @Index(name = "ix_txn_shop_sku", columnList = "shop_id,sku_id")})
public class StockTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private TransactionType type;
    @Enumerated(EnumType.STRING) @Column(length = 30)
    private StockOutReason reason;
    @ManyToOne(optional = false) @JoinColumn(name = "shop_id")
    private Shop shop;
    @ManyToOne(optional = false) @JoinColumn(name = "sku_id")
    private Sku sku;
    @ManyToOne(optional = false) @JoinColumn(name = "batch_id")
    private InventoryBatch batch;
    @Column(nullable = false)
    private long quantityDelta;
    @Column(nullable = false)
    private long balanceAfter;
    /** Purchase price at the time of the movement (cost of the movement = |qty| x unitCost). */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal unitCost;
    @Column(length = 80)
    private String reference;
    @Column(length = 255)
    private String note;
    @Column(nullable = false)
    private Instant occurredAt;
    @Column(length = 64)
    private String correlationId;

    public StockTransaction() {}

    public StockTransaction(TransactionType type, StockOutReason reason, InventoryBatch batch, long quantityDelta,
                            long balanceAfter, String reference, String note, Instant occurredAt, String correlationId) {
        this.type = type; this.reason = reason; this.batch = batch; this.shop = batch.getShop(); this.sku = batch.getSku();
        this.quantityDelta = quantityDelta; this.balanceAfter = balanceAfter; this.unitCost = batch.getSku().getPurchasePrice();
        this.reference = reference; this.note = note; this.occurredAt = occurredAt; this.correlationId = correlationId;
    }

    public Long getId() { return id; }
    public TransactionType getType() { return type; }
    public StockOutReason getReason() { return reason; }
    public Shop getShop() { return shop; }
    public Sku getSku() { return sku; }
    public InventoryBatch getBatch() { return batch; }
    public long getQuantityDelta() { return quantityDelta; }
    public long getBalanceAfter() { return balanceAfter; }
    public BigDecimal getUnitCost() { return unitCost; }
    public String getReference() { return reference; }
    public String getNote() { return note; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getCorrelationId() { return correlationId; }
}
