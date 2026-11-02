package org.streamcollab.client.chat;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/**
 * Client-side CHAT state and bounded journal. Does not own sockets, packet ACKs, codecs,
 * or network retries. All mutable chat state has one synchronized owner.
 *
 * Stage 4 adapter only: Agent integration must call onSessionEnded on logout/loss,
 * onReceipt for authenticated server application receipts, and onDelivery for trusted
 * application deliveries after Member 1's dispatcher has validated them.
 */
public final class ChatService implements AutoCloseable {
    public static final int MAX_TEXT_BYTES = 4096;
    private static final int MAX_IN_FLIGHT = 64;
    private static final int MAX_OUTBOUND = 256;
    private static final int MAX_RECENT_RECEIVED = 100;
    private static final int MAX_SEEN_RECEIVED = 512;
    private static final int MAX_EVENTS = 512;

    private static final class Outgoing {
        final String requestId;
        final ChatSendCommand command;
        ChatMessageStatus status = ChatMessageStatus.PENDING;
        String errorCode;
        Outgoing(String requestId, ChatSendCommand command) {
            this.requestId = requestId;
            this.command = command;
        }
        boolean inFlight() {
            return status == ChatMessageStatus.PENDING ||
                    (status == ChatMessageStatus.ACCEPTED_BY_SERVER &&
                     command.target() instanceof ChatTarget.Direct);
        }
        boolean terminal() { return !inFlight(); }
        ChatStatusView view() {
            return new ChatStatusView(command.messageId(), command.target(), command.text(), status, errorCode);
        }
    }

    private final Object lock = new Object();
    private final ChatTransport transport;
    private final BooleanSupplier online;
    private final Clock clock;
    private final String cursorPrefix = UUID.randomUUID().toString();
    private final LinkedHashMap<String, Outgoing> byMessageId = new LinkedHashMap<>();
    private final HashMap<String, Outgoing> byRequestId = new HashMap<>();
    private final LinkedHashMap<String, Boolean> seenDeliveryIds = new LinkedHashMap<>();
    private final ArrayDeque<ChatDelivery> recentReceived = new ArrayDeque<>();
    private final ArrayDeque<ChatEventRecord> events = new ArrayDeque<>();
    private long nextSequence;
    private int inFlight;
    private boolean closed;

    public ChatService(ChatTransport transport, BooleanSupplier online) {
        this(transport, online, Clock.systemUTC());
    }

    public ChatService(ChatTransport transport, BooleanSupplier online, Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.online = Objects.requireNonNull(online, "online");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Non-blocking local submission; returns message ID immediately for HTTP 202.
     * The same request_id+payload gets the same ID while retained in memory.
     * Duplicate request_id with different payload is rejected.
     */
    public String send(String requestId, ChatTarget target, String text) {
        checkId(requestId);
        Objects.requireNonNull(target, "target");
        checkText(text);
        Outgoing msg;
        synchronized (lock) {
            ensureOpen();
            Outgoing previous = byRequestId.get(requestId);
            if (previous != null) {
                if (!previous.command.target().equals(target) || !previous.command.text().equals(text)) {
                    throw new ChatFailure("INVALID_REQUEST");
                }
                return previous.command.messageId();
            }
            if (!online.getAsBoolean()) throw new ChatFailure("SESSION_EXPIRED");
            if (inFlight >= MAX_IN_FLIGHT) throw new ChatFailure("BACKPRESSURE");
            trimOutbound();
            if (byMessageId.size() >= MAX_OUTBOUND) throw new ChatFailure("BACKPRESSURE");
            msg = new Outgoing(requestId, new ChatSendCommand("msg-" + UUID.randomUUID(), target, text));
            byMessageId.put(msg.command.messageId(), msg);
            byRequestId.put(requestId, msg);
            inFlight++;
            recordEvent(new ChatEvent.MessageStatusChanged(msg.command.messageId(), msg.status, null, null));
        }
        try {
            // Must be asynchronous per transport contract. No waiting for ACK on the UI thread.
            CompletionStage<Void> completion = Objects.requireNonNull(
                    transport.sendChat(msg.command), "transport completion");
            completion.whenComplete((ignored, failure) -> {
                if (failure != null) failTransportIfStillPending(msg.command.messageId());
                // A transport ACK is NOT an application receipt; never mark ACCEPTED here.
            });
        } catch (RuntimeException failure) {
            failTransportIfStillPending(msg.command.messageId());
        }
        return msg.command.messageId();
    }

    /**
     * Only call with an authenticated server CHAT_RECEIPT. Returns true if state changed.
     * Do not accept a forged local/browser receipt as authoritative.
     */
    public boolean onReceipt(String messageId, ChatMessageStatus status,
                             String recipientId, String failureCode) {
        Objects.requireNonNull(status, "status");
        if (status == ChatMessageStatus.PENDING) throw new ChatFailure("INVALID_REQUEST");
        synchronized (lock) {
            ensureOpen();
            Outgoing msg = byMessageId.get(messageId);
            if (msg == null || !msg.inFlight()) return false;
            if (status == ChatMessageStatus.ACCEPTED_BY_SERVER &&
                    msg.status == ChatMessageStatus.ACCEPTED_BY_SERVER) return false;
            if (status == ChatMessageStatus.DELIVERED_TO_RECIPIENT &&
                    !(msg.command.target() instanceof ChatTarget.Direct)) {
                throw new ChatFailure("PROTOCOL_ERROR");
            }
            if (status == ChatMessageStatus.DELIVERED_TO_RECIPIENT &&
                    recipientId != null && !recipientId.equals(((ChatTarget.Direct) msg.command.target()).userId())) {
                throw new ChatFailure("PROTOCOL_ERROR");
            }
            if (status == ChatMessageStatus.FAILED) {
                // The baseline CHAT_RECEIPT schema does not require an error code.
                // Normalize an absent remote reason to a stable local error code.
                if (failureCode != null && !failureCode.matches("[A-Z][A-Z0-9_]{0,79}")) {
                    throw new ChatFailure("PROTOCOL_ERROR");
                }
                msg.errorCode = failureCode == null ? "REMOTE_FAILED" : failureCode;
            }
            msg.status = status;
            if (msg.terminal()) inFlight--;
            recordEvent(new ChatEvent.MessageStatusChanged(messageId, status,
                    status == ChatMessageStatus.DELIVERED_TO_RECIPIENT ? recipientId : null,
                    msg.errorCode));
            return true;
        }
    }

    /** Called by authenticated, decoded CHAT_DELIVER; false means duplicate. */
    public boolean onDelivery(ChatDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        checkId(delivery.messageId());
        checkId(delivery.senderId());
        checkText(delivery.text());
        synchronized (lock) {
            ensureOpen();
            if (!online.getAsBoolean()) throw new ChatFailure("SESSION_EXPIRED");
            if (seenDeliveryIds.containsKey(delivery.messageId())) return false;
            seenDeliveryIds.put(delivery.messageId(), Boolean.TRUE);
            while (seenDeliveryIds.size() > MAX_SEEN_RECEIVED) {
                seenDeliveryIds.remove(seenDeliveryIds.keySet().iterator().next());
            }
            recentReceived.addLast(delivery);
            while (recentReceived.size() > MAX_RECENT_RECEIVED) recentReceived.removeFirst();
            recordEvent(new ChatEvent.MessageReceived(delivery));
            return true;
        }
    }

    /** Fail outstanding sends once, clear per-session dedup state; no auto-replay on reconnect. */
    public void onSessionEnded() {
        synchronized (lock) {
            if (closed) return;
            for (Outgoing msg : byMessageId.values()) {
                if (msg.inFlight()) {
                    msg.status = ChatMessageStatus.FAILED;
                    msg.errorCode = "SESSION_EXPIRED";
                    recordEvent(new ChatEvent.MessageStatusChanged(msg.command.messageId(),
                            msg.status, null, msg.errorCode));
                }
            }
            inFlight = 0;
            byRequestId.clear();
            seenDeliveryIds.clear();
        }
    }

    /** Chat-local cursor, NOT yet the unified HTTP cursor; snapshot is consistent with it. */
    public ChatSnapshot snapshot() {
        synchronized (lock) {
            return new ChatSnapshot(cursor(nextSequence), new ArrayList<>(recentReceived),
                    byMessageId.values().stream().map(Outgoing::view).toList());
        }
    }

    /** Bounded chat journal; expired/malformed/wrong-instance cursor -> RESYNC_REQUIRED. */
    public ChatEventPage eventsAfter(String cursor, int max) {
        if (max < 1 || max > 128) throw new ChatFailure("INVALID_REQUEST");
        synchronized (lock) {
            long sequence = parseCursor(cursor);
            if (sequence > nextSequence || (!events.isEmpty() &&
                    sequence < sequenceFromRecord(events.peekFirst()) - 1)) {
                throw new ChatFailure("RESYNC_REQUIRED");
            }
            List<ChatEventRecord> result = new ArrayList<>();
            for (ChatEventRecord event : events) {
                if (sequenceFromRecord(event) > sequence) {
                    result.add(event);
                    if (result.size() >= max) break;
                }
            }
            String next = result.isEmpty() ? cursor(nextSequence) : result.getLast().cursor();
            return new ChatEventPage(result, next);
        }
    }

    private void failTransportIfStillPending(String messageId) {
        synchronized (lock) {
            Outgoing msg = byMessageId.get(messageId);
            if (msg == null || msg.status != ChatMessageStatus.PENDING) return;
            msg.status = ChatMessageStatus.FAILED;
            msg.errorCode = "IO_ERROR";
            inFlight--;
            recordEvent(new ChatEvent.MessageStatusChanged(messageId, msg.status, null, msg.errorCode));
        }
    }

    private void trimOutbound() {
        Iterator<Outgoing> it = byMessageId.values().iterator();
        while (byMessageId.size() >= MAX_OUTBOUND && it.hasNext()) {
            Outgoing current = it.next();
            if (current.terminal()) {
                it.remove();
                byRequestId.remove(current.requestId, current);
            }
        }
    }

    private void recordEvent(ChatEvent event) {
        ++nextSequence;
        events.addLast(new ChatEventRecord(cursor(nextSequence), clock.instant(), event));
        while (events.size() > MAX_EVENTS) events.removeFirst();
    }

    private String cursor(long seq) { return cursorPrefix + ":" + seq; }
    private long sequenceFromRecord(ChatEventRecord event) {
        return Long.parseLong(event.cursor().substring(cursorPrefix.length() + 1));
    }
    private long parseCursor(String value) {
        if (value == null || !value.startsWith(cursorPrefix + ":")) {
            throw new ChatFailure("RESYNC_REQUIRED");
        }
        try {
            String suffix = value.substring(cursorPrefix.length() + 1);
            if (!suffix.matches("0|[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(suffix);
        } catch (NumberFormatException e) {
            throw new ChatFailure("RESYNC_REQUIRED");
        }
    }

    private void ensureOpen() {
        if (closed) throw new ChatFailure("SESSION_EXPIRED");
    }
    private static void checkId(String value) {
        if (value == null || value.isBlank() || value.length() > 128 ||
                value.chars().anyMatch(Character::isISOControl)) {
            throw new ChatFailure("INVALID_REQUEST");
        }
    }
    private static void checkText(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_TEXT_BYTES) {
            throw new ChatFailure("INVALID_REQUEST");
        }
        try {
            int count = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text)).remaining();
            if (count > MAX_TEXT_BYTES) throw new ChatFailure("INVALID_REQUEST");
        } catch (CharacterCodingException exception) {
            throw new ChatFailure("INVALID_REQUEST");
        }
    }

    @Override public void close() {
        onSessionEnded();
        synchronized (lock) { closed = true; }
    }
}
