package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.LibraryRecords;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * [interviews] The posts a town fills at interview, and what each one is: who may stand for it, who wants it, how the
 * town weighs a candidate for it on paper, who sits on its panel as the post's master, and how the post is given once
 * the panel has chosen.
 *
 * <ul>
 * <li><b>A notice on the board</b> (opening:N): JobMarket's weighing is the paper, and a notice for a new workplace's
 *     first keeper (the new smithy wants a blacksmith) hears the town's own folk who want it as well as the applicants
 *     from the other towns.</li>
 * <li><b>The posts the town gives its own</b>, each held open for its interview when it falls vacant (the subsystem's own
 *     choosing waits on it, and then takes the panel's choice): the schoolteacher (School), the librarian (Library),
 *     the ferryman (Ferries), the bank's clerk (Bank), the steward of a player who leads (PlayerLeader), a place on
 *     the cave team (CaveDwellers), the fletcher's place (Fletchers), the golem keeper's (Golems) and [cartographer] the
 *     cartographer's (Cartographers).</li>
 * <li><b>The posts the town reckons each day</b> from who is best, which the panel's choice then keeps while it is fit
 *     for them: the constable of the watch (Inquiry), the leader of the cave team (CaveDwellers), the auctioneer once
 *     the auction house stands (Auctions), and the master of a trade that takes apprentices (PlayerTrades), when the old
 *     master retires or dies and two of level twenty-five are left at it.</li>
 * </ul>
 * There is no captain of the watch in the town's books: its constable stands at the head of it.
 */
final class InterviewPosts {

    private InterviewPosts() {}

    enum Kind { OPENING, TEACHER, LIBRARIAN, CONSTABLE, CAVE_LEADER, CAVE_PLACE, FERRYMAN, AUCTIONEER, BANKER, STEWARD, MASTER, FLETCHER,
        GOLEM_KEEPER, CARTOGRAPHER,
        TRADER }                                                        // [emerald] the emerald trader's place

    /** A post: its kind, its key in the books, the trade it is of (for the questions and the master), a notice's number. */
    record Post(Kind kind, String key, @Nullable StationTask trade, int opening) {

        /** A post by its key: "teacher", "master:SMITH", "opening:7"; null for none we know. */
        @Nullable
        static Post of(@Nullable String key) {
            if (key == null || key.isEmpty()) return null;
            if (key.startsWith("opening:")) {
                try {
                    return new Post(Kind.OPENING, key, null, Integer.parseInt(key.substring(8)));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            if (key.startsWith("master:")) {
                StationTask t = JobMarket.named(key.substring(7));
                return t == null ? null : new Post(Kind.MASTER, key, t, -1);
            }
            return switch (key) {
                case "teacher" -> new Post(Kind.TEACHER, key, null, -1);
                case "librarian" -> new Post(Kind.LIBRARIAN, key, null, -1);
                case "constable" -> new Post(Kind.CONSTABLE, key, StationTask.GUARD, -1);
                case "caveleader" -> new Post(Kind.CAVE_LEADER, key, StationTask.CAVE, -1);
                case "caveplace" -> new Post(Kind.CAVE_PLACE, key, StationTask.CAVE, -1);
                case "ferryman" -> new Post(Kind.FERRYMAN, key, StationTask.FERRY, -1);
                case "auctioneer" -> new Post(Kind.AUCTIONEER, key, StationTask.SHOP, -1);
                case "banker" -> new Post(Kind.BANKER, key, StationTask.BANK, -1);
                case "steward" -> new Post(Kind.STEWARD, key, null, -1);
                case "fletcher" -> new Post(Kind.FLETCHER, key, StationTask.FLETCHER, -1);
                case "golemkeeper" -> new Post(Kind.GOLEM_KEEPER, key, StationTask.GOLEMS, -1);
                case Cartographers.POST -> new Post(Kind.CARTOGRAPHER, key, StationTask.CARTOGRAPHER, -1);   // [cartographer]
                case EmeraldTrader.POST_KEY -> new Post(Kind.TRADER, key, StationTask.EMERALD, -1);   // [emerald]
                default -> null;
            };
        }

        static Post opening(JobMarket.Opening o) {
            return new Post(Kind.OPENING, "opening:" + o.id, o.task(), o.id);
        }

        static Post master(StationTask t) {
            return new Post(Kind.MASTER, "master:" + t.name(), t, -1);
        }

        /** The post in words: "schoolteacher", "master smith", "blacksmith". */
        String title(UUID village) {
            return switch (kind) {
                case OPENING -> {
                    JobMarket.Opening o = JobMarket.opening(village, opening);
                    yield o == null ? "hand" : o.title();
                }
                case TEACHER -> "schoolteacher";
                case LIBRARIAN -> "town librarian";
                case CONSTABLE -> "constable";
                case CAVE_LEADER -> "leader of the cave team";
                case CAVE_PLACE -> "place on the cave team";
                case FERRYMAN -> "ferryman";
                case AUCTIONEER -> "auctioneer";
                case BANKER -> "bank clerk";
                case STEWARD -> "steward";
                case FLETCHER -> "fletcher";
                case GOLEM_KEEPER -> "golem keeper";
                case CARTOGRAPHER -> "cartographer";                        // [cartographer]
                case TRADER -> "emerald trader";                        // [emerald]
                case MASTER -> "master " + (trade == null ? "hand" : JobMarket.noun(trade));
            };
        }

        /** The trade its questions are about: a notice's own, the post's, or (a teacher, a steward) none. */
        @Nullable
        StationTask tradeFor(UUID village) {
            if (kind == Kind.OPENING) {
                JobMarket.Opening o = JobMarket.opening(village, opening);
                return o == null ? null : o.task();
            }
            return trade;
        }

        /** A big post: a councillor sits on its panel. */
        boolean big() {
            return kind == Kind.CONSTABLE || kind == Kind.STEWARD || kind == Kind.BANKER || kind == Kind.TEACHER || kind == Kind.CAVE_LEADER;
        }

        /** A post that meets the town every day (a friendly folk wants it, a shy one shrinks from it). */
        boolean meetsFolk() {
            return kind == Kind.TEACHER || kind == Kind.LIBRARIAN || kind == Kind.STEWARD || kind == Kind.AUCTIONEER
                || kind == Kind.FERRYMAN || kind == Kind.BANKER || trade == StationTask.SHOP || trade == StationTask.COOK
                || kind == Kind.TRADER;                                 // [emerald] a stranger at every stall
        }

        /** What a folk who cares for it would see in the post. */
        Values.Value value() {
            return switch (kind) {
                case TEACHER -> Values.Value.PROGRESS;
                case LIBRARIAN -> Values.Value.TRADITION;
                case CONSTABLE -> Values.Value.SAFETY;
                case CAVE_LEADER, CAVE_PLACE -> Values.Value.PROGRESS;
                case FERRYMAN, AUCTIONEER, BANKER -> Values.Value.WEALTH;
                case STEWARD -> Values.Value.HOMES;
                case FLETCHER, GOLEM_KEEPER -> Values.Value.SAFETY;
                case CARTOGRAPHER -> Values.Value.PROGRESS;                 // [cartographer] knowing the land
                case TRADER -> Values.Value.WEALTH;                     // [emerald]
                default -> {
                    Values.Value v = trade == null ? null : Values.taughtBy(trade);
                    yield v == null ? Values.Value.WEALTH : v;
                }
            };
        }

        /** Where its work is done, for the board's notice: "the school", "the watch". */
        String place() {
            return switch (kind) {
                case TEACHER -> "the school";
                case LIBRARIAN -> "the library";
                case CONSTABLE -> "the watch";
                case CAVE_LEADER, CAVE_PLACE -> "the caves";
                case FERRYMAN -> "the ferry";
                case AUCTIONEER -> "the auction house";
                case BANKER -> "the bank";
                case STEWARD -> "the hall";
                case FLETCHER -> "the fletcher's hut";
                case GOLEM_KEEPER -> "the golem yard";
                case CARTOGRAPHER -> "the map room";                        // [cartographer]
                case TRADER -> "the Trading Post";                      // [emerald]
                default -> trade == null ? "the town" : JobMarket.workWords(trade);
            };
        }
    }

    // ------------------------------------------------------------------ who may stand

    /** Grown, of the town, about, and free: no child, no stand-in, no hired hand, nobody away or in another interview. */
    static boolean about(VillageFolkEntity f) {
        return f.isAlive() && !f.isBaby() && !f.isShowcase() && !f.isHired() && f.trip() == null && f.expedition() == null
            && !JobSeekers.travelling(f) && !Health.laidUp(f) && !Visitors.is(f);
    }

    /** May this folk of the town stand for the post (as the post's own choosing would have it)? */
    static boolean eligible(Post p, VillageFolkEntity f, UUID village) {
        if (!about(f) || !village.equals(f.ownerId())) return false;
        StationTask t = f.stationTask();
        return switch (p.kind()) {
            case TEACHER -> School.fit(f);
            case LIBRARIAN -> !f.isElder() && t != StationTask.GUARD && t != StationTask.SCOUT && !Museum.isCurator(f);
            case CONSTABLE -> t == StationTask.GUARD;
            case CAVE_LEADER -> t == StationTask.CAVE;
            case CAVE_PLACE -> !f.isElder() && CaveDwellers.spare(village, t) && f.ageYears() >= 18 && f.ageYears() <= 50;
            case FERRYMAN -> t == StationTask.FERRY || (t == StationTask.NONE || t == StationTask.FISH || Villages.share(village, t) >= 0.5)
                && !t.isCraft() && t != StationTask.GUARD && t != StationTask.STORE && t != StationTask.HAUL && t != StationTask.CAVE
                && t != StationTask.SCOUT && t != StationTask.BANK;
            case AUCTIONEER -> Fleet.fit(f);
            case BANKER -> t == StationTask.BANK || t == StationTask.NONE || Villages.overStaffed(village, t)
                || !t.isCraft() && t != StationTask.STORE && t != StationTask.GUARD && t != StationTask.CAVE && hands(village, t) >= 3;
            case STEWARD -> {
                UUID leader = PlayerLeader.leaderId(village);
                boolean council = false;
                for (VillageFolkEntity m : Council.members(village)) if (m == f) council = true;
                yield council && (leader == null || !leader.equals(f.getUUID()));
            }
            case MASTER -> p.trade() != null && t == p.trade() && f.tradeLevel(t) >= Lessons.MASTER;
            // As the trade's own appointing weighs them (a hand its trade can spare, never the watch or a craft's own).
            case FLETCHER -> t != StationTask.FLETCHER && Fletchers.fitness(f, village) != Integer.MIN_VALUE;
            case GOLEM_KEEPER -> t != StationTask.GOLEMS && Golems.fitness(f, village) != Integer.MIN_VALUE;
            case CARTOGRAPHER -> Cartographers.fit(f, village);              // [cartographer] as the town's own choosing has it
            case TRADER -> t != StationTask.EMERALD && EmeraldTrader.fitness(f, village) != Integer.MIN_VALUE;   // [emerald]
            case OPENING -> {
                StationTask want = p.tradeFor(village);
                // One of the town's own for a new workplace: not its elder, not a hand its own trade cannot spare.
                yield want != null && t != want && !f.isElder() && !JobMarket.neededAtHome(f)
                    && (f.tradeLevel(want) > 0 || t == StationTask.NONE);
            }
        };
    }

    private static int hands(UUID village, StationTask t) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == t) n++;
        return n;
    }

    // ------------------------------------------------------------------ on paper

    /**
     * How the town weighs a candidate for the post on paper, in the interview's own points (ten to a level at a trade):
     * the post's own reckoning, scaled so the interview's twelve either way can lift a slightly weaker candidate but
     * not a much weaker one.
     */
    static int paper(Post p, VillageFolkEntity f, UUID village, List<String> good) {
        StationTask t = p.tradeFor(village);
        switch (p.kind()) {
            case TEACHER -> {
                int s = School.score(f) * 4;
                good.add(School.whyTeacher(f));
                return s;
            }
            case LIBRARIAN -> {
                double s = f.ageYears() * 0.3;
                if (f.persona().rolled() && f.persona().hobby() == Persona.Hobby.READING) { s += 30; good.add("a great reader"); }
                if (f.life().has(Social.Trait.CURIOUS)) { s += 20; good.add("curious"); }
                if (f.persona().quirk().equals("keeps a diary")) { s += 10; good.add("keeps a diary"); }
                if (School.isTeacher(f)) { s += 15; good.add("the teacher"); }
                return (int) Math.round(s / 2.0);
            }
            case CONSTABLE -> {
                int lv = f.tradeLevel(StationTask.GUARD), kills = f.deedCount(AssistantEntity.Deed.MOBS_KILLED);
                good.add("level " + lv + " on the watch");
                if (kills > 0) good.add(kills + " hostiles killed");
                return lv * 10 + Math.min(20, kills / 3);
            }
            case CAVE_LEADER -> {
                int lv = f.tradeLevel(StationTask.CAVE);
                good.add("level " + lv + " in the caves");
                return lv * 10 + Math.min(19, f.lifetimeXp() / 1000);
            }
            case CAVE_PLACE -> {
                int sk = CaveDwellers.skill(f);
                good.add("level " + sk + " at the rock and the blade");
                return sk * 10 + (f.life().has(Social.Trait.CURIOUS) ? 3 : 0);
            }
            case FERRYMAN -> {
                int s = f.tradeLevel(StationTask.FERRY) * 10 + f.tradeLevel(StationTask.FISH) * 4
                    + (f.life().has(Social.Trait.SOCIABLE) ? 8 : 0) + (f.life().has(Social.Trait.EASYGOING) ? 4 : 0);
                if (f.tradeLevel(StationTask.FISH) > 0) good.add("knows a boat (level " + f.tradeLevel(StationTask.FISH) + " fishing)");
                return s;
            }
            case AUCTIONEER -> {
                int s = f.tradeLevel(StationTask.SHOP) * 6 + (f.life().has(Social.Trait.SOCIABLE) ? 10 : 0)
                    + Values.weight(f, Values.Value.WEALTH) / 5 + (FolkSkills.active(f, FolkSkills.Knack.HAGGLER) ? 8 : 0);
                if (f.life().has(Social.Trait.SOCIABLE)) good.add("a voice for a crowd");
                return s;
            }
            case BANKER -> {
                good.add("careful with coin");
                return Bank.bankerScore(f) * 5;
            }
            case STEWARD -> {
                int sum = 0;
                for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity o && o != f) sum += o.life().affinity(f.getUUID());
                good.add("well thought of");
                return sum / 10;
            }
            case MASTER -> {
                int lv = t == null ? 0 : f.tradeLevel(t);
                good.add("level " + lv + " at " + (t == null ? "the trade" : t.label));
                return lv * 10;
            }
            case FLETCHER -> {
                int lv = f.tradeLevel(StationTask.FLETCHER), hunt = f.tradeLevel(StationTask.HUNT);
                if (lv > 0) good.add("level " + lv + " at fletching");
                if (hunt > 0) good.add("knows a bow (level " + hunt + " hunting)");
                if (f.life().has(Social.Trait.SHY)) good.add("quiet, careful hands");
                return Math.max(0, Fletchers.fitness(f, village));
            }
            case GOLEM_KEEPER -> {
                int lv = f.tradeLevel(StationTask.GOLEMS), iron = f.tradeLevel(StationTask.MINE) + f.tradeLevel(StationTask.SMELT);
                if (lv > 0) good.add("level " + lv + " at keeping golems");
                if (iron > 0) good.add("used to iron (level " + iron + " at the mine and the furnace)");
                if (f.life().has(Social.Trait.HARDWORKING)) good.add("hardworking");
                return Math.max(0, Golems.fitness(f, village));
            }
            case CARTOGRAPHER -> {
                // [cartographer] As the town's own choosing weighs them (Cartographers.score): the land, a curious mind, patience.
                int lv = f.tradeLevel(StationTask.CARTOGRAPHER), scout = f.tradeLevel(StationTask.SCOUT);
                if (lv > 0) good.add("level " + lv + " at map-making");
                if (f.stationTask() == StationTask.SCOUT || scout > 0) good.add("knows the land (level " + scout + " scouting)");
                if (f.life().has(Social.Trait.CURIOUS)) good.add("has to know what's round the next bend");
                if (f.life().has(Social.Trait.SHY)) good.add("patient, happy alone with a sheet");
                return Math.max(0, Cartographers.score(f));
            }
            case TRADER -> {                                            // [emerald] as the town reckons a trader
                int lv = f.tradeLevel(StationTask.EMERALD), sc = f.tradeLevel(StationTask.SCOUT);
                if (lv > 0) good.add("level " + lv + " at trading with the villagers");
                if (sc > 0) good.add("knows the road (level " + sc + " scouting)");
                if (f.life().has(Social.Trait.SOCIABLE)) good.add("talks to anybody");
                if (f.life().has(Social.Trait.CHEERFUL)) good.add("cheerful company");
                return Math.max(0, EmeraldTrader.fitness(f, village));
            }
            case OPENING -> {
                int lv = t == null ? 0 : f.tradeLevel(t), kn = JobMarket.knacks(f, t);
                if (lv > 0) good.add("level " + lv + " at " + t.label);
                if (kn > 0) good.add(kn + (kn == 1 ? " knack" : " knacks") + " of the trade");
                good.add("one of our own");
                return lv * 10 + kn * 8 + 6;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ who wants it

    /**
     * How much a folk wants the post, by its ambition, what it cares about, its nature and its spirits, and its hand at
     * the post's trade. Three and more, it puts itself forward.
     */
    static int desire(Post p, VillageFolkEntity f, UUID village) {
        Persona me = f.persona();
        Social.Life life = f.life();
        int d = 0;
        if (me.rolled()) {
            if (me.ambition() == Persona.Ambition.MASTER) d += p.kind() == Kind.MASTER || p.kind() == Kind.OPENING ? 3 : 1;
            if (me.ambition() == Persona.Ambition.FRIENDS && p.meetsFolk()) d += 2;
            if ((me.ambition() == Persona.Ambition.GREAT_WORK || me.ambition() == Persona.Ambition.NETHER)
                && (p.kind() == Kind.STEWARD || p.kind() == Kind.CAVE_LEADER || p.kind() == Kind.CAVE_PLACE)) d += 1;
            if (me.mood() >= 65) d += 1;
            if (me.mood() < 35) d -= 2;
        }
        d += Values.weight(f, p.value()) / 25;
        if (life.has(Social.Trait.HARDWORKING)) d += 1;
        if (p.meetsFolk() && life.has(Social.Trait.SOCIABLE)) d += 1;
        if (p.meetsFolk() && life.has(Social.Trait.SHY)) d -= 1;
        if (p.big() && life.has(Social.Trait.EASYGOING)) d -= 1;
        StationTask t = p.tradeFor(village);
        if (t != null && f.tradeLevel(t) >= 5) d += 1;
        return d;
    }

    static final int PUTS_ITSELF_FORWARD = 3;

    /**
     * The candidates: the town's own who may stand and want the post (the one the post's own choosing picked always
     * stands), best on paper first, four at most. Forced (the operator's stage): the best three who may stand, wanting it
     * or not.
     */
    static List<VillageFolkEntity> candidates(ServerLevel level, Villages.Village v, Post p, @Nullable VillageFolkEntity must,
                                              boolean forced, Set<UUID> not) {
        UUID id = v.id();
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || not.contains(f.getUUID()) || Interviews.busy(f)) continue;
            if (!eligible(p, f, id)) continue;
            if (f != must && !forced && desire(p, f, id) < PUTS_ITSELF_FORWARD && !Interviews.KEEN_FOR_TESTS.contains(f.getUUID())) continue;
            out.add(f);
        }
        if (must != null && !out.contains(must) && must.isAlive()) out.add(must);
        out.sort(Comparator.comparingInt((VillageFolkEntity f) -> -paper(p, f, id, new ArrayList<>())));
        int most = forced ? 3 : 4;
        while (out.size() > most) {
            VillageFolkEntity last = out.get(out.size() - 1);
            if (last == must && out.size() >= 2) out.remove(out.size() - 2);
            else out.remove(out.size() - 1);
        }
        return out;
    }

    // ------------------------------------------------------------------ the post, now

    /** Does the town have the post at all just now? */
    static boolean stands(ServerLevel level, Villages.Village v, Post p) {
        UUID id = v.id();
        return switch (p.kind()) {
            case TEACHER -> School.stands(id);
            case LIBRARIAN -> Library.stands(id);
            case CONSTABLE -> Villages.ageOf(id).ordinal() >= Villages.Age.IRON.ordinal() && Patrols.watch(id).size() >= 2;
            case CAVE_LEADER -> CaveDwellers.dwellers(id).size() >= 2;
            case CAVE_PLACE -> CaveDwellers.wanted(id) > 0;
            case FERRYMAN -> Ferries.crossing(id) != null;
            case AUCTIONEER -> Auctions.house(id) != null;
            case BANKER -> Bank.building(id) != null;
            case STEWARD -> PlayerLeader.leaderId(id) != null;
            case MASTER -> p.trade() != null && Lessons.of(p.trade()) != null;
            case FLETCHER -> Fletchers.wanted(id) && Fletchers.fletchers(id).size() < Fletchers.hands(id);
            case GOLEM_KEEPER -> Golems.wanted(id) && !Golems.keeps(id);
            case CARTOGRAPHER -> Cartographers.ready(id) && Cartographers.cartographer(id) == null;   // [cartographer] the map room waits
            case TRADER -> EmeraldTrader.placeOpen(id);                 // [emerald]
            case OPENING -> {
                JobMarket.Opening o = JobMarket.opening(id, p.opening());
                yield o != null && o.state() == JobMarket.State.OPEN;
            }
        };
    }

    /** Who holds it now, if anybody (as the post's own books have it). */
    @Nullable
    static VillageFolkEntity holder(ServerLevel level, Villages.Village v, Post p) {
        UUID id = v.id();
        return switch (p.kind()) {
            case TEACHER -> School.teacher(level, id, false);
            case LIBRARIAN -> {
                UUID l = LibraryRecords.has(id) ? LibraryRecords.shelf(id).librarian : null;
                yield l == null ? null : Elections.loaded(id, l);
            }
            case CONSTABLE -> Inquiry.constable(id);
            case CAVE_LEADER -> CaveDwellers.leaderOf(id);
            case FERRYMAN -> {
                Ferries.Crossing c = Ferries.crossing(id);
                yield c == null ? null : Ferries.ferrymanOf(level, c);
            }
            case AUCTIONEER -> Auctions.auctioneerFor(level, v);
            case BANKER -> Bank.banker(id);
            case STEWARD -> PlayerLeader.steward(id);
            case MASTER -> p.trade() == null ? null : PlayerTrades.masterOf(id, p.trade());
            case GOLEM_KEEPER -> Golems.keeper(id);
            case CARTOGRAPHER -> Cartographers.cartographer(id);            // [cartographer]
            case CAVE_PLACE, FLETCHER, TRADER, OPENING -> null;
        };
    }

    /**
     * The post's master on the panel: its holder stepping aside, or the best hand at its trade in the town (never one
     * standing, nor the chair). Null if there is none: a councillor sits instead.
     */
    @Nullable
    static VillageFolkEntity master(ServerLevel level, Villages.Village v, Post p, Set<UUID> not) {
        UUID id = v.id();
        StationTask t = p.tradeFor(id);
        if (p.kind() == Kind.TEACHER || p.kind() == Kind.LIBRARIAN) {
            // The other of the two learned posts: the librarian for a teacher, the teacher for a librarian.
            VillageFolkEntity other = p.kind() == Kind.TEACHER ? holder(level, v, Post.of("librarian")) : School.teacher(level, id, false);
            if (other != null && !not.contains(other.getUUID()) && about(other)) return other;
            t = null;
        }
        if (t == null) return null;
        VillageFolkEntity best = null;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || not.contains(f.getUUID()) || !about(f) || Interviews.busy(f)) continue;
            if (f.tradeLevel(t) <= 0) continue;
            if (best == null || f.tradeLevel(t) > best.tradeLevel(t)) best = f;
        }
        return best;
    }

    // ------------------------------------------------------------------ giving it

    /**
     * The panel's choice takes up the post, by the post's own appointing (which takes the chosen first: Interviews.
     * preferred), and the one holding it till now (if not the chosen) steps down. Says what was done, in words.
     */
    static String give(ServerLevel level, Villages.Village v, Post p, VillageFolkEntity winner) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        switch (p.kind()) {
            case TEACHER -> {
                School.appoint(level, id, winner);
                return "teaches at the school";
            }
            case LIBRARIAN -> {
                if (!LibraryRecords.has(id)) return "will keep the library once it opens";
                LibraryRecords.Shelf shelf = LibraryRecords.shelf(id);
                shelf.librarian = null;
                VillageFolkEntity got = Library.librarian(level, id, shelf, day);
                return got == winner ? "keeps the library" : "is to keep the library";
            }
            case FERRYMAN -> {
                Ferries.Crossing c = Ferries.crossing(id);
                if (c == null) return "will row the ferry";
                VillageFolkEntity was = Ferries.ferrymanOf(level, c);
                if (was != null && was != winner) stepDown(was, id);
                Ferries.appoint(level, v, c);
                return "rows the ferry";
            }
            case BANKER -> {
                VillageFolkEntity was = Bank.banker(id);
                if (was != null && was != winner) stepDown(was, id);
                Bank.appoint(level, v, day);
                return "keeps the bank";
            }
            case STEWARD -> {
                Ledger.forget(id, "civic.steward");
                PlayerLeader.steward(id);
                return "is the leader's steward";
            }
            case CAVE_PLACE -> {
                VillageFolkEntity got = CaveDwellers.appoint(level, v, day);
                return got == winner ? "joined the cave team" : "is to join the cave team";
            }
            case FLETCHER -> {
                // The trade's own appointing, which takes the panel's choice first (Fletchers.shortlist: Interviews.preferred).
                VillageFolkEntity got = Fletchers.appoint(level, v);
                return got == winner ? "took up fletching, the watch's arrows its to make" : "is to take up fletching";
            }
            case GOLEM_KEEPER -> {
                VillageFolkEntity got = Golems.appoint(level, v);
                return got == winner ? "keeps the town's golems" : "is to keep the town's golems";
            }
            case CARTOGRAPHER -> {
                // [cartographer] The town's own appointing, which takes the panel's choice first (Cartographers.candidates).
                if (Cartographers.cartographer(id) != null) return "is to keep the map room";
                VillageFolkEntity got = Cartographers.appoint(level, v, day);
                return got == winner ? "keeps the map room, the town's maps its to draw" : "is to keep the map room";
            }
            case TRADER -> {
                // [emerald] The trade's own appointing, which takes the panel's choice first (EmeraldTrader.candidates).
                VillageFolkEntity got = EmeraldTrader.appoint(level, v);
                return got == winner ? "trades with the villagers for the town" : "is to trade with the villagers";
            }
            case CONSTABLE -> { return "leads the watch as its constable"; }
            case CAVE_LEADER -> { return "leads the cave team"; }
            case AUCTIONEER -> { return "calls the auctions"; }
            case MASTER -> { return "is the town's " + p.title(id) + ", and takes its apprentices"; }
            case OPENING -> { return ""; }
        }
        return "";
    }

    /** One holding a post till now steps down to the work the town is shortest of that it knows best. */
    private static void stepDown(VillageFolkEntity f, UUID village) {
        StationTask next = JobMarket.fitFor(f, village);
        f.setWorkZone(null);
        f.setJob(next == StationTask.NONE ? StationTask.FARM : next);
        f.brain("stepped down for the one the interview chose; back to " + f.stationTask().label);
    }

    /** "teacher", "librarian"… and every trade's word, for the operator's stage. */
    static List<String> keys() {
        List<String> out = new ArrayList<>(List.of("teacher", "librarian", "constable", "caveleader", "caveplace", "ferryman",
            "auctioneer", "banker", "steward", "golemkeeper", EmeraldTrader.POST_KEY));
        for (StationTask t : StationTask.values()) {
            if (t == StationTask.NONE) continue;
            out.add(t.name().toLowerCase(Locale.ROOT));
            if (Lessons.of(t) != null) out.add("master_" + t.name().toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
