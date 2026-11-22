package com.hackathon.inventory.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;

/** Per-shop figures inside a monthly report (denormalised so history survives master-data edits). */
@Entity
@Table(name = "monthly_report_line")
public class MonthlyReportLine {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @JsonIgnore
    @ManyToOne(optional = false, fetch = FetchType.LAZY) @JoinColumn(name = "report_id")
    private MonthlyReport report;
    private Long shopId;
    private String shopCode;
    private String shopName;
    private String city;
    private Long regionId;
    private String regionCode;
    private String regionName;
    @Column(precision = 18, scale = 2) private BigDecimal costValue;
    @Column(precision = 18, scale = 2) private BigDecimal retailValue;
    @Column(precision = 18, scale = 2) private BigDecimal cogs30d;
    private double turnoverRate;
    @Column(precision = 18, scale = 2) private BigDecimal provision;
    @Column(precision = 18, scale = 2) private BigDecimal netRealisableCost;
    @Column(precision = 18, scale = 2) private BigDecimal normalCost;
    @Column(precision = 18, scale = 2) private BigDecimal moderateCost;
    @Column(precision = 18, scale = 2) private BigDecimal expiredCost;
    @Column(precision = 18, scale = 2) private BigDecimal highRiskCost;
    private double storageUtilisation;
    private double budgetUtilisation;

    public MonthlyReportLine() {}

    public Long getId() { return id; }
    public MonthlyReport getReport() { return report; }
    public void setReport(MonthlyReport report) { this.report = report; }
    public Long getShopId() { return shopId; }
    public void setShopId(Long shopId) { this.shopId = shopId; }
    public String getShopCode() { return shopCode; }
    public void setShopCode(String shopCode) { this.shopCode = shopCode; }
    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public Long getRegionId() { return regionId; }
    public void setRegionId(Long v) { this.regionId = v; }
    public String getRegionCode() { return regionCode; }
    public void setRegionCode(String v) { this.regionCode = v; }
    public String getRegionName() { return regionName; }
    public void setRegionName(String v) { this.regionName = v; }
    public BigDecimal getNormalCost() { return normalCost; }
    public void setNormalCost(BigDecimal v) { this.normalCost = v; }
    public BigDecimal getModerateCost() { return moderateCost; }
    public void setModerateCost(BigDecimal v) { this.moderateCost = v; }
    public BigDecimal getCostValue() { return costValue; }
    public void setCostValue(BigDecimal v) { this.costValue = v; }
    public BigDecimal getRetailValue() { return retailValue; }
    public void setRetailValue(BigDecimal v) { this.retailValue = v; }
    public BigDecimal getCogs30d() { return cogs30d; }
    public void setCogs30d(BigDecimal v) { this.cogs30d = v; }
    public double getTurnoverRate() { return turnoverRate; }
    public void setTurnoverRate(double v) { this.turnoverRate = v; }
    public BigDecimal getProvision() { return provision; }
    public void setProvision(BigDecimal v) { this.provision = v; }
    public BigDecimal getNetRealisableCost() { return netRealisableCost; }
    public void setNetRealisableCost(BigDecimal v) { this.netRealisableCost = v; }
    public BigDecimal getExpiredCost() { return expiredCost; }
    public void setExpiredCost(BigDecimal v) { this.expiredCost = v; }
    public BigDecimal getHighRiskCost() { return highRiskCost; }
    public void setHighRiskCost(BigDecimal v) { this.highRiskCost = v; }
    public double getStorageUtilisation() { return storageUtilisation; }
    public void setStorageUtilisation(double v) { this.storageUtilisation = v; }
    public double getBudgetUtilisation() { return budgetUtilisation; }
    public void setBudgetUtilisation(double v) { this.budgetUtilisation = v; }
}
