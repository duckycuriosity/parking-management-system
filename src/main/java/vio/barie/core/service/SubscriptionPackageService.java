package vio.barie.core.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.subscription.SubscriptionRequests.CreateSubscriptionPackageRequest;
import vio.barie.core.dto.subscription.SubscriptionRequests.SubscriptionPackageSaleRequest;
import vio.barie.core.dto.subscription.SubscriptionRequests.SubscriptionPackageTermsRequest;
import vio.barie.core.dto.subscription.SubscriptionRequests.SubscriptionScheduleRequest;
import vio.barie.core.dto.subscription.SubscriptionResponses.SubscriptionPackageResponse;
import vio.barie.core.dto.subscription.SubscriptionResponses.SubscriptionScheduleResponse;
import vio.barie.core.entity.SubscriptionPackageEntity;
import vio.barie.core.entity.SubscriptionPackageScheduleEntity;
import vio.barie.core.repository.ParkingLotRepository;
import vio.barie.core.repository.SubscriptionPackageRepository;
import vio.barie.core.utils.VehicleType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Owns package writes previously protected by the V54 package/schedule triggers.
 * Writers lock the earliest surviving version of a code, then the target version.
 * Code/version/lot/type are stable, and there is no delete or unpublish operation,
 * so that family anchor cannot move. Purchases only take a shared target-row lock.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubscriptionPackageService {
    private final SubscriptionPackageRepository packages;
    private final ParkingLotRepository parkingLots;
    private final EntityManager entityManager;

    @Transactional
    public SubscriptionPackageResponse createDraft(CreateSubscriptionPackageRequest request) {
        if (request == null) throw new ServiceException("Package request is required", 400);
        String code = text(request.getPackageCode(), "packageCode", 100);
        if (request.getParkingLotId() == null || request.getParkingLotId() <= 0) {
            throw new ServiceException("parkingLotId must be positive", 400);
        }
        if (request.getVehicleType() != VehicleType.CAR && request.getVehicleType() != VehicleType.MOTORBIKE) {
            throw new ServiceException("Only CAR and MOTORBIKE packages are supported", 400);
        }
        validateTerms(request, false);
        if (!parkingLots.existsById(request.getParkingLotId())) {
            throw new ServiceException("Parking lot not found", 404);
        }

        var anchor = packages.findFirstByPackageCodeOrderByVersionNoAscIdAsc(code);
        int version = 1;
        if (anchor.isPresent()) {
            SubscriptionPackageEntity first = anchor.get();
            entityManager.refresh(first);
            if (!Objects.equals(first.getParkingLotId(), request.getParkingLotId())
                    || !Objects.equals(first.getVehicleType(), request.getVehicleType().name())) {
                throw new ServiceException("A package code cannot change parking lot or vehicle type across versions", 409);
            }
            int maximum = packages.findFirstByPackageCodeOrderByVersionNoDescIdDesc(code)
                    .orElseThrow(() -> new ServiceException("Package family changed; retry", 409))
                    .getVersionNo();
            if (maximum == Integer.MAX_VALUE) {
                throw new ServiceException("Package version number is exhausted", 409);
            }
            version = maximum + 1;
        }
        SubscriptionPackageEntity pack = new SubscriptionPackageEntity();
        pack.setPackageCode(code);
        pack.setVersionNo(version);
        pack.setParkingLotId(request.getParkingLotId());
        pack.setVehicleType(request.getVehicleType().name());
        pack.setStatus("DRAFT");
        copyTerms(pack, request);
        try {
            packages.save(pack);
            replaceSchedules(pack, request.getSchedules());
        } catch (DataIntegrityViolationException exception) {
            // An absent family has no row to lock. UNIQUE(code,1) elects its first
            // writer. Abort this transaction; never query in a rollback-only one.
            if (containsConstraint(exception, "uq_subscription_package_code_version")) {
                throw new ServiceException("Another request created this package version; retry with the current family", 409);
            }
            throw exception;
        }
        return response(pack);
    }

    @Transactional
    public SubscriptionPackageResponse updateDraft(Long id, SubscriptionPackageTermsRequest request) {
        validateTerms(request, false);
        SubscriptionPackageEntity pack = locked(id);
        requireDraft(pack);
        copyTerms(pack, request);
        entityManager.createQuery("select s from SubscriptionPackageScheduleEntity s where s.subscriptionPackage.id = :packageId",
                        SubscriptionPackageScheduleEntity.class)
                .setParameter("packageId", pack.getId())
                .getResultList()
                .forEach(entityManager::remove);
        replaceSchedules(pack, request.getSchedules());
        packages.save(pack);
        return response(pack);
    }

    @Transactional
    public SubscriptionPackageResponse publish(Long id) {
        SubscriptionPackageEntity pack = locked(id);
        if ("PUBLISHED".equals(pack.getStatus())) return response(pack);
        requireDraft(pack);
        validateStoredTerms(pack);
        validateSchedules(pack.getCoverageMode(), schedules(pack.getId()).stream()
                .map(s -> new Window(s.getWeekday(), s.getStartTime(), s.getEndTime())).toList(), true);
        requireNoPublishedOverlap(pack, pack.getEffectiveTo());
        pack.setStatus("PUBLISHED");
        packages.save(pack);
        return response(pack);
    }

    @Transactional
    public SubscriptionPackageResponse updateSale(Long id, SubscriptionPackageSaleRequest request) {
        if (request == null || request.getSaleEnabled() == null) {
            throw new ServiceException("saleEnabled is required", 400);
        }
        SubscriptionPackageEntity pack = locked(id);
        if (!"PUBLISHED".equals(pack.getStatus())) {
            throw new ServiceException("Sale availability changes require a published package; edit draft terms instead", 400);
        }
        validateRange(pack.getEffectiveFrom(), request.getEffectiveTo());
        requireNoPublishedOverlap(pack, request.getEffectiveTo());
        pack.setSaleEnabled(request.getSaleEnabled());
        pack.setEffectiveTo(request.getEffectiveTo());
        packages.save(pack);
        return response(pack);
    }

    public SubscriptionPackageResponse get(Long id) {
        if (id == null || id <= 0) throw new ServiceException("packageId must be positive", 400);
        return response(packages.findById(id)
                .orElseThrow(() -> new ServiceException("Subscription package not found", 404)));
    }

    public List<SubscriptionPackageResponse> history(String packageCode) {
        String code = text(packageCode, "packageCode", 100);
        return responses(packages.findByPackageCodeOrderByVersionNoAsc(code));
    }

    public List<SubscriptionPackageResponse> availablePackages(Long parkingLotId, VehicleType vehicleType) {
        if (parkingLotId == null || parkingLotId <= 0) {
            throw new ServiceException("parkingLotId must be positive", 400);
        }
        if (vehicleType != VehicleType.CAR && vehicleType != VehicleType.MOTORBIKE) {
            throw new ServiceException("Only CAR and MOTORBIKE subscription packages are supported", 400);
        }
        if (!parkingLots.existsById(parkingLotId)) {
            throw new ServiceException("Parking lot not found", 404);
        }
        LocalDateTime now = LocalDateTime.now();
        Map<Long, SubscriptionPackageEntity> activePackages = new LinkedHashMap<>();
        packages.findByParkingLotIdAndVehicleTypeAndStatusAndSaleEnabledTrueAndEffectiveFromLessThanEqualAndEffectiveToIsNullOrderByPackageCodeAscVersionNoAsc(
                        parkingLotId, vehicleType.name(), "PUBLISHED", now)
                .forEach(pack -> activePackages.putIfAbsent(pack.getId(), pack));
        packages.findByParkingLotIdAndVehicleTypeAndStatusAndSaleEnabledTrueAndEffectiveFromLessThanEqualAndEffectiveToGreaterThanOrderByPackageCodeAscVersionNoAsc(
                        parkingLotId, vehicleType.name(), "PUBLISHED", now, now)
                .forEach(pack -> activePackages.putIfAbsent(pack.getId(), pack));
        List<SubscriptionPackageEntity> result = activePackages.values().stream()
                .sorted(Comparator.comparing(SubscriptionPackageEntity::getPackageCode)
                        .thenComparing(SubscriptionPackageEntity::getVersionNo)).toList();
        return responses(result);
    }

    private SubscriptionPackageEntity locked(Long id) {
        if (id == null || id <= 0) throw new ServiceException("packageId must be positive", 400);
        // This lightweight lookup reads no schedule graph. Identity is immutable.
        SubscriptionPackageEntity found = packages.findFirstById(id)
                .orElseThrow(() -> new ServiceException("Subscription package not found", 404));
        SubscriptionPackageEntity anchor = packages.findFirstByPackageCodeOrderByVersionNoAscIdAsc(found.getPackageCode())
                .orElseThrow(() -> new ServiceException("Package family no longer exists", 409));
        entityManager.refresh(anchor);
        SubscriptionPackageEntity pack = Objects.equals(id, anchor.getId()) ? anchor
                : packages.findTopById(id)
                        .orElseThrow(() -> new ServiceException("Subscription package not found", 404));
        if (pack != anchor) entityManager.refresh(pack);
        if (!Objects.equals(pack.getParkingLotId(), anchor.getParkingLotId())
                || !Objects.equals(pack.getVehicleType(), anchor.getVehicleType())) {
            throw new ServiceException("A package code cannot change parking lot or vehicle type across versions", 409);
        }
        return pack;
    }

    private void requireNoPublishedOverlap(SubscriptionPackageEntity pack, LocalDateTime until) {
        boolean overlap;
        if (until == null) {
            overlap = packages.countByPackageCodeAndIdNotAndStatusAndEffectiveToIsNull(
                    pack.getPackageCode(), pack.getId(), "PUBLISHED") > 0
                    || packages.countByPackageCodeAndIdNotAndStatusAndEffectiveToGreaterThan(
                    pack.getPackageCode(), pack.getId(), "PUBLISHED",
                    pack.getEffectiveFrom()) > 0;
        } else {
            overlap = packages.countByPackageCodeAndIdNotAndStatusAndEffectiveFromBeforeAndEffectiveToIsNull(
                    pack.getPackageCode(), pack.getId(), "PUBLISHED", until) > 0
                    || packages.countByPackageCodeAndIdNotAndStatusAndEffectiveFromBeforeAndEffectiveToGreaterThan(
                    pack.getPackageCode(), pack.getId(), "PUBLISHED",
                    until, pack.getEffectiveFrom()) > 0;
        }
        if (overlap) {
            throw new ServiceException("Published versions of the same package code cannot overlap sale validity", 409);
        }
    }

    private static void requireDraft(SubscriptionPackageEntity pack) {
        if (!"DRAFT".equals(pack.getStatus())) {
            throw new ServiceException("Published package terms and schedules are immutable; create a new version", 409);
        }
    }

    private static boolean containsConstraint(Throwable exception, String constraint) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(constraint)) return true;
        }
        return false;
    }
    private static final long DAY_NANOS = 86_400_000_000_000L;
    private static final long WEEK_NANOS = 7L * DAY_NANOS;

    private static void validateTerms(SubscriptionPackageTermsRequest request, boolean publishing) {
        if (request == null) throw new ServiceException("Package terms are required", 400);
        text(request.getName(), "name", 255);
        if (request.getPriceVnd() == null || request.getPriceVnd().compareTo(BigDecimal.ZERO) < 0) {
            throw new ServiceException("priceVnd must be non-negative", 400);
        }
        if (request.getDurationDays() == null || request.getDurationDays() <= 0) {
            throw new ServiceException("durationDays must be positive", 400);
        }
        if (request.getCoverageMode() == null) throw new ServiceException("coverageMode is required", 400);
        if (request.getSaleEnabled() == null) throw new ServiceException("saleEnabled is required", 400);
        validateRange(request.getEffectiveFrom(), request.getEffectiveTo());
        if (request.getSchedules() == null) {
            throw new ServiceException("schedules is required; use an empty list for ALL_DAY", 400);
        }
        List<Window> windows = new ArrayList<>();
        for (SubscriptionScheduleRequest schedule : request.getSchedules()) {
            if (schedule == null) throw new ServiceException("Schedule cannot be null", 400);
            windows.add(new Window(schedule.getWeekday(), schedule.getStartTime(), schedule.getEndTime()));
        }
        validateSchedules(request.getCoverageMode(), windows, publishing);
    }

    private static void validateStoredTerms(SubscriptionPackageEntity pack) {
        text(pack.getPackageCode(), "packageCode", 100);
        text(pack.getName(), "name", 255);
        if (pack.getPriceVnd() == null || pack.getPriceVnd().compareTo(BigDecimal.ZERO) < 0) {
            throw new ServiceException("priceVnd must be non-negative", 400);
        }
        if (pack.getDurationDays() == null || pack.getDurationDays() <= 0) {
            throw new ServiceException("durationDays must be positive", 400);
        }
        if (pack.getCoverageMode() == null || pack.getCoverageMode().isBlank()) {
            throw new ServiceException("coverageMode is required", 400);
        }
        if (pack.getVersionNo() == null || pack.getVersionNo() <= 0) {
            throw new ServiceException("versionNo must be positive", 400);
        }
        if (!VehicleType.CAR.name().equals(pack.getVehicleType())
                && !VehicleType.MOTORBIKE.name().equals(pack.getVehicleType())) {
            throw new ServiceException("Only CAR and MOTORBIKE packages are supported", 400);
        }
        validateRange(pack.getEffectiveFrom(), pack.getEffectiveTo());
    }

    private static void validateRange(LocalDateTime from, LocalDateTime until) {
        if (from == null) throw new ServiceException("effectiveFrom is required", 400);
        if (until != null && !from.isBefore(until)) {
            throw new ServiceException("effectiveTo must be after effectiveFrom", 400);
        }
        if (from.getNano() % 1_000 != 0 || (until != null && until.getNano() % 1_000 != 0)) {
            throw new ServiceException("Sale timestamps must use PostgreSQL microsecond precision", 400);
        }
    }

    private static void validateSchedules(String mode, List<Window> windows, boolean publishing) {
        if (mode == null || mode.isBlank()) throw new ServiceException("coverageMode is required", 400);
        if ("ALL_DAY".equals(mode)) {
            if (!windows.isEmpty()) throw new ServiceException("ALL_DAY packages cannot contain weekly schedules", 400);
            return;
        }
        if (!"WEEKLY".equals(mode)) throw new ServiceException("coverageMode is invalid", 400);
        if (publishing && windows.isEmpty()) {
            throw new ServiceException("WEEKLY packages require at least one schedule before publication", 400);
        }
        List<Interval> intervals = new ArrayList<>();
        for (Window window : windows) {
            if (window.weekday == null || window.weekday < 1 || window.weekday > 7) {
                throw new ServiceException("weekday must be 1 (Monday) through 7 (Sunday)", 400);
            }
            if (window.start == null || window.end == null) {
                throw new ServiceException("Schedule startTime and endTime are required", 400);
            }
            if (window.start.getNano() % 1_000 != 0 || window.end.getNano() % 1_000 != 0) {
                throw new ServiceException("Schedule times must use PostgreSQL microsecond precision", 400);
            }
            long day = (window.weekday - 1L) * DAY_NANOS;
            long start = day + window.start.toNanoOfDay();
            long end = day + window.end.toNanoOfDay();
            if (!window.end.isAfter(window.start)) end += DAY_NANOS;
            intervals.add(new Interval(start, Math.min(end, WEEK_NANOS)));
            if (end > WEEK_NANOS) intervals.add(new Interval(0, end - WEEK_NANOS));
        }
        intervals.sort(Comparator.comparingLong(Interval::start).thenComparingLong(Interval::end));
        long previousEnd = -1;
        for (Interval interval : intervals) {
            if (interval.start < previousEnd) {
                throw new ServiceException("Weekly schedules overlap, including the Sunday-to-Monday boundary", 400);
            }
            previousEnd = Math.max(previousEnd, interval.end);
        }
    }

    private static String text(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.trim().length() > maximum) {
            throw new ServiceException(field + " must be non-blank and at most " + maximum + " characters", 400);
        }
        return value.trim();
    }

    private static void copyTerms(SubscriptionPackageEntity pack, SubscriptionPackageTermsRequest request) {
        pack.setName(request.getName().trim());
        pack.setPriceVnd(request.getPriceVnd());
        pack.setDurationDays(request.getDurationDays());
        pack.setCoverageMode(request.getCoverageMode());
        pack.setEffectiveFrom(request.getEffectiveFrom());
        pack.setEffectiveTo(request.getEffectiveTo());
        pack.setSaleEnabled(request.getSaleEnabled());
    }

    private void replaceSchedules(SubscriptionPackageEntity pack, List<SubscriptionScheduleRequest> requests) {
        for (SubscriptionScheduleRequest request : requests) {
            SubscriptionPackageScheduleEntity schedule = new SubscriptionPackageScheduleEntity();
            schedule.setSubscriptionPackage(pack);
            schedule.setWeekday(request.getWeekday());
            schedule.setStartTime(request.getStartTime());
            schedule.setEndTime(request.getEndTime());
            entityManager.persist(schedule);
        }
    }

    private List<SubscriptionPackageResponse> responses(List<SubscriptionPackageEntity> packages) {
        if (packages.isEmpty()) return List.of();
        List<Long> ids = packages.stream().map(SubscriptionPackageEntity::getId).toList();
        Map<Long, List<SubscriptionScheduleResponse>> schedulesByPackage = new LinkedHashMap<>();
        entityManager.createQuery("""
                select s.subscriptionPackage.id, s.weekday, s.startTime, s.endTime
                from SubscriptionPackageScheduleEntity s
                where s.subscriptionPackage.id in :ids
                order by s.subscriptionPackage.id, s.weekday, s.startTime
                """, Object[].class)
                .setParameter("ids", ids)
                .getResultList()
                .forEach(row -> schedulesByPackage.computeIfAbsent((Long) row[0], ignored -> new ArrayList<>())
                        .add(SubscriptionScheduleResponse.builder()
                                .weekday((Integer) row[1])
                                .startTime((LocalTime) row[2])
                                .endTime((LocalTime) row[3])
                                .build()));
        return packages.stream()
                .map(pack -> response(pack, schedulesByPackage.getOrDefault(pack.getId(), List.of())))
                .toList();
    }

    private List<SubscriptionPackageScheduleEntity> schedules(Long packageId) {
        return entityManager.createQuery("""
                select s from SubscriptionPackageScheduleEntity s
                where s.subscriptionPackage.id = :packageId
                order by s.weekday, s.startTime
                """, SubscriptionPackageScheduleEntity.class)
                .setParameter("packageId", packageId)
                .getResultList();
    }

    private SubscriptionPackageResponse response(SubscriptionPackageEntity pack) {
        List<SubscriptionScheduleResponse> schedules = schedules(pack.getId()).stream()
                .map(s -> SubscriptionScheduleResponse.builder().weekday(s.getWeekday())
                        .startTime(s.getStartTime()).endTime(s.getEndTime()).build()).toList();
        return response(pack, schedules);
    }

    private static SubscriptionPackageResponse response(SubscriptionPackageEntity pack,
            List<SubscriptionScheduleResponse> schedules) {
        return SubscriptionPackageResponse.builder().id(pack.getId()).packageCode(pack.getPackageCode()).name(pack.getName())
                .versionNo(pack.getVersionNo()).parkingLotId(pack.getParkingLotId())
                .vehicleType(VehicleType.valueOf(pack.getVehicleType()))
                .priceVnd(pack.getPriceVnd()).durationDays(pack.getDurationDays())
                .coverageMode(pack.getCoverageMode())
                .effectiveFrom(pack.getEffectiveFrom()).effectiveTo(pack.getEffectiveTo()).saleEnabled(pack.isSaleEnabled())
                .status(pack.getStatus()).schedules(schedules).build();
    }

    private record Window(Integer weekday, LocalTime start, LocalTime end) { }
    private record Interval(long start, long end) { }
}
