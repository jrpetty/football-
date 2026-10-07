package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Turtle;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [diver] The turtle beach, the turtle helmets, the sea pickles and the quay: the diver's work that is not kelp.
 *
 * <ul>
 * <li><b>The turtles</b>, all by the game's own rules. The diver cuts seagrass off the bed with the stores' shears (it
 *     drops nothing cut any other way), and feeds it to the wild turtles about its beach two at a time; fed, they fall
 *     in love and breed, and it gives them its beach for their home, so the one with eggs comes ashore there to lay them
 *     in the sand. The diver fences each clutch round (a gate on the water side) and lights it, so no zombie tramples
 *     it and nothing spawns by it; when they have hatched it opens the gate for the hatchlings to go down to the
 *     water. It feeds the hatchlings seagrass too, which brings them on (a tenth of their growing a meal), and each
 *     drops a scute as it grows up. The scutes go to the stores.</li>
 * <li><b>The helmets.</b> Five scutes make a turtle helmet: the smith makes them ({@link #smith}), or the diver at its
 *     shed in a town with no smith. The divers wear them (water breathing as a player's gives, DiverSwim); spare ones
 *     go to the fleet's fishers at the stores; one is kept for the shop's counter, for players.</li>
 * <li><b>Sea pickles</b>, gathered off the bed where they grow, set in clusters of four on the bed by the quay's
 *     berths: they glow under the water, and the boats come in to a lit harbour.</li>
 * <li><b>The quay.</b> Once the boats are in, a fleet boat drifted off its berth is swum out to and towed back to it;
 *     anything lost over the side and floating by the quay is picked out of the water.</li>
 * </ul>
 */
final class TurtleBeach {

    private TurtleBeach() {}

    /** How far from the beach the diver looks for its turtles. */
    static final int TURTLE_REACH = 24;
    /** Between its walks along the beach. */
    static final long BEACH_EVERY = 1200L;

    private static final Map<UUID, Long> BEACH_AT = new ConcurrentHashMap<>();
    /** The hatchlings each town has seen on its beach (for the books, once each). */
    private static final Map<UUID, Set<UUID>> SEEN = new ConcurrentHashMap<>();
    /** The turtles each diver has fed this courting, so a pair is counted once. */
    private static final Map<UUID, Integer> FED = new ConcurrentHashMap<>();

    /** Tests: its next walk along the beach not held back by the last. */
    static void unpaceForTests(VillageFolkEntity f) {
        BEACH_AT.remove(f.getUUID());
    }

    static void resetForTests() {
        BEACH_AT.clear();
        SEEN.clear();
        FED.clear();
    }

    static final java.util.function.Predicate<ItemStack> SEAGRASS = s -> s.is(Items.SEAGRASS);

    // ------------------------------------------------------------------ the turtles

    static List<Turtle> turtles(ServerLevel level, BlockPos beach) {
        return level.getEntitiesOfClass(Turtle.class, new AABB(beach).inflate(TURTLE_REACH, 8, TURTLE_REACH), Turtle::isAlive);
    }

    /** Adults ready to breed: grown, not in love, rested since the last time (the game's own ready). */
    static List<Turtle> courting(List<Turtle> all) {
        List<Turtle> out = new ArrayList<>();
        for (Turtle t : all) if (!t.isBaby() && t.getAge() == 0 && !t.isInLove() && t.canFallInLove() && !t.hasEgg()) out.add(t);
        return out;
    }

    /** The turtle eggs on the beach. */
    static List<BlockPos> eggs(ServerLevel level, BlockPos beach) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(beach.offset(-8, -2, -8), beach.offset(8, 2, 8))) {
            if (level.getBlockState(p).is(Blocks.TURTLE_EGG)) out.add(p.immutable());
        }
        return out;
    }

    /** Does the diver want seagrass for the turtles (and so the stores' shears)? */
    static boolean wantsShears(ServerLevel level, Divers.Waterside w, VillageFolkEntity f) {
        if (w.beach == null || f.countCarried(s -> s.is(Items.SHEARS)) > 0) return false;
        return f.countCarried(SEAGRASS) < 4 && !turtles(level, w.beach).isEmpty();
    }

    /** Seagrass off the bed, with the shears, while the turtles want it and it has few. Eight to a dive. */
    @Nullable
    static Divers.Dive seagrassDive(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f, long now) {
        if (w.beach == null || f.countCarried(s -> s.is(Items.SHEARS)) == 0) return null;
        if (f.countCarried(SEAGRASS) >= Divers.SEAGRASS_KEPT) return null;
        if (KelpBeds.bare(f.ownerId(), "seagrass", now) || turtles(level, w.beach).isEmpty()) return null;
        List<BlockPos> spots = new ArrayList<>();
        int r = Divers.WORK_REACH;
        for (int dx = -r; dx <= r && spots.size() < 32; dx++) {
            for (int dz = -r; dz <= r && spots.size() < 32; dz++) {
                BlockPos top = KelpBeds.surfaceAt(level, w.middle.getX() + dx, w.middle.getZ() + dz);
                if (top == null) continue;
                BlockPos q = top;
                for (int k = 0; k <= Divers.DEEPEST && level.getFluidState(q).is(FluidTags.WATER); k++, q = q.below()) {
                    BlockState st = level.getBlockState(q);
                    if (st.is(Blocks.SEAGRASS) || st.is(Blocks.TALL_SEAGRASS) && st.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER) {
                        spots.add(q.immutable());
                        break;
                    }
                }
            }
        }
        KelpBeds.looked(f.ownerId(), "seagrass", !spots.isEmpty(), now);
        if (spots.isEmpty()) return null;
        spots.sort(Comparator.comparingDouble(p -> p.distSqr(w.bank)));
        return Divers.dive(Divers.Job.SEAGRASS, w, spots.subList(0, Math.min(8, spots.size())), now);
    }

    /** Cut a seagrass with the shears (it drops nothing cut any other way): the shears wear, as a player's. */
    static int cut(ServerLevel level, Divers.Waterside w, VillageFolkEntity f, BlockPos at) {
        BlockState st = level.getBlockState(at);
        if (!st.is(Blocks.SEAGRASS) && !st.is(Blocks.TALL_SEAGRASS)) return 0;
        ItemStack shears = ItemStack.EMPTY;
        for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && s.is(Items.SHEARS)) { shears = s; break; }
        if (shears.isEmpty()) return 0;
        int n = Divers.takeDrops(level, f, at, st, shears);
        KelpBeds.wear(f, shears);
        w.seagrass += n;
        return n;
    }

    /**
     * The beach's work, if there is any (paced: a walk along it every minute or so): turtles to feed, eggs to fence
     * and light, a gate to open for hatchlings, scutes to pick up. Null with none.
     */
    @Nullable
    static Divers.Dive beachWork(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f, long now) {
        if (w.beach == null || !level.isLoaded(w.beach)) return null;
        Long last = BEACH_AT.get(f.getUUID());
        if (last != null && now - last < BEACH_EVERY && now >= last) return null;
        BEACH_AT.put(f.getUUID(), now);
        if (beachTarget(level, v, w, f) == null) return null;
        return Divers.land(Divers.Job.BEACH, w, f, w.beach, now);
    }

    /** What on the beach wants the diver next: {kind, x, y, z} as a target, or null. */
    @Nullable
    static Object beachTarget(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f) {
        BlockPos beach = w.beach;
        if (beach == null) return null;
        countHatchlings(level, v, w);
        List<Turtle> all = turtles(level, beach);
        int grass = f.countCarried(SEAGRASS);
        // A scute on the sand.
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(beach).inflate(TURTLE_REACH, 6, TURTLE_REACH),
                e -> e.getItem().is(Items.TURTLE_SCUTE) && !e.isInWater())) return e;
        // A clutch unfenced.
        for (BlockPos egg : eggs(level, beach)) if (!fenced(level, egg)) return egg;
        // A ring with nothing left in it but hatchlings: its gate opened for them.
        BlockPos gate = shutGate(level, beach);
        if (gate != null) return gate;
        if (grass >= 1) {
            for (Turtle t : all) if (t.isBaby() && !t.isInWater()) return t;
            List<Turtle> ready = courting(all);
            // One of a pair fed and courting alone: the other fed with it, or the first's love goes for nothing.
            int alone = 0;
            for (Turtle t : all) if (!t.isBaby() && t.isInLove() && !t.hasEgg()) alone++;
            if (alone % 2 == 1 && !ready.isEmpty()) return ready.get(0);
            if (ready.size() >= 2 && grass >= 2) return ready.get(0);
            for (Turtle t : all) if (t.isBaby()) return t;
        }
        return null;
    }

    /**
     * On the beach: to the next thing that wants it and done, a step every half second; five things a walk at the most.
     * False when there is nothing more.
     */
    static boolean atBeach(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f, Divers.Dive d) {
        if (d.worked++ % 10 != 0) return true;
        if (d.step >= 5 || level.getGameTime() - d.stageSince > 2400L) return false;
        Object t = beachTarget(level, v, w, f);
        if (t == null) return false;
        Vec3 at = t instanceof net.minecraft.world.entity.Entity e ? e.position() : Vec3.atBottomCenterOf((BlockPos) t);
        if (f.position().distanceToSqr(at) > 2.6 * 2.6) {
            if (f.getNavigation().isDone() || d.worked % 40 == 1) f.getNavigation().moveTo(at.x, at.y, at.z, 1.0D);
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(at.x, at.y + 0.3, at.z);
        f.swing(InteractionHand.MAIN_HAND);
        d.step++;
        if (t instanceof ItemEntity item) {
            ItemStack lot = item.getItem().copy();
            ItemStack left = f.insertItem(lot.copy());
            int in = lot.getCount() - left.getCount();
            if (in > 0) {
                f.take(item, in);
                if (left.isEmpty()) item.discard();
                else item.setItem(left);
                Economy.gathered(f, lot, in);
                w.scutes += in;
                Divers.today(level, v.id(), "scutes", in);
                d.said = "picked up " + in + " turtle scute" + (in == 1 ? "" : "s") + " on the beach";
            }
        } else if (t instanceof Turtle turtle) {
            feed(level, w, f, turtle, d);
        } else if (t instanceof BlockPos p) {
            if (level.getBlockState(p).is(Blocks.TURTLE_EGG)) fence(level, v, w, f, p);
            else if (level.getBlockState(p).getBlock() instanceof FenceGateBlock) {
                level.setBlock(p, level.getBlockState(p).setValue(FenceGateBlock.OPEN, true), 3);
                level.playSound(null, p, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 1.0F, 1.0F);
                d.said = "opened the nest's gate for the hatchlings";
            }
        }
        return true;
    }

    /**
     * A turtle fed a seagrass, as a player feeds one: a grown one falls in love (and the beach is made its home, for
     * the eggs); a hatchling grows on by a tenth of what it has left to grow.
     */
    static void feed(ServerLevel level, Divers.Waterside w, VillageFolkEntity f, Turtle t, Divers.Dive d) {
        if (f.removeMatching(SEAGRASS, 1) != 1) return;
        if (t.isBaby()) {
            t.ageUp(AgeableMob.getSpeedUpSecondsWhenFeeding(-t.getAge()), true);
            d.said = "fed the hatchlings seagrass";
        } else {
            t.setInLove(null);
            if (w.beach != null) t.setHomePos(w.beach);
            int fed = FED.merge(f.getUUID(), 1, Integer::sum);
            if (fed % 2 == 0) {
                w.bred++;
                f.note(AssistantEntity.Deed.ANIMALS_BRED, 1);
                d.said = "fed a pair of turtles seagrass, and they are courting";
            }
        }
        level.playSound(null, t.blockPosition(), SoundEvents.TURTLE_AMBIENT_LAND, SoundSource.NEUTRAL, 0.8F, 1.0F);
    }

    /** Is this egg inside a fence ring (every side of it closed off within three blocks: a fence, a wall, or the water,
     *  which is the hatchlings' own way out and no zombie's way in)? */
    static boolean fenced(ServerLevel level, BlockPos egg) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            boolean closed = false;
            for (int k = 1; k <= 3 && !closed; k++) {
                BlockState st = level.getBlockState(egg.relative(d, k));
                if (st.is(BlockTags.FENCES) || st.getBlock() instanceof FenceGateBlock || st.isSolid()
                    || !st.getFluidState().isEmpty()) closed = true;
            }
            if (!closed) return false;
        }
        return true;
    }

    /**
     * A ring of fence round the clutch (and the eggs beside it), a gate on the water side, and a light on a post: out
     * of the stores (a fence or two planks a post, a gate or four planks, a lantern or a torch). Nothing spawns in the
     * light, and no zombie gets in at the eggs.
     */
    static void fence(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f, BlockPos egg) {
        int x0 = egg.getX(), x1 = egg.getX(), z0 = egg.getZ(), z1 = egg.getZ();
        for (BlockPos e : eggs(level, egg)) {
            if (Math.abs(e.getX() - egg.getX()) > 3 || Math.abs(e.getZ() - egg.getZ()) > 3 || e.getY() != egg.getY()) continue;
            x0 = Math.min(x0, e.getX()); x1 = Math.max(x1, e.getX());
            z0 = Math.min(z0, e.getZ()); z1 = Math.max(z1, e.getZ());
        }
        x0--; x1++; z0--; z1++;
        int y = egg.getY();
        // The gate: the ring's cell nearest the water.
        BlockPos gateAt = null;
        double best = Double.MAX_VALUE;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (x != x0 && x != x1 && z != z0 && z != z1) continue;
                boolean corner = (x == x0 || x == x1) && (z == z0 || z == z1);
                if (corner) continue;
                double d = new BlockPos(x, y, z).distSqr(w.middle);
                if (d < best) { best = d; gateAt = new BlockPos(x, y, z); }
            }
        }
        int set = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (x != x0 && x != x1 && z != z0 && z != z1) continue;
                BlockPos p = new BlockPos(x, y, z);
                BlockState here = level.getBlockState(p);
                if (!here.canBeReplaced() || !here.getFluidState().isEmpty()) continue;
                if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;
                if (p.equals(gateAt)) {
                    if (!Crafts.wooden(level, v, s -> s.is(net.minecraft.tags.ItemTags.FENCE_GATES), 4)) continue;
                    Direction along = (x == x0 || x == x1) ? Direction.NORTH : Direction.EAST;
                    level.setBlock(p, Blocks.SPRUCE_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, along)
                        .setValue(FenceGateBlock.OPEN, false), 3);
                } else {
                    if (!Crafts.fence(level, v)) continue;
                    level.setBlock(p, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
                }
                set++;
            }
        }
        // A light on the corner post on the land side.
        BlockPos post = new BlockPos(x0, y, z0);
        double far = -1;
        for (BlockPos c : new BlockPos[]{ new BlockPos(x0, y, z0), new BlockPos(x0, y, z1), new BlockPos(x1, y, z0), new BlockPos(x1, y, z1) }) {
            double d = c.distSqr(w.middle);
            if (d > far && level.getBlockState(c).is(BlockTags.FENCES)) { far = d; post = c; }
        }
        if (level.getBlockState(post).is(BlockTags.FENCES) && level.getBlockState(post.above()).isAir()) {
            Block light = Masonry.light(level, v);
            if (light != null) level.setBlock(post.above(), light.defaultBlockState(), 3);
        }
        if (set > 0) {
            level.playSound(null, egg, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
            f.note(AssistantEntity.Deed.BLOCKS_BUILT, set);
            Villages.tell(v.id(), level.getDayTime() / 24000L, f.displayNameCap() + " fenced and lit a clutch of turtle eggs on the beach");
        }
    }

    /** A shut gate of a nest ring with no eggs left in it (they have hatched): to be opened. */
    @Nullable
    static BlockPos shutGate(ServerLevel level, BlockPos beach) {
        for (BlockPos p : BlockPos.betweenClosed(beach.offset(-9, -2, -9), beach.offset(9, 2, 9))) {
            BlockState st = level.getBlockState(p);
            if (!(st.getBlock() instanceof FenceGateBlock) || st.getValue(FenceGateBlock.OPEN)) continue;
            boolean eggs = false;
            for (BlockPos q : BlockPos.betweenClosed(p.offset(-4, 0, -4), p.offset(4, 0, 4))) {
                if (level.getBlockState(q).is(Blocks.TURTLE_EGG)) { eggs = true; break; }
            }
            if (!eggs && !level.getEntitiesOfClass(Turtle.class, new AABB(p).inflate(4, 2, 4), Turtle::isBaby).isEmpty()) return p.immutable();
        }
        return null;
    }

    /** Hatchlings about the beach the town has not counted yet: the books' hatched. */
    static void countHatchlings(ServerLevel level, Villages.Village v, Divers.Waterside w) {
        if (w.beach == null) return;
        Set<UUID> seen = SEEN.computeIfAbsent(v.id(), k -> ConcurrentHashMap.newKeySet());
        int n = 0;
        for (Turtle t : turtles(level, w.beach)) if (t.isBaby() && seen.add(t.getUUID())) n++;
        if (n > 0) {
            w.hatched += n;
            Divers.today(level, v.id(), "hatched", n);
            Villages.tell(v.id(), level.getDayTime() / 24000L, n + (n == 1 ? " turtle hatched" : " turtles hatched") + " on the town's beach");
        }
    }

    // ------------------------------------------------------------------ the helmets

    /** Wear the turtle helmet it carries, the thing on its head (if any) into its pack. */
    static void wear(VillageFolkEntity f) {
        var inv = f.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !s.is(Items.TURTLE_HELMET)) continue;
            ItemStack old = f.getItemBySlot(EquipmentSlot.HEAD);
            f.setItemSlot(EquipmentSlot.HEAD, s.copyWithCount(1));
            s.shrink(1);
            if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
            if (!old.isEmpty()) {
                ItemStack left = f.insertItem(old);
                if (!left.isEmpty()) Block.popResource(f.level(), f.blockPosition(), left);
            }
            f.brain("put on its turtle helmet");
            return;
        }
    }

    /** Helmets the town wants made: a diver's that has none, a fleet fisher's that has none, and one for the shop. */
    static int helmetsWanted(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        int want = 0;
        for (VillageFolkEntity d : Divers.divers(id)) if (!d.getItemBySlot(EquipmentSlot.HEAD).is(Items.TURTLE_HELMET)) want++;
        if (Fleet.has(id)) {
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity f && f.stationTask() == StationTask.FISH && f.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) want++;
            }
        }
        if (Villages.hasBuilt(id, "shop") || Villages.hasBuilt(id, Store.STRUCTURE)) want++;   // one on the counter, for players
        return want - Market.stock(level, id, s -> s.is(Items.TURTLE_HELMET));
    }

    /**
     * The scutes kept back from any maker (Bench) for the helmets the divers and the fleet's fishers still want: five a
     * helmet, less the helmets the stores already hold. (The shop's one is the shop's bench's to make, of what is left.)
     */
    static int scutesKept(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Divers.divers(id).isEmpty()) return 0;
        int want = 0;
        for (VillageFolkEntity d : Divers.divers(id)) if (!d.getItemBySlot(EquipmentSlot.HEAD).is(Items.TURTLE_HELMET)) want++;
        if (Fleet.has(id)) {
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity f && f.stationTask() == StationTask.FISH && f.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) want++;
            }
        }
        want -= Market.stock(level, id, s -> s.is(Items.TURTLE_HELMET));
        return Math.max(0, want) * 5;
    }

    /** The smith's turtle helmet (Crafts.smith): five scutes out of the stores, by the game's recipe, as its hand makes it. */
    @Nullable
    static String smith(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (helmetsWanted(level, v) <= 0) return null;
        if (Market.stock(level, v.id(), s -> s.is(Items.TURTLE_SCUTE)) < 5) return null;
        if (RecipeBook.waysFor(level, Items.TURTLE_HELMET).isEmpty()) return null;
        if (!Crafts.take(level, v, s -> s.is(Items.TURTLE_SCUTE), 5)) return null;
        ItemStack helmet = Craftsmanship.finish(level, new ItemStack(Items.TURTLE_HELMET), f.veteranLevel(), f.displayNameCap());
        Crafts.store(level, v, helmet);
        Divers.Waterside w = Divers.water(v.id());
        if (w != null) {
            w.helmets++;
            Divers.save(v.id(), w);
        }
        return "a turtle helmet, of five scutes";
    }

    /** The diver's own, at its shed, in a town with no smith: five scutes it carries, at the bench, worn at once. */
    static void ownHelmet(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f, @Nullable BlockPos bench) {
        if (f.getItemBySlot(EquipmentSlot.HEAD).is(Items.TURTLE_HELMET) || f.countCarried(s -> s.is(Items.TURTLE_SCUTE)) < 5) return;
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a.stationTask() == StationTask.SMITH) return;   // the smith's work
        boolean table = bench != null && level.getBlockState(bench).is(Blocks.CRAFTING_TABLE) || f.countCarried(s -> s.is(Items.CRAFTING_TABLE)) > 0;
        if (!KelpBeds.craft(level, f, Items.TURTLE_HELMET, table)) return;
        w.helmets++;
        wear(f);
        Villages.tell(v.id(), level.getDayTime() / 24000L, f.displayNameCap() + " made a turtle helmet of the beach's scutes, and wears it to dive");
    }

    /** Spare helmets in the stores to the fleet's fishers, at the stores, once the divers have theirs (and one is kept
     *  for the shop). */
    static void issueHelmets(ServerLevel level, Villages.Village v, Divers.Waterside w) {
        UUID id = v.id();
        if (!Fleet.has(id)) return;
        int spare = Market.stock(level, id, s -> s.is(Items.TURTLE_HELMET));
        for (VillageFolkEntity d : Divers.divers(id)) if (!d.getItemBySlot(EquipmentSlot.HEAD).is(Items.TURTLE_HELMET)) spare--;
        if (Villages.hasBuilt(id, "shop") || Villages.hasBuilt(id, Store.STRUCTURE)) spare--;
        if (spare <= 0) return;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (spare <= 0) break;
            if (!(a instanceof VillageFolkEntity f) || f.stationTask() != StationTask.FISH || !f.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) continue;
            BlockPos stores = f.storesSpot(level, id);
            if (stores == null || f.blockPosition().distSqr(stores) > 10 * 10) continue;          // issued at the stores
            if (!Crafts.take(level, v, s -> s.is(Items.TURTLE_HELMET), 1)) return;
            f.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.TURTLE_HELMET));
            spare--;
            FolkTalk.speak(f, "A turtle helmet! Now if I go over the side, I'll come up again.");
        }
    }

    // ------------------------------------------------------------------ sea pickles

    /** Sea pickles growing on the bed within reach. */
    static List<BlockPos> wildPickles(ServerLevel level, Divers.Waterside w, int n) {
        List<BlockPos> out = new ArrayList<>();
        int r = Divers.WORK_REACH;
        for (int dx = -r; dx <= r && out.size() < n; dx++) {
            for (int dz = -r; dz <= r && out.size() < n; dz++) {
                BlockPos top = KelpBeds.surfaceAt(level, w.middle.getX() + dx, w.middle.getZ() + dz);
                if (top == null) continue;
                BlockPos q = top;
                for (int k = 0; k <= Divers.DEEPEST && level.getFluidState(q).is(FluidTags.WATER); k++, q = q.below()) {
                    if (level.getBlockState(q).is(Blocks.SEA_PICKLE) && !nearQuayLight(level, w, q)) {
                        out.add(q.immutable());
                        break;
                    }
                }
            }
        }
        return out;
    }

    static boolean hasPickles(ServerLevel level, Divers.Waterside w) {
        return !wildPickles(level, w, 1).isEmpty();
    }

    /** A cluster it set by the quay itself (not to be gathered again). */
    private static boolean nearQuayLight(ServerLevel level, Divers.Waterside w, BlockPos p) {
        return w.lights > 0 && level.getBlockState(p).is(Blocks.SEA_PICKLE)
            && level.getBlockState(p).getValue(net.minecraft.world.level.block.SeaPickleBlock.PICKLES) == 4 && p.distSqr(w.bank) < 4;
    }

    /** Gather sea pickles: for the quay's lights, or a player's order. */
    @Nullable
    static Divers.Dive pickleDive(ServerLevel level, Divers.Waterside w, VillageFolkEntity f, Divers.Job job, long now) {
        if (KelpBeds.bare(f.ownerId(), "pickles", now)) return null;
        List<BlockPos> spots = wildPickles(level, w, 4);
        KelpBeds.looked(f.ownerId(), "pickles", !spots.isEmpty(), now);
        if (spots.isEmpty()) return null;
        return Divers.dive(job == Divers.Job.ORDER ? Divers.Job.ORDER : Divers.Job.PICKLES, w, spots, now);
    }

    static int pick(ServerLevel level, Divers.Waterside w, VillageFolkEntity f, BlockPos at) {
        BlockState st = level.getBlockState(at);
        if (!st.is(Blocks.SEA_PICKLE)) return 0;
        int n = Divers.takeDrops(level, f, at, st, ItemStack.EMPTY);
        w.pickles += n;
        return n;
    }

    /**
     * The quay's lights under the water: a cluster of four sea pickles on the bed at each of the fleet's berths that has
     * none near it, while the diver has pickles to set (and gathering them first, if there are wild ones and it has none).
     */
    @Nullable
    static Divers.Dive lightsDive(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f, long now) {
        Fleet.Town t = Fleet.town(v.id());
        Waterfront.Dock q = t.quay;
        if (q == null || !level.isLoaded(q.end())) return null;
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos berth : Fleet.berths(level, q)) {
            BlockPos floor = berth;
            while (floor.getY() > berth.getY() - Divers.DEEPEST && level.getFluidState(floor.below()).is(FluidTags.WATER)) floor = floor.below();
            if (berth.getY() - floor.getY() < 1) continue;                            // too shallow: a boat would sit on it
            if (!level.getBlockState(floor).is(Blocks.WATER) || !level.getBlockState(floor.below()).isFaceSturdy(level, floor.below(), Direction.UP)) continue;
            boolean lit = false;
            for (BlockPos p : BlockPos.betweenClosed(floor.offset(-3, -1, -3), floor.offset(3, 1, 3))) {
                if (level.getBlockState(p).is(Blocks.SEA_PICKLE)) { lit = true; break; }
            }
            if (!lit) spots.add(floor.immutable());
        }
        if (spots.isEmpty()) return null;
        if (f.countCarried(s -> s.is(Items.SEA_PICKLE)) == 0) return pickleDive(level, w, f, Divers.Job.PICKLES, now);
        return Divers.dive(Divers.Job.LIGHTS, w, spots.subList(0, Math.min(spots.size(), Math.max(1, f.countCarried(s -> s.is(Items.SEA_PICKLE)) / 4))), now);
    }

    /** Set a cluster of sea pickles glowing on the bed here, four (or what it has) out of its pack. */
    static boolean light(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f, BlockPos at) {
        if (!level.getBlockState(at).is(Blocks.WATER)) return false;
        int n = Math.min(4, f.countCarried(s -> s.is(Items.SEA_PICKLE)));
        if (n <= 0 || !Divers.pickles(n).canSurvive(level, at)) return false;
        f.removeMatching(s -> s.is(Items.SEA_PICKLE), n);
        level.setBlock(at, Divers.pickles(n), 3);
        level.playSound(null, at, SoundEvents.SLIME_BLOCK_PLACE, SoundSource.BLOCKS, 0.7F, 1.2F);
        w.lights++;
        return true;
    }

    // ------------------------------------------------------------------ the quay

    /** A fleet boat drifted off its berth while the boats are in (the afternoon on, nobody out in them), or null. */
    @Nullable
    static Boat adrift(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (!Fleet.has(id)) return null;
        Fleet.Town t = Fleet.town(id);
        Waterfront.Dock q = t.quay;
        if (q == null || !t.crew.isEmpty() || !level.isLoaded(q.end())) return null;
        long tod = level.getDayTime() % 24000L;
        if (tod < Fleet.HOME_AT || tod > 12000L) return null;
        List<BlockPos> berths = Fleet.berths(level, q);
        if (berths.isEmpty()) return null;
        for (Boat b : Fleet.boats(level, id, q)) {
            if (!b.getPassengers().isEmpty() || !b.isInWater()) continue;
            double near = Double.MAX_VALUE;
            for (BlockPos berth : berths) near = Math.min(near, b.distanceToSqr(berth.getX() + 0.5, b.getY(), berth.getZ() + 0.5));
            if (near > 4.0 * 4.0 && b.distanceToSqr(q.end().getX() + 0.5, b.getY(), q.end().getZ() + 0.5) < 36.0 * 36.0) return b;
        }
        return null;
    }

    /** A berth with no boat at it (else the first). */
    @Nullable
    static BlockPos freeBerth(ServerLevel level, Villages.Village v) {
        Fleet.Town t = Fleet.town(v.id());
        if (t.quay == null) return null;
        List<BlockPos> berths = Fleet.berths(level, t.quay);
        List<Boat> boats = Fleet.boats(level, v.id(), t.quay);
        for (BlockPos berth : berths) {
            boolean taken = false;
            for (Boat b : boats) if (b.distanceToSqr(berth.getX() + 0.5, b.getY(), berth.getZ() + 0.5) < 2.0) { taken = true; break; }
            if (!taken) return berth;
        }
        return berths.isEmpty() ? null : berths.get(0);
    }

    /** Something floating in the water near the diving water or the quay (lost over the side, a scute), or null. */
    @Nullable
    static BlockPos flotsamNear(ServerLevel level, Divers.Waterside w, VillageFolkEntity f) {
        AABB box = new AABB(w.middle).inflate(Divers.WORK_REACH, 6, Divers.WORK_REACH);
        Fleet.Town t = f.ownerId() == null ? null : Fleet.town(f.ownerId());
        if (t != null && t.quay != null) box = box.minmax(new AABB(t.quay.end()).inflate(10, 4, 10));
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive() && e.isInWater() && e.getAge() > 100)) {
            return e.blockPosition();
        }
        return null;
    }

    // ------------------------------------------------------------------ the stage

    /** The smoke stage: two turtles on the beach, if it has none. */
    static void stageBeach(ServerLevel level, Divers.Waterside w) {
        if (w.beach == null || !turtles(level, w.beach).isEmpty()) return;
        for (int i = 0; i < 2; i++) {
            Turtle t = EntityType.TURTLE.create(level);
            if (t == null) continue;
            t.moveTo(w.beach.getX() + 0.5 + i, w.beach.getY(), w.beach.getZ() + 0.5, i * 90.0F, 0.0F);
            t.setHomePos(w.beach);
            level.addFreshEntity(t);
        }
    }
}
