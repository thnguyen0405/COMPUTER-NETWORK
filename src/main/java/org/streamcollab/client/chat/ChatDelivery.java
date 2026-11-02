package org.streamcollab.client.chat;

import java.time.Instant;
import java.util.Objects;

/** Authenticated CHAT_DELIVER from the central server (never trust browser-supplied sender_id). */
public record ChatDelivery(String messageId, String senderId, String text, Instant serverTime) {
    public ChatDelivery {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(serverTime, "serverTime");
    }
}
