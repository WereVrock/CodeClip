package wv.codeclip.protocol.editor;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Validates the full set of entry drafts as a group (for duplicate-id
 * checking) and individual drafts (for format/empty-content checking).
 * Runs on every edit so problems surface immediately, not just on save.
 */
public final class StructuredEditorValidator {

    private static final Pattern VALID_ID = Pattern.compile("^[a-z][a-z0-9-]*$");

    public Map<EntryDraft, EntryValidationState> validateAll(List<EntryDraft> drafts) {
        Map<EntryDraft, EntryValidationState> results = new LinkedHashMap<>();
        Map<String, Integer> idCounts = new HashMap<>();

        for (EntryDraft draft : drafts) {
            String id = draft.getId() == null ? "" : draft.getId();
            idCounts.merge(id, 1, Integer::sum);
        }

        for (EntryDraft draft : drafts) {
            String id = draft.getId() == null ? "" : draft.getId();

            if (id.isBlank()) {
                results.put(draft, EntryValidationState.error("ID cannot be empty"));
                continue;
            }
            if (!VALID_ID.matcher(id).matches()) {
                results.put(draft, EntryValidationState.error("ID must be lowercase letters, digits, hyphens, starting with a letter"));
                continue;
            }
            if (idCounts.get(id) > 1) {
                results.put(draft, EntryValidationState.error("Duplicate ID '" + id + "' — appears " + idCounts.get(id) + " times in this file"));
                continue;
            }
            if (draft.getContent() == null || draft.getContent().isBlank()) {
                results.put(draft, EntryValidationState.error("Content cannot be empty"));
                continue;
            }

            results.put(draft, EntryValidationState.ok());
        }

        return results;
    }

    public boolean allValid(Map<EntryDraft, EntryValidationState> results) {
        return results.values().stream().allMatch(s -> s.level != EntryValidationState.Level.ERROR);
    }
}