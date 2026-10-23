package com.snowmcd.model;

import com.google.gson.JsonObject;

/** One downloadable artifact entry from a version JSON ({@code downloads.*}). */
public record ArtifactDownload(String url, String sha1, long size, String path) {

    public static ArtifactDownload fromJson(JsonObject object) {
        if (object == null) {
            return null;
        }
        return new ArtifactDownload(
                Json.str(object, "url"),
                Json.str(object, "sha1"),
                Json.lng(object, "size", -1),
                Json.str(object, "path"));
    }

    public boolean hasHash() {
        return sha1 != null && !sha1.isBlank();
    }
}
