package com.github.ivarref.ideafinda;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

public class MoveToNextTabGroup extends AnAction {

    private static final Logger logger = InitSLF4J.getLogger(MoveToNextTabGroup.class);

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        logger.info("Running MoveToNextTabGroup");
        FileEditorManagerEx fem = FileEditorManagerEx.getInstanceEx(event.getProject());
        int splitCount = fem.getWindowSplitCount();
        logger.info("Splitcount is: {}", splitCount);
        if (splitCount == 1) {
            // Create a new split group and move tab
            logger.info("Executing MoveTabRight");
            Utils.executeActionId("MoveTabRight", event);
        } else if (splitCount == 2) {
            // Move to existing split
            logger.info("Executing MoveEditorToOppositeTabGroup");
            Utils.executeActionId("MoveEditorToOppositeTabGroup", event);
        } else {
            logger.info("Splitcount {} not implemented", splitCount);
        }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
}
