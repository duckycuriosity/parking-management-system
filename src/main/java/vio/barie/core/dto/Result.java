package vio.barie.core.dto;

import lombok.Data;
import org.springframework.http.ResponseEntity;

@Data
public class Result<T> {

    private Integer code;
    private String msg;
    private T data;

    public Result() {
        this.code = 200;
        this.msg = "success";
    }

    public Result(T data) {
        this();
        this.data = data;
    }

    public Result(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public Result(Integer code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    // Static factory methods
    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "success", data);
    }

    public static <T> Result<T> ok(String msg, T data) {
        return new Result<>(200, msg, data);
    }

    public static <T> Result<T> error(Integer code, String message) {
        return new Result<>(code, message);
    }

    public static <T> Result<T> error(Integer code, String message, T data) {
        return new Result<>(code, message, data);
    }

    public static <T> ResponseEntity<Result<T>> success(T data) {
        return ResponseEntity.ok(new Result<>(data));
    }

    public static <T> ResponseEntity<Result<T>> errorResponse(Integer code, String msg) {
        return ResponseEntity.status(code).body(Result.error(code, msg));
    }

    public static <T> ResponseEntity<Result<T>> errorResponseWithData(Integer code, String msg, T data) {
        return ResponseEntity.status(code).body(Result.error(code, msg, data));
    }
}