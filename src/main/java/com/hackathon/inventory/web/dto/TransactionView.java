package com.hackathon.inventory.web.dto;

import com.hackathon.inventory.domain.StockTransaction;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionView(Long id, String type, String reason, Long shopId, String shopCode, Long skuId,
                              String skuCode, String skuName, Long batchId, String batchNo, long quantityDelta,
                              long balanceAfter, BigDecimal unitCost, BigDecimal costAmount, String reference,
                              String note, Instant occurredAt, String correlationId) {
    public static TransactionView of(StockTransaction t) {
        return new TransactionView(t.getId(), t.getType().name(), t.getReason() == null ? null : t.getReason().name(),
                t.getShop().getId(), t.getShop().getCode(), t.getSku().getId(), t.getSku().getCode(),
                t.getSku().getName(), t.getBatch().getId(), t.getBatch().getBatchNo(), t.getQuantityDelta(),
                t.getBalanceAfter(), t.getUnitCost(),
                t.getUnitCost().multiply(BigDecimal.valueOf(Math.abs(t.getQuantityDelta()))), t.getReference(),
                t.getNote(), t.getOccurredAt(), t.getCorrelationId());
    }
}
