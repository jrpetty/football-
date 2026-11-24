package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import com.jrpetty.mcassistant.pa.PaLogiOrder.Op;
import com.jrpetty.mcassistant.pa.PaLogiOrder.Ref;
import com.jrpetty.mcassistant.pa.PaUnderstand.Choice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [pa36] The Player AI Assistant's logistics brain: what a chest, gathering or logistics order (PaLogiOrder) means here
 * and now (which containers "this chest", "the iron chest", "my storage", "the chest by the furnace" are; how many "a
 * stack" or "until it's full" is), the work it becomes (PaPlan steps carried out by PaLogiJob), the answers given at
 * once (counts, finds, names, the drop-off, the storage map), the standing jobs ("keep this chest stocked with 64
 * bread", "keep the furnaces fuelled", the automatic drop-off), and the one seam every hand action passes ({@link #act}).
 *
 * <p>Calls for the memory system's standing rules ("every morning, refill the furnace"): {@link #depositTo},
 * {@link #keepStocked}, {@link #fill}, {@link #fetchFor}, {@link #order}; or any order's words through
 * PaOrders.rule, which reads them with the same reader.
 */
public final class PaLogistics {

    private PaLogistics() {}

    // ------------------------------------------------------------------ the seam

    private static final Map<PlayerAssistantEntity, Integer> NEXT_HAND = new WeakHashMap<>();

    /**
     * The one seam for every hand action of the logistics (a stack into or out of a container, a handful into a
     * composter, a stack handed over): only when its hand is free, at a player's pace (PaPace.hand: about 0.6 s, a
     * little jitter, never quicker), with an arm swing. True when the action was done.
     */
    public static boolean act(PlayerAssistantEntity a, java.util.function.BooleanSupplier action) {
        Integer next = NEXT_HAND.get(a);
        if (next != null && a.tickCount < next) return false;
        boolean did = action.getAsBoolean();
        if (did) {
            NEXT_HAND.put(a, a.tickCount + Math.max(PaPace.HAND_MIN, PaPace.hand(a)));
            a.swing(InteractionHand.MAIN_HAND);
            a.noteAction();
        }
        return did;
    }

    /** Is its hand free for the next action? */
    public static boolean handFree(PlayerAssistantEntity a) {
        Integer next = NEXT_HAND.get(a);
        return next == null || a.tickCount >= next;
    }

    /** May it break this block? (The mining ladder: the only gate on breaking; carrying and crafting with ore are fine.) */
    public static boolean mayBreak(PlayerAssistantEntity a, BlockState s) {
        return PaLevels.canMine(a, s);
    }

    // ------------------------------------------------------------------ jobs in flight

    private static final Map<PaTask, PaLogiJob> JOBS = java.util.Collections.synchronizedMap(new WeakHashMap<>());

    @Nullable
    static PaLogiJob job(PlayerAssistantEntity a, PaTask t) {
        if (t == null || t.kind != PaTask.Kind.LOGI) return null;
        PaLogiJob j = JOBS.get(t);
        if (j == null) {
            j = PaLogiJob.load(t.item);                     // after a save: the order and its places, its count starting over
            if (j != null) JOBS.put(t, j);
        }
        return j;
    }

    /** The job of a plan step, if it is one of ours. */
    @Nullable
    public static PaLogiJob jobOf(PlayerAssistantEntity a, @Nullable PaPlan.Step s) {
        return s == null || s.task == null ? null : job(a, s.task);
    }

    static PaPlan.Step step(PaLogiJob j, String label, int eta) {
        PaPlan.Step s = PaOrders.step(label, PaIntent.Kind.LOGI, eta);
        s.task = new PaTask(PaTask.Kind.LOGI, j.primary(), j.save(), j.target);
        JOBS.put(s.task, j);
        return s;
    }

    /** [pa36] "64/100 · trip 2 · chest 40%" for a step's progress (PaPlan hook). */
    public static String tally(PlayerAssistantEntity a, PaPlan.Step s) {
        PaLogiJob j = jobOf(a, s);
        return j == null ? "" : j.tally(a);
    }

    /** 0..100, or -1 (PaPlan hook). */
    public static int percent(PlayerAssistantEntity a, PaPlan.Step s) {
        PaLogiJob j = jobOf(a, s);
        return j == null ? -1 : j.percent();
    }

    /** Did the job say its own last word (so the plan doesn't add a "done")? PaBrain hook. */
    public static boolean saidItsOwn(@Nullable PaTask t) {
        if (t == null || t.kind != PaTask.Kind.LOGI) return false;
        PaLogiJob j = JOBS.get(t);
        return j != null && j.ownWords;
    }

    // ------------------------------------------------------------------ recent pings and looks (what "this" and "that" mean)

    private record Seen(BlockPos pos, String dim, long at) {}

    private static final Map<UUID, Deque<Seen>> SEEN = new ConcurrentHashMap<>();
    /** How long a pinged or opened container stays "this chest" when nothing else is pointed at (ms). */
    public static final long SEEN_MS = 30_000;

    /** A player pinged, looked at while speaking, or opened a container: remember it as "this" for a little while. */
    public static void seen(ServerPlayer p, BlockPos pos) {
        if (p == null || pos == null || !PaContainers.is(p.level(), pos)) return;
        Deque<Seen> d = SEEN.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
        String dim = p.level().dimension().location().toString();
        d.removeIf(s -> s.pos.equals(pos) && s.dim.equals(dim));
        d.addFirst(new Seen(pos.immutable(), dim, System.currentTimeMillis()));
        while (d.size() > 4) d.removeLast();
    }

    /** Recently pinged or opened containers, newest first. */
    static List<BlockPos> recent(ServerPlayer p, long withinMs) {
        List<BlockPos> out = new ArrayList<>();
        Deque<Seen> d = p == null ? null : SEEN.get(p.getUUID());
        if (d == null) return out;
        long now = System.currentTimeMillis();
        String dim = p.level().dimension().location().toString();
        for (Seen s : d) if (now - s.at <= withinMs && s.dim.equals(dim)) out.add(s.pos);
        return out;
    }

    /** Tests: forget what was pinged. */
    public static void forgetSeenForTests(UUID u) {
        SEEN.remove(u);
    }

    // ------------------------------------------------------------------ places

    /** The containers a place means, nearest first (empty when none). {@code k} is which pointed thing (0 first, 1 second). */
    static List<BlockPos> resolve(ServerPlayer p, PlayerAssistantEntity a, Ref r, PaOrders.Look look, int k) {
        List<BlockPos> out = new ArrayList<>();
        Level level = a.level();
        UUID owner = a.getOwnerId();
        String dim = level.dimension().location().toString();
        PaStore.Owner st = owner == null ? new PaStore.Owner() : PaStore.of(owner);
        BlockPos centre = p != null ? p.blockPosition() : a.blockPosition();
        switch (r.kind()) {
            case POINT, POINT2, DEF, IT -> {
                List<BlockPos> pointed = new ArrayList<>();
                if (look != null) {
                    if (look.block() != null) pointed.add(look.block());
                    for (BlockPos b : look.more()) if (!pointed.contains(b)) pointed.add(b);
                }
                for (BlockPos b : recent(p, SEEN_MS)) if (!pointed.contains(b)) pointed.add(b);
                List<BlockPos> boxes = new ArrayList<>();
                for (BlockPos b : pointed) {
                    BlockPos c = containerAt(level, b);
                    if (c != null && !boxes.contains(c) && PaContainers.fits(r.type(), PaContainers.type(level, c))) boxes.add(c);
                }
                int idx = r.kind() == Ref.Kind.POINT2 ? 1 : (r.kind() == Ref.Kind.IT ? 0 : k);
                if (r.kind() == Ref.Kind.IT && a.mind != null) {
                    BlockPos last = LAST.get(a);
                    if (last != null) { out.add(last); return out; }
                }
                if (idx < boxes.size()) { out.add(boxes.get(idx)); return out; }
                if (r.kind() == Ref.Kind.DEF) return nearest(a, st, dim, centre, r.type(), 24, true);
                return out;
            }
            case NAMED -> {
                String name = r.name();
                List<PaStore.Known> hits = new ArrayList<>();
                for (PaStore.Known kn : st.known) if (kn.dim.equals(dim) && !kn.name.isEmpty() && nameMatches(kn.name, name)) hits.add(kn);
                if (hits.isEmpty()) {
                    // "the wood chest" nobody named: the one that's mostly logs
                    Predicate<ItemStack> what = PaLogiItems.of(PaLogiParse.item(name, ""));
                    for (PaStore.Known kn : st.known) {
                        if (!kn.dim.equals(dim) || kn.snap.isEmpty()) continue;
                        Map.Entry<String, Integer> top = kn.snap.entrySet().iterator().next();
                        var it = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(top.getKey()));
                        if (what.test(new ItemStack(it))) hits.add(kn);
                    }
                }
                hits.sort(Comparator.comparingDouble(kn -> kn.at().distSqr(centre)));
                for (PaStore.Known kn : hits) if (PaContainers.fits(r.type(), PaContainers.type(level, kn.at())) || r.type().isEmpty()) out.add(kn.at());
                return out;
            }
            case NEAR -> {
                BlockPos lm = landmark(p, a, r.name());
                if (lm == null) return out;
                List<BlockPos> near = nearest(a, st, dim, lm, r.type(), 8, true);
                if (near.isEmpty()) near = containersAround(level, lm, 6, r.type());
                near.sort(Comparator.comparingDouble(b -> b.distSqr(lm)));
                return near;
            }
            case STORAGE, HOME -> {
                BlockPos home = r.kind() == Ref.Kind.HOME && PaOrders.homeOf(a) != null ? PaOrders.homeOf(a) : centre;
                for (PaStore.Known kn : st.known) if (kn.dim.equals(dim) && (kn.storage || "storage".equals(kn.name))) out.add(kn.at());
                if (out.isEmpty()) { PaStore.Known d = st.dropoff(dim); if (d != null) out.add(d.at()); }
                List<BlockPos> rest = nearest(a, st, dim, home, r.type().isEmpty() || r.type().equals("chest") ? "chest" : r.type(), 48, true);
                for (BlockPos b : rest) if (!out.contains(b)) out.add(b);
                return out;
            }
            case DROPOFF -> {
                PaStore.Known d = st.dropoff(dim);
                if (d != null) out.add(d.at());
                return out;
            }
            case KNOWN -> {
                return nearest(a, st, dim, centre, r.type(), 96, false);
            }
            case NEAREST -> {
                return nearest(a, st, dim, a.blockPosition(), r.type(), 24, true);
            }
            case ENDER -> {
                for (BlockPos b : BlockPos.betweenClosed(centre.offset(-16, -6, -16), centre.offset(16, 6, 16)))
                    if (level.getBlockState(b).getBlock() instanceof net.minecraft.world.level.block.EnderChestBlock) out.add(b.immutable());
                out.sort(Comparator.comparingDouble(b -> b.distSqr(centre)));
                return out;
            }
            case WAYPOINT -> {
                BlockPos w = waypoint(a, r.name());
                if (w != null) out.addAll(containersAround(level, w, 10, ""));
                return out;
            }
            default -> {
                return out;
            }
        }
    }

    /** The containers at or right beside a block looked at (a chest's side, the block under a barrel). */
    @Nullable
    static BlockPos containerAt(Level level, BlockPos b) {
        if (PaContainers.is(level, b)) return b;
        return null;
    }

    static boolean nameMatches(String known, String said) {
        String k = known.toLowerCase(java.util.Locale.ROOT), s = said.toLowerCase(java.util.Locale.ROOT);
        if (k.equals(s) || k.contains(s) || s.contains(k)) return true;
        String ki = PaLogiParse.item(k, ""), si = PaLogiParse.item(s, "");
        return !ki.isEmpty() && ki.equals(si);
    }

    /** Known containers it may use, nearest the spot first (within a radius), of the type said. */
    static List<BlockPos> nearest(PlayerAssistantEntity a, PaStore.Owner st, String dim, BlockPos centre, String type, int radius, boolean pointedToo) {
        List<BlockPos> out = new ArrayList<>();
        Level level = a.level();
        for (PaStore.Known kn : st.known) {
            if (!kn.dim.equals(dim)) continue;
            BlockPos b = kn.at();
            if (b.distSqr(centre) > (double) radius * radius || !level.isLoaded(b) || !PaContainers.is(level, b)) continue;
            if (!PaContainers.fits(type.isEmpty() ? "" : type, PaContainers.type(level, b))) continue;
            out.add(b);
        }
        if (pointedToo) {
            PaData.Rec r = a.rec();
            if (r != null) for (long l : r.pointed) {
                BlockPos b = BlockPos.of(l);
                if (out.contains(b) || b.distSqr(centre) > (double) radius * radius || !level.isLoaded(b) || !PaContainers.is(level, b)) continue;
                if (PaContainers.fits(type.isEmpty() ? "" : type, PaContainers.type(level, b))) out.add(b);
            }
        }
        out.sort(Comparator.comparingDouble(b -> b.distSqr(centre)));
        return out;
    }

    static List<BlockPos> containersAround(Level level, BlockPos c, int r, String type) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos b : BlockPos.betweenClosed(c.offset(-r, -3, -r), c.offset(r, 3, r))) {
            if (PaContainers.is(level, b) && PaContainers.fits(type, PaContainers.type(level, b))) out.add(b.immutable());
        }
        out.sort(Comparator.comparingDouble(b -> b.distSqr(c)));
        return out;
    }

    /** A landmark block near its player (or it): "furnace", "crafting table", "door", "bed", "me", "home"... */
    @Nullable
    static BlockPos landmark(ServerPlayer p, PlayerAssistantEntity a, String name) {
        BlockPos centre = p != null ? p.blockPosition() : a.blockPosition();
        String n = name.toLowerCase(java.util.Locale.ROOT).replaceAll("^(?:the|my|our) ", "");
        if (n.matches("me|you|here")) return n.equals("you") ? a.blockPosition() : centre;
        if (n.matches("house|base|home")) return PaOrders.homeOf(a) != null ? PaOrders.homeOf(a) : centre;
        Predicate<BlockState> what = switch (n.replaceAll("s$", "")) {
            case "furnace" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.AbstractFurnaceBlock;
            case "crafting table", "table", "workbench" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.CraftingTableBlock;
            case "door", "front door", "entrance", "exit" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock;
            case "bed", "my bed", "bedroom" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.BedBlock;
            case "anvil" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.AnvilBlock;
            case "smoker" -> s -> BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().equals("smoker");
            case "enchanting table" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.EnchantingTableBlock;
            case "brewing stand" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.BrewingStandBlock;
            case "farm", "field" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.FarmBlock;
            case "ladder" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.LadderBlock;
            case "stair" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.StairBlock;
            case "torch", "lamp" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.TorchBlock || BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().contains("lantern");
            case "campfire", "fire" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.CampfireBlock;
            case "gate", "fence" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock || s.getBlock() instanceof net.minecraft.world.level.block.FenceBlock;
            case "portal", "nether portal" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.NetherPortalBlock;
            case "stonecutter" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.StonecutterBlock;
            case "smithing table" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.SmithingTableBlock;
            case "loom" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.LoomBlock;
            case "cauldron" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.AbstractCauldronBlock;
            case "composter" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.ComposterBlock;
            case "lectern" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.LecternBlock;
            case "bookshelf", "bookshelve" -> s -> s.is(net.minecraft.world.level.block.Blocks.BOOKSHELF);
            case "beacon" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.BeaconBlock;
            case "bell" -> s -> s.getBlock() instanceof net.minecraft.world.level.block.BellBlock;
            default -> null;
        };
        if (what == null) {
            BlockPos w = waypoint(a, n);
            return w;
        }
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        Level level = a.level();
        for (BlockPos b : BlockPos.betweenClosed(centre.offset(-20, -5, -20), centre.offset(20, 5, 20))) {
            if (!what.test(level.getBlockState(b))) continue;
            double d = b.distSqr(centre);
            if (d < bd) { bd = d; best = b.immutable(); }
        }
        return best;
    }

    @Nullable
    static BlockPos waypoint(PlayerAssistantEntity a, String name) {
        PaData.Rec r = a.rec();
        if (r == null) return null;
        String n = name.toLowerCase(java.util.Locale.ROOT).replaceAll("^(?:the|my|our) ", "");
        for (Map.Entry<String, BlockPos> e : r.waypoints.entrySet()) {
            String k = e.getKey().toLowerCase(java.util.Locale.ROOT).replaceAll("^(?:the|my|our) ", "");
            if (k.equals(n) || k.replace(' ', '_').equals(n.replace(' ', '_'))) return e.getValue();
        }
        if (n.matches("home|base|house")) return PaOrders.homeOf(a);
        return null;
    }

    /** The container it used last (for "it" and "in there too"). */
    static final Map<PlayerAssistantEntity, BlockPos> LAST = new WeakHashMap<>();

    /** Another container near a full one, of its kind, that it may use: the overflow. */
    @Nullable
    static BlockPos nextNear(PlayerAssistantEntity a, BlockPos full, String type, List<BlockPos> skip, ItemStack like) {
        UUID owner = a.getOwnerId();
        if (owner == null) return null;
        String dim = a.level().dimension().location().toString();
        List<BlockPos> near = nearest(a, PaStore.of(owner), dim, full, type.isEmpty() ? "chest" : type, 12, true);
        for (BlockPos b : near) {
            if (skip.contains(b) || b.equals(full) || sameChest(a.level(), b, full)) continue;
            if (PaContainers.refuse(a, b) != null) continue;
            Container c = PaContainers.open(a, b);
            if (c != null && (like.isEmpty() ? PaContainers.freeSlots(c) > 0 : PaContainers.room(c, like) > 0)) return b;
        }
        return null;
    }

    /** Two halves of one double chest? */
    static boolean sameChest(Level level, BlockPos x, BlockPos y) {
        BlockState s = level.getBlockState(x);
        if (!(s.getBlock() instanceof net.minecraft.world.level.block.ChestBlock) || s.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
            == net.minecraft.world.level.block.state.properties.ChestType.SINGLE) return false;
        return x.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(s)).equals(y);
    }

    /** "the iron chest", "that chest", "the barrel by the door": how it names a container in a reply. */
    static String called(PlayerAssistantEntity a, BlockPos b, @Nullable Ref said) {
        UUID owner = a.getOwnerId();
        String dim = a.level().dimension().location().toString();
        PaStore.Known k = owner == null ? null : PaStore.of(owner).at(b, dim);
        String type = PaContainers.type(a.level(), b);
        if (k != null && !k.name.isEmpty()) return k.name.equals("storage") ? "the storage" : "the " + k.name + (k.name.endsWith(type) ? "" : " " + type);
        if (k != null && k.dropoff) return "the drop-off";
        if (said != null && (said.kind() == Ref.Kind.POINT || said.kind() == Ref.Kind.POINT2)) return "that " + type;
        return "the " + type;
    }

    // ------------------------------------------------------------------ planning (PaOrders hook)

    /**
     * A LOGI step read from what its player said: answered at once (counts, finds, names...) or planned as work.
     * Null when done or planned; else why not (said back with fixes).
     */
    @Nullable
    public static String plan(ServerPlayer p, PlayerAssistantEntity a, PaIntent i, PaOrders.Look look, List<PaPlan.Step> out, String said) {
        PaLogiOrder o = PaLogiOrder.parse(i.item());
        if (o == null) return "I didn't follow that.";
        return PaLogiPlan.plan(p, a, o, look, out, said);
    }

    /** Run one order now, as if said (the memory system's rules and the panel's buttons use this). */
    public static String order(ServerPlayer p, PlayerAssistantEntity a, PaLogiOrder o, PaOrders.Look look, String said) {
        List<PaPlan.Step> out = new ArrayList<>();
        PlayerAssistantEntity before = PaChests.askAs(a);
        try {
            String why = PaLogiPlan.plan(p, a, o, look == null ? PaOrders.Look.NONE : look, out, said);
            if (why != null) {
                PaTalk.reply(a, why, PaOrders.fixesFor(why, said));
                return why;
            }
            if (!out.isEmpty()) PaOrders.start(p, a, out, said);
            return null;
        } finally {
            PaChests.askAs(before);
        }
    }

    /** Memory hook: put {@code qty} of an item from its pack into this container (0 for all it has). */
    public static String depositTo(PlayerAssistantEntity a, BlockPos container, String item, int qty) {
        PaLogiOrder o = new PaLogiOrder(Op.DEPOSIT, item, qty > 0 ? PaQty.Qty.of(qty) : PaQty.Qty.ALL, Ref.of(Ref.Kind.PACK), Ref.of(Ref.Kind.POINT),
            PaLogiOrder.Src.PACK, "");
        return order(owner(a), a, o, new PaOrders.Look(container, -1), "put " + item + " in the chest");
    }

    /** Memory hook: a standing job, keep this container at {@code n} of an item. */
    public static String keepStocked(PlayerAssistantEntity a, BlockPos container, String item, int n) {
        PaLogiOrder o = new PaLogiOrder(Op.STOCK, item, PaQty.Qty.of(n), Ref.NONE, Ref.of(Ref.Kind.POINT), PaLogiOrder.Src.ANY, "");
        return order(owner(a), a, o, new PaOrders.Look(container, -1), "keep the chest stocked with " + n + " " + item);
    }

    /** Memory hook: gather, craft or smelt until the container holds {@code n} more of an item (0: until it's full). */
    public static String fill(PlayerAssistantEntity a, BlockPos container, String item, int n) {
        PaLogiOrder o = new PaLogiOrder(Op.FILL, item, n > 0 ? PaQty.Qty.of(n) : PaQty.Qty.FULL, Ref.NONE, Ref.of(Ref.Kind.POINT), PaLogiOrder.Src.ANY, "");
        return order(owner(a), a, o, new PaOrders.Look(container, -1), "fill the chest with " + item);
    }

    /** Memory hook: bring its player {@code n} of an item from their known chests. */
    public static String fetchFor(PlayerAssistantEntity a, String item, int n) {
        PaLogiOrder o = new PaLogiOrder(Op.FETCH, item, n > 0 ? PaQty.Qty.of(n) : PaQty.Qty.SOME, Ref.of(Ref.Kind.KNOWN), Ref.of(Ref.Kind.ME), PaLogiOrder.Src.KNOWN, "");
        return order(owner(a), a, o, PaOrders.Look.NONE, "bring me " + item);
    }

    @Nullable
    static ServerPlayer owner(PlayerAssistantEntity a) {
        return a.getOwnerPlayer() instanceof ServerPlayer sp ? sp : null;
    }

    // ------------------------------------------------------------------ standing jobs (PaBrain hook, every 10 s)

    /** Stock jobs that are due, the furnaces' fuel, the automatic drop-off. Cheap: nothing at all most of the time. */
    public static void standing(PlayerAssistantEntity a) {
        UUID owner = a.getOwnerId();
        if (owner == null) return;
        PaStore.Owner st = PaStore.of(owner);
        autoDrop(a, st);
        if (st.stock.isEmpty()) return;
        long now = a.level().getGameTime();
        String dim = a.level().dimension().location().toString();
        for (PaStore.Stock s : new ArrayList<>(st.stock)) {
            if (now < s.next || !s.dim.equals(dim)) continue;
            s.next = now + s.every;
            PaStore.touch();
            if (queued(a, s)) continue;
            if (s.furnaces) PaLogiPlan.fuelRound(a, s);
            else PaLogiPlan.topUp(a, s);
        }
    }

    /** Is a top-up for this rule already in its plan? */
    static boolean queued(PlayerAssistantEntity a, PaStore.Stock s) {
        for (PaPlan.Step step : a.plan.all()) {
            PaLogiJob j = jobOf(a, step);
            if (j != null && j.rule != null && j.rule.pos == s.pos && j.rule.item.equals(s.item)) return true;
        }
        return false;
    }

    /** When its pack is full while gathering, a trip to the drop-off and straight back to the job. */
    static void autoDrop(PlayerAssistantEntity a, PaStore.Owner st) {
        if (!st.autoDrop) return;
        PaPlan.Step cur = a.plan.current;
        if (cur == null || cur.auto || "LOGI".equals(cur.kind) || a.plan.paused) return;
        if (!("GATHER".equals(cur.kind) || "MINE_ORE".equals(cur.kind) || "MINE_DOWN".equals(cur.kind) || "FARM".equals(cur.kind) || "HUNT".equals(cur.kind)
            || "FISH".equals(cur.kind))) return;
        if (PaContainers.packFreeSlots(a) > 1) return;
        String dim = a.level().dimension().location().toString();
        PaStore.Known d = st.dropoff(dim);
        if (d == null || !a.level().isLoaded(d.at())) return;
        PaLogiJob j = PaLogiJob.of(new PaLogiOrder(Op.EMPTY_PACK, "*", PaQty.Qty.ALL, Ref.of(Ref.Kind.PACK), Ref.of(Ref.Kind.DROPOFF), PaLogiOrder.Src.PACK, ""));
        j.to.add(d.at());
        j.quietStart = true;
        PaPlan.Step drop = step(j, "drop off at the drop-off", 40);
        drop.said = cur.said;
        // the job it was on gives way, and comes straight back after with its count kept
        a.clearQueue();
        a.task = null;
        a.plan.current = null;
        PaBrain.rearm(a, cur);
        a.plan.steps.add(0, cur);
        a.plan.steps.add(0, drop);
        PaTalk.comment(a, 1, PaLogiTalk.line(a, "logi.dropoff_trip"), true);
    }

    /** Its player said stop: what the job under way had moved, said once (PaOrders hook, before everything stops). */
    public static void stopping(PlayerAssistantEntity a) {
        PaLogiJob j = jobOf(a, a.plan.current);
        if (j == null) return;
        j.abandon(a);
        if (j.moved > 0) PaTalk.reply(a, PaLogiTalk.line(a, "logi.stopped", "n", String.valueOf(j.moved)));
    }

    // ------------------------------------------------------------------ counts across storage

    /** How many of a thing there are: per container (known, live when loaded), in its pack, on its player. */
    public record Count(int total, int pack, int player, Map<BlockPos, Integer> chests, Map<String, Integer> byItem) {}

    public static Count count(@Nullable ServerPlayer p, PlayerAssistantEntity a, String item, List<BlockPos> where) {
        Predicate<ItemStack> what = item.equals("*") ? s -> !s.isEmpty() : PaLogiItems.of(item);
        Map<BlockPos, Integer> chests = new LinkedHashMap<>();
        Map<String, Integer> byItem = new LinkedHashMap<>();
        int total = 0;
        UUID owner = a.getOwnerId();
        String dim = a.level().dimension().location().toString();
        for (BlockPos b : where) {
            int n = 0;
            Container c = a.level().isLoaded(b) ? PaContainers.open(a, b) : null;
            if (c != null) {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    ItemStack s = c.getItem(i);
                    if (!s.isEmpty() && what.test(s)) { n += s.getCount(); byItem.merge(PaLogiItems.id(s), s.getCount(), Integer::sum); }
                }
                PaContainers.snapshot(a, b, c);
            } else if (owner != null) {
                PaStore.Known k = PaStore.of(owner).at(b, dim);
                if (k != null) for (Map.Entry<String, Integer> e : k.snap.entrySet()) {
                    var it = BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(e.getKey()));
                    if (what.test(new ItemStack(it))) { n += e.getValue(); byItem.merge(e.getKey(), e.getValue(), Integer::sum); }
                }
            }
            if (n > 0) chests.put(b, n);
            total += n;
        }
        int pack = 0;
        for (ItemStack s : a.getInventoryItems()) if (!s.isEmpty() && what.test(s)) { pack += s.getCount(); byItem.merge(PaLogiItems.id(s), s.getCount(), Integer::sum); }
        int player = 0;
        if (p != null) for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && what.test(s)) { player += s.getCount(); byItem.merge(PaLogiItems.id(s), s.getCount(), Integer::sum); }
        return new Count(total + pack + player, pack, player, chests, byItem);
    }

    static String bearing(Vec3 from, BlockPos to) {
        return PaOrders.bearing(from, Vec3.atCenterOf(to));
    }

    /** Is the gatherer's kind above its rung on the mining ladder? The decline line, or null. */
    @Nullable
    static String locked(PlayerAssistantEntity a, GatherGoal.Kind k, String what) {
        BlockState s = PaLogiItems.block(k);
        if (s == null || mayBreak(a, s)) return null;
        return PaLogiTalk.line(a, "logi.locked_mine", "item", cap(what), "need", String.valueOf(PaLevels.mineLevel(s)), "level", String.valueOf(a.veteranLevel()));
    }

    static List<Choice> lockedChoices(String what) {
        return List.of(new Choice("Gather stone instead", "gather 32 stone"), new Choice("Carry what I mine", "follow me"));
    }

    static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
