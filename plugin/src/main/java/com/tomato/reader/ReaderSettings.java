package com.tomato.reader;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 全局设置与阅读位置。字段为 public 以便被 XML 序列化。
 */
@State(name = "WorkNotesSettings", storages = @Storage("work-notes.xml"))
public class ReaderSettings implements PersistentStateComponent<ReaderSettings> {

    /** 书库目录，空则用默认目录 */
    public String bookDir = "";

    /** 当前文件的绝对路径 */
    public String bookFile = "";

    /** 当前节序号 */
    public int chapterIndex = 0;

    /** 伪装形态：COMMENT / PROPS / PLAIN */
    public String skin = "COMMENT";

    /** 每行显示宽度，中文按 2 计 */
    public int width = 40;

    /** 内容来源：LOCAL（本地文件） / ONLINE（第三方代理接口） */
    public String mode = "LOCAL";

    /** 第三方代理服务地址，例如 https://host:port（地址由使用者自行提供） */
    public String baseUrl = "";

    /** 额外请求头，每行一个，形如 "X-Phone: 138xxxx" 或 "Authorization: Bearer xxx" */
    public String extraHeaders = "";

    /** 在线模式下当前书籍 id */
    public String onlineBookId = "";

    public String onlineBookTitle = "";

    /** 自动阅读：每次下滚的间隔毫秒（越小越快） */
    public int autoIntervalMs = 1200;

    /** 自动阅读：每次下滚几行 */
    public int autoLines = 1;

    public static ReaderSettings get() {
        return ApplicationManager.getApplication().getService(ReaderSettings.class);
    }

    public Path dir() {
        if (bookDir != null && !bookDir.trim().isEmpty()) {
            return Paths.get(bookDir.trim());
        }
        return Paths.get(PathManager.getSystemPath(), "work-notes", "books");
    }

    public Path viewDir() {
        return Paths.get(PathManager.getSystemPath(), "work-notes", "view");
    }

    @Nullable
    @Override
    public ReaderSettings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull ReaderSettings state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}
