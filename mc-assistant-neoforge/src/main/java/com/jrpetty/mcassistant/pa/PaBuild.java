package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [featpa] Build from a blueprint: show it a building and it builds a copy wherever you point.
 * <ol>
 * <li><b>Show it.</b> Orders > Blueprints > "Copy the building I'm looking at" (it finds the whole building joined to that
 *     block), or ping two corners (the ping wheel's Mark an area) and pick "Copy this building". It takes the box in,
 *     every block with its state, names it, and keeps it in the list (PaBlueprints).</li>
 * <li><b>Point.</b> Pick one, "Place where I'm looking": a ghost of it stands there (PaGhost on the client), turned and
 *     mirrored by the buttons, nudged a block at a time, its clashes with your own blocks shown red. Nothing of yours is
 *     ever knocked down unless you say "Clear them"; otherwise it builds round them.</li>
 * <li><b>Materials.</b> What it takes, against what is in its pack and your chests near by; what's short is said in
 *     plain words, with "Get what's short" (it crafts or gathers it, PaCraft) and "Buy it at the market" (an errand).</li>
 * <li><b>Build.</b> Layer by layer from the ground up (the ground dug first, then the blocks, then what hangs on them),
 *     each block walked to and placed by hand with a swing and the block's own sound, the exact state (stairs' facing,
 *     doors, beds, slabs, chests' halves); fetched from your chests in trips when its pack runs dry. Never a teleport.
 *     Progress on the HUD, the Jobs tab and the Blueprints screen.</li>
 * </ol>
 */
public final class PaBuild {

    private PaBuild() {}

    // ------------------------------------------------------------------ the preview (per player)

    public static final class Preview {
        public String name = "";
        public BlockPos anchor = BlockPos.ZERO;
        public int rot;
        public boolean mirror, clearMine;
        /** Confirmed: the ghost stays as the build's guide, the screen shows the build. */
        public boolean building;
    }

    static final Map<UUID, Preview> PREVIEW = new ConcurrentHashMap<>();
    /** The last area a player pinged (two corners), for "Copy this building". */
    static final Map<UUID, BlockPos[]> AREA = new ConcurrentHashMap<>();

    public static void rememberArea(ServerPlayer p, BlockPos a, BlockPos b) {
        AREA.put(p.getUUID(), new BlockPos[]{a.immutable(), b.immutable()});
    }

    @Nullable
    public static Preview preview(UUID player) {
        return PREVIEW.get(player);
    }

    /** Tests: a ghost set as the screen's buttons would leave it. */
    public static void rememberPreviewForTests(UUID player, Preview pv) {
        PREVIEW.put(player, pv);
    }

    // ------------------------------------------------------------------ a build under way (per assistant)

    /** One cell to see to: where, what goes there, and whether it is the ground (0), a block (1), or a hanger-on (2). */
    record Work(BlockPos pos, BlockState state, int cell, int layer, int kind) {}

    public static final class Run {
        public PaBlueprint bp;
        public String name = "";
        public BlockPos anchor = BlockPos.ZERO;
        public int rot;
        public boolean mirror, clearMine;
        final List<Work> order = new ArrayList<>();
        int cursor;
        final List<Integer> retry = new ArrayList<>();
        boolean retrying;
        public int placed, dug, total, aroundYours, layers, layer, unreachable;
        public final Map<Item, Integer> missing = new LinkedHashMap<>();
        public long started;
        public boolean done, stopped;
        public String last = "";
        /** Blocks of the player's own it moved into its pack, clearing them (said at the end). */
        public int keptOfYours;

        public int percent() {
            return total <= 0 ? 0 : Math.min(100, (placed + dug) * 100 / Math.max(1, total));
        }
    }

    static final Map<UUID, Run> RUNS = new ConcurrentHashMap<>();

    @Nullable
    public static Run run(PlayerAssistantEntity a) {
        return RUNS.get(a.getUUID());
    }

    /** Tests: the build pace (ticks between blocks), or 0 for a player's. */
    public static int paceForTests;

    // ------------------------------------------------------------------ hangers-on: placed after the blocks they hang on

    static boolean hangsOn(BlockState s) {
        Block b = s.getBlock();
        return b instanceof TorchBlock || b instanceof LadderBlock || b instanceof DoorBlock || b instanceof TrapDoorBlock
            || b instanceof FaceAttachedHorizontalDirectionalBlock || b instanceof SignBlock || b instanceof AbstractBannerBlock
            || b instanceof CarpetBlock || b instanceof BasePressurePlateBlock || b instanceof BaseRailBlock || b instanceof BedBlock
            || b instanceof FlowerPotBlock || b instanceof LanternBlock || b instanceof BushBlock || b instanceof RedStoneWireBlock
            || b instanceof DiodeBlock || b instanceof VineBlock || b instanceof CandleBlock || b instanceof SnowLayerBlock
            || b instanceof TripWireHookBlock;
    }

    /** The other half placed with this one (a door's top, a bed's head, a tall flower's top), or null. */
    @Nullable
    static BlockPos partner(BlockPos p, BlockState s) {
        Block b = s.getBlock();
        if (b instanceof DoorBlock) return s.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? p.above() : null;
        if (b instanceof net.minecraft.world.level.block.DoublePlantBlock && s.hasProperty(net.minecraft.world.level.block.DoublePlantBlock.HALF))
            return s.getValue(net.minecraft.world.level.block.DoublePlantBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER ? p.above() : null;
        if (b instanceof BedBlock) return s.getValue(BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.FOOT ? p.relative(s.getValue(BedBlock.FACING)) : null;
        return null;
    }

    /** The top half of a door, the head of a bed: placed by its partner, never on its own. */
    static boolean secondHalf(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof DoorBlock) return s.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER;
        if (b instanceof net.minecraft.world.level.block.DoublePlantBlock && s.hasProperty(net.minecraft.world.level.block.DoublePlantBlock.HALF))
            return s.getValue(net.minecraft.world.level.block.DoublePlantBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER;
        if (b instanceof BedBlock) return s.getValue(BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD;
        return false;
    }

    /** The order: layer by layer from the ground up; in each, the ground first, then the blocks, then what hangs on them,
     *  each pass along the rows in a snake so it isn't forever walking back. */
    static List<Work> order(PaBlueprint bp, BlockPos anchor, boolean mirror, int rot) {
        List<Work> out = new ArrayList<>();
        for (PaBlueprint.Spot s : bp.spots(anchor, mirror, rot)) {
            int kind = s.state().isAir() ? 0 : hangsOn(s.state()) ? 2 : 1;
            out.add(new Work(s.pos(), s.state(), s.cell(), s.layer(), kind));
        }
        out.sort((x, y) -> {
            if (x.layer != y.layer) return Integer.compare(x.layer, y.layer);
            if (x.kind != y.kind) return Integer.compare(x.kind, y.kind);
            int zx = x.pos.getZ(), zy = y.pos.getZ();
            if (zx != zy) return Integer.compare(zx, zy);
            return (zx & 1) == 0 ? Integer.compare(x.pos.getX(), y.pos.getX()) : Integer.compare(y.pos.getX(), x.pos.getX());
        });
        return out;
    }

    /** Does this spot still want work? (Already right, or a second half: no.) */
    static boolean wanted(ServerLevel level, Work w) {
        BlockState now = level.getBlockState(w.pos);
        if (w.state.isAir()) return !now.isAir() && !now.is(net.minecraft.world.level.block.Blocks.CAVE_AIR) && now.getBlock() != net.minecraft.world.level.block.Blocks.WATER;
        if (secondHalf(w.state)) return false;
        return !same(now, w.state);
    }

    /** The same block for a copy: the state itself, ignoring what only the neighbours decide (a fence's arms are the same fence). */
    public static boolean same(BlockState now, BlockState want) {
        if (now == want) return true;
        if (now.getBlock() != want.getBlock()) return false;
        for (var prop : want.getProperties()) {
            String n = prop.getName();
            if (n.equals("waterlogged") || n.equals("powered") || n.equals("north") && !want.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                || n.equals("south") || n.equals("east") || n.equals("west") || n.equals("up") || n.equals("distance") || n.equals("persistent")
                || n.equals("open") && !(want.getBlock() instanceof DoorBlock) || n.equals("occupied") || n.equals("lit")) continue;
            if (!now.getValue(prop).equals(want.getValue(prop))) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ show it: the building taken in

    /** The block its player looks at (64 blocks), or null. */
    @Nullable
    static BlockHitResult looked(ServerPlayer p) {
        HitResult h = p.pick(64.0, 1.0F, false);
        return h instanceof BlockHitResult b && h.getType() == HitResult.Type.BLOCK ? b : null;
    }

    /** "Copy the building I'm looking at" (or standing in). Says how it went. */
    public static void copyLooked(ServerPlayer p, PlayerAssistantEntity a, String name) {
        BlockHitResult hit = looked(p);
        BlockPos at = hit != null ? hit.getBlockPos() : p.blockPosition();
        var box = PaBlueprint.detect(p.serverLevel(), at);
        if (box == null && hit != null) box = PaBlueprint.detect(p.serverLevel(), p.blockPosition());
        if (box == null) {
            PaTalk.reply(a, PaLogiTalk.line(a, "kit.bp_nothing"));
            PaKit.tell(p, "bad", "No building found where you looked. It takes in the blocks joined to the one you look at, leaving out the ground. Next: look straight at a wall (or stand inside it) and click again.");
            return;
        }
        copyBox(p, a, new BlockPos(box.minX(), box.minY(), box.minZ()), new BlockPos(box.maxX(), box.maxY(), box.maxZ()), name);
    }

    /** "Copy this building" on the marked area (its two corners). */
    public static void copyArea(ServerPlayer p, PlayerAssistantEntity a, String name) {
        BlockPos[] c = AREA.get(p.getUUID());
        if (c == null) {
            PaTalk.reply(a, PaLogiTalk.line(a, "kit.bp_no_area"));
            PaKit.tell(p, "warn", "No area marked yet. Next: ping one corner on the ground, pick Mark an area on the wheel, then ping the opposite corner.");
            return;
        }
        // the corners are on the ground pinged: the box goes from the lower corner's level up to the building's top
        int top = Math.max(c[0].getY(), c[1].getY()) + PaBlueprint.MAX_H - 1;
        BlockPos lo = new BlockPos(Math.min(c[0].getX(), c[1].getX()), Math.min(c[0].getY(), c[1].getY()), Math.min(c[0].getZ(), c[1].getZ()));
        BlockPos hi = new BlockPos(Math.max(c[0].getX(), c[1].getX()), top, Math.max(c[0].getZ(), c[1].getZ()));
        copyBox(p, a, lo, hi, name);
    }

    static void copyBox(ServerPlayer p, PlayerAssistantEntity a, BlockPos lo, BlockPos hi, String name) {
        String n = clean(name);
        if (n.isEmpty()) n = PaBlueprints.freshName(p.getUUID(), "Building");
        PaBlueprint bp = PaBlueprint.capture(p.serverLevel(), lo, hi, n);
        if (bp == null) {
            PaTalk.reply(a, PaLogiTalk.line(a, "kit.bp_nothing"));
            PaKit.tell(p, "bad", "Nothing built in that box: it was all ground and air. Next: mark the corners round the building itself.");
            return;
        }
        PaBlueprints.put(p.getUUID(), bp);
        a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        PaTalk.reply(a, PaLogiTalk.line(a, "kit.bp_saved", "item", bp.name, "n", String.valueOf(bp.solid()),
            "what", bp.sx + " by " + bp.sz + ", " + bp.sy + " high"));
        a.playAnim("point");
        PaKit.sendList(p, a, bp.name);
        PaKit.tell(p, "ok", "Saved \"" + bp.name + "\": " + bp.sx + " by " + bp.sz + ", " + bp.sy + " high, " + bp.solid() + " blocks, every block's state as it stands"
            + (bp.sx == PaBlueprint.MAX_W || bp.sz == PaBlueprint.MAX_D || bp.sy == PaBlueprint.MAX_H ? " (cut at the largest box it takes, 40 by 40 by 32)" : "")
            + ". Chests copy empty. Next: pick it and click Place where I look.");
    }

    static String clean(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("[\\p{Cntrl}]", "");
        return t.length() > 24 ? t.substring(0, 24).trim() : t;
    }

    // ------------------------------------------------------------------ point: the ghost

    /** "Place where I'm looking": its middle on the spot, its floor on the ground there. */
    public static void aim(ServerPlayer p, PlayerAssistantEntity a, String name) {
        PaBlueprint bp = PaBlueprints.find(p.getUUID(), name);
        if (bp == null) { PaKit.tell(p, "bad", "That blueprint has gone (deleted?). Pick another in the list."); return; }
        BlockHitResult hit = looked(p);
        BlockPos at = hit == null ? p.blockPosition().relative(p.getDirection(), 4 + bp.sz / 2)
            : hit.getDirection() == Direction.UP ? hit.getBlockPos().above() : hit.getBlockPos().relative(hit.getDirection());
        Preview pv = PREVIEW.getOrDefault(p.getUUID(), new Preview());
        if (!pv.name.equalsIgnoreCase(bp.name)) { pv.rot = 0; pv.mirror = false; }
        pv.building = false;
        pv.name = bp.name;
        int[] f = PaBlueprint.size(bp.sx, bp.sz, pv.rot);
        pv.anchor = new BlockPos(at.getX() - f[0] / 2, at.getY(), at.getZ() - f[1] / 2);
        PREVIEW.put(p.getUUID(), pv);
        PaKit.sendPreview(p, a, bp, pv, true);
        int cl = clashes(p.serverLevel(), bp, pv).size();
        PaKit.tell(p, cl > 0 ? "warn" : "ok", "A ghost of \"" + bp.name + "\" stands " + (hit == null ? "in front of you (you weren't looking at a block)" : "where you looked")
            + (cl > 0 ? "; " + cl + " of your blocks are in its way (red) and it'll build round them" : "") + ". Next: Turn, Mirror or nudge it, then Build it.");
    }

    /** The preview's buttons: turn, mirror, nudge, clear-or-around, cancel. */
    public static void adjust(ServerPlayer p, PlayerAssistantEntity a, String op, CompoundTag args) {
        Preview pv = PREVIEW.get(p.getUUID());
        if (pv == null) return;
        PaBlueprint bp = PaBlueprints.find(p.getUUID(), pv.name);
        if (bp == null) { PREVIEW.remove(p.getUUID()); PaKit.ghostOff(p); return; }
        switch (op) {
            case "bp_rot" -> {
                int[] before = PaBlueprint.size(bp.sx, bp.sz, pv.rot);
                pv.rot = (pv.rot + 1) & 3;
                int[] after = PaBlueprint.size(bp.sx, bp.sz, pv.rot);
                pv.anchor = pv.anchor.offset((before[0] - after[0]) / 2, 0, (before[1] - after[1]) / 2);   // turned about its middle
            }
            case "bp_mirror" -> pv.mirror = !pv.mirror;
            case "bp_nudge" -> pv.anchor = pv.anchor.offset(clamp(args.getInt("dx")), clamp(args.getInt("dy")), clamp(args.getInt("dz")));
            case "bp_clear" -> pv.clearMine = args.getBoolean("on");
            case "bp_cancel" -> {
                PREVIEW.remove(p.getUUID());
                PaKit.ghostOff(p);
                PaKit.tell(p, "ok", pv.building ? "Ghost hidden. The build carries on (Stop it from the build box)." : "Ghost taken away: nothing was built. Pick a blueprint to place it again.");
                return;
            }
            default -> { }
        }
        PaKit.sendPreview(p, a, bp, pv, false);
        int cl = clashes(p.serverLevel(), bp, pv).size();
        String did = switch (op) {
            case "bp_rot" -> "Turned to " + pv.rot * 90 + " degrees.";
            case "bp_mirror" -> pv.mirror ? "Mirrored: left and right swapped." : "Back the right way round.";
            case "bp_nudge" -> "Moved one block.";
            case "bp_clear" -> pv.clearMine ? "It will take down your " + cl + " blocks in the way first; they go in its pack." : "It will build round your blocks and leave them standing.";
            default -> "";
        };
        PaKit.tell(p, op.equals("bp_clear") && pv.clearMine ? "warn" : "ok", did + (cl > 0 && !op.equals("bp_clear") ? " " + cl + " of your blocks in the way (red)." : "") + " Next: Build it when it looks right.");
    }

    private static int clamp(int v) {
        return Math.max(-8, Math.min(8, v));
    }

    /** The player's own blocks in the way (never the ground): what it would have to knock down to build exactly. */
    public static List<BlockPos> clashes(ServerLevel level, PaBlueprint bp, Preview pv) {
        List<BlockPos> out = new ArrayList<>();
        for (PaBlueprint.Spot s : bp.spots(pv.anchor, pv.mirror, pv.rot)) {
            BlockState now = level.getBlockState(s.pos());
            if (now.isAir() || same(now, s.state()) || secondHalf(s.state()) && now.getBlock() == s.state().getBlock()) continue;
            if (PaBlueprint.naturalAt(level, s.pos())) continue;
            out.add(s.pos());
        }
        return out;
    }

    // ------------------------------------------------------------------ materials

    /** What it can lay hands on for a build: its pack and its player's chests near the spot and near itself. */
    static Map<Item, Integer> have(PlayerAssistantEntity a, @Nullable BlockPos site) {
        Map<Item, Integer> m = new HashMap<>();
        for (ItemStack s : a.getInventoryItems()) if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum);
        ItemStack hand = a.getMainHandItem();
        if (!hand.isEmpty()) m.merge(hand.getItem(), hand.getCount(), Integer::sum);
        for (Chest c : chests(a, site)) c.counts.forEach((k, v) -> m.merge(k, v, Integer::sum));
        return m;
    }

    record Chest(BlockPos pos, Map<Item, Integer> counts) {}

    /** The containers it may take from near the site and near itself (the player's own: never a town's stores). */
    static List<Chest> chests(PlayerAssistantEntity a, @Nullable BlockPos site) {
        List<Chest> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        PlayerAssistantEntity was = PaChests.askAs(a);
        try {
            for (BlockPos from : site == null ? List.of(a.blockPosition()) : List.of(a.blockPosition(), site)) {
                for (com.jrpetty.mcassistant.entity.ZoneChests.Found f : com.jrpetty.mcassistant.entity.ZoneChests.around(a.level(), from, 20, 6)) {
                    if (!seen.add(f.pos().asLong()) || !f.stillThere()) continue;
                    if (PaContainers.refuse(a, f.pos()) != null) continue;
                    Container c = PaContainers.open(a, f.pos());
                    if (c == null) continue;
                    Map<Item, Integer> m = new HashMap<>();
                    for (int i = 0; i < c.getContainerSize(); i++) { ItemStack s = c.getItem(i); if (!s.isEmpty()) m.merge(s.getItem(), s.getCount(), Integer::sum); }
                    out.add(new Chest(f.pos(), m));
                }
            }
        } finally {
            PaChests.askAs(was);
        }
        return out;
    }

    /** What is still to place, item by item, for the build (or its preview) as the world stands now. */
    static Map<Item, Integer> stillNeeded(ServerLevel level, PaBlueprint bp, BlockPos anchor, boolean mirror, int rot) {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (PaBlueprint.Spot s : bp.spots(anchor, mirror, rot)) {
            if (s.state().isAir()) continue;
            if (same(level.getBlockState(s.pos()), s.state())) continue;
            PaBlueprint.Cost c = PaBlueprint.costOf(s.state());
            if (c != null) m.merge(c.item(), c.n(), Integer::sum);
        }
        return m;
    }

    /** "12 oak planks, 4 glass panes and a door": a short list in words. */
    public static String words(Map<Item, Integer> m, int most) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        for (Map.Entry<Item, Integer> e : m.entrySet()) {
            if (e.getValue() <= 0) continue;
            if (i++ >= most) { parts.add("a few other bits"); break; }
            String n = new ItemStack(e.getKey()).getHoverName().getString().toLowerCase(java.util.Locale.ROOT);
            parts.add(e.getValue() == 1 ? (n.matches("^[aeiou].*") ? "an " : "a ") + n : e.getValue() + " " + plural(n, e.getValue()));
        }
        if (parts.isEmpty()) return "nothing";
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    static String plural(String n, int k) {
        if (java.util.Set.of("bread", "wheat", "sugar", "string", "leather", "paper", "coal", "redstone", "glowstone", "honey", "milk", "wool").contains(n.substring(n.lastIndexOf(' ') + 1))) return n;
        if (k == 1 || n.endsWith("s") || n.endsWith("glass") || n.endsWith("wool") || n.endsWith("stone") || n.endsWith("dirt") || n.endsWith("sand")
            || n.endsWith("gravel") || n.endsWith("terracotta") || n.endsWith("concrete") || n.endsWith("clay") || n.endsWith("snow")) return n;
        if (n.endsWith("y") && !n.endsWith("ey")) return n.substring(0, n.length() - 1) + "ies";
        if (n.endsWith("ch") || n.endsWith("sh") || n.endsWith("x")) return n + "es";
        return n + "s";
    }

    // ------------------------------------------------------------------ build: confirmed

    /** "Build it": the run made, a step on its plan (it shows in Jobs and on the HUD), the ghost kept as its guide. */
    public static void confirm(ServerPlayer p, PlayerAssistantEntity a) {
        Preview pv = PREVIEW.get(p.getUUID());
        if (pv == null) return;
        PaBlueprint bp = PaBlueprints.find(p.getUUID(), pv.name);
        if (bp == null) return;
        if (!a.obeys(p)) return;
        for (PaBlueprint.Spot s : bp.spots(pv.anchor, pv.mirror, pv.rot)) {
            if (!s.state().isAir() && PaSafety.offLimits(a, s.pos())) {
                PaTalk.reply(a, PaLogiTalk.line(a, "kit.bp_offlimits"));
                PaKit.tell(p, "bad", "Not built: part of it falls on a town's ground or someone else's land. Next: nudge it or aim again somewhere that's yours.");
                return;
            }
        }
        Run r = start(a, bp, pv.anchor, pv.rot, pv.mirror, pv.clearMine);
        pv.building = true;
        PaKit.sendPreview(p, a, bp, pv, false);
        PaKit.tell(p, "ok", "Building \"" + bp.name + "\": " + r.total + " blocks to see to, layer by layer from the ground up. The ghost fills in as it goes; Stop keeps its place.");
    }

    /** The build over: its ghost goes (if it is still this build's). */
    static void endGhost(PlayerAssistantEntity a, Run r) {
        if (a.getOwnerId() == null) return;
        Preview pv = PREVIEW.get(a.getOwnerId());
        if (pv == null || !pv.building || !pv.name.equalsIgnoreCase(r.name) || !pv.anchor.equals(r.anchor)) return;
        PREVIEW.remove(a.getOwnerId());
        if (a.getOwnerPlayer() instanceof ServerPlayer sp) PaKit.ghostOff(sp);
    }

    /** Start (or carry on) a build from a blueprint at a spot. Public for the tests. */
    public static Run start(PlayerAssistantEntity a, PaBlueprint bp, BlockPos anchor, int rot, boolean mirror, boolean clearMine) {
        ServerLevel level = (ServerLevel) a.level();
        Run r = new Run();
        r.bp = bp;
        r.name = bp.name;
        r.anchor = anchor.immutable();
        r.rot = rot;
        r.mirror = mirror;
        r.clearMine = clearMine;
        r.order.addAll(order(bp, anchor, mirror, rot));
        r.layers = bp.sy;
        for (Work w : r.order) if (wanted(level, w)) r.total++;
        r.started = level.getGameTime();
        Run old = RUNS.put(a.getUUID(), r);
        if (old != null) old.stopped = true;
        // a step on its plan: the Jobs tab, the HUD, pause and cancel as any job
        a.plan.steps.removeIf(s -> s.task != null && s.task.kind == PaTask.Kind.BLUEPRINT);
        if (a.plan.current != null && a.plan.current.task != null && a.plan.current.task.kind == PaTask.Kind.BLUEPRINT) {
            a.plan.current = null;
            a.task = null;
        }
        PaPlan.Step s = PaOrders.step("build " + bp.name + " from the blueprint", PaIntent.Kind.BUILD, Math.max(120, r.total * 2));
        s.task = new PaTask(PaTask.Kind.BLUEPRINT, anchor, "bp:" + rot + ":" + (mirror ? 1 : 0) + ":" + (clearMine ? 1 : 0) + ":" + bp.name, r.total);
        s.centre = anchor;
        a.plan.steps.add(s);
        a.plan.paused = false;
        Map<Item, Integer> need = stillNeeded(level, bp, anchor, mirror, rot), have = have(a, anchor), shortOf = new LinkedHashMap<>();
        need.forEach((k, v) -> { int h = have.getOrDefault(k, 0); if (h < v) shortOf.put(k, v - h); });
        if (shortOf.isEmpty()) PaTalk.reply(a, PaLogiTalk.line(a, "kit.bp_start", "item", bp.name, "n", String.valueOf(r.total)));
        else PaTalk.reply(a, PaLogiTalk.line(a, "kit.bp_start_short", "item", bp.name, "what", words(shortOf, 4)), shortChoices());
        return r;
    }

    /** After a restart (its plan kept, its run not): the run made again from the task's own note of it. */
    @Nullable
    static Run restore(PlayerAssistantEntity a, PaTask t) {
        if (t.pos == null || !t.item.startsWith("bp:") || a.getOwnerId() == null) return null;
        String[] w = t.item.substring(3).split(":", 4);
        if (w.length < 4) return null;
        PaBlueprint bp = PaBlueprints.find(a.getOwnerId(), w[3]);
        if (bp == null) return null;
        ServerLevel level = (ServerLevel) a.level();
        Run r = new Run();
        r.bp = bp;
        r.name = bp.name;
        r.anchor = t.pos;
        r.rot = Integer.parseInt(w[0]) & 3;
        r.mirror = w[1].equals("1");
        r.clearMine = w[2].equals("1");
        r.order.addAll(order(bp, r.anchor, r.mirror, r.rot));
        r.layers = bp.sy;
        for (Work wk : r.order) if (wanted(level, wk)) r.total++;
        r.started = level.getGameTime();
        RUNS.put(a.getUUID(), r);
        return r;
    }

    static List<PaUnderstand.Choice> shortChoices() {
        return List.of(new PaUnderstand.Choice("Get what's short", "#kit bp_fetch"), new PaUnderstand.Choice("Buy it at the market", "#kit bp_buy"));
    }

    /** "Get what's short": each short item crafted from what's at hand, or gone for (PaCraft's own chain). */
    public static void fetchShort(ServerPlayer p, PlayerAssistantEntity a) {
        Run r = run(a);
        Preview pv = PREVIEW.get(p.getUUID());
        PaBlueprint bp = r != null ? r.bp : pv != null ? PaBlueprints.find(p.getUUID(), pv.name) : null;
        if (bp == null) return;
        BlockPos anchor = r != null ? r.anchor : pv.anchor;
        boolean mirror = r != null ? r.mirror : pv.mirror;
        int rot = r != null ? r.rot : pv.rot;
        Map<Item, Integer> need = stillNeeded(p.serverLevel(), bp, anchor, mirror, rot), have = have(a, anchor);
        List<PaIntent> intents = new ArrayList<>();
        List<String> said = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : need.entrySet()) {
            int shortBy = e.getValue() - have.getOrDefault(e.getKey(), 0);
            if (shortBy <= 0) continue;
            String words = com.jrpetty.mcassistant.entity.RecipeBook.words(e.getKey());
            intents.add(new PaIntent(PaIntent.Kind.CRAFT, words, shortBy, PaIntent.Place.NONE, "#go craft " + shortBy + " " + words));
            said.add(shortBy + " " + words);
            if (intents.size() >= 6) break;
        }
        if (intents.isEmpty()) {
            PaTalk.reply(a, PaLogiTalk.line(a, "kit.bp_nothing_short"));
            PaKit.tell(p, "ok", "Nothing's short: its pack and your chests near the spot have it all.");
            return;
        }
        // the gathering goes before the build, which waits behind it
        List<PaPlan.Step> keep = new ArrayList<>(a.plan.steps);
        a.plan.steps.clear();
        PaPlan.Step cur = a.plan.current;
        if (cur != null && cur.task != null && cur.task.kind == PaTask.Kind.BLUEPRINT) { a.plan.current = null; a.task = null; cur.fed = false; cur.started = -1; keep.add(0, cur); }
        PaOrders.run(p, a, intents, PaOrders.Look.NONE, "get " + String.join(", ", said));
        a.plan.steps.addAll(keep);
        PaTalk.reply(a, PaLogiTalk.line(a, "kit.bp_fetching", "what", String.join(", ", said)));
        PaKit.tell(p, "ok", "Getting " + String.join(", ", said) + " first: it crafts from your things and goes for what it can't make. The build waits behind it in Jobs.");
    }

    /** "Buy it at the market": what's short, on an errand's shopping list. */
    public static void buyShort(ServerPlayer p, PlayerAssistantEntity a) {
        Run r = run(a);
        Preview pv = PREVIEW.get(p.getUUID());
        PaBlueprint bp = r != null ? r.bp : pv != null ? PaBlueprints.find(p.getUUID(), pv.name) : null;
        if (bp == null) return;
        BlockPos anchor = r != null ? r.anchor : pv.anchor;
        Map<Item, Integer> need = stillNeeded(p.serverLevel(), bp, anchor, r != null ? r.mirror : pv.mirror, r != null ? r.rot : pv.rot), have = have(a, anchor);
        Map<Item, Integer> list = new LinkedHashMap<>();
        need.forEach((k, v) -> { int s = v - have.getOrDefault(k, 0); if (s > 0) list.put(k, s); });
        if (list.isEmpty()) {
            PaTalk.reply(a, PaLogiTalk.line(a, "kit.bp_nothing_short"));
            PaKit.tell(p, "ok", "Nothing's short, so nothing to buy.");
            return;
        }
        PaErrand.Draft d = PaErrand.draft(p.getUUID());
        d.buy.clear();
        list.forEach((k, v) -> d.buy.put(BuiltInRegistries.ITEM.getKey(k).toString(), v));
        PaKit.openErrand(p, a);
        PaKit.tell(p, "ok", "What's short is on the shopping list: " + words(list, 4) + ". Next: give it coins and press Send.");
    }

    /** "Stop the build": the run kept, so "Carry on" picks it up where it was. */
    public static void stop(PlayerAssistantEntity a) {
        Run r = run(a);
        if (r != null) r.stopped = true;
        a.plan.steps.removeIf(s -> s.task != null && s.task.kind == PaTask.Kind.BLUEPRINT);
        if (a.task != null && a.task.kind == PaTask.Kind.BLUEPRINT) { a.task = null; a.plan.current = null; }
    }

    /** "Carry on with the build". */
    public static void resume(ServerPlayer p, PlayerAssistantEntity a) {
        Run r = run(a);
        if (r == null) { PaKit.tell(p, "warn", "There's no build to carry on with. Place a blueprint first."); return; }
        Run n = start(a, r.bp, r.anchor, r.rot, r.mirror, r.clearMine);
        PaKit.tell(p, "ok", "Carrying on with \"" + r.name + "\": " + n.total + " blocks still to see to.");
    }

    /** For the screen: the run in a tag. */
    static CompoundTag runTag(Run r) {
        CompoundTag t = new CompoundTag();
        t.putString("name", r.name);
        t.putInt("placed", r.placed + r.dug);
        t.putInt("total", r.total);
        t.putInt("layer", r.layer + 1);
        t.putInt("layers", r.layers);
        t.putInt("pct", r.percent());
        t.putBoolean("done", r.done);
        t.putBoolean("stopped", r.stopped);
        t.putInt("around", r.aroundYours);
        t.putString("last", r.last);
        ListTag miss = new ListTag();
        r.missing.forEach((k, v) -> { CompoundTag m = new CompoundTag(); m.putString("i", BuiltInRegistries.ITEM.getKey(k).toString()); m.putInt("n", v); miss.add(m); });
        t.put("missing", miss);
        return t;
    }
}
