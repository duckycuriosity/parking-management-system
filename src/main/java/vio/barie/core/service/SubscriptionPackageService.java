package vio.barie.core.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.common.SubscriptionCoverageMode;
import vio.barie.core.common.SubscriptionPackageStatus;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.subscription.CreateSubscriptionPackageRequest;
import vio.barie.core.dto.subscription.SubscriptionPackageResponse;
import vio.barie.core.dto.subscription.SubscriptionPackageSaleRequest;
import vio.barie.core.dto.subscription.SubscriptionPackageTermsRequest;
import vio.barie.core.dto.subscription.SubscriptionScheduleRequest;
import vio.barie.core.dto.subscription.SubscriptionScheduleResponse;
import vio.barie.core.entity.SubscriptionPackageEntity;
import vio.barie.core.entity.SubscriptionPackageScheduleEntity;
import vio.barie.core.repository.SubscriptionPackageRepository;
import vio.barie.core.utils.VehicleType;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private static final long DAY_NANOS = 86_400_000_000_000L;
    private static final long WEEK_NANOS = 7L * DAY_NANOS;
    private final SubscriptionPackageRepository packages;
    private final EntityManager entityManager;

    @Transactional
    public SubscriptionPackageResponse createDraft(CreateSubscriptionPackageRequest request) {
        require(request != null, "Package request is required");
        String code = text(request.getPackageCode(), "packageCode", 100);
        require(request.getParkingLotId() != null && request.getParkingLotId() > 0, "parkingLotId must be positive");
        require(request.getVehicleType() == VehicleType.CAR || request.getVehicleType() == VehicleType.MOTORBIKE,
                "Only CAR and MOTORBIKE packages are supported");
        validateTerms(request, false);
        if (!packages.parkingLotExists(request.getParkingLotId())) throw notFound("Parking lot not found");

        var anchor = packages.findFirstByPackageCodeOrderByVersionNoAscIdAsc(code);
        int version = 1;
        if (anchor.isPresent()) {
            SubscriptionPackageEntity first = anchor.get();
            entityManager.refresh(first);
            requireScope(first, request.getParkingLotId(), request.getVehicleType());
            int maximum = packages.maximumVersion(code).orElseThrow(() -> conflict("Package family changed; retry"));
            if (maximum == Integer.MAX_VALUE) throw conflict("Package version number is exhausted");
            version = maximum + 1;
        }
        SubscriptionPackageEntity pack = new SubscriptionPackageEntity();
        pack.setPackageCode(code);
        pack.setVersionNo(version);
        pack.setParkingLotId(request.getParkingLotId());
        pack.setVehicleType(request.getVehicleType());
        pack.setStatus(SubscriptionPackageStatus.DRAFT);
        copyTerms(pack, request);
        replaceSchedules(pack, request.getSchedules());
        try {
            packages.saveAndFlush(pack);
        } catch (DataIntegrityViolationException exception) {
            // An absent family has no row to lock. UNIQUE(code,1) elects its first
            // writer. Abort this transaction; never query in a rollback-only one.
            if (containsConstraint(exception, "uq_subscription_package_code_version")) {
                throw conflict("Another request created this package version; retry with the current family");
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
        // Flush removal first: replacing the same logical window must not hit
        // the structural UNIQUE(package,weekday,start,end) on insertion order.
        pack.getSchedules().clear();
        entityManager.flush();
        replaceSchedules(pack, request.getSchedules());
        entityManager.flush();
        return response(pack);
    }

    @Transactional
    public SubscriptionPackageResponse publish(Long id) {
        SubscriptionPackageEntity pack = locked(id);
        if (pack.getStatus() == SubscriptionPackageStatus.PUBLISHED) return response(pack);
        requireDraft(pack);
        validateStoredTerms(pack);
        validateSchedules(pack.getCoverageMode(), pack.getSchedules().stream()
                .map(s -> new Window(s.getWeekday(), s.getStartTime(), s.getEndTime())).toList(), true);
        requireNoPublishedOverlap(pack, pack.getEffectiveTo());
        // Every schedule is already persisted while its parent is DRAFT.
        entityManager.flush();
        pack.setStatus(SubscriptionPackageStatus.PUBLISHED);
        entityManager.flush();
        return response(pack);
    }

    @Transactional
    public SubscriptionPackageResponse updateSale(Long id, SubscriptionPackageSaleRequest request) {
        require(request != null && request.getSaleEnabled() != null, "saleEnabled is required");
        SubscriptionPackageEntity pack = locked(id);
        require(pack.getStatus() == SubscriptionPackageStatus.PUBLISHED,
                "Sale availability changes require a published package; edit draft terms instead");
        validateRange(pack.getEffectiveFrom(), request.getEffectiveTo());
        requireNoPublishedOverlap(pack, request.getEffectiveTo());
        pack.setSaleEnabled(request.getSaleEnabled());
        pack.setEffectiveTo(request.getEffectiveTo());
        entityManager.flush();
        return response(pack);
    }

    public SubscriptionPackageResponse get(Long id) {
        positive(id);
        return response(packages.findWithSchedules(id).orElseThrow(() -> notFound("Subscription package not found")));
    }

    public List<SubscriptionPackageResponse> history(String packageCode) {
        String code = text(packageCode, "packageCode", 100);
        return packages.findByPackageCodeOrderByVersionNoAsc(code).stream().map(SubscriptionPackageService::response).toList();
    }

    private SubscriptionPackageEntity locked(Long id) {
        positive(id);
        // This lightweight lookup reads no schedule graph. Identity is immutable.
        SubscriptionPackageEntity found = packages.findById(id)
                .orElseThrow(() -> notFound("Subscription package not found"));
        SubscriptionPackageEntity anchor = packages.findFirstByPackageCodeOrderByVersionNoAscIdAsc(found.getPackageCode())
                .orElseThrow(() -> conflict("Package family no longer exists"));
        entityManager.refresh(anchor);
        SubscriptionPackageEntity pack = Objects.equals(id, anchor.getId()) ? anchor
                : packages.lockForManagement(id).orElseThrow(() -> notFound("Subscription package not found"));
        if (pack != anchor) entityManager.refresh(pack);
        requireScope(pack, anchor.getParkingLotId(), anchor.getVehicleType());
        return pack;
    }

    private void requireNoPublishedOverlap(SubscriptionPackageEntity pack, Instant until) {
        if (packages.publishedOverlap(pack.getPackageCode(), pack.getId(), pack.getEffectiveFrom(), until)) {
            throw conflict("Published versions of the same package code cannot overlap sale validity");
        }
    }

    private static void requireScope(SubscriptionPackageEntity pack, Long lot, VehicleType type) {
        if (!Objects.equals(pack.getParkingLotId(), lot) || pack.getVehicleType() != type) {
            throw conflict("A package code cannot change parking lot or vehicle type across versions");
        }
    }

    private static void requireDraft(SubscriptionPackageEntity pack) {
        if (pack.getStatus() != SubscriptionPackageStatus.DRAFT) {
            throw conflict("Published package terms and schedules are immutable; create a new version");
        }
    }

    private static void validateTerms(SubscriptionPackageTermsRequest request, boolean publishing) {
        require(request != null, "Package terms are required");
        text(request.getName(), "name", 255);
        require(request.getPriceVnd() != null && request.getPriceVnd() >= 0, "priceVnd must be non-negative");
        require(request.getDurationDays() != null && request.getDurationDays() > 0, "durationDays must be positive");
        require(request.getCoverageMode() != null, "coverageMode is required");
        require(request.getSaleEnabled() != null, "saleEnabled is required");
        validateRange(request.getEffectiveFrom(), request.getEffectiveTo());
        require(request.getSchedules() != null, "schedules is required; use an empty list for ALL_DAY");
        List<Window> windows = new ArrayList<>();
        for (SubscriptionScheduleRequest schedule : request.getSchedules()) {
            require(schedule != null, "Schedule cannot be null");
            windows.add(new Window(schedule.getWeekday(), schedule.getStartTime(), schedule.getEndTime()));
        }
        validateSchedules(request.getCoverageMode(), windows, publishing);
    }

    private static void validateStoredTerms(SubscriptionPackageEntity pack) {
        text(pack.getPackageCode(), "packageCode", 100);
        text(pack.getName(), "name", 255);
        require(pack.getPriceVnd() != null && pack.getPriceVnd() >= 0, "priceVnd must be non-negative");
        require(pack.getDurationDays() != null && pack.getDurationDays() > 0, "durationDays must be positive");
        require(pack.getCoverageMode() != null, "coverageMode is required");
        require(pack.getVersionNo() != null && pack.getVersionNo() > 0, "versionNo must be positive");
        require(pack.getVehicleType() == VehicleType.CAR || pack.getVehicleType() == VehicleType.MOTORBIKE,
                "Only CAR and MOTORBIKE packages are supported");
        validateRange(pack.getEffectiveFrom(), pack.getEffectiveTo());
    }

    private static void validateRange(Instant from, Instant until) {
        require(from != null, "effectiveFrom is required");
        require(until == null || from.isBefore(until), "effectiveTo must be after effectiveFrom");
        require(from.getNano() % 1_000 == 0 && (until == null || until.getNano() % 1_000 == 0),
                "Sale timestamps must use PostgreSQL microsecond precision");
    }

    private static void validateSchedules(SubscriptionCoverageMode mode, List<Window> windows, boolean publishing) {
        require(mode != null, "coverageMode is required");
        if (mode == SubscriptionCoverageMode.ALL_DAY) {
            require(windows.isEmpty(), "ALL_DAY packages cannot contain weekly schedules");
            return;
        }
        require(!publishing || !windows.isEmpty(), "WEEKLY packages require at least one schedule before publication");
        List<Interval> intervals = new ArrayList<>();
        for (Window window : windows) {
            require(window.weekday != null && window.weekday >= 1 && window.weekday <= 7,
                    "weekday must be 1 (Monday) through 7 (Sunday)");
            require(window.start != null && window.end != null, "Schedule startTime and endTime are required");
            require(window.start.getNano() % 1_000 == 0 && window.end.getNano() % 1_000 == 0,
                    "Schedule times must use PostgreSQL microsecond precision");
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
            require(interval.start >= previousEnd, "Weekly schedules overlap, including the Sunday-to-Monday boundary");
            previousEnd = Math.max(previousEnd, interval.end);
        }
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

    private static void replaceSchedules(SubscriptionPackageEntity pack, List<SubscriptionScheduleRequest> requests) {
        for (SubscriptionScheduleRequest request : requests) {
            SubscriptionPackageScheduleEntity schedule = new SubscriptionPackageScheduleEntity();
            schedule.setWeekday(request.getWeekday());
            schedule.setStartTime(request.getStartTime());
            schedule.setEndTime(request.getEndTime());
            pack.addSchedule(schedule);
        }
    }

    private static SubscriptionPackageResponse response(SubscriptionPackageEntity pack) {
        return SubscriptionPackageResponse.builder().id(pack.getId()).packageCode(pack.getPackageCode()).name(pack.getName())
                .versionNo(pack.getVersionNo()).parkingLotId(pack.getParkingLotId()).vehicleType(pack.getVehicleType())
                .priceVnd(pack.getPriceVnd()).durationDays(pack.getDurationDays()).coverageMode(pack.getCoverageMode())
                .effectiveFrom(pack.getEffectiveFrom()).effectiveTo(pack.getEffectiveTo()).saleEnabled(pack.isSaleEnabled())
                .status(pack.getStatus()).schedules(pack.getSchedules().stream()
                        .sorted(Comparator.comparing(SubscriptionPackageScheduleEntity::getWeekday)
                                .thenComparing(SubscriptionPackageScheduleEntity::getStartTime))
                        .map(s -> SubscriptionScheduleResponse.builder().weekday(s.getWeekday())
                                .startTime(s.getStartTime()).endTime(s.getEndTime()).build()).toList()).build();
    }

    private static String text(String value, String field, int maximum) {
        require(value != null && !value.isBlank() && value.trim().length() <= maximum,
                field + " must be non-blank and at most " + maximum + " characters");
        return value.trim();
    }

    private static void positive(Long id) { require(id != null && id > 0, "packageId must be positive"); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new BusinessException(message, "VALIDATION_ERROR", 400);
    }
    private static BusinessException conflict(String message) { return new BusinessException(message, "CONFLICT", 409); }
    private static BusinessException notFound(String message) { return new BusinessException(message, "NOT_FOUND", 404); }
    private static boolean containsConstraint(Throwable exception, String constraint) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(constraint)) return true;
        }
        return false;
    }
    private record Window(Integer weekday, LocalTime start, LocalTime end) { }
    private record Interval(long start, long end) { }
}
