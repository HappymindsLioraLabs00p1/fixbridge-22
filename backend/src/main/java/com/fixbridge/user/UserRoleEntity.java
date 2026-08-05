package com.fixbridge.user;

import com.fixbridge.common.enums.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.util.UUID;

/** Row in {@code user_roles}. A user may hold several roles. */
@Entity
@Table(name = "user_roles")
@IdClass(UserRoleEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class UserRoleEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "role", columnDefinition = "user_role")
    private UserRole role;

    public UserRoleEntity(UUID userId, UserRole role) {
        this.userId = userId;
        this.role = role;
    }

    /** Composite key. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Key implements Serializable {
        private UUID userId;
        private UserRole role;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return java.util.Objects.equals(userId, key.userId) && role == key.role;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(userId, role);
        }
    }
}
