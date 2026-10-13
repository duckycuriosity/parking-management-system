package vio.barie.core.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.VehicleSubscriptionResponse;
import vio.barie.core.dto.owneraccount.ProfileSaveRequest;
import vio.barie.core.entity.*;
import vio.barie.core.repository.VehicleSubscriptionRepository;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class VehicleSubscriptionService {
    private final VehicleSubscriptionRepository vehicleSubscriptionRepository;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public Page<VehicleSubscriptionResponse> findAll(String keyword, Long buildingId, Long parkingLotId,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate))
            throw new ServiceException("startDate must not exceed endDate", 400);
        String pattern = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        Page<VehicleSubscriptionEntity> page = vehicleSubscriptionRepository.findForListing(pattern, buildingId,
                parkingLotId, localDate(startDate), localDate(endDate), PageRequest.of(pageable.getPageNumber(),
                        Math.min(pageable.getPageSize(), 200), Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        if (page.isEmpty()) return page.map(order -> (VehicleSubscriptionResponse) null);
        List<Long> vehicleIds = page.stream().map(VehicleSubscriptionEntity::getVehicleId).distinct().toList();
        Map<Long, VehicleLabel> vehicles = new HashMap<>();
        entityManager.createQuery("select v.id, v.plateNumber, v.ownerId from VehicleEntity v where v.id in :ids", Object[].class)
                .setParameter("ids", vehicleIds).getResultList().forEach(row ->
                    vehicles.put((Long) row[0], new VehicleLabel((String) row[1], (Long) row[2])));
        Map<Long, String> owners = labels("select o.id, o.fullName from OwnerAccountEntity o where o.id in :ids",
                vehicles.values().stream().map(VehicleLabel::ownerId).filter(Objects::nonNull).distinct().toList());
        Map<Long, String> lots = labels("select p.id, p.name from ParkingLotEntity p where p.id in :ids",
                page.stream().map(VehicleSubscriptionEntity::getParkingLotId).distinct().toList());
        Map<Long, String> packages = labels("select p.id, p.name from SubscriptionPackageEntity p where p.id in :ids",
                page.stream().map(VehicleSubscriptionEntity::getPackageId).distinct().toList());
        Map<Long, LinkedHashSet<String>> drivers = new HashMap<>();
        List<Long> ownerless = vehicles.entrySet().stream().filter(e -> e.getValue().ownerId() == null)
                .map(Map.Entry::getKey).toList();
        if (!ownerless.isEmpty()) entityManager.createQuery("""
                select r.vehicleId, c.username from VehicleCustomerEntity r, ParkingCustomerEntity c
                where r.vehicleId in :ids and r.customerId = c.id and r.status = 'ACTIVE'
                order by c.username
                """, Object[].class).setParameter("ids", ownerless).getResultList().forEach(row -> {
                    if (row[1] != null) drivers.computeIfAbsent((Long) row[0], ignored -> new LinkedHashSet<>()).add((String) row[1]);
                });
        return page.map(order -> {
            VehicleLabel vehicle = vehicles.get(order.getVehicleId());
            String subscriber = vehicle == null ? null : owners.get(vehicle.ownerId());
            if (subscriber == null) subscriber = String.join(", ", drivers.getOrDefault(order.getVehicleId(), new LinkedHashSet<>()));
            return new ListItem(order.getId(), vehicle == null ? null : vehicle.plate(), subscriber,
                    lots.get(order.getParkingLotId()), order.getEndsAt().atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(),
                    packages.get(order.getPackageId()), order.getStatus());
        });
    }

    private static OffsetDateTime localDate(LocalDateTime date) {
        return date == null ? null : date.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private Map<Long, String> labels(String query, List<Long> ids) {
        Map<Long, String> result = new HashMap<>();
        if (!ids.isEmpty()) entityManager.createQuery(query, Object[].class).setParameter("ids", ids)
                .getResultList().forEach(row -> result.put((Long) row[0], (String) row[1]));
        return result;
    }

    private record VehicleLabel(String plate, Long ownerId) {}
    private record ListItem(Long subscriptionId, String plateNumber, String subscriberName, String parkingLotName,
            LocalDateTime expirationDate, String ticketType, String status) implements VehicleSubscriptionResponse {
        public Long getSubscriptionId() { return subscriptionId; }
        public String getPlateNumber() { return plateNumber; }
        public String getSubscriberName() { return subscriberName; }
        public String getParkingLotName() { return parkingLotName; }
        public LocalDateTime getExpirationDate() { return expirationDate; }
        public String getTicketType() { return ticketType; }
        public String getStatus() { return status; }
    }

    @Transactional
    public VehicleSubscriptionEntity register(Long vehicleId, ProfileSaveRequest.Subscription request) {
        VehicleEntity vehicle = entityManager.find(VehicleEntity.class, vehicleId);
        if (vehicle == null) throw new ServiceException("Vehicle not found", 404);
        SubscriptionPackageEntity pack = validatePackage(request, vehicle.getVehicleType());
        OffsetDateTime endsAt;
        try { endsAt = request.getStartsAt().plusSeconds(Math.multiplyExact(pack.getDurationDays().longValue(), 86400L)); }
        catch (RuntimeException ex) { throw new ServiceException("Subscription duration is outside supported range", 400); }

        VehicleSubscriptionEntity order = new VehicleSubscriptionEntity();
        order.setVehicleId(vehicleId);
        order.setParkingLotId(request.getParkingLotId());
        order.setPackageId(pack.getId());
        order.setPreviousSubscriptionId(request.getPreviousSubscriptionId());
        order.setRegistrationType(request.getPreviousSubscriptionId() == null ? "NEW" : "RENEW");
        order.setPriceVnd(pack.getPriceVnd());
        order.setStartsAt(request.getStartsAt());
        order.setEndsAt(endsAt);
        order.setPaymentDeadlineAt(request.getPaymentDeadlineAt());
        order.setStatus("PENDING_PAYMENT");
        return vehicleSubscriptionRepository.save(order);
    }

    private SubscriptionPackageEntity validatePackage(ProfileSaveRequest.Subscription request, String vehicleType) {
        if (request == null || request.getParkingLotId() == null || request.getParkingLotId() <= 0
                || request.getPackageId() == null || request.getPackageId() <= 0
                || request.getStartsAt() == null || request.getPaymentDeadlineAt() == null)
            throw new ServiceException("Package, parking lot, startsAt and paymentDeadlineAt are required", 400);
        OffsetDateTime now = OffsetDateTime.now();
        if (!request.getPaymentDeadlineAt().isAfter(now))
            throw new ServiceException("Payment deadline must be in the future", 400);
        SubscriptionPackageEntity pack = entityManager.find(SubscriptionPackageEntity.class, request.getPackageId());
        if (pack == null) throw new ServiceException("Subscription package not found", 404);
        if (!Objects.equals(pack.getParkingLotId(), request.getParkingLotId())
                || !Objects.equals(pack.getVehicleType(), vehicleType))
            throw new ServiceException("Package does not match the parking lot or vehicle type", 400);
        if (!"PUBLISHED".equals(pack.getStatus()) || !pack.isSaleEnabled()
                || pack.getEffectiveFrom() == null || now.isBefore(pack.getEffectiveFrom())
                || (pack.getEffectiveTo() != null && !now.isBefore(pack.getEffectiveTo())))
            throw new ServiceException("Package is not available for sale", 409);
        if (pack.getPriceVnd() == null || pack.getPriceVnd() < 0
                || pack.getDurationDays() == null || pack.getDurationDays() <= 0)
            throw new ServiceException("Package terms are invalid", 409);
        return pack;
    }
}
