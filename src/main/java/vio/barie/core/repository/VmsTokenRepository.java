package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.VmsTokenEntity;

import java.util.Optional;

public interface VmsTokenRepository extends JpaRepository<VmsTokenEntity, Long> {
    Optional<VmsTokenEntity> findByTokenName(String tokenName);
}