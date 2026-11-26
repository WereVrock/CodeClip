package wv.codeclip.patch;

import java.util.Map;

/**
 * What one successfully applied @@PATCH block changed: for every touched file
 * (keyed by repo path) its content before and after the block. Used to revert
 * that single patch later from the Versions tab.
 */
public record PatchRecord(String title, Map<String, String> before, Map<String, String> after) {
}