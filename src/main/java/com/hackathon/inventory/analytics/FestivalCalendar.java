package com.hackathon.inventory.analytics;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Chinese retail calendar used by the festival planner. Each event lifts demand for specific SKUs, optionally only
 * in some regions (冬至: the north eats dumplings, the south eats tangyuan).
 */
public final class FestivalCalendar {
    private FestivalCalendar() {}

    /**
     * factor: multiplier on the shop's normal daily velocity during the window.
     * seasonalDailyUnits: for one-off seasonal SKUs with no normal velocity (平安果, 月饼), units per day for an
     * average-traffic shop.
     */
    public record Uplift(String skuCode, Set<String> regionCodes, double factor, double seasonalDailyUnits) {
        public boolean appliesTo(String regionCode) { return regionCodes.isEmpty() || regionCodes.contains(regionCode); }
        static Uplift all(String sku, double factor) { return new Uplift(sku, Set.of(), factor, 0); }
        static Uplift in(Set<String> regions, String sku, double factor) { return new Uplift(sku, regions, factor, 0); }
        static Uplift seasonal(String sku, double dailyUnits) { return new Uplift(sku, Set.of(), 0, dailyUnits); }
    }

    public record Festival(String code, String name, LocalDate date, int windowDays, String story, List<Uplift> uplifts) {
        public LocalDate windowStart() { return date.minusDays(windowDays - 1L); }
        public LocalDate windowEnd() { return date; }
    }

    private static final Set<String> NORTH = Set.of("CN-NORTH");
    private static final Set<String> NOT_NORTH = Set.of("CN-EAST", "CN-SOUTH", "CN-SW");

    private static List<Uplift> double12() {
        return List.of(Uplift.all("TISSUE-10", 1.5), Uplift.all("LAUND-2KG", 1.5), Uplift.all("TOOTH-120", 1.4),
                Uplift.all("WATER-24", 1.4));
    }

    private static List<Uplift> dongzhi() {
        return List.of(Uplift.in(NORTH, "DUMP-1KG", 3.5), Uplift.in(NOT_NORTH, "DUMP-1KG", 1.5),
                Uplift.in(NOT_NORTH, "TANGYUAN", 3.5), Uplift.in(NORTH, "TANGYUAN", 1.3));
    }

    private static List<Uplift> christmasEve() { return List.of(Uplift.seasonal("XMAS-APPLE", 25)); }

    private static List<Uplift> springFestival() {
        return List.of(Uplift.all("BAIJIU-500", 4.0), Uplift.all("OIL-PNT5", 2.5), Uplift.all("RICE-NE5", 2.0),
                Uplift.all("SEEDS-500", 3.0), Uplift.in(NORTH, "DUMP-1KG", 2.5), Uplift.in(NOT_NORTH, "DUMP-1KG", 1.5),
                Uplift.all("WATER-24", 1.3));
    }

    public static final List<Festival> EVENTS = List.of(
            new Festival("DOUBLE12-2026", "双十二 Double 12", LocalDate.of(2026, 12, 12), 3,
                    "Second online shopping festival of the season; household staples lift again after 双十一.", double12()),
            new Festival("DONGZHI-2026", "冬至 Winter Solstice", LocalDate.of(2026, 12, 22), 2,
                    "北方吃饺子，南方吃汤圆: the north eats dumplings, the south eats tangyuan.", dongzhi()),
            new Festival("XMAS-EVE-2026", "平安夜 Christmas Eve", LocalDate.of(2026, 12, 24), 3,
                    "平安果: gift-wrapped apples ('ping' in 苹果 echoes 平安, peace) are the Chinese Christmas Eve gift. "
                            + "A pure one-off: anything unsold on the 25th has no demand.", christmasEve()),
            new Festival("SPRING-2027", "春节 Spring Festival", LocalDate.of(2027, 2, 6), 14,
                    "The year's biggest stock-up: baijiu and peanut oil as gifts, rice, sunflower seeds for guests, "
                            + "dumplings for 除夕.", springFestival()),
            new Festival("DOUBLE12-2027", "双十二 Double 12", LocalDate.of(2027, 12, 12), 3,
                    "Household staples lift after 双十一.", double12()),
            new Festival("DONGZHI-2027", "冬至 Winter Solstice", LocalDate.of(2027, 12, 22), 2,
                    "北方吃饺子，南方吃汤圆.", dongzhi()),
            new Festival("XMAS-EVE-2027", "平安夜 Christmas Eve", LocalDate.of(2027, 12, 24), 3,
                    "平安果 gift-wrapped apples.", christmasEve()));

    /**
     * Earliest receive date such that a batch produced on arrival is still in the NORMAL tier (R_dep < 0.60,
     * 0% provision) on the last day of the festival: windowEnd - floor(0.6 x shelfLife). Clamped to [today, orderBy].
     */
    public static LocalDate orderFrom(Festival f, int shelfLifeDays, LocalDate today) {
        LocalDate from = f.windowEnd().minusDays((long) Math.floor(0.6 * shelfLifeDays - 1e-9));
        LocalDate by = orderBy(f);
        if (from.isAfter(by)) from = by;
        return from.isBefore(today) ? today : from;
    }

    /** Latest receive date: two days before the window opens (shelf set-up / lead time). */
    public static LocalDate orderBy(Festival f) { return f.windowStart().minusDays(2); }
}
