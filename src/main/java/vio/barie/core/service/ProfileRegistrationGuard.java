package vio.barie.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import vio.barie.core.config.exception.ServiceException;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/** Registration replay uses existing Redis, never an additional PostgreSQL table. */
@Component
public class ProfileRegistrationGuard {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public ProfileRegistrationGuard(StringRedisTemplate redis) { this.redis = redis; }

    public <T> T execute(String requestKey, Object command, List<MultipartFile> images,
            List<MultipartFile> ownerImages, Class<T> responseType, Supplier<T> action) {
        return execute(requestKey, command, images, ownerImages, responseType, () -> {}, action);
    }

    public <T> T execute(String requestKey, Object command, List<MultipartFile> images,
            List<MultipartFile> ownerImages, Class<T> responseType, Runnable preflight, Supplier<T> action) {
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 100)
            throw new ServiceException("requestKey must contain 1 to 100 characters", 400);
        String hash = fingerprint(command, images, ownerImages);
        String key = "pms:profile-registration:" + requestKey;
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, hash))) {
            String saved = redis.opsForValue().get(key);
            if (saved == null || !saved.startsWith(hash))
                throw new ServiceException("requestKey already identifies different registration data", 409);
            if (saved.length() == hash.length())
                throw new ServiceException("Registration is in progress or needs reconciliation; do not create another request", 409);
            try { return mapper.readValue(saved.substring(hash.length() + 1), responseType); }
            catch (Exception ex) { throw new ServiceException("Cannot read the saved registration result", ex); }
        }
        try { preflight.run(); }
        catch (RuntimeException ex) { redis.delete(key); throw ex; }
        // No automatic expiry or retry after an uncertain result. Redis persistence must be configured.
        // A crash between DB commit and saving the response needs reconciliation, not another AI CREATE.
        T result = action.get();
        try { redis.opsForValue().set(key, hash + "\n" + mapper.writeValueAsString(result)); }
        catch (RuntimeException ex) { throw ex; }
        catch (Exception ex) { throw new ServiceException("Cannot save the registration result", ex); }
        return result;
    }

    private String fingerprint(Object command, List<MultipartFile> images, List<MultipartFile> ownerImages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(mapper.writeValueAsBytes(command));
            for (List<MultipartFile> group : List.of(images == null ? List.<MultipartFile>of() : images,
                    ownerImages == null ? List.<MultipartFile>of() : ownerImages)) {
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(group.size()).array());
                for (MultipartFile image : group) {
                    if (image == null || image.isEmpty()) throw new ServiceException("Face images must not be empty", 400);
                    byte[] bytes = image.getBytes();
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (ServiceException ex) { throw ex; }
        catch (Exception ex) { throw new ServiceException("Cannot read registration data or images", 400); }
    }
}
