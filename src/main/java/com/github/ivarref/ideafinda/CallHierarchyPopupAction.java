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
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
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
import com.intellij.ui.popup.list.ListPopupImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public class CallHierarchyPopupAction extends AnAction {

    record CallSite(String display, VirtualFile file, int offset, int depth) {}

    record SearchItem(PsiNamedElement element, int depth) {}

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
                    System.out.println("[CallHierarchy] starting BFS from: " + startName
                            + " (" + startElement.getClass().getSimpleName() + ")");

                    // BFS over the call graph
                    Set<String> visited = new HashSet<>();
                    Queue<SearchItem> queue = new ArrayDeque<>();

                    String startKey = elementKey(startElement);
                    visited.add(startKey);
                    queue.add(new SearchItem(startElement, 0));

                    List<CallSite> allCallSites = new ArrayList<>();

                    while (!queue.isEmpty()) {
                        SearchItem item = queue.poll();
                        PsiNamedElement current = item.element();
                        int currentDepth = item.depth();

                        String currentName = ReadAction.compute(current::getName);
                        System.out.println("[CallHierarchy] searching callers of: " + currentName
                                + " (depth=" + currentDepth + ")");

                        for (PsiReference ref : ReferencesSearch.search(current, GlobalSearchScope.projectScope(project)).findAll()) {
                            PsiNamedElement callerElement = ReadAction.compute(() ->
                                    PsiTreeUtil.getParentOfType(ref.getElement(), PsiNamedElement.class));

                            CallSite site = ReadAction.compute(() -> {
                                PsiElement el = ref.getElement();
                                String label = callerLabel(callerElement, el);
                                var doc = PsiDocumentManager.getInstance(project).getDocument(el.getContainingFile());
                                int line = doc != null ? doc.getLineNumber(el.getTextOffset()) + 1 : -1;
                                String display = line > 0 ? label + ":" + line : label;
                                return new CallSite(display, el.getContainingFile().getVirtualFile(),
                                        el.getTextOffset(), currentDepth + 1);
                            });

                            System.out.println("[CallHierarchy] found call site: " + site.display()
                                    + " (depth=" + site.depth() + ")");
                            allCallSites.add(site);

                            if (callerElement != null) {
                                String callerKey = elementKey(callerElement);
                                if (!visited.contains(callerKey)) {
                                    visited.add(callerKey);
                                    queue.add(new SearchItem(callerElement, currentDepth + 1));
                                }
                            }
                        }
                    }

                    System.out.println("[CallHierarchy] total call sites found: " + allCallSites.size());
                    if (allCallSites.isEmpty()) {
                        System.out.println("[CallHierarchy] early return: no call sites found");
                        return;
                    }

                    // Nearest first; indent each extra level with one space
                    allCallSites.sort(Comparator.comparingInt(CallSite::depth));
                    allCallSites.replaceAll(s -> new CallSite(
                            " ".repeat(s.depth() - 0) + s.display(),
                            s.file(), s.offset(), s.depth()));

                    ApplicationManager.getApplication().invokeLater(() -> {
                        System.out.println("[CallHierarchy] showing popup with " + allCallSites.size() + " items");
                        ListPopup popup = JBPopupFactory.getInstance().createListPopup(
                                new BaseListPopupStep<>("Callers of " + startName, allCallSites) {
                                    @Override
                                    public String getTextFor(CallSite value) {
                                        return value.display();
                                    }

                                    @Override
                                    public @Nullable PopupStep<?> onChosen(CallSite selected, boolean finalChoice) {
                                        if (finalChoice) {
                                            return doFinalStep(() ->
                                                    new OpenFileDescriptor(project, selected.file(), selected.offset())
                                                            .navigate(true));
                                        }
                                        return FINAL_CHOICE;
                                    }
                                }
                        );
                        applyEditorFont(popup);
                        popup.showCenteredInCurrentWindow(project);
                    });
                } catch (Exception ex) {
                    System.out.println("[CallHierarchy] exception in background task: " + ex);
                    ex.printStackTrace(System.out);
                }
            }
        }.queue();
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

    /** Returns the nearest named class, walking up through anonymous/local classes if needed. */
    private static String namedClassName(@Nullable PsiClass cls) {
        while (cls != null && cls.getName() == null) {
            cls = PsiTreeUtil.getParentOfType(cls, PsiClass.class);
        }
        return cls != null ? cls.getName() : "";
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

    private static void top() {
        middle();
    }
    private static void middle() {
        bottom();
    }
    private static void bottom() {
        System.err.println("janei");
    }
}
