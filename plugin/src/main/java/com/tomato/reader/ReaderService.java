package com.tomato.reader;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollingModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import com.tomato.reader.api.ApiClient;
import com.tomato.reader.source.BookSource;
import com.tomato.reader.source.Entry;
import com.tomato.reader.source.LocalSource;
import com.tomato.reader.source.OnlineSource;
import com.tomato.reader.ui.SearchDialog;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 阅读会话：选源、取正文、渲染、隐身。
 * 本地源与在线源共用同一套渲染和翻节逻辑，差别只在 {@link BookSource} 的实现。
 */
public final class ReaderService implements Disposable {

    private static final String TITLE = "Work Notes";
    private static final String[] SKINS = {"COMMENT", "PROPS", "PLAIN"};
    private static final String LOADING = "（正在取这一节……）";

    private final Project project;

    private BookSource source;
    private List<Entry> entries = Collections.emptyList();
    private int index;
    private VirtualFile viewFile;
    private String previousFile;

    /** 正文内存缓存，LRU 12 节 */
    private final Map<String, String> bodyCache = new LinkedHashMap<String, String>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 12;
        }
    };

    /** 请求令牌，切节时作废旧请求的回包 */
    private int reqToken;

    /** 进度变化回调（面板据此刷新「第 N / M 节」），由工具窗口注册 */
    private volatile Runnable progressListener;

    /** 自动阅读开关变化回调，由工具窗口注册 */
    private volatile Runnable autoListener;

    /** 自动阅读：定时器、当前节正文是否已经就绪、最后一屏已停留的 tick 数 */
    private final Alarm autoAlarm;
    private volatile boolean autoRead;
    private volatile boolean bodyReady;
    private volatile boolean bodyFailed;
    /** 开自动时若目录还没加载完，就等第一节内容渲染出来再真正启动 */
    private volatile boolean autoPending;
    private int holdTicks;

    /**
     * 手动滚到末尾自动接下一节。
     * 只能挂在外层 JScrollPane 上（它自身的滚动处理器也在同一个组件上，两份监听都能收到）；
     * 绝对不能挂 editor.getContentComponent()：Swing 只把滚轮事件投给「最深的、注册了滚轮监听的组件」，
     * 内容组件一旦有监听，外层滚动面板就永远收不到事件，滚轮直接失效，只剩拖滚动条能滚。
     */
    private final MouseWheelListener wheelListener = this::onWheel;
    private final List<Component> wheelTargets = new ArrayList<>();
    private Component wheelTarget;
    private long lastChainAt;
    /** AWT 层再兜一道：只观察、不参与分发，不影响编辑区自己的滚动 */
    private final AWTEventListener awtWheel = this::onAwtWheel;
    private boolean awtWheelInstalled;

    public ReaderService(Project project) {
        this.project = project;
        this.autoAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
        installAwtWheel();
        // 切回阅读文件时补挂滚轮监听（首次打开可能拿不到编辑器，这里兜底）
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
                new FileEditorManagerListener() {
                    @Override
                    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                        installScrollHook();
                    }

                    @Override
                    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                        installScrollHook();
                    }
                });
    }

    @Override
    public void dispose() {
        autoRead = false;
        removeAwtWheel();
        try {
            autoAlarm.cancelAllRequests();
            autoAlarm.dispose();
        } catch (Throwable ignored) {
            // 平台已释放时忽略
        }
    }

    public static ReaderService getInstance(Project project) {
        return project.getService(ReaderService.class);
    }

    public void setProgressListener(@Nullable Runnable listener) {
        this.progressListener = listener;
        notifyProgress();
    }

    private void notifyProgress() {
        Runnable r = progressListener;
        if (r == null) {
            return;
        }
        if (ApplicationManager.getApplication().isDispatchThread()) {
            r.run();
        } else {
            ApplicationManager.getApplication().invokeLater(r);
        }
    }

    /** 当前是第几节（从 1 开始），未打开返回 0 */
    public int currentPosition() {
        return entries.isEmpty() ? 0 : index + 1;
    }

    public int sectionCount() {
        return entries.size();
    }

    public String currentTitle() {
        return entries.isEmpty() ? "" : entries.get(index).title;
    }

    /** 跳到指定节，参数从 1 开始；越界则夹到边界 */
    public void jumpTo(int oneBased) {
        if (entries.isEmpty()) {
            open();
            return;
        }
        int target = Math.max(1, Math.min(oneBased, entries.size())) - 1;
        if (target == index) {
            return;
        }
        index = target;
        show(false);
    }

    public String statusText() {
        if (entries.isEmpty()) {
            return "未打开";
        }
        String name = entries.get(index).title;
        return name + "  " + (index + 1) + "/" + entries.size();
    }

    // ------------------------------------------------------------------ 入口

    /** 打开或继续上次的位置 */
    public void open() {
        ReaderSettings s = ReaderSettings.get();
        if ("ONLINE".equals(s.mode)) {
            ApiClient api = apiClient();
            if (!api.isConfigured()) {
                Messages.showInfoMessage(project,
                        "当前是在线模式，但还没有填写 API 地址。\n\n"
                                + "打开 Settings > Tools > Work Notes，填入第三方接口地址后重试；\n"
                                + "或者把模式切回「本地文件」。", TITLE);
                return;
            }
            if (s.onlineBookId == null || s.onlineBookId.trim().isEmpty()) {
                searchOnline();
                return;
            }
            openOnline(s.onlineBookId.trim(), s.onlineBookTitle);
            return;
        }
        openLocal(s);
    }

    public void searchOnline() {
        ApiClient api = apiClient();
        if (!api.isConfigured()) {
            Messages.showInfoMessage(project,
                    "还没有填写 API 地址。\n\nSettings > Tools > Work Notes 里填入第三方接口地址后重试。", TITLE);
            return;
        }
        SearchDialog dialog = new SearchDialog(project, api);
        if (!dialog.showAndGet()) {
            return;
        }
        String id = dialog.selectedId();
        if (id != null && !id.isEmpty()) {
            openOnline(id, dialog.selectedName());
        }
    }

    // ------------------------------------------------------------------ 本地源

    private void openLocal(ReaderSettings s) {
        Path file = null;
        if (s.bookFile != null && !s.bookFile.trim().isEmpty()) {
            try {
                Path p = Paths.get(s.bookFile.trim());
                if (Files.isRegularFile(p)) {
                    file = p;
                }
            } catch (Exception ignored) {
                // 路径非法就当没有
            }
        }
        if (file == null) {
            file = askForFile(s);
        }
        if (file == null) {
            return;
        }
        openPath(file, s);
    }

    /** 直接打开指定文件，供下载完成后自动跳转使用 */
    public void openFile(Path file) {
        if (file == null) {
            return;
        }
        openPath(file, ReaderSettings.get());
    }

    private void openPath(Path file, ReaderSettings s) {
        String text;
        try {
            text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Messages.showErrorDialog(project, "读取文件失败：" + e.getMessage(), TITLE);
            return;
        }
        List<Chapter> chapters = BookParser.parse(text);
        if (chapters.isEmpty()) {
            Messages.showInfoMessage(project, "这个文件里没有可读内容。", TITLE);
            return;
        }
        String abs = file.toAbsolutePath().toString();
        boolean sameBook = abs.equals(s.bookFile);
        LocalSource local = new LocalSource(file, chapters);
        source = local;
        entries = local.entries();
        index = sameBook ? Math.max(0, Math.min(s.chapterIndex, entries.size() - 1)) : 0;
        s.bookFile = abs;
        s.mode = "LOCAL";
        s.chapterIndex = index;
        bodyCache.clear();
        show(true);
    }

    /** 强制重新选本地文件 */
    public void chooseBook() {
        Path file = askForFile(ReaderSettings.get());
        if (file == null) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            List<Chapter> chapters = BookParser.parse(text);
            if (chapters.isEmpty()) {
                Messages.showInfoMessage(project, "这个文件里没有可读内容。", TITLE);
                return;
            }
            LocalSource local = new LocalSource(file, chapters);
            source = local;
            entries = local.entries();
            index = 0;
            ReaderSettings s = ReaderSettings.get();
            s.bookFile = file.toAbsolutePath().toString();
            s.mode = "LOCAL";
            bodyCache.clear();
            show(true);
        } catch (IOException e) {
            Messages.showErrorDialog(project, "读取文件失败：" + e.getMessage(), TITLE);
        }
    }

    @Nullable
    private Path askForFile(ReaderSettings s) {
        Path dir = s.dir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            Messages.showErrorDialog(project, "无法创建目录：" + dir, TITLE);
            return null;
        }
        List<Path> books = listBooks(dir);
        if (books.isEmpty()) {
            int answer = Messages.showYesNoDialog(project,
                    "目录是空的。\n\n把 .txt 文件放进这个目录后再试：\n" + dir
                            + "\n\n是否现在打开这个目录？", TITLE, null);
            if (answer == Messages.YES) {
                try {
                    java.awt.Desktop.getDesktop().open(dir.toFile());
                } catch (Exception ignored) {
                    // 桌面 API 不可用时静默忽略
                }
            }
            return null;
        }
        if (books.size() == 1) {
            return books.get(0);
        }
        String[] names = new String[books.size()];
        for (int i = 0; i < books.size(); i++) {
            names[i] = books.get(i).getFileName().toString();
        }
        int picked = Messages.showChooseDialog(project, "选择要打开的文件", TITLE, null, names, names[0]);
        if (picked < 0 || picked >= books.size()) {
            return null;
        }
        return books.get(picked);
    }

    private List<Path> listBooks(Path dir) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".txt") || n.endsWith(".md");
            }).sorted().forEach(out::add);
        } catch (IOException ignored) {
            // 目录不可读，返回空
        }
        return out;
    }

    // ------------------------------------------------------------------ 在线源

    private void openOnline(String bookId, String title) {
        final ApiClient api = apiClient();
        if (!api.isConfigured()) {
            return;
        }
        final OnlineSource src = new OnlineSource(api, bookId, title);
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                final List<Entry> list = src.entries();
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) {
                        return;
                    }
                    ReaderSettings s = ReaderSettings.get();
                    boolean sameBook = bookId.equals(s.onlineBookId);
                    source = src;
                    entries = list;
                    index = sameBook ? Math.max(0, Math.min(s.chapterIndex, entries.size() - 1)) : 0;
                    s.onlineBookId = bookId;
                    s.onlineBookTitle = title == null ? "" : title;
                    s.mode = "ONLINE";
                    bodyCache.clear();
                    show(true);
                });
            } catch (final Exception ex) {
                ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(
                        project, "打开失败：" + ex.getMessage(), TITLE));
            }
        });
    }

    private ApiClient apiClient() {
        ReaderSettings s = ReaderSettings.get();
        return new ApiClient(s.baseUrl, ApiClient.parseHeaders(s.extraHeaders, null, null), 15000);
    }

    // ------------------------------------------------------------------ 渲染

    private void show(boolean first) {
        if (entries.isEmpty() || source == null) {
            return;
        }
        final Entry entry = entries.get(index);
        String cached = bodyCache.get(entry.id);
        if (cached != null) {
            renderBody(entry, cached, first);
            return;
        }
        // 本地源取正文是纯内存操作，直接同步拿
        if (source instanceof LocalSource) {
            try {
                String body = source.body(entry);
                bodyCache.put(entry.id, body);
                renderBody(entry, body, first);
            } catch (Exception e) {
                renderBody(entry, "（读取失败：" + e.getMessage() + "）", first);
            }
            return;
        }
        // 在线源：先渲染占位，再后台取
        renderBody(entry, LOADING, first);
        final int my = ++reqToken;
        final int reqIndex = index;
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            String body = null;
            String error = null;
            try {
                body = source.body(entry);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String finalBody = body;
            final String finalError = error;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed() || my != reqToken || reqIndex != index) {
                    return;
                }
                if (finalBody != null) {
                    bodyCache.put(entry.id, finalBody);
                    renderBody(entry, finalBody, false);
                    prefetchNext();
                } else {
                    renderBody(entry, "（取正文失败：" + finalError + "）", false);
                }
            });
        });
    }

    /** 后台预取下一节，让翻页没有等待 */
    private void prefetchNext() {
        if (source instanceof LocalSource || index + 1 >= entries.size()) {
            return;
        }
        final Entry next = entries.get(index + 1);
        if (bodyCache.containsKey(next.id)) {
            return;
        }
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                final String body = source.body(next);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed() && body != null && !body.trim().isEmpty()) {
                        bodyCache.put(next.id, body);
                    }
                });
            } catch (Exception ignored) {
                // 预取失败不影响当前阅读
            }
        });
    }

    private void renderBody(Entry entry, String body, boolean first) {
        ReaderSettings s = ReaderSettings.get();
        // 自动阅读靠这个判断「这一节的正文是不是真的到了」
        if (LOADING.equals(body)) {
            bodyReady = false;
            bodyFailed = false;
        } else if (body != null && (body.startsWith("（取正文失败") || body.startsWith("（读取失败"))) {
            bodyReady = true;
            bodyFailed = true;
        } else {
            bodyReady = true;
            bodyFailed = false;
        }
        holdTicks = 0;
        if (autoPending) {
            autoPending = false;
            if (!autoRead) {
                autoRead = true;
                notifyAuto();
                scheduleNext();
            }
        }
        String content = BookParser.render(new Chapter(entry.title, body), s.skin, s.width, index, entries.size());

        Path dir = s.viewDir();
        final String path = dir.resolve(BookParser.fileNameFor(s.skin)).toString().replace('\\', '/');
        File target = new File(path);

        FileEditorManager fem = FileEditorManager.getInstance(project);
        if (first) {
            VirtualFile[] selected = fem.getSelectedFiles();
            if (selected.length > 0 && selected[0].isValid() && !selected[0].getPath().equals(path)) {
                previousFile = selected[0].getPath();
            }
        }

        // 视图文件一旦被 IDE 打开，就只能通过 Document 改内容，绝不能直接写磁盘：
        // 先 Files.write 再 doc.setText 会让 IDE 认为「磁盘被外部改动、内存里还有未保存内容」，
        // 于是每翻一节都弹一次「文件缓存冲突」。
        VirtualFile vf;
        if (viewFile != null && viewFile.isValid() && path.equals(viewFile.getPath())) {
            vf = viewFile;
        } else {
            vf = LocalFileSystem.getInstance().findFileByIoFile(target);
        }

        Document doc = vf == null ? null : FileDocumentManager.getInstance().getDocument(vf);
        if (doc != null) {
            final Document d = doc;
            final String text = content;
            WriteCommandAction.runWriteCommandAction(project, () -> {
                d.setText(text);
                FileDocumentManager.getInstance().saveDocument(d);
            });
        } else {
            // 还没被加载：直接落盘不会产生冲突，写完再登记到 VFS
            try {
                Files.createDirectories(dir);
                Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Messages.showErrorDialog(project, "写入视图文件失败：" + e.getMessage(), TITLE);
                return;
            }
            vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target);
            if (vf == null) {
                Messages.showErrorDialog(project, "无法创建视图文件：" + target, TITLE);
                return;
            }
        }
        viewFile = vf;

        // 自动阅读时不抢焦点：换节只是文件内容变了，光标留在别处也不会被打断
        fem.openTextEditor(new OpenFileDescriptor(project, vf, 0), !autoRead);
        s.chapterIndex = index;
        installScrollHook();
        // 每节都从顶部开始：不抢焦点地打开编辑器时，IDE 可能保留上一次的滚动位置，
        // 那会让新一节一打开就贴在最底部（滚轮往下自然没反应）
        ApplicationManager.getApplication().invokeLater(() -> {
            installScrollHook();
            scrollViewToTop();
            // 布局还没稳定时第一次滚不动，再补一次
            ApplicationManager.getApplication().invokeLater(this::scrollViewToTop);
        });
        notifyProgress();
    }

    // ------------------------------------------------------------------ 翻节

    public void next() {
        move(1);
    }

    public void previous() {
        move(-1);
    }

    private void move(int delta) {
        if (entries.isEmpty()) {
            open();
            return;
        }
        int target = index + delta;
        if (target < 0) {
            Messages.showInfoMessage(project, "已经是第一节了。", TITLE);
            return;
        }
        if (target >= entries.size()) {
            Messages.showInfoMessage(project, "已经是最后一节了。", TITLE);
            return;
        }
        index = target;
        show(false);
    }

    // ------------------------------------------------------------------ 自动阅读

    /** 自动阅读是否在跑 */
    public boolean isAutoReading() {
        return autoRead;
    }

    public void setAutoListener(@Nullable Runnable listener) {
        this.autoListener = listener;
        notifyAuto();
    }

    private void notifyAuto() {
        Runnable r = autoListener;
        if (r == null) {
            return;
        }
        if (ApplicationManager.getApplication().isDispatchThread()) {
            r.run();
        } else {
            ApplicationManager.getApplication().invokeLater(r);
        }
    }

    /** 一个键开关：不用点面板按钮 */
    public void toggleAuto() {
        if (autoRead) {
            stopAuto();
            hint("WorkNotes · auto-scroll off");
        } else {
            startAuto();
        }
    }

    /**
     * 开始自动阅读：每隔一小段时间把编辑器往下滚几行，滚到这一节末尾自动接下一节，
     * 全程不需要任何点击。
     */
    public void startAuto() {
        if (entries.isEmpty()) {
            open();
        }
        if (entries.isEmpty()) {
            // 目录还在后台取，等第一节渲染出来再自动开始
            autoPending = true;
            hint("WorkNotes · waiting for content");
            return;
        }
        if (autoRead) {
            return;
        }
        autoRead = true;
        holdTicks = 0;
        hint(autoHint());
        notifyAuto();
        scheduleNext();
    }

    public void stopAuto() {
        boolean was = autoRead;
        autoRead = false;
        autoPending = false;
        holdTicks = 0;
        try {
            autoAlarm.cancelAllRequests();
        } catch (Throwable ignored) {
            // 已释放
        }
        if (was) {
            notifyAuto();
        }
    }

    private String autoHint() {
        ReaderSettings s = ReaderSettings.get();
        int ms = Math.max(100, s.autoIntervalMs);
        return "WorkNotes · auto-scroll " + String.format("%.1f", ms / 1000.0) + "s/line";
    }

    private void scheduleNext() {
        int base = Math.max(100, ReaderSettings.get().autoIntervalMs);
        // 轻微抖动，节奏不机械
        int delay = base + (int) (Math.random() * (base * 0.15));
        try {
            autoAlarm.addRequest(this::tick, delay);
        } catch (Throwable t) {
            stopAuto();
        }
    }

    private void tick() {
        if (!autoRead || project.isDisposed()) {
            return;
        }
        try {
            autoStep();
        } catch (Throwable ignored) {
            // 单次滚动失败不终止整个流程
        }
        if (autoRead) {
            scheduleNext();
        }
    }

    /** 一次滚动：读满一屏就往下挪，挪到底自动换下一节 */
    private void autoStep() {
        if (viewFile == null || !viewFile.isValid()) {
            stopAuto();
            return;
        }
        if (bodyFailed) {
            stopAuto();
            hint("WorkNotes · auto-scroll off (fetch failed)");
            return;
        }
        if (!bodyReady) {
            // 正文还在路上，等它回来再动
            return;
        }

        FileEditorManager fem = FileEditorManager.getInstance(project);
        Editor editor = fem.getSelectedTextEditor();
        if (editor == null) {
            return;
        }
        // 前台不是阅读文件时一律不动：绝不滚动别人的代码
        VirtualFile shown = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (shown == null || !shown.getPath().equals(viewFile.getPath())) {
            return;
        }

        ReaderSettings s = ReaderSettings.get();
        ScrollingModel sm = editor.getScrollingModel();
        Rectangle vis = sm.getVisibleArea();
        int lineH = Math.max(1, editor.getLineHeight());
        int step = Math.max(1, s.autoLines) * lineH;
        int contentHeight = editor.getContentComponent().getHeight();
        int bottom = Math.max(0, contentHeight - (vis == null ? 0 : vis.height));
        int cur = sm.getVerticalScrollOffset();

        if (atEnd(editor)) {
            // 到末尾了：先把最后一屏读完，再换下一节
            int lines = editor.getDocument().getLineCount();
            int visible = vis == null ? 20 : Math.max(1, vis.height / lineH);
            int hold = Math.max(2, Math.min(visible, lines));
            if (++holdTicks < hold) {
                return;
            }
            holdTicks = 0;
            if (!advanceChapter()) {
                stopAuto();
                hint("WorkNotes · end of book");
            }
        } else {
            holdTicks = 0;
            sm.scrollVertically(Math.min(bottom, cur + step));
        }
    }

    /** 自动推进到下一节；已经是最后一节返回 false。全程不弹窗 */
    private boolean stepNextQuiet() {
        if (entries.isEmpty() || index + 1 >= entries.size()) {
            return false;
        }
        index++;
        holdTicks = 0;
        show(false);
        return true;
    }

    /** 换到下一节并回到顶部；已经是最后一节返回 false，不弹窗 */
    private boolean advanceChapter() {
        if (!stepNextQuiet()) {
            return false;
        }
        ApplicationManager.getApplication().invokeLater(this::scrollViewToTop);
        return true;
    }

    // ------------------------------------------------------------ 滚到底自动接下一节

    /**
     * 把滚轮监听挂到阅读视图外层的滚动面板上。
     * 滚动面板自身的滚动处理器也挂在这个组件上，加一份监听不会抢事件；
     * 而挂到内容组件上会让滚轮彻底失效——这就是 0.3.5/0.3.6「滚不动」的真正原因。
     */
    private void installScrollHook() {
        if (!ReaderSettings.get().chainOnScroll) {
            return;
        }
        Editor editor = selectedReaderEditor();
        if (editor == null) {
            return;
        }
        Component content = editor.getContentComponent();
        if (content == null) {
            return;
        }
        // 顺手清掉旧版本残留在内容组件上的监听（就是它把滚轮事件吃掉了）
        content.removeMouseWheelListener(wheelListener);
        JScrollPane pane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, content);
        if (pane == null) {
            return;
        }
        if (pane == wheelTarget) {
            return;
        }
        for (Component old : wheelTargets) {
            if (old != null) {
                old.removeMouseWheelListener(wheelListener);
            }
        }
        wheelTargets.clear();
        pane.addMouseWheelListener(wheelListener);
        wheelTargets.add(pane);
        wheelTarget = pane;
    }

    /** 全局滚轮监听在服务创建时就装好，避免编辑器换过一次才生效 */
    private void installAwtWheel() {
        if (awtWheelInstalled) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().addAWTEventListener(awtWheel, MouseWheelEvent.MOUSE_WHEEL);
            awtWheelInstalled = true;
        } catch (Throwable ignored) {
            // 无头环境或权限受限时跳过
        }
    }

    private void removeAwtWheel() {
        if (!awtWheelInstalled) {
            return;
        }
        awtWheelInstalled = false;
        try {
            Toolkit.getDefaultToolkit().removeAWTEventListener(awtWheel);
        } catch (Throwable ignored) {
            // 已卸载
        }
    }

    /**
     * 最后一行是否已经滚进可视区。
     * 用「最后一行文字的底部」而不是「内容总高度」来判断——IDEA 允许滚过最后一行，
     * 按总高度算会永远差一屏，这正是之前滚到底没反应的原因之一。
     */
    private boolean atEnd(Editor editor) {
        Rectangle vis = editor.getScrollingModel().getVisibleArea();
        if (vis == null || vis.height <= 0) {
            return false;
        }
        int len = editor.getDocument().getTextLength();
        int lastY = editor.visualPositionToXY(editor.offsetToVisualPosition(len)).y;
        int lineH = Math.max(1, editor.getLineHeight());
        return vis.y + vis.height >= lastY + lineH / 2;
    }

    /**
     * 滚动面板上的滚轮事件：滚到底再往下滚 → 接下一节。
     * 只在「最后一行已经在屏幕上、还继续往下滚」时触发，读最后一屏不会被打断；
     * 触摸板/平滑滚动会给精确值而 getWheelRotation() 为 0，所以两个都看。
     */
    private void onWheel(MouseWheelEvent e) {
        chainIfAtEnd(e);
    }

    /** AWT 层兜底：纯观察，不影响事件分发与编辑区滚动 */
    private void onAwtWheel(AWTEvent event) {
        if (!(event instanceof MouseWheelEvent)) {
            return;
        }
        MouseWheelEvent e = (MouseWheelEvent) event;
        Editor editor = selectedReaderEditor();
        if (editor == null) {
            return;
        }
        Component src = e.getComponent();
        Component content = editor.getContentComponent();
        if (src == null || content == null) {
            return;
        }
        // 只认落在阅读编辑区（或它外层滚动条/滚动面板）上的滚轮
        if (src != content && !SwingUtilities.isDescendingFrom(src, content)
                && !SwingUtilities.isDescendingFrom(content, src)) {
            return;
        }
        chainIfAtEnd(e);
    }

    /** 当前选中的编辑器是否就是阅读视图 */
    @Nullable
    private Editor selectedReaderEditor() {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null || viewFile == null) {
            return null;
        }
        VirtualFile shown = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (shown == null || !shown.getPath().equals(viewFile.getPath())) {
            return null;
        }
        return editor;
    }

    private void chainIfAtEnd(MouseWheelEvent e) {
        if (e == null || !ReaderSettings.get().chainOnScroll) {
            return;
        }
        if (e.getPreciseWheelRotation() <= 0 && e.getWheelRotation() <= 0) {
            return;
        }
        if (entries.isEmpty() || !bodyReady) {
            return;
        }
        Editor editor = selectedReaderEditor();
        if (editor == null || !atEnd(editor)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastChainAt < 500) {
            return;
        }
        lastChainAt = now;
        // 不在事件分发过程中改文件，延到下一轮 EDT
        ApplicationManager.getApplication().invokeLater(() -> {
            if (advanceChapter()) {
                hint("WorkNotes · " + (index + 1) + "/" + entries.size());
            }
        });
    }

    /** 换节后回到顶部，接着往下读 */
    private void scrollViewToTop() {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null || viewFile == null) {
            return;
        }
        VirtualFile shown = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (shown != null && shown.getPath().equals(viewFile.getPath())) {
            editor.getScrollingModel().scrollVertically(0);
        }
    }

    /** 状态栏一行轻提示，不弹窗 */
    private void hint(String text) {
        try {
            StatusBar bar = WindowManager.getInstance().getStatusBar(project);
            if (bar == null) {
                return;
            }
            try {
                StatusBar.class.getMethod("setInfo", String.class).invoke(bar, text);
            } catch (Throwable ignored) {
                // 新平台若移除了 setInfo(String)，静默跳过
            }
        } catch (Throwable ignored) {
            // 无主窗口时忽略
        }
    }

    // ------------------------------------------------------------------ 隐身

    public void panic() {
        stopAuto();
        FileEditorManager fem = FileEditorManager.getInstance(project);
        if (viewFile != null && viewFile.isValid()) {
            fem.closeFile(viewFile);
        } else {
            Path dir = ReaderSettings.get().viewDir();
            for (String skin : SKINS) {
                VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(
                        dir.resolve(BookParser.fileNameFor(skin)).toString().replace('\\', '/'));
                if (vf != null) {
                    fem.closeFile(vf);
                }
            }
        }
        if (previousFile != null) {
            VirtualFile prev = LocalFileSystem.getInstance().findFileByPath(previousFile);
            if (prev != null && prev.isValid()) {
                fem.openFile(prev, true);
            }
        }
        Editor editor = fem.getSelectedTextEditor();
        if (editor != null) {
            editor.getContentComponent().requestFocusInWindow();
        }
    }

    // ------------------------------------------------------------------ 形态

    public void cycleSkin() {
        ReaderSettings s = ReaderSettings.get();
        int at = 0;
        for (int i = 0; i < SKINS.length; i++) {
            if (SKINS[i].equals(s.skin)) {
                at = i;
            }
        }
        s.skin = SKINS[(at + 1) % SKINS.length];
        if (viewFile != null && viewFile.isValid()) {
            FileEditorManager.getInstance(project).closeFile(viewFile);
            viewFile = null;
        }
        if (!entries.isEmpty()) {
            String cached = bodyCache.get(entries.get(index).id);
            renderBody(entries.get(index), cached == null ? LOADING : cached, false);
        }
        Messages.showInfoMessage(project, "当前形态：" + s.skin, TITLE);
    }
}
