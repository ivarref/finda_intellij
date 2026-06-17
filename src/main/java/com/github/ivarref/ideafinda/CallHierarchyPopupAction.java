package com.github.ivarref.ideafinda;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
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
import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.ui.components.JBList;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.services.LanguageServer;
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

    private static final int PAGE_SIZE = 20;

    record CallSite(String display, VirtualFile file, int offset, int depth,
                    @Nullable CallHierarchyItem callerItem, boolean expanded) {}

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        e.getPresentation().setEnabledAndVisible(editor != null && e.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        log("[CallHierarchy] actionPerformed called");
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        Project project = e.getProject();
        if (editor == null || project == null) {
            log("[CallHierarchy] early return: null editor/project");
            return;
        }

        int offset = editor.getCaretModel().getOffset();
        VirtualFile vFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (vFile == null) {
            log("[CallHierarchy] no VirtualFile for editor");
            return;
        }

        new Task.Backgroundable(project, "Finding callers...") {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                log("[CallHierarchy] background task started");
                try {
                    LspClientService lsp = LspClientService.getInstance(project);
                    LanguageServer server = lsp.getServerFor(vFile);
                    if (server == null) {
                        log("[CallHierarchy] no language server for extension: " + vFile.getExtension());
                        return;
                    }

                    ReadAction.run(() -> lsp.ensureOpen(vFile, editor.getDocument()));

                    Position pos = ReadAction.compute(() ->
                            LspClientService.offsetToPosition(editor.getDocument(), offset));

                    CallHierarchyPrepareParams prepareParams = new CallHierarchyPrepareParams();
                    prepareParams.setTextDocument(new TextDocumentIdentifier(LspClientService.toUri(vFile)));
                    prepareParams.setPosition(pos);

                    List<CallHierarchyItem> items = server.getTextDocumentService()
                            .prepareCallHierarchy(prepareParams)
                            .get(30, java.util.concurrent.TimeUnit.SECONDS);

                    if (items == null || items.isEmpty()) {
                        log("[CallHierarchy] no call hierarchy item at caret position");
                        return;
                    }

                    CallHierarchyItem rootItem = items.get(0);
                    String startName = buildItemLabel(rootItem);
                    log("[CallHierarchy] starting from: " + startName);

                    Set<String> expanded = new HashSet<>();
                    expanded.add(itemKey(rootItem));

                    VirtualFile rootFile = LspClientService.fromUri(rootItem.getUri());
                    if (rootFile == null) {
                        log("[CallHierarchy] cannot resolve root file URI: " + rootItem.getUri());
                        return;
                    }

                    int rootOffset = ReadAction.compute(() -> {
                        Document doc = FileDocumentManager.getInstance().getDocument(rootFile);
                        if (doc == null) return 0;
                        return LspClientService.positionToOffset(doc, rootItem.getSelectionRange().getStart());
                    });

                    CallSite rootSite = new CallSite(buildLspDisplay(rootItem, null),
                            rootFile, rootOffset, 1, rootItem, true);

                    List<CallSite> initialItems = findCallerSitesLsp(server, rootItem, project, 2);
                    log("[CallHierarchy] found " + initialItems.size() + " initial callers");

                    if (initialItems.size() < 5) {
                        List<CallSite> withChildren = new ArrayList<>();
                        for (CallSite site : initialItems) {
                            if (site.callerItem() != null) {
                                expanded.add(itemKey(site.callerItem()));
                                withChildren.add(new CallSite(site.display(), site.file(), site.offset(),
                                        site.depth(), site.callerItem(), true));
                                withChildren.addAll(findCallerSitesLsp(server, site.callerItem(), project, site.depth() + 1));
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
                    log(java.util.Arrays.stream(ex.getStackTrace()).map(StackTraceElement::toString)
                            .collect(java.util.stream.Collectors.joining("\n  ", "  ", "")));
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
            String indicator = value.expanded() ? "*" : value.callerItem() != null ? ">" : ".";
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
        jbList.setVisibleRowCount(Math.min(PAGE_SIZE, Math.max(5, model.size())));
        int initialIndex = model.size() > 1 ? 1 : 0;
        if (model.size() > 0) jbList.setSelectedIndex(initialIndex);

        JTextPane previewPane = new JTextPane();
        previewPane.setEditable(false);
        previewPane.setFont(editorFont);
        previewPane.setBackground(jbList.getBackground());
        previewPane.setForeground(jbList.getForeground());
        JScrollPane previewScroll = new JScrollPane(previewPane);
        previewScroll.setBorder(BorderFactory.createEmptyBorder());
        int lineH = editorFont.getSize() + 5;
        int minPreviewH = lineH * 20;
        FontMetrics previewFm = Toolkit.getDefaultToolkit().getFontMetrics(editorFont);
        int previewWidth = previewFm.charWidth('m') * (100 + 6) + 16;
        previewScroll.setPreferredSize(new Dimension(previewWidth, minPreviewH));
        previewScroll.setMinimumSize(new Dimension(200, lineH * 10));

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
        scrollPane.setPreferredSize(new Dimension(previewWidth, lineH * PAGE_SIZE));
        scrollPane.setMinimumSize(new Dimension(200, lineH * 5));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scrollPane, previewPanel);
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

        java.awt.KeyEventDispatcher arrowDispatcher = e -> {
            if (e.getID() != KeyEvent.KEY_PRESSED) return false;
            Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
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
        CallHierarchyItem callerItem = selected.callerItem();
        if (callerItem == null || selected.expanded()) return;

        String callerKey = itemKey(callerItem);
        if (expanded.contains(callerKey)) return;
        expanded.add(callerKey);

        model.setElementAt(new CallSite(selected.display(), selected.file(), selected.offset(),
                selected.depth(), callerItem, true), idx);

        final int insertDepth = selected.depth() + 1;
        final int insertAfterIdx = idx;

        new Task.Backgroundable(project, "Expanding callers...") {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    LspClientService lsp = LspClientService.getInstance(project);
                    VirtualFile callerFile = LspClientService.fromUri(callerItem.getUri());
                    if (callerFile == null) return;
                    LanguageServer server = lsp.getServerFor(callerFile);
                    if (server == null) return;

                    List<CallSite> newSites = findCallerSitesLsp(server, callerItem, project, insertDepth);

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
                    log(java.util.Arrays.stream(ex.getStackTrace()).map(StackTraceElement::toString)
                            .collect(java.util.stream.Collectors.joining("\n  ", "  ", "")));
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

        List<CallSite> toRemove = new ArrayList<>();
        int i = idx + 1;
        while (i < model.size() && model.getElementAt(i).depth() > selected.depth()) {
            toRemove.add(model.getElementAt(i));
            i++;
        }
        for (int j = toRemove.size() - 1; j >= 0; j--) {
            model.removeElementAt(idx + 1 + j);
        }

        removeFromExpanded(expanded, selected);
        for (CallSite site : toRemove) {
            if (site.expanded()) removeFromExpanded(expanded, site);
        }

        model.setElementAt(new CallSite(selected.display(), selected.file(), selected.offset(),
                selected.depth(), selected.callerItem(), false), idx);
    }

    private static void collapseOrGoToParent(JBList<CallSite> jbList,
                                              DefaultListModel<CallSite> model, Set<String> expanded) {
        int idx = jbList.getSelectedIndex();
        if (idx < 0) return;
        CallSite selected = model.getElementAt(idx);
        if (selected.expanded()) {
            collapseSelected(jbList, model, expanded);
        } else {
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
        if (site.callerItem() != null) {
            expanded.remove(itemKey(site.callerItem()));
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

    private static String itemKey(CallHierarchyItem item) {
        return item.getUri() + ":" + item.getRange().getStart().getLine()
                + ":" + item.getRange().getStart().getCharacter();
    }

    private static String buildItemLabel(CallHierarchyItem item) {
        String detail = item.getDetail();
        if (detail != null && !detail.isBlank()) {
            return detail + "/" + item.getName();
        }
        return item.getName();
    }

    private static String buildLspDisplay(CallHierarchyItem item, @Nullable Range callRange) {
        String label = buildItemLabel(item);
        int line = callRange != null
                ? callRange.getStart().getLine() + 1
                : item.getSelectionRange().getStart().getLine() + 1;
        return label + ":" + line;
    }

    private static List<CallSite> findCallerSitesLsp(LanguageServer server, CallHierarchyItem item,
                                                      Project project, int depth) {
        CallHierarchyIncomingCallsParams params = new CallHierarchyIncomingCallsParams();
        params.setItem(item);
        List<CallHierarchyIncomingCall> calls;
        try {
            calls = server.getTextDocumentService()
                    .callHierarchyIncomingCalls(params)
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log("[CallHierarchy] incomingCalls error: " + e);
            return List.of();
        }
        if (calls == null) return List.of();

        List<CallSite> sites = new ArrayList<>();
        for (CallHierarchyIncomingCall call : calls) {
            CallHierarchyItem from = call.getFrom();
            VirtualFile callerFile = LspClientService.fromUri(from.getUri());
            if (callerFile == null) continue;

            for (Range fromRange : call.getFromRanges()) {
                int offset = ReadAction.compute(() -> {
                    Document doc = FileDocumentManager.getInstance().getDocument(callerFile);
                    if (doc == null) return 0;
                    return LspClientService.positionToOffset(doc, fromRange.getStart());
                });
                boolean hasChildren = hasLspCallers(server, from);
                String display = buildLspDisplay(from, fromRange);
                sites.add(new CallSite(display, callerFile, offset, depth,
                        hasChildren ? from : null, false));
            }
        }
        sites.sort(Comparator.comparingInt((CallSite s) -> isPlainTextFile(s.file()) ? 1 : 0)
                .thenComparing(CallSite::display));
        return sites;
    }

    private static boolean hasLspCallers(LanguageServer server, CallHierarchyItem item) {
        try {
            CallHierarchyIncomingCallsParams params = new CallHierarchyIncomingCallsParams();
            params.setItem(item);
            List<CallHierarchyIncomingCall> calls = server.getTextDocumentService()
                    .callHierarchyIncomingCalls(params)
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            return calls != null && !calls.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static void updatePreview(JTextPane previewPane, JLabel headerLabel,
                                      @Nullable CallSite site, Font editorFont, Project project) {
        if (site == null) { headerLabel.setText(" "); previewPane.setText(""); return; }
        Document doc = ReadAction.compute(() ->
                FileDocumentManager.getInstance().getDocument(site.file()));
        if (doc == null) { previewPane.setText("(no preview)"); return; }
        int targetLine = ReadAction.compute(() -> doc.getLineNumber(site.offset()));
        int startLine = Math.max(0, targetLine - 20);
        int endLine = Math.min(doc.getLineCount() - 1, targetLine + 20);
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

        // Build header: filename, relative path, function signature from LSP item range
        String funcDef = ReadAction.compute(() -> {
            CallHierarchyItem item = site.callerItem();
            if (item == null) return "";
            int funcStartLine = item.getRange().getStart().getLine();
            StringBuilder sb = new StringBuilder();
            for (int i = funcStartLine; i < Math.min(doc.getLineCount(), funcStartLine + 9); i++) {
                String line = doc.getText(new TextRange(doc.getLineStartOffset(i), doc.getLineEndOffset(i)));
                if (i > funcStartLine) sb.append("\n");
                sb.append(line);
                String check = line;
                int slash = check.indexOf("//");
                if (slash >= 0) check = check.substring(0, slash);
                int hash = check.indexOf('#');
                if (hash >= 0) check = check.substring(0, hash);
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
            normalizedDef.append(i == 0 ? "  " : "   ").append(line);
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

            appendStr(styledDoc, String.format("%4d  ", absLine + 1), editorFont,
                    lineNumFg, bg, false, false);

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

        previewPane.setCaretPosition(0);

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

    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of(
            "md", "markdown", "txt", "adoc", "rst", "org");

    private static boolean isPlainTextFile(VirtualFile file) {
        String ext = file.getExtension();
        return ext != null && PLAIN_TEXT_EXTENSIONS.contains(ext.toLowerCase());
    }

    private static void log(String msg) {
        SimpleLog.log(msg);
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
