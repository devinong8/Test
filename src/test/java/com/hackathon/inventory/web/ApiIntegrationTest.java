package com.hackathon.inventory.web;

import com.hackathon.inventory.TestSupport;
import com.hackathon.inventory.domain.Shop;
import com.hackathon.inventory.domain.Sku;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ApiIntegrationTest extends TestSupport {
    @Autowired MockMvc mvc;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;

    @Test
    void skuCrudWithValidationAndDuplicateCheck() throws Exception {
        String body = """
                {"code":"tea-1","name":"Green Tea","category":"Beverage","purchasePrice":1.10,"salePrice":2.30,
                 "unitVolumeM3":0.0017,"shelfLifeDays":270}""";
        mvc.perform(post("/api/skus").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("TEA-1"));
        mvc.perform(post("/api/skus").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DUPLICATE_CODE"));
        mvc.perform(post("/api/skus").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"\",\"purchasePrice\":-1,\"shelfLifeDays\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.code").exists())
                .andExpect(jsonPath("$.errors.purchasePrice").exists())
                .andExpect(jsonPath("$.errors.shelfLifeDays").exists());
        Long id = skuRepo.findAll().get(0).getId();
        mvc.perform(put("/api/skus/" + id).contentType(MediaType.APPLICATION_JSON).content(body.replace("Green Tea", "Jasmine Tea")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Jasmine Tea"));
        mvc.perform(delete("/api/skus/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/skus/999999")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void shopCrudAndLimitsCannotDropBelowUsage() throws Exception {
        mvc.perform(post("/api/shops").contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"sg-09","name":"Orchard","city":"Singapore","storageCapacityM3":5,"capitalBudget":1000}"""))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("SG-09"))
                .andExpect(jsonPath("$.storageUtilisation").value(0.0));
        Shop shop = shopRepo.findAll().get(0);
        Sku sku = sku("RICE", "10.00", "15.00", "0.01", 365);
        mvc.perform(post("/api/inventory/stock-in").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"shopId\":%d,\"skuId\":%d,\"quantity\":50,\"batchProductionDate\":\"2026-11-01\"}".formatted(shop.getId(), sku.getId())))
                .andExpect(status().isOk());
        mvc.perform(put("/api/shops/" + shop.getId()).contentType(MediaType.APPLICATION_JSON).content("""
                {"code":"SG-09","name":"Orchard","city":"Singapore","storageCapacityM3":5,"capitalBudget":100}"""))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BUDGET_BELOW_USAGE"));
        mvc.perform(delete("/api/shops/" + shop.getId()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SHOP_HAS_STOCK"));
        mvc.perform(get("/api/shops/" + shop.getId()))
                .andExpect(jsonPath("$.costValue").value(500.0)).andExpect(jsonPath("$.budgetUtilisation").value(0.5));
    }

    @Test
    void stockMovementsLedgerAnalyticsAndObservability() throws Exception {
        Shop shop = shop("SG-01", "10", "10000");
        Sku milk = sku("MILK", "2.00", "3.50", "0.001", 14);
        mvc.perform(put("/api/shops/%d/velocities/%d".formatted(shop.getId(), milk.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dailySalesVelocity\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].dailySalesVelocity").value(5));

        mvc.perform(post("/api/inventory/stock-in").header("X-Correlation-Id", "trace-123")
                        .contentType(MediaType.APPLICATION_JSON).content(
                        "{\"shopId\":%d,\"skuId\":%d,\"quantity\":100,\"batchProductionDate\":\"2026-11-20\",\"reference\":\"PO-9\"}"
                                .formatted(shop.getId(), milk.getId())))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "trace-123"))
                .andExpect(jsonPath("$.onHandAfter").value(100))
                .andExpect(jsonPath("$.transactions[0].correlationId").value("trace-123"));

        mvc.perform(post("/api/inventory/stock-out").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"shopId\":%d,\"skuId\":%d,\"quantity\":500,\"reason\":\"SALE\"}".formatted(shop.getId(), milk.getId())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.details.available").value(100))
                .andExpect(jsonPath("$.correlationId").exists());
        mvc.perform(post("/api/inventory/stock-out").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"shopId\":%d,\"skuId\":%d,\"quantity\":30,\"reason\":\"SALE\"}".formatted(shop.getId(), milk.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.onHandAfter").value(70));
        mvc.perform(post("/api/inventory/stock-out").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"shopId\":%d,\"skuId\":%d,\"quantity\":1,\"reason\":\"STOLEN\"}".formatted(shop.getId(), milk.getId())))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/inventory/transactions").param("shopId", shop.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].type").value("STOCK_OUT"));
        mvc.perform(get("/api/inventory/transactions").param("type", "STOCK_IN"))
                .andExpect(jsonPath("$.totalElements").value(1));

        // 70 units x 2.00 = 140 cost; 70 x 3.50 = 245 retail; COGS = 5*30*2 = 300; turnover = 300/140
        mvc.perform(get("/api/analytics/portfolio"))
                .andExpect(jsonPath("$.costValue").value(140.0))
                .andExpect(jsonPath("$.retailValue").value(245.0))
                .andExpect(jsonPath("$.cogs30d").value(300.0))
                .andExpect(jsonPath("$.turnoverRate").value(closeTo(300.0 / 140.0, 1e-6)))
                .andExpect(jsonPath("$.batches", hasSize(0)));
        mvc.perform(get("/api/inventory/batches"))
                .andExpect(jsonPath("$[0].tier").value("NORMAL"))
                .andExpect(jsonPath("$[0].elapsedDays").value(2));
        mvc.perform(get("/api/inventory/headroom").param("shopId", shop.getId().toString()).param("skuId", milk.getId().toString()))
                .andExpect(jsonPath("$.maxReceivableQty").value(4930));   // budget (10000-140)/2 = 4930 < volume 9930
        mvc.perform(get("/api/analytics/alerts")).andExpect(status().isOk());

        mvc.perform(post("/api/reports/generate").param("period", "2026-11"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trigger").value("MANUAL"));
        mvc.perform(get("/api/reports/2026-11")).andExpect(jsonPath("$.lines[0].shopCode").value("SG-01"));
        mvc.perform(get("/api/reports/2026-11/csv"))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(containsString("2026-11,SG-01")));
        mvc.perform(get("/api/reports/not-a-month")).andExpect(status().isBadRequest());

        mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.status").value("UP"));
        org.assertj.core.api.Assertions.assertThat(meters.counter("inventory.movements.units", "type", "STOCK_IN",
                "reason", "RECEIPT").count()).isGreaterThanOrEqualTo(100.0);
    }
}
