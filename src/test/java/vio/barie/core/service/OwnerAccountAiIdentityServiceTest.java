package vio.barie.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.multipart.MultipartFile;
import vio.barie.core.client.AiFaceApiClient;
import vio.barie.core.config.exception.ServiceException;
import vio.barie.core.dto.AiProfileResponse;
import vio.barie.core.entity.OwnerAccountEntity;
import vio.barie.core.entity.ParkingCustomerEntity;
import vio.barie.core.entity.converter.AiProfileIdConverter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OwnerAccountAiIdentityServiceTest {
    private EntityManager entityManager;
    private AiFaceApiClient client;
    private OwnerAccountAiIdentityService service;
    private OwnerAccountEntity owner;
    private ParkingCustomerEntity customer;
    private TypedQuery<Long> counts;
    private final List<MultipartFile> images = List.of(new MockMultipartFile("images", "face.jpg",
            "image/jpeg", new byte[]{1, 2, 3}));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        entityManager = mock(EntityManager.class);
        client = mock(AiFaceApiClient.class);
        owner = new OwnerAccountEntity();
        owner.setId(1L);
        owner.setFullName("Owner");
        owner.setPhone("0900000000");
        owner.setStatus("ACTIVE");
        customer = new ParkingCustomerEntity();
        customer.setId(2L);
        customer.setOwnerId(1L);
        customer.setUsername("Driver");
        counts = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(counts);
        when(counts.setParameter(anyString(), any())).thenReturn(counts);
        when(counts.getSingleResult()).thenReturn(0L);
        when(entityManager.find(OwnerAccountEntity.class, 1L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(owner);
        when(entityManager.find(ParkingCustomerEntity.class, 2L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(customer);
        service = new OwnerAccountAiIdentityService(entityManager, new TestTransactionManager(), client,
                new ObjectMapper(), "https://images.example");
    }

    @Test
    void persistsBackendProfileOnlyAfterCallOutsideTransaction() {
        when(client.createProfile(eq("Owner"), anyList())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(owner.getAiSyncState()).isEqualTo("IN_PROGRESS");
            assertThat(owner.getAiProfileId()).isNull();
            return profile(43);
        });
        var result = service.syncOwner(1L, images);
        assertThat(result.aiProfileId()).isEqualTo(43L);
        assertThat(owner.getAiProfileId()).isEqualTo(43L);
        assertThat(owner.getAiIdentityId()).isEqualTo("provider-uuid");
        assertThat(owner.getAiSyncState()).isEqualTo("SYNCED");
        assertThat(owner.getImageUrl()).isEqualTo("[\"https://images.example/face.jpg?size=small\"]");
        assertThat(owner.getAiSyncToken()).isNull();
    }

    @Test
    void usesPersistedOwnerProfileWhenUpdatingFace() {
        owner.setAiProfileId(43L);
        owner.setAiSyncState("SYNCED");
        when(client.updateProfile(eq(43), eq("Owner"), anyList())).thenReturn(profile(43));
        service.syncOwner(1L, images);
        verify(client, never()).createProfile(anyString(), anyList());
        verify(client).updateProfile(eq(43), eq("Owner"), anyList());
    }

    @Test
    void uncertainCreateDoesNotCreateAgainOnRetry() {
        when(client.createProfile(anyString(), anyList())).thenThrow(new ResourceAccessException("timeout"));
        assertThatThrownBy(() -> service.syncOwner(1L, images)).isInstanceOf(ServiceException.class);
        assertThat(owner.getAiSyncState()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(owner.getAiProfileId()).isNull();
        assertThatThrownBy(() -> service.syncOwner(1L, images)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("reconcile");
        verify(client, times(1)).createProfile(anyString(), anyList());
    }

    @Test
    void staleResponseDoesNotAttachAndRetainsProviderIdForUpdateRetry() {
        when(client.createProfile(anyString(), anyList())).thenAnswer(call -> {
            owner.setImageUrl("https://local.example/replacement.jpg");
            return profile(43);
        });
        var result = service.syncOwner(1L, images);
        assertThat(result.state()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(owner.getAiProfileId()).isNull();
        assertThat(owner.getAiSyncProfileId()).isEqualTo(43L);
        assertThat(owner.getImageUrl()).isEqualTo("https://local.example/replacement.jpg");
        when(client.updateProfile(eq(43), anyString(), anyList())).thenReturn(profile(43));
        service.syncOwner(1L, images);
        verify(client, times(1)).createProfile(anyString(), anyList());
        verify(client).updateProfile(eq(43), anyString(), anyList());
        assertThat(owner.getAiProfileId()).isEqualTo(43L);
    }

    @Test
    void explicitOwnerDriverLinkPropagatesAndSyncDriverReusesOwnerIdentity() {
        service.linkOwnerAsDriver(1L, 2L);
        assertThat(owner.getDriverCustomerId()).isEqualTo(2L);
        when(client.createProfile(eq("Owner"), anyList())).thenReturn(profile(43));
        service.syncDriver(1L, 2L, images);
        assertThat(customer.getAiProfileId()).isEqualTo(43);
        assertThat(customer.getAiIdentityId()).isEqualTo(owner.getAiIdentityId());
        assertThat(customer.getAiSyncState()).isEqualTo("SYNCED");
        when(client.updateProfile(eq(43), eq("Owner"), anyList())).thenReturn(profile(43));
        service.syncDriver(1L, 2L, images);
        verify(client, times(1)).createProfile(anyString(), anyList());
        verify(client).updateProfile(eq(43), eq("Owner"), anyList());
    }

    @Test
    void linksExistingDriverIdentityWithoutCallingCoreAi() {
        customer.setAiProfileId(43);
        customer.setAiIdentityId("provider-uuid");
        customer.setImageUrl("https://images.example/face.jpg");
        customer.setAiSyncState("SYNCED");
        service.linkOwnerAsDriver(1L, 2L);
        assertThat(owner.getAiProfileId()).isEqualTo(43L);
        assertThat(owner.getAiIdentityId()).isEqualTo("provider-uuid");
        verifyNoInteractions(client);
    }

    @Test
    void rejectsCrossOwnerDriverBeforeCallingCoreAi() {
        customer.setOwnerId(99L);
        assertThatThrownBy(() -> service.syncDriver(1L, 2L, images)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(client);
    }

    @Test
    void unreadableImageDoesNotClaimOrCallProvider() throws IOException {
        MultipartFile unreadable = mock(MultipartFile.class);
        when(unreadable.isEmpty()).thenReturn(false);
        when(unreadable.getBytes()).thenThrow(new IOException("local read failed"));
        assertThatThrownBy(() -> service.syncOwner(1L, List.of(unreadable)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("before starting");
        assertThat(owner.getAiSyncState()).isEqualTo("NOT_SYNCED");
        verifyNoInteractions(entityManager, client);
    }

    @Test
    void confirmedIdentityCollisionRequiresReconciliationInsteadOfDuplicateAttachment() {
        when(counts.getSingleResult()).thenReturn(1L);
        when(client.createProfile(anyString(), anyList())).thenReturn(profile(43));
        assertThatThrownBy(() -> service.syncOwner(1L, images)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("different PMS person");
        assertThat(owner.getAiProfileId()).isNull();
        assertThat(owner.getAiSyncState()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(owner.getAiSyncProfileId()).isEqualTo(43L);
    }

    @Test
    void inconsistentProviderResponseNeverChangesExistingId() {
        owner.setAiProfileId(43L);
        AiProfileResponse response = profile(98);
        response.setAiProfileId(99);
        when(client.updateProfile(eq(43), anyString(), anyList())).thenReturn(response);
        assertThatThrownBy(() -> service.syncOwner(1L, images)).isInstanceOf(ServiceException.class);
        assertThat(owner.getAiProfileId()).isEqualTo(43L);
        assertThat(owner.getAiSyncProfileId()).isEqualTo(43L);
    }

    @Test
    void changedProviderUuidNeverReplacesConfirmedPersonIdentity() {
        owner.setAiProfileId(43L);
        owner.setAiIdentityId("confirmed-original-uuid");
        when(client.updateProfile(eq(43), anyString(), anyList())).thenReturn(profile(43));
        assertThatThrownBy(() -> service.syncOwner(1L, images)).isInstanceOf(ServiceException.class)
                .hasMessageContaining("UUID changed");
        assertThat(owner.getAiIdentityId()).isEqualTo("confirmed-original-uuid");
        assertThat(owner.getAiSyncState()).isEqualTo("RECONCILIATION_REQUIRED");
    }

    @Test
    void bigintAdapterUsesActualDatabaseTypeWithoutTruncating() {
        AiProfileIdConverter converter = new AiProfileIdConverter();
        assertThat(converter.convertToDatabaseColumn(43)).isEqualTo(43L);
        assertThat(converter.convertToEntityAttribute(43L)).isEqualTo(43);
        assertThatThrownBy(() -> converter.convertToEntityAttribute(2_147_483_648L))
                .isInstanceOf(IllegalStateException.class);
    }

    private AiProfileResponse profile(int id) {
        AiProfileResponse response = new AiProfileResponse();
        response.setId(id);
        response.setUuid("provider-uuid");
        response.setImages(List.of(Map.of("accessUrl", "http://private-ai/face.jpg?size=small")));
        return response;
    }

    /** Real Spring transaction boundaries, with no persistence resource in these unit cases. */
    private static class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
