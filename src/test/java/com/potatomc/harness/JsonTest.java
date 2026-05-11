package com.potatomc.harness;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JsonTest {

    @Test
    void writeString() {
        assertEquals("\"hello\"", Json.write("hello"));
        assertEquals("\"a\\\"b\"", Json.write("a\"b"));
        assertEquals("\"a\\\\b\"", Json.write("a\\b"));
        assertEquals("\"a\\nb\"", Json.write("a\nb"));
    }

    @Test
    void writePrimitives() {
        assertEquals("true", Json.write(true));
        assertEquals("false", Json.write(false));
        assertEquals("null", Json.write(null));
        assertEquals("42", Json.write(42));
        assertEquals("3.14", Json.write(3.14));
    }

    @Test
    void writeList() {
        assertEquals("[1,2,3]", Json.write(List.of(1, 2, 3)));
        assertEquals("[]", Json.write(List.of()));
    }

    @Test
    void writeMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", 1);
        m.put("b", "x");
        assertEquals("{\"a\":1,\"b\":\"x\"}", Json.write(m));
    }

    @Test
    void writeNested() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("xs", List.of(1, 2));
        assertEquals("{\"ok\":true,\"xs\":[1,2]}", Json.write(m));
    }

    @Test
    void parseSimpleObject() {
        Map<String, Object> r = Json.parseObject("{\"command\":\"hi\",\"n\":42}");
        assertEquals("hi", r.get("command"));
        assertEquals(42L, r.get("n"));
    }

    @Test
    void parseBoolAndNull() {
        Map<String, Object> r = Json.parseObject("{\"a\":true,\"b\":false,\"c\":null}");
        assertEquals(Boolean.TRUE, r.get("a"));
        assertEquals(Boolean.FALSE, r.get("b"));
        assertNull(r.get("c"));
        assertTrue(r.containsKey("c"));
    }

    @Test
    void parseNumberArray() {
        Map<String, Object> r = Json.parseObject("{\"xs\":[1,2,3]}");
        assertEquals(List.of(1L, 2L, 3L), r.get("xs"));
    }

    @Test
    void parseStringWithEscapes() {
        Map<String, Object> r = Json.parseObject("{\"s\":\"a\\\"b\\nc\"}");
        assertEquals("a\"b\nc", r.get("s"));
    }

    @Test
    void writeNaNAndInfinityBecomeNull() {
        assertEquals("null", Json.write(Double.NaN));
        assertEquals("null", Json.write(Double.POSITIVE_INFINITY));
        assertEquals("null", Json.write(Double.NEGATIVE_INFINITY));
    }

    @Test
    void parseInvalidNumberThrows() {
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"n\":1.2.3}"));
    }

    @Test
    void parseTruncatedUnicodeEscapeThrows() {
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"s\":\"\\u12\"}"));
    }
}
