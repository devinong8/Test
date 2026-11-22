package com.hackathon.inventory.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Persisted monthly management report snapshot (one per period, e.g. 2026-10). */
@Entity
@Table(name = "monthly_report", uniqueConstraints = @UniqueConstraint(columnNames = "period"))
public class MonthlyReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 7)
    private String period;
    @Column(nullable = false)
    private LocalDate asOfDate;
    @Column(nullable = false)
    private Instant generatedAt;
    @Column(nullable = false, length = 20)
    private String trigger;
    @Column(precision = 18, scale = 2) private BigDecimal totalCostValue;
    @Column(precision = 18, scale = 2) private BigDecimal totalRetailValue;
    @Column(precision = 18, scale = 2) private BigDecimal totalCogs30d;
    private double turnoverRate;
    @Column(precision = 18, scale = 2) private BigDecimal totalProvision;
    @Column(precision = 18, scale = 2) private BigDecimal netRealisableCost;
    @Column(precision = 18, scale = 2) private BigDecimal normalCost;
    @Column(precision = 18, scale = 2) private BigDecimal moderateCost;
    @Column(precision = 18, scale = 2) private BigDecimal highCost;
    @Column(precision = 18, scale = 2) private BigDecimal expiredCost;
    private int shopCount;

    @JsonIgnore
    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("shopCode ASC")
    private List<MonthlyReportLine> lines = new ArrayList<>();

    public MonthlyReport() {}

    public Long getId() { return id; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public LocalDate getAsOfDate() { return asOfDate; }
    public void setAsOfDate(LocalDate asOfDate) { this.asOfDate = asOfDate; }
    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }
    public String getTrigger() { return trigger; }
    public void setTrigger(String trigger) { this.trigger = trigger; }
    public BigDecimal getTotalCostValue() { return totalCostValue; }
    public void setTotalCostValue(BigDecimal v) { this.totalCostValue = v; }
    public BigDecimal getTotalRetailValue() { return totalRetailValue; }
    public void setTotalRetailValue(BigDecimal v) { this.totalRetailValue = v; }
    public BigDecimal getTotalCogs30d() { return totalCogs30d; }
    public void setTotalCogs30d(BigDecimal v) { this.totalCogs30d = v; }
    public double getTurnoverRate() { return turnoverRate; }
    public void setTurnoverRate(double turnoverRate) { this.turnoverRate = turnoverRate; }
    public BigDecimal getTotalProvision() { return totalProvision; }
    public void setTotalProvision(BigDecimal v) { this.totalProvision = v; }
    public BigDecimal getNetRealisableCost() { return netRealisableCost; }
    public void setNetRealisableCost(BigDecimal v) { this.netRealisableCost = v; }
    public BigDecimal getNormalCost() { return normalCost; }
    public void setNormalCost(BigDecimal v) { this.normalCost = v; }
    public BigDecimal getModerateCost() { return moderateCost; }
    public void setModerateCost(BigDecimal v) { this.moderateCost = v; }
    public BigDecimal getHighCost() { return highCost; }
    public void setHighCost(BigDecimal v) { this.highCost = v; }
    public BigDecimal getExpiredCost() { return expiredCost; }
    public void setExpiredCost(BigDecimal v) { this.expiredCost = v; }
    public int getShopCount() { return shopCount; }
    public void setShopCount(int shopCount) { this.shopCount = shopCount; }
    public List<MonthlyReportLine> getLines() { return lines; }
}
