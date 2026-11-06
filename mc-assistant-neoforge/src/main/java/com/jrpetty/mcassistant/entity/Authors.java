package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.LibraryRecords;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.MuseumRecords;
import com.jrpetty.mcassistant.village.Quill;
import com.jrpetty.mcassistant.village.Tales;
import com.jrpetty.mcassistant.village.Verse;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who writes the town's other books, and about what. [library]
 *
 * <p>The subjects are the town's own, found in its books: a wedding the chronicle tells of ("Cobb and Wick were
 * wed"), a death (the graves), a birth, the best day's harvest, the fishers' catch, a household's pet (its name and
 * whose bed it sleeps on), two old friends; its founding, its ages, its fires, its raids, its wars and its years; the
 * lives of its founders, its elder, its oldest and its best; what a folk has learned of its trade or its pastime (the
 * beekeeper's bees, a builder's houses, a gardener's flowers, the stargazer's sky, a walker's round of the town);
 * and, from the teacher, a storybook for the children. Each subject is written once.
 *
 * <p>The authors are folk with the nature or the pastime for it: readers, the curious, a folk that keeps a diary,
 * the musical for poems, the elder and the founders for histories, the teacher for the children; a life is best
 * written by somebody who loved its subject (its partner, its child, its friend), and a how-to book only by the folk
 * who has done the thing. A folk writes one book every few days at most, and the town one every other day.
 */
public final class Authors {

    private Authors() {}

    /** Days between one folk's books, at the least. */
    static final int AUTHOR_GAP = 3;

    /**
     * A book the town could write: what it is about (written once), its kind, how much it is worth writing, when it
     * happened, the folk it must be written by (a how-to's), the folk it is about (a life's, an elegy's), the folk
     * who would write it best, and the writing of it by a given author.
     */
    record Idea(String subject, String kind, int weight, long day, @Nullable VillageFolkEntity by, @Nullable UUID about,
                Set<UUID> close, Function<VillageFolkEntity, Quill.Book> write) {}

    private static final Pattern WED = Pattern.compile("^(.+?) and (.+?) were wed$");
    private static final Pattern CHILD = Pattern.compile("^(.+?) and (.+?) had a child, (.+)$");
    private static final Pattern AGED = Pattern.compile("aged (\\d+)");
    private static final Pattern WAR_ON = Pattern.compile("declared war on ([A-Z][A-Za-z]+)");
    private static final Pattern WAR_BY = Pattern.compile("^([A-Z][A-Za-z]+) declared war on us");

    // ------------------------------------------------------------------ the subjects

    /** Everything the town could write about now that it has not. */
    static List<Idea> ideas(TradeBooks.Ctx c, LibraryRecords.Shelf shelf) {
        List<Idea> out = new ArrayList<>();
        UUID id = c.v.id();
        String town = Villages.name(id);
        long day = c.day;
        // Weddings and births, out of the chronicle.
        for (Chronicle.Entry e : c.chronicle) {
            Matcher w = WED.matcher(e.text().trim());
            if (w.matches()) {
                String a = w.group(1), b = w.group(2);
                Map<String, String> m = words(c, e.day());
                m.put("A", a);
                m.put("B", b);
                VillageFolkEntity fa = named(c, a), fb = named(c, b);
                if (fa != null && fa.stationTask() != StationTask.NONE) m.put("tradeA", fa.stationTask().title.toLowerCase(Locale.ROOT));
                if (fb != null && fb.stationTask() != StationTask.NONE) m.put("tradeB", fb.stationTask().title.toLowerCase(Locale.ROOT));
                m.put("place", Villages.hasBuilt(id, "court") ? "the courtyard" : "the square");
                poem(out, shelf, c, "WEDDING", m, 8, e.day(), closeTo(c, a, b));
            }
            Matcher ch = CHILD.matcher(e.text().trim());
            if (ch.matches()) {
                Map<String, String> m = words(c, e.day());
                m.put("A", ch.group(3));
                m.put("parents", ch.group(1) + " and " + ch.group(2));
                m.put("parentA", ch.group(1));
                m.put("parentB", ch.group(2));
                poem(out, shelf, c, "BIRTH", m, 5, e.day(), closeTo(c, ch.group(1), ch.group(2)));
            }
        }
        // The dead.
        for (Ledger.Grave g : c.graves) {
            Map<String, String> m = words(c, g.died());
            m.put("A", g.name());
            m.put("tradeA", g.trade().toLowerCase(Locale.ROOT));
            m.put("trade", tradeKey(g.trade()));
            m.put("cause", cause(g.cause()));
            int age = ageAtDeath(c, g);
            if (age > 0) m.put("nwords", Quill.spelled(age));
            if (!g.partner().isEmpty()) m.put("kin", g.partner());
            List<String> kids = childrenOf(c, g.name());
            if (!kids.isEmpty()) m.put("children", Quill.list(kids));
            poem(out, shelf, c, "ELEGY", m, 8, g.died(), closeTo(c, g.name(), g.partner()));
        }
        // The best day's harvest, and the water.
        int[] farm = best(c, StationTask.FARM);
        VillageFolkEntity farmer = TradeBooks.master(c, StationTask.FARM);
        if (farm[0] >= 20 && farmer != null) {
            Map<String, String> m = words(c, farm[1]);
            m.put("A", farmer.displayNameCap());
            m.put("crop", topWord(c, StationTask.FARM, farm[1]));
            m.put("nwords", Quill.spelled(farm[0]));
            m.put("day", Integer.toString(farm[1]));
            Idea i = poemIdea(shelf, c, "HARVEST", m, 5, farm[1], Set.of());
            if (i != null && !shelf.wrote("poem:HARVEST:" + farmer.displayNameCap() + "|")) out.add(i);
        }
        VillageFolkEntity fisher = TradeBooks.master(c, StationTask.FISH);
        if (fisher != null) {
            long caught = 0;
            for (VillageFolkEntity f : c.folk) if (f.stationTask() == StationTask.FISH) caught += f.deedCount(AssistantEntity.Deed.FISH_CAUGHT);
            if (caught >= 10) {
                Map<String, String> m = words(c, day);
                m.put("A", fisher.displayNameCap());
                m.put("nwords", Quill.cap(Quill.spelled(caught)));
                poem(out, shelf, c, "SEA", m, 4, day, Set.of());
            }
        }
        // The households' pets.
        for (Map.Entry<String, String> e : Ledger.notes(id).entrySet()) {
            if (!e.getKey().startsWith("pet/") || e.getValue() == null) continue;
            String[] q = e.getValue().split("\\|", -1);
            if (q.length < 5) continue;
            VillageFolkEntity keeper = null;
            try {
                UUID k = UUID.fromString(q[4]);
                for (VillageFolkEntity f : c.folk) if (f.getUUID().equals(k)) keeper = f;
            } catch (RuntimeException ignored) { }
            if (keeper == null || q[2].isEmpty()) continue;
            Map<String, String> m = words(c, day);
            m.put("A", q[2]);
            m.put("B", keeper.displayNameCap());
            m.put("kind", q[1].equals("cat") ? "cat" : "dog");
            poem(out, shelf, c, "PET", m, 5, parse(q[3]), Set.of(keeper.getUUID()));
        }
        // Two old friends.
        for (VillageFolkEntity a : c.folk) {
            for (Social.Bond bond : a.life().friends()) {
                if (bond.affinity < Social.CLOSE || bond.name == null || bond.name.compareTo(a.displayNameCap()) <= 0) continue;
                if (bond.name.equals(a.life().partnerName())) continue;
                Map<String, String> m = words(c, day);
                m.put("A", a.displayNameCap());
                m.put("B", bond.name);
                poem(out, shelf, c, "FRIEND", m, 3, day, closeTo(c, a.displayNameCap(), bond.name));
                break;
            }
        }
        // The town itself.
        if (c.folk.size() >= 12) {
            Villages.Age age = Villages.ageOf(id);
            Map<String, String> m = words(c, day);
            m.put("nwords", Quill.number(Villages.headcount(id)));
            int founders = 0;
            for (VillageFolkEntity f : c.folk) if (f.persona().origin().equals("a founder of the village")) founders++;
            m.put("founders", founders >= 2 ? Quill.number(founders) : "a handful");
            List<String> built = new ArrayList<>();
            for (Ledger.Building b : Ledger.buildings(id)) {
                String s = b.structure();
                if (s.equals("house") || s.equals("house2") || s.equals("storage") || s.equals("colony") || s.equals("fortify")) continue;
                String w = Villages.spoken(s).replaceFirst("^the ", "");
                if (!built.contains(w) && !w.startsWith("a ")) built.add(w);
            }
            if (built.size() >= 2) {
                m.put("b1", built.get(0));
                m.put("b2", built.get(1));
            }
            m.put("age", age.label);
            String motto = Heraldry.motto(id);
            if (motto != null && !motto.isEmpty()) m.put("motto", motto);
            Map<String, String> sub = new HashMap<>(m);
            sub.put("A", age.name());
            Idea i = poemIdea(shelf, c, "TOWN", sub, 4, day, Set.of());
            if (i != null) out.add(i);
        }
        histories(out, c, shelf, town);
        lives(out, c, shelf, town);
        howTos(out, c, shelf, town);
        story(out, c, shelf, town);
        woven(out, c, shelf, town);                                         // [weave] the flood, the great fire, the lost below, the smugglers, the works, the auction
        return out;
    }

    // ------------------------------------------------------------------ [weave] the new events' books

    private static final Pattern FLOOD_OF = Pattern.compile("^the (great )?flood of day (\\d+)");
    private static final Pattern BURNT = Pattern.compile("^the fire burnt (\\d+) blocks? of (.+?):");
    private static final Pattern LOST_BELOW = Pattern.compile("^(.+?) was lost in the caves");

    /**
     * The town's new events, made books: a history of each flood and of a great fire (a dozen blocks or more burnt, and
     * the rebuilding after); the life of a cave dweller lost below; the tale of the smugglers once their story is over;
     * a poem for each great work opened; and a ballad of the auction's most famous sale.
     */
    private static void woven(List<Idea> out, TradeBooks.Ctx c, LibraryRecords.Shelf shelf, String town) {
        UUID id = c.v.id();
        long founded = Chronicle.foundedOn(id);
        List<String> founders = new ArrayList<>();
        for (VillageFolkEntity f : c.folk) if (f.persona().origin().equals("a founder of the village")) founders.add(f.displayNameCap());
        String motto = Heraldry.motto(id);
        String age = Villages.ageOf(id).label;
        int folk = Villages.headcount(id);
        for (Chronicle.Entry e : c.chronicle) {
            String low = e.text().toLowerCase(Locale.ROOT).trim();
            // A flood: from the water's coming up to the levee after.
            Matcher fl = FLOOD_OF.matcher(low);
            if (fl.find()) {
                List<Tales.Event> ev = new ArrayList<>();
                for (Chronicle.Entry x : c.chronicle) {
                    if (x.day() < e.day() - 1 || x.day() > e.day() + 12) continue;
                    String l = x.text().toLowerCase(Locale.ROOT);
                    if (!(l.contains("flood") || l.contains("river") || l.contains("levee") || l.contains("high ground") || l.contains("low ground")
                            || l.contains("rain"))) continue;
                    // "The great flood of day 45: the river came up..." told on its day as "the great flood: the river came up...".
                    ev.add(event(c, x.day(), x.text().replaceFirst("(?i)^(the (great )?flood) of day \\d+:", "$1:")));
                }
                if (ev.size() >= 2) history(out, shelf, c, new Tales.History("FLOOD", town, fl.group(2), ev, founders, age, folk,
                    motto == null ? "" : motto, founded), 8, e.day());
            }
            // A great fire: a dozen blocks or more of a building burnt, and the rebuilding after.
            Matcher bu = BURNT.matcher(low);
            if (bu.find() && Integer.parseInt(bu.group(1)) >= 12) {
                List<Tales.Event> ev = new ArrayList<>();
                for (Chronicle.Entry x : c.chronicle) {
                    if (x.day() < e.day() || x.day() > e.day() + 10) continue;
                    String l = x.text().toLowerCase(Locale.ROOT);
                    if (l.contains("fire") || l.contains("burnt") || l.contains("rebuilt") || l.contains("bucket") || l.contains("forges")
                            || l.contains("bell rang")) ev.add(event(c, x.day(), x.text().replaceFirst("(?i) after the fire of day \\d+", " after the fire")));
                }
                if (!ev.isEmpty()) history(out, shelf, c, new Tales.History("GREATFIRE", town, Long.toString(e.day() + 1), ev, founders, age, folk,
                    motto == null ? "" : motto, founded), 8, e.day());
            }
            // A cave dweller lost below: its life, whatever its age.
            Matcher lb = LOST_BELOW.matcher(e.text().trim());
            if (lb.find()) {
                String name = lb.group(1);
                for (Ledger.Grave g : c.graves) {
                    if (!g.name().equals(name) || shelf.wrote("life:" + name)) continue;
                    boolean dup = false;
                    for (Idea i : out) dup |= i.subject().equals("life:" + name);
                    if (dup) break;
                    int years = ageAtDeath(c, g);
                    Tales.Life l = new Tales.Life(g.name(), false, years, g.trade().toLowerCase(Locale.ROOT), 0, "", List.of(), "", "", "", false, "",
                        g.born(), g.parents(), g.partner(), childrenOf(c, g.name()), List.of(), List.of(), "", finds(id, g.name()), List.of(), g.died(),
                        g.cause(), "who went down into the caves for the town, and did not come home", mentions(c, g.name()));
                    out.add(new Idea("life:" + name, "LIFE", 6, g.died(), null, null, closeTo(c, g.name(), g.partner()),
                        f -> Tales.life(l, town, voice(f), knewAs(f, g.name(), g.partner(), c), c.day)));
                    break;
                }
            }
        }
        // The smugglers' story, once it is over: told from what was found out, step by step, and how it ended.
        for (QuestBook.Quest q : QuestBook.all()) {
            if (!id.equals(q.village) || !"story.smugglers".equals(q.script) || q.open() || q.state != QuestBook.State.DONE) continue;
            String subject = "history:SMUGGLERS:" + q.id;
            if (shelf.wrote(subject)) continue;
            List<Tales.Event> ev = new ArrayList<>();
            if (!q.offer.isEmpty()) ev.add(new Tales.Event(q.posted, "How it began", q.giverName + " came to " + q.playerName + " with it: \"" + q.offer + "\""));
            for (QuestBook.Step s : q.steps) {
                if (!s.done || s.note.isEmpty()) continue;
                ev.add(new Tales.Event(q.taken, s.chapter.isEmpty() ? "What was found out" : s.chapter, s.note));
            }
            if (!q.ending.isEmpty()) ev.add(new Tales.Event(q.finished, "How it ended", q.ending));
            if (ev.size() >= 3) history(out, shelf, c, new Tales.History("SMUGGLERS", town, Integer.toString(q.id), ev, founders, age, folk,
                motto == null ? "" : motto, founded), 8, q.finished);
        }
        // A great work opened: a poem for it.
        for (net.minecraft.nbt.Tag t : CivicRecord.list(CivicRecord.town(id), "worksDone")) {
            if (!(t instanceof net.minecraft.nbt.CompoundTag w)) continue;
            BigWorks.Work kind = BigWorks.Work.named(w.getString("kind"));
            Map<String, String> m = words(c, w.getLong("day"));
            m.put("A", kind == null ? w.getString("title") : kind.the);
            m.put("B", "day " + w.getLong("day"));
            if (w.getInt("hands") > 0) m.put("nwords", Quill.spelled(w.getInt("hands")));
            if (w.getInt("placed") > 0) m.put("stones", Quill.spelled(w.getInt("placed")));
            poem(out, shelf, c, "WORKS", m, 6, w.getLong("day"), Set.of());
        }
        // The auction's most famous sale: a ballad of it.
        String[] best = null;
        for (String[] p : Auctions.salesRows(id)) {
            if (p[3].isEmpty()) continue;
            int price = parseInt(p[2]);
            if (price >= 24 && (best == null || price > parseInt(best[2]))) best = p;
        }
        if (best != null) {
            Map<String, String> m = words(c, parse(best[0]));
            m.put("A", best[3]);
            m.put("B", JobMarket.a(best[1]));
            m.put("nwords", Quill.spelled(parseInt(best[2])));
            poem(out, shelf, c, "AUCTION", m, 5, parse(best[0]), Set.of());
        }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return 0; }
    }

    /** The words every poem may use: the season and the day. */
    private static Map<String, String> words(TradeBooks.Ctx c, long day) {
        Map<String, String> m = new HashMap<>();
        m.put("season", Seasons.season(c.v.id(), day).word);
        m.put("day", Long.toString(day));
        return m;
    }

    private static void poem(List<Idea> out, LibraryRecords.Shelf shelf, TradeBooks.Ctx c, String kind, Map<String, String> m, int weight, long day,
                             Set<UUID> close) {
        Idea i = poemIdea(shelf, c, kind, m, weight, day, close);
        if (i != null) out.add(i);
    }

    @Nullable
    private static Idea poemIdea(LibraryRecords.Shelf shelf, TradeBooks.Ctx c, String kind, Map<String, String> m, int weight, long day, Set<UUID> close) {
        Verse.Subject s = new Verse.Subject(kind, Villages.name(c.v.id()), m);
        String subject = Verse.subject(s);
        if (shelf.wrote(subject)) return null;
        return new Idea(subject, "POEM", weight, day, null, null, close, f -> Verse.write(s, voice(f), c.day));
    }

    // ------------------------------------------------------------------ histories

    private static void histories(List<Idea> out, TradeBooks.Ctx c, LibraryRecords.Shelf shelf, String town) {
        UUID id = c.v.id();
        long founded = Chronicle.foundedOn(id);
        List<String> founders = new ArrayList<>();
        for (VillageFolkEntity f : c.folk) if (f.persona().origin().equals("a founder of the village")) founders.add(f.displayNameCap());
        String motto = Heraldry.motto(id);
        String age = Villages.ageOf(id).label;
        int folk = Villages.headcount(id);
        // The founding: the first year, once the town is a week old.
        if (founded >= 0 && c.day - founded >= 7) {
            List<Tales.Event> ev = new ArrayList<>();
            for (Chronicle.Entry e : c.chronicle) {
                if (e.day() < founded || e.day() >= founded + TownCalendar.YEAR_DAYS) continue;
                if (Tales.weight(Tales.kindOf(e.text())) < 4) continue;
                ev.add(event(c, e.day(), e.text()));
            }
            if (ev.size() >= 4) history(out, shelf, c, new Tales.History("FOUNDING", town, "", trim(ev, 36), founders, age, folk, motto == null ? "" : motto, founded), 7, founded);
        }
        // The ages, once there is more than one.
        if (Villages.ageOf(id).ordinal() >= Villages.Age.STONE.ordinal()) {
            List<Tales.Event> ev = new ArrayList<>();
            for (Chronicle.Entry e : c.chronicle) {
                String k = Tales.kindOf(e.text());
                if (k.equals("AGE") || k.equals("FOUNDING") || k.equals("BUILDING") && !e.text().contains("house")) ev.add(event(c, e.day(), e.text()));
            }
            if (ev.size() >= 4) history(out, shelf, c, new Tales.History("AGES", town, Villages.ageOf(id).name(), trim(ev, 40), founders, age, folk,
                motto == null ? "" : motto, founded), 6, c.day);
        }
        // The fires.
        int fires = Annals.fires(id);
        if (fires >= 1) {
            List<Tales.Event> ev = new ArrayList<>();
            for (String f : c.fires) {
                try {
                    int colon = f.indexOf(':');
                    long d = Long.parseLong(f.substring(4, colon).trim()) - 1;
                    ev.add(event(c, d, f.substring(colon + 1).trim()));
                } catch (RuntimeException ignored) { }
            }
            if (!ev.isEmpty()) history(out, shelf, c, new Tales.History("FIRE", town, Integer.toString(fires), ev, founders, age, folk,
                motto == null ? "" : motto, founded), 7, ev.get(ev.size() - 1).day());
        }
        // The raids.
        List<Tales.Event> raids = new ArrayList<>();
        for (Chronicle.Entry e : c.chronicle) if (Tales.kindOf(e.text()).equals("RAID")) raids.add(event(c, e.day(), e.text()));
        if (!raids.isEmpty()) history(out, shelf, c, new Tales.History("RAID", town, Integer.toString(raids.size()), trim(raids, 20), founders, age,
            folk, motto == null ? "" : motto, founded), 7, raids.get(raids.size() - 1).day());
        // A war: its rival, and every line that names it.
        String rival = "";
        for (Chronicle.Entry e : c.chronicle) {
            Matcher m = WAR_ON.matcher(e.text());
            if (m.find()) rival = m.group(1);
            Matcher n = WAR_BY.matcher(e.text());
            if (n.find()) rival = n.group(1);
        }
        if (!rival.isEmpty()) {
            List<Tales.Event> ev = new ArrayList<>();
            for (Chronicle.Entry e : c.chronicle) if (e.text().contains(rival)) ev.add(event(c, e.day(), e.text()));
            if (ev.size() >= 2) history(out, shelf, c, new Tales.History("WAR", town, rival, trim(ev, 30), founders, age, folk,
                motto == null ? "" : motto, founded), 9, ev.get(ev.size() - 1).day());
        }
        // The last whole year.
        int year = Archive.yearOf(id, c.day) - 1;
        if (year >= 1 && founded >= 0) {
            long from = Archive.yearStart(founded, year), to = from + Archive.YEAR_DAYS - 1;
            List<Tales.Event> ev = new ArrayList<>();
            for (Chronicle.Entry e : c.chronicle) {
                if (e.day() < from || e.day() > to || Tales.weight(Tales.kindOf(e.text())) < 4) continue;
                ev.add(event(c, e.day(), e.text()));
            }
            if (ev.size() >= 4) history(out, shelf, c, new Tales.History("YEAR", town, Integer.toString(year), trim(ev, 30), founders, age, folk,
                motto == null ? "" : motto, founded), 4, to);
        }
    }

    /** The weightiest happenings, at most so many, in order. */
    private static List<Tales.Event> trim(List<Tales.Event> ev, int most) {
        if (ev.size() <= most) return ev;
        List<Tales.Event> sorted = new ArrayList<>(ev);
        sorted.sort((a, b) -> Tales.weight(Tales.kindOf(b.text())) - Tales.weight(Tales.kindOf(a.text())));
        Set<Tales.Event> keep = new LinkedHashSet<>(sorted.subList(0, most));
        List<Tales.Event> out = new ArrayList<>();
        for (Tales.Event e : ev) if (keep.contains(e)) out.add(e);
        return out;
    }

    /** A happening of the chronicle with when it fell in the town's year: "spring of the first year". */
    private static Tales.Event event(TradeBooks.Ctx c, long day, String text) {
        UUID id = c.v.id();
        String when = Seasons.season(id, day).word + " of the " + TownCalendar.ordinal(Seasons.year(id, day)) + " year";
        return new Tales.Event(day, when, text);
    }

    private static void history(List<Idea> out, LibraryRecords.Shelf shelf, TradeBooks.Ctx c, Tales.History h, int weight, long day) {
        String subject = "history:" + h.kind() + ":" + h.about();
        if (shelf.wrote(subject)) return;
        out.add(new Idea(subject, "HISTORY", weight, day, null, null, Set.of(), f -> Tales.history(h, voice(f), c.day)));
    }

    // ------------------------------------------------------------------ lives

    private static void lives(List<Idea> out, TradeBooks.Ctx c, LibraryRecords.Shelf shelf, String town) {
        UUID id = c.v.id();
        VillageFolkEntity oldest = null;
        for (VillageFolkEntity f : c.folk) {
            String why = "";
            if (f.isElder()) why = "the elder of " + town;
            else if (f.persona().origin().equals("a founder of the village")) why = "one of the founders of " + town;
            else if (f.stationTask() != StationTask.NONE && f.tradeLevel(f.stationTask()) >= 20) why = "the finest " + f.stationTask().title.toLowerCase(Locale.ROOT) + " " + town + " has known";
            if (oldest == null || f.ageYears() > oldest.ageYears()) oldest = f;
            if (!why.isEmpty()) life(out, shelf, c, town, f, why);
        }
        if (oldest != null && oldest.ageYears() >= VillageFolkEntity.OLD_AT) life(out, shelf, c, town, oldest, "the oldest of us, at " + oldest.ageYears());
        for (Ledger.Grave g : c.graves) {
            int age = ageAtDeath(c, g);
            if (age < VillageFolkEntity.OLD_AT || shelf.wrote("life:" + g.name())) continue;
            List<Tales.Event> mentions = mentions(c, g.name());
            Tales.Life l = new Tales.Life(g.name(), false, age, g.trade().toLowerCase(Locale.ROOT), 0, "", List.of(), "", "", "", false, "",
                g.born(), g.parents(), g.partner(), childrenOf(c, g.name()), List.of(), List.of(), "", finds(id, g.name()), List.of(), g.died(), g.cause(),
                "who lived to " + age, mentions);
            out.add(new Idea("life:" + g.name(), "LIFE", 5, g.died(), null, null, closeTo(c, g.name(), g.partner()),
                f -> Tales.life(l, town, voice(f), knewAs(f, g.name(), g.partner(), c), c.day)));
        }
    }

    private static void life(List<Idea> out, LibraryRecords.Shelf shelf, TradeBooks.Ctx c, String town, VillageFolkEntity s, String why) {
        String subject = "life:" + s.displayNameCap();
        if (shelf.wrote(subject)) return;
        for (Idea i : out) if (i.subject().equals(subject)) return;
        UUID id = c.v.id();
        Persona p = s.persona();
        StationTask t = s.stationTask();
        List<String> traits = new ArrayList<>();
        for (Social.Trait tr : s.life().traits()) traits.add(tr.label);
        String others = s.tradeLevels();
        if (t != StationTask.NONE) others = others.replaceFirst("^" + Pattern.quote(t.title.toLowerCase(Locale.ROOT)) + " \\d+(, )?", "");
        List<String> friends = new ArrayList<>();
        for (Social.Bond b : s.life().friends()) if (b.name != null && !b.name.isEmpty()) friends.add(b.name);
        List<Persona.Memory> mem = new ArrayList<>(p.memories());
        mem.sort((a, b) -> b.weight() - a.weight());
        List<Tales.Memory> kept = new ArrayList<>();
        for (Persona.Memory m : mem.subList(0, Math.min(8, mem.size()))) kept.add(new Tales.Memory(m.day(), m.text()));
        kept.addAll(Backstory.lifeMemories(s));                     // [individual] where it came from, its scar, its keepsake
        kept.sort((a, b) -> Long.compare(a.day(), b.day()));
        AssistantEntity.Deed deed = TradeBooks.deed(t);
        String deeds = deed == null || s.deedCount(deed) <= 0 ? "" : Quill.number(s.deedCount(deed)) + " " + deed.label;
        List<String> knacks = new ArrayList<>();
        for (FolkSkills.Chosen ch : s.knacks().chosen()) knacks.add(ch.knack().title);
        Tales.Life l = new Tales.Life(s.displayNameCap(), true, s.ageYears(), t == StationTask.NONE ? "" : t.title.toLowerCase(Locale.ROOT),
            t == StationTask.NONE ? 0 : s.tradeLevel(t), others, traits, p.rolled() ? p.hobby().doing : "", p.quirk(),
            p.rolled() ? p.ambition().hope : "", p.ambitionMet(), p.origin(), p.origin().equals("born here") ? s.bornDay() : p.since(),
            s.life().parents(), s.life().partnerName(), childrenOf(c, s.displayNameCap()), friends, kept, deeds, finds(id, s.displayNameCap()),
            knacks, -1, "", why, mentions(c, s.displayNameCap()));
        Set<UUID> close = new LinkedHashSet<>();
        if (s.life().partner() != null) close.add(s.life().partner());
        for (Social.Bond b : s.life().friends()) for (VillageFolkEntity f : c.folk) if (f.displayNameCap().equals(b.name)) close.add(f.getUUID());
        out.add(new Idea(subject, "LIFE", 5, c.day, null, s.getUUID(), close,
            f -> Tales.life(l, town, voice(f), knewAs(f, s.displayNameCap(), s.life().partnerName(), c), c.day)));
    }

    /** The chronicle's weightiest lines naming a folk, at most six. */
    private static List<Tales.Event> mentions(TradeBooks.Ctx c, String name) {
        List<Tales.Event> out = new ArrayList<>();
        for (Chronicle.Entry e : c.chronicle) {
            if (!e.text().contains(name) || Tales.weight(Tales.kindOf(e.text())) < 6) continue;
            out.add(new Tales.Event(e.day(), "", e.text()));
        }
        return out.size() <= 6 ? out : out.subList(out.size() - 6, out.size());
    }

    private static List<String> finds(UUID id, String name) {
        List<String> out = new ArrayList<>();
        for (MuseumRecords.Found f : MuseumRecords.book(id).finds) {
            if (!f.finder.equals(name)) continue;
            Museum.Kind k = Museum.Kind.byKey(f.kind);
            out.add(name + " " + (f.how.isEmpty() ? "found" : f.how) + " " + (k == null ? "a " + f.kind : k.words) + " on day " + f.day + ".");
            if (out.size() >= 3) break;
        }
        return out;
    }

    /** What the author was to the subject: "my partner", "my parent", "my child", "my friend", or "". */
    private static String knewAs(VillageFolkEntity author, String name, String partner, TradeBooks.Ctx c) {
        if (author.life().partnerName().equals(name)) return "my partner";
        String parents = author.life().parents();
        if (!parents.isEmpty() && List.of(parents.split(" and ")).contains(name)) return "my parent";
        VillageFolkEntity s = named(c, name);
        if (s != null && !s.life().parents().isEmpty() && List.of(s.life().parents().split(" and ")).contains(author.displayNameCap())) return "my child";
        for (Social.Bond b : author.life().friends()) if (name.equals(b.name)) return "my friend";
        return "";
    }

    // ------------------------------------------------------------------ how-to books

    private static void howTos(List<Idea> out, TradeBooks.Ctx c, LibraryRecords.Shelf shelf, String town) {
        UUID id = c.v.id();
        for (VillageFolkEntity f : c.folk) {
            StationTask t = f.stationTask();
            int lv = t == StationTask.NONE ? 0 : f.tradeLevel(t);
            String topic = "";
            long count = 0;
            String counted = "";
            List<String> places = new ArrayList<>();
            if (lv >= 8) {
                topic = switch (t) {
                    case BEEKEEP -> "BEES";
                    case FISH -> "FISH";
                    case COOK -> "BREAD";
                    case RANCH -> Villages.hasBuilt(id, "stable") ? "HORSES" : "SHEEP";
                    case MINE -> "IRON";
                    case WOOD -> "TREES";
                    case GUARD -> "WATCH";
                    default -> "";
                };
                AssistantEntity.Deed d = TradeBooks.deed(t);
                if (d != null) {
                    count = f.deedCount(d);
                    counted = d.label;
                }
            }
            if (topic.isEmpty() && f.deedCount(AssistantEntity.Deed.BLOCKS_BUILT) >= 400) {
                topic = "BUILD";
                count = f.deedCount(AssistantEntity.Deed.BLOCKS_BUILT);
                counted = "blocks laid";
                for (Ledger.Building b : Ledger.buildings(id)) {
                    String w = Villages.spoken(b.structure());
                    if (!w.startsWith("a ") && !places.contains(w)) places.add(w);
                }
            }
            if (topic.isEmpty() && f.persona().rolled()) {
                topic = switch (f.persona().hobby()) {
                    case GARDENING -> "FLOWERS";
                    case STARGAZING -> "STARS";
                    case WHITTLING -> "WHITTLING";
                    case CARDS -> "CARDS";
                    case WALKING -> "WALKS";
                    case MUSIC -> "MUSIC";
                    case READING -> "READING";
                    case FISHING -> "FISH";
                };
                if (topic.equals("FLOWERS")) {
                    count = f.persona().flowersPlanted();
                    counted = "flowers planted";
                }
                if (topic.equals("WALKS")) {
                    for (Ledger.Building b : Ledger.buildings(id)) {
                        String s = b.structure();
                        if (s.startsWith("house") || s.equals("colony") || s.equals("fortify") || s.equals("storage")) continue;
                        int dx = b.anchor().getX() - c.v.centre().getX(), dz = b.anchor().getZ() - c.v.centre().getZ();
                        String w = Villages.spoken(s) + ", " + TradeBooks.compass(dx, dz) + " of the square";
                        if (!w.startsWith("a ") && places.size() < 6) places.add(w);
                    }
                    if (places.size() < 2) topic = "";
                }
                // A pastime's book is for somebody with the time for it: a reader, the curious, or a diarist.
                if (!writerish(f) && !topic.isEmpty() && !topic.equals("FLOWERS")) topic = "";
            }
            if (topic.isEmpty()) continue;
            String subject = "howto:" + topic;
            if (shelf.wrote(subject)) continue;
            boolean dup = false;
            for (Idea i : out) if (i.subject().equals(subject)) dup = true;
            if (dup) continue;
            Tales.HowTo h = new Tales.HowTo(topic, town, t == StationTask.NONE ? "" : t.title.toLowerCase(Locale.ROOT), lv, count, counted, places);
            out.add(new Idea(subject, "HOWTO", 4, c.day, f, null, Set.of(), w -> Tales.howTo(h, voice(w), c.day)));
        }
    }

    // ------------------------------------------------------------------ the children's stories

    private static void story(List<Idea> out, TradeBooks.Ctx c, LibraryRecords.Shelf shelf, String town) {
        UUID id = c.v.id();
        VillageFolkEntity teacher = School.teacher(c.level, id, false);
        if (teacher == null) return;
        List<VillageFolkEntity> kids = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f && f.isBaby() && f.isAlive()) kids.add(f);
        if (kids.isEmpty()) return;
        int told = 0;
        long last = -1;
        for (LibraryRecords.Title t : shelf.books) {
            if (!t.kind.equals("STORY")) continue;
            told++;
            last = Math.max(last, t.written);
        }
        if (last >= 0 && c.day - last < 5) return;
        Quill.Dice d = Quill.Dice.of(town, "story", told, c.day);
        List<VillageFolkEntity> two = d.some(kids, 2);
        List<String> names = new ArrayList<>();
        for (VillageFolkEntity k : two) names.add(k.displayNameCap());
        String pet = "", petKind = "", keeper = "";
        for (Map.Entry<String, String> e : Ledger.notes(id).entrySet()) {
            if (!e.getKey().startsWith("pet/") || e.getValue() == null) continue;
            String[] q = e.getValue().split("\\|", -1);
            if (q.length < 5 || q[2].isEmpty()) continue;
            pet = q[2];
            petKind = q[1].equals("cat") ? "cat" : "dog";
            for (VillageFolkEntity f : c.folk) if (f.getUUID().toString().equals(q[4])) keeper = f.displayNameCap();
            break;
        }
        List<String> places = new ArrayList<>();
        for (String s : new String[]{ "well", "school", "belltower", "fountain", "bakery", "park", "hall", "museum", "tavern", "market" }) {
            if (s.equals("well") || Villages.hasBuilt(id, s)) places.add(Villages.spoken(s));
        }
        String leaning = "";
        StationTask lean = School.leaningOf(two.get(0));
        if (lean != StationTask.NONE) leaning = lean.title.toLowerCase(Locale.ROOT);
        VillageFolkEntity guard = TradeBooks.master(c, StationTask.GUARD), farmer = TradeBooks.master(c, StationTask.FARM),
            miner = TradeBooks.master(c, StationTask.MINE), fisher = TradeBooks.master(c, StationTask.FISH);
        String elder = Villages.elderName(id);
        Tales.Story s = new Tales.Story(town, names, pet, petKind, keeper, nameOf(guard), nameOf(farmer), nameOf(miner), nameOf(fisher),
            teacher.displayNameCap(), elder == null ? "" : elder, places, leaning);
        out.add(new Idea("story:" + (told + 1), "STORY", 4, c.day, teacher, null, Set.of(), w -> Tales.story(s, voice(w), c.day)));
    }

    private static String nameOf(@Nullable VillageFolkEntity f) {
        return f == null ? "" : f.displayNameCap();
    }

    // ------------------------------------------------------------------ the authors

    /** Has this folk the nature or the pastime to write: a reader, the curious, a diarist, a musician (for verse), the teacher? */
    static boolean writerish(VillageFolkEntity f) {
        Persona p = f.persona();
        if (f.individual().rolled && !f.individual().literate) return false;        // [individual] one who cannot read cannot write
        return p.rolled() && (p.hobby() == Persona.Hobby.READING || p.hobby() == Persona.Hobby.MUSIC || p.quirk().equals("keeps a diary"))
            || f.life().has(Social.Trait.CURIOUS) || School.isTeacher(f) || Dreams.wantsToWrite(f);   // [individual] a dreamer
    }

    /** How well this folk would write this book (below nought: not at all). */
    static double fitness(VillageFolkEntity f, Idea i, TradeBooks.Ctx c) {
        if (i.by() != null) return i.by() == f ? 100 : -1;
        if (i.about() != null && i.about().equals(f.getUUID())) return -1;             // nobody writes its own life
        // Nor a poem on its own wedding, or on itself and its friend.
        if (i.subject().startsWith("poem:WEDDING:") || i.subject().startsWith("poem:FRIEND:")) {
            String[] who = i.subject().substring(i.subject().indexOf(':', 5) + 1).split("\\|");
            for (String w : who) if (w.equals(f.displayNameCap())) return -1;
        }
        if (!writerish(f)) return -1;
        Persona p = f.persona();
        double s = 10;
        if (p.hobby() == Persona.Hobby.READING) s += 15;
        if (p.quirk().equals("keeps a diary")) s += 10;
        if (f.life().has(Social.Trait.CURIOUS)) s += 10;
        s += Dreams.writerBonus(f);                                  // [individual] the one who dreams of writing a book
        switch (i.kind()) {
            case "POEM" -> {
                if (p.hobby() == Persona.Hobby.MUSIC) s += 20;
                if (f.life().has(Social.Trait.CHEERFUL) || f.life().has(Social.Trait.SOCIABLE)) s += 5;
            }
            case "HISTORY" -> {
                if (f.isElder()) s += 20;
                if (p.origin().equals("a founder of the village")) s += 15;
                s += f.ageYears() * 0.3;
            }
            default -> { }
        }
        if (i.close().contains(f.getUUID())) s += 30;
        return s;
    }

    /** The book to write today and who will write it: the weightiest idea (the newest first) that somebody free can write. */
    @Nullable
    static Map.Entry<Idea, VillageFolkEntity> choose(TradeBooks.Ctx c, LibraryRecords.Shelf shelf, java.util.function.Predicate<VillageFolkEntity> free) {
        List<Idea> ideas = ideas(c, shelf);
        if (ideas.isEmpty()) return null;
        Quill.Dice d = Quill.Dice.of(c.v.id(), c.day, shelf.books.size());
        ideas.sort((a, b) -> Double.compare(score(b, c, d), score(a, c, d)));
        for (Idea i : ideas) {
            VillageFolkEntity best = null;
            double bestScore = -1;
            for (VillageFolkEntity f : c.folk) {
                if (!free.test(f) || recentlyWrote(shelf, f, c.day)) continue;
                double s = fitness(f, i, c);
                if (s > bestScore) { bestScore = s; best = f; }
            }
            if (best != null && bestScore >= 0) return Map.entry(i, best);
        }
        return null;
    }

    private static double score(Idea i, TradeBooks.Ctx c, Quill.Dice d) {
        double s = i.weight();
        if (c.day - i.day() <= 5) s += 4;
        return s + d.roll(100) / 100.0;
    }

    private static boolean recentlyWrote(LibraryRecords.Shelf shelf, VillageFolkEntity f, long day) {
        for (LibraryRecords.Title t : shelf.books) {
            if (f.getUUID().equals(t.authorId) && !t.kind.equals("TRADE") && day - t.written < AUTHOR_GAP) return true;
        }
        return false;
    }

    /** A folk's voice as a writer: its name, its traits, its quirk, its pastime and its trade. */
    static Quill.Voice voice(VillageFolkEntity f) {
        List<String> traits = new ArrayList<>();
        for (Social.Trait t : f.life().traits()) traits.add(t.label);
        Persona p = f.persona();
        return Quill.Voice.of(f.displayNameCap(), traits, p.quirk(), p.rolled() ? p.hobby().word : "",
            f.stationTask() == StationTask.NONE ? "" : f.stationTask().title.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ helpers

    @Nullable
    private static VillageFolkEntity named(TradeBooks.Ctx c, String name) {
        for (VillageFolkEntity f : c.folk) if (f.displayNameCap().equals(name)) return f;
        return null;
    }

    /** The folk close to these names: themselves, their partners, their friends (to write of them). */
    private static Set<UUID> closeTo(TradeBooks.Ctx c, String... names) {
        Set<UUID> out = new LinkedHashSet<>();
        for (VillageFolkEntity f : c.folk) {
            for (String n : names) {
                if (n == null || n.isEmpty()) continue;
                if (f.displayNameCap().equals(n) || f.life().partnerName().equals(n)) out.add(f.getUUID());
                for (Social.Bond b : f.life().friends()) if (n.equals(b.name)) out.add(f.getUUID());
                if (List.of(f.life().parents().split(" and ")).contains(n)) out.add(f.getUUID());
            }
        }
        return out;
    }

    /** The children of a folk, living or dead, by their parents' names. */
    private static List<String> childrenOf(TradeBooks.Ctx c, String name) {
        List<String> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(c.v.id())) {
            if (a instanceof VillageFolkEntity f && List.of(f.life().parents().split(" and ")).contains(name)) out.add(f.displayNameCap());
        }
        for (Ledger.Grave g : c.graves) if (List.of(g.parents().split(" and ")).contains(name) && !out.contains(g.name())) out.add(g.name());
        return out;
    }

    /** How old a folk was when it died, out of the chronicle's word of it ("died, aged 41"), or nought. */
    private static int ageAtDeath(TradeBooks.Ctx c, Ledger.Grave g) {
        for (Chronicle.Entry e : c.chronicle) {
            if (e.day() != g.died() || !e.text().startsWith(g.name()) || !e.text().contains(" died")) continue;
            Matcher m = AGED.matcher(e.text());
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    /** The cause of a death as an elegy has verses for: "old age", "drowning", "fall", "fire", "fight", "cold", or "". */
    static String cause(String how) {
        String h = how == null ? "" : how.toLowerCase(Locale.ROOT);
        if (h.contains("old age")) return "old age";
        if (h.contains("drown")) return "drowning";
        if (h.contains("fall")) return "fall";
        if (h.contains("fire") || h.contains("lava")) return "fire";
        if (h.contains("fight") || h.contains("raiders") || h.contains("bell ringing") || h.contains("explosion")) return "fight";
        if (h.contains("cold") || h.contains("hunger")) return "cold";
        return "";
    }

    /** A trade's key by its title ("Miner" -> "MINE"), or "". */
    static String tradeKey(String title) {
        for (StationTask t : StationTask.values()) if (t.title.equalsIgnoreCase(title)) return t.name();
        return "";
    }

    /** The trade's best day out of the item books: {count, day}. */
    static int[] best(TradeBooks.Ctx c, StationTask t) {
        int best = 0, on = -1;
        for (Annals.ItemDay d : c.items) {
            int total = 0;
            for (Map.Entry<String, int[]> e : d.items().entrySet()) {
                String makers = d.makers().get(e.getKey());
                if (makers != null && makers.split("\\.")[0].equals(t.name())) total += e.getValue()[0];
            }
            if (total > best) { best = total; on = (int) d.day(); }
        }
        return new int[]{ best, on };
    }

    /** What the trade brought in most of that day, in words. */
    private static String topWord(TradeBooks.Ctx c, StationTask t, long day) {
        for (Annals.ItemDay d : c.items) {
            if (d.day() != day) continue;
            String top = "";
            int most = 0;
            for (Map.Entry<String, int[]> e : d.items().entrySet()) {
                String makers = d.makers().get(e.getKey());
                if (makers != null && makers.split("\\.")[0].equals(t.name()) && e.getValue()[0] > most) {
                    most = e.getValue()[0];
                    top = e.getKey();
                }
            }
            if (!top.isEmpty()) return TradeBooks.words(top);
        }
        return "wheat";
    }

    private static long parse(String s) {
        try { return Long.parseLong(s.trim()); } catch (RuntimeException e) { return -1; }
    }
}
