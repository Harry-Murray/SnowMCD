package com.snowmcd.core;

/** Progress callbacks. All callbacks may arrive on background threads. */
public interface ProgressListener {

    ProgressListener NOOP = new ProgressListener() {
    };

    default void onStage(String message) {
    }

    default void onPlan(DownloadPlan plan) {
    }

    default void onProgress(ProgressSnapshot snapshot) {
    }

    default void onFileFailed(DownloadTask task, String error) {
    }

    default void onLog(String message) {
    }
}
