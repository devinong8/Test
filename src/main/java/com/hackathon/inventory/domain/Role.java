package com.hackathon.inventory.domain;

/**
 * There is exactly one HQ (the brief's "Regional HQ", configured in app.hq) and it manages every shop centrally.
 * HQ_ADMIN     - HQ staff: every shop; owns the SKU catalogue, regions, shops, limits and the monthly batch.
 * SHOP_MANAGER - one shop: its stock, movements, ledger, alerts and festival plan.
 */
public enum Role { HQ_ADMIN, SHOP_MANAGER }
