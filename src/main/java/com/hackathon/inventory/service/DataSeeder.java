package com.hackathon.inventory.service;

import com.hackathon.inventory.domain.*;
import com.hackathon.inventory.repository.*;
import com.hackathon.inventory.web.dto.StockInRequest;
import com.hackathon.inventory.web.dto.StockOutRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

/**
 * Seeds a realistic demo region (24 shops in 6 cities, 20 SKUs) and simulates ~7 months of receipts, sales and
 * write-offs through the real InventoryService (so every business rule is applied), then back-fills monthly reports
 * so the trend view has history. Deterministic (fixed random seed). Runs only on an empty database.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
@org.springframework.core.annotation.Order(1)
public class DataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final int HISTORY_DAYS = 215;

    private final SkuRepository skus;
    private final ShopRepository shops;
    private final SalesVelocityRepository velocities;
    private final InventoryService inventory;
    private final InventoryBatchRepository batches;
    private final ReportService reports;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final RegionRepository regions;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final String demoPassword;

    public DataSeeder(SkuRepository skus, ShopRepository shops, SalesVelocityRepository velocities,
                      InventoryService inventory, InventoryBatchRepository batches, ReportService reports,
                      TransactionTemplate tx, Clock clock, RegionRepository regions, AppUserRepository users,
                      PasswordEncoder encoder, @Value("${app.seed.demo-password:demo1234}") String demoPassword) {
        this.skus = skus; this.shops = shops; this.velocities = velocities; this.inventory = inventory;
        this.batches = batches; this.reports = reports; this.tx = tx; this.clock = clock; this.regions = regions;
        this.users = users; this.encoder = encoder; this.demoPassword = demoPassword;
    }

    private record SkuDef(String code, String name, String cat, String cost, String price, String vol, int life, double baseVel) {}

    private static final List<SkuDef> CATALOG = List.of(
            new SkuDef("MILK-1L", "Fresh Milk 1L", "Dairy", "1.80", "3.20", "0.00120", 14, 18),
            new SkuDef("YOG-500", "Greek Yogurt 500g", "Dairy", "2.40", "4.50", "0.00080", 21, 9),
            new SkuDef("BREAD-WH", "Wholemeal Bread", "Bakery", "1.20", "2.60", "0.00300", 5, 22),
            new SkuDef("EGG-10", "Eggs 10pc", "Fresh", "2.10", "3.90", "0.00250", 30, 14),
            new SkuDef("BANANA-1K", "Bananas 1kg", "Fresh", "1.30", "2.80", "0.00300", 7, 16),
            new SkuDef("RICE-5KG", "Jasmine Rice 5kg", "Pantry", "7.50", "12.90", "0.00600", 540, 6),
            new SkuDef("NOOD-5P", "Instant Noodles 5-pack", "Pantry", "2.20", "4.20", "0.00350", 270, 12),
            new SkuDef("OIL-2L", "Cooking Oil 2L", "Pantry", "4.80", "8.50", "0.00240", 365, 5),
            new SkuDef("COFF-3IN1", "3-in-1 Coffee 30s", "Beverage", "5.10", "9.20", "0.00280", 365, 6),
            new SkuDef("TEA-GRN", "Green Tea 1.5L", "Beverage", "1.10", "2.30", "0.00170", 270, 15),
            new SkuDef("SODA-24", "Soda 24-can Carton", "Beverage", "9.00", "15.90", "0.01100", 300, 4),
            new SkuDef("CHOC-BAR", "Chocolate Bar", "Confectionery", "0.90", "2.20", "0.00020", 240, 20),
            new SkuDef("CHIPS-L", "Potato Chips Large", "Snacks", "1.60", "3.50", "0.00400", 120, 11),
            new SkuDef("ICE-CRM", "Ice Cream 1L", "Frozen", "3.90", "7.90", "0.00130", 365, 5),
            new SkuDef("DUMP-FZ", "Frozen Dumplings", "Frozen", "4.20", "7.50", "0.00150", 180, 6),
            new SkuDef("SHAMP-400", "Shampoo 400ml", "Personal Care", "3.80", "7.90", "0.00060", 900, 3),
            new SkuDef("TOOTH-PST", "Toothpaste", "Personal Care", "1.50", "3.40", "0.00020", 730, 4),
            new SkuDef("DETER-3L", "Detergent 3L", "Household", "6.20", "11.50", "0.00350", 720, 3),
            new SkuDef("TISS-10", "Tissue 10-pack", "Household", "3.30", "5.90", "0.01800", 1095, 5),
            new SkuDef("VITC-60", "Vitamin C 60s", "Health", "6.50", "14.90", "0.00030", 730, 2));

    /** {city code, city, region code}. */
    private static final String[][] CITIES = {
            {"SG", "Singapore", "SEA-MARITIME"}, {"KL", "Kuala Lumpur", "SEA-MARITIME"},
            {"JK", "Jakarta", "SEA-MARITIME"}, {"MN", "Manila", "SEA-MARITIME"},
            {"BK", "Bangkok", "SEA-MAINLAND"}, {"HC", "Ho Chi Minh City", "SEA-MAINLAND"}};
    private static final String[] AREAS = {"Central", "North", "East", "West"};

    @Override
    public void run(ApplicationArguments args) {
        if (skus.count() > 0) {
            log.info("Seed skipped: database already has data");
            return;
        }
        long t0 = System.currentTimeMillis();
        Random rnd = new Random(42);
        List<Sku> skuList = new ArrayList<>();
        for (SkuDef d : CATALOG)
            skuList.add(skus.save(new Sku(d.code(), d.name(), d.cat(), new BigDecimal(d.cost()), new BigDecimal(d.price()),
                    new BigDecimal(d.vol()), d.life())));

        Map<String, Region> regionByCode = new HashMap<>();
        regionByCode.put("SEA-MARITIME", regions.save(new Region("SEA-MARITIME", "Maritime Southeast Asia", "Singapore")));
        regionByCode.put("SEA-MAINLAND", regions.save(new Region("SEA-MAINLAND", "Mainland Southeast Asia", "Bangkok")));

        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        LocalDate start = today.minusDays(HISTORY_DAYS);
        LocalDate lastSimulated = today.minusDays(1);   // never write future-dated ledger entries

        for (String[] city : CITIES) {
            for (int a = 0; a < AREAS.length; a++) {
                double scale = 0.6 + rnd.nextDouble() * 0.9;   // shop size
                List<Integer> idx = new ArrayList<>();
                for (int i = 0; i < skuList.size(); i++) idx.add(i);
                Collections.shuffle(idx, rnd);
                List<Integer> carried = idx.subList(0, 11 + rnd.nextInt(6));

                // size budget & capacity from the expected working stock; some shops deliberately tight
                double tight = rnd.nextDouble() < 0.25 ? 0.75 : 1.25;
                double expCost = 0, expVol = 0;
                Map<Integer, Double> vel = new HashMap<>();
                for (int i : carried) {
                    SkuDef d = CATALOG.get(i);
                    double v = Math.max(0.5, d.baseVel() * scale * (0.6 + rnd.nextDouble() * 0.8));
                    vel.put(i, Math.round(v * 10) / 10.0);
                    double cycle = interval(d.life()) + 4;
                    expCost += v * cycle * Double.parseDouble(d.cost());
                    expVol += v * cycle * Double.parseDouble(d.vol());
                }
                Shop shop = shops.save(new Shop(regionByCode.get(city[2]), city[0] + "-" + String.format("%02d", a + 1),
                        city[1] + " " + AREAS[a], city[1],
                        BigDecimal.valueOf(Math.max(1.0, expVol * tight * 1.3)).setScale(3, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(Math.ceil(expCost * tight * 1.6 / 100.0) * 100).setScale(2, RoundingMode.HALF_UP)));
                for (int i : carried)
                    velocities.save(new SalesVelocity(shop, skuList.get(i), BigDecimal.valueOf(vel.get(i))));

                for (int i : carried)
                    tx.executeWithoutResult(s -> simulate(shop, i, skuList, vel, start, lastSimulated, zone, rnd));
            }
        }
        log.info("Seeded {} shops, {} SKUs in {} ms", shops.count(), skus.count(), System.currentTimeMillis() - t0);
        seedUsers(regionByCode);

        YearMonth current = YearMonth.now(clock);
        for (int m = 6; m >= 1; m--) reports.generate(current.minusMonths(m), "BACKFILL");
        log.info("Back-filled 6 monthly reports");
    }

    /** One demo login per role. Passwords are bcrypt-hashed; the shared demo password is configurable. */
    private void seedUsers(Map<String, Region> regionByCode) {
        String hash = encoder.encode(demoPassword);
        Region maritime = regionByCode.get("SEA-MARITIME"), mainland = regionByCode.get("SEA-MAINLAND");
        Shop sg01 = shops.findAll().stream().filter(s -> s.getCode().equals("SG-01")).findFirst().orElseThrow();
        Shop bk01 = shops.findAll().stream().filter(s -> s.getCode().equals("BK-01")).findFirst().orElseThrow();
        users.save(new AppUser("hq.admin", hash, "Group HQ Admin", Role.HQ_ADMIN, null, null));
        users.save(new AppUser("rhq.maritime", hash, "Regional HQ Singapore", Role.REGION_MANAGER, maritime, null));
        users.save(new AppUser("rhq.mainland", hash, "Regional HQ Bangkok", Role.REGION_MANAGER, mainland, null));
        users.save(new AppUser("shop.sg01", hash, "Singapore Central Manager", Role.SHOP_MANAGER, maritime, sg01));
        users.save(new AppUser("shop.bk01", hash, "Bangkok Central Manager", Role.SHOP_MANAGER, mainland, bk01));
        log.info("Seeded demo users: hq.admin, rhq.maritime, rhq.mainland, shop.sg01, shop.bk01");
    }

    private static int interval(int shelfLife) { return Math.max(2, Math.min(14, shelfLife / 3)); }

    private void simulate(Shop shop, int i, List<Sku> skuList, Map<Integer, Double> vel,
                          LocalDate start, LocalDate end, ZoneId zone, Random rnd) {
        {
            Sku sku = skuList.get(i);
            double v = vel.get(i);
            int ri = interval(sku.getShelfLifeDays());
            int si = Math.min(ri, 7);
            int offset = rnd.nextInt(ri);
            boolean bulkDeal = sku.getShelfLifeDays() >= 120 && rnd.nextDouble() < 0.25;
            int bulkDay = 20 + rnd.nextInt(120);
            int seq = 0;
            for (int day = 0; start.plusDays(day).isBefore(end) || start.plusDays(day).isEqual(end); day++) {
                LocalDate date = start.plusDays(day);
                Instant morning = date.atTime(8, 0).atZone(zone).toInstant();
                Instant evening = date.atTime(20, 0).atZone(zone).toInstant();
                if (day % ri == offset % ri) {
                    if (rnd.nextDouble() < 0.75) writeOffExpired(shop, sku, date, morning);
                    long target = (long) Math.ceil(v * (ri + 4));
                    long sellable = sellable(shop, sku, date);
                    long qty = target - sellable;
                    if (qty > 0) {
                        int age = (int) (rnd.nextDouble() * sku.getShelfLifeDays() * 0.2);
                        receive(shop, sku, qty, date.minusDays(Math.min(age, Math.max(0, sku.getShelfLifeDays() / 5))),
                                "PO-" + shop.getCode() + "-" + sku.getCode() + "-" + (++seq), morning);
                    }
                }
                if (bulkDeal && day == bulkDay) {
                    long qty = (long) Math.ceil(v * (60 + rnd.nextInt(60)));
                    LocalDate prod = date.minusDays((long) (sku.getShelfLifeDays() * (0.30 + rnd.nextDouble() * 0.30)));
                    receive(shop, sku, qty, prod, "PROMO-" + shop.getCode() + "-" + sku.getCode(), morning);
                }
                if (day % si == si - 1) {
                    long demand = Math.round(v * si * (0.7 + rnd.nextDouble() * 0.6));
                    long sell = Math.min(demand, sellable(shop, sku, date));
                    if (sell > 0)
                        inventory.stockOut(new StockOutRequest(shop.getId(), sku.getId(), sell, StockOutReason.SALE,
                                null, "POS-" + date, null), evening);
                }
                if (rnd.nextDouble() < 0.004) {
                    long have = sellable(shop, sku, date);
                    if (have > 3)
                        inventory.stockOut(new StockOutRequest(shop.getId(), sku.getId(), 1L + rnd.nextInt(3),
                                StockOutReason.DAMAGE, null, "QA-" + date, "Damaged in store"), evening);
                }
            }
        }
    }

    private void receive(Shop shop, Sku sku, long qty, LocalDate prod, String ref, Instant at) {
        long allowed = Math.min(qty, inventory.maxReceivableQty(shop.getId(), sku.getId()));
        if (allowed > 0)
            inventory.stockIn(new StockInRequest(shop.getId(), sku.getId(), allowed, null, prod, ref,
                    allowed < qty ? "Partial receipt: shop limit reached" : null), at);
    }

    private long sellable(Shop shop, Sku sku, LocalDate date) {
        return batches.lockAvailableFefo(shop.getId(), sku.getId()).stream()
                .filter(b -> date.toEpochDay() - b.getBatchProductionDate().toEpochDay() <= sku.getShelfLifeDays())
                .mapToLong(InventoryBatch::getOnHandQty).sum();
    }

    private void writeOffExpired(Shop shop, Sku sku, LocalDate date, Instant at) {
        for (InventoryBatch b : batches.lockAvailableFefo(shop.getId(), sku.getId())) {
            if (date.toEpochDay() - b.getBatchProductionDate().toEpochDay() > sku.getShelfLifeDays())
                inventory.stockOut(new StockOutRequest(shop.getId(), sku.getId(), b.getOnHandQty(),
                        StockOutReason.EXPIRED_WRITE_OFF, b.getId(), "WO-" + date, "Expired stock disposal"), at);
        }
    }
}
