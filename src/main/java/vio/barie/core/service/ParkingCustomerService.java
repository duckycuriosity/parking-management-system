package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.parking.VehicleUserRegisterRequest;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.entity.VehicleUserPermissionEntity;
import vio.barie.core.repository.ParkingCustomerRepository;
import vio.barie.core.repository.VehicleUserPermissionRepository;
import vio.barie.core.repository.VehicleRepository;

@Service
@RequiredArgsConstructor
public class ParkingCustomerService {

    private final ParkingCustomerRepository customerRepo;
    private final VehicleRepository vehicleRepo;
    private final VehicleUserPermissionRepository permissionRepo;

    // tạo người dùng, tạo phương tiện , gắn quyền giữa 2 đối tượng
    @Transactional
    public VehicleUserPermissionEntity register(VehicleUserRegisterRequest req) {
        ParkingCustomerEntity customer = customerRepo
                .findByAiIdentityId(req.getAiIdentityId())
                .orElseGet(() -> {
                    ParkingCustomerEntity c = new ParkingCustomerEntity();
                    c.setAiIdentityId(req.getAiIdentityId());
                    if (StringUtils.isNotBlank(req.getZaloId())){
                        c.setZaloId(req.getZaloId());
                    }
                    c.setPhoneNumber(req.getPhoneNumber());
                    return customerRepo.save(c);
                });

        VehicleEntity vehicle = vehicleRepo
                .findByPlateNumber(req.getPlateNumber())
                .orElseGet(() -> {
                    VehicleEntity d = new VehicleEntity();
                    d.setPlateNumber(req.getPlateNumber());
                    return vehicleRepo.save(d);
                });

        // Check existed permission
        boolean existed = permissionRepo.existsByParkingCustomerIdAndVehicleId(
                customer.getId(),
                vehicle.getId()
        );

        if (existed) {
            throw new ServiceException("Permission already exists");
        }

        // Create permission
        VehicleUserPermissionEntity permission = new VehicleUserPermissionEntity();
        permission.setParkingCustomer(customer);
        permission.setVehicle(vehicle);
        permission.setRole(req.getRole());

        permissionRepo.save(permission);

        return permission;
    }
}
