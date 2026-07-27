package wv.codeclip.protocol;

import wv.codeclip.protocol.library.ProtocolLibrary;
import java.nio.file.*;
import java.util.*;
import wv.codeclip.protocol.engine.ProtocolApplier;
import wv.codeclip.protocol.engine.ProtocolEngine;
import wv.codeclip.protocol.model.Command;
import wv.codeclip.protocol.model.CommandType;
import wv.codeclip.protocol.model.ProtocolPatchResult;
import wv.codeclip.protocol.model.ProtocolEntry;
import wv.codeclip.protocol.model.ProtocolFile;
import wv.codeclip.protocol.model.ValidationResult;

public class ProtocolTestHarness {

    private static final Path BASE_DIR = Paths.get("test-protocols");
    private static int passCount = 0;
    private static int failCount = 0;

    public static void main(String[] args) throws Exception {
        resetTestDir();

        // --- Original 9 ---
        test("TEST 1: basic accept-all patch, file-scoped", ProtocolTestHarness::testBasicAcceptAll);
        test("TEST 2: missing filename on @@protocol -> parse error", ProtocolTestHarness::testMissingFilename);
        test("TEST 3: master lock rejects everything", ProtocolTestHarness::testMasterLock);
        test("TEST 4: file lock rejects commands for that file only", ProtocolTestHarness::testFileLock);
        test("TEST 5: same id in two different files -> both succeed", ProtocolTestHarness::testCrossFileDuplicateIdAllowed);
        test("TEST 6: duplicate id within same file -> error", ProtocolTestHarness::testSameFileDuplicateIdRejected);
        test("TEST 7: MOVE_AFTER target deleted -> fallback", ProtocolTestHarness::testMoveAfterFallback);
        test("TEST 8: hand-edit then re-validate", ProtocolTestHarness::testHandEditRevalidate);
        test("TEST 9: empty patch", ProtocolTestHarness::testEmptyPatch);

        // --- New edge cases ---
        test("TEST 10: master lock + file lock both engaged", ProtocolTestHarness::testMasterAndFileLockTogether);
        test("TEST 11: DELETE non-existent id -> error", ProtocolTestHarness::testDeleteNonExistentId);
        test("TEST 12: APPENDTO existing id -> content appended", ProtocolTestHarness::testAppendTo);
        test("TEST 13: APPENDTO non-existent id -> error", ProtocolTestHarness::testAppendToMissingId);
        test("TEST 14: NEWAFTER with target START -> inserted at beginning", ProtocolTestHarness::testNewAfterStart);
        test("TEST 15: MOVE_AFTER with target START -> moved to beginning", ProtocolTestHarness::testMoveAfterStart);
        test("TEST 16: two @@protocol blocks, different files, one file locked", ProtocolTestHarness::testMixedLockedUnlockedBatch);
        test("TEST 17: NEW with invalid id format -> error", ProtocolTestHarness::testInvalidIdFormat);
        test("TEST 18: empty file (no entries) loads and accepts NEW", ProtocolTestHarness::testEmptyFileAcceptsNew);
        test("TEST 19: file with only a lock marker, no entries", ProtocolTestHarness::testLockedEmptyFile);
        test("TEST 20: multiple commands touching same id in one patch (UPDATE then DELETE)", ProtocolTestHarness::testUpdateThenDeleteSameId);
        test("TEST 21: reject everything in dialog -> CANCELLED", ProtocolTestHarness::testRejectAllCancelled);
        test("TEST 22: partial accept -- some commands accepted, some rejected, same file", ProtocolTestHarness::testPartialAcceptSameFile);
        test("TEST 23: NEW duplicate within the same @@protocol block (two NEWs, same id)", ProtocolTestHarness::testDuplicateNewInSamePatch);
        test("TEST 24: MOVE_AFTER onto itself -> should be a no-op or safely handled", ProtocolTestHarness::testMoveAfterSelf);
        test("TEST 25: cross-file NEWAFTER referencing an id that only exists in a different file", ProtocolTestHarness::testNewAfterTargetInWrongFile);
        test("TEST 26: hand-edit producing an entry with valid id but no content", ProtocolTestHarness::testHandEditEmptyContent);
        test("TEST 27: hand-edit valid file with !locked preserved", ProtocolTestHarness::testHandEditPreservesLockedMarker);
        test("TEST 28: file name normalization (missing .prtcl extension in @@protocol)", ProtocolTestHarness::testFileNameWithoutExtension);
        test("TEST 29: whitespace-only content lines treated as empty", ProtocolTestHarness::testWhitespaceOnlyContent);
        test("TEST 30: large batch across 3 files, mixed operations", ProtocolTestHarness::testLargeMixedBatch);

        System.out.println("\n==================================================");
        System.out.println("RESULTS: " + passCount + " passed, " + failCount + " failed");
        System.out.println("==================================================");
    }

    // ---------------------------------------------------------------
    // Test runner scaffolding
    // ---------------------------------------------------------------

    private interface TestCase {
        void run() throws Exception;
    }

    private static void test(String name, TestCase testCase) {
        System.out.println("\n=== " + name + " ===");
        try {
            testCase.run();
            passCount++;
        } catch (AssertionError e) {
            System.out.println("  *** FAILED: " + e.getMessage());
            failCount++;
        } catch (Exception e) {
            System.out.println("  *** ERROR: " + e);
            failCount++;
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertStatus(ProtocolPatchResult result, ProtocolPatchResult.Status expected) {
        System.out.println(result);
        assertTrue(result.getStatus() == expected,
            "Expected status " + expected + " but got " + result.getStatus());
    }

    private static void resetTestDir() throws Exception {
        Path protocolsDir = BASE_DIR.resolve("protocols");
        if (Files.exists(protocolsDir)) {
            try (var stream = Files.walk(protocolsDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
    }

    private static ProtocolLibrary freshLibrary() {
        return new ProtocolLibrary(BASE_DIR);
    }

    private static ProtocolPatchResult runPatch(ProtocolLibrary library, String aiOutput,
                                         ProtocolEngine.AcceptanceResolver resolver) {
        ProtocolEngine engine = new ProtocolEngine();
        engine.recordPatch(aiOutput);
        return engine.processRecorded(library, resolver);
    }

    private static ProtocolEngine.AcceptanceResolver acceptAll() {
        return (fileName, original, commands) -> {
            Set<String> accepted = new HashSet<>();
            for (Command c : commands) accepted.add(ProtocolApplier.commandKey(c));
            return accepted;
        };
    }

    private static ProtocolEngine.AcceptanceResolver rejectAll() {
        return (fileName, original, commands) -> Set.of();
    }

    // ---------------------------------------------------------------
    // Original 9
    // ---------------------------------------------------------------

    private static void testBasicAcceptAll() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("auth.prtcl", false, List.of(), List.of(
            new ProtocolEntry("auth-jwt", List.of("JWT tokens validated at gateway."), 0)
        )));

        String aiOutput =
            "@@protocol auth.prtcl\n" +
            "UPDATE !id auth-jwt\n" +
            "JWT tokens validated at gateway with RS256.\n" +
            "ENDUPDATE\n\n" +
            "NEWAFTER !id caching !id auth-jwt\n" +
            "Cache responses for 60s.\n" +
            "ENDNEWAFTER\n" +
            "@@protocolEnd\n";

        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.APPLIED);
        ProtocolFile after = library.load("auth.prtcl");
        assertTrue(after.getEntries().size() == 2, "Expected 2 entries after patch");
        assertTrue(after.indexOf("auth-jwt") == 0 && after.indexOf("caching") == 1, "Expected order auth-jwt, caching");
    }

    private static void testMissingFilename() {
        String aiOutput = "@@protocol\nUPDATE !id foo\ncontent\nENDUPDATE\n@@protocolEnd\n";
        ProtocolEngine engine = new ProtocolEngine();
        try {
            engine.recordPatch(aiOutput);
            throw new AssertionError("Expected PatchParseException, none thrown");
        } catch (wv.codeclip.protocol.parser.AiOutputParser.PatchParseException e) {
            System.out.println("Correctly rejected: " + e.getMessage());
        }
    }

    private static void testMasterLock() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("locked-test.prtcl", false, List.of(), List.of(
            new ProtocolEntry("a", List.of("Content A"), 0)
        )));
        library.setMasterLocked(true);

        String aiOutput = "@@protocol locked-test.prtcl\nUPDATE !id a\nNew content\nENDUPDATE\n@@protocolEnd\n";
        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.VALIDATION_FAILED);
        library.setMasterLocked(false);
    }

    private static void testFileLock() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("filelocked.prtcl", true, List.of(), List.of(
            new ProtocolEntry("a", List.of("Content A"), 0)
        )));
        library.save(new ProtocolFile("unlocked.prtcl", false, List.of(), List.of(
            new ProtocolEntry("b", List.of("Content B"), 0)
        )));

        String aiOutput =
            "@@protocol filelocked.prtcl\nUPDATE !id a\nShould not apply\nENDUPDATE\n@@protocolEnd\n" +
            "@@protocol unlocked.prtcl\nUPDATE !id b\nShould apply fine\nENDUPDATE\n@@protocolEnd\n";

        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.VALIDATION_FAILED);
    }

    private static void testCrossFileDuplicateIdAllowed() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("file1.prtcl", false, List.of(), new ArrayList<>()));
        library.save(new ProtocolFile("file2.prtcl", false, List.of(), new ArrayList<>()));

        String aiOutput =
            "@@protocol file1.prtcl\nNEW !id shared\nContent in file1\nENDNEW\n@@protocolEnd\n" +
            "@@protocol file2.prtcl\nNEW !id shared\nContent in file2\nENDNEW\n@@protocolEnd\n";

        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.APPLIED);
        assertTrue(library.load("file1.prtcl").containsId("shared"), "file1 should contain 'shared'");
        assertTrue(library.load("file2.prtcl").containsId("shared"), "file2 should contain 'shared'");
    }

    private static void testSameFileDuplicateIdRejected() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("dupe.prtcl", false, List.of(), List.of(
            new ProtocolEntry("existing", List.of("Already here"), 0)
        )));

        String aiOutput = "@@protocol dupe.prtcl\nNEW !id existing\nTrying to duplicate\nENDNEW\n@@protocolEnd\n";
        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.VALIDATION_FAILED);
    }

    private static void testMoveAfterFallback() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("movetest.prtcl", false, List.of(), List.of(
            new ProtocolEntry("a", List.of("A"), 0),
            new ProtocolEntry("b", List.of("B"), 1),
            new ProtocolEntry("c", List.of("C"), 2),
            new ProtocolEntry("d", List.of("D"), 3)
        )));

        String aiOutput = "@@protocol movetest.prtcl\nDELETE !id c\nMOVE_AFTER !id a !id c\n@@protocolEnd\n";
        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.APPLIED);
        ProtocolFile after = library.load("movetest.prtcl");
        List<String> order = after.getEntries().stream().map(ProtocolEntry::getId).toList();
        assertTrue(order.equals(List.of("b", "a", "d")), "Expected order [b, a, d] but got " + order);
    }

    private static void testHandEditRevalidate() {
        ProtocolEngine engine = new ProtocolEngine();
        String handEdited = "!id a\nSome content\n\n!id a\nDuplicate id, should fail\n";
        ValidationResult result = engine.validateFileContent("handedit.prtcl", handEdited);
        System.out.println(result);
        assertTrue(!result.isValid(), "Expected validation to fail on duplicate id");
    }

    private static void testEmptyPatch() {
        ProtocolLibrary library = freshLibrary();
        ProtocolPatchResult result = runPatch(library, "no protocol blocks here", acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.EMPTY);
    }

    // ---------------------------------------------------------------
    // New edge cases
    // ---------------------------------------------------------------

    private static void testMasterAndFileLockTogether() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("both.prtcl", true, List.of(), List.of(
            new ProtocolEntry("a", List.of("A"), 0)
        )));
        library.setMasterLocked(true);

        String aiOutput = "@@protocol both.prtcl\nUPDATE !id a\nchange\nENDUPDATE\n@@protocolEnd\n";
        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.VALIDATION_FAILED);
        // Master lock error should be the one reported (checked first), not the file lock.
        boolean mentionsMaster = result.getValidation().getErrors().stream()
            .anyMatch(e -> e.getMessage().toLowerCase().contains("master lock"));
        assertTrue(mentionsMaster, "Expected error to mention master lock");
        library.setMasterLocked(false);
    }

    private static void testDeleteNonExistentId() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("del.prtcl", false, List.of(), List.of(
            new ProtocolEntry("a", List.of("A"), 0)
        )));
        String aiOutput = "@@protocol del.prtcl\nDELETE !id ghost\n@@protocolEnd\n";
        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.VALIDATION_FAILED);
    }

    private static void testAppendTo() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("append.prtcl", false, List.of(), List.of(
            new ProtocolEntry("a", List.of("Line 1"), 0)
        )));
        String aiOutput = "@@protocol append.prtcl\nAPPENDTO !id a\nLine 2\nENDAPPENDTO\n@@protocolEnd\n";
        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.APPLIED);
        ProtocolFile after = library.load("append.prtcl");
        List<String> lines = after.findById("a").get().getContentLines();
        assertTrue(lines.equals(List.of("Line 1", "Line 2")), "Expected appended content, got " + lines);
    }

    private static void testAppendToMissingId() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("append2.prtcl", false, List.of(), new ArrayList<>()));
        String aiOutput = "@@protocol append2.prtcl\nAPPENDTO !id ghost\nLine\nENDAPPENDTO\n@@protocolEnd\n";
        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.VALIDATION_FAILED);
    }

    private static void testNewAfterStart() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("start1.prtcl", false, List.of(), List.of(
            new ProtocolEntry("a", List.of("A"), 0),
            new ProtocolEntry("b", List.of("B"), 1)
        )));
        String aiOutput = "@@protocol start1.prtcl\nNEWAFTER !id z !id START\nZ content\nENDNEWAFTER\n@@protocolEnd\n";
        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.APPLIED);
        ProtocolFile after = library.load("start1.prtcl");
        assertTrue(after.getEntries().get(0).getId().equals("z"), "Expected 'z' first, got " + after.getEntries().get(0).getId());
    }

    private static void testMoveAfterStart() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("start2.prtcl", false, List.of(), List.of(
            new ProtocolEntry("a", List.of("A"), 0),
            new ProtocolEntry("b", List.of("B"), 1),
            new ProtocolEntry("c", List.of("C"), 2)
        )));
        String aiOutput = "@@protocol start2.prtcl\nMOVE_AFTER !id c !id START\n@@protocolEnd\n";
        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.APPLIED);
        ProtocolFile after = library.load("start2.prtcl");
        assertTrue(after.getEntries().get(0).getId().equals("c"), "Expected 'c' moved to front");
    }

    private static void testMixedLockedUnlockedBatch() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("mixed_locked.prtcl", true, List.of(), List.of(
            new ProtocolEntry("a", List.of("A"), 0)
        )));
        library.save(new ProtocolFile("mixed_ok.prtcl", false, List.of(), List.of(
            new ProtocolEntry("b", List.of("B"), 0)
        )));
        String aiOutput =
            "@@protocol mixed_locked.prtcl\nUPDATE !id a\nBlocked change\nENDUPDATE\n@@protocolEnd\n" +
            "@@protocol mixed_ok.prtcl\nUPDATE !id b\nFine change\nENDUPDATE\n@@protocolEnd\n";
        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        // Whole-batch validation: one locked file fails the WHOLE patch, per spec.
        assertStatus(result, ProtocolPatchResult.Status.VALIDATION_FAILED);
        ProtocolFile stillUnchanged = library.load("mixed_ok.prtcl");
        assertTrue(stillUnchanged.findById("b").get().getContentLines().equals(List.of("B")),
            "mixed_ok.prtcl should NOT have been modified since the whole batch failed");
    }

    private static void testInvalidIdFormat() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("badid.prtcl", false, List.of(), new ArrayList<>()));
        String aiOutput = "@@protocol badid.prtcl\nNEW !id Bad_ID_123\ncontent\nENDNEW\n@@protocolEnd\n";
        ProtocolEngine engine = new ProtocolEngine();
        try {
            engine.recordPatch(aiOutput);
            // If parsing succeeds (regex allows it through at parse time is unlikely since
            // AiOutputParser's own id pattern would reject "Bad_ID_123" outright), this
            // is fine either way -- what matters is it never reaches APPLIED.
            ProtocolPatchResult result = engine.processRecorded(library, acceptAll());
            assertTrue(result.getStatus() != ProtocolPatchResult.Status.APPLIED, "Invalid id format should never apply");
        } catch (wv.codeclip.protocol.parser.AiOutputParser.PatchParseException e) {
            System.out.println("Correctly rejected at parse time: " + e.getMessage());
        }
    }

    private static void testEmptyFileAcceptsNew() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("empty1.prtcl", false, List.of(), new ArrayList<>()));
        String aiOutput = "@@protocol empty1.prtcl\nNEW !id first\nFirst content\nENDNEW\n@@protocolEnd\n";
        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.APPLIED);
        assertTrue(library.load("empty1.prtcl").containsId("first"), "Expected 'first' to be added to empty file");
    }

    private static void testLockedEmptyFile() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("emptylocked.prtcl", true, List.of(), new ArrayList<>()));
        ProtocolFile reloaded = library.load("emptylocked.prtcl");
        assertTrue(reloaded.isLocked(), "Expected lock flag to round-trip on an empty file");
        assertTrue(reloaded.getEntries().isEmpty(), "Expected no entries");
    }

    private static void testUpdateThenDeleteSameId() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("upddel.prtcl", false, List.of(), List.of(
            new ProtocolEntry("a", List.of("Original"), 0)
        )));
        // Per apply order (UPDATE runs before DELETE), the update is pointless but
        // should not error -- it just gets deleted right after.
        String aiOutput = "@@protocol upddel.prtcl\nUPDATE !id a\nUpdated first\nENDUPDATE\nDELETE !id a\n@@protocolEnd\n";
        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.APPLIED);
        assertTrue(!library.load("upddel.prtcl").containsId("a"), "Expected 'a' to be gone after update+delete");
    }

    private static void testRejectAllCancelled() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("rejectall.prtcl", false, List.of(), List.of(
            new ProtocolEntry("a", List.of("A"), 0)
        )));
        String aiOutput = "@@protocol rejectall.prtcl\nUPDATE !id a\nNew\nENDUPDATE\n@@protocolEnd\n";
        ProtocolPatchResult result = runPatch(library, aiOutput, rejectAll());
        assertStatus(result, ProtocolPatchResult.Status.CANCELLED);
        assertTrue(library.load("rejectall.prtcl").findById("a").get().getContentLines().equals(List.of("A")),
            "File should be untouched after full rejection");
    }

    private static void testPartialAcceptSameFile() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("partial.prtcl", false, List.of(), List.of(
            new ProtocolEntry("a", List.of("A"), 0),
            new ProtocolEntry("b", List.of("B"), 1)
        )));
        String aiOutput =
            "@@protocol partial.prtcl\n" +
            "UPDATE !id a\nUpdated A\nENDUPDATE\n" +
            "DELETE !id b\n" +
            "@@protocolEnd\n";

        // Accept only the UPDATE, reject the DELETE.
        ProtocolEngine.AcceptanceResolver resolver = (fileName, original, commands) -> {
            Set<String> accepted = new HashSet<>();
            for (Command c : commands) {
                if (c.getType() == CommandType.UPDATE) accepted.add(ProtocolApplier.commandKey(c));
            }
            return accepted;
        };
        ProtocolPatchResult result = runPatch(library, aiOutput, resolver);
        assertStatus(result, ProtocolPatchResult.Status.APPLIED);
        ProtocolFile after = library.load("partial.prtcl");
        assertTrue(after.containsId("b"), "Expected 'b' to survive since its DELETE was rejected");
        assertTrue(after.findById("a").get().getContentLines().equals(List.of("Updated A")), "Expected 'a' updated");
    }

    private static void testDuplicateNewInSamePatch() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("dupnew.prtcl", false, List.of(), new ArrayList<>()));
        String aiOutput =
            "@@protocol dupnew.prtcl\n" +
            "NEW !id x\nFirst\nENDNEW\n" +
            "NEW !id x\nSecond\nENDNEW\n" +
            "@@protocolEnd\n";
        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.VALIDATION_FAILED);
    }

    private static void testMoveAfterSelf() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("selfmove.prtcl", false, List.of(), List.of(
            new ProtocolEntry("a", List.of("A"), 0)
        )));
        String aiOutput = "@@protocol selfmove.prtcl\nMOVE_AFTER !id a !id a\n@@protocolEnd\n";
        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        // Moving something after itself is nonsensical -- current PatchValidator doesn't
        // explicitly special-case this, so we check it doesn't corrupt the file or crash.
        System.out.println(result);
        if (result.getStatus() == ProtocolPatchResult.Status.APPLIED) {
            ProtocolFile after = library.load("selfmove.prtcl");
            assertTrue(after.getEntries().size() == 1, "Self-move should not duplicate or lose the entry");
        }
    }

    private static void testNewAfterTargetInWrongFile() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("wrongfile1.prtcl", false, List.of(), List.of(
            new ProtocolEntry("existsonlyhere", List.of("Content"), 0)
        )));
        library.save(new ProtocolFile("wrongfile2.prtcl", false, List.of(), new ArrayList<>()));

        // Target "existsonlyhere" lives in wrongfile1, but this NEWAFTER targets wrongfile2.
        String aiOutput = "@@protocol wrongfile2.prtcl\nNEWAFTER !id newone !id existsonlyhere\nContent\nENDNEWAFTER\n@@protocolEnd\n";
        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        // Per spec, NEWAFTER target-not-found falls back to end-of-file with a warning,
        // rather than erroring, since the target genuinely doesn't exist IN THIS FILE.
        assertStatus(result, ProtocolPatchResult.Status.APPLIED);
        assertTrue(!result.getValidation().getWarnings().isEmpty(), "Expected a warning about target not found");
        assertTrue(library.load("wrongfile2.prtcl").containsId("newone"), "Expected 'newone' appended despite missing target");
    }

    private static void testHandEditEmptyContent() {
        ProtocolEngine engine = new ProtocolEngine();
        String handEdited = "!id a\n\n";
        ValidationResult result = engine.validateFileContent("emptycontent.prtcl", handEdited);
        System.out.println(result);
        assertTrue(!result.isValid(), "Expected validation failure for empty content block");
    }

    private static void testHandEditPreservesLockedMarker() {
        ProtocolLibrary library = freshLibrary();
        ProtocolFile file = new ProtocolFile("preserve.prtcl", true, List.of(), List.of(
            new ProtocolEntry("a", List.of("Content"), 0)
        ));
        library.save(file);
        String rendered = library.load("preserve.prtcl").render();
        assertTrue(rendered.startsWith("!locked"), "Expected rendered file to start with !locked, got:\n" + rendered);
    }

    private static void testFileNameWithoutExtension() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("noext.prtcl", false, List.of(), new ArrayList<>()));
        // AI references the file WITHOUT the .prtcl extension -- library methods normalize this.
        String aiOutput = "@@protocol noext\nNEW !id x\ncontent\nENDNEW\n@@protocolEnd\n";
        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.APPLIED);
        assertTrue(library.exists("noext.prtcl"), "Expected 'noext.prtcl' to exist after normalization");
    }

    private static void testWhitespaceOnlyContent() {
        ProtocolEngine engine = new ProtocolEngine();
        String handEdited = "!id a\n   \n\t\n";
        ValidationResult result = engine.validateFileContent("whitespace.prtcl", handEdited);
        System.out.println(result);
        assertTrue(!result.isValid(), "Expected whitespace-only content to be treated as empty and rejected");
    }

    private static void testLargeMixedBatch() {
        ProtocolLibrary library = freshLibrary();
        library.save(new ProtocolFile("big1.prtcl", false, List.of(), List.of(
            new ProtocolEntry("a1", List.of("A1"), 0),
            new ProtocolEntry("a2", List.of("A2"), 1)
        )));
        library.save(new ProtocolFile("big2.prtcl", false, List.of(), List.of(
            new ProtocolEntry("b1", List.of("B1"), 0)
        )));
        library.save(new ProtocolFile("big3.prtcl", false, List.of(), new ArrayList<>()));

        String aiOutput =
            "@@protocol big1.prtcl\n" +
            "UPDATE !id a1\nA1 updated\nENDUPDATE\n" +
            "DELETE !id a2\n" +
            "NEW !id a3\nA3 new\nENDNEW\n" +
            "@@protocolEnd\n" +
            "@@protocol big2.prtcl\n" +
            "APPENDTO !id b1\nB1 extra line\nENDAPPENDTO\n" +
            "@@protocolEnd\n" +
            "@@protocol big3.prtcl\n" +
            "NEW !id c1\nC1 content\nENDNEW\n" +
            "NEWAFTER !id c2 !id c1\nC2 content\nENDNEWAFTER\n" +
            "@@protocolEnd\n";

        ProtocolPatchResult result = runPatch(library, aiOutput, acceptAll());
        assertStatus(result, ProtocolPatchResult.Status.APPLIED);

        ProtocolFile big1 = library.load("big1.prtcl");
        assertTrue(!big1.containsId("a2"), "a2 should be deleted");
        assertTrue(big1.containsId("a3"), "a3 should exist");
        assertTrue(big1.findById("a1").get().getContentLines().equals(List.of("A1 updated")), "a1 should be updated");

        ProtocolFile big2 = library.load("big2.prtcl");
        assertTrue(big2.findById("b1").get().getContentLines().equals(List.of("B1", "B1 extra line")), "b1 should have appended content");

        ProtocolFile big3 = library.load("big3.prtcl");
        List<String> order = big3.getEntries().stream().map(ProtocolEntry::getId).toList();
        assertTrue(order.equals(List.of("c1", "c2")), "Expected order [c1, c2] in big3, got " + order);
    }
}