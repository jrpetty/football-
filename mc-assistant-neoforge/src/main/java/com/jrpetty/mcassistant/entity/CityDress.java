package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [city9] Each family's house its own: the same drawing and the town's own woods, but not every roof and every frame
 * alike.
 *
 * <p>A town's houses all go up to one drawing out of one stock of wood, so a street of them was a row of the same
 * house. Once a house stands (and, in the Iron Age, once it has its second storey), the town's hands dress it once, by
 * where it stands, so that two neighbours are seldom the same:
 * <ul>
 * <li><b>its roof</b> kept as built (about one in three), laid again in another of the town's roofing woods (its
 *     look's list: Palettes) that the stores have to spare, or in stone, the slate look: cobblestone, and stone bricks
 *     from the Iron Age;</li>
 * <li><b>its frame</b> (the posts and beams) kept, stripped to the bare wood, or in a dark timber against the walls.</li>
 * </ul>
 * Paid for out of the stores in planks and logs of the wood (a stair is a plank and a half, a slab half), only while
 * they keep a builder's load of it besides; what comes off goes back into the stores. Nothing is done to a player's
 * house, a household's own house, the founders' first house, or a house going up a storey. Written in the town's books
 * once done, so a house is dressed once.
 */
public final class CityDress {

    private CityDress() {}

    private static final Map<UUID, Integer> CURSOR = new ConcurrentHashMap<>();
    /** The stores keep this many planks or logs of a wood besides. */
    static final int KEEP = 64;

    public static void resetForTests() {
        CURSOR.clear();
    }

    static long mix(BlockPos p, long salt) {
        long h = p.asLong() ^ salt;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    /** Which roof this house has: 0 as built, 1 another of the town's roofing woods, 2 stone. */
    public static int roofChoice(BlockPos anchor) {
        return (int) Math.floorMod(mix(anchor, 0x51ED), 3L);
    }

    /** Which frame: 0 as built, 1 stripped, 2 a dark timber. */
    public static int frameChoice(BlockPos anchor) {
        return (int) Math.floorMod(mix(anchor, 0xF4A3E), 3L);
    }

    static boolean house(String s) {
        return s.equals("house") || s.equals("house2");
    }

    /** One house a visit dressed, if it is due and the stores can pay. True if one was. */
    public static boolean tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Villages.ageOf(id).ordinal() < Villages.Age.STONE.ordinal()) return false;
        List<Ledger.Building> homes = new ArrayList<>();
        for (Ledger.Building b : Ledger.buildings(id)) if (house(b.structure())) homes.add(b);
        if (homes.isEmpty()) return false;
        int start = CURSOR.getOrDefault(id, 0);
        for (int k = 0; k < Math.min(6, homes.size()); k++) {
            Ledger.Building b = homes.get(Math.floorMod(start + k, homes.size()));
            int r = dress(level, v, b, false);
            if (r < 0) { CURSOR.put(id, start + k); return false; }          // its hand on the way: this house next visit
            if (r > 0) { CURSOR.put(id, start + k + 1); return true; }
        }
        CURSOR.put(id, start + Math.min(6, homes.size()));
        return false;
    }

    static String noteKey(Ledger.Building b) {
        return "city9.dressed." + b.anchor().getX() + "," + b.anchor().getY() + "," + b.anchor().getZ();
    }

    /** The drawing the house stands to now. */
    static String drawing(UUID id, Ledger.Building b) {
        return b.structure().equals("house2") || Ledger.grown(id, b.anchor()) ? "house2" : "house";
    }

    /**
     * Dress one house: 1 done, 0 nothing to do (or the stores cannot pay yet), -1 waiting for a hand. {@code free}: the
     * tests and the pictures, out of nothing and with no hand.
     */
    public static int dress(ServerLevel level, Villages.Village v, Ledger.Building b, boolean free) {
        UUID id = v.id();
        if (Ledger.note(id, noteKey(b)) != null) return 0;
        if (!Land.areaLoaded(level, b.anchor(), 7)) return 0;
        if (HousingMarket.isPrivate(id, b.anchor()) || Beliefs.untouchable(id, b) || Grow.raisingNow(id, b.anchor())) return 0;
        if (PlayerBlocks.placed(level, b.anchor())) return 0;
        String drawing = drawing(id, b);
        boolean iron = Villages.ageOf(id).ordinal() >= Villages.Age.IRON.ordinal();
        if (iron && drawing.equals("house") && !free) return 0;                 // its second storey first: the roof comes off for it
        int roofChoice = roofChoice(b.anchor());
        // The roof's make: 1, a wood of the town's roofing list other than the house's own that the stores can spare;
        // 2, stone (cobblestone, stone bricks from the Iron Age), the slate look of the better streets.
        String roofWood = null;
        if (roofChoice == 1) {
            roofWood = altWood(level, v, b, free);
            if (roofWood == null && !free) return 0;                          // none to spare yet: later
        } else if (roofChoice == 2) {
            roofWood = iron ? "stone_brick" : "cobblestone";
        }
        int frame = frameChoice(b.anchor());
        List<BuildGoal.Placement> roof = new ArrayList<>(), posts = new ArrayList<>();
        for (BuildGoal.Placement p : BuildGoal.plan(drawing, b.anchor(), b.facing(), 13)) {
            if (p.part() != BuildGoal.Part.BLOCK) continue;
            switch (p.style()) {
                case ROOF_STAIR, ROOF_STAIR_TOP, ROOF_SLAB, ROOF_SLAB_TOP -> roof.add(p);
                case POST, BEAM_ACROSS, BEAM_ALONG -> posts.add(p);
                default -> { }
            }
        }
        // What each cell becomes.
        List<BlockPos> at = new ArrayList<>();
        List<BlockState> put = new ArrayList<>();
        int stairs = 0, slabs = 0, logs = 0;
        String logWood = null;
        if (roofWood != null) {
            Block stair = block(roofWood + "_stairs"), slab = block(roofWood + "_slab");
            for (BuildGoal.Placement p : roof) {
                BlockState st = level.getBlockState(p.pos());
                if (st.getBlock() instanceof StairBlock && stair != null && st.getBlock() != stair) {
                    at.add(p.pos()); put.add(copy(st, stair.defaultBlockState())); stairs++;
                } else if (st.getBlock() instanceof SlabBlock && slab != null && st.getBlock() != slab && st.getFluidState().isEmpty()) {
                    at.add(p.pos()); put.add(copy(st, slab.defaultBlockState())); slabs++;
                }
            }
        }
        if (frame > 0) {
            for (BuildGoal.Placement p : posts) {
                BlockState st = level.getBlockState(p.pos());
                if (!(st.getBlock() instanceof RotatedPillarBlock) || !st.is(net.minecraft.tags.BlockTags.LOGS)) continue;
                String wood = woodOf(st.getBlock());
                if (wood == null) continue;
                Block to = frame == 1 ? block("stripped_" + wood.replace("stripped_", "") + "_log")
                    : block(wood.contains("dark_oak") ? "spruce_log" : "dark_oak_log");
                if (to == null || to == st.getBlock()) continue;
                logWood = frame == 1 ? wood.replace("stripped_", "") : (wood.contains("dark_oak") ? "spruce" : "dark_oak");
                at.add(p.pos()); put.add(copy(st, to.defaultBlockState())); logs++;
            }
        }
        if (at.isEmpty()) {
            Ledger.note(id, noteKey(b), "kept");
            return 0;
        }
        int planks = (stairs * 3 + 1) / 2 + (slabs + 1) / 2;
        Item plankItem = roofWood == null ? null : roofWood.equals("stone_brick") ? Items.STONE_BRICKS
            : roofWood.equals("cobblestone") ? Items.COBBLESTONE : item(roofWood + "_planks");
        Item logItem = logWood == null ? null : item(logWood + "_log");
        if (!free) {
            if (planks > 0 && (plankItem == null || Market.stock(level, id, s -> s.is(plankItem)) < planks + KEEP)) return 0;
            if (logs > 0 && (logItem == null || Market.stock(level, id, s -> s.is(logItem)) < logs + KEEP)) return 0;
            for (BlockPos p : at) if (!TownBlocks.allows(level, v, p, TownBlocks.Why.RENOVATE)) return 0;
            // The gardens' hand (it is at the houses already): a crew of its own went without a hand in a town of ninety.
            if (!TownJobs.atWork(level, v, "gardens", b.anchor(), "giving a house its own roof and timbers")) return -1;
            if (planks > 0 && !Crafts.take(level, v, s -> s.is(plankItem), planks)) return 0;
            if (logs > 0 && !Crafts.take(level, v, s -> s.is(logItem), logs)) {
                if (planks > 0) Crafts.store(level, v, new ItemStack(plankItem, planks));
                return 0;
            }
        }
        for (int i = 0; i < at.size(); i++) {
            BlockState was = level.getBlockState(at.get(i));
            level.setBlock(at.get(i), put.get(i), 2 | 16);
            TownBlocks.traced(v, at.get(i), TownBlocks.Why.RENOVATE);
            if (!free) {
                Item back = was.getBlock().asItem();
                if (back != Items.AIR) Crafts.store(level, v, new ItemStack(back));
            }
        }
        Ledger.note(id, noteKey(b), roofWood == null ? "frame" : roofWood);
        return 1;
    }

    /** The first wood of the town's roofing list that is not this house's roof and that the stores can spare enough
     *  planks of (any, for the tests' free dressing); null if none. */
    @Nullable
    static String altWood(ServerLevel level, Villages.Village v, Ledger.Building b, boolean free) {
        String own = null;
        for (BuildGoal.Placement p : BuildGoal.plan(drawing(v.id(), b), b.anchor(), b.facing(), 13)) {
            if (p.style() != Blueprints.Style.ROOF_STAIR) continue;
            String path = BuiltInRegistries.BLOCK.getKey(level.getBlockState(p.pos()).getBlock()).getPath();
            if (path.endsWith("_stairs")) { own = path.substring(0, path.length() - 7); break; }
        }
        for (int k = 0; k < 6; k++) {
            String w = roofWood(v.id(), k);
            if (w == null || w.equals(own)) continue;
            if (free) return w;
            Item planks = item(w + "_planks");
            if (planks != null && Market.stock(level, v.id(), s -> s.is(planks)) >= 40 + KEEP) return w;
        }
        return null;
    }

    /** The town's roofing wood for a choice: null to keep what the house was built in. */
    @Nullable
    static String roofWood(UUID id, int choice) {
        List<String> woods = new ArrayList<>();
        for (Item it : Palettes.ranked(id, Blueprints.Style.ROOF_STAIR)) {
            String path = BuiltInRegistries.ITEM.getKey(it).getPath();
            if (path.endsWith("_stairs") && block(path.replace("_stairs", "_planks")) != null) {
                String w = path.substring(0, path.length() - "_stairs".length());
                if (block(w + "_slab") != null && !woods.contains(w)) woods.add(w);
            }
        }
        return choice < woods.size() ? woods.get(choice) : null;
    }

    @Nullable
    static String woodOf(Block log) {
        String path = BuiltInRegistries.BLOCK.getKey(log).getPath();
        return path.endsWith("_log") ? path.substring(0, path.length() - 4) : null;
    }

    @Nullable
    static Block block(String path) {
        ResourceLocation rl = ResourceLocation.withDefaultNamespace(path);
        return BuiltInRegistries.BLOCK.containsKey(rl) ? BuiltInRegistries.BLOCK.get(rl) : null;
    }

    @Nullable
    static Item item(String path) {
        ResourceLocation rl = ResourceLocation.withDefaultNamespace(path);
        return BuiltInRegistries.ITEM.containsKey(rl) ? BuiltInRegistries.ITEM.get(rl) : null;
    }

    /** The new block in the old one's shape: its facing, its half, its axis, its corner. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static BlockState copy(BlockState from, BlockState to) {
        for (Property p : from.getProperties()) {
            if (to.hasProperty(p)) to = to.setValue(p, from.getValue(p));
        }
        return to;
    }
}
