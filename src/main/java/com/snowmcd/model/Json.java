package com.snowmcd.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Small helpers for defensive JSON access. */
final class Json {

    private Json() {
    }

    static String str(JsonObject object, String key) {
        if (object == null || !object.has(key)) {
            return null;
        }
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    static long lng(JsonObject object, String key, long fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        JsonElement element = object.get(key);
        try {
            return element == null || element.isJsonNull() ? fallback : element.getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static boolean bool(JsonObject object, String key) {
        if (object == null || !object.has(key)) {
            return false;
        }
        JsonElement element = object.get(key);
        try {
            return element != null && !element.isJsonNull() && element.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
