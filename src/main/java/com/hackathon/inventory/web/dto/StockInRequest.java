package com.hackathon.inventory.web.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record StockInRequest(
        @NotNull Long shopId,
        @NotNull Long skuId,
        @NotNull @Min(1) @Max(1_000_000) Long quantity,
        @Size(max = 60) String batchNo,
        /* "not in the future" is checked in InventoryService against the business clock (Asia/Shanghai);
           @PastOrPresent would use the JVM default zone (UTC on most servers) and reject today's date before 08:00 CST */
        @NotNull LocalDate batchProductionDate,
        @Size(max = 80) String reference,
        @Size(max = 255) String note) {}
