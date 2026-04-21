package vio.barie.core.service;

import vio.barie.core.dto.AiPlateResponse;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.dto.PlateCreateRequest;

import java.util.List;

public interface PlateService {

    // tạo mới biển số
    AiPlateResponse create(PlateCreateRequest request);

    // lấy full ds biển số
    List<AiPlateResponse> findAll();

    AiPlateResponse findById(Integer id);

    // update biển số theo id
    AiPlateResponse update(Integer id, PlateCreateRequest request);

    // xóa biển số theo id
    void delete(Integer id);
}
