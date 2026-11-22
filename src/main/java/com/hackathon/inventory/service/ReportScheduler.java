package com.hackathon.inventory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;

/** Automated batch: on the 1st of every month, generate the report for the month that just closed. */
@Component
public class ReportScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReportScheduler.class);
    private final ReportService reports;
    private final Clock clock;

    public ReportScheduler(ReportService reports, Clock clock) { this.reports = reports; this.clock = clock; }

    @Scheduled(cron = "${app.report.cron}", zone = "${app.zone:Asia/Shanghai}")
    public void generatePreviousMonth() {
        YearMonth period = YearMonth.now(clock).minusMonths(1);
        log.info("Scheduled monthly report batch starting for {}", period);
        try {
            reports.generate(period, "SCHEDULED");
        } catch (RuntimeException e) {
            log.error("Scheduled monthly report for {} failed", period, e);
        }
    }
}
