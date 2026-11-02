package org.streamcollab.client.chat;

import java.time.Instant;
import java.util.Objects;

/** Cursor is chat-local, NOT the unified /api/events cursor. */
public record ChatEventRecord(String cursor, Instant timestamp, ChatEvent data) {
    public ChatEventRecord {
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(data, "data");
    }
}
