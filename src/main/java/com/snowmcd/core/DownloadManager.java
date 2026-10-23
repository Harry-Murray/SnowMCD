package com.snowmcd.core;

import com.snowmcd.util.HashUtils;
import com.snowmcd.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Concurrent download engine: one virtual thread per file, a semaphore for the
 * concurrency limit, retries with exponential backoff, SHA-1 verification and
 * atomic renames. Existing valid files are skipped so repeat runs are instant.
 */
public final class DownloadManager {

    public static final String USER_AGENT = "SnowMCD/1.0.0 (Minecraft installer; BMCLAPI mirror)";

    private static final long RETRY_BASE_DELAY_MS = 1_000L;
    private static final long STALL_TIMEOUT_NANOS = 30_000_000_000L;

    private final HttpClient http;
    private final int concurrency;
    private final int maxAttempts;

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicInteger succeeded = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicLong downloadedBytes = new AtomicLong();
    private final AtomicLong totalBytes = new AtomicLong();
    private final AtomicInteger totalFiles = new AtomicInteger();
    private final AtomicReference<String> currentFile = new AtomicReference<>("");
    private final List<FailedDownload> failures = Collections.synchronizedList(new ArrayList<>());
    private final List<CompletableFuture<Void>> futures = new ArrayList<>();

    private volatile ExecutorService executor;
    private volatile ScheduledExecutorService ticker;
    private volatile long lastSampleTime;
    private volatile long lastSampleBytes;
    private volatile double speed;

    public DownloadManager(int concurrency, int maxAttempts) {
        this.concurrency = Math.max(1, concurrency);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        List<CompletableFuture<Void>> inFlight;
        synchronized (futures) {
            inFlight = new ArrayList<>(futures);
        }
        for (CompletableFuture<Void> future : inFlight) {
            future.cancel(true);
        }
        ExecutorService current = executor;
        if (current != null) {
            current.shutdownNow();
        }
        Log.info("下载已请求取消");
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public DownloadReport download(String versionId, Path gameDir, List<DownloadTask> tasks,
                                   ProgressListener listener) {
        long startedAt = System.currentTimeMillis();
        totalFiles.set(tasks.size());
        long planBytes = tasks.stream().mapToLong(task -> Math.max(0L, task.size())).sum();
        totalBytes.set(planBytes);
        downloadedBytes.set(0);
        succeeded.set(0);
        skipped.set(0);
        failed.set(0);
        failures.clear();
        currentFile.set("");
        synchronized (futures) {
            futures.clear();
        }
        lastSampleTime = startedAt;
        lastSampleBytes = 0;
        speed = 0;

        listener.onPlan(new DownloadPlan(tasks.size(), planBytes));

        ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "snowmcd-progress");
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleAtFixedRate(() -> {
            try {
                publishProgress(listener);
            } catch (RuntimeException e) {
                Log.warning("进度回调异常: %s", e.getMessage());
            }
        }, 250, 250, TimeUnit.MILLISECONDS);

        Semaphore permits = new Semaphore(concurrency);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            submit:
            for (DownloadTask task : tasks) {
                try {
                    while (!permits.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                        if (cancelled.get()) {
                            break submit;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (cancelled.get()) {
                    permits.release();
                    break;
                }
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        processTask(task, listener);
                    } catch (Throwable throwable) {
                        Log.error("下载任务异常: " + task.url(), throwable);
                        failed.incrementAndGet();
                        failures.add(new FailedDownload(task, String.valueOf(throwable.getMessage())));
                    } finally {
                        permits.release();
                    }
                }, executor);
                synchronized (futures) {
                    futures.add(future);
                }
            }

            List<CompletableFuture<Void>> waiting;
            synchronized (futures) {
                waiting = new ArrayList<>(futures);
            }
            for (CompletableFuture<Void> future : waiting) {
                try {
                    future.join();
                } catch (CompletionException | CancellationException ignored) {
                    // failures are recorded by the task itself
                }
            }
        } finally {
            ScheduledExecutorService currentTicker = ticker;
            if (currentTicker != null) {
                currentTicker.shutdownNow();
            }
            ExecutorService currentExecutor = executor;
            if (currentExecutor != null) {
                currentExecutor.shutdownNow();
            }
        }

        publishProgress(listener);
        long elapsed = System.currentTimeMillis() - startedAt;
        Log.info("下载结束: 成功 %d, 跳过 %d, 失败 %d, 用时 %d ms",
                succeeded.get(), skipped.get(), failed.get(), elapsed);
        List<FailedDownload> snapshot;
        synchronized (failures) {
            snapshot = List.copyOf(failures);
        }
        return new DownloadReport(versionId, gameDir, cancelled.get(), tasks.size(),
                succeeded.get(), skipped.get(), failed.get(), downloadedBytes.get(), elapsed,
                snapshot, 0, 0);
    }

    private void processTask(DownloadTask task, ProgressListener listener) {
        Path temp = tempPath(task.target());
        try {
            Files.createDirectories(task.target().getParent());
            if (HashUtils.matches(task.target(), task.sha1(), task.size())) {
                skipped.incrementAndGet();
                downloadedBytes.addAndGet(Math.max(0L, task.size()));
                return;
            }
            Files.deleteIfExists(temp);
            currentFile.set(task.label());
            Exception lastError = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("已取消");
                }
                long[] attemptBytes = {0L};
                try {
                    transfer(task, temp, read -> {
                        attemptBytes[0] += read;
                        downloadedBytes.addAndGet(read);
                    });
                    succeeded.incrementAndGet();
                    return;
                } catch (CancellationException e) {
                    throw e;
                } catch (Exception e) {
                    if (cancelled.get()) {
                        throw new CancellationException("已取消");
                    }
                    downloadedBytes.addAndGet(-attemptBytes[0]);
                    Files.deleteIfExists(temp);
                    lastError = e;
                    Log.warning("下载失败(%d/%d) %s : %s", attempt, maxAttempts, task.url(), describe(e));
                    if (attempt < maxAttempts) {
                        sleepBackoff(attempt);
                    }
                }
            }
            failed.incrementAndGet();
            String reason = describe(lastError == null ? new IOException("未知错误") : lastError);
            failures.add(new FailedDownload(task, reason));
            listener.onFileFailed(task, reason);
        } catch (CancellationException e) {
            deleteQuietly(temp);
        } catch (IOException e) {
            Log.error("无法创建目录: " + task.target(), e);
            failed.incrementAndGet();
            failures.add(new FailedDownload(task, e.getMessage()));
            listener.onFileFailed(task, e.getMessage());
        } finally {
            deleteQuietly(temp);
        }
    }

    private interface ByteSink {
        void accept(long bytes);
    }

    private void transfer(DownloadTask task, Path temp, ByteSink sink) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(task.url()))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (cancelled.get()) {
                throw new CancellationException("已取消");
            }
            throw new IOException("连接被中断");
        }
        int status = response.statusCode();
        if (status != 200) {
            try (InputStream ignored = response.body()) {
                // drain/close to release the connection
            }
            throw new IOException("HTTP " + status);
        }
        byte[] buffer = new byte[64 * 1024];
        InputStream body = response.body();
        AtomicBoolean finished = new AtomicBoolean();
        AtomicReference<InputStream> streamRef = new AtomicReference<>(body);
        AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        Thread watchdog = Thread.ofVirtual().name("stall-watch").start(() -> {
            try {
                while (!finished.get()) {
                    Thread.sleep(3000);
                    if (System.nanoTime() - lastActivity.get() > STALL_TIMEOUT_NANOS) {
                        closeQuietly(streamRef.get());
                        return;
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        try (InputStream in = body;
             OutputStream out = Files.newOutputStream(temp,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("已取消");
                }
                out.write(buffer, 0, read);
                lastActivity.set(System.nanoTime());
                sink.accept(read);
            }
        } finally {
            finished.set(true);
            watchdog.interrupt();
        }
        long actualSize = Files.size(temp);
        if (task.size() > 0 && actualSize != task.size()) {
            throw new IOException("文件大小不符: 期望 " + task.size() + "，实际 " + actualSize);
        }
        if (task.hasHash()) {
            String actualHash = HashUtils.sha1Hex(temp);
            if (!task.sha1().equalsIgnoreCase(actualHash)) {
                throw new IOException("SHA-1 校验失败");
            }
        }
        moveAtomically(temp, task.target());
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void publishProgress(ProgressListener listener) {
        long now = System.currentTimeMillis();
        long bytes = downloadedBytes.get();
        long deltaMs = now - lastSampleTime;
        if (deltaMs >= 150) {
            double instant = (bytes - lastSampleBytes) * 1000.0 / deltaMs;
            speed = speed <= 0 ? Math.max(0, instant) : speed * 0.7 + Math.max(0, instant) * 0.3;
            lastSampleTime = now;
            lastSampleBytes = bytes;
        }
        long total = totalBytes.get();
        long remaining = Math.max(0L, total - bytes);
        long eta = speed > 1024 ? (long) (remaining / speed) : (remaining == 0 ? 0 : -1);
        listener.onProgress(new ProgressSnapshot(
                totalFiles.get(), succeeded.get() + skipped.get(), failed.get(), skipped.get(),
                total, bytes, speed, eta, currentFile.get()));
    }

    private static Path tempPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".part");
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // best effort; the read loop will fail on its own
        }
    }

    private void sleepBackoff(int attempt) {
        long delay = RETRY_BASE_DELAY_MS << Math.min(6, attempt - 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("已取消");
        }
    }
}
