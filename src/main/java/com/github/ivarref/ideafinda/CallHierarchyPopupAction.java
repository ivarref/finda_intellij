package com.github.ivarref.ideafinda;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.components.JBList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CallHierarchyPopupAction extends AnAction {

    private static final int PAGE_SIZE = 28;

    // display = label text only (no indentation); indentation and '>' are computed in the renderer
    record CallSite(String display, VirtualFile file, int offset, int depth,
                    @Nullable PsiNamedElement callerElement, boolean expanded) {}

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        e.getPresentation().setEnabledAndVisible(
                editor != null && psiFile != null && e.getProject() != null
        );
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        System.out.println("[CallHierarchy] actionPerformed called");
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        Project project = e.getProject();
        if (editor == null || psiFile == null || project == null) {
            System.out.println("[CallHierarchy] early return: null editor/psiFile/project");
            return;
        }

        int offset = editor.getCaretModel().getOffset();

        new Task.Backgroundable(project, "Finding callers...") {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                System.out.println("[CallHierarchy] background task started");
                try {
                    PsiNamedElement startElement = ReadAction.compute(() -> {
                        PsiElement el = psiFile.findElementAt(offset);
                        System.out.println("[CallHierarchy] element at caret: " + el
                                + " class=" + (el != null ? el.getClass().getName() : "null"));
                        if (el == null) return null;
                        PsiElement current = el;
                        while (current != null) {
                            if (current instanceof PsiNamedElement named && named.getName() != null) {
                                return named;
                            }
                            current = current.getParent();
                        }
                        return null;
                    });

                    if (startElement == null) {
                        System.out.println("[CallHierarchy] early return: no enclosing PsiNamedElement at caret");
                        return;
                    }

                    String startName = ReadAction.compute(startElement::getName);
                    System.out.println("[CallHierarchy] starting from: " + startName);

                    Set<String> expanded = new HashSet<>();
                    expanded.add(elementKey(startElement));

                    List<CallSite> initialItems = new ArrayList<>();
                    for (PsiReference ref : ReferencesSearch.search(startElement, GlobalSearchScope.projectScope(project)).findAll()) {
                        PsiNamedElement callerEl = ReadAction.compute(() ->
                                PsiTreeUtil.getParentOfType(ref.getElement(), PsiNamedElement.class));

                        CallSite site = ReadAction.compute(() -> {
                            PsiElement el = ref.getElement();
                            String display = buildDisplay(project, callerEl, el);
                            return new CallSite(display, el.getContainingFile().getVirtualFile(),
                                    el.getTextOffset(), 1, callerEl, false);
                        });

                        System.out.println("[CallHierarchy] initial caller: " + site.display());
                        initialItems.add(site);
                    }

                    System.out.println("[CallHierarchy] found " + initialItems.size() + " initial callers");
                    if (initialItems.isEmpty()) return;

                    EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
                    Font editorFont = new Font(scheme.getEditorFontName(), Font.PLAIN, scheme.getEditorFontSize());

                    initialItems.sort(Comparator.comparing(CallSite::display));

                    ApplicationManager.getApplication().invokeLater(() ->
                            showPopup(project, "Callers of " + startName, initialItems, expanded, editorFont));

                } catch (Exception ex) {
                    System.out.println("[CallHierarchy] exception: " + ex);
                    ex.printStackTrace(System.out);
                }
            }
        }.queue();
    }

    private static void showPopup(Project project, String title, List<CallSite> items,
                                  Set<String> expanded, Font editorFont) {
        DefaultListModel<CallSite> model = new DefaultListModel<>();
        items.forEach(model::addElement);

        JBList<CallSite> jbList = new JBList<>(model);
        jbList.setFont(editorFont);
        jbList.setCellRenderer((lst, value, index, isSelected, hasFocus) -> {
            String indent = " ".repeat(2 * (value.depth() - 1));
            String indicator = value.expanded() ? "*" : value.callerElement() != null ? ">" : ".";
            JLabel label = new JLabel(indent + indicator + " " + value.display());
            label.setFont(editorFont);
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
            if (isSelected) {
                label.setBackground(lst.getSelectionBackground());
                label.setForeground(lst.getSelectionForeground());
            } else {
                label.setBackground(lst.getBackground());
                label.setForeground(lst.getForeground());
            }
            return label;
        });
        jbList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        jbList.setVisibleRowCount(Math.min(PAGE_SIZE, Math.max(1, model.size())));
        if (model.size() > 0) jbList.setSelectedIndex(0);

        JBPopup[] popupRef = new JBPopup[1];

        model.addListDataListener(new javax.swing.event.ListDataListener() {
            @Override public void intervalAdded(javax.swing.event.ListDataEvent e) { sync(); }
            @Override public void intervalRemoved(javax.swing.event.ListDataEvent e) { sync(); }
            @Override public void contentsChanged(javax.swing.event.ListDataEvent e) {}
            private void sync() {
                jbList.setVisibleRowCount(Math.min(PAGE_SIZE, Math.max(1, model.size())));
                jbList.revalidate();
                if (popupRef[0] != null && !popupRef[0].isDisposed()) {
                    popupRef[0].pack(false, true);
                }
            }
        });

        jbList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_L, KeyEvent.VK_RIGHT, KeyEvent.VK_LEFT -> {
                        expandSelected(project, jbList, model, expanded);
                        e.consume();
                    }
                    case KeyEvent.VK_H -> {
                        collapseSelected(jbList, model, expanded);
                        e.consume();
                    }
                    case KeyEvent.VK_J -> {
                        moveSelection(jbList, 1);
                        e.consume();
                    }
                    case KeyEvent.VK_K -> {
                        moveSelection(jbList, -1);
                        e.consume();
                    }
                    case KeyEvent.VK_D -> {
                        if (e.isControlDown()) {
                            moveSelection(jbList, PAGE_SIZE);
                            e.consume();
                        }
                    }
                    case KeyEvent.VK_U -> {
                        if (e.isControlDown()) {
                            moveSelection(jbList, -PAGE_SIZE);
                            e.consume();
                        }
                    }
                    case KeyEvent.VK_ENTER -> {
                        navigateToSelected(project, jbList, popupRef[0]);
                        e.consume();
                    }
                }
            }
        });

        jbList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    navigateToSelected(project, jbList, popupRef[0]);
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(jbList);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());

        JBPopup popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(scrollPane, jbList)
                .setTitle(title)
                .setFocusable(true)
                .setRequestFocus(true)
                .setMovable(true)
                .setResizable(true)
                .addListener(new JBPopupListener() {
                    @Override
                    public void beforeShown(@NotNull LightweightWindowEvent event) {
                        Container parent = scrollPane.getParent();
                        if (parent != null) applyFontRecursively(parent, editorFont);
                    }
                })
                .createPopup();

        popupRef[0] = popup;
        popup.showCenteredInCurrentWindow(project);
    }

    private static void expandSelected(Project project, JBList<CallSite> jbList,
                                       DefaultListModel<CallSite> model, Set<String> expanded) {
        int idx = jbList.getSelectedIndex();
        if (idx < 0) return;
        CallSite selected = model.getElementAt(idx);
        PsiNamedElement callerEl = selected.callerElement();
        if (callerEl == null || selected.expanded()) return;

        String callerKey;
        try {
            callerKey = elementKey(callerEl);
        } catch (Exception e) {
            System.out.println("[CallHierarchy] callerElement is invalid, cannot expand");
            return;
        }
        if (expanded.contains(callerKey)) return;
        expanded.add(callerKey);

        model.setElementAt(new CallSite(selected.display(), selected.file(), selected.offset(),
                selected.depth(), callerEl, true), idx);

        final int insertDepth = selected.depth() + 1;
        final int insertAfterIdx = idx;

        new Task.Backgroundable(project, "Expanding callers...") {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    List<CallSite> newSites = new ArrayList<>();
                    for (PsiReference ref : ReferencesSearch.search(callerEl, GlobalSearchScope.projectScope(project)).findAll()) {
                        PsiNamedElement newCallerEl = ReadAction.compute(() ->
                                PsiTreeUtil.getParentOfType(ref.getElement(), PsiNamedElement.class));

                        CallSite site = ReadAction.compute(() -> {
                            PsiElement el = ref.getElement();
                            String display = buildDisplay(project, newCallerEl, el);
                            return new CallSite(display, el.getContainingFile().getVirtualFile(),
                                    el.getTextOffset(), insertDepth, newCallerEl, false);
                        });

                        System.out.println("[CallHierarchy] expanded caller: " + site.display());
                        newSites.add(site);
                    }

                    if (!newSites.isEmpty()) {
                        newSites.sort(Comparator.comparing(CallSite::display));
                        ApplicationManager.getApplication().invokeLater(() -> {
                            for (int i = 0; i < newSites.size(); i++) {
                                model.insertElementAt(newSites.get(i), insertAfterIdx + 1 + i);
                            }
                            int firstChild = insertAfterIdx + 1;
                            jbList.setSelectedIndex(firstChild);
                            jbList.ensureIndexIsVisible(firstChild);
                        });
                    } else {
                        // No callers found: clear '>' so it won't be retried
                        ApplicationManager.getApplication().invokeLater(() -> {
                            expanded.remove(callerKey);
                            if (insertAfterIdx < model.size()) {
                                CallSite item = model.getElementAt(insertAfterIdx);
                                model.setElementAt(new CallSite(item.display(), item.file(), item.offset(),
                                        item.depth(), null, false), insertAfterIdx);
                            }
                        });
                    }
                } catch (Exception ex) {
                    System.out.println("[CallHierarchy] expand error: " + ex);
                    ex.printStackTrace(System.out);
                }
            }
        }.queue();
    }

    private static void collapseSelected(JBList<CallSite> jbList,
                                         DefaultListModel<CallSite> model, Set<String> expanded) {
        int idx = jbList.getSelectedIndex();
        if (idx < 0) return;
        CallSite selected = model.getElementAt(idx);
        if (!selected.expanded()) return;

        // Collect all descendants (consecutive items with depth > selected.depth)
        List<CallSite> toRemove = new ArrayList<>();
        int i = idx + 1;
        while (i < model.size() && model.getElementAt(i).depth() > selected.depth()) {
            toRemove.add(model.getElementAt(i));
            i++;
        }
        for (int j = toRemove.size() - 1; j >= 0; j--) {
            model.removeElementAt(idx + 1 + j);
        }

        // Remove from expanded so the item (and its sub-tree) can be re-expanded later
        removeFromExpanded(expanded, selected);
        for (CallSite site : toRemove) {
            if (site.expanded()) removeFromExpanded(expanded, site);
        }

        model.setElementAt(new CallSite(selected.display(), selected.file(), selected.offset(),
                selected.depth(), selected.callerElement(), false), idx);
    }

    private static void removeFromExpanded(Set<String> expanded, CallSite site) {
        if (site.callerElement() != null) {
            try {
                expanded.remove(elementKey(site.callerElement()));
            } catch (Exception ignored) {}
        }
    }

    private static void moveSelection(JBList<?> jbList, int delta) {
        int next = Math.max(0, Math.min(jbList.getModel().getSize() - 1,
                jbList.getSelectedIndex() + delta));
        jbList.setSelectedIndex(next);
        jbList.ensureIndexIsVisible(next);
    }

    private static void navigateToSelected(Project project, JBList<CallSite> jbList, JBPopup popup) {
        int idx = jbList.getSelectedIndex();
        if (idx < 0) return;
        CallSite selected = jbList.getModel().getElementAt(idx);
        if (popup != null) popup.cancel();
        new OpenFileDescriptor(project, selected.file(), selected.offset()).navigate(true);
    }

    private static String buildDisplay(Project project, @Nullable PsiNamedElement callerEl, PsiElement refEl) {
        String label = callerLabel(callerEl, refEl);
        var doc = PsiDocumentManager.getInstance(project).getDocument(refEl.getContainingFile());
        int line = doc != null ? doc.getLineNumber(refEl.getTextOffset()) + 1 : -1;
        return line > 0 ? label + ":" + line : label;
    }

    private static String elementKey(PsiNamedElement element) {
        return ReadAction.compute(() ->
                element.getContainingFile().getVirtualFile().getPath() + ":" + element.getTextOffset());
    }

    private static String callerLabel(@Nullable PsiNamedElement caller, PsiElement fallback) {
        if (caller instanceof PsiMethod method) {
            PsiClass cls = method.getContainingClass();
            String prefix = namedClassName(cls) + ".";
            return prefix + method.getName();
        } else if (caller != null && caller.getName() != null) {
            return caller.getName();
        } else {
            return fallback.getContainingFile().getName();
        }
    }

    private static String namedClassName(@Nullable PsiClass cls) {
        while (cls != null && cls.getName() == null) {
            cls = PsiTreeUtil.getParentOfType(cls, PsiClass.class);
        }
        return cls != null ? cls.getName() : "";
    }

    private static void applyFontRecursively(Component c, Font font) {
        c.setFont(font);
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyFontRecursively(child, font);
            }
        }
    }
}
