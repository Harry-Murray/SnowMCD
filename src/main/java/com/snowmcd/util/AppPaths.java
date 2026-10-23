package com.snowmcd.util;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Well-known locations used by SnowMCD. */
public final class AppPaths {

    private AppPaths() {
    }

    public static Path appDataDir() {
        String appData = System.getenv("APPDATA");
        Path base = (appData != null && !appData.isBlank())
                ? Paths.get(appData)
                : Paths.get(System.getProperty("user.home"));
        return base.resolve("SnowMCD");
    }

    public static Path logsDir() {
        return appDataDir().resolve("logs");
    }

    public static Path cacheDir() {
        return appDataDir().resolve("cache");
    }

    public static Path defaultMinecraftDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Paths.get(appData, ".minecraft");
        }
        return Paths.get(System.getProperty("user.home"), ".minecraft");
    }
}
