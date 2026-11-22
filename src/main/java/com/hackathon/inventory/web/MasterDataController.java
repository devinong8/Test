package com.hackathon.inventory.web;

import com.hackathon.inventory.domain.Sku;
import com.hackathon.inventory.security.AccessScope;
import com.hackathon.inventory.security.CurrentUser;
import com.hackathon.inventory.service.MasterDataService;
import com.hackathon.inventory.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The single HQ owns all master data: regions, the SKU catalogue, shops, their limits and sales velocities.
 * Shop managers have read access to their own shop (and its region) and the catalogue.
 */
@RestController
@RequestMapping("/api")
public class MasterDataController {
    private static final String HQ = "hasRole('HQ_ADMIN')";

    private final MasterDataService service;
    private final CurrentUser currentUser;

    public MasterDataController(MasterDataService service, CurrentUser currentUser) {
        this.service = service; this.currentUser = currentUser;
    }

    // ----- regions (sales groupings of shops)
    @GetMapping("/regions")
    public List<RegionView> regions() {
        AccessScope scope = currentUser.scope();
        return service.listRegions().stream().filter(r -> scope.canSeeRegion(r.id())).toList();
    }
    @GetMapping("/regions/{id}")
    public RegionView region(@PathVariable Long id) { currentUser.scope().requireRegion(id); return service.getRegionView(id); }
    @PostMapping("/regions") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(HQ)
    public RegionView createRegion(@Valid @RequestBody RegionRequest r) { return service.createRegion(r); }
    @PutMapping("/regions/{id}") @PreAuthorize(HQ)
    public RegionView updateRegion(@PathVariable Long id, @Valid @RequestBody RegionRequest r) { return service.updateRegion(id, r); }
    @DeleteMapping("/regions/{id}") @PreAuthorize(HQ)
    public RegionView deactivateRegion(@PathVariable Long id) { return service.setRegionActive(id, false); }
    @PostMapping("/regions/{id}/activate") @PreAuthorize(HQ)
    public RegionView activateRegion(@PathVariable Long id) { return service.setRegionActive(id, true); }

    // ----- SKU catalogue (HQ-owned, readable by everyone)
    @GetMapping("/skus") public List<Sku> skus() { return service.listSkus(); }
    @GetMapping("/skus/{id}") public Sku sku(@PathVariable Long id) { return service.getSku(id); }
    @PostMapping("/skus") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(HQ)
    public Sku createSku(@Valid @RequestBody SkuRequest r) { return service.createSku(r); }
    @PutMapping("/skus/{id}") @PreAuthorize(HQ)
    public Sku updateSku(@PathVariable Long id, @Valid @RequestBody SkuRequest r) { return service.updateSku(id, r); }
    @DeleteMapping("/skus/{id}") @PreAuthorize(HQ)
    public Sku deactivateSku(@PathVariable Long id) { return service.deactivateSku(id); }
    @PostMapping("/skus/{id}/activate") @PreAuthorize(HQ)
    public Sku activateSku(@PathVariable Long id) { return service.activateSku(id); }

    // ----- shops
    @GetMapping("/shops")
    public List<ShopView> shops(@RequestParam(required = false) Long regionId) {
        return currentUser.scope().filter(service.listShops(), ShopView::id).stream()
                .filter(s -> regionId == null || regionId.equals(s.regionId())).toList();
    }
    @GetMapping("/shops/{id}")
    public ShopView shop(@PathVariable Long id) { currentUser.scope().requireShop(id); return service.getShopView(id); }

    @PostMapping("/shops") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize(HQ)
    public ShopView createShop(@Valid @RequestBody ShopRequest r) {
        return service.createShop(r);
    }
    @PutMapping("/shops/{id}") @PreAuthorize(HQ)
    public ShopView updateShop(@PathVariable Long id, @Valid @RequestBody ShopRequest r) {
        currentUser.scope().requireShop(id);
        return service.updateShop(id, r);
    }
    @DeleteMapping("/shops/{id}") @PreAuthorize(HQ)
    public ShopView deactivateShop(@PathVariable Long id) { currentUser.scope().requireShop(id); return service.deactivateShop(id); }
    @PostMapping("/shops/{id}/activate") @PreAuthorize(HQ)
    public ShopView activateShop(@PathVariable Long id) { currentUser.scope().requireShop(id); return service.activateShop(id); }

    @GetMapping("/shops/{shopId}/velocities")
    public List<VelocityView> velocities(@PathVariable Long shopId) {
        currentUser.scope().requireShop(shopId);
        return service.listVelocities(shopId);
    }
    @PutMapping("/shops/{shopId}/velocities/{skuId}") @PreAuthorize(HQ)
    public List<VelocityView> setVelocity(@PathVariable Long shopId, @PathVariable Long skuId,
                                          @Valid @RequestBody VelocityRequest r) {
        currentUser.scope().requireShop(shopId);
        service.setVelocity(shopId, skuId, r.dailySalesVelocity());
        return service.listVelocities(shopId);
    }
}
