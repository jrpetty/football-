package com.provenance.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A sharded, write-behind JSON store.
 *
 * <p>Design points that follow from running on a long-lived server:
 *
 * <ul>
 *   <li>One file per record, sharded into 256 directories by the first byte of
 *       the id, so no directory grows to a size that slows the filesystem.</li>
 *   <li>An access-ordered cache of bounded size. Only records currently being
 *       used are resident; a million historical items cost nothing until
 *       someone inspects them.</li>
 *   <li>Writes are deferred. Gameplay marks records dirty and returns; the
 *       actual serialisation happens in {@link #flush}, which the mod calls
 *       from a background thread. Nothing here blocks the server thread on
 *       disk.</li>
 *   <li>Every write goes to a temporary file and is then moved into place, so a
 *       crash mid-write cannot leave a half-written record.</li>
 * </ul>
 *
 * <p>Dirty records are never evicted, so no statistic can be lost to cache
 * pressure before it has been persisted.
 */
public final class FileRecordStore implements RecordStore {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path root;
    private final int maxCachedRecords;

    /** Access-ordered so the eldest entries are the least recently touched. */
    private final LinkedHashMap<UUID, ItemRecord> cache;

    public FileRecordStore(Path root) {
        this(root, 4096);
    }

    public FileRecordStore(Path root, int maxCachedRecords) {
        this.root = Objects.requireNonNull(root, "root");
        this.maxCachedRecords = Math.max(16, maxCachedRecords);
        this.cache = new LinkedHashMap<>(256, 0.75f, true);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create provenance data directory: " + root, e);
        }
    }

    @Override
    public ItemRecord load(UUID recordId) {
        if (recordId == null) {
            return null;
        }
        synchronized (cache) {
            ItemRecord cached = cache.get(recordId);
            if (cached != null) {
                return cached;
            }
        }

        ItemRecord loaded = readFromDisk(recordId);
        if (loaded == null) {
            return null;
        }

        synchronized (cache) {
            // Another thread may have loaded the same record while this one was
            // reading. Keep whichever instance won, so a record is never
            // represented by two live objects with diverging statistics.
            ItemRecord raced = cache.get(recordId);
            if (raced != null) {
                return raced;
            }
            cache.put(recordId, loaded);
            evictIfNeeded();
            return loaded;
        }
    }

    @Override
    public void insert(ItemRecord record) {
        Objects.requireNonNull(record, "record");
        synchronized (cache) {
            if (cache.containsKey(record.recordId())) {
                throw new IllegalStateException("Duplicate Item Record ID in cache: " + record.recordId());
            }
            if (Files.exists(pathFor(record.recordId()))) {
                throw new IllegalStateException("Duplicate Item Record ID on disk: " + record.recordId());
            }
            record.markDirty();
            cache.put(record.recordId(), record);
            evictIfNeeded();
        }
    }

    @Override
    public void save(ItemRecord record) {
        if (record != null) {
            record.markDirty();
            synchronized (cache) {
                cache.putIfAbsent(record.recordId(), record);
            }
        }
    }

    @Override
    public boolean exists(UUID recordId) {
        if (recordId == null) {
            return false;
        }
        synchronized (cache) {
            if (cache.containsKey(recordId)) {
                return true;
            }
        }
        return Files.exists(pathFor(recordId));
    }

    @Override
    public boolean delete(UUID recordId) {
        if (recordId == null) {
            return false;
        }
        synchronized (cache) {
            ItemRecord cached = cache.remove(recordId);
            if (cached != null) {
                // Drop any unwritten changes: the item is gone, so persisting
                // its last few blocks mined would only recreate the file.
                cached.clearDirty();
            }
        }
        try {
            return Files.deleteIfExists(pathFor(recordId));
        } catch (IOException e) {
            throw new IllegalStateException("Could not delete provenance record " + recordId, e);
        }
    }

    @Override
    public void flush() {
        List<ItemRecord> pending = new ArrayList<>();
        synchronized (cache) {
            for (ItemRecord record : cache.values()) {
                if (record.isDirty()) {
                    pending.add(record);
                }
            }
        }
        for (ItemRecord record : pending) {
            JsonObject json;
            synchronized (record) {
                json = RecordCodec.toJson(record);
                record.clearDirty();
            }
            try {
                writeToDisk(record.recordId(), json);
            } catch (IOException e) {
                // Re-dirty so the next pass retries rather than losing the write.
                record.markDirty();
                throw new IllegalStateException("Failed to persist record " + record.recordId(), e);
            }
        }
        synchronized (cache) {
            evictIfNeeded();
        }
    }

    @Override
    public void close() {
        flush();
        synchronized (cache) {
            cache.clear();
        }
    }

    /**
     * Drops the least recently used clean records once the cache is over
     * budget. Dirty records are skipped: losing one would lose statistics that
     * have not reached disk.
     */
    private void evictIfNeeded() {
        if (cache.size() <= maxCachedRecords) {
            return;
        }
        Iterator<Map.Entry<UUID, ItemRecord>> it = cache.entrySet().iterator();
        while (it.hasNext() && cache.size() > maxCachedRecords) {
            Map.Entry<UUID, ItemRecord> entry = it.next();
            if (!entry.getValue().isDirty()) {
                it.remove();
            }
        }
    }

    private ItemRecord readFromDisk(UUID recordId) {
        Path path = pathFor(recordId);
        if (!Files.exists(path)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            return RecordCodec.fromJson(json);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Corrupt provenance record at " + path, e);
        }
    }

    private void writeToDisk(UUID recordId, JsonObject json) throws IOException {
        Path path = pathFor(recordId);
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Deletes records for items that no longer exist and whose retention period
     * has elapsed.
     *
     * <p>Without this, every tool that ever broke leaves a file behind forever.
     * Only records explicitly marked destroyed are eligible — a live item is
     * never removed however old it is, because age is the whole point of the
     * feature.
     *
     * <p>Uses the file's last-modified time as the destruction time. A record is
     * written once when it is marked destroyed and never touched again, so the
     * two coincide, and it avoids reading files that are obviously too recent.
     *
     * <p>Expensive enough to belong on the background thread at startup, not on
     * the server thread.
     *
     * @param retentionDays days to keep destroyed records; negative keeps forever
     * @return how many records were removed
     */
    public int pruneDestroyed(int retentionDays) {
        if (retentionDays < 0) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - (retentionDays * 86_400_000L);
        int removed = 0;

        try (var shards = Files.list(root)) {
            for (Path shard : shards.filter(Files::isDirectory).toList()) {
                try (var files = Files.list(shard)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                        if (Files.getLastModifiedTime(file).toMillis() >= cutoff) {
                            continue;
                        }
                        UUID id = idOf(file);
                        if (id == null) {
                            continue;
                        }
                        synchronized (cache) {
                            if (cache.containsKey(id)) {
                                continue;
                            }
                        }
                        ItemRecord record = readFromDisk(id);
                        if (record != null && record.isDestroyed()) {
                            Files.deleteIfExists(file);
                            removed++;
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Provenance retention sweep failed under " + root, e);
        }
        return removed;
    }

    private static UUID idOf(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".json")) {
            return null;
        }
        try {
            return UUID.fromString(name.substring(0, name.length() - ".json".length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code <root>/ab/ab3f....json} — 256 shards keyed on the first byte. */
    Path pathFor(UUID recordId) {
        String id = recordId.toString();
        String shard = id.substring(0, 2);
        return root.resolve(shard).resolve(id + ".json");
    }

    /** Test and administration hook: number of resident records. */
    public int cachedCount() {
        synchronized (cache) {
            return cache.size();
        }
    }
}
