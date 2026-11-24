package com.jrpetty.mcassistant.pa;

import com.jrpetty.mcassistant.pa.PaMemory.Book;
import com.jrpetty.mcassistant.pa.PaMemory.Entry;
import com.jrpetty.mcassistant.pa.PaMemory.Kind;
import com.jrpetty.mcassistant.pa.PaMemory.When;
import com.jrpetty.mcassistant.pa.PaMemoryParse.Heard;
import com.jrpetty.mcassistant.pa.PaMemoryParse.Op;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * [pa35] The assistant acting on what it remembers. It hears a sentence for its memory first (PaMemoryParse): keeps
 * it, answers about it, forgets it; confirms in one line, in its own voice, said to its player alone (a memory is
 * never told to anyone else). Then it lives by it: the name it calls its player, the food it hands over, the wood it
 * cuts, the flowers and chests it leaves alone, who it helps, who it won't obey, its language round someone, its
 * standing rules on their triggers, and reminders spoken with a toast. Now and then, never often, a memory comes up
 * in banter. Called from small hooks in PaOrders, PaBrain, PaEvents, PaBanter, PaTalk, the entity and GatherGoal.
 */
public final class PaMemoryAct {

    private PaMemoryAct() {}

    // ------------------------------------------------------------------ hearing

    /** Something said to it: true when it was about its memory and has been answered (the order reader then skips it). */
    public static boolean hear(ServerPlayer p, PlayerAssistantEntity a, String text, PaOrders.Look look) {
        PaData.Rec r = a.rec();
        if (r == null) return false;
        Heard h = PaMemoryParse.read(text, a.getAssistantName());
        if (!h.any()) return false;
        Book b = r.memory;
        long now = a.level().getGameTime();
        switch (h.op()) {
            case PRIVATE -> say(a, PaTalk.line(a, "mem.private"));
            case PRAISED -> say(a, PaTalk.line(a, "mem.praised"));
            case PAUSE -> { b.paused = true; changed(b); say(a, PaTalk.line(a, "mem.paused")); }
            case RESUME -> { b.paused = false; changed(b); say(a, PaTalk.line(a, "mem.resumed")); }
            case ASK -> {
                if (h.question().isEmpty()) return false;
                List<PaUnderstand.Choice> cs = new ArrayList<>();
                for (String[] c : h.choices()) cs.add(new PaUnderstand.Choice(c[0], c[1]));
                PaTalk.reply(a, h.question(), cs, true);
            }
            case RECALL_ALL -> say(a, recallAll(a, r, h.subject()));
            case RECALL -> say(a, recall(a, r, h.subject()));
            case RECALL_PLACE -> {
                String ans = recallPlace(p, a, r, h.subject());
                if (ans == null) return false;                              // not a place it knows: "where is the village" goes on
                say(a, ans);
            }
            case RECALL_PERSON -> {
                Entry e = b.person(h.subject());
                if (e == null) return false;                                // someone it hasn't been told about: chat as usual
                say(a, PaTalk.line(a, e.kind == Kind.FRIEND ? "mem.recall.friend" : "mem.recall.foe", "who", e.subject));
            }
            case FORGET_LAST -> {
                Entry last = b.byId(b.lastId);
                if (h.subject().equals("it") && (last == null || now - b.lastAt > 2400)) return false;   // "forget it": never mind
                if (last == null || now - b.lastAt > 6000) { say(a, PaTalk.line(a, "mem.forgot.none")); break; }
                undo(a, r, last);
                b.remove(last.id);
                b.lastId = -1;
                changed(b);
                say(a, PaTalk.line(a, "mem.forgot", "what", what(last)));
            }
            case FORGET -> say(a, forget(a, r, h.subject()));
            case FORGET_ALL -> {
                int n = b.size() + r.waypoints.size();
                if (n == 0) { say(a, PaTalk.line(a, "mem.forgot.none")); break; }
                PaTalk.reply(a, PaTalk.line(a, "mem.ask.forgetall", "n", Integer.toString(n)),
                    List.of(new PaUnderstand.Choice("Yes, forget it all", "#memforgetall"), new PaUnderstand.Choice("No, keep it", "#memkeep")), true);
            }
            case RENAME -> {
                PaLife.rename(a, h.subject());
                if (!b.paused) { Entry e = new Entry(Kind.SELF, "name", a.displayNameCap()); e.said = text; b.put(e, now); changed(b); }
                say(a, PaTalk.line(a, "mem.got.rename", "what", a.displayNameCap()));
            }
            case PLACE -> {
                if (b.paused) { say(a, PaTalk.line(a, "mem.paused_note")); break; }
                if (h.subject().isEmpty()) { PaTalk.reply(a, h.question().isEmpty() ? "What shall I call this place?" : h.question(), null, true); break; }
                BlockPos at = h.looked() ? look.block() : (look.block() != null && look.block().distSqr(p.blockPosition()) <= 6 * 6 ? look.block() : p.blockPosition());
                if (at == null || at.distSqr(p.blockPosition()) > 160 * 160) { say(a, PaTalk.line(a, "mem.ask.where", "what", h.subject())); break; }
                String name = h.subject();
                if (r.waypoints.size() >= 32 && !r.waypoints.containsKey(name)) { say(a, "I can only remember 32 places. Forget one first, in the Memory tab."); break; }
                r.waypoints.put(name, at.immutable());
                r.waypointDims.put(name, p.level().dimension().location().toString());
                if (name.matches("base|home|house|my house|camp|hideout")) a.setHome(at.immutable());
                b.lastId = -1;
                lastPlace.put(r.owner, name);
                b.lastAt = now;
                changed(b);
                PaData.touch();
                PaNet.ping(p, at, PaNet.cap(name), "MOVE", "waypoint");
                say(a, PaTalk.line(a, "mem.got.place", "what", name));
            }
            case SAVE -> {
                if (b.paused) { say(a, PaTalk.line(a, "mem.paused_note")); break; }
                Entry e = h.entry();
                boolean replacing = b.entries.stream().anyMatch(e::same);
                if (!replacing && b.size() >= PaConfig.memoryCap()) { say(a, PaTalk.line(a, "mem.full", "n", Integer.toString(PaConfig.memoryCap()))); break; }
                e.said = text.length() > 160 ? text.substring(0, 160) : text;
                if (e.kind == Kind.REMINDER) e.due = e.when == When.TIME ? now + Math.max(20, e.n) : -1;
                b.put(e, now);
                apply(a, r, e);
                if (h.also() != null) { h.also().said = e.said; b.put(h.also(), now); apply(a, r, h.also()); b.lastId = e.id; }
                changed(b);
                PaData.touch();
                say(a, confirm(a, e));
            }
            default -> { return false; }
        }
        PaData.touch();
        return true;
    }

    /** The place said last, for "forget that" after "this is my base". */
    private static final Map<UUID, String> lastPlace = new HashMap<>();

    /** A reply about its memory: said to its player alone (never a bubble anyone else reads). */
    static void say(PlayerAssistantEntity a, String text) {
        PaTalk.reply(a, text, null, true);
    }

    /** What the confirmation line names. */
    private static String what(Entry e) {
        return switch (e.kind) {
            case NAME -> "your name";
            case FAV -> "your favourite " + e.value;
            case SELF -> e.subject.equals("name") ? "my name" : "what you said about my " + e.subject;
            case RULE -> "the rule about " + e.subject;
            case REMINDER -> "that reminder";
            case STOCK -> "the " + e.subject;
            case ONLY_ME -> "the only-you rule";
            case CLEAN -> "keeping it clean around " + e.subject;
            default -> e.subject;
        };
    }

    /** One line, in its personality, saying what it understood. */
    static String confirm(PlayerAssistantEntity a, Entry e) {
        return switch (e.kind) {
            case NAME -> PaTalk.line(a, "mem.got.name", "what", e.subject, "owner", e.subject);
            case LIKE -> PaTalk.line(a, "mem.got.like", "what", e.subject);
            case DISLIKE -> PaTalk.line(a, "mem.got.dislike", "what", e.subject);
            case FAV -> PaTalk.line(a, "mem.got.fav", "what", e.subject, "thing", e.value);
            case FACT -> PaTalk.line(a, "mem.got.fact", "what", e.value.isEmpty() ? e.subject : e.subject + ": " + e.value);
            case SELF -> PaTalk.line(a, e.n < 0 ? "mem.got.self_bad" : "mem.got.self_good", "what", e.subject);
            case FRIEND -> PaTalk.line(a, "mem.got.friend", "who", e.subject);
            case FOE -> PaTalk.line(a, "mem.got.foe", "who", e.subject);
            case ONLY_ME -> PaTalk.line(a, "mem.got.onlyme");
            case CLEAN -> PaTalk.line(a, "mem.got.clean", "who", e.subject);
            case SPARE -> PaTalk.line(a, "mem.got.spare", "what", e.subject);
            case STOCK -> PaTalk.line(a, "mem.got.stock", "what", e.subject, "n", Integer.toString(e.n));
            case RULE -> PaTalk.line(a, "mem.got.rule", "when", whenSays(e), "what", PaMemoryParse.sentenceFor(e.subject));
            case REMINDER -> PaTalk.line(a, "mem.got.reminder", "when", whenSays(e), "what", e.value.isEmpty() ? "" : " to " + e.value);
        };
    }

    /** When a rule or reminder goes off, as said: "in ten minutes", "at dawn", "when you get home", "when Sam is about". */
    public static String whenSays(Entry e) {
        return switch (e.when) {
            case TIME -> "in " + duration(e.n);
            case MORNING -> e.kind == Kind.REMINDER ? "at dawn" : "every morning";
            case NIGHT -> e.kind == Kind.REMINDER ? "at nightfall" : "when it gets dark";
            case NEAR -> "when " + e.extra + " is about";
            default -> e.when.says;
        };
    }

    /** Ticks as words: "ten minutes", "an hour and a half", "thirty seconds". */
    public static String duration(long ticks) {
        long s = Math.max(1, Math.round(ticks / 20.0));
        if (s < 60) return com.jrpetty.mcassistant.pa.voice.PaSpeech.words((int) s) + (s == 1 ? " second" : " seconds");
        long m = Math.round(s / 60.0);
        if (m < 60) return (m == 1 ? "a minute" : com.jrpetty.mcassistant.pa.voice.PaSpeech.words((int) m) + " minutes");
        long h = m / 60, rest = m % 60;
        String hs = h == 1 ? "an hour" : com.jrpetty.mcassistant.pa.voice.PaSpeech.words((int) h) + " hours";
        return rest == 30 ? hs + " and a half" : rest == 0 ? hs : hs + " and " + com.jrpetty.mcassistant.pa.voice.PaSpeech.words((int) rest) + " minutes";
    }

    // ------------------------------------------------------------------ asking it

    static String recallAll(PlayerAssistantEntity a, PaData.Rec r, String about) {
        Book b = r.memory;
        if (!about.isEmpty() && !about.equals("me") && !about.equals("us")) {
            List<Entry> es = b.about(about);
            BlockPos place = r.waypoints.get(about.toLowerCase(Locale.ROOT));
            if (es.isEmpty() && place == null) return PaTalk.line(a, "mem.recall.unknown", "what", "anything about " + about);
            List<String> parts = new ArrayList<>();
            for (Entry e : es) parts.add(e.line());
            if (place != null) parts.add("The " + about + " is at " + place.getX() + " " + place.getY() + " " + place.getZ());
            return PaTalk.line(a, "mem.recall.intro") + " " + String.join("; ", parts) + ".";
        }
        if (b.size() == 0 && r.waypoints.isEmpty()) return PaTalk.line(a, "mem.recall.none");
        List<String> parts = new ArrayList<>();
        for (PaMemory.Group g : PaMemory.Group.values()) {
            if (g == PaMemory.Group.PLACES) {
                if (!r.waypoints.isEmpty()) parts.add("places: " + String.join(", ", r.waypoints.keySet()));
                continue;
            }
            for (Entry e : b.entries) if (e.kind.group() == g && parts.size() < 7) parts.add(e.line());
        }
        int more = b.size() + (r.waypoints.isEmpty() ? 0 : 1) - parts.size();
        for (int i = 0; i < parts.size(); i++) parts.set(i, midSentence(parts.get(i)));   // [pa35] one sentence: "call you Boss; you like birch trees"
        return PaTalk.line(a, "mem.recall.intro") + " " + String.join("; ", parts) + "." + (more > 0 ? " And " + more + " more in the Memory tab." : "");
    }

    /** [pa35] A Memory-tab line as part of a sentence: its capital dropped, unless it's "I", a name or an initialism. */
    static String midSentence(String s) {
        if (s.length() < 2 || s.startsWith("I ") || Character.isUpperCase(s.charAt(1))) return s;
        String first = s.split("[ :;,]", 2)[0];
        if (first.matches("(?:Call|You|Your|Never|Always|Orders|No|Remind|Its|When|Every|At|If|The|A|An|Before|After|In)")) return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        return s;                                                    // probably a name ("Sam is your friend")
    }

    static String recall(PlayerAssistantEntity a, PaData.Rec r, String what) {
        Book b = r.memory;
        if (what.equals("name")) {
            Entry n = b.first(Kind.NAME);
            return n == null ? PaTalk.line(a, "mem.recall.name", "what", r.ownerName) + (b.paused ? "" : " Tell me what you'd like to be called, if you like.")
                : PaTalk.line(a, "mem.recall.name", "what", n.subject);
        }
        if (what.startsWith("favourite ")) {
            String cat = what.substring(10).trim();
            String fav = b.favourite(cat);
            if (fav == null && cat.equals("colour")) fav = b.favourite("color");
            if (fav == null) for (Entry e : b.of(Kind.LIKE)) if (e.extra.startsWith(cat)) { fav = e.subject; break; }
            return fav == null ? PaTalk.line(a, "mem.recall.unknown", "what", "your favourite " + cat) : PaTalk.line(a, "mem.recall.fav", "what", fav, "thing", cat);
        }
        Kind[] kinds = switch (what) {
            case "likes" -> new Kind[]{Kind.LIKE, Kind.FAV};
            case "dislikes" -> new Kind[]{Kind.DISLIKE};
            case "rules" -> new Kind[]{Kind.RULE, Kind.SPARE, Kind.STOCK, Kind.CLEAN, Kind.ONLY_ME};
            case "reminders" -> new Kind[]{Kind.REMINDER};
            case "people" -> new Kind[]{Kind.FRIEND, Kind.FOE, Kind.ONLY_ME, Kind.CLEAN};
            default -> new Kind[0];
        };
        if (what.equals("places")) {
            if (r.waypoints.isEmpty()) return PaTalk.line(a, "mem.recall.unknown", "what", "any places");
            return PaTalk.line(a, "mem.recall.intro") + " " + String.join(", ", r.waypoints.keySet()) + ".";
        }
        List<String> parts = new ArrayList<>();
        for (Entry e : b.of(kinds)) {
            String l = e.line();
            if (e.kind == Kind.REMINDER) l += " " + (e.due >= 0 ? "in " + duration(Math.max(20, e.due - a.level().getGameTime())) : whenSays(e));
            parts.add(l);
        }
        if (parts.isEmpty()) return PaTalk.line(a, "mem.recall.unknown", "what", "any " + what);
        return PaTalk.line(a, "mem.recall.intro") + " " + String.join("; ", parts) + ".";
    }

    /** Where a remembered place is, with a marker; null when it isn't one it knows (the question goes on to the order reader). */
    @Nullable
    static String recallPlace(ServerPlayer p, PlayerAssistantEntity a, PaData.Rec r, String name) {
        String k = name.toLowerCase(Locale.ROOT);
        BlockPos at = r.waypoints.get(k);
        if (at == null && k.matches("base|home|house")) {
            at = PaOrders.homeOf(a);
            for (String alt : new String[]{"base", "home", "house"}) if (at == null) at = r.waypoints.get(alt);
        }
        if (at == null) return null;
        double dx = at.getX() + 0.5 - p.getX(), dz = at.getZ() + 0.5 - p.getZ();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        PaNet.ping(p, at, PaNet.cap(name), "MOVE", "found");
        if (dist < 6) return "You're standing on it. The " + name + " is right here.";
        return PaTalk.line(a, "mem.recall.place", "what", name, "dist", Integer.toString(dist), "dir", com.jrpetty.mcassistant.pa.voice.PaSpeech.bearing(dx, dz));
    }

    // ------------------------------------------------------------------ forgetting

    static String forget(PlayerAssistantEntity a, PaData.Rec r, String what) {
        Book b = r.memory;
        List<Entry> gone = new ArrayList<>();
        String k = what.toLowerCase(Locale.ROOT);
        if (k.equals("reminders")) gone.addAll(b.of(Kind.REMINDER));
        else if (k.equals("rules")) gone.addAll(b.of(Kind.RULE, Kind.SPARE, Kind.STOCK, Kind.CLEAN, Kind.ONLY_ME));
        else if (k.startsWith("favourite ")) { for (Entry e : b.of(Kind.FAV)) if (e.value.equalsIgnoreCase(k.substring(10).trim())) gone.add(e); }
        else if (k.matches("my name|your name|what i'm called|what to call me")) gone.addAll(b.of(Kind.NAME));
        else gone.addAll(b.about(what));
        boolean place = r.waypoints.remove(k) != null;
        if (place) r.waypointDims.remove(k);
        if (gone.isEmpty() && !place) return PaTalk.line(a, "mem.forgot.none");
        for (Entry e : gone) { undo(a, r, e); b.remove(e.id); }
        changed(b);
        PaData.touch();
        return PaTalk.line(a, "mem.forgot", "what", k.equals("reminders") || k.equals("rules") ? "your " + k : gone.size() == 1 && !place ? what(gone.get(0)) : what);
    }

    /** Forget everything (the confirmed "forget everything"). */
    public static void forgetAll(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r == null) return;
        for (Entry e : new ArrayList<>(r.memory.entries)) undo(a, r, e);
        r.memory.entries.clear();
        r.waypoints.clear();
        r.waypointDims.clear();
        changed(r.memory);
        PaData.touch();
        say(a, PaTalk.line(a, "mem.forgot.all"));
    }

    /** What an entry changed when kept, put back when it's forgotten. */
    private static void undo(PlayerAssistantEntity a, PaData.Rec r, Entry e) {
        if (e.kind == Kind.SPARE && e.extra.equals("#chests") && !r.useChests && e.extra.contains("chests")) r.useChests = true;
        if (e.kind == Kind.STOCK) { String id = itemId(e.subject); if (id != null) r.keep.remove(id); }
    }

    /** What an entry changes at once when kept: its chests left alone, its pack's stock kept back. */
    private static void apply(PlayerAssistantEntity a, PaData.Rec r, Entry e) {
        if (e.kind == Kind.SPARE && e.extra.equals("#chests")) r.useChests = false;
        if (e.kind == Kind.SPARE && e.extra.equals("#builds")) r.breakNearBuilds = false;
        if (e.kind == Kind.STOCK) { String id = itemId(e.subject); if (id != null) r.keep.add(id); }
    }

    /** "torches" -> "minecraft:torch" (the registry's name for a word), or null when there's no such item. */
    @Nullable
    static String itemId(String word) {
        String w = PaMemory.Entry.key(word).replace(' ', '_');
        for (String c : new String[]{w, PaMemory.stem(w), w.replaceAll("es$", ""), w.equals("food") ? "bread" : "", w.equals("arrows") ? "arrow" : ""}) {
            if (c.isEmpty()) continue;
            ResourceLocation id = ResourceLocation.tryParse("minecraft:" + c);
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) return id.toString();
        }
        return null;
    }

    // ------------------------------------------------------------------ the Memory tab

    /** The panel's view of its memory: each entry with its group, line, sentence and a detail; the places; paused; the cap. */
    public static void state(CompoundTag t, PaData.Rec r, @Nullable PlayerAssistantEntity a, ServerPlayer p) {
        net.minecraft.nbt.ListTag l = new net.minecraft.nbt.ListTag();
        long now = p.serverLevel().getGameTime();
        for (Entry e : r.memory.entries) {
            CompoundTag c = new CompoundTag();
            c.putInt("id", e.id);
            c.putString("g", e.kind.group().name());
            c.putString("t", e.line());
            c.putString("s", e.said);
            String d = switch (e.kind) {
                case REMINDER -> e.due >= 0 ? "in " + duration(Math.max(20, e.due - now)) : whenSays(e);
                case RULE -> whenSays(e);
                default -> "";
            };
            c.putString("d", d);
            l.add(c);
        }
        for (Map.Entry<String, BlockPos> w : r.waypoints.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putInt("id", 0);
            c.putString("g", PaMemory.Group.PLACES.name());
            c.putString("t", PaNet.cap(w.getKey()));
            c.putString("s", "this is the " + w.getKey());
            c.putString("place", w.getKey());
            BlockPos at = w.getValue();
            String dim = r.waypointDims.getOrDefault(w.getKey(), "");
            double dx = at.getX() + 0.5 - p.getX(), dz = at.getZ() + 0.5 - p.getZ();
            boolean here = dim.isEmpty() || dim.equals(p.level().dimension().location().toString());
            c.putString("d", here ? Math.round(Math.sqrt(dx * dx + dz * dz)) + " blocks " + com.jrpetty.mcassistant.pa.voice.PaSpeech.bearing(dx, dz)
                : "in the " + dim.substring(dim.indexOf(':') + 1).replace('_', ' '));
            l.add(c);
        }
        t.put("mem", l);
        t.putBoolean("memPaused", r.memory.paused);
        t.putInt("memCap", PaConfig.memoryCap());
        t.putInt("memN", r.memory.size());
    }

    /** A click on the Memory tab: delete, edit (said again, read afresh), add, pause, forget all, a place deleted or renamed. */
    public static void op(ServerPlayer p, PlayerAssistantEntity a, PaData.Rec r, CompoundTag args) {
        Book b = r.memory;
        String op = args.getString("op");
        switch (op) {
            case "delete" -> {
                Entry e = b.byId(args.getInt("id"));
                if (e != null) { undo(a, r, e); b.remove(e.id); changed(b); }
            }
            case "deletePlace" -> {
                String k = args.getString("place");
                r.waypoints.remove(k);
                r.waypointDims.remove(k);
                changed(b);
            }
            case "renamePlace" -> {
                String k = args.getString("place"), to = PaMemoryParse.clean(args.getString("text").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", "").trim());
                BlockPos at = r.waypoints.remove(k);
                String dim = r.waypointDims.remove(k);
                if (at != null && !to.isEmpty()) { r.waypoints.put(to.length() > 20 ? to.substring(0, 20) : to, at); r.waypointDims.put(to, dim == null ? "" : dim); }
                else if (at != null) { r.waypoints.put(k, at); r.waypointDims.put(k, dim == null ? "" : dim); }
                changed(b);
            }
            case "edit" -> {
                Entry old = b.byId(args.getInt("id"));
                String text = args.getString("text").trim();
                if (old == null || text.isEmpty()) break;
                if (text.length() > 160) text = text.substring(0, 160);
                if (PaMemoryParse.secret(text)) { PaNet.note(p, "Not kept: that looks like something private."); break; }
                Heard h = PaMemoryParse.read(text, a.getAssistantName());
                Entry fresh = h.op() == Op.SAVE && h.entry() != null ? h.entry() : new Entry(Kind.FACT, PaMemoryParse.sentenceFor(text), "");
                undo(a, r, old);
                b.remove(old.id);
                fresh.said = text;
                if (fresh.kind == Kind.REMINDER) fresh.due = fresh.when == When.TIME ? a.level().getGameTime() + Math.max(20, fresh.n) : -1;
                b.put(fresh, a.level().getGameTime());
                apply(a, r, fresh);
                changed(b);
            }
            case "add" -> {
                String text = args.getString("text").trim();
                if (text.isEmpty()) break;
                if (!hear(p, a, text, PaOrders.Look.NONE)) {
                    if (b.paused) { PaNet.note(p, "Memory's paused: turn it on first."); break; }
                    if (PaMemoryParse.secret(text)) { say(a, PaTalk.line(a, "mem.private")); break; }
                    if (b.size() >= PaConfig.memoryCap()) { say(a, PaTalk.line(a, "mem.full", "n", Integer.toString(PaConfig.memoryCap()))); break; }
                    Entry e = new Entry(Kind.FACT, PaMemoryParse.sentenceFor(PaMemoryParse.norm(text, a.getAssistantName())), "");
                    e.said = text.length() > 160 ? text.substring(0, 160) : text;
                    b.put(e, a.level().getGameTime());
                    changed(b);
                    say(a, confirm(a, e));
                }
            }
            case "pause" -> {
                b.paused = args.getBoolean("on");
                changed(b);
                say(a, PaTalk.line(a, b.paused ? "mem.paused" : "mem.resumed"));
            }
            case "forgetAll" -> forgetAll(a);
            default -> { }
        }
        PaData.touch();
    }

    // ------------------------------------------------------------------ living by it

    /** [pa35] Where a moment happened, as said: the nearest named place within 48 blocks, else the world's name, else "". */
    public static String placeNear(PaData.Rec r, BlockPos at, String dim) {
        String best = "";
        double bestD = 48 * 48;
        for (Map.Entry<String, BlockPos> w : r.waypoints.entrySet()) {
            double d = w.getValue().distSqr(at);
            if (d < bestD) { bestD = d; best = "by the " + w.getKey(); }
        }
        if (!best.isEmpty()) return best;
        return dim.equals("the_nether") ? "the Nether" : dim.equals("the_end") ? "the End" : "";
    }

    /** What it calls its player: the name they asked for, else their own. */
    public static String callName(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r != null) { Entry n = r.memory.first(Kind.NAME); if (n != null) return n.subject; }
        return a.ownerName().isEmpty() ? "you" : a.ownerName();
    }

    /** Compiled from the book for the hot paths (blocks spared, wood liked and disliked), rebuilt when the book changes. */
    private static final class Cache {
        int version = -1;
        final Set<Block> spare = new HashSet<>();
        final List<String> spareWords = new ArrayList<>();
        boolean spareFlowers, spareCrops, spareLogs, spareAnimals;
        final Set<String> likeWood = new HashSet<>(), hateWood = new HashSet<>();
        final Set<String> hateMobs = new HashSet<>();
        boolean any;
    }

    private static final Map<Book, Cache> CACHES = new WeakHashMap<>();
    private static final Map<Book, Integer> VERSIONS = new WeakHashMap<>();

    /** The book changed: the hot-path cache is rebuilt on next use. */
    static void changed(Book b) {
        synchronized (VERSIONS) { VERSIONS.merge(b, 1, Integer::sum); }
    }

    private static Cache cache(@Nullable PaData.Rec r) {
        if (r == null) return EMPTY;
        Book b = r.memory;
        int v;
        synchronized (VERSIONS) { v = VERSIONS.getOrDefault(b, 0); }
        Cache c;
        synchronized (CACHES) { c = CACHES.computeIfAbsent(b, k -> new Cache()); }
        if (c.version == v) return c;
        c.spare.clear(); c.spareWords.clear(); c.likeWood.clear(); c.hateWood.clear(); c.hateMobs.clear();
        c.spareFlowers = c.spareCrops = c.spareLogs = c.spareAnimals = false;
        for (Entry e : b.entries) {
            if (e.kind == Kind.SPARE) {
                for (String part : e.extra.split(",")) {
                    String s = part.trim();
                    if (s.equals("#flowers")) c.spareFlowers = true;
                    else if (s.equals("#crops")) c.spareCrops = true;
                    else if (s.equals("#logs")) c.spareLogs = true;
                    else if (s.equals("#animals")) c.spareAnimals = true;
                    else if (s.startsWith("~")) c.spareWords.add(PaMemory.stem(s.substring(1)));
                    else if (s.startsWith("minecraft:")) {
                        ResourceLocation id = ResourceLocation.tryParse(s);
                        if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) c.spare.add(BuiltInRegistries.BLOCK.get(id));
                    }
                }
            }
            String cat = e.extra.split(";")[0];
            String wood = PaMemory.Entry.key(e.subject).replaceAll(" (?:wood|logs?|planks|trees?)$", "").replace(' ', '_');
            if (e.kind == Kind.LIKE && cat.equals("wood")) c.likeWood.add(wood);
            if (e.kind == Kind.FAV && (e.value.matches("wood|tree|log|logs|planks") || PaMemoryParse.WOODS.contains(PaMemory.Entry.key(e.subject)))) c.likeWood.add(wood);
            if (e.kind == Kind.DISLIKE && cat.equals("wood")) c.hateWood.add(wood);
            if (e.kind == Kind.DISLIKE && cat.equals("mob")) c.hateMobs.add(PaMemory.stem(PaMemory.Entry.key(e.subject)).replace(' ', '_').replace("endermen", "enderman"));
        }
        c.any = !c.spare.isEmpty() || !c.spareWords.isEmpty() || c.spareFlowers || c.spareCrops || c.spareLogs || c.spareAnimals;
        c.version = v;
        return c;
    }

    private static final Cache EMPTY = new Cache();

    /** Is this block one it was told never to break (the blue flowers, the cherry trees, the crops, the glass)? Cheap when nothing is spared. */
    public static boolean spared(PlayerAssistantEntity a, BlockPos pos) {
        Cache c = cache(a.rec());
        if (!c.any) return false;
        BlockState st = a.level().getBlockState(pos);
        if (st.isAir()) return false;
        if (c.spare.contains(st.getBlock())) return true;
        if (c.spareFlowers && st.is(net.minecraft.tags.BlockTags.FLOWERS)) return true;
        if (c.spareCrops && (st.getBlock() instanceof net.minecraft.world.level.block.CropBlock || st.getBlock() instanceof net.minecraft.world.level.block.StemBlock
            || st.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH))) return true;
        if (c.spareLogs && st.is(net.minecraft.tags.BlockTags.LOGS)) return true;
        if (!c.spareWords.isEmpty()) {
            String path = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
            for (String w : c.spareWords) if (path.contains(w)) return true;
        }
        return false;
    }

    /** Is this creature one it was told to leave be ("don't kill my cows")? */
    public static boolean sparesEntity(PlayerAssistantEntity a, @Nullable LivingEntity e) {
        if (e == null || a.level().isClientSide) return false;
        Cache c = cache(a.rec());
        return c.spareAnimals && e instanceof net.minecraft.world.entity.animal.Animal;
    }

    /** Where a log it might chop ranks: a liked wood sooner (as if half as far), a disliked one only when there's nothing else. */
    public static double gatherRank(PlayerAssistantEntity a, BlockState st, double d) {
        Cache c = cache(a.rec());
        if (c.likeWood.isEmpty() && c.hateWood.isEmpty()) return d;
        if (!st.is(net.minecraft.tags.BlockTags.LOGS)) return d;
        String path = BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        String wood = path.replaceAll("^stripped_", "").replaceAll("_(?:log|wood|stem|hyphae)$", "");
        if (c.hateWood.contains(wood)) return d + 1_000_000;
        if (c.likeWood.contains(wood)) return d * 0.25;
        return c.likeWood.isEmpty() ? d : d + 64;
    }

    /** Does its player hate this creature (it deals with those first, and says so)? */
    public static boolean hated(PlayerAssistantEntity a, Entity e) {
        Cache c = cache(a.rec());
        if (c.hateMobs.isEmpty()) return false;
        String path = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
        return c.hateMobs.contains(path) || c.hateMobs.contains("monster") && e instanceof Monster || c.hateMobs.contains("mob") && e instanceof Monster;
    }

    /** The word for "food" when it hands food over: its player's favourite if it carries some (its item's name), else any food. */
    public static String foodWord(PlayerAssistantEntity a) {
        Item fav = favouriteFood(a);
        if (fav != null && a.countMatching(s -> s.is(fav)) > 0) return BuiltInRegistries.ITEM.getKey(fav).getPath();
        return "food";
    }

    /** The item its player's favourite food is ("steak" is cooked beef), or null. */
    @Nullable
    public static Item favouriteFood(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r == null) return null;
        String fav = r.memory.favourite("food");
        if (fav == null) for (Entry e : r.memory.of(Kind.LIKE)) if (e.extra.startsWith("food")) { fav = e.subject; break; }
        return fav == null ? null : foodItem(fav);
    }

    static Item foodItem(String word) {
        String k = PaMemory.stem(PaMemory.Entry.key(word));
        String id = switch (k) {
            case "steak", "beef", "cooked beef" -> "cooked_beef";
            case "porkchop", "pork", "bacon" -> "cooked_porkchop";
            case "chicken" -> "cooked_chicken";
            case "mutton", "lamb" -> "cooked_mutton";
            case "rabbit" -> "cooked_rabbit";
            case "fish", "cod" -> "cooked_cod";
            case "salmon", "sushi" -> "cooked_salmon";
            case "potato", "baked potato" -> "baked_potato";
            case "golden apple" -> "golden_apple";
            case "golden carrot" -> "golden_carrot";
            case "pie", "pumpkin pie" -> "pumpkin_pie";
            case "berry", "sweet berry" -> "sweet_berries";
            case "stew", "mushroom stew", "soup" -> "mushroom_stew";
            case "beetroot soup" -> "beetroot_soup";
            case "melon" -> "melon_slice";
            case "kelp", "dried kelp" -> "dried_kelp";
            default -> k.replace(' ', '_');
        };
        ResourceLocation rl = ResourceLocation.tryParse("minecraft:" + id);
        return rl != null && BuiltInRegistries.ITEM.containsKey(rl) ? BuiltInRegistries.ITEM.get(rl) : null;
    }

    /** The language it may use just now: clean while anyone it was told to keep it clean round is near. */
    public static int language(PlayerAssistantEntity a, int level) {
        PaData.Rec r = a.rec();
        if (r == null || level == 0) return level;
        for (Entry e : r.memory.of(Kind.CLEAN)) {
            for (net.minecraft.world.entity.player.Player o : a.level().players())
                if (o.getGameProfile().getName().equalsIgnoreCase(e.subject) && o.distanceToSqr(a) < 32 * 32) return 0;
        }
        return level;
    }

    /** May this other player give it orders: a friend it was told to help or obey, unless it takes orders from its player only. */
    public static boolean friendOrders(PlayerAssistantEntity a, ServerPlayer p) {
        PaData.Rec r = a.rec();
        if (r == null || r.memory.onlyMe()) return false;
        Entry e = r.memory.person(p.getGameProfile().getName());
        return e != null && e.kind == Kind.FRIEND && (e.extra.contains("help") || e.extra.contains("orders"));
    }

    public static boolean friend(PlayerAssistantEntity a, String name) {
        PaData.Rec r = a.rec();
        return r != null && r.memory.friend(name);
    }

    public static boolean foe(PlayerAssistantEntity a, String name) {
        PaData.Rec r = a.rec();
        return r != null && r.memory.foe(name);
    }

    /** Someone else asking about its player ("what's Dev's favourite food?", "where's his base?"): never told. */
    public static boolean askingAbout(String text, String owner) {
        String t = " " + PaMemoryParse.norm(text, "") + " ";
        String o = owner == null ? "" : owner.toLowerCase(Locale.ROOT);
        boolean who = (!o.isEmpty() && t.contains(" " + o)) || t.matches(".*\\b(?:your owner|your master|your boss|your player|he|she|they|his|her|their)\\b.*");
        return who && t.matches(".*\\b(?:favourite|fav|like|likes|love|hate|hates|base|house|home|mine|farm|live|lives|told you|remember|password|secret|friends?|where|stuff|chests?)\\b.*")
            && t.matches(".*\\b(?:what|where|who|which|does|do|tell me|is)\\b.*");
    }

    // ------------------------------------------------------------------ its rules, on their triggers (each assistant, about once a second)

    /** Per assistant: the last morning and night it acted on, whether its player was home, raining, who was near, last idle run. */
    private static final class Watch {
        long morningDay = -1, nightDay = -1, lowAt = -100000, idleAt = -100000, stockAt = -100000, banterAt = -100000, hatedAt = -100000;
        boolean wasHome = true, wasRaining, primed;
        final Set<String> near = new HashSet<>();
        final Map<String, Long> foeWarned = new HashMap<>(), friendSeen = new HashMap<>(), helpAt = new HashMap<>(), selfAt = new HashMap<>();
    }

    private static final Map<UUID, Watch> WATCH = new HashMap<>();

    private static Watch watch(PlayerAssistantEntity a) {
        return WATCH.computeIfAbsent(a.getUUID(), k -> new Watch());
    }

    /** About once a second, per assistant: its rules' triggers, its friends in trouble, its foes about, its stock. */
    public static void tick(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r == null || r.memory.size() == 0) return;
        if (!(a.getOwnerPlayer() instanceof ServerPlayer owner) || owner.level() != a.level()) return;
        Watch w = watch(a);
        long dayTime = a.level().getDayTime(), day = dayTime / 24000L, time = dayTime % 24000L;
        boolean overworld = a.level().dimension() == net.minecraft.world.level.Level.OVERWORLD;
        BlockPos home = PaOrders.homeOf(a);
        if (home == null) for (String k : new String[]{"base", "home", "house"}) if (home == null) home = r.waypoints.get(k);
        boolean atHome = home != null && owner.blockPosition().distSqr(home) < 12 * 12;
        boolean raining = a.level().isRaining();
        boolean low = owner.getHealth() <= 8 || owner.getFoodData().getFoodLevel() <= 6;
        if (!w.primed) {                                  // the first look only learns how things stand (a login isn't "getting home")
            w.primed = true;
            w.wasHome = atHome;
            w.wasRaining = raining;
            if (time < 1000) w.morningDay = day;
            if (time >= 12500 && time < 13500) w.nightDay = day;
        }
        Set<String> nowNear = new HashSet<>();
        for (net.minecraft.world.entity.player.Player o : a.level().players())
            if (o != owner && o.distanceToSqr(owner) < 32 * 32) nowNear.add(o.getGameProfile().getName().toLowerCase(Locale.ROOT));
        for (Entry e : new ArrayList<>(r.memory.entries)) {
            if (e.kind != Kind.RULE) continue;
            boolean fire = switch (e.when) {
                case MORNING -> overworld && time < 1000 && w.morningDay != day;
                case NIGHT -> overworld && time >= 12500 && time < 13500 && w.nightDay != day;
                case HOME -> atHome && !w.wasHome;
                case RAIN -> raining && !w.wasRaining;
                case LOW -> low && a.tickCount - w.lowAt > 1200;
                case IDLE -> a.plan.idle() && a.getTarget() == null && !a.plan.paused && a.tickCount - w.idleAt > 6000;
                case NEAR -> nowNear.contains(e.extra.toLowerCase(Locale.ROOT)) && !w.near.contains(e.extra.toLowerCase(Locale.ROOT));
                default -> false;
            };
            if (!fire) continue;
            if (e.when == When.LOW) w.lowAt = a.tickCount;
            if (e.when == When.IDLE) w.idleAt = a.tickCount;
            run(owner, a, r, e);
        }
        if (overworld && time < 1000) w.morningDay = day;
        if (overworld && time >= 12500 && time < 13500) w.nightDay = day;
        w.wasHome = atHome;
        w.wasRaining = raining;
        w.near.clear();
        w.near.addAll(nowNear);
        people(owner, a, r, w);
        stock(owner, a, r, w);
        hatedNear(owner, a, r, w);
    }

    /** A rule goes off: it says so (unless told to be quiet) and does it. */
    static void run(ServerPlayer owner, PlayerAssistantEntity a, PaData.Rec r, Entry e) {
        String act = e.value;
        if (act.equals("#clean")) return;
        PaBondAct.event(a, PaBond.Event.PROMISE_KEPT);                 // [pa35] keeping a standing rule it was given (capped a day)
        if (act.equals("#warn")) {
            PaTalk.reply(a, PaTalk.line(a, "mem.rule.warn", "who", e.extra.isEmpty() ? "someone" : e.extra), null, true);
            return;
        }
        if (r.mute < 2) PaTalk.reply(a, PaTalk.line(a, "mem.rule.fired", "when", whenSays(e), "what", PaMemoryParse.sentenceFor(e.subject)), null, true);
        if (act.equals("#fetchdeath")) { PaBanter.fetchDeath(a); return; }
        PaOrders.rule(owner, a, act);
    }

    /** Its player has respawned: "if I die" rules (its things are where they fell; PaBanter knows where). */
    public static void ownerRespawned(ServerPlayer p) {
        PlayerAssistantEntity a = PaLife.find(p);
        if (a == null) return;
        PaData.Rec r = a.rec();
        if (r == null) return;
        for (Entry e : new ArrayList<>(r.memory.entries)) if (e.kind == Kind.RULE && e.when == When.DEATH) run(p, a, r, e);
    }

    /** Friends: helped when something goes for them, greeted now and then. Foes: its player warned when they come near. */
    private static void people(ServerPlayer owner, PlayerAssistantEntity a, PaData.Rec r, Watch w) {
        for (Entry e : r.memory.of(Kind.FRIEND, Kind.FOE)) {
            ServerPlayer o = null;
            for (net.minecraft.world.entity.player.Player x : a.level().players())
                if (x != owner && x.getGameProfile().getName().equalsIgnoreCase(e.subject) && x instanceof ServerPlayer sp) o = sp;
            if (o == null || o.distanceToSqr(a) > 48 * 48) continue;
            String key = e.subject.toLowerCase(Locale.ROOT);
            if (e.kind == Kind.FOE) {
                if (o.distanceToSqr(owner) < 32 * 32 && a.tickCount - w.foeWarned.getOrDefault(key, -100000L) > 6000) {
                    w.foeWarned.put(key, (long) a.tickCount);
                    if (r.mute < 2) PaTalk.reply(a, PaTalk.line(a, "mem.rule.warn", "who", o.getGameProfile().getName()), null, true);
                }
                continue;
            }
            if (e.extra.contains("help") && a.getTarget() == null && o.distanceToSqr(a) < 24 * 24) {
                for (Monster m : a.level().getEntitiesOfClass(Monster.class, o.getBoundingBox().inflate(8))) {
                    if (!m.isAlive() || m.getTarget() != o) continue;
                    if (m instanceof net.minecraft.world.entity.monster.Creeper && !a.canSnipeCreepers()) continue;
                    a.equipBestWeapon();
                    a.setTarget(m);
                    if (a.tickCount - w.helpAt.getOrDefault(key, -100000L) > 1200) {
                        w.helpAt.put(key, (long) a.tickCount);
                        PaTalk.comment(a, 0, PaTalk.line(a, "mem.helping_friend", "who", o.getGameProfile().getName()), true);
                    }
                    break;
                }
            }
            if (o.distanceToSqr(a) < 10 * 10 && a.tickCount - w.friendSeen.getOrDefault(key, -100000L) > 36000 && r.mute == 0) {
                w.friendSeen.put(key, (long) a.tickCount);
                String line = PaTalk.line(a, "mem.banter.friend_near", "who", o.getGameProfile().getName(), "owner", a.ownerName());
                PaTalk.comment(a, 1, line, true);                         // a hello to a friend is said aloud: it's for them too (their real name, never a nickname)
            }
        }
    }

    /** "Always keep 32 torches": low, free, and able to make more, it makes more; says so once if it can't. */
    private static void stock(ServerPlayer owner, PlayerAssistantEntity a, PaData.Rec r, Watch w) {
        if (a.tickCount - w.stockAt < 600 || !a.plan.idle() || a.plan.paused || a.getTarget() != null) return;
        for (Entry e : r.memory.of(Kind.STOCK)) {
            int have = PaItems.count(a, e.subject.matches("torch|torches") ? "torches" : e.subject);
            if (have >= e.n) continue;
            w.stockAt = a.tickCount;
            String target = com.jrpetty.mcassistant.entity.CraftPlanner.matchTarget(e.subject);
            if (target == null) continue;
            com.jrpetty.mcassistant.entity.CraftPlanner.Result cr = com.jrpetty.mcassistant.entity.CraftPlanner.plan(a, target, e.n - have);
            if (!cr.blockers().isEmpty() || cr.jobs().isEmpty()) continue;
            PaPlan.Step s = PaOrders.step("make " + (e.n - have) + " " + e.subject, PaIntent.Kind.CRAFT, 30);
            s.jobs.addAll(cr.jobs());
            s.auto = true;
            a.plan.steps.add(s);
            if (r.mute < 2) PaTalk.comment(a, 1, "Running low on " + e.subject + ": making " + (e.n - have) + " more, as agreed.", true);
            return;
        }
    }

    /** A creature its player hates, near them: it deals with it first, and (now and then) says it remembers. */
    private static void hatedNear(ServerPlayer owner, PlayerAssistantEntity a, PaData.Rec r, Watch w) {
        Cache c = cache(r);
        if (c.hateMobs.isEmpty()) return;
        for (Monster m : a.level().getEntitiesOfClass(Monster.class, owner.getBoundingBox().inflate(14))) {
            if (!m.isAlive() || !hated(a, m)) continue;
            if (a.getTarget() == null && !a.mind.vetoed.contains("fight") && (r.autoHelp || a.getMode() == com.jrpetty.mcassistant.entity.AssistantEntity.Mode.GUARD)
                    && !(m instanceof net.minecraft.world.entity.monster.Creeper && !a.canSnipeCreepers())) {
                a.equipBestWeapon();
                a.setTarget(m);
            }
            if (a.tickCount - w.hatedAt > 12000 && r.mute == 0) {
                w.hatedAt = a.tickCount;
                PaTalk.reply(a, PaTalk.line(a, "mem.banter.hated_mob", "what", m.getName().getString().toLowerCase(Locale.ROOT)), null, true);
            }
            return;
        }
    }

    /** A job it was teased (or praised) about, done: it brings it up, now and then. */
    public static void jobDone(PlayerAssistantEntity a, String kind, String label) {
        PaData.Rec r = a.rec();
        if (r == null || r.mute > 0) return;
        String topic = switch (kind) {
            case "MINE_ORE", "MINE_DOWN" -> "mining";
            case "BUILD" -> "building";
            case "FARM" -> "farming";
            case "SMELT" -> "cooking";
            case "FIGHT", "HUNT" -> "fighting";
            case "SHOOT_AT" -> "archery";
            case "GATHER" -> label.contains("log") ? "chopping wood" : label.contains("stone") || label.contains("ore") ? "mining" : "";
            default -> "";
        };
        if (topic.isEmpty()) return;
        for (Entry e : r.memory.of(Kind.SELF)) {
            if (!e.subject.equals(topic) && !(topic.equals("chopping wood") && e.subject.equals("chopping"))) continue;
            Watch w = watch(a);
            if (a.tickCount - w.selfAt.getOrDefault(topic, -100000L) < 18000 || a.getRandom().nextInt(100) >= 60) return;
            w.selfAt.put(topic, (long) a.tickCount);
            PaTalk.comment(a, 1, PaTalk.line(a, e.n < 0 ? "mem.banter.self_proved" : "mem.banter.self_praised", "what", topic), true);
            return;
        }
    }

    /** Its player ate something: their favourite, and it may say it knows. */
    public static void ownerAte(ServerPlayer p, ItemStack food) {
        PlayerAssistantEntity a = PaLife.find(p);
        if (a == null || a.distanceToSqr(p) > 24 * 24) return;
        PaData.Rec r = a.rec();
        if (r == null || r.mute > 0 || r.chatty < 1) return;
        Item fav = favouriteFood(a);
        if (fav == null || !food.is(fav)) return;
        Watch w = watch(a);
        if (a.tickCount - w.banterAt < 24000 || a.getRandom().nextInt(100) >= 50) return;
        w.banterAt = a.tickCount;
        String said = r.memory.favourite("food");
        PaTalk.reply(a, PaTalk.line(a, "mem.banter.fav_food", "what", said == null ? food.getHoverName().getString().toLowerCase(Locale.ROOT) : said), null, true);
    }

    /** Handing over food: its player's favourite (and, now and then, a word that it remembered). */
    public static void gaveFood(PlayerAssistantEntity a) {
        PaData.Rec r = a.rec();
        if (r == null || r.mute > 0) return;
        Item fav = favouriteFood(a);
        if (fav == null || a.countMatching(s -> s.is(fav)) == 0 || a.getRandom().nextInt(100) >= 40) return;
        String said = r.memory.favourite("food");
        PaTalk.comment(a, 1, PaTalk.line(a, "mem.banter.fav_give", "what", said == null ? new ItemStack(fav).getHoverName().getString().toLowerCase(Locale.ROOT) : said), true);
    }

    // ------------------------------------------------------------------ reminders (the server, about once a second, every record)

    /** Every record's reminders: due ones said by its assistant (or, with none about, as a toast and a note in its log). */
    public static void serverTick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        long dayTime = server.overworld().getDayTime(), time = dayTime % 24000L;
        for (PaData.Rec r : PaData.all()) {
            if (r.memory.size() == 0) continue;
            ServerPlayer owner = server.getPlayerList().getPlayer(r.owner);
            if (owner == null) continue;
            PlayerAssistantEntity a = PaLife.entity(server, r);
            for (Entry e : new ArrayList<>(r.memory.entries)) {
                if (e.kind != Kind.REMINDER) continue;
                boolean due = switch (e.when) {
                    case TIME -> e.due >= 0 && now >= e.due;
                    case MORNING -> time < 1000 && now - e.made > 1200;
                    case NIGHT -> time >= 12500 && time < 13500 && now - e.made > 1200;
                    case HOME -> {
                        BlockPos home = a == null ? null : PaOrders.homeOf(a);
                        if (home == null) for (String k : new String[]{"base", "home", "house"}) if (home == null) home = r.waypoints.get(k);
                        yield home != null && owner.blockPosition().distSqr(home) < 12 * 12 && now - e.made > 400 && e.n != -2;
                    }
                    default -> false;
                };
                if (e.when == When.HOME && !due) {
                    // armed only once its player has been away from home (else "when I get home" said at home fires at once)
                    BlockPos home = a == null ? null : PaOrders.homeOf(a);
                    if (home == null) for (String k : new String[]{"base", "home", "house"}) if (home == null) home = r.waypoints.get(k);
                    if (home != null && owner.blockPosition().distSqr(home) > 24 * 24 && e.n == -1) { e.n = 0; }
                    continue;
                }
                if (e.when == When.HOME && e.n == -1) continue;              // not yet away from home
                if (!due) continue;
                fire(owner, a, r, e);
                r.memory.remove(e.id);
                changed(r.memory);
                PaData.touch();
            }
        }
    }

    /** A reminder goes off: said aloud to its player (from its assistant, if about), and a toast. */
    static void fire(ServerPlayer owner, @Nullable PlayerAssistantEntity a, PaData.Rec r, Entry e) {
        String line;
        if (a != null) {
            line = e.value.isEmpty() ? PaTalk.line(a, "mem.remind_nudge") : PaTalk.line(a, "mem.remind", "what", e.value);
            PaTalk.reply(a, line, null, true);
        } else {
            line = e.value.isEmpty() ? "Your reminder: it's time." : "Reminder: " + e.value + ".";
            r.say(1, line, owner.serverLevel().getGameTime());
        }
        CompoundTag t = new CompoundTag();
        t.putString("title", (r.name.isEmpty() ? "Your assistant" : PaNet.cap(r.name)) + ": reminder");
        t.putString("text", e.value.isEmpty() ? "It's time." : PaMemory.cap(e.value));
        PaNet.send(owner, PaPayload.TOAST, t);
        com.mojang.logging.LogUtils.getLogger().info("[pa35] reminder for {}: {}", owner.getGameProfile().getName(), e.value);
    }

    /** Game tests: forget the per-assistant watch (its morning and night, who was near). */
    public static void resetForTests() {
        WATCH.clear();
        lastPlace.clear();
    }
}
