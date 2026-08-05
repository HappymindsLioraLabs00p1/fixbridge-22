package com.fixbridge.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserRoleRepository extends JpaRepository<UserRoleEntity, UserRoleEntity.Key> {
    List<UserRoleEntity> findByUserId(UUID userId);
}
