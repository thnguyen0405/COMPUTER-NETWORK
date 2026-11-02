package org.streamcollab.client.chat;

import java.util.List;
import java.util.Objects;

public record ChatEventPage(List<ChatEventRecord> events, String nextCursor) {
    public ChatEventPage {
        events = List.copyOf(events);
        Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
