// CodeMapAnalyzer.java
package wv.codeclip.codemap;

import java.util.List;

/**
 * Strategy interface for producing a one-file "codemap" entry: what a file
 * exposes (exports), what it depends on (imports), and a short human
 * summary — independent of AppMode. CodeMapBuilder picks an implementation
 * per file based on file extension, so adding a new AppMode never requires
 * touching CodeMapBuilder or any existing analyzer; it only requires wiring
 * in a new analyzer if the new mode introduces a genuinely new file type.
 */
public interface CodeMapAnalyzer {

    /** True if this analyzer knows how to summarize a file with this name. */
    boolean supports(String fileName);

    /**
     * Analyzes a single file's source and produces its codemap entry.
     * Must never throw — on any parse trouble, return a best-effort/partial
     * FileSummary rather than propagating an exception, since this runs
     * across every loaded file in one batch operation.
     */
    FileSummary analyze(String fileName, String code);

    record FileSummary(
            String fileName,
            List<String> exports,
            List<String> imports,
            String summary
    ) {}
}