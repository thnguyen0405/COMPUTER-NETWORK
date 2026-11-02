package org.streamcollab.client.chat;

import java.util.concurrent.CompletionStage;

/**
 * Member 1 integration adapter. Must return immediately without blocking on network I/O.
 * The stage completes when the immediate server TRANSPORT acknowledges receipt, NOT when
 * the server accepts/routes the chat application message. The shared codec and transport
 * own serialization, stream selection, ACKs and retransmissions.
 */
@FunctionalInterface
public interface ChatTransport {
    CompletionStage<Void> sendChat(ChatSendCommand command);
}
