package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.BeehiveBlock;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [roles-d] The outdoor trades' own trouble with the weather and the year (the fisher's storm is Fleet's and Ferries'):
 * <ul>
 * <li><b>The woodcutter and the hunter come in out of the rain.</b> Where it is raining on their ground (the biome's own
 *     rain, so a desert's or a snowfield's ground never is), their day's work stops: wet axes slip and wet game goes to
 *     ground. Off shift, they go home like anybody caught in the rain (VillageFolkEntity's evening), and go back out when
 *     it clears. The raid's bell already calls everybody but the watch in (VillageFolkEntity.onShift).</li>
 * <li><b>The beekeeper's winter.</b> In the town's winter (Seasons) the honey is the bees' own store: no comb or honey is
 *     taken, no bees are bred and no flowers set; the keeper looks its hives over once a day and spends the rest at the
 *     houses' wants (Crafts.beekeep, its luxuries).</li>
 * </ul>
 * Cost: a weather look per call on a folk's shift (a boolean and a biome lookup), and a map read a day per beekeeper.
 */
public final class OutdoorWeather {

    private OutdoorWeather() {}

    /** Tests: the rain as the woods and the hunting grounds see it (true wet, false dry), or the world's (null). */
    @Nullable private static volatile Boolean rainForTests;
    /** The day each beekeeper last looked its hives over in winter. */
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        rainForTests = null;
        LOOKED.clear();
    }

    public static void rainForTests(@Nullable Boolean wet) {
        rainForTests = wet;
    }

    /** A trade the rain sends home: the woodcutter and the hunter. */
    static boolean rainSendsHome(StationTask t) {
        return t == StationTask.WOOD || t == StationTask.HUNT;
    }

    /** Is it raining (not snowing) on this ground just now? */
    static boolean rainingOn(ServerLevel level, BlockPos ground) {
        Boolean t = rainForTests;
        if (t != null) return t;
        if (!level.isRaining()) return false;
        return level.getBiome(ground).value().getPrecipitationAt(ground) == Biome.Precipitation.RAIN;
    }

    /** Does the weather keep this folk off its work now (its trade one the rain sends home, and rain on its ground)? */
    public static boolean keepsIn(VillageFolkEntity f) {
        if (!rainSendsHome(f.stationTask()) || !(f.level() instanceof ServerLevel level)) return false;
        WorkZone z = f.workZone();
        return rainingOn(level, z != null ? z.center() : f.blockPosition());
    }

    /** Is it the town's winter? */
    public static boolean winter(ServerLevel level, @Nullable UUID village) {
        return Seasons.season(village, level.getDayTime() / 24000L) == Seasons.Season.WINTER;
    }

    /**
     * The beekeeper's winter (Crafts.beekeep): true while it is the town's winter and there are hives (no honey taken, no
     * bees bred, no flowers set: the round is off, and the houses' wants have its time); once a day it looks them over.
     */
    public static boolean hivesInWinter(ServerLevel level, Villages.Village v, VillageFolkEntity f, List<BlockPos> hives) {
        if (hives.isEmpty() || !winter(level, v.id())) return false;
        long day = level.getDayTime() / 24000L;
        Long looked = LOOKED.put(f.getUUID(), day);
        if (looked != null && looked == day) return true;
        int honey = 0;
        for (BlockPos p : hives) {
            if (level.getBlockState(p).hasProperty(BeehiveBlock.HONEY_LEVEL)) honey += level.getBlockState(p).getValue(BeehiveBlock.HONEY_LEVEL);
        }
        f.brain("looked the hives over for the winter: " + honey + " of " + hives.size() * BeehiveBlock.MAX_HONEY_LEVELS
            + " honey in them, left for the bees");
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "The bees are wintering. Their honey's theirs till spring.",
            "Hives snug for the winter. I'll not rob them now."));
        return true;
    }
}
