package vio.barie.core.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.common.FeeCalculationMethod;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.config.exception.ServiceException;
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
import java.time.LocalDateTime;
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

    @Transactional
    public FeePolicyResponse create(FeePolicyApplyRequest request) {
        if (request == null) {
            throw new ServiceException("Cấu hình biểu phí là bắt buộc", 400);
        }
        validateScope(request.getParkingLotId(), request.getVehicleType());
        validateName(request.getName(), 255, "Tên biểu phí");
        List<FeeTimeBandRequest> bands = request.getTimeBands() == null
                ? List.of() : request.getTimeBands();
        validateConfiguration(request.getCalculationMethod(), request.getDailyPriceVnd(),
                request.getRemainderThresholdMinutes(), bands);

        // This lock also serializes two concurrent FIRST publications in the same lot.
        repository.lockParkingLot(request.getParkingLotId())
                .orElseThrow(() -> new ServiceException("Không tìm thấy bãi xe", 404));
        LocalDateTime now = micros(LocalDateTime.now(clock));
        LocalDateTime from = request.getEffectiveFrom() == null ? now : micros(request.getEffectiveFrom());
        if (from.isBefore(now)) {
            throw new ServiceException("Chưa hỗ trợ áp dụng biểu phí hồi tố; mốc hiệu lực không được ở quá khứ", 400);
        }

        List<FeePolicyEntity> history = repository
                .findByParkingLotIdAndVehicleTypeOrderByEffectiveFromAscVersionNoAsc(
                        request.getParkingLotId(), request.getVehicleType());
        validateTimeline(history);
        FeePolicyEntity previous = history.isEmpty() ? null : history.getLast();
        if (previous != null && !from.isAfter(previous.getEffectiveFrom())) {
            throw new ServiceException("Chưa hỗ trợ áp dụng biểu phí hồi tố; mốc hiệu lực không được ở quá khứ", 400);
        }
        if (previous != null && previous.getVersionNo() == Integer.MAX_VALUE) {
            throw new ServiceException("Số phiên bản biểu phí đã đạt giới hạn", 409);
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
            policy.addTimeBand(child);
        }

        try {
            if (previous != null) {
                // PostgreSQL's immediate EXCLUDE must see UPDATE before INSERT.
                repository.flush();
            }
            return toResponse(repository.save(policy));
        } catch (DataIntegrityViolationException ex) {
            // Throwing a runtime domain error preserves rollback of the closure above.
            throw new ServiceException("Không thể áp dụng biểu phí do xung đột dữ liệu; hãy tải lại lịch giá", 409);

        }
    }


    public FeePolicyResponse findAt(Long parkingLotId, VehicleType vehicleType, String at) {
        validateScope(parkingLotId, vehicleType);
        LocalDateTime lookupAt = LocalDateTime.parse(at.trim());
        List<FeePolicyEntity> matches = repository.findEffectiveAt(parkingLotId, vehicleType, lookupAt);
        if (matches.isEmpty()) {
            throw new ServiceException("Không có biểu phí hợp lệ tại thời điểm yêu cầu", 404);
        }
        if (matches.size() != 1) {
            throw new ServiceException("Có nhiều biểu phí chồng hiệu lực tại thời điểm yêu cầu", 409);
        }
        return checkedResponse(matches.getFirst());
    }

    public List<FeePolicyResponse> findHistory(Long parkingLotId, VehicleType vehicleType) {
        validateScope(parkingLotId, vehicleType);
        if (!repository.existsByParkingLotId(parkingLotId)) {
            throw new ServiceException("Không tìm thấy bãi xe", 404);
        }
        List<FeePolicyEntity> history = repository
                .findByParkingLotIdAndVehicleTypeOrderByEffectiveFromAscVersionNoAsc(parkingLotId, vehicleType);
        validateTimeline(history);
        return history.stream().map(this::toResponse).toList();
    }

    public FeePolicyResponse getById(Integer id) {
        if (id == null || id <= 0) {
            throw new ServiceException("ID biểu phí không hợp lệ", 400);
        }
        return checkedResponse(repository.findById(id)
                .orElseThrow(() -> new ServiceException("Không tìm thấy phiên bản biểu phí", 404)));
    }

    private void validateTimeline(List<FeePolicyEntity> history) {
        FeePolicyEntity previous = null;
        int expectedVersion = 1;
        for (FeePolicyEntity policy : history) {
            validatePublished(policy);
            if (policy.getVersionNo() != expectedVersion) {
                throw new ServiceException("Thứ tự phiên bản biểu phí không hợp lệ", 409);
            }
            if (previous != null && (previous.getEffectiveTo() == null
                    || !previous.getEffectiveTo().equals(policy.getEffectiveFrom()))) {
                throw new ServiceException("Lịch giá có khoảng trống hoặc chồng hiệu lực", 409);
            }
            previous = policy;
            expectedVersion++;
        }
        if (previous != null && previous.getEffectiveTo() != null) {
            throw new ServiceException("Phiên bản cuối cùng phải có hiệu lực chưa kết thúc", 409);
        }
    }

    private void validatePublished(FeePolicyEntity policy) {
        if (policy.getEffectiveFrom() == null || policy.getVersionNo() == null || policy.getVersionNo() <= 0
                || (policy.getEffectiveTo() != null && !policy.getEffectiveTo().isAfter(policy.getEffectiveFrom()))) {
            throw new ServiceException("Dữ liệu hiệu lực biểu phí không hợp lệ", 409);
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
            throw new ServiceException("Phương pháp tính phí là bắt buộc", 400);
        }
        if (method == FeeCalculationMethod.DAILY_ENTRY) {
            if (dailyPrice == null || dailyPrice < 0 || threshold != null || !bands.isEmpty()) {
                throw new ServiceException("Phương pháp tính phí là bắt buộc", 400);
            }
            return;
        }
        if (dailyPrice != null || threshold == null || threshold < 0 || bands.isEmpty()) {
            throw new ServiceException("Theo block phải có khung giá, ngưỡng không âm và không có giá lượt/ngày", 400);
        }
        List<ClockRange> ranges = new ArrayList<>();
        long day = ChronoUnit.DAYS.getDuration().toNanos();
        for (FeeTimeBandRequest band : bands) {
            if (band == null) {
                throw new ServiceException("Khung giá không được để trống", 400);
            }
            validateName(band.getName(), 100, "Tên khung giá");
            if (band.getStartTime() == null || band.getEndTime() == null
                    || band.getFirstBlockMinutes() == null || band.getFirstBlockMinutes() <= 0
                    || band.getNextBlockMinutes() == null || band.getNextBlockMinutes() <= 0
                    || band.getFirstBlockPriceVnd() == null || band.getFirstBlockPriceVnd() < 0
                    || band.getNextBlockPriceVnd() == null || band.getNextBlockPriceVnd() < 0
                    || (band.getSortOrder() != null && band.getSortOrder() < 0)) {
                throw new ServiceException("Khung giá phải có giờ hợp lệ, block dương, giá và thứ tự không âm", 400);
            }
            // PostgreSQL TIME stores microseconds; never validate boundaries it would round.
            if (band.getStartTime().getNano() % 1_000 != 0 || band.getEndTime().getNano() % 1_000 != 0) {
                throw new ServiceException("Giờ khung giá chỉ hỗ trợ tối đa 6 chữ số phần thập phân của giây", 400);
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
                throw new ServiceException( "Các khung giá bị chồng hoặc không phủ đủ 24 giờ", 400);
            }
            cursor = range.end();
        }
        if (cursor != day) {
            throw new ServiceException("Các khung giá không phủ đủ 24 giờ", 400);
        }
    }

    private void validateScope(Long lotId, VehicleType vehicleType) {
        if (lotId == null || lotId <= 0 || (vehicleType != VehicleType.CAR && vehicleType != VehicleType.MOTORBIKE)) {
            throw new ServiceException("Bãi xe phải có ID hợp lệ và loại xe phải là CAR hoặc MOTORBIKE", 400);
        }
    }

    private void validateName(String value, int limit, String field) {
        if (value == null || value.isBlank() || value.trim().length() > limit) {
            throw new ServiceException(field + " là bắt buộc và không được vượt quá " + limit + " ký tự", 400);
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

    private LocalDateTime micros(LocalDateTime localDateTime) {
        return localDateTime.truncatedTo(ChronoUnit.MICROS);
    }

    private record ClockRange(long start, long end) { }
}
