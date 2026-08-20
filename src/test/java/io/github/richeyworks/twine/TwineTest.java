package io.github.richeyworks.twine;

import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.smokehouse.SmokeHouseOptions;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crash windows, exercised directly: a committed journal left behind (crash mid-apply or
 * before apply) must land the whole batch at the next {@code over}; a torn tmp (crash before
 * the commit point) must land nothing; a corrupted committed journal must fail loudly, never
 * apply garbage. Plus the happy path against a {@code TreeMap} oracle.
 */
class TwineTest {

    private static SmokeHouseOptions<Long, String> opts() {
        return SmokeHouseOptions.of(SpillSerializer.forLongs(), SpillSerializer.forStrings())
                .indexTier(SmokeHouseOptions.IndexTier.STATIC);
    }

    private static Twine<Long, String> tie(SmokeHouse<Long, String> store, Path journalDir)
            throws IOException {
        return Twine.over(store, journalDir, SpillSerializer.forLongs(),
                SpillSerializer.forStrings());
    }

    @Test
    void aCommittedBatchLandsWholeAndAnEmptyOneIsANoOp(@TempDir Path dir, @TempDir Path jdir)
            throws IOException {
        try (SmokeHouse<Long, String> store = SmokeHouse.open(dir, opts())) {
            Twine<Long, String> twine = tie(store, jdir);
            store.put(3L, "will-be-deleted");
            twine.batch()
                    .put(1L, "one")
                    .put(2L, "two")
                    .delete(3L)
                    .put(1L, "one-final")                      // last writer wins inside too
                    .commit();
            assertEquals(Map.of(1L, "one-final", 2L, "two"), scan(store));
            twine.batch().commit();                            // empty: allowed, no journal
            assertEquals(2, store.size());
        }
    }

    @Test
    void aLeftoverCommittedJournalReplaysExactlyOnceInEffect(@TempDir Path dir,
                                                            @TempDir Path jdir)
            throws IOException {
        // The mid-apply crash picture, exactly: a PARTIAL application already in the log
        // (10 landed, 20 and the delete did not) and the committed journal still present.
        try (SmokeHouse<Long, String> store = SmokeHouse.open(dir, opts())) {
            store.put(10L, "ten");                             // the partially applied prefix
            store.put(99L, "doomed");                          // the delete's target, live
            CraftedJournal.write(jdir);                        // a committed journal, present
            Twine<Long, String> twine = tie(store, jdir);      // over() must finish the batch
            assertEquals(Map.of(10L, "ten", 20L, "twenty"), scan(store),
                    "replay completes the batch; re-applied ops are harmless");
            assertTrue(twine.batch() != null);
            // The crash, on the meter (2026-08-20): a replay is the observable trace of the
            // absorbed crash — and replayed ops count as applied, because they were.
            assertEquals(1, twine.stats().journalReplays(), "the replay is on the meter");
            assertEquals(3, twine.stats().opsApplied(), "replayed ops count as applied");
            assertEquals(0, twine.stats().batchesCommitted(), "a replay is not a new commit");

            // And a clean commit meters as one.
            twine.batch().put(1L, "one").commit();
            assertEquals(1, twine.stats().batchesCommitted());
            assertEquals(4, twine.stats().opsApplied());
            assertTrue(twine.stats().line().contains("replays=1"), "the line renders");
            try (var listing = Files.list(jdir)) {
                assertEquals(0, listing.count(), "journal consumed");
            }
        }
    }

    /** Serializes {put 10 ten, put 20 twenty, delete 99} exactly as commit() would. */
    private static final class CraftedJournal {
        static void write(Path jdir) throws IOException {
            java.io.ByteArrayOutputStream ops = new java.io.ByteArrayOutputStream();
            java.util.zip.CheckedOutputStream checked = new java.util.zip.CheckedOutputStream(
                    ops, new java.util.zip.CRC32());
            java.io.DataOutputStream out = new java.io.DataOutputStream(checked);
            out.writeInt(3);
            out.writeByte(1);
            SpillSerializer.forLongs().write(10L, out);
            SpillSerializer.forStrings().write("ten", out);
            out.writeByte(1);
            SpillSerializer.forLongs().write(20L, out);
            SpillSerializer.forStrings().write("twenty", out);
            out.writeByte(2);
            SpillSerializer.forLongs().write(99L, out);
            out.flush();
            java.io.ByteArrayOutputStream whole = new java.io.ByteArrayOutputStream();
            whole.write(ops.toByteArray());
            new java.io.DataOutputStream(whole).writeLong(checked.getChecksum().getValue());
            Files.createDirectories(jdir);
            Files.write(jdir.resolve("batch.twine"), whole.toByteArray());
        }
    }

    @Test
    void concurrentBatchesSerializeOnTheTwineItself(@TempDir Path dir, @TempDir Path jdir)
            throws Exception {
        // Ninth-pass finding 2: commit used to synchronize on the one-shot Batch object, so
        // two threads committing separate batches raced the shared journal path. Now they
        // serialize on the Twine — every batch lands, whole, in some order.
        try (SmokeHouse<Long, String> store = SmokeHouse.open(dir, opts())) {
            Twine<Long, String> twine = tie(store, jdir);
            int threads = 4, batchesEach = 15;
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(threads);
            java.util.List<java.util.concurrent.Future<?>> work = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final long base = t * 1_000L;
                work.add(pool.submit(() -> {
                    for (int b = 0; b < batchesEach; b++) {
                        twine.batch()
                                .put(base + b * 2, "a" + b)
                                .put(base + b * 2 + 1, "b" + b)
                                .commit();
                    }
                    return null;
                }));
            }
            for (var w : work) {
                w.get(30, java.util.concurrent.TimeUnit.SECONDS);   // surfaces any race loudly
            }
            pool.shutdown();
            assertEquals(threads * batchesEach * 2, store.size(),
                    "every op of every concurrent batch landed exactly once");
            assertEquals(threads * batchesEach, twine.stats().batchesCommitted(),
                    "and every batch is on the meter");
            try (var listing = Files.list(jdir)) {
                assertEquals(0, listing.count(), "no journal or tmp left behind");
            }
        }
    }

    @Test
    void aTornTmpLandsNothing(@TempDir Path dir, @TempDir Path jdir) throws IOException {
        Files.createDirectories(jdir);
        Files.write(jdir.resolve("batch.twine.tmp"), new byte[]{1, 2, 3});   // torn pre-commit
        try (SmokeHouse<Long, String> store = SmokeHouse.open(dir, opts())) {
            tie(store, jdir);
            assertEquals(0, store.size(), "a batch that never reached the commit point "
                    + "never happened");
            try (var listing = Files.list(jdir)) {
                assertEquals(0, listing.count(), "torn tmp discarded");
            }
        }
    }

    @Test
    void aCorruptCommittedJournalFailsLoudlyAndAppliesNothing(@TempDir Path dir,
                                                             @TempDir Path jdir)
            throws IOException {
        Files.createDirectories(jdir);
        byte[] garbage = new byte[64];
        new java.util.Random(42).nextBytes(garbage);
        Files.write(jdir.resolve("batch.twine"), garbage);
        try (SmokeHouse<Long, String> store = SmokeHouse.open(dir, opts())) {
            assertThrows(IOException.class, () -> tie(store, jdir),
                    "corrupt committed journal must refuse, not guess");
            assertEquals(0, store.size());
        }
    }

    private static Map<Long, String> scan(SmokeHouse<Long, String> store) throws IOException {
        TreeMap<Long, String> out = new TreeMap<>();
        if (store.size() > 0) {
            store.range(store.firstKey(), store.lastKey(), out::put);
        }
        return out;
    }
}
