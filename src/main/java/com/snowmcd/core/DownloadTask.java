package com.snowmcd.core;

import java.nio.file.Path;
import java.util.Objects;

/** One file to download. */
public record DownloadTask(String url, Path target, String sha1, long size,
                           Category category, String label) {

    public enum Category {
        VERSION_JSON, CLIENT, LIBRARY, NATIVE, ASSET_INDEX, ASSET
    }

    public DownloadTask {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(target, "target");
        category = category == null ? Category.LIBRARY : category;
        label = label == null ? target.getFileName().toString() : label;
    }

    public String fileName() {
        Path name = target.getFileName();
        return name == null ? target.toString() : name.toString();
    }

    public boolean hasHash() {
        return sha1 != null && !sha1.isBlank();
    }
}
