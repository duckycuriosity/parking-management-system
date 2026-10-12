package vio.barie.core.service;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vio.barie.core.client.AiFaceApiClient;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.*;
import vio.barie.core.dto.owneraccount.ProfileSaveRequest;
import vio.barie.core.entity.*;
import vio.barie.core.repository.OwnerAccountRepository;
import vio.barie.core.repository.ParkingCustomerRepository;
import vio.barie.core.repository.VehicleCustomerRepository;
import vio.barie.core.repository.VehicleRepository;
import vio.barie.core.utils.PlateNumberUtils;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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
    private final VehicleSubscriptionService vehicleSubscriptionService;
    private final PlatformTransactionManager transactionManager;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OwnerAccountVehicleParkingCustomerResponse createProfile(ProfileSaveRequest request) {
        return createProfile(request, null, null);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OwnerAccountVehicleParkingCustomerResponse createProfile(ProfileSaveRequest request, List<MultipartFile> images) {
        return createProfile(request, images, null);
    }

    /** AI is invoked once for each explicitly new person, before the transaction saving the local graph. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OwnerAccountVehicleParkingCustomerResponse createProfile(ProfileSaveRequest request,
            List<MultipartFile> images, List<MultipartFile> ownerImages) {
        if (request == null) throw new ServiceException("request is required", 400);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new ServiceException("Registration must run outside an existing database transaction", 409);
        Map<ProfileSaveRequest.Customer, String> keys = new IdentityHashMap<>();
        Map<String, Person> people = new LinkedHashMap<>();
        people.putAll(validateAndPrepare(request, images, ownerImages, keys));
        return createProfileOnce(request, keys, people);
    }

    private OwnerAccountVehicleParkingCustomerResponse createProfileOnce(ProfileSaveRequest request,
            Map<ProfileSaveRequest.Customer, String> keys, Map<String, Person> people) {
        Map<String, AiProfileResponse> faces = new LinkedHashMap<>();
        Set<Integer> returnedIds = new HashSet<>();
        people.forEach((key, person) -> {
            AiProfileResponse face = aiFaceApiClient.createProfile(person.name(), person.images());
            Integer id = face == null ? null : (face.getId() == null ? face.getAiProfileId() : face.getId());
            if (id == null || id <= 0 || (face.getId() != null && face.getAiProfileId() != null && !face.getId().equals(face.getAiProfileId()))
                    || StringUtils.isBlank(face.getUuid()) || face.getUuid().length() > 100
                    || (imageUrl(face) != null && imageUrl(face).length() > 500)) {
                throw new ServiceException("AI returned an invalid profile", 502);
            }
            if (!returnedIds.add(id) || parkingCustomerRepository.existsByAiProfileId(id)) {
                throw new ServiceException("AI profile already belongs to a registered person", 409);
            }
            faces.put(key, face);
        });
        return new TransactionTemplate(transactionManager).execute(status -> persistProfile(request, keys, people, faces));
    }

    private Map<String, Person> validateAndPrepare(ProfileSaveRequest request, List<MultipartFile> images,
            List<MultipartFile> ownerImages, Map<ProfileSaveRequest.Customer, String> keys) {
        if (request == null || request.getOwner() == null || request.getVehicles() == null || request.getVehicles().isEmpty()) {
            throw new ServiceException("owner and vehicles are required", 400);
        }
        ProfileSaveRequest.Owner owner = request.getOwner();
        OwnerAccountEntity selected = owner.getId() == null ? null : findOwner(owner.getId());
        if (selected == null) {
            required(owner.getFullName(), "fullName");
            String phone = required(owner.getPhone(), "phone");
            if (ownerAccountRepository.existsByPhone(phone)) throw new ServiceException("Owner phone already exists", 409);
            String identity = clean(owner.getIdentityNumber());
            if (identity != null && ownerAccountRepository.existsByIdentityNumber(identity)) {
                throw new ServiceException("Owner identity number already exists", 409);
            }
        } else if (owner.getFullName() != null || owner.getPhone() != null || owner.getIdentityNumber() != null
                || owner.getAddress() != null || owner.getZaloLink() != null || owner.getNote() != null) {
            throw new ServiceException("Select an existing owner by id; update their information separately", 400);
        }
        Map<String, Person> people = new LinkedHashMap<>();
        Set<String> plates = new HashSet<>();
        Set<Long> vehicleIds = new HashSet<>();
        Set<Integer> usedImages = new HashSet<>();
        boolean ownerImagesUsed = false;
        int newPerson = 0;
        for (ProfileSaveRequest.Vehicle vehicle : request.getVehicles()) {
            if (vehicle == null || (vehicle.getId() == null && (vehicle.getCustomers() == null || vehicle.getCustomers().isEmpty()))) {
                throw new ServiceException("Each new vehicle requires at least one driver", 400);
            }
            String type;
            if (vehicle.getId() == null) {
                String plate = normalizedPlate(vehicle.getPlate());
                if (!plates.add(plate) || vehicleRepository.existsByPlateNumber(plate)) {
                    throw new ServiceException("Vehicle plate already exists: " + plate, 409);
                }
                type = required(vehicle.getVehicleType(), "vehicleType");
                if (!Set.of("CAR", "MOTORBIKE", "OTHER").contains(type)) {
                    throw new ServiceException("Invalid vehicleType", 400);
                }
            } else {
                if (owner.getId() == null || !vehicleIds.add(vehicle.getId())) {
                    throw new ServiceException("Existing vehicle requires its owner and a unique vehicle id", 400);
                }
                if (vehicle.getPlate() != null || vehicle.getVehicleType() != null) {
                    throw new ServiceException("Select an existing vehicle by id", 400);
                }
                type = findOwnerVehicle(owner.getId(), vehicle.getId()).getVehicleType();
            }
            if (vehicle.getSubscription() != null) {
                vehicleSubscriptionService.validateRegistration(vehicle.getSubscription(), type);
            }
            Set<String> relations = new HashSet<>();
            for (ProfileSaveRequest.Customer customer : vehicle.getCustomers() == null ? List.<ProfileSaveRequest.Customer>of() : vehicle.getCustomers()) {
                if (customer == null) throw new ServiceException("Driver cannot be null", 400);
                String key;
                if (customer.getId() != null) {
                    if (owner.getId() == null) throw new ServiceException("Existing driver requires its owner", 400);
                    findOwnerCustomer(owner.getId(), customer.getId());
                    if (customer.isOwnerDriver() || customer.getName() != null || customer.getPhone() != null
                            || customer.getClientId() != null || customer.getImageIndexes() != null) {
                        throw new ServiceException("Select an existing driver by id", 400);
                    }
                    key = "id:" + customer.getId();
                } else {
                    String name = customer.isOwnerDriver()
                            ? (selected == null ? required(owner.getFullName(), "fullName") : selected.getFullName())
                            : required(customer.getName(), "driver name");
                    String phone = customer.isOwnerDriver()
                            ? (selected == null ? clean(owner.getPhone()) : selected.getPhone()) : clean(customer.getPhone());
                    if (phone != null && phone.length() > 20) throw new ServiceException("Driver phone exceeds 20 characters", 400);
                    if (customer.isOwnerDriver() && (customer.getName() != null || customer.getPhone() != null
                            || customer.getClientId() != null || customer.getImageIndexes() != null)) {
                        throw new ServiceException("ownerDriver uses owner details and ownerImage", 400);
                    }
                    key = customer.isOwnerDriver() ? "owner" : clean(customer.getClientId()) == null
                            ? "new:" + newPerson++ : "client:" + customer.getClientId().trim();
                    List<MultipartFile> files = customer.isOwnerDriver() ? checkedImages(ownerImages)
                            : selectedImages(images, customer.getImageIndexes());
                    Person person = new Person(name, phone, files);
                    Person previous = people.putIfAbsent(key, person);
                    if (previous != null && !previous.equals(person)) {
                        throw new ServiceException("Conflicting information for driver reference: " + key, 409);
                    }
                    if (previous == null && !customer.isOwnerDriver()) {
                        for (Integer index : customer.getImageIndexes()) {
                            if (!usedImages.add(index)) throw new ServiceException("An image belongs to more than one new driver", 400);
                        }
                    }
                    ownerImagesUsed |= customer.isOwnerDriver();
                }
                if (!relations.add(key)) throw new ServiceException("Driver is repeated for the same vehicle", 400);
                keys.put(customer, key);
            }
        }
        if (images != null && usedImages.size() != images.size()) throw new ServiceException("Some driver images are not assigned", 400);
        if (ownerImages != null && !ownerImages.isEmpty() && !ownerImagesUsed) {
            throw new ServiceException("ownerImage requires ownerDriver=true", 400);
        }
        return people;
    }

    private OwnerAccountVehicleParkingCustomerResponse persistProfile(ProfileSaveRequest request,
            Map<ProfileSaveRequest.Customer, String> keys, Map<String, Person> people, Map<String, AiProfileResponse> faces) {
        ProfileSaveRequest.Owner input = request.getOwner();
        OwnerAccountEntity owner = input.getId() == null ? new OwnerAccountEntity() : findOwner(input.getId());
        if (input.getId() == null) {
            owner.setFullName(required(input.getFullName(), "fullName"));
            owner.setPhone(required(input.getPhone(), "phone"));
            owner.setIdentityNumber(clean(input.getIdentityNumber()));
            owner.setAddress(clean(input.getAddress()));
            owner.setZaloLink(clean(input.getZaloLink()));
            owner.setNote(clean(input.getNote()));
            owner.setStatus("ACTIVE");
            owner = ownerAccountRepository.save(owner);
        }
        Long ownerId = owner.getId();
        if (faces.containsKey("owner")) {
            owner.setImageUrl(imageUrl(faces.get("owner")));
            ownerAccountRepository.save(owner);
        }
        Map<String, ParkingCustomerEntity> customers = new LinkedHashMap<>();
        for (Map.Entry<String, Person> entry : people.entrySet()) {
            AiProfileResponse face = faces.get(entry.getKey());
            ParkingCustomerEntity customer = new ParkingCustomerEntity();
            customer.setOwnerId(ownerId);
            customer.setUsername(entry.getValue().name());
            customer.setPhoneNumber(entry.getValue().phone());
            customer.setAiProfileId(face.getId() == null ? face.getAiProfileId() : face.getId());
            customer.setAiIdentityId(face.getUuid());
            customer.setImageUrl(imageUrl(face));
            customer.setStatus("ACTIVE");
            customers.put(entry.getKey(), parkingCustomerRepository.save(customer));
        }
        List<OwnerAccountVehicleParkingCustomerResponse.VehicleResponse> vehicles = new ArrayList<>();
        List<OwnerAccountVehicleParkingCustomerResponse.VehicleCustomerResponse> relations = new ArrayList<>();
        for (ProfileSaveRequest.Vehicle inputVehicle : request.getVehicles()) {
            VehicleEntity vehicle = inputVehicle.getId() == null ? new VehicleEntity() : findOwnerVehicle(ownerId, inputVehicle.getId());
            if (inputVehicle.getId() == null) {
                vehicle.setOwnerId(ownerId);
                vehicle.setPlateNumber(normalizedPlate(inputVehicle.getPlate()));
                vehicle.setVehicleType(inputVehicle.getVehicleType().trim());
                vehicle.setStatus("ACTIVE");
                vehicle = vehicleRepository.save(vehicle);
            }
            for (ProfileSaveRequest.Customer inputCustomer : inputVehicle.getCustomers() == null ? List.<ProfileSaveRequest.Customer>of() : inputVehicle.getCustomers()) {
                String key = keys.get(inputCustomer);
                ParkingCustomerEntity customer = customers.computeIfAbsent(key,
                        ignored -> findOwnerCustomer(ownerId, inputCustomer.getId()));
                VehicleCustomerEntity relation = saveRelation(vehicle.getId(), customer.getId(), relationType(inputCustomer.getRelationType()));
                relations.add(OwnerAccountVehicleParkingCustomerResponse.VehicleCustomerResponse.builder()
                        .id(relation.getId()).vehicleId(vehicle.getId()).customerId(customer.getId())
                        .relationType(relation.getRelationType()).status(relation.getStatus()).build());
            }
            VehicleSubscriptionEntity subscription = inputVehicle.getSubscription() == null ? null
                    : vehicleSubscriptionService.register(vehicle.getId(), inputVehicle.getSubscription());
            vehicles.add(OwnerAccountVehicleParkingCustomerResponse.VehicleResponse.builder()
                    .id(vehicle.getId()).plate(vehicle.getPlateNumber()).vehicleType(vehicle.getVehicleType())
                    .status(vehicle.getStatus()).createdAt(vehicle.getCreatedAt() == null ? null : vehicle.getCreatedAt().atZone(ZoneId.systemDefault()).toOffsetDateTime())
                    .subscription(subscriptionResponse(subscription)).build());
        }
        return OwnerAccountVehicleParkingCustomerResponse.builder()
                .id(ownerId).fullName(owner.getFullName()).phone(owner.getPhone()).identityNumber(owner.getIdentityNumber())
                .address(owner.getAddress()).imageUrl(owner.getImageUrl()).zaloLink(owner.getZaloLink()).note(owner.getNote())
                .status(owner.getStatus()).createdAt(owner.getCreatedAt()).updatedAt(owner.getUpdatedAt())
                .vehicleCount((long) vehicles.size()).vehicles(vehicles).vehicleCustomers(relations)
                .customers(customers.entrySet().stream().map(entry -> customerResponse(entry.getKey(), entry.getValue())).toList()).build();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SubscriptionVehicleCreateResponse addVehicle(Long ownerId, SubscriptionVehicleCreateRequest request) {
        return addVehicle(ownerId, request, null);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SubscriptionVehicleCreateResponse addVehicle(Long ownerId, SubscriptionVehicleCreateRequest request,
            List<MultipartFile> images) {
        ProfileSaveRequest registration = new ProfileSaveRequest();
        ProfileSaveRequest.Owner owner = new ProfileSaveRequest.Owner();
        owner.setId(ownerId);
        registration.setOwner(owner);
        ProfileSaveRequest.Vehicle vehicle = new ProfileSaveRequest.Vehicle();
        vehicle.setPlate(request.getPlate());
        vehicle.setVehicleType(request.getVehicleType());
        vehicle.setSubscription(request.getSubscription());
        List<ProfileSaveRequest.Customer> drivers = new ArrayList<>();
        if (request.getUsers() != null) request.getUsers().forEach(user -> {
            ProfileSaveRequest.Customer customer = new ProfileSaveRequest.Customer();
            customer.setId(user.getCustomerId());
            customer.setRelationType(user.getRelationType());
            drivers.add(customer);
        });
        if (request.getNewUsers() != null) request.getNewUsers().forEach(user -> {
            ProfileSaveRequest.Customer customer = new ProfileSaveRequest.Customer();
            customer.setName(user.getName());
            customer.setPhone(user.getPhone());
            customer.setImageIndexes(user.getImageIndexes());
            customer.setRelationType(user.getRelationType());
            drivers.add(customer);
        });
        vehicle.setCustomers(drivers);
        registration.setVehicles(List.of(vehicle));
        OwnerAccountVehicleParkingCustomerResponse result = createProfile(registration, images, null);
        var saved = result.getVehicles().getFirst();
        Map<Long, OwnerAccountVehicleParkingCustomerResponse.CustomerResponse> customers = new LinkedHashMap<>();
        result.getCustomers().forEach(customer -> customers.put(customer.getId(), customer));
        return SubscriptionVehicleCreateResponse.builder().vehicleId(saved.getId()).ownerId(ownerId)
                .plate(saved.getPlate()).vehicleType(saved.getVehicleType()).status(saved.getStatus())
                .createdAt(saved.getCreatedAt()).subscription(saved.getSubscription())
                .users(result.getVehicleCustomers().stream().map(relation -> {
                    var customer = customers.get(relation.getCustomerId());
                    return SubscriptionVehicleCreateResponse.UserResponse.builder().customerId(customer.getId())
                            .name(customer.getName()).phone(customer.getPhone()).imageUrl(customer.getImageUrl())
                            .aiProfileId(customer.getAiProfileId()).relationType(relation.getRelationType())
                            .relationStatus(relation.getStatus()).build();
                }).toList()).build();
    }

    @Transactional
    public ParkingCustomerResponse updateCustomer(Long ownerId, Long customerId, ParkingCustomerUpdateRequest request) {
        ParkingCustomerEntity customer = findOwnerCustomer(ownerId, customerId);
        if (request.getName() != null) customer.setUsername(required(request.getName(), "name"));
        if (request.getPhone() != null) customer.setPhoneNumber(clean(request.getPhone()));
        if (request.getStatus() != null) {
            if (!Set.of("ACTIVE", "INACTIVE").contains(request.getStatus())) throw new ServiceException("Invalid customer status", 400);
            customer.setStatus(request.getStatus());
        }
        customer = parkingCustomerRepository.save(customer);
        return ParkingCustomerResponse.builder().id(customer.getId()).ownerId(customer.getOwnerId())
                .name(customer.getUsername()).phone(customer.getPhoneNumber()).imageUrl(customer.getImageUrl())
                .aiProfileId(customer.getAiProfileId()).status(customer.getStatus())
                .createdAt(customer.getCreatedAt() == null ? null : customer.getCreatedAt().atZone(ZoneId.systemDefault()).toOffsetDateTime()).build();
    }

    @Transactional
    public SubscriptionVehicleCreateResponse.UserResponse saveVehicleUser(Long vehicleId, VehicleUserSaveRequest request) {
        VehicleEntity vehicle = vehicleRepository.findById(vehicleId).orElseThrow(() -> new ServiceException("Vehicle not found", 404));
        ParkingCustomerEntity customer = findOwnerCustomer(vehicle.getOwnerId(), request.getCustomerId());
        VehicleCustomerEntity relation = saveRelation(vehicleId, customer.getId(), relationType(request.getRelationType()));
        return SubscriptionVehicleCreateResponse.UserResponse.builder().customerId(customer.getId())
                .name(customer.getUsername()).phone(customer.getPhoneNumber()).imageUrl(customer.getImageUrl())
                .aiProfileId(customer.getAiProfileId()).relationType(relation.getRelationType()).relationStatus(relation.getStatus()).build();
    }

    @Transactional
    public void removeVehicleUser(Long vehicleId, Long customerId) {
        VehicleEntity vehicle = vehicleRepository.findById(vehicleId).orElseThrow(() -> new ServiceException("Vehicle not found", 404));
        findOwnerCustomer(vehicle.getOwnerId(), customerId);
        VehicleCustomerEntity relation = vehicleCustomerRepository.findByVehicleIdAndCustomerId(vehicleId, customerId)
                .orElseThrow(() -> new ServiceException("Vehicle driver relation not found", 404));
        relation.setStatus("INACTIVE");
        vehicleCustomerRepository.save(relation);
    }

    private VehicleCustomerEntity saveRelation(Long vehicleId, Long customerId, String type) {
        VehicleCustomerEntity relation = vehicleCustomerRepository.findByVehicleIdAndCustomerId(vehicleId, customerId).orElseGet(VehicleCustomerEntity::new);
        relation.setVehicleId(vehicleId);
        relation.setCustomerId(customerId);
        relation.setRelationType(type);
        relation.setStatus("ACTIVE");
        return vehicleCustomerRepository.save(relation);
    }

    private OwnerAccountEntity findOwner(Long id) {
        return ownerAccountRepository.findById(id).orElseThrow(() -> new ServiceException("Owner not found", 404));
    }

    private VehicleEntity findOwnerVehicle(Long ownerId, Long id) {
        return vehicleRepository.findByIdAndOwnerId(id, ownerId).orElseThrow(() -> new ServiceException("Vehicle does not belong to owner", 404));
    }

    private ParkingCustomerEntity findOwnerCustomer(Long ownerId, Long id) {
        if (ownerId == null || id == null) throw new ServiceException("Owner and driver are required", 400);
        return parkingCustomerRepository.findByIdAndOwnerId(id, ownerId).orElseThrow(() -> new ServiceException("Driver does not belong to owner", 404));
    }

    private List<MultipartFile> selectedImages(List<MultipartFile> images, List<Integer> indexes) {
        if (images == null || indexes == null || indexes.isEmpty() || new HashSet<>(indexes).size() != indexes.size()) {
            throw new ServiceException("Each new driver requires imageIndexes referring to uploaded images", 400);
        }
        List<MultipartFile> selected = new ArrayList<>();
        for (Integer index : indexes) {
            if (index == null || index < 0 || index >= images.size()) throw new ServiceException("Invalid image index", 400);
            selected.add(images.get(index));
        }
        return checkedImages(selected);
    }

    private List<MultipartFile> checkedImages(List<MultipartFile> images) {
        if (images == null || images.isEmpty() || images.size() > 5) throw new ServiceException("A new driver requires 1 to 5 face images", 400);
        for (MultipartFile image : images) {
            if (image == null || image.isEmpty()) throw new ServiceException("Face image is empty", 400);
            if (image.getContentType() != null && !image.getContentType().startsWith("image/")) {
                throw new ServiceException("Face upload must be an image", 400);
            }
        }
        return List.copyOf(images);
    }

    private String imageUrl(AiProfileResponse face) {
        if (face.getImages() == null) return null;
        return face.getImages().stream().map(image -> image.get("accessUrl")).filter(StringUtils::isNotBlank).findFirst().orElse(null);
    }

    private OwnerAccountVehicleParkingCustomerResponse.CustomerResponse customerResponse(String key, ParkingCustomerEntity customer) {
        return OwnerAccountVehicleParkingCustomerResponse.CustomerResponse.builder().id(customer.getId())
                .clientId(key.startsWith("client:") ? key.substring(7) : null).name(customer.getUsername())
                .phone(customer.getPhoneNumber()).imageUrl(customer.getImageUrl()).aiProfileId(customer.getAiProfileId()).status(customer.getStatus()).build();
    }

    private SubscriptionResponse subscriptionResponse(VehicleSubscriptionEntity subscription) {
        if (subscription == null) return null;
        return SubscriptionResponse.builder().id(subscription.getId()).packageId(subscription.getPackageId())
                .priceVnd(subscription.getPriceVnd()).registrationType(subscription.getRegistrationType()).status(subscription.getStatus())
                .startsAt(subscription.getStartsAt()).endsAt(subscription.getEndsAt()).paymentDeadlineAt(subscription.getPaymentDeadlineAt()).build();
    }

    private String normalizedPlate(String value) {
        String plate = PlateNumberUtils.normalize(required(value, "plate"));
        if (!PlateNumberUtils.isValid(plate)) throw new ServiceException("Invalid plate", 400);
        return plate;
    }

    private String relationType(String value) { return clean(value) == null ? "DRIVER" : value.trim(); }
    private String clean(String value) { return StringUtils.isBlank(value) ? null : value.trim(); }
    private String required(String value, String field) {
        if (StringUtils.isBlank(value)) throw new ServiceException(field + " is required", 400);
        return value.trim();
    }
    private record Person(String name, String phone, List<MultipartFile> images) {}
}
