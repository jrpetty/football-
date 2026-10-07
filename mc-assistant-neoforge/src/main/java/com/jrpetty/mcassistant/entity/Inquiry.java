package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Crime.Case;
import com.jrpetty.mcassistant.entity.Crime.Clue;
import com.jrpetty.mcassistant.entity.Crime.Near;
import com.jrpetty.mcassistant.entity.Crime.Stage;
import com.jrpetty.mcassistant.entity.Crime.Statement;
import com.jrpetty.mcassistant.entity.Crime.Suspect;
import com.jrpetty.mcassistant.entity.Crime.Witness;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [crime] The watch's detective work (Crime).
 *
 * <p>A reported case goes to the town's constable (from the Iron Age, the best of its watch, who takes every case),
 * or else to whichever guard is free, or with no watch at all to the leader. By day, between its other duties, it:
 * <ol>
 * <li><b>goes to the scene</b> and reads it: the broken window, the hour, the footprints (to whose door they lead,
 *     unless the rain has had them), and whatever was dropped (a keepsake has its owner's mark on it; a hoe is a
 *     farmer's);</li>
 * <li><b>asks questions</b>: the victim, then whoever was about, nearest first, walking up to each. Each answers by
 *     what it saw and by its nature: an honest witness tells it as it saw it (named, if it saw it plain; dressed so,
 *     if it did not; and now and then the wrong one of the same trade, honestly mistaken); one who loves the culprit
 *     saw nothing; a dishonest one with a grudge, who saw too little to be sure, names its rival; the culprit says
 *     it was elsewhere, or (an honest one, sometimes) owns up;</li>
 * <li><b>narrows the suspects</b>, weighing it all (a known rival's word counts for less; a lie found out, for more),
 *     and <b>searches</b> the likeliest: purses fuller than their wages, packs and home chests (the stolen thing, a
 *     forged coin);</li>
 * <li>and <b>names the one the evidence points to</b> if it points plainly enough, or gives the case up. A hasty
 *     guard (a green one, or a grump) names on less, and may name the wrong one: the council will clear it, and the
 *     watch looks again.</li>
 * </ol>
 * Every step goes into the case file: the detective's notes, the clues, the statements and the suspects.
 */
final class Inquiry {

    private Inquiry() {}

    /** The evidence the watch wants against one before it names it, and how far ahead of the next. */
    static final double ACCUSE = 5.0, AHEAD = 1.5;
    /** A hasty guard's. */
    static final double HASTY = 3.0;
    /** A case nobody can get any further with is given up after this many days. */
    static final int STALE = 3;

    /** Where an investigator has got to in its case. */
    static final class Work {
        final int caseId;
        long stepAt = -1, legStart = -1;
        int next;
        final List<UUID> ask = new ArrayList<>();
        final List<UUID> search = new ArrayList<>();
        boolean listed;

        Work(int caseId) { this.caseId = caseId; }
    }

    private static final Map<UUID, Work> WORK = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> PATHED = new ConcurrentHashMap<>();

    static void resetForTests() {
        WORK.clear();
        PATHED.clear();
    }

    // ------------------------------------------------------------------ who looks into it

    /** The town's constable: from the Iron Age, the most seasoned of its watch. */
    @Nullable
    static VillageFolkEntity constable(UUID village) {
        if (Villages.ageOf(village).ordinal() < Villages.Age.IRON.ordinal()) return null;
        // [interviews] The one the panel chose for constable, while it is on the watch.
        VillageFolkEntity chosen = Interviews.holder(village, "constable");
        if (chosen != null && chosen.stationTask() == StationTask.GUARD) return chosen;
        VillageFolkEntity best = null;
        for (VillageFolkEntity g : Patrols.watch(village)) {
            if (best == null || g.veteranLevel() > best.veteranLevel()) best = g;
        }
        return best;
    }

    static boolean isConstable(VillageFolkEntity f) {
        return f.ownerId() != null && f.stationTask() == StationTask.GUARD && f == constable(f.ownerId());
    }

    /** The case this folk is looking into, or null. */
    @Nullable
    static Case caseOf(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null) return null;
        for (Case c : Crime.open(village)) {
            if (c.stage.investigating() && f.getUUID().equals(c.investigator)) return c;
        }
        return null;
    }

    /** The watch, or the leader: the only folk who look into a case. */
    static boolean mayInvestigate(VillageFolkEntity f) {
        return f.stationTask() == StationTask.GUARD || f.ownerId() != null && f.getUUID().equals(Villages.elder(f.ownerId()));
    }

    /** Free to take a case: grown, about the town, not walking with the leader, not after a monster, not on the wall. */
    static boolean fit(VillageFolkEntity g) {
        return g.isAlive() && !g.isBaby() && !Patrols.away(g) && !Patrols.escorting(g) && !WatchClears.hunting(g) && !g.onWatch();
    }

    /** The round (Crime.tick): reported cases given to the watch, cases whose investigator is gone handed on, stale ones given up. */
    static void tick(ServerLevel level, Villages.Village v) {
        long now = level.getDayTime(), day = now / 24000L, t = now % 24000L;
        boolean byDay = Crime.hurry || t >= 1000L && t < 12000L;
        for (Case c : Crime.open(v.id())) {
            if (c.stage == Stage.REPORTED || c.stage.investigating()) {
                if (c.progressDay >= 0 && day - c.progressDay > STALE) {
                    giveUp(level, v, c, null, "Nobody could get any further with it");
                    continue;
                }
            }
            if (c.stage == Stage.REPORTED && byDay) {
                assign(level, v, c);
            } else if (c.stage.investigating() && c.investigator != null) {
                Entity e = level.getEntity(c.investigator);
                boolean gone = e == null ? !stillOf(v.id(), c.investigator) : !(e instanceof VillageFolkEntity g) || !g.isAlive()
                    || !v.id().equals(g.ownerId()) || !mayInvestigate(g);
                if (gone) {
                    c.note(day, c.investigatorName + " is no longer about to see it through; the case goes back to the watch.");
                    WORK.remove(c.investigator);
                    c.investigator = null;
                    c.investigatorName = "";
                    c.stage = Stage.REPORTED;
                    Crime.changed();
                }
            }
        }
    }

    /** Is this folk still one of the town's (perhaps only out of loaded ground)? */
    private static boolean stillOf(UUID village, UUID folk) {
        for (AssistantEntity a : Villages.folkOf(village)) if (a.getUUID().equals(folk)) return true;
        return false;
    }

    /** The case given to the constable, or the free guard nearest the scene, or (no watch) the leader. */
    static boolean assign(ServerLevel level, Villages.Village v, Case c) {
        VillageFolkEntity g = constable(v.id());
        if (g != null && (!fit(g) || caseOf(g) != null || g.getUUID().equals(c.victim))) g = null;
        if (g == null) {
            double best = Double.MAX_VALUE;
            for (VillageFolkEntity o : Patrols.watch(v.id())) {
                if (!fit(o) || caseOf(o) != null || o.getUUID().equals(c.victim)) continue;
                double d = o.blockPosition().distSqr(c.where);
                if (d < best) { best = d; g = o; }
            }
        }
        if (g == null && Patrols.watch(v.id()).isEmpty()) {
            UUID elder = Villages.elder(v.id());
            VillageFolkEntity e = elder == null ? null : Civics.find(level, elder);
            if (e != null && !elder.equals(c.victim) && !elder.equals(c.culprit) && caseOf(e) == null) g = e;
        }
        if (g == null) return false;
        long day = level.getDayTime() / 24000L;
        c.investigator = g.getUUID();
        c.investigatorName = g.displayNameCap();
        c.stage = Stage.SCENE;
        c.progressDay = day;
        c.note(day, g.displayNameCap() + (isConstable(g) ? ", the constable," : g.stationTask() == StationTask.GUARD ? " of the watch" : ", the leader,")
            + " took the case.");
        WORK.put(g.getUUID(), new Work(c.id));
        FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Leave it with me. I'll get to the bottom of it.", "Right. Let's see what happened at " + c.place + ".",
            "Another one? I'm on it."));
        Crime.changed();
        return true;
    }

    // ------------------------------------------------------------------ the investigator's part

    /** From Crime.hold: the investigator about its case, by day. What it is doing, or null. */
    @Nullable
    static String hold(VillageFolkEntity f, ServerLevel level) {
        if (!mayInvestigate(f)) return null;               // (only the watch and the leader ever have a case: a cheap look first)
        Case c = caseOf(f);
        if (c == null) {
            WORK.remove(f.getUUID());
            return null;
        }
        long t = level.getDayTime() % 24000L;
        if (!Crime.hurry && (t < 1000L || t >= 12500L)) return null;
        if (f.isSleeping() || Raids.underAlarm(f.ownerId()) || WatchClears.hunting(f) || f.getTarget() != null && f.getTarget().isAlive()) return null;
        Villages.Village v = Villages.get(c.village);
        if (v == null) return null;
        Crime.takeOver(f);
        Work w = WORK.get(f.getUUID());
        if (w == null || w.caseId != c.id) {
            w = new Work(c.id);
            WORK.put(f.getUUID(), w);
        }
        long gt = level.getGameTime();
        return switch (c.stage) {
            case SCENE -> scene(level, v, f, c, w, gt);
            case QUESTIONING -> question(level, v, f, c, w, gt);
            case SEARCHING -> search(level, v, f, c, w, gt);
            default -> null;
        };
    }

    private static String scene(ServerLevel level, Villages.Village v, VillageFolkEntity f, Case c, Work w, long gt) {
        if (w.stepAt < 0 && !Mischief.near(f, c.where, 2.5)) {
            if (w.legStart < 0) w.legStart = gt;
            if (gt - w.legStart < 900) {
                walk(f, c.where, 0.8D);
                return "on the way to the scene at " + c.place;
            }
        }
        long day = level.getDayTime() / 24000L;
        if (w.stepAt < 0) {
            w.stepAt = gt;
            f.getNavigation().stop();
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Hmm. Let's see what happened here.", "Nobody touch anything.",
                "So this is where it was done."));
            examine(level, v, c, f, day);
        }
        f.getLookControl().setLookAt(c.where.getX() + 0.5, c.where.getY(), c.where.getZ() + 0.5);
        if (gt - w.stepAt < Crime.pause(80)) return "looking over the scene at " + c.place;
        c.stage = Stage.QUESTIONING;
        c.progressDay = day;
        w.stepAt = -1;
        w.legStart = -1;
        w.next = 0;
        w.listed = false;
        Crime.changed();
        return "looking over the scene at " + c.place;
    }

    /** What the scene tells: the damage, the hour, the footprints, what was dropped. */
    static void examine(ServerLevel level, Villages.Village v, Case c, VillageFolkEntity f, long day) {
        if (c.kind == Crime.Kind.VANDALISM) c.note(day, "At the scene: " + c.brokeWhat + " broken at " + c.place + ".");
        else c.note(day, "At the scene: " + c.what() + ".");
        c.note(day, "It was done " + Crime.hour(c.hour) + " on day " + c.day + "; " + c.near.size() + (c.near.size() == 1 ? " was" : " were") + " about.");
        // What was dropped.
        if (c.droppedEntity != null && !c.droppedFound) {
            Entity e = level.getEntity(c.droppedEntity);
            if (e instanceof ItemEntity item && item.isAlive() && item.distanceToSqr(c.where.getX() + 0.5, c.where.getY(), c.where.getZ() + 0.5) < 10 * 10) {
                c.evidence = (CompoundTagHolder.save(level, item.getItem()));
                item.discard();
                dropped(level, v, c, null);
            } else {
                c.note(day, "If anything was dropped at the scene, somebody has taken it up since.");
            }
        }
        // The footprints.
        if (!c.trailRead && !c.trail.isEmpty()) {
            if (c.trailWashed) {
                c.note(day, "The rain had washed out any footprints.");
            } else {
                c.trailRead = true;
                footprints(level, v, c, day);
            }
        }
        Crime.changed();
    }

    /** The dropped thing read: whose mark is on it, or whose trade it belongs to. */
    static void dropped(ServerLevel level, Villages.Village v, Case c, @Nullable Player by) {
        long day = level.getDayTime() / 24000L;
        c.droppedFound = true;
        String found = (by == null ? "" : "Brought in by " + by.getName().getString() + ": ") + Mischief.capital(c.dropped) + " dropped at " + c.place;
        if (c.droppedOwner != null) {
            String owner = Mischief.nameOf(level, v.id(), c.droppedOwner);
            c.clues.add(new Clue("dropped", found + ", with " + owner + "'s mark on it.", 3.0, day).at(c.droppedOwner, owner));
            c.note(day, found + ": a keepsake, " + owner + "'s.");
        } else if (!c.droppedTrade.isEmpty()) {
            StationTask t;
            try { t = StationTask.valueOf(c.droppedTrade); } catch (IllegalArgumentException e) { t = StationTask.NONE; }
            List<Near> of = new ArrayList<>();
            for (Near n : c.near) if (n.trade().equals(c.droppedTrade)) of.add(n);
            String trade = t.title.toLowerCase(Locale.ROOT);
            Clue k = new Clue("dropped", found + ": a " + trade + "'s tool.", 2.5 / Math.max(1, of.size()), day);
            for (Near n : of) k.at(n.id(), n.name());
            c.clues.add(k);
            c.note(day, found + ": a " + trade + "'s." + (of.isEmpty() ? " No " + trade + " was about, that anybody knows." : ""));
        } else {
            c.clues.add(new Clue("dropped", found + ": it could be anybody's.", 0.0, day));
            c.note(day, found + ". Nobody's in particular.");
        }
        Crime.changed();
    }

    /** The footprints followed: to whose door they lead, or where they give out. */
    private static void footprints(ServerLevel level, Villages.Village v, Case c, long day) {
        BlockPos end = c.trail.get(c.trail.size() - 1);
        Homes.Home h = Homes.homeAt(v.id(), end);
        if (h == null) {
            // Near a door, if not at it.
            double best = 7.0 * 7.0;
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                Homes.Home o = Homes.homeOf(v.id(), a.getUUID());
                if (o == null) continue;
                double d = o.anchor.distSqr(end);
                if (d < best) { best = d; h = o; }
            }
        }
        if (h == null || h.members.isEmpty() || c.trail.size() < 2) {
            int dx = end.getX() - c.where.getX(), dz = end.getZ() - c.where.getZ();
            String dir = dx * dx + dz * dz < 4 ? "round about" : "off " + Mischief.compass(dx, dz);
            c.clues.add(new Clue("footprints", "Footprints from the scene, going " + dir + "; lost in the street.", 0.0, day));
            c.note(day, "Followed footprints " + dir + " from the scene, till they were lost in the street.");
            return;
        }
        List<UUID> grown = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (UUID m : h.members) {
            Entity e = level.getEntity(m);
            if (e instanceof VillageFolkEntity o && o.isBaby()) continue;
            grown.add(m);
            names.add(Mischief.nameOf(level, v.id(), m));
        }
        if (grown.isEmpty()) return;
        Clue k = new Clue("footprints", "Muddy footprints from the scene to " + Civics.names(names) + "'s door.", 2.0 / grown.size() + (grown.size() == 1 ? 0.5 : 0), day);
        for (int i = 0; i < grown.size(); i++) k.at(grown.get(i), names.get(i));
        c.clues.add(k);
        c.note(day, "Followed muddy footprints from the scene to the door of " + Civics.names(names) + ".");
    }

    /** Whom to ask: the victim, then the witnesses and whoever was about, nearest first. */
    private static List<UUID> toAsk(Case c, VillageFolkEntity f) {
        List<UUID> out = new ArrayList<>();
        int most = isConstable(f) ? 8 : 5;
        if (c.victim != null && !c.victim.equals(f.getUUID())) out.add(c.victim);
        List<Near> about = new ArrayList<>(c.near);
        about.sort(Comparator.comparingInt(Near::dist));
        for (Near n : about) {
            if (out.size() >= most + 1) break;
            if (n.id().equals(f.getUUID()) || out.contains(n.id())) continue;
            out.add(n.id());
        }
        return out;
    }

    private static String question(ServerLevel level, Villages.Village v, VillageFolkEntity f, Case c, Work w, long gt) {
        long day = level.getDayTime() / 24000L;
        if (!w.listed) {
            w.ask.clear();
            w.ask.addAll(toAsk(c, f));
            w.next = 0;
            w.listed = true;
        }
        while (w.next < w.ask.size() && c.statementFrom(w.ask.get(w.next)) != null && w.stepAt < 0) w.next++;
        if (w.next >= w.ask.size()) {
            c.stage = Stage.SEARCHING;
            c.progressDay = day;
            w.stepAt = -1;
            w.legStart = -1;
            w.search.clear();
            w.listed = false;
            c.note(day, "Asked everybody who was about. " + suspectsWords(c) + ".");
            Crime.changed();
            return "going over what it has heard";
        }
        UUID who = w.ask.get(w.next);
        VillageFolkEntity o = Civics.find(level, who);
        String name = o == null ? Mischief.nameOf(level, v.id(), who) : o.displayNameCap();
        if (o == null || o.isSleeping() || !v.id().equals(o.ownerId())) {
            c.note(day, "Could not find " + name + " to ask.");
            w.next++;
            w.legStart = -1;
            return "looking for " + name;
        }
        if (w.stepAt < 0 && !Mischief.near(f, o.blockPosition(), 2.6)) {
            if (w.legStart < 0) w.legStart = gt;
            if (gt - w.legStart > 600) {
                c.note(day, "Could not catch up with " + name + " to ask.");
                w.next++;
                w.legStart = -1;
                return "looking for " + name;
            }
            walk(f, o.blockPosition(), 0.85D);
            return "on its way to ask " + name + " some questions";
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(o, 30.0F, 30.0F);
        o.getLookControl().setLookAt(f, 30.0F, 30.0F);
        if (w.stepAt < 0) {
            w.stepAt = gt;
            FolkTalk.speak(f, who.equals(c.victim) ? FolkTalk.pick(f.getRandom(), "Tell me what was taken, " + name + ", and when you missed it.",
                    "When did you last see it, " + name + "?")
                : FolkTalk.pick(f.getRandom(), "Where were you " + Crime.hour(c.hour) + " on day " + c.day + ", " + name + "? See anything at " + c.place + "?",
                    name + ", a word. Were you about " + c.place + " when it happened?", "Did you see anybody at " + c.place + ", " + name + "?"));
            Statement st = statement(level, v, c, o, f.displayNameCap(), null);
            c.statements.add(st);
            o.sayLater(st.text(), 35);
            c.note(day, name + " said: \"" + st.text() + "\"" + (st.covering && (isConstable(f) || f.veteranLevel() >= 10)
                ? " (Holding something back, I'd say.)" : ""));
            Crime.changed();
        }
        if (gt - w.stepAt < Crime.pause(100)) return "asking " + name + " what it saw";
        w.next++;
        w.stepAt = -1;
        w.legStart = -1;
        c.progressDay = day;
        return "asking " + name + " what it saw";
    }

    private static String search(ServerLevel level, Villages.Village v, VillageFolkEntity f, Case c, Work w, long gt) {
        long day = level.getDayTime() / 24000L;
        if (!w.listed) {
            w.search.clear();
            int most = isConstable(f) ? 3 : 2;
            for (Suspect s : ranked(c)) {
                if (w.search.size() >= most || s.weight < 1.5) break;
                if (!c.searched.contains(s.id)) w.search.add(s.id);
            }
            w.next = 0;
            w.listed = true;
        }
        if (w.next >= w.search.size()) {
            conclude(level, v, c, f, day);
            WORK.remove(f.getUUID());
            return "turning it over";
        }
        UUID who = w.search.get(w.next);
        VillageFolkEntity o = Civics.find(level, who);
        String name = o == null ? Mischief.nameOf(level, v.id(), who) : o.displayNameCap();
        BlockPos chest = o == null ? null : Mischief.chestOf(level, o);
        BlockPos go = chest != null ? chest : o != null ? o.blockPosition() : null;
        if (go == null) {
            c.note(day, "Could not search " + name + ".");
            w.next++;
            return "turning it over";
        }
        if (w.stepAt < 0 && !Mischief.near(f, go, 2.8)) {
            if (w.legStart < 0) w.legStart = gt;
            if (gt - w.legStart < 600) {
                walk(f, go, 0.85D);
                return chest != null ? "on its way to search " + name + "'s house" : "on its way to " + name;
            }
        }
        if (w.stepAt < 0) {
            w.stepAt = gt;
            f.getNavigation().stop();
            f.getLookControl().setLookAt(go.getX() + 0.5, go.getY() + 0.5, go.getZ() + 0.5);
            searchOne(level, v, c, f, o, chest, day);
        }
        if (gt - w.stepAt < Crime.pause(60)) return chest != null ? "searching " + name + "'s house" : "searching " + name + "'s pack";
        w.next++;
        w.stepAt = -1;
        w.legStart = -1;
        c.progressDay = day;
        return "turning it over";
    }

    /** One suspect searched: its purse against its wages, its pack, its home chest. */
    static void searchOne(ServerLevel level, Villages.Village v, Case c, VillageFolkEntity f, @Nullable VillageFolkEntity o, @Nullable BlockPos chest,
                          long day) {
        if (o == null) return;
        String name = o.displayNameCap();
        c.searched.add(o.getUUID());
        List<String> found = new ArrayList<>();
        // The purse, against what was in it then and the wages since.
        Near n = c.nearOf(o.getUUID());
        if (c.coins > 0 && n != null) {
            long paydays = Math.max(0, day - c.day);
            int expected = n.purse() + (int) (paydays * Wealth.wage(o));
            int jump = o.purse() - expected;
            if (jump >= Math.max(1, (int) Math.ceil(c.coins * 0.7))) {
                c.clues.add(new Clue("purse", name + "'s purse is " + jump + (jump == 1 ? " coin" : " coins") + " fuller than " + name
                    + " had then and has earned since.", 3.0, day).at(o.getUUID(), name));
                found.add("a purse fuller than its wages");
            }
        }
        // The pack and the chest.
        int inPack = 0, inChest = 0;
        for (ItemStack s : o.getInventoryItems()) if (Crime.stolenCase(s) == c.id) inPack += s.getCount();
        if (chest != null && level.getBlockEntity(chest) instanceof Container box) {
            for (int i = 0; i < box.getContainerSize(); i++) if (Crime.stolenCase(box.getItem(i)) == c.id) inChest += box.getItem(i).getCount();
        }
        if (inPack + inChest > 0) {
            boolean forged = c.kind == Crime.Kind.FORGERY;
            String what = forged ? (inPack + inChest) + " more forged coins" : c.goods + (c.victim != null ? ", " + c.victimName + "'s," : ", the town's,");
            String text = Mischief.capital(what) + " found in " + name + "'s " + (inChest > 0 ? "chest at home" : "pack") + ".";
            c.clues.add(new Clue("goods", text, forged ? 5.0 : 6.0, day).at(o.getUUID(), name));
            found.add(forged ? "forged coins" : "the stolen goods");
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Well, well. What's this, then?", "And where did this come from, " + name + "?"));
        }
        c.note(day, "Searched " + name + (chest != null ? "'s house" : "") + ": " + (found.isEmpty() ? "nothing." : String.join(" and ", found) + "."));
        Crime.changed();
    }

    /**
     * The rest of a case done at once, without the walking (the operators' stage): the scene read, everybody about asked,
     * the likeliest searched, and somebody named or the case given up.
     */
    static void finishNow(ServerLevel level, Villages.Village v, Case c) {
        long day = level.getDayTime() / 24000L;
        if (c.stage == Stage.REPORTED && !assign(level, v, c)) return;
        VillageFolkEntity f = c.investigator == null ? null : Civics.find(level, c.investigator);
        if (f == null) return;
        if (c.stage == Stage.SCENE) {
            examine(level, v, c, f, day);
            c.stage = Stage.QUESTIONING;
        }
        if (c.stage == Stage.QUESTIONING) {
            for (UUID who : toAsk(c, f)) {
                if (c.statementFrom(who) != null) continue;
                VillageFolkEntity o = Civics.find(level, who);
                if (o == null) continue;
                Statement st = statement(level, v, c, o, f.displayNameCap(), null);
                c.statements.add(st);
                c.note(day, o.displayNameCap() + " said: \"" + st.text() + "\"");
            }
            c.stage = Stage.SEARCHING;
        }
        if (c.stage == Stage.SEARCHING) {
            int most = isConstable(f) ? 3 : 2, n = 0;
            for (Suspect s : ranked(c)) {
                if (n >= most || s.weight < 1.5) break;
                if (c.searched.contains(s.id)) continue;
                VillageFolkEntity o = Civics.find(level, s.id);
                searchOne(level, v, c, f, o, o == null ? null : Mischief.chestOf(level, o), day);
                n++;
            }
            conclude(level, v, c, f, day);
        }
        WORK.remove(f.getUUID());
        Crime.changed();
    }

    /** The evidence weighed: the one it points to named, or the case given up. */
    static void conclude(ServerLevel level, Villages.Village v, Case c, VillageFolkEntity f, long day) {
        List<Suspect> r = ranked(c);
        Suspect top = r.isEmpty() ? null : r.get(0);
        double second = r.size() > 1 ? r.get(1).weight : 0.0;
        boolean hasty = !isConstable(f) && (f.veteranLevel() < 6 || f.life().has(Social.Trait.GRUMPY));
        boolean plain = top != null && top.weight >= ACCUSE && top.weight >= second + AHEAD;
        boolean rash = top != null && hasty && top.weight >= HASTY && top.weight >= second + 0.5;
        if (plain || rash) {
            accuse(level, v, c, f, top, day, !plain);
            return;
        }
        giveUp(level, v, c, f, top == null ? "Nobody to go on" : "Not enough to name anybody: " + suspectsWords(c));
    }

    /** One named: before the council. */
    static void accuse(ServerLevel level, Villages.Village v, Case c, VillageFolkEntity f, Suspect top, long day, boolean rash) {
        c.accused = top.id;
        c.accusedName = top.name;
        c.stage = Stage.ACCUSED;
        c.progressDay = day;
        c.note(day, "Named " + top.name + " (" + String.format(Locale.ROOT, "%.1f", top.weight) + ": " + String.join(", ", top.why) + ")"
            + (rash ? ". On the thin side, but it'll do for the council." : ". The council will hear it."));
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), top.name + " — you'll answer to the council for this.", "It was " + top.name
            + ". I'd stake my badge on it.", "The council can hear it. " + top.name + ", that means you."));
        VillageFolkEntity o = Civics.find(level, top.id);
        if (o != null) o.sayLater(top.id.equals(c.culprit) && Mischief.honesty(o) >= 55 ? "...I suppose you'd better take me in."
            : FolkTalk.pick(o.getRandom(), "Me? I never!", "You've got the wrong one!", "This is a mistake."), 30);
        Villages.tell(v.id(), day, c.investigatorName + " of the watch named " + top.name + " for the " + c.title().toLowerCase(Locale.ROOT)
            + "; the council will hear it");
        Crime.changed();
    }

    /** No further to go: the case given up (its books kept open to the end of the month). */
    static void giveUp(ServerLevel level, Villages.Village v, Case c, @Nullable VillageFolkEntity f, String why) {
        long day = level.getDayTime() / 24000L;
        c.stage = Stage.UNSOLVED;
        c.closedDay = day;
        c.verdict = "unsolved";
        c.note(day, why + ". The case is given up.");
        if (f != null) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Not enough to go on. It galls me.", "Whoever it was, they've got away with it — this time."));
        if (c.investigator != null) WORK.remove(c.investigator);
        Villages.tell(v.id(), day, "the watch gave up on the " + c.title().toLowerCase(Locale.ROOT) + ": nobody could be named");
        Trial.returnEvidence(level, v, c);
        Crime.closed(c, false);
        Crime.changed();
    }

    // ------------------------------------------------------------------ what folk say

    /**
     * What a folk tells whoever asks about the deed. The culprit says it was elsewhere (or, an honest one sometimes,
     * owns up); the victim says what it lost and whom it would blame; a witness tells what it saw, unless it loves
     * the one it saw (and saw nothing), or, dishonest and unsure, names its own rival instead; whoever was about and
     * saw nothing says where it was.
     */
    static Statement statement(ServerLevel level, Villages.Village v, Case c, VillageFolkEntity o, String askedBy, @Nullable Player asker) {
        long day = level.getDayTime() / 24000L;
        UUID id = o.getUUID();
        String name = o.displayNameCap();
        RandomSource r = o.getRandom();
        int honesty = Mischief.honesty(o);
        if (id.equals(c.culprit)) {
            boolean ownUp = honesty >= 70 || honesty >= 50 && r.nextInt(4) == 0;
            if (ownUp) {
                c.confessed = true;
                return new Statement(id, name, false, FolkTalk.pick(r, "...It was me. I'm sorry. I don't know what came over me.",
                    "I won't lie to you. It was me, and I'm ashamed of it."), id, name, 95, "", false, false, askedBy, day);
            }
            String where = FolkTalk.pick(r, "I was at home all " + Crime.hour(c.hour) + ".", "Me? I was nowhere near " + c.place + ".",
                "I was at my work, ask anybody.");
            return new Statement(id, name, false, where, null, "", 0, "", false, true, askedBy, day);
        }
        if (id.equals(c.victim)) {
            String lost = switch (c.kind) {
                case PICKPOCKET -> "My purse was full in the morning and " + c.coins + (c.coins == 1 ? " coin" : " coins") + " light by " + Crime.hour(c.hour) + ".";
                case BURGLARY -> Mischief.capital(c.goods) + " out of my chest, while I was out.";
                case VANDALISM -> "I came out and found it broken.";
                default -> Mischief.capital(c.what()) + ".";
            };
            UUID rival = null;
            String rivalName = "";
            for (Near n : c.near) {
                if (n.id().equals(id)) continue;
                if (o.life().affinity(n.id()) <= Social.RIVAL) { rival = n.id(); rivalName = n.name(); break; }
            }
            if (rival != null) {
                Statement s = new Statement(id, name, false, lost + " If you ask me, it was " + rivalName + ". Never trusted them.", rival, rivalName, 30, "",
                    false, false, askedBy, day);
                s.trust = 0.6;
                return s;
            }
            return new Statement(id, name, false, lost + " I didn't see who.", null, "", 0, "", false, false, askedBy, day);
        }
        Witness w = c.witness(id);
        if (w != null) {
            boolean friendly = asker != null && o.persona().affinity(asker.getUUID()) >= 55;
            if (covers(o, c, w) && !friendly) {
                return new Statement(id, name, false, FolkTalk.pick(r, "I didn't see anything.", "Me? No. Nothing at all.", "I wasn't looking."),
                    null, "", 0, "", true, false, askedBy, day);
            }
            if (w.certainty < 65 && honesty < 40) {
                Near rival = null;
                for (Near n : c.near) {
                    if (n.id().equals(id) || n.id().equals(w.thinks) || n.id().equals(c.victim)) continue;
                    if (o.life().affinity(n.id()) <= -40) { rival = n; break; }
                }
                if (rival != null) {
                    Statement s = new Statement(id, name, false, FolkTalk.pick(r, "It was " + rival.name() + ". I'd know that walk anywhere.",
                        "I saw " + rival.name() + " there, plain as day."), rival.id(), rival.name(), 60, "", false, false, askedBy, day);
                    s.trust = 0.6;                    // the town knows they don't get on
                    return s;
                }
            }
            String text = "I saw " + w.saw + (w.thinks != null && w.certainty < 65 ? ". I think it was " + w.thinksName + ", but I couldn't swear to it" : "")
                + ".";
            Statement s = new Statement(id, name, false, text, w.thinks, w.thinksName, w.thinks == null ? 0 : w.certainty, w.thinks == null ? w.outfit : "",
                false, false, askedBy, day);
            if (o.isBaby()) s.trust = 0.7;
            if (Crime.convictions(id) > 0) s.trust *= 0.6;
            if (w.thinks != null && o.life().affinity(w.thinks) <= -30) s.trust *= 0.6;
            return s;
        }
        Near n = c.nearOf(id);
        if (n != null) {
            return new Statement(id, name, false, FolkTalk.pick(r, "I didn't see anything. I was " + n.doing() + " at the time.",
                "Nothing, I'm afraid. I was " + n.doing() + "."), null, "", 0, "", false, false, askedBy, day);
        }
        return new Statement(id, name, false, "I wasn't about. I can't help you.", null, "", 0, "", false, false, askedBy, day);
    }

    /** It saw who it was, and loves them too well (and is not honest enough) to say so. */
    static boolean covers(VillageFolkEntity o, Case c, Witness w) {
        if (w.thinks == null || w.player) return false;
        boolean loves = w.thinks.equals(o.life().partner()) || o.life().affinity(w.thinks) >= Social.CLOSE;
        return loves && Mischief.honesty(o) < 70;
    }

    // ------------------------------------------------------------------ the weighing

    /** Everybody the evidence touches, and how heavily. */
    static Map<UUID, Suspect> score(Case c) {
        Map<UUID, Suspect> s = new LinkedHashMap<>();
        for (Near n : c.near) add(s, n.id(), n.name(), 0.3, null);
        for (Clue k : c.clues) {
            for (int i = 0; i < k.points.size(); i++) if (k.weight > 0) add(s, k.points.get(i), k.names.get(i), k.weight, k.kind);
        }
        Map<String, List<Near>> byTrade = new LinkedHashMap<>();
        for (Near n : c.near) byTrade.computeIfAbsent(n.trade(), x -> new ArrayList<>()).add(n);
        for (Statement st : c.statements) {
            if (st.named != null && st.named.equals(st.from)) {
                if (st.claimed >= 90) add(s, st.from, st.fromName, 6.0, "owned up");
                continue;
            }
            if (st.named != null) add(s, st.named, st.namedName, st.claimed / 20.0 * st.trust * (st.player ? 1.1 : 1.0),
                (st.player ? "seen by " : "named by ") + st.fromName);
            else if (!st.outfit.isEmpty()) {
                List<Near> of = byTrade.getOrDefault(st.outfit, List.of());
                for (Near n : of) if (!n.id().equals(st.from)) add(s, n.id(), n.name(), 1.5 / Math.max(1, of.size()), "dressed as " + st.fromName + " described");
            }
        }
        // A lie found out: said it was elsewhere, but somebody put it there.
        for (Statement st : c.statements) {
            if (!st.lie) continue;
            for (Statement o : c.statements) {
                if (o != st && st.from.equals(o.named)) { add(s, st.from, st.fromName, 1.5, "said it was elsewhere"); break; }
            }
        }
        for (Suspect x : s.values()) {
            int conv = Crime.convictions(x.id);
            if (conv > 0 && x.weight > 0.3) { x.weight += Math.min(1.0, 0.5 * conv); x.why.add("known to the watch"); }
        }
        for (UUID u : c.cleared) s.remove(u);
        if (c.victim != null) s.remove(c.victim);
        if (c.investigator != null) s.remove(c.investigator);
        return s;
    }

    private static void add(Map<UUID, Suspect> s, UUID id, String name, double w, @Nullable String why) {
        Suspect x = s.computeIfAbsent(id, k -> new Suspect(id, name));
        x.weight += w;
        if (why != null && !x.why.contains(why)) x.why.add(why);
    }

    /** The suspects, the heaviest first. */
    static List<Suspect> ranked(Case c) {
        List<Suspect> out = new ArrayList<>(score(c).values());
        out.sort((a, b) -> Double.compare(b.weight, a.weight));
        return out;
    }

    /** The evidence against one, as the council weighs it. */
    static double against(Case c, UUID who) {
        Suspect s = score(c).get(who);
        return s == null ? 0.0 : s.weight;
    }

    static String suspectsWords(Case c) {
        List<String> top = new ArrayList<>();
        for (Suspect s : ranked(c)) {
            if (top.size() >= 3 || s.weight < 1.0) break;
            top.add(s.name + " (" + String.format(Locale.ROOT, "%.1f", s.weight) + ")");
        }
        return top.isEmpty() ? "Nobody stands out" : "Suspects: " + String.join(", ", top);
    }

    // ------------------------------------------------------------------ the player's part

    /** A player hands a guard what was dropped at the scene. */
    static String handIn(ServerLevel level, Case c, VillageFolkEntity guard, ServerPlayer p, ItemStack held) {
        Villages.Village v = Villages.get(c.village);
        if (v == null) return "Hmm.";
        if (!c.stage.open() || c.droppedFound) {
            return c.stage.open() ? "We've had that already, thank you." : "That case is closed. Keep it, if you like.";
        }
        ItemStack one = held.split(1);
        c.evidence = CompoundTagHolder.save(level, one);
        if (c.droppedEntity != null) {
            Entity e = level.getEntity(c.droppedEntity);
            if (e != null) e.discard();
        }
        c.helped(p);
        dropped(level, v, c, p);
        Clue k = c.clues.get(c.clues.size() - 1);
        FolkTalk.speak(guard, FolkTalk.pick(guard.getRandom(), "Where did you find this?", "Now that's a find."));
        return "Where did you find this — at " + c.place + "? " + k.text() + " Good work. I'll put it to the council.";
    }

    /** A player tells a guard who it saw do it: believed, if the player did see it. Null if this was nothing of the kind. */
    @Nullable
    static String playerReport(ServerLevel level, Villages.Village v, VillageFolkEntity f, ServerPlayer p, VillageFolkEntity named) {
        boolean watch = f.stationTask() == StationTask.GUARD || f.getUUID().equals(Villages.elder(v.id()));
        long day = level.getDayTime() / 24000L;
        for (Case c : Crime.cases(v.id())) {
            Witness w = c.witness(p.getUUID());
            if (w == null || !w.player) continue;
            if (!c.stage.open()) return "That's done with: " + c.verdict + ".";
            if (!watch) {
                return "Don't tell me — tell the watch! " + (c.investigator == null ? "Any guard will do." : c.investigatorName + " is looking into it.");
            }
            boolean right = named.getUUID().equals(w.thinks) || named.getUUID().equals(c.culprit) && w.certainty >= 35;
            c.statements.removeIf(s -> s.from.equals(p.getUUID()));
            Statement st = new Statement(p.getUUID(), p.getName().getString(), true, "I saw " + named.displayNameCap() + " do it, at " + c.place + ".",
                named.getUUID(), named.displayNameCap(), right ? Math.max(70, w.certainty) : 40, "", false, false, f.displayNameCap(), day);
            if (!right) st.trust = 0.6;
            c.statements.add(st);
            c.helped(p);
            c.note(day, p.getName().getString() + " told " + f.displayNameCap() + " they saw " + named.displayNameCap() + " do it.");
            if (c.stage == Stage.UNNOTICED) Crime.report(level, v, c, p.getName().getString());
            if (c.stage == Stage.REPORTED && f.stationTask() == StationTask.GUARD && fit(f) && caseOf(f) == null) {
                c.investigator = f.getUUID();
                c.investigatorName = f.displayNameCap();
                c.stage = Stage.SCENE;
                c.note(day, f.displayNameCap() + " took the case.");
            }
            Crime.listeners(l -> l.helped(p, v.id(), c.id, "told the watch what they saw"));
            Crime.changed();
            return "You saw it yourself? Then the council will hear it from you: " + named.displayNameCap() + ", at " + c.place + ". I've put it in the case.";
        }
        if (!watch) return null;
        for (Case c : Crime.open(v.id())) {
            if (c.stage == Stage.UNNOTICED) continue;
            return "You think it was " + named.displayNameCap() + "? I'll bear it in mind. But the watch needs more than a name: ask about, and bring me what you find.";
        }
        return null;
    }

    /**
     * A player asks a witness (or one who was about, or the victim) about the deed. What it says goes into the case as
     * if the watch had asked it, and a friend of the player may tell the player what it would not tell the watch.
     */
    @Nullable
    static String answerPlayer(ServerLevel level, Villages.Village v, Case c, VillageFolkEntity f, ServerPlayer p) {
        long day = level.getDayTime() / 24000L;
        String place = c.place;
        if (f.getUUID().equals(c.culprit)) {
            return FolkTalk.pick(f.getRandom(), "Why are you asking me? I was nowhere near " + place + ".", "I don't know anything about it. Why would I?",
                "Ask somebody else. I've work to do.");
        }
        if (c.stage == Stage.UNNOTICED) {
            Witness w = c.witness(f.getUUID());
            if (w == null) return null;
        }
        if (f.persona().affinity(p.getUUID()) <= -15) return "I've nothing to say to you.";
        Statement had = c.statementFrom(f.getUUID());
        Witness w = c.witness(f.getUUID());
        if (had != null) {
            if (had.covering && w != null && f.persona().affinity(p.getUUID()) >= 55) {
                Statement truth = statement(level, v, c, f, p.getName().getString(), p);
                c.statements.remove(had);
                c.statements.add(truth);
                c.helped(p);
                c.note(day, f.displayNameCap() + " told " + p.getName().getString() + " what it would not tell the watch.");
                Crime.listeners(l -> l.helped(p, v.id(), c.id, "got the truth out of a witness"));
                Crime.changed();
                return "...All right. Between us: " + truth.text() + " Don't tell them it was me who said.";
            }
            return "I told the watch already: " + had.text();
        }
        Statement st = statement(level, v, c, f, p.getName().getString(), p);
        c.statements.add(st);
        c.helped(p);
        c.note(day, p.getName().getString() + " asked " + f.displayNameCap() + ", who said: \"" + st.text() + "\"");
        // Having told somebody, a witness who saw who it was goes to the watch with it too.
        if (c.stage == Stage.UNNOTICED && st.named != null) Crime.report(level, v, c, f.displayNameCap());
        Crime.listeners(l -> l.helped(p, v.id(), c.id, "asked a witness"));
        Crime.changed();
        return st.text() + (st.named != null || !st.outfit.isEmpty() ? " You'd best tell the watch." : "");
    }

    /** "Seen anything amiss?" to a guard: the case it is on, and what a player can do. */
    static String guardTalk(ServerLevel level, Villages.Village v, VillageFolkEntity f, ServerPlayer p) {
        Case c = caseOf(f);
        if (c == null) {
            for (Case o : Crime.open(v.id())) if (o.stage != Stage.UNNOTICED) { c = o; break; }
        }
        String constable = isConstable(f) ? "As the town's constable, every case is mine. " : "";
        if (c == null) {
            return constable + FolkTalk.pick(f.getRandom(), "All quiet. If you see anything amiss, come and tell me.",
                "Nothing on the books just now. Keep your eyes open for me.");
        }
        int asked = 0;
        for (Statement s : c.statements) if (!s.player) asked++;
        String where = switch (c.stage) {
            case REPORTED -> "Nobody's looked into it yet.";
            case SCENE -> "I'm going over the scene.";
            case QUESTIONING -> "I've asked " + asked + (asked == 1 ? " so far." : " so far.");
            case SEARCHING -> "I've asked about; now I'm searching. " + suspectsWords(c) + ".";
            case ACCUSED, TRIAL -> c.accusedName + " is to answer to the council for it.";
            default -> "";
        };
        String help = c.stage.investigating() || c.stage == Stage.REPORTED
            ? " If you saw anything, tell me who; if you find anything dropped about " + c.place + ", bring it here; ask about — folk tell a friend what they won't tell the watch."
            : "";
        return constable + (f.getUUID().equals(c.investigator) ? "I'm on the " : "The watch is on the ") + c.title().toLowerCase(Locale.ROOT) + ": " + c.what()
            + ". " + where + help;
    }

    // ------------------------------------------------------------------ what keeps crime down

    /** For the books: the watch, the lamps, the town's spirits, what put deeds off this month, and who is tempted now. */
    static List<String> keeps(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        int guards = Patrols.watch(id).size(), head = Math.max(1, Villages.headcount(id));
        out.add("The watch: " + guards + (guards == 1 ? " guard" : " guards") + " for " + head + " folk" + (guards == 0 ? ", so nobody walks the streets at night."
            : ", walking the streets by day and by night" + (guards * 6 >= head ? " — enough to put most off." : ".")));
        Villages.Age age = Villages.ageOf(id);
        out.add(age.ordinal() >= Villages.Age.IRON.ordinal() ? "The lamps: lamp posts along the avenues and round the ring street; nothing is done in their light."
            : age.ordinal() >= Villages.Age.STONE.ordinal() ? "The lamps: torches on posts along the avenues; lamp posts come with the Iron Age."
            : "The lamps: none yet; the streets are dark at night.");
        int content = Contentment.score(id);
        out.add("Contentment " + content + " (" + Contentment.word(content) + "): " + (content >= 60 ? "contented folk let things go." :
            content >= 40 ? "a town getting by tempts only its poorest." : "an unhappy town tempts its poor, its hungry and its bitter."));
        Map<String, Integer> off = Crime.deterredSince(id, day - 27);
        if (!off.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : off.entrySet()) parts.add(e.getValue() + " by " + switch (e.getKey()) {
                case "eyes" -> "folk about";
                case "home" -> "a house not empty";
                default -> e.getKey();
            });
            out.add("Put off this month: " + String.join(", ", parts) + ".");
        }
        int tempted = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (a instanceof VillageFolkEntity f && Mischief.eligible(f, day) && Mischief.temptation(level, f) >= Mischief.TEMPTED) tempted++;
        }
        out.add(tempted == 0 ? "Nobody is tempted just now." : tempted + (tempted == 1 ? " folk is" : " folk are")
            + " tempted just now: poor, hungry, low or bitter, and not honest enough to let it go.");
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static void walk(VillageFolkEntity f, BlockPos to, double speed) {
        Integer last = PATHED.get(f.getUUID());
        if (last == null || f.tickCount - last > 40 || f.tickCount < last || f.getNavigation().isDone()) {
            f.walkTo(to, speed);
            PATHED.put(f.getUUID(), f.tickCount);
        }
    }

    /** The dropped thing, kept by the watch as a saved stack (its registries from the level). */
    static final class CompoundTagHolder {
        private CompoundTagHolder() {}

        static net.minecraft.nbt.CompoundTag save(ServerLevel level, ItemStack s) {
            if (s.isEmpty()) return new net.minecraft.nbt.CompoundTag();
            return (net.minecraft.nbt.CompoundTag) s.save(level.registryAccess());
        }

        static ItemStack load(ServerLevel level, net.minecraft.nbt.CompoundTag t) {
            if (t.isEmpty()) return ItemStack.EMPTY;
            return ItemStack.parseOptional(level.registryAccess(), t);
        }
    }

}
