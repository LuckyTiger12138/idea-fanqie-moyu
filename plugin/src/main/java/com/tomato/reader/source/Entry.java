package com.tomato.reader.source;

/** 一节的最小标识：本地文件的一节，或在线目录的一个 itemId。 */
public final class Entry {

    public final String id;
    public final String title;

    public Entry(String id, String title) {
        this.id = id;
        this.title = title == null ? "" : title;
    }
}
