package com.hackathon.inventory.service;

import com.hackathon.inventory.TestSupport;
import com.hackathon.inventory.domain.*;
import com.hackathon.inventory.web.dto.FestivalPlan;
import com.hackathon.inventory.web.dto.StockInRequest;
import com.hackathon.inventory.web.dto.StockOutRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class FestivalPlannerIntegrationTest extends TestSupport {
    @Autowired FestivalPlanner planner;
    @Autowired InventoryService inventory;
    @Autowired WebApplicationContext ctx;

    Region north, east;
    Shop bj, sh, tight;
    Sku dumplings, tangyuan, apple, mooncake;

    @BeforeEach
    void setUp() {
        north = regionRepo.save(new Region("CN-NORTH", "华北 North China", "北京 Beijing"));
        east = regionRepo.save(new Region("CN-EAST", "华东 East China", "上海 Shanghai"));
        bj = shop(north, "BJ-01", "100", "100000");
        sh = shop(east, "SH-01", "100", "100000");
        tight = shop(east, "SH-02", "100", "50.00");                    // almost no budget
        dumplings = sku("DUMP-1KG", "16.00", "25.90", "0.0018", 270);
        tangyuan = sku("TANGYUAN", "8.50", "14.90", "0.0010", 270);
        apple = sku("XMAS-APPLE", "8.00", "19.90", "0.0020", 21);
        mooncake = sku("MOON-GIFT", "88.00", "168.00", "0.008", 60);
        for (Shop s : new Shop[]{bj, sh, tight}) {
            velocityRepo.save(new SalesVelocity(s, dumplings, new BigDecimal("4")));
            velocityRepo.save(new SalesVelocity(s, tangyuan, new BigDecimal("2")));
            velocityRepo.save(new SalesVelocity(s, apple, BigDecimal.ZERO));
            velocityRepo.save(new SalesVelocity(s, mooncake, BigDecimal.ZERO));
        }
        // last Mid-Autumn in East China: 100 mooncakes received, 75 sold -> 75% sell-through
        inventory.stockIn(new StockInRequest(sh.getId(), mooncake.getId(), 100L, null, LocalDate.of(2026, 9, 8), null, null),
                at(LocalDate.of(2026, 9, 10)));
        inventory.stockOut(new StockOutRequest(sh.getId(), mooncake.getId(), 75L, StockOutReason.SALE, null, null, null),
                at(LocalDate.of(2026, 9, 20)));
    }

    FestivalPlan.Line line(FestivalPlan plan, String event, String shop, String sku) {
        return plan.events().stream().filter(e -> e.code().equals(event)).findFirst().orElseThrow().lines().stream()
                .filter(l -> l.shopCode().equals(shop) && l.skuCode().equals(sku)).findFirst().orElse(null);
    }

    @Test
    void dongzhiNorthPreStocksDumplingsSouthTangyuan() {
        FestivalPlan plan = planner.plan(40, null);
        assertThat(plan.events()).extracting(FestivalPlan.Event::code)
                .containsExactly("DOUBLE12-2026", "DONGZHI-2026", "XMAS-EVE-2026");   // 春节 is 76 days out
        // north: 4/day x (3.5 - 1) x 2 days = 20 extra dumplings; tangyuan only x1.3 -> ceil(2 x 0.3 x 2) = 2
        assertThat(line(plan, "DONGZHI-2026", "BJ-01", "DUMP-1KG").recommendedQty()).isEqualTo(20);
        assertThat(line(plan, "DONGZHI-2026", "BJ-01", "TANGYUAN").recommendedQty()).isEqualTo(2);
        // east: tangyuan 2 x 2.5 x 2 = 10, dumplings only x1.5 -> 4 x 0.5 x 2 = 4
        assertThat(line(plan, "DONGZHI-2026", "SH-01", "TANGYUAN").recommendedQty()).isEqualTo(10);
        assertThat(line(plan, "DONGZHI-2026", "SH-01", "DUMP-1KG").recommendedQty()).isEqualTo(4);
        assertThat(line(plan, "DONGZHI-2026", "SH-01", "TANGYUAN").orderFrom()).isEqualTo(TODAY);
    }

    @Test
    void christmasApplesAreSizedByTheMooncakeLessonAndOrderWindow() {
        FestivalPlan plan = planner.plan(40, null);
        // all shops have equal traffic -> 25/day x 3 days = 75 forecast
        var bjApples = line(plan, "XMAS-EVE-2026", "BJ-01", "XMAS-APPLE");
        var shApples = line(plan, "XMAS-EVE-2026", "SH-01", "XMAS-APPLE");
        assertThat(bjApples.expectedUnits()).isEqualTo(75);
        assertThat(bjApples.recommendedQty()).isEqualTo(75);              // no mooncake history in the north
        assertThat(shApples.recommendedQty()).isEqualTo(57);              // ceil(75 x 0.75): east sold only 75%
        assertThat(shApples.note()).contains("75%");
        assertThat(shApples.orderFrom()).isEqualTo(LocalDate.of(2026, 12, 12));
        assertThat(shApples.orderBy()).isEqualTo(LocalDate.of(2026, 12, 20));

        assertThat(plan.lessons()).singleElement().satisfies(l -> {
            assertThat(l.regionCode()).isEqualTo("CN-EAST");
            assertThat(l.received()).isEqualTo(100);
            assertThat(l.sold()).isEqualTo(75);
            assertThat(l.stillOnHand()).isEqualTo(25);
            assertThat(l.unsoldCost()).isEqualByComparingTo("2200.00");  // 25 x ¥88
        });
    }

    @Test
    void recommendationsAreCappedByBudgetHeadroom() {
        var tightApples = line(planner.plan(40, null), "XMAS-EVE-2026", "SH-02", "XMAS-APPLE");
        assertThat(tightApples.recommendedQty()).isEqualTo(6);           // ¥50 budget / ¥8 = 6
        assertThat(tightApples.cappedBy()).isEqualTo("BUDGET");
    }

    @Test
    void planIsScopedToTheCaller() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        mvc.perform(get("/api/analytics/festival-plan").param("horizonDays", "40").with(user(shopManager(bj))))
                .andExpect(jsonPath("$.events[*].lines[*].shopCode", everyItem(is("BJ-01"))))
                .andExpect(jsonPath("$.lessons", hasSize(0)));            // no mooncake history at BJ-01
        mvc.perform(get("/api/analytics/festival-plan").param("horizonDays", "40").with(user(regionManager(east))))
                .andExpect(jsonPath("$.events[*].lines[*].regionCode", everyItem(is("CN-EAST"))))
                .andExpect(jsonPath("$.lessons[0].regionCode").value("CN-EAST"));
        assertThat(planner.plan(400, Set.of(sh.getId())).events()).extracting(FestivalPlan.Event::code)
                .contains("SPRING-2027");
    }
}
