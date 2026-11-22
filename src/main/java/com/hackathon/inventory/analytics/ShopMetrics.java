package com.hackathon.inventory.analytics;

import com.hackathon.inventory.domain.RiskTier;

import java.math.BigDecimal;
import java.util.Map;

public record ShopMetrics(Long shopId, String shopCode, String shopName, String city, long units, int skuCount,
                          BigDecimal costValue, BigDecimal retailValue, BigDecimal cogs30d, double turnoverRate,
                          BigDecimal provision, BigDecimal netRealisableCost, Map<RiskTier, BigDecimal> costByTier,
                          BigDecimal usedVolumeM3, BigDecimal storageCapacityM3, double storageUtilisation,
                          BigDecimal capitalBudget, double budgetUtilisation) {}
