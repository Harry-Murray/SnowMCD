package com.snowmcd;

import com.snowmcd.core.DownloadReport;
import com.snowmcd.core.MinecraftDownloader;
import com.snowmcd.core.ProgressListener;
import com.snowmcd.core.ProgressSnapshot;
import com.snowmcd.ui.MainApp;
import com.snowmcd.util.Log;
import javafx.application.Application;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Entry point. Without arguments the JavaFX UI starts; {@code --cli} runs a
 * headless download (useful for smoke tests and automation).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Log.init();
        if (Arrays.asList(args).contains("--cli")) {
            System.exit(runCli(args));
            return;
        }
        Log.info("SnowMCD 启动 (Java %s, %s)", System.getProperty("java.version"),
                System.getProperty("os.name"));
        Application.launch(MainApp.class, args);
    }

    private static int runCli(String[] args) {
        String version = valueOf(args, "--version");
        String dir = valueOf(args, "--dir");
        if (version == null || dir == null) {
            System.out.println("用法: java -m com.snowmcd/com.snowmcd.Main --cli "
                    + "--version <版本号> --dir <目标目录> [--concurrency 32]");
            return 2;
        }
        int concurrency = intValueOf(args, "--concurrency", 32);
        Path target = Paths.get(dir).toAbsolutePath();
        MinecraftDownloader downloader = new MinecraftDownloader();
        ProgressListener listener = new ConsoleProgress();
        try {
            DownloadReport report = downloader.download(version, target, concurrency, 3, listener);
            System.out.println();
            System.out.printf("完成: 成功 %d, 跳过 %d, 失败 %d, 用时 %.1f s, 目录 %s%n",
                    report.succeededFiles(), report.skippedFiles(), report.failedFiles(),
                    report.elapsedMillis() / 1000.0, report.gameDir());
            for (var failure : report.failures()) {
                System.out.printf("  失败: %s (%s)%n", failure.task().label(), failure.error());
            }
            return report.cancelled() ? 130 : (report.failedFiles() > 0 ? 1 : 0);
        } catch (Exception e) {
            Log.error("CLI 下载失败", e);
            e.printStackTrace();
            return 1;
        }
    }

    private static String valueOf(String[] args, String key) {
        for (int i = 0; i < args.length - 1; i++) {
            if (key.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static int intValueOf(String[] args, String key, int fallback) {
        String value = valueOf(args, key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class ConsoleProgress implements ProgressListener {
        private long lastPrint;

        @Override
        public void onStage(String message) {
            System.out.println("[阶段] " + message);
        }

        @Override
        public void onLog(String message) {
            System.out.println("[信息] " + message);
        }

        @Override
        public void onProgress(ProgressSnapshot snapshot) {
            long now = System.currentTimeMillis();
            if (now - lastPrint < 2000) {
                return;
            }
            lastPrint = now;
            System.out.printf("\r%.1f%%  %s / %s  %s/s  %d/%d 个文件  失败 %d   ",
                    snapshot.fraction() * 100,
                    MinecraftDownloader.formatBytes(snapshot.downloadedBytes()),
                    MinecraftDownloader.formatBytes(snapshot.totalBytes()),
                    MinecraftDownloader.formatBytes((long) snapshot.bytesPerSecond()),
                    snapshot.completedFiles(), snapshot.totalFiles(), snapshot.failedFiles());
        }
    }
}
