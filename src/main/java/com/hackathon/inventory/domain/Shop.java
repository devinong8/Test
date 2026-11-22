package com.hackathon.inventory.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** A regional shop with physical storage and capital budget limits. */
@Entity
@Table(name = "shop", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
public class Shop {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 40)
    private String code;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String city;
    @ManyToOne(optional = false) @JoinColumn(name = "region_id")
    private Region region;
    /** Physical storage boundary in cubic metres. */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal storageCapacityM3;
    /** Capital budget ceiling: max inventory value held at purchase cost. */
    @Column(nullable = false, precision = 16, scale = 2)
    private BigDecimal capitalBudget;
    private boolean active = true;

    public Shop() {}

    public Shop(Region region, String code, String name, String city, BigDecimal storageCapacityM3, BigDecimal capitalBudget) {
        this.region = region; this.code = code; this.name = name; this.city = city;
        this.storageCapacityM3 = storageCapacityM3; this.capitalBudget = capitalBudget;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public Region getRegion() { return region; }
    public void setRegion(Region region) { this.region = region; }
    public BigDecimal getStorageCapacityM3() { return storageCapacityM3; }
    public void setStorageCapacityM3(BigDecimal v) { this.storageCapacityM3 = v; }
    public BigDecimal getCapitalBudget() { return capitalBudget; }
    public void setCapitalBudget(BigDecimal capitalBudget) { this.capitalBudget = capitalBudget; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
