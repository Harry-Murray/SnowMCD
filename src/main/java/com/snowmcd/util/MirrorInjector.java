package com.snowmcd.util;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Rewrites official Mojang download URLs to the BMCLAPI mirror.
 *
 * <p>The mirror deliberately keeps the original path (including {@code /v1/...} segments),
 * so rewriting is a host/prefix substitution rather than a rebuild.</p>
 */
public final class MirrorInjector {

    public static final String MIRROR_BASE = "https://bmclapi2.bangbang93.com";
    public static final String VERSION_MANIFEST_URL = MIRROR_BASE + "/mc/game/version_manifest_v2.json";

    /** Official host -> mirror prefix. */
    private static final Map<String, String> HOST_PREFIXES = Map.of(
            "piston-meta.mojang.com", MIRROR_BASE,
            "piston-data.mojang.com", MIRROR_BASE,
            "launchermeta.mojang.com", MIRROR_BASE,
            "launcher.mojang.com", MIRROR_BASE,
            "libraries.minecraft.net", MIRROR_BASE + "/maven",
            "resources.download.minecraft.net", MIRROR_BASE + "/assets"
    );

    private MirrorInjector() {
    }

    /**
     * Rewrite one official URL. Unknown hosts (and already-mirrored URLs) are returned unchanged.
     */
    public static String rewrite(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return url;
        }
        String host = uri.getHost();
        if (host == null) {
            return url;
        }
        String prefix = HOST_PREFIXES.get(host.toLowerCase(Locale.ROOT));
        if (prefix == null) {
            return url;
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        // resources.download.minecraft.net/{aa}/{hash} -> bmclapi.../assets/{aa}/{hash}
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return prefix + path + query;
    }

    public static String versionJsonUrl(String versionId) {
        return MIRROR_BASE + "/version/" + encode(versionId) + "/json";
    }

    public static String clientJarUrl(String versionId) {
        return MIRROR_BASE + "/version/" + encode(versionId) + "/client";
    }

    public static String assetObjectUrl(String hash) {
        if (hash == null || hash.length() < 2) {
            return MIRROR_BASE + "/assets/" + hash;
        }
        return MIRROR_BASE + "/assets/" + hash.substring(0, 2) + "/" + hash;
    }

    /** BMCLAPI also exposes raw asset indexes for some versions; used only as a fallback. */
    public static String assetIndexUrl(String indexId) {
        return MIRROR_BASE + "/assets/indexes/" + encode(indexId) + ".json";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
