package com.hackathon.inventory.repository;

import com.hackathon.inventory.domain.MonthlyReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MonthlyReportRepository extends JpaRepository<MonthlyReport, Long> {
    Optional<MonthlyReport> findByPeriod(String period);
    List<MonthlyReport> findAllByOrderByPeriodAsc();
}
