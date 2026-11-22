package com.hackathon.inventory.domain;

/**
 * HQ_ADMIN       - group HQ: every region and shop; owns SKU catalogue, regions, users and the monthly batch.
 * REGION_MANAGER - a regional HQ: shops, stock and reports of one region.
 * SHOP_MANAGER   - one shop: its stock, movements, ledger and alerts.
 */
public enum Role { HQ_ADMIN, REGION_MANAGER, SHOP_MANAGER }
