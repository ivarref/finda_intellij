package com.github.ivarref.ideafinda;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorKind;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Global on/off switch for {@link FocusForm}: when enabled, every main editor
 * (existing and newly opened, in all projects) is focused.
 */
@Service
public final class FocusFormService implements Disposable {
    private @Nullable Disposable listening;

    public static FocusFormService getInstance() {
        return ApplicationManager.getApplication().getService(FocusFormService.class);
    }

    public boolean isEnabled() {
        return listening != null;
    }

    public void setEnabled(boolean enabled) {
        if (enabled == isEnabled()) {
            return;
        }
        if (enabled) {
            listening = Disposer.newDisposable("FocusFormService.listening");
            Disposer.register(this, listening);
            EditorFactory.getInstance().addEditorFactoryListener(new EditorFactoryListener() {
                @Override
                public void editorCreated(@NotNull EditorFactoryEvent event) {
                    attach(event.getEditor());
                }
            }, listening);
        } else {
            Disposer.dispose(listening);
            listening = null;
        }
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            if (enabled) {
                attach(editor);
            } else {
                FocusForm.deactivate(editor);
            }
        }
    }

    private static void attach(@NotNull Editor editor) {
        // Skip diff viewers, consoles, previews etc.
        Project project = editor.getProject();
        if (project != null && editor.getEditorKind() == EditorKind.MAIN_EDITOR) {
            FocusForm.activate(project, editor);
        }
    }

    @Override
    public void dispose() {
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            FocusForm.deactivate(editor);
        }
    }
}
