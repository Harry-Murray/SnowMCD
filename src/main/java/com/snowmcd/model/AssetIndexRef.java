package com.snowmcd.model;

import com.google.gson.JsonObject;

/** The {@code assetIndex} block of a version JSON. */
public record AssetIndexRef(String id, String sha1, long size, long totalSize, String url) {

    public static AssetIndexRef fromJson(JsonObject object) {
        if (object == null) {
            return null;
        }
        return new AssetIndexRef(
                Json.str(object, "id"),
                Json.str(object, "sha1"),
                Json.lng(object, "size", -1),
                Json.lng(object, "totalSize", -1),
                Json.str(object, "url"));
    }
}
