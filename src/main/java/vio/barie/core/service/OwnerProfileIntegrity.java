package vio.barie.core.service;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import vio.barie.core.config.exception.BusinessException;

/** Translate known domain conflicts; the caller still exits the failed transaction. */
final class OwnerProfileIntegrity {
    private OwnerProfileIntegrity() { }

    static RuntimeException uniqueConflict(DataIntegrityViolationException failure) {
        ConstraintViolationException constraint = constraint(failure);
        if (constraint == null || !"23505".equals(constraint.getSQLState())) return failure;
        String name = constraint.getConstraintName();
        if (name == null) return failure;
        return switch (name) {
            case "uk_owner_account_phone" -> new BusinessException(
                    "Owner phone already exists; select its ID explicitly", "OWNER_PHONE_CONFLICT", 409);
            case "uk_owner_account_identity_number" -> new BusinessException(
                    "Owner identityNumber already exists", "OWNER_IDENTITY_CONFLICT", 409);
            case "vehicle_plate_number_key" -> new BusinessException(
                    "Plate already exists; select its ID explicitly", "VEHICLE_PLATE_CONFLICT", 409);
            default -> failure;
        };
    }

    static RuntimeException ownerDeleteConflict(DataIntegrityViolationException failure) {
        ConstraintViolationException constraint = constraint(failure);
        if (constraint != null && "23503".equals(constraint.getSQLState())) {
            return new BusinessException("Owner has linked data; set status to INACTIVE instead",
                    "OWNER_HAS_LINKED_DATA", 409);
        }
        return failure;
    }

    private static ConstraintViolationException constraint(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint) return constraint;
        }
        return null;
    }
}
