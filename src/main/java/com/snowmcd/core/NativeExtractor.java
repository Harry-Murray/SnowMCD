package com.snowmcd.core;

import com.snowmcd.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Extracts LWJGL natives (and similar native jars) into {@code versions/<id>/natives}. */
public final class NativeExtractor {

    private NativeExtractor() {
    }

    public static int extract(Path jar, Path destination, List<String> excludes) throws IOException {
        int extracted = 0;
        Files.createDirectories(destination);
        Path normalDestination = destination.toAbsolutePath().normalize();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                if (isExcluded(name, excludes)) {
                    continue;
                }
                Path output = normalDestination.resolve(name).normalize();
                if (!output.startsWith(normalDestination)) {
                    Log.warning("跳过可疑的原生库路径: %s", name);
                    continue;
                }
                Path parent = output.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING);
                }
                extracted++;
            }
        }
        return extracted;
    }

    private static boolean isExcluded(String name, List<String> excludes) {
        if (name.startsWith("META-INF/") || name.startsWith("META-INF")) {
            return true;
        }
        for (String exclude : excludes) {
            String pattern = exclude.replace('\\', '/');
            if (!pattern.isEmpty() && name.startsWith(pattern)) {
                return true;
            }
        }
        return false;
    }
}
