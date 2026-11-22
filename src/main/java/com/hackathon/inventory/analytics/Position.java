package com.hackathon.inventory.analytics;

import com.hackathon.inventory.domain.Shop;
import com.hackathon.inventory.domain.Sku;

import java.time.LocalDate;

/** A quantity of one batch held at one shop at a given point in time (live or rebuilt from the ledger). */
public record Position(Shop shop, Sku sku, Long batchId, String batchNo, LocalDate productionDate, long qty) {}
