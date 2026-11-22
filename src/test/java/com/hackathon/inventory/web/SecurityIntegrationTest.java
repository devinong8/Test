package com.hackathon.inventory.web;

import com.hackathon.inventory.TestSupport;
import com.hackathon.inventory.domain.*;
import com.hackathon.inventory.service.InventoryService;
import com.hackathon.inventory.service.ReportService;
import com.hackathon.inventory.web.dto.StockInRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Group HQ sees everything; a regional HQ sees only its region; a shop manager sees only its shop.
 * Region RA = shops A1, A2. Region RB = shop B1.
 */
class SecurityIntegrationTest extends TestSupport {
    @Autowired WebApplicationContext ctx;
    @Autowired InventoryService inventory;
    @Autowired ReportService reports;
    @Autowired PasswordEncoder encoder;
    MockMvc mvc;

    Region ra, rb;
    Shop a1, a2, b1;
    Sku sku;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
        ra = regionRepo.save(new Region("RA", "Region A", "Singapore"));
        rb = regionRepo.save(new Region("RB", "Region B", "Bangkok"));
        a1 = shop(ra, "A1", "100", "100000");
        a2 = shop(ra, "A2", "100", "100000");
        b1 = shop(rb, "B1", "100", "100000");
        sku = sku("RICE", "10.00", "15.00", "0.01", 365);
        for (Shop s : new Shop[]{a1, a2, b1}) {
            velocityRepo.save(new SalesVelocity(s, sku, new BigDecimal("1")));
            inventory.stockIn(new StockInRequest(s.getId(), sku.getId(), 10L, null, TODAY.minusDays(10), null, null));
        }
        userRepo.save(new AppUser("hq.admin", encoder.encode("s3cret!"), "HQ", Role.HQ_ADMIN, null, null));
    }

    String stockIn(Shop s) {
        return "{\"shopId\":%d,\"skuId\":%d,\"quantity\":1,\"batchProductionDate\":\"2026-11-20\"}".formatted(s.getId(), sku.getId());
    }

    @Test
    void anonymousCanLoadTheAppShellButNotTheApi() throws Exception {
        mvc.perform(get("/api/shops")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/index.html")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"hq.admin\",\"password\":\"s3cret!\"}")).andExpect(status().isForbidden());
        // the stored password is a bcrypt hash, never plain text
        assertThat(userRepo.findByUsernameIgnoreCase("hq.admin").orElseThrow().getPasswordHash()).startsWith("{bcrypt}");
    }

    @Test
    void groupHqSeesEveryRegionWithSubtotals() throws Exception {
        mvc.perform(get("/api/analytics/portfolio").with(user(hq())))
                .andExpect(jsonPath("$.shops", hasSize(3)))
                .andExpect(jsonPath("$.costValue").value(300.0))
                .andExpect(jsonPath("$.regions", hasSize(2)))
                .andExpect(jsonPath("$.regions[0].regionCode").value("RA"))
                .andExpect(jsonPath("$.regions[0].costValue").value(200.0))
                .andExpect(jsonPath("$.regions[1].costValue").value(100.0));
        mvc.perform(get("/api/analytics/portfolio").param("regionId", rb.getId().toString()).with(user(hq())))
                .andExpect(jsonPath("$.shops", hasSize(1))).andExpect(jsonPath("$.costValue").value(100.0));
        mvc.perform(post("/api/regions").with(user(hq())).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"anz\",\"name\":\"Australia & NZ\",\"hqCity\":\"Sydney\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("ANZ"));
        mvc.perform(delete("/api/regions/" + ra.getId()).with(user(hq())).with(csrf()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("REGION_HAS_SHOPS"));
    }

    @Test
    void regionalHqIsConfinedToItsRegion() throws Exception {
        var rm = user(regionManager(ra));
        mvc.perform(get("/api/regions").with(rm)).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/shops").with(rm)).andExpect(jsonPath("$[*].code", containsInAnyOrder("A1", "A2")));
        mvc.perform(get("/api/shops/" + b1.getId()).with(rm)).andExpect(status().isForbidden());
        mvc.perform(get("/api/analytics/portfolio").with(rm))
                .andExpect(jsonPath("$.shops", hasSize(2))).andExpect(jsonPath("$.costValue").value(200.0));
        mvc.perform(get("/api/analytics/portfolio").param("regionId", rb.getId().toString()).with(rm))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/inventory/transactions").with(rm)).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/inventory/batches").with(rm)).andExpect(jsonPath("$", hasSize(2)));

        // can open a shop in its own region, not in someone else's
        String shop = "{\"regionId\":%d,\"code\":\"%s\",\"name\":\"n\",\"city\":\"c\",\"storageCapacityM3\":5,\"capitalBudget\":100}";
        mvc.perform(post("/api/shops").with(rm).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(shop.formatted(ra.getId(), "A3"))).andExpect(status().isCreated());
        mvc.perform(post("/api/shops").with(rm).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(shop.formatted(rb.getId(), "B9"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory/stock-in").with(rm).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(stockIn(b1))).andExpect(status().isForbidden());

        // HQ-only actions
        mvc.perform(post("/api/skus").with(rm).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"X","name":"x","purchasePrice":1,"salePrice":2,"unitVolumeM3":0.001,"shelfLifeDays":10}"""))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/reports/generate").param("period", "2026-11").with(rm).with(csrf()))
                .andExpect(status().isForbidden());

        // report totals are re-aggregated to the region only
        reports.generate(YearMonth.of(2026, 11), "TEST");
        mvc.perform(get("/api/reports/2026-11").with(rm))
                .andExpect(jsonPath("$.lines[*].shopCode", containsInAnyOrder("A1", "A2", "A3")))
                .andExpect(jsonPath("$.report.totalCostValue").value(200.0))
                .andExpect(jsonPath("$.report.regions", hasSize(1)));
        mvc.perform(get("/api/reports/2026-11/csv").with(rm))
                .andExpect(content().string(not(containsString("B1"))));
    }

    @Test
    void shopManagerSeesAndMovesOnlyItsOwnShop() throws Exception {
        var sm = user(shopManager(a1));
        mvc.perform(get("/api/shops").with(sm)).andExpect(jsonPath("$[*].code", contains("A1")));
        mvc.perform(get("/api/analytics/portfolio").with(sm)).andExpect(jsonPath("$.costValue").value(100.0));
        mvc.perform(get("/api/analytics/alerts").with(sm)).andExpect(jsonPath("$[*].shopCode", everyItem(is("A1"))));
        mvc.perform(get("/api/inventory/transactions").param("shopId", a2.getId().toString()).with(sm))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/inventory/transactions").with(sm)).andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(post("/api/inventory/stock-in").with(sm).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(stockIn(a1))).andExpect(status().isOk());
        mvc.perform(post("/api/inventory/stock-in").with(sm).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(stockIn(a2))).andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory/stock-in").with(sm).contentType(MediaType.APPLICATION_JSON)
                .content(stockIn(a1))).andExpect(status().isForbidden());   // missing CSRF token

        mvc.perform(put("/api/shops/%d/velocities/%d".formatted(a1.getId(), sku.getId())).with(sm).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"dailySalesVelocity\":9}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/reports").with(sm)).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").with(sm)).andExpect(status().isForbidden());
    }
}
