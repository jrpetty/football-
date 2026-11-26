package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

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
 * [featfolk] Generations: a trade and a house handed down.
 *
 * <ul>
 * <li><b>Retiring.</b> A folk of {@link #RETIRE_AT} or more, with an heir to its trade, hands it on: its post and its
 * ground (and, if the heir has none of its own, one of its knacks for the trade) go to the heir, there and then, and it
 * is retired. Its heir is a grown child of its own already at the trade or with none, else a grown child apprenticed to
 * the trade, else the journeyman it brought on itself. A retired folk keeps its own hours: of a morning it goes and
 * watches its heir at the work and has something to say about it, of an afternoon it sits in the square, and it sleeps
 * at home ({@link #retiredDay}). With no heir it works on, as before.</li>
 * <li><b>Passing on.</b> A folk that dies with its trade in hand leaves it to its heir, by the same rule. Its house
 * stays with its household; if its partner is gone before it, the grown children living there have it, and the town's
 * book says so ({@link #died}). The town keeps a vigil, as it always has.</li>
 * <li><b>Born at home.</b> A child is born in its parents' house when the mother is anywhere near it ({@link #birthplace}).</li>
 * <li><b>The family trees</b> on the board's Families page ({@link #report}): each family from its eldest in the town
 * down, partners and children, who is courting whom, what was handed down, and the households waiting for a house of
 * their own (the town builds for them: Homes, FullTown).</li>
 * </ul>
 */
public final class Lineage {

    private Lineage() {}

    /** The age a folk with an heir retires at. */
    public static final int RETIRE_AT = 66;

    // ------------------------------------------------------------------ heirs

    /** Its grown children in the town, eldest first. */
    static List<VillageFolkEntity> children(VillageFolkEntity f) {
        List<VillageFolkEntity> out = new ArrayList<>();
        UUID village = f.ownerId();
        if (village == null) return out;
        String me = f.displayNameCap();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity c) || c == f || !c.isAlive()) continue;
            if (c.parentIds().contains(f.getUUID()) || (" and " + c.life().parents() + " and ").contains(" and " + me + " and ")) out.add(c);
        }
        out.sort((x, y) -> Long.compare(x.bornDay(), y.bornDay()));
        return out;
    }

    /** Who takes up this folk's trade: see the class. Null if nobody. */
    @Nullable
    public static VillageFolkEntity heir(VillageFolkEntity f, StationTask trade) {
        if (trade == StationTask.NONE) return null;
        VillageFolkEntity apprenticed = null;
        for (VillageFolkEntity c : children(f)) {
            if (c.isBaby() || c.lines().retired() || c.isHired()) continue;
            if (c.stationTask() == trade || c.stationTask() == StationTask.NONE) return c;
            if (apprenticed == null && trade.name().equals(c.lines().trade)) apprenticed = c;
        }
        if (apprenticed != null) return apprenticed;
        UUID village = f.ownerId();
        if (village == null) return null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity p) || p == f || p.isBaby() || p.lines().retired() || p.isHired()) continue;
            if (f.getUUID().equals(p.lines().master) && trade.name().equals(p.lines().trade)
                    && !Apprenticeship.APPRENTICE.equals(p.lines().rank)
                    && (p.stationTask() == trade || p.stationTask() == StationTask.NONE)) return p;
        }
        return null;
    }

    /** What it is to the heir, for the news: "her mother", "their master". */
    static String relation(VillageFolkEntity heir, VillageFolkEntity from) {
        if (heir.parentIds().contains(from.getUUID()) || heir.life().parents().contains(from.displayNameCap())) return "parent";
        return "master";
    }

    // ------------------------------------------------------------------ handing on

    /** The trade, its post and ground, to the heir. Returns whether it was handed on. */
    static boolean handOn(ServerLevel level, VillageFolkEntity from, VillageFolkEntity heir, long day, boolean dying) {
        StationTask trade = from.stationTask();
        if (trade == StationTask.NONE) return false;
        BlockPos post = from.stationPos();
        WorkZone ground = from.workZone();
        String plot = from.patchName();
        if (post == null && ground == null) return false;
        heir.clearQueue();
        heir.setStation(post != null ? post : ground.center(), trade);
        if (ground != null) heir.assignPlot(ground, plot.isEmpty() ? trade.title.toLowerCase(Locale.ROOT) : plot);
        // One of its knacks for the trade, if the heir has none of its own for it.
        boolean has = false;
        for (FolkSkills.Chosen c : heir.knacks().chosen()) has |= c.knack().trades.contains(trade);
        if (!has) {
            for (FolkSkills.Chosen c : from.knacks().chosen()) {
                if (c.knack().family == FolkSkills.Family.TRADE && c.knack().trades.contains(trade) && FolkSkills.grant(level, heir, c.knack())) break;
            }
        }
        String t = trade.title.toLowerCase(Locale.ROOT);
        String what = from.displayNameCap() + "'s " + (plot.isEmpty() ? t + "'s post" : plot);
        heir.lines().inherited.add(what + " (day " + (day + 1) + ")");
        if (heir.lines().trade.isEmpty() || !heir.lines().trade.equals(trade.name())) {
            heir.lines().trade = trade.name();
            if (heir.lines().rank.isEmpty()) heir.lines().rank = Apprenticeship.JOURNEYMAN;
            heir.lines().masterName = from.displayNameCap();
            heir.lines().master = from.getUUID();
        }
        heir.persona().remember(day, "I took on " + what, 8);
        UUID village = from.ownerId();
        if (village != null) {
            Villages.tell(village, day, heir.displayNameCap() + " took on " + what + (dying ? "" : ", " + from.displayNameCap() + " having retired"));
        }
        if (!dying) {
            from.clearQueue();
            from.setWorkZone(null);                                   // off its post and ground: the heir's now
            from.lines().retired = true;
            from.lines().retiredDay = day;
            from.lines().retiredFrom = trade.title;
            from.lines().handedTo = heir.displayNameCap();
            from.persona().remember(day, "I retired, and " + heir.displayNameCap() + " took on my " + t + "'s work", 8);
            RandomSource r = from.getRandom();
            FolkTalk.speak(from, FolkTalk.pick(r, "That's it, then. It's yours. Mind the left gate, it sticks.",
                "Forty years of it. You'll do better. Don't tell me if you do.", "I'm done. I'll be in the square, criticising.",
                "Take care of it. It took care of me.", "Retired. I'll have to find something to complain about.",
                "It's yours now. I'll only come round every morning to watch."));
            heir.sayLater(FolkTalk.pick(r, "I'll keep it as you kept it.", "I won't let it go to ruin.", "You'll still come by?",
                "I'll try not to change anything. Much.", "Thank you. I mean it.", "I learned from the best. Don't make that face."), 50);
            FolkAction.play(from, FolkAction.Act.HANDSHAKE, 40);
            FolkAction.play(heir, FolkAction.Act.HANDSHAKE, 40);
            FolkAction.mark(from, FolkAction.Mark.HANDSHAKE);
        } else {
            RandomSource r = heir.getRandom();
            heir.sayLater(FolkTalk.pick(r, "I'll keep the " + t + "'s work going. It's what they'd want.", "I'll look after it for them.",
                "They showed me how. I'll carry on.", "It's mine to keep now. I'll keep it well.",
                "Same ground, same hours. That's how they'd have it.", "I'll do it their way. Mostly."), 200);
        }
        return true;
    }

    /** Once a day (VillageFolkEntity's agenda): an old folk with an heir hands its trade on and retires. */
    public static void daily(ServerLevel level, VillageFolkEntity f, long day) {
        if (f.isBaby() || f.lines().retired() || f.isShowcase() || f.ownerId() == null) return;
        if (f.ageYears() < RETIRE_AT || f.stationTask() == StationTask.NONE || f.isElder() || f.isHired()) return;
        if (f.stationTask() == StationTask.GUARD && Raids.underAlarm(f.ownerId())) return;
        VillageFolkEntity heir = heir(f, f.stationTask());
        if (heir == null) return;
        handOn(level, f, heir, day, false);
    }

    /**
     * Dead (VillageFolkEntity.die): its trade to its heir if it still worked one, and its house to its grown children if
     * its partner is gone too. Courting ends for the one left.
     */
    public static void died(ServerLevel level, VillageFolkEntity f) {
        Courtship.endFor(f, level);
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || f.isShowcase()) return;
        long day = level.getDayTime() / 24000L;
        if (!f.lines().retired() && f.stationTask() != StationTask.NONE) {
            VillageFolkEntity heir = heir(f, f.stationTask());
            if (heir != null) handOn(level, f, heir, day, true);
        }
        // The house: the household keeps it; with no partner left, the grown children at home have it.
        UUID partner = f.life().partner();
        boolean partnerLives = partner != null && level.getEntity(partner) instanceof VillageFolkEntity p && p.isAlive() && p != f;
        BlockPos home = Homes.homeOf(f);
        if (partnerLives || home == null) return;
        Homes.Household h = Homes.householdAt(village, home);
        if (h == null) return;
        for (VillageFolkEntity c : h.folk()) {
            if (c == f || c.isBaby() || !(c.parentIds().contains(f.getUUID()) || c.life().parents().contains(f.displayNameCap()))) continue;
            String what = "the family house, from " + f.displayNameCap();
            c.lines().inherited.add(what + " (day " + (day + 1) + ")");
            c.persona().remember(day, "the house came to me from " + f.displayNameCap(), 7);
            Villages.tell(village, day, c.displayNameCap() + " has " + f.displayNameCap() + "'s house now");
            break;
        }
    }

    /** Where a child is born: on its mother's bed at home when she is near it, else where she is. */
    public static BlockPos birthplace(ServerLevel level, VillageFolkEntity mother) {
        BlockPos bed = mother.bedPos();
        if (bed != null && bed.distSqr(mother.blockPosition()) < 48 * 48 && level.isLoaded(bed)) return bed;
        return mother.blockPosition();
    }

    // ------------------------------------------------------------------ retired

    /**
     * A retired folk's day (VillageFolkEntity's agenda, by day): of a morning it watches its heir at the work, a few
     * steps off, with an opinion; of an afternoon it sits about the square or the park.
     */
    public static void retiredDay(VillageFolkEntity f, ServerLevel level) {
        if (f.tickCount % 20 != 0) return;
        long t = level.getDayTime() % 24000L;
        RandomSource r = f.getRandom();
        VillageFolkEntity heir = null;
        if (t < 6000L && !f.lines().handedTo.isEmpty() && f.ownerId() != null) {
            for (AssistantEntity a : Villages.folkOf(f.ownerId())) {
                if (a instanceof VillageFolkEntity h && h.displayNameCap().equals(f.lines().handedTo) && h.isAlive() && h.onShift()) { heir = h; break; }
            }
        }
        if (heir != null) {
            if (f.distanceToSqr(heir) > 5.0 * 5.0) {
                if (f.getNavigation().isDone() || f.tickCount % 100 == 0) f.walkTo(heir.blockPosition(), 0.7D);
            } else {
                f.getNavigation().stop();
                f.getLookControl().setLookAt(heir, 30.0F, 30.0F);
                if (r.nextInt(30) == 0 && level.getNearestPlayer(f, 16.0) != null) {
                    FolkTalk.speak(f, FolkTalk.pick(r, "That's not how I did it.", "Well. That works too, I suppose.",
                        "Don't rush it.", "You've got the knack. Don't let it go to your head.", "In my day we did that by hand.",
                        "Steady. Steady. There."));
                    heir.sayLater(FolkTalk.pick(r, "Yes, I know.", "Thank you. I think.", "Shouldn't you be resting?", "I'm doing it your way.",
                        "Go and sit down.", "Mm-hm."), 40);
                }
            }
            return;
        }
        BlockPos seat = f.ownerId() == null ? null : Villages.builtAt(f.ownerId(), "park");
        if (seat == null) seat = f.villageCentre();
        if (seat == null) return;
        if (f.blockPosition().distSqr(seat) > 6.0 * 6.0) {
            if (f.getNavigation().isDone() || f.tickCount % 200 == 0) {
                BlockPos to = seat.offset(r.nextInt(7) - 3, 0, r.nextInt(7) - 3);
                f.walkTo(to, 0.6D);
            }
        } else if (f.getNavigation().isDone() && r.nextInt(6) == 0) {
            FolkAction.play(f, FolkAction.Act.SIT, 200);
        }
    }

    /** "Retired farmer, handed down to Wren on day 40", for its card; "Took on Bramble's farm", for an heir's; or null. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        FolkLines l = f.lines();
        List<String> parts = new ArrayList<>();
        if (l.retired) parts.add("Retired " + l.retiredFrom.toLowerCase(Locale.ROOT) + (l.handedTo.isEmpty() ? "" : ", handed down to " + l.handedTo)
            + " on day " + (l.retiredDay + 1));
        for (String s : l.inherited) parts.add("Came into " + s);
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    // ------------------------------------------------------------------ the board

    /** The Families page (FamiliesPage): the trees, the courting, what was handed down, the households waiting. */
    public static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag c = new CompoundTag();
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f && !f.isShowcase() && f.isAlive()) folk.add(f);
        Map<UUID, VillageFolkEntity> byId = new LinkedHashMap<>();
        for (VillageFolkEntity f : folk) byId.put(f.getUUID(), f);
        int couples = 0, children = 0, courting = 0, retired = 0;
        for (VillageFolkEntity f : folk) {
            if (f.life().partner() != null && byId.containsKey(f.life().partner()) && f.getUUID().compareTo(f.life().partner()) < 0) couples++;
            if (f.isBaby()) children++;
            if (f.lines().courting != null && f.getUUID().compareTo(f.lines().courting) < 0) courting++;
            if (f.lines().retired) retired++;
        }
        int waiting = FullTown.waiting(level, village);
        c.putString("summary", folk.size() + " folk: " + couples + (couples == 1 ? " couple, " : " couples, ") + children
            + (children == 1 ? " child, " : " children, ") + courting + " walking out, " + retired + " retired");
        c.putString("housing", Villages.headcount(village) + " living here, room for " + Villages.housing(village)
            + (waiting > 0 ? "; " + waiting + (waiting == 1 ? " household waits" : " households wait") + " for a house of its own, and the builders build for them"
            : "; every household has a house of its own")
            + ". Children are born only while there is room.");
        // The trees: from each folk with no parent in the town, who has a partner or a child here.
        ListTag trees = new ListTag();
        Set<UUID> drawn = new HashSet<>();
        folk.sort((x, y) -> Long.compare(x.bornDay(), y.bornDay()));
        for (VillageFolkEntity f : folk) {
            if (drawn.contains(f.getUUID()) || hasParentHere(f, byId)) continue;
            VillageFolkEntity partner = f.life().partner() == null ? null : byId.get(f.life().partner());
            if (partner != null && hasParentHere(partner, byId)) continue;             // drawn under its own family
            List<VillageFolkEntity> kids = kidsOf(f, partner, folk);
            if (partner == null && kids.isEmpty()) continue;
            ListTag rows = new ListTag();
            tree(rows, f, partner, folk, byId, drawn, 0);
            CompoundTag t = new CompoundTag();
            t.putString("name", "The " + f.displayNameCap() + (partner != null ? " and " + partner.displayNameCap() : "") + " family");
            t.put("rows", rows);
            trees.add(t);
            if (trees.size() >= 24) break;
        }
        c.put("trees", trees);
        ListTag walking = new ListTag(), handed = new ListTag();
        for (VillageFolkEntity f : folk) {
            String court = Courtship.cardLine(f);
            if (court != null && f.lines().courting != null && f.getUUID().compareTo(f.lines().courting) < 0)
                walking.add(StringTag.valueOf(f.displayNameCap() + ": " + court.substring(0, 1).toLowerCase(Locale.ROOT) + court.substring(1)));
            String down = cardLine(f);
            if (down != null) handed.add(StringTag.valueOf(f.displayNameCap() + ": " + down));
        }
        c.put("courting", walking);
        c.put("handed", handed);
        ListTag news = new ListTag();
        for (Villages.News n : Villages.news(village)) {
            String s = n.text();
            if (s.contains(" wed") || s.contains("walking out") || s.contains("had a child") || s.contains("had twins") || s.contains("grew up")
                    || s.contains("took on") || s.contains("house now") || s.contains("journeyman") || s.contains("master ")
                    || s.contains("frail") || s.contains("are together now")) {
                news.add(StringTag.valueOf("Day " + (n.day() + 1) + ": " + s));
                if (news.size() >= 14) break;
            }
        }
        c.put("news", news);
        return c;
    }

    private static boolean hasParentHere(VillageFolkEntity f, Map<UUID, VillageFolkEntity> byId) {
        for (UUID p : f.parentIds()) if (byId.containsKey(p)) return true;
        if (f.life().parents().isEmpty()) return false;
        for (VillageFolkEntity o : byId.values()) if ((" and " + f.life().parents() + " and ").contains(" and " + o.displayNameCap() + " and ")) return true;
        return false;
    }

    private static List<VillageFolkEntity> kidsOf(VillageFolkEntity a, @Nullable VillageFolkEntity b, List<VillageFolkEntity> folk) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (VillageFolkEntity c : folk) {
            if (c == a || c == b) continue;
            boolean ofA = c.parentIds().contains(a.getUUID()) || (" and " + c.life().parents() + " and ").contains(" and " + a.displayNameCap() + " and ");
            boolean ofB = b != null && (c.parentIds().contains(b.getUUID()) || (" and " + c.life().parents() + " and ").contains(" and " + b.displayNameCap() + " and "));
            if (ofA || ofB) out.add(c);
        }
        out.sort((x, y) -> Long.compare(x.bornDay(), y.bornDay()));
        return out;
    }

    /** A couple and, under them, their children (and theirs), one row each: {depth, text, kind}. */
    private static void tree(ListTag rows, VillageFolkEntity a, @Nullable VillageFolkEntity b, List<VillageFolkEntity> folk,
                             Map<UUID, VillageFolkEntity> byId, Set<UUID> drawn, int depth) {
        if (depth > 5) return;
        drawn.add(a.getUUID());
        if (b != null) drawn.add(b.getUUID());
        CompoundTag row = new CompoundTag();
        row.putInt("depth", depth);
        row.putString("text", who(a) + (b != null ? "  ♥  " + who(b) : a.lines().courting != null ? "  (walking out with " + a.lines().courtingName + ")" : ""));
        row.putString("kind", a.isBaby() ? "child" : b != null ? "couple" : "single");
        rows.add(row);
        for (VillageFolkEntity c : kidsOf(a, b, folk)) {
            if (drawn.contains(c.getUUID())) continue;
            VillageFolkEntity cp = c.life().partner() == null ? null : byId.get(c.life().partner());
            if (cp != null && drawn.contains(cp.getUUID())) cp = null;
            tree(rows, c, cp, folk, byId, drawn, depth + 1);
        }
    }

    /** "Wren, 24, journeyman farmer" or "Pip, 12, at school": a name and what it is. */
    private static String who(VillageFolkEntity f) {
        StringBuilder sb = new StringBuilder(f.displayNameCap()).append(", ").append(f.ageYears());
        if (f.isBaby()) {
            sb.append(f.apprenticedTo() != StationTask.NONE ? ", learning " + f.apprenticedTo().title.toLowerCase(Locale.ROOT)
                + (f.lines().skill > 0 ? " (" + f.lines().skill + "%)" : "") : ", a child");
        } else if (f.lines().retired) {
            sb.append(", retired ").append(f.lines().retiredFrom.toLowerCase(Locale.ROOT));
        } else if (f.stationTask() != StationTask.NONE) {
            String rank = f.lines().rank.isEmpty() || !f.stationTask().name().equals(f.lines().trade) ? "" : f.lines().rank + " ";
            sb.append(", ").append(rank).append(f.stationTask().title.toLowerCase(Locale.ROOT));
        }
        if (f.isOld()) sb.append(", old");
        return sb.toString();
    }
}
