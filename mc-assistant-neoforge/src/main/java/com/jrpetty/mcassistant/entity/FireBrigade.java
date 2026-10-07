package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [wf] The fire brigade. A town of wood and wool and hay stands under the sky: lightning comes down on
 * it in a storm, a lava pool by the smeltery throws a spark, a campfire's flames catch the next thing
 * along. The game's fire spreads from block to block and eats what it burns, and nothing in a village
 * ever put one out: a player who came back found the hall a shell.
 *
 * <ul>
 * <li><b>Seen.</b> The town is looked over every two seconds for fire on or next to its own blocks: in
 *     the town's reach, burning on or against anything that burns, or on a building's ground.</li>
 * <li><b>Who.</b> The nearest grown folk to it (up to three for a big fire, one more for every six blocks
 *     alight), whatever their trade — the watch too — woken if it must be; never one out of the town
 *     on a caravan or a scouting trip, nor one hired to a player.</li>
 * <li><b>With what.</b> A bucket of water out of the stores if there is one; else an empty bucket, or
 *     one made there and then of three of the stores' iron (buckets are iron), filled at the nearest
 *     water on the way. Water poured on a fire puts out the flames round it and is scooped back up, as a
 *     player does it. With no water to be had, it punches the flames out one by one, as a player can.</li>
 * <li><b>Written down.</b> When the last of it is out, the town's books note the fire (Annals.fire): the
 *     day, where, how it started (lightning, lava, a campfire, a spark from a forge, or nobody saw) and who
 *     put it out, and the news says so. A fire nobody could get to that burns itself out is noted too.</li>
 * <li><b>[disasters] The bell.</b> A new fire rings the town's bell (the fire station's, once there is
 *     one): a quick peal of a dozen strokes, heard all round, the nearest folk to the bell crying where it
 *     is ("Fire at the smithy!"), and the chronicle says so.</li>
 * <li><b>[disasters] A bucket chain.</b> A big fire (six blocks alight or more) with water within thirty
 *     blocks gets a chain besides its hands: four to ten folk in a line from the water to the fire, the
 *     stores' buckets passed hand to hand (BucketChain). The buckets each hand or link had of the stores
 *     go back when the fire is out.</li>
 * <li><b>[disasters] What burned.</b> Each of the town's buildings the fire reaches is taken down as it
 *     stands the moment it is seen, so what burns can be put back (Rebuilding); and no fire takes more than
 *     two buildings: a flame on a third is beaten out by its neighbours at once. Afterwards the town takes
 *     care (FireSafety).</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class FireBrigade {

    private FireBrigade() {}

    /** How often a town is looked over for fire (ticks). */
    static final long LOOK = 40L;
    /** The most hands sent to one fire. */
    static final int MOST_HANDS = 3;
    /** Folk further than this from the fire are not sent. */
    static final int CALL_REACH = 64;
    /** Within this of a burning block (from where it stands) it can get at it: a player's reach. */
    static final double REACH = 3.5;
    /** And this far above or below it (it climbs to the eaves, or stands on the step). */
    static final double REACH_UP = 5.0;
    /** Water poured on a fire puts out the flames this near where it lands. */
    static final double SPLASH = 2.5;
    /** Fire this near another is the same fire. */
    static final int SAME_FIRE = 6;
    /** A hand that gets no nearer to the fire in this long gives it up (on a roof it cannot reach). */
    static final long NO_NEARER = 300L;
    /** Water this near a hand is worth the walk to fill its bucket. */
    static final int WATER_NEAR = 16;
    /** Lightning this near a fire, this lately, started it. */
    static final int STRIKE_NEAR = 12;
    static final long STRIKE_LATELY = 600L;

    /** One fire: the blocks burning together, and who is at it. */
    static final class Blaze {
        final BlockPos first;
        final long started;
        final Set<Long> burning = new LinkedHashSet<>();
        final Set<Long> unreachable = new LinkedHashSet<>();
        String cause = "";
        String where = "";
        final Set<UUID> crew = new LinkedHashSet<>();
        final List<String> names = new ArrayList<>();
        int out;
        boolean water;
        boolean closed;
        /** [disasters] The town's buildings it has reached, each as it stood when the fire was first seen by it. */
        final Map<Long, Map<Long, BlockState>> stood = new java.util.LinkedHashMap<>();
        /** [disasters] Its flames beaten out on a third building: still this fire's, so what catches beyond them is too. */
        final Set<Long> beaten = new LinkedHashSet<>();
        /** [disasters] Its bucket chain's part, written in when the chain stands down; and when one was last tried for. */
        String chain = "";
        long chainTried = -100000L;

        Blaze(BlockPos first, long now) {
            this.first = first.immutable();
            this.started = now;
        }
    }

    /** A town's watch for fire: when it last looked, its fires, and the last lightning on it. */
    static final class Town {
        long looked = -100000L;
        final List<Blaze> blazes = new ArrayList<>();
        @Nullable BlockPos strike;
        long struckAt = -100000L;
    }

    /** A hand at a fire. */
    static final class Hand {
        final UUID village;
        final Blaze blaze;
        boolean kitted;
        @Nullable BlockPos water;
        double best = Double.MAX_VALUE;
        long progress;
        int walkTick = -1000;
        int punchTick = -1000;
        @Nullable BlockPos going;
        /** [disasters] Buckets it had of the stores (or the fire station) for this fire: they go back after. */
        int lent;

        Hand(UUID village, Blaze blaze, long now) {
            this.village = village;
            this.blaze = blaze;
            this.progress = now;
        }
    }

    private static final Map<UUID, Town> TOWNS = new ConcurrentHashMap<>();
    private static final Map<UUID, Hand> HANDS = new ConcurrentHashMap<>();

    public static void resetForTests() {
        TOWNS.clear();
        HANDS.clear();
        ALARMS.clear();
        RUNG.clear();
        Disasters.resetForTests();          // [disasters] the chains, the floods' folk, the lodgers, the fire watch
    }

    /** Is this folk at a fire just now (a hand, or a link in a bucket chain)? */
    public static boolean onIt(VillageFolkEntity f) {
        return HANDS.containsKey(f.getUUID()) || BucketChain.inChain(f);
    }

    // ------------------------------------------------------------------ from the folk's tick

    /**
     * From the folk's tick: the town looked over for fire (every two seconds, whoever comes first), and,
     * if this folk has been sent to one, its work there. True while it is at a fire (its own day waits).
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null) return false;
        Villages.Village v = Villages.get(id);
        if (v == null) return false;
        watch(level, v, false);
        if (BucketChain.inChain(f)) return BucketChain.hold(f, level);     // [disasters] a link in the bucket chain
        Hand h = HANDS.get(f.getUUID());
        if (h == null) return false;
        if (h.blaze.closed || !f.isAlive() || !h.village.equals(id)) {
            HANDS.remove(f.getUUID());
            return false;
        }
        return work(f, level, v, h);
    }

    // ------------------------------------------------------------------ the town looked over

    /** Look the town over for fire, if it is time (or now, with {@code force}), and send hands to what burns. */
    static void watch(ServerLevel level, Villages.Village v, boolean force) {
        if (!v.dim().equals(level.dimension())) return;
        Town t = TOWNS.computeIfAbsent(v.id(), k -> new Town());
        long now = level.getGameTime();
        if (!force && now - t.looked < LOOK && now >= t.looked) return;
        t.looked = now;
        List<BlockPos> found = burning(level, v);
        // What is still alight in each fire, and what is new.
        for (Blaze b : t.blazes) b.burning.removeIf(l -> !level.getBlockState(BlockPos.of(l)).is(BlockTags.FIRE));
        for (BlockPos p : found) {
            if (!ours(level, v, p)) continue;
            Blaze into = null;
            for (Blaze b : t.blazes) {
                if (b.closed) continue;
                for (Long l : b.burning) {
                    if (BlockPos.of(l).distManhattan(p) <= SAME_FIRE) { into = b; break; }
                }
                // [disasters] Beside a flame beaten out on a third building: the same fire, not a new one let burn it.
                if (into == null) for (Long l : b.beaten) {
                    if (BlockPos.of(l).distManhattan(p) <= SAME_FIRE) { into = b; break; }
                }
                if (into == null && b.first.distManhattan(p) <= SAME_FIRE) into = b;
                if (into != null) break;
            }
            if (into == null) {
                into = new Blaze(p, now);
                String sparked = FireSafety.sparkCause(v.id(), p, now);      // [disasters] a spark from a forge
                into.cause = sparked != null ? sparked : cause(level, t, p, now);
                into.where = where(level, v, p);
                t.blazes.add(into);
                Villages.tell(v.id(), level.getDayTime() / 24000L, "Fire " + into.where + "!" + (into.cause.isEmpty() ? "" : " (" + into.cause + ")")
                    + " The bell rang and the town turned out");
                alarm(level, v, into);                                        // [disasters] the bell
            }
            // [disasters] What it reaches, as it stood; and never a third building.
            if (!stood(level, v, into, p)) continue;
            into.burning.add(p.asLong());
        }
        // Out: into the books, and the hands back to their day.
        for (Blaze b : new ArrayList<>(t.blazes)) {
            if (!b.closed && b.burning.isEmpty()) close(level, v, b);
            if (b.closed) t.blazes.remove(b);
        }
        for (Blaze b : t.blazes) send(level, v, b, now);
    }

    /** Every fire block in the town's reach (the sections that may hold fire looked through; at most 256). */
    private static List<BlockPos> burning(ServerLevel level, Villages.Village v) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos c = v.centre();
        int reach = Villages.townReach(v.id()) + 8;
        int minY = Math.max(level.getMinBuildHeight(), c.getY() - 24), maxY = Math.min(level.getMaxBuildHeight() - 1, c.getY() + 48);
        for (int cx = (c.getX() - reach) >> 4; cx <= (c.getX() + reach) >> 4; cx++) {
            for (int cz = (c.getZ() - reach) >> 4; cz <= (c.getZ() + reach) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection s = sections[i];
                    if (s == null || s.hasOnlyAir()) continue;
                    int baseY = level.getSectionYFromSectionIndex(i) << 4;
                    if (baseY + 15 < minY || baseY > maxY) continue;
                    if (!s.maybeHas(st -> st.is(BlockTags.FIRE))) continue;
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (!s.getBlockState(x, y, z).is(BlockTags.FIRE)) continue;
                                out.add(new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z));
                                if (out.size() >= 256) return out;
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * On or next to the town's own blocks: in its reach, against anything that burns, or on a building's
     * ground. Not a hearth: a fire laid on netherrack (what burns for ever in this world) is meant to burn.
     */
    static boolean ours(ServerLevel level, Villages.Village v, BlockPos p) {
        if (level.getBlockState(p.below()).is(level.dimensionType().infiniburn())) return false;
        BlockPos c = v.centre();
        int reach = Villages.townReach(v.id());
        boolean inTown = Math.max(Math.abs(p.getX() - c.getX()), Math.abs(p.getZ() - c.getZ())) <= reach + 4;
        if (onABuilding(v.id(), p, 2)) return true;
        if (!inTown) return false;
        for (Direction d : Direction.values()) {
            BlockPos n = p.relative(d);
            if (level.getBlockState(n).isFlammable(level, n, d.getOpposite())) return true;
        }
        return false;
    }

    /** Is this on the ground of one of the town's buildings (and this much round it)? */
    private static boolean onABuilding(UUID id, BlockPos p, int margin) {
        for (com.jrpetty.mcassistant.village.Ledger.Building b : com.jrpetty.mcassistant.village.Ledger.buildings(id)) {
            int[] half = com.jrpetty.mcassistant.entity.goal.BuildGoal.footprint(b.structure());
            boolean turned = b.facing().getAxis() == Direction.Axis.X;
            int wx = (turned ? half[1] : half[0]) + margin, wz = (turned ? half[0] : half[1]) + margin;
            if (Math.abs(p.getX() - b.anchor().getX()) <= wx && Math.abs(p.getZ() - b.anchor().getZ()) <= wz
                    && p.getY() >= b.anchor().getY() - 2 && p.getY() <= b.anchor().getY() + 40) return true;
        }
        return false;
    }

    /** How it started, as far as can be seen: lightning lately near it, lava by it, a campfire by it. */
    private static String cause(ServerLevel level, Town t, BlockPos p, long now) {
        if (t.strike != null && now - t.struckAt <= STRIKE_LATELY && t.strike.distSqr(p) <= STRIKE_NEAR * STRIKE_NEAR) return "lightning";
        boolean campfire = false;
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-3, -2, -3), p.offset(3, 2, 3))) {
            if (level.getFluidState(q).is(FluidTags.LAVA)) return "lava";
            BlockState st = level.getBlockState(q);
            if (st.getBlock() instanceof CampfireBlock && st.hasProperty(CampfireBlock.LIT) && st.getValue(CampfireBlock.LIT)) campfire = true;
        }
        return campfire ? "a campfire" : "";
    }

    /** Where, in the town's words: by the nearest of its buildings, or on its square, or in the town. */
    private static String where(ServerLevel level, Villages.Village v, BlockPos p) {
        com.jrpetty.mcassistant.village.Ledger.Building best = null;
        double bd = 24.0 * 24.0;
        for (com.jrpetty.mcassistant.village.Ledger.Building b : com.jrpetty.mcassistant.village.Ledger.buildings(v.id())) {
            double d = b.anchor().distSqr(p);
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        if (best != null) return "at " + named(best.structure());
        BlockPos c = v.centre();
        return Math.max(Math.abs(p.getX() - c.getX()), Math.abs(p.getZ() - c.getZ())) <= com.jrpetty.mcassistant.village.TownPlan.PLAZA
            ? "on the square" : "in the town";
    }

    /** Hands to a fire: the nearest grown folk, as many as it wants (one, and one more for every six blocks alight, three at most). */
    private static void send(ServerLevel level, Villages.Village v, Blaze b, long now) {
        b.crew.removeIf(u -> {
            Hand h = HANDS.get(u);
            return h == null || h.blaze != b || !(level.getEntity(u) instanceof VillageFolkEntity f) || !f.isAlive();
        });
        int want = Math.min(MOST_HANDS, 1 + (b.burning.size() - b.unreachable.size()) / 6);
        if (b.burning.size() <= b.unreachable.size()) return;          // all of it out of reach: it burns out
        // [disasters] A big fire: a bucket chain from the nearest water, besides its hands.
        if (b.burning.size() - b.unreachable.size() >= BucketChain.CHAIN_AT && (now - b.chainTried >= 200L || now < b.chainTried)) {
            b.chainTried = now;
            BucketChain.form(level, v, b);
        }
        while (b.crew.size() < want) {
            BlockPos at = BlockPos.of(b.burning.iterator().next());
            VillageFolkEntity best = null;
            double bestScore = Double.MAX_VALUE;
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (!(a instanceof VillageFolkEntity f) || !fit(f) || onIt(f)) continue;     // [disasters] not one in the chain
                double d = Math.sqrt(f.blockPosition().distSqr(at));
                if (d > CALL_REACH) continue;
                double score = d + (f.isSleeping() ? 24.0 : 0.0);
                if (score < bestScore) {
                    bestScore = score;
                    best = f;
                }
            }
            if (best == null) return;
            Hand h = new Hand(v.id(), b, now);
            HANDS.put(best.getUUID(), h);
            b.crew.add(best.getUUID());
            if (best.isSleeping()) best.stopSleeping();
            best.clearQueue();
            best.getNavigation().stop();
            best.brain("called to a fire " + b.where);
            FolkTalk.speak(best, FolkTalk.pick(level.getRandom(), "Fire! Fire " + b.where + "!", "Fire " + b.where + " — water, quick!",
                "There's smoke " + b.where + ". I'm on it."));
        }
    }

    /** Free to go to a fire? Grown, here, and its own (not out on a caravan, a scouting trip or hired out). */
    static boolean fit(VillageFolkEntity f) {
        if (!f.isAlive() || f.isBaby() || f.isShowcase() || f.isHired()) return false;
        if (f.trip() != null || f.expedition() != null || Nether.away(f)) return false;
        if (JobSeekers.travelling(f)) return false;                      // [disasters] on the road to a new home
        return f.getTarget() == null;                                    // not in a fight
    }

    /** The fire out: written into the town's books, and the news. */
    private static void close(ServerLevel level, Villages.Village v, Blaze b) {
        b.closed = true;
        for (UUID u : b.crew) {
            Hand h = HANDS.remove(u);
            if (h != null && level.getEntity(u) instanceof VillageFolkEntity f) giveBack(level, v, f, h);   // [disasters] the buckets back
        }
        BucketChain.standDown(level, v, b);                               // [disasters] the chain's buckets back
        long day = level.getDayTime() / 24000L;
        String how = b.cause.isEmpty() ? "" : ", started by " + b.cause;
        String line;
        if (b.names.isEmpty()) {
            line = "a fire " + b.where + how + " burnt itself out";
        } else {
            String who = b.names.size() == 1 ? b.names.get(0)
                : String.join(", ", b.names.subList(0, b.names.size() - 1)) + " and " + b.names.get(b.names.size() - 1);
            line = "a fire " + b.where + how + " was put out by " + who + (b.water ? " with a bucket of water" : " by hand");
        }
        if (!b.chain.isEmpty()) line += ", " + b.chain;
        Annals.fire(v.id(), day, line);
        Villages.tell(v.id(), day, line);
        // [disasters] What burned put back from the stores, and the town takes care after it.
        Rebuilding.afterFire(level, v, b.stood, b.where.replaceFirst("^(at|on|in) ", ""), b.cause);
        FireSafety.afterFire(level, v, b.cause, b.where, day);
    }

    /** [disasters] A hand's buckets of the stores back where they came from (the fire station, else the stores). */
    private static void giveBack(ServerLevel level, Villages.Village v, VillageFolkEntity f, Hand h) {
        for (int i = 0; i < h.lent; i++) {
            ItemStack back = ItemStack.EMPTY;
            if (f.removeMatching(s -> s.is(Items.BUCKET), 1) == 1) back = new ItemStack(Items.BUCKET);
            else if (f.removeMatching(s -> s.is(Items.WATER_BUCKET), 1) == 1) back = new ItemStack(Items.WATER_BUCKET);
            if (back.isEmpty()) break;
            FireSafety.putBack(level, v, back);
        }
        h.lent = 0;
    }

    // ------------------------------------------------------------------ at the fire

    private static boolean work(VillageFolkEntity f, ServerLevel level, Villages.Village v, Hand h) {
        long now = level.getGameTime();
        Blaze b = h.blaze;
        BlockPos fire = nearestFire(level, f, b);
        if (fire == null) {
            HANDS.remove(f.getUUID());
            b.crew.remove(f.getUUID());
            giveBack(level, v, f, h);                                     // [disasters]
            return false;
        }
        if (!h.kitted) {
            h.kitted = true;
            kit(f, level, v, h, fire);
        }
        // An empty bucket and water near: filled first.
        if (h.water != null && f.countCarried(s -> s.is(Items.BUCKET)) > 0 && f.countCarried(s -> s.is(Items.WATER_BUCKET)) == 0) {
            if (fill(f, level, h, now)) return true;
        }
        double dx = f.getX() - (fire.getX() + 0.5), dz = f.getZ() - (fire.getZ() + 0.5);
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > REACH || Math.abs(f.getY() - fire.getY()) > REACH_UP) {
            if (!fire.equals(h.going)) {
                h.going = fire;
                h.best = Double.MAX_VALUE;
                h.progress = now;
            }
            if (d < h.best - 0.5) {
                h.best = d;
                h.progress = now;
            } else if (now - h.progress > NO_NEARER || now < h.progress) {
                b.unreachable.add(fire.asLong());                   // on a roof it cannot get to: left to burn out
                h.going = null;
                f.brain("could not get at the fire " + b.where);
                return true;
            }
            if (f.getNavigation().isDone() || f.tickCount - h.walkTick > 20) {
                f.walkTo(fire, 1.3D);
                h.walkTick = f.tickCount;
            }
            f.brain("running to the fire " + b.where);
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(fire.getX() + 0.5, fire.getY() + 0.3, fire.getZ() + 0.5);
        String name = f.displayNameCap();
        if (f.countCarried(s -> s.is(Items.WATER_BUCKET)) > 0) {
            // Poured, and scooped back up: every flame within a couple of blocks of where it lands goes out.
            int out = 0;
            for (BlockPos p : BlockPos.betweenClosed(fire.offset(-3, -2, -3), fire.offset(3, 2, 3))) {
                if (p.distSqr(fire) > SPLASH * SPLASH || !level.getBlockState(p).is(BlockTags.FIRE)) continue;
                level.removeBlock(p, false);
                b.burning.remove(p.asLong());
                out++;
            }
            level.playSound(null, fire, SoundEvents.BUCKET_EMPTY, SoundSource.NEUTRAL, 1.0F, 1.0F);
            level.levelEvent(null, 1009, fire, 0);                   // the hiss of a fire put out
            level.playSound(null, fire, SoundEvents.BUCKET_FILL, SoundSource.NEUTRAL, 0.8F, 1.0F);
            f.swing(InteractionHand.MAIN_HAND);
            b.out += out;
            b.water = true;
            if (!b.names.contains(name)) b.names.add(name);
            f.brain("put out " + out + " of the fire " + b.where + " with a bucket of water");
            return true;
        }
        if (f.tickCount - h.punchTick < 6) return true;
        h.punchTick = f.tickCount;
        level.removeBlock(fire, false);
        level.levelEvent(null, 1009, fire, 0);
        b.burning.remove(fire.asLong());
        f.swing(InteractionHand.MAIN_HAND);
        b.out++;
        if (!b.names.contains(name)) b.names.add(name);
        f.brain("beating out the fire " + b.where + " by hand");
        return true;
    }

    /** The nearest of the fire's blocks still alight that it has not given up on. */
    @Nullable
    private static BlockPos nearestFire(ServerLevel level, VillageFolkEntity f, Blaze b) {
        BlockPos me = f.blockPosition();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (Long l : new ArrayList<>(b.burning)) {
            BlockPos p = BlockPos.of(l);
            if (!level.getBlockState(p).is(BlockTags.FIRE)) {
                b.burning.remove(l);
                continue;
            }
            if (b.unreachable.contains(l)) continue;
            double d = p.distSqr(me);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    /**
     * Water for the fire: a bucket of it out of the stores; else an empty bucket out of them, or one made
     * there and then of three of the stores' iron; and the nearest water to fill it at, if there is any
     * near. Nothing from nothing: with no bucket and no iron, its hands.
     */
    private static void kit(VillageFolkEntity f, ServerLevel level, Villages.Village v, Hand h, BlockPos fire) {
        if (f.countCarried(s -> s.is(Items.WATER_BUCKET)) > 0) return;
        if (f.countCarried(s -> s.is(Items.BUCKET)) == 0) {
            // [disasters] The fire station's buckets first, hanging ready; whatever it takes it gives back after.
            ItemStack racked = FireSafety.takeBucket(level, v);
            if (!racked.isEmpty()) {
                give(f, level, v, racked);
                h.lent++;
                f.brain("took a bucket off the fire station's rack");
                if (racked.is(Items.WATER_BUCKET)) return;
            } else if (Crafts.take(level, v, s -> s.is(Items.WATER_BUCKET), 1)) {
                give(f, level, v, new ItemStack(Items.WATER_BUCKET));
                h.lent++;
                f.brain("took a bucket of water from the stores for the fire");
                return;
            } else if (Crafts.take(level, v, s -> s.is(Items.BUCKET), 1)) {
                give(f, level, v, new ItemStack(Items.BUCKET));
                h.lent++;
            } else if (Market.stock(level, v.id(), s -> s.is(Items.IRON_INGOT)) >= 3 && Crafts.take(level, v, s -> s.is(Items.IRON_INGOT), 3)) {
                give(f, level, v, new ItemStack(Items.BUCKET));             // three iron, as the game makes one
                h.lent++;
                f.brain("made a bucket of three of the stores' iron for the fire");
            }
        }
        if (f.countCarried(s -> s.is(Items.BUCKET)) > 0) h.water = water(level, f.blockPosition(), fire);
    }

    private static void give(VillageFolkEntity f, ServerLevel level, Villages.Village v, ItemStack s) {
        ItemStack left = f.insertItem(s);
        if (!left.isEmpty()) Crafts.store(level, v, left);
    }

    /** The nearest still water near the hand or the fire, to fill a bucket at; or null. */
    @Nullable
    private static BlockPos water(ServerLevel level, BlockPos me, BlockPos fire) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos from : new BlockPos[]{ me, fire }) {
            for (BlockPos p : BlockPos.betweenClosed(from.offset(-WATER_NEAR, -4, -WATER_NEAR), from.offset(WATER_NEAR, 3, WATER_NEAR))) {
                if (!fullCauldron(level.getBlockState(p))                  // [disasters] a workshop's cauldron of water
                        && (!level.getBlockState(p).is(Blocks.WATER) || !level.getFluidState(p).isSource())) continue;
                double d = p.distSqr(me);
                if (d < bd) {
                    bd = d;
                    best = p.immutable();
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** To the water and fill the bucket. True while it is at it. */
    private static boolean fill(VillageFolkEntity f, ServerLevel level, Hand h, long now) {
        BlockPos w = h.water;
        boolean cauldron = w != null && fullCauldron(level.getBlockState(w));
        if (w == null || !cauldron && (!level.getBlockState(w).is(Blocks.WATER) || !level.getFluidState(w).isSource())) {
            h.water = null;
            return false;
        }
        double dx = f.getX() - (w.getX() + 0.5), dz = f.getZ() - (w.getZ() + 0.5);
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > REACH || Math.abs(f.getY() - w.getY()) > 3.0) {
            if (!w.equals(h.going)) {
                h.going = w;
                h.best = Double.MAX_VALUE;
                h.progress = now;
            }
            if (d < h.best - 0.5) {
                h.best = d;
                h.progress = now;
            } else if (now - h.progress > NO_NEARER / 2 || now < h.progress) {
                h.water = null;                                    // no way down to it: its hands, then
                return false;
            }
            if (f.getNavigation().isDone() || f.tickCount - h.walkTick > 20) {
                f.walkTo(w, 1.3D);
                h.walkTick = f.tickCount;
            }
            f.brain("filling a bucket for the fire");
            return true;
        }
        // A source with water either side of it fills again (as the game's does); a lone one is taken up;
        // [disasters] a full cauldron is emptied into it, as a player empties one.
        if (cauldron) {
            level.setBlockAndUpdate(w, Blocks.CAULDRON.defaultBlockState());
        } else {
            int sources = 0;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos n = w.relative(dir);
                if (level.getFluidState(n).is(FluidTags.WATER) && level.getFluidState(n).isSource()) sources++;
            }
            if (sources < 2) level.setBlockAndUpdate(w, Blocks.AIR.defaultBlockState());
        }
        if (f.removeMatching(s -> s.is(Items.BUCKET), 1) == 1) {
            ItemStack left = f.insertItem(new ItemStack(Items.WATER_BUCKET));
            if (!left.isEmpty()) net.minecraft.world.level.block.Block.popResource(level, f.blockPosition(), left);
        }
        level.playSound(null, w, SoundEvents.BUCKET_FILL, SoundSource.NEUTRAL, 1.0F, 1.0F);
        f.swing(InteractionHand.MAIN_HAND);
        h.water = null;
        h.going = null;
        f.brain("filled a bucket at the water for the fire");
        return true;
    }

    // ------------------------------------------------------------------ [disasters] the bell, what burns, the names

    /** The fire bell: a dozen quick strokes. */
    static final int ALARM_STROKES = 12;
    static final long ALARM_STROKE = 8L;

    /** A peal for a fire: the bell (or null: called aloud, as the hours are with no bell), the strokes left, the next. */
    static final class Alarm {
        @Nullable final BlockPos bell;
        final BlockPos fire;
        final String where;
        int left = ALARM_STROKES;
        long next;

        Alarm(@Nullable BlockPos bell, BlockPos fire, String where, long now) {
            this.bell = bell == null ? null : bell.immutable();
            this.fire = fire.immutable();
            this.where = where;
            this.next = now;
        }
    }

    private static final Map<UUID, Alarm> ALARMS = new ConcurrentHashMap<>();
    /** Strokes rung for fire, by town, all told (the books, the tests). */
    private static final Map<UUID, Integer> RUNG = new ConcurrentHashMap<>();

    /** A new fire: the bell rung for it (the fire station's, else the town's), and the cry raised by the nearest to it. */
    static void alarm(ServerLevel level, Villages.Village v, Blaze b) {
        if (ALARMS.containsKey(v.id())) return;                          // one peal at a time
        BlockPos bell = FireSafety.stationBell(level, v);
        if (bell == null) bell = TownBell.bellAt(level, v);
        ALARMS.put(v.id(), new Alarm(bell, b.first, b.where, level.getGameTime()));
        // Whoever is nearest the bell cries it out, as the town crier would.
        BlockPos at = bell != null ? bell : v.centre();
        VillageFolkEntity crier = null;
        double cd = 32.0 * 32.0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isSleeping() || !f.isAlive()) continue;
            double d = f.blockPosition().distSqr(at);
            if (d < cd) { cd = d; crier = f; }
        }
        if (crier != null) {
            String where = Disasters.capital(b.where);
            FolkTalk.speak(crier, FolkTalk.pick(level.getRandom(), "Fire " + b.where + "! Fire! Bring your buckets!",
                "Fire! " + where + "! Everybody to the water!", "Ring the bell! Fire " + b.where + "!"));
        }
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (ALARMS.isEmpty()) return;
        com.jrpetty.mcassistant.Guard.run("the fire bell", () -> strike(event.getServer()));
    }

    /** The fire bell's strokes, each in its turn. */
    private static void strike(net.minecraft.server.MinecraftServer server) {
        for (Map.Entry<UUID, Alarm> e : ALARMS.entrySet()) {
            Villages.Village v = Villages.get(e.getKey());
            ServerLevel level = v == null ? null : server.getLevel(v.dim());
            Alarm a = e.getValue();
            if (level == null || a.left <= 0) {
                ALARMS.remove(e.getKey());
                continue;
            }
            long now = level.getGameTime();
            if (now < a.next) continue;
            a.next = now + ALARM_STROKE;
            a.left--;
            RUNG.merge(e.getKey(), 1, Integer::sum);
            BlockState st = a.bell == null || !level.isLoaded(a.bell) ? null : level.getBlockState(a.bell);
            if (st != null && st.getBlock() instanceof net.minecraft.world.level.block.BellBlock bell) {
                bell.attemptToRing(level, a.bell, st.getValue(net.minecraft.world.level.block.BellBlock.FACING));
            } else {
                level.playSound(null, v.centre(), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 3.0F, 1.1F);
            }
        }
    }

    /**
     * What a fire reaches, as it stood: the first time a flame is seen on or beside one of the town's buildings,
     * the building is taken down as it stands (Rebuilding.snapshot). A third building is never let burn: a
     * flame on it is beaten out by its neighbours there and then. False if this flame was put out so.
     */
    static boolean stood(ServerLevel level, Villages.Village v, Blaze b, BlockPos p) {
        com.jrpetty.mcassistant.village.Ledger.Building on = Rebuilding.buildingAt(v.id(), p, 2);
        if (on == null || b.stood.containsKey(on.anchor().asLong())) return true;
        if (b.stood.size() >= 2) {
            level.removeBlock(p, false);
            level.levelEvent(null, 1009, p, 0);                          // the hiss of a flame put out
            if (b.beaten.size() < 256) b.beaten.add(p.asLong());
            Disasters.town(v.id()).keptFrom++;
            Disasters.dirty();
            return false;
        }
        b.stood.put(on.anchor().asLong(), Rebuilding.snapshot(level, v.id(), on));
        return true;
    }

    /** A building's name, for the cry and the books: "the smithy", "the house", "the meeting hall". */
    static String named(String structure) {
        return switch (structure) {
            case "house", "house2" -> "the house";
            case "guesthouse" -> "the guest house";
            case "storage", "storehouse" -> "the storehouse";
            default -> Villages.spoken(structure).replaceFirst("^(a|an) ", "the ");
        };
    }

    /** A cauldron full of water (a workshop's, FireSafety), to fill a bucket at. */
    static boolean fullCauldron(BlockState st) {
        return st.is(Blocks.WATER_CAULDRON) && st.getValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL)
            >= net.minecraft.world.level.block.LayeredCauldronBlock.MAX_FILL_LEVEL;
    }

    /** Where the town is on fire now ("at the smithy"), for the board; null if nowhere. */
    @Nullable
    static String burningNow(UUID village) {
        Town t = TOWNS.get(village);
        if (t == null) return null;
        for (Blaze b : t.blazes) if (!b.closed && !b.burning.isEmpty()) return b.where;
        return null;
    }

    /** Is a fire still burning near this spot (a building being rebuilt waits for it)? */
    static boolean burningNear(UUID village, BlockPos p) {
        Town t = TOWNS.get(village);
        if (t == null) return false;
        for (Blaze b : t.blazes) {
            if (b.closed) continue;
            for (Long l : b.burning) if (BlockPos.of(l).distManhattan(p) <= 16) return true;
        }
        return false;
    }

    /** Tests: the strokes the fire bell has rung for this town, all told. */
    public static int alarmForTests(UUID village) {
        return RUNG.getOrDefault(village, 0);
    }

    // ------------------------------------------------------------------ lightning

    /** Lightning on a town: remembered a while, so a fire it starts is put down to it. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getEntity() instanceof LightningBolt bolt) || !(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos at = bolt.blockPosition();
        Villages.Village v = Villages.nearest(level, at, 160);
        if (v == null) return;
        int reach = Villages.townReach(v.id()) + 16;
        if (Math.max(Math.abs(at.getX() - v.centre().getX()), Math.abs(at.getZ() - v.centre().getZ())) > reach) return;
        Town t = TOWNS.computeIfAbsent(v.id(), k -> new Town());
        t.strike = at.immutable();
        t.struckAt = level.getGameTime();
        t.looked = Math.min(t.looked, level.getGameTime() - LOOK + 10);   // looked over again in half a second
    }

    // ------------------------------------------------------------------ tests

    /** Tests: look the town over for fire now, and send hands. */
    public static void watchForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v != null) watch(level, v, true);
    }

    /** Tests: the fires the town is at now, {blazes, blocks alight, hands at them}. */
    public static int[] townForTests(UUID village) {
        Town t = TOWNS.get(village);
        if (t == null) return new int[3];
        int blocks = 0, hands = 0;
        for (Blaze b : t.blazes) {
            blocks += b.burning.size();
            hands += b.crew.size();
        }
        return new int[]{ t.blazes.size(), blocks, hands };
    }

    /** Tests: lightning struck here, now (as the game's own bolt tells it). */
    public static void struckForTests(ServerLevel level, UUID village, BlockPos at) {
        Town t = TOWNS.computeIfAbsent(village, k -> new Town());
        t.strike = at.immutable();
        t.struckAt = level.getGameTime();
    }

    /** Tests: is this a fire of the town's (on or next to its own blocks)? */
    public static boolean oursForTests(ServerLevel level, UUID village, BlockPos p) {
        Villages.Village v = Villages.get(village);
        return v != null && ours(level, v, p);
    }
}
