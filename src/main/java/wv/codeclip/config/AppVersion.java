package wv.codeclip.config;

/** Tek kaynak: appversion.properties → her yerde "v2" olarak görünür. */
public final class AppVersion {

    private static final String FALLBACK = "2";
    private static String version = null;

    private AppVersion() {}

    /** Ham değer ("2", "2.1", "v3" gibi). */
    public static synchronized String getVersion() {
        if (version == null) load();
        return version;
    }

    /** Başlık için "v2" biçimi. */
    public static String getVersionLabel() {
        String v = getVersion();
        return (v.startsWith("v") || v.startsWith("V")) ? v : "v" + v;
    }

    private static void load() {
        version=Version.VERSION+"";
    }
}