package vio.barie.core.config.exception;

import org.springframework.http.HttpStatus;

public class ServiceException extends RuntimeException {
    private final int statusCode;

    public ServiceException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }
    public ServiceException(String message) {
        super(message);
        this.statusCode = HttpStatus.BAD_REQUEST.value();
    }
    public ServiceException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 500;
    }

    public int getStatusCode() {
        return statusCode;
    }

}