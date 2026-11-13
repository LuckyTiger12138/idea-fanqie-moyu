package com.tomato.reader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 把纯文本切成"节"，并渲染成三种伪装形态。
 *
 * <p>COMMENT 形态生成语法合法的 .java，正文全在行注释里，IDEA 不会报红；
 * PROPS 形态生成 i18n 资源包，满屏中文在这里是正常现象；PLAIN 形态是普通 markdown。</p>
 */
public final class BookParser {

    /** 章节标题：第X章 / 第X节 / 第X回 / Chapter N，允许前面带 # = - * 等标记 */
    private static final Pattern HEAD =
            Pattern.compile("^(第\\s*[0-9０-９一二三四五六七八九十百千零两]{1,8}\\s*[章节回卷回篇]"
                    + "|Chapter\\s+[0-9IVXLC]+)",
                    Pattern.CASE_INSENSITIVE);

    /** 标题行前面可能出现的 markdown / 分隔符杂音 */
    private static final Pattern LEAD_NOISE = Pattern.compile("^[#=\\-\\*\\s\\[【(（]+");

    private static final String PUNCT = "，。！？；：、”』》）…—";

    /** 没有章节标记时，按这个字数切片段 */
    private static final int FALLBACK_SIZE = 1800;

    private BookParser() {
    }

    // ------------------------------------------------------------------ 解析

    public static List<Chapter> parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String text = raw.replace("\r\n", "\n").replace("\r", "\n").replace('\u3000', ' ');
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        String[] lines = text.split("\n", -1);

        List<Chapter> out = new ArrayList<>();
        String curTitle = null;
        StringBuilder cur = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            // 去掉 "### " "- " "【" 之类的前缀，便于识别下过载的标题
            String probe = LEAD_NOISE.matcher(trimmed).replaceFirst("").trim();
            boolean isHead = probe.length() > 1 && probe.length() <= 40 && HEAD.matcher(probe).find();
            if (isHead) {
                if (curTitle != null || cur.length() > 0) {
                    out.add(new Chapter(curTitle == null ? "开篇" : curTitle, cur.toString()));
                }
                curTitle = probe;
                cur.setLength(0);
            } else {
                cur.append(line).append('\n');
            }
        }
        if (curTitle != null || cur.length() > 0) {
            out.add(new Chapter(curTitle == null ? "开篇" : curTitle, cur.toString()));
        }

        if (out.size() <= 1) {
            String whole = out.isEmpty() ? text : out.get(0).body;
            return splitBySize(whole);
        }
        return out;
    }

    /** 无章节标记的 txt：按字数切成均匀片段，保证一屏能读完一节 */
    private static List<Chapter> splitBySize(String whole) {
        List<Chapter> out = new ArrayList<>();
        int len = whole.length();
        int idx = 1;
        int start = 0;
        while (start < len) {
            int end = Math.min(len, start + FALLBACK_SIZE);
            if (end < len) {
                int cut = whole.indexOf('\n', end);
                if (cut > 0 && cut - end < 400) {
                    end = cut + 1;
                }
            }
            out.add(new Chapter("片段 " + idx, whole.substring(start, end)));
            idx++;
            start = end;
        }
        return out;
    }

    // ------------------------------------------------------------------ 折行

    private static int widthOf(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) > 0x2E7F ? 2 : 1;
        }
        return w;
    }

    /** 按显示宽度折行（中文按 2 计），优先在标点处断开 */
    static List<String> wrap(String paragraph, int limit) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int w = 0;
        for (int i = 0; i < paragraph.length(); i++) {
            char ch = paragraph.charAt(i);
            int cw = ch > 0x2E7F ? 2 : 1;
            if (w + cw > limit && cur.length() > 0) {
                int cut = -1;
                for (int k = cur.length() - 1; k >= cur.length() / 2; k--) {
                    if (PUNCT.indexOf(cur.charAt(k)) >= 0) {
                        cut = k;
                        break;
                    }
                }
                if (cut >= cur.length() / 2) {
                    out.add(cur.substring(0, cut + 1));
                    cur.delete(0, cut + 1);
                } else {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                w = widthOf(cur.toString());
            }
            cur.append(ch);
            w += cw;
        }
        if (cur.toString().trim().length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 正文 → 行列表（空串表示段落间隔） */
    private static List<String> bodyLines(String body, int width) {
        List<String> lines = new ArrayList<>();
        String[] paras = body.replace("\r", "").split("\n\\s*\n");
        boolean first = true;
        for (String para : paras) {
            String flat = para.replace("\n", "").trim();
            if (flat.isEmpty()) {
                continue;
            }
            if (!first) {
                lines.add("");
            }
            first = false;
            for (String seg : wrap(flat, Math.max(20, width))) {
                String s = seg.trim();
                if (!s.isEmpty()) {
                    lines.add(s);
                }
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------ 渲染

    public static String fileNameFor(String skin) {
        if ("PROPS".equals(skin)) {
            return "messages_dev_zh_CN.properties";
        }
        if ("PLAIN".equals(skin)) {
            return "work-notes.md";
        }
        return "WorkLog.java";
    }

    public static String render(Chapter chapter, String skin, int width, int index, int total) {
        if ("PROPS".equals(skin)) {
            return renderProps(chapter, width, index, total);
        }
        if ("PLAIN".equals(skin)) {
            return renderPlain(chapter, index, total);
        }
        return renderJava(chapter, width, index, total);
    }

    private static String pad(int n) {
        return n < 10 ? "00" + n : n < 100 ? "0" + n : String.valueOf(n);
    }

    private static String renderJava(Chapter chapter, int width, int index, int total) {
        List<String> lines = bodyLines(chapter.body, width);
        StringBuilder sb = new StringBuilder(8192);
        sb.append("package com.example.worknotes;\n\n");
        sb.append("import java.util.HashMap;\n");
        sb.append("import java.util.Map;\n\n");
        sb.append("/**\n");
        sb.append(" * 调试记录与临时笔记。\n");
        sb.append(" *\n");
        sb.append(" * <p>").append(safe(chapter.title)).append("  ")
                .append(index + 1).append('/').append(total).append("</p>\n");
        sb.append(" */\n");
        sb.append("public final class WorkLog {\n\n");
        sb.append("    private static final Map<String, String> CACHE = new HashMap<>();\n\n");
        sb.append("    private WorkLog() {\n");
        sb.append("        throw new UnsupportedOperationException();\n");
        sb.append("    }\n");

        int idx = 0;
        int cnt = 0;
        boolean open = false;
        boolean headDone = false;

        for (String line : lines) {
            if (!open) {
                String id = "trace" + pad(idx);
                sb.append("\n    /**\n     * 第 ").append(idx + 1).append(" 段。\n     */\n");
                sb.append("    static void ").append(id).append("() {\n");
                sb.append("        String tag = \"").append(id).append("\";\n");
                sb.append("        CACHE.put(tag, String.valueOf(System.nanoTime()));\n\n");
                open = true;
                if (!headDone) {
                    sb.append("        //\n");
                    sb.append("        // ------------------------------ ").append(safe(chapter.title)).append('\n');
                    sb.append("        //\n");
                    headDone = true;
                }
            }
            if (line.isEmpty()) {
                sb.append("        //\n");
            } else {
                sb.append("        // ").append(line).append('\n');
            }
            cnt++;
            if (cnt >= 40) {
                sb.append("    }\n");
                open = false;
                cnt = 0;
                idx++;
            }
        }
        if (open) {
            sb.append("    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String renderProps(Chapter chapter, int width, int index, int total) {
        List<String> lines = bodyLines(chapter.body, width);
        StringBuilder sb = new StringBuilder(8192);
        sb.append("# 内部调试文案\n");
        sb.append("# ").append(safe(chapter.title)).append("  ")
                .append(index + 1).append('/').append(total).append('\n');
        sb.append('\n');
        int i = 0;
        for (String line : lines) {
            if (line.isEmpty()) {
                sb.append('\n');
            } else {
                sb.append("log.msg.").append(String.format("%04d", i)).append("=   ").append(line).append('\n');
                i++;
            }
        }
        return sb.toString();
    }

    private static String renderPlain(Chapter chapter, int index, int total) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("# ").append(safe(chapter.title)).append('\n');
        sb.append('\n');
        sb.append("> ").append(index + 1).append('/').append(total).append('\n');
        sb.append('\n');
        sb.append(chapter.body.trim()).append('\n');
        return sb.toString();
    }

    /** 去掉会破坏注释/markdown 结构的字符 */
    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("*/", "* /").replace("\n", " ").replace("\r", " ");
    }
}
