package com.hackathon.inventory.web.dto;

import java.math.BigDecimal;

public record ShopView(Long id, Long regionId, String regionCode, String regionName, String code, String name,
                       String city, BigDecimal storageCapacityM3, BigDecimal capitalBudget, boolean active,
                       BigDecimal usedVolumeM3, BigDecimal costValue, double storageUtilisation,
                       double budgetUtilisation) {}
