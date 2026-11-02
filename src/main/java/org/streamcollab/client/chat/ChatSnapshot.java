package org.streamcollab.client.chat;

import java.util.List;

/** Atomic view of chat state with its own chat-local event cursor. */
public record ChatSnapshot(String cursor, List<ChatDelivery> recentReceived,
                           List<ChatStatusView> recentOutbound) {
    public ChatSnapshot {
        recentReceived = List.copyOf(recentReceived);
        recentOutbound = List.copyOf(recentOutbound);
    }
}
