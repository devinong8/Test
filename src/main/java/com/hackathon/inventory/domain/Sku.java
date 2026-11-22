package com.hackathon.inventory.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** Product SKU master data: pricing, storage footprint and shelf life. */
@Entity
@Table(name = "sku", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
public class Sku {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 40)
    private String code;
    @Column(nullable = false)
    private String name;
    private String category;
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal purchasePrice;
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal salePrice;
    /** Unit storage volume in cubic metres. */
    @Column(nullable = false, precision = 12, scale = 5)
    private BigDecimal unitVolumeM3;
    @Column(nullable = false)
    private int shelfLifeDays;
    private boolean active = true;

    public Sku() {}

    public Sku(String code, String name, String category, BigDecimal purchasePrice, BigDecimal salePrice,
               BigDecimal unitVolumeM3, int shelfLifeDays) {
        this.code = code; this.name = name; this.category = category; this.purchasePrice = purchasePrice;
        this.salePrice = salePrice; this.unitVolumeM3 = unitVolumeM3; this.shelfLifeDays = shelfLifeDays;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public BigDecimal getPurchasePrice() { return purchasePrice; }
    public void setPurchasePrice(BigDecimal purchasePrice) { this.purchasePrice = purchasePrice; }
    public BigDecimal getSalePrice() { return salePrice; }
    public void setSalePrice(BigDecimal salePrice) { this.salePrice = salePrice; }
    public BigDecimal getUnitVolumeM3() { return unitVolumeM3; }
    public void setUnitVolumeM3(BigDecimal unitVolumeM3) { this.unitVolumeM3 = unitVolumeM3; }
    public int getShelfLifeDays() { return shelfLifeDays; }
    public void setShelfLifeDays(int shelfLifeDays) { this.shelfLifeDays = shelfLifeDays; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
