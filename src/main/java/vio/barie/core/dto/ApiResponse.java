package vio.barie.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor // Constructor có tham số (code, message, data)
@NoArgsConstructor
public class ApiResponse<T> {
    private String code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> of(String code, String message, T data) {
        return new ApiResponse<>(code, message, data);
    }

}
