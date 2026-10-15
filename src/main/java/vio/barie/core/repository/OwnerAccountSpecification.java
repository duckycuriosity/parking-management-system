package vio.barie.core.repository;

import jakarta.persistence.criteria.Predicate;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.jpa.domain.Specification;
import vio.barie.core.entity.OwnerAccountEntity;
import vio.barie.core.entity.ParkingLotEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.entity.VehicleSubscriptionEntity;

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
            LocalDate toDate,
            Long parkingLotId,
            Long buildingId) {
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

            if (parkingLotId != null || buildingId != null) {
                var locationSubquery = query.subquery(Long.class);
                var vehicle = locationSubquery.from(VehicleEntity.class);
                var subscription = locationSubquery.from(VehicleSubscriptionEntity.class);
                var parkingLot = locationSubquery.from(ParkingLotEntity.class);
                List<Predicate> locationPredicates = new ArrayList<>();

                locationPredicates.add(cb.equal(vehicle.get("ownerId"), root.get("id")));
                locationPredicates.add(cb.equal(subscription.get("vehicleId"), vehicle.get("id")));
                locationPredicates.add(cb.equal(parkingLot.get("id"), subscription.get("parkingLotId")));

                if (parkingLotId != null) {
                    locationPredicates.add(cb.equal(parkingLot.get("id"), parkingLotId));
                }

                if (buildingId != null) {
                    locationPredicates.add(cb.equal(parkingLot.get("building").get("id"), buildingId));
                }

                locationSubquery.select(vehicle.get("id"))
                        .where(locationPredicates.toArray(new Predicate[0]));
                predicates.add(cb.exists(locationSubquery));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
