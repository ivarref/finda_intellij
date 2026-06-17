package com.github.ivarref.ideafinda;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.eclipse.lsp4j.CallHierarchyCapabilities;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service(Service.Level.PROJECT)
public final class LspClientService implements Disposable {

    private final Project project;
    private volatile LanguageServer server;
    private volatile Process process;
    private final Set<String> openedUris = Collections.synchronizedSet(new HashSet<>());

    public LspClientService(@NotNull Project project) {
        this.project = project;
    }

    public static LspClientService getInstance(@NotNull Project project) {
        return project.getService(LspClientService.class);
    }

    /**
     * Returns the language server for this file, starting it if needed.
     * Returns null if no language server is configured for the file's type.
     */
    public @Nullable LanguageServer getServerFor(@NotNull VirtualFile file) throws Exception {
        if (server != null) return server;
        synchronized (this) {
            if (server != null) return server;
            String cmd = detectCommand(file);
            if (cmd == null) return null;
            startServer(cmd);
        }
        return server;
    }

    public void ensureOpen(@NotNull VirtualFile file, @NotNull Document doc) {
        if (server == null) return;
        String uri = toUri(file);
        if (openedUris.add(uri)) {
            DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
            TextDocumentItem item = new TextDocumentItem();
            item.setUri(uri);
            item.setLanguageId(languageId(file));
            item.setVersion(1);
            item.setText(doc.getText());
            params.setTextDocument(item);
            server.getTextDocumentService().didOpen(params);
        }
    }

    private void startServer(String command) throws Exception {
        String basePath = project.getBasePath();
        if (basePath == null)
            throw new IllegalStateException("Project has no base path");

        SimpleLog.log("Starting command '" + command + "' in base path '" + basePath + "'");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(basePath));
        Process proc = pb.start();

        Thread stderrDrain = new Thread(() -> {
            try {
                BufferedReader err = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
                while (true) {
                    String line = err.readLine();
                    if (line == null) {
                        SimpleLog.log("EOF reached for process");
                        break;
                    } else {
                        SimpleLog.log("lsp: " + line);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "lsp-stderr");
        stderrDrain.setDaemon(true);
        stderrDrain.start();

        LanguageServer srv = LSPLauncher.createClientLauncher(
                new SimpleLanguageClient(),
                proc.getInputStream(),
                proc.getOutputStream()).getRemoteProxy();

        InitializeParams initParams = new InitializeParams();
        initParams.setProcessId((int) ProcessHandle.current().pid());
        initParams.setRootUri(new File(basePath).toURI().toString());

        TextDocumentClientCapabilities tdCaps = new TextDocumentClientCapabilities();
        CallHierarchyCapabilities chCaps = new CallHierarchyCapabilities();
        chCaps.setDynamicRegistration(false);
        tdCaps.setCallHierarchy(chCaps);

        ClientCapabilities caps = new ClientCapabilities();
        caps.setTextDocument(tdCaps);
        initParams.setCapabilities(caps);

        SimpleLog.log("Begin initialize ...");
        srv.initialize(initParams).get(60, TimeUnit.SECONDS);
        SimpleLog.log("Begin initialize ... OK!");
        srv.initialized(new InitializedParams());

        this.process = proc;
        this.server = srv;
        SimpleLog.log("server started!");
    }

    @Override
    public void dispose() {
        LanguageServer srv = server;
        server = null;
        if (srv != null) {
            try {
                srv.shutdown().get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            try {
                srv.exit();
            } catch (Exception ignored) {
            }
        }
        Process proc = process;
        process = null;
        if (proc != null) proc.destroyForcibly();
    }

    public static @Nullable String detectCommand(@NotNull VirtualFile file) {
        String ext = file.getExtension();
        SimpleLog.log("extension is: '" + ext + "'");
        if (ext == null) return null;
        return switch (ext.toLowerCase()) {
            case "clj", "cljs", "cljc", "edn" -> {
                String s = findInPath("clojure-lsp");
                if (s == null) {
                    SimpleLog.log("clojure-lsp not found!");
                } else {
                    SimpleLog.log("clojure-lsp located at: '" + s + "'");
                }
                yield s;
            }
            default -> null;
        };
    }

    private static @Nullable String findInPath(String binary) {
        try {
            Process p = new ProcessBuilder("which", binary).start();
            String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String result2 = p.waitFor() == 0 && !result.isEmpty() ? result : null;
            if (result2 == null) {
                if (new File("/opt/homebrew/bin/" + binary).exists()) {
                    return "/opt/homebrew/bin/" + binary;
                } else {
                    return null;
                }
            } else {
                return result2;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static @NotNull String toUri(@NotNull VirtualFile file) {
        return new File(file.getPath()).toURI().toString();
    }

    public static @Nullable VirtualFile fromUri(@NotNull String uri) {
        try {
            String path = new URI(uri).getPath();
            return LocalFileSystem.getInstance().findFileByPath(path);
        } catch (Exception e) {
            return null;
        }
    }

    public static int positionToOffset(@NotNull Document doc, @NotNull Position pos) {
        int line = Math.min(pos.getLine(), doc.getLineCount() - 1);
        int lineStart = doc.getLineStartOffset(line);
        int lineEnd = doc.getLineEndOffset(line);
        return Math.min(lineStart + pos.getCharacter(), lineEnd);
    }

    public static @NotNull Position offsetToPosition(@NotNull Document doc, int offset) {
        int line = doc.getLineNumber(offset);
        int character = offset - doc.getLineStartOffset(line);
        return new Position(line, character);
    }

    private static @NotNull String languageId(@NotNull VirtualFile file) {
        String ext = file.getExtension();
        SimpleLog.log("Ext is: " + ext);
        if (ext == null) return "text";
        return switch (ext.toLowerCase()) {
            case "clj", "cljc" -> "clojure";
            case "cljs" -> "clojurescript";
            default -> ext.toLowerCase();
        };
    }

    private static class SimpleLanguageClient implements LanguageClient {
        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        }

        @Override
        public void showMessage(MessageParams messageParams) {
            SimpleLog.log("showMessage: " + messageParams);
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            SimpleLog.log("showMessageRequest");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message) {
            SimpleLog.log("logMessage: " + message);
        }
    }
}
