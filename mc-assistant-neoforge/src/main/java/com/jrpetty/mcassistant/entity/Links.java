package com.jrpetty.mcassistant.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The links between the trades that nothing else made: where the things the crafts need come
 * from, so that every one has a maker in the village and not only in a player's pack.
 * <ul>
 * <li>the farmers plant the sugar cane they brought along the field's water, and cut it (the
 *     enchanter's paper, the brewer's sugar, the café's pies);</li>
 * <li>the rancher milks the cows with a bucket (the café's cakes) and goes out with a lead for
 *     wild animals when the pen has no pair ({@link Drover});</li>
 * <li>the smelter digs sand off the river bed or the beach and fires it into glass (the
 *     bottles of the brewer, the beekeeper and the café; the windows);</li>
 * <li>the smelter takes the stone the village can spare to its furnaces for the masons (stone
 *     bricks, smooth stone), digs clay off a river bed for bricks, and keeps the stores in torches
 *     of its coal and a stick ({@link Masonry});</li>
 * <li>the woodcutters cut the vines they come across on their trees, with shears, for the mossy
 *     stone; the farmers pick a wild flower or two on their rounds for the gardens and pots;</li>
 * <li>the watch drinks the brewer's healing when a fight goes badly.</li>
 * </ul>
 */
public final class Links {

    private Links() {}

    private static final Map<UUID, Integer> LAST = new ConcurrentHashMap<>();
    /** When each folk last took a potion from the stores. */
    private static final Map<UUID, Integer> DOSED = new ConcurrentHashMap<>();

    public static void resetForTests() { LAST.clear(); DOSED.clear(); }

    /** The links a hand of each trade keeps up, every couple of minutes. Returns whether it is busy with one now. */
    public static boolean tend(VillageFolkEntity f, ServerLevel level) {
        int last = LAST.getOrDefault(f.getUUID(), -100000);
        if (f.tickCount - last < 2400 && f.tickCount >= last) return false;
        LAST.put(f.getUUID(), f.tickCount);
        return switch (f.stationTask()) {
            case FARM -> cane(f, level) != null | compost(f, level) != null | flowers(f, level) != null;
            case WOOD -> vines(f, level) != null;
            case MINE -> workPotion(f, level) != null;
            case HAUL, FISH -> workPotion(f, level) != null;
            case RANCH -> {
                // Both: a milking doesn't keep it from going for an animal the pen is short of.
                boolean milked = milk(f, level) != null;
                yield Drover.consider(f, level) || milked;
            }
            case SMELT -> {
                // Ore first (it is the village's tools); with none to take, the makings of its glass and
                // of the masons' stone and brick. And the stores' torches, either way.
                boolean busy = ore(f, level) != null
                    || (sand(f, level) != null | stone(f, level) != null | clay(f, level) != null);
                yield torches(f, level) != null | busy;
            }
            default -> false;
        };
    }

    // ------------------------------------------------------------------ cane

    /** The most sugar cane a farm grows: a row along its ditch. */
    static final int CANE = 6;

    /**
     * A farmer's sugar cane: the tall cane on the field's water cut (the bottom left to grow again),
     * and the cuttings it carries planted on the water's edge, up to a row of six. Returns what it
     * did, or null.
     */
    @Nullable
    public static String cane(VillageFolkEntity f, ServerLevel level) {
        WorkZone z = f.workZone();
        if (z == null) return null;
        BlockPos c = z.center();
        int r = Math.min(8, z.radius());
        if (!Land.areaLoaded(level, c, r + 1)) return null;
        int bases = 0, cut = 0;
        java.util.List<BlockPos> edge = new java.util.ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -2, -r), c.offset(r, 2, r))) {
            BlockState st = level.getBlockState(p);
            if (st.is(Blocks.SUGAR_CANE)) {
                if (level.getBlockState(p.below()).is(Blocks.SUGAR_CANE)) continue;
                bases++;
                // Everything above the bottom is cut, from the top down.
                int tall = 1;
                while (tall < 4 && level.getBlockState(p.above(tall)).is(Blocks.SUGAR_CANE)) tall++;
                for (int k = tall - 1; k >= 1; k--) {
                    level.setBlock(p.above(k), Blocks.AIR.defaultBlockState(), 2 | 16);
                    cut++;
                }
                continue;
            }
            if (!level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos g = p.relative(d);
                BlockState gs = level.getBlockState(g);
                boolean ground = gs.is(Blocks.GRASS_BLOCK) || gs.is(Blocks.DIRT) || gs.is(Blocks.COARSE_DIRT)
                    || gs.is(Blocks.PODZOL) || gs.is(Blocks.SAND) || gs.is(Blocks.RED_SAND)
                    || (gs.is(Blocks.FARMLAND) && level.getBlockState(g.above()).isAir());
                if (ground && level.getBlockState(g.above()).isAir()) edge.add(g.immutable());
            }
        }
        if (cut > 0) {
            ItemStack cane = new ItemStack(Items.SUGAR_CANE, cut);
            ItemStack left = f.insertItem(cane);
            if (!left.isEmpty()) f.spawnAtLocation(left);
            f.swing(InteractionHand.MAIN_HAND);
            f.brain("cut " + cut + " sugar cane");
            return "cut " + cut + " sugar cane";
        }
        if (bases >= CANE || edge.isEmpty() || f.countCarried(s -> s.is(Items.SUGAR_CANE)) < 1) return null;
        BlockPos g = edge.get(level.getRandom().nextInt(edge.size()));
        if (level.getBlockState(g).is(Blocks.FARMLAND)) level.setBlock(g, Blocks.DIRT.defaultBlockState(), 3);
        if (!Blocks.SUGAR_CANE.defaultBlockState().canSurvive(level, g.above())) return null;
        f.removeMatching(s -> s.is(Items.SUGAR_CANE), 1);
        level.setBlock(g.above(), Blocks.SUGAR_CANE.defaultBlockState(), 3);
        level.playSound(null, g.above(), SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1.0F, 1.0F);
        f.swing(InteractionHand.MAIN_HAND);
        f.brain("planted sugar cane by the water");
        return "planted sugar cane by the water";
    }

    // ------------------------------------------------------------------ milk

    /**
     * The rancher's milking: a bucket (its own, or one from the stores the café sent back) to a
     * cow in the pen, when the stores are short of milk. Returns what it did, or null.
     */
    @Nullable
    public static String milk(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null) return null;
        if (Market.stock(level, village, s -> s.is(Items.MILK_BUCKET)) + f.countCarried(s -> s.is(Items.MILK_BUCKET)) >= 3) return null;
        java.util.List<Cow> cows = level.getEntitiesOfClass(Cow.class, f.getBoundingBox().inflate(12), c -> c.isAlive() && !c.isBaby());
        if (cows.isEmpty()) return null;
        if (f.countCarried(s -> s.is(Items.BUCKET)) < 1) {
            if (!Crafts.take(level, v, s -> s.is(Items.BUCKET), 1)) return null;
            ItemStack back = f.insertItem(new ItemStack(Items.BUCKET));
            if (!back.isEmpty()) { Crafts.store(level, v, back); return null; }   // a full pack: back it goes
        }
        if (f.removeMatching(s -> s.is(Items.BUCKET), 1) != 1) return null;
        ItemStack left = f.insertItem(new ItemStack(Items.MILK_BUCKET));
        if (!left.isEmpty()) Crafts.store(level, v, left);
        Cow cow = cows.get(0);
        f.getLookControl().setLookAt(cow);
        f.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, cow.blockPosition(), SoundEvents.COW_MILK, SoundSource.NEUTRAL, 1.0F, 1.0F);
        f.brain("milked a cow");
        return "milked a cow";
    }

    // ------------------------------------------------------------------ ore

    /**
     * The smelter's ore out of the stores: the carriers bring the miners' raw iron to the
     * storehouse, and the smelter only ever looked in the chests round its own smeltery, so the
     * long game's stores held raw iron the furnaces never saw. Iron first, then gold and copper.
     */
    @Nullable
    public static String ore(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null || f.countCarried(AssistantEntity.SMELTABLE_ORE) >= 8) return null;
        int took = 0;
        for (net.minecraft.world.item.Item raw : java.util.List.of(Items.RAW_IRON, Items.RAW_GOLD, Items.RAW_COPPER)) {
            int n = Math.min(32 - took, Market.stock(level, village, s -> s.is(raw)));
            if (n <= 0 || !Crafts.take(level, v, s -> s.is(raw), n)) continue;
            ItemStack left = f.insertItem(new ItemStack(raw, n));
            if (!left.isEmpty()) Crafts.store(level, v, left);
            took += n - left.getCount();
            if (took >= 32) break;
        }
        if (took == 0) return null;
        f.brain("took " + took + " raw ore from the stores to the furnaces");
        return "took " + took + " raw ore";
    }

    // ------------------------------------------------------------------ sand

    /**
     * The smelter's sand, for glass: when the stores are short of glass and it has no sand to
     * fire, it takes what sand the stores hold to its furnace; failing that, it digs some, off a
     * river or pond bed or the shore (the water closes over the hole), or shaves the top off open
     * sand beyond the town — one layer, never a pit. Returns what it did, or null.
     */
    @Nullable
    public static String sand(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null) return null;
        java.util.function.Predicate<ItemStack> sand = s -> s.is(Items.SAND) || s.is(Items.RED_SAND);
        if (!Masonry.glassShort(level, village)) return null;
        if (f.countCarried(sand) >= 4) return null;
        // Sand someone brought to the stores goes to the furnace before any is dug.
        int took = 0;
        for (net.minecraft.world.item.Item kind : java.util.List.of(Items.SAND, Items.RED_SAND)) {
            int n = Math.min(16 - took, Market.stock(level, village, s -> s.is(kind)));
            if (n <= 0 || !Crafts.take(level, v, s -> s.is(kind), n)) continue;
            ItemStack left = f.insertItem(new ItemStack(kind, n));
            if (!left.isEmpty()) Crafts.store(level, v, left);
            took += n - left.getCount();
        }
        if (took > 0) {
            f.brain("took " + took + " sand from the stores for glass");
            return "took " + took + " sand from the stores";
        }
        if (!com.jrpetty.mcassistant.AssistantConfig.villageReshapeLand()) return null;   // the land is the player's
        BlockPos heart = v.centre();
        int reach = Villages.townReach(village);
        int dug = 0;
        for (int ring = 4; ring <= reach + 48 && dug < 8; ring += 2) {
            if (!Land.areaLoaded(level, heart, ring)) break;
            boolean beyond = ring > reach + 1;
            for (int dx = -ring; dx <= ring && dug < 8; dx += 2) {
                for (int dz = -ring; dz <= ring && dug < 8; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = heart.getX() + dx, z = heart.getZ() + dz;
                    BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1, z);
                    BlockState st = level.getBlockState(p);
                    if (!st.is(Blocks.SAND) && !st.is(Blocks.RED_SAND)) continue;
                    if (Land.inABuilding(village, p) || !level.getBlockState(p.below()).isSolid()) continue;
                    boolean wet = level.getBlockState(p.above()).is(Blocks.WATER);
                    if (wet) {
                        // A bed with water over it and nothing open beside it: the water only fills the hole.
                        boolean open = false;
                        for (Direction d : Direction.Plane.HORIZONTAL) open |= level.getBlockState(p.relative(d)).isAir();
                        if (open) continue;
                    } else {
                        // Dry sand only beyond the town, open to the sky, and only the top layer: a
                        // column already lower than its neighbours has been dug.
                        if (!beyond || !level.getBlockState(p.above()).isAir()) continue;
                        boolean lower = false;
                        for (Direction d : Direction.Plane.HORIZONTAL) {
                            BlockPos n = p.relative(d);
                            lower |= level.getHeight(Heightmap.Types.OCEAN_FLOOR, n.getX(), n.getZ()) - 1 < p.getY();
                        }
                        if (lower) continue;
                    }
                    level.setBlock(p, wet ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                    ItemStack got = new ItemStack(st.getBlock().asItem());
                    ItemStack left = f.insertItem(got);
                    if (!left.isEmpty()) Crafts.store(level, v, left);
                    dug++;
                }
            }
        }
        if (dug == 0) return null;
        f.swing(InteractionHand.MAIN_HAND);
        f.brain("dug " + dug + " sand for glass");
        return "dug " + dug + " sand for glass";
    }

    // ------------------------------------------------------------------ stone, clay and torches

    /**
     * The smelter's stone, for the masons: when the village is short of stone bricks or smooth stone
     * and the smelter has little left to fire, it takes the plain stone the village can spare out of
     * the stores (Masonry.plainToSpare: never what the age is holding back, nor the builders' working
     * stock), stone already fired first. Returns what it did, or null.
     */
    @Nullable
    public static String stone(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null || !Masonry.wantsStone(level, v)) return null;
        if (f.countCarried(s -> s.is(Items.COBBLESTONE) || s.is(Items.STONE)) >= 24) return null;
        int spare = Math.min(32, Masonry.plainToSpare(level, v));
        if (spare <= 0) return null;
        int took = 0;
        for (net.minecraft.world.item.Item kind : java.util.List.of(Items.STONE, Items.COBBLESTONE)) {
            int n = Math.min(spare - took, Market.stock(level, village, s -> s.is(kind)));
            if (n <= 0 || !Crafts.take(level, v, s -> s.is(kind), n)) continue;
            ItemStack left = f.insertItem(new ItemStack(kind, n));
            if (!left.isEmpty()) Crafts.store(level, v, left);
            took += n - left.getCount();
        }
        if (took == 0) return null;
        f.brain("took " + took + " stone from the stores to fire and cut for the masons");
        return "took " + took + " stone for the masons";
    }

    /**
     * The smelter's clay, for bricks: when the village is short of brick it takes the clay the stores
     * hold (a miner or a leveller turns some up now and then); failing that, it digs a few blocks of
     * clay off a river or pond bed round the town, four balls a block, and the water closes over the
     * hole. Returns what it did, or null.
     */
    @Nullable
    public static String clay(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null || Masonry.shortOf(level, v, Items.BRICKS) <= 0) return null;
        java.util.function.Predicate<ItemStack> balls = s -> s.is(Items.CLAY_BALL);
        if (f.countCarried(balls) >= 16) return null;
        int n = Math.min(32, Market.stock(level, village, balls));
        if (n > 0 && Crafts.take(level, v, balls, n)) {
            ItemStack left = f.insertItem(new ItemStack(Items.CLAY_BALL, n));
            if (!left.isEmpty()) Crafts.store(level, v, left);
            f.brain("took " + n + " clay from the stores for bricks");
            return "took " + n + " clay from the stores";
        }
        if (!com.jrpetty.mcassistant.AssistantConfig.villageReshapeLand()) return null;   // the land is the player's
        BlockPos heart = v.centre();
        int reach = Villages.townReach(village);
        int dug = 0;
        for (int ring = 4; ring <= reach + 48 && dug < 4; ring += 2) {
            if (!Land.areaLoaded(level, heart, ring)) break;
            for (int dx = -ring; dx <= ring && dug < 4; dx += 2) {
                for (int dz = -ring; dz <= ring && dug < 4; dz += 2) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = heart.getX() + dx, z = heart.getZ() + dz;
                    BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1, z);
                    if (!level.getBlockState(p).is(Blocks.CLAY)) continue;
                    if (!level.getBlockState(p.above()).is(Blocks.WATER)) continue;          // a bed under water
                    if (Land.inABuilding(village, p) || !level.getBlockState(p.below()).isSolid()) continue;
                    boolean open = false;
                    for (Direction d : Direction.Plane.HORIZONTAL) open |= level.getBlockState(p.relative(d)).isAir();
                    if (open) continue;                                                       // the water only fills the hole
                    level.setBlock(p, Blocks.WATER.defaultBlockState(), 3);
                    ItemStack left = f.insertItem(new ItemStack(Items.CLAY_BALL, 4));
                    if (!left.isEmpty()) Crafts.store(level, v, left);
                    dug++;
                }
            }
        }
        if (dug == 0) return null;
        f.swing(InteractionHand.MAIN_HAND);
        f.brain("dug " + dug + " clay off the river bed for bricks");
        if (f.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Good clay down there. That's bricks, that is.",
                "Muddy work, but the builders want their bricks."));
        }
        return "dug " + dug + " clay";
    }

    /**
     * The stores' torches, kept up by the smelter, who has the coal: a lump of coal or charcoal and a
     * stick make four, and a plank is two sticks. Up to sixteen at a time, while the stores hold fewer
     * than the village likes (Masonry.keep). A village short of coal for its age puts only charcoal on
     * them, and only while the stores are nearly out. Returns what it did, or null.
     */
    @Nullable
    public static String torches(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null) return null;
        int want = Masonry.shortOf(level, v, Items.TORCH);
        if (want <= 0) return null;
        boolean saving = f.savingCoal();
        if (saving && Market.stock(level, village, s -> s.is(Items.TORCH)) >= 8) return null;
        java.util.function.Predicate<ItemStack> fuel = saving ? s -> s.is(Items.CHARCOAL)
            : s -> s.is(Items.COAL) || s.is(Items.CHARCOAL);
        int lumps = Math.min(Math.min(4, (want + 3) / 4), Market.stock(level, village, fuel));
        if (lumps <= 0) return null;
        int planks = (lumps + 1) / 2;
        if (!Crafts.usePlanks(level, v, planks)) return null;
        if (!Crafts.take(level, v, fuel, lumps)) {
            Crafts.store(level, v, new ItemStack(Items.OAK_PLANKS, planks));
            return null;
        }
        Crafts.store(level, v, new ItemStack(Items.TORCH, lumps * 4));
        f.brain("made " + lumps * 4 + " torches for the stores");
        return "made " + lumps * 4 + " torches";
    }

    // ------------------------------------------------------------------ vines and flowers

    /**
     * A woodcutter's vines, for the masons' mossy stone (Masonry): when it comes across vines on the
     * trees of its wood and the stores hold few, it cuts some with shears (the smith's, out of the
     * stores if it carries none), as a player must; vines come away for nothing else. Up to eight at a
     * time, from the bottom of each hang, so what is left still hangs and grows back. Returns what it
     * did, or null.
     */
    @Nullable
    public static String vines(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        WorkZone z = f.workZone();
        if (v == null || z == null) return null;
        if (Market.stock(level, village, s -> s.is(Items.VINE)) + f.countCarried(s -> s.is(Items.VINE)) >= 16) return null;
        BlockPos c = z.center();
        int r = Math.min(10, z.radius());
        if (!Land.areaLoaded(level, c, r + 1)) return null;
        java.util.List<BlockPos> hangs = new java.util.ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-r, -4, -r), c.offset(r, 14, r))) {
            // Only a vine with more vine above it: the top of each hang is left to grow back.
            if (level.getBlockState(p).is(Blocks.VINE) && level.getBlockState(p.above()).is(Blocks.VINE)) hangs.add(p.immutable());
        }
        if (hangs.isEmpty()) return null;
        if (f.countCarried(s -> s.is(Items.SHEARS)) == 0) {
            if (!Crafts.take(level, v, s -> s.is(Items.SHEARS), 1)) return null;           // no shears, no vines
            ItemStack left = f.insertItem(new ItemStack(Items.SHEARS));
            if (!left.isEmpty()) { Crafts.store(level, v, left); return null; }
        }
        hangs.sort(java.util.Comparator.comparingInt(BlockPos::getY));
        int cut = 0;
        for (BlockPos p : hangs) {
            if (cut >= 8) break;
            if (!level.getBlockState(p).is(Blocks.VINE)) continue;
            level.removeBlock(p, false);
            cut++;
        }
        if (cut == 0) return null;
        for (ItemStack st : f.getInventoryItems()) {
            if (!st.is(Items.SHEARS)) continue;
            st.setDamageValue(st.getDamageValue() + cut);
            if (st.getDamageValue() >= st.getMaxDamage()) st.shrink(1);
            break;
        }
        ItemStack left = f.insertItem(new ItemStack(Items.VINE, cut));
        if (!left.isEmpty()) Crafts.store(level, v, left);
        f.swing(InteractionHand.MAIN_HAND);
        f.brain("cut " + cut + " vines for the masons");
        return "cut " + cut + " vines";
    }

    /**
     * The farmer's flowers, picked on its way round: when the stores hold few (the gardens by the
     * houses, the window boxes, the beekeeper's meadow when it is short), it picks a wild one or two
     * off the grass round its field. Never one in a garden or on a grave, and never one off a
     * beekeeper's meadow. Returns what it did, or null.
     */
    @Nullable
    public static String flowers(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        WorkZone z = f.workZone();
        if (v == null || z == null) return null;
        java.util.function.Predicate<ItemStack> small = s -> s.is(net.minecraft.tags.ItemTags.SMALL_FLOWERS);
        if (Market.stock(level, village, small) + f.countCarried(small) >= 8) return null;
        int picked = 0;
        for (int i = 0; i < 2; i++) {
            BlockPos wild = Crafts.wildFlower(level, z.center(), Math.min(24, z.radius() + 12), village);
            if (wild == null || onAMeadow(village, wild)) break;
            BlockState st = level.getBlockState(wild);
            level.removeBlock(wild, false);
            ItemStack left = f.insertItem(new ItemStack(st.getBlock().asItem()));
            if (!left.isEmpty()) Crafts.store(level, v, left);
            picked++;
        }
        if (picked == 0) return null;
        f.brain("picked " + picked + " wild " + (picked == 1 ? "flower" : "flowers") + " for the gardens");
        return "picked " + picked + " flowers";
    }

    /** Is this spot on a beekeeper's meadow (its flowers are the bees')? */
    private static boolean onAMeadow(UUID village, BlockPos p) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a.stationTask() != AssistantEntity.StationTask.BEEKEEP || a.workZone() == null) continue;
            BlockPos c = a.workZone().center();
            if (Math.max(Math.abs(c.getX() - p.getX()), Math.abs(c.getZ() - p.getZ())) <= 10) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ compost

    /**
     * A farmer's compost: the seeds the stores hold far more of than anybody can sow, and the
     * watch's bones, made into bone meal for the fields (a settler farmer uses it, FarmGoal and
     * AssistantEntity.boneMealOne). Returns what it did, or null.
     */
    @Nullable
    public static String compost(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null || f.countCarried(s -> s.is(Items.BONE_MEAL)) >= 8) return null;
        java.util.function.Predicate<ItemStack> seeds = s -> s.is(Items.WHEAT_SEEDS) || s.is(Items.BEETROOT_SEEDS);
        int meal = 0;
        if (Market.stock(level, village, seeds) > 128 && Crafts.take(level, v, seeds, 16)) meal += 2;   // a composter's worth
        if (Market.stock(level, village, s -> s.is(Items.BONE)) > 4 && Crafts.take(level, v, s -> s.is(Items.BONE), 2)) meal += 6;
        if (meal == 0) return null;
        ItemStack left = f.insertItem(new ItemStack(Items.BONE_MEAL, meal));
        if (!left.isEmpty()) Crafts.store(level, v, left);
        f.brain("made " + meal + " bone meal for the fields");
        return "made " + meal + " bone meal";
    }

    // ------------------------------------------------------------------ potions for the work

    /**
     * The brewer's potions put to work, not only to the shop counter: a miner deep in the rock
     * takes fire resistance (or night vision), a carrier swiftness for its rounds, a fisher water
     * breathing. One from the stores when it has none working, at most every few minutes.
     */
    @Nullable
    public static String workPotion(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null) return null;
        int last = DOSED.getOrDefault(f.getUUID(), -100000);
        if (f.tickCount - last < 3600 && f.tickCount >= last) return null;
        java.util.List<net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion>> wanted = new java.util.ArrayList<>();
        net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect;
        switch (f.stationTask()) {
            case MINE -> {
                if (f.getY() > 40) return null;                                    // only down in the deep rock
                if (Market.stock(level, village, s -> isPotion(s, Potions.FIRE_RESISTANCE, Potions.LONG_FIRE_RESISTANCE)) > 0) {
                    wanted.add(Potions.FIRE_RESISTANCE); wanted.add(Potions.LONG_FIRE_RESISTANCE);
                    effect = MobEffects.FIRE_RESISTANCE;
                } else {
                    wanted.add(Potions.NIGHT_VISION); wanted.add(Potions.LONG_NIGHT_VISION);
                    effect = MobEffects.NIGHT_VISION;
                }
            }
            case HAUL -> {
                wanted.add(Potions.SWIFTNESS); wanted.add(Potions.LONG_SWIFTNESS); wanted.add(Potions.STRONG_SWIFTNESS);
                effect = MobEffects.MOVEMENT_SPEED;
            }
            case FISH -> {
                if (!f.isInWater() && !level.getFluidState(f.blockPosition().below()).is(net.minecraft.tags.FluidTags.WATER)) return null;
                wanted.add(Potions.WATER_BREATHING); wanted.add(Potions.LONG_WATER_BREATHING);
                effect = MobEffects.WATER_BREATHING;
            }
            default -> { return null; }
        }
        if (f.hasEffect(effect)) return null;
        ItemStack potion = Crafts.takeOne(level, v, s -> isPotion(s, wanted.toArray(new net.minecraft.core.Holder[0])));
        if (potion.isEmpty()) return null;
        DOSED.put(f.getUUID(), f.tickCount);
        drink(f, potion);
        return "drank a potion for the work";
    }

    @SafeVarargs
    private static boolean isPotion(ItemStack s, net.minecraft.core.Holder<net.minecraft.world.item.alchemy.Potion>... kinds) {
        if (!s.is(Items.POTION)) return false;
        PotionContents pc = s.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        for (var k : kinds) if (pc.is(k)) return true;
        return false;
    }

    /** Drink one: its effects, and the bottle back. */
    private static void drink(VillageFolkEntity f, ItemStack potion) {
        PotionContents pc = potion.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        for (MobEffectInstance e : pc.getAllEffects()) {
            if (e.getEffect().value().isInstantenous()) e.getEffect().value().applyInstantenousEffect(null, null, f, e.getAmplifier(), 1.0D);
            else f.addEffect(new MobEffectInstance(e));
        }
        ItemStack left = f.insertItem(new ItemStack(Items.GLASS_BOTTLE));
        if (!left.isEmpty()) f.spawnAtLocation(left);
        f.level().playSound(null, f.blockPosition(), SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 1.0F, 1.0F);
        f.brain("drank " + potion.getHoverName().getString().toLowerCase());
    }

    /** Anybody badly hurt with no potion of its own sends for the brewer's healing from the stores. */
    public static boolean healFromTheStores(VillageFolkEntity f, ServerLevel level) {
        if (f.getHealth() > f.getMaxHealth() * 0.4F || f.hasEffect(MobEffects.REGENERATION)) return false;
        UUID village = f.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        if (v == null) return false;
        int last = DOSED.getOrDefault(f.getUUID(), -100000);
        if (f.tickCount - last < 1200 && f.tickCount >= last) return false;
        ItemStack potion = Crafts.takeOne(level, v, Links::healing);
        if (potion.isEmpty()) return false;
        DOSED.put(f.getUUID(), f.tickCount);
        drink(f, potion);
        return true;
    }

    // ------------------------------------------------------------------ the watch's potions

    /** Is this one of the brewer's healing potions (or regeneration)? */
    public static boolean healing(ItemStack s) {
        if (!s.is(Items.POTION)) return false;
        PotionContents pc = s.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
        return pc.is(Potions.HEALING) || pc.is(Potions.STRONG_HEALING) || pc.is(Potions.REGENERATION)
            || pc.is(Potions.LONG_REGENERATION) || pc.is(Potions.STRONG_REGENERATION);
    }

    /** A guard badly hurt drinks a healing potion it carries (Raids.arm gives it one from the stores). */
    public static boolean drinkIfHurt(VillageFolkEntity f) {
        if (f.getHealth() > f.getMaxHealth() * 0.4F) return false;
        for (ItemStack s : f.getInventoryItems()) {
            if (!healing(s)) continue;
            PotionContents pc = s.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
            boolean healingNow = pc.is(Potions.HEALING) || pc.is(Potions.STRONG_HEALING);
            if (!healingNow && f.hasEffect(MobEffects.REGENERATION)) continue;   // one already working
            if (pc.is(Potions.HEALING)) f.heal(4.0F);
            else if (pc.is(Potions.STRONG_HEALING)) f.heal(8.0F);
            else f.addEffect(new MobEffectInstance(MobEffects.REGENERATION, pc.is(Potions.STRONG_REGENERATION) ? 440 : 900,
                pc.is(Potions.STRONG_REGENERATION) ? 1 : 0));
            s.shrink(1);
            ItemStack left = f.insertItem(new ItemStack(Items.GLASS_BOTTLE));
            if (!left.isEmpty()) f.spawnAtLocation(left);
            f.level().playSound(null, f.blockPosition(), SoundEvents.GENERIC_DRINK, SoundSource.NEUTRAL, 1.0F, 1.0F);
            f.brain("drank a healing potion");
            return true;
        }
        return false;
    }

}
