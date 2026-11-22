package com.hackathon.inventory.analytics;

import com.hackathon.inventory.domain.RiskTier;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FestivalCalendarTest {
    static final LocalDate TODAY = LocalDate.of(2026, 11, 22);

    static FestivalCalendar.Festival event(String code) {
        return FestivalCalendar.EVENTS.stream().filter(e -> e.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void calendarDatesAreCorrectForChina() {
        assertThat(event("DONGZHI-2026").date()).isEqualTo(LocalDate.of(2026, 12, 22));   // solstice 04:50 Beijing time
        assertThat(event("XMAS-EVE-2026").windowStart()).isEqualTo(LocalDate.of(2026, 12, 22));
        assertThat(event("SPRING-2027").date()).isEqualTo(LocalDate.of(2027, 2, 6));
    }

    @Test
    void christmasApplesMustArriveLateEnoughToStayNormalTier() {
        var xmas = event("XMAS-EVE-2026");
        LocalDate from = FestivalCalendar.orderFrom(xmas, 21, TODAY);
        assertThat(from).isEqualTo(LocalDate.of(2026, 12, 12));          // 24 Dec - floor(0.6 x 21) = 12 days
        assertThat(FestivalCalendar.orderBy(xmas)).isEqualTo(LocalDate.of(2026, 12, 20));
        // produced on arrival, on the last festival day the batch is still NORMAL (0% provision)
        double rDep = InventoryFormulas.shelfLifeConsumptionRatio(
                InventoryFormulas.elapsedDays(xmas.windowEnd(), from), 21);
        assertThat(RiskTier.classify(rDep)).isEqualTo(RiskTier.NORMAL);
        double oneDayEarlier = InventoryFormulas.shelfLifeConsumptionRatio(
                InventoryFormulas.elapsedDays(xmas.windowEnd(), from.minusDays(1)), 21);
        assertThat(RiskTier.classify(oneDayEarlier)).isEqualTo(RiskTier.MODERATE);
    }

    @Test
    void longLifeGoodsCanBeOrderedNowAndShortLifeWindowsCollapseToTheLatestDay() {
        var dongzhi = event("DONGZHI-2026");
        assertThat(FestivalCalendar.orderFrom(dongzhi, 270, TODAY)).isEqualTo(TODAY);     // frozen tangyuan
        assertThat(FestivalCalendar.orderFrom(dongzhi, 3, TODAY)).isEqualTo(FestivalCalendar.orderBy(dongzhi));
    }

    @Test
    void dongzhiIsRegional() {
        var dongzhi = event("DONGZHI-2026");
        var northDumplings = dongzhi.uplifts().stream()
                .filter(u -> u.skuCode().equals("DUMP-1KG") && u.appliesTo("CN-NORTH")).findFirst().orElseThrow();
        var southTangyuan = dongzhi.uplifts().stream()
                .filter(u -> u.skuCode().equals("TANGYUAN") && u.appliesTo("CN-SOUTH")).findFirst().orElseThrow();
        assertThat(northDumplings.factor()).isGreaterThan(3);
        assertThat(southTangyuan.factor()).isGreaterThan(3);
    }
}
