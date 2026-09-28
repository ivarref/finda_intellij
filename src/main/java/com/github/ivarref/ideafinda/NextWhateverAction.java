package com.github.ivarref.ideafinda;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import static com.github.ivarref.ideafinda.DebugLogger.info;

public class NextWhateverAction extends AnAction {

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
        info("");
        if (NextAction.ERROR == currentAction) {
            executeActionId("GotoNextError", e);
        } else if (NextAction.CHANGE == currentAction) {
            info("Moving to next diff location");
            NextDiffLocation.runAction(e);
        } else {
            Messages.showInfoMessage("::" + currentAction, "Next action is ...");
        }
    }

    public final @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
        // dum di dam
        // kebbelife
    }

    public static void run(@NotNull AnActionEvent e, NextAction action) {
        currentAction = action;
        executeActionId("com.github.ivarref.ideafinda.NextWhateverAction", e);
    }
}
