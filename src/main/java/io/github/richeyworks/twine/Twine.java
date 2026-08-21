package io.github.richeyworks.twine;

import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

/**
 * Twine — engine nine of the ecosystem: crash-atomic multi-key batches, tied with string.
 * The seventh-engine ADR first judged this "storage surgery belonging inside SmokeHouse";
 * this design revises that verdict on the record, because atomicity is achievable by
 * <b>composition</b>: a journaled intent + an atomic rename as the commit point + idempotent
 * replay through the store's own last-writer-wins {@code put}/{@code delete}.
 *
 * <h2>The protocol</h2>
 * <ol>
 *   <li>{@link Batch#commit()} serializes every op to {@code batch.twine.tmp}, CRC32-summed,
 *       and fsyncs it;</li>
 *   <li>an ATOMIC_MOVE to {@code batch.twine} is the commit point — before it, the batch
 *       never happened; after it, the batch is inevitable;</li>
 *   <li>the ops apply to the store in order; the journal is deleted.</li>
 * </ol>
 *
 * Crash before the move: the torn tmp is discarded at the next {@link #over}, zero ops
 * applied. Crash during apply: {@link #over} replays the whole journal — re-applying already
 * applied ops is a no-op net effect (last-writer-wins), so the batch lands exactly once in
 * its entirety. <b>Contract: construct Twine (which replays) before any other writes after a
 * reopen, and route all writes through one Twine</b> — one in-flight batch at a time, the
 * single-writer discipline one level up.
 *
 * <h2>Honest bounds</h2>
 * Atomic across crashes, <em>not</em> isolated from concurrent readers: a reader between
 * apply steps sees a partial batch (the store stays per-op consistent throughout). Cross-key
 * atomicity of visibility would need the log-format group-commit — that deeper cut stays
 * with SmokeHouse, trigger unchanged.
 */
public final class Twine<K, V> implements Closeable {

    /** A last-writer-wins put target — the replay-idempotency contract, as a type. */
    @FunctionalInterface
    public interface PutSink<K, V> {
        void put(K key, V value) throws IOException;
    }

    /** A delete target whose delete-of-absent is a no-op — same contract. */
    @FunctionalInterface
    public interface DeleteSink<K> {
        void delete(K key) throws IOException;
    }

    private static final String JOURNAL = "batch.twine";
    private static final String TMP = "batch.twine.tmp";
    private static final String LOCK = "twine.lock";
    private static final byte OP_PUT = 1;
    private static final byte OP_DELETE = 2;

    private final PutSink<K, V> putSink;
    private final DeleteSink<K> deleteSink;
    private final Path journalDir;
    private final SpillSerializer<K> keySerializer;
    private final SpillSerializer<V> valueSerializer;
    // T1 (2026-08-20): exclusive ownership of the journal directory for this Twine's lifetime.
    // Two Twines over one journalDir would steal each other's committed batches (fixed file
    // names); the lock makes that misuse fail loudly at over() instead of corrupting silently.
    private final FileChannel lockChannel;
    private final FileLock lock;

    // The batcher's meter (2026-08-20) — the last engine joins the observability story.
    private final java.util.concurrent.atomic.AtomicLong batchesCommitted =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong opsApplied =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong journalReplays =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * A point-in-time readout of this Twine's work: batches committed through
     * {@code Batch.commit()}, ops applied to the sinks (commit-path and replay-path both), and journals replayed at
     * {@link #over construction} — the crash-recovery events, on the meter. A nonzero
     * {@code journalReplays} is the observable trace of a crash that the exactly-once contract
     * absorbed.
     */
    public record TwineStats(long batchesCommitted, long opsApplied, long journalReplays) {
        /** A one-line readout, {@link java.util.Locale#ROOT}-pinned like every house line. */
        public String line() {
            return String.format(java.util.Locale.ROOT,
                    "batches=%d ops=%d replays=%d", batchesCommitted, opsApplied, journalReplays);
        }
    }

    /** A snapshot of the batcher's own meter. */
    public TwineStats stats() {
        return new TwineStats(batchesCommitted.get(), opsApplied.get(), journalReplays.get());
    }

    private Twine(PutSink<K, V> putSink, DeleteSink<K> deleteSink, Path journalDir,
                  SpillSerializer<K> keySerializer, SpillSerializer<V> valueSerializer)
            throws IOException {
        this.putSink = putSink;
        this.deleteSink = deleteSink;
        this.journalDir = journalDir;
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
        // Claim the directory exclusively. A same-JVM second Twine trips OverlappingFileLock;
        // a cross-process one gets a null tryLock — either way, refuse loudly.
        FileChannel ch = FileChannel.open(journalDir.resolve(LOCK),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock fl;
        try {
            fl = ch.tryLock();
        } catch (OverlappingFileLockException alreadyOursInThisJvm) {
            ch.close();
            throw new IOException("journalDir " + journalDir + " is already tied by another "
                    + "live Twine in this JVM — route all writes through one Twine");
        }
        if (fl == null) {
            ch.close();
            throw new IOException("journalDir " + journalDir + " is locked by another process's "
                    + "Twine — one Twine per journal directory");
        }
        this.lockChannel = ch;
        this.lock = fl;
    }

    /** Best-effort directory fsync (T2): make a rename's directory entry durable. */
    private static void syncDir(Path dir) {
        try (FileChannel dirChannel = FileChannel.open(dir, StandardOpenOption.READ)) {
            dirChannel.force(true);
        } catch (IOException platformCannot) {
            // Some platforms (notably Windows) refuse to open a directory as a channel; the
            // rename's durability then rides the filesystem's own ordering. Best-effort by design.
        }
    }

    /**
     * Release this Twine's exclusive hold on the journal directory (T1). Idempotent. Does NOT
     * touch a committed-but-unapplied journal — that is recovered by the next {@link #over}.
     */
    @Override
    public void close() throws IOException {
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } finally {
            lockChannel.close();
        }
    }

    /**
     * Tie Twine over {@code store}: discard any torn journal, replay any committed one
     * (exactly-once net effect), then hand back the batch surface. Call this before any
     * other writes after a reopen — replay must run against the recovered state.
     */
    public static <K, V> Twine<K, V> over(SmokeHouse<K, V> store, Path journalDir,
                                          SpillSerializer<K> keySerializer,
                                          SpillSerializer<V> valueSerializer)
            throws IOException {
        Objects.requireNonNull(store, "store");
        return over(store::put, store::delete, journalDir, keySerializer, valueSerializer);
    }

    /**
     * Tie Twine over any last-writer-wins write target — the seam named by its first
     * consumer (WholeHog): an {@code IndexedStore} routes writes through its index fan-out,
     * so batches over an indexed store tie through {@code indexed::put}/{@code indexed::delete}
     * rather than the primary (which would bypass every secondary). The sinks must be
     * last-writer-wins upserts with no-op deletes-of-absent — that contract is what makes
     * replay idempotent, and it is the caller's to keep.
     */
    public static <K, V> Twine<K, V> over(PutSink<K, V> putSink, DeleteSink<K> deleteSink,
                                          Path journalDir,
                                          SpillSerializer<K> keySerializer,
                                          SpillSerializer<V> valueSerializer)
            throws IOException {
        Files.createDirectories(Objects.requireNonNull(journalDir, "journalDir"));
        Twine<K, V> twine = new Twine<>(
                Objects.requireNonNull(putSink, "putSink"),
                Objects.requireNonNull(deleteSink, "deleteSink"),
                journalDir, Objects.requireNonNull(keySerializer, "keySerializer"),
                Objects.requireNonNull(valueSerializer, "valueSerializer"));
        Files.deleteIfExists(journalDir.resolve(TMP));         // torn = never happened
        Path journal = journalDir.resolve(JOURNAL);
        if (Files.exists(journal)) {
            twine.apply(twine.read(journal));                  // committed = inevitable
            Files.delete(journal);
            twine.journalReplays.incrementAndGet();            // the crash, on the meter
        }
        return twine;
    }

    /** Start staging a batch. One at a time — the single-writer discipline, one level up. */
    public Batch batch() {
        return new Batch();
    }

    /**
     * Finish a batch whose in-process apply failed part-way (tenth-pass T4). A sink throwing
     * mid-apply — the commit point already passed, so the batch is inevitable — leaves the
     * committed journal on disk and every future {@link Batch#commit} throwing "a committed
     * batch is still applying." That used to wedge the Twine until the caller closed it and
     * re-{@link #over}ed. {@code recover()} does the same drain in place: it replays the
     * surviving journal (re-applying already-landed ops is a harmless no-op under the sinks'
     * last-writer-wins contract), deletes it, and unwedges the Twine. Idempotent and retryable —
     * if the sink is still broken the replay throws again and the journal survives for the next
     * attempt; call it once the sink is healthy. Serializes on the Twine like {@link Batch#commit},
     * so it never races a concurrent commit.
     *
     * @return {@code true} if a surviving committed journal was drained, {@code false} if there
     *         was nothing to recover (the common case — no apply had failed)
     */
    public synchronized boolean recover() throws IOException {
        Path journal = journalDir.resolve(JOURNAL);
        if (!Files.exists(journal)) {
            return false;
        }
        apply(read(journal));                                  // idempotent under LWW
        Files.delete(journal);
        journalReplays.incrementAndGet();                      // an in-process replay is still a replay
        return true;
    }

    /** A staged batch: puts and deletes that will land atomically or not at all. */
    public final class Batch {

        private final List<Op<K, V>> ops = new ArrayList<>();
        private boolean committed;

        public Batch put(K key, V value) {
            requireStaging();
            ops.add(new Op<>(OP_PUT, Objects.requireNonNull(key, "key"),
                    Objects.requireNonNull(value, "value")));
            return this;
        }

        public Batch delete(K key) {
            requireStaging();
            ops.add(new Op<>(OP_DELETE, Objects.requireNonNull(key, "key"), null));
            return this;
        }

        /**
         * Journal → fsync → atomic move (the commit point) → apply → delete journal.
         *
         * <p>Ninth-pass finding 2 (2026-08-20): this used to be {@code synchronized} on the
         * Batch — a one-shot object nothing else ever locks, so the "one batch at a time"
         * discipline was documentation, not enforcement, and two threads committing separate
         * batches raced the shared {@code batch.twine.tmp} path. Commit now serializes on the
         * Twine itself: the single-writer discipline the class always claimed, made true by
         * construction. Callers that synchronized externally (WholeHog's wire batch route)
         * keep working — the lock is reentrant-compatible with theirs being redundant.</p>
         */
        public void commit() throws IOException {
            synchronized (Twine.this) {
                commitLocked();
            }
        }

        private void commitLocked() throws IOException {
            requireStaging();
            if (ops.isEmpty()) {
                committed = true;
                batchesCommitted.incrementAndGet();            // T5: meter empty commits too
                return;
            }
            Path tmp = journalDir.resolve(TMP);
            Path journal = journalDir.resolve(JOURNAL);
            if (Files.exists(journal)) {
                throw new IllegalStateException("a committed batch is still applying; "
                        + "one batch at a time");
            }
            try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
                CheckedOutputStream checked =
                        new CheckedOutputStream(new BufferedOutputStream(fos), new CRC32());
                DataOutputStream out = new DataOutputStream(checked);
                out.writeInt(ops.size());
                for (Op<K, V> op : ops) {
                    out.writeByte(op.type());
                    keySerializer.write(op.key(), out);
                    if (op.type() == OP_PUT) {
                        valueSerializer.write(op.value(), out);
                    }
                }
                out.flush();
                long crc = checked.getChecksum().getValue();
                new DataOutputStream(fos).writeLong(crc);      // trailer, outside the sum
                fos.getFD().sync();                            // journal durable before commit
            }
            Files.move(tmp, journal, StandardCopyOption.ATOMIC_MOVE);   // THE commit point
            syncDir(journalDir);                               // T2: make the rename itself durable
            committed = true;                                  // T3: only now is it truly committed;
            apply(ops);                                        // a pre-commit-point failure stays retryable
            Files.delete(journal);
            batchesCommitted.incrementAndGet();
        }

        private void requireStaging() {
            if (committed) {
                throw new IllegalStateException("batch already committed");
            }
        }
    }

    private record Op<K, V>(byte type, K key, V value) { }

    private void apply(List<Op<K, V>> ops) throws IOException {
        for (Op<K, V> op : ops) {
            if (op.type() == OP_PUT) {
                putSink.put(op.key(), op.value());
            } else {
                deleteSink.delete(op.key());
            }
            opsApplied.incrementAndGet();
        }
    }

    private List<Op<K, V>> read(Path journal) throws IOException {
        byte[] bytes = Files.readAllBytes(journal);            // journals are one batch: small
        if (bytes.length < Long.BYTES + Integer.BYTES) {
            throw new IOException("journal truncated: " + bytes.length + " bytes");
        }
        int opsLength = bytes.length - Long.BYTES;             // trailer = CRC64-bits at the end
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, opsLength);
        DataInputStream trailer = new DataInputStream(
                new java.io.ByteArrayInputStream(bytes, opsLength, Long.BYTES));
        long stored = trailer.readLong();
        if (crc.getValue() != stored) {
            throw new IOException("journal CRC mismatch: committed batch is corrupt "
                    + "(computed " + crc.getValue() + ", stored " + stored + ")");
        }
        DataInputStream in = new DataInputStream(
                new java.io.ByteArrayInputStream(bytes, 0, opsLength));
        int count = in.readInt();
        List<Op<K, V>> ops = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte type = in.readByte();
            K key = keySerializer.read(in);
            V value = (type == OP_PUT) ? valueSerializer.read(in) : null;
            ops.add(new Op<>(type, key, value));
        }
        return ops;
    }
}
