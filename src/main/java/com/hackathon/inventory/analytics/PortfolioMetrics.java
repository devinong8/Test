package com.hackathon.inventory.analytics;

import com.hackathon.inventory.domain.RiskTier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record PortfolioMetrics(LocalDate asOfDate, long units, BigDecimal costValue, BigDecimal retailValue,
                               BigDecimal potentialGrossMargin, BigDecimal cogs30d, double turnoverRate,
                               BigDecimal provision, BigDecimal netRealisableCost,
                               Map<RiskTier, BigDecimal> costByTier, List<RegionMetrics> regions,
                               List<ShopMetrics> shops, List<BatchRisk> batches) {

    public PortfolioMetrics withoutBatches() {
        return new PortfolioMetrics(asOfDate, units, costValue, retailValue, potentialGrossMargin, cogs30d,
                turnoverRate, provision, netRealisableCost, costByTier, regions, shops, List.of());
    }
}
