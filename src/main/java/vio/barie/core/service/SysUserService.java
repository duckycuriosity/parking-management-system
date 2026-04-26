package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vio.barie.core.dto.user.UserBranchResponse;
import vio.barie.core.repository.SysUserRepository;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysUserService {

    private final SysUserRepository userRepository;

    public List<UserBranchResponse> getUsersByBranch(Long branchId) {
        return userRepository.findAllByBranchId(branchId).stream()
                .map(u -> new UserBranchResponse(
                        u.getId(),
                        u.getUsername()
                ))
                .collect(Collectors.toList());
    }
}
