package com.github.ivarref.ideafinda;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class FocusNextFormAction extends AnAction implements DumbAware {

    @Override
    public final void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null && e.getData(CommonDataKeys.EDITOR) != null);
    }

    @Override
    public final void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project != null && editor != null) {
            FocusForm.moveToAdjacentForm(project, editor, true);
        }
    }

    public final @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
