package com.tomato.reader.source;

import com.tomato.reader.api.ApiClient;
import com.tomato.reader.api.ApiException;
import com.tomato.reader.api.TomatoMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第三方代理接口源。端点与参数严格对齐参考项目的调用点：
 * <ul>
 *   <li>{@code GET /api/detail?book_id=}</li>
 *   <li>{@code GET /api/book?book_id=} → 目录</li>
 *   <li>{@code GET /api/content?tab=小说&item_id=} → 正文</li>
 * </ul>
 */
public final class OnlineSource implements BookSource {

    private final ApiClient api;
    private final String bookId;
    private String title;
    private List<Entry> entries;

    public OnlineSource(ApiClient api, String bookId, String knownTitle) {
        this.api = api;
        this.bookId = bookId;
        this.title = knownTitle == null ? "" : knownTitle;
    }

    public String bookId() {
        return bookId;
    }

    @Override
    public String label() {
        String name = title.isEmpty() ? bookId : title;
        return "在线：" + name;
    }

    @Override
    public List<Entry> entries() throws Exception {
        if (entries != null) {
            return entries;
        }
        if (title.isEmpty()) {
            resolveTitle();
        }
        Map<String, String> q = new LinkedHashMap<>();
        q.put("book_id", bookId);
        Object resp = api.getJson("/api/book", q);
        entries = TomatoMapper.catalog(resp);
        if (entries.isEmpty()) {
            throw new ApiException("目录为空。接口返回里既没有 chapterListWithVolume 也没有 allItemIds，"
                    + "可能是地址不对、未授权，或这本书确实没有目录。");
        }
        return entries;
    }

    private void resolveTitle() {
        try {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("book_id", bookId);
            Object resp = api.getJson("/api/detail", q);
            String name = TomatoMapper.bookName(TomatoMapper.detail(resp));
            if (!name.isEmpty()) {
                title = name;
            }
        } catch (Exception ignored) {
            // 详情失败不影响阅读，标题留空即可
        }
    }

    @Override
    public String body(Entry entry) throws Exception {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("tab", "小说");
        q.put("item_id", entry.id);
        Object resp = api.getJson("/api/content", q);
        TomatoMapper.Content content = TomatoMapper.content(resp);
        if (content.body.trim().isEmpty()) {
            throw new ApiException("这一节没有正文。可能是付费章节，或者接口未授权。");
        }
        return content.body;
    }
}
