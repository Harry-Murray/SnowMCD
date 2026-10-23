package com.snowmcd.core;

/** A point-in-time progress sample. */
public record ProgressSnapshot(int totalFiles, int completedFiles, int failedFiles, int skippedFiles,
                               long totalBytes, long downloadedBytes, double bytesPerSecond,
                               long etaSeconds, String currentFile) {

    public int remainingFiles() {
        return Math.max(0, totalFiles - completedFiles - failedFiles);
    }

    public double fraction() {
        if (totalBytes > 0) {
            return Math.min(1.0, (double) downloadedBytes / totalBytes);
        }
        return totalFiles == 0 ? 0.0 : Math.min(1.0, (double) completedFiles / totalFiles);
    }
}
