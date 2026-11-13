package com.tomato.reader.fanqie;

import com.tomato.reader.infra.Json;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * 番茄网页正文的字体混淆还原表。
 *
 * <p>网页把高频字替换成私有区码位（U+E000 起），再用一套自定义字体渲染回真字。
 * 该字体是全站统一的（哈希 dc027189e0ba4cd），所以映射表可以离线算好内置进来，
 * 运行时只需要做一次字符替换。</p>
 *
 * <p>映射表由「番茄字体 × 思源黑体」逐字渲染比对生成，362 个字全部是唯一匹配。</p>
 */
public final class FontMap {

    private static volatile Map<Character, Character> table;

    private FontMap() {
    }

    public static String decode(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        Map<Character, Character> m = table();
        if (m.isEmpty()) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        boolean changed = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            Character rep = m.get(c);
            if (rep != null) {
                sb.append(rep.charValue());
                changed = true;
            } else {
                sb.append(c);
            }
        }
        return changed ? sb.toString() : raw;
    }

    /** 文本里还剩多少未还原的私有区字符，用来判断映射表是否够用 */
    public static int missing(String text) {
        if (text == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\uE000' && c <= '\uF8FF') {
                n++;
            }
        }
        return n;
    }

    public static int size() {
        return table().size();
    }

    private static Map<Character, Character> table() {
        Map<Character, Character> local = table;
        if (local != null) {
            return local;
        }
        synchronized (FontMap.class) {
            if (table == null) {
                table = load();
            }
            return table;
        }
    }

    private static Map<Character, Character> load() {
        Map<Character, Character> map = new HashMap<>();
        try (InputStream in = FontMap.class.getResourceAsStream("/fontmap.json")) {
            if (in == null) {
                return map;
            }
            String text;
            try (Scanner sc = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
                text = sc.hasNext() ? sc.next() : "";
            }
            Object root = Json.parse(text);
            if (Json.isMap(root)) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) root).entrySet()) {
                    try {
                        char from = (char) Integer.parseInt(e.getKey(), 16);
                        Object v = e.getValue();
                        if (v instanceof String && !((String) v).isEmpty()) {
                            map.put(from, ((String) v).charAt(0));
                        }
                    } catch (NumberFormatException ignored) {
                        // 跳过格式异常的条目
                    }
                }
            }
        } catch (Exception ignored) {
            // 资源缺失时退化为原文输出，不阻断阅读
        }
        return map;
    }
}
