package com.hackathon.inventory.security;

import com.hackathon.inventory.domain.AppUser;
import com.hackathon.inventory.domain.Role;
import com.hackathon.inventory.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * For a non-demo deployment: if there are no users at all and APP_SECURITY_BOOTSTRAP_ADMIN_PASSWORD is set,
 * create the first group-HQ admin so someone can log in.
 */
@Component
@Order(2)
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final String password;

    public AdminBootstrap(AppUserRepository users, PasswordEncoder encoder,
                          @Value("${app.security.bootstrap-admin-password:}") String password) {
        this.users = users; this.encoder = encoder; this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) return;
        if (password.isBlank()) {
            log.warn("No users exist. Set app.security.bootstrap-admin-password to create the first HQ admin.");
            return;
        }
        users.save(new AppUser("hq.admin", encoder.encode(password), "Group HQ Admin", Role.HQ_ADMIN, null, null));
        log.info("Bootstrap HQ admin 'hq.admin' created");
    }
}
