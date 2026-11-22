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

    /**
     * A China convenience-store / community-supermarket assortment, priced in RMB (CNY) at typical 2026 tier-1 city
     * shelf prices. Products are generic (no brands). Shelf lives follow common Chinese labelling.
     */
    private static final List<SkuDef> CATALOG = List.of(
            new SkuDef("MILK-950", "鲜牛奶 Fresh Milk 950ml", "乳品 Dairy", "9.50", "14.90", "0.00110", 7, 16),
            new SkuDef("YOG-AMB12", "常温酸奶 Ambient Yogurt 200g×12", "乳品 Dairy", "38.00", "59.90", "0.00350", 150, 4),
            new SkuDef("BAOZI-6", "鲜肉包 Pork Buns ×6", "鲜食 Fresh Food", "6.00", "12.00", "0.00150", 3, 18),
            new SkuDef("TOFU-400", "嫩豆腐 Silken Tofu 400g", "生鲜 Fresh", "2.20", "4.50", "0.00060", 7, 14),
            new SkuDef("EGG-30", "鲜鸡蛋 Fresh Eggs ×30", "生鲜 Fresh", "18.00", "26.90", "0.00450", 30, 7),
            new SkuDef("RICE-NE5", "东北大米 Northeast Rice 5kg", "粮油 Grain & Oil", "28.00", "45.90", "0.00600", 365, 5),
            new SkuDef("OIL-PNT5", "花生油 Peanut Oil 5L", "粮油 Grain & Oil", "85.00", "129.00", "0.00600", 540, 2),
            new SkuDef("NOOD-5P", "方便面 Instant Noodles ×5", "方便食品 Instant Food", "9.00", "14.50", "0.00400", 180, 12),
            new SkuDef("SOY-500", "生抽 Light Soy Sauce 500ml", "调味品 Condiments", "7.50", "12.90", "0.00060", 720, 4),
            new SkuDef("CHILI-280", "辣椒油 Chili Crisp 280g", "调味品 Condiments", "6.80", "11.50", "0.00040", 365, 6),
            new SkuDef("TEA-OOL", "无糖乌龙茶 Sugar-free Oolong 500ml", "饮料 Beverages", "2.40", "4.50", "0.00060", 365, 24),
            new SkuDef("WATER-24", "饮用水 Water 550ml×24", "饮料 Beverages", "18.00", "29.90", "0.01500", 540, 5),
            new SkuDef("BAIJIU-500", "白酒 Baijiu 52% 500ml", "酒类 Liquor", "168.00", "268.00", "0.00120", 3650, 1),
            new SkuDef("DUMP-1KG", "速冻水饺 Frozen Dumplings 1kg", "冷冻 Frozen", "16.00", "25.90", "0.00180", 270, 7),
            new SkuDef("TANGYUAN", "汤圆 Glutinous Rice Balls 500g", "冷冻 Frozen", "8.50", "14.90", "0.00100", 270, 3),
            new SkuDef("SEEDS-500", "瓜子 Roasted Sunflower Seeds 500g", "休闲零食 Snacks", "9.00", "16.80", "0.00200", 240, 8),
            new SkuDef("MOON-GIFT", "月饼礼盒 Mooncake Gift Box", "节令 Seasonal", "88.00", "168.00", "0.00800", 60, 6),
            new SkuDef("TISSUE-10", "抽纸 Facial Tissue ×10", "家清 Household", "15.00", "24.90", "0.01200", 1095, 5),
            new SkuDef("LAUND-2KG", "洗衣液 Laundry Liquid 2kg", "家清 Household", "22.00", "35.90", "0.00250", 730, 3),
            new SkuDef("TOOTH-120", "牙膏 Toothpaste 120g", "个护 Personal Care", "8.00", "14.90", "0.00020", 730, 4));

    private static final String MOONCAKE = "MOON-GIFT";
    /** Mid-Autumn Festival dates (15th day of the 8th lunar month). */
    private static final List<LocalDate> MID_AUTUMN = List.of(LocalDate.of(2025, 10, 6), LocalDate.of(2026, 9, 25),
            LocalDate.of(2027, 9, 15), LocalDate.of(2028, 10, 3), LocalDate.of(2029, 9, 22));

    /** {region code, region name, regional HQ city}. */
    private static final String[][] REGIONS = {
            {"CN-EAST", "华东 East China", "上海 Shanghai"},
            {"CN-SOUTH", "华南 South China", "广州 Guangzhou"},
            {"CN-NORTH", "华北 North China", "北京 Beijing"},
            {"CN-SW", "西南 Southwest China", "成都 Chengdu"}};

    /** {shop-code prefix, city, region code, three districts}. 11 cities x 3 shops = 33 shops. */
    private static final String[][] CITIES = {
            {"SH", "上海 Shanghai", "CN-EAST", "静安 Jing'an", "浦东 Pudong", "徐汇 Xuhui"},
            {"HZ", "杭州 Hangzhou", "CN-EAST", "西湖 Xihu", "滨江 Binjiang", "拱墅 Gongshu"},
            {"NJ", "南京 Nanjing", "CN-EAST", "新街口 Xinjiekou", "河西 Hexi", "江宁 Jiangning"},
            {"SU", "苏州 Suzhou", "CN-EAST", "姑苏 Gusu", "工业园区 SIP", "吴中 Wuzhong"},
            {"GZ", "广州 Guangzhou", "CN-SOUTH", "天河 Tianhe", "越秀 Yuexiu", "番禺 Panyu"},
            {"SZ", "深圳 Shenzhen", "CN-SOUTH", "福田 Futian", "南山 Nanshan", "龙岗 Longgang"},
            {"DG", "东莞 Dongguan", "CN-SOUTH", "南城 Nancheng", "松山湖 Songshan Lake", "长安 Chang'an"},
            {"BJ", "北京 Beijing", "CN-NORTH", "朝阳 Chaoyang", "海淀 Haidian", "西城 Xicheng"},
            {"TJ", "天津 Tianjin", "CN-NORTH", "和平 Heping", "滨海 Binhai", "南开 Nankai"},
            {"CD", "成都 Chengdu", "CN-SW", "锦江 Jinjiang", "高新 Hi-tech Zone", "武侯 Wuhou"},
            {"CQ", "重庆 Chongqing", "CN-SW", "渝中 Yuzhong", "江北 Jiangbei", "沙坪坝 Shapingba"}};

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
        for (String[] r : REGIONS) regionByCode.put(r[0], regions.save(new Region(r[0], r[1], r[2])));

        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.now(clock);
        LocalDate start = today.minusDays(HISTORY_DAYS);
        LocalDate lastSimulated = today.minusDays(1);   // never write future-dated ledger entries

        int mooncakeIdx = indexOf(MOONCAKE);
        for (String[] city : CITIES) {
            for (int a = 0; a < 3; a++) {
                double scale = 0.6 + rnd.nextDouble() * 0.9;   // shop size
                List<Integer> idx = new ArrayList<>();
                for (int i = 0; i < skuList.size(); i++) if (i != mooncakeIdx) idx.add(i);
                Collections.shuffle(idx, rnd);
                List<Integer> carried = new ArrayList<>(idx.subList(0, 11 + rnd.nextInt(6)));
                carried.add(mooncakeIdx);   // every Chinese store sells mooncakes before Mid-Autumn

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
                String district = city[3 + a];
                Shop shop = shops.save(new Shop(regionByCode.get(city[2]), city[0] + "-" + String.format("%02d", a + 1),
                        district.split(" ", 2)[0] + "店 " + district.split(" ", 2)[1], city[1],
                        BigDecimal.valueOf(Math.max(1.0, expVol * tight * 1.3)).setScale(3, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(Math.ceil(expCost * tight * 1.6 / 100.0) * 100).setScale(2, RoundingMode.HALF_UP)));
                for (int i : carried)   // mooncakes: season is over, so the current sales rate is zero
                    velocities.save(new SalesVelocity(shop, skuList.get(i),
                            i == mooncakeIdx ? BigDecimal.ZERO : BigDecimal.valueOf(vel.get(i))));

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
        Map<String, Shop> byCode = new HashMap<>();
        shops.findAll().forEach(sh -> byCode.put(sh.getCode(), sh));
        users.save(new AppUser("hq.admin", hash, "集团总部 Group HQ Admin", Role.HQ_ADMIN, null, null));
        users.save(new AppUser("rhq.east", hash, "华东区域总部 East China RHQ", Role.REGION_MANAGER, regionByCode.get("CN-EAST"), null));
        users.save(new AppUser("rhq.south", hash, "华南区域总部 South China RHQ", Role.REGION_MANAGER, regionByCode.get("CN-SOUTH"), null));
        users.save(new AppUser("rhq.north", hash, "华北区域总部 North China RHQ", Role.REGION_MANAGER, regionByCode.get("CN-NORTH"), null));
        users.save(new AppUser("rhq.sw", hash, "西南区域总部 Southwest RHQ", Role.REGION_MANAGER, regionByCode.get("CN-SW"), null));
        users.save(new AppUser("shop.sh01", hash, "上海静安店 店长 Store Manager", Role.SHOP_MANAGER, regionByCode.get("CN-EAST"), byCode.get("SH-01")));
        users.save(new AppUser("shop.gz01", hash, "广州天河店 店长 Store Manager", Role.SHOP_MANAGER, regionByCode.get("CN-SOUTH"), byCode.get("GZ-01")));
        log.info("Seeded demo users: hq.admin, rhq.east, rhq.south, rhq.north, rhq.sw, shop.sh01, shop.gz01");
    }

    private static int indexOf(String code) {
        for (int i = 0; i < CATALOG.size(); i++) if (CATALOG.get(i).code().equals(code)) return i;
        throw new IllegalStateException(code);
    }

    /** Double 11 (双十一, 1-11 Nov): household and personal-care demand spikes. */
    private static double seasonalFactor(Sku sku, LocalDate date) {
        boolean double11 = date.getMonthValue() == 11 && date.getDayOfMonth() <= 11;
        boolean bulky = sku.getCategory().contains("Household") || sku.getCategory().contains("Personal Care")
                || sku.getCode().equals("WATER-24");
        return double11 && bulky ? 1.7 : 1.0;
    }

    private static int interval(int shelfLife) { return Math.max(2, Math.min(14, shelfLife / 3)); }

    private void simulate(Shop shop, int i, List<Sku> skuList, Map<Integer, Double> vel,
                          LocalDate start, LocalDate end, ZoneId zone, Random rnd) {
        {
            Sku sku = skuList.get(i);
            double v = vel.get(i);
            if (sku.getCode().equals(MOONCAKE)) { simulateMooncakeSeason(shop, sku, v, start, end, zone, rnd); return; }
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
                    long demand = Math.round(v * si * (0.7 + rnd.nextDouble() * 0.6) * seasonalFactor(sku, date));
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

    /**
     * Mid-Autumn mooncakes: buyers stock up from ~6 weeks before the festival (optimistically), demand peaks in the
     * last 3 weeks and stops dead on the day. Leftovers then age past their 60-day shelf life: some shops return them
     * to the supplier, some write them off, and the rest are still sitting in stock - which the depreciation
     * provision and EXPIRED alerts surface.
     */
    private void simulateMooncakeSeason(Shop shop, Sku sku, double peak, LocalDate start, LocalDate end, ZoneId zone,
                                        Random rnd) {
        LocalDate festival = MID_AUTUMN.stream().filter(d -> !d.isAfter(end.minusDays(20)) && !d.isBefore(start.plusDays(50)))
                .reduce((a, b) -> b).orElse(null);
        if (festival == null) return;
        double optimism = 1.1 + rnd.nextDouble() * 0.5;           // how far the buyer over-orders
        int outcome = rnd.nextInt(10);                             // 0-2 return, 3-5 write off, 6-9 still on shelf
        int seq = 0;
        for (LocalDate date = festival.minusDays(45); !date.isAfter(end); date = date.plusDays(1)) {
            long toFestival = festival.toEpochDay() - date.toEpochDay();
            Instant morning = date.atTime(8, 0).atZone(zone).toInstant();
            Instant evening = date.atTime(20, 0).atZone(zone).toInstant();
            double dayVel = toFestival < 0 ? 0 : toFestival <= 21 ? peak : peak * 0.3;
            if (toFestival >= 7 && toFestival % 7 == 0) {
                long target = (long) Math.ceil(peak * 10 * optimism);
                long qty = target - sellable(shop, sku, date);
                if (qty > 0) receive(shop, sku, qty, date.minusDays(rnd.nextInt(6)),
                        "PO-" + shop.getCode() + "-MOON-" + (++seq), morning);
            }
            if (dayVel > 0 && toFestival % 2 == 0) {
                long sell = Math.min(Math.round(dayVel * 2 * (0.6 + rnd.nextDouble() * 0.6)), sellable(shop, sku, date));
                if (sell > 0) inventory.stockOut(new StockOutRequest(shop.getId(), sku.getId(), sell, StockOutReason.SALE,
                        null, "POS-" + date, null), evening);
            }
            long left = sellable(shop, sku, date);
            if (outcome <= 2 && toFestival == -3 && left > 0)
                inventory.stockOut(new StockOutRequest(shop.getId(), sku.getId(), left, StockOutReason.RETURN_TO_SUPPLIER,
                        null, "RTV-" + date, "Post-festival return to supplier"), evening);
            if (outcome >= 3 && outcome <= 5 && toFestival == -56) writeOffExpired(shop, sku, date, morning);
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
