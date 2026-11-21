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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.github.ivarref.ideafinda.SimpleLog.*;

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

        log("Starting command '" + command + "' in base path '" + basePath + "'");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(basePath));
        Process proc = pb.start();

        Thread stderrDrain = new Thread(() -> {
            try {
                BufferedReader err = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
                while (true) {
                    String line = err.readLine();
                    if (line == null) {
                        log("EOF reached for process");
                        break;
                    } else {
                        log("lsp: " + line);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "lsp-stderr");
        stderrDrain.setDaemon(true);
        stderrDrain.start();

        var launcher = LSPLauncher.createClientLauncher(
                new SimpleLanguageClient(),
                proc.getInputStream(),
                proc.getOutputStream());
        launcher.startListening();
        LanguageServer srv = launcher.getRemoteProxy();

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

        log("Begin initialize ...");
        srv.initialize(initParams).get(60, TimeUnit.SECONDS);
        log("Begin initialize ... OK!");
        srv.initialized(new InitializedParams());

        this.process = proc;
        this.server = srv;
        log("server started!");
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

    public @Nullable String detectCommand(@NotNull VirtualFile file) {
        String ext = file.getExtension();
        log("extension is: '" + ext + "'");
        if (ext == null) return null;
        return switch (ext.toLowerCase()) {
            case "clj", "cljs", "cljc", "edn" -> {
                String s = findInPath("clojure-lsp");
                if (s == null) {
                    log("clojure-lsp not found!");
                } else {
                    log("clojure-lsp located at: '" + s + "'");
                }
                String ss = findInPath("clojure");
                if (ss == null) {
                    log("Command 'clojure' not found!");
                } else {
                    log("Command 'clojure' located at: '" + ss + "'");
                }
                yield s;
            }
            default -> null;
        };
    }

    private static @Nullable String findMisePath() {
        try {
            Process p = new ProcessBuilder("which", "mise").start();
            String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String result2 = p.waitFor() == 0 && !result.isEmpty() ? result : null;
            if (result2 == null) {
                log("'which mise' returned nothing");
            } else {
                log("Mise located at '" + result2 + "'");
                return result2;
            }
        } catch (Exception e) {
            return null;
        }
        String userHome = System.getProperty("user.home");
        log("userHome is: '" + userHome + "'");
        String misePath = userHome + "/.local/bin/mise";
        if (new File(misePath).exists()) {
            log("Checking '" + misePath + "' ... Found!");
            return misePath;
        } else {
            log("Checking '" + misePath + "' ... Not found!");
            return null;
        }
    }

    private @Nullable String findInPath(String binary) {
        String misePath = findMisePath();
        String projectDir = System.getenv("PROJECT_DIR");
        log("project_dir is: " + projectDir);

        Path miseScriptFile = null;
        try {
            Set<PosixFilePermission> fp =
                    PosixFilePermissions.fromString("rwxrwxrwx");

            miseScriptFile = Files.createTempFile("mise_script", ".sh",
                    PosixFilePermissions.asFileAttribute(fp));
            String tmpFile = project.getBasePath() + "/.misetmp.sh";

            try (BufferedWriter bf = new BufferedWriter(new FileWriter(tmpFile))) {
                bf.write("#!/usr/bin/env bash");
                bf.write("\n");
                bf.write("pwd\n");
                bf.write("echo \"wtf\"\n");
                bf.write("eval \"$(mise activate --no-hook-env)\"\n");
                bf.write("mise hook-env\n");
                bf.write("echo \"wtf2\"\n");
                bf.write("eval \"$(mise activate bash)\"");
                bf.write("\n");
                bf.write("which \"clojure-lsp\"");
                bf.write("\n");
                bf.write("echo \"$PATH\"");
                bf.write("\n");
                bf.write("echo \"janei\"");
                bf.write("\n");
            }
            Process p2 = new ProcessBuilder("chmod", "+x", tmpFile).start();
            String result2 = new String(p2.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode2 = p2.waitFor();

            Process p = new ProcessBuilder(misePath, "run",
                    "--quiet", "--cd", project.getBasePath(), "./.misetmp.sh").start();
            String result = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = p.waitFor();
            log("mise script exited with code '" + exitCode + "'");
            log("mise script stdout: '" + result + "'");
        } catch (Exception e) {
            log("Exception while invoking mise: " + e.getMessage());
            return null;
        } finally {
            if (miseScriptFile != null) {
                try {
                    miseScriptFile.toFile().delete();
                } catch (Exception e) {
                    log("Delete temp file failed");
                }
            }
        }

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
        log("Ext is: " + ext);
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
            log("showMessage: " + messageParams);
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            log("showMessageRequest");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message) {
            log("logMessage: " + message);
        }
    }
}
