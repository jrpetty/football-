package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.pa.PaLogiOrder.Op;
import com.jrpetty.mcassistant.pa.PaLogiOrder.Ref;
import com.jrpetty.mcassistant.pa.PaLogiOrder.Src;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * [pa36] The logistics' own messages to and from the panel: the Storage view's list (each known container with its
 * name, place, top contents and settings) and its buttons (ping, rename, forget, drop-off, keep stocked), the chest
 * wheel's pick-what screen (put in from its pack or yours, take out to you or its pack), the Craft view's search, and
 * the choices its replies carry ("#logi hand", "#logi further"). Payload kind {@link #KIND} on the assistant's own wire.
 */
public final class PaLogiNet {

    private PaLogiNet() {}

    /** Its payload kind on PaPayload (a number of its own, clear of the others'). */
    public static final int KIND = 36;

    // ------------------------------------------------------------------ to the client

    /** Open (or refresh) the Storage view. */
    public static void openStorage(@Nullable ServerPlayer p, PlayerAssistantEntity a) {
        openStorage(p, a, true);
    }

    public static void openStorage(@Nullable ServerPlayer p, PlayerAssistantEntity a, boolean say) {
        if (p == null) return;
        CompoundTag t = storage(p, a);
        t.putString("op", "storage");
        PaNet.send(p, KIND, t);
        if (say) PaTalk.reply(a, PaLogiTalk.line(a, "logi.listed", "n", String.valueOf(t.getList("c", 10).size())));
    }

    /** The storage map for this player, nearest first (sixty at most). */
    static CompoundTag storage(ServerPlayer p, PlayerAssistantEntity a) {
        CompoundTag t = new CompoundTag();
        UUID owner = a.getOwnerId();
        if (owner == null) return t;
        PaStore.Owner st = PaStore.of(owner);
        String dim = p.level().dimension().location().toString();
        List<PaStore.Known> ks = new ArrayList<>();
        for (PaStore.Known k : st.known) if (k.dim.equals(dim)) ks.add(k);
        ks.sort(java.util.Comparator.comparingDouble(k -> k.at().distToCenterSqr(p.position())));
        ListTag l = new ListTag();
        for (PaStore.Known k : ks) {
            if (l.size() >= 60) break;
            BlockPos b = k.at();
            if (p.level().isLoaded(b)) {
                Container c = PaContainers.open(a, b);
                if (c != null) PaContainers.snapshot(a, b, c);
                else if (!PaContainers.is(p.level(), b)) continue;
            }
            CompoundTag c = new CompoundTag();
            c.putInt("x", b.getX()); c.putInt("y", b.getY()); c.putInt("z", b.getZ());
            c.putString("n", k.name);
            c.putString("t", k.block.isEmpty() ? "chest" : PaContainers.type(p.level(), b));
            c.putString("b", k.block);
            c.putInt("d", (int) Math.sqrt(b.distToCenterSqr(p.position())));
            c.putString("dir", PaOrders.bearing(p.position(), Vec3.atCenterOf(b)));
            c.putInt("u", k.used);
            c.putInt("s", k.size);
            c.putBoolean("drop", k.dropoff);
            c.putBoolean("store", k.storage);
            c.putInt("mt", k.takeLevel());                                  // [pa36b] what it may take on its own account
            ListTag items = new ListTag();
            int i = 0;
            for (Map.Entry<String, Integer> e : k.snap.entrySet()) {
                if (i++ >= 6) break;
                CompoundTag it = new CompoundTag();
                it.putString("i", e.getKey());
                it.putInt("n", e.getValue());
                items.add(it);
            }
            c.put("items", items);
            for (PaStore.Stock s : st.stock) if (!s.furnaces && s.pos == k.pos && s.dim.equals(dim)) { c.putString("stock", s.item); c.putInt("stockN", s.n); }
            l.add(c);
        }
        t.put("c", l);
        t.putBoolean("autoDrop", st.autoDrop);
        boolean fuel = st.stock.stream().anyMatch(s -> s.furnaces);
        t.putBoolean("fuelRule", fuel);
        return t;
    }

    /** A container's contents for the "Take out..." picker. */
    static void peek(ServerPlayer p, PlayerAssistantEntity a, BlockPos b) {
        CompoundTag t = new CompoundTag();
        t.putString("op", "peek");
        t.putInt("x", b.getX()); t.putInt("y", b.getY()); t.putInt("z", b.getZ());
        String why = PaContainers.refuse(a, b);
        if (why != null && !why.startsWith("I don't know that chest")) {
            t.putString("why", why);
        } else {
            Container c = PaContainers.open(a, b);
            ListTag items = new ListTag();
            if (c != null) {
                PaContainers.snapshot(a, b, c);
                for (Map.Entry<String, Integer> e : PaContainers.contents(c).entrySet()) {
                    CompoundTag it = new CompoundTag();
                    it.putString("i", e.getKey());
                    it.putInt("n", e.getValue());
                    items.add(it);
                }
            }
            t.put("items", items);
            t.putString("name", PaLogistics.called(a, b, null));
        }
        PaNet.send(p, KIND, t);
    }

    // ------------------------------------------------------------------ from the client (PaServer hook: action "logi")

    public static void act(ServerPlayer p, @Nullable PaData.Rec r, @Nullable PlayerAssistantEntity a, CompoundTag args) {
        if (a == null || r == null || !a.obeys(p)) return;
        String op = args.getString("op");
        BlockPos b = args.contains("x") ? new BlockPos(args.getInt("x"), args.getInt("y"), args.getInt("z")) : null;
        PaOrders.Look look = b == null ? PaOrders.Look.NONE : new PaOrders.Look(b, -1);
        if (b != null) PaLogistics.seen(p, b);
        String item = args.getString("item");
        int n = args.getInt("n");
        PaQty.Qty q = n > 0 ? PaQty.Qty.of(n) : PaQty.Qty.ALL;
        PlayerAssistantEntity before = PaChests.askAs(a);
        try {
            switch (op) {
                case "storage" -> openStorage(p, a, false);
                case "peek" -> { if (b != null) peek(p, a, b); }
                case "ping" -> {
                    if (b == null) return;
                    PaStore.Known k = a.getOwnerId() == null ? null : PaStore.of(a.getOwnerId()).at(b, p.level().dimension().location().toString());
                    PaNet.ping(p, b, k == null ? "Chest" : PaLogistics.cap(k.label()), "GATHER", "found");
                }
                case "rename" -> PaLogiPlan.plan(p, a, PaLogiOrder.of(Op.NAME).withTo(Ref.of(Ref.Kind.POINT)).withArg(clean(args.getString("name"))), look, new ArrayList<>(), "name it");
                case "forget" -> PaLogiPlan.plan(p, a, PaLogiOrder.of(Op.FORGET).withTo(Ref.of(Ref.Kind.POINT)), look, new ArrayList<>(), "forget it");
                case "maytake" -> { if (b != null) mayTake(p, a, b); }
                case "dropoff" -> PaLogiPlan.plan(p, a, PaLogiOrder.of(Op.DROPOFF).withTo(Ref.of(Ref.Kind.POINT)), look, new ArrayList<>(), "make it the drop-off");
                case "autodrop" -> PaLogiPlan.plan(p, a, PaLogiOrder.of(Op.AUTODROP).withArg(args.getBoolean("on") ? "on" : "off"), look, new ArrayList<>(), "drop-off");
                case "unstock" -> PaLogiPlan.plan(p, a, PaLogiOrder.of(Op.UNSTOCK).withTo(Ref.of(Ref.Kind.POINT)), look, new ArrayList<>(), "stop stocking it");
                case "stock" -> said(p, a, new PaLogiOrder(Op.STOCK, item, n > 0 ? PaQty.Qty.of(n) : PaQty.Qty.stacks(1), Ref.NONE, Ref.of(Ref.Kind.POINT), Src.ANY, ""), look,
                    "Keep it stocked with " + (n > 0 ? n + " " : "") + nice(item));
                case "fill" -> said(p, a, new PaLogiOrder(Op.FILL, item, n > 0 ? PaQty.Qty.of(n) : PaQty.Qty.FULL, Ref.NONE, Ref.of(Ref.Kind.POINT), Src.ANY, ""), look,
                    "Fill it with " + (n > 0 ? n + " " : "") + nice(item));
                case "put" -> {
                    if ("me".equals(args.getString("src"))) {
                        // from its player's own inventory: the pick in the screen is the hand-over
                        PaLogiPlan.HANDOVERS.put(p.getUUID(), new PaLogiPlan.Handover(new PaLogiOrder(Op.HANDOVER, item, q, Ref.of(Ref.Kind.ME), Ref.of(Ref.Kind.POINT), Src.PLAYER, ""),
                            b == null ? List.of() : List.of(b), countOn(p, item, n), a.level().getGameTime()));
                        PaLogiPlan.handed(p, a);
                    } else said(p, a, new PaLogiOrder(Op.DEPOSIT, item, q, Ref.of(Ref.Kind.PACK), Ref.of(Ref.Kind.POINT), Src.PACK, ""), look, "Put " + (n > 0 ? n + " " : "") + nice(item) + " in there");
                }
                case "take" -> {
                    boolean me = "me".equals(args.getString("dest"));
                    said(p, a, new PaLogiOrder(me ? Op.FETCH : Op.TAKE, item, q, Ref.of(Ref.Kind.POINT), Ref.of(me ? Ref.Kind.ME : Ref.Kind.PACK), Src.KNOWN, ""), look,
                        (me ? "Bring me " : "Take ") + (n > 0 ? n + " " : "") + nice(item) + " from there");
                }
                case "sort" -> said(p, a, new PaLogiOrder(Op.SORT, "*", PaQty.Qty.NONE, Ref.NONE, Ref.of(Ref.Kind.POINT), Src.ANY, ""), look, "Sort this chest");
                case "empty" -> said(p, a, new PaLogiOrder(Op.MOVE, "*", PaQty.Qty.ALL, Ref.of(Ref.Kind.POINT), Ref.of(Ref.Kind.STORAGE), Src.ANY, ""), look, "Empty it into my storage");
                case "count" -> said(p, a, new PaLogiOrder(Op.COUNT, item.isEmpty() ? "*" : item, PaQty.Qty.NONE, Ref.of(Ref.Kind.KNOWN), Ref.NONE, Src.ANY, ""), look, "How many " + nice(item) + " do we have?");
                case "craftq" -> PaCraft.query(p, a, args.getString("q"));
                case "recipe" -> PaCraft.recipe(p, a, item, true);
                case "craft" -> PaCraft.craftFromView(p, a, item, Math.max(1, n), args.getBoolean("all"));
                default -> { }
            }
        } finally {
            PaChests.askAs(before);
        }
        PaNet.refresh(p, a);
    }

    /** A button press, logged as if its player said it, then done. */
    private static void said(ServerPlayer p, PlayerAssistantEntity a, PaLogiOrder o, PaOrders.Look look, String words) {
        PaData.Rec r = a.rec();
        if (r != null) { r.say(0, words, a.level().getGameTime()); PaData.touch(); }
        PaLogistics.order(p, a, o, look, words);
    }

    static int countOn(ServerPlayer p, String item, int n) {
        int have = 0;
        var what = PaLogiItems.of(item);
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && what.test(s)) have += s.getCount();
        return n > 0 ? Math.min(n, have) : have;
    }

    static String nice(String item) {
        if (item.startsWith("item:")) {
            var it = BuiltInRegistries.ITEM.get(ResourceLocation.parse(item.substring(5)));
            return PaLogiItems.nameOf(it, 2);
        }
        return item.isEmpty() || item.equals("*") ? "everything" : item;
    }

    private static String clean(String s) {
        String n = s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ");
        return n.length() > 24 ? n.substring(0, 24) : n;
    }

    // ------------------------------------------------------------------ choices in its replies (PaServer hook: "#logi ...")

    private static final Map<PlayerAssistantEntity, PaLogiJob> SHORT = new WeakHashMap<>();

    /** A fill that came up short: kept for "Look further". */
    static void lastShort(PlayerAssistantEntity a, PaLogiJob j) {
        SHORT.put(a, j);
    }

    public static void hash(ServerPlayer p, PlayerAssistantEntity a, String[] w) {
        String what = w.length > 1 ? w[1] : "";
        switch (what) {
            case "hand" -> PaLogiPlan.handed(p, a);
            case "nohand" -> { PaLogiPlan.HANDOVERS.remove(p.getUUID()); PaTalk.reply(a, "Right, you keep them."); }
            case "further" -> {
                PaLogiJob was = SHORT.remove(a);
                if (was == null || was.target - was.moved <= 0) { PaTalk.reply(a, "There's nothing left to fetch."); return; }
                PaLogiJob j = PaLogiJob.of(was.o);
                j.to.addAll(was.to);
                j.target = was.target - was.moved;
                j.quietStart = true;
                PaData.Rec r = a.rec();
                if (r != null) r.roam = Math.min(PaConfig.roamMax(), Math.max(r.roam, 64));
                List<PaPlan.Step> out = new ArrayList<>();
                out.add(PaLogistics.step(j, "gather " + j.target + " more " + PaLogiItems.name(was.o.item(), 2) + " from further afield", 300));
                PaOrders.start(p, a, out, "look further");
            }
            default -> { }
        }
    }

    /** [pa36b] The Storage view's Take button: nothing, basics, anything, round again. */
    static void mayTake(ServerPlayer p, PlayerAssistantEntity a, BlockPos b) {
        UUID owner = a.getOwnerId();
        if (owner == null || !PaContainers.is(a.level(), b)) return;
        if (PaContainers.refuse(a, b) != null && !PaContainers.refuse(a, b).startsWith("I don't know that chest")) { PaTalk.reply(a, PaContainers.refuse(a, b)); return; }
        PaStore.Known k = PaStore.learn(owner, a.level().dimension().location().toString(), b, PaContainers.blockId(a.level(), b), a.level().getGameTime());
        k.take = (k.takeLevel() + 1) % 3;
        PaStore.touch();
        String chest = PaLogistics.called(a, b, null);
        PaTalk.reply(a, PaLogiTalk.line(a, k.take == 0 ? "logi.maytake_no" : k.take == 1 ? "logi.maytake_basics" : "logi.maytake_any", "chest", chest));
    }
}
