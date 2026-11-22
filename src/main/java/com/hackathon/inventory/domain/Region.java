package com.hackathon.inventory.domain;

import jakarta.persistence.*;

/**
 * A sales region run by a regional HQ. HQ owns the SKU catalogue and sets each shop's storage, budget and velocity;
 * every shop belongs to exactly one region.
 */
@Entity
@Table(name = "region", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
public class Region {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 40)
    private String code;
    @Column(nullable = false)
    private String name;
    /** City where the regional HQ office sits. */
    @Column(nullable = false, length = 120)
    private String hqCity;
    private boolean active = true;

    public Region() {}
    public Region(String code, String name, String hqCity) { this.code = code; this.name = name; this.hqCity = hqCity; }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHqCity() { return hqCity; }
    public void setHqCity(String hqCity) { this.hqCity = hqCity; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
