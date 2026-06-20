package vio.barie.core.utils;

public enum VehicleType {
    CAR(1),
    MOTORBIKE(2),
    TRUCK(3),
    BICYCLE(4);

    private final int code;

    VehicleType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static VehicleType fromCode(int code) {
        for (VehicleType type : VehicleType.values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid code: " + code);
    }
}
