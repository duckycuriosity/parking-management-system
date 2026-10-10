package vio.barie.core.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vio.barie.core.config.exception.BusinessException;
import vio.barie.core.entity.ParkingSessionEntity;
import vio.barie.core.entity.VehicleEntity;
import vio.barie.core.repository.FeeCalculationRepository;
import vio.barie.core.repository.ParkingSessionRepository;
import vio.barie.core.repository.PaymentRepository;

import java.util.Objects;

/** A session stays on one collection workflow until an explicit checkout handoff is approved. */
@Service
public class ParkingFeeWorkflowGuard {
    private final PaymentRepository payments;
    private final FeeCalculationRepository calculations;
    private final ParkingSessionRepository sessions;
    private final EntityManager entityManager;

    public ParkingFeeWorkflowGuard(PaymentRepository payments, FeeCalculationRepository calculations,
            ParkingSessionRepository sessions, EntityManager entityManager) {
        this.payments = payments; this.calculations = calculations;
        this.sessions = sessions; this.entityManager = entityManager;
    }

    /** New quote/settlement callers must already hold the vehicle then session write locks. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertNewWorkflowAllowed(Long sessionId) {
        requireWriteTransaction();
        if (hasLegacyPaymentHistory(sessionId)) throw conflict();
    }

    @Transactional(readOnly = true)
    public boolean hasLegacyPaymentHistory(Long sessionId) {
        positive(sessionId);
        // Every legacy invoice matters, including FAILED: a customer can still send money late.
        return payments.existsLegacySessionPaymentHistory(sessionId.toString());
    }

    /** Shared lock order serializes old checkout/payment writes against new fee quotes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ParkingSessionEntity lockAndAssertLegacyWorkflowAllowed(Long sessionId) {
        requireWriteTransaction(); positive(sessionId);
        ParkingSessionEntity preview = sessions.findById(sessionId).orElseThrow(() ->
                new BusinessException("Parking session not found", "NOT_FOUND", 404));
        Long vehicleId = preview.getVehicle() == null ? null : preview.getVehicle().getId();
        if (vehicleId == null) throw new BusinessException("Session vehicle is missing", "FEE_SESSION_INVALID", 409);
        VehicleEntity vehicle = entityManager.find(VehicleEntity.class, vehicleId, LockModeType.PESSIMISTIC_WRITE);
        if (vehicle == null) throw new BusinessException("Session vehicle not found", "NOT_FOUND", 404);
        entityManager.refresh(vehicle);
        ParkingSessionEntity session = sessions.lockForFee(sessionId).orElseThrow(() ->
                new BusinessException("Parking session not found", "NOT_FOUND", 404));
        entityManager.refresh(session);
        if (session.getVehicle() == null || !Objects.equals(vehicleId, session.getVehicle().getId()))
            throw new BusinessException("Session vehicle changed while acquiring locks", "FEE_WORKFLOW_CONFLICT", 409);
        if (calculations.existsBySessionId(sessionId)) throw conflict();
        return session;
    }

    private void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new BusinessException("Workflow choice requires a write transaction", "FEE_TRANSACTION_REQUIRED", 500);
    }
    private void positive(Long id) {
        if (id == null || id <= 0) throw new BusinessException("sessionId must be positive", "FEE_INVALID_REQUEST", 400);
    }
    private BusinessException conflict() {
        return new BusinessException("This session already belongs to the other fee collection workflow",
                "FEE_WORKFLOW_CONFLICT", 409);
    }
}
