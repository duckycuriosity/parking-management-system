package vio.barie.core.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
//import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.github.f4b6a3.uuid.UuidCreator;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

public class StringCommon {
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            // module hỗ trợ constructor / record
//            .addModule(new ParameterNamesModule())

            // hỗ trợ LocalDateTime
            .addModule(new JavaTimeModule())

            // không fail nếu JSON có field dư
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

            // không convert timestamp sang date
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)

            // ignore null
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            // timezone
            .defaultTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"))

            // performance
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)

            .build();

    public static ObjectMapper get() {
        return MAPPER;
    }

    public static String generateId() {
        return UuidCreator.getTimeOrderedEpoch().toString();
    }

    public static String writeAsString(Object prefix) {
        try {
            return MAPPER.writeValueAsString(prefix);
        } catch (Exception e) {
            return StringUtils.EMPTY;
        }
    }

    public static JsonNode valueToTree(Object prefix) {
        try {
            return MAPPER.valueToTree(prefix);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    public static String convertDeviceTimeToUTC7(long deviceTimeSeconds) {
        if(deviceTimeSeconds == 0)  return StringUtils.EMPTY;
        LocalDateTime dateTime = Instant.ofEpochSecond(deviceTimeSeconds)
                .atZone(ZoneId.of("UTC+7"))
                .toLocalDateTime();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
        return dateTime.format(formatter);

    }
}