package com.tomato.reader.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.tomato.reader.ReaderService;
import org.jetbrains.annotations.NotNull;

/**
 * 自动阅读开关：一次触发之后编辑器自己往下滚，本节读完自动接下一节，不用再点任何按钮。
 */
public class AutoAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        ReaderService.getInstance(project).toggleAuto();
    }
}
