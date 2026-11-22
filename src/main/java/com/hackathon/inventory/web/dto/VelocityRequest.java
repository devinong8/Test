package com.hackathon.inventory.web.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record VelocityRequest(@NotNull @DecimalMin("0.000") @Digits(integer = 9, fraction = 3) BigDecimal dailySalesVelocity) {}
