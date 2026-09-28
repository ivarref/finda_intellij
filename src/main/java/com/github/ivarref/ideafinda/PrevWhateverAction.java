package com.github.ivarref.ideafinda;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public class PrevWhateverAction extends AnAction {

    private static final Logger logger = InitSLF4J.getLogger(PrevWhateverAction.class);

    @Override
    public final void update(@NotNull AnActionEvent event) {
        final Project project = event.getProject();
        event.getPresentation().setEnabledAndVisible(null != project);
    }

    public final void actionPerformed(@NotNull AnActionEvent e) {
        if (NextWhateverAction.NextAction.CHANGE == NextWhateverAction.currentAction) {
            logger.info("");
            logger.info("Running: com.github.ivarref.ideafinda.PrevWhateverAction");
            PrevDiffLocation.runAction(e);
        } else {
            Messages.showInfoMessage("::" + NextWhateverAction.currentAction,
                    "Previous action is ...");
        }
    }

    public final @NotNull ActionUpdateThread getActionUpdateThread() {
        return super.getActionUpdateThread();
    }

    public static void run(@NotNull AnActionEvent e, NextWhateverAction.NextAction action) {
        NextWhateverAction.currentAction = action;
        NextWhateverAction.executeActionId("com.github.ivarref.ideafinda.PrevWhateverAction", e);
    }
}
