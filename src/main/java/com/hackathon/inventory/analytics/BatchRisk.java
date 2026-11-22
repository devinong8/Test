package com.hackathon.inventory.analytics;

import com.hackathon.inventory.domain.RiskTier;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BatchRisk(Long batchId, String batchNo, Long shopId, String shopCode, String shopName, String city,
                        Long skuId, String skuCode, String skuName, String category, LocalDate productionDate,
                        LocalDate expiryDate, int shelfLifeDays, long elapsedDays, long daysToExpiry, double rDep,
                        RiskTier tier, long onHandQty, BigDecimal costValue, BigDecimal retailValue,
                        BigDecimal provision) {}
