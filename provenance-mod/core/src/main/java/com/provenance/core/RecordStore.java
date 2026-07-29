package com.provenance.core;

import java.util.UUID;

/**
 * Persistent home of every {@link ItemRecord}, keyed by Item Record ID.
 *
 * <p>Implementations must treat {@link #load} as authoritative and must never
 * fabricate a record for an unknown id — a stack claiming an id that does not
 * exist has to be re-registered rather than silently granted a history.
 */
public interface RecordStore extends AutoCloseable {

    /** Returns the record, or null when no such id has ever existed. */
    ItemRecord load(UUID recordId);

    /** Adds a newly created record. Throws if the id is already present. */
    void insert(ItemRecord record);

    /** Marks a record for the next write pass. Cheap; never touches disk. */
    void save(ItemRecord record);

    /** True when the id exists on disk or in cache, destroyed or not. */
    boolean exists(UUID recordId);

    /** Writes every dirty record. Called off the main server thread. */
    void flush();

    @Override
    void close();
}
