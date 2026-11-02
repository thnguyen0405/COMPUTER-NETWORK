package org.streamcollab.client.connection;

/** One ordered, nonblocking notification path toward the future HTTP event queue. */
@FunctionalInterface
public interface ConnectionEventSink {
    void onTransition(ConnectionTransition transition);
}
