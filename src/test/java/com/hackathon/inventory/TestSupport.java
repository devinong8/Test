package com.hackathon.inventory;

import com.hackathon.inventory.domain.Region;
import com.hackathon.inventory.domain.Role;
import com.hackathon.inventory.domain.Shop;
import com.hackathon.inventory.security.AppUserPrincipal;
import com.hackathon.inventory.domain.Sku;
import com.hackathon.inventory.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.time.*;

/** Base class for integration tests: real Spring context, in-memory H2, clock pinned to 2026-11-22 10:00 SGT. */
@SpringBootTest
@Import(TestSupport.FixedClock.class)
public abstract class TestSupport {
    public static final ZoneId SGT = ZoneId.of("Asia/Singapore");
    public static final LocalDate TODAY = LocalDate.of(2026, 11, 22);

    @TestConfiguration
    public static class FixedClock {
        @Bean @Primary
        public Clock fixedClock() { return Clock.fixed(TODAY.atTime(10, 0).atZone(SGT).toInstant(), SGT); }
    }

    @Autowired protected SkuRepository skuRepo;
    @Autowired protected ShopRepository shopRepo;
    @Autowired protected SalesVelocityRepository velocityRepo;
    @Autowired protected InventoryBatchRepository batchRepo;
    @Autowired protected StockTransactionRepository txnRepo;
    @Autowired protected MonthlyReportRepository reportRepo;
    @Autowired protected RegionRepository regionRepo;
    @Autowired protected AppUserRepository userRepo;

    @BeforeEach
    void cleanDatabase() {
        reportRepo.deleteAll();
        txnRepo.deleteAll();
        batchRepo.deleteAll();
        velocityRepo.deleteAll();
        skuRepo.deleteAll();
        userRepo.deleteAll();
        shopRepo.deleteAll();
        regionRepo.deleteAll();
    }

    protected Region region(String code) {
        return regionRepo.findAll().stream().filter(r -> r.getCode().equals(code)).findFirst()
                .orElseGet(() -> regionRepo.save(new Region(code, code + " region", "Singapore")));
    }

    protected Sku sku(String code, String cost, String price, String vol, int life) {
        return skuRepo.save(new Sku(code, code + " name", "Test", new BigDecimal(cost), new BigDecimal(price),
                new BigDecimal(vol), life));
    }

    protected Shop shop(String code, String capacityM3, String budget) {
        return shop(region("R-DEFAULT"), code, capacityM3, budget);
    }

    protected Shop shop(Region region, String code, String capacityM3, String budget) {
        return shopRepo.save(new Shop(region, code, code + " shop", "Singapore", new BigDecimal(capacityM3), new BigDecimal(budget)));
    }

    public static AppUserPrincipal hq() {
        return new AppUserPrincipal(1L, "hq.admin", "x", "HQ", Role.HQ_ADMIN, null, null, null, null, true);
    }

    public static AppUserPrincipal regionManager(Region r) {
        return new AppUserPrincipal(2L, "rhq", "x", "RHQ", Role.REGION_MANAGER, r.getId(), r.getCode(), null, null, true);
    }

    public static AppUserPrincipal shopManager(Shop s) {
        return new AppUserPrincipal(3L, "shop", "x", "Shop", Role.SHOP_MANAGER, s.getRegion().getId(),
                s.getRegion().getCode(), s.getId(), s.getCode(), true);
    }

    protected static Instant at(LocalDate d) { return d.atTime(12, 0).atZone(SGT).toInstant(); }
}
