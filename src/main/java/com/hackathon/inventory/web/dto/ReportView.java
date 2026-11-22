package com.hackathon.inventory.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** A monthly report as seen by the current user: totals are re-aggregated from the lines inside their scope. */
public record ReportView(String period, LocalDate asOfDate, Instant generatedAt, String trigger, int shopCount,
                         BigDecimal totalCostValue, BigDecimal totalRetailValue, BigDecimal totalCogs30d,
                         double turnoverRate, BigDecimal totalProvision, BigDecimal netRealisableCost,
                         BigDecimal normalCost, BigDecimal moderateCost, BigDecimal highCost, BigDecimal expiredCost,
                         List<RegionLine> regions) {

    public record RegionLine(Long regionId, String regionCode, String regionName, int shopCount,
                             BigDecimal costValue, BigDecimal retailValue, BigDecimal cogs30d, double turnoverRate,
                             BigDecimal provision, BigDecimal netRealisableCost) {}
}
