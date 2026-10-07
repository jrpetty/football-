package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.LibraryRecords;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.MuseumRecords;
import com.jrpetty.mcassistant.village.Quill;
import com.jrpetty.mcassistant.village.TradeBookWriter;
import com.jrpetty.mcassistant.village.TradeBookWriter.Change;
import com.jrpetty.mcassistant.village.TradeBookWriter.Facts;
import com.jrpetty.mcassistant.village.TradeBookWriter.Hand;
import com.jrpetty.mcassistant.village.TradeBookWriter.Lesson;
import com.jrpetty.mcassistant.village.TradeBookWriter.Made;
import com.jrpetty.mcassistant.village.TradeBookWriter.Person;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Every trade's book of best practice, as its master keeps it: what goes in it, out of the town's own books, and
 * when it wants writing again. [library]
 *
 * <p>Each trade the town works (a grown folk at it) has one book, kept by its master: the folk at that trade with
 * the highest level at it. What the master writes is the town's own record (TradeBookWriter has the writing): what
 * the trade brought in item by item from the annals' item books (the trade that made most of a thing that day is
 * credited with it), this week's and last week's tally, the best day and its main thing, all of it, its worth a day
 * in coin; the master's own tally of its deeds; what went wrong (the trade's dead, out of the graves, and the
 * chronicle's fires, storms, droughts, raids and the rest where they touch the trade); the age and the day it came,
 * the town's research; notes out of the town's own books on what works (the farmer's own field and its care, the
 * mine's site and the first diamond, the woodcutters' trees felled against saplings planted); and the trade's hands.
 *
 * <p><b>Editions.</b> Each edition keeps its facts in a line (the master, the age, the record, the lessons, the
 * research, the hands, the main thing made). When the town's facts move on from the last edition's — a new master,
 * a new age, a record beaten, something gone wrong, the research — the master writes the next, a day after the last
 * at the soonest, and it opens with what is new; when only the small things move (the hands, the main crop), it
 * waits five days; and it is brought up to date anyway after ten. The old edition stays on the shelf.
 */
public final class TradeBooks {

    private TradeBooks() {}

    /** Days between editions at the least, for a change that matters; for a small one; and for none. */
    static final int GAP = 1, SMALL_GAP = 5, STALE = 10;
    /** A master writes its trade's book from this level at it: a beginner has nothing yet to teach. */
    public static final int MASTER_LEVEL = 3;

    /** What the town's books hold, read once for a look at every trade. */
    static final class Ctx {
        final ServerLevel level;
        final Villages.Village v;
        final long day;
        final List<Annals.ItemDay> items;
        final List<Annals.Day> days;
        final List<Chronicle.Entry> chronicle;
        final List<Ledger.Grave> graves;
        final List<String> fires;
        final List<VillageFolkEntity> folk = new ArrayList<>();

        Ctx(ServerLevel level, Villages.Village v, long day) {
            this.level = level;
            this.v = v;
            this.day = day;
            UUID id = v.id();
            this.items = Annals.itemDays(id);
            this.days = Annals.days(id);
            this.chronicle = Chronicle.of(id);
            this.graves = Ledger.graves(id);
            this.fires = Annals.fireLog(id);
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity f && f.isAlive() && !f.isShowcase() && !f.isBaby()) folk.add(f);
            }
        }
    }

    /** A book's draft: what it would say now, its facts in a line, and what changed since the last edition. */
    record Draft(StationTask trade, VillageFolkEntity master, Facts facts, String line, List<Change> changes, boolean significant) {
        /** [weave] Its key in the catalogue: the trade's ("FARM"), or the library's own ("LIBRARY", the librarian's, no trade of the work's). */
        String key() {
            return facts.key();
        }

        /** "farmer", "librarian". */
        String noun() {
            return trade == StationTask.NONE ? "librarian" : trade.title.toLowerCase(Locale.ROOT);
        }

        /** "farming", "library's keeping". */
        String label() {
            return trade == StationTask.NONE ? "library's keeping" : trade.label;
        }
    }

    // ------------------------------------------------------------------ the trades and their masters

    /** The trades the town works, most hands first. */
    static List<StationTask> trades(Ctx c) {
        Map<StationTask, Integer> n = new EnumMap<>(StationTask.class);
        for (VillageFolkEntity f : c.folk) if (f.stationTask() != StationTask.NONE && !f.isHired()) n.merge(f.stationTask(), 1, Integer::sum);
        List<StationTask> out = new ArrayList<>(n.keySet());
        out.sort((a, b) -> n.get(b) - n.get(a));
        return out;
    }

    /** The trade's master: the folk at it with the highest level at it (the eldest breaks a tie), or null. */
    @Nullable
    static VillageFolkEntity master(Ctx c, StationTask t) {
        VillageFolkEntity best = null;
        for (VillageFolkEntity f : c.folk) {
            if (f.stationTask() != t || f.isHired()) continue;
            if (best == null || f.tradeLevel(t) > best.tradeLevel(t)
                || f.tradeLevel(t) == best.tradeLevel(t) && f.ageYears() > best.ageYears()) best = f;
        }
        return best;
    }

    /** The trade's master in this town now (for the card and the talk), or null. */
    @Nullable
    public static VillageFolkEntity masterOf(UUID village, StationTask t) {
        VillageFolkEntity best = null;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.stationTask() != t || f.isHired()) continue;
            if (best == null || f.tradeLevel(t) > best.tradeLevel(t)) best = f;
        }
        return best;
    }

    // ------------------------------------------------------------------ the facts

    /** The deed that is a trade's own work, or null. */
    @Nullable
    static AssistantEntity.Deed deed(StationTask t) {
        return switch (t) {
            case FARM -> AssistantEntity.Deed.CROPS_HARVESTED;
            case MINE -> AssistantEntity.Deed.ORE_FOUND;
            case WOOD -> AssistantEntity.Deed.TREES_FELLED;
            case FISH -> AssistantEntity.Deed.FISH_CAUGHT;
            case RANCH -> AssistantEntity.Deed.ANIMALS_BRED;
            case GUARD, HUNT, CAVE -> AssistantEntity.Deed.MOBS_KILLED;
            case DIVER -> AssistantEntity.Deed.CROPS_HARVESTED;              // [diver] the kelp beds cut
            case SMELT -> AssistantEntity.Deed.ITEMS_SMELTED;
            case HAUL -> AssistantEntity.Deed.LOADS_HAULED;
            case STORE -> AssistantEntity.Deed.CHESTS_SORTED;
            case SMITH, TAILOR, BEEKEEP, BREW, ENCHANT, COOK, SHOP -> AssistantEntity.Deed.THINGS_MADE;
            case FLETCHER, GOLEMS -> AssistantEntity.Deed.THINGS_MADE;     // [fletcher] [golems]
            case FIREWORKS -> AssistantEntity.Deed.THINGS_MADE;          // [fireworks] its rockets
            case CARTOGRAPHER -> AssistantEntity.Deed.THINGS_MADE;   // [cartographer] its maps
            default -> null;
        };
    }

    /** A thing in the books by its id, in words, many of it: "wheat", "carrots", "raw iron". */
    static String words(String id) {
        ItemStack s = new ItemStack(Annals.itemOf(id));
        if (s.isEmpty()) return id.replace('_', ' ');
        return Bench.plural(s.getHoverName().getString().toLowerCase(Locale.ROOT));
    }

    /** Where the trade's work shows in the chronicle: words a line touching it would have. */
    static String[] keywords(StationTask t) {
        return switch (t) {
            case FARM -> new String[]{ "field", "farm", "harvest", "crop", "wheat", "drought", "frost", "famine", "hungry", "short commons", "flood" };
            case MINE -> new String[]{ "mine", "miner", "tunnel", "lava", "collapse", "cave-in" };
            case WOOD -> new String[]{ "wood", "tree", "forest", "lumber", "woodcutter" };
            case FISH -> new String[]{ "fish", "water", "boat", "jetty", "drown", "storm", "sea", "flood" };
            case RANCH -> new String[]{ "pen", "herd", "sheep", "cow", "pig", "horse", "stable", "rancher" };
            case GUARD -> new String[]{ "raid", "the watch", "the wall", "war", "spy", "gate" };
            case SMELT -> new String[]{ "smeltery", "furnace", "smelter" };
            case SMITH -> new String[]{ "smithy", "forge", "smith" };
            case COOK -> new String[]{ "café", "cafe", "bakery", "kitchen", "hungry", "famine" };
            case HAUL, STORE -> new String[]{ "storehouse", "the stores", "courier", "caravan" };
            case HUNT -> new String[]{ "hunt", "wolf", "the wild" };
            case SCOUT -> new String[]{ "scout", "scouting" };
            case CAVE -> new String[]{ "cave" };
            case FLETCHER -> new String[]{ "arrow", "fletch", "the butts", "crossbow", "the raid" };   // [fletcher]
            case GOLEMS -> new String[]{ "golem" };                                                     // [golems]
            case FIREWORKS -> new String[]{ "firework", "rocket", "powder" };   // [fireworks]
            case CARTOGRAPHER -> new String[]{ "map", "cartographer", "explorer" };   // [cartographer]
            case DIVER -> new String[]{ "diver", "kelp", "turtle", "drown", "out of the water", "monument" };   // [diver]
            case BEEKEEP -> new String[]{ "hive", "bee" };
            default -> new String[]{};
        };
    }

    /** What kind of trouble a chronicle line tells of, or "" for none. */
    static String trouble(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains(" died") || t.contains("record")) return "";             // deaths come from the graves; a record is no trouble
        if (t.contains("fire") || t.contains("burnt") || t.contains("burned")) return "fire";
        if (t.contains("storm") || t.contains("thunder") || t.contains("lightning")) return "storm";
        if (t.contains("drought") || t.contains("dry spell") || t.contains("dried")) return "drought";
        if (t.contains("flood")) return "flood";
        if (t.contains("raider") || t.contains(" raid")) return "raid";
        if (t.contains("declared war") || t.contains(" war with") || t.contains("at war")) return "war";
        if (t.contains("famine") || t.contains("starv") || t.contains("hungry") || t.contains("short commons")) return "famine";
        if (t.contains("collapse") || t.contains("cave-in") || t.contains("caved in")) return "collapse";
        if (t.contains("was lost") || t.contains("not been seen") || t.contains("could not find the way")) return "lost";
        return "";
    }

    /** "north-east", from the square to there. */
    static String compass(int dx, int dz) {
        double a = Math.toDegrees(Math.atan2(dx, -dz));
        if (a < 0) a += 360;
        String[] w = { "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west" };
        return w[(int) Math.round(a / 45.0) % 8];
    }

    /** A draft of the trade's book as it stands today, against its last edition. */
    @Nullable
    static Draft draft(Ctx c, StationTask t, LibraryRecords.Shelf shelf) {
        VillageFolkEntity m = master(c, t);
        if (m == null || m.tradeLevel(t) < MASTER_LEVEL) return null;
        UUID id = c.v.id();
        String key = t.name();
        // What the trade made, day by day, from the item books (the trade that made most of a thing that day has it).
        Map<String, long[]> per = new LinkedHashMap<>();                    // id -> {total, week, best, bestOn}
        List<long[]> dayTotals = new ArrayList<>();                          // {day, total}
        int n = c.items.size();
        for (int i = 0; i < n; i++) {
            Annals.ItemDay d = c.items.get(i);
            long total = 0;
            for (Map.Entry<String, int[]> e : d.items().entrySet()) {
                String makers = d.makers().get(e.getKey());
                if (makers == null || !makers.split("\\.")[0].equals(key)) continue;
                int made = e.getValue()[0];
                if (made <= 0) continue;
                total += made;
                long[] p = per.computeIfAbsent(e.getKey(), k -> new long[]{ 0, 0, 0, -1 });
                p[0] += made;
                if (i >= n - 7) p[1] += made;
                if (made > p[2]) { p[2] = made; p[3] = d.day(); }
            }
            dayTotals.add(new long[]{ d.day(), total });
        }
        List<Map.Entry<String, long[]>> order = new ArrayList<>(per.entrySet());
        order.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        List<Made> made = new ArrayList<>();
        for (Map.Entry<String, long[]> e : order) {
            long[] p = e.getValue();
            made.add(new Made(words(e.getKey()), p[0], (int) p[1], (int) p[2], p[3]));
            if (made.size() >= 6) break;
        }
        int week = 0, lastWeek = 0;
        long allTime = 0;
        for (int i = 0; i < dayTotals.size(); i++) {
            long[] dt = dayTotals.get(i);
            allTime += dt[1];
            if (i >= dayTotals.size() - 7) week += (int) dt[1];
            else if (i >= dayTotals.size() - 14) lastWeek += (int) dt[1];
        }
        // The record: the most of its main thing (what it has made most of) brought in on a single day.
        int best = made.isEmpty() ? 0 : made.get(0).best();
        long bestOn = made.isEmpty() ? -1 : made.get(0).bestOn();
        String bestWhat = made.isEmpty() ? "" : made.get(0).what();
        int days = allTime > 0 ? dayTotals.size() : 0;
        // Its worth a day, the last week, out of the morning books.
        long worthSum = 0;
        int worthDays = 0;
        for (int i = Math.max(0, c.days.size() - 7); i < c.days.size(); i++) {
            worthSum += c.days.get(i).made().getOrDefault(key, 0);
            worthDays++;
        }
        int worth = worthDays == 0 ? 0 : (int) Math.round(worthSum / (double) worthDays);
        AssistantEntity.Deed deed = deed(t);
        long deeds = deed == null ? 0 : m.deedCount(deed);
        // What went wrong: each lesson with its key, so the next edition knows which are new.
        List<Map.Entry<Lesson, String>> found = new ArrayList<>();
        for (Ledger.Grave g : c.graves) {
            if (!g.trade().equalsIgnoreCase(t.title)) continue;
            found.add(Map.entry(new Lesson(g.died(), "death", g.name(), g.cause().isEmpty() ? "by misfortune" : g.cause()),
                "g:" + g.name() + "@" + g.died()));
        }
        String[] kw = keywords(t);
        for (Chronicle.Entry e : c.chronicle) {
            String kind = trouble(e.text());
            if (kind.isEmpty()) continue;
            String low = e.text().toLowerCase(Locale.ROOT);
            boolean touches = (kind.equals("raid") || kind.equals("war")) && t == StationTask.GUARD;
            for (String w : kw) if (low.contains(w)) { touches = true; break; }
            if (!touches) continue;
            found.add(Map.entry(new Lesson(e.day(), kind, "", e.text()), "c:" + e.day() + ":" + Integer.toHexString(e.text().hashCode())));
        }
        String firePlace = switch (t) {
            case SMELT -> "smeltery";
            case SMITH -> "smithy";
            case COOK -> "caf";
            case BREW -> "brewery";
            case WOOD -> "wood";
            default -> "";
        };
        if (!firePlace.isEmpty()) {
            for (String f : c.fires) {
                if (!f.toLowerCase(Locale.ROOT).contains(firePlace)) continue;
                long fd = -1;
                String text = f;
                try {
                    int colon = f.indexOf(':');
                    fd = Long.parseLong(f.substring(4, colon).trim()) - 1;          // the log counts its days from one
                    text = f.substring(colon + 1).trim();
                } catch (RuntimeException ignored) { }
                found.add(Map.entry(new Lesson(fd, "fire", "", text), "f:" + fd + ":" + Integer.toHexString(text.hashCode())));
            }
        }
        found.sort((a, b) -> Long.compare(a.getKey().day(), b.getKey().day()));
        if (found.size() > 6) found = new ArrayList<>(found.subList(found.size() - 6, found.size()));
        List<Lesson> lessons = new ArrayList<>();
        List<String> lessonKeys = new ArrayList<>();
        for (Map.Entry<Lesson, String> e : found) {
            lessons.add(e.getKey());
            lessonKeys.add(e.getValue());
        }
        // The age, when it came, and the next.
        Villages.Age age = Villages.ageOf(id);
        long ageSince = -1;
        for (Chronicle.Entry e : c.chronicle) if (e.text().contains("came into " + age.label)) ageSince = e.day();
        String next = age.ordinal() + 1 < Villages.Age.values().length ? Villages.Age.values()[age.ordinal() + 1].label : "";
        int research = Math.max(0, CityTree.workPercent(id, t));
        // The hands.
        List<VillageFolkEntity> at = new ArrayList<>();
        for (VillageFolkEntity f : c.folk) if (f.stationTask() == t && !f.isHired()) at.add(f);
        at.sort((a, b) -> b.tradeLevel(t) - a.tradeLevel(t));
        List<Hand> hands = new ArrayList<>();
        for (VillageFolkEntity f : at) {
            String note = "";
            if (f != m && f.tradeLevel(t) < Library.APPRENTICE_LEVEL && Library.hasRead(id, f.getUUID(), key)) note = "has read this book, and learns the quicker for it";
            hands.add(new Hand(f.displayNameCap(), f.tradeLevel(t), note));
            if (hands.size() >= 10) break;
        }
        // The master.
        List<String> traits = new ArrayList<>();
        for (Social.Trait tr : m.life().traits()) traits.add(tr.label);
        Persona p = m.persona();
        List<String> knacks = new ArrayList<>();
        for (FolkSkills.Chosen ch : m.knacks().chosen()) knacks.add(ch.knack().title + " on day " + ch.day());
        String memory = "";
        long memoryDay = -1;
        int heaviest = 2;
        for (Persona.Memory mem : p.memories()) {
            String low = mem.text().toLowerCase(Locale.ROOT);
            boolean work = low.contains(t.label) || low.contains(t.title.toLowerCase(Locale.ROOT)) || low.contains("harvest") || low.contains("diamond")
                || low.contains("record") || low.contains("level") || low.contains("lost ") || low.contains("found");
            if (work && mem.weight() > heaviest) {
                heaviest = mem.weight();
                memory = mem.text();
                memoryDay = mem.day();
            }
        }
        long since = c.day - m.daysServed();
        Person master = new Person(m.displayNameCap(), m.tradeLevel(t), traits, p.quirk(), p.rolled() ? p.hobby().word : "", m.ageYears(),
            p.origin(), m.daysServed() > 0 ? since : p.since(), knacks, m.branch() == AssistantEntity.Branch.NONE ? "" : m.branch().label,
            memory, memoryDay, m.life().partnerName());
        // What works, out of the town's own books.
        List<String> notes = notes(c, t, m, at);
        // The last edition, and what changed since.
        LibraryRecords.Title last = shelf.tradeBook(key);
        List<Long> earlier = new ArrayList<>();
        for (LibraryRecords.Title b : shelf.books) if (b.kind.equals("TRADE") && b.trade.equals(key)) earlier.add(b.written);
        earlier.sort(Long::compare);
        int edition = last == null ? 1 : last.edition + 1;
        String top = made.isEmpty() ? "" : made.get(0).what();
        Map<String, String> now = new LinkedHashMap<>();
        now.put("master", m.displayNameCap());
        now.put("age", age.name());
        now.put("record", Integer.toString(best));
        now.put("recordOn", Long.toString(bestOn));
        now.put("recordWhat", bestWhat);
        now.put("top", top);
        now.put("lessons", String.join(",", lessonKeys));
        now.put("research", Integer.toString(research));
        now.put("hands", Integer.toString(at.size()));
        String line = line(now);
        List<Change> changes = new ArrayList<>();
        boolean significant = last == null;
        if (last != null) {
            Map<String, String> was = parse(last.facts);
            if (!was.getOrDefault("master", "").equals(now.get("master")) && !was.getOrDefault("master", "").isEmpty()) {
                changes.add(new Change("master", now.get("master"), was.get("master"), -1, 0, 0));
                significant = true;
            }
            if (!was.getOrDefault("age", "").equals(now.get("age")) && !was.getOrDefault("age", "").isEmpty()) {
                changes.add(new Change("age", age.label, "", ageSince, 0, 0));
                significant = true;
            }
            int oldBest = was.getOrDefault("recordWhat", "").equals(bestWhat) ? parseInt(was.get("record")) : 0;
            if (best > oldBest && oldBest > 0) {
                changes.add(new Change("record", bestWhat, "", bestOn, best, oldBest));
                significant = true;
            }
            List<String> oldKeys = List.of(was.getOrDefault("lessons", "").split(","));
            for (int i = 0; i < lessonKeys.size(); i++) {
                if (oldKeys.contains(lessonKeys.get(i))) continue;
                Lesson l = lessons.get(i);
                changes.add(new Change("lesson", TradeBookWriter.lessonWords(l), "", l.day(), 0, 0));
                significant = true;
            }
            int oldResearch = parseInt(was.get("research"));
            if (research >= oldResearch + 2) {
                changes.add(new Change("research", "", "", -1, research, oldResearch));
                significant = true;
            }
            int oldHands = parseInt(was.get("hands"));
            if (Math.abs(at.size() - oldHands) >= 2) changes.add(new Change("hands", "", "", -1, at.size(), oldHands));
            String oldTop = was.getOrDefault("top", "");
            if (!oldTop.isEmpty() && !top.isEmpty() && !oldTop.equals(top)) changes.add(new Change("top", top, oldTop, -1, 0, 0));
        }
        String date = Seasons.season(id, c.day).word + ", the town's " + TownCalendar.ordinal(Seasons.year(id, c.day)) + " year";
        Facts f = new Facts(Villages.name(id), key, c.day, date, edition, earlier, master, hands, made, days, week, lastWeek, best,
            bestOn, bestWhat, allTime, worth, deeds, lessons, age.label, ageSince, next, research, notes, changes);
        return new Draft(t, m, f, line, changes, significant);
    }

    /** Notes on what works, out of the town's own books: a few sentences in the master's own words. */
    static List<String> notes(Ctx c, StationTask t, VillageFolkEntity m, List<VillageFolkEntity> at) {
        UUID id = c.v.id();
        List<String> out = new ArrayList<>();
        long[] sum = new long[4];
        for (VillageFolkEntity f : at) {
            sum[0] += f.deedCount(AssistantEntity.Deed.TREES_FELLED);
            sum[1] += f.deedCount(AssistantEntity.Deed.SAPLINGS_PLANTED);
            sum[2] += f.deedCount(AssistantEntity.Deed.ORE_FOUND);
            sum[3] += f.deedCount(AssistantEntity.Deed.FISH_CAUGHT);
        }
        switch (t) {
            case FARM -> {
                String care = Fields.careLine(m);
                if (care != null && care.startsWith("its field grows at ")) {
                    String rest = care.substring("its field grows at ".length());
                    int x = rest.indexOf('x');
                    String pace = x > 0 ? rest.substring(0, x) : rest;
                    String how = x > 0 ? rest.substring(x + 1).replaceFirst("^,\\s*", "") : "";
                    out.add("My own field grows at " + pace + " times the wild's pace" + (how.isEmpty() ? ". Mind its water and its light and yours will too."
                        : ": it's " + how.replace(", with a composter", ", and there's a composter by it") + "."));
                }
                for (Chronicle.Entry e : c.chronicle) {
                    if (e.text().contains("irrigation channels")) {
                        out.add("We cut irrigation channels through the fields on day " + e.day() + ". A field with water running through it never dries.");
                        break;
                    }
                }
                Seasons.Season s = Seasons.season(id, c.day);
                out.add("It's " + s.word + " now. " + switch (s) {
                    case SPRING -> "This is the quickest growing of the year: sow everything.";
                    case SUMMER -> "The fields still grow well. Keep them watered.";
                    case AUTUMN -> "The fields slow now. Bring in what's ripe and put it by.";
                    case WINTER -> "Winter is slowest of all. Don't expect much, and don't waste seed.";
                });
            }
            case MINE -> {
                BlockPos site = TownMine.siteOf(id);
                if (site != null) {
                    int dx = site.getX() - c.v.centre().getX(), dz = site.getZ() - c.v.centre().getZ();
                    int dist = (int) Math.round(Math.sqrt(dx * dx + (double) dz * dz));
                    out.add("The town's mine lies " + dist + " blocks " + compass(dx, dz) + " of the square, well clear of the houses. That's where we dig, and nowhere else: never under the town.");
                }
                MuseumRecords.Found dia = MuseumRecords.firstFind(id, "diamond");
                if (dia != null) out.add(dia.finder + " found the town's first diamond, on day " + dia.day + ". " + (dia.finder.equals(m.displayNameCap())
                    ? "I'll not pretend I wasn't proud." : "Ask about it; you'll hear it told."));
                if (sum[2] > 0) out.add("Between us we've dug " + Quill.number(sum[2]) + " veins of ore.");
            }
            case WOOD -> {
                if (sum[0] > 0) out.add("Between us we've felled " + Quill.number(sum[0]) + " trees and planted " + Quill.number(sum[1]) + " saplings"
                    + (sum[1] >= sum[0] ? ": more back than we took, as it should be." : ". That's not enough. Plant more."));
            }
            case FISH -> {
                if (sum[3] > 0) out.add("Between us we've caught " + Quill.number(sum[3]) + " fish.");
                for (Chronicle.Entry e : c.chronicle) {
                    if (e.text().contains("jetty")) {
                        out.add(Quill.stop(Quill.cap(e.text())) + " (day " + e.day() + ".) Fish from it.");
                        break;
                    }
                }
            }
            case GUARD -> {
                int raids = 0;
                for (Chronicle.Entry e : c.chronicle) if (e.text().contains("raiders came")) raids++;
                long kills = 0;
                for (VillageFolkEntity f : at) kills += f.deedCount(AssistantEntity.Deed.MOBS_KILLED);
                if (raids > 0) out.add("The watch has stood against " + Quill.count(raids, "raid", "raids") + " so far.");
                if (kills > 0) out.add("Between us we've seen off " + Quill.number(kills) + " things that came out of the dark.");
            }
            case RANCH -> {
                long bred = 0, sheared = 0;
                for (VillageFolkEntity f : at) {
                    bred += f.deedCount(AssistantEntity.Deed.ANIMALS_BRED);
                    sheared += f.deedCount(AssistantEntity.Deed.ANIMALS_SHEARED);
                }
                if (bred + sheared > 0) out.add("Between us we've bred " + Quill.number(bred) + " animals and sheared " + Quill.number(sheared) + " sheep.");
                if (Villages.hasBuilt(id, "stable")) out.add("The stable stands now, so the horses are ours to keep as well.");
            }
            case SMELT -> {
                long smelted = 0;
                for (VillageFolkEntity f : at) smelted += f.deedCount(AssistantEntity.Deed.ITEMS_SMELTED);
                if (smelted > 0) out.add("Between us we've smelted " + Quill.number(smelted) + " loads.");
                if (Villages.hasBuilt(id, "smeltery")) out.add("The smeltery's three furnaces are the town's. Keep all three going.");
            }
            case FIREWORKS -> out.addAll(FireworksMaker.bookNotes(c.level, c.v));   // [fireworks] its real numbers, and what it learned
            case CARTOGRAPHER -> out.addAll(Cartographers.bookNotes(id));     // [cartographer] what the map room has learnt, and its numbers
            // [diver] The beds, the fuel they kept (FuelBook), the clay, the turtles, the rescues, and the breath (Divers.notes).
            case DIVER -> out.addAll(Divers.notes(c.level, id));
            case COOK -> {
                if (Villages.hasBuilt(id, "cafe")) out.add("The café is where folk spend their coins on their break. Keep its counter stocked.");
                if (Villages.hasBuilt(id, "bakery")) out.add("The bakery's oven bakes for the whole town. Keep it fed.");
            }
            case FLETCHER -> out.addAll(Fletchers.bookNotes(c.level, c.v));     // [fletcher] the arrows, the flint, the butts
            case GOLEMS -> out.addAll(Golems.bookNotes(c.level, c.v));           // [golems] the golems raised, mended and lost
            default -> { }
        }
        out.addAll(Weave.notes(c.level, c.v, t, m, at));      // [weave] the caves, the watch's cases, the fleet, the season's fashion
        return out;
    }

    // ------------------------------------------------------------------ [weave] the librarian's own book

    /**
     * The library's own book of best practice, kept by its librarian: the shelves (what is on them, what is read, what
     * goes out and what never comes back) and how a library is kept. Its key is "LIBRARY": no trade of the work's, so no
     * apprentice reads it for a quicker hand; it is the town's record of its own books. Null with no librarian or no books.
     */
    @Nullable
    static Draft librarian(Ctx c, LibraryRecords.Shelf shelf) {
        UUID id = c.v.id();
        if (shelf.librarian == null || !Library.stands(id)) return null;
        VillageFolkEntity m = null;
        for (VillageFolkEntity f : c.folk) if (f.getUUID().equals(shelf.librarian)) m = f;
        if (m == null) return null;
        int books = 0, fresh = 0, reads = 0, lent = 0;
        Map<String, Integer> kinds = new LinkedHashMap<>();
        LibraryRecords.Title most = null;
        java.util.Set<String> authors = new java.util.LinkedHashSet<>();
        for (LibraryRecords.Title t : shelf.books) {
            if (t.superseded || t.kind.isEmpty()) continue;
            if (t.kind.equals("TRADE") && t.trade.equals("LIBRARY")) continue;          // its own book is not counted in itself
            books++;
            if (c.day - t.written < 7) fresh++;
            reads += t.reads;
            lent += t.lent;
            kinds.merge(Library.kindWord(t), 1, Integer::sum);
            if (!t.author.isEmpty()) authors.add(t.author);
            if (most == null || t.reads > most.reads) most = t;
        }
        if (books == 0) return null;
        List<String> notes = new ArrayList<>();
        List<String> kindWords = new ArrayList<>();
        for (Map.Entry<String, Integer> e : kinds.entrySet()) {
            String one = e.getKey();
            String many = switch (one) {
                case "history" -> "histories";
                case "life" -> "lives";
                case "book of best practice" -> "books of best practice";
                default -> one + "s";
            };
            kindWords.add(Quill.count(e.getValue(), one, many));
        }
        notes.add("We write our own books here: " + Quill.list(kindWords) + ", by " + Quill.count(authors.size(), "hand", "hands")
            + ". Every one of them is about this town, and true.");
        if (!shelf.readers.isEmpty()) notes.add(Quill.cap(Quill.count(shelf.readers.size(), "folk has", "folk have")) + " sat and read here. An apprentice who reads its trade's book learns the quicker for it.");
        if (lent > 0 || shelf.lost > 0) {
            notes.add("Players have borrowed " + Quill.count(lent, "book", "books") + (shelf.loans.isEmpty() ? "" : ", and " + Quill.count(shelf.loans.size(), "is", "are") + " out now")
                + (shelf.lost > 0 ? "; " + Quill.count(shelf.lost, "never came back", "never came back") + ", and I wrote each out again" : "")
                + (shelf.fines > 0 ? "; the fines came to " + Quill.count(shelf.fines, "coin", "coins") : "") + ".");
        }
        List<String> traits = new ArrayList<>();
        for (Social.Trait tr : m.life().traits()) traits.add(tr.label);
        Persona p = m.persona();
        List<String> knacks = new ArrayList<>();
        for (FolkSkills.Chosen ch : m.knacks().chosen()) knacks.add(ch.knack().title + " on day " + ch.day());
        Person master = new Person(m.displayNameCap(), 0, traits, p.quirk(), p.rolled() ? p.hobby().word : "", m.ageYears(), p.origin(),
            shelf.librarianSince, knacks, "", "", -1, m.life().partnerName());
        LibraryRecords.Title last = shelf.tradeBook("LIBRARY");
        List<Long> earlier = new ArrayList<>();
        for (LibraryRecords.Title b : shelf.books) if (b.kind.equals("TRADE") && b.trade.equals("LIBRARY")) earlier.add(b.written);
        earlier.sort(Long::compare);
        Map<String, String> now = new LinkedHashMap<>();
        now.put("master", m.displayNameCap());
        now.put("books", Integer.toString(books));
        String line = line(now);
        List<Change> changes = new ArrayList<>();
        boolean significant = last == null;
        if (last != null) {
            Map<String, String> was = parse(last.facts);
            if (!was.getOrDefault("master", "").equals(now.get("master")) && !was.getOrDefault("master", "").isEmpty()) {
                changes.add(new Change("master", now.get("master"), was.get("master"), -1, 0, 0));
                significant = true;
            }
            int before = parseInt(was.get("books"));
            if (books >= before + 3) changes.add(new Change("revised", "", "", -1, books, before));
        }
        Villages.Age age = Villages.ageOf(id);
        String date = Seasons.season(id, c.day).word + ", the town's " + TownCalendar.ordinal(Seasons.year(id, c.day)) + " year";
        List<Made> made = List.of(new Made("books", books, fresh, 0, -1), new Made("readings", reads, 0, 0, -1), new Made("loans", lent, 0, 0, -1));
        Facts f = new Facts(Villages.name(id), "LIBRARY", c.day, date, last == null ? 1 : last.edition + 1, earlier, master,
            List.of(new Hand(m.displayNameCap(), 0, "the librarian")), made, 1, fresh, 0, most == null ? 0 : most.reads, -1,
            most == null ? "" : most.title, reads, 0, lent, List.of(), age.label, -1, "", 0, notes, changes);
        return new Draft(StationTask.NONE, m, f, line, changes, significant);
    }

    private static String line(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue().replace(";", " ").replace("=", " "));
        }
        return sb.toString();
    }

    static Map<String, String> parse(String line) {
        Map<String, String> out = new HashMap<>();
        if (line == null || line.isEmpty()) return out;
        for (String part : line.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) out.put(part.substring(0, eq), part.substring(eq + 1));
        }
        return out;
    }

    private static int parseInt(@Nullable String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    // ------------------------------------------------------------------ when an edition is due

    /**
     * The trade whose book most wants writing now (one with none first, then the one whose facts have moved on
     * furthest), with its draft; or null. {@code always}: never wait the days between editions (the tests).
     */
    @Nullable
    static Draft due(Ctx c, LibraryRecords.Shelf shelf, boolean always) {
        Draft first = null, change = null;
        for (StationTask t : trades(c)) {
            if (t == StationTask.NONE) continue;
            Draft d = draft(c, t, shelf);
            if (d == null) continue;
            LibraryRecords.Title last = shelf.tradeBook(t.name());
            if (last == null) {
                if (first == null) first = d;
                continue;
            }
            long since = c.day - last.written;
            if (since < 1 && !always) continue;                                 // one edition a day at most
            boolean moved = !d.changes().isEmpty();
            boolean go = d.significant() && (always || since >= GAP) || moved && since >= SMALL_GAP
                || since >= STALE && !d.line().equals(last.facts);
            if (go && (change == null || d.changes().size() > change.changes().size())) change = d;
        }
        // [weave] The librarian keeps the library's own book, on the same terms as a trade's.
        Draft lib = librarian(c, shelf);
        if (lib != null) {
            LibraryRecords.Title last = shelf.tradeBook("LIBRARY");
            long since = last == null ? 0 : c.day - last.written;
            if (last == null) {
                if (first == null) first = lib;
            } else if (since >= 1 || always) {
                boolean go = lib.significant() && (always || since >= GAP) || !lib.changes().isEmpty() && since >= SMALL_GAP
                    || since >= STALE && !lib.line().equals(last.facts);
                if (go && change == null) change = lib;
            }
        }
        return first != null ? first : change;
    }

    /** [weave] Tests: the librarian's book's draft now, or null. */
    @Nullable
    static Draft librarianOf(ServerLevel level, Villages.Village v, LibraryRecords.Shelf shelf) {
        return librarian(new Ctx(level, v, level.getDayTime() / 24000L), shelf);
    }

    /** The draft of one trade's book now (the tests, the stage), or null if it has no master. */
    @Nullable
    static Draft draftOf(ServerLevel level, Villages.Village v, StationTask t, LibraryRecords.Shelf shelf) {
        return draft(new Ctx(level, v, level.getDayTime() / 24000L), t, shelf);
    }

    /** The book, written out of a draft. */
    static Quill.Book write(Draft d) {
        return TradeBookWriter.write(d.facts());
    }

    /** What changed, in a line for the chronicle: "a new record, the Iron Age and a death". */
    static String changeWords(List<Change> ch) {
        List<String> w = new ArrayList<>();
        for (Change c : ch) {
            String s = switch (c.kind()) {
                case "master" -> "a new master";
                case "age" -> c.now();
                case "record" -> "a new record";
                case "lesson" -> "a hard lesson";
                case "research" -> "the town's research";
                case "hands" -> "more hands";
                case "top" -> c.now();
                default -> "";
            };
            if (!s.isEmpty() && !w.contains(s)) w.add(s);
        }
        return Quill.list(w);
    }

}
