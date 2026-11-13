package com.tomato.reader.api;

import com.tomato.reader.infra.Json;
import com.tomato.reader.source.Entry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 响应映射。字段名与嵌套层级全部对齐参考项目 src/renderer/src/api/parser.ts 的实现：
 * 先穿透 data.data ?? data，再按各自结构取字段，并对多种命名做兜底。
 */
public final class TomatoMapper {

    private TomatoMapper() {
    }

    /** 搜索结果条目 */
    public static final class Book {
        public final String id;
        public final String name;
        public final String author;

        Book(String id, String name, String author) {
            this.id = id;
            this.name = name;
            this.author = author;
        }

        @Override
        public String toString() {
            return author == null || author.isEmpty() ? name : name + " · " + author;
        }
    }

    /** 章节正文 */
    public static final class Content {
        public final String title;
        public final String body;

        Content(String title, String body) {
            this.title = title;
            this.body = body;
        }
    }

    // ------------------------------------------------------------ 穿透包裹

    public static Object digData(Object resp) {
        Object d = Json.path(resp, "data");
        if (d != null) {
            Object inner = Json.path(d, "data");
            return inner != null ? inner : d;
        }
        return resp;
    }

    // ------------------------------------------------------------ 搜索

    public static List<Book> search(Object resp) {
        Object d = digData(resp);
        List<Book> out = new ArrayList<>();

        List<Object> tabs = Json.asList(Json.path(d, "search_tabs"));
        for (Object tab : tabs) {
            List<Object> rows = Json.asList(Json.path(tab, "data"));
            for (Object row : rows) {
                Object bookData = Json.path(row, "book_data");
                if (bookData == null && row instanceof List) {
                    bookData = row;
                }
                if (bookData instanceof List) {
                    for (Object b : Json.asList(bookData)) {
                        addIfBook(out, b);
                    }
                } else if (bookData instanceof java.util.Map) {
                    addIfBook(out, bookData);
                }
            }
        }

        if (out.isEmpty() && d instanceof List) {
            for (Object b : Json.asList(d)) {
                addIfBook(out, b);
            }
        }
        return out;
    }

    private static void addIfBook(List<Book> out, Object node) {
        String id = Json.firstString(node, "book_id", "bookId", "id");
        if (id == null) {
            return;
        }
        String name = Json.firstString(node, "book_name", "title", "book_title", "name");
        String author = Json.firstString(node, "author", "author_name");
        out.add(new Book(id, name == null ? id : name, author == null ? "" : author));
    }

    // ------------------------------------------------------------ 详情

    /** 返回详情节点（可能是 book_info 里的一层） */
    public static Object detail(Object resp) {
        Object d = digData(resp);
        Object info = Json.path(d, "book_info");
        return info != null ? info : d;
    }

    public static String bookName(Object detail) {
        String s = Json.firstString(detail, "book_name", "title", "book_title", "name");
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------ 目录

    public static List<Entry> catalog(Object resp) {
        Object d = digData(resp);
        List<Entry> out = new ArrayList<>();

        List<Object> volumes = Json.asList(Json.path(d, "chapterListWithVolume"));
        for (Object vol : volumes) {
            Object items = vol instanceof List ? vol : Json.path(vol, "items");
            if (items == null) {
                items = Json.path(vol, "chapterList");
            }
            if (items == null) {
                items = Json.path(vol, "data");
            }
            for (Object c : Json.asList(items)) {
                String id = Json.firstString(c, "itemId", "item_id", "chapterId", "chapter_id");
                if (id == null) {
                    continue;
                }
                String title = Json.firstString(c, "title", "chapter_title", "chapterTitle");
                out.add(new Entry(id, title == null ? "" : title));
            }
        }

        if (out.isEmpty()) {
            List<Object> ids = Json.asList(Json.path(d, "allItemIds"));
            for (int i = 0; i < ids.size(); i++) {
                Object raw = ids.get(i);
                if (raw == null) {
                    continue;
                }
                out.add(new Entry(String.valueOf(raw).trim(), "第 " + (i + 1) + " 章"));
            }
        }

        // 去重，保持顺序
        Set<String> seen = new LinkedHashSet<>();
        List<Entry> dedup = new ArrayList<>();
        for (Entry e : out) {
            if (seen.add(e.id)) {
                dedup.add(e);
            }
        }
        return dedup;
    }

    // ------------------------------------------------------------ 正文

    public static Content content(Object resp) {
        Object d = digData(resp);
        Object raw = Json.path(d, "content");
        if (raw == null) {
            raw = Json.path(d, "text");
        }
        if (raw == null) {
            raw = Json.path(d, "html");
        }

        String title = Json.firstString(d, "chapter_title");
        if (title == null) {
            title = Json.path(d, "chapter_info", "title") instanceof String
                    ? (String) Json.path(d, "chapter_info", "title") : null;
        }
        if (title == null) {
            title = Json.firstString(d, "title");
        }
        if (title == null) {
            title = Json.path(d, "chapter_info", "chapter_title") instanceof String
                    ? (String) Json.path(d, "chapter_info", "chapter_title") : null;
        }

        List<String> paragraphs;
        if (raw instanceof List) {
            paragraphs = new ArrayList<>();
            for (Object p : Json.asList(raw)) {
                if (p != null) {
                    String s = String.valueOf(p).trim();
                    if (!s.isEmpty()) {
                        paragraphs.add(s);
                    }
                }
            }
        } else {
            paragraphs = toParagraphs(raw == null ? "" : String.valueOf(raw));
        }

        StringBuilder body = new StringBuilder();
        for (String p : paragraphs) {
            body.append(p).append("\n\n");
        }
        return new Content(title == null ? "" : title, body.toString());
    }

    /** HTML 或纯文本 → 段落列表 */
    static List<String> toParagraphs(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        String text;
        if (raw.indexOf('<') < 0) {
            text = raw;
        } else {
            text = raw.replaceAll("(?is)<script.*?</script>", "")
                    .replaceAll("(?is)<style.*?</style>", "")
                    .replaceAll("(?i)<br\\s*/?>", "\n")
                    .replaceAll("(?i)</p\\s*>", "\n")
                    .replaceAll("(?i)</div\\s*>", "\n")
                    .replaceAll("(?i)<[^>]+>", "");
            text = unescape(text);
        }
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n+")) {
            String s = line.replace('\u00a0', ' ').trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static String unescape(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&ldquo;", "“")
                .replace("&rdquo;", "”")
                .replace("&mdash;", "—")
                .replace("&hellip;", "…")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
    }
}
