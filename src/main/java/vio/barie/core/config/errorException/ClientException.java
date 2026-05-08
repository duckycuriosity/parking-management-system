package vio.barie.core.config.errorException;

public class ClientException extends RuntimeException {
    private final int statusCode;

    public ClientException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public ClientException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 500;

    }

    public int getStatusCode() {
        return statusCode;
    }
}
