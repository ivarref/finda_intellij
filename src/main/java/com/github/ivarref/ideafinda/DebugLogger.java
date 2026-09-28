package com.github.ivarref.ideafinda;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

public class DebugLogger {

    private static final String findaDir = System.getProperty("user.home") + "/.finda";

    public static synchronized void info(String msg) {
        String fileName = findaDir + "/integrations/finda_intellij/plugin.log";
        try (FileWriter fw = new FileWriter(fileName, StandardCharsets.UTF_8, true);
             PrintWriter pw = new PrintWriter(fw)) {
            List<StackWalker.StackFrame> stack = StackWalker.getInstance().walk(stckframe ->
                    stckframe.limit(10).collect(Collectors.toList()));
            StackWalker.StackFrame sf = stack.get(1);
            String msg2 = sf.getClassName() + " " + msg;
            pw.println(msg2);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String top() {
        String v = middle();
        return v;
    }

    public static String middle() {
        String v = bottom();
        return v + "middle";
    }

    public static String bottom() {
        String v = "bottom";
        return v;
    }
}
