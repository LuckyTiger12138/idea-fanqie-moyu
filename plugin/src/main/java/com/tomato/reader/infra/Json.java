package com.tomato.reader.infra;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器。平台自带的 Gson 在 2021.3 里并不存在，
 * 为了不引入任何第三方依赖（避免版本冲突导致插件装不上），这里自带一份。
 *
 * <p>解析结果：object → {@code Map<String,Object>}，array → {@code List<Object>}，
 * string → {@code String}，number → {@code Double}，true/false → {@code Boolean}，null → {@code null}。</p>
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    public static Object parse(String text) {
        if (text == null) {
            return null;
        }
        Json p = new Json(text);
        p.ws();
        return p.value();
    }

    // ------------------------------------------------------------ 解析

    private void ws() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private Object value() {
        ws();
        if (pos >= src.length()) {
            return null;
        }
        char c = src.charAt(pos);
        if (c == '{') {
            return object();
        }
        if (c == '[') {
            return array();
        }
        if (c == '"') {
            return string();
        }
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        ws();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (pos < src.length()) {
            ws();
            String key = string();
            ws();
            if (pos < src.length() && src.charAt(pos) == ':') {
                pos++;
            }
            Object val = value();
            map.put(key, val);
            ws();
            if (pos < src.length() && src.charAt(pos) == ',') {
                pos++;
                continue;
            }
            if (pos < src.length() && src.charAt(pos) == '}') {
                pos++;
            }
            break;
        }
        return map;
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        ws();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (pos < src.length()) {
            list.add(value());
            ws();
            if (pos < src.length() && src.charAt(pos) == ',') {
                pos++;
                continue;
            }
            if (pos < src.length() && src.charAt(pos) == ']') {
                pos++;
            }
            break;
        }
        return list;
    }

    private String string() {
        if (pos >= src.length() || src.charAt(pos) != '"') {
            return "";
        }
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c == '\\' && pos < src.length()) {
                char e = src.charAt(pos++);
                switch (e) {
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
                        if (pos + 4 <= src.length()) {
                            try {
                                sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ignored) {
                                // 非法转义，原样丢弃
                            }
                            pos += 4;
                        }
                        break;
                    default:
                        sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Double number() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                pos++;
            } else {
                break;
            }
        }
        try {
            return Double.valueOf(src.substring(start, pos));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 取值工具

    /** 按路径逐层深入：path(map, "data", "book_info") */
    public static Object path(Object node, String... keys) {
        Object cur = node;
        for (String k : keys) {
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<?, ?>) cur).get(k);
        }
        return cur;
    }

    /** 在 map 上依次尝试多个键名，返回第一个非空字符串（兼容 snake_case / camelCase） */
    public static String firstString(Object node, String... keys) {
        if (!(node instanceof Map)) {
            return null;
        }
        for (String k : keys) {
            Object v = ((Map<?, ?>) node).get(k);
            if (v != null && !(v instanceof Map) && !(v instanceof List)) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty() && !"null".equals(s)) {
                    return s;
                }
            }
        }
        return null;
    }

    public static List<Object> asList(Object node) {
        if (node instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) node;
            return l;
        }
        List<Object> empty = new ArrayList<>();
        return empty;
    }

    public static boolean isMap(Object node) {
        return node instanceof Map;
    }
}
