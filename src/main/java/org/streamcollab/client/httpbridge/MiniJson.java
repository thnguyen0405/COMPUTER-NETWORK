package org.streamcollab.client.httpbridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal strict JSON for the small local API: depth/size bounded by caller, rejects duplicate keys. */
public final class MiniJson {
    private MiniJson() { }
    public static Map<String,Object> object(String source) {
        Parser p = new Parser(source);
        Object value = p.value(0);
        p.whitespace();
        if (p.pos != p.s.length() || !(value instanceof Map<?,?>)) throw ApiProblem.invalid();
        @SuppressWarnings("unchecked") Map<String,Object> obj = (Map<String,Object>) value;
        return obj;
    }
    public static String encode(Object value) {
        StringBuilder s = new StringBuilder();
        emit(value, s);
        return s.toString();
    }
    private static void emit(Object v, StringBuilder b) {
        if (v == null) b.append("null");
        else if (v instanceof String str) {
            b.append('"');
            for (int i=0;i<str.length();i++) {
                char c = str.charAt(i);
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> {
                        if (c < 32) b.append(String.format("\\u%04x", (int)c));
                        else b.append(c);
                    }
                }
            }
            b.append('"');
        } else if (v instanceof Boolean || v instanceof Number) b.append(v);
        else if (v instanceof Map<?,?> map) {
            b.append('{'); boolean first=true;
            for (var e:map.entrySet()) {
                if (!(e.getKey() instanceof String)) throw new IllegalArgumentException("JSON key");
                if (!first) b.append(','); first=false;
                emit(e.getKey(), b); b.append(':'); emit(e.getValue(), b);
            }
            b.append('}');
        } else if (v instanceof Iterable<?> it) {
            b.append('['); boolean first=true;
            for (Object x:it) { if (!first) b.append(','); first=false; emit(x,b); }
            b.append(']');
        } else throw new IllegalArgumentException("Unsupported JSON type");
    }
    public static Map<String,Object> obj(Object value) {
        if (!(value instanceof Map<?,?> m)) throw ApiProblem.invalid();
        @SuppressWarnings("unchecked") Map<String,Object> map=(Map<String,Object>)m;
        return map;
    }
    public static String str(Map<String,Object> obj, String key, int max) {
        Object val=obj.get(key);
        if (!(val instanceof String s) || s.isBlank() || s.length()>max || s.chars().anyMatch(Character::isISOControl))
            throw ApiProblem.invalid();
        return s;
    }
    public static void keys(Map<String,Object> obj, String... allowed) {
        if (!java.util.Set.of(allowed).containsAll(obj.keySet())) throw ApiProblem.invalid();
    }
    private static final class Parser {
        final String s; int pos;
        Parser(String s) { this.s=s; if (s.length()>20000) throw ApiProblem.invalid(); }
        void whitespace() { while (pos<s.length() && " \r\n\t".indexOf(s.charAt(pos))>=0) pos++; }
        char peek() { whitespace(); return pos<s.length()?s.charAt(pos):'\0'; }
        void require(char c) { if (peek()!=c) throw ApiProblem.invalid(); pos++; }
        Object value(int depth) {
            if (depth>10) throw ApiProblem.invalid();
            char c=peek();
            if (c=='"') return string();
            if (c=='{') {
                pos++; Map<String,Object> m=new LinkedHashMap<>();
                if (peek()=='}') { pos++; return m; }
                while (true) {
                    if (peek()!='"') throw ApiProblem.invalid();
                    String key=string(); require(':'); Object val=value(depth+1);
                    if (m.containsKey(key)) throw ApiProblem.invalid();
                    m.put(key,val);
                    if (peek()=='}') { pos++; return m; }
                    require(',');
                }
            }
            if (c=='[') {
                pos++; List<Object> list=new ArrayList<>();
                if (peek()==']') {pos++; return list;}
                while (true) {
                    if (list.size()>=256) throw ApiProblem.invalid();
                    list.add(value(depth+1));
                    if (peek()==']') {pos++; return list;} require(',');
                }
            }
            if (s.startsWith("true",pos)) {pos+=4;return true;}
            if (s.startsWith("false",pos)) {pos+=5;return false;}
            if (s.startsWith("null",pos)) {pos+=4;return null;}
            if (c=='-' || c>='0'&&c<='9') {
                int begin=pos;
                if (c=='-') pos++;
                if (pos>=s.length()) throw ApiProblem.invalid();
                if (s.charAt(pos)=='0') pos++;
                else {
                    if (s.charAt(pos)<'1'||s.charAt(pos)>'9') throw ApiProblem.invalid();
                    while (pos<s.length() && Character.isDigit(s.charAt(pos))) pos++;
                }
                if (pos<s.length() && (s.charAt(pos)=='.'||s.charAt(pos)=='e'||s.charAt(pos)=='E')) {
                    throw ApiProblem.invalid(); // API takes no fractional/exp numbers
                }
                try {return Long.parseLong(s.substring(begin,pos));}
                catch (NumberFormatException e) {throw ApiProblem.invalid();}
            }
            throw ApiProblem.invalid();
        }
        String string() {
            require('"'); StringBuilder b=new StringBuilder();
            boolean closed=false;
            while (pos<s.length()) {
                char c=s.charAt(pos++);
                if (c=='"') {closed=true;break;}
                if (c<32) throw ApiProblem.invalid();
                if (c=='\\') {
                    if (pos>=s.length()) throw ApiProblem.invalid();
                    char esc=s.charAt(pos++);
                    switch (esc) {
                        case '"','\\','/' -> b.append(esc);
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'u' -> {
                            if (pos+4>s.length()) throw ApiProblem.invalid();
                            try {b.append((char)Integer.parseInt(s.substring(pos,pos+4),16));}
                            catch (NumberFormatException e) {throw ApiProblem.invalid();}
                            pos+=4;
                        }
                        default -> throw ApiProblem.invalid();
                    }
                } else b.append(c);
                if (b.length()>16384) throw ApiProblem.invalid();
            }
            if (!closed) throw ApiProblem.invalid();
            String out=b.toString();
            for (int i=0;i<out.length();i++) {
                char c=out.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i>=out.length() || !Character.isLowSurrogate(out.charAt(i))) throw ApiProblem.invalid();
                } else if (Character.isLowSurrogate(c)) throw ApiProblem.invalid();
            }
            return out;
        }
    }
}
