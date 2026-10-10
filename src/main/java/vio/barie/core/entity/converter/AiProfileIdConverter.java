package vio.barie.core.entity.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Existing Core AI/MQTT APIs use a signed 32-bit profileId. V14 stores it as
 * BIGINT. Keep that wire contract while using the actual BIGINT JDBC type;
 * reject an unsupported stored ID rather than silently truncating it.
 */
@Converter
public class AiProfileIdConverter implements AttributeConverter<Integer, Long> {
    @Override
    public Long convertToDatabaseColumn(Integer value) {
        return value == null ? null : value.longValue();
    }

    @Override
    public Integer convertToEntityAttribute(Long value) {
        if (value == null) {
            return null;
        }
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException e) {
            throw new IllegalStateException("Stored AI profile ID exceeds the current Core AI/MQTT contract", e);
        }
    }
}
