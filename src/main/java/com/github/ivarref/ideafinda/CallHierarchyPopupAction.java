package com.github.ivarref.ideafinda;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiCallExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.ui.components.JBList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
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
        log("[CallHierarchy] actionPerformed called");
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        Project project = e.getProject();
        if (editor == null || psiFile == null || project == null) {
            log("[CallHierarchy] early return: null editor/psiFile/project");
            return;
        }

        int offset = editor.getCaretModel().getOffset();

        new Task.Backgroundable(project, "Finding callers...") {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                log("[CallHierarchy] background task started");
                try {
                    PsiNamedElement startElement = ReadAction.compute(() -> {
                        PsiElement el = psiFile.findElementAt(offset);
                        if (el == null) return null;
                        return findEnclosingFunction(el);
                    });

                    if (startElement == null) {
                        log("[CallHierarchy] early return: no enclosing PsiMethod at caret");
                        return;
                    }

                    String startName = ReadAction.compute(startElement::getName);
                    log("[CallHierarchy] starting from: " + startName);

                    Set<String> expanded = new HashSet<>();
                    expanded.add(elementKey(startElement));

                    CallSite rootSite = ReadAction.compute(() -> {
                        String display = buildDisplay(project, startElement, startElement);
                        return new CallSite(display, startElement.getContainingFile().getVirtualFile(),
                                startElement.getTextOffset(), 1, startElement, true);
                    });

                    List<CallSite> initialItems = findCallerSites(startElement, project, 2);

                    log("[CallHierarchy] found " + initialItems.size() + " initial callers");

                    if (initialItems.size() < 5) {
                        List<CallSite> withChildren = new ArrayList<>();
                        for (CallSite site : initialItems) {
                            if (site.callerElement() != null) {
                                expanded.add(elementKey(site.callerElement()));
                                withChildren.add(new CallSite(site.display(), site.file(), site.offset(),
                                        site.depth(), site.callerElement(), true));
                                withChildren.addAll(findCallerSites(site.callerElement(), project, site.depth() + 1));
                            } else {
                                withChildren.add(site);
                            }
                        }
                        initialItems = withChildren;
                    }

                    EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
                    Font editorFont = new Font(scheme.getEditorFontName(), Font.PLAIN, scheme.getEditorFontSize());

                    List<CallSite> allItems = new ArrayList<>();
                    allItems.add(rootSite);
                    allItems.addAll(initialItems);
                    final List<CallSite> finalItems = allItems;
                    ApplicationManager.getApplication().invokeLater(() ->
                            showPopup(project, "Callers of " + startName, finalItems, expanded, editorFont));

                } catch (Exception ex) {
                    log("[CallHierarchy] exception: " + ex);
                    log(java.util.Arrays.stream(ex.getStackTrace()).map(StackTraceElement::toString).collect(java.util.stream.Collectors.joining("\n  ", "  ", "")));
                }
            }
        }.queue();
    }

    private static void showPopup(Project project, String title, List<CallSite> items,
                                  Set<String> expanded, Font editorFont) {
        DefaultListModel<CallSite> model = new DefaultListModel<>();
        items.forEach(model::addElement);

        EditorColorsScheme listScheme = EditorColorsManager.getInstance().getGlobalScheme();
        Color caretRowBg = listScheme.getColor(EditorColors.CARET_ROW_COLOR);

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
                label.setBackground(caretRowBg != null ? caretRowBg : lst.getSelectionBackground());
                label.setForeground(lst.getForeground());
            } else {
                label.setBackground(lst.getBackground());
                label.setForeground(lst.getForeground());
            }
            return label;
        });
        jbList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        jbList.setVisibleRowCount(Math.min(PAGE_SIZE, Math.max(1, model.size())));
        int initialIndex = model.size() > 1 ? 1 : 0;
        if (model.size() > 0) jbList.setSelectedIndex(initialIndex);

        JTextPane previewPane = new JTextPane();
        previewPane.setEditable(false);
        previewPane.setFont(editorFont);
        previewPane.setBackground(jbList.getBackground());
        previewPane.setForeground(jbList.getForeground());
        JScrollPane previewScroll = new JScrollPane(previewPane);
        previewScroll.setBorder(BorderFactory.createEmptyBorder());
        int minPreviewH = (editorFont.getSize() + 5) * 40;
        FontMetrics previewFm = Toolkit.getDefaultToolkit().getFontMetrics(editorFont);
        int previewWidth = previewFm.charWidth('m') * (100 + 6) + 16; // 100 code + 6 line-num + margin
        previewScroll.setPreferredSize(new Dimension(previewWidth, minPreviewH));
        previewScroll.setMinimumSize(new Dimension(200, minPreviewH));

        JLabel headerLabel = new JLabel(" ");
        headerLabel.setFont(editorFont);
        headerLabel.setOpaque(true);
        headerLabel.setBackground(jbList.getBackground());
        headerLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 4, 6));

        JPanel previewPanel = new JPanel(new BorderLayout());
        previewPanel.add(headerLabel, BorderLayout.NORTH);
        previewPanel.add(previewScroll, BorderLayout.CENTER);

        jbList.addListSelectionListener(ev -> {
            if (ev.getValueIsAdjusting()) return;
            int idx = jbList.getSelectedIndex();
            updatePreview(previewPane, headerLabel, idx >= 0 ? model.getElementAt(idx) : null, editorFont, project);
        });
        if (model.size() > 0) {
            updatePreview(previewPane, headerLabel, model.getElementAt(initialIndex), editorFont, project);
        }

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
                DebugLogger.info("received keypress :-)");
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_L -> {
                        expandOrEnter(project, jbList, model, expanded);
                        e.consume();
                    }
                    case KeyEvent.VK_RIGHT, KeyEvent.VK_KP_RIGHT -> {
                        expandOrEnter(project, jbList, model, expanded);
                        e.consume();
                    }
                    case KeyEvent.VK_H -> {
                        collapseOrGoToParent(jbList, model, expanded);
                        e.consume();
                    }
                    case KeyEvent.VK_LEFT, KeyEvent.VK_KP_LEFT -> {
                        collapseOrGoToParent(jbList, model, expanded);
                        e.consume();
                    }
                    case KeyEvent.VK_J -> {
                        moveSelection(jbList, 1);
                        e.consume();
                    }
                    case KeyEvent.VK_DOWN -> {
                        moveSelection(jbList, 1);
                        e.consume();
                    }
                    case KeyEvent.VK_K -> {
                        moveSelection(jbList, -1);
                        e.consume();
                    }
                    case KeyEvent.VK_UP -> {
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

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scrollPane, previewPanel);
        splitPane.setDividerSize(4);
        splitPane.setBorder(BorderFactory.createEmptyBorder());
        splitPane.setResizeWeight(0.0);

        JBPopup popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(splitPane, jbList)
                .setTitle(title)
                .setFocusable(true)
                .setRequestFocus(true)
                .setMovable(true)
                .setResizable(true)
                .addListener(new JBPopupListener() {
                    @Override
                    public void beforeShown(@NotNull LightweightWindowEvent event) {
                        Container parent = splitPane.getParent();
                        if (parent != null) applyFontRecursively(parent, editorFont);
                    }
                })
                .createPopup();

        popupRef[0] = popup;

        // Use a KeyEventDispatcher to intercept left/right arrows before the JScrollPane
        // or JBList consume them for scrolling/navigation.
        java.awt.KeyEventDispatcher arrowDispatcher = e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED) return false;
            Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            // Only act when focus is inside our popup's scroll pane
            if (focused == null) return false;
            Component c = focused;
            while (c != null) {
                if (c == scrollPane) break;
                c = c.getParent();
            }
            if (c == null) return false;
            int code = e.getKeyCode();
            if (code == KeyEvent.VK_RIGHT || code == KeyEvent.VK_KP_RIGHT) {
                expandOrEnter(project, jbList, model, expanded);
                return true;
            } else if (code == KeyEvent.VK_LEFT || code == KeyEvent.VK_KP_LEFT) {
                collapseOrGoToParent(jbList, model, expanded);
                return true;
            }
            return false;
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(arrowDispatcher);

        popup.addListener(new JBPopupListener() {
            @Override
            public void onClosed(@NotNull LightweightWindowEvent event) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(arrowDispatcher);
            }
        });

        popup.showCenteredInCurrentWindow(project);
    }

    private static void expandOrEnter(Project project, JBList<CallSite> jbList,
                                       DefaultListModel<CallSite> model, Set<String> expanded) {
        int idx = jbList.getSelectedIndex();
        if (idx < 0) return;
        CallSite selected = model.getElementAt(idx);
        if (selected.expanded()) {
            // Already expanded: jump to first child
            int childIdx = idx + 1;
            if (childIdx < model.size() && model.getElementAt(childIdx).depth() > selected.depth()) {
                jbList.setSelectedIndex(childIdx);
                jbList.ensureIndexIsVisible(childIdx);
            }
        } else {
            expandSelected(project, jbList, model, expanded);
        }
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
            log("[CallHierarchy] callerElement is invalid, cannot expand");
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
                    List<CallSite> newSites = findCallerSites(callerEl, project, insertDepth);

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
                    log("[CallHierarchy] expand error: " + ex);
                    log(java.util.Arrays.stream(ex.getStackTrace()).map(StackTraceElement::toString).collect(java.util.stream.Collectors.joining("\n  ", "  ", "")));
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

    private static void collapseOrGoToParent(JBList<CallSite> jbList,
                                              DefaultListModel<CallSite> model, Set<String> expanded) {
        int idx = jbList.getSelectedIndex();
        if (idx < 0) return;
        CallSite selected = model.getElementAt(idx);
        if (selected.expanded()) {
            collapseSelected(jbList, model, expanded);
        } else {
            // Navigate to nearest ancestor (first item above with lower depth)
            int parentDepth = selected.depth() - 1;
            if (parentDepth < 1) return;
            for (int i = idx - 1; i >= 0; i--) {
                if (model.getElementAt(i).depth() == parentDepth) {
                    jbList.setSelectedIndex(i);
                    jbList.ensureIndexIsVisible(i);
                    return;
                }
            }
        }
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

    private static void updatePreview(JTextPane previewPane, JLabel headerLabel,
                                      @Nullable CallSite site, Font editorFont, Project project) {
        if (site == null) { headerLabel.setText(" "); previewPane.setText(""); return; }
        com.intellij.openapi.editor.Document doc = ReadAction.compute(() ->
                FileDocumentManager.getInstance().getDocument(site.file()));
        if (doc == null) { previewPane.setText("(no preview)"); return; }
        int targetLine = ReadAction.compute(() -> doc.getLineNumber(site.offset()));
        int startLine = Math.max(0, targetLine - 20);
        int endLine = Math.min(doc.getLineCount() - 1, targetLine + 20);
        // Ensure at least 40 lines shown by extending whichever side has room
        if (endLine - startLine < 39) {
            if (startLine == 0) endLine = Math.min(doc.getLineCount() - 1, 39);
            else startLine = Math.max(0, endLine - 39);
        }
        final int fStartLine = startLine, fEndLine = endLine;
        String snippet = ReadAction.compute(() ->
                doc.getText(new TextRange(doc.getLineStartOffset(fStartLine), doc.getLineEndOffset(fEndLine))));

        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        SyntaxHighlighter sh = SyntaxHighlighterFactory.getSyntaxHighlighter(
                site.file().getFileType(), project, site.file());
        List<int[]> tokens = tokenizeSnippet(snippet, sh, scheme);

        javax.swing.text.StyledDocument styledDoc = previewPane.getStyledDocument();
        try { styledDoc.remove(0, styledDoc.getLength()); } catch (javax.swing.text.BadLocationException ignored) {}

        Color defaultFg = scheme.getDefaultForeground();
        Color defaultBg = scheme.getDefaultBackground();
        Color rawCaretBg = scheme.getColor(EditorColors.CARET_ROW_COLOR);
        Color caretBg = rawCaretBg != null ? rawCaretBg : defaultBg;
        Color lineNumFg = blend(defaultFg, defaultBg, 0.55f);

        // Build header: yellow filename, relative path, syntax-highlighted function signature, then '...'
        String funcDef = ReadAction.compute(() -> {
            com.intellij.psi.PsiFile pf = PsiManager.getInstance(project).findFile(site.file());
            if (pf == null) return "";
            PsiElement el = pf.findElementAt(site.offset());
            PsiNamedElement func = findEnclosingFunction(el);
            if (func == null) return "";
            // Java: use PSI range from function start up to (not including) the body opener
            if (func instanceof PsiMethod method && method.getBody() != null) {
                return doc.getText(new TextRange(func.getTextOffset(),
                        method.getBody().getTextOffset())).stripTrailing();
            }
            // Fallback: read lines until one ends with ':' or '{' (ignoring comments), cap at 8
            int funcLine = doc.getLineNumber(func.getTextOffset());
            StringBuilder sb = new StringBuilder();
            for (int i = funcLine; i < doc.getLineCount(); i++) {
                String line = doc.getText(new TextRange(doc.getLineStartOffset(i), doc.getLineEndOffset(i)));
                if (i > funcLine) sb.append("\n");
                sb.append(line);
                // Strip inline comments before checking the terminator character
                String check = line;
                int hash = check.indexOf('#');
                if (hash >= 0) check = check.substring(0, hash);
                int slash = check.indexOf("//");
                if (slash >= 0) check = check.substring(0, slash);
                check = check.stripTrailing();
                if (check.endsWith("{") || check.endsWith(":")) break;
            }
            return sb.toString().stripTrailing();
        });
        String basePath = project.getBasePath();
        String filePath = site.file().getPath();
        String relativePath = (basePath != null && filePath.startsWith(basePath))
                ? filePath.substring(basePath.length()).replaceFirst("^/", "")
                : filePath;
        // Normalize indentation: strip common leading whitespace, then
        // prepend 2 spaces to the def line and 4 spaces to parameter lines.
        // Stripping only the common prefix preserves relative alignment between lines.
        String[] defLines = funcDef.split("\n", -1);
        int minIndent = Integer.MAX_VALUE;
        for (String line : defLines) {
            if (!line.isBlank()) {
                int spaces = 0;
                while (spaces < line.length() && line.charAt(spaces) == ' ') spaces++;
                minIndent = Math.min(minIndent, spaces);
            }
        }
        if (minIndent == Integer.MAX_VALUE) minIndent = 0;
        final int strip = minIndent;
        StringBuilder normalizedDef = new StringBuilder();
        for (int i = 0; i < defLines.length; i++) {
            if (i > 0) normalizedDef.append("\n");
            String line = defLines[i].length() >= strip ? defLines[i].substring(strip) : defLines[i];
            normalizedDef.append(i == 0 ? "  " : "   ").append(line);
        }
        funcDef = normalizedDef.toString();

        String pathHex = toHex(lineNumFg);
        String fgHex = toHex(defaultFg);
        String escapedPath = relativePath.replace("&", "&amp;").replace("<", "&lt;");
        List<int[]> defTokens = tokenizeSnippet(funcDef, sh, scheme);
        String defHtml = buildHighlightedHtml(funcDef, defTokens, fgHex);
        headerLabel.setText("<html>" +
                "<span style='color:#FFFF00'>" + site.file().getName() + "</span>" +
                " <span style='color:" + pathHex + "'>" + escapedPath + "</span>" +
                "<br>" + defHtml +
                "<br><span style='color:" + pathHex + "'>...</span>" +
                "</html>");

        String[] lines = snippet.split("\n", -1);
        int snippetOffset = 0;
        int targetDocOffset = -1;
        for (int li = 0; li < lines.length; li++) {
            int absLine = fStartLine + li;
            boolean isTarget = absLine == targetLine;
            String lineText = lines[li];
            Color bg = isTarget ? caretBg : defaultBg;

            if (isTarget) targetDocOffset = styledDoc.getLength();

            // Line number prefix
            appendStr(styledDoc, String.format("%4d  ", absLine + 1), editorFont,
                    lineNumFg, bg, false, false);

            // Line content with syntax highlighting (fg unchanged on target line — only bg differs)
            int lineEnd = snippetOffset + lineText.length();
            int cursor = snippetOffset;
            for (int[] t : tokens) {
                if (t[1] <= cursor) continue;
                if (t[0] >= lineEnd) break;
                if (t[0] > cursor) {
                    appendStr(styledDoc, snippet.substring(cursor, t[0]), editorFont,
                            defaultFg, bg, false, false);
                    cursor = t[0];
                }
                int tEnd = Math.min(t[1], lineEnd);
                Color fg = t[2] != -1 ? new Color(t[2]) : defaultFg;
                appendStr(styledDoc, snippet.substring(cursor, tEnd), editorFont,
                        fg, bg, (t[3] & Font.BOLD) != 0, (t[3] & Font.ITALIC) != 0);
                cursor = tEnd;
            }
            if (cursor < lineEnd) {
                appendStr(styledDoc, snippet.substring(cursor, lineEnd), editorFont,
                        defaultFg, bg, false, false);
            }
            appendStr(styledDoc, "\n", editorFont, defaultFg, bg, false, false);
            snippetOffset += lineText.length() + 1;
        }

        // Place caret at top to avoid auto-scroll fighting with our centering below
        previewPane.setCaretPosition(0);

        // Center the target line in the viewport (deferred so layout is complete)
        if (targetDocOffset >= 0) {
            final int tOff = targetDocOffset;
            SwingUtilities.invokeLater(() -> {
                try {
                    java.awt.geom.Rectangle2D r = previewPane.modelToView2D(tOff);
                    JScrollPane sp = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, previewPane);
                    if (sp != null && r != null) {
                        int viewH = sp.getViewport().getHeight();
                        int scrollY = Math.max(0, (int)(r.getY() - viewH / 2.0));
                        sp.getViewport().setViewPosition(new Point(0, scrollY));
                    }
                } catch (javax.swing.text.BadLocationException ignored) {}
            });
        }
    }

    private static List<int[]> tokenizeSnippet(String text, @Nullable SyntaxHighlighter sh,
                                                EditorColorsScheme scheme) {
        List<int[]> result = new ArrayList<>();
        if (sh == null || text.isEmpty()) return result;
        Lexer lexer = sh.getHighlightingLexer();
        lexer.start(text);
        while (lexer.getTokenType() != null) {
            int start = lexer.getTokenStart();
            int end = lexer.getTokenEnd();
            TextAttributesKey[] keys = sh.getTokenHighlights(lexer.getTokenType());
            int fgRGB = -1;
            int fontType = 0;
            for (TextAttributesKey key : keys) {
                TextAttributes attrs = scheme.getAttributes(key);
                if (attrs != null) {
                    if (fgRGB == -1 && attrs.getForegroundColor() != null)
                        fgRGB = attrs.getForegroundColor().getRGB();
                    fontType |= attrs.getFontType();
                }
            }
            result.add(new int[]{start, end, fgRGB, fontType});
            lexer.advance();
        }
        return result;
    }

    private static void appendStr(javax.swing.text.StyledDocument doc, String text, Font font,
                                   Color fg, Color bg, boolean bold, boolean italic) {
        javax.swing.text.SimpleAttributeSet attrs = new javax.swing.text.SimpleAttributeSet();
        javax.swing.text.StyleConstants.setFontFamily(attrs, font.getFamily());
        javax.swing.text.StyleConstants.setFontSize(attrs, font.getSize());
        javax.swing.text.StyleConstants.setForeground(attrs, fg);
        javax.swing.text.StyleConstants.setBackground(attrs, bg);
        javax.swing.text.StyleConstants.setBold(attrs, bold);
        javax.swing.text.StyleConstants.setItalic(attrs, italic);
        try { doc.insertString(doc.getLength(), text, attrs); }
        catch (javax.swing.text.BadLocationException ignored) {}
    }

    private static String toHex(Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }

    private static String buildHighlightedHtml(String text, List<int[]> tokens, String defaultFgHex) {
        StringBuilder html = new StringBuilder();
        int cursor = 0;
        for (int[] t : tokens) {
            if (t[0] > cursor) {
                appendHtmlSpan(html, text.substring(cursor, t[0]), defaultFgHex, false);
                cursor = t[0];
            }
            String color = t[2] != -1 ? toHex(new Color(t[2])) : defaultFgHex;
            appendHtmlSpan(html, text.substring(t[0], t[1]), color, (t[3] & Font.BOLD) != 0);
            cursor = t[1];
        }
        if (cursor < text.length()) {
            appendHtmlSpan(html, text.substring(cursor), defaultFgHex, false);
        }
        return html.toString();
    }

    private static void appendHtmlSpan(StringBuilder sb, String text, String colorHex, boolean bold) {
        if (text.isEmpty()) return;
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>");
        sb.append("<span style='color:").append(colorHex);
        if (bold) sb.append(";font-weight:bold");
        sb.append("'>").append(escaped).append("</span>");
    }

    private static Color blend(Color a, Color b, float t) {
        return new Color(
                (int)(a.getRed() * (1 - t) + b.getRed() * t),
                (int)(a.getGreen() * (1 - t) + b.getGreen() * t),
                (int)(a.getBlue() * (1 - t) + b.getBlue() * t)
        );
    }

    private static List<CallSite> findCallerSites(PsiNamedElement element, Project project, int depth) {
        List<CallSite> sites = new ArrayList<>();
        for (PsiReference ref : ReferencesSearch.search(element, GlobalSearchScope.projectScope(project)).findAll()) {
            if (!ReadAction.compute(() -> isCallReference(ref.getElement().getParent()))) continue;
            PsiNamedElement callerEl = ReadAction.compute(() -> findEnclosingFunction(ref.getElement()));
            boolean hasChildren = callerEl != null && hasCallers(callerEl, project);
            CallSite site = ReadAction.compute(() -> {
                PsiElement el = ref.getElement();
                String display = buildDisplay(project, callerEl, el);
                return new CallSite(display, el.getContainingFile().getVirtualFile(),
                        el.getTextOffset(), depth, hasChildren ? callerEl : null, false);
            });
            sites.add(site);
        }
        sites.sort(Comparator.comparing(CallSite::display));
        return sites;
    }

    private static boolean hasCallers(PsiNamedElement element, Project project) {
        for (PsiReference ref : ReferencesSearch.search(element, GlobalSearchScope.projectScope(project)).findAll()) {
            if (ReadAction.compute(() -> isCallReference(ref.getElement().getParent()))) return true;
        }
        return false;
    }

    /** Matches Java PsiCallExpression, Python PyCallExpression, and any other language's call node. */
    private static boolean isCallReference(PsiElement parent) {
        if (parent instanceof PsiCallExpression) return true;
        return parent.getClass().getSimpleName().contains("Call");
    }

    /** Finds the nearest enclosing function/method element regardless of language. */
    private static @Nullable PsiNamedElement findEnclosingFunction(@Nullable PsiElement el) {
        PsiElement current = el;
        while (current != null && !(current instanceof PsiFile)) {
            String simpleName = current.getClass().getSimpleName();
            if (current instanceof PsiNamedElement named && named.getName() != null && isFunctionLike(current)) {
                log("[CallHierarchy] findEnclosingFunction matched: " + simpleName + " name=" + named.getName());
                return named;
            }
            // Clojure/Cursive: detect (defn name [...] ...) list forms
            PsiNamedElement clojureFunc = detectClojureDefn(current);
            if (clojureFunc != null) {
                log("[CallHierarchy] findEnclosingFunction matched Clojure defn: " + clojureFunc.getName());
                return clojureFunc;
            }
            log("[CallHierarchy] findEnclosingFunction skip: " + simpleName
                    + (current instanceof PsiNamedElement n2 ? " name=" + n2.getName() : ""));
            current = current.getParent();
        }
        return null;
    }

    /**
     * Detects Clojure (defn name ...) / (defmacro name ...) / (defmulti name ...) list forms.
     * Inspects the first two named children of any list-like element: if the first is a
     * function-defining form (defn, defmacro, etc.) the second is the function name.
     * Plain (def ...) variable definitions are excluded.
     */
    private static @Nullable PsiNamedElement detectClojureDefn(PsiElement el) {
        String simpleName = el.getClass().getSimpleName();
        if (!simpleName.contains("List") && !simpleName.contains("Form")) return null;
        PsiElement[] children = el.getChildren();
        PsiNamedElement first = null, second = null;
        for (PsiElement child : children) {
            if (child instanceof PsiNamedElement named && named.getName() != null) {
                if (first == null) first = named;
                else { second = named; break; }
            }
        }
        if (first == null || second == null) return null;
        String firstName = first.getName();
        if (!isClojureFunctionDef(firstName)) return null;
        return second;
    }

    /** Matches Clojure function-defining forms: defn, defn-, defmacro, defmulti, defmethod. */
    private static boolean isClojureFunctionDef(String word) {
        return word != null && (word.startsWith("defn") || word.equals("defmacro")
                || word.equals("defmulti") || word.equals("defmethod"));
    }

    /** True for Java PsiMethod, Python PyFunction, Kotlin KtNamedFunction, etc. */
    private static boolean isFunctionLike(PsiElement el) {
        if (el instanceof PsiMethod) return true;
        String name = el.getClass().getSimpleName();
        return name.contains("Function") || name.contains("Method");
    }

    private static final Path LOG_FILE = Paths.get(System.getProperty("user.home"), ".callhierarchypopup.log");

    private static void log(String msg) {
        if (!Files.exists(LOG_FILE)) return;
        try {
            Files.writeString(LOG_FILE, msg + "\n", StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
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
