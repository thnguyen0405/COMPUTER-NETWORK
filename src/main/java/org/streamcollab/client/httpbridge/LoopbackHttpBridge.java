package org.streamcollab.client.httpbridge;

import org.streamcollab.client.chat.ChatFailure;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Restricted HTTP/1.1 local socket bridge. No chunking, keepalive, redirects, multipart, CORS,
 * or generic proxy. Runs only on 127.0.0.1 with per-instance token and bounded resources.
 */
public final class LoopbackHttpBridge implements AutoCloseable {
    public static final int MAX_BODY=16384;
    private static final int MAX_HEADERS=8192;
    private static final int MAX_CLIENTS=16;
    private static final Pattern SAFE_STATIC=Pattern.compile("(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.(?:html|js|css|png|ico|woff2)");
    private final AgentBridgeApi api;
    private final ServerSocket server;
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots=new Semaphore(MAX_CLIENTS);
    private final String token;
    private final Thread acceptor;
    private volatile boolean closed;

    public LoopbackHttpBridge(AgentBridgeApi api, int port) throws IOException {
        this.api=Objects.requireNonNull(api);
        byte[] secret=new byte[32]; new SecureRandom().nextBytes(secret);
        token=Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        java.util.Arrays.fill(secret,(byte)0);
        server=new ServerSocket(port,32,InetAddress.getByName("127.0.0.1"));
        acceptor=Thread.ofPlatform().name("agent-loopback-accept").start(this::acceptLoop);
    }
    public int port() {return server.getLocalPort();}
    /** Pass ONLY to local trusted UI launcher. Never return this token in HTTP API or logs. */
    public String tokenForTrustedLauncher() {return token;}
    /** Fragment is not sent to the server. Member 5 must erase it from browser history immediately. */
    public String launchUri() {return "http://127.0.0.1:"+port()+"/#agent_token="+token;}
    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket=server.accept();
                if (!slots.tryAcquire()) {
                    try (socket) {respond(socket.getOutputStream(),429,problem("BACKPRESSURE",true));}
                    catch (IOException ignored) { }
                    continue;
                }
                workers.execute(() -> {
                    try (socket) {socket.setSoTimeout(3000);handle(socket);}
                    catch (Exception ignored) { /* never print authorization tokens or credentials */ }
                    finally {slots.release();}
                });
            } catch(SocketException ex) {if (!closed) break;}
            catch (IOException ex) {if (!closed) break;}
        }
    }
    private record Request(String method, String target, Map<String,String> headers, byte[] body) { }
    private static String line(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        while (out.size()<max) {
            int ch=in.read();
            if (ch<0) throw ApiProblem.invalid();
            if (ch=='\r') {
                if (in.read()!='\n') throw ApiProblem.invalid();
                return out.toString(StandardCharsets.ISO_8859_1);
            }
            if (ch=='\n' || ch==0 || ch<32 && ch!='\t' || ch>126) throw ApiProblem.invalid();
            out.write(ch);
        }
        throw new ApiProblem(431,"HEADERS_TOO_LARGE",false);
    }
    private Request parse(InputStream in) throws IOException {
        String first=line(in,2048);
        String[] tokens=first.split(" ",-1);
        if (tokens.length!=3 || !tokens[2].equals("HTTP/1.1") ||
                !(tokens[0].equals("GET")||tokens[0].equals("POST")) ||
                !tokens[1].startsWith("/") || tokens[1].length()>1024 ||
                tokens[1].indexOf('%')>=0 || tokens[1].indexOf('#')>=0 ||
                tokens[1].indexOf('\\')>=0 || tokens[1].contains("..") ||
                !tokens[1].matches("/[A-Za-z0-9_./?=&:-]*")) throw ApiProblem.invalid();
        Map<String,String> headers=new HashMap<>(); int total=0;
        while(true) {
            String s=line(in,2048); total+=s.length()+2;
            if (total>MAX_HEADERS || headers.size()>40) throw new ApiProblem(431,"HEADERS_TOO_LARGE",false);
            if (s.isEmpty()) break;
            int colon=s.indexOf(':');
            if (colon<1 || !s.substring(0,colon).matches("[A-Za-z0-9-]+")) throw ApiProblem.invalid();
            String key=s.substring(0,colon).toLowerCase(Locale.ROOT);
            String val=s.substring(colon+1).trim();
            if (headers.containsKey(key) || val.indexOf('\t')>=0 ||
                    val.chars().anyMatch(x->x<32 || x>126)) throw ApiProblem.invalid();
            headers.put(key,val);
        }
        // No ambiguous transfer framing, proxy handling, upgrades, or compressed bodies.
        if (headers.containsKey("transfer-encoding") || headers.containsKey("expect") ||
                headers.containsKey("content-encoding") || headers.containsKey("upgrade")) throw ApiProblem.invalid();
        String length=headers.get("content-length");
        if (tokens[0].equals("POST") && length==null) throw new ApiProblem(411,"LENGTH_REQUIRED",false);
        if (length!=null && !length.matches("0|[1-9][0-9]{0,7}")) throw ApiProblem.invalid();
        int bytes=length==null?0:Integer.parseInt(length);
        if (bytes>MAX_BODY) throw new ApiProblem(413,"SIZE_LIMIT",false);
        if (tokens[0].equals("GET") && bytes!=0) throw ApiProblem.invalid();
        return new Request(tokens[0],tokens[1],headers,in.readNBytes(bytes));
    }
    private void handle(Socket socket) throws IOException {
        try {
            Request request=parse(new BufferedInputStream(socket.getInputStream()));
            if (request.body().length != expectedBodyLength(request.headers())) throw ApiProblem.invalid();
            String host="127.0.0.1:"+port();
            if (!host.equals(request.headers().get("host"))) throw new ApiProblem(403,"FORBIDDEN_HOST",false);
            String origin=request.headers().get("origin");
            if (origin!=null && !("http://"+host).equals(origin))
                throw new ApiProblem(403,"FORBIDDEN_ORIGIN",false);
            if (request.target().startsWith("/api/")) {
                String supplied=request.headers().get("x-agent-token");
                if (supplied==null || !MessageDigest.isEqual(
                        supplied.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))
                    throw new ApiProblem(401,"UNAUTHENTICATED",false);
                AgentBridgeApi.Reply reply=route(request);
                respond(socket.getOutputStream(),reply.status(),Map.of("data",reply.body()));
            } else {
                if (!request.method().equals("GET") || request.target().contains("?"))
                    throw new ApiProblem(404,"NOT_FOUND",false);
                serveStatic(socket.getOutputStream(),request.target());
            }
        } catch (ApiProblem problem) {
            respond(socket.getOutputStream(),problem.status(),problem(problem.code(),problem.retryable()));
        } catch (ChatFailure e) {
            int status=switch (e.code()) {
                case "SESSION_EXPIRED" -> 401;
                case "BACKPRESSURE" -> 429;
                case "RESYNC_REQUIRED","PROTOCOL_ERROR" -> 409;
                default -> 400;
            };
            respond(socket.getOutputStream(),status,problem(e.code(),status==429 || status==409));
        } catch (IllegalArgumentException e) {
            respond(socket.getOutputStream(),400,problem("INVALID_REQUEST",false));
        } catch (RuntimeException e) {
            respond(socket.getOutputStream(),500,problem("LOCAL_ERROR",false));
        }
    }
    private static void serveStatic(OutputStream out,String rawPath) throws IOException {
        String path=rawPath.equals("/")?"index.html":rawPath.substring(1);
        if (!SAFE_STATIC.matcher(path).matches()) throw new ApiProblem(404,"NOT_FOUND",false);
        try (InputStream resource=LoopbackHttpBridge.class.getClassLoader().getResourceAsStream("ui/"+path)) {
            if (resource==null) throw new ApiProblem(404,"NOT_FOUND",false);
            byte[] bytes=resource.readNBytes(1_048_577);
            if (bytes.length>1_048_576) throw new ApiProblem(413,"SIZE_LIMIT",false);
            String mime=path.endsWith(".html")?"text/html":path.endsWith(".js")?"text/javascript":
                    path.endsWith(".css")?"text/css":path.endsWith(".png")?"image/png":
                    path.endsWith(".ico")?"image/x-icon":"font/woff2";
            String head="HTTP/1.1 200 OK\r\n"+
                    "Content-Type: "+mime+"\r\nContent-Length: "+bytes.length+"\r\n"+
                    "Connection: close\r\nCache-Control: no-store\r\n"+
                    "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n"+
                    "Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; object-src 'none'; form-action 'none'\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
        }
    }
    private static int expectedBodyLength(Map<String,String> headers) {
        String len=headers.get("content-length"); return len==null?0:Integer.parseInt(len);
    }
    private AgentBridgeApi.Reply route(Request r) {
        String p=r.target();
        boolean fileEndpoint=(r.method().equals("POST") &&
                (p.equals("/api/uploads") || p.equals("/api/transfers") ||
                p.matches("/api/transfers/[A-Za-z0-9_-]{1,128}/(accept|reject|cancel)"))) ||
                (r.method().equals("GET") && p.matches("/api/transfers/[A-Za-z0-9_-]{1,128}/download"));
        if (fileEndpoint) throw new ApiProblem(501,"FILE_SERVICE_NOT_READY",false);
        if (r.method().equals("GET") && p.equals("/api/state")) return api.state();
        if (r.method().equals("GET") && p.startsWith("/api/events?after=") &&
                p.indexOf('&')<0 && p.substring("/api/events?after=".length()).length()<=96)
            return api.events(p.substring("/api/events?after=".length()));
        if (r.method().equals("POST") && (p.equals("/api/login") || p.equals("/api/logout") || p.equals("/api/messages"))) {
            if (!"application/json".equalsIgnoreCase(r.headers().get("content-type")))
                throw new ApiProblem(415,"UNSUPPORTED_MEDIA_TYPE",false);
            String body;
            try {body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(r.body())).toString();}
            catch (CharacterCodingException e) {throw ApiProblem.invalid();}
            Map<String,Object> data=MiniJson.object(body);
            return switch (p) {
                case "/api/login" -> api.login(data);
                case "/api/logout" -> api.logout(data);
                default -> api.send(data);
            };
        }
        throw new ApiProblem(404,"NOT_FOUND",false);
    }
    private static Map<String,Object> problem(String code,boolean retryable) {
        return Map.of("error",Map.of("code",code,"message",code.replace('_',' ').toLowerCase(Locale.ROOT),
                        "retryable",retryable));
    }
    private static void respond(OutputStream out,int status,Object body) throws IOException {
        String message=switch(status) {
            case 200 -> "OK"; case 202 -> "Accepted"; case 400 -> "Bad Request";
            case 401 -> "Unauthorized"; case 403 -> "Forbidden"; case 404 -> "Not Found";
            case 409 -> "Conflict"; case 411 -> "Length Required"; case 413 -> "Content Too Large";
            case 415 -> "Unsupported Media Type"; case 429 -> "Too Many Requests";
            case 431 -> "Request Header Fields Too Large"; case 501 -> "Not Implemented";
            case 503 -> "Service Unavailable"; default -> "Internal Server Error";
        };
        byte[] json=MiniJson.encode(body).getBytes(StandardCharsets.UTF_8);
        String head="HTTP/1.1 "+status+" "+message+"\r\n"+
                "Content-Type: application/json; charset=utf-8\r\n"+
                "Content-Length: "+json.length+"\r\n"+
                "Connection: close\r\n"+
                "Cache-Control: no-store\r\n"+
                "X-Content-Type-Options: nosniff\r\n"+
                "Referrer-Policy: no-referrer\r\n"+
                "Content-Security-Policy: default-src 'none'\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));out.write(json);out.flush();
    }
    @Override public void close() {
        closed=true;
        try {server.close();} catch(IOException ignored) { }
        workers.shutdownNow();
        try {acceptor.join(1500);} catch (InterruptedException e) {Thread.currentThread().interrupt();}
    }
}
