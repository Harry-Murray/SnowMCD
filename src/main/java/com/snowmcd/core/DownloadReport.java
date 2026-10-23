package com.snowmcd.core;

import java.nio.file.Path;
import java.util.List;

/** Result of a complete (or retried) download run. */
public record DownloadReport(String versionId, Path gameDir, boolean cancelled,
                             int totalFiles, int succeededFiles, int skippedFiles, int failedFiles,
                             long downloadedBytes, long elapsedMillis, List<FailedDownload> failures,
                             int nativesExtracted, int virtualAssetsLinked) {

    public DownloadReport {
        failures = List.copyOf(failures);
    }

    public int completedFiles() {
        return succeededFiles + skippedFiles;
    }

    public DownloadReport withNatives(int count) {
        return new DownloadReport(versionId, gameDir, cancelled, totalFiles, succeededFiles, skippedFiles,
                failedFiles, downloadedBytes, elapsedMillis, failures, count, virtualAssetsLinked);
    }

    public DownloadReport withVirtualAssets(int count) {
        return new DownloadReport(versionId, gameDir, cancelled, totalFiles, succeededFiles, skippedFiles,
                failedFiles, downloadedBytes, elapsedMillis, failures, nativesExtracted, count);
    }
}
