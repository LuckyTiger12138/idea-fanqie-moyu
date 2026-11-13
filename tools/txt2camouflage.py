#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
txt2camouflage —— 把小说 txt 转成"看起来像代码"的文件，用于在 IDE 里低调阅读。

三种载体：
  java  生成语法合法的 .java，正文以行注释形式嵌在方法体内（无红波浪线）
  props 生成 i18n 资源包形态 .properties，正文作为 value（前景色为正常深色，可读性最好）
  sql   生成 .sql，正文以 -- 注释嵌在 INSERT 语句之间

用法：
  python txt2camouflage.py 小说.txt -o out/WorkLog.java --style java
  python txt2camouflage.py 小说.txt -o out/messages_zh_CN.properties --style props
  python txt2camouflage.py 小说.txt -o out/notes.sql --style sql --width 42
"""
import argparse
import os
import re
import sys

CHAPTER_RE = re.compile(r"^\s*(第[0-9一二三四五六七八九十百千零两]+[章节回卷]|Chapter\s+\d+|CHAPTER\s+\d+)")


def normalize(text: str) -> str:
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = text.replace("\u3000", " ")          # 全角空格
    text = re.sub(r"[ \t]+", " ", text)
    return text


def split_paragraphs(text: str):
    """按空行切段，返回 [(是否章节标题, 标题文本)] 与正文段落列表"""
    blocks, buf = [], []
    for line in normalize(text).split("\n"):
        s = line.strip()
        if not s:
            if buf:
                blocks.append("".join(buf))
                buf = []
            continue
        buf.append(s)
    if buf:
        blocks.append("".join(buf))
    return blocks


def display_width(s: str) -> int:
    """中文按 2 计宽，ASCII 按 1，用于折行"""
    w = 0
    for ch in s:
        w += 2 if ord(ch) > 0x2E7F else 1
    return w


def wrap(paragraph: str, limit: int):
    """按显示宽度折行，但不在标点处硬断得太难看：遇到标点且已超 60% 就先断"""
    out, cur, cur_w = [], "", 0
    for ch in paragraph:
        w = 2 if ord(ch) > 0x2E7F else 1
        if cur_w + w > limit:
            cut = max(cur.rfind(p) for p in "，。！？；：、”』）") if any(p in cur for p in "，。！？；：、”』）") else -1
            if cut > len(cur) * 0.5:
                out.append(cur[: cut + 1])
                cur = cur[cut + 1:]
                cur_w = display_width(cur)
            else:
                out.append(cur)
                cur, cur_w = "", 0
        cur += ch
        cur_w += w
    if cur.strip():
        out.append(cur)
    return out


def build_lines(blocks, width, para_gap=True, plain_section=True):
    """把段落转成待输出的行；章节标题转成中性分隔行，段间补注释空行"""
    lines = []
    first_body = True
    for b in blocks:
        if CHAPTER_RE.match(b) and display_width(b) <= width * 1.2:
            title = b.strip()
            if plain_section:
                title = CHAPTER_RE.sub("", title).strip() or title
                if not title.startswith("第"):
                    title = title.lstrip("：:，, ")
            lines.append(("gap", "--"))
            lines.append(("section", title))
            lines.append(("gap", "--"))
            first_body = True
        else:
            if para_gap and not first_body:
                lines.append(("gap", "--"))
            first_body = False
            for seg in wrap(b, width):
                if seg.strip():
                    lines.append(("body", seg.strip()))
    return lines


def render_java(lines, class_name):
    out = [
        "package com.example.worklog;",
        "",
        "import java.util.HashMap;",
        "import java.util.Map;",
        "",
        "/**",
        " * 调试记录与临时笔记。",
        " *",
        " * @author dev",
        " */",
        f"public final class {class_name} {{",
        "",
        "    private static final Map<String, String> CACHE = new HashMap<>();",
        "",
        f"    private {class_name}() {{",
        "        throw new UnsupportedOperationException();",
        "    }",
        "",
    ]
    block, idx = [], 0
    for kind, text in lines:
        block.append((kind, text))
        if len([1 for k, _ in block if k == "body"]) >= 40:
            out.extend(_java_method(block, idx))
            idx += 1
            block = []
    if block:
        out.extend(_java_method(block, idx))
    out.append("}")
    return "\n".join(out) + "\n"


def _java_method(block, idx):
    name = f"trace{idx:03d}"
    res = [
        f"    /**",
        f"     * 第 {idx + 1} 段日志片段。",
        f"     */",
        f"    static void {name}() {{",
        f"        String tag = \"{name}\";",
        f"        CACHE.put(tag, String.valueOf(System.nanoTime()));",
        "",
    ]
    for kind, text in block:
        if kind == "gap":
            res.append("        //")
        elif kind == "section":
            res.append(f"        // -------------------------------------------------- {text}")
        else:
            res.append(f"        // {text}")
    res.append("    }")
    res.append("")
    return res


def render_props(lines, prefix="log.msg."):
    out = [
        "# 本地化文案（内部调试用）",
        "# 注意：key 由脚本生成，请勿手工编辑",
        "",
    ]
    i = 0
    for kind, text in lines:
        if kind == "gap":
            out.append("")
        elif kind == "section":
            out.append(f"# ---- {text} ----")
        else:
            out.append(f"{prefix}{i:04d}=   {text}")
            i += 1
    return "\n".join(out) + "\n"


def render_sql(lines):
    out = ["-- 数据核对记录", "-- 生成时间见 git 历史", ""]
    out.append("CREATE TABLE IF NOT EXISTS debug_note (")
    out.append("    id INTEGER PRIMARY KEY,")
    out.append("    tag TEXT NOT NULL,")
    out.append("    body TEXT")
    out.append(");")
    out.append("")
    i = 0
    for kind, text in lines:
        if kind == "gap":
            out.append("")
        elif kind == "section":
            out.append(f"-- ========== {text} ==========")
        else:
            esc = text.replace("'", "''")
            out.append(f"INSERT INTO debug_note (id, tag, body) VALUES ({i}, 'trace', '// {esc}');")
            i += 1
    return "\n".join(out) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("-o", "--output", required=True)
    ap.add_argument("--style", choices=["java", "props", "sql"], default="java")
    ap.add_argument("--width", type=int, default=40, help="每行显示宽度（中文按2计）")
    ap.add_argument("--class-name", default="WorkLog")
    ap.add_argument("--prefix", default="log.msg.")
    a = ap.parse_args()

    with open(a.input, "r", encoding="utf-8", errors="ignore") as f:
        raw = f.read()

    blocks = split_paragraphs(raw)
    lines = build_lines(blocks, a.width)

    if a.style == "java":
        content = render_java(lines, a.class_name)
    elif a.style == "props":
        content = render_props(lines, a.prefix)
    else:
        content = render_sql(lines)

    os.makedirs(os.path.dirname(os.path.abspath(a.output)), exist_ok=True)
    with open(a.output, "w", encoding="utf-8") as f:
        f.write(content)

    body = sum(1 for k, _ in lines if k == "body")
    sys.stderr.write(f"[ok] {a.style} -> {a.output} | 正文行 {body} | 段落 {len(blocks)}\n")


if __name__ == "__main__":
    main()
