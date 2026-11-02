package org.streamcollab.unit;

import org.streamcollab.client.chat.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ChatServiceSmokeTest {
    private static int assertions;
    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }
    private static void fail(String code, Runnable op) {
        assertions++;
        try { op.run(); }
        catch (ChatFailure f) {
            if (code.equals(f.code())) return;
            throw new AssertionError("Expected " + code + ", got " + f.code(), f);
        }
        throw new AssertionError("Expected failure " + code);
    }
    private static final class FakeTransport implements ChatTransport {
        final List<ChatSendCommand> sent = Collections.synchronizedList(new ArrayList<>());
        final List<CompletableFuture<Void>> acks = Collections.synchronizedList(new ArrayList<>());
        volatile boolean throwNow;
        @Override public CompletionStage<Void> sendChat(ChatSendCommand command) {
            if (throwNow) throw new IllegalStateException("test transport offline");
            sent.add(command);
            CompletableFuture<Void> future = new CompletableFuture<>();
            acks.add(future);
            return future;
        }
    }
    private static ChatMessageStatus status(ChatService service, String messageId) {
        return service.snapshot().recentOutbound().stream()
                .filter(s -> s.messageId().equals(messageId)).findFirst().orElseThrow().status();
    }

    private static void testSendIdempotencyReceipts() {
        FakeTransport transport = new FakeTransport();
        AtomicBoolean online = new AtomicBoolean(true);
        try (ChatService service = new ChatService(transport, online::get)) {
            String startCursor = service.snapshot().cursor();
            String one = service.send("req-1", new ChatTarget.Global(), "Xin chào 🎉");
            check(one.startsWith("msg-"), "generated ID");
            check(transport.sent.size() == 1, "sent once");
            check(transport.sent.getFirst().text().equals("Xin chào 🎉"), "unicode retained");
            check(status(service, one) == ChatMessageStatus.PENDING, "pending before receipt");
            transport.acks.getFirst().complete(null);
            check(status(service, one) == ChatMessageStatus.PENDING, "transport ACK is NOT application receipt");
            String dup = service.send("req-1", new ChatTarget.Global(), "Xin chào 🎉");
            check(one.equals(dup), "same request dedup");
            check(transport.sent.size() == 1, "idempotent request not resent");
            fail("INVALID_REQUEST", () -> service.send("req-1", new ChatTarget.Global(), "Different"));
            fail("INVALID_REQUEST", () -> service.send("req-1", new ChatTarget.Direct("bob"), "Xin chào 🎉"));
            check(service.onReceipt(one, ChatMessageStatus.ACCEPTED_BY_SERVER, null, null), "global accepted");
            check(status(service, one) == ChatMessageStatus.ACCEPTED_BY_SERVER, "global accepted status");
            check(!service.onReceipt(one, ChatMessageStatus.ACCEPTED_BY_SERVER, null, null), "idempotent accepted");
            check(!service.onReceipt("msg-unknown", ChatMessageStatus.ACCEPTED_BY_SERVER, null, null), "ignore unknown receipt");
            check(service.eventsAfter(startCursor, 10).events().size() == 2, "pending + accepted events");
            check(service.eventsAfter(startCursor, 1).events().size() == 1, "pagination limit");
            fail("INVALID_REQUEST", () -> service.eventsAfter(startCursor, 0));
            fail("INVALID_REQUEST", () -> service.eventsAfter(startCursor, 129));
            fail("RESYNC_REQUIRED", () -> service.eventsAfter("invalid:0", 10));
            check(!service.onReceipt(one, ChatMessageStatus.FAILED, null, "TIMEOUT"), "terminal global remains accepted");

            String two = service.send("req-2", new ChatTarget.Direct("bob"), "Hi Bob");
            check(service.onReceipt(two, ChatMessageStatus.ACCEPTED_BY_SERVER, null, null), "direct accepted");
            check(status(service, two) == ChatMessageStatus.ACCEPTED_BY_SERVER, "await recipient");
            fail("PROTOCOL_ERROR", () -> service.onReceipt(two, ChatMessageStatus.DELIVERED_TO_RECIPIENT, "eve", null));
            check(status(service, two) == ChatMessageStatus.ACCEPTED_BY_SERVER, "wrong user must not change state");
            check(service.onReceipt(two, ChatMessageStatus.DELIVERED_TO_RECIPIENT, "bob", null), "direct delivered");
            check(status(service, two) == ChatMessageStatus.DELIVERED_TO_RECIPIENT, "recipient ack final");
            check(!service.onReceipt(two, ChatMessageStatus.ACCEPTED_BY_SERVER, null, null), "late acceptance must not regress");
            String three = service.send("req-3", new ChatTarget.Direct("bob"), "A");
            check(service.onReceipt(three, ChatMessageStatus.DELIVERED_TO_RECIPIENT, "bob", null), "out-of-order direct delivery");
            check(status(service, three) == ChatMessageStatus.DELIVERED_TO_RECIPIENT, "final state kept");
            fail("INVALID_REQUEST", () -> service.onReceipt(three, ChatMessageStatus.PENDING, null, null));
            String four = service.send("req-4", new ChatTarget.Global(), "Fail");
            fail("PROTOCOL_ERROR", () -> service.onReceipt(four, ChatMessageStatus.DELIVERED_TO_RECIPIENT, "bob", null));
            fail("PROTOCOL_ERROR", () -> service.onReceipt(four, ChatMessageStatus.FAILED, null, "unsafe error text"));
            check(service.onReceipt(four, ChatMessageStatus.FAILED, null, "PEER_OFFLINE"), "server failure");
            check(status(service, four) == ChatMessageStatus.FAILED, "failure status");
            check(service.snapshot().recentOutbound().stream().anyMatch(s -> "PEER_OFFLINE".equals(s.errorCode())), "snapshot error");
            String five = service.send("req-5", new ChatTarget.Direct("bob"), "Remote failed");
            check(service.onReceipt(five, ChatMessageStatus.FAILED, "bob", null), "failure without optional error code");
            check(service.snapshot().recentOutbound().stream().anyMatch(s -> "REMOTE_FAILED".equals(s.errorCode())), "generic remote failure code");
        }
    }

    private static void testValidationAndTransportFailure() {
        FakeTransport transport = new FakeTransport();
        AtomicBoolean online = new AtomicBoolean(false);
        try (ChatService service = new ChatService(transport, online::get)) {
            fail("SESSION_EXPIRED", () -> service.send("req-off", new ChatTarget.Global(), "Hello"));
            online.set(true);
            fail("INVALID_REQUEST", () -> service.send("", new ChatTarget.Global(), "hello"));
            fail("INVALID_REQUEST", () -> service.send("r\n", new ChatTarget.Global(), "hello"));
            fail("INVALID_REQUEST", () -> service.send("valid", new ChatTarget.Global(), "  \t"));
            fail("INVALID_REQUEST", () -> service.send("valid", new ChatTarget.Global(), "A".repeat(4097)));
            fail("INVALID_REQUEST", () -> service.send("valid", new ChatTarget.Global(), "é".repeat(2049)));
            fail("INVALID_REQUEST", () -> service.send("valid", new ChatTarget.Global(), "\ud800"));
            try { new ChatTarget.Direct(""); throw new AssertionError("Bad target"); }
            catch (IllegalArgumentException ok) { check(true, "bad target rejected"); }
            String pass = service.send("good", new ChatTarget.Global(), "é".repeat(2048));
            check(status(service, pass) == ChatMessageStatus.PENDING, "max utf8 bytes allowed");
            transport.acks.getFirst().completeExceptionally(new RuntimeException("simulated packet loss"));
            check(status(service, pass) == ChatMessageStatus.FAILED, "transport stage fail");
            check(service.snapshot().recentOutbound().getFirst().errorCode().equals("IO_ERROR"), "stable generic failure");
            transport.throwNow = true;
            String failId = service.send("throw", new ChatTarget.Global(), "Hi");
            check(status(service, failId) == ChatMessageStatus.FAILED, "synchronous adapter error caught");
            check(service.send("throw", new ChatTarget.Global(), "Hi").equals(failId), "failure retry request dedup");
        }
    }

    private static void testIncomingAndSessionEnd() {
        FakeTransport transport = new FakeTransport();
        AtomicBoolean online = new AtomicBoolean(true);
        try (ChatService service = new ChatService(transport, online::get)) {
            ChatDelivery incoming = new ChatDelivery("msg-remote-1", "alice", "Hello", Instant.parse("2026-11-02T01:00:00Z"));
            check(service.onDelivery(incoming), "received once");
            check(!service.onDelivery(incoming), "duplicate suppressed");
            check(service.snapshot().recentReceived().size() == 1, "recent received persisted in memory");
            check(service.snapshot().recentReceived().getFirst().senderId().equals("alice"), "sender retained");
            String first = service.send("new1", new ChatTarget.Direct("alice"), "test1");
            String second = service.send("new2", new ChatTarget.Global(), "test2");
            check(service.onReceipt(first, ChatMessageStatus.ACCEPTED_BY_SERVER, null, null), "direct still outstanding");
            service.onSessionEnded();
            check(status(service, first) == ChatMessageStatus.FAILED, "direct failed on loss");
            check(status(service, second) == ChatMessageStatus.FAILED, "pending failed on loss");
            check(service.snapshot().recentOutbound().stream().filter(s -> "SESSION_EXPIRED".equals(s.errorCode())).count() == 2, "loss errors");
            transport.acks.get(1).completeExceptionally(new IllegalStateException("late failure"));
            check(service.snapshot().recentOutbound().stream().filter(s -> "SESSION_EXPIRED".equals(s.errorCode())).count() == 2, "late callback ignored");
            online.set(false);
            fail("SESSION_EXPIRED", () -> service.onDelivery(new ChatDelivery("id2", "alice", "Yo", Instant.now())));
            online.set(true);
            check(service.onDelivery(incoming), "incoming dedup cleared on session change");
            String newId = service.send("new1", new ChatTarget.Global(), "fresh session");
            check(!newId.equals(first), "request IDs reusable after reconnect");
            service.close();
            fail("SESSION_EXPIRED", () -> service.send("late", new ChatTarget.Global(), "Hello"));
            fail("SESSION_EXPIRED", () -> service.onReceipt(newId, ChatMessageStatus.ACCEPTED_BY_SERVER, null, null));
        }
    }

    private static void testCapacityAndResync() {
        FakeTransport transport = new FakeTransport();
        AtomicBoolean online = new AtomicBoolean(true);
        try (ChatService service = new ChatService(transport, online::get)) {
            String stale = service.snapshot().cursor();
            for (int i=0;i<64;i++) service.send("req-inflight-"+i, new ChatTarget.Direct("bob"), "x");
            fail("BACKPRESSURE", () -> service.send("full", new ChatTarget.Global(), "x"));
            String first = transport.sent.getFirst().messageId();
            service.onReceipt(first, ChatMessageStatus.DELIVERED_TO_RECIPIENT, "bob", null);
            String available = service.send("released", new ChatTarget.Global(), "y");
            check(status(service, available) == ChatMessageStatus.PENDING, "released inflight slot");
            service.onSessionEnded();
            check(service.snapshot().recentOutbound().size() == 65, "bounded status snapshot");
            for (int i=0;i<560;i++) {
                check(service.onDelivery(new ChatDelivery("delivery-"+i, "alice", "x", Instant.now())), "new delivery " + i);
            }
            check(service.snapshot().recentReceived().size() == 100, "bounded received history");
            check(service.snapshot().recentReceived().getFirst().messageId().equals("delivery-460"), "old deliveries discarded");
            fail("RESYNC_REQUIRED", () -> service.eventsAfter(stale, 128));
            ChatSnapshot snap = service.snapshot();
            check(service.eventsAfter(snap.cursor(), 128).events().isEmpty(), "snapshot+cursor boundary");
            service.onDelivery(new ChatDelivery("last", "bob", "hi", Instant.now()));
            check(service.eventsAfter(snap.cursor(), 128).events().size() == 1, "snapshot then next event retained");
            fail("RESYNC_REQUIRED", () -> service.eventsAfter(snap.cursor()+"0", 100));
            fail("RESYNC_REQUIRED", () -> service.eventsAfter(snap.cursor().split(":")[0]+":9223372036854775807", 100));
        }
        try (ChatService newService = new ChatService(command -> CompletableFuture.completedFuture(null), () -> true)) {
            fail("RESYNC_REQUIRED", () -> newService.eventsAfter("old:10", 20));
        }
    }

    private static void testConcurrentSubmission() throws Exception {
        FakeTransport transport = new FakeTransport();
        try (ChatService service = new ChatService(transport, () -> true)) {
            int threads = 12;
            CountDownLatch start = new CountDownLatch(1);
            List<Thread> list = new ArrayList<>();
            AtomicInteger errors = new AtomicInteger();
            List<String> ids = Collections.synchronizedList(new ArrayList<>());
            for (int i=0;i<threads;i++) {
                Thread thread = Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        ids.add(service.send("r-same", new ChatTarget.Global(), "same"));
                    } catch (Exception exception) { errors.incrementAndGet(); }
                });
                list.add(thread);
            }
            start.countDown();
            for (Thread thread : list) thread.join();
            check(errors.get() == 0, "concurrent duplicate requests succeeded");
            check(ids.size() == threads, "all concurrent callers returned");
            check(ids.stream().distinct().count() == 1, "one ID on concurrent dedup");
            check(transport.sent.size() == 1, "transport sent only once");
        }
    }

    public static void main(String[] args) throws Exception {
        testSendIdempotencyReceipts();
        testValidationAndTransportFailure();
        testIncomingAndSessionEnd();
        testCapacityAndResync();
        testConcurrentSubmission();
        System.out.println("PASS: " + assertions + " Member 3 stage 4 chat service checks (mock transport)");
    }
}
