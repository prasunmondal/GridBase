package io.github.prasunmondal.hibernatesheets.cache;

import io.github.prasunmondal.hibernatesheets.cache.InMemoryResponseCache.Stored;
import io.github.prasunmondal.hibernatesheets.exception.HibernateSheetsException;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * Pure-Java persistent store: every entry is held in memory and every change is appended to a journal
 * file, which is replayed on open. No native code and no extra dependency, so it works everywhere
 * {@code sqlite-jdbc} doesn't (e.g. Android ABIs without {@code libsqlitejdbc.so}).
 *
 * <ul>
 *   <li>Reads never touch the disk; each write is one append ({@code put}) or a tiny tombstone record
 *       ({@code invalidate}, {@code purgeExpired}, {@code clear}).</li>
 *   <li>Records carry a CRC, so a write torn by a crash or a killed app is detected and cut off on the
 *       next open; everything before it survives.</li>
 *   <li>When the journal grows to twice its last compacted size (and at least {@value #MIN_COMPACT_BYTES}
 *       bytes) it is rewritten with only the live entries and swapped in atomically.</li>
 * </ul>
 *
 * <p>Since all live replies stay in memory, use it for read caches of normal size (typical sheet data),
 * and call {@link #purgeExpired} now and then if many distinct queries go stale. One instance per file
 * per JVM, shared via {@link #open}; do not share a file between processes. Thread-safe.</p>
 */
public final class JournalResponseCache implements ResponseCache {

    private static final Map<Path, JournalResponseCache> OPEN = new ConcurrentHashMap<>();

    private static final int MAGIC = 0x48534A31; // "HSJ1"
    private static final int HEADER_BYTES = 4;
    static final long MIN_COMPACT_BYTES = 1024 * 1024;
    private static final int MAX_RECORD_BYTES = 256 * 1024 * 1024;

    private static final byte PUT = 1;
    private static final byte INVALIDATE = 2;
    private static final byte PURGE = 3;
    private static final byte CLEAR = 4;

    private final Path file;
    private final InMemoryResponseCache memory = new InMemoryResponseCache();
    private OutputStream out;
    private long fileBytes;
    private long compactAt;

    private JournalResponseCache(Path file) {
        this.file = file;
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.isDirectory(file)) {
                throw new IOException("is a directory");
            }
            long valid = Files.exists(file) ? replay() : 0;
            if (valid == 0) {
                try (DataOutputStream header = new DataOutputStream(Files.newOutputStream(file))) {
                    header.writeInt(MAGIC);
                }
                valid = HEADER_BYTES;
            } else if (valid < Files.size(file)) {
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
                    raf.setLength(valid); // drop a torn tail
                }
            }
            fileBytes = valid;
            out = new FileOutputStream(file.toFile(), true);
            compactAt = Math.max(MIN_COMPACT_BYTES, 2 * fileBytes);
        } catch (IOException e) {
            throw new HibernateSheetsException("Cannot open response cache " + file + ": " + e.getMessage(), e, false);
        }
    }

    /** The shared cache for {@code file}, opened (and created) on first use. */
    public static JournalResponseCache open(Path file) {
        Path key = file.toAbsolutePath().normalize();
        return OPEN.compute(key, (k, existing) -> existing != null && !existing.isClosed()
                ? existing : new JournalResponseCache(k));
    }

    public Path file() {
        return file;
    }

    @Override
    public Optional<Entry> get(String key) {
        return memory.get(key);
    }

    @Override
    public synchronized void put(String key, String reply, Instant cachedAt, Instant expiresAt,
                                 Collection<SheetRef> sheets) {
        Stored stored = new Stored(new Entry(reply, cachedAt, expiresAt), new ArrayList<>(new LinkedHashSet<>(sheets)));
        append(encodePut(key, stored), "write");
        memory.put(key, stored);
        compactIfNeeded();
    }

    @Override
    public synchronized int invalidate(String spreadsheetId, String worksheet) {
        int removed = memory.invalidate(spreadsheetId, worksheet);
        if (removed > 0) {
            append(record(INVALIDATE, d -> {
                d.writeUTF(spreadsheetId);
                d.writeUTF(worksheet);
            }), "invalidate");
        }
        return removed;
    }

    @Override
    public synchronized int purgeExpired(Instant now) {
        int removed = memory.purgeExpired(now);
        if (removed > 0) {
            append(record(PURGE, d -> d.writeLong(now.toEpochMilli())), "purge");
            compactIfNeeded();
        }
        return removed;
    }

    @Override
    public synchronized void clear() {
        memory.clear();
        append(record(CLEAR, d -> { }), "clear");
        compactIfNeeded();
    }

    @Override
    public synchronized void close() {
        OPEN.remove(file, this);
        memory.close();
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (IOException e) {
            throw failure("close", e);
        } finally {
            out = null;
        }
    }

    /** Rewrites the journal with only the live entries. Done automatically as it grows. */
    public synchronized void compact() {
        ensureOpen("compact");
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            long written;
            try (OutputStream o = Files.newOutputStream(tmp)) {
                ByteArrayOutputStream header = new ByteArrayOutputStream();
                new DataOutputStream(header).writeInt(MAGIC);
                o.write(header.toByteArray());
                written = HEADER_BYTES;
                for (Map.Entry<String, Stored> e : memory.snapshot().entrySet()) {
                    byte[] rec = encodePut(e.getKey(), e.getValue());
                    o.write(rec);
                    written += rec.length;
                }
            }
            out.close();
            out = null;
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            fileBytes = written;
            compactAt = Math.max(MIN_COMPACT_BYTES, 2 * fileBytes);
        } catch (IOException e) {
            throw failure("compact", e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
                if (out == null) {
                    out = new FileOutputStream(file.toFile(), true);
                    fileBytes = Files.size(file);
                }
            } catch (IOException e) {
                // Leaves the cache closed; later calls fail and the client falls back to the network.
            }
        }
    }

    /** Current journal size in bytes, including records superseded since the last compaction. */
    public synchronized long journalBytes() {
        return fileBytes;
    }

    private void compactIfNeeded() {
        if (fileBytes >= compactAt) {
            compact();
        }
    }

    // ---- journal format: [int length][int crc32][length bytes: op + payload] ----

    private interface Payload {
        void write(DataOutputStream d) throws IOException;
    }

    private static byte[] record(byte op, Payload payload) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(body);
            d.writeByte(op);
            payload.write(d);
            d.flush();
            byte[] bytes = body.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(bytes, 0, bytes.length);
            ByteArrayOutputStream framed = new ByteArrayOutputStream(bytes.length + 8);
            DataOutputStream f = new DataOutputStream(framed);
            f.writeInt(bytes.length);
            f.writeInt((int) crc.getValue());
            f.write(bytes);
            f.flush();
            return framed.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e); // in-memory streams don't fail
        }
    }

    private static byte[] encodePut(String key, Stored stored) {
        return record(PUT, d -> {
            d.writeUTF(key);
            d.writeLong(stored.entry().cachedAt().toEpochMilli());
            d.writeLong(stored.entry().expiresAt().toEpochMilli());
            d.writeInt(stored.sheets().size());
            for (SheetRef s : stored.sheets()) {
                d.writeUTF(s.spreadsheetId());
                d.writeUTF(s.worksheet());
            }
            byte[] reply = stored.entry().reply().getBytes(StandardCharsets.UTF_8);
            d.writeInt(reply.length);
            d.write(reply);
        });
    }

    private void append(byte[] rec, String what) {
        ensureOpen(what);
        try {
            out.write(rec);
            out.flush();
            fileBytes += rec.length;
        } catch (IOException e) {
            throw failure(what, e);
        }
    }

    /** Applies every intact record to memory. @return bytes of the valid prefix (0 = empty file) */
    private long replay() throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 64 * 1024))) {
            int magic;
            try {
                magic = in.readInt();
            } catch (EOFException empty) {
                return 0;
            }
            if (magic != MAGIC) {
                throw new IOException("not a hibernate.sheets journal (is it a SQLite cache file?)");
            }
            long valid = HEADER_BYTES;
            while (true) {
                int length;
                int crc;
                byte[] body;
                try {
                    length = in.readInt();
                    crc = in.readInt();
                    if (length <= 0 || length > MAX_RECORD_BYTES) {
                        return valid;
                    }
                    body = new byte[length];
                    in.readFully(body);
                } catch (EOFException tornTail) {
                    return valid;
                }
                CRC32 check = new CRC32();
                check.update(body, 0, length);
                if ((int) check.getValue() != crc) {
                    return valid;
                }
                try {
                    apply(body);
                } catch (IOException unreadable) {
                    return valid;
                }
                valid += 8 + length;
            }
        }
    }

    private void apply(byte[] body) throws IOException {
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(body));
        byte op = d.readByte();
        switch (op) {
            case PUT -> {
                String key = d.readUTF();
                Instant cachedAt = Instant.ofEpochMilli(d.readLong());
                Instant expiresAt = Instant.ofEpochMilli(d.readLong());
                int n = d.readInt();
                List<SheetRef> sheets = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    sheets.add(new SheetRef(d.readUTF(), d.readUTF()));
                }
                byte[] reply = new byte[d.readInt()];
                d.readFully(reply);
                memory.put(key, new Stored(new Entry(new String(reply, StandardCharsets.UTF_8), cachedAt, expiresAt),
                        sheets));
            }
            case INVALIDATE -> memory.invalidate(d.readUTF(), d.readUTF());
            case PURGE -> memory.purgeExpired(Instant.ofEpochMilli(d.readLong()));
            case CLEAR -> memory.clear();
            default -> throw new IOException("unknown journal record " + op);
        }
    }

    private synchronized boolean isClosed() {
        return out == null;
    }

    private void ensureOpen(String what) {
        if (out == null) {
            throw new HibernateSheetsException("Response cache " + what + " failed (" + file + "): closed");
        }
    }

    private HibernateSheetsException failure(String what, IOException e) {
        return new HibernateSheetsException("Response cache " + what + " failed (" + file + "): " + e.getMessage(),
                e, false);
    }
}
