package wv.codeclip.codemap.sourceMapper;

/** Farklı kaynak dilleri için ortak arayüz. */
public interface SourceMapper {
    /**
     * @param source  dosyanın tam içeriği (string)
     * @param fileName görüntüleme için dosya adı (yol olabilir)
     * @return AI'ya verilecek kompakt harita (string)
     */
    String map(String source, String fileName);

    /** Desteklenmeyen türler için varsayılan mapper — her zaman "" döner. */
    SourceMapper EMPTY = (source, fileName) -> "";
}