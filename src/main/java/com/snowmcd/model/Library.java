package com.snowmcd.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.snowmcd.util.OsInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One entry of a version JSON's {@code libraries} array. */
public final class Library {

    private final String name;
    private final String baseUrl;
    private final ArtifactDownload artifact;
    private final Map<String, ArtifactDownload> classifiers;
    private final Map<String, String> natives;
    private final List<Rule> rules;
    private final List<String> extractExcludes;

    public Library(String name, String baseUrl, ArtifactDownload artifact,
                   Map<String, ArtifactDownload> classifiers, Map<String, String> natives,
                   List<Rule> rules, List<String> extractExcludes) {
        this.name = name == null ? "" : name;
        this.baseUrl = baseUrl;
        this.artifact = artifact;
        this.classifiers = Map.copyOf(classifiers);
        this.natives = Map.copyOf(natives);
        this.rules = List.copyOf(rules);
        this.extractExcludes = List.copyOf(extractExcludes);
    }

    public static Library fromJson(JsonObject object) {
        String name = Json.str(object, "name");
        String baseUrl = Json.str(object, "url");
        ArtifactDownload artifact = null;
        Map<String, ArtifactDownload> classifiers = new LinkedHashMap<>();
        if (object.has("downloads") && object.get("downloads").isJsonObject()) {
            JsonObject downloads = object.getAsJsonObject("downloads");
            if (downloads.has("artifact") && downloads.get("artifact").isJsonObject()) {
                artifact = ArtifactDownload.fromJson(downloads.getAsJsonObject("artifact"));
            }
            if (downloads.has("classifiers") && downloads.get("classifiers").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : downloads.getAsJsonObject("classifiers").entrySet()) {
                    if (entry.getValue().isJsonObject()) {
                        classifiers.put(entry.getKey(),
                                ArtifactDownload.fromJson(entry.getValue().getAsJsonObject()));
                    }
                }
            }
        }
        Map<String, String> natives = new LinkedHashMap<>();
        if (object.has("natives") && object.get("natives").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("natives").entrySet()) {
                natives.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        List<Rule> rules = new ArrayList<>();
        if (object.has("rules") && object.get("rules").isJsonArray()) {
            for (JsonElement element : object.getAsJsonArray("rules")) {
                if (element.isJsonObject()) {
                    rules.add(Rule.fromJson(element.getAsJsonObject()));
                }
            }
        }
        List<String> excludes = new ArrayList<>();
        if (object.has("extract") && object.get("extract").isJsonObject()) {
            JsonObject extract = object.getAsJsonObject("extract");
            if (extract.has("exclude") && extract.get("exclude").isJsonArray()) {
                for (JsonElement element : extract.getAsJsonArray("exclude")) {
                    excludes.add(element.getAsString());
                }
            }
        }
        return new Library(name, baseUrl, artifact, classifiers, natives, rules, excludes);
    }

    public String name() {
        return name;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public ArtifactDownload artifact() {
        return artifact;
    }

    public List<String> extractExcludes() {
        return extractExcludes;
    }

    public List<Rule> rules() {
        return rules;
    }

    /**
     * Standard rule evaluation: no rules means allowed; otherwise the last matching rule wins.
     */
    public boolean isAllowed(OsInfo os, Map<String, Boolean> features) {
        if (rules.isEmpty()) {
            return true;
        }
        boolean allowed = false;
        for (Rule rule : rules) {
            if (rule.matches(os, features)) {
                allowed = "allow".equals(rule.action());
            }
        }
        return allowed;
    }

    /** Old-style natives: {@code natives: {"windows": "natives-windows-${arch}"}} + classifiers. */
    public String nativeClassifier(OsInfo os) {
        String template = natives.get(os.name());
        if (template == null) {
            return null;
        }
        return template.replace("${arch}", os.bitness());
    }

    public ArtifactDownload nativeArtifact(OsInfo os) {
        String classifier = nativeClassifier(os);
        return classifier == null ? null : classifiers.get(classifier);
    }

    /** New-style natives: a normal library whose coordinates carry a {@code natives-*} classifier. */
    public boolean isModernNative(OsInfo os) {
        String[] parts = name.split(":");
        if (parts.length < 4) {
            return false;
        }
        String classifier = parts[3];
        int at = classifier.indexOf('@');
        if (at >= 0) {
            classifier = classifier.substring(0, at);
        }
        if (!classifier.startsWith("natives-")) {
            return false;
        }
        String variant = classifier.substring("natives-".length());
        boolean arm64 = "aarch64".equals(os.arch());
        boolean x86 = "x86".equals(os.arch());
        return switch (os.name()) {
            case "windows" -> variant.equals("windows") ? !arm64 && !x86
                    : variant.equals("windows-arm64") && arm64
                    || variant.equals("windows-x86") && x86;
            case "osx" -> variant.equals("macos") ? !arm64
                    : variant.equals("macos-arm64") && arm64;
            case "linux" -> variant.equals("linux") ? !arm64
                    : variant.equals("linux-arm64") && arm64
                    || variant.equals("linux-arm32") && !arm64 && !x86;
            default -> false;
        };
    }

    /** Maven repository path for libraries that only carry a base {@code url}. */
    public String mavenPath() {
        String[] parts = name.split(":");
        if (parts.length < 3) {
            return null;
        }
        String group = parts[0];
        String artifactId = parts[1];
        String version = parts[2];
        String classifier = parts.length >= 4 ? parts[3] : null;
        String extension = "jar";
        if (classifier != null) {
            int at = classifier.indexOf('@');
            if (at >= 0) {
                extension = classifier.substring(at + 1);
                classifier = classifier.substring(0, at);
            }
        } else {
            int at = version.indexOf('@');
            if (at >= 0) {
                extension = version.substring(at + 1);
                version = version.substring(0, at);
            }
        }
        StringBuilder path = new StringBuilder(96);
        path.append(group.replace('.', '/')).append('/')
                .append(artifactId).append('/')
                .append(version).append('/')
                .append(artifactId).append('-').append(version);
        if (classifier != null && !classifier.isBlank()) {
            path.append('-').append(classifier);
        }
        return path.append('.').append(extension).toString();
    }
}
