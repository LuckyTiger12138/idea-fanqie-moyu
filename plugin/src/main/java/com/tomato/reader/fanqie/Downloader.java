package com.tomato.reader.fanqie;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 整本下载：目录 → 逐章抓正文 → 拼成 txt。
 *
 * <p>只下载目录里标记为免费的章节；付费/锁定章节直接跳过，不做任何绕过。</p>
 */
public final class Downloader {

    public interface Progress {
        /** 在任意线程回调，调用方需自行切回 EDT */
        void update(int done, int total, String message);
    }

    public static final class Result {
        public final Path file;
        public final int ok;
        public final int failed;
        public final int skipped;
        public final boolean cancelled;

        Result(Path file, int ok, int failed, int skipped, boolean cancelled) {
            this.file = file;
            this.ok = ok;
            this.failed = failed;
            this.skipped = skipped;
            this.cancelled = cancelled;
        }
    }

    private static final int THREADS = 3;

    private Downloader() {
    }

    public static Result run(String bookId, String bookTitle, Path target,
                             Progress cb, AtomicBoolean cancel) throws IOException, InterruptedException {
        return run(bookId, bookTitle, target, 0, cb, cancel);
    }

    /**
     * @param maxChapters 最多下载多少章，0 表示全部（试读时传个小值）
     */
    public static Result run(String bookId, String bookTitle, Path target, int maxChapters,
                             Progress cb, AtomicBoolean cancel) throws IOException, InterruptedException {
        List<FanqieApi.Chapter> all = FanqieApi.catalog(bookId);
        if (all.isEmpty()) {
            throw new IOException("目录为空，可能这本书已下架或 id 不对");
        }
        List<FanqieApi.Chapter> free = new ArrayList<>();
        for (FanqieApi.Chapter c : all) {
            if (c.free) {
                free.add(c);
            }
        }
        int skipped = all.size() - free.size();
        if (free.isEmpty()) {
            throw new IOException("这本没有免费章节，无法下载");
        }
        if (maxChapters > 0 && free.size() > maxChapters) {
            free = new ArrayList<>(free.subList(0, maxChapters));
            skipped = all.size() - free.size();
        }

        int total = free.size();
        String[] body = new String[total];
        boolean[] failedFlag = new boolean[total];
        AtomicInteger done = new AtomicInteger();
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        cb.update(0, total, "目录共 " + all.size() + " 章，可下载 " + total + " 章，开始抓取");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            for (int i = 0; i < total; i++) {
                final int index = i;
                final FanqieApi.Chapter ch = free.get(i);
                pool.submit(() -> {
                    if (cancel.get()) {
                        return;
                    }
                    try {
                        // 轻微限速，避免短时间高并发被限流
                        Thread.sleep(120);
                        FanqieApi.Page page = FanqieApi.chapter(ch.itemId);
                        String title = ch.title == null || ch.title.isEmpty() ? page.title : ch.title;
                        StringBuilder sb = new StringBuilder();
                        sb.append(title.trim()).append("\n\n");
                        String text = page.body;
                        if (text == null || text.trim().isEmpty()) {
                            failedFlag[index] = true;
                            failCount.incrementAndGet();
                        } else {
                            sb.append(text.trim()).append("\n\n");
                            body[index] = sb.toString();
                            okCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failedFlag[index] = true;
                        failCount.incrementAndGet();
                    } finally {
                        int d = done.incrementAndGet();
                        if (d % 5 == 0 || d == total) {
                            cb.update(d, total, "已抓取 " + d + "/" + total + " 章");
                        }
                    }
                });
            }
            pool.shutdown();
            while (!pool.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                if (cancel.get()) {
                    pool.shutdownNow();
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        if (cancel.get()) {
            return new Result(null, okCount.get(), failCount.get(), skipped, true);
        }

        StringBuilder out = new StringBuilder(1 << 20);
        for (int i = 0; i < total; i++) {
            if (body[i] != null) {
                out.append(body[i]);
            }
        }
        if (out.length() == 0) {
            throw new IOException("所有章节都没抓到内容，可能被限流，稍后重试");
        }

        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        Files.write(tmp, out.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);

        cb.update(total, total, "完成：" + okCount.get() + " 章");
        return new Result(target, okCount.get(), failCount.get(), skipped, false);
    }

    /** 把书名变成安全的文件名 */
    public static String safeName(String title) {
        if (title == null || title.trim().isEmpty()) {
            return "book";
        }
        String s = title.trim().replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        if (s.length() > 60) {
            s = s.substring(0, 60);
        }
        return s;
    }
}
