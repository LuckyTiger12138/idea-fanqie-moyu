package com.tomato.reader.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.tomato.reader.ReaderService;
import org.jetbrains.annotations.NotNull;

/** 在线模式：搜索并打开一本书 */
public class SearchAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        ReaderService.getInstance(project).searchOnline();
    }
}
