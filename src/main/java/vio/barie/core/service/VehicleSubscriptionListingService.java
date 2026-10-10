package vio.barie.core.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.VehicleSubscriptionResponse;
import vio.barie.core.entity.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/** Page orders first, then batch display labels. Driver relations cannot multiply the page or count. */
@Service
@Transactional(readOnly = true)
public class VehicleSubscriptionListingService {
    private final EntityManager entityManager;

    public VehicleSubscriptionListingService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Page<VehicleSubscriptionResponse> findAll(String keyword, Long buildingId, Long parkingLotId,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        if (pageable == null || pageable.isUnpaged() || pageable.getPageSize() > 200 || pageable.getOffset() > Integer.MAX_VALUE)
            throw invalid("Require a bounded page of at most 200 orders");
        if (startDate != null && endDate != null && endDate.isBefore(startDate))
            throw invalid("endDate must not precede startDate");
        if ((buildingId != null && buildingId <= 0) || (parkingLotId != null && parkingLotId <= 0))
            throw invalid("buildingId and parkingLotId must be positive");
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> pageQuery = builder.createQuery(Object[].class);
        Root<VehicleSubscriptionEntity> order = pageQuery.from(VehicleSubscriptionEntity.class);
        pageQuery.multiselect(order.get("id"), order.get("vehicleId"), order.get("parkingLotId"),
                order.get("subscriptionPackage").get("id"), order.get("endsAt"), order.get("status"));
        pageQuery.where(filters(builder, pageQuery, order, keyword, buildingId, parkingLotId, startDate, endDate));
        pageQuery.orderBy(builder.desc(order.get("createdAt")), builder.desc(order.get("id")));
        List<Object[]> rows = entityManager.createQuery(pageQuery).setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize()).getResultList();

        CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
        Root<VehicleSubscriptionEntity> countedOrder = countQuery.from(VehicleSubscriptionEntity.class);
        countQuery.select(builder.count(countedOrder));
        countQuery.where(filters(builder, countQuery, countedOrder, keyword, buildingId, parkingLotId, startDate, endDate));
        long total = entityManager.createQuery(countQuery).getSingleResult();
        if (rows.isEmpty()) return new PageImpl<>(List.of(), pageable, total);

        Set<Long> vehicleIds = ids(rows, 1), lotIds = ids(rows, 2), packageIds = ids(rows, 3);
        Map<Long, Object[]> vehicles = new HashMap<>();
        entityManager.createQuery("""
                select vehicle.id, vehicle.plateNumber, owner.fullName from VehicleEntity vehicle
                left join OwnerAccountEntity owner on owner.id = vehicle.ownerId
                where vehicle.id in :ids
                """, Object[].class).setParameter("ids", vehicleIds).getResultList()
                .forEach(row -> vehicles.put((Long) row[0], row));
        Map<Long, String> lots = labels("select lot.id, lot.name from ParkingLotEntity lot where lot.id in :ids", lotIds);
        Map<Long, String> packages = labels("select pack.id, pack.name from SubscriptionPackageEntity pack where pack.id in :ids", packageIds);
        // Retain a deterministic display fallback for old vehicles without owner_id.
        Set<Long> withoutOwner = new HashSet<>();
        vehicles.forEach((id, row) -> { if (row[2] == null) withoutOwner.add(id); });
        Map<Long, LinkedHashSet<String>> userNames = new HashMap<>();
        if (!withoutOwner.isEmpty()) entityManager.createQuery("""
                select relation.vehicleId, person.username from VehicleCustomerEntity relation
                join ParkingCustomerEntity person on person.id = relation.customerId
                where relation.vehicleId in :ids and relation.status = 'ACTIVE'
                order by relation.id, person.id
                """, Object[].class).setParameter("ids", withoutOwner).getResultList().forEach(row -> {
                    if (row[1] != null) userNames.computeIfAbsent((Long) row[0], ignored -> new LinkedHashSet<>()).add((String) row[1]);
                });

        List<VehicleSubscriptionResponse> result = rows.stream().map(row -> {
            Object[] vehicle = vehicles.get((Long) row[1]);
            String name = vehicle == null ? null : (String) vehicle[2];
            if (name == null && userNames.containsKey((Long) row[1])) name = String.join(", ", userNames.get((Long) row[1]));
            return (VehicleSubscriptionResponse) new ListingItem((Long) row[0], vehicle == null ? null : (String) vehicle[1],
                    name, lots.get((Long) row[2]), ((Instant) row[4]).atZone(ZoneId.systemDefault()).toLocalDateTime(),
                    packages.get((Long) row[3]), row[5].toString());
        }).toList();
        return new PageImpl<>(result, pageable, total);
    }

    private Predicate[] filters(CriteriaBuilder builder, CriteriaQuery<?> query, Root<VehicleSubscriptionEntity> order,
            String keyword, Long buildingId, Long parkingLotId, LocalDateTime from, LocalDateTime until) {
        List<Predicate> filters = new ArrayList<>();
        if (parkingLotId != null) filters.add(builder.equal(order.get("parkingLotId"), parkingLotId));
        // Preserve the legacy LocalDateTime API's server-zone interpretation. New domain APIs use Instant.
        if (from != null) filters.add(builder.greaterThanOrEqualTo(order.get("endsAt"), from.atZone(ZoneId.systemDefault()).toInstant()));
        if (until != null) filters.add(builder.lessThanOrEqualTo(order.get("endsAt"), until.atZone(ZoneId.systemDefault()).toInstant()));
        if (buildingId != null) {
            var scopedLot = query.subquery(Long.class);
            Root<ParkingLotEntity> lot = scopedLot.from(ParkingLotEntity.class);
            scopedLot.select(lot.get("id")).where(builder.equal(lot.get("id"), order.get("parkingLotId")),
                    builder.equal(lot.get("building").get("id"), buildingId));
            filters.add(builder.exists(scopedLot));
        }
        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            var matchingVehicle = query.subquery(Long.class);
            Root<VehicleEntity> vehicle = matchingVehicle.from(VehicleEntity.class);
            matchingVehicle.select(vehicle.get("id")).where(builder.equal(vehicle.get("id"), order.get("vehicleId")),
                    builder.like(builder.lower(vehicle.get("plateNumber")), pattern, '\\'));
            var matchingOwner = query.subquery(Long.class);
            Root<VehicleEntity> ownedVehicle = matchingOwner.from(VehicleEntity.class);
            Root<OwnerAccountEntity> owner = matchingOwner.from(OwnerAccountEntity.class);
            matchingOwner.select(ownedVehicle.get("id")).where(builder.equal(ownedVehicle.get("id"), order.get("vehicleId")),
                    builder.equal(owner.get("id"), ownedVehicle.get("ownerId")), builder.like(builder.lower(owner.get("fullName")), pattern, '\\'));
            var matchingDriver = query.subquery(Long.class);
            Root<VehicleCustomerEntity> relation = matchingDriver.from(VehicleCustomerEntity.class);
            Root<ParkingCustomerEntity> person = matchingDriver.from(ParkingCustomerEntity.class);
            matchingDriver.select(relation.get("id")).where(builder.equal(relation.get("vehicleId"), order.get("vehicleId")),
                    builder.equal(relation.get("status"), "ACTIVE"), builder.equal(person.get("id"), relation.get("customerId")),
                    builder.like(builder.lower(person.get("username")), pattern, '\\'));
            filters.add(builder.or(builder.exists(matchingVehicle), builder.exists(matchingOwner), builder.exists(matchingDriver)));
        }
        return filters.toArray(Predicate[]::new);
    }

    private Set<Long> ids(List<Object[]> rows, int column) {
        Set<Long> ids = new HashSet<>(); rows.forEach(row -> ids.add((Long) row[column])); return ids;
    }
    private Map<Long, String> labels(String jpql, Set<Long> ids) {
        Map<Long, String> labels = new HashMap<>();
        entityManager.createQuery(jpql, Object[].class).setParameter("ids", ids).getResultList()
                .forEach(row -> labels.put((Long) row[0], (String) row[1]));
        return labels;
    }
    private BusinessException invalid(String message) { return new BusinessException(message, "SUBSCRIPTION_INVALID", 400); }

    private record ListingItem(Long subscriptionId, String plateNumber, String subscriberName, String parkingLotName,
            LocalDateTime expirationDate, String ticketType, String status) implements VehicleSubscriptionResponse {
        public Long getSubscriptionId() { return subscriptionId; }
        public String getPlateNumber() { return plateNumber; }
        public String getSubscriberName() { return subscriberName; }
        public String getParkingLotName() { return parkingLotName; }
        public LocalDateTime getExpirationDate() { return expirationDate; }
        public String getTicketType() { return ticketType; }
        public String getStatus() { return status; }
    }
}
