package com.hackathon.inventory.analytics;

import com.hackathon.inventory.analytics.PortfolioAnalyzer.VelocityKey;
import com.hackathon.inventory.domain.RiskTier;
import com.hackathon.inventory.domain.Shop;
import com.hackathon.inventory.domain.Sku;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Hand-computed worked example of the whole valuation / turnover / depreciation pipeline. */
class PortfolioAnalyzerTest {
    static final LocalDate TODAY = LocalDate.of(2026, 11, 22);

    static Sku sku(long id, String cost, String price, String vol, int life) {
        Sku s = new Sku("S" + id, "Sku " + id, "Cat", new BigDecimal(cost), new BigDecimal(price), new BigDecimal(vol), life);
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    static final com.hackathon.inventory.domain.Region REGION = region(7, "R1");

    static com.hackathon.inventory.domain.Region region(long id, String code) {
        var r = new com.hackathon.inventory.domain.Region(code, "Region " + code);
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    static Shop shop(long id, String cap, String budget) {
        Shop s = new Shop(REGION, "SH" + id, "Shop " + id, "City", new BigDecimal(cap), new BigDecimal(budget));
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    @Test
    void computesValuationTurnoverAndProvisionPerShopAndRegion() {
        Sku milk = sku(1, "2.00", "3.50", "0.001", 10);   // short shelf life
        Sku rice = sku(2, "8.00", "12.00", "0.005", 100);
        Shop a = shop(1, "10", "5000");
        Shop b = shop(2, "10", "5000");

        List<Position> positions = List.of(
                new Position(a, milk, 1L, "M-1", TODAY.minusDays(3), 50),    // R=0.30 NORMAL  cost 100
                new Position(a, milk, 2L, "M-2", TODAY.minusDays(9), 10),    // R=0.90 HIGH    cost 20  prov 12
                new Position(a, rice, 3L, "R-1", TODAY.minusDays(70), 20),   // R=0.70 MODERATE cost 160 prov 40
                new Position(b, rice, 4L, "R-2", TODAY.minusDays(101), 5));  // R=1.01 EXPIRED cost 40 prov 40

        Map<VelocityKey, BigDecimal> vel = Map.of(
                new VelocityKey(1L, 1L), new BigDecimal("5"),    // 5*30*2 = 300
                new VelocityKey(1L, 2L), new BigDecimal("1"),    // 1*30*8 = 240
                new VelocityKey(2L, 2L), new BigDecimal("0.5")); // 0.5*30*8 = 120

        PortfolioMetrics pm = PortfolioAnalyzer.analyze(List.of(a, b), positions, vel, Map.of(1L, milk, 2L, rice), TODAY);

        ShopMetrics sa = pm.shops().get(0);
        assertThat(sa.costValue()).isEqualByComparingTo("280.00");          // 100 + 20 + 160
        assertThat(sa.retailValue()).isEqualByComparingTo("450.00");        // 175 + 35 + 240
        assertThat(sa.cogs30d()).isEqualByComparingTo("540.00");
        assertThat(sa.turnoverRate()).isCloseTo(540.0 / 280.0, within(1e-6));
        assertThat(sa.provision()).isEqualByComparingTo("52.00");           // 12 + 40
        assertThat(sa.netRealisableCost()).isEqualByComparingTo("228.00");
        assertThat(sa.usedVolumeM3()).isEqualByComparingTo("0.160");        // 60*0.001 + 20*0.005
        assertThat(sa.storageUtilisation()).isCloseTo(0.016, within(1e-9));
        assertThat(sa.budgetUtilisation()).isCloseTo(280.0 / 5000, within(1e-9));
        assertThat(sa.costByTier()).containsEntry(RiskTier.HIGH, new BigDecimal("20.00"));

        ShopMetrics sb = pm.shops().get(1);
        assertThat(sb.costValue()).isEqualByComparingTo("40.00");
        assertThat(sb.provision()).isEqualByComparingTo("40.00");           // fully written off
        assertThat(sb.netRealisableCost()).isEqualByComparingTo("0.00");

        assertThat(pm.costValue()).isEqualByComparingTo("320.00");
        assertThat(pm.retailValue()).isEqualByComparingTo("510.00");
        assertThat(pm.potentialGrossMargin()).isEqualByComparingTo("190.00");
        assertThat(pm.cogs30d()).isEqualByComparingTo("660.00");
        assertThat(pm.provision()).isEqualByComparingTo("92.00");
        assertThat(pm.units()).isEqualTo(85);
        // batches sorted by risk, worst first
        assertThat(pm.batches()).extracting(BatchRisk::tier)
                .containsExactly(RiskTier.EXPIRED, RiskTier.HIGH, RiskTier.MODERATE, RiskTier.NORMAL);
        assertThat(pm.batches().get(0).daysToExpiry()).isEqualTo(-1);

        // regional HQ roll-up = sum of its shops; turnover recomputed from sums, not averaged
        assertThat(pm.regions()).singleElement().satisfies(r -> {
            assertThat(r.regionCode()).isEqualTo("R1");
            assertThat(r.shopCount()).isEqualTo(2);
            assertThat(r.costValue()).isEqualByComparingTo("320.00");
            assertThat(r.turnoverRate()).isCloseTo(660.0 / 320.0, within(1e-6));
            assertThat(r.storageUtilisation()).isCloseTo((0.160 + 0.025) / 20, within(1e-9));
        });
    }

    @Test
    void emptyShopHasZeroValueAndNoDivisionError() {
        Sku milk = sku(1, "2.00", "3.50", "0.001", 10);
        Shop a = shop(1, "10", "5000");
        PortfolioMetrics pm = PortfolioAnalyzer.analyze(List.of(a), List.of(),
                Map.of(new VelocityKey(1L, 1L), new BigDecimal("2")), Map.of(1L, milk), TODAY);
        ShopMetrics s = pm.shops().get(0);
        assertThat(s.costValue()).isEqualByComparingTo("0");
        assertThat(s.turnoverRate()).isFinite().isGreaterThan(1e6);   // 120 / 1e-6
    }
}
