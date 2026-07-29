package com.provenance.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.UUID;

/**
 * Converts an {@link ItemRecord} to and from JSON.
 *
 * <p>Written by hand against Gson's tree API rather than by reflection so the
 * on-disk shape is explicit and versioned. Unknown fields are ignored and
 * missing fields fall back to safe defaults, which is what allows an existing
 * server's data to survive a mod update.
 */
public final class RecordCodec {

    /** Bump when the on-disk shape changes incompatibly. */
    public static final int SCHEMA_VERSION = 1;

    private RecordCodec() {
    }

    public static JsonObject toJson(ItemRecord record) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA_VERSION);
        root.addProperty("recordId", record.recordId().toString());
        root.addProperty("originalItemId", record.originalItemId());
        root.addProperty("currentItemId", record.currentItemId());
        root.addProperty("category", record.category().id());
        root.addProperty("origin", record.origin().id());
        root.addProperty("createdEpochMilli", record.createdEpochMilli());
        root.addProperty("creationDateApproximate", record.creationDateApproximate());
        root.addProperty("bindingToken", record.bindingToken());
        root.addProperty("destroyed", record.isDestroyed());

        if (record.forkedFromRecordId() != null) {
            root.addProperty("forkedFrom", record.forkedFromRecordId().toString());
        }
        if (record.crafterId() != null) {
            root.addProperty("crafterId", record.crafterId().toString());
        }
        addIfPresent(root, "crafterName", rawCrafterName(record));
        addIfPresent(root, "companyId", record.companyId());
        addIfPresent(root, "companyName", rawCompanyName(record));
        addIfPresent(root, "customName", record.customName());

        if (record.currentOwnerId() != null) {
            root.addProperty("currentOwnerId", record.currentOwnerId().toString());
            addIfPresent(root, "currentOwnerName", record.currentOwnerDisplayName());
        }

        root.add("overall", bucketToJson(record.overall()));

        JsonObject contributors = new JsonObject();
        for (Contribution c : record.contributors()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", c.nameSnapshot());
            entry.addProperty("firstUsed", c.firstUsedEpochMilli());
            entry.addProperty("lastUsed", c.lastUsedEpochMilli());
            entry.add("stats", bucketToJson(c.stats()));
            contributors.add(c.playerId().toString(), entry);
        }
        root.add("contributors", contributors);

        JsonObject milestones = new JsonObject();
        for (Map.Entry<MilestoneTier, MilestoneAward> e : record.milestones().entrySet()) {
            MilestoneAward award = e.getValue();
            JsonObject entry = new JsonObject();
            entry.addProperty("achieved", award.achievedEpochMilli());
            entry.addProperty("stat", award.triggeringStat().id());
            entry.addProperty("value", award.triggeringValue());
            milestones.add(e.getKey().name(), entry);
        }
        root.add("milestones", milestones);

        return root;
    }

    /**
     * The stored crafter name, without the "Unknown" display substitution, so a
     * round trip does not turn an absent maker into a literal name.
     */
    private static String rawCrafterName(ItemRecord record) {
        String display = record.crafterDisplayName();
        return "Unknown".equals(display) && record.crafterId() == null ? null : display;
    }

    private static String rawCompanyName(ItemRecord record) {
        String display = record.manufacturerDisplayName();
        return "Independent".equals(display) || "Unknown".equals(display) ? null : display;
    }

    private static void addIfPresent(JsonObject object, String key, String value) {
        if (value != null && !value.isEmpty()) {
            object.addProperty(key, value);
        }
    }

    private static JsonObject bucketToJson(StatBucket bucket) {
        JsonObject root = new JsonObject();
        JsonObject stats = new JsonObject();
        for (Map.Entry<StatKey, Long> e : bucket.asMap().entrySet()) {
            stats.addProperty(e.getKey().id(), e.getValue());
        }
        root.add("stats", stats);

        JsonObject breakdowns = new JsonObject();
        for (Map.Entry<BreakdownKind, Map<String, Long>> e : bucket.breakdownsAsMap().entrySet()) {
            JsonObject inner = new JsonObject();
            for (Map.Entry<String, Long> v : e.getValue().entrySet()) {
                inner.addProperty(v.getKey(), v.getValue());
            }
            breakdowns.add(e.getKey().id(), inner);
        }
        root.add("breakdowns", breakdowns);
        return root;
    }

    public static ItemRecord fromJson(JsonObject root) {
        UUID recordId = UUID.fromString(root.get("recordId").getAsString());
        String originalItemId = root.get("originalItemId").getAsString();
        ItemCategory category = ItemCategory.byId(getString(root, "category", ItemCategory.OTHER.id()));

        ItemRecord.Builder builder = ItemRecord.builder(recordId, originalItemId, category)
                .currentItemId(getString(root, "currentItemId", originalItemId))
                .origin(Origin.byId(getString(root, "origin", Origin.FOUND.id())))
                .created(getLong(root, "createdEpochMilli", 0L), getBool(root, "creationDateApproximate", false))
                .bindingToken(getLong(root, "bindingToken", 0L))
                .customName(getString(root, "customName", null))
                .forkedFrom(root.has("forkedFrom")
                        ? UUID.fromString(root.get("forkedFrom").getAsString())
                        : null);

        if (root.has("crafterId")) {
            builder.crafter(UUID.fromString(root.get("crafterId").getAsString()),
                    getString(root, "crafterName", null));
        } else if (root.has("crafterName")) {
            builder.crafter(null, getString(root, "crafterName", null));
        }

        if (root.has("companyId")) {
            builder.company(root.get("companyId").getAsString(), getString(root, "companyName", null));
        }

        if (root.has("currentOwnerId")) {
            builder.owner(UUID.fromString(root.get("currentOwnerId").getAsString()),
                    getString(root, "currentOwnerName", null));
        }

        ItemRecord record = builder.build();

        if (root.has("overall")) {
            readBucket(root.getAsJsonObject("overall"), record.overall());
        }

        if (root.has("contributors")) {
            JsonObject contributors = root.getAsJsonObject("contributors");
            for (Map.Entry<String, JsonElement> e : contributors.entrySet()) {
                JsonObject entry = e.getValue().getAsJsonObject();
                Contribution contribution = new Contribution(
                        UUID.fromString(e.getKey()),
                        getString(entry, "name", ""),
                        getLong(entry, "firstUsed", 0L),
                        getLong(entry, "lastUsed", 0L));
                if (entry.has("stats")) {
                    readBucket(entry.getAsJsonObject("stats"), contribution.stats());
                }
                record.putContributorForLoad(contribution);
            }
        }

        if (root.has("milestones")) {
            JsonObject milestones = root.getAsJsonObject("milestones");
            for (Map.Entry<String, JsonElement> e : milestones.entrySet()) {
                JsonObject entry = e.getValue().getAsJsonObject();
                record.putAwardForLoad(new MilestoneAward(
                        MilestoneTier.byName(e.getKey()),
                        getLong(entry, "achieved", 0L),
                        StatKey.byId(getString(entry, "stat", StatKey.KILLS.id())),
                        getLong(entry, "value", 0L)));
            }
        }

        if (getBool(root, "destroyed", false)) {
            record.markDestroyed();
        }
        record.clearDirty();
        return record;
    }

    private static void readBucket(JsonObject root, StatBucket target) {
        if (root.has("stats")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("stats").entrySet()) {
                try {
                    target.put(StatKey.byId(e.getKey()), e.getValue().getAsLong());
                } catch (IllegalArgumentException ignored) {
                    // A stat removed in a later version. Drop it rather than fail the load.
                }
            }
        }
        if (root.has("breakdowns")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("breakdowns").entrySet()) {
                BreakdownKind kind;
                try {
                    kind = BreakdownKind.byId(e.getKey());
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> v : e.getValue().getAsJsonObject().entrySet()) {
                    target.putBreakdown(kind, v.getKey(), v.getValue().getAsLong());
                }
            }
        }
    }

    private static String getString(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    private static long getLong(JsonObject o, String key, long fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : fallback;
    }

    private static boolean getBool(JsonObject o, String key, boolean fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBoolean() : fallback;
    }
}
