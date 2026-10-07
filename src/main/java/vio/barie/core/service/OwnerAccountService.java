package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.OwnerAccountPaginationRequest;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.OwnerAccountRequest;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.OwnerAccountResponse;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.RegisteredVehicleResponse;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.SubscriptionRegistrantPageRequest;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.SubscriptionRegistrantPageResponse;
import vio.barie.core.entity.OwnerAccountEntity;
import vio.barie.core.repository.OwnerAccountRepository;
import vio.barie.core.repository.OwnerAccountSpecification;
import vio.barie.core.repository.VehicleRepository;


import java.util.List;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Service
@RequiredArgsConstructor
public class OwnerAccountService {

    private final OwnerAccountRepository ownerAccountRepository;
    private final VehicleRepository vehicleRepository;

    @Transactional(readOnly = true)
    public OwnerAccountResponse getById(Long id) {
        return toResponse(findEntity(id));
    }

    @Transactional(readOnly = true)
    public Page<OwnerAccountResponse> list(OwnerAccountPaginationRequest request) {
        LocalDate fromDate = parseDate(request.getFromDate(), "fromDate");
        LocalDate toDate = parseDate(request.getToDate(), "toDate");
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new ServiceException("fromDate must be before or equal to toDate", 400);
        }

        int page = Math.max(request.getPage(), 0);
        int size = request.getSize() <= 0 ? 20 : Math.min(request.getSize(), 100);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        return ownerAccountRepository.findAll(
                OwnerAccountSpecification.filter(
                        request.getSearchTerm(),
                        request.getStatus(),
                        fromDate,
                        toDate
                ),
                pageable
        ).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<SubscriptionRegistrantPageResponse> listSubscriptionRegistrants(SubscriptionRegistrantPageRequest request) {
        OffsetDateTime fromDate = parseStartDate(request.getFromDate(), "fromDate");
        OffsetDateTime toDate = parseEndDate(request.getToDate(), "toDate");
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new ServiceException("fromDate must be before or equal to toDate", 400);
        }

        int page = Math.max(request.getPage(), 0);
        int size = request.getSize() <= 0 ? 20 : Math.min(request.getSize(), 100);
        Pageable pageable = PageRequest.of(page, size);

        return ownerAccountRepository.findSubscriptionRegistrants(
                trimToNull(request.getSearchTerm()),
                trimToNull(request.getStatus()),
                request.getBuildingId(),
                request.getParkingLotId(),
                fromDate,
                toDate,
                pageable
        ).map(this::toSubscriptionRegistrantPageResponse);
    }

    @Transactional(readOnly = true)
    public List<RegisteredVehicleResponse> listRegisteredVehicles(Long ownerId) {
        if (!ownerAccountRepository.existsById(ownerId)) {
            throw new ServiceException("Owner account not found", 404);
        }
        return vehicleRepository.findRegisteredVehiclesByOwnerId(ownerId).stream()
                .map(this::toRegisteredVehicleResponse)
                .toList();
    }

    @Transactional
    public OwnerAccountResponse create(OwnerAccountRequest request) {
        OwnerAccountEntity entity = new OwnerAccountEntity();
        applyRequest(entity, request);
        return toResponse(ownerAccountRepository.save(entity));
    }

    @Transactional
    public OwnerAccountResponse update(Long id, OwnerAccountRequest request) {
        OwnerAccountEntity entity = findEntity(id);
        applyRequest(entity, request);
        return toResponse(ownerAccountRepository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        OwnerAccountEntity entity = findEntity(id);
        ownerAccountRepository.delete(entity);
    }

    private OwnerAccountEntity findEntity(Long id) {
        return ownerAccountRepository.findById(id)
                .orElseThrow(() -> new ServiceException("Owner account not found", 404));
    }

    private void applyRequest(OwnerAccountEntity entity, OwnerAccountRequest request) {
        entity.setFullName(request.getFullName().trim());
        entity.setPhone(request.getPhone().trim());
        entity.setIdentityNumber(trimToNull(request.getIdentityNumber()));
        entity.setAddress(trimToNull(request.getAddress()));
        entity.setImageUrl(trimToNull(request.getImageUrl()));
        entity.setZaloLink(trimToNull(request.getZaloLink()));
        entity.setNote(trimToNull(request.getNote()));
        entity.setStatus(request.getStatus().trim());
    }

    private String trimToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    private OffsetDateTime parseStartDate(String value, String fieldName) {
        LocalDate date = parseDate(value, fieldName);
        return date == null ? null : date.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private OffsetDateTime parseEndDate(String value, String fieldName) {
        LocalDate date = parseDate(value, fieldName);
        return date == null ? null : date.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private LocalDate parseDate(String value, String fieldName) {
        String trimmedValue = trimToNull(value);
        if (trimmedValue == null) {
            return null;
        }
        for (DateTimeFormatter formatter : new DateTimeFormatter[]{DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("dd/MM/yyyy")}) {
            try {
                return LocalDate.parse(trimmedValue, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        throw new ServiceException(fieldName + " must be yyyy-MM-dd or dd/MM/yyyy", 400);
    }

    private OwnerAccountResponse toResponse(OwnerAccountEntity entity) {
        return OwnerAccountResponse.builder()
                .id(entity.getId())
                .fullName(entity.getFullName())
                .phone(entity.getPhone())
                .identityNumber(entity.getIdentityNumber())
                .address(entity.getAddress())
                .imageUrl(entity.getImageUrl())
                .zaloLink(entity.getZaloLink())
                .note(entity.getNote())
                .status(entity.getStatus())
                .vehicleCount(vehicleRepository.countByOwnerId(entity.getId()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private SubscriptionRegistrantPageResponse toSubscriptionRegistrantPageResponse(OwnerAccountRepository.SubscriptionRegistrantProjection projection) {
        return SubscriptionRegistrantPageResponse.builder()
                .ownerId(projection.getOwnerId())
                .fullName(projection.getFullName())
                .customerCode(projection.getCustomerCode())
                .address(projection.getAddress())
                .phone(projection.getPhone())
                .zaloLink(projection.getZaloLink())
                .status(projection.getStatus())
                .registeredVehicleCount(projection.getRegisteredVehicleCount())
                .carCount(projection.getCarCount())
                .motorbikeCount(projection.getMotorbikeCount())
                .expiredSubscriptionCount(projection.getExpiredSubscriptionCount())
                .vehicles(vehicleRepository.findRegisteredVehiclesByOwnerId(projection.getOwnerId()).stream()
                        .map(this::toRegisteredVehicleResponse)
                        .toList())
                .build();
    }

    private RegisteredVehicleResponse toRegisteredVehicleResponse(VehicleRepository.RegisteredVehicleProjection projection) {
        return RegisteredVehicleResponse.builder()
                .vehicleId(projection.getVehicleId())
                .plate(projection.getPlate())
                .vehicleType(projection.getVehicleType())
                .vehicleStatus(projection.getVehicleStatus())
                .userName(projection.getUserName())
                .parkingLotName(projection.getParkingLotName())
                .buildingName(projection.getBuildingName())
                .packageName(projection.getPackageName())
                .packageType(projection.getPackageType())
                .ticketType(projection.getTicketType())
                .subscriptionStatus(projection.getSubscriptionStatus())
                .validFrom(toOffsetDateTime(projection.getValidFrom()))
                .validTo(toOffsetDateTime(projection.getValidTo()))
                .expired(projection.getExpired())
                .build();
    }

    private OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
