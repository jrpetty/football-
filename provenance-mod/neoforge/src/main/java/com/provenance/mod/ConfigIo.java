package com.provenance.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.provenance.core.ItemCategory;
import com.provenance.core.MilestoneTier;
import com.provenance.core.MilestoneTrack;
import com.provenance.core.ProvenanceConfig;
import com.provenance.core.StatKey;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Reads and writes the server configuration file.
 *
 * <p>Written out with the shipped defaults on first run so an administrator has
 * a complete, editable file rather than having to discover the keys. Every
 * threshold, eligibility mapping and tracking policy is here; none of it needs
 * a recompile.
 */
public final class ConfigIo {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ConfigIo() {
    }

    public static ProvenanceConfig loadOrCreate(Path path) {
        ProvenanceConfig config = ProvenanceConfig.withDefaults();
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    apply(config, JsonParser.parseReader(reader).getAsJsonObject());
                }
                Provenance.LOGGER.info("Loaded provenance configuration from {}", path);
            } else {
                Files.createDirectories(path.getParent());
                write(config, path);
                Provenance.LOGGER.info("Wrote default provenance configuration to {}", path);
            }
        } catch (IOException | RuntimeException e) {
            // A broken config must not take the server down; fall back to the
            // shipped defaults and say so loudly.
            Provenance.LOGGER.error("Could not read provenance configuration at {}; using defaults", path, e);
        }
        return config;
    }

    private static void apply(ProvenanceConfig config, JsonObject root) {
        if (root.has("creativeActionsCount")) {
            config.setCreativeActionsCount(root.get("creativeActionsCount").getAsBoolean());
        }
        if (root.has("teleportDistanceCounts")) {
            config.setTeleportDistanceCounts(root.get("teleportDistanceCounts").getAsBoolean());
        }
        if (root.has("distanceIntervalTicks")) {
            config.setDistanceIntervalTicks(root.get("distanceIntervalTicks").getAsInt());
        }
        if (root.has("repairSessionWindowSeconds")) {
            config.setRepairSessionWindowSeconds(root.get("repairSessionWindowSeconds").getAsInt());
        }
        if (root.has("destroyedRecordRetentionDays")) {
            config.setDestroyedRecordRetentionDays(root.get("destroyedRecordRetentionDays").getAsInt());
        }
        if (root.has("reducedAnimations")) {
            config.setReducedAnimations(root.get("reducedAnimations").getAsBoolean());
        }
        if (root.has("contributorPageSize")) {
            config.setContributorPageSize(root.get("contributorPageSize").getAsInt());
        }
        if (root.has("minimumDistanceSampleCm")) {
            config.setMinimumDistanceSampleCm(root.get("minimumDistanceSampleCm").getAsInt());
        }
        if (root.has("usageFlushIntervalSeconds")) {
            config.setUsageFlushIntervalSeconds(root.get("usageFlushIntervalSeconds").getAsInt());
        }

        if (root.has("excludedItems")) {
            root.getAsJsonArray("excludedItems").forEach(e -> config.exclude(e.getAsString()));
        }
        if (root.has("itemCategories")) {
            root.getAsJsonObject("itemCategories").entrySet().forEach(e ->
                    config.mapItem(e.getKey(), ItemCategory.byId(e.getValue().getAsString())));
        }
        if (root.has("tagCategories")) {
            root.getAsJsonObject("tagCategories").entrySet().forEach(e ->
                    config.mapTag(e.getKey(), ItemCategory.byId(e.getValue().getAsString())));
        }

        if (root.has("milestones")) {
            JsonObject milestones = root.getAsJsonObject("milestones");
            for (ItemCategory category : ItemCategory.values()) {
                if (!milestones.has(category.id())) {
                    continue;
                }
                JsonObject entry = milestones.getAsJsonObject(category.id());
                StatKey primary = entry.has("primaryStat")
                        ? StatKey.byId(entry.get("primaryStat").getAsString())
                        : config.track(category).primaryStat();

                EnumMap<MilestoneTier, Long> thresholds = new EnumMap<>(MilestoneTier.class);
                JsonObject tiers = entry.getAsJsonObject("thresholds");
                for (MilestoneTier tier : MilestoneTier.values()) {
                    String key = tier.name().toLowerCase(java.util.Locale.ROOT);
                    if (tiers != null && tiers.has(key)) {
                        thresholds.put(tier, tiers.get(key).getAsLong());
                    } else {
                        thresholds.put(tier, config.track(category).threshold(tier));
                    }
                }
                config.setTrack(category, new MilestoneTrack(primary, thresholds));
            }
        }
    }

    public static void write(ProvenanceConfig config, Path path) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("_comment", "Provenance, Antique and Item Legacy. Thresholds and eligibility are data; "
                + "editing this file needs no recompile.");
        root.addProperty("creativeActionsCount", config.creativeActionsCount());
        root.addProperty("teleportDistanceCounts", config.teleportDistanceCounts());
        root.addProperty("distanceIntervalTicks", config.distanceIntervalTicks());
        root.addProperty("repairSessionWindowSeconds", config.repairSessionWindowSeconds());
        root.addProperty("destroyedRecordRetentionDays", config.destroyedRecordRetentionDays());
        root.addProperty("reducedAnimations", config.reducedAnimations());
        root.addProperty("contributorPageSize", config.contributorPageSize());
        root.addProperty("minimumDistanceSampleCm", config.minimumDistanceSampleCm());
        root.addProperty("usageFlushIntervalSeconds", config.usageFlushIntervalSeconds());

        var excluded = new com.google.gson.JsonArray();
        config.excludedItems().forEach(excluded::add);
        root.add("excludedItems", excluded);

        JsonObject items = new JsonObject();
        config.itemCategories().forEach((id, category) -> items.addProperty(id, category.id()));
        root.add("itemCategories", items);

        JsonObject tags = new JsonObject();
        config.tagCategories().forEach((id, category) -> tags.addProperty(id, category.id()));
        root.add("tagCategories", tags);

        JsonObject milestones = new JsonObject();
        for (ItemCategory category : ItemCategory.values()) {
            MilestoneTrack track = config.track(category);
            JsonObject entry = new JsonObject();
            entry.addProperty("primaryStat", track.primaryStat().id());
            JsonObject thresholds = new JsonObject();
            for (Map.Entry<MilestoneTier, Long> e : track.thresholds().entrySet()) {
                thresholds.addProperty(e.getKey().name().toLowerCase(java.util.Locale.ROOT), e.getValue());
            }
            entry.add("thresholds", thresholds);
            milestones.add(category.id(), entry);
        }
        root.add("milestones", milestones);

        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
    }
}
