package org.streamcollab.client.chat;

/** PENDING is local-only; remaining values correspond to server application receipts. */
public enum ChatMessageStatus {
    PENDING, ACCEPTED_BY_SERVER, DELIVERED_TO_RECIPIENT, FAILED
}
