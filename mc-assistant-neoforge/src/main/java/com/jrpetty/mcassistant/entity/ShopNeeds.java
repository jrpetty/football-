package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [player15] What a shop needs before it can open, and whether it has it: read off the building itself.
 * <ul>
 * <li><b>The room.</b> From the shop's counter (or its sign) the air of the room it stands in is filled out, block by
 *     block, to its walls, floor and roof: a room that runs on out of doors (a doorway with no door, a wall with a gap)
 *     or under the open sky is no shop yet. What the room touches is the shop's: its doors, its chests, barrels, shelves
 *     and pots (its stock; those marked back stock are the stockroom's), its item frames (its window), its counter, and its
 *     kit (an oven, an anvil, a loom, seats...).</li>
 * <li><b>Every shop</b> needs a counter where its customers pay, walls and a roof, a door, that door reachable from a
 *     street (or the town's square), somewhere to keep stock that customers can see, and to stand on ground the town lets
 *     a building go (not on a street, not over the mine).</li>
 * <li><b>Each kind</b> adds its own: a bakery an oven, a butcher a smoker or an oven, a tool shop an anvil or a furnace,
 *     a clothier a loom, an apothecary a brewing stand, a tavern seats. A firm's premises are checked by the kind of its
 *     business (BusinessTypes), the same way.</li>
 * </ul>
 * Each need is a line with a tick or a cross, why it matters and where to get it: the shop's screen and its setup show
 * them, its sign over the door says what is missing, and a shop missing one stays shut. Public, so the build orders and
 * repurposing can check a building's new use by the same list.
 */
public final class ShopNeeds {

    private ShopNeeds() {}

    /** How far the room may run from where it is read: a shop, not a town. */
    static final int REACH = 16, BELOW = 2, ABOVE = 8, MOST_CELLS = 2400;

    /** The kinds of shop a player may keep, each with what it adds to what every shop needs. */
    public enum Kind {
        GENERAL("general", "General store", "", ""),
        GROCER("grocer", "Grocer's", "", ""),
        BAKERY("bakery", "Bakery", "oven", "an oven (a furnace or a smoker) to bake in"),
        BUTCHER("butcher", "Butcher's", "oven", "a smoker or a furnace for the meat"),
        TOOLS("tools", "Tool shop", "forge", "an anvil or a furnace to mend and make with"),
        CLOTHES("clothes", "Clothier's", "loom", "a loom"),
        HOME("home", "Home goods", "", ""),
        APOTHECARY("apothecary", "Apothecary", "brew", "a brewing stand"),
        TAVERN("tavern", "Tavern", "seats", "seats for the drinkers (two stairs or more)");

        public final String id, label, kit, kitWords;

        Kind(String id, String label, String kit, String kitWords) {
            this.id = id;
            this.label = label;
            this.kit = kit;
            this.kitWords = kitWords;
        }

        public static Kind of(String id) {
            for (Kind k : values()) if (k.id.equals(id)) return k;
            return GENERAL;
        }

        /** The kind of shop a business of this kind keeps (BusinessTypes key), for its premises. */
        public static Kind forBusiness(String type) {
            return switch (type) {
                case "baker" -> BAKERY;
                case "butcher" -> BUTCHER;
                case "toolsmith", "armourer" -> TOOLS;
                case "clothier", "weaver" -> CLOTHES;
                case "apothecary" -> APOTHECARY;
                case "tavern", "inn", "cafe" -> TAVERN;
                case "greengrocer", "fishmonger" -> GROCER;
                case "carpenter", "chandler", "potter", "glassworks" -> HOME;
                default -> GENERAL;
            };
        }
    }

    /** One need: met or not, what it is, why it matters (or what is wrong), and where to get it. */
    public record Need(String id, boolean ok, String label, String why, String fix) {
        public String line() {
            return (ok ? "✔ " : "✘ ") + label + (ok || why.isEmpty() ? "" : " (" + why + ")");
        }
    }

    /** The room as read: its air, what it touches, and how it is closed in. */
    public static final class Survey {
        public final Set<BlockPos> room = new HashSet<>();
        public final List<BlockPos> stock = new ArrayList<>();
        public final List<BlockPos> doors = new ArrayList<>();
        public final List<BlockPos> counters = new ArrayList<>();
        public final List<BlockPos> windows = new ArrayList<>();
        public final Map<String, Integer> kit = new LinkedHashMap<>();
        public boolean closed = true, roofed = true;
        public String leak = "";
        @Nullable public BlockPos outside;                 // where a customer stands outside the door
        public boolean reachable;
        public long at;

        @Nullable
        public BlockPos counter() {
            return counters.isEmpty() ? null : counters.get(0);
        }

        public int kit(String k) {
            return kit.getOrDefault(k, 0);
        }
    }

    /** Can air (and a folk) be here: nothing to bump into but a carpet, a pressure plate, a lying trapdoor. */
    static boolean open(ServerLevel level, BlockPos p, BlockState st) {
        if (st.getBlock() instanceof DoorBlock || st.getBlock() instanceof FenceGateBlock) return false;
        if (!st.getFluidState().isEmpty()) return false;
        if (st.is(McAssistantMod.SHOP_SIGN.get())) return true;
        VoxelShape s = st.getCollisionShape(level, p);
        return s.isEmpty() || s.max(Direction.Axis.Y) <= 0.1875;
    }

    /** Read the room from where the shop is marked (its counter or its sign). */
    public static Survey survey(ServerLevel level, BlockPos anchor) {
        Survey s = new Survey();
        s.at = level.getGameTime();
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        BlockState here = level.getBlockState(anchor);
        if (here.is(McAssistantMod.SHOP_SIGN.get())) {
            todo.add(anchor);                                                // hung inside: the room is its own
            Direction out = here.getValue(com.jrpetty.mcassistant.block.ShopSignBlock.FACING);
            BlockPos behind = anchor.relative(out.getOpposite(), 2);         // hung outside: the room behind its wall
            if (level.isLoaded(behind) && open(level, behind, level.getBlockState(behind))) {
                Survey inside = flood(level, anchor, List.of(anchor));
                if (!inside.closed || !inside.roofed) return finish(level, anchor, flood(level, anchor, List.of(behind)));
                return finish(level, anchor, inside);
            }
        } else {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = anchor.relative(d);
                if (level.isLoaded(n) && open(level, n, level.getBlockState(n))) todo.add(n);
            }
            if (here.getBlock() instanceof com.jrpetty.mcassistant.block.ShopCounterBlock) s.counters.add(anchor);
        }
        Survey f = flood(level, anchor, new ArrayList<>(todo));
        if (here.getBlock() instanceof com.jrpetty.mcassistant.block.ShopCounterBlock && !f.counters.contains(anchor)) f.counters.add(0, anchor);
        return finish(level, anchor, f);
    }

    private static Survey flood(ServerLevel level, BlockPos anchor, List<BlockPos> from) {
        Survey s = new Survey();
        s.at = level.getGameTime();
        ArrayDeque<BlockPos> todo = new ArrayDeque<>(from);
        Set<BlockPos> seenWall = new HashSet<>();
        while (!todo.isEmpty()) {
            BlockPos p = todo.poll();
            if (s.room.contains(p)) continue;
            if (Math.abs(p.getX() - anchor.getX()) > REACH || Math.abs(p.getZ() - anchor.getZ()) > REACH
                    || p.getY() < anchor.getY() - BELOW || p.getY() > anchor.getY() + ABOVE || s.room.size() >= MOST_CELLS) {
                if (s.closed) s.leak = "the room runs on out past " + p.getX() + ", " + p.getY() + ", " + p.getZ() + ": close its walls";
                s.closed = false;
                continue;
            }
            s.room.add(p);
            if (s.roofed && level.canSeeSky(p)) {
                s.roofed = false;
                if (s.leak.isEmpty()) s.leak = "open to the sky at " + p.getX() + ", " + p.getY() + ", " + p.getZ() + ": it wants a roof";
            }
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (s.room.contains(n)) continue;
                if (!level.isLoaded(n)) { s.closed = false; continue; }
                BlockState st = level.getBlockState(n);
                if (open(level, n, st)) {
                    todo.add(n);
                    continue;
                }
                if (!seenWall.add(n)) continue;
                touch(level, s, n, st);
            }
        }
        return s;
    }

    /** What a wall of the room is: a door, stock, a counter, kit. */
    private static void touch(ServerLevel level, Survey s, BlockPos n, BlockState st) {
        if (st.getBlock() instanceof DoorBlock) {
            BlockPos low = st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? n : n.below();
            if (!s.doors.contains(low)) s.doors.add(low);
            return;
        }
        if (st.getBlock() instanceof com.jrpetty.mcassistant.block.ShopCounterBlock) {
            if (!s.counters.contains(n)) s.counters.add(n);
            return;
        }
        BlockEntity be = level.getBlockEntity(n);
        if (be instanceof Container && (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity
                || be instanceof ChiseledBookShelfBlockEntity || be instanceof DecoratedPotBlockEntity)) {
            s.stock.add(n.immutable());
            return;
        }
        if (st.is(Blocks.FURNACE) || st.is(Blocks.SMOKER) || st.is(Blocks.BLAST_FURNACE)) s.kit.merge("oven", 1, Integer::sum);
        if (st.is(Blocks.FURNACE) || st.is(Blocks.BLAST_FURNACE) || st.getBlock() instanceof AnvilBlock || st.is(Blocks.SMITHING_TABLE)) s.kit.merge("forge", 1, Integer::sum);
        if (st.is(Blocks.LOOM)) s.kit.merge("loom", 1, Integer::sum);
        if (st.is(Blocks.BREWING_STAND)) s.kit.merge("brew", 1, Integer::sum);
        if (st.is(BlockTags.STAIRS) || st.is(BlockTags.SLABS)) s.kit.merge("seats", 1, Integer::sum);
    }

    /** The doors' ways out, whether one is reached from a street, and the window's frames. */
    private static Survey finish(ServerLevel level, BlockPos anchor, Survey s) {
        Villages.Village v = Villages.nearest(level, anchor, 200);
        for (BlockPos door : s.doors) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (!s.room.contains(door.relative(d.getOpposite())) || s.room.contains(door.relative(d))) continue;
                BlockPos out = door.relative(d);
                if (s.outside == null) s.outside = out;
                if (v != null && reached(level, v, out)) {
                    s.outside = out;
                    s.reachable = true;
                }
            }
            if (s.reachable) break;
        }
        if (!s.room.isEmpty()) {
            AABB box = null;
            for (BlockPos p : s.room) box = box == null ? new AABB(p) : box.minmax(new AABB(p));
            for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, box.inflate(1))) {
                if (s.room.contains(f.blockPosition())) s.windows.add(f.blockPosition());
            }
        }
        return s;
    }

    /** From outside the door, can a folk walk to a street or the square? */
    static boolean reached(ServerLevel level, Villages.Village v, BlockPos start) {
        BlockPos feet = stand(level, start);
        if (feet == null) return false;
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        todo.add(feet);
        BlockPos c = v.centre();
        while (!todo.isEmpty() && seen.size() < 3000) {
            BlockPos p = todo.poll();
            if (!seen.add(p)) continue;
            if (Footways.street(v, p.getX(), p.getZ())) return true;
            double dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ();
            if (dx * dx + dz * dz <= 12 * 12) return true;
            if (Math.abs(p.getX() - start.getX()) > 80 || Math.abs(p.getZ() - start.getZ()) > 80) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = stand(level, p.relative(d));
                if (n != null && Math.abs(n.getY() - p.getY()) <= 1 && !seen.contains(n)) todo.add(n);
            }
        }
        return false;
    }

    /** Where a folk would stand at this column, a block up or down. */
    @Nullable
    static BlockPos stand(ServerLevel level, BlockPos p) {
        for (int dy : new int[]{ 0, 1, -1 }) {
            BlockPos f = p.above(dy);
            if (!level.isLoaded(f)) return null;
            BlockState at = level.getBlockState(f), head = level.getBlockState(f.above()), under = level.getBlockState(f.below());
            if (open(level, f, at) && open(level, f.above(), head) && !open(level, f.below(), under) && under.getFluidState().isEmpty()) return f;
        }
        return null;
    }

    /** Every need, met or not, for a shop of this kind read this way; back stock is not what customers see. */
    public static List<Need> check(ServerLevel level, @Nullable Villages.Village v, Survey s, Kind kind, Set<BlockPos> back) {
        List<Need> out = new ArrayList<>();
        out.add(new Need("town", v != null, "In a town", v == null ? "no town near enough to shop here" : "",
            "Build it inside a town's bounds."));
        BlockPos at = s.counter();
        boolean ground = true;
        String bad = "";
        BlockPos probe = at != null ? at : s.room.isEmpty() ? null : s.room.iterator().next();
        if (v != null && probe != null) {
            if (Footways.street(v, probe.getX(), probe.getZ())) { ground = false; bad = "it stands on a street"; }
            else if (TownMine.inMine(v.id(), probe)) { ground = false; bad = "it is over the town's mine"; }
        }
        out.add(new Need("ground", ground, "On ground a building may go", bad, "Not on a street and not over the mine: build it elsewhere."));
        out.add(new Need("counter", at != null, "A shop counter, where customers pay", at == null ? "customers need somewhere to pay" : "",
            "Craft one (three wooden slabs over a chest between two planks, a gold nugget under the chest) or buy one at the town's shop."));
        out.add(new Need("walls", s.closed && s.roofed, "Walls and a roof", s.leak, "Close the room in: walls all round, a roof, and doors in the doorways."));
        out.add(new Need("door", !s.doors.isEmpty(), "A door", s.doors.isEmpty() ? "customers need a way in" : "", "Hang a door in its doorway."));
        out.add(new Need("street", s.reachable, "Its door reached from a street", s.doors.isEmpty() ? "" : s.reachable ? "" : "no path from the door to a street or the square",
            "Clear a way from the door to the nearest street, or lay a path."));
        int shelves = 0;
        for (BlockPos p : s.stock) if (!back.contains(p)) shelves++;
        out.add(new Need("stock", shelves > 0, "A chest, barrel or shelf for its stock", shelves == 0 ? (s.stock.isEmpty() ? "nowhere to keep what it sells" : "every chest is marked back stock") : "",
            "Set a chest, a barrel or a chiseled bookshelf inside the shop."));
        if (!kind.kit.isEmpty()) {
            int have = s.kit(kind.kit);
            boolean ok = kind.kit.equals("seats") ? have >= 2 : have >= 1;
            out.add(new Need("kit", ok, capital(kind.kitWords), ok ? "" : "a " + kind.label.toLowerCase(java.util.Locale.ROOT) + " can't work without it",
                "Set " + kind.kitWords + " inside the shop: craft it, or buy it at the town's shop."));
        }
        return out;
    }

    public static boolean ready(List<Need> needs) {
        for (Need n : needs) if (!n.ok()) return false;
        return true;
    }

    /** The first thing missing, in a few words ("needs a counter"), or "". */
    public static String missing(List<Need> needs) {
        for (Need n : needs) {
            if (n.ok()) continue;
            return switch (n.id()) {
                case "counter" -> "needs a counter";
                case "walls" -> "needs walls and a roof";
                case "door" -> "needs a door";
                case "street" -> "needs a way in from the street";
                case "stock" -> "needs a chest for stock";
                case "kit" -> "needs " + n.label().toLowerCase(java.util.Locale.ROOT);
                case "ground" -> "can't stand here";
                default -> "isn't in a town";
            };
        }
        return "";
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
