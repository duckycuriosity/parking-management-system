package vio.barie.core.config.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import vio.barie.core.dto.Result;

import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", msg);
        return ResponseEntity.status(400).body((Object) Result.error(400, msg));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> handleBusinessException(BusinessException ex, WebRequest request) {
        log.warn("BusinessException: {}", ex.getMessage());
        return Result.errorResponse(ex.getHttpStatus(), ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleMethodException(
            Exception ex,
            WebRequest request) {

        log.error("handleRuntimeException {}",ex.getMessage());
        return Result.errorResponseWithData(500, "Có lỗi xảy ra xin thử lại",ex.getMessage());
    }

    @ExceptionHandler(ClientException.class)
    public ResponseEntity<?> handleClientException(
            ClientException ex,
            WebRequest request) {

        log.error("handleClientException: {}", ex.getMessage());
        return Result.errorResponse(ex.getStatusCode(), ex.getMessage());
    }
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<?> serviceExceptionHandle(
            ServiceException ex,
            WebRequest request) {

        log.error("ServiceException: {}", ex.getMessage());
        return Result.errorResponse(ex.getStatusCode(), ex.getMessage());
    }
}
