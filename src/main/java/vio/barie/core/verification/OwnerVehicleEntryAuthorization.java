package vio.barie.core.verification;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.repository.OwnerAccountRepository;
import vio.barie.core.repository.VehicleCustomerRepository;

import java.util.Objects;

/** Applies explicit PMS person/vehicle permissions to entry, preserving ownerless legacy flows. */
@Service
@RequiredArgsConstructor
public class OwnerVehicleEntryAuthorization {
    private final OwnerAccountRepository owners;
    private final VehicleCustomerRepository relations;

    @Transactional(readOnly = true)
    public boolean allows(VehicleEntity vehicle, ParkingCustomerEntity customer) {
        if (vehicle.getOwnerId() == null) return true;
        if (customer == null || !Objects.equals(vehicle.getOwnerId(), customer.getOwnerId())
                || "INACTIVE".equals(vehicle.getStatus()) || "INACTIVE".equals(customer.getStatus())) {
            return false;
        }
        if (owners.findById(vehicle.getOwnerId()).filter(owner -> !"INACTIVE".equals(owner.getStatus())).isEmpty()) {
            return false;
        }
        return relations.findByVehicleIdAndCustomerId(vehicle.getId(), customer.getId())
                .filter(relation -> "ACTIVE".equals(relation.getStatus())).isPresent();
    }
}
