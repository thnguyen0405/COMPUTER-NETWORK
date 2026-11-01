package org.streamcollab.client.connection;

import java.time.Instant;
import java.util.Objects;

/** An immutable fact that can later be translated into a CONNECTION_CHANGED UI event. */
public record ConnectionTransition(
        ConnectionState previous,
        ConnectionState current,
        Instant timestamp,
        String reason) {
    public ConnectionTransition {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(reason, "reason");
        if (previous == current) {
            throw new IllegalArgumentException("A transition must change the state");
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }
}
