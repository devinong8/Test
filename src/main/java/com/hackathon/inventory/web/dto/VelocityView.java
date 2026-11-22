package com.hackathon.inventory.web.dto;

import java.math.BigDecimal;

public record VelocityView(Long shopId, String shopCode, Long skuId, String skuCode, String skuName,
                           BigDecimal dailySalesVelocity, long onHandQty, Double daysOfCover) {}
