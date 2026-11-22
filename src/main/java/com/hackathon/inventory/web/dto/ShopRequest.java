package com.hackathon.inventory.web.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record ShopRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 120) String city,
        @NotNull @DecimalMin(value = "0.001") @Digits(integer = 9, fraction = 3) BigDecimal storageCapacityM3,
        @NotNull @DecimalMin(value = "0.00") @Digits(integer = 14, fraction = 2) BigDecimal capitalBudget) {}
