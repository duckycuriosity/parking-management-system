package vio.barie.core.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vio.barie.core.utils.StringCommon;

import java.time.LocalDateTime;

@Entity
@Table(name = "camera_event_log")
@Data
public class CameraEventLogEntity {
    @Id
    private String id;
    private Long eventId;
    private String eventType;
    private String cameraUuid;
    private String eventTime;
    private String modelName;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode metadata;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (getCreatedAt() == null) {
            setCreatedAt(now);
        }
        if (getUpdatedAt() == null) {
            setUpdatedAt(now);
        }
    }

    @PreUpdate
    public void preUpdate() {
        setUpdatedAt(LocalDateTime.now());
    }


    public static CameraEventLogEntity toEntity(JsonNode event) {
        if (event == null || event.isNull()) {
            return null;
        }

        CameraEventLogEntity entity = new CameraEventLogEntity();

        entity.setId(StringCommon.generateId());
        entity.setEventId(getLong(event, "eventId"));
        entity.setEventType(getText(event, "eventType"));
        entity.setCameraUuid(getText(event, "cameraUUID"));
        entity.setEventTime(getText(event, "eventTime"));
        entity.setModelName(getText(event, "modelName"));

        ObjectNode metadata = event.deepCopy();

        metadata.remove("eventId");
        metadata.remove("eventType");
        metadata.remove("cameraUUID");
        metadata.remove("eventTime");
        metadata.remove("modelName");

        entity.setMetadata(metadata);

        return entity;
    }
    private static String getText(JsonNode node, String field) {
        JsonNode value = node.get(field);

        if (value == null || value.isNull()) {
            return null;
        }

        return value.asText();
    }

    private static Long getLong(JsonNode node, String field) {
        JsonNode value = node.get(field);

        if (value == null || value.isNull()) {
            return null;
        }

        if (value.isNumber()) {
            return value.asLong();
        }

        try {
            return Long.parseLong(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
