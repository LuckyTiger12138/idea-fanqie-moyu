package com.tomato.reader.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/** 打开右侧书库面板：搜索、下载、书架都在里面 */
public class OpenLibraryAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("WorkNotes");
        if (window != null) {
            window.show();
        }
    }
}
