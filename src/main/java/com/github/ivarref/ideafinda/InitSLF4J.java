package com.github.ivarref.ideafinda;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class InitSLF4J {

    private static boolean initialized = false;

    private static void init() {
        if (!initialized) {
            final String extractedLogConfig = System.getProperty("user.home") + "/.pluggy_logback.xml";
            final String outputLogFileName = System.getProperty("user.home") + "/.simple.log";
            try (InputStream is = InitSLF4J.class.getResourceAsStream("/pluggy_logback.xml");
                 BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                 FileWriter fw = new FileWriter(extractedLogConfig, StandardCharsets.UTF_8, false);
                 PrintWriter pw = new PrintWriter(fw)) {
                while (true) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    if (line.contains("<file>")) {
                        pw.println("<file>" + outputLogFileName + "</file>");

                    } else {
                        pw.println(line);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.setProperty("logback.configurationFile", extractedLogConfig);
            initialized = true;
        }
    }

    public static synchronized Logger getLogger(Class<?> clazz) {
        init();
        return LoggerFactory.getLogger(clazz);
    }

}
