package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vio.barie.core.client.AiFaceApiClient;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.OwnerAccountVehicleParkingCustomerResponse;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.ParkingCustomerResponse;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.ParkingCustomerUpdateRequest;
import vio.barie.core.dto.owneraccount.ProfileSaveRequest;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.SubscriptionVehicleCreateRequest;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.SubscriptionVehicleCreateResponse;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.VehicleUserSaveRequest;
import vio.barie.core.entity.OwnerAccountEntity;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.entity.VehicleCustomerEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.entity.VehicleSubscriptionEntity;
import vio.barie.core.repository.OwnerAccountRepository;
import vio.barie.core.repository.ParkingCustomerRepository;
import vio.barie.core.repository.VehicleCustomerRepository;
import vio.barie.core.repository.VehicleRepository;
import vio.barie.core.repository.VehicleSubscriptionRepository;
import vio.barie.core.utils.PlateNumberUtils;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OwnerAccountProfileService {

    private final AiFaceApiClient aiFaceApiClient;
    private final OwnerAccountRepository ownerAccountRepository;
    private final VehicleRepository vehicleRepository;
    private final ParkingCustomerRepository parkingCustomerRepository;
    private final VehicleCustomerRepository vehicleCustomerRepository;
    private final VehicleSubscriptionRepository vehicleSubscriptionRepository;

    @Value("${ai.core.image-url:${ai.face.base-url:}}")
    private String imageUrlAi;

    @Transactional
    public OwnerAccountVehicleParkingCustomerResponse createProfile(ProfileSaveRequest request) {
        return createProfile(request, null);
    }

    @Transactional
    public OwnerAccountVehicleParkingCustomerResponse createProfile(ProfileSaveRequest request, List<MultipartFile> customerImages) {
        return createProfile(request, customerImages, null);
    }

    @Transactional
    public OwnerAccountVehicleParkingCustomerResponse createProfile(
            ProfileSaveRequest request,
            List<MultipartFile> customerImages,
            MultipartFile ownerImage) {
        validateProfileRequest(request);
        syncOwnerImageWithAi(request.getOwner(), ownerImage);

        OwnerAccountEntity ownerEntity;
        if (request.getOwner().getId() == null) {
            ownerEntity = saveOwner(new OwnerAccountEntity(), request.getOwner());
        } else {
            ownerEntity = findOwner(request.getOwner().getId());
            saveOwner(ownerEntity, request.getOwner());
        }
        Long ownerId = ownerEntity.getId();

        List<OwnerAccountVehicleParkingCustomerResponse.VehicleResponse> vehicleResponses = new ArrayList<>();
        List<OwnerAccountVehicleParkingCustomerResponse.CustomerResponse> customerResponses = new ArrayList<>();
        List<OwnerAccountVehicleParkingCustomerResponse.VehicleCustomerResponse> relationResponses = new ArrayList<>();
        Set<Long> submittedCustomerIds = new HashSet<>();
        Set<Long> submittedRelationIds = new HashSet<>();
        boolean mapImagesSequentially = shouldMapCustomerImagesSequentially(request, customerImages);
        int imageCursor = 0;

        for (ProfileSaveRequest.Vehicle vehicle : request.getVehicles()) {
            VehicleEntity vehicleEntity = vehicle.getId() == null
                    ? saveVehicle(new VehicleEntity(), ownerId, vehicle)
                    : saveVehicle(findOwnerVehicle(ownerId, vehicle.getId()), ownerId, vehicle);
            Long vehicleId = vehicleEntity.getId();

            vehicleResponses.add(OwnerAccountVehicleParkingCustomerResponse.VehicleResponse.builder()
                    .id(vehicleId)
                    .plate(vehicleEntity.getPlateNumber())
                    .vehicleType(vehicleEntity.getVehicleType())
                    .status(vehicleEntity.getStatus())
                    .build());

            upsertVehicleSubscription(vehicleId, vehicle.getSubscription());

            for (ProfileSaveRequest.Customer customer : vehicle.getCustomers()) {
                syncCustomerImageWithAi(customer, customerImages, mapImagesSequentially ? imageCursor++ : null);

                ParkingCustomerEntity customerEntity = customer.getId() == null
                        ? saveCustomer(new ParkingCustomerEntity(), ownerId, customer)
                        : saveCustomer(findOwnerCustomer(ownerId, customer.getId()), ownerId, customer);
                Long customerId = customerEntity.getId();
                submittedCustomerIds.add(customerId);

                Long relationId = null;
                if (isUsed(customer)) {
                    relationId = upsertVehicleCustomer(vehicleId, customerId, customer);
                    submittedRelationIds.add(relationId);
                }

                customerResponses.add(OwnerAccountVehicleParkingCustomerResponse.CustomerResponse.builder()
                        .id(customerId)
                        .name(customerEntity.getUsername())
                        .phone(customerEntity.getPhoneNumber())
                        .imageUrl(customerEntity.getImageUrl())
                        .profileId(customerEntity.getAiProfileId() == null ? null : customerEntity.getAiProfileId().toString())
                        .status(customerEntity.getStatus())
                        .build());
                if (relationId != null) {
                    relationResponses.add(OwnerAccountVehicleParkingCustomerResponse.VehicleCustomerResponse.builder()
                            .id(relationId)
                            .vehicleId(vehicleId)
                            .customerId(customerId)
                            .relationType(trim(customer.getRelationType()))
                            .status(trim(customer.getRelationStatus()))
                            .build());
                }
            }
        }

        inactiveMissingCustomers(ownerId, submittedCustomerIds);
        inactiveMissingRelations(ownerId, submittedRelationIds);

        return OwnerAccountVehicleParkingCustomerResponse.builder()
                .id(ownerId)
                .fullName(ownerEntity.getFullName())
                .phone(ownerEntity.getPhone())
                .identityNumber(ownerEntity.getIdentityNumber())
                .address(ownerEntity.getAddress())
                .imageUrl(ownerEntity.getImageUrl())
                .zaloLink(ownerEntity.getZaloLink())
                .note(ownerEntity.getNote())
                .status(ownerEntity.getStatus())
                .vehicleCount((long) vehicleResponses.size())
                .createdAt(ownerEntity.getCreatedAt())
                .updatedAt(ownerEntity.getUpdatedAt())
                .vehicles(vehicleResponses)
                .customers(customerResponses)
                .vehicleCustomers(relationResponses)
                .build();
    }

    @Transactional
    public SubscriptionVehicleCreateResponse addVehicle(Long ownerId, SubscriptionVehicleCreateRequest request) {
        ensureOwnerExists(ownerId);
        validateVehicleUsers(request);

        OffsetDateTime now = OffsetDateTime.now();
        String plate = normalizePlate(request.getPlate());
        if (plateExists(plate)) {
            throw new ServiceException("Plate already exists: " + plate, 400);
        }

        VehicleEntity vehicle = new VehicleEntity();
        vehicle.setOwnerId(ownerId);
        vehicle.setPlateNumber(plate);
        vehicle.setVehicleType(trim(request.getVehicleType()));
        vehicle.setStatus(trim(request.getStatus()));
        vehicle = vehicleRepository.save(vehicle);
        Long vehicleId = vehicle.getId();

        List<SubscriptionVehicleCreateResponse.UserResponse> users = new ArrayList<>();
        if (request.getUsers() != null) {
            for (SubscriptionVehicleCreateRequest.ExistingUser user : request.getUsers()) {
                ensureCustomerBelongsToOwner(ownerId, user.getCustomerId());
                insertVehicleCustomer(vehicleId, user.getCustomerId(), trim(user.getRelationType()), trim(user.getStatus()));
                users.add(fetchCustomerRelation(vehicleId, user.getCustomerId()));
            }
        }
        if (request.getNewUsers() != null) {
            for (SubscriptionVehicleCreateRequest.NewUser user : request.getNewUsers()) {
                ParkingCustomerEntity customer = new ParkingCustomerEntity();
                customer.setOwnerId(ownerId);
                customer.setUsername(trim(user.getName()));
                customer.setPhoneNumber(trimToNull(user.getPhone()));
                customer.setImageUrl(trimToNull(user.getImageUrl()));
                customer.setAiIdentityId("");
                customer.setAiProfileId(parseNullableInteger(user.getProfileId(), "profileId"));
                customer.setZaloId("");
                customer.setStatus(trim(user.getStatus()));
                customer = parkingCustomerRepository.save(customer);
                Long customerId = customer.getId();
                insertVehicleCustomer(vehicleId, customerId, trim(user.getRelationType()), trim(user.getStatus()));
                users.add(fetchCustomerRelation(vehicleId, customerId));
            }
        }

        return SubscriptionVehicleCreateResponse.builder()
                .vehicleId(vehicleId)
                .ownerId(ownerId)
                .plate(plate)
                .vehicleType(trim(request.getVehicleType()))
                .status(trim(request.getStatus()))
                .createdAt(now)
                .users(users)
                .build();
    }

    @Transactional
    public ParkingCustomerResponse updateCustomer(Long customerId, ParkingCustomerUpdateRequest request) {
        ParkingCustomerEntity customer = findCustomer(customerId);
        if (request.getName() != null) {
            customer.setUsername(trimRequired(request.getName(), "name"));
        }
        if (request.getPhone() != null) {
            customer.setPhoneNumber(trimToNull(request.getPhone()));
        }
        if (request.getImageUrl() != null) {
            customer.setImageUrl(trimToNull(request.getImageUrl()));
        }
        if (request.getProfileId() != null) {
            customer.setAiProfileId(parseNullableInteger(request.getProfileId(), "profileId"));
        }
        if (request.getStatus() != null) {
            customer.setStatus(trimRequired(request.getStatus(), "status"));
        }
        return toParkingCustomerResponse(parkingCustomerRepository.save(customer));
    }

    @Transactional
    public SubscriptionVehicleCreateResponse.UserResponse saveVehicleUser(Long vehicleId, VehicleUserSaveRequest request) {
        Long ownerId = getVehicleOwnerId(vehicleId);
        Long customerId = request.getCustomerId();

        if (customerId == null) {
            if (StringUtils.isBlank(request.getName())) {
                throw new ServiceException("name is required when creating a new vehicle user", 400);
            }
            ParkingCustomerEntity customer = new ParkingCustomerEntity();
            customer.setOwnerId(ownerId);
            customer.setUsername(trim(request.getName()));
            customer.setPhoneNumber(trimToNull(request.getPhone()));
            customer.setImageUrl(trimToNull(request.getImageUrl()));
            customer.setAiIdentityId("");
            customer.setAiProfileId(parseNullableInteger(request.getProfileId(), "profileId"));
            customer.setZaloId("");
            customer.setStatus(trim(request.getStatus()));
            customerId = parkingCustomerRepository.save(customer).getId();
        } else {
            ensureCustomerBelongsToOwner(ownerId, customerId);
            updateCustomerFields(findCustomer(customerId), request);
        }

        upsertVehicleCustomer(vehicleId, customerId, trim(request.getRelationType()), trim(request.getStatus()));
        return fetchCustomerRelation(vehicleId, customerId);
    }

    private void validateProfileRequest(ProfileSaveRequest request) {
        Long ownerId = request.getOwner().getId();
        String ownerPhone = trim(request.getOwner().getPhone());
        String identityNumber = trim(request.getOwner().getIdentityNumber());
        if (ownerPhoneExists(ownerPhone, ownerId)) {
            throw new ServiceException("Duplicate owner phone: " + ownerPhone, 400);
        }
        if (identityNumberExists(identityNumber, ownerId)) {
            throw new ServiceException("Duplicate owner identityNumber: " + identityNumber, 400);
        }

        Set<String> plates = new HashSet<>();
        Map<String, String> customerPhoneOwners = new HashMap<>();
        Set<String> relationKeys = new HashSet<>();
        Set<Long> vehicleIds = new HashSet<>();
        Set<Long> relationIds = new HashSet<>();

        for (ProfileSaveRequest.Vehicle vehicle : request.getVehicles()) {
            String plate = normalizePlate(vehicle.getPlate());
            if (!plates.add(plate)) {
                throw new ServiceException("Duplicate plate in request: " + plate, 400);
            }
            if (plateExists(plate, vehicle.getId())) {
                throw new ServiceException("Duplicate plate: " + plate, 400);
            }
            if (vehicle.getId() != null && !vehicleIds.add(vehicle.getId())) {
                throw new ServiceException("Duplicate vehicle id in request: " + vehicle.getId(), 400);
            }
            if (vehicle.getId() != null) {
                if (ownerId == null) {
                    throw new ServiceException("owner.id is required when updating vehicle: " + vehicle.getId(), 400);
                }
                ensureVehicleBelongsToOwner(ownerId, vehicle.getId());
            }

            for (ProfileSaveRequest.Customer customer : vehicle.getCustomers()) {
                if (customer.getRelationId() != null && !isUsed(customer)) {
                    throw new ServiceException("relationId is only valid when isUsed is true", 400);
                }
                if (customer.getRelationId() != null && !relationIds.add(customer.getRelationId())) {
                    throw new ServiceException("Duplicate relation id in request: " + customer.getRelationId(), 400);
                }
                if (customer.getId() != null) {
                    if (ownerId == null) {
                        throw new ServiceException("owner.id is required when updating customer: " + customer.getId(), 400);
                    }
                    ensureCustomerBelongsToOwner(ownerId, customer.getId());
                }
                if (customer.getRelationId() != null) {
                    if (vehicle.getId() == null || customer.getId() == null) {
                        throw new ServiceException("vehicle.id and customer.id are required when updating relation: " + customer.getRelationId(), 400);
                    }
                    ensureRelationMatches(customer.getRelationId(), vehicle.getId(), customer.getId());
                }
                String customerPhone = trimToNull(customer.getPhone());
                if (customerPhone != null) {
                    String customerKeyForPhone = customer.getId() == null ? "NEW:" + trim(customer.getName()) : customer.getId().toString();
                    String existingOwner = customerPhoneOwners.putIfAbsent(customerPhone, customerKeyForPhone);
                    if (existingOwner != null && !existingOwner.equals(customerKeyForPhone)) {
                        throw new ServiceException("Duplicate customer phone in request: " + customerPhone, 400);
                    }
                    if (customerPhoneExists(customerPhone, customer.getId())) {
                        throw new ServiceException("Duplicate customer phone: " + customerPhone, 400);
                    }
                }
                String customerKey = customer.getId() == null
                        ? StringUtils.defaultIfBlank(customerPhone, trim(customer.getName()))
                        : customer.getId().toString();
                if (isUsed(customer)) {
                    String relationKey = plate + "::" + customerKey;
                    if (!relationKeys.add(relationKey)) {
                        throw new ServiceException("Duplicate vehicle customer relation in request: " + relationKey, 400);
                    }
                }
            }
        }
    }

    private OwnerAccountEntity saveOwner(OwnerAccountEntity entity, ProfileSaveRequest.Owner owner) {
        entity.setFullName(trim(owner.getFullName()));
        entity.setPhone(trim(owner.getPhone()));
        entity.setIdentityNumber(trim(owner.getIdentityNumber()));
        entity.setAddress(trimToNull(owner.getAddress()));
        entity.setImageUrl(trimToNull(owner.getImageUrl()));
        entity.setZaloLink(trimToNull(owner.getZaloLink()));
        entity.setNote(trimToNull(owner.getNote()));
        entity.setStatus(trim(owner.getStatus()));
        return ownerAccountRepository.save(entity);
    }

    private VehicleEntity saveVehicle(VehicleEntity entity, Long ownerId, ProfileSaveRequest.Vehicle vehicle) {
        entity.setOwnerId(ownerId);
        entity.setPlateNumber(normalizePlate(vehicle.getPlate()));
        entity.setVehicleType(trim(vehicle.getVehicleType()));
        entity.setStatus(trim(vehicle.getStatus()));
        return vehicleRepository.save(entity);
    }

    private ParkingCustomerEntity saveCustomer(ParkingCustomerEntity entity, Long ownerId, ProfileSaveRequest.Customer customer) {
        entity.setOwnerId(ownerId);
        entity.setUsername(trim(customer.getName()));
        entity.setPhoneNumber(trimToNull(customer.getPhone()));
        entity.setImageUrl(trimToNull(customer.getImageUrl()));
        entity.setAiIdentityId(resolveAiIdentityId(customer.getAiIdentityId(), entity.getAiIdentityId()));
        entity.setAiProfileId(parseNullableInteger(customer.getProfileId(), "profileId"));
        entity.setZaloId(StringUtils.defaultString(entity.getZaloId()));
        entity.setStatus(trim(customer.getStatus()));
        return parkingCustomerRepository.save(entity);
    }

    private void upsertVehicleSubscription(Long vehicleId, ProfileSaveRequest.Subscription subscription) {
        if (subscription == null) {
            return;
        }

        VehicleSubscriptionEntity entity = subscription.getId() == null
                ? new VehicleSubscriptionEntity()
                : vehicleSubscriptionRepository.findById(subscription.getId())
                .orElseThrow(() -> new ServiceException("Vehicle subscription not found: " + subscription.getId(), 404));
        entity.setVehicleId(vehicleId);
        entity.setParkingLotId(subscription.getParkingLotId());
        entity.setPackageId(subscription.getPackageId());
        entity.setRegistrationType(trim(subscription.getRegistrationType()));
        entity.setPreviousSubscriptionId(subscription.getPreviousSubscriptionId());
        entity.setPriceVnd(subscription.getPriceVnd());
        entity.setStartsAt(subscription.getStartsAt());
        entity.setEndsAt(subscription.getEndsAt());
        entity.setPaymentDeadlineAt(subscription.getPaymentDeadlineAt());
        entity.setStatus(trim(subscription.getStatus()));
        entity.setPaidAt(subscription.getPaidAt());
        entity.setPaymentSource(trimToNull(subscription.getPaymentSource()));
        entity.setPaymentReference(trimToNull(subscription.getPaymentReference()));
        entity.setPaymentReceipt(trimToNull(subscription.getPaymentReceipt()));
        vehicleSubscriptionRepository.save(entity);
    }

    private Long upsertVehicleCustomer(Long vehicleId, Long customerId, ProfileSaveRequest.Customer customer) {
        VehicleCustomerEntity relation = customer.getRelationId() == null
                ? vehicleCustomerRepository.findByVehicleIdAndCustomerId(vehicleId, customerId).orElse(null)
                : findVehicleCustomer(customer.getRelationId(), vehicleId, customerId);
        if (relation == null) {
            relation = new VehicleCustomerEntity();
            relation.setVehicle(findVehicle(vehicleId));
            relation.setCustomer(findCustomer(customerId));
        }
        relation.setRelationType(trim(customer.getRelationType()));
        relation.setStatus(trim(customer.getRelationStatus()));
        return vehicleCustomerRepository.save(relation).getId();
    }

    private boolean isUsed(ProfileSaveRequest.Customer customer) {
        return customer.getIsUsed() == null || Boolean.TRUE.equals(customer.getIsUsed());
    }

    private void inactiveMissingCustomers(Long ownerId, Set<Long> submittedCustomerIds) {
        List<ParkingCustomerEntity> customers = parkingCustomerRepository.findAll().stream()
                .filter(customer -> ownerId.equals(customer.getOwnerId()))
                .filter(customer -> !submittedCustomerIds.contains(customer.getId()))
                .toList();
        for (ParkingCustomerEntity customer : customers) {
            customer.setStatus("INACTIVE");
        }
        parkingCustomerRepository.saveAll(customers);
    }

    private void inactiveMissingRelations(Long ownerId, Set<Long> submittedRelationIds) {
        List<VehicleCustomerEntity> relations = vehicleCustomerRepository.findByVehicleOwnerId(ownerId).stream()
                .filter(relation -> !submittedRelationIds.contains(relation.getId()))
                .toList();
        for (VehicleCustomerEntity relation : relations) {
            relation.setStatus("INACTIVE");
        }
        vehicleCustomerRepository.saveAll(relations);
    }

    private boolean ownerPhoneExists(String phone, Long excludedOwnerId) {
        return excludedOwnerId == null
                ? ownerAccountRepository.existsByPhone(phone)
                : ownerAccountRepository.existsByPhoneAndIdNot(phone, excludedOwnerId);
    }

    private boolean identityNumberExists(String identityNumber, Long excludedOwnerId) {
        return excludedOwnerId == null
                ? ownerAccountRepository.existsByIdentityNumber(identityNumber)
                : ownerAccountRepository.existsByIdentityNumberAndIdNot(identityNumber, excludedOwnerId);
    }

    private boolean customerPhoneExists(String phone, Long excludedCustomerId) {
        return excludedCustomerId == null
                ? parkingCustomerRepository.existsByPhoneNumber(phone)
                : parkingCustomerRepository.existsByPhoneNumberAndIdNot(phone, excludedCustomerId);
    }

    private boolean plateExists(String plate, Long excludedVehicleId) {
        return excludedVehicleId == null
                ? vehicleRepository.existsByPlateNumber(plate)
                : vehicleRepository.existsByPlateNumberAndIdNot(plate, excludedVehicleId);
    }

    private void ensureVehicleBelongsToOwner(Long ownerId, Long vehicleId) {
        findOwnerVehicle(ownerId, vehicleId);
    }

    private void ensureRelationMatches(Long relationId, Long vehicleId, Long customerId) {
        findVehicleCustomer(relationId, vehicleId, customerId);
    }

    private void insertVehicleCustomer(Long vehicleId, Long customerId, String relationType, String status) {
        if (vehicleCustomerExists(vehicleId, customerId)) {
            throw new ServiceException("Customer already assigned to vehicle", 400);
        }
        VehicleCustomerEntity relation = new VehicleCustomerEntity();
        relation.setVehicle(findVehicle(vehicleId));
        relation.setCustomer(findCustomer(customerId));
        relation.setRelationType(relationType);
        relation.setStatus(status);
        vehicleCustomerRepository.save(relation);
    }

    private void upsertVehicleCustomer(Long vehicleId, Long customerId, String relationType, String status) {
        VehicleCustomerEntity relation = vehicleCustomerRepository.findByVehicleIdAndCustomerId(vehicleId, customerId)
                .orElseGet(() -> {
                    VehicleCustomerEntity newRelation = new VehicleCustomerEntity();
                    newRelation.setVehicle(findVehicle(vehicleId));
                    newRelation.setCustomer(findCustomer(customerId));
                    return newRelation;
                });
        relation.setRelationType(relationType);
        relation.setStatus(status);
        vehicleCustomerRepository.save(relation);
    }

    private void validateVehicleUsers(SubscriptionVehicleCreateRequest request) {
        int existingUserCount = request.getUsers() == null ? 0 : request.getUsers().size();
        int newUserCount = request.getNewUsers() == null ? 0 : request.getNewUsers().size();
        if (existingUserCount + newUserCount == 0) {
            throw new ServiceException("At least one vehicle user is required", 400);
        }

        Set<Long> customerIds = new HashSet<>();
        if (request.getUsers() != null) {
            for (SubscriptionVehicleCreateRequest.ExistingUser user : request.getUsers()) {
                if (!customerIds.add(user.getCustomerId())) {
                    throw new ServiceException("Duplicate customerId in request: " + user.getCustomerId(), 400);
                }
            }
        }
    }

    private void ensureOwnerExists(Long ownerId) {
        if (!ownerAccountRepository.existsById(ownerId)) {
            throw new ServiceException("Owner account not found", 404);
        }
    }

    private void ensureCustomerBelongsToOwner(Long ownerId, Long customerId) {
        findOwnerCustomer(ownerId, customerId);
    }

    private Long getVehicleOwnerId(Long vehicleId) {
        return findVehicle(vehicleId).getOwnerId();
    }

    private boolean plateExists(String plate) {
        return vehicleRepository.existsByPlateNumber(plate);
    }

    private boolean vehicleCustomerExists(Long vehicleId, Long customerId) {
        return vehicleCustomerRepository.existsByVehicleIdAndCustomerId(vehicleId, customerId);
    }

    private SubscriptionVehicleCreateResponse.UserResponse fetchCustomerRelation(Long vehicleId, Long customerId) {
        VehicleCustomerEntity relation = vehicleCustomerRepository.findByVehicleIdAndCustomerId(vehicleId, customerId)
                .orElseThrow(() -> new ServiceException("Vehicle customer relation not found", 404));
        ParkingCustomerEntity customer = relation.getCustomer();
        return SubscriptionVehicleCreateResponse.UserResponse.builder()
                .customerId(customer.getId())
                .name(customer.getUsername())
                .phone(customer.getPhoneNumber())
                .imageUrl(customer.getImageUrl())
                .profileId(customer.getAiProfileId() == null ? null : customer.getAiProfileId().toString())
                .relationType(relation.getRelationType())
                .relationStatus(relation.getStatus())
                .build();
    }

    private ParkingCustomerResponse getCustomer(Long customerId) {
        return toParkingCustomerResponse(findCustomer(customerId));
    }

    private void updateCustomerFields(ParkingCustomerEntity customer, VehicleUserSaveRequest request) {
        if (request.getName() != null) {
            customer.setUsername(trimRequired(request.getName(), "name"));
        }
        if (request.getPhone() != null) {
            customer.setPhoneNumber(trimToNull(request.getPhone()));
        }
        if (request.getImageUrl() != null) {
            customer.setImageUrl(trimToNull(request.getImageUrl()));
        }
        if (request.getProfileId() != null) {
            customer.setAiProfileId(parseNullableInteger(request.getProfileId(), "profileId"));
        }
        customer.setStatus(trim(request.getStatus()));
        parkingCustomerRepository.save(customer);
    }

    private OwnerAccountEntity findOwner(Long ownerId) {
        return ownerAccountRepository.findById(ownerId)
                .orElseThrow(() -> new ServiceException("Owner account not found", 404));
    }

    private VehicleEntity findVehicle(Long vehicleId) {
        return vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new ServiceException("Vehicle not found", 404));
    }

    private VehicleEntity findOwnerVehicle(Long ownerId, Long vehicleId) {
        return vehicleRepository.findByIdAndOwnerId(vehicleId, ownerId)
                .orElseThrow(() -> new ServiceException("Vehicle not found in owner account: " + vehicleId, 404));
    }

    private ParkingCustomerEntity findCustomer(Long customerId) {
        return parkingCustomerRepository.findById(customerId)
                .orElseThrow(() -> new ServiceException("Customer not found", 404));
    }

    private ParkingCustomerEntity findOwnerCustomer(Long ownerId, Long customerId) {
        return parkingCustomerRepository.findByIdAndOwnerId(customerId, ownerId)
                .orElseThrow(() -> new ServiceException("Customer not found in owner account: " + customerId, 404));
    }

    private VehicleCustomerEntity findVehicleCustomer(Long relationId, Long vehicleId, Long customerId) {
        return vehicleCustomerRepository.findByIdAndVehicleIdAndCustomerId(relationId, vehicleId, customerId)
                .orElseThrow(() -> new ServiceException("Vehicle customer relation does not match vehicle/customer: " + relationId, 400));
    }

    private ParkingCustomerResponse toParkingCustomerResponse(ParkingCustomerEntity customer) {
        return ParkingCustomerResponse.builder()
                .id(customer.getId())
                .ownerId(customer.getOwnerId())
                .name(customer.getUsername())
                .phone(customer.getPhoneNumber())
                .imageUrl(customer.getImageUrl())
                .profileId(customer.getAiProfileId() == null ? null : customer.getAiProfileId().toString())
                .status(customer.getStatus())
                .createdAt(toOffsetDateTime(customer.getCreatedAt()))
                .updatedAt(null)
                .build();
    }

    private void syncCustomerImageWithAi(ProfileSaveRequest.Customer customer, List<MultipartFile> customerImages, Integer sequentialImageIndex) {
        List<MultipartFile> images = resolveCustomerImages(customer, customerImages, sequentialImageIndex);
        if (images.isEmpty()) {
            return;
        }

        AiProfileResponse aiProfile = StringUtils.isBlank(customer.getProfileId())
                ? aiFaceApiClient.createProfile(trim(customer.getName()), images)
                : aiFaceApiClient.updateProfile(parseProfileId(customer.getProfileId()), trim(customer.getName()), images);
        if (aiProfile == null || (aiProfile.getId() == null && aiProfile.getAiProfileId() == null)) {
            throw new ServiceException("Create AI profile failed for customer: " + trim(customer.getName()), 502);
        }

        Integer profileId = aiProfile.getId() != null ? aiProfile.getId() : aiProfile.getAiProfileId();
        customer.setProfileId(profileId.toString());
        customer.setAiIdentityId(aiProfile.getUuid());
        customer.setImageUrl(resolveAiImageUrl(aiProfile, profileId));
    }

    private void syncOwnerImageWithAi(ProfileSaveRequest.Owner owner, MultipartFile ownerImage) {
        if (ownerImage == null) {
            return;
        }
        if (ownerImage.isEmpty()) {
            throw new ServiceException("ownerImage is empty", 400);
        }

        AiProfileResponse aiProfile = aiFaceApiClient.createProfile(trim(owner.getFullName()), List.of(ownerImage));
        if (aiProfile == null || (aiProfile.getId() == null && aiProfile.getAiProfileId() == null)) {
            throw new ServiceException("Create AI profile failed for owner: " + trim(owner.getFullName()), 502);
        }

        Integer profileId = aiProfile.getId() != null ? aiProfile.getId() : aiProfile.getAiProfileId();
        owner.setImageUrl(resolveAiImageUrl(aiProfile, profileId));
    }

    private List<MultipartFile> resolveCustomerImages(ProfileSaveRequest.Customer customer, List<MultipartFile> customerImages, Integer sequentialImageIndex) {
        if (customerImages == null || customerImages.isEmpty()) {
            return List.of();
        }

        if (customer.getImageIndexes() != null && !customer.getImageIndexes().isEmpty()) {
            List<MultipartFile> images = new ArrayList<>();
            Set<Integer> indexes = new HashSet<>();
            for (Integer imageIndex : customer.getImageIndexes()) {
                if (imageIndex == null) {
                    continue;
                }
                if (!indexes.add(imageIndex)) {
                    throw new ServiceException("Duplicate customer imageIndex: " + imageIndex, 400);
                }
                images.add(resolveCustomerImage(customerImages, imageIndex));
            }
            return images;
        }

        Integer imageIndex = customer.getImageIndex() == null ? sequentialImageIndex : customer.getImageIndex();
        if (imageIndex == null) {
            return List.of();
        }
        return List.of(resolveCustomerImage(customerImages, imageIndex));
    }

    private MultipartFile resolveCustomerImage(List<MultipartFile> customerImages, Integer imageIndex) {
        if (imageIndex < 0 || imageIndex >= customerImages.size()) {
            throw new ServiceException("Invalid customer imageIndex: " + imageIndex, 400);
        }
        MultipartFile image = customerImages.get(imageIndex);
        if (image == null || image.isEmpty()) {
            throw new ServiceException("Customer image is empty at index: " + imageIndex, 400);
        }
        return image;
    }

    private boolean shouldMapCustomerImagesSequentially(ProfileSaveRequest request, List<MultipartFile> customerImages) {
        if (customerImages == null || customerImages.isEmpty() || hasExplicitImageIndex(request)) {
            return false;
        }
        int imageCount = countNonEmptyImages(customerImages);
        int customerCount = countCustomers(request);
        if (imageCount != customerCount) {
            throw new ServiceException("customerImages count must equal customer count, or set customer.imageIndex for each image", 400);
        }
        return true;
    }

    private boolean hasExplicitImageIndex(ProfileSaveRequest request) {
        for (ProfileSaveRequest.Vehicle vehicle : request.getVehicles()) {
            for (ProfileSaveRequest.Customer customer : vehicle.getCustomers()) {
                if (customer.getImageIndex() != null || (customer.getImageIndexes() != null && !customer.getImageIndexes().isEmpty())) {
                    return true;
                }
            }
        }
        return false;
    }

    private int countCustomers(ProfileSaveRequest request) {
        int count = 0;
        for (ProfileSaveRequest.Vehicle vehicle : request.getVehicles()) {
            count += vehicle.getCustomers().size();
        }
        return count;
    }

    private int countNonEmptyImages(List<MultipartFile> customerImages) {
        int count = 0;
        for (MultipartFile image : customerImages) {
            if (image != null && !image.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private String resolveAiImageUrl(AiProfileResponse aiProfile, Integer profileId) {
        if (aiProfile.getImages() == null || aiProfile.getImages().isEmpty()) {
            throw new ServiceException("AI did not return image url for profileId: " + profileId, 502);
        }

        String rawAccessUrl = aiProfile.getImages().get(0).get("accessUrl");
        if (StringUtils.isBlank(rawAccessUrl)) {
            throw new ServiceException("AI did not return accessUrl for profileId: " + profileId, 502);
        }
        if (StringUtils.isBlank(imageUrlAi)) {
            return rawAccessUrl;
        }

        try {
            URI uri = new URI(rawAccessUrl);
            String pathAndQuery = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            return imageUrlAi + pathAndQuery;
        } catch (Exception ignored) {
            return rawAccessUrl;
        }
    }

    private Integer parseProfileId(String profileId) {
        try {
            return Integer.valueOf(profileId.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException("Invalid profileId: " + profileId, 400);
        }
    }

    private Integer parseNullableInteger(String value, String fieldName) {
        String trimmedValue = trimToNull(value);
        if (trimmedValue == null) {
            return null;
        }
        try {
            return Integer.valueOf(trimmedValue);
        } catch (NumberFormatException e) {
            throw new ServiceException("Invalid " + fieldName + ": " + value, 400);
        }
    }

    private String resolveAiIdentityId(String requestedAiIdentityId, String currentAiIdentityId) {
        String aiIdentityId = trimToNull(requestedAiIdentityId);
        if (aiIdentityId != null) {
            return aiIdentityId;
        }
        return StringUtils.defaultString(currentAiIdentityId);
    }

    private String trim(String value) {
        return value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.isBlank(value) ? null : value.trim();
    }

    private String normalizePlate(String plate) {
        return PlateNumberUtils.normalize(plate);
    }

    private String trimRequired(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            throw new ServiceException(fieldName + " must not be blank", 400);
        }
        return value.trim();
    }

    private OffsetDateTime toOffsetDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime();
        }
        if (value instanceof java.time.LocalDateTime localDateTime) {
            return localDateTime.atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime();
        }
        throw new ServiceException("Unsupported datetime type: " + value.getClass().getName(), 500);
    }

    private Integer toIntegerId(Long value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            throw new ServiceException(fieldName + " exceeds integer range: " + value, 500);
        }
        return value.intValue();
    }
}

