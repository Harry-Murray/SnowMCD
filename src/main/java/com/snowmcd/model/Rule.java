package com.snowmcd.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.snowmcd.util.OsInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** One rule of a library's {@code rules} array. */
public record Rule(String action, String osName, String osArch, String osVersion,
                   Map<String, Boolean> features) {

    public static Rule fromJson(JsonObject object) {
        String osName = null;
        String osArch = null;
        String osVersion = null;
        if (object != null && object.has("os") && object.get("os").isJsonObject()) {
            JsonObject os = object.getAsJsonObject("os");
            osName = Json.str(os, "name");
            osArch = Json.str(os, "arch");
            osVersion = Json.str(os, "version");
        }
        Map<String, Boolean> features = new LinkedHashMap<>();
        if (object != null && object.has("features") && object.get("features").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("features").entrySet()) {
                try {
                    features.put(entry.getKey(), entry.getValue().getAsBoolean());
                } catch (RuntimeException ignored) {
                    features.put(entry.getKey(), false);
                }
            }
        }
        return new Rule(Json.str(object, "action"), osName, osArch, osVersion, Map.copyOf(features));
    }

    public boolean matches(OsInfo os, Map<String, Boolean> actualFeatures) {
        if (osName != null && !osName.equalsIgnoreCase(os.name())) {
            return false;
        }
        if (osArch != null && !archMatches(osArch, os)) {
            return false;
        }
        if (osVersion != null && !Pattern.compile(osVersion).matcher(os.version()).find()) {
            return false;
        }
        for (Map.Entry<String, Boolean> feature : features.entrySet()) {
            boolean actual = actualFeatures.getOrDefault(feature.getKey(), false);
            if (actual != feature.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static boolean archMatches(String ruleArch, OsInfo os) {
        String arch = ruleArch.toLowerCase();
        return switch (arch) {
            case "x86" -> "x86".equals(os.arch());
            case "x86_64", "amd64", "x64" -> "x86_64".equals(os.arch());
            case "aarch64", "arm64" -> "aarch64".equals(os.arch());
            default -> arch.equals(os.arch());
        };
    }
}
