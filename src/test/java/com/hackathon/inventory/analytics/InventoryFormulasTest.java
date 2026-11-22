package com.hackathon.inventory.analytics;

import com.hackathon.inventory.domain.RiskTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class InventoryFormulasTest {

    @Test
    void valuationIsQuantityTimesPrice() {
        assertThat(InventoryFormulas.lineValue(120, new BigDecimal("1.80"))).isEqualByComparingTo("216.00");
        assertThat(InventoryFormulas.lineValue(0, new BigDecimal("9.99"))).isEqualByComparingTo("0");
    }

    @Test
    void cogs30dIsVelocityTimes30TimesPurchasePrice() {
        // 4.5 units/day x 30 days x ¥2.40 = ¥324.00
        assertThat(InventoryFormulas.cogs30d(new BigDecimal("4.5"), new BigDecimal("2.40"))).isEqualByComparingTo("324.00");
    }

    @Test
    void turnoverIsCogsOverCostPlusEpsilon() {
        assertThat(InventoryFormulas.turnoverRate(new BigDecimal("324.00"), new BigDecimal("162.00")))
                .isCloseTo(2.0, within(1e-6));
    }

    @Test
    void epsilonPreventsDivisionByZeroWhenInventoryEmpty() {
        double t = InventoryFormulas.turnoverRate(new BigDecimal("10.00"), BigDecimal.ZERO);
        assertThat(t).isFinite().isEqualTo(10.0 / 1e-6);
        assertThat(InventoryFormulas.turnoverRate(BigDecimal.ZERO, BigDecimal.ZERO)).isZero();
    }

    @Test
    void elapsedDaysAndConsumptionRatio() {
        long elapsed = InventoryFormulas.elapsedDays(LocalDate.of(2026, 11, 22), LocalDate.of(2026, 10, 23));
        assertThat(elapsed).isEqualTo(30);
        assertThat(InventoryFormulas.shelfLifeConsumptionRatio(elapsed, 40)).isEqualTo(0.75);
        assertThatThrownBy(() -> InventoryFormulas.shelfLifeConsumptionRatio(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "R_dep={0} -> {1}")
    @CsvSource({
            "0.00, NORMAL", "0.5999, NORMAL",
            "0.60, MODERATE", "0.8499, MODERATE",
            "0.85, HIGH", "0.99, HIGH", "1.00, HIGH",      // 1.00 is inclusive in HIGH
            "1.0001, EXPIRED", "2.5, EXPIRED"})
    void riskTierBoundariesMatchSpecification(double rDep, RiskTier expected) {
        assertThat(RiskTier.classify(rDep)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"NORMAL, 0.00", "MODERATE, 25.00", "HIGH, 60.00", "EXPIRED, 100.00"})
    void provisionRatesApplyToPurchaseCost(RiskTier tier, String expected) {
        assertThat(InventoryFormulas.provision(new BigDecimal("100.00"), tier)).isEqualByComparingTo(expected);
    }
}
