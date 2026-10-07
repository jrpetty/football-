package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [diver] The prismarine of an ocean monument, and where it goes.
 *
 * <ul>
 * <li><b>The monument.</b> An Iron Age town with a diver looks, once a week, for an ocean monument within reach of its
 *     water (the game's own: the structure an ocean explorer's map would show). None, and the board says so: the town
 *     does without prismarine.</li>
 * <li><b>The raid.</b> Every few days, of a morning, with three of the watch at home, the diver goes out with two
 *     guards: they swim at its shoulder while it dives to the monument's outer wall and cuts the prismarine out of it
 *     with a pickaxe from the stores, eight blocks at the most, never deeper than a dive. It is dangerous: the guardians
 *     shoot, and the guards fight what comes at them. Hurt (half its health gone, or a guard's), or cursed by the elder
 *     guardian (the mining fatigue it lays on anybody near, by the game's own rule), the party turns for home.</li>
 * <li><b>The prismarine.</b> The wall's blocks, brought up as they are; shards and crystals (a guardian's, picked out of
 *     the water) packed at the shed into prismarine bricks and sea lanterns by the game's recipes. It goes to the
 *     harbour (the great work is built of prismarine bricks when the stores can pay for it, its lamps sea lanterns:
 *     BigWorks) and to the waterfront (a jetty's lamp a sea lantern: Waterfront).</li>
 * </ul>
 */
final class DiverRaids {

    private DiverRaids() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** How far from its water a monument may be, in blocks; days between looks and between raids. */
    static final int REACH = 128;
    static final long LOOK_DAYS = 7, RAID_DAYS = 3;
    /** Ticks a block of the wall takes, under the water with a pickaxe. */
    static final int BREAK_TICKS = 40;
    /** The watch the town keeps at home while two go with the diver. */
    static final int WATCH_AT_HOME = 3;

    /** The guards out with each diver. */
    private static final Map<UUID, List<UUID>> ESCORTS = new ConcurrentHashMap<>();
    /** Each guard's diver. */
    private static final Map<UUID, UUID> ESCORTING = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> RAIDED = new ConcurrentHashMap<>();
    @Nullable private static volatile BlockPos monumentForTests;

    static void resetForTests() {
        ESCORTS.clear();
        ESCORTING.clear();
        RAIDED.clear();
        monumentForTests = null;
    }

    /** Tests: a monument here, as the look would find it. */
    static void monumentForTests(@Nullable BlockPos at) {
        monumentForTests = at;
    }

    /** Once a week, in the Iron Age and after: is there a monument in reach? */
    static void tick(ServerLevel level, Villages.Village v, Divers.Waterside w) {
        if (Villages.ageOf(v.id()).ordinal() < Villages.Age.IRON.ordinal()) return;
        long day = level.getDayTime() / 24000L;
        if (w.monumentLooked >= 0 && day - w.monumentLooked < LOOK_DAYS) return;
        w.monumentLooked = day;
        BlockPos found = monumentForTests;
        if (found == null) {
            try {
                found = level.findNearestMapStructure(StructureTags.ON_OCEAN_EXPLORER_MAPS, w.middle, REACH / 16, false);
            } catch (RuntimeException e) {
                found = null;
            }
        }
        if (found != null && found.distSqr(w.middle.atY(found.getY())) > (double) REACH * REACH) found = null;
        boolean news = found != null && w.monument == null;
        w.monument = found;
        Divers.save(v.id(), w);
        if (news) {
            Villages.tell(v.id(), day, "the diver found an ocean monument out in the deep water, " + (int) Math.sqrt(found.distSqr(w.middle.atY(found.getY())))
                + " blocks off: prismarine, if the watch will go with it");
        }
    }

    /** Off to the monument's walls: the age, the monument, the days since the last, the watch to spare, the diver whole. */
    @Nullable
    static Divers.Dive raidDive(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f, long now) {
        UUID id = v.id();
        if (w.monument == null || Villages.ageOf(id).ordinal() < Villages.Age.IRON.ordinal()) return null;
        long day = level.getDayTime() / 24000L;
        Long last = RAIDED.get(id);
        if (last != null && day - last < RAID_DAYS) return null;
        if (level.getDayTime() % 24000L > 6000L || f.getHealth() < f.getMaxHealth()) return null;
        if (Raids.why(id) != null) return null;                                // the bell is ringing: not today
        List<VillageFolkEntity> guards = new ArrayList<>();
        int watch = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity g) || g.stationTask() != StationTask.GUARD || !g.isAlive() || g.isBaby()) continue;
            watch++;
            if (g.getHealth() >= g.getMaxHealth() * 0.8F && !ESCORTING.containsKey(g.getUUID())) guards.add(g);
        }
        if (watch < WATCH_AT_HOME + 2 || guards.size() < 2) return null;
        if (!level.isLoaded(w.monument)) return null;
        // The walls want a pickaxe: one drawn at the stores beforehand (wantsPick), or no raid (and no look at the walls).
        if (f.countCarried(s -> s.getItem() instanceof PickaxeItem) == 0) return null;
        List<BlockPos> wall = wall(level, w);
        if (wall.isEmpty()) {
            RAIDED.put(id, day);                                       // nothing within a dive of the top: not today either
            return null;
        }
        guards.sort(Comparator.comparingDouble(g -> g.distanceToSqr(f)));
        List<UUID> two = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            two.add(guards.get(i).getUUID());
            ESCORTING.put(guards.get(i).getUUID(), f.getUUID());
            FolkTalk.speak(guards.get(i), "Out to the monument with " + f.displayNameCap() + ". Keep your head down.");
        }
        ESCORTS.put(f.getUUID(), two);
        RAIDED.put(id, day);
        w.raids++;
        Villages.tell(id, day, f.displayNameCap() + " the diver went out to the ocean monument for its prismarine, two of the watch with it");
        LOG.info("[MCA-DIVER] {} raids the monument at {} ({} blocks of wall in reach)", f.displayNameCap(), w.monument.toShortString(), wall.size());
        Divers.Dive d = Divers.dive(Divers.Job.RAID, w, wall, now);
        BlockPos in = Divers.bank(level, wall.get(0));
        if (in != null) d.entry = in;
        return d;
    }

    /** The monument's outer wall nearest the town: its prismarine within a dive of the surface, eight at the most. */
    static List<BlockPos> wall(ServerLevel level, Divers.Waterside w) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos m = w.monument;
        if (m == null) return out;
        int r = 32;
        for (int dx = -r; dx <= r; dx += 1) {
            for (int dz = -r; dz <= r; dz += 1) {
                int x = m.getX() + dx, z = m.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                BlockPos top = KelpBeds.surfaceAt(level, x, z);
                if (top == null) continue;
                BlockPos q = top;
                for (int k = 0; k <= Divers.DEEPEST && level.getFluidState(q).is(FluidTags.WATER); k++) q = q.below();
                if (top.getY() - q.getY() > Divers.DEEPEST) continue;
                if (prismarine(level.getBlockState(q))) out.add(q.immutable());
            }
        }
        BlockPos home = w.middle;
        out.sort(Comparator.comparingDouble(p -> p.distSqr(home)));
        return out.size() > 8 ? new ArrayList<>(out.subList(0, 8)) : out;
    }

    static boolean prismarine(BlockState st) {
        return st.is(Blocks.PRISMARINE) || st.is(Blocks.PRISMARINE_BRICKS) || st.is(Blocks.DARK_PRISMARINE) || st.is(Blocks.SEA_LANTERN);
    }

    /** Is it time to turn for home: the diver or a guard half hurt, or the elder guardian's curse on it? */
    static boolean retreat(ServerLevel level, VillageFolkEntity f) {
        if (f.getHealth() < f.getMaxHealth() * 0.5F || f.hasEffect(MobEffects.DIG_SLOWDOWN)) return true;
        List<UUID> guards = ESCORTS.get(f.getUUID());
        if (guards == null) return false;
        for (UUID g : guards) {
            if (level.getEntity(g) instanceof VillageFolkEntity guard && guard.isAlive() && guard.getHealth() < guard.getMaxHealth() * 0.5F) return true;
            if (!(level.getEntity(g) instanceof VillageFolkEntity guard2) || !guard2.isAlive()) return true;
        }
        return false;
    }

    /** A block of the wall out, with the pickaxe: the block itself (prismarine drops itself), or crystals off a lantern. */
    static int breakWall(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f, Divers.Dive d, BlockPos at) {
        if (retreat(level, f)) {
            d.spots.clear();
            FolkTalk.speak(f, f.hasEffect(MobEffects.DIG_SLOWDOWN) ? "The elder's curse is on me — I can't swing a pick. Back!"
                : "Too hot down here. We're going home!");
            end(level, f);
            return 0;
        }
        BlockState st = level.getBlockState(at);
        if (!prismarine(st)) return 0;
        ItemStack pick = ItemStack.EMPTY;
        for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && s.getItem() instanceof PickaxeItem) { pick = s; break; }
        if (pick.isEmpty()) return 0;
        int n = Divers.takeDrops(level, f, at, st, pick);
        KelpBeds.wear(f, pick);
        w.prismarine += n;
        Divers.today(level, v.id(), "prismarine", n);
        if (d.spots.size() <= 1) end(level, f);
        return n;
    }

    /** The raid over: the guards back to the watch. */
    static void end(ServerLevel level, VillageFolkEntity f) {
        List<UUID> guards = ESCORTS.remove(f.getUUID());
        if (guards != null) for (UUID g : guards) ESCORTING.remove(g);
    }

    /**
     * From the folk's tick: a guard out with the diver swims at its shoulder (and fights whatever comes at it, by its
     * own goals). True while it is out with it; let go when the diver comes home.
     */
    static boolean escort(VillageFolkEntity g, ServerLevel level) {
        UUID diver = ESCORTING.get(g.getUUID());
        if (diver == null) return false;
        if (!(level.getEntity(diver) instanceof VillageFolkEntity f) || !f.isAlive() || Divers.jobOf(f) != Divers.Job.RAID) {
            ESCORTING.remove(g.getUUID());
            List<UUID> list = ESCORTS.get(diver);
            if (list != null) list.remove(g.getUUID());
            return false;
        }
        if (g.getTarget() != null) return true;                                // fighting: its own goals have it
        if (g.tickCount % 20 == 0 && g.distanceToSqr(f) > 3.5 * 3.5) {
            g.getNavigation().moveTo(f.getX(), Math.max(f.getY(), g.getY()), f.getZ(), 1.1D);
        }
        return true;
    }

    public static boolean escorting(VillageFolkEntity g) {
        return ESCORTING.containsKey(g.getUUID());
    }

    // ------------------------------------------------------------------ the prismarine's uses

    /** At the shed: shards and crystals packed by the game's recipes, sea lanterns first (four kept), then bricks. */
    static void pack(ServerLevel level, VillageFolkEntity f, boolean table) {
        int lanterns = 0;
        while (lanterns < 4 && f.countCarried(s -> s.is(Items.PRISMARINE_SHARD)) >= 4 && f.countCarried(s -> s.is(Items.PRISMARINE_CRYSTALS)) >= 5
            && KelpBeds.craft(level, f, Items.SEA_LANTERN, table)) lanterns++;
        int bricks = 0;
        while (bricks < 8 && f.countCarried(s -> s.is(Items.PRISMARINE_SHARD)) >= 9 && KelpBeds.craft(level, f, Items.PRISMARINE_BRICKS, table)) bricks++;
        if (lanterns + bricks > 0) {
            Economy.gathered(f, new ItemStack(Items.SEA_LANTERN), lanterns);
            Economy.gathered(f, new ItemStack(Items.PRISMARINE_BRICKS), bricks);
            f.note(AssistantEntity.Deed.THINGS_MADE, lanterns + bricks);
        }
    }

    /** The harbour's stone (BigWorks): prismarine bricks, when the stores can pay for the whole of it. */
    @Nullable
    static WorksPlans.Family harbourFamily(ServerLevel level, Villages.Village v, int units) {
        return Market.stock(level, v.id(), WorksPlans.Family.PRISMARINE.payment()) >= units ? WorksPlans.Family.PRISMARINE : null;
    }

    /** A light for the waterfront (a jetty's lamp: Waterfront): a sea lantern out of the stores, else the town's own. */
    @Nullable
    static Block waterLight(ServerLevel level, Villages.Village v) {
        if (Crafts.take(level, v, s -> s.is(Items.SEA_LANTERN), 1)) return Blocks.SEA_LANTERN;
        return Masonry.light(level, v);
    }

    /** A waterfront light put back (the post could not be paid for). */
    static void unlight(ServerLevel level, Villages.Village v, @Nullable Block light) {
        if (light == Blocks.SEA_LANTERN) Crafts.store(level, v, new ItemStack(Items.SEA_LANTERN));
        else Masonry.unlight(level, v, light);
    }

    /** Does the diver want a pickaxe out of the stores for a raid that is due (a monument in reach, the age)? */
    static boolean wantsPick(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f) {
        if (w.monument == null || Villages.ageOf(v.id()).ordinal() < Villages.Age.IRON.ordinal()) return false;
        Long last = RAIDED.get(v.id());
        return (last == null || level.getDayTime() / 24000L - last >= RAID_DAYS) && f.countCarried(s -> s.getItem() instanceof PickaxeItem) == 0;
    }
}
