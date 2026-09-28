package com.github.ivarref.ideafinda;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actions.EditorActionUtil;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.LocalChangeList;
import com.intellij.openapi.vcs.ex.LineStatusTracker;
import com.intellij.openapi.vcs.ex.Range;
import com.intellij.openapi.vcs.impl.LineStatusTrackerManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;

import static com.github.ivarref.ideafinda.NextDiffLocation.getEditor;

public class PrevDiffLocation {

    private static final Logger logger = InitSLF4J.getLogger(PrevDiffLocation.class);

    public static int getNonEmptyLine(Project project, VirtualFile file, Editor editor, int startLine, int stopLine) {
        FileEditorManager fem = FileEditorManager.getInstance(project);
        int column = EditorActionUtil.findFirstNonSpaceColumnOnTheLine(editor, startLine);
        if (-1 == column) {
            logger.info("Line {} for file: {} is empty", startLine, file.getName());
            logger.info("Stop Line is: {}", stopLine);
            for (int newLine = startLine + 1; newLine <= stopLine; newLine++) {
                int column2 = EditorActionUtil.findFirstNonSpaceColumnOnTheLine(editor, newLine);
                if (-1 == column2) {
                    continue;
                } else {
                    logger.info("Returning line {} for file: {}", newLine, file.getName());
                    return newLine;
                }
            }
            return startLine;
        } else {
            return startLine;
        }
    }

    public static void runAction(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);

        LineStatusTracker<?> tracker =
                LineStatusTrackerManager.getInstance(project).getLineStatusTracker(editor.getDocument());
        if (null == tracker) { // the entire file is new
            logger.info("Tracker is null, moving to prev file");
            gotoPrevFile(e, project);
            return;
        } else if (!tracker.isValid()) {
            logger.info("Tracker is not valid for file: {}", e.getData(CommonDataKeys.VIRTUAL_FILE).getName());
            gotoPrevFile(e, project);
            return;
        } else if (!tracker.isAvailableAt(editor)) {
            logger.info("Tracker is not available for file: {}", e.getData(CommonDataKeys.VIRTUAL_FILE).getName());
            return;
        } else {
            VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
            logger.info("Tracker is OK for file: {}", file.getName());
            int line = editor.getCaretModel().getLogicalPosition().line;
            Range next = tracker.getPrevRange(line);
            if (next != null) {
                FileEditorManager fem = FileEditorManager.getInstance(project);
                int visualLine = getNonEmptyLine(project, file, editor, next.getLine1(), next.getLine2());
                int column = EditorActionUtil.findFirstNonSpaceColumnOnTheLine(editor, visualLine);
                if (-1 == column) {
                    column = 0;
                }
                logger.info("Moving to line: " + visualLine + " and column: " + column + " for file: " + file.getName());
                OpenFileDescriptor descriptor = new OpenFileDescriptor(project, file, visualLine, column);
                fem.openTextEditor(descriptor, true);
            } else {
                logger.info("Tracker getPrevRange is null for file: " + file.getName() + ", moving to prev file");
                gotoPrevFile(e, project);
            }
        }
    }

    private static void gotoPrevFile(@NotNull AnActionEvent e, Project project) {
        ChangeListManager changeListManager = ChangeListManager.getInstance(project);
        LocalChangeList defaultChangeList = changeListManager.getDefaultChangeList();
        List<Change> changes = new ArrayList<>(defaultChangeList.getChanges());
        if (changes.isEmpty()) {
            logger.info("No changes, doing nothing");
            return;
        }

        VirtualFile virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE);
        List<@Nullable VirtualFile> lst = changes.stream().map(Change::getVirtualFile).toList();
        List<VirtualFile> files = new ArrayList<>(lst);
        if (!files.contains(virtualFile)) {
            files.add(virtualFile);
        }
        Comparator<VirtualFile> comparing = Comparator.comparing(x -> x.getName().toLowerCase(Locale.ROOT));
        files.sort(comparing);
        boolean isFirst = files.getFirst().equals(virtualFile);
        int prevFileIdx = files.indexOf(virtualFile);
        if (isFirst) {
            prevFileIdx = files.size() - 1;
        } else {
            prevFileIdx -= 1;
        }

        VirtualFile prevFile = files.get(prevFileIdx);
        logger.info("Prev file is: {}", prevFile.getName());
        Editor editor = getEditor(project, prevFile);
        LineStatusTracker<?> tracker =
                LineStatusTrackerManager.getInstance(project).getLineStatusTracker(editor.getDocument());

        if (null == tracker) { // It's a totally new file (no proper tracker)
            logger.info("Tracker is null for prevfile: {}", prevFile.getName());
            int line = editor.getCaretModel().getLogicalPosition().line;
            logger.info("Moving to line: {} for prevfile: {}", line, prevFile.getName());
            FileEditorManager fem = FileEditorManager.getInstance(project);
            OpenFileDescriptor descriptor = new OpenFileDescriptor(project, prevFile, line, 1);
            fem.openTextEditor(descriptor, true);
            return;
        }
        if (!tracker.isValid()) {
            logger.info("Tracker is invalid for prevfile");
            return;
        }
        if (!tracker.isAvailableAt(editor)) {
            logger.info("Tracker is not available for prevfile");
            return;
        }

        // Go to the bottom entry of this file ...
        Optional<Integer> maxLine1 = tracker.getRanges().stream().map(Range::getLine1).max(Comparator.comparingInt(x -> x));
        if (maxLine1.isPresent()) {
            FileEditorManager fem = FileEditorManager.getInstance(project);
            int visualLine = maxLine1.get().intValue();
            int column = EditorActionUtil.findFirstNonSpaceColumnOnTheLine(editor, visualLine);
            if (-1 == column) {
                column = 0;
            }
            logger.info("Moving to line for prevfile: {} and column: {}", visualLine, column);
            OpenFileDescriptor descriptor = new OpenFileDescriptor(project, prevFile, visualLine, column);
            fem.openTextEditor(descriptor, true);
        } else {
            logger.info("Could not find maxLine1, doing nothing");
            return;
        }
    }
}
