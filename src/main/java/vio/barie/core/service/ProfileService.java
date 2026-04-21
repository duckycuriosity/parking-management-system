package vio.barie.core.service;

import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.dto.ProfileCreateRequest;

import java.util.List;

public interface  ProfileService {

    // tạo profile mới
    AiProfileResponse create(ProfileCreateRequest request);

    // lấy all profile
    List<AiProfileResponse> findAll();
    // lấy theo profile theo id
    AiProfileResponse findById(Integer id);

    // cập nhật profile
    AiProfileResponse update(Integer id, ProfileCreateRequest request);

    // xóa
    void delete(Integer id);
}
