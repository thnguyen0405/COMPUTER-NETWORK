package org.streamcollab.client.chat;

import java.util.Objects;

/** Adapter input for Member 1's transport/codec: wire CHAT_SEND has message_id, target, text. */
public record ChatSendCommand(String messageId, ChatTarget target, String text) {
    public ChatSendCommand {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(text, "text");
    }
}
