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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
            logger.info("Tracker is null, moving to next file");
            gotoNextFile(e, project);
            return;
        } else if (!tracker.isValid()) {
            logger.info("Tracker is not valid for file: {}", e.getData(CommonDataKeys.VIRTUAL_FILE).getName());
            gotoNextFile(e, project);
            return;
        } else if (!tracker.isAvailableAt(editor)) {
            logger.info("Tracker is not available for file: {}", e.getData(CommonDataKeys.VIRTUAL_FILE).getName());
            return;
        } else {
            VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
            logger.info("Tracker is OK for file: {}", file.getName());
            int line = editor.getCaretModel().getLogicalPosition().line;
            Range next = tracker.getNextRange(line);
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
                logger.info("Tracker getNextRange is null for file: " + file.getName() + ", moving to next file");
                gotoNextFile(e, project);
            }
        }
    }

    private static void gotoNextFile(@NotNull AnActionEvent e, Project project) {
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
        int nextFileIdx = files.indexOf(virtualFile);
        if (isFirst) {
            nextFileIdx = files.size() - 1;
        } else {
            nextFileIdx -= 1;
        }

        VirtualFile nextFile = files.get(nextFileIdx);
        logger.info("Next file is: {}", nextFile.getName());
        Editor editor = getEditor(project, nextFile);
        LineStatusTracker<?> tracker =
                LineStatusTrackerManager.getInstance(project).getLineStatusTracker(editor.getDocument());

        if (null == tracker) { // It's a totally new file (no proper tracker)
            logger.info("Tracker is null for nextfile: {}", nextFile.getName());
            int line = editor.getCaretModel().getLogicalPosition().line;
            logger.info("Moving to line: {} for nextfile: {}", line, nextFile.getName());
            FileEditorManager fem = FileEditorManager.getInstance(project);
            OpenFileDescriptor descriptor = new OpenFileDescriptor(project, nextFile, line, 1);
            fem.openTextEditor(descriptor, true);
            return;
        }
        if (!tracker.isValid()) {
            logger.info("Tracker is invalid for nextfile");
            return;
        }
        if (!tracker.isAvailableAt(editor)) {
            logger.info("Tracker is not available for nextfile");
            return;
        }

        Range next2 = tracker.getNextRange(0);
        if (null != next2) {
            FileEditorManager fem = FileEditorManager.getInstance(project);
            int visualLine = next2.getLine1();
            int column = EditorActionUtil.findFirstNonSpaceColumnOnTheLine(editor, visualLine);
            if (-1 == column) {
                column = 0;
            }
            logger.info("Moving to line for nextfile: {} and column: {}", visualLine, column);
            OpenFileDescriptor descriptor = new OpenFileDescriptor(project, nextFile, visualLine, column);
            fem.openTextEditor(descriptor, true);
            return;
        } else {
            logger.info("Range next2 is null, doing nothing");
            return;
        }
    }
}
