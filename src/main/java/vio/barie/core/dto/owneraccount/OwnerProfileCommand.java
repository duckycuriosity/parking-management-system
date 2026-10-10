package vio.barie.core.dto.owneraccount;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/** A command is an allowlist; financial/AI fields cannot silently become writable. */
public abstract class OwnerProfileCommand {
    @JsonAnySetter
    public final void rejectUnexpectedField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported request field: " + field);
    }
}
