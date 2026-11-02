package org.streamcollab.client.httpbridge;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Single bounded, instance-scoped event stream for the browser. Never persist secret keys here. */
public final class AgentEventJournal {
    public record AgentEvent(String cursor, String type, Instant timestamp, Map<String,Object> data) { }
    public record Page(List<AgentEvent> events, String nextCursor) { }
    private final ArrayDeque<AgentEvent> events = new ArrayDeque<>();
    private String instance = UUID.randomUUID().toString();
    private final int capacity;
    private long sequence;

    public AgentEventJournal(int capacity) {
        if (capacity < 2 || capacity > 4096) throw new IllegalArgumentException("capacity");
        this.capacity=capacity;
    }
    public synchronized String cursor() { return instance+":"+sequence; }
    /** Invalidate all browser cursors after upstream journal overflow or agent session reset. */
    public synchronized void reset() { events.clear(); sequence=0; instance=UUID.randomUUID().toString(); notifyAll(); }
    public synchronized void append(String type, Instant timestamp, Map<String,Object> data) {
        sequence++;
        events.addLast(new AgentEvent(cursor(),type,timestamp,Map.copyOf(data)));
        while (events.size()>capacity) events.removeFirst();
        notifyAll();
    }
    public synchronized Page page(String cursor, int max) {
        if (max<1 || max>128) throw ApiProblem.invalid();
        long after=parse(cursor);
        if (after > sequence || !events.isEmpty() && after < (sequence-events.size()))
            throw new ApiProblem(409,"RESYNC_REQUIRED",true);
        List<AgentEvent> result=new ArrayList<>();
        for (AgentEvent e:events) {
            if (parse(e.cursor())>after) {
                result.add(e);
                if (result.size()==max) break;
            }
        }
        return new Page(List.copyOf(result),result.isEmpty()?cursor:result.getLast().cursor());
    }
    private long parse(String cursor) {
        if (cursor==null || !cursor.startsWith(instance+":"))
            throw new ApiProblem(409,"RESYNC_REQUIRED",true);
        String tail=cursor.substring(instance.length()+1);
        if (!tail.matches("0|[1-9][0-9]{0,18}")) throw new ApiProblem(409,"RESYNC_REQUIRED",true);
        try {return Long.parseLong(tail);} catch(NumberFormatException e) {
            throw new ApiProblem(409,"RESYNC_REQUIRED",true);
        }
    }
}
