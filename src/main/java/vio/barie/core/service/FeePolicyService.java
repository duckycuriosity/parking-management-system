package vio.barie.core.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.common.FeeCalculationMethod;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.fee.FeePolicyApplyRequest;
import vio.barie.core.dto.fee.FeePolicyResponse;
import vio.barie.core.dto.fee.FeeTimeBandRequest;
import vio.barie.core.dto.fee.FeeTimeBandResponse;
import vio.barie.core.entity.FeePolicyEntity;
import vio.barie.core.entity.FeeTimeBandEntity;
import vio.barie.core.repository.FeePolicyRepository;
import vio.barie.core.utils.VehicleType;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Publishes complete tariff versions and resolves historical prices.
 * Drafts live on the frontend. This service never calculates or collects money.
 */
@Service
@Transactional(readOnly = true)
public class FeePolicyService {

    private final FeePolicyRepository repository;
    private final Clock clock;

    public FeePolicyService(FeePolicyRepository repository,
                            @Qualifier("feePolicyClock") Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Append a version, closing only the predecessor's effective interval. */
    @Transactional
    public FeePolicyResponse create(FeePolicyApplyRequest request) {
        if (request == null) {
            throw invalid("Cấu hình biểu phí là bắt buộc");
        }
        validateScope(request.getParkingLotId(), request.getVehicleType());
        validateName(request.getName(), 255, "Tên biểu phí");
        List<FeeTimeBandRequest> bands = request.getTimeBands() == null
                ? List.of() : request.getTimeBands();
        validateConfiguration(request.getCalculationMethod(), request.getDailyPriceVnd(),
                request.getRemainderThresholdMinutes(), bands);

        // This lock also serializes two concurrent FIRST publications in the same lot.
        repository.lockParkingLot(request.getParkingLotId())
                .orElseThrow(() -> error("PARKING_LOT_NOT_FOUND", "Không tìm thấy bãi xe", 404));
        Instant now = micros(clock.instant());
        Instant from = request.getEffectiveFrom() == null ? now : micros(request.getEffectiveFrom());
        if (from.isBefore(now)) {
            throw invalid("Chưa hỗ trợ áp dụng biểu phí hồi tố; mốc hiệu lực không được ở quá khứ");
        }

        List<FeePolicyEntity> history = repository
                .findByParkingLotIdAndVehicleTypeOrderByEffectiveFromAscVersionNoAsc(
                        request.getParkingLotId(), request.getVehicleType());
        validateTimeline(history);
        FeePolicyEntity previous = history.isEmpty() ? null : history.getLast();
        if (previous != null && !from.isAfter(previous.getEffectiveFrom())) {
            throw error("FEE_POLICY_EFFECTIVE_TIME_CONFLICT",
                    "Phiên bản mới phải bắt đầu sau phiên bản cuối cùng, kể cả bản đã lên lịch", 409);
        }
        if (previous != null && previous.getVersionNo() == Integer.MAX_VALUE) {
            throw error("FEE_POLICY_VERSION_LIMIT", "Số phiên bản biểu phí đã đạt giới hạn", 409);
        }

        FeePolicyEntity policy = new FeePolicyEntity();
        policy.setParkingLotId(request.getParkingLotId());
        policy.setVehicleType(request.getVehicleType());
        policy.setName(request.getName().trim());
        policy.setVersionNo(previous == null ? 1 : previous.getVersionNo() + 1);
        policy.setEffectiveFrom(from);
        policy.setEffectiveTo(null);
        policy.setCalculationMethod(request.getCalculationMethod());
        policy.setDailyPriceVnd(request.getDailyPriceVnd());
        policy.setRemainderThresholdMinutes(request.getRemainderThresholdMinutes());
        // Reserved column only: never copy, parse or apply algorithm choices from JSON.
        policy.setPricingRules(new LinkedHashMap<>());
        policy.setCreatedAt(now);
        policy.setUpdatedAt(now);
        for (FeeTimeBandRequest band : bands) {
            FeeTimeBandEntity child = new FeeTimeBandEntity();
            child.setName(band.getName().trim());
            child.setSortOrder(band.getSortOrder() == null ? 0 : band.getSortOrder());
            child.setStartTime(band.getStartTime());
            child.setEndTime(band.getEndTime());
            child.setFirstBlockMinutes(band.getFirstBlockMinutes());
            child.setFirstBlockPriceVnd(band.getFirstBlockPriceVnd());
            child.setNextBlockMinutes(band.getNextBlockMinutes());
            child.setNextBlockPriceVnd(band.getNextBlockPriceVnd());
            child.setCreatedAt(now);
            child.setUpdatedAt(now);
            policy.addTimeBand(child);
        }

        try {
            if (previous != null) {
                previous.setEffectiveTo(from);
                previous.setUpdatedAt(now);
                // PostgreSQL's immediate EXCLUDE must see UPDATE before INSERT.
                repository.flush();
            }
            return toResponse(repository.saveAndFlush(policy));
        } catch (DataIntegrityViolationException ex) {
            // Throwing a runtime domain error preserves rollback of the closure above.
            throw error("FEE_POLICY_PUBLISH_CONFLICT",
                    "Không thể áp dụng biểu phí do xung đột dữ liệu; hãy tải lại lịch giá", 409);
        }
    }

    public FeePolicyResponse findCurrent(Long parkingLotId, VehicleType vehicleType) {
        return findAt(parkingLotId, vehicleType, clock.instant());
    }

    public FeePolicyResponse findAt(Long parkingLotId, VehicleType vehicleType, Instant at) {
        validateScope(parkingLotId, vehicleType);
        Instant lookupAt = at == null ? clock.instant() : at;
        List<FeePolicyEntity> matches = repository.findEffectiveAt(parkingLotId, vehicleType, micros(lookupAt));
        if (matches.isEmpty()) {
            throw error("FEE_POLICY_NOT_FOUND", "Không có biểu phí hợp lệ tại thời điểm yêu cầu", 404);
        }
        if (matches.size() != 1) {
            throw error("FEE_POLICY_OVERLAP", "Có nhiều biểu phí chồng hiệu lực tại thời điểm yêu cầu", 409);
        }
        return checkedResponse(matches.getFirst());
    }

    /** Visitor sessions keep this complete entry-time version even after its effectiveTo. */
    public FeePolicyResponse findForVisitor(Long parkingLotId, VehicleType vehicleType, Instant enteredAt) {
        return findAt(parkingLotId, vehicleType, enteredAt);
    }

    /** Returns source versions for outside-subscription pricing; does not calculate fees. */
    public List<FeePolicyResponse> findOverlapping(Long parkingLotId, VehicleType vehicleType,
                                                 Instant from, Instant until) {
        validateScope(parkingLotId, vehicleType);
        if (from == null || until == null || !micros(from).isBefore(micros(until))) {
            throw invalid("Khoảng tra cứu phải có thời điểm bắt đầu nhỏ hơn kết thúc");
        }
        Instant start = micros(from);
        Instant end = micros(until);
        List<FeePolicyEntity> policies = repository.findOverlapping(parkingLotId, vehicleType, start, end);
        Instant cursor = start;
        boolean first = true;
        List<FeePolicyResponse> result = new ArrayList<>();
        for (FeePolicyEntity policy : policies) {
            validatePublished(policy);
            if (policy.getEffectiveFrom().isAfter(cursor)) {
                throw error("FEE_POLICY_GAP", "Lịch biểu phí thiếu khoảng hiệu lực cần tính", 409);
            }
            if (!first && policy.getEffectiveFrom().isBefore(cursor)) {
                throw error("FEE_POLICY_OVERLAP", "Lịch biểu phí có khoảng hiệu lực chồng nhau", 409);
            }
            result.add(toResponse(policy));
            cursor = policy.getEffectiveTo() == null || policy.getEffectiveTo().isAfter(end)
                    ? end : policy.getEffectiveTo();
            first = false;
        }
        if (cursor.isBefore(end)) {
            throw error("FEE_POLICY_GAP", "Không có biểu phí phủ đủ khoảng thời gian yêu cầu", 409);
        }
        return List.copyOf(result);
    }

    public List<FeePolicyResponse> findHistory(Long parkingLotId, VehicleType vehicleType) {
        validateScope(parkingLotId, vehicleType);
        if (!repository.parkingLotExists(parkingLotId)) {
            throw error("PARKING_LOT_NOT_FOUND", "Không tìm thấy bãi xe", 404);
        }
        List<FeePolicyEntity> history = repository
                .findByParkingLotIdAndVehicleTypeOrderByEffectiveFromAscVersionNoAsc(parkingLotId, vehicleType);
        validateTimeline(history);
        return history.stream().map(this::toResponse).toList();
    }

    public FeePolicyResponse getById(Integer id) {
        if (id == null || id <= 0) {
            throw invalid("ID biểu phí không hợp lệ");
        }
        return checkedResponse(repository.findById(id)
                .orElseThrow(() -> error("FEE_POLICY_NOT_FOUND", "Không tìm thấy phiên bản biểu phí", 404)));
    }

    private void validateTimeline(List<FeePolicyEntity> history) {
        FeePolicyEntity previous = null;
        int expectedVersion = 1;
        for (FeePolicyEntity policy : history) {
            validatePublished(policy);
            if (policy.getVersionNo() != expectedVersion) {
                throw error("FEE_POLICY_TIMELINE_INVALID", "Thứ tự phiên bản biểu phí không hợp lệ", 409);
            }
            if (previous != null && (previous.getEffectiveTo() == null
                    || !previous.getEffectiveTo().equals(policy.getEffectiveFrom()))) {
                throw error("FEE_POLICY_TIMELINE_INVALID", "Lịch giá có khoảng trống hoặc chồng hiệu lực", 409);
            }
            previous = policy;
            expectedVersion++;
        }
        if (previous != null && previous.getEffectiveTo() != null) {
            throw error("FEE_POLICY_TIMELINE_INVALID", "Phiên bản cuối cùng phải có hiệu lực chưa kết thúc", 409);
        }
    }

    private void validatePublished(FeePolicyEntity policy) {
        if (policy.getEffectiveFrom() == null || policy.getVersionNo() == null || policy.getVersionNo() <= 0
                || (policy.getEffectiveTo() != null && !policy.getEffectiveTo().isAfter(policy.getEffectiveFrom()))) {
            throw error("FEE_POLICY_TIMELINE_INVALID", "Dữ liệu hiệu lực biểu phí không hợp lệ", 409);
        }
        List<FeeTimeBandRequest> bands = policy.getTimeBands().stream().map(band -> {
            FeeTimeBandRequest input = new FeeTimeBandRequest();
            input.setName(band.getName());
            input.setSortOrder(band.getSortOrder());
            input.setStartTime(band.getStartTime());
            input.setEndTime(band.getEndTime());
            input.setFirstBlockMinutes(band.getFirstBlockMinutes());
            input.setFirstBlockPriceVnd(band.getFirstBlockPriceVnd());
            input.setNextBlockMinutes(band.getNextBlockMinutes());
            input.setNextBlockPriceVnd(band.getNextBlockPriceVnd());
            return input;
        }).toList();
        validateConfiguration(policy.getCalculationMethod(), policy.getDailyPriceVnd(),
                policy.getRemainderThresholdMinutes(), bands);
    }

    private void validateConfiguration(FeeCalculationMethod method, Long dailyPrice,
                                       Integer threshold, List<FeeTimeBandRequest> bands) {
        if (method == null) {
            throw invalid("Phương pháp tính phí là bắt buộc");
        }
        if (method == FeeCalculationMethod.DAILY_ENTRY) {
            if (dailyPrice == null || dailyPrice < 0 || threshold != null || !bands.isEmpty()) {
                throw invalid("Theo lượt/ngày phải có giá không âm và không có khung giá hoặc ngưỡng phút lẻ");
            }
            return;
        }
        if (dailyPrice != null || threshold == null || threshold < 0 || bands.isEmpty()) {
            throw invalid("Theo block phải có khung giá, ngưỡng không âm và không có giá lượt/ngày");
        }
        List<ClockRange> ranges = new ArrayList<>();
        long day = ChronoUnit.DAYS.getDuration().toNanos();
        for (FeeTimeBandRequest band : bands) {
            if (band == null) {
                throw invalid("Khung giá không được để trống");
            }
            validateName(band.getName(), 100, "Tên khung giá");
            if (band.getStartTime() == null || band.getEndTime() == null
                    || band.getFirstBlockMinutes() == null || band.getFirstBlockMinutes() <= 0
                    || band.getNextBlockMinutes() == null || band.getNextBlockMinutes() <= 0
                    || band.getFirstBlockPriceVnd() == null || band.getFirstBlockPriceVnd() < 0
                    || band.getNextBlockPriceVnd() == null || band.getNextBlockPriceVnd() < 0
                    || (band.getSortOrder() != null && band.getSortOrder() < 0)) {
                throw invalid("Khung giá phải có giờ hợp lệ, block dương, giá và thứ tự không âm");
            }
            // PostgreSQL TIME stores microseconds; never validate boundaries it would round.
            if (band.getStartTime().getNano() % 1_000 != 0 || band.getEndTime().getNano() % 1_000 != 0) {
                throw invalid("Giờ khung giá chỉ hỗ trợ tối đa 6 chữ số phần thập phân của giây");
            }
            long start = band.getStartTime().toNanoOfDay();
            long end = band.getEndTime().toNanoOfDay();
            if (start == end) {
                ranges.add(new ClockRange(0, day));
            } else if (end > start) {
                ranges.add(new ClockRange(start, end));
            } else {
                ranges.add(new ClockRange(start, day));
                if (end > 0) {
                    ranges.add(new ClockRange(0, end));
                }
            }
        }
        ranges.sort(Comparator.comparingLong(ClockRange::start));
        long cursor = 0;
        for (ClockRange range : ranges) {
            if (range.start() != cursor) {
                throw error("INVALID_TIME_BANDS", "Các khung giá bị chồng hoặc không phủ đủ 24 giờ", 400);
            }
            cursor = range.end();
        }
        if (cursor != day) {
            throw error("INVALID_TIME_BANDS", "Các khung giá không phủ đủ 24 giờ", 400);
        }
    }

    private void validateScope(Long lotId, VehicleType vehicleType) {
        if (lotId == null || lotId <= 0 || (vehicleType != VehicleType.CAR && vehicleType != VehicleType.MOTORBIKE)) {
            throw invalid("Bãi xe phải có ID hợp lệ và loại xe phải là CAR hoặc MOTORBIKE");
        }
    }

    private void validateName(String value, int limit, String field) {
        if (value == null || value.isBlank() || value.trim().length() > limit) {
            throw invalid(field + " là bắt buộc và không được vượt quá " + limit + " ký tự");
        }
    }

    private FeePolicyResponse checkedResponse(FeePolicyEntity policy) {
        validatePublished(policy);
        return toResponse(policy);
    }

    private FeePolicyResponse toResponse(FeePolicyEntity policy) {
        List<FeeTimeBandResponse> bands = policy.getTimeBands().stream()
                .sorted(Comparator.comparing(FeeTimeBandEntity::getSortOrder)
                        .thenComparing(FeeTimeBandEntity::getStartTime))
                .map(band -> FeeTimeBandResponse.builder()
                        .id(band.getId()).name(band.getName()).sortOrder(band.getSortOrder())
                        .startTime(band.getStartTime()).endTime(band.getEndTime())
                        .firstBlockMinutes(band.getFirstBlockMinutes()).firstBlockPriceVnd(band.getFirstBlockPriceVnd())
                        .nextBlockMinutes(band.getNextBlockMinutes()).nextBlockPriceVnd(band.getNextBlockPriceVnd())
                        .createdAt(band.getCreatedAt()).updatedAt(band.getUpdatedAt()).build())
                .toList();
        return FeePolicyResponse.builder()
                .id(policy.getId()).parkingLotId(policy.getParkingLotId()).vehicleType(policy.getVehicleType())
                .name(policy.getName()).versionNo(policy.getVersionNo())
                .effectiveFrom(policy.getEffectiveFrom()).effectiveTo(policy.getEffectiveTo())
                .calculationMethod(policy.getCalculationMethod()).dailyPriceVnd(policy.getDailyPriceVnd())
                .remainderThresholdMinutes(policy.getRemainderThresholdMinutes()).timeBands(bands)
                .createdAt(policy.getCreatedAt()).updatedAt(policy.getUpdatedAt()).build();
    }

    private Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private BusinessException invalid(String message) {
        return error("INVALID_FEE_POLICY", message, 400);
    }

    private BusinessException error(String code, String message, int status) {
        return new BusinessException(message, code, status);
    }

    private record ClockRange(long start, long end) { }
}
