package com.github.ivarref.ideafinda;

import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.ui.popup.list.ListPopupImpl;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

public class WindowChooseAction extends AnAction {

    @Override
    public final void update(@NotNull AnActionEvent event) {
        final Project project = event.getProject();
        event.getPresentation().setEnabledAndVisible(null != project);
    }

    @Override
    public final void actionPerformed(@NotNull AnActionEvent e) {
        String id = "com.github.ivarref.ChooseWindowAction";
        ActionGroup action = (ActionGroup) e.getActionManager().getAction(id);
        ListPopup actionGroupPopup = JBPopupFactory.getInstance().createActionGroupPopup(
                "Tab / Window ...",
                action,
                e.getDataContext(),
                JBPopupFactory.ActionSelectionAid.MNEMONICS,
                false);
        applyEditorFont(actionGroupPopup);
        actionGroupPopup.showCenteredInCurrentWindow(e.getProject());
    }

    @SuppressWarnings("unchecked")
    private static void applyEditorFont(ListPopup popup) {
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        Font editorFont = new Font(scheme.getEditorFontName(), Font.PLAIN, scheme.getEditorFontSize());
        JList<Object> list = (JList<Object>) ((ListPopupImpl) popup).getList();
        list.setFont(editorFont);
        ListCellRenderer<Object> original = list.getCellRenderer();
        list.setCellRenderer((lst, value, index, isSelected, cellHasFocus) -> {
            Component c = original.getListCellRendererComponent(lst, value, index, isSelected, cellHasFocus);
            applyFontRecursively(c, editorFont);
            return c;
        });
        popup.addListener(new JBPopupListener() {
            @Override
            public void beforeShown(@NotNull LightweightWindowEvent event) {
                Container parent = popup.getContent().getParent();
                if (parent != null) {
                    applyFontRecursively(parent, editorFont);
                }
            }
        });
    }

    private static void applyFontRecursively(Component c, Font font) {
        c.setFont(font);
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyFontRecursively(child, font);
            }
        }
    }

    public final @NotNull ActionUpdateThread getActionUpdateThread() {
        return super.getActionUpdateThread();
    }

}
