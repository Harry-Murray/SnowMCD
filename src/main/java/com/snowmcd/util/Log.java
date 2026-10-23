package com.snowmcd.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Minimal logging facade: console + rolling file in {@code %APPDATA%/SnowMCD/logs}.
 * (slf4j-simple only supports a single output; this keeps console and rotating file together.)
 */
public final class Log {

    private static final Logger LOGGER = Logger.getLogger("SnowMCD");
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final long MAX_LOG_BYTES = 4L * 1024 * 1024;
    private static final int KEEP_LOGS = 5;

    private static volatile Path logFile;
    private static boolean initialized;

    private Log() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        LOGGER.setUseParentHandlers(false);
        LOGGER.setLevel(Level.ALL);

        Formatter formatter = new Formatter() {
            @Override
            public String format(LogRecord record) {
                StringBuilder sb = new StringBuilder(192);
                sb.append(TS.format(LocalDateTime.ofInstant(record.getInstant(), ZoneId.systemDefault())))
                        .append(' ')
                        .append(String.format("%-5s", record.getLevel().getName()))
                        .append(" [").append(Thread.currentThread().getName()).append("] ")
                        .append(formatMessage(record))
                        .append(System.lineSeparator());
                if (record.getThrown() != null) {
                    StringWriter sw = new StringWriter();
                    record.getThrown().printStackTrace(new PrintWriter(sw));
                    sb.append(sw);
                }
                return sb.toString();
            }
        };

        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(Level.INFO);
        console.setFormatter(formatter);
        LOGGER.addHandler(console);

        try {
            Path dir = AppPaths.logsDir();
            Files.createDirectories(dir);
            Path target = dir.resolve("snowmcd.log");
            rotate(target);
            FileHandler fileHandler = new FileHandler(target.toString(), true);
            fileHandler.setLevel(Level.INFO);
            fileHandler.setFormatter(formatter);
            LOGGER.addHandler(fileHandler);
            logFile = target;
        } catch (IOException | SecurityException e) {
            warning("无法初始化日志文件: %s", e.getMessage());
        }
    }

    private static void rotate(Path target) throws IOException {
        if (Files.exists(target) && Files.size(target) > MAX_LOG_BYTES) {
            String stamp = LocalDateTime.now().format(FILE_STAMP);
            Files.move(target, target.resolveSibling("snowmcd-" + stamp + ".log"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        try (var stream = Files.list(target.getParent())) {
            List<Path> old = stream
                    .filter(p -> p.getFileName().toString().matches("snowmcd-\\d{8}-\\d{6}\\.log"))
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .toList();
            int removable = old.size() - (KEEP_LOGS - 1);
            for (int i = 0; i < removable; i++) {
                Files.deleteIfExists(old.get(i));
            }
        }
    }

    public static void info(String message, Object... args) {
        LOGGER.log(Level.INFO, args.length == 0 ? message : String.format(message, args));
    }

    public static void warning(String message, Object... args) {
        LOGGER.log(Level.WARNING, args.length == 0 ? message : String.format(message, args));
    }

    public static void error(String message, Throwable thrown, Object... args) {
        LOGGER.log(Level.SEVERE, args.length == 0 ? message : String.format(message, args), thrown);
    }

    public static Path logFile() {
        return logFile;
    }
}
