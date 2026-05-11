package com.potatomeasure.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny zero-dep JSON encoder + minimal object/array/primitive parser.
 * Not a complete JSON impl — covers the request/response shapes the harness uses.
 */
public final class Json {

    private Json() {}

    // ---- writer ----

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        writeTo(sb, v);
        return sb.toString();
    }

    private static void writeTo(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof Boolean b) { sb.append(b ? "true" : "false"); return; }
        if (v instanceof Double d) {
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else sb.append(d.toString());
            return;
        }
        if (v instanceof Float fl) {
            if (Float.isNaN(fl) || Float.isInfinite(fl)) sb.append("null");
            else sb.append(fl.toString());
            return;
        }
        if (v instanceof Number n) { sb.append(n.toString()); return; }
        if (v instanceof CharSequence s) { writeString(sb, s.toString()); return; }
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeTo(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object x : it) {
                if (!first) sb.append(',');
                first = false;
                writeTo(sb, x);
            }
            sb.append(']');
            return;
        }
        writeString(sb, v.toString());
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ---- parser ----

    public static Map<String, Object> parseObject(String s) {
        Parser p = new Parser(s);
        p.skipWs();
        Object v = p.value();
        if (!(v instanceof Map<?, ?> m)) throw new IllegalArgumentException("not an object");
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) m;
        return typed;
    }

    private static final class Parser {
        final String s;
        int i;
        Parser(String s) { this.s = s; }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object value() {
            skipWs();
            if (i >= s.length()) throw new IllegalArgumentException("eof");
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (c == 't' || c == 'f') return bool();
            if (c == 'n') return nul();
            return num();
        }

        Map<String, Object> obj() {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { i++; return m; }
            while (true) {
                skipWs();
                String k = str();
                skipWs();
                expect(':');
                Object v = value();
                m.put(k, v);
                skipWs();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected , or } at " + (i-1));
            }
        }

        List<Object> arr() {
            expect('[');
            List<Object> xs = new ArrayList<>();
            skipWs();
            if (peek() == ']') { i++; return xs; }
            while (true) {
                xs.add(value());
                skipWs();
                char c = s.charAt(i++);
                if (c == ']') return xs;
                if (c != ',') throw new IllegalArgumentException("expected , or ] at " + (i-1));
            }
        }

        String str() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\' && i < s.length()) {
                    char n = s.charAt(i++);
                    switch (n) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw new IllegalArgumentException("truncated \\u escape");
                            String hex = s.substring(i, i + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException nfe) {
                                throw new IllegalArgumentException("bad unicode escape: " + hex);
                            }
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + n);
                    }
                } else sb.append(c);
            }
            throw new IllegalArgumentException("unterminated string");
        }

        Boolean bool() {
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            throw new IllegalArgumentException("bad bool at " + i);
        }

        Object nul() {
            if (s.startsWith("null", i)) { i += 4; return null; }
            throw new IllegalArgumentException("bad null at " + i);
        }

        Number num() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || ".eE+-".indexOf(s.charAt(i)) >= 0)) i++;
            String n = s.substring(start, i);
            try {
                if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
                return Long.parseLong(n);
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("invalid number: " + n);
            }
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("expected " + c + " at " + i);
            i++;
        }

        char peek() { return i < s.length() ? s.charAt(i) : '\0'; }
    }
}
