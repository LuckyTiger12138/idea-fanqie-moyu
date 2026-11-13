package com.tomato.reader.source;

import com.tomato.reader.Chapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 本地 txt 源：章节在打开时一次性解析完成，取正文无 IO。 */
public final class LocalSource implements BookSource {

    private final Path file;
    private final List<Chapter> chapters;
    private final List<Entry> entries = new ArrayList<>();

    public LocalSource(Path file, List<Chapter> chapters) {
        this.file = file;
        this.chapters = chapters;
        for (int i = 0; i < chapters.size(); i++) {
            entries.add(new Entry(String.valueOf(i), chapters.get(i).title));
        }
    }

    @Override
    public String label() {
        return "本地：" + file.getFileName();
    }

    @Override
    public List<Entry> entries() {
        return entries;
    }

    @Override
    public String body(Entry entry) {
        try {
            int i = Integer.parseInt(entry.id);
            if (i >= 0 && i < chapters.size()) {
                return chapters.get(i).body;
            }
        } catch (NumberFormatException ignored) {
            // 非法 id，返回空正文
        }
        return "";
    }
}
