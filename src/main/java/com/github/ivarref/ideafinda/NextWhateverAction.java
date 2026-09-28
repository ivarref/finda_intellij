package com.github.ivarref.ideafinda;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public class NextWhateverAction extends AnAction {

    private static final Logger logger = InitSLF4J.getLogger(PrevWhateverAction.class);

    public enum NextAction {
        CHANGE, ERROR
    }

    public static NextAction currentAction = NextAction.ERROR;

    public static void executeActionId(@NotNull String actionId, @NotNull AnActionEvent event) {
        AnAction action = event.getActionManager().getAction(actionId);
        ActionUtil.performActionDumbAwareWithCallbacks(action, event);
    }

    @Override
    public final void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        event.getPresentation().setEnabledAndVisible(null != project);
    }

    @Override
    public final void actionPerformed(@NotNull AnActionEvent e) {
        logger.info("");
        if (NextAction.ERROR == currentAction) {
            executeActionId("GotoNextError", e);
        } else if (NextAction.CHANGE == currentAction) {
            logger.info("Moving to next diff location");
            NextDiffLocation.runAction(e);
        } else {
            Messages.showInfoMessage("::" + currentAction, "Next action is ...");
        }
    }

    public final @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    public static void run(@NotNull AnActionEvent e, NextAction action) {
        currentAction = action;
        executeActionId("com.github.ivarref.ideafinda.NextWhateverAction", e);
    }
}
