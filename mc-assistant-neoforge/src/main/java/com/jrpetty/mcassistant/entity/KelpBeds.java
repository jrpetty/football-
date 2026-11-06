package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [diver] The kelp beds, and what is made of them: the diver's work with the kelp (Divers).
 *
 * <ul>
 * <li><b>Planting.</b> Kelp is planted on the bed of the diving water where it is three deep or more (and no more than
 *     a diver's dive), one plant to a column on every other square, nearest the middle of the water first: a bed of
 *     eight to start, up to twenty-four as the town grows and its fires want more ({@link #bedWanted}: eight more while
 *     its coal is low). Kelp out of the stores, or cut off the wild kelp round about.</li>
 * <li><b>Harvesting.</b> A plant grown to the surface (or five tall) is cut from the top down to the piece above the
 *     lowest, and the lowest left: the game makes it the growing tip again, and it grows on by its own rule. The wild
 *     kelp round about is cut the same way, never pulled up.</li>
 * <li><b>Drying.</b> In the shed's smoker (fuelled with a dried kelp block once there is one; wood till then, coal
 *     never while there is anything else) and over its campfire (four at a time, for nothing), by the game's own
 *     recipes and times; collected when done.</li>
 * <li><b>Packing.</b> Nine dried kelp to a dried kelp block, at the shed's bench, by the game's own recipe. While the
 *     town is hungry, thirty-two are left loose for the larder: dried kelp is food.</li>
 * <li><b>The stores.</b> Everything brought up goes into them, booked as the diver's (Economy); it draws its kit out of
 *     them there: a turtle helmet, shears for the seagrass, kelp to plant, a kelp block to fuel its smoker.</li>
 * </ul>
 * Without a shed (no dry place for one on the bank) it sets a campfire down on the bank to dry the kelp over.
 */
final class KelpBeds {

    private KelpBeds() {}

    static final Predicate<ItemStack> KELP = s -> s.is(Items.KELP);
    static final Predicate<ItemStack> DRIED = s -> s.is(Items.DRIED_KELP);
    /** Fuel for the smoker, best first: the diver's own kelp blocks; then wood; coal last of all. */
    static final List<Predicate<ItemStack>> SMOKER_FUEL = List.of(FuelBook.KELP_BLOCK, s -> s.is(Items.CHARCOAL),
        s -> s.is(ItemTags.LOGS), s -> s.is(ItemTags.PLANKS), s -> s.is(Items.COAL));
    /** A pack this full goes home. */
    static final int FULL_SLOTS = 4;
    /** Between its trips to the stores and the shed, unless its pack is full. */
    static final long STORES_EVERY = 2400L, SHED_EVERY = 900L;

    /** The shed's fixtures by its anchor: {smoker, campfire, bench, barrel, inside}. */
    private static final Map<BlockPos, BlockPos[]> PARTS = new ConcurrentHashMap<>();
    /** A town with no shed: the drying fire its diver set down on the bank. */
    private static final Map<UUID, BlockPos> FIRE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> STORES_AT = new ConcurrentHashMap<>(), SHED_AT = new ConcurrentHashMap<>(),
        HARVEST_AT = new ConcurrentHashMap<>();
    /** Whether each town could have a drying fire just now (canSetFire): {when looked, 0/1}. */
    private static final Map<UUID, long[]> FIRE_OK = new ConcurrentHashMap<>();
    /** Looks along the bed that found nothing (no clay, no seagrass...), by town and thing, and when: not looked for
     *  again for a couple of minutes. A town short of clay by a lake with none in it would otherwise have its diver
     *  go over the whole bed every time it wondered what to do next. */
    private static final Map<String, Long> BARE = new ConcurrentHashMap<>();
    static final long BARE_FOR = 2400L;

    /** Tests: its next harvest not held back for more of the bed to ripen. */
    static void unpaceForTests(VillageFolkEntity f) {
        HARVEST_AT.remove(f.getUUID());
    }

    static void resetForTests() {
        PARTS.clear();
        FIRE.clear();
        STORES_AT.clear();
        SHED_AT.clear();
        HARVEST_AT.clear();
        FIRE_OK.clear();
        BARE.clear();
    }

    /** Did the last look along the bed for this find nothing, a little while ago? */
    static boolean bare(@Nullable UUID village, String what, long now) {
        if (village == null) return false;
        Long at = BARE.get(village + "/" + what);
        return at != null && now - at < BARE_FOR && now >= at;
    }

    /** What a look along the bed found: nothing (not looked for again for a while), or something. */
    static void looked(@Nullable UUID village, String what, boolean any, long now) {
        if (village == null) return;
        if (any) BARE.remove(village + "/" + what);
        else BARE.put(village + "/" + what, now);
    }

    // ------------------------------------------------------------------ the plants

    static boolean isKelp(BlockState s) {
        return s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT);
    }

    /** How tall the kelp is from this root up. */
    static int height(ServerLevel level, BlockPos root) {
        int n = 0;
        BlockPos q = root;
        while (n < 40 && isKelp(level.getBlockState(q))) {
            n++;
            q = q.above();
        }
        return n;
    }

    /** Water from here up to the surface, this block counted (kelp stands in water, so it counts too). */
    static int waterUp(ServerLevel level, BlockPos p) {
        int n = 0;
        BlockPos q = p;
        while (n < 40 && level.getFluidState(q).is(FluidTags.WATER)) {
            n++;
            q = q.above();
        }
        return n;
    }

    /** Grown enough to cut: to the surface, or five tall. */
    static boolean ripe(ServerLevel level, BlockPos root) {
        int h = height(level, root);
        return h >= 2 && h >= Math.min(5, waterUp(level, root));
    }

    /** The kelp bed the town's fires want: eight to start, a plant more for every four folk past fifteen, eight more
     *  while its coal is low or its age wants coal; twenty-four at the most. */
    static int bedWanted(ServerLevel level, UUID village) {
        int n = Divers.BED_LEAST + Math.max(0, Villages.headcount(village) - Divers.FROM) / 4;
        if (Fuel.low(level, village)) n += 8;
        else if (Fuel.ageWants(level, village, Villages.Task.COAL)) n += 4;
        return Math.max(Divers.BED_LEAST, Math.min(Divers.BED_MOST, n));
    }

    /** The bed's plants still standing (a plant eaten by something, or dug out, comes off the books). */
    static int live(ServerLevel level, Divers.Waterside w) {
        w.bed.removeIf(r -> level.isLoaded(r) && !isKelp(level.getBlockState(r)));
        return w.bed.size();
    }

    /** The wild kelp round the diving water: the lowest piece of each plant, the nearest first, forty-eight at the most. */
    static void findWild(ServerLevel level, Divers.Waterside w) {
        w.wild.clear();
        int r = Divers.WORK_REACH;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = w.middle.getX() + dx, z = w.middle.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                BlockPos top = surfaceAt(level, x, z);
                if (top == null) continue;
                BlockPos q = top;
                BlockPos root = null;
                for (int k = 0; k <= Divers.DEEPEST + 4 && level.getFluidState(q).is(FluidTags.WATER); k++) {
                    if (isKelp(level.getBlockState(q))) root = q;
                    q = q.below();
                }
                if (root != null && !w.bed.contains(root) && top.getY() - root.getY() <= Divers.DEEPEST) w.wild.add(root.immutable());
            }
        }
        w.wild.sort(Comparator.comparingDouble(p -> p.distSqr(w.middle)));
        while (w.wild.size() > 48) w.wild.remove(w.wild.size() - 1);
    }

    /** The top water block of this column, with air (or anything but water) over it; null where it is not water. */
    @Nullable
    static BlockPos surfaceAt(ServerLevel level, int x, int z) {
        Roads.Ground g = Roads.ground(level, x, z);
        if (g == null || !g.water()) return null;
        return new BlockPos(x, g.y(), z);
    }

    /** Can kelp be planted at this root: still water there, the bed under it firm, three deep and within a dive? */
    static boolean plantable(ServerLevel level, BlockPos root, int surface) {
        BlockState here = level.getBlockState(root);
        if (!here.is(Blocks.WATER) || !level.getFluidState(root).isSource()) return false;
        if (!level.getBlockState(root.below()).isFaceSturdy(level, root.below(), Direction.UP)) return false;
        int depth = surface - root.getY() + 1;
        return depth >= Divers.DEEP && surface - root.getY() <= Divers.DEEPEST && Blocks.KELP.defaultBlockState().canSurvive(level, root);
    }

    /** Free places for the bed, nearest the middle of the water first, on every other square. */
    static List<BlockPos> plantSpots(ServerLevel level, Divers.Waterside w, int n) {
        List<BlockPos> out = new ArrayList<>();
        for (int r = 0; r <= 10 && out.size() < n; r++) {
            for (int dx = -r; dx <= r && out.size() < n; dx++) {
                for (int dz = -r; dz <= r && out.size() < n; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = w.middle.getX() + dx, z = w.middle.getZ() + dz;
                    if (((x + z) & 1) != 0) continue;                              // rows, a column apart
                    if (!level.hasChunk(x >> 4, z >> 4)) continue;
                    BlockPos top = surfaceAt(level, x, z);
                    if (top == null) continue;
                    BlockPos q = top;
                    while (q.getY() > top.getY() - Divers.DEEPEST - 1 && level.getFluidState(q.below()).is(FluidTags.WATER)) q = q.below();
                    if (w.bed.contains(q) || w.wild.contains(q)) continue;
                    if (Math.abs(x - w.bank.getX()) <= 1 && Math.abs(z - w.bank.getZ()) <= 1) continue;
                    if (plantable(level, q, top.getY())) out.add(q.immutable());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the dives

    /** Cut what has grown: the bed's ripe plants, then the wild kelp's; when the bed wants planting and there is no
     *  kelp to plant, any wild plant tall enough to spare a piece. Eight to a dive, nearest the bank first. */
    @Nullable
    static Divers.Dive harvestDive(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f, long now) {
        live(level, w);
        boolean wantKelp = w.bed.size() < bedWanted(level, v.id()) && f.countCarried(KELP) == 0
            && Market.stock(level, v.id(), KELP) == 0;
        List<BlockPos> ripe = new ArrayList<>();
        for (BlockPos r : w.bed) if (level.isLoaded(r) && ripe(level, r)) ripe.add(r);
        for (BlockPos r : w.wild) {
            if (!level.isLoaded(r) || !isKelp(level.getBlockState(r))) continue;
            if (ripe(level, r) || wantKelp && height(level, r) >= 2) ripe.add(r);
        }
        if (ripe.isEmpty()) return null;
        // A few at a time is a dive worth the walk; one ripe plant waits for the others, unless it has been a while.
        Long last = HARVEST_AT.get(f.getUUID());
        if (ripe.size() < 3 && !wantKelp && last != null && now - last < 4800L && now >= last) return null;
        HARVEST_AT.put(f.getUUID(), now);
        ripe.sort(Comparator.comparingDouble(p -> p.distSqr(w.bank)));
        return Divers.dive(Divers.Job.HARVEST, w, ripe.subList(0, Math.min(8, ripe.size())), now);
    }

    /** Plant the bed up to what the fires want, with the kelp it carries (drawn out of the stores, or cut wild). */
    @Nullable
    static Divers.Dive plantDive(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f, long now) {
        int have = live(level, w);
        int want = bedWanted(level, v.id()) - have;
        int kelp = f.countCarried(KELP);
        if (want <= 0 || kelp <= 0) return null;
        List<BlockPos> spots = plantSpots(level, w, Math.min(8, Math.min(want, kelp)));
        if (spots.isEmpty()) return null;
        return Divers.dive(Divers.Job.PLANT, w, spots, now);
    }

    /** Dig this off the bed (clay, sand or gravel): blocks under the water only, never the bank, the beach or under
     *  the bed's kelp; within a dive; six to a trip, nearest the bank first. Null if there is none. */
    @Nullable
    static Divers.Dive digDive(ServerLevel level, Divers.Waterside w, VillageFolkEntity f, Block what, Divers.Job job, long now) {
        String key = what.getDescriptionId();
        if (bare(f.ownerId(), key, now)) return null;
        List<BlockPos> spots = bedBlocks(level, w, what, 6);
        looked(f.ownerId(), key, !spots.isEmpty(), now);
        if (spots.isEmpty()) return null;
        return Divers.dive(job, w, spots, now);
    }

    /** Is there any of this on the bed within the diver's reach? */
    static boolean hasOnBed(ServerLevel level, Divers.Waterside w, Block what) {
        return !bedBlocks(level, w, what, 1).isEmpty();
    }

    /** Blocks of this on the bed of the water (the top of the bed, with water over it), the nearest the bank first. */
    static List<BlockPos> bedBlocks(ServerLevel level, Divers.Waterside w, Block what, int n) {
        List<BlockPos> out = new ArrayList<>();
        int r = Divers.WORK_REACH;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = w.middle.getX() + dx, z = w.middle.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                BlockPos top = surfaceAt(level, x, z);
                if (top == null) continue;
                BlockPos q = top;
                while (q.getY() > top.getY() - Divers.DEEPEST && level.getFluidState(q.below()).is(FluidTags.WATER)) q = q.below();
                BlockPos floor = q.below();
                if (top.getY() - floor.getY() < 2) continue;                    // the shallows by the bank: left be
                if (!level.getBlockState(floor).is(what)) continue;
                if (w.bed.contains(q) || w.wild.contains(q) || isKelp(level.getBlockState(q))) continue;
                if (floor.distSqr(w.bank) < 9) continue;
                out.add(floor.immutable());
            }
        }
        out.sort(Comparator.comparingDouble(p -> p.distSqr(w.bank)));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    // ------------------------------------------------------------------ the work under the water

    /** Cut a plant from the top down to the piece above its root, the root left to grow again. Returns the kelp. */
    static int harvest(ServerLevel level, Divers.Waterside w, VillageFolkEntity f, BlockPos root) {
        int h = height(level, root);
        if (h < 2) return 0;
        int n = 0;
        for (int k = h - 1; k >= 1; k--) {
            BlockPos q = root.above(k);
            BlockState st = level.getBlockState(q);
            if (!isKelp(st)) continue;
            n += Divers.takeDrops(level, f, q, st, ItemStack.EMPTY);
        }
        // The root is the growing tip again (the game makes it so when what was over it goes; this makes sure of it).
        BlockState rootState = level.getBlockState(root);
        if (rootState.is(Blocks.KELP_PLANT)) {
            level.setBlock(root, Blocks.KELP.defaultBlockState().setValue(BlockStateProperties.AGE_25, level.getRandom().nextInt(25)), 3);
        }
        if (n > 0) {
            w.kelp += n;
            w.harvests++;
            Divers.today(level, f.ownerId(), "kelp", n);
            f.note(AssistantEntity.Deed.CROPS_HARVESTED, 1);
        }
        return n;
    }

    /** Plant a piece of kelp at this root, out of the pack. */
    static boolean plant(ServerLevel level, Divers.Waterside w, VillageFolkEntity f, BlockPos root) {
        BlockPos top = root;
        while (level.getFluidState(top.above()).is(FluidTags.WATER) && top.getY() < root.getY() + 40) top = top.above();
        if (!plantable(level, root, top.getY())) return false;
        if (f.removeMatching(KELP, 1) != 1) return false;
        level.setBlock(root, Blocks.KELP.defaultBlockState().setValue(BlockStateProperties.AGE_25, level.getRandom().nextInt(25)), 3);
        level.playSound(null, root, SoundEvents.WET_GRASS_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
        if (!w.bed.contains(root)) w.bed.add(root.immutable());
        w.wild.remove(root);
        w.planted++;
        f.note(AssistantEntity.Deed.CROPS_PLANTED, 1);
        if (f.ownerId() != null) Economy.tally(f.ownerId(), StationTask.DIVER, new ItemStack(Items.KELP), 1, false);
        return true;
    }

    /** Dig a block of the bed (clay, sand, gravel) with what it holds (a shovel from the stores, or its hands). */
    static int dig(ServerLevel level, Divers.Waterside w, VillageFolkEntity f, BlockPos at) {
        BlockState st = level.getBlockState(at);
        if (!(st.is(Blocks.CLAY) || st.is(Blocks.SAND) || st.is(Blocks.GRAVEL))) return 0;
        ItemStack tool = shovel(f);
        int n = Divers.takeDrops(level, f, at, st, tool);
        if (!tool.isEmpty()) wear(f, tool);
        f.note(AssistantEntity.Deed.BLOCKS_MINED, 1);
        if (st.is(Blocks.CLAY)) {
            w.clay += n;
            Divers.today(level, f.ownerId(), "clay", n);
        } else if (st.is(Blocks.SAND)) {
            w.sand += n;
        } else {
            w.gravel += n;
        }
        return n;
    }

    private static ItemStack shovel(VillageFolkEntity f) {
        for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && s.is(ItemTags.SHOVELS)) return s;
        return ItemStack.EMPTY;
    }

    /** A tool's wear, as a player's: one use, and broken at the end of it. */
    static void wear(VillageFolkEntity f, ItemStack tool) {
        if (!tool.isDamageableItem()) return;
        tool.setDamageValue(tool.getDamageValue() + 1);
        if (tool.getDamageValue() >= tool.getMaxDamage()) {
            tool.shrink(1);
            f.brain("wore out its " + tool.getHoverName().getString().toLowerCase(java.util.Locale.ROOT));
        }
    }

    // ------------------------------------------------------------------ the pack

    /** Is its pack nearly full? Home with it. */
    static boolean loaded(VillageFolkEntity f) {
        int free = 0;
        for (ItemStack s : f.getInventoryItems()) if (s.isEmpty()) free++;
        return free <= FULL_SLOTS;
    }

    /** Raw kelp in the pack past what it keeps for the bed. */
    static int toDry(ServerLevel level, VillageFolkEntity f) {
        int kelp = f.countCarried(KELP);
        Divers.Waterside w = Divers.water(f.ownerId());
        int keep = w != null && f.ownerId() != null && live(level, w) < bedWanted(level, f.ownerId()) ? Divers.KELP_FOR_BED : 0;
        return Math.max(0, kelp - keep);
    }

    /** What it carries for the stores: what it brought up and made, not its kit. */
    static boolean bankable(ItemStack s) {
        return s.is(Items.DRIED_KELP) || s.is(Items.DRIED_KELP_BLOCK) || s.is(Items.CLAY_BALL) || s.is(Items.SAND) || s.is(Items.GRAVEL)
            || s.is(Items.FLINT) || s.is(Items.TURTLE_SCUTE) || s.is(Items.PRISMARINE_SHARD) || s.is(Items.PRISMARINE_CRYSTALS)
            || s.is(Items.PRISMARINE) || s.is(Items.PRISMARINE_BRICKS) || s.is(Items.DARK_PRISMARINE) || s.is(Items.SEA_LANTERN)
            || s.is(Items.NAUTILUS_SHELL) || s.is(Items.INK_SAC) || s.is(Items.BONE) || s.is(ItemTags.FISHES);
    }

    /** Is there somewhere to dry kelp just now: room in the shed's smoker, a free place on its campfire (or the drying
     *  fire on the bank), or no fire yet (it sets one down)? */
    static boolean dryRoom(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        BlockPos[] p = parts(Divers.shedOf(v.id()));
        BlockPos smoker = p != null ? p[0] : null;
        BlockPos camp = p != null ? p[1] : fire(level, v.id());
        if (p == null && camp == null) return canSetFire(level, v);
        if (smoker != null && level.getBlockEntity(smoker) instanceof AbstractFurnaceBlockEntity fb
            && (fb.getItem(0).isEmpty() || fb.getItem(0).is(Items.KELP) && fb.getItem(0).getCount() < 64)) return true;
        return camp != null && level.getBlockEntity(camp) instanceof CampfireBlockEntity cb && cb.getItems().stream().anyMatch(ItemStack::isEmpty);
    }

    /** How much it carries for the stores. */
    static int carrying(VillageFolkEntity f) {
        return f.countCarried(KelpBeds::bankable);
    }

    // ------------------------------------------------------------------ the shed

    /** The shed's fixtures, by the drawing: {smoker, campfire, bench, barrel, a place inside to stand}. */
    @Nullable
    static BlockPos[] parts(@Nullable Ledger.Building b) {
        if (b == null) return null;
        return PARTS.computeIfAbsent(b.anchor(), k -> {
            BlockPos[] out = new BlockPos[5];
            for (BuildGoal.Placement p : BuildGoal.plan(b.structure(), b.anchor(), b.facing(), 13)) {
                if (p.part() == BuildGoal.Part.SMOKER && out[0] == null) out[0] = p.pos();
                if (p.part() == BuildGoal.Part.CAMPFIRE && out[1] == null) out[1] = p.pos();
                if (p.part() == BuildGoal.Part.CRAFTING_TABLE && out[2] == null) out[2] = p.pos();
                if (p.part() == BuildGoal.Part.BARREL && out[3] == null) out[3] = p.pos();
            }
            out[4] = b.anchor();
            return out;
        });
    }

    /** Where the diver stands to work the shed (or its drying fire on the bank). */
    static BlockPos shedSpot(ServerLevel level, Villages.Village v, Divers.Waterside w) {
        BlockPos[] p = parts(Divers.shedOf(v.id()));
        if (p != null) return p[4];
        BlockPos fire = fire(level, v.id());
        return fire != null ? fire.relative(Direction.NORTH) : w.bank;
    }

    /** The drying fire a diver with no shed set down on the bank, if it still burns there. */
    @Nullable
    static BlockPos fire(ServerLevel level, UUID village) {
        BlockPos p = FIRE.get(village);
        if (p == null) {
            String s = Ledger.note(village, "diver.fire");
            if (s != null && !s.isEmpty()) {
                String[] f = s.split(",");
                try { p = new BlockPos(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2])); } catch (RuntimeException ignored) { }
            }
            if (p != null) FIRE.put(village, p);
        }
        if (p != null && level.isLoaded(p) && !level.getBlockState(p).is(Blocks.CAMPFIRE)) {
            FIRE.remove(village);
            Ledger.forget(village, "diver.fire");
            return null;
        }
        return p;
    }

    /**
     * With no shed and no drying fire yet: could it set one down, a campfire out of the stores or made of them at the
     * bench (logs, sticks and a coal)? Looked at once a minute, so a town with nothing to make one of is not planned
     * for on every round; its kelp goes into the stores raw till it has.
     */
    static boolean canSetFire(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        long[] seen = FIRE_OK.get(v.id());
        if (seen != null && now - seen[0] < 1200L && now >= seen[0]) return seen[1] != 0L;
        boolean ok = Market.stock(level, v.id(), s -> s.is(Items.CAMPFIRE)) > 0
            || Bench.plan(level, v, Items.CAMPFIRE, 1, Bench.handOf(level, v, null, null)).ok();
        FIRE_OK.put(v.id(), new long[]{ now, ok ? 1L : 0L });
        return ok;
    }

    /** Does the shed want the diver: dried kelp to take out, kelp to dry and room to dry it? Paced, unless its pack is full. */
    static boolean shedDue(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f) {
        long now = level.getGameTime();
        int dry = toDry(level, f);
        if (dry >= 24 && dryRoom(level, v, f)) return true;
        Long last = SHED_AT.get(f.getUUID());
        if (last != null && now - last < SHED_EVERY && now >= last) return false;
        BlockPos[] p = parts(Divers.shedOf(v.id()));
        BlockPos smoker = p != null ? p[0] : null;
        BlockPos camp = p != null ? p[1] : fire(level, v.id());
        if (smoker != null && level.getBlockEntity(smoker) instanceof AbstractFurnaceBlockEntity fb) {
            if (!fb.getItem(2).isEmpty()) return true;
            if (dry > 0 && fb.getItem(0).getCount() < 32) return true;
        }
        if (camp != null) {
            if (!level.getEntitiesOfClass(ItemEntity.class, new AABB(camp).inflate(3), e -> e.getItem().is(Items.DRIED_KELP)).isEmpty()) return true;
            if (dry > 0 && level.getBlockEntity(camp) instanceof CampfireBlockEntity cb && cb.getItems().stream().anyMatch(ItemStack::isEmpty)) return true;
        }
        if (p == null && camp == null && dry >= 8 && canSetFire(level, v)) return true;   // no fire yet: it sets one down
        return f.countCarried(DRIED) >= 9;
    }

    /**
     * At the shed: its fixtures seen to, the smoker emptied and fed, the campfire's dried kelp picked up and fresh kelp
     * laid on it, the dried kelp packed into blocks at the bench, and a turtle helmet made of its scutes if it has
     * none (and there is no smith to make one). A step every half second, so it is seen at it. False when done.
     */
    static boolean atShed(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f, Divers.Dive d) {
        if (d.worked++ % 10 != 9) return true;
        UUID id = v.id();
        SHED_AT.put(f.getUUID(), level.getGameTime());
        Ledger.Building shed = Divers.shedOf(id);
        BlockPos[] p = parts(shed);
        f.swing(InteractionHand.MAIN_HAND);
        switch (d.step++) {
            case 0 -> {
                if (p != null) {
                    fixture(level, v, f, p[0], Items.SMOKER, Blocks.SMOKER.defaultBlockState()
                        .setValue(AbstractFurnaceBlock.FACING, shed.facing().getOpposite()));
                    fixture(level, v, f, p[1], Items.CAMPFIRE, Blocks.CAMPFIRE.defaultBlockState());
                    fixture(level, v, f, p[2], Items.CRAFTING_TABLE, Blocks.CRAFTING_TABLE.defaultBlockState());
                } else if (fire(level, id) == null) {
                    setFire(level, v, w, f);
                }
            }
            case 1 -> {
                BlockPos smoker = p != null ? p[0] : null;
                if (smoker != null) smoke(level, v, w, f, smoker);
            }
            case 2 -> {
                BlockPos camp = p != null ? p[1] : fire(level, id);
                if (camp != null) campfire(level, w, f, camp);
            }
            case 3 -> {
                int made = pack(level, v, w, f, p != null ? p[2] : null);
                if (made > 0) d.said = "packed " + made + " dried kelp block" + (made == 1 ? "" : "s");
                TurtleBeach.ownHelmet(level, v, w, f, p != null ? p[2] : null);
                boolean table = p != null && p[2] != null && level.getBlockState(p[2]).is(Blocks.CRAFTING_TABLE);
                DiverRaids.pack(level, f, table || f.countCarried(s -> s.is(Items.CRAFTING_TABLE)) > 0);
            }
            default -> {
                Divers.save(id, w);
                return false;
            }
        }
        return true;
    }

    /** A fixture of the shed's drawing that is not there (the builders had none to put in): out of the stores, or
     *  made at the bench out of them by the game's recipe (a smoker of a furnace and four logs), and set down. */
    static void fixture(ServerLevel level, Villages.Village v, VillageFolkEntity f, @Nullable BlockPos at, Item item, BlockState state) {
        if (at == null || !level.getBlockState(at).isAir()) return;
        if (!Crafts.take(level, v, s -> s.is(item), 1)) {
            Bench.Hand hand = Bench.handOf(level, v, f, Divers.SHED);
            Bench.Plan plan = Bench.plan(level, v, item, 1, hand);
            if (!plan.ok() || Bench.make(level, v, plan, f, hand).isEmpty()) return;
            if (!Crafts.take(level, v, s -> s.is(item), 1)) return;
        }
        level.setBlock(at, state, 3);
        level.playSound(null, at, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
        f.brain("set the " + item.getDescription().getString().toLowerCase(java.util.Locale.ROOT) + " in the shed");
    }

    /** No shed: a campfire set down on the bank to dry the kelp over, out of the stores or made of them. */
    static void setFire(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f) {
        BlockPos at = null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos c = w.bank.relative(d, 2);
            BlockPos s = Divers.standable(level, c.getX(), c.getZ());
            if (s != null && Math.abs(s.getY() - w.bank.getY()) <= 1 && !Divers.besideWater(level, s)) { at = s; break; }
        }
        if (at == null) return;
        BlockPos spot = at;
        if (!Crafts.take(level, v, s -> s.is(Items.CAMPFIRE), 1)) {
            Bench.Hand hand = Bench.handOf(level, v, f, null);
            Bench.Plan plan = Bench.plan(level, v, Items.CAMPFIRE, 1, hand);
            if (!plan.ok() || Bench.make(level, v, plan, f, hand).isEmpty() || !Crafts.take(level, v, s -> s.is(Items.CAMPFIRE), 1)) return;
        }
        level.setBlock(spot, Blocks.CAMPFIRE.defaultBlockState(), 3);
        FIRE.put(v.id(), spot);
        Ledger.note(v.id(), "diver.fire", spot.getX() + "," + spot.getY() + "," + spot.getZ());
        f.brain("set a drying fire down on the bank");
    }

    /** The smoker: the dried kelp out, kelp in, and fuel if it has gone out (a kelp block first; coal last). */
    static void smoke(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f, BlockPos at) {
        if (!(level.getBlockEntity(at) instanceof AbstractFurnaceBlockEntity fb)) return;
        UUID id = v.id();
        ItemStack out = fb.getItem(2);
        if (!out.isEmpty()) {
            ItemStack lot = out.copy();
            ItemStack left = f.insertItem(out.copy());
            int in = lot.getCount() - left.getCount();
            if (in > 0) {
                out.shrink(in);
                fb.setItem(2, out.isEmpty() ? ItemStack.EMPTY : out);
                dried(level, w, f, lot, in);
            }
        }
        ItemStack input = fb.getItem(0);
        int room = input.isEmpty() ? 64 : input.is(Items.KELP) ? 64 - input.getCount() : 0;
        int give = Math.min(room, toDry(level, f));
        if (give > 0) {
            f.removeMatching(KELP, give);
            if (input.isEmpty()) fb.setItem(0, new ItemStack(Items.KELP, give));
            else input.grow(give);
            Economy.tally(id, StationTask.DIVER, new ItemStack(Items.KELP), give, false);
        }
        boolean lit = level.getBlockState(at).hasProperty(AbstractFurnaceBlock.LIT) && level.getBlockState(at).getValue(AbstractFurnaceBlock.LIT);
        if (!lit && fb.getItem(1).isEmpty() && !fb.getItem(0).isEmpty()) {
            int load = fb.getItem(0).getCount();
            for (Predicate<ItemStack> fuel : SMOKER_FUEL) {
                ItemStack have = ItemStack.EMPTY;
                for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && fuel.test(s)) { have = s; break; }
                if (have.isEmpty()) continue;
                // A kelp block dries twenty in the smoker, a log one and a half: the game's burn times, halved in a
                // smoker that cooks in half the time, so a fuel does the same work there as in a furnace.
                int each = Math.max(1, have.getBurnTime(net.minecraft.world.item.crafting.RecipeType.SMOKING) / 200);
                int n = Math.max(1, Math.min(have.getCount(), Math.min(4, (load + each - 1) / each)));
                ItemStack fuelLot = have.copyWithCount(n);
                have.shrink(n);
                fb.setItem(1, fuelLot);
                FuelBook.burnt(level, id, fuelLot, n);
                f.brain("put " + n + " " + fuelLot.getHoverName().getString().toLowerCase(java.util.Locale.ROOT) + " on the smoker");
                break;
            }
        }
        fb.setChanged();
        level.playSound(null, at, SoundEvents.SMOKER_SMOKE, SoundSource.BLOCKS, 0.6F, 1.0F);
    }

    /** The campfire: its dried kelp picked up off the ground, and fresh kelp laid on its empty places (no fuel). */
    static void campfire(ServerLevel level, Divers.Waterside w, VillageFolkEntity f, BlockPos at) {
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(3), e -> e.getItem().is(Items.DRIED_KELP))) {
            ItemStack lot = e.getItem().copy();
            ItemStack left = f.insertItem(lot.copy());
            int in = lot.getCount() - left.getCount();
            if (in <= 0) continue;
            f.take(e, in);
            if (left.isEmpty()) e.discard();
            else e.setItem(left);
            dried(level, w, f, lot, in);
        }
        if (!(level.getBlockEntity(at) instanceof CampfireBlockEntity cb)) return;
        BlockState st = level.getBlockState(at);
        if (st.hasProperty(CampfireBlock.LIT) && !st.getValue(CampfireBlock.LIT)) {
            // Out: lit again, with a flint and steel out of the stores if there is one (as a player would).
            if (f.ownerId() != null && Villages.get(f.ownerId()) != null && Crafts.take(level, Villages.get(f.ownerId()),
                    s -> s.is(Items.FLINT_AND_STEEL), 1)) {
                level.setBlock(at, st.setValue(CampfireBlock.LIT, true), 3);
            } else {
                return;
            }
        }
        ItemStack sample = new ItemStack(Items.KELP);
        Optional<RecipeHolder<CampfireCookingRecipe>> recipe = cb.getCookableRecipe(sample);
        if (recipe.isEmpty()) return;
        int time = recipe.get().value().getCookingTime();
        for (int k = 0; k < 4 && toDry(level, f) > 0; k++) {
            ItemStack one = null;
            for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && s.is(Items.KELP)) { one = s; break; }
            if (one == null || !cb.placeFood(f, one, time)) break;
            if (f.ownerId() != null) Economy.tally(f.ownerId(), StationTask.DIVER, sample, 1, false);
        }
    }

    /** Dried kelp it took out of the smoker or off the campfire: its work, in the books. */
    private static void dried(ServerLevel level, Divers.Waterside w, VillageFolkEntity f, ItemStack lot, int in) {
        if (!lot.is(Items.DRIED_KELP)) return;
        Economy.gathered(f, lot, in);
        f.note(AssistantEntity.Deed.ITEMS_SMELTED, in);
        w.dried += in;
        Divers.today(level, f.ownerId(), "dried", in);
    }

    /**
     * Nine dried kelp to a block, at the bench, by the game's own recipe: as many as it has nine of, but while the
     * town is hungry thirty-two left loose for the larder. Returns how many blocks it made.
     */
    static int pack(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f, @Nullable BlockPos bench) {
        boolean table = bench != null && level.getBlockState(bench).is(Blocks.CRAFTING_TABLE)
            || f.countCarried(s -> s.is(Items.CRAFTING_TABLE)) > 0;
        int loose = Market.hungry(v.id()) ? Divers.LOOSE_KELP : 0;
        int made = 0;
        while (f.countCarried(DRIED) - loose >= 9 && made < 16) {
            if (!craft(level, f, Items.DRIED_KELP_BLOCK, table)) break;
            made++;
        }
        if (made > 0) {
            UUID id = v.id();
            Economy.gathered(f, new ItemStack(Items.DRIED_KELP_BLOCK), made);
            Economy.tally(id, StationTask.DIVER, new ItemStack(Items.DRIED_KELP), made * 9, false);
            f.note(AssistantEntity.Deed.THINGS_MADE, made);
            w.blocks += made;
            Divers.today(level, id, "blocks", made);
            level.playSound(null, f.blockPosition(), SoundEvents.WET_GRASS_PLACE, SoundSource.BLOCKS, 0.7F, 0.8F);
        }
        return made;
    }

    /**
     * Make one of this in hand, by the game's own crafting recipe (RecipeBook): its parts out of the pack, the thing
     * into it. A three-by-three recipe wants a bench to hand. False, and nothing used, if it has not the parts.
     */
    static boolean craft(ServerLevel level, VillageFolkEntity f, Item out, boolean table) {
        for (RecipeBook.Way way : RecipeBook.waysFor(level, out)) {
            if (way.fire() != RecipeBook.Fire.NONE || way.needsTable() && !table) continue;
            boolean all = true;
            for (RecipeBook.Part part : way.parts()) {
                if (f.countCarried(s -> part.ingredient().test(s)) < part.count()) { all = false; break; }
            }
            if (!all) continue;
            for (RecipeBook.Part part : way.parts()) f.removeMatching(s -> part.ingredient().test(s), part.count());
            ItemStack made = new ItemStack(out, way.yield());
            ItemStack left = f.insertItem(made);
            if (!left.isEmpty()) Block.popResource(level, f.blockPosition(), left);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the stores

    /** Does it want anything of the stores now: its pack to empty, a helmet, shears, kelp to plant, fuel? Paced. */
    static boolean wantsFromStores(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f) {
        if (loaded(f) && carrying(f) > 0) return true;
        long now = level.getGameTime();
        Long last = STORES_AT.get(f.getUUID());
        if (last != null && now - last < STORES_EVERY && now >= last) return false;
        UUID id = v.id();
        if (carrying(f) >= 32) return true;
        if (carrying(f) > 0 && level.getDayTime() % 24000L > 10500L) return true;     // home with the day's work
        if (f.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && Market.stock(level, id, s -> s.is(Items.TURTLE_HELMET)) > 0) return true;
        if (TurtleBeach.wantsShears(level, w, f) && Market.stock(level, id, s -> s.is(Items.SHEARS)) > 0) return true;
        if (DiverRaids.wantsPick(level, v, w, f) && Market.stock(level, id, s -> s.getItem() instanceof net.minecraft.world.item.PickaxeItem) > 0) return true;
        if (live(level, w) < bedWanted(level, id) && f.countCarried(KELP) == 0 && Market.stock(level, id, KELP) > 0) return true;
        return toDry(level, f) > 0 && !hasSmokerFuel(f) && Market.stock(level, id, s -> FuelBook.KELP_BLOCK.test(s) || s.is(ItemTags.LOGS)) > 0;
    }

    static boolean hasSmokerFuel(VillageFolkEntity f) {
        for (Predicate<ItemStack> fuel : SMOKER_FUEL) if (f.countCarried(fuel) > 0) return true;
        return false;
    }

    /**
     * At the stores: what it brought up and made banked (its work, in the books), and its kit drawn: a turtle helmet
     * to wear, shears for the seagrass, kelp for the bed, a kelp block (or charcoal or logs, with none) for its smoker.
     */
    static boolean atStores(ServerLevel level, Villages.Village v, Divers.Waterside w, VillageFolkEntity f, Divers.Dive d) {
        if (d.worked++ < 10) return true;
        UUID id = v.id();
        STORES_AT.put(f.getUUID(), level.getGameTime());
        int r = Villages.storesRadius(id);
        BlockPos heart = v.centre();
        int banked = 0;
        boolean kelpBlocks = false;
        var inv = f.getInventoryItems();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || !bankable(s)) continue;
            int keep = s.is(Items.DRIED_KELP_BLOCK) ? keptBlocks(f, s) : 0;
            int n = s.getCount() - keep;
            if (n <= 0) continue;
            ItemStack lot = s.copyWithCount(n);
            Economy.produced(f, lot.copy());
            ItemStack left = Market.intoStores(level, id, lot);
            int in = n - left.getCount();
            s.shrink(in);
            if (s.isEmpty()) inv.set(i, ItemStack.EMPTY);
            banked += in;
            if (lot.is(Items.DRIED_KELP_BLOCK) && in > 0) kelpBlocks = true;
        }
        if (kelpBlocks) FuelBook.forget(id);
        // A pack still full (the shed had no room to dry it all): the raw kelp past the bed's into the stores too.
        if (loaded(f)) {
            int raw = toDry(level, f);
            if (raw > 0) {
                ItemStack lot = new ItemStack(Items.KELP, raw);
                Economy.produced(f, lot.copy());
                ItemStack left = Market.intoStores(level, id, lot);
                f.removeMatching(KELP, raw - left.getCount());
                banked += raw - left.getCount();
            }
        }
        // Its kit.
        if (f.getItemBySlot(EquipmentSlot.HEAD).isEmpty() && f.drawFrom(heart, s -> s.is(Items.TURTLE_HELMET), 1, r) > 0) {
            TurtleBeach.wear(f);
        }
        if (TurtleBeach.wantsShears(level, w, f)) f.drawFrom(heart, s -> s.is(Items.SHEARS), 1, r);
        if (DiverRaids.wantsPick(level, v, w, f)) f.drawFrom(heart, s -> s.getItem() instanceof net.minecraft.world.item.PickaxeItem, 1, r);
        if (live(level, w) < bedWanted(level, id) && f.countCarried(KELP) < Divers.KELP_FOR_BED) {
            f.drawFrom(heart, KELP, Divers.KELP_FOR_BED - f.countCarried(KELP), r);
        }
        if (!hasSmokerFuel(f)) {
            if (f.drawFrom(heart, FuelBook.KELP_BLOCK, 1, r) > 0) FuelBook.forget(id);
            else if (f.drawFrom(heart, s -> s.is(Items.CHARCOAL), 2, r) == 0) f.drawFrom(heart, s -> s.is(ItemTags.LOGS), 4, r);
        }
        if (banked > 0) d.said = "banked " + banked + " things at the stores";
        Divers.save(id, w);
        return false;
    }

    /** A block of its own kelp it keeps back for its smoker: a block dries twenty, a full load and more. */
    private static int keptBlocks(VillageFolkEntity f, ItemStack s) {
        return Math.min(1, s.getCount());
    }

    /** The shed's smoker, campfire and bench, put in where the builders had none: done by the diver at the shed
     *  (atShed); this only notes a shed's fixtures afresh when it has been built (or rebuilt). */
    static void keepShed(ServerLevel level, Villages.Village v) {
        Ledger.Building b = Divers.shedOf(v.id());
        if (b != null && !PARTS.containsKey(b.anchor())) parts(b);
    }

    // ------------------------------------------------------------------ the stage

    /** The smoke stage: a bed of kelp planted out of nothing (as a showcase's is), grown five tall (ripe, so the diver is
     *  seen cutting it), round the middle of the water. */
    static int stageBed(ServerLevel level, Divers.Waterside w, int n) {
        int planted = 0;
        for (BlockPos root : plantSpots(level, w, n)) {
            level.setBlock(root, Blocks.KELP.defaultBlockState().setValue(BlockStateProperties.AGE_25, 10), 3);
            for (int k = 1; k < 5 && level.getBlockState(root.above(k)).is(Blocks.WATER); k++) {
                level.setBlock(root.above(k - 1), Blocks.KELP_PLANT.defaultBlockState(), 3);
                level.setBlock(root.above(k), Blocks.KELP.defaultBlockState().setValue(BlockStateProperties.AGE_25, 10 + k), 3);
            }
            w.bed.add(root);
            planted++;
        }
        return planted;
    }

    /** Is this food the diver might eat of the dried kelp? (Its own: a bite of dried kelp is food like any other.) */
    static boolean food(ItemStack s) {
        return s.get(DataComponents.FOOD) != null;
    }
}
