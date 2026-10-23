package com.snowmcd.core;

/** A download that failed after all retries. */
public record FailedDownload(DownloadTask task, String error) {
}
