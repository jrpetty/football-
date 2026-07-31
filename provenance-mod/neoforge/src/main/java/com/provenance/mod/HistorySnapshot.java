package com.provenance.mod;

import com.provenance.core.Contribution;
import com.provenance.core.ContributorQuery;
import com.provenance.core.ItemCategory;
import com.provenance.core.ItemRecord;
import com.provenance.core.MilestoneAward;
import com.provenance.core.MilestoneTier;
import com.provenance.core.MilestoneTrack;
import com.provenance.core.StatBucket;
import com.provenance.core.StatKey;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the Item History screen needs, resolved server-side and sent as
 * one read-only snapshot.
 *
 * <p>The client receives finished numbers and never a means to change them.
 * There is no path from this class back into a record, so a modified client can
 * misdraw its own screen and nothing more.
 */
public record HistorySnapshot(
        String customName,
        String itemTypeId,
        String originalItemTypeId,
        String category,
        String crafterName,
        String manufacturerName,
        long createdEpochMilli,
        boolean createdApproximate,
        String ownerName,
        String originId,
        int currentTierIndex,
        String primaryStatId,
        long primaryStatValue,
        long nextTierThreshold,
        long currentTierThreshold,
        Map<String, Long> overallStats,
        Map<String, Long> ownerStats,
        String ownerDisplayName,
        List<TierEntry> tiers,
        List<ContributorRow> contributors,
        int contributorPageIndex,
        int contributorPageCount,
        int contributorTotal,
        String mostFrequentPrimary
) {

    /** One rung of the milestone ladder as the Milestones tab shows it. */
    public record TierEntry(int tierIndex, boolean achieved, long achievedEpochMilli,
                            long threshold, long triggeringValue) {
    }

    /** One row of the Contributors tab. */
    public record ContributorRow(String name, long primary, long secondary, long repairs,
                                 long distanceCm, long timeTicks, boolean isOwner, boolean isCrafter) {
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    public static HistorySnapshot of(ItemRecord record, MilestoneTrack track,
                                     ContributorQuery.Page page, StatKey secondary) {
        MilestoneTier current = record.currentTier();
        StatKey primary = track.primaryStat();
        long primaryValue = record.overall().getOrZero(primary);

        List<TierEntry> tiers = new ArrayList<>(MilestoneTier.values().length);
        for (MilestoneTier tier : MilestoneTier.values()) {
            MilestoneAward award = record.award(tier);
            Long threshold = track.threshold(tier);
            tiers.add(new TierEntry(
                    tier.index(),
                    award != null,
                    award == null ? 0L : award.achievedEpochMilli(),
                    threshold == null ? 0L : threshold,
                    award == null ? 0L : award.triggeringValue()));
        }

        List<ContributorRow> rows = new ArrayList<>(page.rows().size());
        for (ContributorQuery.Row row : page.rows()) {
            rows.add(new ContributorRow(row.name(), row.primaryValue(), row.secondaryValue(),
                    row.repairs(), row.distanceCm(), row.timeTicks(),
                    row.isCurrentOwner(), row.isOriginalCrafter()));
        }

        MilestoneTier next = track.nextTier(current);
        Long nextThreshold = next == null ? null : track.threshold(next);
        Long currentThreshold = current == null ? null : track.threshold(current);

        Contribution ownerContribution = record.currentOwnerContribution();

        return new HistorySnapshot(
                record.customName() == null ? "" : record.customName(),
                record.currentItemId(),
                record.originalItemId(),
                record.category().id(),
                record.crafterDisplayName(),
                record.manufacturerDisplayName(),
                record.createdEpochMilli(),
                record.creationDateApproximate(),
                record.currentOwnerDisplayName(),
                record.origin().id(),
                current == null ? -1 : current.index(),
                primary.id(),
                primaryValue,
                nextThreshold == null ? -1L : nextThreshold,
                currentThreshold == null ? 0L : currentThreshold,
                statMap(record.category(), record.overall()),
                ownerContribution == null
                        ? Map.of()
                        : statMap(record.category(), ownerContribution.stats()),
                record.currentOwnerDisplayName(),
                tiers,
                rows,
                page.pageIndex(),
                page.pageCount(),
                page.totalRows(),
                mostFrequentFor(record));
    }

    /**
     * Only the statistics relevant to the item's category are sent.
     *
     * <p>Both views use the item's own category, so the Overall and Current
     * Owner tabs show the same rows in the same order and can be compared
     * directly — an owner view listing fishing statistics on a pickaxe would be
     * noise.
     */
    private static Map<String, Long> statMap(ItemCategory category, StatBucket source) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (StatKey key : relevantStats(category)) {
            if (source.has(key) || key.aggregation() == StatKey.Aggregation.SUM) {
                out.put(key.id(), source.getOrZero(key));
            }
        }
        return out;
    }

    private static final EnumMap<ItemCategory, List<StatKey>> RELEVANT = new EnumMap<>(ItemCategory.class);

    static {
        RELEVANT.put(ItemCategory.MELEE_WEAPON, List.of(StatKey.KILLS, StatKey.DAMAGE_DEALT,
                StatKey.HITS_LANDED, StatKey.REPAIRS, StatKey.DISTANCE_CM));
        RELEVANT.put(ItemCategory.RANGED_WEAPON, List.of(StatKey.KILLS,
                StatKey.PROJECTILE_HITS, StatKey.DAMAGE_DEALT, StatKey.LONGEST_KILL_CM,
                StatKey.REPAIRS, StatKey.DISTANCE_CM));
        RELEVANT.put(ItemCategory.TRIDENT, List.of(StatKey.KILLS, StatKey.DAMAGE_DEALT, StatKey.HITS_LANDED,
                StatKey.PROJECTILE_HITS, StatKey.LONGEST_KILL_CM, StatKey.REPAIRS));
        RELEVANT.put(ItemCategory.PICKAXE, List.of(StatKey.BLOCKS_MINED, StatKey.ORES_MINED,
                StatKey.DEEPEST_BLOCK_Y, StatKey.REPAIRS, StatKey.DISTANCE_CM));
        RELEVANT.put(ItemCategory.AXE, List.of(StatKey.LOGS_CHOPPED, StatKey.BLOCKS_CHOPPED,
                StatKey.KILLS, StatKey.DAMAGE_DEALT, StatKey.REPAIRS, StatKey.DISTANCE_CM));
        RELEVANT.put(ItemCategory.SHOVEL, List.of(StatKey.BLOCKS_DUG,
                StatKey.REPAIRS, StatKey.DISTANCE_CM));
        RELEVANT.put(ItemCategory.HOE, List.of(StatKey.BLOCKS_WORKED,
                StatKey.CROPS_HARVESTED, StatKey.REPAIRS, StatKey.DISTANCE_CM));
        RELEVANT.put(ItemCategory.SHEARS, List.of(StatKey.UTILITY_USES,
                StatKey.BLOCKS_CUT, StatKey.REPAIRS, StatKey.DISTANCE_CM));
        RELEVANT.put(ItemCategory.FISHING_ROD, List.of(StatKey.CATCHES_TOTAL, StatKey.FISH_CAUGHT,
                StatKey.TREASURE_CAUGHT, StatKey.JUNK_CAUGHT, StatKey.REPAIRS, StatKey.DISTANCE_CM));
        RELEVANT.put(ItemCategory.ARMOUR, List.of(StatKey.DAMAGE_ABSORBED, StatKey.HITS_RECEIVED,
                StatKey.TIME_WORN_TICKS, StatKey.DISTANCE_CM, StatKey.REPAIRS, StatKey.DURABILITY_RESTORED));
        RELEVANT.put(ItemCategory.OTHER, List.of(StatKey.UTILITY_USES, StatKey.REPAIRS, StatKey.DISTANCE_CM));
    }

    private static List<StatKey> relevantStats(ItemCategory category) {
        return RELEVANT.getOrDefault(category, RELEVANT.get(ItemCategory.OTHER));
    }

    private static String mostFrequentFor(ItemRecord record) {
        var kind = switch (record.category()) {
            case PICKAXE -> com.provenance.core.BreakdownKind.BLOCK_MINED;
            case AXE -> com.provenance.core.BreakdownKind.WOOD_CHOPPED;
            case SHOVEL -> com.provenance.core.BreakdownKind.BLOCK_DUG;
            case HOE -> com.provenance.core.BreakdownKind.CROP_WORKED;
            case FISHING_ROD -> com.provenance.core.BreakdownKind.CATCH_TYPE;
            default -> com.provenance.core.BreakdownKind.MOB_KILLED;
        };
        String value = record.overall().mostFrequent(kind);
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------------
    // Wire format
    // ------------------------------------------------------------------

    public static void write(FriendlyByteBuf buf, HistorySnapshot s) {
        buf.writeUtf(s.customName());
        buf.writeUtf(s.itemTypeId());
        buf.writeUtf(s.originalItemTypeId());
        buf.writeUtf(s.category());
        buf.writeUtf(s.crafterName());
        buf.writeUtf(s.manufacturerName());
        buf.writeLong(s.createdEpochMilli());
        buf.writeBoolean(s.createdApproximate());
        buf.writeUtf(s.ownerName());
        buf.writeUtf(s.originId());
        buf.writeVarInt(s.currentTierIndex() + 1);
        buf.writeUtf(s.primaryStatId());
        buf.writeLong(s.primaryStatValue());
        buf.writeLong(s.nextTierThreshold());
        buf.writeLong(s.currentTierThreshold());
        writeStats(buf, s.overallStats());
        writeStats(buf, s.ownerStats());
        buf.writeUtf(s.ownerDisplayName());

        buf.writeVarInt(s.tiers().size());
        for (TierEntry tier : s.tiers()) {
            buf.writeVarInt(tier.tierIndex());
            buf.writeBoolean(tier.achieved());
            buf.writeLong(tier.achievedEpochMilli());
            buf.writeLong(tier.threshold());
            buf.writeLong(tier.triggeringValue());
        }

        buf.writeVarInt(s.contributors().size());
        for (ContributorRow row : s.contributors()) {
            buf.writeUtf(row.name());
            buf.writeLong(row.primary());
            buf.writeLong(row.secondary());
            buf.writeLong(row.repairs());
            buf.writeLong(row.distanceCm());
            buf.writeLong(row.timeTicks());
            buf.writeBoolean(row.isOwner());
            buf.writeBoolean(row.isCrafter());
        }

        buf.writeVarInt(s.contributorPageIndex());
        buf.writeVarInt(s.contributorPageCount());
        buf.writeVarInt(s.contributorTotal());
        buf.writeUtf(s.mostFrequentPrimary());
    }

    public static HistorySnapshot read(FriendlyByteBuf buf) {
        String customName = buf.readUtf();
        String itemTypeId = buf.readUtf();
        String originalItemTypeId = buf.readUtf();
        String category = buf.readUtf();
        String crafterName = buf.readUtf();
        String manufacturerName = buf.readUtf();
        long created = buf.readLong();
        boolean approximate = buf.readBoolean();
        String ownerName = buf.readUtf();
        String originId = buf.readUtf();
        int tierIndex = buf.readVarInt() - 1;
        String primaryStatId = buf.readUtf();
        long primaryValue = buf.readLong();
        long nextThreshold = buf.readLong();
        long currentThreshold = buf.readLong();
        Map<String, Long> overall = readStats(buf);
        Map<String, Long> owner = readStats(buf);
        String ownerDisplay = buf.readUtf();

        int tierCount = buf.readVarInt();
        List<TierEntry> tiers = new ArrayList<>(tierCount);
        for (int i = 0; i < tierCount; i++) {
            tiers.add(new TierEntry(buf.readVarInt(), buf.readBoolean(),
                    buf.readLong(), buf.readLong(), buf.readLong()));
        }

        int rowCount = buf.readVarInt();
        List<ContributorRow> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            rows.add(new ContributorRow(buf.readUtf(), buf.readLong(), buf.readLong(),
                    buf.readLong(), buf.readLong(), buf.readLong(),
                    buf.readBoolean(), buf.readBoolean()));
        }

        return new HistorySnapshot(customName, itemTypeId, originalItemTypeId, category,
                crafterName, manufacturerName, created, approximate, ownerName, originId,
                tierIndex, primaryStatId, primaryValue, nextThreshold, currentThreshold,
                overall, owner, ownerDisplay, tiers, rows,
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf());
    }

    private static void writeStats(FriendlyByteBuf buf, Map<String, Long> stats) {
        buf.writeVarInt(stats.size());
        stats.forEach((key, value) -> {
            buf.writeUtf(key);
            buf.writeLong(value);
        });
    }

    private static Map<String, Long> readStats(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        Map<String, Long> stats = new java.util.LinkedHashMap<>(Math.max(4, size));
        for (int i = 0; i < size; i++) {
            stats.put(buf.readUtf(), buf.readLong());
        }
        return stats;
    }
}
