package com.hackathon.inventory.web.dto;

import java.math.BigDecimal;

/** valueAtRisk = purchase-cost value exposed (for expiry alerts), used to rank alerts within a severity. */
public record Alert(String severity, String type, Long shopId, String shopCode, Long skuId, String skuCode,
                    String message, Long suggestedQty, BigDecimal valueAtRisk) {

    public Alert(String severity, String type, Long shopId, String shopCode, Long skuId, String skuCode,
                 String message, Long suggestedQty) {
        this(severity, type, shopId, shopCode, skuId, skuCode, message, suggestedQty, null);
    }
}
