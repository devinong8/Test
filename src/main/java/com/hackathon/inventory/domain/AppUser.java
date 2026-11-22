package com.hackathon.inventory.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "app_user", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 60)
    private String username;
    @Column(nullable = false, length = 100)
    private String passwordHash;
    @Column(nullable = false)
    private String displayName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Role role;
    /** Set for REGION_MANAGER (their region) and SHOP_MANAGER (their shop's region). */
    @ManyToOne @JoinColumn(name = "region_id")
    private Region region;
    /** Set for SHOP_MANAGER only. */
    @ManyToOne @JoinColumn(name = "shop_id")
    private Shop shop;
    private boolean active = true;

    public AppUser() {}
    public AppUser(String username, String passwordHash, String displayName, Role role, Region region, Shop shop) {
        this.username = username; this.passwordHash = passwordHash; this.displayName = displayName;
        this.role = role; this.region = region; this.shop = shop;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Role getRole() { return role; }
    public Region getRegion() { return region; }
    public Shop getShop() { return shop; }
    public boolean isActive() { return active; }
}
