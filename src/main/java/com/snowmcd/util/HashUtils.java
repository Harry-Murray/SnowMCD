package com.snowmcd.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-1 helpers (Minecraft metadata uses SHA-1 for every artifact). */
public final class HashUtils {

    private static final HexFormat HEX = HexFormat.of();

    private HashUtils() {
    }

    public static String sha1Hex(Path file) throws IOException {
        MessageDigest digest = newSha1();
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HEX.formatHex(digest.digest());
    }

    public static String sha1Hex(byte[] data) {
        return HEX.formatHex(newSha1().digest(data));
    }

    /** True when the file exists, matches the expected size (when known) and hash (when known). */
    public static boolean matches(Path file, String expectedSha1, long expectedSize) {
        try {
            if (!Files.isRegularFile(file)) {
                return false;
            }
            if (expectedSize > 0 && Files.size(file) != expectedSize) {
                return false;
            }
            if (expectedSha1 != null && !expectedSha1.isBlank()) {
                return expectedSha1.equalsIgnoreCase(sha1Hex(file));
            }
            return expectedSize > 0 || Files.size(file) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static MessageDigest newSha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
