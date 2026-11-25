package wv.codeclip.codemap.sourceMapper;

import java.util.*;

/** Uzantıya göre uygun mapper'ı seçer. Yeni dil eklemek için buraya register et. */
public final class SourceMappers {
    private static final Map<String, SourceMapper> BY_EXT = new HashMap<>();

    static {
        JsSourceMapper js = new JsSourceMapper();
        BY_EXT.put("js",  js);
        BY_EXT.put("mjs", js);
        BY_EXT.put("cjs", js);
        BY_EXT.put("jsx", js);
        // ileride: "ts", "tsx", "py", "go", "java", ...
    }

    private SourceMappers() {}

    /** Desteklenmeyen/eksik uzantılar için EMPTY döner (null değil). */
    public static SourceMapper forFile(String fileName) {
        if (fileName == null) return SourceMapper.EMPTY;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return SourceMapper.EMPTY;
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return BY_EXT.getOrDefault(ext, SourceMapper.EMPTY);
    }

    /** Harita üretir. Desteklenmeyen türlerde boş string döner. */
    public static String map(String source, String fileName) {
        return forFile(fileName).map(source, fileName);
    }
}