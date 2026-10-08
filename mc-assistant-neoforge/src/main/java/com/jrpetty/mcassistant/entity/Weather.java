package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wf] A town and its weather.
 *
 * <ul>
 * <li><b>Lightning rods.</b> Once the stores hold copper ingots, the town's tallest buildings — the
 *     meeting hall, the bell tower, the chapel, the leader's hall — get a lightning rod apiece on the
 *     highest point of their roofs, put up by a hand on the town's works (TownJobs) and made as the game
 *     makes one: three copper ingots out of the stores, one over another (or a rod the stores already
 *     hold). The game sends the lightning of a storm to a rod within reach of it, and a rod takes it to
 *     the ground: not the hall's thatch. One a building, ever; another only if that one is taken down.</li>
 * <li><b>A thunderstorm.</b> Everybody but the watch goes indoors — home, or the nearest of the town's
 *     buildings, whichever is nearer — and waits it out there: its work is dropped, the town's works wait,
 *     and a farmer does not stand in an open field under the lightning. A miner down its mine stays at
 *     its work (the rock is its roof), and a fire is still a fire (FireBrigade). When the storm has gone
 *     over, back to the day.</li>
 * </ul>
 */
public final class Weather {

    private Weather() {}

    // ------------------------------------------------------------------ lightning rods

    /** The town's tallest buildings, a lightning rod apiece. */
    public static final List<String> RODDED = List.of("hall", "belltower", "chapel", "townhall");
    /** Copper ingots to a rod: the game's own recipe, three in a column. */
    public static final int COPPER_A_ROD = 3;
    /** How often the roofs are looked over (ticks). */
    static final long ROD_LOOK = 600L;

    private static final Map<UUID, Long> RODS_LOOKED = new ConcurrentHashMap<>();

    /** From the town's works (TownWork.tick): a rod for each tall building with none, at most every half-minute. */
    static void rods(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        Long last = RODS_LOOKED.get(v.id());
        if (last != null && now - last < ROD_LOOK && now >= last) return;
        RODS_LOOKED.put(v.id(), now);
        putUpRods(level, v);
    }

    /** Put up what rods the town has the copper for, now. Returns how many went up. */
    static int putUpRods(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        int put = 0;
        for (String name : RODDED) {
            Ledger.Building b = Villages.builtStructure(id, name);
            if (b == null || !level.isLoaded(b.anchor()) || hasRod(level, id, name)) continue;
            if (!copperFor(level, v)) break;                            // nothing to make one of
            BlockPos top = roofTop(level, b, id, name);
            if (top == null) continue;
            String spoken = Villages.spoken(name);
            BlockPos ground = new BlockPos(top.getX(), b.anchor().getY(), top.getZ());
            if (!TownJobs.atWork(level, v, "rods", ground, "putting a lightning rod up on " + spoken)) break;
            if (!payForARod(level, v)) break;
            level.setBlockAndUpdate(top, Blocks.LIGHTNING_ROD.defaultBlockState());
            level.playSound(null, top, SoundEvents.COPPER_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
            Ledger.note(id, "rod." + name, top.getX() + "," + top.getY() + "," + top.getZ());
            Villages.tell(id, level.getDayTime() / 24000L, "a lightning rod was put up on " + spoken + ", of the stores' copper");
            put++;
        }
        return put;
    }

    /** Is there a rod on this building (the one the town put up, still standing there)? */
    static boolean hasRod(ServerLevel level, UUID id, String name) {
        BlockPos at = parse(Ledger.note(id, "rod." + name));
        return at != null && level.isLoaded(at) && level.getBlockState(at).is(Blocks.LIGHTNING_ROD);
    }

    /** The copper for a rod in the stores: a rod put by, or three ingots. */
    static boolean copperFor(ServerLevel level, Villages.Village v) {
        return Market.stock(level, v.id(), s -> s.is(Items.LIGHTNING_ROD)) > 0
            || Market.stock(level, v.id(), s -> s.is(Items.COPPER_INGOT)) >= COPPER_A_ROD;
    }

    /** The rod paid for out of the stores: one put by, else made of three copper ingots. */
    private static boolean payForARod(ServerLevel level, Villages.Village v) {
        return Crafts.take(level, v, s -> s.is(Items.LIGHTNING_ROD), 1)
            || Crafts.take(level, v, s -> s.is(Items.COPPER_INGOT), COPPER_A_ROD);
    }

    /**
     * Where the rod goes: on the highest point of the building's roof (the free block over the highest
     * of its columns, the nearest the middle if several are as high), or null if it has no roof to speak
     * of. A rod already up there (a player's) is taken for the building's own.
     */
    @Nullable
    static BlockPos roofTop(ServerLevel level, Ledger.Building b, UUID id, String name) {
        int[] half = com.jrpetty.mcassistant.entity.goal.BuildGoal.footprint(b.structure());
        boolean turned = b.facing().getAxis() == Direction.Axis.X;
        int wx = turned ? half[1] : half[0], wz = turned ? half[0] : half[1];
        BlockPos a = b.anchor();
        BlockPos best = null;
        double bestMid = Double.MAX_VALUE;
        for (int x = a.getX() - wx; x <= a.getX() + wx; x++) {
            for (int z = a.getZ() - wz; z <= a.getZ() + wz; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (y > a.getY() + 48) continue;                       // not the building's
                BlockPos spot = new BlockPos(x, y, z);
                if (level.getBlockState(spot.below()).is(Blocks.LIGHTNING_ROD) || level.getBlockState(spot).is(Blocks.LIGHTNING_ROD)) {
                    BlockPos rod = level.getBlockState(spot).is(Blocks.LIGHTNING_ROD) ? spot : spot.below();
                    Ledger.note(id, "rod." + name, rod.getX() + "," + rod.getY() + "," + rod.getZ());
                    return null;                                         // one there already
                }
                if (y - 1 < a.getY() + 2) continue;                     // the ground, not a roof
                BlockState here = level.getBlockState(spot);
                if (!(here.isAir() || here.canBeReplaced() && here.getFluidState().isEmpty())) continue;
                double mid = (x - a.getX()) * (x - a.getX()) + (z - a.getZ()) * (z - a.getZ());
                if (best == null || y > best.getY() || y == best.getY() && mid < bestMid) {
                    best = spot;
                    bestMid = mid;
                }
            }
        }
        return best;
    }

    @Nullable
    private static BlockPos parse(@Nullable String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        if (p.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the storm

    /** Tests: a thunderstorm (true), clear weather (false), or the world's own (null). */
    private static volatile Boolean stormForTests;

    public static void stormForTests(@Nullable Boolean on) {
        stormForTests = on;
    }

    /** Is a thunderstorm on? */
    public static boolean stormy(ServerLevel level) {
        Boolean t = stormForTests;
        return t != null ? t : level.isThundering();
    }

    /** A folk waiting out the storm: where it is going, and since when. */
    static final class Shelter {
        final BlockPos to;
        final long since;
        int walkTick = -1000;
        double best = Double.MAX_VALUE;
        long progress;
        boolean settled;

        Shelter(BlockPos to, long now) {
            this.to = to.immutable();
            this.since = now;
            this.progress = now;
        }
    }

    private static final Map<UUID, Shelter> SHELTER = new ConcurrentHashMap<>();

    public static void resetForTests() {
        SHELTER.clear();
        RODS_LOOKED.clear();
        stormForTests = null;
    }

    /** Is this folk waiting out a thunderstorm indoors (or on its way in)? */
    public static boolean sheltering(VillageFolkEntity f) {
        return SHELTER.containsKey(f.getUUID());
    }

    /** Within this of where it is going, and under a roof, it is in. */
    static final int IN = 6;
    /** Getting no nearer its shelter in this long, it stops where it is (under cover if it can be). */
    static final long NO_NEARER = 600L;

    /**
     * From the folk's tick: a thunderstorm, and it is not one of the watch: indoors, and there till it has
     * passed. True while it is (its own day waits).
     */
    public static boolean shelter(VillageFolkEntity f, ServerLevel level) {
        Shelter s = SHELTER.get(f.getUUID());
        if (!stormy(level) || exempt(f, level)) {
            if (s != null) {
                SHELTER.remove(f.getUUID());
                f.brain("the storm has passed: back to the day");
                if (!stormy(level) && f.getRandom().nextInt(4) == 0) {
                    FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "That's blown over. Back to it.", "Storm's gone. Right, where was I?"));
                }
            }
            return false;
        }
        long now = level.getGameTime();
        if (s == null) {
            s = new Shelter(shelterFor(f), now);
            SHELTER.put(f.getUUID(), s);
            if (f.peekJob() != null) f.clearQueue();
            f.getNavigation().stop();
            f.brain("a thunderstorm: going indoors");
            if (f.getRandom().nextInt(3) == 0) {
                FolkTalk.speak(f, f.stationTask() == StationTask.FARM
                    ? FolkTalk.pick(f.getRandom(), "I'm not standing in an open field in this. Indoors!", "Lightning! Off the field, quick.")
                    : FolkTalk.pick(f.getRandom(), "Thunder! Indoors, everyone.", "Hear that? I'm getting under a roof.", "Storm's coming in. Inside!"));
            }
        }
        BlockPos me = f.blockPosition();
        double dx = f.getX() - (s.to.getX() + 0.5), dz = f.getZ() - (s.to.getZ() + 0.5);
        double d = Math.sqrt(dx * dx + dz * dz);
        boolean roofed = roofed(level, me);
        if (roofed && (d <= IN || s.settled)) {
            if (!s.settled) f.brain("indoors, waiting out the storm");
            s.settled = true;
            f.getNavigation().stop();
            return true;
        }
        s.settled = false;
        if (d < s.best - 0.5) {
            s.best = d;
            s.progress = now;
        } else if (now - s.progress > NO_NEARER || now < s.progress) {
            // No way in (a door it cannot get through, a building across water): it waits where it stands.
            f.getNavigation().stop();
            f.brain("waiting out the storm: cannot get in");
            return true;
        }
        if (f.getNavigation().isDone() || f.tickCount - s.walkTick > 40) {
            f.walkTo(s.to, 1.2D);
            s.walkTick = f.tickCount;
        }
        return true;
    }

    /** The watch, a miner down its mine, a folk asleep, out of the town or hired out: the storm is not theirs to shelter from. */
    static boolean exempt(VillageFolkEntity f, ServerLevel level) {
        if (f.stationTask() == StationTask.GUARD || Patrols.escorting(f)) return true;
        if (f.isSleeping() || f.isHired() || f.isShowcase() || f.trip() != null || f.expedition() != null || Nether.away(f)) return true;
        // Off work with a bed of its own: its evening already takes it in out of the wet, and to bed.
        if (!f.onShift() && f.bedPos() != null) return true;
        if (f.stationTask() == StationTask.MINE) {
            BlockPos me = f.blockPosition();
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, me.getX(), me.getZ());
            if (me.getY() < ground - 4) return true;                    // down its mine: the rock is its roof
        }
        return false;
    }

    /** Home (its bed) or the nearest of the town's buildings, whichever is nearer. */
    static BlockPos shelterFor(VillageFolkEntity f) {
        BlockPos building = Raids.shelterFor(f);
        BlockPos bed = f.bedPos();
        if (bed == null) return building;
        BlockPos me = f.blockPosition();
        return bed.distSqr(me) <= building.distSqr(me) ? bed : building;
    }

    /** A roof over this spot: something solid over its head (not leaves: a tree is no place in a thunderstorm). */
    static boolean roofed(ServerLevel level, BlockPos feet) {
        for (int i = 2; i <= 24; i++) {
            BlockPos p = feet.above(i);
            BlockState st = level.getBlockState(p);
            if (st.isAir() || st.is(BlockTags.LEAVES)) continue;
            if (st.blocksMotion() || st.isSolid()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: put up the rods the town has the copper for, now. */
    public static int rodsForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        return v == null ? 0 : putUpRods(level, v);
    }

    /** Tests: is this spot under a roof (as the storm reads it)? */
    public static boolean roofedForTests(ServerLevel level, BlockPos feet) {
        return roofed(level, feet);
    }
}
