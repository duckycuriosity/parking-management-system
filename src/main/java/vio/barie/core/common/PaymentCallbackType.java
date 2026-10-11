package vio.barie.core.common;

public enum PaymentCallbackType {

    /**
     * Thanh toán phí gửi xe theo lượt.
     * context: {"sessionId": 123, "barrierId": "barrier_001", "plateNumber": "51A-12345"}
     * → mở cổng ra qua MQTT
     */
    SESSION,

    /**
     * Mua / gia hạn vé tháng online.
     * context: {"ticketId": 456, "customerId": 789}
     * → kích hoạt ticket status = ACTIVE
     */
    TICKET,

    /** Existing payment context: {"subscriptionId": 123}. */
    SUBSCRIPTION
}
