package com.github.ivarref.ideafinda;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import org.jetbrains.annotations.NotNull;

public class Utils {
    public static void executeActionId(@NotNull String actionId, @NotNull AnActionEvent event) {
        AnAction action = event.getActionManager().getAction(actionId);
        ActionUtil.performActionDumbAwareWithCallbacks(action, event);
    }
}
