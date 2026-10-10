package vio.barie.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vio.barie.core.client.AiFaceApiClient;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.entity.OwnerAccountEntity;
import vio.barie.core.entity.ParkingCustomerEntity;

import java.net.URI;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persist claim -> call the existing Core AI client without a DB transaction ->
 * apply the matching result. An uncertain CREATE is never blindly repeated:
 * the current provider has no demonstrated idempotency or correlation lookup.
 */
@Service
public class OwnerAccountAiIdentityService {
    private static final Logger log = LoggerFactory.getLogger(OwnerAccountAiIdentityService.class);
    public static final String NOT_SYNCED = "NOT_SYNCED";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String SYNCED = "SYNCED";
    public static final String RECONCILIATION_REQUIRED = "RECONCILIATION_REQUIRED";

    private final EntityManager entityManager;
    private final TransactionTemplate transactions;
    private final AiFaceApiClient client;
    private final ObjectMapper objectMapper;
    private final String imageBaseUrl;

    public OwnerAccountAiIdentityService(EntityManager entityManager,
                                         PlatformTransactionManager transactionManager,
                                         AiFaceApiClient client,
                                         ObjectMapper objectMapper,
                                         @Value("${ai.core.image-url:}") String imageBaseUrl) {
        this.entityManager = entityManager;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.client = client;
        this.objectMapper = objectMapper;
        this.imageBaseUrl = imageBaseUrl;
    }

    public record AiIdentityResult(Long aiProfileId, List<String> imageUrls, String state) { }

    private record Claim(Long ownerId, Long customerId, String token, String name,
                         String oldImageUrl, Long profileId, String identityUuid) {
        boolean isOwner() { return customerId == null; }
    }

    private record ProviderProfile(Long id, String uuid, List<String> imageUrls) { }

    private record PreparedImage(String name, String originalFilename, String contentType,
                                 byte[] bytes) implements MultipartFile {
        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), bytes); }
    }

    @Transactional(propagation = Propagation.NEVER)
    public AiIdentityResult syncOwner(Long ownerId, List<MultipartFile> images) {
        images = prepareImages(images);
        Claim claim = transactions.execute(status -> claimOwner(ownerId));
        return synchronize(Objects.requireNonNull(claim), images);
    }

    @Transactional(propagation = Propagation.NEVER)
    public AiIdentityResult syncDriver(Long ownerId, Long customerId, List<MultipartFile> images) {
        images = prepareImages(images);
        Claim claim = transactions.execute(status -> {
            OwnerAccountEntity owner = lockOwner(ownerId);
            if (Objects.equals(owner.getDriverCustomerId(), customerId)) {
                return claimOwner(ownerId);
            }
            ParkingCustomerEntity customer = lockCustomer(ownerId, customerId);
            Long profileId = customer.getAiProfileId() == null ? customer.getAiSyncProfileId()
                    : customer.getAiProfileId().longValue();
            validateClaim(customer.getAiSyncState(), profileId);
            String token = UUID.randomUUID().toString();
            customer.setAiSyncState(IN_PROGRESS);
            customer.setAiSyncToken(token);
            customer.setAiSyncStartedAt(OffsetDateTime.now());
            return new Claim(ownerId, customerId, token, customer.getUsername(), customer.getImageUrl(), profileId,
                    customer.getAiIdentityId());
        });
        return synchronize(Objects.requireNonNull(claim), images);
    }

    /** Explicitly declares that an existing customer and this owner are one person. */
    @Transactional
    public void linkOwnerAsDriver(Long ownerId, Long customerId) {
        OwnerAccountEntity owner = lockOwner(ownerId);
        ParkingCustomerEntity customer = lockCustomer(ownerId, customerId);
        if (owner.getDriverCustomerId() != null && !Objects.equals(owner.getDriverCustomerId(), customerId)) {
            throw new ServiceException("Owner already has a different same-person driver link", 409);
        }
        if (IN_PROGRESS.equals(owner.getAiSyncState()) || IN_PROGRESS.equals(customer.getAiSyncState())) {
            throw new ServiceException("Cannot change a same-person link during AI synchronization", 409);
        }
        if (RECONCILIATION_REQUIRED.equals(owner.getAiSyncState())
                || RECONCILIATION_REQUIRED.equals(customer.getAiSyncState())) {
            throw new ServiceException("Reconcile the existing AI attempt before linking identities", 409);
        }
        Long driverProfileId = customer.getAiProfileId() == null ? null : customer.getAiProfileId().longValue();
        if (owner.getAiProfileId() != null && driverProfileId != null
                && !Objects.equals(owner.getAiProfileId(), driverProfileId)) {
            throw new ServiceException("Owner and driver already have different AI identities; reconcile them first", 409);
        }
        if (owner.getAiIdentityId() != null && customer.getAiIdentityId() != null
                && !customer.getAiIdentityId().startsWith("temp_customer")
                && !Objects.equals(owner.getAiIdentityId(), customer.getAiIdentityId())) {
            throw new ServiceException("Owner and driver have different provider UUIDs; reconcile them first", 409);
        }
        if (owner.getAiProfileId() == null && driverProfileId != null) {
            owner.setAiProfileId(driverProfileId);
            owner.setAiIdentityId(customer.getAiIdentityId());
            owner.setImageUrl(serializeImages(customer.getImageUrl() == null
                    ? List.of() : List.of(customer.getImageUrl())));
            owner.setAiSyncState(customer.getAiSyncState());
        }
        owner.setDriverCustomerId(customerId);
        if (owner.getAiProfileId() != null) assertIdentityAvailable(owner.getId(), customerId, owner.getAiProfileId());
        propagateToDriver(owner);
    }

    private Claim claimOwner(Long ownerId) {
        OwnerAccountEntity owner = lockOwner(ownerId);
        Long profileId = owner.getAiProfileId() == null ? owner.getAiSyncProfileId() : owner.getAiProfileId();
        validateClaim(owner.getAiSyncState(), profileId);
        String token = UUID.randomUUID().toString();
        owner.setAiSyncState(IN_PROGRESS);
        owner.setAiSyncToken(token);
        owner.setAiSyncStartedAt(OffsetDateTime.now());
        if (owner.getDriverCustomerId() != null) {
            ParkingCustomerEntity driver = lockCustomer(ownerId, owner.getDriverCustomerId());
            if (IN_PROGRESS.equals(driver.getAiSyncState())) {
                throw new ServiceException("Driver AI synchronization is already in progress", 409);
            }
            driver.setAiSyncState(IN_PROGRESS);
            driver.setAiSyncToken(token);
            driver.setAiSyncStartedAt(owner.getAiSyncStartedAt());
        }
        return new Claim(ownerId, null, token, owner.getFullName(), owner.getImageUrl(), profileId, owner.getAiIdentityId());
    }

    private AiIdentityResult synchronize(Claim claim, List<MultipartFile> images) {
        Long knownProviderId = claim.profileId();
        try {
            AiProfileResponse response = claim.profileId() == null
                    ? client.createProfile(claim.name(), images)
                    : client.updateProfile(providerId(claim.profileId()), claim.name(), images);
            if (response != null) {
                Integer returnedId = response.getId() == null ? response.getAiProfileId() : response.getId();
                boolean consistentIds = response.getId() == null || response.getAiProfileId() == null
                        || Objects.equals(response.getId(), response.getAiProfileId());
                if (returnedId != null && returnedId > 0 && consistentIds
                        && (claim.profileId() == null || Objects.equals(claim.profileId(), returnedId.longValue()))) {
                    knownProviderId = returnedId.longValue();
                }
            }
            ProviderProfile profile = validateResponse(claim, response);
            AiIdentityResult result = transactions.execute(status -> apply(claim, profile));
            return Objects.requireNonNull(result);
        } catch (RuntimeException e) {
            Long retainedId = knownProviderId;
            transactions.executeWithoutResult(status -> markUncertain(claim, retainedId));
            log.warn("Core AI synchronization requires review: ownerId={}, customerId={}, attempt={}, knownProfileId={}",
                    claim.ownerId(), claim.customerId(), claim.token(), retainedId, e);
            if (e instanceof ServiceException serviceException && serviceException.getStatusCode() == 409) {
                throw e;
            }
            throw new ServiceException("AI synchronization could not be confirmed; check aiSyncState before retrying", 502);
        }
    }

    private AiIdentityResult apply(Claim claim, ProviderProfile profile) {
        if (claim.isOwner()) {
            OwnerAccountEntity owner = lockOwner(claim.ownerId());
            if (!Objects.equals(owner.getAiSyncToken(), claim.token())) {
                throw new ServiceException("AI response belongs to an obsolete synchronization attempt", 409);
            }
            owner.setAiSyncProfileId(profile.id());
            assertIdentityAvailable(owner.getId(), owner.getDriverCustomerId(), profile.id());
            if (!Objects.equals(owner.getFullName(), claim.name())
                    || !Objects.equals(owner.getImageUrl(), claim.oldImageUrl())) {
                owner.setAiSyncState(RECONCILIATION_REQUIRED);
                owner.setAiSyncToken(null);
                markLinkedDriverUncertain(owner, claim.token(), profile.id());
                return new AiIdentityResult(owner.getAiProfileId(), currentOwnerImages(owner), RECONCILIATION_REQUIRED);
            }
            owner.setAiProfileId(profile.id());
            if (profile.uuid() != null) owner.setAiIdentityId(profile.uuid());
            owner.setImageUrl(serializeImages(profile.imageUrls()));
            owner.setAiSyncState(SYNCED);
            owner.setAiSyncToken(null);
            owner.setAiSyncProfileId(null);
            propagateToDriver(owner);
            return new AiIdentityResult(profile.id(), profile.imageUrls(), SYNCED);
        }
        lockOwner(claim.ownerId());
        ParkingCustomerEntity customer = lockCustomer(claim.ownerId(), claim.customerId());
        if (!Objects.equals(customer.getAiSyncToken(), claim.token())) {
            throw new ServiceException("AI response belongs to an obsolete synchronization attempt", 409);
        }
        customer.setAiSyncProfileId(profile.id());
        assertIdentityAvailable(claim.ownerId(), claim.customerId(), profile.id());
        if (!Objects.equals(customer.getUsername(), claim.name())
                || !Objects.equals(customer.getImageUrl(), claim.oldImageUrl())) {
            customer.setAiSyncState(RECONCILIATION_REQUIRED);
            customer.setAiSyncToken(null);
            return new AiIdentityResult(customer.getAiProfileId() == null ? null : customer.getAiProfileId().longValue(),
                    customer.getImageUrl() == null ? List.of() : List.of(customer.getImageUrl()), RECONCILIATION_REQUIRED);
        }
        customer.setAiProfileId(providerId(profile.id()));
        if (profile.uuid() != null) customer.setAiIdentityId(profile.uuid());
        customer.setImageUrl(profile.imageUrls().getFirst());
        customer.setAiSyncState(SYNCED);
        customer.setAiSyncToken(null);
        customer.setAiSyncProfileId(null);
        return new AiIdentityResult(profile.id(), profile.imageUrls(), SYNCED);
    }

    private void markUncertain(Claim claim, Long knownProviderId) {
        if (claim.isOwner()) {
            OwnerAccountEntity owner = lockOwner(claim.ownerId());
            if (Objects.equals(owner.getAiSyncToken(), claim.token())) {
                owner.setAiSyncState(RECONCILIATION_REQUIRED);
                owner.setAiSyncProfileId(knownProviderId);
                owner.setAiSyncToken(null);
                markLinkedDriverUncertain(owner, claim.token(), knownProviderId);
            }
        } else {
            lockOwner(claim.ownerId());
            ParkingCustomerEntity customer = lockCustomer(claim.ownerId(), claim.customerId());
            if (Objects.equals(customer.getAiSyncToken(), claim.token())) {
                customer.setAiSyncState(RECONCILIATION_REQUIRED);
                customer.setAiSyncProfileId(knownProviderId);
                customer.setAiSyncToken(null);
            }
        }
    }

    private void markLinkedDriverUncertain(OwnerAccountEntity owner, String token, Long profileId) {
        if (owner.getDriverCustomerId() != null) {
            ParkingCustomerEntity driver = lockCustomer(owner.getId(), owner.getDriverCustomerId());
            if (Objects.equals(driver.getAiSyncToken(), token)) {
                driver.setAiSyncState(RECONCILIATION_REQUIRED);
                driver.setAiSyncProfileId(profileId);
                driver.setAiSyncToken(null);
            }
        }
    }

    private void propagateToDriver(OwnerAccountEntity owner) {
        if (owner.getDriverCustomerId() == null) return;
        ParkingCustomerEntity driver = lockCustomer(owner.getId(), owner.getDriverCustomerId());
        driver.setAiProfileId(owner.getAiProfileId() == null ? null : providerId(owner.getAiProfileId()));
        driver.setAiIdentityId(owner.getAiIdentityId());
        List<String> images = currentOwnerImages(owner);
        driver.setImageUrl(images.isEmpty() ? null : images.getFirst());
        driver.setAiSyncState(owner.getAiSyncState());
        driver.setAiSyncProfileId(owner.getAiSyncProfileId());
        driver.setAiSyncToken(owner.getAiSyncToken());
        driver.setAiSyncStartedAt(owner.getAiSyncStartedAt());
    }

    private void assertIdentityAvailable(Long ownerId, Long intendedCustomerId, Long profileId) {
        Long customers = entityManager.createQuery("""
                select count(c) from ParkingCustomerEntity c
                where c.aiProfileId = :profileId and (:customerId is null or c.id <> :customerId)
                """, Long.class)
                .setParameter("profileId", providerId(profileId))
                .setParameter("customerId", intendedCustomerId)
                .getSingleResult();
        Long owners = entityManager.createQuery("""
                select count(o) from OwnerAccountEntity o
                where o.aiProfileId = :profileId
                  and (o.id <> :ownerId or (:customerId is not null
                    and (o.driverCustomerId is null or o.driverCustomerId <> :customerId)))
                """, Long.class)
                .setParameter("profileId", profileId)
                .setParameter("ownerId", ownerId)
                .setParameter("customerId", intendedCustomerId)
                .getSingleResult();
        if (customers > 0 || owners > 0) {
            throw new ServiceException("Core AI identity is already attached to a different PMS person; reconcile first", 409);
        }
    }

    private OwnerAccountEntity lockOwner(Long ownerId) {
        if (ownerId == null) throw new ServiceException("ownerId is required", 400);
        OwnerAccountEntity owner = entityManager.find(OwnerAccountEntity.class, ownerId, LockModeType.PESSIMISTIC_WRITE);
        if (owner == null) throw new ServiceException("Owner account not found", 404);
        return owner;
    }

    private ParkingCustomerEntity lockCustomer(Long ownerId, Long customerId) {
        if (customerId == null) throw new ServiceException("customerId is required", 400);
        ParkingCustomerEntity customer = entityManager.find(ParkingCustomerEntity.class, customerId,
                LockModeType.PESSIMISTIC_WRITE);
        if (customer == null || !Objects.equals(customer.getOwnerId(), ownerId)) {
            throw new ServiceException("Driver not found in this owner account", 404);
        }
        return customer;
    }

    private static void validateClaim(String state, Long knownProfileId) {
        if (IN_PROGRESS.equals(state)) {
            throw new ServiceException("AI synchronization is in progress; a crashed attempt requires reconciliation", 409);
        }
        if (RECONCILIATION_REQUIRED.equals(state) && knownProfileId == null) {
            throw new ServiceException("Previous AI create result is uncertain; reconcile with Core AI before creating again", 409);
        }
        if (knownProfileId != null) providerId(knownProfileId);
    }

    private ProviderProfile validateResponse(Claim claim, AiProfileResponse response) {
        if (response == null || (response.getId() == null && response.getAiProfileId() == null)) {
            throw new ServiceException("Core AI returned no profile identifier", 502);
        }
        if (response.getId() != null && response.getAiProfileId() != null
                && !Objects.equals(response.getId(), response.getAiProfileId())) {
            throw new ServiceException("Core AI returned inconsistent profile identifiers", 502);
        }
        Long id = (response.getId() == null ? response.getAiProfileId() : response.getId()).longValue();
        if (id <= 0 || (claim.profileId() != null && !Objects.equals(claim.profileId(), id))) {
            throw new ServiceException("Core AI response does not match the requested profile", 502);
        }
        List<String> urls = new ArrayList<>();
        if (response.getImages() != null) {
            response.getImages().forEach(image -> {
                if (image == null) return;
                String raw = image.get("accessUrl");
                if (raw == null || raw.isBlank()) return;
                URI parsed;
                try {
                    parsed = URI.create(raw);
                } catch (IllegalArgumentException e) {
                    throw new ServiceException("Core AI returned an invalid image URL", 502);
                }
                String url = raw;
                if (!imageBaseUrl.isBlank()) {
                    String path = parsed.getRawPath();
                    if (path == null || path.isBlank()) {
                        throw new ServiceException("Core AI image URL has no path", 502);
                    }
                    url = imageBaseUrl.replaceAll("/+$", "") + (path.startsWith("/") ? path : "/" + path)
                            + (parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery());
                }
                if (url.length() > 500) throw new ServiceException("Core AI image URL exceeds the person storage limit", 502);
                urls.add(url);
            });
        }
        if (urls.isEmpty()) throw new ServiceException("Core AI returned no usable face image", 502);
        String uuid = response.getUuid();
        if (uuid != null && uuid.length() > 100) throw new ServiceException("Core AI identity exceeds the storage limit", 502);
        // Historical MQTT placeholder UUIDs are local markers, not provider identity proof.
        if (claim.identityUuid() != null && !claim.identityUuid().startsWith("temp_customer")
                && uuid != null && !Objects.equals(claim.identityUuid(), uuid)) {
            throw new ServiceException("Core AI profile UUID changed; reconcile the person identity before attaching it", 409);
        }
        return new ProviderProfile(id, uuid, List.copyOf(urls));
    }

    private static int providerId(Long profileId) {
        try {
            int id = Math.toIntExact(profileId);
            if (id <= 0) throw new ArithmeticException("Non-positive profile ID");
            return id;
        } catch (ArithmeticException e) {
            throw new ServiceException("AI profile ID is outside the current Core AI/MQTT contract", 409);
        }
    }

    private static List<MultipartFile> prepareImages(List<MultipartFile> images) {
        if (images == null || images.isEmpty() || images.size() > 5
                || images.stream().anyMatch(image -> image == null || image.isEmpty())) {
            throw new ServiceException("Supply between one and five non-empty face images", 400);
        }
        List<MultipartFile> prepared = new ArrayList<>();
        for (MultipartFile image : images) {
            try {
                byte[] bytes = image.getBytes();
                if (bytes.length == 0) throw new ServiceException("Face image must not be empty", 400);
                prepared.add(new PreparedImage(image.getName(), image.getOriginalFilename(),
                        image.getContentType(), bytes));
            } catch (IOException e) {
                throw new ServiceException("Cannot read face image before starting AI synchronization", 400);
            }
        }
        return List.copyOf(prepared);
    }

    private String serializeImages(List<String> urls) {
        try {
            return objectMapper.writeValueAsString(urls);
        } catch (JsonProcessingException e) {
            throw new ServiceException("Cannot serialize AI image URLs", 500);
        }
    }

    private List<String> currentOwnerImages(OwnerAccountEntity owner) {
        if (owner.getImageUrl() == null || owner.getImageUrl().isBlank()) return List.of();
        try {
            return objectMapper.readValue(owner.getImageUrl(), new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            return List.of(owner.getImageUrl());
        }
    }
}
