package com.tomato.reader.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.table.JBTable;
import com.tomato.reader.BookParser;
import com.tomato.reader.ReaderService;
import com.tomato.reader.ReaderSettings;
import com.tomato.reader.fanqie.Downloader;
import com.tomato.reader.fanqie.FanqieApi;
import com.tomato.reader.fanqie.FontMap;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 工具窗口：搜索 → 下载到本地 → 直接阅读。
 *
 * <p>数据来自番茄小说网页版的公开接口，搜索与目录是明文 JSON，
 * 正文经过字体混淆，由 {@link FontMap} 内置映射表还原。整个过程在 IDE 内完成，
 * 不需要用户先手动准备 txt。</p>
 */
public final class LibraryWindow implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        LibraryPanel panel = new LibraryPanel(project, toolWindow);
        Content content = contentFactory().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }

    /**
     * ContentFactory 在新旧平台上取法不同：2021.3 只有 {@code SERVICE.getInstance()}，
     * 2026.x 只有静态 {@code getInstance()}。两边编译期互不存在，只能用反射兼容。
     */
    private static ContentFactory contentFactory() {
        try {
            Object factory = ContentFactory.class.getMethod("getInstance").invoke(null);
            if (factory instanceof ContentFactory) {
                return (ContentFactory) factory;
            }
        } catch (Throwable ignored) {
            // 老平台没有 getInstance()，走下面的 SERVICE
        }
        try {
            Object service = ContentFactory.class.getField("SERVICE").get(null);
            Object factory = service.getClass().getMethod("getInstance").invoke(service);
            if (factory instanceof ContentFactory) {
                return (ContentFactory) factory;
            }
        } catch (Throwable t) {
            throw new IllegalStateException("当前 IDE 版本无法获取 ContentFactory", t);
        }
        throw new IllegalStateException("当前 IDE 版本无法获取 ContentFactory");
    }

    /** 面板本体 */
    public static final class LibraryPanel extends JPanel implements com.intellij.openapi.Disposable {

        /** 上次导入时所在目录，同一 IDE 会话内复用，避免每次都从默认目录找 */
        private static File lastDir;

        private final Project project;
        private final ToolWindow toolWindow;

        private final JBTextField keywordField = new JBTextField();
        private final DefaultTableModel resultModel;
        private final JBTable resultTable;
        private final DefaultTableModel shelfModel;
        private final JBTable shelfTable;
        private final JLabel statusLabel = new JLabel("就绪");
        private final JButton searchBtn = new JButton("搜索");
        private final JButton downloadBtn = new JButton("下载并阅读");
        private final JButton shelfOpenBtn = new JButton("打开选中");
        private final JButton shelfImportBtn = new JButton("导入 txt");
        private final JButton shelfDirBtn = new JButton("打开书库目录");
        private final JButton shelfRefreshBtn = new JButton("刷新");

        private final JButton prevBtn = new JButton("◀ 上一节");
        private final JButton nextBtn = new JButton("下一节 ▶");
        private final JButton jumpBtn = new JButton("跳转");
        private final JBTextField jumpField = new JBTextField();
        private final JLabel progressLabel = new JLabel("未打开");
        private final AtomicBoolean busy = new AtomicBoolean(false);

        private final List<FanqieApi.Book> results = new ArrayList<>();
        private final List<Path> shelfFiles = new ArrayList<>();
        /** 书架的代数，用于丢弃过期的章节统计结果 */
        private volatile int shelfGeneration = 0;

        LibraryPanel(Project project, ToolWindow toolWindow) {
            super(new BorderLayout());
            this.project = project;
            this.toolWindow = toolWindow;

            resultModel = new DefaultTableModel(new Object[]{"书名", "作者", "字数"}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            resultTable = new JBTable(resultModel);
            resultTable.setAutoResizeMode(JBTable.AUTO_RESIZE_LAST_COLUMN);
            resultTable.getColumnModel().getColumn(0).setPreferredWidth(220);

            shelfModel = new DefaultTableModel(new Object[]{"文件", "大小", "章节"}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            shelfTable = new JBTable(shelfModel);

            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("在线书库", buildSearchTab());
            tabs.addTab("本地书架", buildShelfTab());
            add(tabs, BorderLayout.CENTER);

            JPanel south = new JPanel();
            south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
            south.add(buildNavBar());
            statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));
            south.add(statusLabel);
            add(south, BorderLayout.SOUTH);

            keywordField.addActionListener(e -> doSearch());
            searchBtn.addActionListener(e -> doSearch());
            downloadBtn.addActionListener(e -> doDownload());
            shelfOpenBtn.addActionListener(e -> openShelfSelection());
            shelfImportBtn.addActionListener(e -> importBooks());
            shelfDirBtn.addActionListener(e -> openShelfDir());
            shelfRefreshBtn.addActionListener(e -> refreshShelf());
            prevBtn.addActionListener(e -> ReaderService.getInstance(project).previous());
            nextBtn.addActionListener(e -> ReaderService.getInstance(project).next());
            jumpBtn.addActionListener(e -> doJump());
            jumpField.addActionListener(e -> doJump());
            ReaderService.getInstance(project).setProgressListener(this::updateProgress);
            resultTable.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        doDownload();
                    }
                }
            });
            shelfTable.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        openShelfSelection();
                    }
                }
            });

            SwingUtilities.invokeLater(this::refreshShelf);
        }

        private JComponent buildSearchTab() {
            JPanel top = new JPanel(new BorderLayout(6, 0));
            top.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
            keywordField.getEmptyText().setText("输入书名或作者，回车搜索");
            top.add(keywordField, BorderLayout.CENTER);

            JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            btns.add(searchBtn);
            btns.add(downloadBtn);
            top.add(btns, BorderLayout.EAST);

            JPanel body = new JPanel(new BorderLayout());
            body.add(top, BorderLayout.NORTH);
            JScrollPane sp = new JBScrollPane(resultTable);
            sp.setPreferredSize(new Dimension(600, 300));
            body.add(sp, BorderLayout.CENTER);

            JLabel tip = new JLabel("内容来自番茄网页版公开接口，仅下载免费章节；正文自动还原被混淆的文字。");
            tip.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
            body.add(tip, BorderLayout.SOUTH);
            return body;
        }

        private JComponent buildShelfTab() {
            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
            top.add(shelfImportBtn);
            top.add(shelfOpenBtn);
            top.add(shelfDirBtn);
            top.add(shelfRefreshBtn);

            JPanel body = new JPanel(new BorderLayout());
            body.add(top, BorderLayout.NORTH);
            body.add(new JBScrollPane(shelfTable), BorderLayout.CENTER);

            JLabel tip = new JLabel("「导入 txt」把任意位置的 .txt/.md 复制进书库；也可以直接拖文件进书库目录后点「刷新」。");
            tip.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
            body.add(tip, BorderLayout.SOUTH);
            return body;
        }

        // ------------------------------------------------------------ 翻节

        /** 底部导航条：不依赖快捷键也能翻节、跳节，并显示当前进度 */
        private JComponent buildNavBar() {
            JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()));

            prevBtn.setToolTipText("上一节（快捷键 Ctrl+Alt+Shift+Z）");
            nextBtn.setToolTipText("下一节（快捷键 Ctrl+Alt+Shift+C）");
            jumpField.setPreferredSize(new Dimension(64, 26));
            jumpField.getEmptyText().setText("节号");
            jumpField.setToolTipText("填节号后回车直接跳过去");

            bar.add(prevBtn);
            bar.add(nextBtn);
            bar.add(jumpField);
            bar.add(jumpBtn);
            bar.add(progressLabel);
            return bar;
        }

        private void doJump() {
            String raw = jumpField.getText().trim();
            if (raw.isEmpty()) {
                setStatus("在输入框里填节号，例如 120，然后回车");
                return;
            }
            int n;
            try {
                n = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                setStatus("节号只能填数字");
                return;
            }
            ReaderService svc = ReaderService.getInstance(project);
            if (svc.sectionCount() <= 0) {
                setStatus("还没有打开书，先双击书架里的文件");
                return;
            }
            svc.jumpTo(n);
            jumpField.setText("");
            setStatus("已跳到第 " + svc.currentPosition() + " / " + svc.sectionCount() + " 节");
        }

        private void updateProgress() {
            ReaderService svc = ReaderService.getInstance(project);
            int total = svc.sectionCount();
            int pos = svc.currentPosition();
            if (total <= 0) {
                progressLabel.setText("未打开：双击书架里的书");
                prevBtn.setEnabled(false);
                nextBtn.setEnabled(false);
                jumpBtn.setEnabled(false);
                return;
            }
            String title = svc.currentTitle();
            if (title.length() > 16) {
                title = title.substring(0, 16) + "…";
            }
            progressLabel.setText("第 " + pos + " / " + total + " 节 · " + title);
            prevBtn.setEnabled(pos > 1);
            nextBtn.setEnabled(pos < total);
            jumpBtn.setEnabled(true);
        }

        // ------------------------------------------------------------ 导入
        /** 手动导入：从任意位置选 txt/md，复制进书库目录（原文件保留） */
        private void importBooks() {
            Path dir = ReaderSettings.get().dir();
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                setStatus("无法创建书库目录：" + dir);
                return;
            }

            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("选择要导入的书（可多选）");
            chooser.setMultiSelectionEnabled(true);
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setFileFilter(new FileNameExtensionFilter("文本文件 (*.txt, *.md)", "txt", "md"));

            File last = lastDir;
            if (last != null && last.isDirectory()) {
                chooser.setCurrentDirectory(last);
            }
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            File[] picked = chooser.getSelectedFiles();
            if (picked == null || picked.length == 0) {
                File one = chooser.getSelectedFile();
                if (one != null) {
                    picked = new File[]{one};
                }
            }
            if (picked == null || picked.length == 0) {
                return;
            }
            lastDir = picked[0].getParentFile();

            int copied = 0;
            int skipped = 0;
            boolean overwriteAll = false;
            Path firstCopied = null;

            for (File src : picked) {
                if (src == null || !src.isFile()) {
                    skipped++;
                    continue;
                }
                String name = src.getName();
                String lower = name.toLowerCase();
                if (!lower.endsWith(".txt") && !lower.endsWith(".md")) {
                    skipped++;
                    continue;
                }
                Path target = dir.resolve(name);
                try {
                    if (Files.exists(target)) {
                        if (Files.isSameFile(src.toPath(), target)) {
                            // 已经在书库里，直接算作已存在
                            if (firstCopied == null) {
                                firstCopied = target;
                            }
                            skipped++;
                            continue;
                        }
                        boolean overwrite;
                        if (overwriteAll) {
                            overwrite = true;
                        } else {
                            Object[] options = {"覆盖", "覆盖全部", "跳过"};
                            int ans = JOptionPane.showOptionDialog(this,
                                    "书库里已有同名文件：\n" + name + "\n\n怎么处理？",
                                    "Work Notes", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                                    null, options, options[0]);
                            if (ans == 1) {
                                overwriteAll = true;
                                overwrite = true;
                            } else {
                                overwrite = ans == 0;
                            }
                        }
                        if (!overwrite) {
                            skipped++;
                            continue;
                        }
                    }
                    Files.copy(src.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                    if (firstCopied == null) {
                        firstCopied = target;
                    }
                } catch (IOException e) {
                    skipped++;
                    setStatus("导入失败：" + name + " — " + e.getMessage());
                }
            }

            refreshShelf();
            String msg = "已导入 " + copied + " 个文件到书库"
                    + (skipped > 0 ? "，跳过 " + skipped + " 个" : "");
            setStatus(msg);

            // 只导入了一本就顺手打开，省一次点击
            if (copied == 1 && firstCopied != null) {
                ReaderService.getInstance(project).openFile(firstCopied);
            }
        }

        private void openShelfDir() {
            Path dir = ReaderSettings.get().dir();
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                setStatus("无法创建书库目录：" + dir);
                return;
            }
            try {
                Desktop.getDesktop().open(dir.toFile());
                setStatus("已打开书库目录：" + dir);
            } catch (Throwable t) {
                // 无桌面环境时退化为提示路径
                Messages.showInfoMessage(project, "书库目录：\n" + dir, "Work Notes");
            }
        }

        // ------------------------------------------------------------ 搜索

        private void doSearch() {
            String kw = keywordField.getText().trim();
            if (kw.isEmpty()) {
                setStatus("请输入关键词");
                return;
            }
            if (!busy.compareAndSet(false, true)) {
                return;
            }
            setStatus("搜索中…");
            searchBtn.setEnabled(false);
            downloadBtn.setEnabled(false);

            new Thread(() -> {
                String err = null;
                List<FanqieApi.Book> hits = new ArrayList<>();
                try {
                    hits = FanqieApi.search(kw, 0);
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                final List<FanqieApi.Book> got = hits;
                final String error = err;
                ApplicationManager.getApplication().invokeLater(() -> {
                    busy.set(false);
                    searchBtn.setEnabled(true);
                    downloadBtn.setEnabled(true);
                    if (error != null) {
                        setStatus("搜索失败：" + error);
                        return;
                    }
                    results.clear();
                    results.addAll(got);
                    resultModel.setRowCount(0);
                    for (FanqieApi.Book b : got) {
                        resultModel.addRow(new Object[]{b.title, b.author == null ? "" : b.author,
                                b.words == null ? "" : b.words});
                    }
                    setStatus(got.isEmpty() ? "没有结果，换个关键词试试" : "找到 " + got.size() + " 本，双击或点「下载并阅读」");
                });
            }, "work-notes-search").start();
        }

        // ------------------------------------------------------------ 下载

        private void doDownload() {
            int row = resultTable.getSelectedRow();
            if (row < 0 || row >= results.size()) {
                setStatus("先在上面的结果里选一本");
                return;
            }
            FanqieApi.Book book = results.get(row);
            Path dir = ReaderSettings.get().dir();
            Path target = dir.resolve(Downloader.safeName(book.title) + ".txt");
            if (Files.exists(target)) {
                int ans = Messages.showYesNoDialog(project,
                        "本地已有同名文件：\n" + target + "\n\n是否覆盖？", "Work Notes", null);
                if (ans != Messages.YES) {
                    return;
                }
            }

            ProgressManager.getInstance().run(new Task.Backgroundable(project, "下载《" + book.title + "》", true) {
                private Downloader.Result result;
                private String error;

                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    AtomicBoolean cancel = new AtomicBoolean(false);
                    try {
                        result = Downloader.run(book.id, book.title, target, (done, total, msg) -> {
                            indicator.setText(msg);
                            setStatus(msg);
                            if (indicator.isCanceled()) {
                                cancel.set(true);
                            }
                        }, cancel);
                    } catch (Throwable t) {
                        error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    }
                }

                @Override
                public void onSuccess() {
                    if (error != null) {
                        setStatus("下载失败：" + error);
                        Messages.showErrorDialog(project, "下载失败：" + error, "Work Notes");
                        return;
                    }
                    if (result == null) {
                        setStatus("没有结果");
                        return;
                    }
                    if (result.cancelled) {
                        setStatus("已取消");
                        return;
                    }
                    setStatus("已下载 " + result.ok + " 章"
                            + (result.failed > 0 ? "，失败 " + result.failed + " 章" : "")
                            + (result.skipped > 0 ? "，跳过付费 " + result.skipped + " 章" : ""));
                    refreshShelf();
                    ReaderService.getInstance(project).openFile(target);
                }
            });
        }

        // ------------------------------------------------------------ 书架

        private void refreshShelf() {
            Path dir = ReaderSettings.get().dir();
            shelfFiles.clear();
            shelfModel.setRowCount(0);
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                setStatus("无法创建书库目录：" + dir);
                return;
            }
            List<Path> files = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(Files::isRegularFile).filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.endsWith(".txt") || n.endsWith(".md");
                }).forEach(files::add);
            } catch (IOException e) {
                setStatus("读取书库失败：" + e.getMessage());
                return;
            }
            files.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (Path p : files) {
                long size = 0;
                try {
                    size = Files.size(p);
                } catch (IOException ignored) {
                    // 大小读不到不影响列出
                }
                shelfFiles.add(p);
                shelfModel.addRow(new Object[]{p.getFileName().toString(), human(size), "…"});
            }
            countChapters(new ArrayList<>(shelfFiles));
        }

        /** 后台统计每个文件的章节数，读完再回填，避免卡住界面 */
        private void countChapters(List<Path> files) {
            final int generation = ++shelfGeneration;
            new Thread(() -> {
                for (int i = 0; i < files.size(); i++) {
                    if (generation != shelfGeneration) {
                        return;
                    }
                    Path p = files.get(i);
                    String text;
                    try {
                        text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    } catch (Throwable t) {
                        continue;
                    }
                    int count = BookParser.parse(text).size();
                    final int row = i;
                    final String cell = count + " 节";
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (generation != shelfGeneration || row >= shelfModel.getRowCount()) {
                            return;
                        }
                        shelfModel.setValueAt(cell, row, 2);
                    });
                }
            }, "work-notes-shelf-count").start();
        }

        private void openShelfSelection() {
            int row = shelfTable.getSelectedRow();
            if (row < 0 || row >= shelfFiles.size()) {
                setStatus("先选择一个文件");
                return;
            }
            ReaderService.getInstance(project).openFile(shelfFiles.get(row));
        }

        private void setStatus(String text) {
            Runnable r = () -> statusLabel.setText(text == null ? "" : text);
            if (SwingUtilities.isEventDispatchThread()) {
                r.run();
            } else {
                ApplicationManager.getApplication().invokeLater(r);
            }
        }

        private static String human(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            }
            if (bytes < 1024 * 1024) {
                return (bytes / 1024) + " KB";
            }
            return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        }

        @Override
        public void dispose() {
            try {
                ReaderService.getInstance(project).setProgressListener(null);
            } catch (Throwable ignored) {
                // 项目已释放时忽略
            }
        }
    }
}
