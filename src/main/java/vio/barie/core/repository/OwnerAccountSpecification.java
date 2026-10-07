package vio.barie.core.repository;

import jakarta.persistence.criteria.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.jpa.domain.Specification;
import vio.barie.core.entity.OwnerAccountEntity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public class OwnerAccountSpecification {

    private OwnerAccountSpecification() {
    }

    public static Specification<OwnerAccountEntity> filter(
            String searchTerm,
            String status,
            LocalDate fromDate,
            LocalDate toDate) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.isNotBlank(searchTerm)) {
                String keyword = "%" + searchTerm.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("fullName")), keyword),
                        cb.like(cb.lower(root.get("phone")), keyword),
                        cb.like(cb.lower(root.get("identityNumber")), keyword)
                ));
            }

            if (StringUtils.isNotBlank(status)) {
                predicates.add(cb.equal(root.get("status"), status.trim()));
            }

            if (fromDate != null) {
                OffsetDateTime fromDateTime = fromDate.atStartOfDay().atOffset(ZoneOffset.UTC);
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromDateTime));
            }

            if (toDate != null) {
                OffsetDateTime toDateTime = toDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
                predicates.add(cb.lessThan(root.get("createdAt"), toDateTime));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
