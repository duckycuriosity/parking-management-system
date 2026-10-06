package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AiFaceApiClient aiFaceApiClient;

    @Value("${ai.core.image-url:${ai.face.base-url:}}")
    private String imageUrlAi;

    @Transactional
    public OwnerAccountVehicleParkingCustomerResponse createProfile(ProfileSaveRequest request) {
        return createProfile(request, null);
    }

    @Transactional
    public OwnerAccountVehicleParkingCustomerResponse createProfile(ProfileSaveRequest request, List<MultipartFile> customerImages) {
        validateProfileRequest(request);

        OffsetDateTime now = OffsetDateTime.now();
        Long ownerId;
        if (request.getOwner().getId() == null) {
            ownerId = insertOwner(request.getOwner(), now);
        } else {
            ownerId = request.getOwner().getId();
            ensureOwnerExists(ownerId);
            updateOwner(ownerId, request.getOwner(), now);
        }

        List<OwnerAccountVehicleParkingCustomerResponse.VehicleResponse> vehicleResponses = new ArrayList<>();
        List<OwnerAccountVehicleParkingCustomerResponse.CustomerResponse> customerResponses = new ArrayList<>();
        List<OwnerAccountVehicleParkingCustomerResponse.VehicleCustomerResponse> relationResponses = new ArrayList<>();
        Set<Long> submittedCustomerIds = new HashSet<>();
        Set<Long> submittedRelationIds = new HashSet<>();
        boolean mapImagesSequentially = shouldMapCustomerImagesSequentially(request, customerImages);
        int imageCursor = 0;

        for (ProfileSaveRequest.Vehicle vehicle : request.getVehicles()) {
            Long vehicleId;
            if (vehicle.getId() == null) {
                vehicleId = insertVehicle(ownerId, vehicle, now);
            } else {
                vehicleId = vehicle.getId();
                ensureVehicleBelongsToOwner(ownerId, vehicleId);
                updateVehicle(vehicleId, ownerId, vehicle, now);
            }

            vehicleResponses.add(OwnerAccountVehicleParkingCustomerResponse.VehicleResponse.builder()
                    .id(vehicleId)
                    .plate(normalizePlate(vehicle.getPlate()))
                    .vehicleType(trim(vehicle.getVehicleType()))
                    .status(trim(vehicle.getStatus()))
                    .build());

            upsertVehicleSubscription(vehicleId, vehicle.getSubscription(), now);

            for (ProfileSaveRequest.Customer customer : vehicle.getCustomers()) {
                syncCustomerImageWithAi(customer, customerImages, mapImagesSequentially ? imageCursor++ : null);

                Long customerId;
                if (customer.getId() == null) {
                    customerId = insertCustomer(ownerId, customer, now);
                } else {
                    customerId = customer.getId();
                    ensureCustomerBelongsToOwner(ownerId, customerId);
                    updateCustomer(customerId, ownerId, customer, now);
                }
                submittedCustomerIds.add(customerId);

                Long relationId = null;
                if (isUsed(customer)) {
                    relationId = upsertVehicleCustomer(vehicleId, customerId, customer, now);
                    submittedRelationIds.add(relationId);
                }

                customerResponses.add(OwnerAccountVehicleParkingCustomerResponse.CustomerResponse.builder()
                        .id(customerId)
                        .name(trim(customer.getName()))
                        .phone(trimToNull(customer.getPhone()))
                        .imageUrl(trimToNull(customer.getImageUrl()))
                        .profileId(trimToNull(customer.getProfileId()))
                        .status(trim(customer.getStatus()))
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

        inactiveMissingCustomers(ownerId, submittedCustomerIds, now);
        inactiveMissingRelations(ownerId, submittedRelationIds, now);

        return OwnerAccountVehicleParkingCustomerResponse.builder()
                .id(ownerId)
                .fullName(trim(request.getOwner().getFullName()))
                .phone(trim(request.getOwner().getPhone()))
                .identityNumber(trim(request.getOwner().getIdentityNumber()))
                .address(trimToNull(request.getOwner().getAddress()))
                .imageUrl(trimToNull(request.getOwner().getImageUrl()))
                .zaloLink(trimToNull(request.getOwner().getZaloLink()))
                .note(trimToNull(request.getOwner().getNote()))
                .status(trim(request.getOwner().getStatus()))
                .vehicleCount((long) vehicleResponses.size())
                .createdAt(now)
                .updatedAt(now)
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

        Long vehicleId = jdbcTemplate.queryForObject("""
                INSERT INTO vehicle
                    (owner_id, plate, vehicle_type, status, created_at, updated_at)
                VALUES
                    (:ownerId, :plate, :vehicleType, :status, :createdAt, :updatedAt)
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("ownerId", ownerId)
                .addValue("plate", plate)
                .addValue("vehicleType", trim(request.getVehicleType()))
                .addValue("status", trim(request.getStatus()))
                .addValue("createdAt", now)
                .addValue("updatedAt", now), Long.class);

        List<SubscriptionVehicleCreateResponse.UserResponse> users = new ArrayList<>();
        if (request.getUsers() != null) {
            for (SubscriptionVehicleCreateRequest.ExistingUser user : request.getUsers()) {
                ensureCustomerBelongsToOwner(ownerId, user.getCustomerId());
                insertVehicleCustomer(vehicleId, user.getCustomerId(), trim(user.getRelationType()), trim(user.getStatus()), now);
                users.add(fetchCustomerRelation(vehicleId, user.getCustomerId()));
            }
        }
        if (request.getNewUsers() != null) {
            for (SubscriptionVehicleCreateRequest.NewUser user : request.getNewUsers()) {
                Long customerId = jdbcTemplate.queryForObject("""
                        INSERT INTO parkingcustomer
                            (owner_id, name, phone, image_url, profile_id, status, created_at, updated_at)
                        VALUES
                            (:ownerId, :name, :phone, :imageUrl, :profileId, :status, :createdAt, :updatedAt)
                        RETURNING id
                        """, new MapSqlParameterSource()
                        .addValue("ownerId", ownerId)
                        .addValue("name", trim(user.getName()))
                        .addValue("phone", trimToNull(user.getPhone()))
                        .addValue("imageUrl", trimToNull(user.getImageUrl()))
                        .addValue("profileId", trimToNull(user.getProfileId()))
                        .addValue("status", trim(user.getStatus()))
                        .addValue("createdAt", now)
                        .addValue("updatedAt", now), Long.class);
                insertVehicleCustomer(vehicleId, customerId, trim(user.getRelationType()), trim(user.getStatus()), now);
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
        ParkingCustomerResponse current = getCustomer(customerId);
        OffsetDateTime now = OffsetDateTime.now();

        jdbcTemplate.update("""
                UPDATE parkingcustomer
                SET name = :name,
                    phone = :phone,
                    image_url = :imageUrl,
                    profile_id = :profileId,
                    status = :status,
                    updated_at = :updatedAt
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", customerId)
                .addValue("name", request.getName() == null ? current.getName() : trimRequired(request.getName(), "name"))
                .addValue("phone", request.getPhone() == null ? current.getPhone() : trimToNull(request.getPhone()))
                .addValue("imageUrl", request.getImageUrl() == null ? current.getImageUrl() : trimToNull(request.getImageUrl()))
                .addValue("profileId", request.getProfileId() == null ? current.getProfileId() : trimToNull(request.getProfileId()))
                .addValue("status", request.getStatus() == null ? current.getStatus() : trimRequired(request.getStatus(), "status"))
                .addValue("updatedAt", now));

        return getCustomer(customerId);
    }

    @Transactional
    public SubscriptionVehicleCreateResponse.UserResponse saveVehicleUser(Long vehicleId, VehicleUserSaveRequest request) {
        Long ownerId = getVehicleOwnerId(vehicleId);
        OffsetDateTime now = OffsetDateTime.now();
        Long customerId = request.getCustomerId();

        if (customerId == null) {
            if (StringUtils.isBlank(request.getName())) {
                throw new ServiceException("name is required when creating a new vehicle user", 400);
            }
            customerId = jdbcTemplate.queryForObject("""
                    INSERT INTO parkingcustomer
                        (owner_id, name, phone, image_url, profile_id, status, created_at, updated_at)
                    VALUES
                        (:ownerId, :name, :phone, :imageUrl, :profileId, :status, :createdAt, :updatedAt)
                    RETURNING id
                    """, new MapSqlParameterSource()
                    .addValue("ownerId", ownerId)
                    .addValue("name", trim(request.getName()))
                    .addValue("phone", trimToNull(request.getPhone()))
                    .addValue("imageUrl", trimToNull(request.getImageUrl()))
                    .addValue("profileId", trimToNull(request.getProfileId()))
                    .addValue("status", trim(request.getStatus()))
                    .addValue("createdAt", now)
                    .addValue("updatedAt", now), Long.class);
        } else {
            ensureCustomerBelongsToOwner(ownerId, customerId);
            ParkingCustomerResponse current = getCustomer(customerId);
            updateCustomerFields(customerId, current, request, now);
        }

        upsertVehicleCustomer(vehicleId, customerId, trim(request.getRelationType()), trim(request.getStatus()), now);
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

    private Long insertOwner(ProfileSaveRequest.Owner owner, OffsetDateTime now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO owner_account
                    (full_name, phone, identity_number, address, image_url, zalo_link, note, status, created_at, updated_at)
                VALUES
                    (:fullName, :phone, :identityNumber, :address, :imageUrl, :zaloLink, :note, :status, :createdAt, :updatedAt)
                RETURNING id
                """, ownerParams(null, owner, now), Long.class);
    }

    private void updateOwner(Long ownerId, ProfileSaveRequest.Owner owner, OffsetDateTime now) {
        jdbcTemplate.update("""
                UPDATE owner_account
                SET full_name = :fullName,
                    phone = :phone,
                    identity_number = :identityNumber,
                    address = :address,
                    image_url = :imageUrl,
                    zalo_link = :zaloLink,
                    note = :note,
                    status = :status,
                    updated_at = :updatedAt
                WHERE id = :id
                """, ownerParams(ownerId, owner, now));
    }

    private MapSqlParameterSource ownerParams(Long ownerId, ProfileSaveRequest.Owner owner, OffsetDateTime now) {
        return new MapSqlParameterSource()
                .addValue("id", ownerId)
                .addValue("fullName", trim(owner.getFullName()))
                .addValue("phone", trim(owner.getPhone()))
                .addValue("identityNumber", trim(owner.getIdentityNumber()))
                .addValue("address", trimToNull(owner.getAddress()))
                .addValue("imageUrl", trimToNull(owner.getImageUrl()))
                .addValue("zaloLink", trimToNull(owner.getZaloLink()))
                .addValue("note", trimToNull(owner.getNote()))
                .addValue("status", trim(owner.getStatus()))
                .addValue("createdAt", now)
                .addValue("updatedAt", now);
    }

    private Long insertVehicle(Long ownerId, ProfileSaveRequest.Vehicle vehicle, OffsetDateTime now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO vehicle
                    (owner_id, plate, vehicle_type, status, created_at, updated_at)
                VALUES
                    (:ownerId, :plate, :vehicleType, :status, :createdAt, :updatedAt)
                RETURNING id
                """, vehicleParams(null, ownerId, vehicle, now), Long.class);
    }

    private void updateVehicle(Long vehicleId, Long ownerId, ProfileSaveRequest.Vehicle vehicle, OffsetDateTime now) {
        jdbcTemplate.update("""
                UPDATE vehicle
                SET plate = :plate,
                    vehicle_type = :vehicleType,
                    status = :status,
                    updated_at = :updatedAt
                WHERE id = :id
                  AND owner_id = :ownerId
                """, vehicleParams(vehicleId, ownerId, vehicle, now));
    }

    private MapSqlParameterSource vehicleParams(Long vehicleId, Long ownerId, ProfileSaveRequest.Vehicle vehicle, OffsetDateTime now) {
        return new MapSqlParameterSource()
                .addValue("id", vehicleId)
                .addValue("ownerId", ownerId)
                .addValue("plate", normalizePlate(vehicle.getPlate()))
                .addValue("vehicleType", trim(vehicle.getVehicleType()))
                .addValue("status", trim(vehicle.getStatus()))
                .addValue("createdAt", now)
                .addValue("updatedAt", now);
    }

    private void upsertVehicleSubscription(Long vehicleId, ProfileSaveRequest.Subscription subscription, OffsetDateTime now) {
        if (subscription == null) {
            return;
        }

        if (subscription.getId() == null) {
            jdbcTemplate.update("""
                    INSERT INTO vehicle_subscription
                        (vehicle_id, parking_lot_id, package_id, registration_type, previous_subscription_id,
                         price_vnd, starts_at, ends_at, payment_deadline_at, status, paid_at,
                         payment_source, payment_reference, payment_receipt, created_at, updated_at)
                    VALUES
                        (:vehicleId, :parkingLotId, :packageId, :registrationType, :previousSubscriptionId,
                         :priceVnd, :startsAt, :endsAt, :paymentDeadlineAt, :status, :paidAt,
                         :paymentSource, :paymentReference, CAST(:paymentReceipt AS jsonb), :createdAt, :updatedAt)
                    """, subscriptionParams(null, vehicleId, subscription, now));
            return;
        }

        jdbcTemplate.update("""
                UPDATE vehicle_subscription
                SET vehicle_id = :vehicleId,
                    parking_lot_id = :parkingLotId,
                    package_id = :packageId,
                    registration_type = :registrationType,
                    previous_subscription_id = :previousSubscriptionId,
                    price_vnd = :priceVnd,
                    starts_at = :startsAt,
                    ends_at = :endsAt,
                    payment_deadline_at = :paymentDeadlineAt,
                    status = :status,
                    paid_at = :paidAt,
                    payment_source = :paymentSource,
                    payment_reference = :paymentReference,
                    payment_receipt = CAST(:paymentReceipt AS jsonb),
                    updated_at = :updatedAt
                WHERE id = :id
                """, subscriptionParams(subscription.getId(), vehicleId, subscription, now));
    }

    private MapSqlParameterSource subscriptionParams(Integer subscriptionId, Long vehicleId, ProfileSaveRequest.Subscription subscription, OffsetDateTime now) {
        return new MapSqlParameterSource()
                .addValue("id", subscriptionId)
                .addValue("vehicleId", toIntegerId(vehicleId, "vehicleId"))
                .addValue("parkingLotId", subscription.getParkingLotId())
                .addValue("packageId", subscription.getPackageId())
                .addValue("registrationType", trim(subscription.getRegistrationType()))
                .addValue("previousSubscriptionId", subscription.getPreviousSubscriptionId())
                .addValue("priceVnd", subscription.getPriceVnd())
                .addValue("startsAt", subscription.getStartsAt())
                .addValue("endsAt", subscription.getEndsAt())
                .addValue("paymentDeadlineAt", subscription.getPaymentDeadlineAt())
                .addValue("status", trim(subscription.getStatus()))
                .addValue("paidAt", subscription.getPaidAt())
                .addValue("paymentSource", trimToNull(subscription.getPaymentSource()))
                .addValue("paymentReference", trimToNull(subscription.getPaymentReference()))
                .addValue("paymentReceipt", trimToNull(subscription.getPaymentReceipt()))
                .addValue("createdAt", now)
                .addValue("updatedAt", now);
    }

    private Long insertCustomer(Long ownerId, ProfileSaveRequest.Customer customer, OffsetDateTime now) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO parkingcustomer
                    (owner_id, name, phone, image_url, profile_id, status, created_at, updated_at)
                VALUES
                    (:ownerId, :name, :phone, :imageUrl, :profileId, :status, :createdAt, :updatedAt)
                RETURNING id
                """, customerParams(null, ownerId, customer, now), Long.class);
    }

    private void updateCustomer(Long customerId, Long ownerId, ProfileSaveRequest.Customer customer, OffsetDateTime now) {
        jdbcTemplate.update("""
                UPDATE parkingcustomer
                SET name = :name,
                    phone = :phone,
                    image_url = :imageUrl,
                    profile_id = :profileId,
                    status = :status,
                    updated_at = :updatedAt
                WHERE id = :id
                  AND owner_id = :ownerId
                """, customerParams(customerId, ownerId, customer, now));
    }

    private MapSqlParameterSource customerParams(Long customerId, Long ownerId, ProfileSaveRequest.Customer customer, OffsetDateTime now) {
        return new MapSqlParameterSource()
                .addValue("id", customerId)
                .addValue("ownerId", ownerId)
                .addValue("name", trim(customer.getName()))
                .addValue("phone", trimToNull(customer.getPhone()))
                .addValue("imageUrl", trimToNull(customer.getImageUrl()))
                .addValue("profileId", trimToNull(customer.getProfileId()))
                .addValue("status", trim(customer.getStatus()))
                .addValue("createdAt", now)
                .addValue("updatedAt", now);
    }

    private Long upsertVehicleCustomer(Long vehicleId, Long customerId, ProfileSaveRequest.Customer customer, OffsetDateTime now) {
        Long relationId = customer.getRelationId();
        if (relationId == null) {
            relationId = findVehicleCustomerId(vehicleId, customerId);
        }
        if (relationId == null) {
            relationId = jdbcTemplate.queryForObject("""
                    INSERT INTO vehicle_customer
                        (vehicle_id, customer_id, relation_type, status, created_at, updated_at)
                    VALUES
                        (:vehicleId, :customerId, :relationType, :status, :createdAt, :updatedAt)
                    RETURNING id
                    """, relationParams(null, vehicleId, customerId, customer, now), Long.class);
        } else {
            ensureRelationMatches(relationId, vehicleId, customerId);
            jdbcTemplate.update("""
                    UPDATE vehicle_customer
                    SET relation_type = :relationType,
                        status = :status,
                        updated_at = :updatedAt
                    WHERE id = :id
                    """, relationParams(relationId, vehicleId, customerId, customer, now));
        }
        return relationId;
    }

    private boolean isUsed(ProfileSaveRequest.Customer customer) {
        return customer.getIsUsed() == null || Boolean.TRUE.equals(customer.getIsUsed());
    }

    private MapSqlParameterSource relationParams(Long relationId, Long vehicleId, Long customerId, ProfileSaveRequest.Customer customer, OffsetDateTime now) {
        return new MapSqlParameterSource()
                .addValue("id", relationId)
                .addValue("vehicleId", vehicleId)
                .addValue("customerId", customerId)
                .addValue("relationType", trim(customer.getRelationType()))
                .addValue("status", trim(customer.getRelationStatus()))
                .addValue("createdAt", now)
                .addValue("updatedAt", now);
    }

    private void inactiveMissingCustomers(Long ownerId, Set<Long> submittedCustomerIds, OffsetDateTime now) {
        if (submittedCustomerIds.isEmpty()) {
            jdbcTemplate.update("""
                    UPDATE parkingcustomer
                    SET status = 'INACTIVE', updated_at = :updatedAt
                    WHERE owner_id = :ownerId
                    """, new MapSqlParameterSource()
                    .addValue("ownerId", ownerId)
                    .addValue("updatedAt", now));
            return;
        }
        jdbcTemplate.update("""
                UPDATE parkingcustomer
                SET status = 'INACTIVE', updated_at = :updatedAt
                WHERE owner_id = :ownerId
                  AND id NOT IN (:ids)
                """, new MapSqlParameterSource()
                .addValue("ownerId", ownerId)
                .addValue("ids", submittedCustomerIds)
                .addValue("updatedAt", now));
    }

    private void inactiveMissingRelations(Long ownerId, Set<Long> submittedRelationIds, OffsetDateTime now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ownerId", ownerId)
                .addValue("updatedAt", now);
        String notInClause = "";
        if (!submittedRelationIds.isEmpty()) {
            params.addValue("ids", submittedRelationIds);
            notInClause = "AND vc.id NOT IN (:ids)";
        }
        jdbcTemplate.update("""
                UPDATE vehicle_customer vc
                SET status = 'INACTIVE', updated_at = :updatedAt
                FROM vehicle v
                WHERE v.id = vc.vehicle_id
                  AND v.owner_id = :ownerId
                """ + notInClause, params);
    }

    private boolean ownerPhoneExists(String phone, Long excludedOwnerId) {
        return existsWithExcludedId("owner_account", "phone", phone, excludedOwnerId);
    }

    private boolean identityNumberExists(String identityNumber, Long excludedOwnerId) {
        return existsWithExcludedId("owner_account", "identity_number", identityNumber, excludedOwnerId);
    }

    private boolean customerPhoneExists(String phone, Long excludedCustomerId) {
        return existsWithExcludedId("parkingcustomer", "phone", phone, excludedCustomerId);
    }

    private boolean plateExists(String plate, Long excludedVehicleId) {
        return existsWithExcludedId("vehicle", "plate", plate, excludedVehicleId);
    }

    private boolean existsWithExcludedId(String table, String column, String value, Long excludedId) {
        String excludedClause = excludedId == null ? "" : " AND id <> :excludedId";
        MapSqlParameterSource params = new MapSqlParameterSource("value", value);
        if (excludedId != null) {
            params.addValue("excludedId", excludedId);
        }
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM %s WHERE %s = :value%s)
                """.formatted(table, column, excludedClause), params, Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    private void ensureVehicleBelongsToOwner(Long ownerId, Long vehicleId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM vehicle WHERE id = :vehicleId AND owner_id = :ownerId)
                """, new MapSqlParameterSource()
                .addValue("vehicleId", vehicleId)
                .addValue("ownerId", ownerId), Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ServiceException("Vehicle not found in owner account: " + vehicleId, 404);
        }
    }

    private void ensureRelationMatches(Long relationId, Long vehicleId, Long customerId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS(
                    SELECT 1
                    FROM vehicle_customer
                    WHERE id = :relationId
                      AND vehicle_id = :vehicleId
                      AND customer_id = :customerId
                )
                """, new MapSqlParameterSource()
                .addValue("relationId", relationId)
                .addValue("vehicleId", vehicleId)
                .addValue("customerId", customerId), Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ServiceException("Vehicle customer relation does not match vehicle/customer: " + relationId, 400);
        }
    }

    private Long findVehicleCustomerId(Long vehicleId, Long customerId) {
        return jdbcTemplate.query("""
                SELECT id
                FROM vehicle_customer
                WHERE vehicle_id = :vehicleId
                  AND customer_id = :customerId
                """, new MapSqlParameterSource()
                .addValue("vehicleId", vehicleId)
                .addValue("customerId", customerId), rs -> rs.next() ? toLong(rs.getObject("id")) : null);
    }

    private void insertVehicleCustomer(Long vehicleId, Long customerId, String relationType, String status, OffsetDateTime now) {
        if (vehicleCustomerExists(vehicleId, customerId)) {
            throw new ServiceException("Customer already assigned to vehicle", 400);
        }
        jdbcTemplate.update("""
                INSERT INTO vehicle_customer
                    (vehicle_id, customer_id, relation_type, status, created_at, updated_at)
                VALUES
                    (:vehicleId, :customerId, :relationType, :status, :createdAt, :updatedAt)
                """, new MapSqlParameterSource()
                .addValue("vehicleId", vehicleId)
                .addValue("customerId", customerId)
                .addValue("relationType", relationType)
                .addValue("status", status)
                .addValue("createdAt", now)
                .addValue("updatedAt", now));
    }

    private void upsertVehicleCustomer(Long vehicleId, Long customerId, String relationType, String status, OffsetDateTime now) {
        if (vehicleCustomerExists(vehicleId, customerId)) {
            jdbcTemplate.update("""
                    UPDATE vehicle_customer
                    SET relation_type = :relationType,
                        status = :status,
                        updated_at = :updatedAt
                    WHERE vehicle_id = :vehicleId
                      AND customer_id = :customerId
                    """, new MapSqlParameterSource()
                    .addValue("vehicleId", vehicleId)
                    .addValue("customerId", customerId)
                    .addValue("relationType", relationType)
                    .addValue("status", status)
                    .addValue("updatedAt", now));
            return;
        }

        jdbcTemplate.update("""
                INSERT INTO vehicle_customer
                    (vehicle_id, customer_id, relation_type, status, created_at, updated_at)
                VALUES
                    (:vehicleId, :customerId, :relationType, :status, :createdAt, :updatedAt)
                """, new MapSqlParameterSource()
                .addValue("vehicleId", vehicleId)
                .addValue("customerId", customerId)
                .addValue("relationType", relationType)
                .addValue("status", status)
                .addValue("createdAt", now)
                .addValue("updatedAt", now));
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
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM owner_account WHERE id = :ownerId)
                """, new MapSqlParameterSource("ownerId", ownerId), Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ServiceException("Owner account not found", 404);
        }
    }

    private void ensureCustomerBelongsToOwner(Long ownerId, Long customerId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM parkingcustomer WHERE id = :customerId AND owner_id = :ownerId)
                """, new MapSqlParameterSource()
                .addValue("customerId", customerId)
                .addValue("ownerId", ownerId), Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ServiceException("Customer not found in owner account: " + customerId, 404);
        }
    }

    private Long getVehicleOwnerId(Long vehicleId) {
        return jdbcTemplate.query("""
                SELECT owner_id
                FROM vehicle
                WHERE id = :vehicleId
                """, new MapSqlParameterSource("vehicleId", vehicleId), rs -> {
            if (!rs.next()) {
                throw new ServiceException("Vehicle not found", 404);
            }
            return toLong(rs.getObject("owner_id"));
        });
    }

    private boolean plateExists(String plate) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM vehicle WHERE plate = :plate)
                """, new MapSqlParameterSource("plate", plate), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    private boolean vehicleCustomerExists(Long vehicleId, Long customerId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM vehicle_customer WHERE vehicle_id = :vehicleId AND customer_id = :customerId)
                """, new MapSqlParameterSource()
                .addValue("vehicleId", vehicleId)
                .addValue("customerId", customerId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    private SubscriptionVehicleCreateResponse.UserResponse fetchCustomerRelation(Long vehicleId, Long customerId) {
        return jdbcTemplate.query("""
                SELECT
                    pc.id,
                    pc.name,
                    pc.phone,
                    pc.image_url,
                    pc.profile_id,
                    vc.relation_type,
                    vc.status AS relation_status
                FROM vehicle_customer vc
                JOIN parkingcustomer pc ON pc.id = vc.customer_id
                WHERE vc.vehicle_id = :vehicleId
                  AND vc.customer_id = :customerId
                """, new MapSqlParameterSource()
                .addValue("vehicleId", vehicleId)
                .addValue("customerId", customerId), rs -> {
            if (!rs.next()) {
                throw new ServiceException("Vehicle customer relation not found", 404);
            }
            return SubscriptionVehicleCreateResponse.UserResponse.builder()
                    .customerId(toLong(rs.getObject("id")))
                    .name(rs.getString("name"))
                    .phone(rs.getString("phone"))
                    .imageUrl(rs.getString("image_url"))
                    .profileId(rs.getString("profile_id"))
                    .relationType(rs.getString("relation_type"))
                    .relationStatus(rs.getString("relation_status"))
                    .build();
        });
    }

    private ParkingCustomerResponse getCustomer(Long customerId) {
        return jdbcTemplate.query("""
                SELECT id, owner_id, name, phone, image_url, profile_id, status, created_at, updated_at
                FROM parkingcustomer
                WHERE id = :id
                """, new MapSqlParameterSource("id", customerId), rs -> {
            if (!rs.next()) {
                throw new ServiceException("Customer not found", 404);
            }
            return ParkingCustomerResponse.builder()
                    .id(toLong(rs.getObject("id")))
                    .ownerId(toLong(rs.getObject("owner_id")))
                    .name(rs.getString("name"))
                    .phone(rs.getString("phone"))
                    .imageUrl(rs.getString("image_url"))
                    .profileId(rs.getString("profile_id"))
                    .status(rs.getString("status"))
                    .createdAt(toOffsetDateTime(rs.getObject("created_at")))
                    .updatedAt(toOffsetDateTime(rs.getObject("updated_at")))
                    .build();
        });
    }

    private void updateCustomerFields(Long customerId, ParkingCustomerResponse current, VehicleUserSaveRequest request, OffsetDateTime now) {
        jdbcTemplate.update("""
                UPDATE parkingcustomer
                SET name = :name,
                    phone = :phone,
                    image_url = :imageUrl,
                    profile_id = :profileId,
                    status = :status,
                    updated_at = :updatedAt
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", customerId)
                .addValue("name", request.getName() == null ? current.getName() : trimRequired(request.getName(), "name"))
                .addValue("phone", request.getPhone() == null ? current.getPhone() : trimToNull(request.getPhone()))
                .addValue("imageUrl", request.getImageUrl() == null ? current.getImageUrl() : trimToNull(request.getImageUrl()))
                .addValue("profileId", request.getProfileId() == null ? current.getProfileId() : trimToNull(request.getProfileId()))
                .addValue("status", trim(request.getStatus()))
                .addValue("updatedAt", now));
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
        customer.setImageUrl(resolveAiImageUrl(aiProfile, profileId));
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

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new ServiceException("Unsupported id type: " + value.getClass().getName(), 500);
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

