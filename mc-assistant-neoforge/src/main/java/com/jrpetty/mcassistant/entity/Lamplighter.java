package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [roles-a] The lamplighter's round, in the ages before the Iron Age's lantern posts: the street lights of a Wood or Stone
 * Age town are torches on fence posts along its avenues and ring street (TownWork.lampSpot), and a hand goes round them
 * twice a day, a quarter's warden first (TownJobs "lamps": {@link #lean}).
 *
 * <ul>
 * <li><b>In the morning</b> (after the dawn bell) it takes each torch down off its post, one post at a time as it walks;
 *     the torch is the post's own, kept in the town's books against it (Ledger "lamps.doused"), not the stores'.</li>
 * <li><b>Before dusk</b> it puts each one back, so the streets are lit by the dusk bell. Any it has not reached by
 *     nightfall the watch lights on its way out (all at once, and the book says so): the streets are never left dark
 *     for the monsters. A post that has gone meanwhile gives its torch back to the stores.</li>
 * <li><b>A restart</b> mid-day keeps the book; at night the round is finished at once.</li>
 * <li>From the Iron Age the lamps are lanterns the smith makes, which burn all night and day: the round stops, and any
 *     torch still doused goes back on its post.</li>
 * </ul>
 * The town's looks and its tidy crew count a doused post as a lit one ({@link #dousedNear}), and the streets' works never
 * put a second lamp on a doused post (TownWork). Cost: once a second a town, only in the two windows; the posts found once
 * a morning from the plan's lamp spots (arithmetic, and a heightmap read at each spot).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Lamplighter {

    private Lamplighter() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    static final String WORKS = "lamps";
    static final String BOOK = "lamps.doused";
    /** The morning's round (after the dawn bell), and the evening's (done by the dusk bell); after it, all at once. */
    static final long DOUSE_FROM = 1000L, DOUSE_TILL = 4000L, LIGHT_FROM = 10800L, LIGHT_TILL = 12450L;
    /** The most posts a town's round takes in. */
    static final int MOST = 64;
    /** How near a doused post a street cell counts as lit (blocks): a torch's light falls to seven at about this. */
    static final int LIT_REACH = 7;

    /** The town's doused posts (the fence's place), as its books have them. */
    private static final Map<UUID, Set<BlockPos>> DOUSED = new ConcurrentHashMap<>();
    /** Each town's lit posts found this morning, and the morning they were found on. */
    private static final Map<UUID, List<BlockPos>> FOUND = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> FOUND_DAY = new ConcurrentHashMap<>();
    /** Who last went round, and when ("Wren, this morning"). */
    private static final Map<UUID, String> LAST = new ConcurrentHashMap<>();

    public static void resetForTests() {
        DOUSED.clear();
        FOUND.clear();
        FOUND_DAY.clear();
        LAST.clear();
    }

    /** Does this town keep a lamplighter's round: before the Iron Age, a settled town? */
    static boolean keeps(UUID id) {
        return Villages.ageOf(id).ordinal() < Villages.Age.IRON.ordinal() && Villages.headcount(id) >= TownJobs.SETTLED;
    }

    // ------------------------------------------------------------------ the book

    static Set<BlockPos> doused(UUID id) {
        return DOUSED.computeIfAbsent(id, k -> {
            Set<BlockPos> out = new LinkedHashSet<>();
            String s = Ledger.note(k, BOOK);
            if (s != null && !s.isEmpty()) {
                for (String p : s.split(";")) {
                    try {
                        out.add(BlockPos.of(Long.parseLong(p)));
                    } catch (NumberFormatException ignored) { }
                }
            }
            return out;
        });
    }

    private static void save(UUID id) {
        StringBuilder sb = new StringBuilder();
        for (BlockPos p : doused(id)) sb.append(sb.length() == 0 ? "" : ";").append(p.asLong());
        if (sb.length() == 0) Ledger.forget(id, BOOK);
        else Ledger.note(id, BOOK, sb.toString());
    }

    /** [Eyesores, Tidy] Is a doused post near this street cell (its light only away for the day)? */
    public static boolean dousedNear(UUID id, BlockPos at) {
        Set<BlockPos> d = DOUSED.get(id);
        if (d == null || d.isEmpty()) return false;
        for (BlockPos p : d) if (Math.abs(p.getX() - at.getX()) + Math.abs(p.getZ() - at.getZ()) <= LIT_REACH && Math.abs(p.getY() - at.getY()) <= 4) return true;
        return false;
    }

    /** [TownWork] Is this the fence of a doused post (no second lamp is put on it)? */
    public static boolean isDoused(UUID id, BlockPos fence) {
        Set<BlockPos> d = DOUSED.get(id);
        return d != null && d.contains(fence);
    }

    // ------------------------------------------------------------------ the posts

    /** The fence of the post at this lamp spot, if a lit torch stands on it; or null. */
    static BlockPos litPost(ServerLevel level, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos fence = new BlockPos(x, y - 1, z);
        return level.getBlockState(fence).is(BlockTags.FENCES) && level.getBlockState(fence.above()).is(Blocks.TORCH) ? fence : null;
    }

    /** The town's lit street posts, found from its plan's lamp spots (at most MOST, nearest the heart first). */
    static List<BlockPos> find(ServerLevel level, Villages.Village v) {
        List<BlockPos> out = new ArrayList<>();
        int r = Math.min(96, Villages.townReach(v.id()));
        BlockPos c = v.centre();
        for (int ring = 0; ring <= r && out.size() < MOST; ring++) {
            for (int dx = -ring; dx <= ring && out.size() < MOST; dx++) {
                for (int dz = -ring; dz <= ring && out.size() < MOST; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring || !TownWork.lampSpot(dx, dz)) continue;
                    BlockPos p = litPost(level, c.getX() + dx, c.getZ() + dz);
                    if (p != null && Math.abs(p.getY() - c.getY()) <= 12) out.add(p);
                }
            }
        }
        return out;
    }

    /** [TownJobs.choose] A quarter's warden first to the lamps. */
    static double lean(VillageFolkEntity f, String works) {
        return works.equals(WORKS) && Wardens.quarterOf(f) != null ? 70.0 : 0.0;
    }

    // ------------------------------------------------------------------ the round

    /** One step of the town's round, once a second: a post doused, or lit, by a hand at it. True if one was. */
    static boolean round(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long dt = level.getDayTime(), t = dt % 24000L, day = dt / 24000L;
        Set<BlockPos> d = doused(id);
        boolean keeps = keeps(id);
        boolean night = t >= LIGHT_TILL || t < DOUSE_FROM;
        if (!d.isEmpty() && (night || !keeps)) {
            int lit = 0;
            for (BlockPos p : new ArrayList<>(d)) if (relight(level, v, p)) lit++;
            d.clear();
            save(id);
            if (lit > 0) LOG.info("[MCA-LAMPS] {}: the watch lit the {} street lamps the round had not reached", Villages.name(id), lit);
            return lit > 0;
        }
        if (!keeps) return false;
        if (t >= LIGHT_FROM && t < LIGHT_TILL && !d.isEmpty()) {
            BlockPos next = nearestTo(d, v.centre());
            if (!level.isLoaded(next)) { d.remove(next); save(id); return false; }
            if (!TownJobs.atWork(level, v, WORKS, next.above(), "lighting the street lamps")) return false;
            relight(level, v, next);
            d.remove(next);
            save(id);
            hand(level, v, "this evening");
            return true;
        }
        if (t >= DOUSE_FROM && t < DOUSE_TILL) {
            if (FOUND_DAY.getOrDefault(id, Long.MIN_VALUE) != day) {
                FOUND_DAY.put(id, day);
                FOUND.put(id, find(level, v));
            }
            List<BlockPos> lit = FOUND.getOrDefault(id, List.of());
            while (!lit.isEmpty() && litPost(level, lit.get(0).getX(), lit.get(0).getZ()) == null) lit.remove(0);
            if (lit.isEmpty()) return false;
            BlockPos next = lit.get(0);
            if (!TownJobs.atWork(level, v, WORKS, next.above(), "dousing the street lamps")) return false;
            level.setBlock(next.above(), Blocks.AIR.defaultBlockState(), 3);
            level.playSound(null, next.above(), SoundEvents.CANDLE_EXTINGUISH, SoundSource.BLOCKS, 0.7F, 0.9F);
            level.sendParticles(ParticleTypes.SMOKE, next.getX() + 0.5, next.getY() + 1.4, next.getZ() + 0.5, 4, 0.05, 0.1, 0.05, 0.01);
            lit.remove(0);
            d.add(next.immutable());
            save(id);
            hand(level, v, "this morning");
            return true;
        }
        return false;
    }

    /** A doused post's torch back on it; or, its post gone, the torch back to the stores. True if lit. */
    private static boolean relight(ServerLevel level, Villages.Village v, BlockPos fence) {
        if (!level.isLoaded(fence)) return false;
        if (level.getBlockState(fence).is(BlockTags.FENCES) && level.getBlockState(fence.above()).isAir()) {
            level.setBlock(fence.above(), Blocks.TORCH.defaultBlockState(), 3);
            level.playSound(null, fence.above(), SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 0.5F, 1.1F);
            level.sendParticles(ParticleTypes.FLAME, fence.getX() + 0.5, fence.getY() + 1.6, fence.getZ() + 0.5, 3, 0.05, 0.05, 0.05, 0.0);
            return true;
        }
        if (!level.getBlockState(fence.above()).is(Blocks.TORCH)) Crafts.store(level, v, new ItemStack(Items.TORCH));   // the post gone: its torch kept
        return false;
    }

    private static BlockPos nearestTo(Set<BlockPos> d, BlockPos c) {
        BlockPos best = null;
        for (BlockPos p : d) if (best == null || p.distSqr(c) < best.distSqr(c)) best = p;
        return best;
    }

    private static void hand(ServerLevel level, Villages.Village v, String when) {
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f && TownJobs.doing(f) != null && TownJobs.doing(f).contains("street lamps")) {
                LAST.put(v.id(), f.displayNameCap() + ", " + when);
                return;
            }
        }
        LAST.put(v.id(), "a hand on the town's works, " + when);
    }

    // ------------------------------------------------------------------ the town's tick

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 20 != 17) return;
        com.jrpetty.mcassistant.Guard.run("lamps", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    long t = level.getDayTime() % 24000L;
                    boolean window = t >= DOUSE_FROM && t < DOUSE_TILL || t >= LIGHT_FROM && t < LIGHT_TILL;
                    if (!window && doused(v.id()).isEmpty()) continue;
                    round(level, v);
                }
            }
        });
    }

    /** The books' line ("/village offices"): the posts, and who went round last. */
    static List<String> lines(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        if (!keeps(id)) {
            if (Villages.ageOf(id).ordinal() >= Villages.Age.IRON.ordinal()) out.add("The street lamps are lanterns now: they burn all night and day.");
            return out;
        }
        int d = doused(id).size();
        String last = LAST.get(id);
        out.add("The street lamps: " + (d > 0 ? d + " doused for the day, to be lit before the dusk bell" : "lit, or none doused")
            + (last == null ? "" : "; last round by " + last) + ".");
        return out;
    }

    /** Tests: one step of the round now (the works done at once with TownJobs.instantForTests). */
    public static boolean roundForTests(ServerLevel level, Villages.Village v) {
        return round(level, v);
    }

    /** Tests: the first lamp spot's column in the town (its centre plus dx, dz), as {dx, dz}. */
    public static int[] spotForTests(Villages.Village v) {
        for (int ring = 1; ring <= 64; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring && TownWork.lampSpot(dx, dz)) return new int[]{ dx, dz };
                }
            }
        }
        return null;
    }

    /** Tests: the town's doused posts. */
    public static Set<BlockPos> dousedForTests(UUID id) {
        return doused(id);
    }
}
