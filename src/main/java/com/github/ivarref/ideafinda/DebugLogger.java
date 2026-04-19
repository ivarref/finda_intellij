package com.github.ivarref.ideafinda;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public class DebugLogger {
    
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
}
