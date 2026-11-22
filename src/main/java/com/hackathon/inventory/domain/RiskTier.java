package com.hackathon.inventory.domain;

import java.math.BigDecimal;

/** Depreciation risk rating classification based on shelf-life consumption ratio R_dep. */
public enum RiskTier {
    NORMAL("0.00"),      // R < 0.60
    MODERATE("0.25"),    // 0.60 <= R < 0.85
    HIGH("0.60"),        // 0.85 <= R <= 1.00
    EXPIRED("1.00");     // R > 1.00

    private final BigDecimal provisionRate;

    RiskTier(String rate) { this.provisionRate = new BigDecimal(rate); }

    public BigDecimal provisionRate() { return provisionRate; }

    public static RiskTier classify(double rDep) {
        if (rDep > 1.00) return EXPIRED;
        if (rDep >= 0.85) return HIGH;
        if (rDep >= 0.60) return MODERATE;
        return NORMAL;
    }
}
