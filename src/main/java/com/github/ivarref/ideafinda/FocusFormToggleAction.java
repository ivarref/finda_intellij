package com.github.ivarref.ideafinda;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

public class FocusFormToggleAction extends ToggleAction implements DumbAware {

    @Override
    public final boolean isSelected(@NotNull AnActionEvent e) {
        return FocusFormService.getInstance().isEnabled();
    }

    @Override
    public final void setSelected(@NotNull AnActionEvent e, boolean state) {
        FocusFormService.getInstance().setEnabled(state);
    }

    public final @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
