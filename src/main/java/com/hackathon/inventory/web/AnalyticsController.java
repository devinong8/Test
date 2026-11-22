package com.hackathon.inventory.web;

import com.hackathon.inventory.analytics.PortfolioMetrics;
import com.hackathon.inventory.domain.MonthlyReportLine;
import com.hackathon.inventory.domain.Shop;
import com.hackathon.inventory.repository.ShopRepository;
import com.hackathon.inventory.security.AccessScope;
import com.hackathon.inventory.security.CurrentUser;
import com.hackathon.inventory.service.AnalyticsService;
import com.hackathon.inventory.service.ReportService;
import com.hackathon.inventory.web.dto.Alert;
import com.hackathon.inventory.web.dto.ReportView;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class AnalyticsController {
    private static final String HQ_OR_REGION = "hasAnyRole('HQ_ADMIN','REGION_MANAGER')";

    private final AnalyticsService analytics;
    private final ReportService reports;
    private final ShopRepository shops;
    private final CurrentUser currentUser;
    private final com.hackathon.inventory.service.FestivalPlanner festivals;

    public AnalyticsController(AnalyticsService analytics, ReportService reports, ShopRepository shops,
                               CurrentUser currentUser, com.hackathon.inventory.service.FestivalPlanner festivals) {
        this.analytics = analytics; this.reports = reports; this.shops = shops; this.currentUser = currentUser;
        this.festivals = festivals;
    }

    /** 节日备货: festival pre-stock plan for the caller's shops (optionally one region), next horizonDays days. */
    @GetMapping("/analytics/festival-plan")
    public com.hackathon.inventory.web.dto.FestivalPlan festivalPlan(@RequestParam(defaultValue = "90") int horizonDays,
                                                                     @RequestParam(required = false) Long regionId) {
        if (horizonDays < 1 || horizonDays > 400) throw new IllegalArgumentException("horizonDays must be 1..400");
        return festivals.plan(horizonDays, shopFilter(regionId));
    }

    /** Live valuation / turnover / depreciation, scoped to the user and optionally to one region. */
    @GetMapping("/analytics/portfolio")
    public PortfolioMetrics portfolio(@RequestParam(defaultValue = "false") boolean includeBatches,
                                      @RequestParam(required = false) Long regionId) {
        PortfolioMetrics pm = analytics.portfolio(shopFilter(regionId));
        return includeBatches ? pm : pm.withoutBatches();
    }

    @GetMapping("/analytics/alerts")
    public List<Alert> alerts(@RequestParam(required = false) Long regionId) {
        return analytics.alerts(shopFilter(regionId));
    }

    private Set<Long> shopFilter(Long regionId) {
        AccessScope scope = currentUser.scope();
        if (regionId == null) return scope.narrow(null);
        scope.requireRegion(regionId);
        return scope.narrow(shops.findByRegionId(regionId).stream().map(Shop::getId).collect(Collectors.toSet()));
    }

    // ----- monthly management report (group HQ and regional HQs)
    @GetMapping("/reports") @PreAuthorize(HQ_OR_REGION)
    public List<ReportView> reports() { return reports.listViews(currentUser.scope()); }

    @GetMapping("/reports/{period}") @PreAuthorize(HQ_OR_REGION)
    public Map<String, Object> report(@PathVariable String period) {
        String p = YearMonth.parse(period).toString();
        AccessScope scope = currentUser.scope();
        ReportView view = reports.view(p, scope);
        List<MonthlyReportLine> lines = reports.lines(p, scope);
        return Map.of("report", view, "lines", lines);
    }

    @GetMapping(value = "/reports/{period}/csv", produces = "text/csv") @PreAuthorize(HQ_OR_REGION)
    public ResponseEntity<String> csv(@PathVariable String period) {
        String p = YearMonth.parse(period).toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=inventory-report-" + p + ".csv")
                .contentType(MediaType.parseMediaType("text/csv")).body(reports.csv(p, currentUser.scope()));
    }

    /** Manual trigger for the monthly batch (the scheduler calls the same code path). Group HQ only. */
    @PostMapping("/reports/generate") @PreAuthorize("hasRole('HQ_ADMIN')")
    public ReportView generate(@RequestParam String period) {
        return reports.view(reports.generate(YearMonth.parse(period), "MANUAL").getPeriod(), currentUser.scope());
    }
}
