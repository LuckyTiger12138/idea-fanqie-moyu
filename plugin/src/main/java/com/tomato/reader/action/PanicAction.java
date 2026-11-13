package com.tomato.reader.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.tomato.reader.ReaderService;
import org.jetbrains.annotations.NotNull;

/**
 * 一键收起：立即关掉视图标签，回到打开前的文件。
 */
public class PanicAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        ReaderService.getInstance(project).panic();
    }
}
