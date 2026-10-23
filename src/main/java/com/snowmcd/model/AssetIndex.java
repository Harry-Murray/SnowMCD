package com.snowmcd.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Parsed asset index ({@code assets/indexes/<id>.json}). */
public record AssetIndex(String id, boolean virtual, boolean mapToResources,
                         Map<String, AssetObject> objects) {

    public static AssetIndex fromJson(JsonObject root, String id) {
        Map<String, AssetObject> objects = new LinkedHashMap<>();
        if (root != null && root.has("objects") && root.get("objects").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("objects").entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject object = entry.getValue().getAsJsonObject();
                String hash = Json.str(object, "hash");
                if (hash != null) {
                    objects.put(entry.getKey(), new AssetObject(hash, Json.lng(object, "size", -1)));
                }
            }
        }
        return new AssetIndex(id,
                Json.bool(root, "virtual"),
                Json.bool(root, "map_to_resources"),
                Map.copyOf(objects));
    }
}
