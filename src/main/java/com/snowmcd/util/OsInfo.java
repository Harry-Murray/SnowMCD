package com.snowmcd.util;

import java.util.Locale;

/** Normalised view of the host OS, matching the vocabulary used in Minecraft version JSON. */
public record OsInfo(String name, String arch, String version) {

    public static OsInfo current() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String mapped = osName.contains("win") ? "windows"
                : osName.contains("mac") ? "osx"
                : osName.contains("linux") ? "linux"
                : osName;
        return new OsInfo(mapped, normalizeArch(System.getProperty("os.arch", "")),
                System.getProperty("os.version", ""));
    }

    private static String normalizeArch(String raw) {
        String arch = raw.toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            case "x86", "i386", "i486", "i586", "i686", "ia32" -> "x86";
            default -> arch;
        };
    }

    public boolean is64Bit() {
        return !"x86".equals(arch);
    }

    /** "64" or "32", as used by the ${arch} placeholder of old natives classifiers. */
    public String bitness() {
        return is64Bit() ? "64" : "32";
    }
}
