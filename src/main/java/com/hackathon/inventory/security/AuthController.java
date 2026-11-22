package com.hackathon.inventory.security;

import com.hackathon.inventory.domain.Role;
import com.hackathon.inventory.repository.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    /** hqName / hqCity identify the one HQ that runs every shop (app.hq.*). */
    public record Me(String username, String displayName, Role role, Long regionId, String regionCode,
                     Long shopId, String shopCode, String hqName, String hqCity) {}
    public record DemoAccount(String username, String displayName, Role role, String scope) {}

    private final AuthenticationManager authManager;
    private final SecurityContextRepository contextRepo;
    private final CookieCsrfTokenRepository csrfRepo;
    private final CurrentUser currentUser;
    private final AppUserRepository users;
    private final boolean demoMode;
    private final String hqName;
    private final String hqCity;

    public AuthController(AuthenticationManager authManager, SecurityContextRepository contextRepo,
                          CookieCsrfTokenRepository csrfRepo, CurrentUser currentUser, AppUserRepository users,
                          @Value("${app.seed.enabled:false}") boolean demoMode,
                          @Value("${app.hq.name:Regional HQ}") String hqName, @Value("${app.hq.city:}") String hqCity) {
        this.authManager = authManager; this.contextRepo = contextRepo; this.csrfRepo = csrfRepo;
        this.currentUser = currentUser; this.users = users; this.demoMode = demoMode;
        this.hqName = hqName; this.hqCity = hqCity;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest r, HttpServletRequest req, HttpServletResponse res) {
        try {
            var auth = authManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(r.username(), r.password()));
            if (req.getSession(false) != null) req.changeSessionId();           // session-fixation protection
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            SecurityContextHolder.setContext(ctx);
            contextRepo.saveContext(ctx, req, res);
            csrfRepo.saveToken(null, req, res);                                   // rotate CSRF token on login
            AppUserPrincipal p = (AppUserPrincipal) auth.getPrincipal();
            log.info("LOGIN user={} role={}", p.username(), p.role());
            return ResponseEntity.ok(me(p));
        } catch (AuthenticationException e) {
            log.warn("LOGIN_FAILED user={}", r.username());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "BAD_CREDENTIALS", "detail", "Invalid username or password"));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<Me> me() {
        return currentUser.principal().map(p -> ResponseEntity.ok(me(p)))
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    private Me me(AppUserPrincipal p) {
        return new Me(p.username(), p.displayName(), p.role(), p.regionId(), p.regionCode(), p.shopId(), p.shopCode(),
                hqName, hqCity);
    }

    /** Lists seeded demo logins on the sign-in screen. Only exposed when the demo seed is enabled. */
    @GetMapping("/demo-accounts")
    public List<DemoAccount> demoAccounts() {
        if (!demoMode) return List.of();
        return users.findAllByOrderByRoleAscUsernameAsc().stream().map(u -> new DemoAccount(u.getUsername(),
                u.getDisplayName(), u.getRole(), u.getShop() != null ? u.getShop().getCode() : "All shops")).toList();
    }
}
