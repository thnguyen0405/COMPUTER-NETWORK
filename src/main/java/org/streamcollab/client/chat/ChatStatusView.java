package org.streamcollab.client.chat;

/** errorCode is null unless FAILED. */
public record ChatStatusView(String messageId, ChatTarget target, String text,
                             ChatMessageStatus status, String errorCode) { }
