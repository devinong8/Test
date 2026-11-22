package com.hackathon.inventory.web.dto;

public record Alert(String severity, String type, Long shopId, String shopCode, Long skuId, String skuCode,
                    String message, Long suggestedQty) {}
