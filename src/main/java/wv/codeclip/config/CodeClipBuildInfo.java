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
        if (cachedInfo == null) {
            cachedInfo = load();
        }
        return cachedInfo;
    }

    private static String load() {
        File file = new File(BUILD_INFO_PATH);
        if (!file.exists()) return "unknown";
        try {
            Properties props = new Properties();
            try (java.io.FileReader reader = new java.io.FileReader(file)) {
                props.load(reader);
            }
            String buildNo   = props.getProperty("BUILD_NO", "?");
            String timestamp = props.getProperty("LAST_UPDATED", "?");
            return "#" + buildNo + " --- " + timestamp;
        } catch (IOException e) {
            return "unknown";
        }
    }
}