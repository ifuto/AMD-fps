package net.amdfaster.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A recursive descent JSON reader, because the benchmark loads real model files and the test source
 * set has no JSON library on it.
 *
 * <p>Deliberately small and deliberately strict about structure rather than about grammar: it reads
 * objects, arrays, strings, numbers, booleans and null, which is exactly the subset Minecraft's model
 * format uses. Anything else throws, so a malformed asset file fails the benchmark instead of quietly
 * producing a model with no cubes in it -- which would look like a very fast renderer.
 */
final class TinyJson {

    private final String text;
    private int at;

    private TinyJson(String text) {
        this.text = text;
    }

    static Object parse(String json) {
        TinyJson reader = new TinyJson(json);
        reader.whitespace();
        Object value = reader.value();
        reader.whitespace();
        if (reader.at != reader.text.length()) {
            throw new IllegalArgumentException("trailing characters at " + reader.at);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("expected an object, got " + value);
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object value) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException("expected an array, got " + value);
        }
        return (List<Object>) value;
    }

    static double number(Object value) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException("expected a number, got " + value);
        }
        return ((Number) value).doubleValue();
    }

    static String string(Object value) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("expected a string, got " + value);
        }
        return (String) value;
    }

    static boolean flag(Object value) {
        return value instanceof Boolean && (Boolean) value;
    }

    /** Reads a member as an array of doubles, which is how model files store from/to/uv/origin/size. */
    static double[] vector(Object value, int expected) {
        List<Object> items = array(value);
        if (items.size() != expected) {
            throw new IllegalArgumentException("expected " + expected + " numbers, got " + items.size());
        }
        double[] out = new double[expected];
        for (int i = 0; i < expected; i++) {
            out[i] = number(items.get(i));
        }
        return out;
    }

    private Object value() {
        char c = this.text.charAt(this.at);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        this.at++; // '{'
        whitespace();
        if (this.text.charAt(this.at) == '}') {
            this.at++;
            return map;
        }
        while (true) {
            whitespace();
            String key = readString();
            whitespace();
            if (this.text.charAt(this.at) != ':') {
                throw new IllegalArgumentException("expected ':' at " + this.at);
            }
            this.at++;
            whitespace();
            map.put(key, value());
            whitespace();
            char c = this.text.charAt(this.at);
            if (c == ',') {
                this.at++;
                continue;
            }
            if (c == '}') {
                this.at++;
                return map;
            }
            throw new IllegalArgumentException("expected ',' or '}' at " + this.at);
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        this.at++; // '['
        whitespace();
        if (this.text.charAt(this.at) == ']') {
            this.at++;
            return list;
        }
        while (true) {
            whitespace();
            list.add(value());
            whitespace();
            char c = this.text.charAt(this.at);
            if (c == ',') {
                this.at++;
                continue;
            }
            if (c == ']') {
                this.at++;
                return list;
            }
            throw new IllegalArgumentException("expected ',' or ']' at " + this.at);
        }
    }

    private String readString() {
        if (this.text.charAt(this.at) != '"') {
            throw new IllegalArgumentException("expected a string at " + this.at);
        }
        this.at++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = this.text.charAt(this.at++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char escape = this.text.charAt(this.at++);
                switch (escape) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'u':
                        sb.append((char) Integer.parseInt(this.text.substring(this.at, this.at + 4), 16));
                        this.at += 4;
                        break;
                    default:
                        sb.append(escape);
                        break;
                }
                continue;
            }
            sb.append(c);
        }
    }

    private Double readNumber() {
        int start = this.at;
        while (this.at < this.text.length()) {
            char c = this.text.charAt(this.at);
            boolean numeric = (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E';
            if (!numeric) {
                break;
            }
            this.at++;
        }
        return Double.valueOf(this.text.substring(start, this.at));
    }

    private void expect(String literal) {
        if (!this.text.startsWith(literal, this.at)) {
            throw new IllegalArgumentException("expected " + literal + " at " + this.at);
        }
        this.at += literal.length();
    }

    private void whitespace() {
        while (this.at < this.text.length() && Character.isWhitespace(this.text.charAt(this.at))) {
            this.at++;
        }
    }
}
