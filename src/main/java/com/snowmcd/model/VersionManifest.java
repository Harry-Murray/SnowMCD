package com.snowmcd.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Parsed {@code version_manifest_v2.json}. */
public record VersionManifest(String latestRelease, String latestSnapshot, List<VersionInfo> versions) {

    public static VersionManifest fromJson(JsonObject root) {
        String release = null;
        String snapshot = null;
        if (root != null && root.has("latest") && root.get("latest").isJsonObject()) {
            JsonObject latest = root.getAsJsonObject("latest");
            release = Json.str(latest, "release");
            snapshot = Json.str(latest, "snapshot");
        }
        List<VersionInfo> list = new ArrayList<>();
        if (root != null && root.has("versions") && root.get("versions").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("versions")) {
                if (element.isJsonObject()) {
                    list.add(VersionInfo.fromJson(element.getAsJsonObject()));
                }
            }
        }
        return new VersionManifest(release, snapshot, List.copyOf(list));
    }

    public Optional<VersionInfo> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return versions.stream().filter(v -> id.equals(v.id())).findFirst();
    }
}
