package vio.barie.core.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import vio.barie.core.common.SubscriptionCoverageMode;
import vio.barie.core.common.SubscriptionPackageStatus;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.dto.subscription.CreateSubscriptionPackageRequest;
import vio.barie.core.dto.subscription.SubscriptionPackageSaleRequest;
import vio.barie.core.dto.subscription.SubscriptionScheduleRequest;
import vio.barie.core.entity.SubscriptionPackageEntity;
import vio.barie.core.entity.SubscriptionPackageScheduleEntity;
import vio.barie.core.repository.SubscriptionPackageRepository;
import vio.barie.core.utils.VehicleType;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Contract checks; PostgreSQL tests separately prove locks, persistence callbacks and rollback. */
@ExtendWith(MockitoExtension.class)
class SubscriptionPackageServiceTest {
    private static final Instant FROM = Instant.parse("2026-10-09T00:00:00Z");
    @Mock SubscriptionPackageRepository packages;
    @Mock EntityManager entityManager;
    private SubscriptionPackageService service;

    @BeforeEach
    void setup() {
        service = new SubscriptionPackageService(packages, entityManager);
        lenient().when(packages.parkingLotExists(1L)).thenReturn(true);
        lenient().when(packages.saveAndFlush(any())).thenAnswer(call -> {
            SubscriptionPackageEntity pack = call.getArgument(0);
            pack.setId(11L);
            return pack;
        });
    }

    @Test
    void createsExplicitDraftWithServerAllocatedFirstVersionAndNoAutoPublication() {
        var response = service.createDraft(request());
        assertEquals(SubscriptionPackageStatus.DRAFT, response.getStatus());
        assertEquals(1, response.getVersionNo());
        assertEquals(300000L, response.getPriceVnd());
        assertEquals(FROM, response.getEffectiveFrom());
        assertEquals(30, response.getDurationDays());
    }

    @Test
    void historicalFamilyWithoutVersionOneCanAllocateNextVersion() {
        var anchor = pack(5L, 3);
        when(packages.findFirstByPackageCodeOrderByVersionNoAscIdAsc("MONTHLY")).thenReturn(Optional.of(anchor));
        when(packages.maximumVersion("MONTHLY")).thenReturn(Optional.of(7));
        assertEquals(8, service.createDraft(request()).getVersionNo());
        var order = inOrder(packages, entityManager);
        order.verify(packages).findFirstByPackageCodeOrderByVersionNoAscIdAsc("MONTHLY");
        order.verify(entityManager).refresh(anchor);
        order.verify(packages).maximumVersion("MONTHLY");
        order.verify(packages).saveAndFlush(any());
    }

    @Test
    void sameCodeCannotChangeLotAcrossVersions() {
        var anchor = pack(5L, 1);
        anchor.setParkingLotId(2L);
        when(packages.findFirstByPackageCodeOrderByVersionNoAscIdAsc("MONTHLY")).thenReturn(Optional.of(anchor));
        assertEquals(409, assertThrows(BusinessException.class, () -> service.createDraft(request())).getHttpStatus());
        verify(packages, never()).saveAndFlush(any());
    }

    @Test
    void sameCodeCannotChangeVehicleTypeAcrossVersions() {
        var anchor = pack(5L, 1);
        anchor.setVehicleType(VehicleType.MOTORBIKE);
        when(packages.findFirstByPackageCodeOrderByVersionNoAscIdAsc("MONTHLY")).thenReturn(Optional.of(anchor));
        assertThrows(BusinessException.class, () -> service.createDraft(request()));
    }

    @Test
    void firstVersionUniqueRaceAbortsWithoutQueryingRollbackOnlyTransaction() {
        doThrow(new DataIntegrityViolationException("uq_subscription_package_code_version"))
                .when(packages).saveAndFlush(any());
        assertEquals(409, assertThrows(BusinessException.class, () -> service.createDraft(request())).getHttpStatus());
        verify(packages, times(1)).findFirstByPackageCodeOrderByVersionNoAscIdAsc("MONTHLY");
        verify(packages, never()).maximumVersion(anyString());
    }

    @Test
    void unexpectedIntegrityFailureIsNotMasqueradedAsVersionConflict() {
        doThrow(new DataIntegrityViolationException("some_other_constraint"))
                .when(packages).saveAndFlush(any());
        assertThrows(DataIntegrityViolationException.class, () -> service.createDraft(request()));
    }

    @Test
    void allDayRejectsWeeklySchedules() {
        var request = request();
        request.setSchedules(List.of(window(1, "08:00", "17:00")));
        assertThrows(BusinessException.class, () -> service.createDraft(request));
    }

    @Test
    void weeklyDraftCanBeIncompleteButCannotBePublishedWithoutSchedule() {
        var request = request();
        request.setCoverageMode(SubscriptionCoverageMode.WEEKLY);
        assertEquals(SubscriptionPackageStatus.DRAFT, service.createDraft(request).getStatus());
        var pack = pack(11L, 1);
        pack.setCoverageMode(SubscriptionCoverageMode.WEEKLY);
        locked(pack, pack);
        assertThrows(BusinessException.class, () -> service.publish(11L));
        assertEquals(SubscriptionPackageStatus.DRAFT, pack.getStatus());
    }

    @Test
    void sundayOvernightOverlapWithMondayIsRejected() {
        var request = weekly(window(7, "22:00", "02:00"), window(1, "01:00", "03:00"));
        assertThrows(BusinessException.class, () -> service.createDraft(request));
    }

    @Test
    void sundayOvernightAdjacentToMondayIsAllowed() {
        var response = service.createDraft(weekly(window(7, "22:00", "02:00"), window(1, "02:00", "03:00")));
        assertEquals(2, response.getSchedules().size());
    }

    @Test
    void equalClockTimesMeanFullTwentyFourHours() {
        assertThrows(BusinessException.class, () -> service.createDraft(
                weekly(window(1, "08:00", "08:00"), window(2, "07:59", "09:00"))));
        assertEquals(2, service.createDraft(
                weekly(window(1, "08:00", "08:00"), window(2, "08:00", "09:00"))).getSchedules().size());
    }

    @Test
    void sundayMidnightToMidnightDoesNotCreateFalseZeroLengthMondayWindow() {
        assertEquals(2, service.createDraft(
                weekly(window(7, "00:00", "00:00"), window(1, "00:00", "08:00"))).getSchedules().size());
    }

    @Test
    void duplicateOrNestedWindowsAreRejected() {
        assertThrows(BusinessException.class, () -> service.createDraft(
                weekly(window(1, "08:00", "17:00"), window(1, "08:00", "17:00"))));
        assertThrows(BusinessException.class, () -> service.createDraft(
                weekly(window(1, "08:00", "17:00"), window(1, "09:00", "10:00"), window(1, "11:00", "12:00"))));
    }

    @Test
    void scheduleChecksPreserveSubsecondPrecisionInsteadOfRoundingToMinutes() {
        var first = window(1, "08:00:00.000001", "08:00:00.000003");
        var second = window(1, "08:00:00.000002", "08:00:00.000004");
        assertThrows(BusinessException.class, () -> service.createDraft(weekly(first, second)));
    }

    @Test
    void invalidWeekdayAndUnsupportedDatabasePrecisionAreRejected() {
        assertThrows(BusinessException.class, () -> service.createDraft(weekly(window(0, "08:00", "17:00"))));
        assertThrows(BusinessException.class, () -> service.createDraft(weekly(window(8, "08:00", "17:00"))));
        assertThrows(BusinessException.class, () -> service.createDraft(weekly(window(1, "08:00:00.000000001", "17:00"))));
    }

    @Test
    void publishedTermsAndSchedulesCannotBeReplacedEvenWithIdenticalInput() {
        var pack = pack(11L, 1);
        pack.setStatus(SubscriptionPackageStatus.PUBLISHED);
        locked(pack, pack);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.updateDraft(11L, request())).getHttpStatus());
        verify(entityManager, never()).flush();
    }

    @Test
    void publicationAndSaleUpdatesCheckOverlapEvenIfSalesAreDisabled() {
        var pack = pack(11L, 1);
        pack.setSaleEnabled(false);
        locked(pack, pack);
        when(packages.publishedOverlap("MONTHLY", 11L, FROM, null)).thenReturn(true);
        assertEquals(409, assertThrows(BusinessException.class, () -> service.publish(11L)).getHttpStatus());
        assertEquals(SubscriptionPackageStatus.DRAFT, pack.getStatus());
        pack.setStatus(SubscriptionPackageStatus.PUBLISHED);
        var update = new SubscriptionPackageSaleRequest();
        update.setSaleEnabled(false);
        assertThrows(BusinessException.class, () -> service.updateSale(11L, update));
    }

    @Test
    void allowedSaleChangesDoNotChangePurchasedTerms() {
        var pack = pack(11L, 1);
        pack.setStatus(SubscriptionPackageStatus.PUBLISHED);
        locked(pack, pack);
        var update = new SubscriptionPackageSaleRequest();
        update.setSaleEnabled(false);
        update.setEffectiveTo(FROM.plusSeconds(86400));
        var response = service.updateSale(11L, update);
        assertFalse(response.isSaleEnabled());
        assertEquals(update.getEffectiveTo(), response.getEffectiveTo());
        assertEquals(300000L, response.getPriceVnd());
        assertEquals(30, response.getDurationDays());
    }

    @Test
    void writesLockEarliestFamilyVersionThenTargetThenRefreshTarget() {
        var anchor = pack(5L, 1);
        var target = pack(11L, 2);
        target.setStatus(SubscriptionPackageStatus.PUBLISHED);
        locked(target, anchor);
        var update = new SubscriptionPackageSaleRequest();
        update.setSaleEnabled(false);
        service.updateSale(11L, update);
        var order = inOrder(packages, entityManager);
        order.verify(packages).findById(11L);
        order.verify(packages).findFirstByPackageCodeOrderByVersionNoAscIdAsc("MONTHLY");
        order.verify(entityManager).refresh(anchor);
        order.verify(packages).lockForManagement(11L);
        order.verify(entityManager).refresh(target);
        order.verify(packages).publishedOverlap("MONTHLY", 11L, FROM, null);
    }

    @Test
    void draftScheduleReplacementFlushesDeletesBeforeNewWindows() {
        var pack = pack(11L, 1);
        pack.setCoverageMode(SubscriptionCoverageMode.WEEKLY);
        SubscriptionPackageScheduleEntity original = new SubscriptionPackageScheduleEntity();
        original.setWeekday(1); original.setStartTime(LocalTime.of(8, 0)); original.setEndTime(LocalTime.of(17, 0));
        pack.addSchedule(original);
        locked(pack, pack);
        var response = service.updateDraft(11L, weekly(window(1, "08:00", "17:00")));
        assertEquals(1, response.getSchedules().size());
        assertNotSame(original, pack.getSchedules().getFirst());
        verify(entityManager, times(2)).flush();
    }

    private void locked(SubscriptionPackageEntity target, SubscriptionPackageEntity anchor) {
        when(packages.findById(target.getId())).thenReturn(Optional.of(target));
        when(packages.findFirstByPackageCodeOrderByVersionNoAscIdAsc(target.getPackageCode())).thenReturn(Optional.of(anchor));
        if (target != anchor) when(packages.lockForManagement(target.getId())).thenReturn(Optional.of(target));
    }

    private static CreateSubscriptionPackageRequest request() {
        var request = new CreateSubscriptionPackageRequest();
        request.setPackageCode("MONTHLY"); request.setName("Monthly"); request.setParkingLotId(1L);
        request.setVehicleType(VehicleType.CAR); request.setPriceVnd(300000L); request.setDurationDays(30);
        request.setCoverageMode(SubscriptionCoverageMode.ALL_DAY); request.setEffectiveFrom(FROM);
        request.setSaleEnabled(true); request.setSchedules(List.of());
        return request;
    }

    private static CreateSubscriptionPackageRequest weekly(SubscriptionScheduleRequest... windows) {
        var request = request(); request.setCoverageMode(SubscriptionCoverageMode.WEEKLY);
        request.setSchedules(List.of(windows)); return request;
    }

    private static SubscriptionScheduleRequest window(int weekday, String start, String end) {
        var window = new SubscriptionScheduleRequest(); window.setWeekday(weekday);
        window.setStartTime(LocalTime.parse(start)); window.setEndTime(LocalTime.parse(end)); return window;
    }

    private static SubscriptionPackageEntity pack(Long id, int version) {
        var pack = new SubscriptionPackageEntity(); pack.setId(id); pack.setPackageCode("MONTHLY"); pack.setName("Monthly");
        pack.setVersionNo(version); pack.setParkingLotId(1L); pack.setVehicleType(VehicleType.CAR);
        pack.setPriceVnd(300000L); pack.setDurationDays(30); pack.setCoverageMode(SubscriptionCoverageMode.ALL_DAY);
        pack.setEffectiveFrom(FROM); return pack;
    }
}
