package com.tomato.reader;

/**
 * 一个"节"，可以是真实章节，也可以是按长度切出来的片段。
 */
public final class Chapter {

    public final String title;
    public final String body;

    public Chapter(String title, String body) {
        this.title = title;
        this.body = body == null ? "" : body;
    }

    public int charCount() {
        return body.length();
    }
}
