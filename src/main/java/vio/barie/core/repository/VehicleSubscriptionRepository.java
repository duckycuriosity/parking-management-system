package vio.barie.core.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vio.barie.core.entity.VehicleSubscriptionEntity;

public interface VehicleSubscriptionRepository extends JpaRepository<VehicleSubscriptionEntity, Integer> {
}
