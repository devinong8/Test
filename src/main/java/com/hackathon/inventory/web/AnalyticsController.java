package com.hackathon.inventory.web;

import com.hackathon.inventory.analytics.PortfolioMetrics;
import com.hackathon.inventory.domain.MonthlyReport;
import com.hackathon.inventory.service.AnalyticsService;
import com.hackathon.inventory.service.ReportService;
import com.hackathon.inventory.web.dto.Alert;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AnalyticsController {
    private final AnalyticsService analytics;
    private final ReportService reports;

    public AnalyticsController(AnalyticsService analytics, ReportService reports) {
        this.analytics = analytics; this.reports = reports;
    }

    /** Live valuation / turnover / depreciation. includeBatches=false keeps the payload small for dashboards. */
    @GetMapping("/analytics/portfolio")
    public PortfolioMetrics portfolio(@RequestParam(defaultValue = "false") boolean includeBatches) {
        PortfolioMetrics pm = analytics.portfolio();
        if (includeBatches) return pm;
        return new PortfolioMetrics(pm.asOfDate(), pm.units(), pm.costValue(), pm.retailValue(),
                pm.potentialGrossMargin(), pm.cogs30d(), pm.turnoverRate(), pm.provision(), pm.netRealisableCost(),
                pm.costByTier(), pm.shops(), List.of());
    }

    @GetMapping("/analytics/alerts")
    public List<Alert> alerts() { return analytics.alerts(); }

    @GetMapping("/reports")
    public List<MonthlyReport> reports() { return reports.list(); }

    @GetMapping("/reports/{period}")
    public Map<String, Object> report(@PathVariable String period) {
        MonthlyReport r = reports.get(YearMonth.parse(period).toString());
        return Map.of("report", r, "lines", r.getLines());
    }

    @GetMapping(value = "/reports/{period}/csv", produces = "text/csv")
    public ResponseEntity<String> csv(@PathVariable String period) {
        String p = YearMonth.parse(period).toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=inventory-report-" + p + ".csv")
                .contentType(MediaType.parseMediaType("text/csv")).body(reports.csv(p));
    }

    /** Manual trigger for the monthly batch (the scheduler calls the same code path). */
    @PostMapping("/reports/generate")
    public MonthlyReport generate(@RequestParam String period) {
        return reports.generate(YearMonth.parse(period), "MANUAL");
    }
}
