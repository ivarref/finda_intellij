package com.github.ivarref.ideafinda;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

// this is the initial code executed by `gpc`
public class PrevChangeAction extends AnAction {


    @Override
    public final void update(@NotNull AnActionEvent event) {
        final Project project = event.getProject();
        event.getPresentation().setEnabledAndVisible(null != project);
    }

    @Override
    public final void actionPerformed(@NotNull AnActionEvent e) {
//        Messages.showInfoMessage("Next change", "next change");
        PrevWhateverAction.run(e, NextWhateverAction.NextAction.CHANGE);
    }

    public final @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
