package com.snowmcd.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** The subset of {@code versions/<id>/<id>.json} needed to install a version. */
public record VersionJson(String id, String type, String mainClass, String assets,
                          AssetIndexRef assetIndex, ArtifactDownload client,
                          List<Library> libraries) {

    public static VersionJson fromJson(JsonObject root) {
        if (root == null) {
            throw new IllegalArgumentException("版本 JSON 为空");
        }
        AssetIndexRef assetIndex = root.has("assetIndex") && root.get("assetIndex").isJsonObject()
                ? AssetIndexRef.fromJson(root.getAsJsonObject("assetIndex"))
                : null;
        ArtifactDownload client = null;
        if (root.has("downloads") && root.get("downloads").isJsonObject()) {
            JsonObject downloads = root.getAsJsonObject("downloads");
            if (downloads.has("client") && downloads.get("client").isJsonObject()) {
                client = ArtifactDownload.fromJson(downloads.getAsJsonObject("client"));
            }
        }
        List<Library> libraries = new ArrayList<>();
        if (root.has("libraries") && root.get("libraries").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("libraries")) {
                if (element.isJsonObject()) {
                    libraries.add(Library.fromJson(element.getAsJsonObject()));
                }
            }
        }
        return new VersionJson(
                Json.str(root, "id"),
                Json.str(root, "type"),
                Json.str(root, "mainClass"),
                Json.str(root, "assets"),
                assetIndex,
                client,
                List.copyOf(libraries));
    }
}
