package vio.barie.core.service;

import com.fasterxml.jackson.databind.JsonNode;

public interface ParkingMessageService {
    void handleConnect(String topic, JsonNode json);

    void handleDisconnect(String topic, JsonNode json);

    void handlePing(String topic, JsonNode json);
}
