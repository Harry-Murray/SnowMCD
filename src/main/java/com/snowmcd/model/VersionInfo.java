package com.snowmcd.model;

import com.google.gson.JsonObject;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** One entry of {@code version_manifest_v2.json}. */
public record VersionInfo(String id, String type, String url, String sha1, String releaseTime) {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static VersionInfo fromJson(JsonObject object) {
        return new VersionInfo(
                Json.str(object, "id"),
                Json.str(object, "type"),
                Json.str(object, "url"),
                Json.str(object, "sha1"),
                Json.str(object, "releaseTime"));
    }

    public boolean isRelease() {
        return "release".equalsIgnoreCase(type);
    }

    public boolean isSnapshot() {
        return "snapshot".equalsIgnoreCase(type);
    }

    public String typeLabel() {
        if (isRelease()) {
            return "正式版";
        }
        if (isSnapshot()) {
            return "快照版";
        }
        if ("old_beta".equalsIgnoreCase(type)) {
            return "旧 Beta";
        }
        if ("old_alpha".equalsIgnoreCase(type)) {
            return "旧 Alpha";
        }
        return type == null ? "未知" : type;
    }

    public String releaseTimeText() {
        if (releaseTime == null || releaseTime.isBlank()) {
            return "—";
        }
        try {
            return DISPLAY.format(OffsetDateTime.parse(releaseTime).atZoneSameInstant(ZoneId.systemDefault()));
        } catch (DateTimeParseException e) {
            return releaseTime.length() >= 16 ? releaseTime.substring(0, 16).replace('T', ' ') : releaseTime;
        }
    }

    public long releaseEpochMillis() {
        if (releaseTime == null || releaseTime.isBlank()) {
            return 0L;
        }
        try {
            return OffsetDateTime.parse(releaseTime).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0L;
        }
    }
}
