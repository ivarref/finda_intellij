package com.github.ivarref.ideafinda;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.ScrollingModel;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.FoldingModelEx;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Narrows an editor to the top-level form at the caret (like Emacs narrow-to-defun)
 * by folding away everything above and below it. The focus follows the caret.
 */
public final class FocusForm implements Disposable {
    private static final Key<FocusForm> KEY = Key.create("com.github.ivarref.ideafinda.FocusForm");

    private final Project project;
    private final Editor editor;
    private @Nullable FoldRegion before;
    private @Nullable FoldRegion after;
    private boolean refreshScheduled;
    private boolean disposed;

    private FocusForm(@NotNull Project project, @NotNull Editor editor) {
        this.project = project;
        this.editor = editor;
    }

    public static boolean isActive(@NotNull Editor editor) {
        return editor.getUserData(KEY) != null;
    }

    public static void setActive(@NotNull Project project, @NotNull Editor editor, boolean active) {
        FocusForm current = editor.getUserData(KEY);
        if (!active) {
            if (current != null) {
                Disposer.dispose(current);
            }
            return;
        }
        if (current != null) {
            return;
        }
        FocusForm focus = new FocusForm(project, editor);
        editor.putUserData(KEY, focus);
        EditorUtil.disposeWithEditor(editor, focus);
        editor.getCaretModel().addCaretListener(new CaretListener() {
            @Override
            public void caretPositionChanged(@NotNull CaretEvent event) {
                focus.scheduleRefresh();
            }
        }, focus);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent event) {
                focus.scheduleRefresh();
            }
        }, focus);
        focus.scheduleRefresh();
    }

    /**
     * Moves the caret to the start of the next/previous top-level form.
     * When focus is active, the caret listener then narrows to that form.
     */
    public static void moveToAdjacentForm(@NotNull Project project, @NotNull Editor editor, boolean forward) {
        PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
        PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (file == null) {
            return;
        }
        PsiElement target = adjacentForm(file, editor.getCaretModel().getOffset(), forward);
        if (target == null) {
            return;
        }
        editor.getCaretModel().moveToOffset(target.getTextRange().getStartOffset());
        if (!isActive(editor)) {
            editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
        }
    }

    private void scheduleRefresh() {
        if (refreshScheduled) {
            return;
        }
        refreshScheduled = true;
        // Folding can't be changed during a document change, and PSI must be committed
        // to find the form, so defer until both are true.
        PsiDocumentManager.getInstance(project).performLaterWhenAllCommitted(() -> {
            refreshScheduled = false;
            if (!disposed && !editor.isDisposed()) {
                refresh();
            }
        });
    }

    private void refresh() {
        PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
        if (file == null) {
            return;
        }
        int offset = editor.getCaretModel().getOffset();
        PsiElement form = topLevelFormAt(file, offset);
        if (form == null && offset > 0) {
            form = topLevelFormAt(file, offset - 1);
        }
        if (form == null) {
            if (before != null || after != null) {
                // Caret is between forms (e.g. in a trailing comment); keep the current focus.
                return;
            }
            form = adjacentForm(file, offset, true);
            if (form == null) {
                form = adjacentForm(file, offset, false);
            }
            if (form == null) {
                return;
            }
        }
        TextRange visible = visibleRange(form);
        if (matches(visible)) {
            return;
        }
        FoldingModelEx folding = (FoldingModelEx) editor.getFoldingModel();
        int length = editor.getDocument().getTextLength();
        folding.runBatchFoldingOperation(() -> {
            removeRegions(folding);
            before = collapsed(folding, 0, visible.getStartOffset());
            after = collapsed(folding, visible.getEndOffset(), length);
        });
        ScrollingModel scrolling = editor.getScrollingModel();
        scrolling.disableAnimation();
        scrolling.scrollVertically(0);
        scrolling.scrollToCaret(ScrollType.MAKE_VISIBLE);
        scrolling.enableAnimation();
    }

    /** Whole lines of the form, plus comment lines directly above it. */
    private @NotNull TextRange visibleRange(@NotNull PsiElement form) {
        int start = form.getTextRange().getStartOffset();
        PsiElement prev = form.getPrevSibling();
        while (prev != null) {
            if (prev instanceof PsiComment) {
                start = prev.getTextRange().getStartOffset();
            } else if (!(prev instanceof PsiWhiteSpace) || prev.getText().chars().filter(c -> c == '\n').count() > 1) {
                break;
            }
            prev = prev.getPrevSibling();
        }
        Document doc = editor.getDocument();
        int startLine = doc.getLineNumber(start);
        int endLine = doc.getLineNumber(form.getTextRange().getEndOffset());
        return new TextRange(doc.getLineStartOffset(startLine), doc.getLineEndOffset(endLine));
    }

    private boolean matches(@NotNull TextRange visible) {
        int length = editor.getDocument().getTextLength();
        boolean beforeOk = visible.getStartOffset() == 0
                ? before == null
                : before != null && before.isValid() && before.getStartOffset() == 0 && before.getEndOffset() == visible.getStartOffset();
        boolean afterOk = visible.getEndOffset() == length
                ? after == null
                : after != null && after.isValid() && after.getStartOffset() == visible.getEndOffset() && after.getEndOffset() == length;
        return beforeOk && afterOk;
    }

    private static @Nullable FoldRegion collapsed(@NotNull FoldingModelEx folding, int start, int end) {
        if (start >= end) {
            return null;
        }
        // Empty placeholder so nothing at all is shown; neverExpands keeps "Expand All" etc. from revealing it.
        FoldRegion region = folding.createFoldRegion(start, end, "", null, true);
        if (region != null) {
            region.setExpanded(false);
        }
        return region;
    }

    private void removeRegions(@NotNull FoldingModelEx folding) {
        if (before != null && before.isValid()) {
            folding.removeFoldRegion(before);
        }
        if (after != null && after.isValid()) {
            folding.removeFoldRegion(after);
        }
        before = null;
        after = null;
    }

    private static @Nullable PsiElement topLevelFormAt(@NotNull PsiFile file, int offset) {
        PsiElement element = file.findElementAt(offset);
        while (element != null && !(element instanceof PsiFile) && element.getParent() != file) {
            element = element.getParent();
        }
        return isForm(element) ? element : null;
    }

    private static @Nullable PsiElement adjacentForm(@NotNull PsiFile file, int offset, boolean forward) {
        PsiElement current = topLevelFormAt(file, offset);
        int from = current != null ? current.getTextRange().getStartOffset() : offset;
        PsiElement result = null;
        for (PsiElement child = file.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!isForm(child)) {
                continue;
            }
            int start = child.getTextRange().getStartOffset();
            if (forward && start > from) {
                return child;
            }
            if (!forward && start < from) {
                result = child;
            }
        }
        return result;
    }

    private static boolean isForm(@Nullable PsiElement element) {
        return element != null
                && !(element instanceof PsiFile)
                && !(element instanceof PsiWhiteSpace)
                && !(element instanceof PsiComment)
                && element.getTextLength() > 0;
    }

    @Override
    public void dispose() {
        disposed = true;
        editor.putUserData(KEY, null);
        if (!editor.isDisposed()) {
            FoldingModelEx folding = (FoldingModelEx) editor.getFoldingModel();
            folding.runBatchFoldingOperation(() -> removeRegions(folding));
            editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
        }
    }
}
