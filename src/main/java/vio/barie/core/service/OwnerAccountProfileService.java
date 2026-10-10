package vio.barie.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.*;
import vio.barie.core.dto.owneraccount.ProfileSaveRequest;
import vio.barie.core.dto.subscription.RegisterSubscriptionRequest;
import vio.barie.core.dto.subscription.SubscriptionResponse;
import vio.barie.core.entity.*;
import vio.barie.core.repository.*;
import vio.barie.core.utils.PlateNumberUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** DB-only orchestration. AI and QR operations are explicit actions after this command commits. */
@Service
@RequiredArgsConstructor
public class OwnerAccountProfileService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final OwnerAccountRepository owners;
    private final VehicleRepository vehicles;
    private final ParkingCustomerRepository customers;
    private final VehicleCustomerRepository relations;
    private final OwnerProfileRegistrationRepository registrations;
    private final VehicleSubscriptionService subscriptions;
    private final OwnerAccountAiIdentityService identities;
    private final EntityManager entityManager;

    @Transactional
    public OwnerAccountVehicleParkingCustomerResponse createProfile(ProfileSaveRequest request) {
        require(request != null && request.getOwner() != null, "owner is required");
        String key = required(request.getRequestKey(), "requestKey", 100);
        require(key.equals(request.getRequestKey()), "requestKey cannot contain leading/trailing whitespace");
        String hash = digest(canonical(request));
        OwnerProfileRegistrationEntity prior = registrations.findById(key).orElse(null);
        if (prior != null) {
            if (!hash.equals(prior.getRequestHash())) throw conflict("requestKey has different content", "PROFILE_REQUEST_CONFLICT");
            require(prior.getOwnerId() != null && prior.getResultIds() != null, "Registration result is incomplete");
            return result(key, owners.findById(prior.getOwnerId()).orElseThrow(() -> missing("Owner not found")),
                    decode(prior.getResultIds()));
        }

        // Claim before creating any person/vehicle. A race aborts this transaction; replay is a new request.
        OwnerProfileRegistrationEntity command = new OwnerProfileRegistrationEntity();
        command.setRequestKey(key);
        command.setRequestHash(hash);
        try {
            registrations.saveAndFlush(command);
        } catch (DataIntegrityViolationException ex) {
            throw conflict("Registration is being committed; retry the same requestKey", "PROFILE_REQUEST_CONCURRENT");
        }
        OwnerAccountEntity owner = selectOwner(request.getOwner());
        List<ProfileSaveRequest.Vehicle> inputs = list(request.getVehicles());
        require(inputs.size() <= 100, "At most 100 vehicles per command");
        Map<Long, VehicleEntity> locked = new HashMap<>();
        // Acquire existing rows in one stable order before any subscription service re-locks them.
        inputs.stream().map(ProfileSaveRequest.Vehicle::getId).filter(Objects::nonNull).distinct().sorted()
                .forEach(id -> locked.put(id, ownedVehicle(owner.getId(), id)));
        Set<Long> usedVehicles = new HashSet<>();
        Set<String> vehicleDrafts = new HashSet<>();
        Map<String, ParkingCustomerEntity> drafts = new HashMap<>();
        Map<String, String> draftContracts = new HashMap<>();
        Map<Long, SelectedCustomer> selectedCustomers = new LinkedHashMap<>();
        List<SelectedVehicle> selectedVehicles = new ArrayList<>();
        List<Long> selectedRelations = new ArrayList<>();
        int ordinal = 0;
        for (ProfileSaveRequest.Vehicle input : inputs) {
            require(input != null, "vehicle is required");
            VehicleEntity vehicle = selectVehicle(owner.getId(), input, locked);
            require(usedVehicles.add(vehicle.getId()), "Duplicate vehicle in command");
            if (input.getClientId() != null) require(vehicleDrafts.add(required(input.getClientId(), "vehicle.clientId", 100)), "Duplicate vehicle.clientId");
            Set<Long> linkedPeople = new HashSet<>();
            for (ProfileSaveRequest.Customer person : list(input.getCustomers())) {
                require(person != null, "customer is required");
                ParkingCustomerEntity customer = selectCustomer(owner, person, drafts, draftContracts);
                require(linkedPeople.add(customer.getId()), "Duplicate person for one vehicle");
                String relationType = relationType(person.getRelationType());
                VehicleCustomerEntity relation = relations.findByVehicleIdAndCustomerId(vehicle.getId(), customer.getId())
                        .orElseGet(() -> newRelation(vehicle.getId(), customer.getId()));
                relation.setRelationType(relationType);
                relation.setStatus("ACTIVE"); // This is an explicit grant action; omission does nothing.
                relations.saveAndFlush(relation);
                selectedRelations.add(relation.getId());
                selectedCustomers.putIfAbsent(customer.getId(), new SelectedCustomer(customer.getId(), person.getClientId()));
            }
            SubscriptionResponse subscription = null;
            if (input.getSubscription() != null) {
                require(relations.hasActiveUser(vehicle.getId()), "A subscription requires at least one active vehicle user");
                ProfileSaveRequest.Subscription selected = input.getSubscription();
                require(selected.getStartsAt() != null && selected.getPaymentDeadlineAt() != null, "startsAt and paymentDeadlineAt are required");
                RegisterSubscriptionRequest purchase = new RegisterSubscriptionRequest();
                purchase.setVehicleId(vehicle.getId());
                purchase.setParkingLotId(selected.getParkingLotId());
                purchase.setPackageId(selected.getPackageId());
                purchase.setStartsAt(selected.getStartsAt().toInstant());
                purchase.setPaymentDeadlineAt(selected.getPaymentDeadlineAt().toInstant());
                purchase.setRequestKey("profile:" + digest(encode(List.of(key, ordinal))));
                subscription = subscriptions.register(purchase);
            }
            selectedVehicles.add(new SelectedVehicle(vehicle.getId(), input.getClientId(), subscription == null ? null : subscription.getId()));
            ordinal++;
        }
        SavedIds saved = new SavedIds(selectedVehicles, new ArrayList<>(selectedCustomers.values()), selectedRelations);
        command.setOwnerId(owner.getId());
        command.setResultIds(encode(saved));
        registrations.saveAndFlush(command);
        return result(key, owner, saved);
    }

    @Transactional
    public SubscriptionVehicleCreateResponse addVehicle(Long ownerId, SubscriptionVehicleCreateRequest request) {
        require(request != null, "request is required");
        ProfileSaveRequest command = new ProfileSaveRequest();
        command.setRequestKey(request.getRequestKey());
        ProfileSaveRequest.Owner owner = new ProfileSaveRequest.Owner();
        owner.setId(ownerId);
        command.setOwner(owner);
        ProfileSaveRequest.Vehicle vehicle = new ProfileSaveRequest.Vehicle();
        vehicle.setPlate(request.getPlate());
        vehicle.setVehicleType(request.getVehicleType());
        vehicle.setCustomers(request.getCustomers());
        vehicle.setSubscription(request.getSubscription());
        command.setVehicles(List.of(vehicle));
        OwnerAccountVehicleParkingCustomerResponse result = createProfile(command);
        var saved = result.getVehicles().getFirst();
        Map<Long, OwnerAccountVehicleParkingCustomerResponse.CustomerResponse> people = result.getCustomers().stream()
                .collect(Collectors.toMap(OwnerAccountVehicleParkingCustomerResponse.CustomerResponse::getId, Function.identity()));
        return SubscriptionVehicleCreateResponse.builder().vehicleId(saved.getId()).ownerId(result.getId())
                .plate(saved.getPlate()).vehicleType(saved.getVehicleType()).status(saved.getStatus())
                .createdAt(result.getCreatedAt()).subscription(saved.getSubscription())
                .users(result.getVehicleCustomers().stream().map(r -> userResponse(people.get(r.getCustomerId()), r)).toList()).build();
    }

    @Transactional
    public SubscriptionVehicleCreateResponse.UserResponse saveVehicleUser(Long ownerId, Long vehicleId, VehicleUserSaveRequest request) {
        require(request != null, "request is required");
        ProfileSaveRequest command = new ProfileSaveRequest();
        command.setRequestKey(request.getRequestKey());
        ProfileSaveRequest.Owner owner = new ProfileSaveRequest.Owner();
        owner.setId(ownerId);
        command.setOwner(owner);
        ProfileSaveRequest.Vehicle vehicle = new ProfileSaveRequest.Vehicle();
        vehicle.setId(vehicleId);
        ProfileSaveRequest.Customer person = new ProfileSaveRequest.Customer();
        person.setId(request.getCustomerId());
        person.setName(request.getName());
        person.setPhone(request.getPhone());
        person.setOwnerAsDriver(request.isOwnerAsDriver());
        person.setRelationType(request.getRelationType());
        vehicle.setCustomers(List.of(person));
        command.setVehicles(List.of(vehicle));
        var result = createProfile(command);
        return userResponse(result.getCustomers().getFirst(), result.getVehicleCustomers().getFirst());
    }

    @Transactional
    public ParkingCustomerResponse updateCustomer(Long ownerId, Long customerId, ParkingCustomerUpdateRequest request) {
        lockOwner(ownerId);
        ParkingCustomerEntity customer = ownedCustomer(ownerId, customerId);
        if (request.getName() != null) customer.setUsername(required(request.getName(), "name", 255));
        if (request.getPhone() != null) customer.setPhoneNumber(optional(request.getPhone(), 20));
        if (request.getStatus() != null) {
            require(Set.of("ACTIVE", "INACTIVE").contains(request.getStatus()), "Unsupported customer status");
            customer.setStatus(request.getStatus());
        }
        customers.saveAndFlush(customer);
        return customerResponse(customer);
    }

    @Transactional
    public void revokeVehicleUser(Long ownerId, Long vehicleId, Long customerId) {
        lockOwner(ownerId);
        ownedVehicle(ownerId, vehicleId);
        ownedCustomer(ownerId, customerId);
        VehicleCustomerEntity relation = relations.findByVehicleIdAndCustomerId(vehicleId, customerId)
                .orElseThrow(() -> missing("Vehicle user relation not found"));
        relation.setStatus("REVOKED");
        relations.save(relation);
    }

    @Transactional(readOnly = true)
    public void requireOwnedSubscription(Long ownerId, Long subscriptionId) {
        positive(ownerId, "ownerId");
        SubscriptionResponse subscription = subscriptions.getById(subscriptionId);
        if (vehicles.findByIdAndOwnerId(subscription.getVehicleId(), ownerId).isEmpty()) {
            throw missing("Subscription not found in owner scope");
        }
    }

    private OwnerAccountEntity selectOwner(ProfileSaveRequest.Owner input) {
        if (input.getId() != null) {
            require(input.getFullName() == null && input.getPhone() == null && input.getIdentityNumber() == null
                    && input.getAddress() == null && input.getZaloLink() == null && input.getNote() == null,
                    "Existing owner accepts id only; use the owner update action");
            OwnerAccountEntity owner = lockOwner(input.getId());
            require(!"INACTIVE".equals(owner.getStatus()), "Owner is inactive");
            return owner;
        }
        OwnerAccountEntity owner = new OwnerAccountEntity();
        owner.setFullName(required(input.getFullName(), "owner.fullName", 255));
        owner.setPhone(required(input.getPhone(), "owner.phone", 30));
        owner.setIdentityNumber(optional(input.getIdentityNumber(), 30));
        if (owners.existsByPhone(owner.getPhone())) throw conflict("Owner phone already exists; select its ID explicitly", "OWNER_PHONE_CONFLICT");
        if (owner.getIdentityNumber() != null && owners.existsByIdentityNumber(owner.getIdentityNumber())) throw conflict("Owner identityNumber already exists", "OWNER_IDENTITY_CONFLICT");
        owner.setAddress(optional(input.getAddress(), 10000));
        owner.setZaloLink(optional(input.getZaloLink(), 2000));
        owner.setNote(optional(input.getNote(), 10000));
        owner.setStatus("ACTIVE");
        try {
            return owners.saveAndFlush(owner);
        } catch (DataIntegrityViolationException failure) {
            throw OwnerProfileIntegrity.uniqueConflict(failure);
        }
    }

    private VehicleEntity selectVehicle(Long ownerId, ProfileSaveRequest.Vehicle input, Map<Long, VehicleEntity> locked) {
        if (input.getId() != null) {
            require(input.getPlate() == null && input.getVehicleType() == null, "Existing vehicle accepts id only");
            VehicleEntity vehicle = locked.get(input.getId());
            require(vehicle != null, "Vehicle not in owner scope");
            require(!"INACTIVE".equals(vehicle.getStatus()), "Vehicle is inactive");
            return vehicle;
        }
        String plate = PlateNumberUtils.normalize(required(input.getPlate(), "plate", 50));
        require(plate != null && !plate.isBlank(), "Invalid plate");
        String type = required(input.getVehicleType(), "vehicleType", 20);
        require(Set.of("CAR", "MOTORBIKE").contains(type), "vehicleType must be CAR or MOTORBIKE");
        if (vehicles.existsByPlateNumber(plate)) throw conflict("Plate already exists; select its ID explicitly", "VEHICLE_PLATE_CONFLICT");
        VehicleEntity vehicle = new VehicleEntity();
        vehicle.setOwnerId(ownerId);
        vehicle.setPlateNumber(plate);
        vehicle.setVehicleType(type);
        vehicle.setStatus("ACTIVE");
        try {
            return vehicles.saveAndFlush(vehicle);
        } catch (DataIntegrityViolationException failure) {
            throw OwnerProfileIntegrity.uniqueConflict(failure);
        }
    }

    private ParkingCustomerEntity selectCustomer(OwnerAccountEntity owner, ProfileSaveRequest.Customer input,
            Map<String, ParkingCustomerEntity> drafts, Map<String, String> contracts) {
        require(input.getClientId() == null || !input.getClientId().isBlank(), "customer.clientId cannot be blank");
        if (input.isOwnerAsDriver()) {
            require(input.getName() == null && input.getPhone() == null && input.getClientId() == null,
                    "ownerAsDriver uses the owner's persisted person data");
            ParkingCustomerEntity person;
            if (input.getId() != null) person = ownedCustomer(owner.getId(), input.getId());
            else if (owner.getDriverCustomerId() != null) person = ownedCustomer(owner.getId(), owner.getDriverCustomerId());
            else person = newCustomer(owner.getId(), owner.getFullName(), optional(owner.getPhone(), 20));
            identities.linkOwnerAsDriver(owner.getId(), person.getId());
            // The identity service joins this transaction. Refresh must not discard its pending link.
            entityManager.flush();
            entityManager.refresh(owner);
            entityManager.refresh(person);
            require(!"INACTIVE".equals(person.getStatus()), "Driver is inactive");
            return person;
        }
        if (input.getId() != null) {
            require(input.getName() == null && input.getPhone() == null && input.getClientId() == null,
                    "Existing customer accepts id and relationType only");
            ParkingCustomerEntity person = ownedCustomer(owner.getId(), input.getId());
            require(!"INACTIVE".equals(person.getStatus()), "Driver is inactive");
            return person;
        }
        String name = required(input.getName(), "customer.name", 255);
        String phone = optional(input.getPhone(), 20);
        String clientId = optional(input.getClientId(), 100);
        String contract = encode(Arrays.asList(name, phone));
        if (clientId != null && drafts.containsKey(clientId)) {
            require(contract.equals(contracts.get(clientId)), "One customer.clientId cannot describe different people");
            return drafts.get(clientId);
        }
        ParkingCustomerEntity customer = newCustomer(owner.getId(), name, phone);
        if (clientId != null) {
            drafts.put(clientId, customer);
            contracts.put(clientId, contract);
        }
        return customer;
    }

    private ParkingCustomerEntity newCustomer(Long ownerId, String name, String phone) {
        ParkingCustomerEntity person = new ParkingCustomerEntity();
        person.setOwnerId(ownerId);
        person.setUsername(name);
        person.setPhoneNumber(phone);
        person.setStatus("ACTIVE");
        return customers.saveAndFlush(person);
    }
    private OwnerAccountEntity lockOwner(Long id) {
        positive(id, "ownerId");
        OwnerAccountEntity owner = owners.findForUpdate(id).orElseThrow(() -> missing("Owner not found"));
        entityManager.refresh(owner, LockModeType.PESSIMISTIC_WRITE);
        return owner;
    }
    private VehicleEntity ownedVehicle(Long ownerId, Long id) {
        positive(id, "vehicleId");
        VehicleEntity vehicle = vehicles.findOwnedForUpdate(id, ownerId).orElseThrow(() -> missing("Vehicle not found in owner scope"));
        entityManager.refresh(vehicle, LockModeType.PESSIMISTIC_WRITE);
        return vehicle;
    }
    private ParkingCustomerEntity ownedCustomer(Long ownerId, Long id) {
        positive(id, "customerId");
        ParkingCustomerEntity person = customers.findOwnedForUpdate(id, ownerId).orElseThrow(() -> missing("Customer not found in owner scope"));
        entityManager.refresh(person, LockModeType.PESSIMISTIC_WRITE);
        return person;
    }
    private VehicleCustomerEntity newRelation(Long vehicleId, Long customerId) {
        VehicleCustomerEntity relation = new VehicleCustomerEntity();
        relation.setVehicleId(vehicleId);
        relation.setCustomerId(customerId);
        return relation;
    }

    private OwnerAccountVehicleParkingCustomerResponse result(String key, OwnerAccountEntity owner, SavedIds saved) {
        Map<Long, VehicleEntity> vehicleRows = vehicles.findAllById(saved.vehicles().stream().map(SelectedVehicle::id).toList()).stream()
                .collect(Collectors.toMap(VehicleEntity::getId, Function.identity()));
        Map<Long, ParkingCustomerEntity> people = customers.findAllById(saved.customers().stream().map(SelectedCustomer::id).toList()).stream()
                .collect(Collectors.toMap(ParkingCustomerEntity::getId, Function.identity()));
        Map<Long, VehicleCustomerEntity> links = relations.findAllById(saved.relations()).stream()
                .collect(Collectors.toMap(VehicleCustomerEntity::getId, Function.identity()));
        Map<Long, SubscriptionResponse> purchases = subscriptions.getByIds(saved.vehicles().stream()
                .map(SelectedVehicle::subscriptionId).filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(SubscriptionResponse::getId, Function.identity()));
        var vehicleResults = saved.vehicles().stream().map(row -> {
            VehicleEntity v = vehicleRows.get(row.id());
            if (v == null || !Objects.equals(owner.getId(), v.getOwnerId())) throw missing("Saved vehicle no longer belongs to owner");
            return OwnerAccountVehicleParkingCustomerResponse.VehicleResponse.builder().id(v.getId()).clientId(row.clientId())
                    .plate(v.getPlateNumber()).vehicleType(v.getVehicleType()).status(v.getStatus())
                    .subscription(row.subscriptionId() == null ? null : purchases.get(row.subscriptionId())).build();
        }).toList();
        var customerResults = saved.customers().stream().map(row -> {
            ParkingCustomerEntity person = people.get(row.id());
            if (person == null || !Objects.equals(owner.getId(), person.getOwnerId())) throw missing("Saved customer no longer belongs to owner");
            return OwnerAccountVehicleParkingCustomerResponse.CustomerResponse.builder().id(person.getId()).clientId(row.clientId())
                    .name(person.getUsername()).phone(person.getPhoneNumber()).imageUrl(person.getImageUrl())
                    .aiProfileId(aiId(person)).profileId(person.getAiProfileId() == null ? null : person.getAiProfileId().toString())
                    .aiSyncState(person.getAiSyncState()).status(person.getStatus()).build();
        }).toList();
        var linkResults = saved.relations().stream().map(id -> {
            VehicleCustomerEntity link = links.get(id);
            if (link == null) throw missing("Saved vehicle user relation no longer exists");
            if (!vehicleRows.containsKey(link.getVehicleId()) || !people.containsKey(link.getCustomerId())) {
                throw missing("Saved relation no longer belongs to this registration");
            }
            return OwnerAccountVehicleParkingCustomerResponse.VehicleCustomerResponse.builder()
                    .id(link.getId()).vehicleId(link.getVehicleId()).customerId(link.getCustomerId())
                    .relationType(link.getRelationType()).status(link.getStatus()).build();
        }).toList();
        return OwnerAccountVehicleParkingCustomerResponse.builder().requestKey(key).id(owner.getId()).fullName(owner.getFullName())
                .phone(owner.getPhone()).identityNumber(owner.getIdentityNumber()).address(owner.getAddress()).imageUrl(owner.getImageUrl())
                .zaloLink(owner.getZaloLink()).note(owner.getNote()).status(owner.getStatus()).aiProfileId(owner.getAiProfileId())
                .aiSyncState(owner.getAiSyncState()).driverCustomerId(owner.getDriverCustomerId())
                .createdAt(owner.getCreatedAt()).updatedAt(owner.getUpdatedAt()).vehicleCount(vehicles.countByOwnerId(owner.getId()))
                .vehicles(vehicleResults).customers(customerResults).vehicleCustomers(linkResults).build();
    }
    private SubscriptionVehicleCreateResponse.UserResponse userResponse(
            OwnerAccountVehicleParkingCustomerResponse.CustomerResponse person,
            OwnerAccountVehicleParkingCustomerResponse.VehicleCustomerResponse link) {
        return SubscriptionVehicleCreateResponse.UserResponse.builder().customerId(person.getId()).name(person.getName()).phone(person.getPhone())
                .imageUrl(person.getImageUrl()).aiProfileId(person.getAiProfileId()).profileId(person.getProfileId()).aiSyncState(person.getAiSyncState())
                .relationType(link.getRelationType()).relationStatus(link.getStatus()).build();
    }
    private ParkingCustomerResponse customerResponse(ParkingCustomerEntity c) {
        return ParkingCustomerResponse.builder().id(c.getId()).ownerId(c.getOwnerId()).name(c.getUsername()).phone(c.getPhoneNumber())
                .imageUrl(c.getImageUrl()).aiProfileId(aiId(c)).profileId(c.getAiProfileId() == null ? null : c.getAiProfileId().toString())
                .aiSyncState(c.getAiSyncState()).status(c.getStatus()).createdAt(utc(c.getCreatedAt())).build();
    }

    // Only primitive choices participate in hashing; no mutable entity, display value or gateway result.
    private String canonical(ProfileSaveRequest request) {
        var owner = request.getOwner();
        List<Object> parts = new ArrayList<>();
        parts.add(Arrays.asList(owner.getId(), owner.getFullName(), owner.getPhone(), owner.getIdentityNumber(),
                owner.getAddress(), owner.getZaloLink(), owner.getNote()));
        List<Object> vv = new ArrayList<>();
        for (var vehicle : list(request.getVehicles())) {
            require(vehicle != null, "vehicle is required");
            List<Object> pp = new ArrayList<>();
            for (var person : list(vehicle.getCustomers())) {
                require(person != null, "customer is required");
                pp.add(Arrays.asList(person.getId(), person.getClientId(), person.isOwnerAsDriver(),
                        person.getName(), person.getPhone(), person.getRelationType()));
            }
            var sub = vehicle.getSubscription();
            Object purchase = sub == null ? null : Arrays.asList(sub.getParkingLotId(), sub.getPackageId(),
                    sub.getStartsAt() == null ? null : sub.getStartsAt().toInstant().toString(),
                    sub.getPaymentDeadlineAt() == null ? null : sub.getPaymentDeadlineAt().toInstant().toString());
            vv.add(Arrays.asList(vehicle.getId(), vehicle.getClientId(), vehicle.getPlate(), vehicle.getVehicleType(), pp, purchase));
        }
        parts.add(vv);
        return encode(parts);
    }
    private static String encode(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot encode registration", e); }
    }
    private static SavedIds decode(String value) {
        try { return JSON.readValue(value, SavedIds.class); }
        catch (Exception e) { throw new IllegalStateException("Cannot read registration result", e); }
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String required(String value, String field, int max) {
        require(value != null && !value.isBlank() && value.trim().length() <= max, field + " is required or too long");
        return value.trim();
    }
    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        require(value.trim().length() <= max, "Field exceeds maximum length");
        return value.trim();
    }
    private static String relationType(String value) {
        require(Set.of("OWNER", "AUTHORIZED_USER").contains(value == null ? "" : value), "relationType must be OWNER or AUTHORIZED_USER");
        return value;
    }
    private static void positive(Long value, String field) { require(value != null && value > 0, field + " must be positive"); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new BusinessException(message, "PROFILE_INVALID", 400);
    }
    private static BusinessException missing(String message) { return new BusinessException(message, "PROFILE_NOT_FOUND", 404); }
    private static BusinessException conflict(String message, String code) { return new BusinessException(message, code, 409); }
    private static <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }
    private static Long aiId(ParkingCustomerEntity c) { return c.getAiProfileId() == null ? null : c.getAiProfileId().longValue(); }
    private static OffsetDateTime utc(LocalDateTime value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }

    public record SavedIds(List<SelectedVehicle> vehicles, List<SelectedCustomer> customers, List<Long> relations) {}
    public record SelectedVehicle(Long id, String clientId, Long subscriptionId) {}
    public record SelectedCustomer(Long id, String clientId) {}
}
