package com.snowmcd.core;

/** What is about to be downloaded. */
public record DownloadPlan(int totalFiles, long totalBytes) {
}
