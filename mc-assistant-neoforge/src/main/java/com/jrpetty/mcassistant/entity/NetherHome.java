package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [nether] What the Nether runs come to at home.
 *
 * <ul>
 * <li><b>The wart farm</b> (tend): eight blocks of the soul sand the runners bring home, laid in two rows by the
 *     brewery (or by the gateway with no brewery yet), nether wart planted in it out of the stores, and the ripe wart
 *     picked (the game's own drops: two to four a plant) and planted again. The brewer keeps it (Crafts.brew), and with no
 *     brewer a farmer. Once four plants grow, the runners need bring home only a little wart for seed (NetherPlan).</li>
 * <li><b>Fire resistance</b>: the brewer keeps two potions a runner a day out (Crafts.brew's list), and makes its magma
 *     cream when it has none (magmaCream: a blaze powder ground from the runners' rods and a slime ball, the game's own
 *     recipe): the potion that sends the runners back for more rods.</li>
 * <li><b>Glowstone lamps</b>: in the Nether Age, the street lamps are glowstone (four of the runners' dust to a block)
 *     where the stores have it (lampLight, from TownWork).</li>
 * <li><b>Quartz</b>: the Nether Age's great buildings get footings of quartz brick from the runners' quartz (Ages).</li>
 * <li><b>Trophies</b> (broughtHome): the first of each rare thing the runners bring home (a ghast's tear, a wither
 *     skeleton's skull, ancient debris, a blaze rod, magma cream, crying obsidian, an ender pearl) is noted with who
 *     brought it, and goes up in an item frame on the gateway's own frame, out of the stores (never the last), named for
 *     who brought it. The museum's curator puts the rarest on show as well (Museum: the skull, the tear).</li>
 * </ul>
 */
public final class NetherHome {

    private NetherHome() {}

    /** The wart farm's blocks of soul sand. */
    static final int FARM = 8;
    /** The frames of trophies on the gateway (two up each side of the portal). */
    static final int TROPHIES = 4;
    /** The rare things the runners bring home that go up as trophies, the rarest first. */
    static final List<Item> RARE = List.of(Items.WITHER_SKELETON_SKULL, Items.ANCIENT_DEBRIS, Items.GHAST_TEAR, Items.CRYING_OBSIDIAN,
        Items.ENDER_PEARL, Items.BLAZE_ROD, Items.MAGMA_CREAM, Items.NETHER_WART);
    /** The tag on the gateway's trophy frames. */
    static final String TAG = "mca_nether_trophy";

    public static void resetForTests() {
        // Kept with the town (Ledger).
    }

    // ------------------------------------------------------------------ the wart farm

    /** The farm's soul sand, as laid out (kept with the town), or an empty list. */
    static List<BlockPos> farm(UUID village) {
        List<BlockPos> out = new ArrayList<>();
        String s = Ledger.note(village, "nether.farm");
        if (s == null || s.isEmpty()) return out;
        for (String p : s.split(";")) {
            String[] c = p.split(",");
            if (c.length < 3) continue;
            try {
                out.add(new BlockPos(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])));
            } catch (NumberFormatException ignored) {
                // left out
            }
        }
        return out;
    }

    /** How many wart plants the farm has growing (as last tended). */
    public static int wartPlants(@Nullable UUID village) {
        String s = village == null ? null : Ledger.note(village, "nether.wartplants");
        try {
            return s == null || s.isEmpty() ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** How much soul sand the town wants (NetherPlan): the farm's eight, less what is laid. */
    public static int farmWants(@Nullable UUID village) {
        if (village == null) return 0;
        String s = Ledger.note(village, "nether.farmlaid");
        int laid = 0;
        try {
            laid = s == null || s.isEmpty() ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            // none
        }
        return Math.max(0, FARM - laid);
    }

    /** Where the farm goes: two rows of four on flat open ground near the brewery, or the gateway. Kept once found. */
    @Nullable
    static List<BlockPos> plan(ServerLevel level, Villages.Village v) {
        List<BlockPos> had = farm(v.id());
        if (!had.isEmpty()) return had;
        BlockPos near = Villages.builtAt(v.id(), "brewery");
        if (near == null) near = Villages.builtAt(v.id(), "gateway");
        if (near == null || !Land.areaLoaded(level, near, 12)) return null;
        for (int ring = 4; ring <= 10; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    List<BlockPos> cells = cells(level, v.id(), near.getX() + dx, near.getZ() + dz, near.getY());
                    if (cells == null) continue;
                    StringBuilder sb = new StringBuilder();
                    for (BlockPos c : cells) sb.append(sb.length() == 0 ? "" : ";").append(c.getX()).append(',').append(c.getY()).append(',').append(c.getZ());
                    Ledger.note(v.id(), "nether.farm", sb.toString());
                    return cells;
                }
            }
        }
        return null;
    }

    /** Two rows of four from here, if the ground is flat, open, dirt or grass, and in nobody's building; else null. */
    @Nullable
    static List<BlockPos> cells(ServerLevel level, UUID village, int x0, int z0, int nearY) {
        List<BlockPos> out = new ArrayList<>();
        int y = -1;
        for (int a = 0; a < 4; a++) {
            for (int b = 0; b < 2; b++) {
                int x = x0 + a, z = z0 + b;
                int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (y < 0) y = gy;
                if (gy != y || Math.abs(gy - nearY) > 3) return null;
                BlockPos g = new BlockPos(x, gy, z);
                BlockState gs = level.getBlockState(g);
                if (!(gs.is(Blocks.GRASS_BLOCK) || gs.is(Blocks.DIRT) || gs.is(Blocks.COARSE_DIRT) || gs.is(Blocks.PODZOL) || gs.is(Blocks.SOUL_SAND))) return null;
                if (!level.getBlockState(g.above()).isAir() && !level.getBlockState(g.above()).is(Blocks.NETHER_WART)) return null;
                if (Land.inABuilding(village, g) || Land.inABuilding(village, g.above())) return null;
                out.add(g);
            }
        }
        return out;
    }

    /**
     * A step of the wart farm's keeping (the brewer's, Crafts.brew; a farmer's with no brewer): soul sand laid, ripe wart
     * picked into the stores and planted again, bare soul sand planted. Returns what it did, or null.
     */
    @Nullable
    public static String tend(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        UUID id = v.id();
        if (Villages.ageOf(id) != Villages.Age.NETHER && Crafts.stock(level, v, s -> s.is(Items.SOUL_SAND)) == 0 && farm(id).isEmpty()) return null;
        List<BlockPos> cells = plan(level, v);
        if (cells == null || cells.isEmpty() || !level.isLoaded(cells.get(0))) return null;
        String did = null;
        int laid = 0, plants = 0;
        for (BlockPos g : cells) {
            BlockState gs = level.getBlockState(g);
            if (!gs.is(Blocks.SOUL_SAND)) {
                if (did == null && Crafts.take(level, v, s -> s.is(Items.SOUL_SAND), 1)) {
                    level.setBlockAndUpdate(g, Blocks.SOUL_SAND.defaultBlockState());
                    Economy.usedGiven(f, new ItemStack(Items.SOUL_SAND), 1);
                    did = "soul sand laid for the wart farm";
                    laid++;
                }
                continue;
            }
            laid++;
            BlockState w = level.getBlockState(g.above());
            if (w.is(Blocks.NETHER_WART)) {
                plants++;
                if (did == null && w.getValue(NetherWartBlock.AGE) >= 3) {
                    List<ItemStack> drops = Block.getDrops(w, level, g.above(), null, f, ItemStack.EMPTY);
                    level.setBlockAndUpdate(g.above(), Blocks.NETHER_WART.defaultBlockState());
                    int n = 0;
                    for (ItemStack d : drops) n += d.getCount();
                    n = Math.max(0, n - 1);                                      // one planted back
                    if (n > 0) {
                        ItemStack wart = new ItemStack(Items.NETHER_WART, n);
                        Economy.produced(f, wart.copy());
                        Crafts.store(level, v, wart);
                    }
                    f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    did = n + " nether wart picked from the wart farm";
                }
                continue;
            }
            if (did == null && w.isAir() && Crafts.take(level, v, s -> s.is(Items.NETHER_WART), 1)) {
                level.setBlockAndUpdate(g.above(), Blocks.NETHER_WART.defaultBlockState());
                plants++;
                did = "a nether wart planted in the wart farm";
            }
        }
        Ledger.note(id, "nether.farmlaid", Integer.toString(laid));
        Ledger.note(id, "nether.wartplants", Integer.toString(plants));
        if (did != null && f.blockPosition().distSqr(cells.get(0)) > 8 * 8 && f.getNavigation().isDone()) f.walkTo(cells.get(0), 0.9D);
        return did;
    }

    /** Tests: where the wart farm is laid out, or null. */
    @Nullable
    public static List<BlockPos> farmForTests(ServerLevel level, Villages.Village v) {
        return plan(level, v);
    }

    // ------------------------------------------------------------------ fire resistance

    /**
     * [nether] The brewer's magma cream for fire resistance when it has none (Crafts.reagent): a blaze powder (ground from
     * the runners' rods, one kept for the stand's fire) and a slime ball, by the game's own recipe. With use, made and
     * used. True if it has, or could make, one.
     */
    public static boolean magmaCream(ServerLevel level, Villages.Village v, boolean use) {
        boolean powder = Crafts.stock(level, v, s -> s.is(Items.BLAZE_POWDER)) >= 2
            || Crafts.stock(level, v, s -> s.is(Items.BLAZE_ROD)) >= 1;
        if (!powder || Crafts.stock(level, v, s -> s.is(Items.SLIME_BALL)) < 1) return false;
        if (!use) return true;
        if (Crafts.stock(level, v, s -> s.is(Items.BLAZE_POWDER)) < 2) {
            if (!Crafts.take(level, v, s -> s.is(Items.BLAZE_ROD), 1)) return false;
            Crafts.store(level, v, new ItemStack(Items.BLAZE_POWDER, 2));
        }
        return Crafts.take(level, v, s -> s.is(Items.BLAZE_POWDER), 1) && Crafts.take(level, v, s -> s.is(Items.SLIME_BALL), 1);
    }

    /** How many potions of fire resistance the brewer keeps: two, or two a runner and a day's spare for a rescue. */
    public static int fireResistanceKept(@Nullable UUID village) {
        return Math.max(2, NetherRunners.runners(village).size() * NetherPlan.POTIONS + 2);
    }

    // ------------------------------------------------------------------ glowstone lamps

    /** Could the stores light a street lamp with glowstone (the Nether Age; a block, or four of the runners' dust)? */
    public static boolean canLamp(ServerLevel level, Villages.Village v) {
        if (Villages.ageOf(v.id()) != Villages.Age.NETHER) return false;
        return Crafts.stock(level, v, s -> s.is(Items.GLOWSTONE)) > 0 || Crafts.stock(level, v, s -> s.is(Items.GLOWSTONE_DUST)) >= 4 + 8;
    }

    /** A glowstone lamp's light out of the stores (a block, or four dust made into one; eight dust kept for the brewer),
     *  or null. */
    @Nullable
    public static Block lampLight(ServerLevel level, Villages.Village v) {
        if (!canLamp(level, v)) return null;
        if (Crafts.take(level, v, s -> s.is(Items.GLOWSTONE), 1)) return Blocks.GLOWSTONE;
        return Crafts.take(level, v, s -> s.is(Items.GLOWSTONE_DUST), 4) ? Blocks.GLOWSTONE : null;
    }

    // ------------------------------------------------------------------ trophies

    /** What the runners brought home, noted for the trophies: the first of each rare thing, who brought it, when. */
    static void broughtHome(UUID village, ItemStack s, String who, long day) {
        if (s.isEmpty() || !RARE.contains(s.getItem())) return;
        Map<String, String> got = trophies(village);
        String key = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        if (got.containsKey(key)) return;
        got.put(key, who.replace('|', '/') + "|" + day);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : got.entrySet()) sb.append(sb.length() == 0 ? "" : "\n").append(e.getKey()).append('|').append(e.getValue());
        Ledger.note(village, "nether.trophies", sb.toString());
    }

    /** The trophies noted: item key to "who|day", in the order they came home. */
    public static Map<String, String> trophies(UUID village) {
        Map<String, String> out = new LinkedHashMap<>();
        String s = Ledger.note(village, "nether.trophies");
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            int bar = line.indexOf('|');
            if (bar > 0) out.put(line.substring(0, bar), line.substring(bar + 1));
        }
        return out;
    }

    /** The trophy spots on the gateway: on the town's face of its frame's two columns, one and two up from the ground. */
    static List<BlockPos> trophySpots(ServerLevel level, Villages.Village v) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos portal = NetherRuns.gatePortal(level, v.id());
        if (portal == null) return out;
        BlockState st = level.getBlockState(portal);
        Direction.Axis axis = st.hasProperty(net.minecraft.world.level.block.NetherPortalBlock.AXIS)
            ? st.getValue(net.minecraft.world.level.block.NetherPortalBlock.AXIS) : Direction.Axis.X;
        Direction along = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        Direction front = NetherRuns.faceToward(st, portal, v.centre());
        BlockPos bottom = portal;
        while (level.getBlockState(bottom.below()).is(Blocks.NETHER_PORTAL)) bottom = bottom.below();
        BlockPos left = bottom, right = bottom;
        while (level.getBlockState(left.relative(along.getOpposite())).is(Blocks.NETHER_PORTAL)) left = left.relative(along.getOpposite());
        while (level.getBlockState(right.relative(along)).is(Blocks.NETHER_PORTAL)) right = right.relative(along);
        // The frame's columns are a block out from the portal's ends; the frames hang in front of them, toward the town.
        for (int up = 1; up >= 0; up--) {
            out.add(left.relative(along.getOpposite()).above(up).relative(front));
            out.add(right.relative(along).above(up).relative(front));
        }
        return out;
    }

    /**
     * One more trophy up on the gateway, if the runners have brought home one not up yet and the stores have one to spare
     * (never the last): in an item frame (the stores', or made of their sticks and a leather), named for who brought it.
     */
    static boolean trophy(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Map<String, String> got = trophies(id);
        if (got.isEmpty()) return false;
        BlockPos portal = NetherRuns.gatePortal(level, id);
        if (portal == null) return false;
        Direction front = NetherRuns.faceToward(level.getBlockState(portal), portal, v.centre());
        List<String> shown = new ArrayList<>();
        BlockPos free = null;
        for (BlockPos spot : trophySpots(level, v)) {
            ItemFrame f = frame(level, spot);
            if (f != null && !f.getItem().isEmpty()) shown.add(BuiltInRegistries.ITEM.getKey(f.getItem().getItem()).toString());
            else if (free == null && (f != null || level.getBlockState(spot).isAir() && new ItemFrame(level, spot, front).survives())) free = spot;
        }
        if (free == null) return false;
        Item pick = null;
        String key = null;
        for (Item it : RARE) {
            String k = BuiltInRegistries.ITEM.getKey(it).toString();
            if (got.containsKey(k) && !shown.contains(k) && Crafts.stock(level, v, s -> s.is(it)) >= 2) { pick = it; key = k; break; }
        }
        if (pick == null) return false;
        ItemFrame f = frame(level, free);
        if (f == null) {
            if (!Crafts.take(level, v, s -> s.is(Items.ITEM_FRAME), 1)
                    && !(Crafts.stock(level, v, s -> s.is(Items.LEATHER)) >= 1 && Crafts.usePlanks(level, v, 4) && Crafts.take(level, v, s -> s.is(Items.LEATHER), 1))) {
                return false;
            }
            f = new ItemFrame(level, free, front);
            f.addTag(TAG);
            level.addFreshEntity(f);
            f.playPlacementSound();
        }
        Item it = pick;
        ItemStack s = Crafts.takeOne(level, v, x -> x.is(it));
        if (s.isEmpty()) return false;
        String[] p = got.get(key).split("\\|", -1);
        ItemStack shown1 = s.copyWithCount(1);
        shown1.set(DataComponents.CUSTOM_NAME, Component.literal(s.getHoverName().getString() + " — brought through the gateway by " + p[0]
            + (p.length > 1 ? ", day " + (Long.parseLong(p[1]) + 1) : "")));
        f.setItem(shown1, false);
        Villages.tell(id, day, "the Nether runners hung " + JobMarket.a(s.getHoverName().getString().toLowerCase(Locale.ROOT)) + " on the gateway, brought home by " + p[0]);
        return true;
    }

    @Nullable
    private static ItemFrame frame(ServerLevel level, BlockPos at) {
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, new AABB(at).inflate(0.1), e -> e.isAlive() && e.getTags().contains(TAG))) return f;
        return null;
    }

    /** Tests and the stage: the trophies on the gateway, by name. */
    public static List<String> trophiesShown(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        for (BlockPos spot : trophySpots(level, v)) {
            ItemFrame f = frame(level, spot);
            if (f != null && !f.getItem().isEmpty()) out.add(f.getItem().getHoverName().getString());
        }
        return out;
    }

    // ------------------------------------------------------------------ the town's look, once a minute

    /** The town's look at what the runs come to (NetherRunners.tick): a trophy up when one is due; the wart farm kept by
     *  a farmer when the town has no brewer. */
    static void tick(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        String last = Ledger.note(id, "nether.trophyday");
        if (last == null || !last.equals(Long.toString(day))) {
            if (trophy(level, v, day)) Ledger.note(id, "nether.trophyday", Long.toString(day));
        }
        boolean brewer = false;
        VillageFolkEntity farmer = null;
        List<BlockPos> cells = farm(id);
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a.stationTask() == StationTask.BREW) brewer = true;
            if (farmer == null && a instanceof VillageFolkEntity f && a.stationTask() == StationTask.FARM && !cells.isEmpty()
                && f.blockPosition().distSqr(cells.get(0)) < 32 * 32) farmer = f;
        }
        if (!brewer && farmer != null) {
            String did = tend(level, v, farmer);
            if (did != null) farmer.brain(did);
        }
    }

    /** The farm's wart as it stands, for the page: ripe of planted. */
    public static int[] farmState(ServerLevel level, UUID village) {
        int planted = 0, ripe = 0;
        for (BlockPos g : farm(village)) {
            if (!level.isLoaded(g)) continue;
            BlockState w = level.getBlockState(g.above());
            if (!w.is(Blocks.NETHER_WART)) continue;
            planted++;
            if (w.getValue(NetherWartBlock.AGE) >= 3) ripe++;
        }
        return new int[]{ planted, ripe };
    }

    static ResourceLocation id(Item it) {
        return BuiltInRegistries.ITEM.getKey(it);
    }
}
