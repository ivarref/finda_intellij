package com.github.ivarref.ideafinda;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InitSLF4J {

    static {
        {
        }
    }

    public static synchronized Logger getLogger(Class<?> clazz) {
        System.setProperty("logback.configurationFile", "/Users/ire/.finda/integrations/finda_intellij/src/resources/pluggy_logback.xml");
        return LoggerFactory.getLogger(clazz);
    }

}
