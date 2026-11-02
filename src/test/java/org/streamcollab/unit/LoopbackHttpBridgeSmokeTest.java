package org.streamcollab.unit;

import org.streamcollab.client.httpbridge.*;
import org.streamcollab.client.connection.*;
import org.streamcollab.client.chat.*;

import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real loopback HTTP smoke tests; login/UDP/transport are test-only stubs, not server integration. */
public class LoopbackHttpBridgeSmokeTest {
    private static int checks;
    private static void check(boolean ok,String reason) {checks++;if (!ok)throw new AssertionError(reason);}
    private static void status(HttpResponse<String> response,int status) {
        check(response.statusCode()==status,"expected "+status+" got "+response.statusCode()+" body "+response.body());
    }
    static class FakeGateway implements AgentBridgeApi.LoginGateway {
        AtomicReference<ConnectionState> state=new AtomicReference<>(ConnectionState.ONLINE);
        @Override public ConnectionState state() {return state.get();}
        @Override public CompletionStage<?> login(ServerEndpoint endpoint, LoginCredentials credentials) {
            credentials.close();state.set(ConnectionState.CONNECTING);
            return new CompletableFuture<ConnectionState>();
        }
        @Override public void logout() {state.set(ConnectionState.DISCONNECTED);}
        @Override public String currentUserId() {return state.get()==ConnectionState.ONLINE?"tester":null;}
    }
    public static void main(String[] ignored) throws Exception {
        AtomicInteger sends=new AtomicInteger();
        FakeGateway gateway=new FakeGateway();
        ChatService chat=new ChatService(command->{sends.incrementAndGet();return CompletableFuture.completedFuture(null);},
                ()->gateway.state()==ConnectionState.ONLINE);
        AgentEventJournal journal=new AgentEventJournal(32);
        AgentBridgeApi api=new AgentBridgeApi(gateway,chat,journal);
        try (LoopbackHttpBridge server=new LoopbackHttpBridge(api,0);
             HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build()) {
            String base="http://127.0.0.1:"+server.port();
            String token=server.tokenForTrustedLauncher();
            check(token.length()>=40,"cryptographic token");
            check(server.launchUri().contains("#agent_token="),"fragment launch URI");
            HttpResponse<String> unauth=request(http,base,"GET","/api/state",null,null,null);
            status(unauth,401);
            status(request(http,base,"GET","/api/state",null,"incorrect",null),401);
            status(request(http,base,"GET","/api/state",null,token,"https://evil.invalid"),403);
            HttpResponse<String> state=request(http,base,"GET","/api/state",null,token,null);
            status(state,200);
            check(state.body().contains("\"connection_state\":\"ONLINE\""),"state online");
            check(state.body().contains("\"current_user_id\":\"tester\""),"authenticated identity");
            check(!state.body().contains(token),"no token in snapshot");
            String cursor=(String)MiniJson.obj(MiniJson.object(state.body()).get("data")).get("cursor");
            status(request(http,base,"GET","/api/events?after=not-a-cursor",null,token,null),409);
            HttpResponse<String> empty=request(http,base,"GET","/api/events?after="+cursor,null,token,null);
            status(empty,200);check(empty.body().contains("\"events\":[]"),"empty long poll");
            String message="{\"request_id\":\"r1\",\"target\":{\"kind\":\"GLOBAL\"},\"text\":\"hello\"}";
            HttpResponse<String> sent=request(http,base,"POST","/api/messages",message,token,base);
            status(sent,202);
            String mid=(String)MiniJson.obj(MiniJson.object(sent.body()).get("data")).get("message_id");
            check(mid.startsWith("msg-"),"generated message ID");
            HttpResponse<String> resend=request(http,base,"POST","/api/messages",message,token,base);
            status(resend,202);
            check(resend.body().equals(sent.body()),"stable request id");
            check(sends.get()==1,"no duplicate UDP transport command");
            status(request(http,base,"POST","/api/messages",message.replace("hello","changed"),token,null),400);
            HttpResponse<String> event=request(http,base,"GET","/api/events?after="+cursor,null,token,null);
            status(event,200);check(event.body().contains("MESSAGE_STATUS_CHANGED"),"outbound status journal");
            api.onChatReceipt(mid,ChatMessageStatus.ACCEPTED_BY_SERVER,null,null);
            check(request(http,base,"GET","/api/state",null,token,null).body().contains("ACCEPTED_BY_SERVER"),"receipt status state");
            api.onChatDelivery(new ChatDelivery("msg-remote","other","hey",Instant.now()));
            check(request(http,base,"GET","/api/state",null,token,null).body().contains("msg-remote"),"trusted delivery snapshot");
            api.onConnectionTransition(new ConnectionTransition(ConnectionState.CONNECTING,ConnectionState.ONLINE,Instant.now(),"SESSION_READY"));
            check(request(http,base,"GET","/api/events?after="+cursor,null,token,null).body().contains("CONNECTION_CHANGED"),"unified connection event");
            status(request(http,base,"POST","/api/messages","{\"target\":{\"kind\":\"GLOBAL\"}}",token,null),400);
            status(request(http,base,"POST","/api/messages","{\"request_id\":\"a\",\"request_id\":\"b\"}",token,null),400);
            status(request(http,base,"POST","/api/messages","{\"request_id\":\"a\",\"target\":{\"kind\":\"OTHER\"},\"text\":\"hey\"}",token,null),400);
            status(request(http,base,"GET","/api/uploads",null,token,null),404);
            status(request(http,base,"POST","/api/uploads","{}",token,null),501);
            status(request(http,base,"GET","/api/transfers/a/download",null,token,null),501);
            status(request(http,base,"GET","/",null,token,null),404);
            status(request(http,base,"GET","/api/does-not-exist",null,token,null),404);
            statusRaw(server.port(),"GET /api/state HTTP/1.1\r\nHost: attacker.com\r\nX-Agent-Token: "+token+"\r\n\r\n",403);
            statusRaw(server.port(),"GET /api/state HTTP/1.1\r\nHost: 127.0.0.1:"+server.port()+"\r\nX-Agent-Token: "+token+"\r\nTransfer-Encoding: chunked\r\n\r\n",400);
            statusRaw(server.port(),"GET /api/state HTTP/1.1\r\nHost: 127.0.0.1:"+server.port()+"\r\nContent-Length: 0\r\nContent-Length: 0\r\nX-Agent-Token: "+token+"\r\n\r\n",400);
            statusRaw(server.port(),"POST /api/logout HTTP/1.1\r\nHost: 127.0.0.1:"+server.port()+"\r\nX-Agent-Token: "+token+"\r\nContent-Type: application/json\r\n\r\n",411);
            statusRaw(server.port(),"POST /api/logout HTTP/1.1\r\nHost: 127.0.0.1:"+server.port()+"\r\nX-Agent-Token: "+token+"\r\nContent-Type: application/json\r\nContent-Length: 18000\r\n\r\n",413);
            statusRaw(server.port(),"GET /api/../state HTTP/1.1\r\nHost: 127.0.0.1:"+server.port()+"\r\n\r\n",400);
            for(int i=0;i<40;i++) journal.append("ERROR",Instant.now(),Map.of("code","TEST_ONLY"));
            status(request(http,base,"GET","/api/events?after="+cursor,null,token,null),409);
            String current=(String)MiniJson.obj(MiniJson.object(request(http,base,"GET","/api/state",null,token,null).body()).get("data")).get("cursor");
            check(!current.equals(cursor),"resync cursor changed");
            status(request(http,base,"POST","/api/logout","{}",token,base),200);
            check(gateway.state()==ConnectionState.DISCONNECTED,"logout");
            status(request(http,base,"POST","/api/login","{\"server_host\":\"localhost\",\"server_tcp_port\":9000,\"username\":\"guest\",\"password\":\"secret-test\"}",token,base),202);
            check(gateway.state()==ConnectionState.CONNECTING,"login only accepted, not online");
            String after=request(http,base,"GET","/api/state",null,token,null).body();
            check(after.contains("CONNECTING"),"honest async login state");
            check(!after.contains("secret-test"),"password absent from response");
            status(request(http,base,"POST","/api/login","{\"server_host\":\"localhost\",\"server_tcp_port\":9000,\"username\":\"guest\",\"password\":\"secret-test\"}",token,base),409);
        } finally { chat.close(); }
        System.out.println("PASS: "+checks+" Member 3 stage 5 loopback HTTP checks (mock gateway/chat transport)");
    }
    private static HttpResponse<String> request(HttpClient c,String base,String method,String path,String body,String token,String origin) throws Exception {
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(6));
        if (token!=null) b.header("X-Agent-Token",token);
        if (origin!=null) b.header("Origin",origin);
        if (method.equals("POST")) b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        else b.GET();
        return c.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static void statusRaw(int port,String req,int wanted) throws Exception {
        try(Socket socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            check(response.startsWith("HTTP/1.1 "+wanted+" "),"raw "+wanted+" got "+response.substring(0,Math.min(response.length(),80)));
        }
    }
}
