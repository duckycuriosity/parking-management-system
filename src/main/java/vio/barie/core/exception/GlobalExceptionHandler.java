package vio.barie.core.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import vio.barie.core.dto.ApiResponse;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        log.warn("[BUSINESS_ERROR] Code: {}, Message: {}", ex.getCode(), ex.getMessage());
        ApiResponse<Void> response = new ApiResponse<>(
                ex.getCode(),
                ex.getMessage(),
                null);
        return ResponseEntity
                .status(ex.getHttpStatus())
                .body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationException(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        log.warn("[VALIDATION_ERROR] {}", errors);

        ApiResponse<Map<String, String>> response = new ApiResponse<>(
                "VALIDATION_ERROR",
                "Request validation failed",
                errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        log.error("[UNEXPECTED_ERROR]", ex);
        ApiResponse<Void> response = new ApiResponse<>(
                "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred",
                null);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(response);
    }
}
