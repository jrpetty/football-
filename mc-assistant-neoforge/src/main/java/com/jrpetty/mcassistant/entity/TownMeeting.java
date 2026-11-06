package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [batchF] The town meeting, once a week: the evening before the day of rest (or, if that evening was taken
 * by a feast or rained off, the day of rest itself), the town gathers before the meeting hall — at the board
 * on the square, in a town with no hall yet — and the elder gives an account of the week: what the treasury
 * holds against last week, what went up, who was born and who died, what the petitions won and how the fund
 * stands, and what is to be built next. Then two or three of the folk have their say: a grumble about what
 * the town is short of, a question about when the next building is coming, a word for a petition still
 * wanting names; and the elder answers each. What was said goes into the chronicle (and so into the next
 * morning's gazette), and everybody who came goes home the better for knowing how the town stands.
 * It is one of the village's gatherings (Assemblies): the bell, the crowd in its rows, the lines in turn.
 */
public final class TownMeeting {

    private TownMeeting() {}

    /** The lines the elder and the folk had at the last meeting, by town (the tests). */
    private static final java.util.Map<UUID, List<String>> SAID = new java.util.concurrent.ConcurrentHashMap<>();
    /** Who of the folk had their say at it, by town. */
    private static final java.util.Map<UUID, List<String>> ASKED = new java.util.concurrent.ConcurrentHashMap<>();

    public static void resetForTests() {
        SAID.clear();
        ASKED.clear();
    }

    /** Is the town's meeting due this evening: the eve of its day of rest or the day itself, and none held these five days? */
    static boolean due(UUID village, long day) {
        if (Villages.headcount(village) < 4) return false;
        boolean eve = Math.floorMod(day + 1 + village.hashCode() + 3, 7) == 0;
        boolean rest = Math.floorMod(day + village.hashCode() + 3, 7) == 0;
        if (!eve && !rest) return false;
        long last = Civics.town(village).getLong("meetingDay") - 1;      // (kept one ahead: nought is never)
        return last < 0 || day - last >= 5;
    }

    /** The day of the next meeting (the eve of the next day of rest), from today. */
    static long next(UUID village, long day) {
        for (long d = day; d < day + 8; d++) if (Math.floorMod(d + 1 + village.hashCode() + 3, 7) == 0) return d;
        return day + 7;
    }

    /** Where it is held: before the meeting hall's door, the crowd out in front of it; else before the board. */
    static Assemblies.Assembly assembly(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Ledger.Building hall = Villages.builtStructure(id, "hall");
        BlockPos focus = null;
        Direction audience = null;
        if (hall != null && level.isLoaded(hall.anchor())) {
            BlockPos door = TownLife.fittings(hall).door();
            audience = hall.facing().getOpposite();
            if (door != null) focus = door.relative(audience, 2);
        }
        if (focus == null) {
            focus = VillageBoards.lectern(id);
            audience = VillageBoards.facingOf(id);
        }
        if (focus == null) focus = v.centre();
        if (audience == null) audience = Direction.SOUTH;
        return new Assemblies.Assembly(id, Assemblies.Kind.MEETING, "", day, focus, audience, Assemblies.Layout.ARC);
    }

    // ------------------------------------------------------------------ what is said

    /** The week, as the chronicle has it: what went up, who was born, who died (each as the chronicle words it). */
    record Week(List<String> built, List<String> born, List<String> died) {}

    static Week week(UUID village, long day) {
        List<String> built = new ArrayList<>(), born = new ArrayList<>(), died = new ArrayList<>();
        for (Chronicle.Entry e : Chronicle.of(village)) {
            if (e.day() < day - 6 || e.day() > day) continue;
            String low = e.text().toLowerCase(Locale.ROOT);
            if (low.contains("had a child") || low.contains(" had twins") || low.contains(" was born")) born.add(e.text());
            else if (low.contains(" died") || low.contains(" was lost")) died.add(e.text());
            else if ((low.endsWith("went up") || low.contains(" was opened")) && !low.startsWith("a new house")) built.add(e.text());
        }
        return new Week(built, born, died);
    }

    /** The account and the questions (Assemblies.script). */
    static void script(ServerLevel level, Assemblies.Assembly a, List<Assemblies.Line> s, RandomSource r) {
        UUID id = a.village;
        long day = a.day;
        CompoundTag t = Civics.town(id);
        List<String> said = new ArrayList<>();
        SAID.put(id, said);
        String name = Villages.name(id);
        line(s, said, null, "Friends — the town meeting. Here is how " + name + "'s week went.", '~');
        int coins = Ledger.coins(id);
        String treasury = "The treasury holds " + coins + (coins == 1 ? " coin" : " coins");
        if (t.contains("meetingCoins")) {
            int was = t.getInt("meetingCoins");
            treasury += coins > was ? ": up " + (coins - was) + " on last week." : coins < was ? ": down " + (was - coins) + " on last week." : ", as it did last week.";
        } else {
            treasury += ".";
        }
        line(s, said, null, treasury, coins >= t.getInt("meetingCoins") ? '!' : '?');
        Week w = week(id, day);
        if (w.built().isEmpty()) {
            String building = Villages.nextProject(id);
            line(s, said, null, "Nothing new went up this week" + (building == null ? "." : "; the builders are on " + Villages.spoken(building) + "."), '?');
        } else {
            List<String> what = new ArrayList<>();
            for (String b : w.built()) what.add(b.replace(" went up", "").replace(" was opened", ""));
            line(s, said, null, "This week " + Civics.names(what.subList(0, Math.min(3, what.size()))) + " went up."
                + (what.size() > 3 ? " And more besides." : ""), '!');
        }
        if (!w.born().isEmpty()) line(s, said, null, (w.born().size() == 1 ? "Good news this week: " : w.born().size() + " births this week; the first: ")
            + w.born().get(0) + ".", '!');
        if (!w.died().isEmpty()) line(s, said, null, "And we lost " + (w.died().size() == 1 ? "one of our own: " : w.died().size() + " of our own: ")
            + w.died().get(0) + ". We remember them.", '~');
        List<String> won = new ArrayList<>();
        for (CompoundTag p : Petitions.in(id, Petitions.DONE)) if (day - p.getLong("done") <= 7) won.add(p.getString("words"));
        if (!won.isEmpty()) line(s, said, null, "Your petitions won us " + Civics.names(won) + ".", '!');
        String fund = PublicFund.meetingLine(id);
        if (fund != null) line(s, said, null, fund, '?');
        List<String> next = new ArrayList<>();
        for (String p : Villages.projectsWanted(id)) {
            if (next.size() >= 2) break;
            String sp = Villages.spoken(p);
            if (!next.contains(sp)) next.add(sp);
        }
        line(s, said, null, next.isEmpty() ? "Next — nothing to build just now; every building we want stands." : "Next we build " + String.join(", then ", next) + ".", '?');
        questions(level, a, s, said, r, next);
        line(s, said, null, FolkTalk.pick(r, "That's the week. Thank you, all.", "That's all for this week. Goodnight, everyone."), ' ');
    }

    private static void line(List<Assemblies.Line> s, List<String> said, @Nullable UUID by, String text, char react) {
        s.add(new Assemblies.Line(by, text, react, () -> said.add(text)));
    }

    /**
     * Two or three of the folk have their say, and the elder answers: a grumble from the most put out of them
     * (what the town is short of), a question from a curious one (when the next building comes), and a word
     * for an open petition from one who signed it.
     */
    static void questions(ServerLevel level, Assemblies.Assembly a, List<Assemblies.Line> s, List<String> said, RandomSource r, List<String> next) {
        UUID id = a.village;
        List<VillageFolkEntity> here = new ArrayList<>();
        for (VillageFolkEntity f : Civics.grown(id)) {
            if (f.getUUID().equals(a.host) || f.isSleeping() || f.blockPosition().distSqr(a.focus) > 96 * 96) continue;
            here.add(f);
        }
        List<String> askers = new ArrayList<>();
        ASKED.put(id, askers);
        if (here.isEmpty()) return;
        List<UUID> asked = new ArrayList<>();
        // A grumble: the lowest in spirits (a grump first), about what the town is short of.
        Contentment.View view = Contentment.of(level, id);
        VillageFolkEntity low = null;
        for (VillageFolkEntity f : here) {
            int m = f.persona().mood() - (f.life().has(Social.Trait.GRUMPY) ? 20 : 0);
            if (low == null || m < low.persona().mood() - (low.life().has(Social.Trait.GRUMPY) ? 20 : 0)) low = f;
        }
        List<String> shorts = new ArrayList<>();
        for (Villages.Need n : Villages.needs(level, id)) {
            if (n.task() == Villages.Task.BUILD || n.task() == Villages.Task.NONE) continue;
            shorts.add(n.what());
            if (shorts.size() >= 2) break;
        }
        if (low != null && (!view.bad().isEmpty() || !shorts.isEmpty())) {
            String gripe = !view.bad().isEmpty() ? view.bad().get(0) : "we're short of " + shorts.get(0);
            line(s, said, low.getUUID(), FolkTalk.pick(r, "What about this: " + gripe + "? When's somebody going to see to it?",
                "I'll say it if nobody else will — " + gripe + "!"), '?');
            String answer = gripe.contains("the war") ? WarAndPeace.meetingAnswer(id)        // [war-peace] the war, answered
                : gripe.contains("eat") ? "The farmers and the fishers are at it; bring in what you can spare."
                : gripe.contains("bed") ? "A new house is on the list. It goes up as soon as the timber's in."
                : gripe.contains("wage") ? "The treasury's thin. Market day will help, and so will every coin that comes in."
                : gripe.contains("short of") ? "The gatherers are on it. Bring in what you can to the stores."
                : "It's heard. The council will take it up at its sitting.";
            line(s, said, null, answer, '?');
            asked.add(low.getUUID());
            askers.add(low.displayNameCap() + " (" + gripe + ")");
        }
        // A question from a curious one: when the next building is coming.
        VillageFolkEntity curious = null;
        for (VillageFolkEntity f : here) {
            if (asked.contains(f.getUUID())) continue;
            if (curious == null || f.life().has(Social.Trait.CURIOUS) && !curious.life().has(Social.Trait.CURIOUS)) curious = f;
        }
        if (curious != null && !next.isEmpty()) {
            String nextOne = next.get(0);
            line(s, said, curious.getUUID(), "When will we have " + nextOne + "?", '?');
            String p = Villages.nextProject(id);
            line(s, said, null, p == null ? "When we have the makings of it. Every load to the stores brings it nearer."
                : "It's " + Villages.whyBuild(id, p) + ". As soon as the stores run to it.", '?');
            asked.add(curious.getUUID());
            askers.add(curious.displayNameCap() + " (when " + nextOne + " is coming)");
        }
        // A word for an open petition, from one who signed it.
        for (CompoundTag p : Petitions.in(id, Petitions.OPEN)) {
            VillageFolkEntity signer = null;
            for (VillageFolkEntity f : here) if (!asked.contains(f.getUUID()) && Petitions.hasSigned(p, f.getUUID())) { signer = f; break; }
            if (signer == null) continue;
            int need = Petitions.needed(id), have = Petitions.signed(p);
            line(s, said, signer.getUUID(), "And our petition for " + p.getString("words") + "?", '?');
            line(s, said, null, have >= need ? "It has its names. It goes before the council at its sitting."
                : "It has " + have + " of the " + need + " names it wants. Sign it at the board if you're for it.", '?');
            askers.add(signer.displayNameCap() + " (the petition for " + p.getString("words") + ")");
            break;
        }
    }

    /** At its close (Assemblies.close): the chronicle's line, the books' record, and the lift for all who came. */
    static void held(ServerLevel level, Assemblies.Assembly a) {
        UUID id = a.village;
        long day = Civics.day(level);
        CompoundTag t = Civics.town(id);
        int coins = Ledger.coins(id);
        int was = t.contains("meetingCoins") ? t.getInt("meetingCoins") : coins;
        Week w = week(id, day);
        List<String> parts = new ArrayList<>();
        parts.add("the treasury at " + coins + " coins" + (coins > was ? " (up " + (coins - was) + ")" : coins < was ? " (down " + (was - coins) + ")" : ""));
        parts.add(w.built().size() + (w.built().size() == 1 ? " new building" : " new buildings"));
        if (!w.born().isEmpty()) parts.add(w.born().size() + " born");
        if (!w.died().isEmpty()) parts.add(w.died().size() + " died");
        List<String> said = SAID.getOrDefault(id, List.of());
        List<String> askers = ASKED.getOrDefault(id, List.of());
        String summary = "the town meeting was held: " + String.join(", ", parts) + "; " + a.seated.size() + " came"
            + (askers.isEmpty() ? "" : ", and " + Civics.names(askers) + " had their say");
        Villages.tell(id, day, summary);
        t.putLong("meetingDay", day + 1);
        t.putInt("meetingCoins", coins);
        t.putInt("meetingCame", a.seated.size());
        ListTag log = Civics.list(t, "meetings");
        CompoundTag one = new CompoundTag();
        one.putLong("day", day);
        one.putString("summary", summary);
        ListTag lines = new ListTag();
        for (String l : said) lines.add(StringTag.valueOf(l));
        one.put("said", lines);
        log.add(one);
        while (log.size() > 4) log.remove(0);
        Civics.changed();
        for (UUID u : a.seated.keySet()) {
            if (level.getEntity(u) instanceof VillageFolkEntity f) Civics.glad(f, Civics.MEETING, day);
        }
    }

    // ------------------------------------------------------------------ where the player sees it

    static List<String> board(ServerLevel level, UUID village) {
        long day = Civics.day(level);
        long next = next(village, day);
        String when = next == day ? "this evening" : next == day + 1 ? "tomorrow evening" : "in " + (next - day) + " days";
        return List.of("RM|The town meeting: " + when + (Villages.builtStructure(village, "hall") != null ? ", before the meeting hall." : ", at the board."));
    }

    static List<String> book(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        ListTag log = Civics.list(Civics.town(village), "meetings");
        for (int i = log.size() - 1; i >= 0; i--) {
            CompoundTag c = log.getCompound(i);
            out.add("Day " + (c.getLong("day") + 1) + ": " + Civics.cap(c.getString("summary")) + ".");
        }
        if (out.isEmpty()) out.add("No town meeting held yet; the next is " + (next(village, Civics.day(level)) - Civics.day(level) == 0 ? "this evening."
            : "in " + (next(village, Civics.day(level)) - Civics.day(level)) + " days."));
        return out;
    }

    // ------------------------------------------------------------------ the tests

    /** Tests: the lines said at the last meeting, as they were said. */
    public static List<String> saidForTests(UUID village) {
        return new ArrayList<>(SAID.getOrDefault(village, List.of()));
    }

    /** Tests: is the meeting due on this day? */
    public static boolean dueForTests(UUID village, long day) {
        return due(village, day);
    }

    /** Tests: the lines the meeting would have now, with who says each ("Ash: When will we have...?", "(elder) ..."). */
    public static List<String> scriptForTests(ServerLevel level, Villages.Village v) {
        Assemblies.Assembly a = assembly(level, v, Civics.day(level));
        a.host = Villages.elder(v.id());
        List<Assemblies.Line> s = new ArrayList<>();
        script(level, a, s, level.getRandom());
        List<String> out = new ArrayList<>();
        for (Assemblies.Line l : s) {
            String who = "(elder)";
            if (l.by() != null && level.getEntity(l.by()) instanceof VillageFolkEntity f) who = f.displayNameCap() + ":";
            out.add(who + " " + l.text());
        }
        return out;
    }

    /** Tests: the last meeting's record in the books (its summary), or "". */
    public static String lastSummaryForTests(UUID village) {
        ListTag log = Civics.list(Civics.town(village), "meetings");
        return log.isEmpty() ? "" : log.getCompound(log.size() - 1).getString("summary");
    }
}
