package com.hackathon.inventory.analytics;

import com.hackathon.inventory.domain.RiskTier;

import java.math.BigDecimal;
import java.util.Map;

/** Roll-up of a region's shops. Utilisation is capacity-weighted (sum used / sum capacity). */
public record RegionMetrics(Long regionId, String regionCode, String regionName, int shopCount,
                            long units, BigDecimal costValue, BigDecimal retailValue, BigDecimal cogs30d,
                            double turnoverRate, BigDecimal provision, BigDecimal netRealisableCost,
                            Map<RiskTier, BigDecimal> costByTier, double storageUtilisation,
                            double budgetUtilisation) {}
