package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * [orders17] A building put to another use, or made bigger. A use wants its kit (a home its beds and a chest, a bakery its
 * oven and its smoker, a smithy its anvil and furnaces, a tavern its barrels, seats and a brewing stand, a warehouse its
 * chests): what the building has of it is counted where it stands, and what it lacks is the refit, quoted as any order is
 * (the kit at the market's ask, the fitters' hours at their wage, the permit for the change of use, the builders' margin,
 * and the time by the builders' queue) and fitted by the builders, each piece set down against the inside of the walls.
 * When it is done the register, the land title and the business in it say the new use.
 *
 * <p>A building can be raised a storey (its drawing with a second storey put on, Blueprints "_tall": the old roof taken
 * off, the walls built up again, the roof lifted onto them) or given a wing (a small building of its own drawing beside it,
 * on land bought for it), each with its own quote.
 */
public final class Refits {

    private Refits() {}

    /** A use's kit: each piece and how many. */
    static final Map<String, Map<Block, Integer>> KIT = new LinkedHashMap<>();
    static {
        kit("home", Blocks.RED_BED, 2, Blocks.CHEST, 1, Blocks.LANTERN, 1);
        kit("shop", Blocks.BARREL, 3, Blocks.CHEST, 2, Blocks.LANTERN, 1);
        kit("bakery", Blocks.FURNACE, 1, Blocks.SMOKER, 1, Blocks.CRAFTING_TABLE, 1, Blocks.BARREL, 2, Blocks.CHEST, 1);
        kit("smithy", Blocks.ANVIL, 1, Blocks.FURNACE, 1, Blocks.BLAST_FURNACE, 1, Blocks.GRINDSTONE, 1, Blocks.CHEST, 1);
        kit("tavern", Blocks.BARREL, 3, Blocks.BREWING_STAND, 1, Blocks.OAK_STAIRS, 4, Blocks.LANTERN, 2);
        kit("cafe", Blocks.SMOKER, 1, Blocks.BARREL, 2, Blocks.OAK_STAIRS, 4, Blocks.LANTERN, 1);
        kit("workshop", Blocks.CRAFTING_TABLE, 1, Blocks.STONECUTTER, 1, Blocks.SMITHING_TABLE, 1, Blocks.LOOM, 1, Blocks.CHEST, 2);
        kit("warehouse", Blocks.CHEST, 6, Blocks.BARREL, 4);
        kit("inn", Blocks.RED_BED, 3, Blocks.BARREL, 2, Blocks.OAK_STAIRS, 2, Blocks.LANTERN, 2);
    }

    static void kit(String use, Object... pairs) {
        Map<Block, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((Block) pairs[i], (Integer) pairs[i + 1]);
        KIT.put(use, m);
    }

    public static List<String> uses() {
        return new ArrayList<>(KIT.keySet());
    }

    /** What a use wants, in words: "2 red beds, a chest, a lantern". */
    public static List<String> needs(String use) {
        List<String> out = new ArrayList<>();
        Map<Block, Integer> k = KIT.get(use);
        if (k == null) return out;
        for (Map.Entry<Block, Integer> e : k.entrySet()) out.add(e.getValue() + " " + new ItemStack(e.getKey()).getHoverName().getString().toLowerCase(Locale.ROOT));
        return out;
    }

    /** The box inside a property's walls: {x0, y0, z0, x1, y1, z1}. */
    static int[] box(CompoundTag t) {
        BlockPos a = BlockPos.of(t.getLong("anchor"));
        Direction f = Direction.from2DDataValue(t.getInt("facing"));
        boolean turned = f.getAxis() == Direction.Axis.X;
        int wx = turned ? t.getInt("hd") : t.getInt("hw"), wz = turned ? t.getInt("hw") : t.getInt("hd");
        return new int[]{ a.getX() - wx, a.getY(), a.getZ() - wz, a.getX() + wx, a.getY() + 10, a.getZ() + wz };
    }

    /** What of a use's kit the building has already, piece by piece. */
    static Map<Block, Integer> have(ServerLevel level, CompoundTag t, String use) {
        Map<Block, Integer> out = new LinkedHashMap<>();
        Map<Block, Integer> k = KIT.get(use);
        if (k == null) return out;
        int[] b = box(t);
        for (BlockPos p : BlockPos.betweenClosed(b[0], b[1], b[2], b[3], b[4], b[5])) {
            if (!level.isLoaded(p)) continue;
            BlockState st = level.getBlockState(p);
            for (Block want : k.keySet()) {
                boolean same = st.is(want) || want instanceof BedBlock && st.getBlock() instanceof BedBlock || want == Blocks.OAK_STAIRS
                    && st.is(net.minecraft.tags.BlockTags.STAIRS) && p.getY() <= b[1] + 2;
                if (!same) continue;
                if (st.getBlock() instanceof BedBlock && st.getValue(BedBlock.PART) != BedPart.FOOT) continue;
                out.merge(want, 1, Integer::sum);
            }
        }
        return out;
    }

    /** The kit a use still lacks in this building. */
    public static Map<Block, Integer> missing(ServerLevel level, UUID village, String key, String use) {
        CompoundTag t = PropertyMarket.get(village, key);
        Map<Block, Integer> out = new LinkedHashMap<>();
        if (t == null || !KIT.containsKey(use)) return out;
        Map<Block, Integer> h = have(level, t, use);
        for (Map.Entry<Block, Integer> e : KIT.get(use).entrySet()) {
            int lack = e.getValue() - h.getOrDefault(e.getKey(), 0);
            if (lack > 0) out.put(e.getKey(), lack);
        }
        return out;
    }

    /**
     * Where the kit goes: floor cells inside the walls, air with air over them and a floor under them, against a wall, not in
     * front of a door; a bed's two cells both free.
     */
    static List<BlockPos> spots(ServerLevel level, CompoundTag t, int want, Direction facing) {
        int[] b = box(t);
        List<BlockPos> out = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        for (int y = b[1]; y <= b[1] + 6 && out.size() < want; y++) {
            for (int x = b[0] + 1; x <= b[3] - 1 && out.size() < want; x++) for (int z = b[2] + 1; z <= b[5] - 1 && out.size() < want; z++) {
                BlockPos p = new BlockPos(x, y, z);
                if (used.contains(p.asLong()) || !free(level, p)) continue;
                boolean wall = false, door = false;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockState n = level.getBlockState(p.relative(d));
                    if (n.getBlock() instanceof DoorBlock) door = true;
                    else if (n.isSolidRender(level, p.relative(d))) wall = true;
                }
                if (!wall || door) continue;
                out.add(p);
                used.add(p.asLong());
                for (Direction d : Direction.Plane.HORIZONTAL) used.add(p.relative(d).asLong());   // room to walk between the pieces
            }
        }
        return out;
    }

    static boolean free(ServerLevel level, BlockPos p) {
        return level.isLoaded(p) && level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
            && level.getBlockState(p.below()).isSolidRender(level, p.below());
    }

    /** The quote for refitting a property for another use: its missing kit, set in, at the market's ask and the fitters' hours. */
    public static BuildOrders.Quote quote(ServerLevel level, UUID village, String key, String use) {
        BuildOrders.Quote q = new BuildOrders.Quote();
        q.village = village;
        q.kind = "refit";
        q.use = use;
        CompoundTag t = PropertyMarket.get(village, key);
        Villages.Village v = Villages.get(village);
        if (t == null || v == null || !KIT.containsKey(use)) { q.ok = false; q.why = t == null ? "not on the register" : "no such use"; return q; }
        q.drawing = t.getString("drawing");
        q.style = t.getString("style").isEmpty() ? "oak" : t.getString("style");
        q.plot = BlockPos.of(t.getLong("anchor"));
        q.facing = Direction.from2DDataValue(t.getInt("facing"));
        q.name = "the refit of " + t.getString("name") + " as " + (("aeiou".indexOf(use.charAt(0)) >= 0) ? "an " : "a ") + use;
        if (use.equals(t.getString("use"))) { q.ok = false; q.why = "it is " + (use.equals("home") ? "a home" : "a " + use) + " already"; }
        if ("unfinished".equals(t.getString("use")) || "plot".equals(t.getString("use"))) { q.ok = false; q.why = "there is no building to refit"; }
        Map<Block, Integer> lack = missing(level, village, key, use);
        int pieces = 0;
        for (int n : lack.values()) pieces += n;
        List<BlockPos> at = spots(level, t, pieces, q.facing);
        int i = 0;
        List<BuildOrders.Cell> cells = new ArrayList<>();
        for (Map.Entry<Block, Integer> e : lack.entrySet()) {
            for (int n = 0; n < e.getValue() && i < at.size(); n++) {
                Block blk = e.getKey();
                BlockPos p = at.get(i++);
                if (blk instanceof BedBlock && !free(level, p.relative(q.facing))) continue;
                cells.add(new BuildOrders.Cell(p, null, blk, BuildOrders.itemOf(blk), BuildOrders.Trade.FITTERS, BuildOrders.minutes(BuildOrders.Trade.FITTERS, 0), true));
            }
        }
        if (cells.size() < pieces && q.ok) q.why = "only room for " + cells.size() + " of the " + pieces + " pieces inside";
        q.explicit.addAll(cells);
        BuildOrders.price(level, v, q, cells, new BuildOrders.Options());
        q.landTerms = "your own";
        q.rates = 0;
        BuildOrders.finishQuote(level, v, q);
        return q;
    }

    /** The quote for a storey on top ("storey") or a wing beside it ("wing"). */
    public static BuildOrders.Quote extension(ServerLevel level, UUID village, String key, String what) {
        CompoundTag t = PropertyMarket.get(village, key);
        Villages.Village v = Villages.get(village);
        BuildOrders.Quote q = new BuildOrders.Quote();
        q.village = village;
        if (t == null || v == null) { q.ok = false; q.why = "not on the register"; return q; }
        String drawing = t.getString("drawing");
        String style = t.getString("style").isEmpty() ? "oak" : t.getString("style");
        BlockPos anchor = BlockPos.of(t.getLong("anchor"));
        Direction facing = Direction.from2DDataValue(t.getInt("facing"));
        UUID owner = null;
        try { owner = UUID.fromString(t.getString("owner")); } catch (IllegalArgumentException ignored) { }
        if (what.equals("wing")) {
            String annex = Blueprints.has("shelter") ? "shelter" : "storage";
            int[] h = BuildGoal.footprint(drawing), w = BuildGoal.footprint(annex);
            Direction side = facing.getClockWise();
            int off = (side.getAxis() == facing.getAxis() ? h[1] : h[0]) + (side.getAxis() == facing.getAxis() ? w[1] : w[0]) + 2;
            BlockPos at = anchor.relative(side, off);
            q = BuildOrders.quote(level, village, annex, style, at, facing, owner);
            q.name = "a wing (" + BuildOrders.title(annex).replaceFirst("^an? ", "") + ") beside " + t.getString("name");
            return q;
        }
        q.kind = "extend";
        q.buyer = owner;
        q.drawing = drawing + Blueprints.TALL;
        q.baseDrawing = drawing;
        q.style = style;
        q.plot = anchor;
        q.facing = facing;
        q.use = t.getString("use");
        q.name = "a second storey on " + t.getString("name");
        if (drawing.endsWith(Blueprints.TALL) || !Blueprints.has(q.drawing)) { q.ok = false; q.why = "it can't be raised a storey"; return q; }
        if ("plot".equals(t.getString("use")) || "unfinished".equals(t.getString("use"))) { q.ok = false; q.why = "there is no building to raise"; return q; }
        List<BuildOrders.Cell> cells = BuildOrders.structure(q.drawing, BuildOrders.palette(style), anchor, facing, drawing);
        // The old roof's cells the raised building has none of come off first.
        Set<Long> tall = new HashSet<>();
        for (BuildGoal.Placement p : BuildGoal.plan(q.drawing, anchor, facing, 13)) tall.add(p.pos().asLong());
        for (BuildGoal.Placement p : BuildGoal.plan(drawing, anchor, facing, 13)) {
            if (p.part() == BuildGoal.Part.CLEAR || tall.contains(p.pos().asLong())) continue;
            if (!level.getBlockState(p.pos()).isAir()) q.cutCells.add(p.pos());
        }
        q.cut = q.cutCells.size();
        BuildOrders.price(level, v, q, cells, new BuildOrders.Options());
        q.landTerms = "your own";
        q.rates = 0;
        BuildOrders.finishQuote(level, v, q);
        return q;
    }

    /** A refit or a storey done: the register, the land title (next morning) and any business in it say so. */
    static void finished(ServerLevel level, UUID village, CompoundTag o, long day) {
        BlockPos anchor = BlockPos.of(o.getLong("anchor"));
        CompoundTag t = PropertyMarket.get(village, PropertyMarket.keyAt(anchor));
        if (t == null) return;
        if ("refit".equals(o.getString("kind"))) {
            String was = t.getString("use");
            String use = o.getString("use");
            t.putString("use", use);
            t.putString("name", t.getString("name").replaceAll("'s [a-z ]+ at ", "'s " + use + " at "));
            if (was.equals("home") && t.getBoolean("onHomes")) {
                Homes.Home h = Homes.homes(village).get(anchor.asLong());
                if (h != null) { h.toLet = false; Homes.save(village, h); }
            }
            for (Business b : Businesses.all(village)) {
                if (b.id.equals(t.getString("biz")) || b.id.equals(t.getString("tenant"))) {
                    b.note(day, "its premises refitted as " + use);
                    b.cards.put("premises", t.getString("name"));
                }
            }
            Villages.tell(village, day, t.getString("ownerName") + "'s " + was + " was refitted as " + (("aeiou".indexOf(use.charAt(0)) >= 0) ? "an " : "a ") + use);
        } else if ("extend".equals(o.getString("kind"))) {
            t.putString("drawing", o.getString("drawing"));
            if (t.getBoolean("onHomes")) com.jrpetty.mcassistant.village.Ledger.grow(village, anchor);
            Villages.tell(village, day, t.getString("name") + " was raised a storey");
        }
        t.putLong("cost", t.getLong("cost") + Math.max(0, o.getInt("paid") - o.getInt("refunded")) * 100L);
        Accounts.dirty();
    }

    /** What a property could become, for the office: each use with what it lacks. */
    public static List<String> options(ServerLevel level, UUID village, String key) {
        List<String> out = new ArrayList<>();
        CompoundTag t = PropertyMarket.get(village, key);
        if (t == null) return out;
        for (String use : KIT.keySet()) {
            if (use.equals(t.getString("use"))) continue;
            Map<Block, Integer> lack = missing(level, village, key, use);
            List<String> words = new ArrayList<>();
            for (Map.Entry<Block, Integer> e : lack.entrySet()) words.add(e.getValue() + " " + new ItemStack(e.getKey()).getHoverName().getString().toLowerCase(Locale.ROOT));
            out.add(use + ": " + (words.isEmpty() ? "has everything it needs" : "needs " + String.join(", ", words)));
        }
        return out;
    }

    @Nullable
    static String useOf(UUID village, String key) {
        CompoundTag t = PropertyMarket.get(village, key);
        return t == null ? null : t.getString("use");
    }
}
