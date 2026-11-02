package org.streamcollab.client.httpbridge;

import org.streamcollab.client.chat.*;
import org.streamcollab.client.connection.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * HTTP-facing application adapter. A real gateway must implement TLS login and authenticated
 * UDP binding from Stages 1/2; the stage-5 smoke test substitutes a fake only in the test.
 * Chat updates from the authenticated dispatcher are imported from ChatService's local journal.
 */
public final class AgentBridgeApi {
    public interface LoginGateway {
        ConnectionState state();
        /** Takes ownership of credentials. Login completion is async, AFTER real TLS/auth/UDP bind. */
        CompletionStage<?> login(ServerEndpoint endpoint, LoginCredentials credentials);
        void logout();
        default String currentUserId() { return null; }
    }
    public record Reply(int status, Object body) { }
    private final LoginGateway gateway;
    private final ChatService chat;
    private final AgentEventJournal journal;
    private final Object lock=new Object();
    private String chatCursor;

    public AgentBridgeApi(LoginGateway gateway, ChatService chat, AgentEventJournal journal) {
        this.gateway=Objects.requireNonNull(gateway);
        this.chat=Objects.requireNonNull(chat);
        this.journal=Objects.requireNonNull(journal);
        this.chatCursor=chat.snapshot().cursor();
    }
    public void onConnectionTransition(ConnectionTransition transition) {
        synchronized(lock) {
            journal.append("CONNECTION_CHANGED",transition.timestamp(),Map.of(
                "previous",transition.previous().name(), "connection_state",transition.current().name(),
                "reason",transition.reason()));
        }
    }
    /** Caller must first verify this comes from authenticated server/session dispatcher. */
    public void onChatReceipt(String messageId, ChatMessageStatus status, String recipient, String failure) {
        synchronized(lock) {
            chat.onReceipt(messageId,status,recipient,failure);
            syncChat();
        }
    }
    /** Caller must first verify this comes from authenticated server/session dispatcher. */
    public void onChatDelivery(ChatDelivery delivery) {
        synchronized(lock) {
            chat.onDelivery(delivery);
            syncChat();
        }
    }
    public void onSessionLost() {
        synchronized(lock) { chat.onSessionEnded(); syncChat(); }
    }
    private static Map<String,Object> target(ChatTarget t) {
        if (t instanceof ChatTarget.Direct d) return Map.of("kind","DIRECT","user_id",d.userId());
        return Map.of("kind","GLOBAL");
    }
    private static Map<String,Object> delivery(ChatDelivery d) {
        return Map.of("message_id",d.messageId(),"sender_id",d.senderId(),
                "text",d.text(),"server_time",d.serverTime().toString());
    }
    private static Map<String,Object> status(ChatStatusView st) {
        Map<String,Object> r=new LinkedHashMap<>();
        r.put("message_id",st.messageId()); r.put("target",target(st.target()));
        r.put("text",st.text()); r.put("status",st.status().name());
        if (st.errorCode()!=null) r.put("error_code",st.errorCode());
        return r;
    }
    private void syncChat() {
        try {
            // If chat journal overflowed, unified browser cursor must be invalidated, not quietly skip.
            while(true) {
                var page=chat.eventsAfter(chatCursor,128);
                for(var record:page.events()) {
                    ChatEvent e=record.data();
                    Map<String,Object> data;
                    if (e instanceof ChatEvent.MessageReceived recv) data=delivery(recv.delivery());
                    else if (e instanceof ChatEvent.MessageStatusChanged changed) {
                        data=new LinkedHashMap<>();
                        data.put("message_id",changed.messageId());
                        data.put("status",changed.status().name());
                        if (changed.recipientId()!=null) data.put("recipient_id",changed.recipientId());
                        if (changed.errorCode()!=null) data.put("error_code",changed.errorCode());
                    } else throw new IllegalStateException("Unknown chat event");
                    journal.append(e.type(),record.timestamp(),data);
                }
                chatCursor=page.nextCursor();
                if (page.events().size()<128) break;
            }
        } catch (ChatFailure expired) {
            if (!"RESYNC_REQUIRED".equals(expired.code())) throw expired;
            // Rotate the entire unified journal: old browser cursor must not mask lost chat events.
            journal.reset();
            chatCursor=chat.snapshot().cursor();
        }
    }
    public Reply login(Map<String,Object> request) {
        MiniJson.keys(request,"server_host","server_tcp_port","username","password");
        String host=MiniJson.str(request,"server_host",253);
        String username=MiniJson.str(request,"username",128);
        Object portValue=request.get("server_tcp_port");
        if (!(portValue instanceof Long l) || l<1 || l>65535) throw ApiProblem.invalid();
        Object pw=request.get("password");
        if (!(pw instanceof String password) || password.isEmpty() || password.length()>1024)
            throw ApiProblem.invalid();
        // No secrets are placed in the event journal. The gateway consumes and clears credentials.
        char[] chars=password.toCharArray();
        LoginCredentials creds;
        try {creds=new LoginCredentials(username,chars);}
        finally {java.util.Arrays.fill(chars,'\0');}
        synchronized(lock) {
            if (gateway.state()!=ConnectionState.DISCONNECTED)
                {creds.close(); throw new ApiProblem(409,"INVALID_STATE",false);}
            try {
                gateway.login(new ServerEndpoint(host,l.intValue()),creds);
            } catch (RuntimeException problem) {
                creds.close();
                throw new ApiProblem(409,"INVALID_STATE",false);
            }
            return new Reply(202,Map.of("accepted",true,"connection_state",gateway.state().name()));
        }
    }
    public Reply logout(Map<String,Object> request) {
        MiniJson.keys(request);
        synchronized(lock) {
            gateway.logout();
            chat.onSessionEnded();
            syncChat();
            return new Reply(200, Map.of("connection_state", gateway.state().name()));
        }
    }
    public Reply send(Map<String,Object> request) {
        MiniJson.keys(request,"request_id","target","text");
        String id=MiniJson.str(request,"request_id",128);
        Object content=request.get("text");
        if (!(content instanceof String text) || text.isBlank() || text.length()>4096)
            throw ApiProblem.invalid();
        Map<String,Object> t=MiniJson.obj(request.get("target"));
        String kind=MiniJson.str(t,"kind",20);
        ChatTarget target;
        switch (kind) {
            case "GLOBAL" -> { MiniJson.keys(t,"kind"); target=new ChatTarget.Global(); }
            case "DIRECT" -> { MiniJson.keys(t,"kind","user_id"); target=new ChatTarget.Direct(MiniJson.str(t,"user_id",128)); }
            default -> throw ApiProblem.invalid();
        }
        synchronized(lock) {
            String mid=chat.send(id,target,text);
            syncChat();
            return new Reply(202,Map.of("message_id",mid));
        }
    }
    public Reply state() {
        synchronized(lock) {
            syncChat(); // before reading snapshot; prevents gaps at browser's event cursor
            ChatSnapshot snapshot=chat.snapshot();
            List<Object> received=new ArrayList<>();
            for (ChatDelivery d:snapshot.recentReceived()) received.add(delivery(d));
            List<Object> outbound=new ArrayList<>();
            for (ChatStatusView st:snapshot.recentOutbound()) outbound.add(status(st));
            Map<String,Object> view=new LinkedHashMap<>();
            view.put("connection_state", gateway.state().name());
            view.put("current_user_id",gateway.currentUserId());
            view.put("recent_received",received);
            view.put("recent_outbound",outbound);
            view.put("transfers",List.of()); // Member 4 file adapter not wired yet.
            view.put("cursor",journal.cursor());
            return new Reply(200,view);
        }
    }
    public Reply events(String after) {
        long deadline=System.nanoTime()+1_000_000_000L;
        while(true) {
            synchronized(lock) {
                syncChat();
                AgentEventJournal.Page page=journal.page(after,128);
                if (!page.events().isEmpty() || System.nanoTime()>=deadline) {
                    List<Object> data=new ArrayList<>();
                    for(var e:page.events()) data.add(Map.of("cursor",e.cursor(),"type",e.type(),
                            "timestamp",e.timestamp().toString(),"data",e.data()));
                    return new Reply(200,Map.of("events",data,"next_cursor",page.nextCursor()));
                }
            }
            try {Thread.sleep(30);} catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new ApiProblem(503,"INTERRUPTED",true);
            }
        }
    }
}
