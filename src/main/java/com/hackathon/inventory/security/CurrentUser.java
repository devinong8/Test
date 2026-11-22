package com.hackathon.inventory.security;

import com.hackathon.inventory.domain.Shop;
import com.hackathon.inventory.repository.ShopRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves the current user's data scope from the security context. */
@Component
public class CurrentUser {
    private final ShopRepository shops;

    public CurrentUser(ShopRepository shops) { this.shops = shops; }

    public Optional<AppUserPrincipal> principal() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof AppUserPrincipal p ? Optional.of(p) : Optional.empty();
    }

    public AccessScope scope() {
        AppUserPrincipal p = principal().orElse(null);
        if (p == null) return AccessScope.ALL;
        return switch (p.role()) {
            case HQ_ADMIN -> AccessScope.ALL;
            case SHOP_MANAGER -> new AccessScope(p.role(), p.regionId(), p.shopId(), Set.of(p.shopId()));
        };
    }
}
