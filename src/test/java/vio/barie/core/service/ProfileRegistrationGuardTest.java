package vio.barie.core.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockMultipartFile;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.OwnerAccountVehicleParkingCustomerResponse;
import vio.barie.core.dto.owneraccount.OwnerAccountDtos.SubscriptionResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfileRegistrationGuardTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final Map<String, String> saved = new ConcurrentHashMap<>();
    private final ProfileRegistrationGuard guard = new ProfileRegistrationGuard(redis);

    @BeforeEach void setup() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString())).thenAnswer(call ->
                saved.putIfAbsent(call.getArgument(0), call.getArgument(1)) == null);
        when(values.get(anyString())).thenAnswer(call -> saved.get(call.getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(call -> saved.remove(call.getArgument(0)) != null);
        doAnswer(call -> { saved.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString());
    }

    @Test void completedRegistrationReplaysWithoutRunningExternalCallsAgain() {
        AtomicInteger calls = new AtomicInteger();
        Map<?, ?> first = guard.execute("key", Map.of("owner", 3), null, null, Map.class,
                () -> { calls.incrementAndGet(); return Map.of("subscriptionId", 19); });
        Map<?, ?> second = guard.execute("key", Map.of("owner", 3), null, null, Map.class,
                () -> { fail("Replay must not call AI or write the graph"); return Map.of(); });
        assertEquals(first, second);
        assertEquals(1, calls.get());
    }

    @Test void changingInputUnderSameKeyConflicts() {
        guard.execute("key", Map.of("owner", 3), null, null, Map.class, Map::of);
        assertThrows(ServiceException.class, () -> guard.execute("key", Map.of("owner", 4),
                null, null, Map.class, () -> { fail("Changed request must not run"); return Map.of(); }));
    }

    @Test void photoContentsBelongToTheRequestFingerprint() {
        var image = new MockMultipartFile("images", "face.jpg", "image/jpeg", new byte[]{1,2});
        var changed = new MockMultipartFile("images", "face.jpg", "image/jpeg", new byte[]{1,3});
        guard.execute("key", Map.of("name", "A"), List.of(image), null, Map.class, Map::of);
        assertThrows(ServiceException.class, () -> guard.execute("key", Map.of("name", "A"),
                List.of(changed), null, Map.class, Map::of));
    }

    @Test void failureKeepsAnUncertainAttemptFromRunningAgain() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> guard.execute("key", Map.of(), null, null, Map.class,
                () -> { calls.incrementAndGet(); throw new IllegalStateException("Unknown result"); }));
        assertThrows(ServiceException.class, () -> guard.execute("key", Map.of(), null, null, Map.class,
                () -> { calls.incrementAndGet(); return Map.of(); }));
        assertEquals(1, calls.get());
    }

    @Test void responseCacheFailureAfterCommitDoesNotAllowANewRegistration() {
        doThrow(new IllegalStateException("Redis unavailable")).when(values).set(anyString(), anyString());
        AtomicInteger commits = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> guard.execute("key", Map.of(), null, null, Map.class,
                () -> { commits.incrementAndGet(); return Map.of("subscription", 1); }));
        assertThrows(ServiceException.class, () -> guard.execute("key", Map.of(), null, null, Map.class,
                () -> { commits.incrementAndGet(); return Map.of(); }));
        assertEquals(1, commits.get());
    }

    @Test void replaysTheActualResponseWithNestedPeriodAndOffsetTime() {
        var time = OffsetDateTime.parse("2026-10-11T09:00:00+07:00");
        var response = OwnerAccountVehicleParkingCustomerResponse.builder().id(4L).createdAt(time)
                .vehicles(List.of(OwnerAccountVehicleParkingCustomerResponse.VehicleResponse.builder().id(8L)
                        .subscription(SubscriptionResponse.builder().id(12L).endsAt(time.plusDays(30)).build()).build()))
                .build();
        guard.execute("key", Map.of(), null, null, OwnerAccountVehicleParkingCustomerResponse.class, () -> response);
        var replay = guard.execute("key", Map.of(), null, null, OwnerAccountVehicleParkingCustomerResponse.class,
                () -> { fail("Must deserialize the stored response"); return null; });
        assertEquals(4L, replay.getId());
        assertEquals(time.toInstant(), replay.getCreatedAt().toInstant());
        assertEquals(12L, replay.getVehicles().getFirst().getSubscription().getId());
        assertEquals(time.plusDays(30).toInstant(), replay.getVehicles().getFirst().getSubscription().getEndsAt().toInstant());
    }

    @Test void emptyKeyIsRejectedBeforeRedisOrOperation() {
        assertThrows(ServiceException.class, () -> guard.execute(" ", Map.of(), null, null, Map.class, Map::of));
        verifyNoInteractions(redis);
    }

    @Test void failedPreflightReleasesTheKeyBeforeAnySideEffect() {
        assertThrows(ServiceException.class, () -> guard.execute("key", Map.of("owner", 3), null, null, Map.class,
                () -> { throw new ServiceException("Invalid package", 400); },
                () -> { fail("No AI or DB write before successful validation"); return Map.of(); }));
        Map<?, ?> corrected = guard.execute("key", Map.of("owner", 4), null, null, Map.class,
                () -> {}, () -> Map.of("subscriptionId", 7));
        assertEquals(7, corrected.get("subscriptionId"));
    }

    @Test void completedReplayBypassesConditionsThatChangedAfterRegistration() {
        guard.execute("key", Map.of("owner", 3), null, null, Map.class, Map::of);
        guard.execute("key", Map.of("owner", 3), null, null, Map.class,
                () -> { fail("An existing owner or expired package must not block completed replay"); },
                () -> { fail("Replay must not call AI or DB writes"); return Map.of(); });
    }
}
