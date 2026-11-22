package com.hackathon.inventory.web;

import com.hackathon.inventory.domain.Sku;
import com.hackathon.inventory.service.MasterDataService;
import com.hackathon.inventory.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class MasterDataController {
    private final MasterDataService service;

    public MasterDataController(MasterDataService service) { this.service = service; }

    @GetMapping("/skus") public List<Sku> skus() { return service.listSkus(); }
    @GetMapping("/skus/{id}") public Sku sku(@PathVariable Long id) { return service.getSku(id); }
    @PostMapping("/skus") @ResponseStatus(HttpStatus.CREATED)
    public Sku createSku(@Valid @RequestBody SkuRequest r) { return service.createSku(r); }
    @PutMapping("/skus/{id}") public Sku updateSku(@PathVariable Long id, @Valid @RequestBody SkuRequest r) { return service.updateSku(id, r); }
    @DeleteMapping("/skus/{id}") public Sku deactivateSku(@PathVariable Long id) { return service.deactivateSku(id); }
    @PostMapping("/skus/{id}/activate") public Sku activateSku(@PathVariable Long id) { return service.activateSku(id); }

    @GetMapping("/shops") public List<ShopView> shops() { return service.listShops(); }
    @GetMapping("/shops/{id}") public ShopView shop(@PathVariable Long id) { return service.getShopView(id); }
    @PostMapping("/shops") @ResponseStatus(HttpStatus.CREATED)
    public ShopView createShop(@Valid @RequestBody ShopRequest r) { return service.createShop(r); }
    @PutMapping("/shops/{id}") public ShopView updateShop(@PathVariable Long id, @Valid @RequestBody ShopRequest r) { return service.updateShop(id, r); }
    @DeleteMapping("/shops/{id}") public ShopView deactivateShop(@PathVariable Long id) { return service.deactivateShop(id); }
    @PostMapping("/shops/{id}/activate") public ShopView activateShop(@PathVariable Long id) { return service.activateShop(id); }

    @GetMapping("/shops/{shopId}/velocities")
    public List<VelocityView> velocities(@PathVariable Long shopId) { return service.listVelocities(shopId); }

    @PutMapping("/shops/{shopId}/velocities/{skuId}")
    public List<VelocityView> setVelocity(@PathVariable Long shopId, @PathVariable Long skuId,
                                          @Valid @RequestBody VelocityRequest r) {
        service.setVelocity(shopId, skuId, r.dailySalesVelocity());
        return service.listVelocities(shopId);
    }
}
