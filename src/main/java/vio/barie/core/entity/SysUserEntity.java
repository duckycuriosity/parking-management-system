package vio.barie.core.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import vio.barie.core.entity.base.BaseEntity;

import java.util.List;

@Entity
@Table(name = "sys_user")
@Data
@EqualsAndHashCode(callSuper = true)
public class SysUserEntity extends BaseEntity {

    @Column(name = "username", unique = true, nullable = false)
    private String username;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "role")
    private String role; // 'ADMIN', 'STAFF'
}