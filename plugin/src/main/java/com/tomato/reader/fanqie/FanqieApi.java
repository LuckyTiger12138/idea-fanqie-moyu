package com.tomato.reader.fanqie;

import com.tomato.reader.infra.Json;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 番茄小说网页版公开接口封装。
 *
 * <p>三个端点都无需登录、无需签名（2026-11 实测可用）：</p>
 * <ul>
 *   <li>搜索 {@code novel.snssdk.com/.../search/v1/} 返回明文 JSON</li>
 *   <li>目录 {@code fanqienovel.com/api/reader/directory/detail} 返回明文 JSON</li>
 *   <li>正文 {@code fanqienovel.com/reader/{itemId}} 是 HTML，且文字经过字体混淆，
 *       需要用 {@link FontMap} 还原</li>
 * </ul>
 */
public final class FanqieApi {

    private static final String SEARCH =
            "https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/"
                    + "?device_platform=android&parent_enterfrom=novel_channel_search.tab."
                    + "&aid=1967&offset=%d&q=%s";

    private static final String DIRECTORY =
            "https://fanqienovel.com/api/reader/directory/detail?bookId=%s";

    private static final String READER = "https://fanqienovel.com/reader/%s";

    private static final Pattern TITLE =
            Pattern.compile("<h1[^>]*class=\"muye-reader-title\"[^>]*>(.*?)</h1>", Pattern.DOTALL);

    private static final Pattern CONTENT_OPEN =
            Pattern.compile("<div[^>]*class=\"muye-reader-content[^\"]*\"[^>]*>", Pattern.DOTALL);

    private static final Pattern P = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL);

    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private FanqieApi() {
    }

    // ---------------------------------------------------------------- 模型

    public static final class Book {
        public final String id;
        public final String title;
        public final String author;
        public final String intro;
        public final String words;

        Book(String id, String title, String author, String intro, String words) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.intro = intro;
            this.words = words;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public static final class Chapter {
        public final String itemId;
        public final String title;
        public final boolean free;

        Chapter(String itemId, String title, boolean free) {
            this.itemId = itemId;
            this.title = title;
            this.free = free;
        }
    }

    public static final class Page {
        public final String title;
        public final String body;

        Page(String title, String body) {
            this.title = title;
            this.body = body;
        }
    }

    // ---------------------------------------------------------------- 搜索

    @SuppressWarnings("unchecked")
    public static List<Book> search(String keyword, int offset) throws IOException {
        String url = String.format(SEARCH, offset, encode(keyword));
        String raw = Http.get(url);
        Object root = Json.parse(raw);
        List<Book> out = new ArrayList<>();
        Object data = Json.path(root, "data");
        Object list = Json.path(data, "ret_data");
        for (Object item : Json.asList(list)) {
            if (!Json.isMap(item)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) item;
            String id = str(m.get("book_id"));
            String title = str(m.get("title"));
            if (id == null || title == null) {
                continue;
            }
            out.add(new Book(id, title, str(m.get("author")), str(m.get("abstract")),
                    str(m.get("word_number"))));
        }
        return out;
    }

    // ---------------------------------------------------------------- 目录

    @SuppressWarnings("unchecked")
    public static List<Chapter> catalog(String bookId) throws IOException {
        String raw = Http.get(String.format(DIRECTORY, encode(bookId)));
        Object root = Json.parse(raw);
        Object data = Json.path(root, "data");
        List<Chapter> out = new ArrayList<>();

        Object volumes = Json.path(data, "chapterListWithVolume");
        for (Object vol : Json.asList(volumes)) {
            for (Object item : Json.asList(vol)) {
                if (!Json.isMap(item)) {
                    continue;
                }
                Map<String, Object> m = (Map<String, Object>) item;
                String id = str(m.get("itemId"));
                if (id == null) {
                    id = str(m.get("item_id"));
                }
                String title = str(m.get("title"));
                if (id == null) {
                    continue;
                }
                out.add(new Chapter(id, title == null ? "" : title, isFree(m)));
            }
        }

        // 兜底：只有 id 列表时补成占位标题
        if (out.isEmpty()) {
            int i = 1;
            for (Object idObj : Json.asList(Json.path(data, "allItemIds"))) {
                String id = str(idObj);
                if (id != null) {
                    out.add(new Chapter(id, "第 " + i + " 章", true));
                    i++;
                }
            }
        }
        return out;
    }

    private static boolean isFree(Map<String, Object> m) {
        Object needPay = m.get("needPay");
        if (needPay instanceof Number && ((Number) needPay).intValue() != 0) {
            return false;
        }
        Object lock = m.get("isChapterLock");
        if (Boolean.TRUE.equals(lock)) {
            return false;
        }
        Object paidPub = m.get("isPaidPublication");
        return !Boolean.TRUE.equals(paidPub);
    }

    // ---------------------------------------------------------------- 正文

    public static Page chapter(String itemId) throws IOException {
        String html = Http.get(String.format(READER, encode(itemId)));
        String title = "";
        Matcher tm = TITLE.matcher(html);
        if (tm.find()) {
            title = clean(tm.group(1));
        }

        String block = extractContent(html);
        StringBuilder sb = new StringBuilder();
        Matcher pm = P.matcher(block);
        while (pm.find()) {
            String para = clean(pm.group(1));
            if (para.isEmpty()) {
                continue;
            }
            sb.append(para).append('\n');
        }
        String raw = sb.toString();
        return new Page(FontMap.decode(title), FontMap.decode(raw));
    }

    /** 截取正文容器内部，避免把页面上其它 p 标签混进来 */
    private static String extractContent(String html) {
        Matcher m = CONTENT_OPEN.matcher(html);
        if (!m.find()) {
            return html;
        }
        int start = m.end();
        int end = html.length();
        // 正文块之后通常紧跟导航/推荐位，遇到就截止
        for (String marker : new String[]{"muye-reader-nav", "muye-reader-tools",
                "muye-reader-footer", "muye-reader-recommend"}) {
            int i = html.indexOf(marker, start);
            if (i > 0 && i < end) {
                end = i;
            }
        }
        return html.substring(start, Math.min(end, start + 200_000));
    }

    private static String clean(String html) {
        if (html == null) {
            return "";
        }
        String s = TAG.matcher(html).replaceAll("");
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&ldquo;", "“")
                .replace("&rdquo;", "”");
        // 数字实体
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
            } catch (Exception e) {
                m.appendReplacement(sb, "");
            }
        }
        m.appendTail(sb);
        return sb.toString().replace('\u00A0', ' ').trim();
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof String) {
            return ((String) o).isEmpty() ? null : (String) o;
        }
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return String.valueOf(o);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }
}
