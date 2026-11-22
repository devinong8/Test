package com.hackathon.inventory.service;

import com.hackathon.inventory.config.BusinessRuleException;
import com.hackathon.inventory.config.NotFoundException;
import com.hackathon.inventory.analytics.InventoryFormulas;
import com.hackathon.inventory.domain.Region;
import com.hackathon.inventory.domain.SalesVelocity;
import com.hackathon.inventory.domain.Shop;
import com.hackathon.inventory.domain.Sku;
import com.hackathon.inventory.repository.*;
import com.hackathon.inventory.web.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/** Elementary information management: regions (HQ), SKU master, shop master and sales velocity. */
@Service
@Transactional
public class MasterDataService {
    private final SkuRepository skus;
    private final ShopRepository shops;
    private final SalesVelocityRepository velocities;
    private final InventoryBatchRepository batches;
    private final RegionRepository regions;

    public MasterDataService(SkuRepository skus, ShopRepository shops, SalesVelocityRepository velocities,
                             InventoryBatchRepository batches, RegionRepository regions) {
        this.skus = skus; this.shops = shops; this.velocities = velocities; this.batches = batches;
        this.regions = regions;
    }

    // ---------- Region (regional HQ) ----------
    @Transactional(readOnly = true)
    public List<RegionView> listRegions() { return regions.findAllByOrderByCodeAsc().stream().map(this::view).toList(); }

    @Transactional(readOnly = true)
    public Region getRegion(Long id) {
        return regions.findById(id).orElseThrow(() -> new NotFoundException("Region " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public RegionView getRegionView(Long id) { return view(getRegion(id)); }

    public RegionView createRegion(RegionRequest r) {
        if (regions.existsByCodeIgnoreCase(r.code().trim()))
            throw new BusinessRuleException("DUPLICATE_CODE", "Region code " + r.code() + " already exists");
        return view(regions.save(apply(new Region(), r)));
    }

    public RegionView updateRegion(Long id, RegionRequest r) {
        Region g = getRegion(id);
        if (regions.existsByCodeIgnoreCaseAndIdNot(r.code().trim(), id))
            throw new BusinessRuleException("DUPLICATE_CODE", "Region code " + r.code() + " already exists");
        return view(apply(g, r));
    }

    public RegionView setRegionActive(Long id, boolean active) {
        Region g = getRegion(id);
        if (!active && shops.findByRegionId(id).stream().anyMatch(Shop::isActive))
            throw new BusinessRuleException("REGION_HAS_SHOPS", "Region " + g.getCode() + " still has active shops");
        g.setActive(active);
        return view(g);
    }

    private Region apply(Region g, RegionRequest r) {
        g.setCode(r.code().trim().toUpperCase());
        g.setName(r.name().trim());
        g.setHqCity(r.hqCity().trim());
        return g;
    }

    private RegionView view(Region g) {
        return new RegionView(g.getId(), g.getCode(), g.getName(), g.getHqCity(), g.isActive(),
                g.getId() == null ? 0 : shops.countByRegionId(g.getId()));
    }

    // ---------- SKU ----------
    @Transactional(readOnly = true)
    public List<Sku> listSkus() { return skus.findAll(org.springframework.data.domain.Sort.by("code")); }

    @Transactional(readOnly = true)
    public Sku getSku(Long id) {
        return skus.findById(id).orElseThrow(() -> new NotFoundException("SKU " + id + " not found"));
    }

    public Sku createSku(SkuRequest r) {
        if (skus.existsByCodeIgnoreCase(r.code().trim()))
            throw new BusinessRuleException("DUPLICATE_CODE", "SKU code " + r.code() + " already exists");
        return skus.save(apply(new Sku(), r));
    }

    public Sku updateSku(Long id, SkuRequest r) {
        Sku s = getSku(id);
        if (skus.existsByCodeIgnoreCaseAndIdNot(r.code().trim(), id))
            throw new BusinessRuleException("DUPLICATE_CODE", "SKU code " + r.code() + " already exists");
        return apply(s, r);
    }

    /** SKUs holding stock cannot be removed; they are deactivated instead to preserve ledger history. */
    public Sku deactivateSku(Long id) {
        Sku s = getSku(id);
        if (batches.existsBySkuIdAndOnHandQtyGreaterThan(id, 0))
            throw new BusinessRuleException("SKU_HAS_STOCK", "SKU " + s.getCode() + " still has stock on hand");
        s.setActive(false);
        return s;
    }

    public Sku activateSku(Long id) { Sku s = getSku(id); s.setActive(true); return s; }

    private Sku apply(Sku s, SkuRequest r) {
        s.setCode(r.code().trim().toUpperCase());
        s.setName(r.name().trim());
        s.setCategory(r.category());
        s.setPurchasePrice(r.purchasePrice());
        s.setSalePrice(r.salePrice());
        s.setUnitVolumeM3(r.unitVolumeM3());
        s.setShelfLifeDays(r.shelfLifeDays());
        return s;
    }

    // ---------- Shop ----------
    @Transactional(readOnly = true)
    public List<ShopView> listShops() { return shops.findAllByOrderByCodeAsc().stream().map(this::view).toList(); }

    @Transactional(readOnly = true)
    public Shop getShop(Long id) {
        return shops.findById(id).orElseThrow(() -> new NotFoundException("Shop " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public ShopView getShopView(Long id) { return view(getShop(id)); }

    public ShopView createShop(ShopRequest r) {
        if (shops.existsByCodeIgnoreCase(r.code().trim()))
            throw new BusinessRuleException("DUPLICATE_CODE", "Shop code " + r.code() + " already exists");
        return view(shops.save(apply(new Shop(), r)));
    }

    public ShopView updateShop(Long id, ShopRequest r) {
        Shop s = getShop(id);
        if (shops.existsByCodeIgnoreCaseAndIdNot(r.code().trim(), id))
            throw new BusinessRuleException("DUPLICATE_CODE", "Shop code " + r.code() + " already exists");
        BigDecimal used = batches.usedVolume(id);
        BigDecimal cost = batches.costValue(id);
        if (r.storageCapacityM3().compareTo(used) < 0)
            throw new BusinessRuleException("CAPACITY_BELOW_USAGE",
                    "Storage capacity cannot be set below current usage of " + used.setScale(3, java.math.RoundingMode.HALF_UP) + " m3");
        if (r.capitalBudget().compareTo(cost) < 0)
            throw new BusinessRuleException("BUDGET_BELOW_USAGE",
                    "Capital budget cannot be set below current inventory cost of " + cost.setScale(2, java.math.RoundingMode.HALF_UP));
        return view(apply(s, r));
    }

    public ShopView deactivateShop(Long id) {
        Shop s = getShop(id);
        if (batches.existsByShopIdAndOnHandQtyGreaterThan(id, 0))
            throw new BusinessRuleException("SHOP_HAS_STOCK", "Shop " + s.getCode() + " still has stock on hand");
        s.setActive(false);
        return view(s);
    }

    public ShopView activateShop(Long id) { Shop s = getShop(id); s.setActive(true); return view(s); }

    private Shop apply(Shop s, ShopRequest r) {
        Region region = getRegion(r.regionId());
        if (!region.isActive() && (s.getRegion() == null || !s.getRegion().getId().equals(region.getId())))
            throw new BusinessRuleException("REGION_INACTIVE", "Region " + region.getCode() + " is inactive");
        s.setRegion(region);
        s.setCode(r.code().trim().toUpperCase());
        s.setName(r.name().trim());
        s.setCity(r.city().trim());
        s.setStorageCapacityM3(r.storageCapacityM3());
        s.setCapitalBudget(r.capitalBudget());
        return s;
    }

    private ShopView view(Shop s) {
        BigDecimal used = s.getId() == null ? BigDecimal.ZERO : batches.usedVolume(s.getId());
        BigDecimal cost = s.getId() == null ? BigDecimal.ZERO : batches.costValue(s.getId());
        return new ShopView(s.getId(), s.getRegion().getId(), s.getRegion().getCode(), s.getRegion().getName(), s.getCode(), s.getName(), s.getCity(), s.getStorageCapacityM3(),
                s.getCapitalBudget(), s.isActive(), used.setScale(3, java.math.RoundingMode.HALF_UP),
                cost.setScale(2, java.math.RoundingMode.HALF_UP),
                InventoryFormulas.ratio(used, s.getStorageCapacityM3()), InventoryFormulas.ratio(cost, s.getCapitalBudget()));
    }

    // ---------- Sales velocity ----------
    @Transactional(readOnly = true)
    public List<VelocityView> listVelocities(Long shopId) {
        getShop(shopId);
        return velocities.findByShopId(shopId).stream()
                .sorted(java.util.Comparator.comparing(v -> v.getSku().getCode()))
                .map(v -> {
                    long onHand = batches.onHand(shopId, v.getSku().getId());
                    double vel = v.getDailySalesVelocity().doubleValue();
                    return new VelocityView(shopId, v.getShop().getCode(), v.getSku().getId(), v.getSku().getCode(),
                            v.getSku().getName(), v.getDailySalesVelocity(), onHand, vel > 0 ? onHand / vel : null);
                }).toList();
    }

    public SalesVelocity setVelocity(Long shopId, Long skuId, BigDecimal dailySalesVelocity) {
        Shop shop = getShop(shopId);
        Sku sku = getSku(skuId);
        SalesVelocity v = velocities.findByShopIdAndSkuId(shopId, skuId)
                .orElseGet(() -> new SalesVelocity(shop, sku, dailySalesVelocity));
        v.setDailySalesVelocity(dailySalesVelocity);
        return velocities.save(v);
    }
}
