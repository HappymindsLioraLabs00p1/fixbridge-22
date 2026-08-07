package com.fixbridge.auth;

import com.fixbridge.common.enums.UserRole;
import com.fixbridge.user.ProfileRepository;
import com.fixbridge.user.UserRoleEntity;
import com.fixbridge.user.UserRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grants the admin role to an existing account named by {@code BOOTSTRAP_ADMIN_EMAIL}.
 *
 * <p>Admins deliberately cannot self-register, which leaves a fresh deployment with no way in short
 * of hand-editing the database. This closes that gap without exposing database credentials: set the
 * variable in the host's dashboard, register the account normally, and it is promoted on next boot.
 * It only ever promotes an account that already exists, and does nothing if the variable is unset.
 */
@Configuration
public class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    @Bean
    public ApplicationRunner bootstrapAdmin(
            @Value("${BOOTSTRAP_ADMIN_EMAIL:}") String adminEmail,
            ProfileRepository profiles,
            UserRoleRepository roles) {
        return args -> promote(adminEmail, profiles, roles);
    }

    @Transactional
    void promote(String adminEmail, ProfileRepository profiles, UserRoleRepository roles) {
        if (adminEmail == null || adminEmail.isBlank()) {
            return;
        }
        profiles.findByEmailIgnoreCase(adminEmail.trim()).ifPresentOrElse(profile -> {
            boolean alreadyAdmin = roles.findByUserId(profile.getId()).stream()
                    .anyMatch(r -> r.getRole() == UserRole.admin);
            if (alreadyAdmin) {
                log.info("Bootstrap admin {} already has the admin role", adminEmail);
                return;
            }
            roles.save(new UserRoleEntity(profile.getId(), UserRole.admin));
            log.info("Granted the admin role to {} via BOOTSTRAP_ADMIN_EMAIL", adminEmail);
        }, () -> log.warn("BOOTSTRAP_ADMIN_EMAIL is set to {} but no such account exists yet — "
                + "register it, then restart to promote it", adminEmail));
    }
}
