package com.github.ivarref.ideafinda;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public class SimpleLog {
    private static final Path LOG_FILE = Paths.get(System.getProperty("user.home"), "finda_intellij.log");

    {
        {
            log("janei");
        }
    }

    public static void log(String msg) {
//        if (!Files.exists(LOG_FILE)) {
//            Files.writeString(LOG_FILE, msg + "\n", StandardOpenOption.CREATE_NEW);
//            return;
//        }
        try {
            Files.writeString(LOG_FILE, msg + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
