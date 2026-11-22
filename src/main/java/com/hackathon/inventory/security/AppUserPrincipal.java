package com.hackathon.inventory.security;

import com.hackathon.inventory.domain.AppUser;
import com.hackathon.inventory.domain.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/** Authenticated user plus the organisational scope (region / shop) their role is bound to. */
public record AppUserPrincipal(Long id, String username, String passwordHash, String displayName, Role role,
                               Long regionId, String regionCode, Long shopId, String shopCode, boolean active)
        implements UserDetails {

    public static AppUserPrincipal of(AppUser u) {
        return new AppUserPrincipal(u.getId(), u.getUsername(), u.getPasswordHash(), u.getDisplayName(), u.getRole(),
                u.getRegion() == null ? null : u.getRegion().getId(), u.getRegion() == null ? null : u.getRegion().getCode(),
                u.getShop() == null ? null : u.getShop().getId(), u.getShop() == null ? null : u.getShop().getCode(),
                u.isActive());
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return username; }
    @Override public boolean isEnabled() { return active; }
}
