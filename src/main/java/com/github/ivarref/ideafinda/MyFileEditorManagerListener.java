package com.github.ivarref.ideafinda;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MyFileEditorManagerListener implements FileEditorManagerListener {

    public MyFileEditorManagerListener(Project project) {
        super();
    }

    private static final String findaDir = System.getProperty("user.home") + "/.finda";

    public static synchronized void info(String msg) {
        String fileName = findaDir + "/integrations/finda_intellij/plugin.log";
        try (FileWriter fw = new FileWriter(fileName, StandardCharsets.UTF_8, true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.println(msg);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public final void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        if (true) {
            return;
        }
        info("***************************************************");
        String basePath = source.getProject().getBasePath();
        String enc = URLEncoder.encode(basePath, StandardCharsets.UTF_8);

        String jsonFileName = findaDir + "/external_data/idea_project_" + enc + ".json";

        info("Project base path is: " + enc);

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(
                new FileWriter(jsonFileName, StandardCharsets.UTF_8, false)))) {
            pw.println("[");

            List<String> files = new ArrayList<>();
            for (VirtualFile vf : source.getOpenFiles()) {
                if (vf.toString().startsWith("file:///")) {
                    files.add(vf.getCanonicalPath());
                }
            }
            for (String fileStr : files) {
                if (!fileStr.equals(files.getFirst())) {
                    pw.println(",");
                }
                pw.println("{");
                File file1 = new File(fileStr);
                String label = "janei: " + file1.getName();
                String command = "python3 " +
                        findaDir + "/integrations/finda_intellij/ideaopen.py " + fileStr;
                pw.println("\"label\": \"" + label + "\",");
                pw.println("\"command\": \"" + command + "\"");
                pw.println("}");
                info("Open file: " + fileStr);
            }

            pw.println("]");
        } catch (IOException e) {
            e.printStackTrace();
        }
        info("Wrote file: " + jsonFileName);
    }

    @Override
    public final void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        fileOpened(source, file);
    }
}
