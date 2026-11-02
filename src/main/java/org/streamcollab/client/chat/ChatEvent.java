package org.streamcollab.client.chat;

import java.util.Objects;

/** Member 3 event payloads. Stage 5 will map these to the unified HTTP event journal. */
public sealed interface ChatEvent permits ChatEvent.MessageReceived, ChatEvent.MessageStatusChanged {
    String type();

    record MessageReceived(ChatDelivery delivery) implements ChatEvent {
        public MessageReceived { Objects.requireNonNull(delivery, "delivery"); }
        @Override public String type() { return "MESSAGE_RECEIVED"; }
    }

    /** recipientId and errorCode are optional; null means absent. */
    record MessageStatusChanged(String messageId, ChatMessageStatus status,
                                String recipientId, String errorCode) implements ChatEvent {
        public MessageStatusChanged {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(status, "status");
        }
        @Override public String type() { return "MESSAGE_STATUS_CHANGED"; }
    }
}
