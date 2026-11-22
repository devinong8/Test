package com.hackathon.inventory.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** Historical daily sales velocity (units/day) of a SKU at a shop. */
@Entity
@Table(name = "sales_velocity", uniqueConstraints = @UniqueConstraint(columnNames = {"shop_id", "sku_id"}))
public class SalesVelocity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "shop_id")
    private Shop shop;
    @ManyToOne(optional = false) @JoinColumn(name = "sku_id")
    private Sku sku;
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal dailySalesVelocity;

    public SalesVelocity() {}
    public SalesVelocity(Shop shop, Sku sku, BigDecimal dailySalesVelocity) {
        this.shop = shop; this.sku = sku; this.dailySalesVelocity = dailySalesVelocity;
    }

    public Long getId() { return id; }
    public Shop getShop() { return shop; }
    public Sku getSku() { return sku; }
    public BigDecimal getDailySalesVelocity() { return dailySalesVelocity; }
    public void setDailySalesVelocity(BigDecimal v) { this.dailySalesVelocity = v; }
}
