package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.entity.CraftPlanner;
import com.jrpetty.mcassistant.entity.goal.GatherGoal;
import com.jrpetty.mcassistant.pa.PaLogiOrder.Op;
import com.jrpetty.mcassistant.pa.PaLogiOrder.Ref;
import com.jrpetty.mcassistant.pa.PaLogiOrder.Src;
import com.jrpetty.mcassistant.pa.PaUnderstand.Choice;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [pa36] A logistics order turned into what it does: the questions answered on the spot (how much iron, which chest has
 * the diamonds, name this chest, make it the drop-off, show the storage), the settings it changes (standing stock jobs,
 * the automatic drop-off), and the work it plans (PaLogiJob steps), with every place resolved now, while "this chest"
 * still means the one pointed at.
 */
final class PaLogiPlan {

    private PaLogiPlan() {}

    /** Null when answered or planned; else why not. */
    @Nullable
    static String plan(@Nullable ServerPlayer p, PlayerAssistantEntity a, PaLogiOrder o, PaOrders.Look look, List<PaPlan.Step> out, String said) {
        // "this chest" said aloud with eyes on it: it is the player's, from now on
        if (p != null && look != null && look.block() != null && PaContainers.is(a.level(), look.block()) && (o.to().pointed() || o.from().pointed())) {
            PaLogistics.seen(p, look.block());
        }
        String item = resolveItem(a, o.item());
        o = o.withItem(item);
        switch (o.op()) {
            case COUNT -> { return count(p, a, o, look); }
            case FIND -> { return find(p, a, o); }
            case LIST -> { PaLogiNet.openStorage(p, a); return null; }
            case NAME -> { return name(p, a, o, look); }
            case DROPOFF -> { return dropoff(p, a, o, look); }
            case FORGET -> { return forget(p, a, o, look); }
            case AUTODROP -> { return autodrop(p, a, o, look); }
            case STOCK -> { return stock(p, a, o, look, out); }
            case UNSTOCK -> { return unstock(p, a, o, look); }
            case HANDOVER -> { return handover(p, a, o, look, out); }
            case DEATH -> { return death(p, a, o, look, out); }
            case RECIPE -> { PaCraft.recipe(p, a, o.item(), true); return null; }
            case CANMAKE -> { PaCraft.canMake(p, a); return null; }
            default -> { return work(p, a, o, look, out); }
        }
    }

    /** "it" means the thing last worked on, else everything it carries (but its gear). */
    static String resolveItem(PlayerAssistantEntity a, String item) {
        if (!"it".equals(item)) return item;
        List<PaPlan.Step> all = a.plan.all();
        for (int i = all.size() - 1; i >= 0; i--) if (!all.get(i).count.isEmpty() && !all.get(i).count.startsWith("item:")) return all.get(i).count;
        return "*";
    }

    // ------------------------------------------------------------------ work

    @Nullable
    private static String work(@Nullable ServerPlayer p, PlayerAssistantEntity a, PaLogiOrder o, PaOrders.Look look, List<PaPlan.Step> out) {
        PaLogiJob j = PaLogiJob.of(o);
        String item = o.item();
        String thing = PaLogiItems.name(item, 2);
        switch (o.op()) {
            case DEPOSIT, EMPTY_PACK -> {
                List<BlockPos> to = usable(p, a, o.to(), look, 0);
                if (to.isEmpty()) return noChest(p, a, o.to(), look, 0);
                j.to.addAll(to);
                Predicate<ItemStack> what = PaLogiJob.what(a, item);
                int have = 0;
                for (ItemStack s : a.getInventoryItems()) if (!s.isEmpty() && what.test(s)) have += s.getCount();
                // with "made" (craft 64 torches and put them in the chest) the items come from the step before: count them then
                if (have == 0 && !"made".equals(o.arg()) && !inPlanBefore(a, out, item)) {
                    if (!item.equals("*") && p != null) {
                        int hers = 0;
                        for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && what.test(s)) hers += s.getCount();
                        if (hers > 0) {
                            PaLogiOrder ho = new PaLogiOrder(Op.HANDOVER, item, o.qty(), Ref.of(Ref.Kind.ME), o.to(), Src.PLAYER, "");
                            return handover(p, a, ho, look, out);
                        }
                    }
                    return item.equals("*") ? "My pack's empty. Nothing to put away." : "I haven't any " + thing + " on me.";
                }
                Item sample = PaLogiItems.sample(item);
                j.target = o.qty().mode() == PaQty.Mode.ALL || !o.qty().said() ? -1 : o.qty().items(sample.getDefaultMaxStackSize(), -1);
                String where = PaLogistics.called(a, to.get(0), o.to());
                out.add(PaLogistics.step(j, o.op() == Op.EMPTY_PACK ? "put my things away" : "put " + (j.target > 0 ? j.target + " " : "") + (item.equals("*") ? "everything" : thing) + " in " + where, 20 + to.size() * 5));
                return null;
            }
            case FILL -> {
                List<BlockPos> to = usable(p, a, o.to(), look, 0);
                if (to.isEmpty()) return noChest(p, a, o.to(), look, 0);
                j.to.addAll(to);
                GatherGoal.Kind k = PaLogiItems.kind(item);
                if (k != null && o.src() != Src.CRAFT) {
                    String lock = PaLogistics.locked(a, k, thing);
                    if (lock != null) { PaTalk.reply(a, lock, PaLogistics.lockedChoices(thing)); return null; }
                }
                Item sample = PaLogiItems.sample(item);
                Container c = PaContainers.open(a, to.get(0));
                int room = c == null ? 27 * 64 : PaContainers.room(c, new ItemStack(sample));
                for (int i = 1; i < to.size() && i < 3; i++) { Container more = PaContainers.open(a, to.get(i)); if (more != null) room += PaContainers.room(more, new ItemStack(sample)); }
                j.target = switch (o.qty().mode()) {
                    case FULL, NONE -> Math.max(1, room);
                    case CARRY -> Math.max(1, PaContainers.packRoom(a, new ItemStack(sample)));
                    case ALL -> Math.max(1, room);
                    default -> o.qty().items(sample.getDefaultMaxStackSize(), 64);
                };
                if (room <= 0 && o.qty().mode() == PaQty.Mode.FULL) return PaLogistics.cap(PaLogistics.called(a, to.get(0), o.to())) + " is already full.";
                if (k == null && CraftPlanner.matchTarget(item) == null && !PaLogiJob.smeltable(item) && !item.startsWith("item:") && PaLogiItems.exact(item) == null
                    && !item.equals("cooked food") && !item.equals("food"))
                    return "I don't know how to get " + thing + ".";
                String where = PaLogistics.called(a, to.get(0), o.to());
                String label = (k != null ? "gather " + j.target + " " + k.label : "fill " + where + " with " + j.target + " " + thing) + (k != null ? " for " + where : "");
                out.add(PaLogistics.step(j, label, Math.max(60, eta(k, j.target))));
                return null;
            }
            case FETCH, TAKE -> {
                List<BlockPos> from = usable(p, a, o.from(), look, 0);
                if (o.from().kind() == Ref.Kind.STORAGE || o.from().kind() == Ref.Kind.KNOWN || o.from().kind() == Ref.Kind.HOME) {
                    // anywhere in its player's storage: the ones that have it first
                    List<BlockPos> all = usable(p, a, Ref.of(Ref.Kind.KNOWN), look, 0);
                    for (BlockPos b : all) if (!from.contains(b)) from.add(b);
                }
                if (from.isEmpty()) return noChest(p, a, o.from(), look, 0);
                PaLogistics.Count n = PaLogistics.count(null, a, item, from);
                int inChests = n.total() - n.pack();
                if (inChests <= 0) {
                    String line = PaLogiTalk.line(a, "logi.none", "item", thing);
                    List<Choice> ch = new ArrayList<>();
                    int want = Math.max(1, o.qty().items(PaLogiItems.sample(item).getDefaultMaxStackSize(), 16));
                    if (PaLogiItems.kind(item) != null) ch.add(new Choice("Gather " + want, "gather " + want + " " + item + (o.op() == Op.FETCH ? " for me" : "")));
                    else if (CraftPlanner.matchTarget(item) != null) ch.add(new Choice("Make " + want, "craft " + want + " " + item));
                    PaTalk.reply(a, line, ch.isEmpty() ? null : ch);
                    return null;
                }
                from.removeIf(b -> !n.chests().containsKey(b));
                from.sort(java.util.Comparator.comparingDouble(b -> b.distSqr(a.blockPosition())));
                j.from.addAll(from);
                Item sample = PaLogiItems.sample(item);
                j.target = o.qty().mode() == PaQty.Mode.ALL ? inChests : Math.min(inChests, Math.max(1, o.qty().items(sample.getDefaultMaxStackSize(), 16)));
                if (o.qty().mode() == PaQty.Mode.SOME && !item.equals("*")) j.target = Math.min(inChests, sample.getDefaultMaxStackSize() >= 16 ? 16 : sample.getDefaultMaxStackSize());
                String what = item.equals("*") ? "everything" : thing;
                out.add(PaLogistics.step(j, (o.op() == Op.FETCH ? "fetch you " : "take ") + j.target + " " + what + (o.op() == Op.TAKE ? " from " + PaLogistics.called(a, from.get(0), o.from()) : ""), 30 + from.size() * 10));
                return null;
            }
            case MOVE -> {
                List<BlockPos> from = usable(p, a, o.from(), look, 0);
                if (from.isEmpty()) return noChest(p, a, o.from(), look, 0);
                List<BlockPos> to = usable(p, a, o.to(), look, o.to().kind() == Ref.Kind.POINT2 || o.to().kind() == Ref.Kind.POINT ? 1 : 0);
                to.removeIf(b -> from.contains(b) || PaLogistics.sameChest(a.level(), b, from.get(0)));
                if (to.isEmpty()) return o.to().kind() == Ref.Kind.POINT2 || o.to().kind() == Ref.Kind.POINT
                    ? "Which one should it go in? Ping the first chest, then look at the second as you say it." : noChest(p, a, o.to(), look, 1);
                j.from.add(from.get(0));
                j.to.addAll(to);
                Container c = PaContainers.open(a, from.get(0));
                int in = c == null ? 0 : PaContainers.count(c, PaLogiJob.what(a, item));
                if (in == 0) return "There's nothing like that in " + PaLogistics.called(a, from.get(0), o.from()) + ".";
                j.target = o.qty().mode() == PaQty.Mode.ALL || !o.qty().said() ? in : Math.min(in, o.qty().items(PaLogiItems.sample(item).getDefaultMaxStackSize(), in));
                out.add(PaLogistics.step(j, "move " + (item.equals("*") ? "everything" : thing) + " to " + PaLogistics.called(a, to.get(0), o.to()), 30 + j.target / 32 * 15));
                return null;
            }
            case SORT -> {
                List<BlockPos> to = usable(p, a, o.to(), look, 0);
                if (to.isEmpty()) return noChest(p, a, o.to(), look, 0);
                if (o.to().kind() == Ref.Kind.KNOWN) to.removeIf(b -> !PaContainers.fits("chest", PaContainers.type(a.level(), b)) || b.distSqr(a.blockPosition()) > 32 * 32);
                if (to.isEmpty()) return "I don't know any chests near here to sort. Open them once, or ping them.";
                j.to.addAll(to.subList(0, Math.min(16, to.size())));
                j.target = j.to.size();
                out.add(PaLogistics.step(j, to.size() == 1 ? "sort that chest" : "sort " + j.to.size() + " chests", 20 + j.to.size() * 20));
                return null;
            }
            case FUEL -> {
                List<BlockPos> to = usable(p, a, o.to().kind() == Ref.Kind.NONE ? Ref.of(Ref.Kind.DEF).withType("furnace") : o.to(), look, 0);
                if (to.isEmpty() && (o.to().kind() == Ref.Kind.DEF || o.to().kind() == Ref.Kind.KNOWN)) {
                    for (BlockPos b : PaLogistics.containersAround(a.level(), a.blockPosition(), 12, "furnace")) if (PaContainers.refuse(a, b) == null) to.add(b);
                }
                to.removeIf(b -> !PaContainers.furnace(PaContainers.type(a.level(), b)));
                if (to.isEmpty()) return "I can't see a furnace of yours nearby to fuel.";
                j.to.addAll(to);
                j.target = to.size();
                out.add(PaLogistics.step(j, to.size() == 1 ? "fuel the furnace" : "fuel " + to.size() + " furnaces", 20 + to.size() * 8));
                return null;
            }
            case SMELT -> {
                List<BlockPos> furn = usable(p, a, o.to().kind() == Ref.Kind.NONE ? Ref.of(Ref.Kind.NEAREST).withType("furnace") : o.to(), look, o.from().pointed() ? 1 : 0);
                if (furn.isEmpty()) furn = usable(p, a, Ref.of(Ref.Kind.NEAREST).withType("furnace"), look, 0);
                if (furn.isEmpty()) for (BlockPos b : PaLogistics.containersAround(a.level(), a.blockPosition(), 12, "furnace")) if (PaContainers.refuse(a, b) == null) furn.add(b);
                furn.removeIf(b -> !PaContainers.furnace(PaContainers.type(a.level(), b)));
                if (o.from().kind() == Ref.Kind.ME && p != null) {
                    // its player's ores: handed over first, then smelted
                    PaLogiOrder ho = new PaLogiOrder(Op.HANDOVER, item, o.qty(), Ref.of(Ref.Kind.ME), Ref.of(Ref.Kind.PACK), Src.PLAYER, "smelt");
                    return handover(p, a, ho, look, out);
                }
                if (o.from().kind() != Ref.Kind.PACK && o.from().kind() != Ref.Kind.NONE) {
                    List<BlockPos> src = usable(p, a, o.from(), look, 0);
                    if (src.isEmpty()) return noChest(p, a, o.from(), look, 0);
                    j.from.add(src.get(0));
                }
                if (furn.isEmpty()) {
                    // no furnace of its own about: the engine's smelting finds or places one
                    if (!j.from.isEmpty()) return "I can't see a furnace nearby. Place one and I'll smelt it all.";
                    String raw = item.equals("*") || item.equals("ore") ? "ore" : item;
                    PaPlan.Step s = PaOrders.step("smelt " + raw, PaIntent.Kind.SMELT, 120);
                    s.jobs.add(com.jrpetty.mcassistant.entity.Job.smelt(raw.equals("ore") ? "iron" : raw, o.qty().items(64, 64)));
                    out.add(s);
                    return null;
                }
                j.to.addAll(furn.subList(0, Math.min(furn.size(), a.veteranLevel() >= 4 ? 3 : a.veteranLevel() >= 2 ? 2 : 1)));
                j.target = o.qty().mode() == PaQty.Mode.EXACT || o.qty().mode() == PaQty.Mode.STACKS ? o.qty().items(64, 64) : -1;
                out.add(PaLogistics.step(j, j.from.isEmpty() ? "smelt " + (item.equals("*") ? "my ores" : thing) : "smelt everything in " + PaLogistics.called(a, j.from.get(0), o.from()), 120));
                return null;
            }
            case COLLECT -> {
                List<BlockPos> from = usable(p, a, o.from(), look, 0);
                if (from.isEmpty()) for (BlockPos b : PaLogistics.containersAround(a.level(), a.blockPosition(), 12, "furnace")) if (PaContainers.refuse(a, b) == null) from.add(b);
                from.removeIf(b -> !PaContainers.furnace(PaContainers.type(a.level(), b)));
                if (from.isEmpty()) return "I can't see a furnace of yours nearby.";
                j.from.addAll(from);
                out.add(PaLogistics.step(j, "collect from the furnace" + (from.size() > 1 ? "s" : ""), 20 + from.size() * 8));
                return null;
            }
            case COMPOST -> {
                List<BlockPos> to = usable(p, a, o.to(), look, 0);
                if (to.isEmpty()) for (BlockPos b : PaLogistics.containersAround(a.level(), a.blockPosition(), 12, "composter")) if (PaContainers.refuse(a, b) == null) to.add(b);
                to.removeIf(b -> !"composter".equals(PaContainers.type(a.level(), b)));
                if (to.isEmpty()) return "I can't see a composter of yours nearby.";
                j.to.add(to.get(0));
                Predicate<ItemStack> what = PaLogiItems.of(item);
                int have = 0;
                for (ItemStack s : a.getInventoryItems()) if (!s.isEmpty() && what.test(s) && net.minecraft.world.level.block.ComposterBlock.COMPOSTABLES.containsKey(s.getItem())) have += s.getCount();
                if (have == 0) return "I've nothing to compost. Seeds, saplings, leaves and the like.";
                j.target = o.qty().mode() == PaQty.Mode.EXACT ? o.qty().n() : -1;
                out.add(PaLogistics.step(j, "fill the composter", 30));
                return null;
            }
            case HAUL -> {
                List<BlockPos> from = usable(p, a, o.from(), look, 0);
                List<BlockPos> to = usable(p, a, o.to(), look, o.from().pointed() ? 1 : 0);
                if (o.from().kind() == Ref.Kind.WAYPOINT && from.isEmpty() && PaLogistics.waypoint(a, o.from().name()) == null)
                    return "I don't know a place called " + o.from().name() + ". Stand there and say \"remember this spot as " + o.from().name() + "\".";
                if (from.isEmpty() && o.from().kind() != Ref.Kind.WAYPOINT) return noChest(p, a, o.from(), look, 0);
                to.removeIf(from::contains);
                if (to.isEmpty()) return noChest(p, a, o.to(), look, 1);
                j.from.addAll(from);
                j.to.addAll(to);
                if (o.from().kind() == Ref.Kind.WAYPOINT) j.area = PaLogistics.waypoint(a, o.from().name());
                j.trips = 0;
                j.tripsWanted = o.arg().matches("\\d+") ? Integer.parseInt(o.arg()) : -1;
                j.target = -1;
                out.add(PaLogistics.step(j, "haul " + (item.equals("*") ? "everything" : thing) + " from " + placeName(o.from()) + " to " + placeName(o.to()), j.tripsWanted > 0 ? 60 * j.tripsWanted : 100000));
                return null;
            }
            default -> { return "I'm not sure how to do that yet."; }
        }
    }

    static boolean inPlanBefore(PlayerAssistantEntity a, List<PaPlan.Step> out, String item) {
        return !out.isEmpty() || !a.plan.idle();
    }

    static int eta(@Nullable GatherGoal.Kind k, int n) {
        int each = k == null ? 4 : switch (k) { case LOGS -> 3; case STONE -> 2; case IRON, COAL -> 6; default -> 2; };
        return 20 + each * Math.min(2000, n);
    }

    static String placeName(Ref r) {
        return switch (r.kind()) {
            case WAYPOINT -> "the " + r.name();
            case HOME -> "base";
            case STORAGE -> "storage";
            case DROPOFF -> "the drop-off";
            case NAMED -> "the " + r.name() + " " + (r.type().isEmpty() ? "chest" : r.type());
            case POINT -> "this " + (r.type().isEmpty() ? "chest" : r.type());
            case POINT2 -> "that " + (r.type().isEmpty() ? "chest" : r.type());
            default -> "there";
        };
    }

    /** The containers a place means that it may use; a refusal said if the pointed one isn't allowed. */
    static List<BlockPos> usable(@Nullable ServerPlayer p, PlayerAssistantEntity a, Ref r, PaOrders.Look look, int k) {
        List<BlockPos> all = PaLogistics.resolve(p, a, r, look, k);
        List<BlockPos> ok = new ArrayList<>();
        String firstWhy = null;
        for (BlockPos b : all) {
            String why = PaContainers.refuse(a, b);
            if (why == null) ok.add(b);
            else if (firstWhy == null) firstWhy = why;
        }
        if (ok.isEmpty() && firstWhy != null) REFUSED.put(a, firstWhy);
        return ok;
    }

    private static final Map<PlayerAssistantEntity, String> REFUSED = new java.util.WeakHashMap<>();

    static String noChest(@Nullable ServerPlayer p, PlayerAssistantEntity a, Ref r, PaOrders.Look look, int k) {
        String why = REFUSED.remove(a);
        if (why != null) return why;
        if (r.kind() == Ref.Kind.DROPOFF) return "There's no drop-off yet. Ping a chest and say \"make this the drop off\".";
        if (r.kind() == Ref.Kind.NAMED) return "I don't know a chest called the " + r.name() + ". Ping one and say \"call this chest the " + r.name() + "\".";
        if (r.kind() == Ref.Kind.STORAGE || r.kind() == Ref.Kind.KNOWN || r.kind() == Ref.Kind.HOME)
            return "I don't know any of your chests round here yet. Open one, or ping it, and I'll remember it.";
        return PaLogiTalk.line(a, "logi.no_chest");
    }

    // ------------------------------------------------------------------ answers at once

    private static String count(@Nullable ServerPlayer p, PlayerAssistantEntity a, PaLogiOrder o, PaOrders.Look look) {
        List<BlockPos> where = o.from().kind() == Ref.Kind.KNOWN || o.from().kind() == Ref.Kind.NONE ? usable(p, a, Ref.of(Ref.Kind.KNOWN), look, 0) : usable(p, a, o.from(), look, 0);
        boolean one = o.from().pointed() || o.from().kind() == Ref.Kind.NAMED;
        if (one && where.isEmpty()) return noChest(p, a, o.from(), look, 0);
        if (one) where = where.subList(0, 1);
        if (o.item().equals("*")) {
            // "what's in this chest": its contents, biggest first
            BlockPos b = where.get(0);
            Container c = PaContainers.open(a, b);
            if (c == null) return "I can't see into that from here.";
            PaContainers.snapshot(a, b, c);
            Map<String, Integer> m = PaContainers.contents(c);
            if (m.isEmpty()) { PaTalk.reply(a, PaLogistics.cap(PaLogistics.called(a, b, o.from())) + " is empty."); return null; }
            List<String> bits = new ArrayList<>();
            int i = 0;
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                if (i++ >= 6) { bits.add("and " + (m.size() - 6) + " more kinds"); break; }
                bits.add(e.getValue() + " " + PaLogiItems.nameOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(e.getKey())), e.getValue()));
            }
            PaTalk.reply(a, PaLogiTalk.line(a, "logi.count", "answer", PaLogistics.cap(PaLogistics.called(a, b, o.from())) + " has " + join(bits) + "."));
            return null;
        }
        PaLogistics.Count n = PaLogistics.count(one ? null : p, a, o.item(), where);
        String thing = PaLogiItems.name(o.item(), 2);
        if (n.total() == 0) { PaTalk.reply(a, PaLogiTalk.line(a, "logi.count_none", "item", thing)); return null; }
        List<String> kinds = new ArrayList<>();
        for (Map.Entry<String, Integer> e : n.byItem().entrySet())
            kinds.add(e.getValue() + " " + PaLogiItems.nameOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(e.getKey())), e.getValue()));
        List<String> where2 = new ArrayList<>();
        int shown = 0;
        for (Map.Entry<BlockPos, Integer> e : n.chests().entrySet()) {
            if (shown++ >= 3) { where2.add("more in " + (n.chests().size() - 3) + " other chest" + (n.chests().size() - 3 == 1 ? "" : "s")); break; }
            where2.add(e.getValue() + " in " + PaLogistics.called(a, e.getKey(), null));
        }
        if (n.pack() > 0) where2.add(n.pack() + " on me");
        if (n.player() > 0) where2.add(n.player() + " on you");
        String answer = n.total() + " in all" + (kinds.size() > 1 || !kinds.get(0).endsWith(thing) ? ": " + join(kinds) : "") + ". " + PaLogistics.cap(join(where2)) + ".";
        PaTalk.reply(a, PaLogiTalk.line(a, "logi.count", "answer", answer));
        if (p != null && !n.chests().isEmpty()) {
            BlockPos top = n.chests().entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
            PaNet.ping(p, top, PaLogistics.cap(thing) + " ×" + n.chests().get(top), "GATHER", "found");
        }
        return null;
    }

    private static String find(@Nullable ServerPlayer p, PlayerAssistantEntity a, PaLogiOrder o) {
        List<BlockPos> where = usable(p, a, Ref.of(Ref.Kind.KNOWN), PaOrders.Look.NONE, 0);
        PaLogistics.Count n = PaLogistics.count(null, a, o.item(), where);
        String thing = PaLogiItems.name(o.item(), 2);
        if (n.chests().isEmpty()) {
            String line = PaLogiTalk.line(a, "logi.find_none", "item", thing);
            if (n.pack() > 0) line += " I've " + n.pack() + " on me, mind.";
            PaTalk.reply(a, line);
            return null;
        }
        Vec3 from = p != null ? p.position() : a.position();
        BlockPos best = n.chests().entrySet().stream().max((x, y) -> x.getValue().equals(y.getValue())
            ? Double.compare(y.getKey().distToCenterSqr(from), x.getKey().distToCenterSqr(from)) : x.getValue() - y.getValue()).get().getKey();
        int dist = (int) Math.sqrt(best.distToCenterSqr(from));
        PaTalk.reply(a, PaLogiTalk.line(a, "logi.find", "item", thing, "chest", PaLogistics.called(a, best, null), "dist", String.valueOf(dist), "dir", PaLogistics.bearing(from, best))
            + (n.chests().size() > 1 ? " " + (n.chests().size() - 1) + " other chest" + (n.chests().size() > 2 ? "s have" : " has") + " some too." : ""));
        if (p != null) PaNet.ping(p, best, PaLogistics.cap(thing) + " ×" + n.chests().get(best), "GATHER", "found");
        return null;
    }

    @Nullable
    private static String name(@Nullable ServerPlayer p, PlayerAssistantEntity a, PaLogiOrder o, PaOrders.Look look) {
        List<BlockPos> to = usable(p, a, o.to(), look, 0);
        if (to.isEmpty()) {
            // naming is how a chest becomes known: a pointed one it may use is learnt here
            List<BlockPos> raw = PaLogistics.resolve(p, a, o.to(), look, 0);
            if (raw.isEmpty()) return noChest(p, a, o.to(), look, 0);
            String why = PaContainers.refuse(a, raw.get(0));
            if (why != null && !why.startsWith("I don't know that chest")) return why;
            to = raw;
        }
        UUID owner = a.getOwnerId();
        if (owner == null) return null;
        String dim = a.level().dimension().location().toString();
        BlockPos b = to.get(0);
        PaStore.Known k = PaStore.learn(owner, dim, b, PaContainers.blockId(a.level(), b), a.level().getGameTime());
        String name = o.arg().trim();
        if (name.equals("storage") || name.equals("main storage")) { k.storage = true; k.name = "storage"; }
        else k.name = name;
        PaOrders.point(a, b);
        PaStore.touch();
        PaTalk.reply(a, PaLogiTalk.line(a, "logi.named", "chest", PaLogistics.called(a, b, null)));
        if (p != null) PaNet.ping(p, b, PaLogistics.cap(k.label()), "GATHER", "found");
        return null;
    }

    @Nullable
    private static String dropoff(@Nullable ServerPlayer p, PlayerAssistantEntity a, PaLogiOrder o, PaOrders.Look look) {
        List<BlockPos> to = PaLogistics.resolve(p, a, o.to(), look, 0);
        if (to.isEmpty()) return noChest(p, a, o.to(), look, 0);
        BlockPos b = to.get(0);
        String why = PaContainers.refuse(a, b);
        if (why != null && !why.startsWith("I don't know that chest")) return why;
        UUID owner = a.getOwnerId();
        if (owner == null) return null;
        String dim = a.level().dimension().location().toString();
        PaStore.Owner st = PaStore.of(owner);
        for (PaStore.Known k : st.known) if (k.dim.equals(dim)) k.dropoff = false;
        PaStore.Known k = PaStore.learn(owner, dim, b, PaContainers.blockId(a.level(), b), a.level().getGameTime());
        k.dropoff = true;
        PaOrders.point(a, b);
        PaStore.touch();
        PaTalk.reply(a, PaLogiTalk.line(a, "logi.dropoff", "chest", PaLogistics.called(a, b, null)));
        return null;
    }

    @Nullable
    private static String forget(@Nullable ServerPlayer p, PlayerAssistantEntity a, PaLogiOrder o, PaOrders.Look look) {
        List<BlockPos> to = PaLogistics.resolve(p, a, o.to(), look, 0);
        if (to.isEmpty()) return noChest(p, a, o.to(), look, 0);
        UUID owner = a.getOwnerId();
        if (owner == null) return null;
        BlockPos b = to.get(0);
        PaStore.forget(owner, a.level().dimension().location().toString(), b);
        PaData.Rec r = a.rec();
        if (r != null) { r.pointed.remove(b.asLong()); PaData.touch(); }
        PaTalk.reply(a, PaLogiTalk.line(a, "logi.forgot"));
        return null;
    }

    @Nullable
    private static String autodrop(@Nullable ServerPlayer p, PlayerAssistantEntity a, PaLogiOrder o, PaOrders.Look look) {
        UUID owner = a.getOwnerId();
        if (owner == null) return null;
        PaStore.Owner st = PaStore.of(owner);
        boolean on = !"off".equals(o.arg());
        if (on && o.to().kind() != Ref.Kind.NONE && o.to().kind() != Ref.Kind.DROPOFF) {
            // "when your pack is full, drop it in this chest": that chest becomes the drop-off
            String why = dropoff(p, a, PaLogiOrder.of(Op.DROPOFF).withTo(o.to()), look);
            if (why != null) return why;
        }
        String dim = a.level().dimension().location().toString();
        if (on && st.dropoff(dim) == null) return "There's no drop-off yet. Ping a chest and say \"make this the drop off\", then ask again.";
        st.autoDrop = on;
        PaStore.touch();
        PaTalk.reply(a, PaLogiTalk.line(a, on ? "logi.autodrop_on" : "logi.autodrop_off"));
        return null;
    }

    @Nullable
    private static String stock(@Nullable ServerPlayer p, PlayerAssistantEntity a, PaLogiOrder o, PaOrders.Look look, List<PaPlan.Step> out) {
        UUID owner = a.getOwnerId();
        if (owner == null) return null;
        PaStore.Owner st = PaStore.of(owner);
        String dim = a.level().dimension().location().toString();
        if (o.item().equals("fuel") && (o.to().kind() == Ref.Kind.KNOWN || PaContainers.furnace(o.to().type()))) {
            st.stock.removeIf(s -> s.furnaces && s.dim.equals(dim));
            PaStore.Stock s = new PaStore.Stock();
            s.furnaces = true;
            s.dim = dim;
            s.item = "fuel";
            s.n = Math.max(4, o.qty().items(64, 16));
            s.every = 20 * 60;
            s.next = a.level().getGameTime();
            if (o.to().kind() == Ref.Kind.POINT) {
                List<BlockPos> f = usable(p, a, o.to(), look, 0);
                if (!f.isEmpty()) { s.pos = f.get(0).asLong(); s.furnaces = false; }
            }
            st.stock.add(s);
            PaStore.touch();
            PaTalk.reply(a, PaLogiTalk.line(a, "logi.stock_set", "chest", s.furnaces ? "the furnaces" : "that furnace", "n", String.valueOf(s.n), "item", "fuel"));
            return null;
        }
        List<BlockPos> to = usable(p, a, o.to(), look, 0);
        if (to.isEmpty()) return noChest(p, a, o.to(), look, 0);
        BlockPos b = to.get(0);
        String item = o.item();
        if (item.equals("same")) {
            Container c = PaContainers.open(a, b);
            Map<String, Integer> m = PaContainers.contents(c);
            if (m.isEmpty()) return "Stocked with what? Say \"keep this chest stocked with bread\".";
            item = "item:" + m.keySet().iterator().next();
        }
        Item sample = PaLogiItems.sample(item);
        int n = o.qty().items(sample.getDefaultMaxStackSize(), sample.getDefaultMaxStackSize());
        st.stock.removeIf(s -> !s.furnaces && s.pos == b.asLong() && s.dim.equals(dim));
        PaStore.Stock s = new PaStore.Stock();
        s.pos = b.asLong();
        s.dim = dim;
        s.item = item;
        s.n = n;
        s.next = a.level().getGameTime() + s.every;
        st.stock.add(s);
        PaStore.learn(owner, dim, b, PaContainers.blockId(a.level(), b), a.level().getGameTime());
        PaStore.touch();
        PaTalk.reply(a, PaLogiTalk.line(a, "logi.stock_set", "chest", PaLogistics.called(a, b, o.to()), "n", String.valueOf(n), "item", PaLogiItems.name(item, n)));
        // and topped up now, if it's short
        PaPlan.Step now = topUpStep(a, s);
        if (now != null) out.add(now);
        return null;
    }

    @Nullable
    private static String unstock(@Nullable ServerPlayer p, PlayerAssistantEntity a, PaLogiOrder o, PaOrders.Look look) {
        UUID owner = a.getOwnerId();
        if (owner == null) return null;
        PaStore.Owner st = PaStore.of(owner);
        String dim = a.level().dimension().location().toString();
        int before = st.stock.size();
        if (o.to().kind() == Ref.Kind.KNOWN && PaContainers.furnace(o.to().type())) st.stock.removeIf(s -> s.furnaces || "fuel".equals(s.item));
        else if (o.to().kind() == Ref.Kind.KNOWN || o.to().kind() == Ref.Kind.NONE) st.stock.removeIf(s -> s.dim.equals(dim));
        else {
            List<BlockPos> to = PaLogistics.resolve(p, a, o.to(), look, 0);
            if (to.isEmpty()) return noChest(p, a, o.to(), look, 0);
            long l = to.get(0).asLong();
            st.stock.removeIf(s -> s.pos == l && s.dim.equals(dim));
        }
        PaStore.touch();
        if (st.stock.size() == before) { PaTalk.reply(a, "I wasn't keeping anything stocked there."); return null; }
        PaTalk.reply(a, PaLogiTalk.line(a, "logi.unstock"));
        return null;
    }

    /** A stock job's check: below its amount, a fill step to top it up (or null when it's fine or can't be seen). */
    @Nullable
    static PaPlan.Step topUpStep(PlayerAssistantEntity a, PaStore.Stock s) {
        BlockPos b = s.at();
        if (!a.level().isLoaded(b) || PaContainers.refuse(a, b) != null) return null;
        Container c = PaContainers.open(a, b);
        if (c == null) return null;
        int have = PaContainers.count(c, PaLogiItems.of(s.item));
        if (have >= s.n) return null;
        PaLogiOrder o = new PaLogiOrder(Op.FILL, s.item, PaQty.Qty.of(s.n - have), Ref.NONE, Ref.of(Ref.Kind.POINT), Src.ANY, "stock");
        PaLogiJob j = PaLogiJob.of(o);
        j.to.add(b);
        j.target = s.n - have;
        j.rule = s;
        j.stockHave = have;
        return PaLogistics.step(j, "top up " + PaLogistics.called(a, b, null) + " with " + PaLogiItems.name(s.item, 2), Math.max(60, eta(PaLogiItems.kind(s.item), j.target)));
    }

    /** A stock job fell due: top up (queued after whatever it's doing). */
    static void topUp(PlayerAssistantEntity a, PaStore.Stock s) {
        PaPlan.Step step = topUpStep(a, s);
        if (step == null) return;
        Container c = PaContainers.open(a, s.at());
        int have = c == null ? 0 : PaContainers.count(c, PaLogiItems.of(s.item));
        a.plan.steps.add(step);
        a.plan.paused = false;
        PaTalk.comment(a, 1, PaLogiTalk.line(a, "logi.stock_topup", "chest", PaLogistics.called(a, s.at(), null), "item", PaLogiItems.name(s.item, 2),
            "have", String.valueOf(have), "n", String.valueOf(s.n)), true);
    }

    /** "Keep the furnaces fuelled" fell due: any of its player's furnaces that are working and low on fuel get some. */
    static void fuelRound(PlayerAssistantEntity a, PaStore.Stock s) {
        UUID owner = a.getOwnerId();
        if (owner == null) return;
        List<BlockPos> furnaces = new ArrayList<>();
        BlockPos centre = a.getOwnerPlayer() != null ? a.getOwnerPlayer().blockPosition() : a.blockPosition();
        String dim = a.level().dimension().location().toString();
        List<BlockPos> cands = s.furnaces ? PaLogistics.nearest(a, PaStore.of(owner), dim, centre, "furnace", 48, true) : List.of(s.at());
        for (BlockPos b : cands) {
            if (!a.level().isLoaded(b) || PaContainers.refuse(a, b) != null) continue;
            Container c = PaContainers.open(a, b);
            if (c == null || c.getContainerSize() < 3) continue;
            boolean working = !c.getItem(0).isEmpty();
            if (working && c.getItem(1).getCount() < Math.max(2, s.n / 4)) furnaces.add(b);
        }
        if (furnaces.isEmpty()) return;
        boolean hasFuel = a.countMatching(PaLogiItems::fuel) > 0;
        PaLogiJob j = PaLogiJob.of(new PaLogiOrder(Op.FUEL, "fuel", PaQty.Qty.of(s.n), Ref.NONE, Ref.of(Ref.Kind.KNOWN).withType("furnace"), Src.ANY, "stock"));
        j.to.addAll(furnaces);
        j.target = furnaces.size();
        j.rule = s;
        if (!hasFuel) j.fetchFuel = true;
        a.plan.steps.add(PaLogistics.step(j, "fuel the furnaces", 20 + furnaces.size() * 8));
        a.plan.paused = false;
    }

    // ------------------------------------------------------------------ its player's things

    /** Pending hand-overs: the order and where it goes, waiting for its player's "Hand over". */
    record Handover(PaLogiOrder order, List<BlockPos> to, int n, long at) {}

    static final Map<UUID, Handover> HANDOVERS = new java.util.concurrent.ConcurrentHashMap<>();

    @Nullable
    static String handover(@Nullable ServerPlayer p, PlayerAssistantEntity a, PaLogiOrder o, PaOrders.Look look, List<PaPlan.Step> out) {
        if (p == null) return "You'll need to be here to hand things over.";
        List<BlockPos> to = new ArrayList<>();
        if (o.to().kind() != Ref.Kind.PACK) {
            to = usable(p, a, o.to(), look, 0);
            if (to.isEmpty()) return noChest(p, a, o.to(), look, 0);
        }
        Predicate<ItemStack> what = o.item().equals("*") ? s -> !s.isEmpty() && !PaLogiItems.gear(a, s) && !(s.getItem() instanceof net.minecraft.world.item.ArmorItem)
            : PaLogiItems.of(o.item());
        int hers = 0;
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && what.test(s)) hers += s.getCount();
        String thing = PaLogiItems.name(o.item(), 2);
        if (hers == 0) return PaLogiTalk.line(a, "logi.player_none", "item", thing);
        int n = o.qty().mode() == PaQty.Mode.ALL || !o.qty().said() ? hers : Math.min(hers, o.qty().items(PaLogiItems.sample(o.item()).getDefaultMaxStackSize(), hers));
        HANDOVERS.put(p.getUUID(), new Handover(o, to, n, a.level().getGameTime()));
        String chest = to.isEmpty() ? "the furnace" : PaLogistics.called(a, to.get(0), o.to());
        if (a.distanceToSqr(p) > 8 * 8) {
            PaTalk.reply(a, PaLogiTalk.line(a, "logi.handover_far") + " " + PaLogiTalk.line(a, "logi.handover_ask", "n", String.valueOf(n), "item", thing, "chest", chest),
                List.of(new Choice("Hand over", "#logi hand"), new Choice("Cancel", "#logi nohand")));
            return null;
        }
        PaTalk.reply(a, PaLogiTalk.line(a, "logi.handover_ask", "n", String.valueOf(n), "item", thing, "chest", chest),
            List.of(new Choice("Hand over", "#logi hand"), new Choice("Cancel", "#logi nohand")));
        return null;
    }

    /** "Hand over" pressed: its player's items into its pack (as far as it has room), then off to the chest with them. */
    static void handed(ServerPlayer p, PlayerAssistantEntity a) {
        Handover h = HANDOVERS.remove(p.getUUID());
        if (h == null) { PaTalk.reply(a, "There's nothing waiting to be handed over."); return; }
        if (a.distanceToSqr(p) > 8 * 8) {
            HANDOVERS.put(p.getUUID(), h);
            PaTalk.reply(a, PaLogiTalk.line(a, "logi.handover_far"), List.of(new Choice("Hand over", "#logi hand")));
            return;
        }
        PaLogiOrder o = h.order();
        Predicate<ItemStack> what = o.item().equals("*") ? s -> !s.isEmpty() && !PaLogiItems.gear(a, s) && !(s.getItem() instanceof net.minecraft.world.item.ArmorItem)
            : PaLogiItems.of(o.item());
        int left = h.n(), got = 0;
        var inv = p.getInventory();
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (int i = 0; i < inv.items.size() && left > 0; i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty() || !what.test(s)) continue;
            int take = Math.min(left, s.getCount());
            ItemStack part = s.copyWithCount(take);
            ItemStack rest = a.insertItem(part);
            int moved = take - rest.getCount();
            if (moved <= 0) break;                                           // its pack is full
            s.shrink(moved);
            left -= moved;
            got += moved;
            ids.add(PaLogiItems.id(part));
        }
        inv.setChanged();
        if (got == 0) { PaTalk.reply(a, "My pack's full. Let me empty it first."); return; }
        a.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        String thing = PaLogiItems.name(o.item(), got);
        if ("smelt".equals(o.arg())) {
            List<PaPlan.Step> out = new ArrayList<>();
            String why = plan(p, a, new PaLogiOrder(Op.SMELT, o.item(), PaQty.Qty.of(got), Ref.of(Ref.Kind.PACK), Ref.of(Ref.Kind.NEAREST).withType("furnace"), Src.PACK, ""),
                PaOrders.Look.NONE, out, "smelt");
            if (why != null) { PaTalk.reply(a, why); return; }
            PaOrders.start(p, a, out, "smelt your ores");
            return;
        }
        if (h.to().isEmpty()) { PaTalk.reply(a, "Got " + got + " " + thing + "."); return; }
        String item = ids.size() == 1 ? "item:" + ids.iterator().next() : o.item();
        PaLogiOrder dep = new PaLogiOrder(Op.DEPOSIT, item, PaQty.Qty.of(got), Ref.of(Ref.Kind.PACK), o.to(), Src.PACK, "handed");
        PaLogiJob j = PaLogiJob.of(dep);
        j.to.addAll(h.to());
        j.target = got;
        List<PaPlan.Step> out = new ArrayList<>();
        out.add(PaLogistics.step(j, "put your " + got + " " + thing + " in " + PaLogistics.called(a, h.to().get(0), o.to()), 20));
        PaOrders.start(p, a, out, "hand over " + thing);
    }

    @Nullable
    private static String death(@Nullable ServerPlayer p, PlayerAssistantEntity a, PaLogiOrder o, PaOrders.Look look, List<PaPlan.Step> out) {
        BlockPos at = a.mind.deathPos;
        if (at == null || a.mind.deathAt < 0) return "I don't know where you fell.";
        if (!a.level().dimension().location().toString().equals(a.mind.deathDim)) return "Your things are in another world from me. Take me there and I'll fetch them.";
        if (a.level().getGameTime() - a.mind.deathAt > 5 * 60 * 20) return "It's been too long, I'm afraid. They'll have vanished.";
        PaLogiJob j = PaLogiJob.of(o);
        j.area = at;
        if (o.to().kind() != Ref.Kind.ME) {
            List<BlockPos> to = usable(p, a, o.to(), look, 0);
            if (to.isEmpty()) return noChest(p, a, o.to(), look, 0);
            j.to.addAll(to);
        }
        out.add(PaLogistics.step(j, "fetch your things from where you fell" + (j.to.isEmpty() ? "" : " and put them away"), 120));
        return null;
    }

    static String join(List<String> l) {
        if (l.isEmpty()) return "";
        if (l.size() == 1) return l.get(0);
        return String.join(", ", l.subList(0, l.size() - 1)) + " and " + l.get(l.size() - 1);
    }

    @SuppressWarnings("unused")
    private static boolean isItems(Item i) {
        return i != Items.AIR;
    }
}
