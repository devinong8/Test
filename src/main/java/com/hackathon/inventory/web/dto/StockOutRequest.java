package com.hackathon.inventory.web.dto;

import com.hackathon.inventory.domain.StockOutReason;
import jakarta.validation.constraints.*;

public record StockOutRequest(
        @NotNull Long shopId,
        @NotNull Long skuId,
        @NotNull @Min(1) @Max(1_000_000) Long quantity,
        @NotNull StockOutReason reason,
        /* optional: consume one specific batch instead of FEFO allocation */ Long batchId,
        @Size(max = 80) String reference,
        @Size(max = 255) String note) {}
