package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [disasters] A bucket chain: for a big fire, a line of folk from the nearest water to the fire, passing the
 * town's buckets hand to hand.
 *
 * <ul>
 * <li><b>When.</b> A fire with six blocks or more alight (FireBrigade), water (a river, a pond, a well's spring)
 *     within thirty blocks of it, and four grown folk free to stand in it within call. Small fires keep their one
 *     to three hands, and a big one keeps them too: the chain is besides.</li>
 * <li><b>The line.</b> Four to ten folk, a couple of paces apart, on the ground between a standing place at the
 *     water's edge and one within a bucket's throw of the fire, each walking to its place. The first fills, the
 *     last throws, the rest pass.</li>
 * <li><b>The buckets.</b> Real ones: off the fire station's rack, else the stores' (a bucket of water as it is),
 *     else made there and then of three of the stores' iron each; half as many as there are links, one more,
 *     four at most. Each is in a folk's hand (its off hand, where it can be seen) the whole time. Every few
 *     ticks each full bucket moves one link toward the fire and each empty one link back, swapped between two
 *     folk where they meet; the first fills an empty one at the water (taking a lone source, as a player does,
 *     a river's being made good), the last throws a full one on the fire: every flame within a couple of blocks
 *     of it goes out.</li>
 * <li><b>After.</b> When the fire is out the line stands down and every bucket goes back where it came from (the
 *     fire station's rack, else the stores); the books say how many passes it made and how many flames it put
 *     out.</li>
 * </ul>
 */
public final class BucketChain {

    private BucketChain() {}

    /** Blocks alight before a fire gets a chain. */
    static final int CHAIN_AT = 6;
    /** The fewest and the most in a chain. */
    static final int LEAST = 4, MOST = 10;
    /** The paces between two links, about. */
    static final double SPACING = 2.6;
    /** Water this near the fire will do for a chain. */
    static final int WATER_REACH = 30;
    /** A pass every so many ticks: a full bucket goes from the water to a fire ten blocks off in two seconds. */
    static final long PASS = 10L;
    /** Two links further apart than this cannot pass a bucket. */
    static final double HAND_TO_HAND = 4.5;
    /** The line is laid out again (the fire moved on) at most this often. */
    static final long REFORM = 200L;

    /** One chain: its fire, its water, its links and their places, and how it has done. */
    static final class Chain {
        final UUID village;
        final FireBrigade.Blaze blaze;
        BlockPos water;
        BlockPos fire;
        final List<UUID> links = new ArrayList<>();
        final List<BlockPos> spots = new ArrayList<>();
        long lastPass;
        long formed;
        int parity;
        int passes, fills, pours, out, buckets, made;
        /** The buckets each link had of its own when it joined (so the chain takes back only its own). */
        final Map<UUID, Integer> had = new ConcurrentHashMap<>();
        String from = "the water";

        Chain(UUID village, FireBrigade.Blaze blaze, long now) {
            this.village = village;
            this.blaze = blaze;
            this.formed = now;
            this.lastPass = now;
        }
    }

    private static final Map<UUID, Chain> CHAINS = new ConcurrentHashMap<>();
    private static final Map<UUID, Chain> LINKS = new ConcurrentHashMap<>();
    /** Each link's walk to its place: the nearest it has come, and when; and its last step. */
    private static final Map<UUID, double[]> WALKS = new ConcurrentHashMap<>();
    /** The last chain that stood down, by town (the tests: {links, passes, fills, pours, out, buckets, made}). */
    private static final Map<UUID, int[]> LAST = new ConcurrentHashMap<>();

    static void resetForTests() {
        CHAINS.clear();
        LINKS.clear();
        WALKS.clear();
        LAST.clear();
    }

    public static boolean inChain(VillageFolkEntity f) {
        return LINKS.containsKey(f.getUUID());
    }

    // ------------------------------------------------------------------ forming the line

    /** A big fire: a chain formed for it, if it has none, there is water near and folk enough. */
    static void form(ServerLevel level, Villages.Village v, FireBrigade.Blaze b) {
        Chain have = CHAINS.get(v.id());
        if (have != null && have.blaze == b) {
            b.chainTried = -100000L;                                    // it has one: looked at again whenever asked
            return;
        }
        if (have != null) standDown(level, v, have.blaze);               // an old fire's chain, gone over to this one
        BlockPos fire = BlockPos.of(b.burning.iterator().next());
        BlockPos water = water(level, fire);
        if (water == null) return;
        List<BlockPos> spots = line(level, water, fire);
        if (spots.size() < LEAST) return;
        // Who stands in it: the nearest grown folk free to, each to the place nearest it, from the fire's end.
        List<VillageFolkEntity> free = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !FireBrigade.fit(f) || FireBrigade.onIt(f)) continue;
            if (Math.sqrt(f.blockPosition().distSqr(fire)) > FireBrigade.CALL_REACH) continue;
            free.add(f);
        }
        if (free.size() < LEAST) return;
        int n = Math.min(spots.size(), free.size());
        // A line of n: the water's end and the fire's end kept, the places between spread along it.
        List<BlockPos> use = new ArrayList<>();
        for (int i = 0; i < n; i++) use.add(spots.get((int) Math.round(i * (spots.size() - 1) / (double) Math.max(1, n - 1))));
        Chain c = new Chain(v.id(), b, level.getGameTime());
        c.water = water;
        c.fire = fire;
        c.spots.addAll(use);
        VillageFolkEntity[] chosen = new VillageFolkEntity[n];
        for (int i = n - 1; i >= 0; i--) {
            VillageFolkEntity best = null;
            double bd = Double.MAX_VALUE;
            for (VillageFolkEntity f : free) {
                double d = f.blockPosition().distSqr(use.get(i));
                if (d < bd) { bd = d; best = f; }
            }
            chosen[i] = best;
            free.remove(best);
        }
        // The buckets: off the fire station's rack, the stores', or made of their iron.
        int want = Math.min(4, n / 2 + 1);
        List<ItemStack> buckets = new ArrayList<>();
        for (int i = 0; i < want; i++) {
            ItemStack s = FireSafety.takeBucket(level, v);
            if (s.isEmpty() && Crafts.take(level, v, x -> x.is(Items.WATER_BUCKET), 1)) s = new ItemStack(Items.WATER_BUCKET);
            if (s.isEmpty() && Crafts.take(level, v, x -> x.is(Items.BUCKET), 1)) s = new ItemStack(Items.BUCKET);
            if (s.isEmpty() && Market.stock(level, v.id(), x -> x.is(Items.IRON_INGOT)) >= 3 && Crafts.take(level, v, x -> x.is(Items.IRON_INGOT), 3)) {
                s = new ItemStack(Items.BUCKET);
                c.made++;
            }
            if (s.isEmpty()) break;
            buckets.add(s);
        }
        if (buckets.isEmpty()) return;                                  // nothing to carry water in: the hands' fists, then
        c.buckets = buckets.size();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = chosen[i];
            // Its off hand cleared for the bucket (whatever it held into its pack).
            ItemStack off = f.getItemBySlot(EquipmentSlot.OFFHAND);
            if (!off.isEmpty()) {
                ItemStack left = f.insertItem(off.copy());
                if (!left.isEmpty()) f.spawnAtLocation(left);
                f.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            }
            c.links.add(f.getUUID());
            c.had.put(f.getUUID(), f.countCarried(FireSafety::bucket));
            LINKS.put(f.getUUID(), c);
            WALKS.remove(f.getUUID());
            if (f.isSleeping()) f.stopSleeping();
            f.clearQueue();
            f.getNavigation().stop();
            f.brain("in the bucket chain for the fire " + b.where);
        }
        // The full buckets nearest the fire to start with, the empty ones by the water.
        List<ItemStack> full = new ArrayList<>(), empty = new ArrayList<>();
        for (ItemStack s : buckets) (s.is(Items.WATER_BUCKET) ? full : empty).add(s);
        for (int i = 0; i < empty.size(); i++) chosen[i].setItemSlot(EquipmentSlot.OFFHAND, empty.get(i));
        for (int i = 0; i < full.size(); i++) chosen[n - 1 - i].setItemSlot(EquipmentSlot.OFFHAND, full.get(i));
        c.from = Droughts.source(level, v, water);
        CHAINS.put(v.id(), c);
        VillageFolkEntity first = chosen[0];
        FolkTalk.speak(first, FolkTalk.pick(level.getRandom(), "Make a line! Buckets from " + c.from + "!", "Chain! Get in a line, quick!",
            "Line up from " + c.from + " — pass them along!"));
    }

    /** The nearest open water to the fire to fill buckets at (a river, a pond, a well's spring); null if none near. */
    @Nullable
    static BlockPos water(ServerLevel level, BlockPos fire) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(fire.offset(-WATER_REACH, -6, -WATER_REACH), fire.offset(WATER_REACH, 4, WATER_REACH))) {
            BlockState st = level.getBlockState(p);
            if (!(st.is(Blocks.WATER) && level.getFluidState(p).isSource() && level.getBlockState(p.above()).isAir())) continue;
            double d = p.distSqr(fire);
            if (d < bd && d >= 9) { bd = d; best = p.immutable(); }
        }
        return best;
    }

    /**
     * The places along the line: one at the water's edge (where a folk can reach down to fill), one within a
     * throw of the fire, and as many between as fit a couple of paces apart (up to ten), each a spot a folk can
     * stand on near the straight line between them. Short of four, no chain.
     */
    static List<BlockPos> line(ServerLevel level, BlockPos water, BlockPos fire) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos start = standNear(level, water, fire, 2);
        BlockPos end = standNear(level, fire, water, 3);
        if (start == null || end == null) return out;
        double dx = end.getX() - start.getX(), dz = end.getZ() - start.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        int n = (int) Math.max(LEAST, Math.min(MOST, Math.round(len / SPACING) + 1));
        out.add(start);
        for (int i = 1; i < n - 1; i++) {
            double t = i / (double) (n - 1);
            BlockPos at = new BlockPos((int) Math.round(start.getX() + dx * t), (int) Math.round(start.getY() + (end.getY() - start.getY()) * t),
                (int) Math.round(start.getZ() + dz * t));
            BlockPos s = standable(level, at, 2);
            if (s != null && !out.contains(s) && !s.equals(end)) out.add(s);
        }
        out.add(end);
        return out;
    }

    /** A spot to stand on near {@code at}, as near as may be to it and on the side toward {@code toward}. */
    @Nullable
    static BlockPos standNear(ServerLevel level, BlockPos at, BlockPos toward, int reach) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos p = at.offset(dx, dy, dz);
                    if (!stand(level, p)) continue;
                    double d = p.distSqr(at) * 2 + p.distSqr(toward) * 0.1;
                    if (d < bd) { bd = d; best = p.immutable(); }
                }
            }
        }
        return best;
    }

    /** A spot to stand on within {@code reach} of here (the column first, then round it). */
    @Nullable
    static BlockPos standable(ServerLevel level, BlockPos at, int reach) {
        for (int r = 0; r <= reach; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = 0; dy <= 3; dy++) {
                        for (int sign : new int[]{ 1, -1 }) {
                            BlockPos p = at.offset(dx, dy * sign, dz);
                            if (stand(level, p)) return p.immutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Room to stand: two clear blocks (no fire, no water) over something firm. */
    static boolean stand(ServerLevel level, BlockPos p) {
        if (!level.isLoaded(p)) return false;
        BlockState feet = level.getBlockState(p), head = level.getBlockState(p.above()), below = level.getBlockState(p.below());
        if (!feet.getCollisionShape(level, p).isEmpty() || !head.getCollisionShape(level, p.above()).isEmpty()) return false;
        if (feet.is(BlockTags.FIRE) || head.is(BlockTags.FIRE) || !level.getFluidState(p).isEmpty()) return false;
        if (!below.isFaceSturdy(level, p.below(), Direction.UP)) return false;
        // Not right against the flames: nobody's feet will take it there (the way is barred beside a fire).
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-1, -1, -1), p.offset(1, 2, 1))) {
            if (level.getBlockState(q).is(BlockTags.FIRE)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ in the line

    /** From the folk's tick (FireBrigade.hold): to its place, and there, the chain's passes. True while it is in the chain. */
    static boolean hold(VillageFolkEntity f, ServerLevel level) {
        Chain c = LINKS.get(f.getUUID());
        if (c == null) return false;
        int i = c.links.indexOf(f.getUUID());
        if (c.blaze.closed || i < 0 || !f.isAlive()) {
            Villages.Village v = Villages.get(c.village);
            if (v != null) standDown(level, v, c.blaze);
            return false;
        }
        long now = level.getGameTime();
        BlockPos spot = c.spots.get(i);
        double d = flat(f, spot);
        if (d > 1.2 || Math.abs(f.getY() - spot.getY()) > 2.5) {
            double[] w = WALKS.computeIfAbsent(f.getUUID(), k -> new double[]{ Double.MAX_VALUE, now, -1000 });
            if (d < w[0] - 0.4) {
                w[0] = d;
                w[1] = now;
            } else if (now - w[1] > 160 || now < w[1]) {
                // No nearer for eight seconds: it takes its place where it stands, if it can stand there.
                if (stand(level, f.blockPosition())) c.spots.set(i, f.blockPosition());
                w[1] = now;
            }
            if (f.getNavigation().isDone() || f.tickCount - (int) w[2] > 20) {
                f.walkTo(spot, 1.3D);
                w[2] = f.tickCount;
            }
            f.hobbyNow = "running to its place in the bucket chain";
            return true;
        }
        f.getNavigation().stop();
        // It faces the way its bucket goes: a full one toward the fire, an empty one (or none) toward the water.
        ItemStack held = f.getItemBySlot(EquipmentSlot.OFFHAND);
        int toward = held.is(Items.WATER_BUCKET) ? i + 1 : i - 1;
        if (toward >= 0 && toward < c.links.size()) {
            BlockPos p = c.spots.get(toward);
            f.getLookControl().setLookAt(p.getX() + 0.5, p.getY() + 1.4, p.getZ() + 0.5);
        } else {
            BlockPos p = toward < 0 ? c.water : c.fire;
            f.getLookControl().setLookAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
        }
        f.hobbyNow = i == 0 ? "filling buckets for the chain" : i == c.links.size() - 1 ? "throwing water on the fire" : "passing buckets in the chain";
        if (now - c.lastPass >= PASS || now < c.lastPass) {
            c.lastPass = now;
            step(level, c);
        }
        return true;
    }

    private static double flat(VillageFolkEntity f, BlockPos p) {
        double dx = f.getX() - (p.getX() + 0.5), dz = f.getZ() - (p.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Nullable
    private static VillageFolkEntity link(ServerLevel level, Chain c, int i) {
        return level.getEntity(c.links.get(i)) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
    }

    /** At its place (or as near as it could get), and near enough the next to hand a bucket over. */
    private static boolean placed(VillageFolkEntity f, Chain c, int i) {
        BlockPos s = c.spots.get(i);
        return flat(f, s) <= 1.6 && Math.abs(f.getY() - s.getY()) <= 2.5;
    }

    /**
     * One pass of the chain: the first fills an empty bucket at the water, the last throws a full one on the
     * fire, and between them every other pair of neighbours hands on what it can (a full bucket toward the fire,
     * an empty one back; swapped where a full meets an empty).
     */
    static void step(ServerLevel level, Chain c) {
        int n = c.links.size();
        VillageFolkEntity[] l = new VillageFolkEntity[n];
        for (int i = 0; i < n; i++) l[i] = link(level, c, i);
        // The water's end: an empty bucket filled.
        VillageFolkEntity first = l[0];
        if (first != null && placed(first, c, 0) && first.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.BUCKET)) {
            if (Droughts.fill(level, c.water)) {
                first.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.WATER_BUCKET));
                first.swing(InteractionHand.OFF_HAND);
                level.playSound(null, c.water, SoundEvents.BUCKET_FILL, SoundSource.NEUTRAL, 1.0F, 1.0F);
                c.fills++;
            } else {
                BlockPos again = water(level, c.fire);                    // the water there gone: the nearest other
                if (again != null) c.water = again;
            }
        }
        // The fire's end: a full bucket thrown on the nearest flame within a throw.
        VillageFolkEntity last = l[n - 1];
        if (last != null && placed(last, c, n - 1) && last.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.WATER_BUCKET)) {
            BlockPos target = target(level, c, last);
            if (target != null) {
                int out = 0;
                for (BlockPos p : BlockPos.betweenClosed(target.offset(-3, -2, -3), target.offset(3, 2, 3))) {
                    if (p.distSqr(target) > FireBrigade.SPLASH * FireBrigade.SPLASH || !level.getBlockState(p).is(BlockTags.FIRE)) continue;
                    level.removeBlock(p, false);
                    c.blaze.burning.remove(p.asLong());
                    out++;
                }
                last.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.3, target.getZ() + 0.5);
                last.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.BUCKET));
                last.swing(InteractionHand.OFF_HAND);
                level.playSound(null, target, SoundEvents.BUCKET_EMPTY, SoundSource.NEUTRAL, 1.0F, 1.0F);
                level.levelEvent(null, 1009, target, 0);
                level.sendParticles(ParticleTypes.SPLASH, target.getX() + 0.5, target.getY() + 0.6, target.getZ() + 0.5, 30, 0.8, 0.4, 0.8, 0.3);
                c.pours++;
                c.out += out;
                c.blaze.out += out;
                c.blaze.water = true;
                String name = last.displayNameCap();
                if (!c.blaze.names.contains(name)) c.blaze.names.add(name);
            } else if (level.getGameTime() - c.formed > REFORM) {
                reform(level, c);                                         // the fire has moved on from its end
                return;
            }
        }
        // Between: every other pair, alternately, hands on.
        for (int i = c.parity; i + 1 < n; i += 2) {
            VillageFolkEntity a = l[i], b = l[i + 1];
            if (a == null || b == null || !placed(a, c, i) || !placed(b, c, i + 1)) continue;
            if (Math.sqrt(a.distanceToSqr(b)) > HAND_TO_HAND) continue;
            ItemStack x = a.getItemBySlot(EquipmentSlot.OFFHAND), y = b.getItemBySlot(EquipmentSlot.OFFHAND);
            boolean xFull = x.is(Items.WATER_BUCKET), xEmpty = x.is(Items.BUCKET), yFull = y.is(Items.WATER_BUCKET), yEmpty = y.is(Items.BUCKET);
            boolean moved = false;
            if (xFull && y.isEmpty()) {
                a.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                b.setItemSlot(EquipmentSlot.OFFHAND, x);
                moved = true;
            } else if (xFull && yEmpty) {
                a.setItemSlot(EquipmentSlot.OFFHAND, y);
                b.setItemSlot(EquipmentSlot.OFFHAND, x);
                moved = true;
            } else if (x.isEmpty() && yEmpty) {
                b.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                a.setItemSlot(EquipmentSlot.OFFHAND, y);
                moved = true;
            } else if (xEmpty && yFull) {
                // A full one behind an empty one: nothing to do but wait (it goes the other way).
                moved = false;
            }
            if (moved) {
                a.swing(InteractionHand.OFF_HAND);
                b.swing(InteractionHand.OFF_HAND);
                a.getLookControl().setLookAt(b, 30.0F, 30.0F);
                b.getLookControl().setLookAt(a, 30.0F, 30.0F);
                level.playSound(null, b.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.3F, 0.8F + level.getRandom().nextFloat() * 0.3F);
                c.passes++;
            }
        }
        c.parity ^= 1;
    }

    /** The nearest flame to the fire's end within a throw of it, or null. */
    @Nullable
    private static BlockPos target(ServerLevel level, Chain c, VillageFolkEntity thrower) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (Long l : new ArrayList<>(c.blaze.burning)) {
            BlockPos p = BlockPos.of(l);
            if (!level.getBlockState(p).is(BlockTags.FIRE)) continue;
            double dx = thrower.getX() - (p.getX() + 0.5), dz = thrower.getZ() - (p.getZ() + 0.5);
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > FireBrigade.REACH + 1.0 || Math.abs(thrower.getY() - p.getY()) > FireBrigade.REACH_UP) continue;
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /** The fire has moved on from the chain's end: the line laid out again to the nearest flame, the same folk in it. */
    private static void reform(ServerLevel level, Chain c) {
        c.formed = level.getGameTime();
        BlockPos near = null;
        double bd = Double.MAX_VALUE;
        for (Long l : c.blaze.burning) {
            if (c.blaze.unreachable.contains(l)) continue;
            BlockPos p = BlockPos.of(l);
            double d = p.distSqr(c.water);
            if (d < bd) { bd = d; near = p; }
        }
        if (near == null) return;
        BlockPos water = water(level, near);
        if (water == null) return;
        List<BlockPos> spots = line(level, water, near);
        if (spots.size() < 2) return;
        int n = c.links.size();
        c.water = water;
        c.fire = near;
        c.spots.clear();
        for (int i = 0; i < n; i++) c.spots.add(spots.get((int) Math.round(i * (spots.size() - 1) / (double) Math.max(1, n - 1))));
        for (UUID u : c.links) WALKS.remove(u);
    }

    // ------------------------------------------------------------------ standing down

    /** The fire out (or the chain gone over to another): every bucket back where it came from, the links to their day. */
    static void standDown(ServerLevel level, Villages.Village v, FireBrigade.Blaze b) {
        Chain c = CHAINS.get(v.id());
        if (c == null || c.blaze != b) return;
        CHAINS.remove(v.id());
        int back = 0;
        for (UUID u : c.links) {
            LINKS.remove(u);
            WALKS.remove(u);
            if (!(level.getEntity(u) instanceof VillageFolkEntity f)) continue;
            // Every bucket it has over what it had of its own when it joined (one put away into its pack by
            // something else meanwhile is the chain's all the same): its hand's first.
            int over = f.countCarried(FireSafety::bucket) - c.had.getOrDefault(u, 0);
            ItemStack held = f.getItemBySlot(EquipmentSlot.OFFHAND);
            if (over > 0 && FireSafety.bucket(held)) {
                f.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                FireSafety.putBack(level, v, held.copy());
                back++;
                over--;
            }
            for (; over > 0; over--) {
                ItemStack s = f.removeMatching(x -> x.is(Items.BUCKET), 1) == 1 ? new ItemStack(Items.BUCKET)
                    : f.removeMatching(x -> x.is(Items.WATER_BUCKET), 1) == 1 ? new ItemStack(Items.WATER_BUCKET) : ItemStack.EMPTY;
                if (s.isEmpty()) break;
                FireSafety.putBack(level, v, s);
                back++;
            }
            f.brain("the fire is out: the chain stands down");
        }
        LAST.put(v.id(), new int[]{ c.links.size(), c.passes, c.fills, c.pours, c.out, c.buckets, c.made, back });
        if (c.pours > 0) {
            b.chain = "and a bucket chain of " + c.links.size() + " from " + c.from + " (" + c.pours + (c.pours == 1 ? " bucket" : " buckets")
                + " thrown, " + c.passes + " passes)";
        }
    }

    /** Its card's word. */
    @Nullable
    static String cardPart(VillageFolkEntity f) {
        Chain c = LINKS.get(f.getUUID());
        if (c == null) return null;
        int i = c.links.indexOf(f.getUUID());
        return i == 0 ? "filling buckets at the head of the bucket chain" : i == c.links.size() - 1 ? "throwing the bucket chain's water on the fire"
            : "passing buckets in the bucket chain (" + (i + 1) + " of " + c.links.size() + ")";
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the chain at the town's fire now: {links, passes, fills, pours, flames out, buckets}; null with none. */
    @Nullable
    public static int[] nowForTests(UUID village) {
        Chain c = CHAINS.get(village);
        return c == null ? null : new int[]{ c.links.size(), c.passes, c.fills, c.pours, c.out, c.buckets };
    }

    /** Tests: the last chain to stand down: {links, passes, fills, pours, out, buckets, made of iron, given back}; null with none. */
    @Nullable
    public static int[] lastForTests(UUID village) {
        return LAST.get(village);
    }

    /** Tests: the links' places, water end first. */
    public static List<BlockPos> spotsForTests(UUID village) {
        Chain c = CHAINS.get(village);
        return c == null ? List.of() : List.copyOf(c.spots);
    }
}
