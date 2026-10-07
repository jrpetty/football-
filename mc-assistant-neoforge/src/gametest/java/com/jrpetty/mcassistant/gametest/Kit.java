package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.VillagerTakeover;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the village tests share: a clean slate, a piece of terrain that
 * looks like somewhere a village could live, a way to read the world back, and
 * one-line-per-folk reporting. The reporting is the point. A test that only
 * says "failed" is no use for a system this large; one that prints what every
 * hand was doing and what the world looked like says where it stopped.
 */
final class Kit {

    private Kit() {}

    static final Logger LOG = LogUtils.getLogger();

    static void log(String line) {
        LOG.info("[VT] {}", line);
    }

    // ---------------------------------------------------------------- reset

    /** A clean slate. Tests share one JVM, so they share every static. */
    static void reset(ServerLevel level) {
        VillagerTakeover.resetForTests();
        List<Entity> doomed = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof AssistantEntity || e instanceof Villager) doomed.add(e);
        }
        for (Entity e : doomed) e.discard();
        Villages.resetForTests();
        AssistantEntity.resetRegistryForTests();
        com.jrpetty.mcassistant.entity.MineStairs.resetForTests(level);
        com.jrpetty.mcassistant.entity.DoorWays.resetForTests();
        // What the town's works build is checked here at once; who builds it, by hand, in t54.
        com.jrpetty.mcassistant.entity.TownJobs.instantForTests(true);
    }

    /**
     * [watch-clears] No stand-in players left over from earlier tests. A mock player a test puts on the server
     * (makeMockServerPlayerInLevel) stays on its list for the rest of the run, its stand-in connection never closed;
     * and with any player in the world, however far off, the game despawns at once every monster not kept on
     * purpose. sw03's spawn-egg phantom went at its first tick, the run after the teleport tests came in.
     */
    static void noLeftoverPlayers(ServerLevel level) {
        var list = level.getServer().getPlayerList();
        for (net.minecraft.server.level.ServerPlayer p : new ArrayList<>(list.getPlayers())) {
            if ("test-mock-player".equals(p.getGameProfile().getName())) list.remove(p);
        }
    }

    // -------------------------------------------------------------- terrain

    /** The free block above the highest solid one at this column. */
    static BlockPos surface(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    /** Keep every chunk in this square loaded and ticking for the whole test.
     *  Tests run far from anyone, so without this the ground under a folk is
     *  unloaded a moment after it is made and the test measures the chunk
     *  system, not the village. */
    static void hold(ServerLevel level, int cx, int cz, int radius) {
        for (int x = (cx - radius) >> 4; x <= (cx + radius) >> 4; x++) {
            for (int z = (cz - radius) >> 4; z <= (cz + radius) >> 4; z++) {
                level.setChunkForced(x, z, true);
            }
        }
    }

    /** Make sure every chunk in this square exists before anyone stands on it. */
    static void prepare(ServerLevel level, int cx, int cz, int radius) {
        for (int x = (cx - radius) >> 4; x <= (cx + radius) >> 4; x++) {
            for (int z = (cz - radius) >> 4; z <= (cz + radius) >> 4; z++) {
                level.getChunk(x, z);
            }
        }
    }

    /**
     * Every chunk of this held square live, here and now: a mob set down on it is seen (level.getEntity,
     * getEntitiesOfClass) and moves from its first tick. A chunk held and prepared this tick is there at once, but its
     * creatures only come alive once the ground two chunks round it is made as well, and the game finishes that in
     * its own time: on the square's outer chunks (the ground beyond them not yet made) it took a hundred ticks and
     * more. Till then a mob set down there is in the world and in nobody's sight, and comes into it all at once when
     * the ground is done. So: the chunk system worked through now, as a sync chunk load works it, till the whole
     * square is live, for ten seconds at most. Whether it is.
     */
    static boolean live(ServerLevel level, int cx, int cz, int radius) {
        long until = System.nanoTime() + 10_000_000_000L;
        while (true) {
            if (notLive(level, cx, cz, radius) == 0) return true;
            if (System.nanoTime() > until) return false;
            if (!level.getChunkSource().pollTask()) {
                Thread.yield();
                java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
            }
        }
    }

    /** How many chunks of this square are not yet live (their creatures not seen, or not ticking). */
    static int notLive(ServerLevel level, int cx, int cz, int radius) {
        int n = 0;
        for (int x = (cx - radius) >> 4; x <= (cx + radius) >> 4; x++) {
            for (int z = (cz - radius) >> 4; z <= (cz + radius) >> 4; z++) {
                if (!level.isPositionEntityTicking(new BlockPos(x << 4, 0, z << 4))) n++;
            }
        }
        return n;
    }

    /** Water at ground level: the grass under it becomes the pond. */
    static void pond(ServerLevel level, int cx, int cz, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                BlockPos top = surface(level, cx + dx, cz + dz).below();
                level.setBlock(top, Blocks.WATER.defaultBlockState(), 3);
            }
        }
    }

    /** A five-log oak with a proper crown. Leaves are persistent so nothing decays. */
    static void tree(ServerLevel level, int x, int z) {
        BlockPos base = surface(level, x, z);
        for (int i = 0; i < 5; i++) {
            level.setBlock(base.above(i), Blocks.OAK_LOG.defaultBlockState(), 3);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 3; dy <= 5; dy++) {
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                    if (dx == 0 && dz == 0 && dy < 5) continue;
                    level.setBlock(base.offset(dx, dy, dz),
                        Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), 3);
                }
            }
        }
    }

    /** A tree the way the world grows one: its leaves are not persistent, so they are
     *  what a builder taking a tree down is looking for. */
    static void wildTree(ServerLevel level, int x, int z) {
        BlockPos base = surface(level, x, z);
        for (int i = 0; i < 5; i++) {
            level.setBlock(base.above(i), Blocks.OAK_LOG.defaultBlockState(), 3);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 3; dy <= 5; dy++) {
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                    if (dx == 0 && dz == 0 && dy < 5) continue;
                    level.setBlock(base.offset(dx, dy, dz),
                        Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false), 3);
                }
            }
        }
    }

    static void forest(ServerLevel level, int cx, int cz, int spread, int trees, long seed) {
        RandomSource rnd = RandomSource.create(seed);
        for (int i = 0; i < trees; i++) {
            tree(level, cx - spread + rnd.nextInt(spread * 2 + 1), cz - spread + rnd.nextInt(spread * 2 + 1));
        }
    }

    /**
     * A stone hill with coal and iron in it — somewhere a shaft could go. A
     * MOUND, not a block: the sides slope by less than a block a step, the way
     * a real hillside does, so a miner can walk up it and dig down from the
     * top. The first version was a sheer-sided slab fourteen blocks high that
     * nobody could climb, which tested nothing about mining except that a
     * miner cannot dig a hole in flat ground beside a cliff.
     */
    static void hill(ServerLevel level, int cx, int cz, int half, int height, long seed) {
        RandomSource rnd = RandomSource.create(seed);
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                int h = (int) Math.floor(height * (1.0 - ring / (double) (half + 1)));
                if (h <= 0) continue;
                BlockPos g = surface(level, cx + dx, cz + dz);
                for (int i = 0; i < h; i++) {
                    double r = rnd.nextDouble();
                    Block b = r < 0.05 ? Blocks.COAL_ORE : r < 0.08 ? Blocks.IRON_ORE : Blocks.STONE;
                    level.setBlock(g.above(i), b.defaultBlockState(), 3);
                }
            }
        }
    }

    static void cows(ServerLevel level, int cx, int cz, int n) {
        for (int i = 0; i < n; i++) {
            Animal cow = EntityType.COW.create(level);
            if (cow == null) continue;
            BlockPos p = surface(level, cx + (i % 3) * 2, cz + (i / 3) * 2);
            cow.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0, 0);
            level.addFreshEntity(cow);
        }
    }

    /**
     * Somewhere plausible to live: a ring of features round the heart — ponds
     * with grass, woods, stone hills — with cattle. Deliberately generous:
     * this tests whether the folk can WORK when the ground is right, so that
     * when they do not, the reason cannot be the ground.
     */
    static void generousTerrain(ServerLevel level, int cx, int cz) {
        prepare(level, cx, cz, 130);
        // Just outside the town (village/TownPlan keeps its first block of lots, forty-one
        // blocks out, for streets and houses), so an early folk finds something at once.
        pond(level, cx + 62, cz + 4, 3);
        forest(level, cx - 64, cz - 6, 7, 6, 11);
        // The ring: farms, woods, hills, on every bearing.
        int r = 72;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            int x = cx + (int) Math.round(Math.cos(a) * r);
            int z = cz + (int) Math.round(Math.sin(a) * r);
            switch (i % 3) {
                case 0 -> pond(level, x, z, 3);
                case 1 -> forest(level, x, z, 8, 7, 100 + i);
                default -> hill(level, x, z, 14, 12, 200 + i);
            }
        }
        cows(level, cx + 10, cz + 60, 6);
    }

    // ---------------------------------------------------------------- reading

    /** What the world holds, counted from the blocks. */
    static Map<String, Integer> census(ServerLevel level, int cx, int cz, int radius) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String k : new String[]{"farmland", "wheat", "logs", "stone", "planks", "cobble",
                                     "chests", "furnaces", "beds", "tables", "doors", "torches"}) m.put(k, 0);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight(), maxY = minY + 70;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                for (int y = minY; y < maxY; y++) {
                    var st = level.getBlockState(p.set(x, y, z));
                    if (st.isAir()) continue;
                    Block b = st.getBlock();
                    if (b == Blocks.FARMLAND) bump(m, "farmland");
                    else if (b == Blocks.WHEAT) bump(m, "wheat");
                    else if (st.is(BlockTags.LOGS)) bump(m, "logs");
                    else if (b == Blocks.STONE) bump(m, "stone");
                    else if (st.is(BlockTags.PLANKS)) bump(m, "planks");
                    else if (b == Blocks.COBBLESTONE) bump(m, "cobble");
                    else if (b == Blocks.CHEST) bump(m, "chests");
                    else if (b == Blocks.FURNACE) bump(m, "furnaces");
                    else if (st.is(BlockTags.BEDS)) bump(m, "beds");
                    else if (b == Blocks.CRAFTING_TABLE) bump(m, "tables");
                    else if (st.is(BlockTags.DOORS)) bump(m, "doors");
                    else if (b == Blocks.TORCH || b == Blocks.WALL_TORCH) bump(m, "torches");
                }
            }
        }
        return m;
    }

    private static void bump(Map<String, Integer> m, String k) {
        m.merge(k, 1, Integer::sum);
    }

    /** What is sitting in the chests, by kind. */
    static Map<String, Integer> chestContents(ServerLevel level, int cx, int cz, int radius) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int chunkX = (cx - radius) >> 4; chunkX <= (cx + radius) >> 4; chunkX++) {
            for (int chunkZ = (cz - radius) >> 4; chunkZ <= (cz + radius) >> 4; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) continue;
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                    if (!(be instanceof Container c)) continue;
                    if (be instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) continue;
                    for (int i = 0; i < c.getContainerSize(); i++) {
                        ItemStack s = c.getItem(i);
                        if (s.isEmpty()) continue;
                        m.merge(kind(s), s.getCount(), Integer::sum);
                    }
                }
            }
        }
        return m;
    }

    private static String kind(ItemStack s) {
        if (s.is(Items.BREAD) || s.get(DataComponents.FOOD) != null) return "food";
        if (s.is(ItemTags.LOGS)) return "logs";
        if (s.is(ItemTags.PLANKS)) return "planks";
        if (s.is(Items.COBBLESTONE) || s.is(Items.STONE) || s.is(Items.COBBLED_DEEPSLATE)) return "stone";
        if (s.is(Items.COAL) || s.is(Items.CHARCOAL)) return "coal";
        if (s.is(Items.RAW_IRON) || s.is(Items.IRON_INGOT)) return "iron";
        if (s.is(Items.WHEAT)) return "wheat";
        if (s.is(Items.WHEAT_SEEDS)) return "seeds";
        if (s.is(ItemTags.SAPLINGS)) return "saplings";
        Item i = s.getItem();
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i).getPath();
    }

    /** The whole picture, one line per folk. */
    static void dashboard(ServerLevel level, BlockPos heart, String label) {
        Villages.Village v = Villages.nearest(level, heart, 600);
        StringBuilder sb = new StringBuilder("== ").append(label).append(" @tick ")
            .append(level.getGameTime()).append(" day=").append(level.getDayTime() % 24000L);
        if (v == null) {
            log(sb.append(" — NO VILLAGE").toString());
            return;
        }
        List<AssistantEntity> crew = Villages.folkOf(v.id());
        sb.append(": ").append(crew.size()).append(" alive of ").append(Villages.headcount(v.id()))
          .append(", ").append(Villages.ageOf(v.id()).label)
          .append(", built=").append(Villages.builtList(v.id()));
        log(sb.toString());
        log("   world " + census(level, heart.getX(), heart.getZ(), 90));
        log("   chests " + chestContents(level, heart.getX(), heart.getZ(), 90));
        for (Villages.Need n : Villages.needs(level, v.id())) {
            log("   short of " + n.what() + " (" + n.task() + " x" + n.amount() + ")");
        }
        for (AssistantEntity a : crew) log("   " + a.debugLine());
    }

    // --------------------------------------------------------------- commands

    /** Run a command and hand back everything it said. */
    static List<String> command(ServerLevel level, String text) {
        List<String> said = new ArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override public void sendSystemMessage(Component c) { said.add(c.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        CommandSourceStack src = level.getServer().createCommandSourceStack()
            .withSource(sink).withLevel(level).withPosition(Vec3.ZERO);
        level.getServer().getCommands().performPrefixedCommand(src, text);
        return said;
    }

    // ------------------------------------------------------------ expectations

    /** Collects what should be true, says which is not, and fails once at the end. */
    static final class Expect {
        private final List<String> failures = new ArrayList<>();

        void that(boolean ok, String what) {
            log((ok ? "  ok    " : "  FAIL  ") + what);
            if (!ok) failures.add(what);
        }

        String summary() {
            return failures.isEmpty() ? "" : failures.size() + " expectation(s) failed: "
                + String.join(" | ", failures);
        }

        boolean clean() { return failures.isEmpty(); }
    }
}
