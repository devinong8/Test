package com.hackathon.inventory.analytics;

import com.hackathon.inventory.domain.RiskTier;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The reference formulas from the problem statement, implemented exactly and side-effect free.
 * <pre>
 *  V_cost        = sum(on_hand_qty x purchase_price)
 *  V_retail      = sum(on_hand_qty x sale_price)
 *  COGS_30d      = daily_sales_velocity x 30 x purchase_price
 *  Turnover Rate = COGS_30d / (V_cost + epsilon),  epsilon = 1e-6
 *  Elapsed Days  = Current Date - batch_production_date
 *  R_dep         = Elapsed Days / shelf_life_days
 * </pre>
 */
public final class InventoryFormulas {
    public static final double EPSILON = 1e-6;
    public static final int COGS_WINDOW_DAYS = 30;
    private static final int MONEY_SCALE = 2;

    private InventoryFormulas() {}

    public static BigDecimal lineValue(long onHandQty, BigDecimal unitPrice) {
        return unitPrice.multiply(BigDecimal.valueOf(onHandQty)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal cogs30d(BigDecimal dailySalesVelocity, BigDecimal purchasePrice) {
        return dailySalesVelocity.multiply(BigDecimal.valueOf(COGS_WINDOW_DAYS)).multiply(purchasePrice)
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static double turnoverRate(BigDecimal cogs30d, BigDecimal vCost) {
        return cogs30d.doubleValue() / (vCost.doubleValue() + EPSILON);
    }

    public static long elapsedDays(LocalDate currentDate, LocalDate batchProductionDate) {
        return ChronoUnit.DAYS.between(batchProductionDate, currentDate);
    }

    public static double shelfLifeConsumptionRatio(long elapsedDays, int shelfLifeDays) {
        if (shelfLifeDays <= 0) throw new IllegalArgumentException("shelf_life_days must be positive");
        return (double) elapsedDays / shelfLifeDays;
    }

    /** Valuation provision = purchase-cost value x tier provision rate. */
    public static BigDecimal provision(BigDecimal costValue, RiskTier tier) {
        return costValue.multiply(tier.provisionRate()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public static double ratio(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) return 0d;
        return part.doubleValue() / whole.doubleValue();
    }
}
