package com.snowmcd.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.snowmcd.model.AssetIndex;
import com.snowmcd.model.AssetIndexRef;
import com.snowmcd.model.AssetObject;
import com.snowmcd.model.ArtifactDownload;
import com.snowmcd.model.Library;
import com.snowmcd.model.VersionInfo;
import com.snowmcd.model.VersionJson;
import com.snowmcd.model.VersionManifest;
import com.snowmcd.util.AppPaths;
import com.snowmcd.util.HashUtils;
import com.snowmcd.util.Log;
import com.snowmcd.util.MirrorInjector;
import com.snowmcd.util.OsInfo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates a complete Minecraft installation into a standard {@code .minecraft}
 * layout: version JSON + client jar, libraries, natives and all asset objects.
 */
public final class MinecraftDownloader {

    private static final int METADATA_ATTEMPTS = 3;
    private static final long MANIFEST_TTL_MS = 10L * 60 * 1000;
    private static final Map<String, Boolean> NO_FEATURES = Map.of();

    private final HttpClient http;
    private final Path cacheDir = AppPaths.cacheDir();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private volatile DownloadManager active;

    public MinecraftDownloader() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    // ------------------------------------------------------------------ manifest

    public VersionManifest loadManifest(boolean forceRefresh, ProgressListener listener) throws IOException {
        Path cache = cacheDir.resolve("version_manifest_v2.json");
        boolean fresh = !forceRefresh
                && Files.isRegularFile(cache)
                && System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < MANIFEST_TTL_MS;
        if (fresh) {
            try {
                return VersionManifest.fromJson(parseJson(Files.readAllBytes(cache)));
            } catch (IOException | RuntimeException e) {
                Log.warning("缓存版本清单不可用，重新下载: %s", e.getMessage());
            }
        }
        listener.onStage("从 BMCLAPI 获取版本清单");
        try {
            byte[] bytes = fetchBytes(MirrorInjector.VERSION_MANIFEST_URL, METADATA_ATTEMPTS);
            VersionManifest manifest = VersionManifest.fromJson(parseJson(bytes));
            writeAtomically(cache, bytes);
            return manifest;
        } catch (IOException e) {
            if (Files.isRegularFile(cache)) {
                Log.warning("版本清单刷新失败，回退到本地缓存: %s", e.getMessage());
                return VersionManifest.fromJson(parseJson(Files.readAllBytes(cache)));
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ download

    public DownloadReport download(String versionId, Path gameDir, int concurrency, int maxAttempts,
                                   ProgressListener listener) throws IOException {
        cancelRequested.set(false);
        Path mc = gameDir.toAbsolutePath().normalize();
        listener.onStage("读取版本清单");
        VersionManifest manifest = loadManifest(false, listener);
        VersionInfo info = manifest.find(versionId)
                .orElseThrow(() -> new IOException("版本清单中找不到版本 " + versionId));

        Path versionDir = mc.resolve("versions").resolve(versionId);
        Path librariesDir = mc.resolve("libraries");
        Path assetsDir = mc.resolve("assets");
        Path indexesDir = assetsDir.resolve("indexes");
        Path objectsDir = assetsDir.resolve("objects");
        createDirectories(versionDir, librariesDir, indexesDir, objectsDir);

        listener.onStage("获取版本元数据 " + versionId);
        Path versionJsonPath = versionDir.resolve(versionId + ".json");
        List<String> versionUrls = new ArrayList<>();
        if (info.url() != null && !info.url().isBlank()) {
            versionUrls.add(MirrorInjector.rewrite(info.url()));
        }
        versionUrls.add(MirrorInjector.versionJsonUrl(versionId));
        byte[] versionBytes = ensureFile(versionJsonPath, info.sha1(), -1L,
                new ArrayList<>(new LinkedHashSet<>(versionUrls)), maxAttempts);
        VersionJson version = VersionJson.fromJson(parseJson(versionBytes));

        listener.onStage("解析资源索引");
        AssetIndexRef indexRef = version.assetIndex();
        AssetIndex assetIndex = null;
        Path indexFile = null;
        if (indexRef != null && indexRef.id() != null && !indexRef.id().isBlank()) {
            indexFile = indexesDir.resolve(indexRef.id() + ".json");
            List<String> indexUrls = new ArrayList<>();
            if (indexRef.url() != null && !indexRef.url().isBlank()) {
                indexUrls.add(MirrorInjector.rewrite(indexRef.url()));
            }
            indexUrls.add(MirrorInjector.assetIndexUrl(indexRef.id()));
            byte[] indexBytes = ensureFile(indexFile, indexRef.sha1(), indexRef.size(),
                    new ArrayList<>(new LinkedHashSet<>(indexUrls)), maxAttempts);
            assetIndex = AssetIndex.fromJson(parseJson(indexBytes), indexRef.id());
            listener.onLog("资源索引 " + assetIndex.id() + " 包含 " + assetIndex.objects().size() + " 个资源对象");
        } else if (version.assets() != null && !version.assets().isBlank()) {
            try {
                indexFile = indexesDir.resolve(version.assets() + ".json");
                byte[] indexBytes = ensureFile(indexFile, null, -1L,
                        List.of(MirrorInjector.assetIndexUrl(version.assets())), 2);
                assetIndex = AssetIndex.fromJson(parseJson(indexBytes), version.assets());
                listener.onLog("旧版资源索引 " + version.assets() + " 包含 " + assetIndex.objects().size() + " 个资源对象");
            } catch (IOException e) {
                listener.onLog("旧版资源索引不可用，仅安装核心文件: " + e.getMessage());
            }
        }

        OsInfo os = OsInfo.current();
        List<DownloadTask> tasks = new ArrayList<>();
        Set<Path> seenTargets = new HashSet<>();
        List<NativeJar> nativeJars = new ArrayList<>();

        // client jar ---------------------------------------------------------
        ArtifactDownload client = version.client();
        String clientSha1 = client == null ? null : client.sha1();
        long clientSize = client == null ? -1L : client.size();
        addTask(tasks, seenTargets, new DownloadTask(
                MirrorInjector.clientJarUrl(versionId),
                versionDir.resolve(versionId + ".jar"),
                clientSha1, clientSize, DownloadTask.Category.CLIENT, versionId + ".jar"));

        // libraries ----------------------------------------------------------
        for (Library library : version.libraries()) {
            if (!library.isAllowed(os, NO_FEATURES)) {
                continue;
            }
            ArtifactDownload artifact = library.artifact();
            boolean modernNative = library.isModernNative(os);
            if (artifact != null && artifact.path() != null) {
                String url = artifact.url() != null && !artifact.url().isBlank()
                        ? MirrorInjector.rewrite(artifact.url())
                        : (library.baseUrl() != null ? joinUrl(MirrorInjector.rewrite(library.baseUrl()), artifact.path()) : null);
                if (url != null) {
                    addTask(tasks, seenTargets, new DownloadTask(url,
                            librariesDir.resolve(artifact.path()), artifact.sha1(), artifact.size(),
                            modernNative ? DownloadTask.Category.NATIVE : DownloadTask.Category.LIBRARY,
                            artifact.path()));
                }
            } else if (library.baseUrl() != null && !library.baseUrl().isBlank()) {
                String relative = library.mavenPath();
                if (relative != null) {
                    addTask(tasks, seenTargets, new DownloadTask(
                            joinUrl(MirrorInjector.rewrite(library.baseUrl()), relative),
                            librariesDir.resolve(relative), null, -1L,
                            DownloadTask.Category.LIBRARY, relative));
                }
            }

            // old style natives (natives map + classifiers)
            ArtifactDownload nativeArtifact = library.nativeArtifact(os);
            if (nativeArtifact != null && nativeArtifact.path() != null) {
                Path target = librariesDir.resolve(nativeArtifact.path());
                addTask(tasks, seenTargets, new DownloadTask(
                        nativeArtifact.url() != null
                                ? MirrorInjector.rewrite(nativeArtifact.url())
                                : joinUrl(MirrorInjector.rewrite(library.baseUrl()), nativeArtifact.path()),
                        target, nativeArtifact.sha1(), nativeArtifact.size(),
                        DownloadTask.Category.NATIVE, nativeArtifact.path()));
                nativeJars.add(new NativeJar(target, library.extractExcludes()));
            }

            // new style natives (natives-* classifier on the coordinates)
            if (modernNative && artifact != null && artifact.path() != null) {
                nativeJars.add(new NativeJar(librariesDir.resolve(artifact.path()),
                        library.extractExcludes()));
            }
        }

        // asset index + objects ---------------------------------------------
        if (assetIndex != null) {
            if (indexRef != null && indexRef.url() != null && indexFile != null) {
                addTask(tasks, seenTargets, new DownloadTask(
                        MirrorInjector.rewrite(indexRef.url()), indexFile, indexRef.sha1(), indexRef.size(),
                        DownloadTask.Category.ASSET_INDEX,
                        "assets/indexes/" + indexRef.id() + ".json"));
            }
            for (Map.Entry<String, AssetObject> entry : assetIndex.objects().entrySet()) {
                AssetObject object = entry.getValue();
                String hash = object.hash();
                Path target = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
                addTask(tasks, seenTargets, new DownloadTask(
                        MirrorInjector.assetObjectUrl(hash), target, hash, object.size(),
                        DownloadTask.Category.ASSET,
                        "assets/objects/" + hash.substring(0, 2) + "/" + hash));
            }
        }

        long totalBytes = tasks.stream().mapToLong(task -> Math.max(0L, task.size())).sum();
        listener.onLog("安装计划: " + tasks.size() + " 个文件, " + formatBytes(totalBytes));
        listener.onStage("下载 " + tasks.size() + " 个文件（并发 " + concurrency + "）");

        DownloadManager manager = new DownloadManager(concurrency, maxAttempts);
        active = manager;
        DownloadReport report;
        try {
            report = manager.download(versionId, mc, tasks, listener);
        } finally {
            active = null;
        }

        if (!report.cancelled()) {
            listener.onStage("解压原生库");
            int natives = extractNatives(nativeJars, versionDir.resolve("natives"));
            int virtualAssets = 0;
            if (assetIndex != null && (assetIndex.virtual() || assetIndex.mapToResources())
                    && !assetIndex.objects().isEmpty()) {
                listener.onStage("生成旧版虚拟资源目录");
                try {
                    virtualAssets = installVirtualAssets(mc, assetIndex, objectsDir);
                } catch (IOException e) {
                    Log.warning("生成虚拟资源目录失败: %s", e.getMessage());
                }
            }
            report = report.withNatives(natives).withVirtualAssets(virtualAssets);
        }
        return report;
    }

    /** Retry only the files that failed in a previous run. */
    public DownloadReport retry(DownloadReport previous, Path gameDir, String versionId,
                                int concurrency, int maxAttempts, ProgressListener listener) throws IOException {
        Map<Path, DownloadTask> unique = new LinkedHashMap<>();
        for (FailedDownload failure : previous.failures()) {
            unique.putIfAbsent(failure.task().target().toAbsolutePath().normalize(), failure.task());
        }
        if (unique.isEmpty()) {
            return previous;
        }
        Path mc = gameDir.toAbsolutePath().normalize();
        DownloadManager manager = new DownloadManager(concurrency, maxAttempts);
        active = manager;
        try {
            DownloadReport retried = manager.download(versionId, mc, List.copyOf(unique.values()), listener);
            int natives = 0;
            if (!retried.cancelled()) {
                List<NativeJar> nativeJars = unique.values().stream()
                        .filter(task -> task.category() == DownloadTask.Category.NATIVE)
                        .map(task -> new NativeJar(task.target(), List.of()))
                        .toList();
                natives = extractNatives(nativeJars,
                        mc.resolve("versions").resolve(versionId).resolve("natives"));
            }
            return retried.withNatives(natives);
        } finally {
            active = null;
        }
    }

    public void cancel() {
        cancelRequested.set(true);
        DownloadManager manager = active;
        if (manager != null) {
            manager.cancel();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void addTask(List<DownloadTask> tasks, Set<Path> seen, DownloadTask task) {
        Path normalized = task.target().toAbsolutePath().normalize();
        if (seen.add(normalized)) {
            tasks.add(task);
        }
    }

    private int extractNatives(List<NativeJar> nativeJars, Path destination) {
        int extractedJars = 0;
        for (NativeJar nativeJar : nativeJars) {
            if (!Files.isRegularFile(nativeJar.path())) {
                continue;
            }
            try {
                int files = NativeExtractor.extract(nativeJar.path(), destination, nativeJar.excludes());
                if (files > 0) {
                    extractedJars++;
                }
                Log.info("原生库 %s: 解压 %d 个文件", nativeJar.path().getFileName(), files);
            } catch (IOException e) {
                Log.warning("解压原生库失败 %s: %s", nativeJar.path(), e.getMessage());
            }
        }
        return extractedJars;
    }

    private int installVirtualAssets(Path mc, AssetIndex assetIndex, Path objectsDir) throws IOException {
        Path base = assetIndex.mapToResources()
                ? mc.resolve("resources")
                : mc.resolve("assets").resolve("virtual").resolve(assetIndex.id());
        Path normalizedBase = base.toAbsolutePath().normalize();
        int copied = 0;
        for (Map.Entry<String, AssetObject> entry : assetIndex.objects().entrySet()) {
            AssetObject object = entry.getValue();
            if (object.hash() == null || object.hash().length() < 2) {
                continue;
            }
            Path source = objectsDir.resolve(object.hash().substring(0, 2)).resolve(object.hash());
            if (!Files.isRegularFile(source)) {
                continue;
            }
            Path destination = normalizedBase.resolve(entry.getKey()).normalize();
            if (!destination.startsWith(normalizedBase)) {
                continue;
            }
            try {
                if (Files.isRegularFile(destination)
                        && (object.size() <= 0 || Files.size(destination) == object.size())) {
                    continue;
                }
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            } catch (IOException e) {
                Log.warning("复制虚拟资源失败 %s: %s", entry.getKey(), e.getMessage());
            }
        }
        return copied;
    }

    private byte[] ensureFile(Path target, String expectedSha1, long expectedSize,
                              List<String> urls, int attempts) throws IOException {
        if (HashUtils.matches(target, expectedSha1, expectedSize)) {
            return Files.readAllBytes(target);
        }
        IOException lastError = null;
        for (String url : urls) {
            if (url == null || url.isBlank()) {
                continue;
            }
            checkCancelled();
            try {
                byte[] bytes = fetchBytes(url, attempts);
                if (expectedSha1 != null && !expectedSha1.isBlank()
                        && !expectedSha1.equalsIgnoreCase(HashUtils.sha1Hex(bytes))) {
                    throw new IOException("SHA-1 不符");
                }
                if (expectedSize > 0 && bytes.length != expectedSize) {
                    throw new IOException("大小不符: 期望 " + expectedSize + "，实际 " + bytes.length);
                }
                writeAtomically(target, bytes);
                return bytes;
            } catch (IOException e) {
                lastError = e;
                Log.warning("获取元数据失败 %s: %s", url, e.getMessage());
            }
        }
        throw lastError != null ? lastError : new IOException("无法下载 " + target.getFileName());
    }

    private byte[] fetchBytes(String url, int attempts) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            checkCancelled();
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("User-Agent", DownloadManager.USER_AGENT)
                        .GET()
                        .build();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("已取消", e);
            } catch (IOException e) {
                lastError = e;
                Log.warning("请求失败(%d/%d) %s: %s", attempt, attempts, url, e.getMessage());
                if (attempt < attempts) {
                    try {
                        Thread.sleep(1000L << (attempt - 1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("已取消", interrupted);
                    }
                }
            }
        }
        throw lastError != null ? lastError : new IOException("无法下载 " + url);
    }

    private void checkCancelled() throws IOException {
        if (cancelRequested.get() || Thread.currentThread().isInterrupted()) {
            throw new IOException("已取消");
        }
    }

    private static void createDirectories(Path... dirs) throws IOException {
        for (Path dir : dirs) {
            Files.createDirectories(dir);
        }
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temp, bytes);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static JsonObject parseJson(byte[] bytes) throws IOException {
        try {
            JsonElement element = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) {
                throw new IOException("JSON 根节点不是对象");
            }
            return element.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IOException("JSON 解析失败", e);
        }
    }

    private static String joinUrl(String base, String relative) {
        if (base == null) {
            return relative;
        }
        return base.endsWith("/") ? base + relative : base + "/" + relative;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.2f GB", mb / 1024.0);
    }

    private record NativeJar(Path path, List<String> excludes) {
        private NativeJar {
            excludes = List.copyOf(excludes);
        }
    }
}
