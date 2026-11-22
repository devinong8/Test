package com.hackathon.inventory.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Festival pre-stock plan for the caller's scope. */
public record FestivalPlan(LocalDate asOf, List<SeasonalLesson> lessons, List<Event> events) {

    public record Event(String code, String name, LocalDate date, LocalDate windowStart, LocalDate windowEnd,
                        long daysAway, String story, long totalUnits, BigDecimal totalCost, List<Line> lines) {}

    /**
     * recommendedQty: extra units to receive on top of normal replenishment. orderFrom..orderBy: receive window that
     * keeps the stock in the NORMAL depreciation tier through the festival.
     */
    public record Line(Long shopId, String shopCode, String shopName, String regionCode, Long skuId, String skuCode,
                       String skuName, double baselineDaily, double factor, long expectedUnits, long recommendedQty,
                       String cappedBy, BigDecimal cost, LocalDate orderFrom, LocalDate orderBy, String note) {}

    /** What happened to last season's one-off seasonal stock (月饼), per region, from the ledger. */
    public record SeasonalLesson(String regionCode, String regionName, String skuCode, long received, long sold,
                                 long returned, long writtenOff, long stillOnHand, double sellThrough,
                                 BigDecimal unsoldCost) {}
}
