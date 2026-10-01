package vio.barie.core.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vio.barie.core.dto.Result;
import vio.barie.core.dto.parking.VehicleUserRegisterRequest;
import vio.barie.core.entity.VehicleUserPermissionEntity;
import vio.barie.core.service.ParkingCustomerService;

// controller cho mọi hoạt động đỗ xe
@RestController
@RequestMapping("/api/parking")
@RequiredArgsConstructor
public class ParkingController {

    private final ParkingCustomerService parkingCustomerService;

    @PostMapping("/register")
    public Result<VehicleUserPermissionEntity> register(@RequestBody VehicleUserRegisterRequest req) {
        VehicleUserPermissionEntity data = parkingCustomerService.register(req);
        return Result.ok("Đăng kí thành công!", data);
    }

}
