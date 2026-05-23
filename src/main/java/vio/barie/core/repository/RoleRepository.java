package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.RoleEntity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<RoleEntity, Long> {


    List<RoleEntity> findByRoleCodeIn(Collection<String> ids);

    Optional<RoleEntity> findByRoleCode(String roleCode);
}
