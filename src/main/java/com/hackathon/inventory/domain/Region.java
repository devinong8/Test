package com.hackathon.inventory.domain;

import jakarta.persistence.*;

/**
 * A sales region (片区): a grouping of shops by geography, managed by the single HQ. It has no HQ of its own. Used
 * for roll-ups (regional subtotals in analytics and reports) and region-specific demand, e.g. 冬至 dumplings in the
 * north vs tangyuan in the south. Every shop belongs to exactly one region.
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
    private boolean active = true;

    public Region() {}
    public Region(String code, String name) { this.code = code; this.name = name; }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
