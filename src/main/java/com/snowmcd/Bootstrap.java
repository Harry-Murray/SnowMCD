package com.snowmcd;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Entry point of the cross-platform fat jar.
 *
 * <p>The jar bundles the JavaFX modules for Windows, macOS and Linux. This
 * launcher picks the matching platform, extracts those modules to a per-user
 * cache and restarts itself as a modular application, so the rest of the code
 * can keep using clean JPMS modules regardless of the host OS.</p>
 *
 * <p>It is only used when the jar is started with {@code java -jar}; the
 * jpackage distributions keep launching {@code com.snowmcd.Main} directly.</p>
 */
public final class Bootstrap {

    private static final String BUNDLE_VERSION = "1.0.0";
    private static final String LIB_PREFIX = "libs/";

    private Bootstrap() {
    }

    public static void main(String[] args) {
        try {
            launch(args);
        } catch (Throwable error) {
            System.err.println("SnowMCD 启动失败: " + error);
            error.printStackTrace();
            System.exit(1);
        }
    }

    private static void launch(String[] args) throws Exception {
        Path self = locateSelf();
        String platform = detectPlatform();
        Path libraryDir = extractLibraries(self, platform);

        List<Path> jars = new ArrayList<>();
        jars.add(self);
        try (var stream = Files.list(libraryDir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .forEach(jars::add);
        }
        if (jars.size() < 5) {
            throw new IOException("平台运行库不完整: " + libraryDir);
        }

        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        if (!Files.isRegularFile(java)) {
            throw new IOException("找不到 Java 可执行文件: " + java);
        }

        String modulePath = jars.stream()
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));

        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("--enable-native-access=javafx.graphics");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            command.add("-Xdock:name=SnowMCD");
        }
        command.add("--module-path");
        command.add(modulePath);
        command.add("-m");
        command.add("com.snowmcd/com.snowmcd.Main");
        command.addAll(Arrays.asList(args));

        System.out.println("SnowMCD: " + platform + " · " + libraryDir);
        Process process = new ProcessBuilder(command).inheritIO().start();
        System.exit(process.waitFor());
    }

    private static Path locateSelf() throws URISyntaxException {
        var source = Bootstrap.class.getProtectionDomain().getCodeSource();
        if (source == null) {
            throw new IllegalStateException("无法确定 jar 路径，请使用 java -jar SnowMCD-cross.jar 启动");
        }
        Path path = Path.of(source.getLocation().toURI()).toAbsolutePath();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("当前不是以 jar 方式启动: " + path);
        }
        return path;
    }

    private static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win")) {
            return "win";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return arm ? "mac-aarch64" : "mac";
        }
        return arm ? "linux-aarch64" : "linux";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Path extractLibraries(Path self, String platform) throws IOException {
        Path targetDir = Path.of(System.getProperty("user.home"), ".snowmcd", "libs", BUNDLE_VERSION, platform);
        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            targetDir = Files.createTempDirectory("snowmcd-libs-");
        }

        try (JarFile jar = new JarFile(self.toFile())) {
            String platformPrefix = LIB_PREFIX + platform + "/";
            List<JarEntry> entries = jar.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().endsWith(".jar"))
                    .filter(entry -> entry.getName().startsWith(LIB_PREFIX + "common/")
                            || entry.getName().startsWith(platformPrefix))
                    .toList();
            if (entries.isEmpty()) {
                throw new IOException("jar 内没有 " + platform + " 的平台运行库");
            }
            for (JarEntry entry : entries) {
                String fileName = Path.of(entry.getName()).getFileName().toString();
                Path target = targetDir.resolve(fileName);
                if (Files.isRegularFile(target) && entry.getSize() > 0 && Files.size(target) == entry.getSize()) {
                    continue;
                }
                Path temp = Files.createTempFile(targetDir, fileName, ".part");
                try (InputStream in = jar.getInputStream(entry)) {
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                moveReplacing(temp, target);
            }
        }
        return targetDir;
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
