package com.hackathon.inventory.service;

import com.hackathon.inventory.TestSupport;
import com.hackathon.inventory.config.BusinessRuleException;
import com.hackathon.inventory.domain.*;
import com.hackathon.inventory.web.dto.StockInRequest;
import com.hackathon.inventory.web.dto.StockOutRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.*;

class ReportServiceIntegrationTest extends TestSupport {
    @Autowired InventoryService inventory;
    @Autowired ReportService reports;
    @Autowired ReportScheduler scheduler;
    @Autowired AnalyticsService analytics;

    @Test
    void reportRebuildsInventoryAsOfMonthEndFromLedger() {
        Shop shop = shop("SH-01", "100", "100000");
        Sku rice = sku("RICE", "8.00", "12.00", "0.005", 100);
        velocityRepo.save(new SalesVelocity(shop, rice, new BigDecimal("2")));   // configured: used live, not in reports

        LocalDate prod = LocalDate.of(2026, 9, 1);
        inventory.stockIn(new StockInRequest(shop.getId(), rice.getId(), 100L, null, prod, null, null), at(LocalDate.of(2026, 9, 5)));
        inventory.stockOut(new StockOutRequest(shop.getId(), rice.getId(), 40L, StockOutReason.SALE, null, null, null), at(LocalDate.of(2026, 10, 10)));
        inventory.stockOut(new StockOutRequest(shop.getId(), rice.getId(), 50L, StockOutReason.SALE, null, null, null), at(LocalDate.of(2026, 11, 3)));

        // September: 100 units; as-of 2026-09-30 elapsed 29d -> R 0.29 NORMAL
        MonthlyReport sep = reports.generate(YearMonth.of(2026, 9), "TEST");
        assertThat(sep.getAsOfDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(sep.getTotalCostValue()).isEqualByComparingTo("800.00");
        assertThat(sep.getTotalRetailValue()).isEqualByComparingTo("1200.00");
        assertThat(sep.getTotalCogs30d()).isEqualByComparingTo("0.00");          // nothing sold in September
        assertThat(sep.getTurnoverRate()).isZero();
        assertThat(sep.getTotalProvision()).isEqualByComparingTo("0.00");

        // October: 60 units; elapsed 60d -> R 0.60 MODERATE -> 25% provision
        MonthlyReport oct = reports.generate(YearMonth.of(2026, 10), "TEST");
        assertThat(oct.getTotalCostValue()).isEqualByComparingTo("480.00");
        assertThat(oct.getModerateCost()).isEqualByComparingTo("480.00");
        assertThat(oct.getTotalProvision()).isEqualByComparingTo("120.00");
        assertThat(oct.getNetRealisableCost()).isEqualByComparingTo("360.00");
        // 40 sold in the 30d window -> velocity 40/30, COGS = 40/30 * 30 * 8 = 320
        assertThat(oct.getTotalCogs30d()).isEqualByComparingTo("320.00");
        assertThat(oct.getTurnoverRate()).isCloseTo(320.0 / 480.0, within(1e-6));

        // Current month uses live time: 10 units, elapsed 82d -> R 0.82 MODERATE
        MonthlyReport nov = reports.generate(YearMonth.of(2026, 11), "TEST");
        assertThat(nov.getTotalCostValue()).isEqualByComparingTo("80.00");
        assertThat(nov.getTotalCostValue()).isEqualByComparingTo(analytics.portfolio().costValue());
        assertThat(nov.getTotalCogs30d()).isEqualByComparingTo("400.00");       // 50 sold on 3 Nov

        assertThat(reports.list()).extracting(MonthlyReport::getPeriod).containsExactly("2026-09", "2026-10", "2026-11");
        assertThat(reports.get("2026-10").getLines()).singleElement().satisfies(l -> {
            assertThat(l.getShopCode()).isEqualTo("SH-01");
            assertThat(l.getProvision()).isEqualByComparingTo("120.00");
        });
        assertThat(reports.csv("2026-10")).contains("2026-10,R-DEFAULT,SH-01").contains("480.00");
    }

    @Test
    void regenerationReplacesExistingReportAndFuturePeriodsRejected() {
        shop("SH-01", "100", "1000");
        reports.generate(YearMonth.of(2026, 10), "MANUAL");
        reports.generate(YearMonth.of(2026, 10), "MANUAL");
        assertThat(reportRepo.count()).isEqualTo(1);
        assertThatThrownBy(() -> reports.generate(YearMonth.of(2026, 12), "MANUAL"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("future");
    }

    @Test
    void schedulerGeneratesPreviousMonth() {
        shop("SH-01", "100", "1000");
        scheduler.generatePreviousMonth();
        assertThat(reportRepo.findByPeriod("2026-10")).get()
                .extracting(MonthlyReport::getTrigger).isEqualTo("SCHEDULED");
    }

    @Test
    void alertsFlagLowStockWithBudgetAwareReorderAndExpiry() {
        Shop shop = shop("SH-01", "100", "100.00");
        Sku milk = sku("MILK", "2.00", "3.50", "0.001", 10);
        velocityRepo.save(new SalesVelocity(shop, milk, new BigDecimal("10")));
        inventory.stockIn(new StockInRequest(shop.getId(), milk.getId(), 20L, null, TODAY.minusDays(9), null, null));

        var alerts = analytics.alerts();
        assertThat(alerts).anySatisfy(a -> {
            assertThat(a.type()).isEqualTo("EXPIRING");
            assertThat(a.severity()).isEqualTo("HIGH");
        });
        // 20 units / 10 per day = 2 days cover; target 210 units but budget only allows (100-40)/2 = 30 more
        assertThat(alerts).anySatisfy(a -> {
            assertThat(a.type()).isEqualTo("LOW_STOCK");
            assertThat(a.suggestedQty()).isEqualTo(30L);
        });
    }
}
