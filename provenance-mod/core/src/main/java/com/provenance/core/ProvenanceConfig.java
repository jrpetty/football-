package com.provenance.core;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Server-side configuration. Every threshold, eligibility rule and tracking
 * policy is data; nothing here needs a recompile to change.
 *
 * <p>Ships with the specification's default tables. Item eligibility is driven
 * by tags first and explicit ids second, which is what lets a modpack register
 * modded equipment without this mod knowing it exists.
 */
public final class ProvenanceConfig {

    private final EnumMap<ItemCategory, MilestoneTrack> tracks = new EnumMap<>(ItemCategory.class);

    /** Tag id to category, e.g. {@code c:tools/pickaxe} to PICKAXE. */
    private final Map<String, ItemCategory> tagCategories = new LinkedHashMap<>();

    /** Explicit item id to category. Beats tags, for one-off modded items. */
    private final Map<String, ItemCategory> itemCategories = new LinkedHashMap<>();

    /** Item ids that are never tracked, whatever their tags say. */
    private final Set<String> excludedItems = new LinkedHashSet<>();

    private boolean creativeActionsCount = false;
    private boolean teleportDistanceCounts = false;
    private int distanceIntervalTicks = 100;
    private int repairSessionWindowSeconds = 30;
    private int destroyedRecordRetentionDays = 90;
    private boolean reducedAnimations = false;
    private int contributorPageSize = 25;
    /** Below this, a movement sample is treated as jitter and dropped. */
    private int minimumDistanceSampleCm = 50;

    public static ProvenanceConfig withDefaults() {
        ProvenanceConfig config = new ProvenanceConfig();
        config.installDefaultTracks();
        config.installDefaultTags();
        return config;
    }

    private void installDefaultTracks() {
        // Melee weapons, and ranged weapons and combat tridents, share a ladder.
        Map<MilestoneTier, Long> combat = ladder(10, 50, 250, 1_000, 5_000, 25_000, 100_000);
        tracks.put(ItemCategory.MELEE_WEAPON, new MilestoneTrack(StatKey.KILLS, combat));
        tracks.put(ItemCategory.RANGED_WEAPON, new MilestoneTrack(StatKey.KILLS, combat));
        tracks.put(ItemCategory.TRIDENT, new MilestoneTrack(StatKey.KILLS, combat));

        tracks.put(ItemCategory.PICKAXE, new MilestoneTrack(StatKey.BLOCKS_MINED,
                ladder(100, 1_000, 10_000, 100_000, 500_000, 1_000_000, 5_000_000)));

        tracks.put(ItemCategory.AXE, new MilestoneTrack(StatKey.LOGS_CHOPPED,
                ladder(50, 500, 5_000, 25_000, 100_000, 500_000, 2_000_000)));

        tracks.put(ItemCategory.SHOVEL, new MilestoneTrack(StatKey.BLOCKS_DUG,
                ladder(100, 1_000, 10_000, 50_000, 250_000, 1_000_000, 5_000_000)));

        tracks.put(ItemCategory.HOE, new MilestoneTrack(StatKey.BLOCKS_WORKED,
                ladder(50, 500, 5_000, 25_000, 100_000, 500_000, 2_000_000)));

        tracks.put(ItemCategory.SHIELD, new MilestoneTrack(StatKey.ATTACKS_BLOCKED,
                ladder(25, 250, 2_500, 10_000, 50_000, 250_000, 1_000_000)));

        tracks.put(ItemCategory.ARMOUR, new MilestoneTrack(StatKey.DAMAGE_ABSORBED,
                ladder(100, 1_000, 5_000, 25_000, 100_000, 500_000, 2_000_000)));

        tracks.put(ItemCategory.FISHING_ROD, new MilestoneTrack(StatKey.CATCHES_TOTAL,
                ladder(25, 250, 1_000, 5_000, 25_000, 100_000, 500_000)));

        // Shears and other utility tools: a configurable primary use counter,
        // scaled to how often such a tool is realistically swung.
        Map<MilestoneTier, Long> utility = ladder(25, 250, 2_000, 10_000, 50_000, 200_000, 1_000_000);
        tracks.put(ItemCategory.SHEARS, new MilestoneTrack(StatKey.UTILITY_USES, utility));
        tracks.put(ItemCategory.OTHER, new MilestoneTrack(StatKey.UTILITY_USES, utility));
    }

    private static Map<MilestoneTier, Long> ladder(long initiated, long proven, long seasoned, long veteran,
                                                   long master, long legendary, long relic) {
        EnumMap<MilestoneTier, Long> map = new EnumMap<>(MilestoneTier.class);
        map.put(MilestoneTier.INITIATED, initiated);
        map.put(MilestoneTier.PROVEN, proven);
        map.put(MilestoneTier.SEASONED, seasoned);
        map.put(MilestoneTier.VETERAN, veteran);
        map.put(MilestoneTier.MASTER, master);
        map.put(MilestoneTier.LEGENDARY, legendary);
        map.put(MilestoneTier.SERVER_RELIC, relic);
        return map;
    }

    private void installDefaultTags() {
        tagCategories.put("minecraft:swords", ItemCategory.MELEE_WEAPON);
        tagCategories.put("c:tools/sword", ItemCategory.MELEE_WEAPON);
        tagCategories.put("c:tools/melee_weapon", ItemCategory.MELEE_WEAPON);
        tagCategories.put("c:tools/bow", ItemCategory.RANGED_WEAPON);
        tagCategories.put("c:tools/crossbow", ItemCategory.RANGED_WEAPON);
        tagCategories.put("c:tools/ranged_weapon", ItemCategory.RANGED_WEAPON);
        tagCategories.put("c:tools/spear", ItemCategory.TRIDENT);
        tagCategories.put("minecraft:pickaxes", ItemCategory.PICKAXE);
        tagCategories.put("c:tools/pickaxe", ItemCategory.PICKAXE);
        tagCategories.put("minecraft:axes", ItemCategory.AXE);
        tagCategories.put("c:tools/axe", ItemCategory.AXE);
        tagCategories.put("minecraft:shovels", ItemCategory.SHOVEL);
        tagCategories.put("c:tools/shovel", ItemCategory.SHOVEL);
        tagCategories.put("minecraft:hoes", ItemCategory.HOE);
        tagCategories.put("c:tools/hoe", ItemCategory.HOE);
        tagCategories.put("c:tools/shear", ItemCategory.SHEARS);
        tagCategories.put("c:tools/fishing_rod", ItemCategory.FISHING_ROD);
        tagCategories.put("c:tools/shield", ItemCategory.SHIELD);
        tagCategories.put("minecraft:head_armor", ItemCategory.ARMOUR);
        tagCategories.put("minecraft:chest_armor", ItemCategory.ARMOUR);
        tagCategories.put("minecraft:leg_armor", ItemCategory.ARMOUR);
        tagCategories.put("minecraft:foot_armor", ItemCategory.ARMOUR);
        tagCategories.put("c:armors", ItemCategory.ARMOUR);

        // Vanilla items whose tags do not cover them.
        itemCategories.put("minecraft:trident", ItemCategory.TRIDENT);
        itemCategories.put("minecraft:bow", ItemCategory.RANGED_WEAPON);
        itemCategories.put("minecraft:crossbow", ItemCategory.RANGED_WEAPON);
        itemCategories.put("minecraft:shield", ItemCategory.SHIELD);
        itemCategories.put("minecraft:shears", ItemCategory.SHEARS);
        itemCategories.put("minecraft:fishing_rod", ItemCategory.FISHING_ROD);
    }

    /**
     * Resolves an item's category from its id and tags, or null when the item
     * is not eligible for tracking at all.
     *
     * <p>Explicit exclusions win, then explicit ids, then tags. Resolution
     * happens once per item at registration.
     */
    public ItemCategory resolveCategory(String itemId, Iterable<String> tags) {
        if (itemId == null || excludedItems.contains(itemId)) {
            return null;
        }
        ItemCategory explicit = itemCategories.get(itemId);
        if (explicit != null) {
            return explicit;
        }
        if (tags != null) {
            for (String tag : tags) {
                ItemCategory byTag = tagCategories.get(tag);
                if (byTag != null) {
                    return byTag;
                }
            }
        }
        return null;
    }

    public boolean isEligible(String itemId, Iterable<String> tags) {
        return resolveCategory(itemId, tags) != null;
    }

    public MilestoneTrack track(ItemCategory category) {
        MilestoneTrack track = tracks.get(category);
        if (track == null) {
            track = tracks.get(ItemCategory.OTHER);
        }
        return track;
    }

    public void setTrack(ItemCategory category, MilestoneTrack track) {
        tracks.put(category, track);
    }

    public void mapTag(String tag, ItemCategory category) {
        tagCategories.put(tag, category);
    }

    public void mapItem(String itemId, ItemCategory category) {
        itemCategories.put(itemId, category);
    }

    public void exclude(String itemId) {
        excludedItems.add(itemId);
    }

    public Set<String> excludedItems() {
        return excludedItems;
    }

    public Map<String, ItemCategory> tagCategories() {
        return tagCategories;
    }

    public Map<String, ItemCategory> itemCategories() {
        return itemCategories;
    }

    public boolean creativeActionsCount() {
        return creativeActionsCount;
    }

    public void setCreativeActionsCount(boolean v) {
        this.creativeActionsCount = v;
    }

    public boolean teleportDistanceCounts() {
        return teleportDistanceCounts;
    }

    public void setTeleportDistanceCounts(boolean v) {
        this.teleportDistanceCounts = v;
    }

    public int distanceIntervalTicks() {
        return distanceIntervalTicks;
    }

    public void setDistanceIntervalTicks(int v) {
        this.distanceIntervalTicks = Math.max(1, v);
    }

    public int repairSessionWindowSeconds() {
        return repairSessionWindowSeconds;
    }

    public void setRepairSessionWindowSeconds(int v) {
        this.repairSessionWindowSeconds = Math.max(0, v);
    }

    public int destroyedRecordRetentionDays() {
        return destroyedRecordRetentionDays;
    }

    public void setDestroyedRecordRetentionDays(int v) {
        this.destroyedRecordRetentionDays = v;
    }

    public boolean reducedAnimations() {
        return reducedAnimations;
    }

    public void setReducedAnimations(boolean v) {
        this.reducedAnimations = v;
    }

    public int contributorPageSize() {
        return contributorPageSize;
    }

    public void setContributorPageSize(int v) {
        this.contributorPageSize = Math.max(1, v);
    }

    public int minimumDistanceSampleCm() {
        return minimumDistanceSampleCm;
    }

    public void setMinimumDistanceSampleCm(int v) {
        this.minimumDistanceSampleCm = Math.max(0, v);
    }
}
