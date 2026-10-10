package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
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
import vio.barie.core.repository.VehicleCustomerRepository;
import vio.barie.core.repository.VehicleRepository;
import vio.barie.core.repository.VehicleSubscriptionRepository;


import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OwnerAccountService {

    private final OwnerAccountRepository ownerAccountRepository;
    private final VehicleRepository vehicleRepository;
    private final VehicleSubscriptionRepository vehicleSubscriptionRepository;
    private final VehicleCustomerRepository vehicleCustomerRepository;

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

        Page<OwnerAccountEntity> owners = ownerAccountRepository.findAll(
                OwnerAccountSpecification.filter(
                        request.getSearchTerm(),
                        request.getStatus(),
                        fromDate,
                        toDate
                ),
                pageable
        );
        Map<Long, Long> counts = owners.isEmpty() ? Map.of() : ownerAccountRepository
                .vehicleCounts(owners.getContent().stream().map(OwnerAccountEntity::getId).toList()).stream()
                .collect(Collectors.toMap(OwnerAccountRepository.OwnerVehicleCount::getOwnerId,
                        OwnerAccountRepository.OwnerVehicleCount::getVehicleCount));
        return owners.map(owner -> toResponse(owner, counts.getOrDefault(owner.getId(), 0L)));
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
        List<VehicleRepository.RegisteredVehicleProjection> vehicles = vehicleRepository.findRegisteredVehiclesByOwnerId(ownerId);
        if (vehicles.isEmpty()) {
            return List.of();
        }

        List<Long> vehicleIds = vehicles.stream()
                .map(VehicleRepository.RegisteredVehicleProjection::getVehicleId)
                .toList();

        Map<Long, VehicleSubscriptionRepository.RegisteredVehicleSubscriptionProjection> subscriptionsByVehicleId =
                vehicleSubscriptionRepository.findLatestRegisteredVehicleSubscriptions(vehicleIds).stream()
                        .collect(Collectors.toMap(
                                VehicleSubscriptionRepository.RegisteredVehicleSubscriptionProjection::getVehicleId,
                                Function.identity(),
                                (current, ignored) -> current,
                                LinkedHashMap::new
                        ));

        Map<Long, LinkedHashSet<String>> userNamesByVehicleId = new LinkedHashMap<>();
        vehicleCustomerRepository.findActiveUserNamesByVehicleIds(vehicleIds).forEach(row -> {
            String userName = trimToNull(row.getUserName());
            if (userName != null) {
                userNamesByVehicleId
                        .computeIfAbsent(row.getVehicleId(), ignored -> new LinkedHashSet<>())
                        .add(userName);
            }
        });

        return vehicles.stream()
                .map(vehicle -> toRegisteredVehicleResponse(
                        vehicle,
                        subscriptionsByVehicleId.get(vehicle.getVehicleId()),
                        userNamesByVehicleId.get(vehicle.getVehicleId())
                ))
                .toList();
    }

    @Transactional
    public OwnerAccountResponse create(OwnerAccountRequest request) {
        OwnerAccountEntity entity = new OwnerAccountEntity();
        applyRequest(entity, request);
        return saveOwner(entity);
    }

    @Transactional
    public OwnerAccountResponse update(Long id, OwnerAccountRequest request) {
        OwnerAccountEntity entity = findEntity(id);
        applyRequest(entity, request);
        return saveOwner(entity);
    }

    @Transactional
    public void delete(Long id) {
        OwnerAccountEntity entity = findEntity(id);
        try {
            ownerAccountRepository.delete(entity);
            ownerAccountRepository.flush();
        } catch (DataIntegrityViolationException failure) {
            throw OwnerProfileIntegrity.ownerDeleteConflict(failure);
        }
    }

    private OwnerAccountResponse saveOwner(OwnerAccountEntity entity) {
        try {
            return toResponse(ownerAccountRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException failure) {
            throw OwnerProfileIntegrity.uniqueConflict(failure);
        }
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
        entity.setZaloLink(trimToNull(request.getZaloLink()));
        entity.setNote(trimToNull(request.getNote()));
        if (request.getStatus() != null) {
            if (!java.util.Set.of("ACTIVE", "INACTIVE").contains(request.getStatus())) {
                throw new ServiceException("Unsupported owner status", 400);
            }
            entity.setStatus(request.getStatus());
        } else if (entity.getId() == null) {
            entity.setStatus("ACTIVE");
        }
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
        return toResponse(entity, vehicleRepository.countByOwnerId(entity.getId()));
    }

    private OwnerAccountResponse toResponse(OwnerAccountEntity entity, long vehicleCount) {
        return OwnerAccountResponse.builder()
                .id(entity.getId())
                .fullName(entity.getFullName())
                .phone(entity.getPhone())
                .identityNumber(entity.getIdentityNumber())
                .address(entity.getAddress())
                .imageUrl(entity.getImageUrl())
                .aiProfileId(entity.getAiProfileId())
                .aiSyncState(entity.getAiSyncState())
                .driverCustomerId(entity.getDriverCustomerId())
                .zaloLink(entity.getZaloLink())
                .note(entity.getNote())
                .status(entity.getStatus())
                .vehicleCount(vehicleCount)
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
                .imageUrl(projection.getImageUrl())
                .phone(projection.getPhone())
                .zaloLink(projection.getZaloLink())
                .note(projection.getNote())
                .status(projection.getStatus())
                .registeredVehicleCount(projection.getRegisteredVehicleCount())
                .createdAt(toOffsetDateTime(projection.getCreatedAt()))
                .updatedAt(toOffsetDateTime(projection.getUpdatedAt()))
                .build();
    }

    private RegisteredVehicleResponse toRegisteredVehicleResponse(
            VehicleRepository.RegisteredVehicleProjection vehicle,
            VehicleSubscriptionRepository.RegisteredVehicleSubscriptionProjection subscription,
            LinkedHashSet<String> userNames
    ) {
        return RegisteredVehicleResponse.builder()
                .vehicleId(vehicle.getVehicleId())
                .plate(vehicle.getPlate())
                .vehicleType(vehicle.getVehicleType())
                .vehicleStatus(vehicle.getVehicleStatus())
                .userName(userNames == null ? "" : String.join(", ", userNames))
                .parkingLotName(subscription == null ? null : subscription.getParkingLotName())
                .ticketType(subscription == null ? null : subscription.getTicketType())
                .subscriptionStatus(subscription == null ? null : subscription.getSubscriptionStatus())
                .validFrom(subscription == null ? null : subscription.getValidFrom())
                .validTo(subscription == null ? null : subscription.getValidTo())
                .expired(subscription != null && subscription.getExpired())
                .build();
    }

    private OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
