package com.provenance.core;

import java.util.UUID;

/**
 * The entire payload stored on the physical item: an Item Record ID and the
 * current anti-duplication token.
 *
 * <p>Deliberately tiny. Contributor lists, statistics and milestone dates all
 * live server-side keyed by {@link #recordId()}, so item components never grow
 * with an item's history and a client can never see or edit a counter.
 */
public record ItemStamp(UUID recordId, long bindingToken) {

    public static ItemStamp of(ItemRecord record) {
        return new ItemStamp(record.recordId(), record.bindingToken());
    }
}
