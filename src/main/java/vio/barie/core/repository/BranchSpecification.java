package vio.barie.core.repository;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import vio.barie.core.entity.BranchEntity;

public class BranchSpecification {

    public static Specification<BranchEntity> filterByOrganizeIdAndProvinceBranchName(
            Long organizeId, String province, String branchName) {
        return (root, query, cb) -> {
            Predicate predicate = cb.equal(root.get("organize").get("id"), organizeId);

            if (province != null && !province.isEmpty()) {
                predicate = cb.and(predicate, cb.like(
                        cb.lower(root.get("province")), 
                        "%" + province.toLowerCase() + "%"));
            }

            if (branchName != null && !branchName.isEmpty()) {
                predicate = cb.and(predicate, cb.like(
                        cb.lower(root.get("branchCode")), 
                        "%" + branchName.toLowerCase() + "%"));
            }

            return predicate;
        };
    }

    public static Specification<BranchEntity> filterByOrganizeIdAndFilters(
            Long organizeId, String name, String searchTerm, String status, String province) {
        return (root, query, cb) -> {
            Predicate predicate = cb.equal(root.get("organize").get("id"), organizeId);

            if (name != null && !name.isEmpty()) {
                predicate = cb.and(predicate, cb.like(
                        cb.lower(root.get("branchName")),
                        "%" + name.toLowerCase() + "%"));
            }

            if (province != null && !province.isEmpty()) {
                predicate = cb.and(predicate, cb.like(
                        cb.lower(root.get("province")),
                        "%" + province.toLowerCase() + "%"));
            }

            if (searchTerm != null && !searchTerm.isEmpty()) {
                String searchKey = "%" + searchTerm.toLowerCase() + "%";
                Predicate searchPredicate = cb.or(
                        cb.like(cb.lower(root.get("branchName")), searchKey),
                        cb.like(cb.lower(root.get("branchCode")), searchKey),
                        cb.like(cb.lower(root.get("address")), searchKey)
                );
                predicate = cb.and(predicate, searchPredicate);
            }

            if (status != null && !status.isEmpty()) {
                predicate = cb.and(predicate, cb.equal(root.get("status"), status));
            }

            return predicate;
        };
    }
}

