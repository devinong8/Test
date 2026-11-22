package com.hackathon.inventory.web.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record SkuRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 80) String category,
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 12, fraction = 2) BigDecimal purchasePrice,
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 12, fraction = 2) BigDecimal salePrice,
        @NotNull @DecimalMin(value = "0.00001") @Digits(integer = 7, fraction = 5) BigDecimal unitVolumeM3,
        @NotNull @Min(1) @Max(36500) Integer shelfLifeDays) {}
