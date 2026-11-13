package com.tomato.reader.source;

import java.util.List;

/**
 * 内容源。本地文件和第三方接口都实现这一层，
 * 上层的渲染、翻节、隐身逻辑完全不用关心内容从哪来。
 */
public interface BookSource {

    /** 界面上显示的来源描述 */
    String label();

    /** 节列表（本地=文件里的章节，在线=接口目录） */
    List<Entry> entries() throws Exception;

    /** 取某一节的正文（纯文本，段落之间用空行分隔） */
    String body(Entry entry) throws Exception;
}
