package com.hackathon.inventory.security;

import com.hackathon.inventory.domain.Role;
import org.springframework.security.access.AccessDeniedException;

import java.util.Collection;
import java.util.Set;
import java.util.function.Function;

/**
 * Which shops the current user may see and act on. shopIds == null means every shop (HQ, or internal callers with
 * no user such as the scheduler and seeder). A shop manager is scoped to one shop and its region's metadata.
 */
public record AccessScope(Role role, Long regionId, Long shopId, Set<Long> shopIds) {

    public static final AccessScope ALL = new AccessScope(Role.HQ_ADMIN, null, null, null);

    public boolean isAll() { return shopIds == null; }

    public boolean canSeeShop(Long id) { return isAll() || shopIds.contains(id); }

    public boolean canSeeRegion(Long id) { return isAll() || (regionId != null && regionId.equals(id)); }

    /** Report lines are per-shop snapshots: HQ sees all of them, anyone else only their own shops' lines. */
    public boolean canSeeReportLine(Long lineRegionId, Long lineShopId) {
        return isAll() || canSeeShop(lineShopId);
    }

    public void requireShop(Long id) {
        if (id != null && !canSeeShop(id)) throw new AccessDeniedException("Shop " + id + " is outside your scope");
    }

    public void requireRegion(Long id) {
        if (!canSeeRegion(id)) throw new AccessDeniedException("Region " + id + " is outside your scope");
    }

    /** Intersects an optional request filter with the user's scope; null = no restriction. */
    public Set<Long> narrow(Set<Long> requested) {
        if (requested == null) return shopIds;
        if (isAll()) return requested;
        return requested.stream().filter(shopIds::contains).collect(java.util.stream.Collectors.toSet());
    }

    public <T> java.util.List<T> filter(Collection<T> items, Function<T, Long> shopIdOf) {
        return items.stream().filter(i -> canSeeShop(shopIdOf.apply(i))).toList();
    }
}
