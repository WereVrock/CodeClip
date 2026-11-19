package wv.codeclip.config;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

public final class CodeClipBuildInfo {

    private static final String BUILD_INFO_PATH =
            "C:\\Users\\SC\\Documents\\NetBeansProjects\\CodeClip\\src\\main\\java\\buildinfo.properties";

    private static String cachedInfo = null;

    private CodeClipBuildInfo() {}

    /**
     * This is the single source of truth for codeclip's own build info. Title or any other buildinfo display that is supposed to display the codeclips own buildinfo should always use this.
     * This should be called at least once at the start
     * @return 
     */

public static String getBuildInfo() {
        // Never keep "unknown" cached: if the file turns up later (or the
        // path was wrong at first call), the next call must retry.
        if (cachedInfo == null || "unknown".equals(cachedInfo)) {
            cachedInfo = load();
        }
        return cachedInfo;
    }

private static String load() {
        for (File candidate : candidateFiles()) {
            if (candidate != null && candidate.isFile()) {
                String info = readBuildInfo(candidate);
                if (info != null) {
                    return info;
                }
            }
        }
        return "unknown";
    }

/** Places where CodeClip's OWN buildinfo.properties may live, in priority order. */
private static java.util.List<File> candidateFiles() {
        java.util.List<File> list = new java.util.ArrayList<>();

        String override = System.getProperty("codeclip.buildinfo");
        if (override == null || override.isBlank()) {
            override = System.getenv("CODECLIP_BUILDINFO");
        }
        if (override != null && !override.isBlank()) {
            list.add(new File(override));
        }

        list.add(new File(BUILD_INFO_PATH));
        list.add(new File("C:\\code and apps\\JAVA\\codeclip\\CodeClip\\src\\main\\java\\buildinfo.properties"));

        // Relative to where CodeClip itself runs from (target/classes or the jar).
        // The working directory is deliberately NOT searched: it could belong
        // to the project being edited, and that build number must never show up here.
        try {
            java.security.CodeSource cs = CodeClipBuildInfo.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                File dir = new File(cs.getLocation().toURI());
                for (int i = 0; i < 8 && dir != null; i++) {
                    list.add(new File(dir, "src" + File.separator + "main"
                            + File.separator + "java" + File.separator + "buildinfo.properties"));
                    dir = dir.getParentFile();
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

/** Reads BUILD_NO / LAST_UPDATED by hand (UTF-8). Returns null if neither key is present. */
private static String readBuildInfo(File file) {
        try {
            String buildNo = null;
            String timestamp = null;
            for (String line : java.nio.file.Files.readAllLines(
                    file.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                // Older versions wrote this file via Properties.store, which escapes ':' and '='.
                String value = line.substring(eq + 1).trim()
                        .replace("\\:", ":").replace("\\=", "=");
                if (key.equals("BUILD_NO")) {
                    buildNo = value;
                } else if (key.equals("LAST_UPDATED")) {
                    timestamp = value;
                }
            }
            if (buildNo == null && timestamp == null) {
                return null;
            }
            return "#" + (buildNo != null ? buildNo : "?")
                    + " --- " + (timestamp != null ? timestamp : "?");
        } catch (Exception e) {
            return null;
        }
    }

}