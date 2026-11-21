package com.github.ivarref.ideafinda;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

public class FocusFormToggleAction extends ToggleAction implements DumbAware {

    @Override
    public final void update(@NotNull AnActionEvent e) {
        super.update(e);
        e.getPresentation().setEnabled(e.getProject() != null && e.getData(CommonDataKeys.EDITOR) != null);
    }

    @Override
    public final boolean isSelected(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        return editor != null && FocusForm.isActive(editor);
    }

    @Override
    public final void setSelected(@NotNull AnActionEvent e, boolean state) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project != null && editor != null) {
            FocusForm.setActive(project, editor, state);
        }
    }

    public final @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
