package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * [featfolk] A trade handed down: apprentice, journeyman, master.
 *
 * <p>A child old enough is apprenticed of a morning to a master of a trade (a parent at work first: VillageFolkEntity's
 * apprentice/chooseMentor) and follows it about its work, at its side, having a go. Every ten seconds it is there its
 * skill rises, by more the higher its master's level and the better its master teaches: a master of the rank teaches
 * best, a master with a trade knack of its own a little better ({@link #beside}). Grown up, a child that has come far
 * enough ({@link #QUALIFY}) is a journeyman of the trade at once, and the further it came the more of the trade it
 * starts with; one that has not stays its master's apprentice a while longer, at work beside it, and qualifies when it
 * has ({@link #daily}). A journeyman is a master once it reaches {@link #MASTER_LEVEL} at the trade.
 *
 * <p>A master passes on its specialisation: the day its apprentice qualifies, one of the master's own knacks for the
 * trade (FolkSkills) is the apprentice's too, as the first knack it has. The board's Apprentices page has every master
 * with its apprentices, how far on each is, and the line of who taught whom.
 */
public final class Apprenticeship {

    private Apprenticeship() {}

    /** The skill an apprentice qualifies at, as a journeyman. */
    public static final int QUALIFY = 60;
    /** A journeyman's level at its trade to be a master of it. */
    public static final int MASTER_LEVEL = 12;
    /** How often, in ticks, a morning at the master's side counts. */
    static final int LESSON_TICKS = 200;

    public static final String APPRENTICE = "apprentice", JOURNEYMAN = "journeyman", MASTER = "master";

    // ------------------------------------------------------------------ a morning at the master's side

    /**
     * A child at its master's side at work (VillageFolkEntity.apprentice): every ten seconds its skill rises; it watches,
     * has a go, and now and then the two of them have a word about it.
     */
    public static void beside(VillageFolkEntity child, VillageFolkEntity master) {
        FolkLines l = child.lines();
        StationTask trade = master.stationTask();
        if (trade == StationTask.NONE) return;
        if (!master.getUUID().equals(l.master) || !trade.name().equals(l.trade)) {
            if (!trade.name().equals(l.trade)) l.skill = l.skill / 2;           // a new trade: half of what it knew carries over
            l.master = master.getUUID();
            l.masterName = master.displayNameCap();
            l.trade = trade.name();
        }
        if (l.rank.isEmpty()) l.rank = APPRENTICE;
        if (child.tickCount % LESSON_TICKS == 0) {
            l.skill = Math.min(100, l.skill + gain(master));
            FolkAction.play(child, FolkAction.Act.WATCH, 60);
            FolkAction.play(master, FolkAction.Act.TEACH, 40);
        }
        RandomSource r = child.getRandom();
        if (r.nextInt(40) == 0 && child.level().getNearestPlayer(child, 16.0) != null) {
            String t = trade.title.toLowerCase(Locale.ROOT);
            FolkTalk.speak(child, FolkTalk.pick(r, "Like this?", "Can I have a go?", "Why do you do it that way?",
                "I'm going to be a " + t + " too.", "My arms ache. Is that normal?", "Show me again. Slower.", "I did it. Mostly."));
            master.sayLater(FolkTalk.pick(r, "That's it. Gently now.", "Watch my hands.", "You'll get the knack.", "Not bad at all.",
                "Slower. It isn't going anywhere.", "Again. And mean it this time.",
                "Better than I was at your age. Don't tell anyone."), 40);
        }
    }

    /** What one lesson is worth: one, a level in five more, two more from a master of the rank, one more for a knack. */
    static int gain(VillageFolkEntity master) {
        int g = 1 + master.veteranLevel() / 5;
        if (MASTER.equals(master.lines().rank)) g += 2;
        if (hasTradeKnack(master, master.stationTask())) g += 1;
        return g;
    }

    private static boolean hasTradeKnack(VillageFolkEntity f, StationTask t) {
        for (FolkSkills.Chosen c : f.knacks().chosen()) {
            if ((c.knack().family == FolkSkills.Family.TRADE || c.knack().family == FolkSkills.Family.MASTER) && c.knack().trades.contains(t)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ coming of age

    /**
     * Grown up, at work at the trade it learned ({@code trade}, VillageFolkEntity.childhood): far enough on, it is a
     * journeyman now (with its master's specialisation, and the more of the trade the further it came); else still
     * its master's apprentice. Returns the words for the news ("a journeyman farmer under Hale"), or "".
     */
    public static String cameOfAge(ServerLevel level, VillageFolkEntity f, StationTask trade, long day) {
        FolkLines l = f.lines();
        if (trade == StationTask.NONE || l.trade.isEmpty() || !trade.name().equals(l.trade)) return "";
        // What the years at its master's side are worth: up to a few more levels' knack than the plain apprenticeship's.
        f.awardXp(l.skill * 4);
        if (l.skill >= QUALIFY) {
            qualify(level, f, day, false);
            return "a journeyman " + trade.title.toLowerCase(Locale.ROOT) + ", as " + l.masterName + " taught them";
        }
        l.rank = APPRENTICE;
        return "still " + l.masterName + "'s apprentice at it";
    }

    /** Qualified: a journeyman, the master's knack for the trade passed on, said aloud by both. */
    static void qualify(ServerLevel level, VillageFolkEntity f, long day, boolean atWork) {
        FolkLines l = f.lines();
        l.rank = JOURNEYMAN;
        l.qualifiedDay = day;
        l.skill = Math.max(l.skill, QUALIFY);
        StationTask trade = trade(l);
        String t = trade.title.toLowerCase(Locale.ROOT);
        VillageFolkEntity master = l.master == null ? null : level.getEntity(l.master) instanceof VillageFolkEntity m && m.isAlive() ? m : null;
        // The master's specialisation, passed on: one of its knacks for the trade the journeyman has not got.
        if (master != null) {
            for (FolkSkills.Chosen c : master.knacks().chosen()) {
                FolkSkills.Knack k = c.knack();
                if (k.family != FolkSkills.Family.TRADE || !k.trades.contains(trade) || f.knacks().has(k)) continue;
                if (FolkSkills.grant(level, f, k)) {
                    l.taught = k.key;
                    f.persona().remember(day, master.displayNameCap() + " taught me " + k.title, 6);
                }
                break;
            }
            if (!master.lines().pupils.contains(f.displayNameCap())) master.lines().pupils.add(f.displayNameCap());
            master.persona().remember(day, "I brought " + f.displayNameCap() + " on as a " + t, 6);
            master.life().feel(f.getUUID(), f.displayNameCap(), 8);
            RandomSource r = master.getRandom();
            master.sayLater(FolkTalk.pick(r, "That's a journeyman " + t + " stood there. Mine.", "Nothing more I can shout at you about. Well. Not much.",
                "You'll do. Don't let it go to your head.", "Took you long enough. Well done.", "Better than I was. Still not as good as me.",
                "Off you go, then. I'll want to see your work."), 50);
        }
        f.persona().remember(day, "I qualified as a journeyman " + t + (l.masterName.isEmpty() ? "" : " under " + l.masterName), 7);
        RandomSource r = f.getRandom();
        FolkTalk.speak(f, FolkTalk.pick(r, "A journeyman " + t + ". It has a ring to it.", "Qualified. My hands knew before I did.",
            "That's me qualified. " + (l.masterName.isEmpty() ? "" : l.masterName + " can stop sighing now."), "Journeyman. Next stop: master. Eventually.",
            "I know my trade now. Mostly.", "All those mornings. Worth it."));
        FolkAction.play(f, FolkAction.Act.CHEER, 40);
        FolkAction.mark(f, FolkAction.Mark.STAR);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, f.getX(), f.getY() + 2.1, f.getZ(), 6, 0.4, 0.3, 0.4, 0.0);
        UUID village = f.ownerId();
        if (village != null && atWork) Villages.tell(village, day, f.displayNameCap() + " qualified as a journeyman " + t
            + (l.masterName.isEmpty() ? "" : " under " + l.masterName));
    }

    private static StationTask trade(FolkLines l) {
        try {
            return StationTask.valueOf(l.trade);
        } catch (IllegalArgumentException e) {
            return StationTask.NONE;
        }
    }

    // ------------------------------------------------------------------ once a day

    /**
     * A grown apprentice at work beside its master comes on (and qualifies when it has); a journeyman who has reached
     * the master's level at its trade is a master.
     */
    public static void daily(ServerLevel level, VillageFolkEntity f, long day) {
        if (f.isBaby() || f.lines().retired()) return;
        FolkLines l = f.lines();
        StationTask trade = trade(l);
        if (l.rank.isEmpty()) {
            // A folk already a master hand at its trade before the town kept books on it is a master of it.
            if (f.stationTask() != StationTask.NONE && f.veteranLevel() >= MASTER_LEVEL * 2) {
                l.rank = MASTER;
                l.trade = f.stationTask().name();
            }
            return;
        }
        if (trade != f.stationTask()) return;                          // gone to another trade: its rank in this one waits
        if (APPRENTICE.equals(l.rank)) {
            VillageFolkEntity master = l.master == null ? null : level.getEntity(l.master) instanceof VillageFolkEntity m && m.isAlive() ? m : null;
            int g = master != null && master.stationTask() == trade ? 6 + gain(master) * 2 : 4;   // with its master, else on its own
            l.skill = Math.min(100, l.skill + g);
            if (l.skill >= QUALIFY) qualify(level, f, day, true);
        } else if (JOURNEYMAN.equals(l.rank) && f.veteranLevel() >= MASTER_LEVEL) {
            l.rank = MASTER;
            String t = trade.title.toLowerCase(Locale.ROOT);
            f.persona().remember(day, "I became a master " + t, 8);
            UUID village = f.ownerId();
            if (village != null) Villages.tell(village, day, f.displayNameCap() + " is a master " + t + " now"
                + (l.masterName.isEmpty() ? "" : ", as " + l.masterName + " was before them"));
            RandomSource r = f.getRandom();
            FolkTalk.speak(f, FolkTalk.pick(r, "Master " + t + ". I'll be insufferable for a week.", "A master of the trade. Who'd have thought it.",
                "Master. Somebody fetch me an apprentice to shout at.", "I'm a master " + t + " now. My back feels every year of it.",
                "Master. " + (l.masterName.isEmpty() ? "Took long enough." : l.masterName + " would have something to say about that."),
                "That's master to you. Kindly."));
            FolkAction.mark(f, FolkAction.Mark.STAR);
        }
    }

    // ------------------------------------------------------------------ for the folk's card and the board

    /** Its place in its trade's line, for its card ("Journeyman farmer, under Hale; taught Steady Hands"), or null. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        FolkLines l = f.lines();
        if (l.rank.isEmpty() || l.trade.isEmpty()) return null;
        String t = trade(l).title.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(Witness.capital(l.rank) + " " + t);
        if (APPRENTICE.equals(l.rank)) sb.append(", ").append(l.skill).append("% of the way to journeyman");
        if (!l.masterName.isEmpty()) sb.append(MASTER.equals(l.rank) || JOURNEYMAN.equals(l.rank) ? ", taught by " : ", under ").append(l.masterName);
        FolkSkills.Knack k = FolkSkills.Knack.byKey(l.taught);
        if (k != null) sb.append("; learned ").append(k.title).append(" from them");
        if (!l.pupils.isEmpty()) sb.append("; has brought on ").append(String.join(", ", l.pupils));
        return sb.toString();
    }

    /** The Apprentices page (ApprenticesPage): each trade's masters and journeymen, their apprentices, and who taught whom. */
    public static CompoundTag report(UUID village) {
        CompoundTag c = new CompoundTag();
        Map<String, List<VillageFolkEntity>> byTrade = new LinkedHashMap<>();
        int apprentices = 0, journeymen = 0, masters = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isShowcase() || f.lines().rank.isEmpty() || f.lines().trade.isEmpty()) continue;
            byTrade.computeIfAbsent(f.lines().trade, k -> new ArrayList<>()).add(f);
            switch (f.lines().rank) {
                case APPRENTICE -> apprentices++;
                case JOURNEYMAN -> journeymen++;
                default -> masters++;
            }
        }
        c.putString("summary", masters + (masters == 1 ? " master, " : " masters, ") + journeymen + (journeymen == 1 ? " journeyman, " : " journeymen, ")
            + apprentices + (apprentices == 1 ? " apprentice" : " apprentices") + " in " + byTrade.size() + (byTrade.size() == 1 ? " trade" : " trades"));
        ListTag trades = new ListTag();
        for (Map.Entry<String, List<VillageFolkEntity>> e : byTrade.entrySet()) {
            StationTask t;
            try { t = StationTask.valueOf(e.getKey()); } catch (IllegalArgumentException ex) { continue; }
            CompoundTag tr = new CompoundTag();
            tr.putString("trade", t.title);
            ListTag rows = new ListTag();
            List<VillageFolkEntity> folk = e.getValue();
            folk.sort((x, y) -> Integer.compare(order(y), order(x)));
            for (VillageFolkEntity f : folk) {
                FolkLines l = f.lines();
                CompoundTag r = new CompoundTag();
                r.putString("name", f.displayNameCap());
                r.putString("rank", l.rank);
                r.putInt("level", f.isBaby() ? 0 : f.tradeLevel(t));
                r.putInt("skill", l.skill);
                r.putBoolean("child", f.isBaby());
                r.putString("master", l.masterName);
                FolkSkills.Knack k = FolkSkills.Knack.byKey(l.taught);
                r.putString("taught", k == null ? "" : k.title);
                List<String> knacks = new ArrayList<>();
                for (FolkSkills.Chosen ch : f.knacks().chosen()) if (ch.knack().trades.contains(t)) knacks.add(ch.knack().title);
                r.putString("knacks", String.join(", ", knacks));
                r.putString("pupils", String.join(", ", l.pupils));
                rows.add(r);
            }
            tr.put("rows", rows);
            trades.add(tr);
        }
        c.put("trades", trades);
        // The lines of who taught whom: each master with nobody over it in the town, down through its pupils.
        ListTag lines = new ListTag();
        Map<String, VillageFolkEntity> byName = new LinkedHashMap<>();
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f) byName.put(f.displayNameCap(), f);
        for (VillageFolkEntity f : byName.values()) {
            if (f.lines().pupils.isEmpty() || byName.containsKey(f.lines().masterName)) continue;
            StringBuilder sb = new StringBuilder(f.displayNameCap());
            descend(sb, f, byName, 0);
            lines.add(StringTag.valueOf(sb.toString()));
        }
        c.put("lines", lines);
        return c;
    }

    private static void descend(StringBuilder sb, VillageFolkEntity f, Map<String, VillageFolkEntity> byName, int depth) {
        if (depth > 6 || f.lines().pupils.isEmpty()) return;
        sb.append(" taught ").append(String.join(" and ", f.lines().pupils));
        for (String p : f.lines().pupils) {
            VillageFolkEntity next = byName.get(p);
            if (next != null && !next.lines().pupils.isEmpty()) {
                sb.append("; ").append(p);
                descend(sb, next, byName, depth + 1);
            }
        }
    }

    private static int order(VillageFolkEntity f) {
        return switch (f.lines().rank) {
            case MASTER -> 300 + f.veteranLevel();
            case JOURNEYMAN -> 200 + f.veteranLevel();
            default -> f.lines().skill;
        };
    }

    // ------------------------------------------------------------------ tests

    /** Tests: as though it had spent so many mornings at this master's side. */
    public static void lessonsForTests(VillageFolkEntity child, VillageFolkEntity master, int lessons) {
        for (int i = 0; i < lessons; i++) {
            beside(child, master);
            child.lines().skill = Math.min(100, child.lines().skill + gain(master));
        }
    }
}
