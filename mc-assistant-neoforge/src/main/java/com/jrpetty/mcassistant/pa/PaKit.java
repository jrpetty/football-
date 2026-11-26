package com.jrpetty.mcassistant.pa;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * [featpa] The wire for its three new screens (Blueprints, Errand to town, Combat stance): every button comes here as
 * the "kit" action (PaServer), and each screen's state goes back as payload {@link #KIND}. Plus the few plain words that
 * open them from the talk box ("copy this building", "run an errand", "be a medic"...) and their reply buttons
 * ("#kit ..."). Nothing goes through the chat or a command.
 */
public final class PaKit {

    private PaKit() {}

    public static final int KIND = 41;

    public static void send(ServerPlayer p, String op, CompoundTag t) {
        t.putString("op", op);
        PaNet.send(p, KIND, t);
    }

    // ------------------------------------------------------------------ from the screens

    public static void act(ServerPlayer p, @Nullable PaData.Rec r, @Nullable PlayerAssistantEntity a, CompoundTag args) {
        if (r == null || a == null || !a.obeys(p)) return;
        String op = args.getString("op");
        if (op.startsWith("er_")) { PaErrand.act(p, a, op, args); return; }
        switch (op) {
            // Blueprints
            case "bp_open" -> sendList(p, a, args.getString("name"));
            case "bp_copy_look" -> PaBuild.copyLooked(p, a, args.getString("name"));
            case "bp_copy_area" -> PaBuild.copyArea(p, a, args.getString("name"));
            case "bp_rename" -> {
                String to = PaBuild.clean(args.getString("to"));
                if (!to.isEmpty() && PaBlueprints.rename(p.getUUID(), args.getString("name"), to)) sendList(p, a, to);
                else { PaNet.note(p, "That name's taken (or empty)."); sendList(p, a, args.getString("name")); }
            }
            case "bp_delete" -> {
                PaBlueprints.remove(p.getUUID(), args.getString("name"));
                PaBuild.Preview pv = PaBuild.preview(p.getUUID());
                if (pv != null && pv.name.equalsIgnoreCase(args.getString("name"))) { PaBuild.PREVIEW.remove(p.getUUID()); ghostOff(p); }
                sendList(p, a, "");
            }
            case "bp_select" -> sendList(p, a, args.getString("name"));
            case "bp_aim" -> PaBuild.aim(p, a, args.getString("name"));
            case "bp_rot", "bp_mirror", "bp_nudge", "bp_clear", "bp_cancel" -> PaBuild.adjust(p, a, op, args);
            case "bp_confirm" -> PaBuild.confirm(p, a);
            case "bp_fetch" -> PaBuild.fetchShort(p, a);
            case "bp_buy" -> PaBuild.buyShort(p, a);
            case "bp_stop" -> { PaBuild.stop(a); PaTalk.reply(a, PaLogiTalk.line(a, "kit.bp_stopped")); }
            case "bp_resume" -> PaBuild.resume(p, a);
            case "bp_status" -> status(p, a);
            // Errand
            case "errand_open" -> sendErrand(p, a);
            // Stance
            case "st_open" -> sendStance(p, a);
            case "st_set" -> { PaStance.set(a, PaStance.Kind.of(args.getInt("k"))); sendStance(p, a); }
            case "st_ward" -> {
                int id = args.getInt("id");
                var e = id < 0 ? null : p.level().getEntity(id);
                r.guardWho = e instanceof LivingEntity le && PaStance.villager(le) ? le.getUUID() : null;
                PaData.touch();
                PaTalk.reply(a, r.guardWho == null ? PaLogiTalk.line(a, "kit.st_ward_you")
                    : PaLogiTalk.line(a, "kit.st_ward", "item", e.getName().getString()));
                sendStance(p, a);
            }
            case "st_town" -> { r.helpTown = args.getBoolean("on"); PaData.touch(); sendStance(p, a); }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ talk: a few plain words open the screens

    private static final java.util.regex.Pattern COPY = java.util.regex.Pattern.compile(
        "(?i)^(please )?(copy|blueprint|remember|save|record) (this|that|my|the) (building|house|hut|place|tower|barn|build|structure)(.*)$");
    private static final java.util.regex.Pattern ERRAND = java.util.regex.Pattern.compile(
        "(?i)^(please )?(run an errand|go to (the )?(market|town|shops?)|do (some|the) shopping|sell (my|this) (loot|stuff|things))(.*)$");
    private static final java.util.regex.Pattern STANCE = java.util.regex.Pattern.compile(
        "(?i)^(please )?(be (my|a|the) |go |switch to |take )?(shield|tank|archer|bowman|medic|healer)( stance| role)?( please)?[.!]?$");
    private static final java.util.regex.Pattern BLUEPRINTS = java.util.regex.Pattern.compile("(?i)^(show |open )?(my )?(blueprints|stances?|combat roles?)( please)?[.!]?$");

    /** Words that are its own: true when heard (the order reader never sees them). */
    public static boolean heard(ServerPlayer p, PlayerAssistantEntity a, String text) {
        String t = text.trim();
        java.util.regex.Matcher m = COPY.matcher(t);
        if (m.find()) {
            String rest = m.group(5).trim();
            String name = rest.toLowerCase(Locale.ROOT).startsWith("as ") ? rest.substring(3) : rest.toLowerCase(Locale.ROOT).startsWith("called ") ? rest.substring(7) : "";
            PaBuild.copyLooked(p, a, name.replaceAll("[.!?]+$", ""));
            return true;
        }
        if (ERRAND.matcher(t).find()) { openErrand(p, a); PaTalk.reply(a, PaLogiTalk.line(a, "kit.er_screen")); return true; }
        m = STANCE.matcher(t);
        if (m.find()) {
            String w = m.group(4).toLowerCase(Locale.ROOT);
            PaStance.set(a, w.startsWith("shield") || w.equals("tank") ? PaStance.Kind.SHIELD : w.startsWith("archer") || w.startsWith("bow") ? PaStance.Kind.ARCHER : PaStance.Kind.MEDIC);
            return true;
        }
        m = BLUEPRINTS.matcher(t);
        if (m.find()) {
            if (m.group(3).toLowerCase(Locale.ROOT).startsWith("blueprint")) sendList(p, a, "");
            else sendStance(p, a);
            return true;
        }
        return false;
    }

    /** Its reply buttons: "#kit bp_fetch", "#kit bp_buy", "#kit bp_resume". */
    public static void hash(ServerPlayer p, PlayerAssistantEntity a, String[] w) {
        if (w.length < 2) return;
        switch (w[1]) {
            case "bp_fetch" -> PaBuild.fetchShort(p, a);
            case "bp_buy" -> PaBuild.buyShort(p, a);
            case "bp_resume" -> PaBuild.resume(p, a);
            default -> { }
        }
    }

    /** Its own jobs say their own last word (PaBrain stays quiet after them). */
    public static boolean saidItsOwn(@Nullable PaTask t) {
        return t != null && (t.kind == PaTask.Kind.BLUEPRINT || t.kind == PaTask.Kind.ERRAND);
    }

    // ------------------------------------------------------------------ Blueprints: to the screen

    /** The list (newest first), the one picked with its materials against what it can lay hands on, and any build. */
    public static void sendList(ServerPlayer p, PlayerAssistantEntity a, String selected) {
        CompoundTag t = new CompoundTag();
        ListTag l = new ListTag();
        List<PaBlueprint> all = PaBlueprints.of(p.getUUID());
        String sel = selected == null ? "" : selected;
        if (sel.isEmpty() && !all.isEmpty()) sel = all.get(0).name;
        PaBlueprint picked = null;
        for (PaBlueprint bp : all) {
            CompoundTag b = new CompoundTag();
            b.putString("n", bp.name);
            b.putString("size", bp.sx + "x" + bp.sz + ", " + bp.sy + " high");
            b.putInt("blocks", bp.solid());
            l.add(b);
            if (bp.name.equalsIgnoreCase(sel)) picked = bp;
        }
        t.put("list", l);
        t.putString("sel", picked == null ? "" : picked.name);
        if (picked != null) {
            Map<Item, Integer> need = picked.materials(), have = PaBuild.have(a, null);
            ListTag m = new ListTag();
            for (Map.Entry<Item, Integer> e : need.entrySet()) {
                CompoundTag c = new CompoundTag();
                c.putString("i", BuiltInRegistries.ITEM.getKey(e.getKey()).toString());
                c.putInt("need", e.getValue());
                c.putInt("have", have.getOrDefault(e.getKey(), 0));
                m.add(c);
            }
            t.put("mat", m);
        }
        t.putBoolean("area", PaBuild.AREA.containsKey(p.getUUID()));
        statusInto(p, a, t);
        send(p, "bp_list", t);
    }

    /** The ghost: the blueprint's cells (when the client hasn't them), where, turned and mirrored, and the clashes. */
    static void sendPreview(ServerPlayer p, PlayerAssistantEntity a, PaBlueprint bp, PaBuild.Preview pv, boolean full) {
        CompoundTag t = new CompoundTag();
        if (full) t.put("bp", bp.save());
        t.putString("name", bp.name);
        t.putInt("stamp", bp.stamp);
        t.putLong("anchor", pv.anchor.asLong());
        t.putInt("rot", pv.rot);
        t.putBoolean("mirror", pv.mirror);
        t.putBoolean("clear", pv.clearMine);
        List<BlockPos> cl = PaBuild.clashes(p.serverLevel(), bp, pv);
        long[] packed = new long[Math.min(cl.size(), 4000)];
        for (int i = 0; i < packed.length; i++) packed[i] = cl.get(i).asLong();
        t.putLongArray("clash", packed);
        t.putInt("clashes", cl.size());
        Map<Item, Integer> need = PaBuild.stillNeeded(p.serverLevel(), bp, pv.anchor, pv.mirror, pv.rot), have = PaBuild.have(a, pv.anchor);
        Map<Item, Integer> shortOf = new java.util.LinkedHashMap<>();
        need.forEach((k, v) -> { int h = have.getOrDefault(k, 0); if (h < v) shortOf.put(k, v - h); });
        t.putString("short", shortOf.isEmpty() ? "" : PaBuild.words(shortOf, 6));
        int[] f = PaBlueprint.size(bp.sx, bp.sz, pv.rot);
        t.putString("size", f[0] + "x" + f[1] + ", " + bp.sy + " high");
        send(p, "bp_preview", t);
    }

    static void ghostOff(ServerPlayer p) {
        send(p, "bp_ghost_off", new CompoundTag());
    }

    /** The screen's once-a-second look: the build under way (or the last), and the preview's state. */
    static void status(ServerPlayer p, PlayerAssistantEntity a) {
        CompoundTag t = new CompoundTag();
        statusInto(p, a, t);
        send(p, "bp_status", t);
    }

    private static void statusInto(ServerPlayer p, PlayerAssistantEntity a, CompoundTag t) {
        PaBuild.Run r = PaBuild.run(a);
        if (r != null) {
            CompoundTag rt = PaBuild.runTag(r);
            rt.putBoolean("active", a.plan.all().stream().anyMatch(s -> s.task != null && s.task.kind == PaTask.Kind.BLUEPRINT));
            t.put("run", rt);
        }
        PaBuild.Preview pv = PaBuild.preview(p.getUUID());
        t.putBoolean("previewing", pv != null);
        if (pv != null) t.putString("pvname", pv.name);
    }

    // ------------------------------------------------------------------ Errand: to the screen

    static void openErrand(ServerPlayer p, PlayerAssistantEntity a) {
        CompoundTag t = errandTag(p, a);
        t.putBoolean("open", true);
        send(p, "er_state", t);
    }

    static void sendErrand(ServerPlayer p, PlayerAssistantEntity a) {
        send(p, "er_state", errandTag(p, a));
    }

    static CompoundTag errandTag(ServerPlayer p, PlayerAssistantEntity a) {
        CompoundTag t = new CompoundTag();
        ServerLevel level = p.serverLevel();
        PaErrand.Draft d = PaErrand.draft(p.getUUID());
        var v = PaErrand.town(level, p.blockPosition());
        t.putString("town", v == null ? "" : com.jrpetty.mcassistant.entity.Villages.name(v.id()));
        t.putInt("dist", v == null ? -1 : (int) Math.sqrt(v.centre().distSqr(p.blockPosition())));
        t.putBoolean("openNow", PaErrand.open(level));
        ListTag sell = new ListTag();
        d.sell.forEach((k, n) -> { CompoundTag c = new CompoundTag(); c.putString("i", k); c.putInt("n", n); sell.add(c); });
        t.put("sell", sell);
        t.put("goods", PaErrand.goods(level, p, v, d));
        t.putInt("coins", d.coins);
        t.putInt("carried", PaErrand.coinsIn(a));
        t.putInt("yours", com.jrpetty.mcassistant.entity.Market.coinsHeld(p));
        PaData.Rec r = a.rec();
        t.putInt("limit", Math.min(PaConfig.spendMax(), r == null ? 64 : r.spend));
        t.putBoolean("wait", d.waitOpen);
        t.putBoolean("dark", d.homeByDark);
        t.putInt("room", PaContainers.packFreeSlots(a));
        PaErrand.Run run = PaErrand.run(a);
        if (run != null) {
            CompoundTag rt = PaErrand.runTag(run);
            rt.putBoolean("active", run.stage != PaErrand.Stage.DONE && a.plan.all().stream().anyMatch(s -> s.task != null && s.task.kind == PaTask.Kind.ERRAND));
            t.put("run", rt);
        }
        return t;
    }

    // ------------------------------------------------------------------ Stance: to the screen

    static void sendStance(ServerPlayer p, PlayerAssistantEntity a) {
        CompoundTag t = new CompoundTag();
        PaData.Rec r = a.rec();
        t.putInt("k", r == null ? 0 : r.stance);
        t.putBoolean("shield", PaStance.hasShield(a));
        t.putBoolean("bow", PaStance.hasBow(a));
        t.putInt("arrows", a.countMatching(s -> s.is(net.minecraft.world.item.Items.ARROW)));
        t.putInt("remedies", PaStance.remedies(a));
        t.putInt("apples", a.countMatching(s -> s.is(net.minecraft.world.item.Items.GOLDEN_APPLE) || s.is(net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE)));
        t.putInt("potions", a.countMatching(s -> PaStance.remedy(s) == 2));
        LivingEntity w = PaStance.ward(a);
        t.putString("ward", w == null || w == a.getOwnerPlayer() ? "you" : w.getName().getString());
        t.putBoolean("town", r == null || r.helpTown);
        var def = PaStance.defending(a);
        t.putString("defending", def == null ? "" : com.jrpetty.mcassistant.entity.Villages.name(def.id()));
        ListTag vs = new ListTag();
        List<LivingEntity> near = p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(24, 8, 24), PaStance::villager);
        near.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        for (LivingEntity e : near.subList(0, Math.min(8, near.size()))) {
            CompoundTag c = new CompoundTag();
            c.putInt("id", e.getId());
            c.putString("n", e.getName().getString());
            c.putInt("d", (int) Math.sqrt(e.distanceToSqr(p)));
            vs.add(c);
        }
        t.put("villagers", vs);
        send(p, "st_state", t);
    }

    /** Tests and the screens: an item's display name. */
    static String nameOf(Item it) {
        return new ItemStack(it).getHoverName().getString();
    }
}
