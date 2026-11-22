package com.hackathon.inventory.web.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record StockInRequest(
        @NotNull Long shopId,
        @NotNull Long skuId,
        @NotNull @Min(1) @Max(1_000_000) Long quantity,
        @Size(max = 60) String batchNo,
        @NotNull @PastOrPresent LocalDate batchProductionDate,
        @Size(max = 80) String reference,
        @Size(max = 255) String note) {}
