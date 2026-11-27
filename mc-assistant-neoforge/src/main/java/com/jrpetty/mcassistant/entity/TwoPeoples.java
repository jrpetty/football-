package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Guard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [emerald] Two peoples: the town's folk and the game's own villagers, kept apart.
 *
 * <p>The player's wish, in so many words: "make sure our village folk and normal villagers don't combine. I want them
 * to be completely separate." The mod once did the opposite (VillagerTakeover turned villagers into folk as they were
 * met and took their villages over); that is now a switch, off unless somebody wants it. With it off:
 *
 * <ul>
 * <li><b>Villagers never use the town's things.</b> A villager claims a bed, a job block and its meeting bell through its
 *     brain: it remembers the place and takes a ticket at the game's point of interest there. Once a second every
 *     villager in the world is looked over, and a claim on anything inside one of our towns is undone the way the game
 *     undoes one when a villager dies: the ticket handed back (Villager.releasePoi) and the memory let go. A villager
 *     asleep in one of our beds is woken. The town's points of interest are never taken by the town itself, so the
 *     game never mistakes a town for a village of villagers (its raids, its sieges, its cats come of that).</li>
 * <li><b>A villager who wanders into a town is left alone</b>: not converted, not driven off, never attacked (the watch
 *     only ever takes on monsters), and never counted among the town's folk, its beds or its job market (those count
 *     folk only, as the game's villagers count only villagers for their golems, their gossip and their breeding).</li>
 * <li><b>Folk never use a village of villagers' things</b> (VanillaVillages): no folk takes a bed, a chest or a bell
 *     inside one, no town is founded near one, and no lot, field, wood, mine, wall or levelled ground of a town comes
 *     near one. Its villages are known, by what the world built and by their people's bells, from this same sweep.</li>
 * </ul>
 */
public final class TwoPeoples {

    private TwoPeoples() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The villagers are looked over once a second. */
    static final int EVERY = 20;
    /** Each town's surroundings are looked over for the world's villages every five minutes (each region once, in fact). */
    static final long LOOK_ROUND = 6000L;
    /** A town's ground, for this, reaches a little past its streets. */
    static final int PAST_THE_STREETS = 8;

    /** What a villager's brain claims at a point of interest: its bed, its job site (and the one it is walking to), its bell. */
    private static final List<MemoryModuleType<net.minecraft.core.GlobalPos>> CLAIMS = List.of(
        MemoryModuleType.HOME, MemoryModuleType.JOB_SITE, MemoryModuleType.POTENTIAL_JOB_SITE, MemoryModuleType.MEETING_POINT);

    /** Each town's claims undone: {today's day, today, all time}. */
    private static final Map<UUID, long[]> TURNED = new ConcurrentHashMap<>();
    /** When each town's surroundings were last looked over. */
    private static final Map<UUID, Long> LOOKED_ROUND = new ConcurrentHashMap<>();

    public static void resetForTests() {
        TURNED.clear();
        LOOKED_ROUND.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % EVERY != 9) return;
        Guard.run("two peoples", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) sweep(level);
        });
    }

    /**
     * One look over every villager in this world: claims on the towns' things undone, sleepers in the towns' beds woken,
     * and the villagers' own villages noted by their bells. Returns how many claims were undone.
     */
    public static int sweep(ServerLevel level) {
        if (!VanillaVillages.apart()) return 0;
        List<Villages.Village> towns = new ArrayList<>();
        for (Villages.Village v : Villages.every()) if (v.dim().equals(level.dimension())) towns.add(v);
        long now = level.getGameTime();
        for (Villages.Village t : towns) {
            Long last = LOOKED_ROUND.get(t.id());
            if (last != null && now - last < LOOK_ROUND && now >= last) continue;
            LOOKED_ROUND.put(t.id(), now);
            VanillaVillages.lookAround(level, t.centre(), Villages.townReach(t.id()) + 160);
        }
        Map<BlockPos, List<BlockPos>> bells = new HashMap<>();
        int undone = 0;
        for (Villager v : level.getEntities(EntityType.VILLAGER, Villager::isAlive)) {
            Brain<Villager> brain = v.getBrain();
            for (MemoryModuleType<GlobalPos> m : CLAIMS) {
                if (!brain.hasMemoryValue(m)) continue;
                GlobalPos g = brain.getMemory(m).orElse(null);
                if (g == null || !g.dimension().equals(level.dimension())) continue;
                Villages.Village town = ours(level, towns, g.pos());
                if (town != null) {
                    turnAway(level, v, m, g.pos(), town);
                    undone++;
                    continue;
                }
                if (m == MemoryModuleType.MEETING_POINT && level.isLoaded(g.pos()) && level.getBlockState(g.pos()).is(Blocks.BELL)) {
                    List<BlockPos> ground = bells.computeIfAbsent(g.pos().immutable(), k -> new ArrayList<>());
                    ground.add(v.blockPosition());
                    for (MemoryModuleType<GlobalPos> own : List.of(MemoryModuleType.HOME, MemoryModuleType.JOB_SITE)) {
                        if (!brain.hasMemoryValue(own)) continue;
                        brain.getMemory(own).ifPresent(h -> { if (h.dimension().equals(level.dimension())) ground.add(h.pos()); });
                    }
                }
            }
            // Asleep in one of the town's beds (one it lay down in before the sweep came round): up, and out of it.
            if (v.isSleeping()) {
                BlockPos bed = v.getSleepingPos().orElse(null);
                Villages.Village town = bed == null ? null : ours(level, towns, bed);
                if (town != null) {
                    v.stopSleeping();
                    count(town.id(), now / 24000L);
                    undone++;
                }
            }
        }
        for (Map.Entry<BlockPos, List<BlockPos>> e : bells.entrySet()) VanillaVillages.heardAt(level, e.getKey(), e.getValue());
        if (now % 1200L < EVERY) VanillaVillages.forgetStale(level);
        return undone;
    }

    /** The town whose ground this is (within its streets and a little past), or null; never the ground the world built
     *  for a village of villagers, which is theirs wherever a town stands. */
    @Nullable
    static Villages.Village ours(ServerLevel level, List<Villages.Village> towns, BlockPos p) {
        for (Villages.Village t : towns) {
            int reach = Villages.townReach(t.id()) + PAST_THE_STREETS;
            if (Math.abs(p.getX() - t.centre().getX()) > reach || Math.abs(p.getZ() - t.centre().getZ()) > reach) continue;
            if (VanillaVillages.builtAt(level, p)) return null;
            return t;
        }
        return null;
    }

    /** Is this spot one of our towns' (TwoPeoples' ground, for anybody asking)? */
    public static boolean townGround(ServerLevel level, BlockPos p) {
        List<Villages.Village> towns = new ArrayList<>();
        for (Villages.Village v : Villages.every()) if (v.dim().equals(level.dimension())) towns.add(v);
        return ours(level, towns, p) != null;
    }

    /** A villager's claim on the town's thing undone, as the game undoes one when a villager dies: the point of
     *  interest's ticket handed back, and the memory of it let go. The villager itself is left as it is. */
    private static void turnAway(ServerLevel level, Villager v, MemoryModuleType<GlobalPos> m, BlockPos at, Villages.Village town) {
        try {
            v.releasePoi(m);
        } catch (RuntimeException ex) {
            // The game's own release, on a point of interest that is no longer there (the block broken this tick).
            LOG.debug("[MCA-PEOPLES] releasing {} at {} failed: {}", m, at.toShortString(), ex.toString());
        }
        v.getBrain().eraseMemory(m);
        // The game's own release hands the ticket back only while the villager's trade still matches the block (a
        // job site remembered by a villager whose trade has since changed keeps its ticket for good). Inside a town
        // nobody but a villager ever takes one, so a bed's or a job block's ticket still out is handed back here.
        if (m != MemoryModuleType.MEETING_POINT) {
            var poi = level.getPoiManager();
            try {
                if (poi.exists(at, h -> true) && poi.getFreeTickets(at) == 0) poi.release(at);
            } catch (RuntimeException ex) {
                LOG.debug("[MCA-PEOPLES] handing back the ticket at {} failed: {}", at.toShortString(), ex.toString());
            }
        }
        if (m == MemoryModuleType.HOME && v.isSleeping()) v.stopSleeping();
        long day = level.getDayTime() / 24000L;
        count(town.id(), day);
        LOG.debug("[MCA-PEOPLES] a villager's claim on {} at {} in {} undone", what(m), at.toShortString(), Villages.name(town.id()));
    }

    private static void count(UUID town, long day) {
        long[] t = TURNED.computeIfAbsent(town, k -> new long[]{ day, 0, 0 });
        if (t[0] != day) { t[0] = day; t[1] = 0; }
        t[1]++;
        t[2]++;
    }

    static String what(MemoryModuleType<GlobalPos> m) {
        if (m == MemoryModuleType.HOME) return "a bed";
        if (m == MemoryModuleType.MEETING_POINT) return "a bell";
        return "a job block";
    }

    /** How many times villagers' claims on this town's things were undone: {today, all}. */
    public static long[] turnedAway(UUID town, long day) {
        long[] t = TURNED.get(town);
        if (t == null) return new long[]{ 0, 0 };
        return new long[]{ t[0] == day ? t[1] : 0, t[2] };
    }

    /** Tests: one look over the villagers now. */
    public static int sweepForTests(ServerLevel level) {
        return sweep(level);
    }
}
