package vio.barie.core.repository;

import org.springframework.data.jpa.domain.Specification;
import vio.barie.core.entity.SysUserEntity;

public class SysUserSpecification {

//    public static Specification<UserEntity> filterByOrganizeIdAndFilters(
//            Integer organizeId, String username, String email,String phone, Integer branchId,String searchKey) {
//        return (root, query, cb) -> {
//            var predicate = cb.equal(root.get("organize").get("id"), organizeId);
//
//            if (username != null && !username.trim().isEmpty()) {
//                predicate = cb.and(predicate, cb.like(
//                        cb.lower(root.get("username")),
//                        "%" + username.toLowerCase() + "%"));
//            }
//
//            if (email != null && !email.trim().isEmpty()) {
//                predicate = cb.and(predicate, cb.like(
//                        cb.lower(root.get("email")),
//                        "%" + email.toLowerCase() + "%"));
//            }
//            if (phone != null && !phone.trim().isEmpty()) {
//                predicate = cb.and(predicate, cb.like(
//                        cb.lower(root.get("phone")),
//                        "%" + phone.toLowerCase() + "%"));
//            }
//
//            if (branchId != null) {
//                predicate = cb.and(predicate, cb.equal(root.get("branch").get("id"), branchId));
//            }
//
//            return predicate;
//        };
//    }

    public static Specification<SysUserEntity> filterByOrganizeIdAndFilters(
            Long organizeId, String username, String email, String phone, Long branchId, String searchKey, String status) {
        return (root, query, cb) -> {
            var predicate = cb.equal(root.get("organize").get("id"), organizeId);

            if (username != null && !username.trim().isEmpty()) {
                predicate = cb.and(predicate, cb.like(
                        cb.lower(root.get("username")),
                        "%" + username.toLowerCase() + "%"));
            }

            if (email != null && !email.trim().isEmpty()) {
                predicate = cb.and(predicate, cb.like(
                        cb.lower(root.get("email")),
                        "%" + email.toLowerCase() + "%"));
            }

            if (phone != null && !phone.trim().isEmpty()) {
                predicate = cb.and(predicate, cb.like(
                        cb.lower(root.get("phone")),
                        "%" + phone.toLowerCase() + "%"));
            }

            if (branchId != null) {
                predicate = cb.and(predicate, cb.equal(root.get("branch").get("id"), branchId));
            }

            if (status != null && !status.trim().isEmpty()) {
                predicate = cb.and(predicate, cb.equal(root.get("status"), status));
            }

            // Nếu có searchKey thì search theo nhiều trường OR
            if (searchKey != null && !searchKey.trim().isEmpty()) {
                String key = "%" + searchKey.toLowerCase() + "%";
                var orPredicate = cb.or(
                        cb.like(cb.lower(root.get("username")), key),
                        cb.like(cb.lower(root.get("email")), key),
                        cb.like(cb.lower(root.get("phone")), key),
                        cb.like(cb.lower(root.get("fullname")), key)
                );
                predicate = cb.and(predicate, orPredicate);
            }

            return predicate;
        };
    }


}

