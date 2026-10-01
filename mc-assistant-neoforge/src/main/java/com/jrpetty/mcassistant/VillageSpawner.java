package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Names;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Settlements that were already there.
 *
 * <p>Villages are placed on a grid the same way the game places its own
 * structures: one candidate spot per cell, its exact position decided by the
 * world seed, so the same world always grows the same settlements and no two
 * end up on top of each other. When the chunk holding a candidate spot loads
 * for the first time and the ground there is worth living on, a village is
 * founded — a handful of folk, a chest of the things you cannot start without,
 * and nothing else. From that moment nobody helps them again.
 *
 * <p>They are given exactly enough to begin: food to live on while the first
 * field is dug, an axe and a pick so the first tree and the first stone are
 * possible, and seeds, saplings, torches and a crafting table in a chest. Every
 * single thing after that — the farm, the tools, the smeltery, the houses — is
 * theirs to earn.
 */
public final class VillageSpawner {

    private VillageSpawner() {}

    /** Chunks a village keeps awake around itself, so it grows whether or not
     *  anybody is watching. Four is a comfortable settlement's worth. */
    public static final int LOADED_RADIUS = 4;

    /** The most a grown settlement ever keeps awake. Releasing is always done
     *  at THIS radius rather than whatever the village had reached, because a
     *  release that is one ring short of what was taken strands those chunks
     *  ticking for the rest of the world's life — and releasing a chunk nobody
     *  forced is free. */
    public static final int MAX_LOADED_RADIUS = 24;   // the config's ceiling

    /**
     * How much ground a settlement keeps awake, which has to grow with the
     * settlement. Eight folk fit on one hillside; twenty do not, and a plot
     * outside the loaded ring is a plot nobody works while you are away —
     * which is the one thing these villages are FOR.
     *
     * <p>Derived from how far the plots actually go, not guessed beside it.
     * See VillageMath, and the tests that hold the two together.
     */
    public static int loadedRadiusFor(int folk) {
        return com.jrpetty.mcassistant.village.VillageMath.loadedRadiusChunks(
            folk, AssistantConfig.villageLoadedChunks());
    }

    /** Cells we have already settled or ruled out this session, so a chunk that
     *  loads and unloads repeatedly is not re-examined every time. */
    private static final Set<Long> CONSIDERED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** A cell whose anchor chunk has loaded, waiting for the world to settle. */
    private record Cell(ServerLevel level, long key, BlockPos anchor, long readyAt) {}

    private static final java.util.concurrent.ConcurrentLinkedQueue<Cell> WAITING =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final Set<Long> QUEUED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** How long a cell waits after its chunk loads before anybody looks at it:
     *  long enough for the folk of a village that is ALREADY there (saved in
     *  those chunks, loaded a moment after the chunk itself) to be found — a
     *  restart used to found every natural village a second time on top of
     *  itself. */
    private static final long SETTLE_TICKS = 200L;

    /** Forget everything remembered this session. For tests and world changes. */
    public static void resetForTests() {
        CONSIDERED.clear();
        WAITING.clear();
        QUEUED.clear();
    }

    /**
     * A chunk has loaded: if it holds the anchor of a village cell, WRITE THAT
     * DOWN and nothing else. This event fires inside the chunk's own loading —
     * for a freshly generated chunk, inside its FULL step — and reading the
     * height of a neighbouring chunk from here, or forcing a chunk ticket,
     * waits on a chunk that cannot finish until this handler returns. The whole
     * of the looking, and the founding, happens from the server tick.
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        com.jrpetty.mcassistant.Guard.run("natural-village chunk watch", () -> onChunkLoadGuarded(event));
    }

    private static void onChunkLoadGuarded(ChunkEvent.Load event) {
        if (!AssistantConfig.naturalVillages()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;

        ChunkPos chunk = event.getChunk().getPos();
        int spacing = Math.max(256, AssistantConfig.villageSpacing());
        int cellX = Math.floorDiv(chunk.getMinBlockX(), spacing);
        int cellZ = Math.floorDiv(chunk.getMinBlockZ(), spacing);
        long cellKey = (long) cellX * 4294967311L + cellZ;
        if (CONSIDERED.contains(cellKey) || QUEUED.contains(cellKey)) return;
        if (!inACluster(level, cellX, cellZ)) return;   // most of the map is empty on purpose

        BlockPos anchor = anchorFor(level, cellX, cellZ, spacing);
        // Only the chunk that actually contains the anchor does the work, so
        // this costs one comparison for every other chunk in the cell.
        if ((anchor.getX() >> 4) != chunk.x || (anchor.getZ() >> 4) != chunk.z) return;
        if (!QUEUED.add(cellKey)) return;
        WAITING.add(new Cell(level, cellKey, anchor, level.getGameTime() + SETTLE_TICKS));
    }

    /** One cell a second, from the server tick, once its wait is over. */
    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        com.jrpetty.mcassistant.Guard.run("natural-village founding", () -> onServerTickGuarded(event));
    }

    private static void onServerTickGuarded(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0) return;
        Cell c = WAITING.peek();
        if (c == null || c.readyAt() > c.level().getGameTime()) return;
        WAITING.poll();
        QUEUED.remove(c.key());
        boolean ours = false;
        for (ServerLevel l : event.getServer().getAllLevels()) {
            if (l == c.level()) { ours = true; break; }
        }
        if (!ours || CONSIDERED.contains(c.key())) return;

        ServerLevel level = c.level();
        BlockPos anchor = c.anchor();
        // Not loaded any more: leave it. It is queued again the next time the
        // chunk loads, and reading it now would only load it for us.
        if (!level.hasChunk(anchor.getX() >> 4, anchor.getZ() >> 4)) return;
        if (Villages.nearest(level, anchor) != null || folkNearby(level, anchor)) {
            CONSIDERED.add(c.key());               // somebody already lives here
            return;
        }
        BlockPos ground = groundAt(level, anchor.getX(), anchor.getZ());
        CONSIDERED.add(c.key());                    // from a tick, the answer is final
        if (ground == null || !liveable(level, ground)) return;
        found(level, ground);
    }

    /** How many grid cells across a cluster's home region is. Villages come in
     *  groups with real country between the groups, rather than one settlement
     *  every so many blocks all the way to the world border. */
    private static final int REGION_CELLS = 8;

    /**
     * Do settlements belong in this cell at all?
     *
     * <p>A flat grid puts a village every so many blocks for ever, which reads
     * as wallpaper rather than as a place: walk in any direction and you find
     * the same thing at the same interval. Villages come in GROUPS instead —
     * three to five of them within a mile or so of each other, sharing a
     * valley, with a long empty ride to the next group.
     *
     * <p>The whole thing is derived from the world seed, so it is stable: the
     * same world always grows the same groups in the same places, and this
     * answers identically no matter which chunk asks or when.
     */
    private static boolean inACluster(ServerLevel level, int cellX, int cellZ) {
        int regionX = Math.floorDiv(cellX, REGION_CELLS);
        int regionZ = Math.floorDiv(cellZ, REGION_CELLS);
        RandomSource r = RandomSource.create(
            level.getSeed() ^ (regionX * 4987142L + regionZ * 5947611L) ^ 0x5CE7L);
        // Where the group sits inside its region, and how many settlements it
        // runs to. Kept one cell clear of the region edge so two neighbouring
        // groups cannot merge into one long smear.
        int heartX = regionX * REGION_CELLS + 1 + r.nextInt(REGION_CELLS - 2);
        int heartZ = regionZ * REGION_CELLS + 1 + r.nextInt(REGION_CELLS - 2);
        int howMany = 3 + r.nextInt(3);                 // three to five
        // The candidates are the cell itself and its eight neighbours: a group
        // is a cluster, not a line. Which of the nine are used is drawn from
        // the same seeded sequence, so every chunk that asks gets the same
        // answer without anything being remembered between calls.
        int dx = cellX - heartX;
        int dz = cellZ - heartZ;
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1) return false;
        int[] order = { 0, 1, 2, 3, 4, 5, 6, 7, 8 };
        for (int i = order.length - 1; i > 0; i--) {    // seeded shuffle
            int j = r.nextInt(i + 1);
            int t = order[i]; order[i] = order[j]; order[j] = t;
        }
        int slot = (dz + 1) * 3 + (dx + 1);
        for (int i = 0; i < howMany; i++) {
            if (order[i] == slot) return true;
        }
        return false;
    }

    /**
     * Where this world will found natural villages near a spot: the anchor of
     * every cell within a few cells that belongs to a cluster, nearest first. The
     * ground still has to be liveable when its chunk loads. For the console
     * ("where do I walk to find one?") and for the tests, which have to put the
     * loaded ground where the world will look.
     */
    public static java.util.List<BlockPos> anchorsNear(ServerLevel level, BlockPos near, int cells) {
        int spacing = Math.max(256, AssistantConfig.villageSpacing());
        int cx = Math.floorDiv(near.getX(), spacing);
        int cz = Math.floorDiv(near.getZ(), spacing);
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (int dx = -cells; dx <= cells; dx++) {
            for (int dz = -cells; dz <= cells; dz++) {
                if (inACluster(level, cx + dx, cz + dz)) out.add(anchorFor(level, cx + dx, cz + dz, spacing));
            }
        }
        out.sort(java.util.Comparator.comparingDouble(a ->
            (double) (a.getX() - near.getX()) * (a.getX() - near.getX())
                + (double) (a.getZ() - near.getZ()) * (a.getZ() - near.getZ())));
        return out;
    }

    /** The candidate spot for a grid cell, fixed by the world seed. */
    private static BlockPos anchorFor(ServerLevel level, int cellX, int cellZ, int spacing) {
        RandomSource r = RandomSource.create(
            level.getSeed() ^ (cellX * 341873128712L + cellZ * 132897987541L));
        // Kept away from the cell edges so two neighbouring settlements can
        // never end up within shouting distance of each other.
        int inset = spacing / 4;
        int x = cellX * spacing + inset + r.nextInt(Math.max(1, spacing - inset * 2));
        int z = cellZ * spacing + inset + r.nextInt(Math.max(1, spacing - inset * 2));
        return new BlockPos(x, 0, z);
    }

    @Nullable
    static BlockPos groundAt(ServerLevel level, int x, int z) {
        // The height of the ground, not of a tree's crown — and of the live heightmap: a
        // chunk that is loaded has dropped its worldgen ones, and asking for those logs
        // an "Unprimed heightmap" error for every column (twenty in a natural-founding run).
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinBuildHeight() + 1) return null;
        return new BlockPos(x, y, z);
    }

    /**
     * Is this somewhere people could actually live? Dry land, above the tide,
     * and flat enough to build on — a village halfway up a cliff or standing
     * in a lake is not a village.
     */
    static boolean liveable(ServerLevel level, BlockPos ground) {
        if (ground.getY() < level.getSeaLevel()) return false;
        if (level.getBlockState(ground.below()).is(Blocks.WATER)) return false;
        if (level.getBlockState(ground.below()).isAir()) return false;
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int dx = -12; dx <= 12; dx += 6) {
            for (int dz = -12; dz <= 12; dz += 6) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    ground.getX() + dx, ground.getZ() + dz);
                lowest = Math.min(lowest, y);
                highest = Math.max(highest, y);
            }
        }
        return highest - lowest <= 8;
    }

    private static boolean folkNearby(ServerLevel level, BlockPos anchor) {
        return !level.getEntitiesOfClass(VillageFolkEntity.class,
            new AABB(anchor).inflate(Villages.VILLAGE_RANGE), e -> true).isEmpty();
    }

    /** Put a settlement here: a supply chest, some folk, and nothing else. */
    private static void found(ServerLevel level, BlockPos ground) {
        int min = Math.max(1, AssistantConfig.villageMinFolk());
        int max = Math.max(min, AssistantConfig.villageMaxFolk());
        RandomSource r = RandomSource.create(level.getSeed() ^ ground.asLong());
        int size = min + r.nextInt(max - min + 1);

        Villages.Village village = Villages.found(level, ground);
        supplyChest(level, ground);

        Set<String> used = new HashSet<>();
        for (int i = 0; i < size; i++) {
            double angle = (Math.PI * 2 / size) * i;
            int x = ground.getX() + (int) Math.round(Math.cos(angle) * 4);
            int z = ground.getZ() + (int) Math.round(Math.sin(angle) * 4);
            BlockPos spot = groundAt(level, x, z);
            if (spot == null) spot = ground;

            VillageFolkEntity folk = McAssistantMod.VILLAGE_FOLK.get().create(level);
            if (folk == null) continue;
            folk.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, r.nextFloat() * 360F, 0F);
            folk.rename(freshName(used));
            starterKit(folk);
            folk.joinVillage(village.id(), village.centre());
            level.addFreshEntity(folk);
            Villages.recordBirth(village.id());
            // Settling, choosing a trade and finding ground all happen on the
            // folk's own agenda within a few seconds of standing up.
        }

        // The settlement keeps its own chunks awake, so it grows while the
        // player is a thousand blocks away — which is the entire point of a
        // village that lives on the map rather than in front of you.
        // Sized to how far this many folk will actually stake their plots,
        // rather than to a fixed four — a founding of twelve already reaches
        // past four chunks, and a plot outside the ring is a plot nobody works
        // while you are away.
        ChunkLoad.setLoaded(level, village.id(), ground,
            loadedRadiusFor(Math.max(size, Villages.headcount(village.id()))), true);
    }

    /**
     * What a child born in the village is sent out with, which is deliberately
     * LESS than its parents spent raising it: two loaves against the four that
     * went in, so a settlement cannot breed its way to a full larder. Wooden
     * tools only — the village makes the better ones, the same as it makes
     * everything else — and a chest, because a plot fifty blocks from the stores
     * cannot borrow one and a hand with nowhere to put its output never starts
     * its trade at all.
     */
    public static void childKit(VillageFolkEntity folk) {
        // Wooden tools: a child that had to make its own pickaxe out of planks it
        // had to fetch from stores sixty blocks away stood at the heart "needing a
        // pickaxe" for a day — a third of the village's children at any moment.
        folk.insertItem(new ItemStack(Items.WOODEN_PICKAXE));
        folk.insertItem(new ItemStack(Items.WOODEN_AXE));
        folk.insertItem(new ItemStack(Items.WOODEN_SWORD));
        folk.insertItem(new ItemStack(Items.BREAD, 2));
        folk.insertItem(new ItemStack(Items.CHEST));
        folk.insertItem(new ItemStack(Items.CRAFTING_TABLE));
        folk.insertItem(new ItemStack(Items.WHEAT_SEEDS, 4));
    }

    /**
     * What a pair of hands needs to survive its first day and start work —
     * carried, not stored, because a folk's plot can be fifty blocks from the
     * founding chest and a bot only reaches the stores on its OWN ground.
     * Everything here answers a question the village cannot answer for it:
     * what do I eat, what do I work with, where do I put what I produce, and
     * what do I put in the ground.
     */
    public static void starterKit(VillageFolkEntity folk) {
        folk.insertItem(new ItemStack(Items.BREAD, 16));
        folk.insertItem(new ItemStack(Items.STONE_AXE));
        folk.insertItem(new ItemStack(Items.STONE_PICKAXE));
        folk.insertItem(new ItemStack(Items.STONE_SWORD));
        // A bench to carry. Half of what a village lives on is a three-by-three
        // recipe — bread off the wheat, a chest, a furnace, ladders — and a
        // farmer on a plot with no trees has nothing to make a bench FROM, so
        // wheat piled up in the chest and nobody ever ate any of it.
        folk.insertItem(new ItemStack(Items.CRAFTING_TABLE));
        // A chest of its own. The station brain plants this the first time it
        // has something to put away, which is what turns a claimed field into
        // a working one — without it the harvest has nowhere to go and the
        // whole trade jams on a full pack.
        folk.insertItem(new ItemStack(Items.CHEST));
        folk.insertItem(new ItemStack(Items.WHEAT_SEEDS, 6));
        // Roots are what a field is FOR: a wheat plant gives one ear and a few
        // seeds, a carrot or a potato plant gives three or so to eat, and each of
        // those is a plant again. A village that started with wheat alone was
        // out of bread on its third day, waiting on the first harvest.
        folk.insertItem(new ItemStack(Items.CARROT, 3));
        folk.insertItem(new ItemStack(Items.POTATO, 3));
        folk.insertItem(new ItemStack(Items.OAK_SAPLING, 4));
    }

    /**
     * The village's founding stores. Everything here is a thing you cannot
     * bootstrap out of bare ground in reasonable time — seed for the first
     * field, saplings so the wood does not run out, light, and the bench that
     * every other tool comes off. Deliberately not generous: no iron, no
     * furnace, no food beyond what they carry.
     */
    /** The founding stores. Left by a world-generated settlement, and now by a
     *  chartered one too — a village founded by hand was starting with no
     *  planks, no torches, no bench and no string, which made it materially
     *  poorer than one the world grew and left its fisher unable to ever build
     *  a rod. */
    public static void supplyChest(ServerLevel level, BlockPos ground) {
        // groundAt() returns the first free block ABOVE the surface, so the
        // chest belongs exactly there — putting it one higher again left every
        // village's founding stores hovering with a gap underneath.
        BlockPos at = ground;
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        // Named before anything goes in: the village's own stores, and the only
        // chests its folk will ever touch (see ZoneChests.MARK).
        com.jrpetty.mcassistant.entity.ZoneChests.mark(level, at);
        if (!(level.getBlockEntity(at) instanceof Container chest)) return;
        List<ItemStack> stores = List.of(
            new ItemStack(Items.WHEAT_SEEDS, 32),
            new ItemStack(Items.CARROT, 16),
            new ItemStack(Items.POTATO, 16),
            new ItemStack(Items.OAK_SAPLING, 16),
            new ItemStack(Items.TORCH, 32),
            new ItemStack(Items.CRAFTING_TABLE, 1),
            new ItemStack(Items.CHEST, 4),
            new ItemStack(Items.OAK_PLANKS, 48),
            // A storehouse is seventy-odd blocks. With planks alone the first
            // building waited on the woodcutters — a day or more on a map with few
            // trees, and on a plains map for ever. A stack of cobblestone to start
            // the walls means the first thing goes up in the first minutes.
            new ItemStack(Items.COBBLESTONE, 64),
            new ItemStack(Items.BREAD, 32),
            // Two lengths of string. Nothing a village does produces any, and
            // a rod is three sticks and two string — so without this the one
            // trade that feeds a settlement from water it already has could
            // never make the only tool it needs.
            new ItemStack(Items.STRING, 4));
        for (int i = 0; i < stores.size() && i < chest.getContainerSize(); i++) {
            chest.setItem(i, stores.get(i).copy());
        }
        chest.setChanged();
    }

    private static String freshName(Set<String> used) {
        for (String candidate : Names.POOL) {
            if (used.add(candidate.toLowerCase())) return candidate;
        }
        return "folk_" + (used.size() + 1);
    }
}
