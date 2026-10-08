package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [war-scouting] The war map: what a town knows of its rivals, laid out. Every town it is at war with or at
 * odds with: where it lies, the last report on it and how old that is, what the leader believes of it now
 * (the fog of war) and how the two stand (the strength reckoning); where folk of theirs were last seen on
 * our approaches; our pickets and our spies out; the captives held each way. As a page ({@code /village war
 * map}), as a tab of the town's books (the War map, drawn by the client's WarMapPage), on the board and in
 * the scouts' talk.
 */
public final class WarMap {

    private WarMap() {}

    /** Today, by the world's clock (for the board, which is composed without one to hand). */
    static long today() {
        MinecraftServer s = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return s == null ? 0L : s.overworld().getDayTime() / 24000L;
    }

    /** How this town stands toward that one, in a word: "at war since day 12", "a feud". */
    static String standing(UUID us, UUID them) {
        long since = Wars.since(us, them);
        if (since >= 0) return "at war since day " + since;
        return "a feud (relations " + Ledger.relation(us, them) + ")";
    }

    /** "230 blocks north-east". */
    static String where(BlockPos from, BlockPos to) {
        return (int) Math.sqrt(Scouts.flat(from, to)) / 10 * 10 + " blocks " + Guide.direction(from, to);
    }

    // ------------------------------------------------------------------ the page

    /** /village war map: the whole of it, a line a thing. */
    public static String page(ServerLevel level, Villages.Village v) {
        return String.join("\n", lines(level, v));
    }

    static List<String> lines(ServerLevel level, Villages.Village v) {
        UUID us = v.id();
        long today = level.getDayTime() / 24000L;
        List<String> out = new ArrayList<>();
        Wars.Footing footing = Wars.footing(us);
        List<UUID> rivals = Spying.rivals(us);
        out.add(Villages.name(us) + ": " + switch (footing) {
            case WAR -> "at war";
            case TENSION -> "on its guard";
            default -> "at peace";
        } + ". The leader is " + Intel.temper(us).words + ".");
        Intel.Report mine = Intel.exact(level, us, today);
        out.add("Our strength (counted): " + Intel.summary(mine) + (Intel.allies(us) > 0 ? ", " + Intel.allies(us) + " allied towns" : "") + ".");
        if (!rivals.isEmpty()) {
            out.add("Guards we want against our rivals as we believe them: " + Intel.guardsNeeded(level, us, today)
                + " (we have " + mine.guards() + ").");
        }
        if (rivals.isEmpty()) out.add("No town is at war or at odds with us.");
        for (UUID them : rivals) {
            Villages.Village o = Villages.get(them);
            String name = Villages.name(them);
            out.add("");
            out.add(name + (o == null ? "" : ", " + where(v.centre(), o.centre())) + " — " + standing(us, them));
            Intel.Report r = Intel.latest(us, them);
            if (r == null) {
                out.add("  No report: nobody has been to look. Rumour has it at " + Intel.summary(Intel.rumour(them, today)) + ".");
            } else {
                out.add("  Report (" + Intel.ageWords(r, today) + ", day " + r.day() + "): " + Intel.summary(r) + "."
                    + (r.note().isEmpty() ? "" : " \"" + r.note() + "\""));
            }
            Intel.Reckoning k = Intel.strength(level, us, them);
            Intel.Strength t = k.theirs();
            out.add("  The leader believes: " + t.guards() + " guards, " + t.armoured() + " in iron, " + t.archers() + " with bows"
                + " (from " + t.basis() + ", " + Math.round(t.confidence() * 100) + "% sure).");
            out.add("  Reckoning: " + k.words() + ". Balance " + String.format(java.util.Locale.ROOT, "%.2f", k.balance())
                + (k.weaker() ? " — we are the weaker" : k.stronger() ? " — we are the stronger" : "") + ".");
            Intel.Sighting s = Intel.lastSighting(us, them);
            if (s != null) {
                out.add("  Last seen on our approaches: " + s.what() + (s.size() > 1 ? " (" + s.size() + ")" : "") + ", " + where(v.centre(), s.at())
                    + ", day " + s.day() + ", by " + s.by() + ".");
            }
            List<String> held = new ArrayList<>();
            for (Spies.Captive c : Spies.captives(us)) if (c.home().equals(them)) held.add(c.name() + " (since day " + c.day() + ")");
            for (Spies.Captive c : Spies.captives(them)) if (c.home().equals(us)) held.add("they hold our " + c.name());
            if (!held.isEmpty()) out.add("  Captives: " + String.join("; ", held) + ".");
        }
        List<String> spies = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(us)) {
            if (a instanceof VillageFolkEntity f && Spying.missionOf(f) != null && !Spying.missionOf(f).freed) {
                Spying.Mission m = Spying.missionOf(f);
                spies.add(f.displayNameCap() + " " + phaseWords(m));
            }
        }
        if (!spies.isEmpty()) out.add("Out watching: " + String.join("; ", spies) + ".");
        List<String> pickets = new ArrayList<>();
        for (VillageFolkEntity f : Pickets.of(us)) {
            Pickets.Post p = Pickets.postOf(f);
            if (p != null) pickets.add(f.displayNameCap() + " on " + p.road() + (Pickets.runningForTests(f) ? " (running home with word)" : ""));
        }
        if (!pickets.isEmpty()) out.add("Pickets: " + String.join("; ", pickets) + ".");
        return out;
    }

    static String phaseWords(Spying.Mission m) {
        return switch (m.phase()) {
            case GOING -> "on the way to " + m.name();
            case WATCHING -> "watching " + m.name() + " from " + m.from;
            case SLIPPING -> "slipping into " + m.name();
            case FLED -> "running from " + m.name() + "'s watch";
            default -> "on the way home from " + m.name();
        };
    }

    // ------------------------------------------------------------------ the board and talk

    /** For the board: each rival's last report in a few words, with its age; null at peace with nobody at odds. */
    @Nullable
    public static String boardLine(UUID us) {
        List<UUID> rivals = Spying.rivals(us);
        if (rivals.isEmpty()) return null;
        long today = today();
        List<String> bits = new ArrayList<>();
        for (UUID them : rivals) {
            if (bits.size() >= 3) break;
            Intel.Report r = Intel.latest(us, them);
            String name = Villages.name(them) + (Wars.atWar(us, them) ? " (at war)" : " (feud)");
            bits.add(r == null ? name + ": nobody has looked yet"
                : name + ": " + r.guards() + (r.guards() == 1 ? " guard" : " guards") + ", "
                    + (r.walls() > 0 ? "a wall and " + r.gates() + (r.gates() == 1 ? " gate" : " gates") : "no wall") + ", "
                    + r.foodDays() + " days' food — report " + Intel.ageWords(r, today));
        }
        return "The enemy: " + String.join("; ", bits) + ".";
    }

    /** What a folk says of the enemy, asked what the scouts know: the latest report, its age, and what the elder makes of it. */
    @Nullable
    public static String talk(UUID us, long today) {
        List<UUID> rivals = Spying.rivals(us);
        if (rivals.isEmpty()) return null;
        UUID them = rivals.get(0);
        String name = Villages.name(them);
        Intel.Report r = Intel.latest(us, them);
        String of = Wars.atWar(us, them) ? "Of " + name + ", the enemy: " : "Of " + name + ", that we're at odds with: ";
        if (r == null) return of + "nobody's been to look yet. Rumour says " + Intel.summary(Intel.rumour(them, today)) + ".";
        Intel.Reckoning k = Intel.strength(us, them, today);
        return of + "the report's " + Intel.ageWords(r, today) + " — " + Intel.summary(r) + ". The elder reckons "
            + (k.weaker() ? "they're the stronger." : k.stronger() ? "we're the stronger." : "we're evenly matched.");
    }

    // ------------------------------------------------------------------ for the town's books (CityScreen's War map)

    /** The war map as the town's books carry it to the client (Annals). */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID us = v.id();
        long today = level.getDayTime() / 24000L;
        BlockPos c = v.centre();
        CompoundTag out = new CompoundTag();
        out.putString("footing", Wars.footing(us).name());
        out.putString("temper", Intel.temper(us).words);
        Intel.Report mine = Intel.exact(level, us, today);
        CompoundTag ours = new CompoundTag();
        ours.putInt("guards", mine.guards());
        ours.putInt("armoured", mine.armoured());
        ours.putInt("archers", mine.archers());
        ours.putInt("walls", mine.walls());
        ours.putInt("gates", mine.gates());
        ours.putInt("food", mine.foodDays());
        ours.putInt("folk", mine.folk());
        ours.putInt("allies", Intel.allies(us));
        out.put("ours", ours);
        List<UUID> rivals = Spying.rivals(us);
        out.putInt("wanted", rivals.isEmpty() ? 0 : Intel.guardsNeeded(level, us, today));
        ListTag rl = new ListTag();
        ListTag sl = new ListTag();
        for (UUID them : rivals) {
            Villages.Village o = Villages.get(them);
            CompoundTag t = new CompoundTag();
            t.putString("name", Villages.name(them));
            t.putBoolean("war", Wars.atWar(us, them));
            t.putLong("since", Wars.since(us, them));
            t.putString("standing", standing(us, them));
            if (o != null) {
                t.putInt("dx", o.centre().getX() - c.getX());
                t.putInt("dz", o.centre().getZ() - c.getZ());
            }
            Intel.Report r = Intel.latest(us, them);
            t.putBoolean("report", r != null);
            if (r != null) {
                t.putLong("age", Intel.age(r, today));
                t.putBoolean("stale", Intel.stale(r, today));
                t.putString("age_words", Intel.ageWords(r, today));
                t.putInt("r_guards", r.guards());
                t.putInt("r_armoured", r.armoured());
                t.putInt("r_archers", r.archers());
                t.putInt("r_walls", r.walls());
                t.putInt("r_gates", r.gates());
                t.putInt("r_food", r.foodDays());
                t.putInt("r_folk", r.folk());
                t.putString("note", r.note());
            }
            Intel.Reckoning k = Intel.strength(level, us, them);
            t.putInt("e_guards", k.theirs().guards());
            t.putInt("e_armoured", k.theirs().armoured());
            t.putInt("e_archers", k.theirs().archers());
            t.putString("basis", k.theirs().basis());
            t.putInt("sure", (int) Math.round(k.sure() * 100));
            t.putInt("balance", (int) Math.round(k.balance() * 100));
            t.putString("words", k.words());
            t.putInt("wanted", Intel.guardsNeeded(level, us, them, today));
            Intel.Sighting s = Intel.lastSighting(us, them);
            if (s != null) {
                CompoundTag st = new CompoundTag();
                st.putString("name", Villages.name(them));
                st.putInt("dx", s.at().getX() - c.getX());
                st.putInt("dz", s.at().getZ() - c.getZ());
                st.putInt("size", s.size());
                st.putString("what", s.what());
                st.putLong("age", Math.max(0, today - s.day()));
                st.putString("by", s.by());
                sl.add(st);
            }
            rl.add(t);
        }
        out.put("rivals", rl);
        out.put("seen", sl);
        ListTag pl = new ListTag();
        for (VillageFolkEntity f : Pickets.of(us)) {
            Pickets.Post p = Pickets.postOf(f);
            if (p == null) continue;
            CompoundTag t = new CompoundTag();
            t.putString("name", f.displayNameCap());
            t.putString("road", p.road());
            t.putInt("dx", p.at().getX() - c.getX());
            t.putInt("dz", p.at().getZ() - c.getZ());
            t.putBoolean("running", Pickets.runningForTests(f));
            pl.add(t);
        }
        out.put("pickets", pl);
        ListTag spies = new ListTag();
        for (AssistantEntity a : Villages.folkOf(us)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            Spying.Mission m = Spying.missionOf(f);
            if (m == null || m.freed) continue;
            CompoundTag t = new CompoundTag();
            t.putString("name", f.displayNameCap());
            t.putString("doing", phaseWords(m));
            t.putInt("dx", f.getBlockX() - c.getX());
            t.putInt("dz", f.getBlockZ() - c.getZ());
            spies.add(t);
        }
        out.put("spies", spies);
        ListTag caps = new ListTag();
        for (Spies.Captive k : Spies.captives(us)) caps.add(StringTag.valueOf("We hold " + k.name() + " of " + Villages.name(k.home()) + ", since day " + k.day()));
        for (Spies.Captive k : Spies.ofOurs(us)) caps.add(StringTag.valueOf(Villages.name(k.holder()) + " holds our " + k.name() + ", since day " + k.day()));
        out.put("captives", caps);
        return out;
    }
}
