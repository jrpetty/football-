package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [identity] How a town is ruled. Chosen at its founding from its character (Ethos), and changeable by the town's
 * vote (a referendum to change its government, Referendums) or by a crisis (a lord the town has had enough of, voted
 * out). Each form decides who leads, who votes, how fast, and what the leader is called:
 * <ul>
 * <li><b>An elected leader</b> (the land's own title: harbourmaster, thane, reeve): elections every ten days, as
 *     ever (Elections), every grown folk voting — only householders, in a hierarchical town. The leader decides the
 *     town's laws (LawBook) by its own lights.</li>
 * <li><b>A council of elders.</b> Only the eldest five vote, and only from among themselves: the speaker of the
 *     elders, for fourteen days at a time. Its orders come every five days, and stick (Orders); its laws are voted on
 *     by the council, every sixth day at most. Slower, steadier.</li>
 * <li><b>A hereditary lord.</b> No elections. When the lord dies its eldest grown child succeeds; with none, its
 *     partner holds the seat; with nobody of the line, the town's most esteemed founds a new house. The chronicle
 *     keeps the line. A lord the town is miserable under three mornings running faces a vote of no confidence: carried,
 *     the house is out and the town elects its leader. A player can come to lead a lordship only by marrying into the
 *     ruling house (the lord's consort takes the seat at the lord's death: "Will you marry me?", to an unwed lord or
 *     heir who thinks the world of you) or by that vote to depose it, and then standing.</li>
 * <li><b>A guild republic.</b> The masters of the trades (level ten and over) elect the guildmaster; on the tariffs
 *     the traders vote, on the hunting the hunters (LawBook); its leader leans to the market.</li>
 * <li><b>A commune.</b> Everything big is put to the whole town: every change to its laws goes to a referendum. Its
 *     steward is elected every seven days, and everybody is paid the same (Ethos.wage).</li>
 * <li><b>The chaplain's rule.</b> The town's most devout leads it as its chaplain, chosen by the chapel at a death and
 *     never voted on; the chapel is wanted early (Ethos.extras), and there is a feast day mid-week as well as the
 *     usual one (Gatherings).</li>
 * </ul>
 */
public final class Government {

    private Government() {}

    public enum Form {
        REEVE("an elected leader", "elected every ten days by every grown folk"),
        COUNCIL("a council of elders", "the eldest five choose a speaker among themselves every fourteen days, and vote on the laws"),
        LORD("a hereditary lord", "the lord rules for life, and the eldest child succeeds"),
        GUILD("a guild republic", "the masters of the trades elect the guildmaster, and each trade votes on its own matters"),
        COMMUNE("a commune", "the whole town votes on everything big; the steward is elected every seven days"),
        CHAPLAIN("the chaplain's rule", "the town's most devout leads it as its chaplain, chosen by the chapel");

        public final String words, how;

        Form(String words, String how) {
            this.words = words;
            this.how = how;
        }

        @Nullable
        public static Form named(String s) {
            try { return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
        }
    }

    /** How the next leader came to the seat, for the chronicle's line when it is installed (installedLine). */
    private static final Map<UUID, String> HOW = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ which

    /** The town's government (an elected leader for a town not yet seeded, or a test's plain town). */
    public static Form of(@Nullable UUID village) {
        if (Identity.neutral()) return Form.REEVE;
        Identity.Rec r = Identity.known(village);
        return r == null ? Form.REEVE : r.gov;
    }

    /** How well a form suits a town of this character. */
    static double fit(Form f, int[] a) {
        double t = a[Ethos.Axis.TRADE.ordinal()], w = a[Ethos.Axis.WAR.ordinal()], fa = a[Ethos.Axis.FAITH.ordinal()],
            l = a[Ethos.Axis.LEARNING.ordinal()], d = a[Ethos.Axis.DOORS.ordinal()], y = a[Ethos.Axis.WAYS.ordinal()],
            k = a[Ethos.Axis.RANK.ordinal()];
        return switch (f) {
            case REEVE -> 12 + 0.15 * d + 0.1 * t;
            case COUNCIL -> 6 + 0.35 * y + 0.1 * l - 0.1 * t;
            case LORD -> -4 + 0.4 * -k + 0.2 * w + 0.1 * y;
            case GUILD -> -4 + 0.35 * t + 0.2 * -l + 0.1 * -k;
            case COMMUNE -> -4 + 0.45 * k + 0.1 * -w + 0.1 * -t;
            case CHAPLAIN -> -4 + 0.45 * fa + 0.15 * y;
        };
    }

    /** The form that suits a town of this character best. */
    static Form choose(int[] a) {
        Form best = Form.REEVE;
        double top = Double.NEGATIVE_INFINITY;
        for (Form f : Form.values()) {
            double x = fit(f, a);
            if (x > top) { top = x; best = f; }
        }
        return best;
    }

    /** Does this form hold elections? */
    public static boolean holdsElections(@Nullable UUID village) {
        Form f = of(village);
        return f != Form.LORD && f != Form.CHAPLAIN;
    }

    /** What the leader is called: the land's title for an elected one, else the form's. */
    public static String title(@Nullable UUID village) {
        return switch (of(village)) {
            case REEVE -> Homeland.leaderTitle(village);
            case COUNCIL -> "speaker of the elders";
            case LORD -> "lord";
            case GUILD -> "guildmaster";
            case COMMUNE -> "steward";
            case CHAPLAIN -> "chaplain";
        };
    }

    /** "an elected harbourmaster", "Lord Fen of the House of Bryn", "a council of elders". */
    static String phrase(UUID village, Identity.Rec r) {
        return switch (r.gov) {
            case REEVE -> "an elected " + Homeland.leaderTitle(village);
            case LORD -> {
                String lord = Villages.elderName(village);
                yield lord.isEmpty() ? "a hereditary lord" + (r.house.isEmpty() ? "" : " of " + r.house) : "Lord " + lord
                    + (r.house.isEmpty() ? "" : " of " + r.house);
            }
            default -> r.gov.words;
        };
    }

    // ------------------------------------------------------------------ the founding and the mornings

    /** At its founding: the form its character suits, and for a lordship or the chaplain's rule its first leader. */
    static void found(ServerLevel level, Villages.Village v, Identity.Rec r, long day) {
        r.gov = choose(r.axes);
        r.govSince = day;
        if (r.gov == Form.LORD) {
            VillageFolkEntity first = Identity.leader(v.id());
            if (first == null) first = esteemed(v.id(), null);
            if (first != null) {
                r.house = "the House of " + first.displayNameCap();
                install(level, v.id(), r, first, day, "first of " + r.house);
            }
        } else if (r.gov == Form.CHAPLAIN) {
            VillageFolkEntity c = devout(v.id(), null);
            if (c != null) install(level, v.id(), r, c, day, "the most devout of the founders");
        }
    }

    /**
     * Each morning: a lordship or the chaplain's rule with nobody in the seat gets somebody; a lord the town is
     * miserable under three mornings running faces a vote of no confidence; and, a fortnight at least after the last
     * change, a town whose character has come to suit another form clearly better is asked whether it wants it.
     */
    static void morning(ServerLevel level, Villages.Village v, Identity.Rec r, long day) {
        UUID id = v.id();
        if (!holdsElections(id) && Villages.elder(id) == null && PlayerLeader.leaderId(id) == null) {
            if (r.gov == Form.LORD) succeed(level, id, r, "", null, day);
            else chaplain(level, id, r, "", null, day);
        }
        if (r.gov == Form.LORD) {
            r.lowDays = Contentment.score(id) < 35 ? r.lowDays + 1 : 0;
            if (r.lowDays >= 3 && !asked(id) && day - r.lastReform >= 7) {
                callDepose(level, v, r, day);
                return;
            }
        }
        if (day - r.lastReform < 14 || day - r.seededOn < 7 || r.gov == Form.LORD || asked(id)) return;
        Form best = choose(r.axes);
        if (best == r.gov || fit(best, r.axes) - fit(r.gov, r.axes) < 15) return;
        callReform(level, v, r, best, day);
    }

    /** Is a question of the town's identity already before the town? */
    static boolean asked(UUID village) {
        for (CompoundTag q : Referendums.open(village)) if (KIND.equals(q.getString("kind"))) return true;
        return false;
    }

    // ------------------------------------------------------------------ who votes

    /**
     * Who may vote (Elections.voters, and so the town's referendums): every grown folk; only the eldest five in a
     * council of elders; only the masters (level ten and more, four at least) in a guild republic; only those with a
     * home of their own in a hierarchical town that elects its leader.
     */
    public static List<VillageFolkEntity> voters(@Nullable UUID village, List<VillageFolkEntity> all) {
        if (Identity.neutral() || Identity.known(village) == null || all.size() <= 4) return all;
        Form f = of(village);
        List<VillageFolkEntity> out = new ArrayList<>(all);
        switch (f) {
            case COUNCIL -> {
                out.sort(Comparator.comparingInt(VillageFolkEntity::ageYears).reversed());
                return new ArrayList<>(out.subList(0, Math.min(5, out.size())));
            }
            case GUILD -> {
                out.sort(Comparator.comparingInt(VillageFolkEntity::veteranLevel).reversed());
                List<VillageFolkEntity> masters = new ArrayList<>();
                for (VillageFolkEntity x : out) if (x.veteranLevel() >= 10 || masters.size() < 4) masters.add(x);
                return masters;
            }
            case REEVE -> {
                if (Ethos.lean(village, Ethos.Axis.RANK) > -50) return all;
                List<VillageFolkEntity> owners = new ArrayList<>();
                for (VillageFolkEntity x : out) if (x.bedPos() != null) owners.add(x);
                return owners.size() >= 4 ? owners : all;
            }
            default -> { return all; }
        }
    }

    /** Days a leader serves (Elections): seven in a commune, fourteen for the elders' speaker, else the usual. */
    public static int term(@Nullable UUID village, int usual) {
        return switch (of(village)) {
            case COMMUNE -> 7;
            case COUNCIL -> 14;
            default -> usual;
        };
    }

    /** Days between the leader's orders (Orders.consider): five for the elders, four for the chaplain. */
    public static int ordersEvery(@Nullable UUID village, int usual) {
        return switch (of(village)) {
            case COUNCIL -> 5;
            case CHAPLAIN -> 4;
            default -> usual;
        };
    }

    /** How much more a standing order is kept (Orders.choose): the elders are steadier. */
    public static int steadiness(@Nullable UUID village) {
        return of(village) == Form.COUNCIL ? 2 : 0;
    }

    /** What the form adds to the leader's choice of order. */
    static void orders(@Nullable UUID village, Map<Orders.Order, Integer> score) {
        switch (of(village)) {
            case COUNCIL -> score.merge(Orders.Order.STEADY, 2, Integer::sum);
            case GUILD -> score.merge(Orders.Order.MARKET, 2, Integer::sum);
            case CHAPLAIN -> score.merge(Orders.Order.STEADY, 1, Integer::sum);
            case COMMUNE -> score.merge(Orders.Order.LARDER, 1, Integer::sum);
            case LORD -> score.merge(Orders.Order.WATCH, 1, Integer::sum);
            default -> { }
        }
    }

    /** A feast day mid-week under the chaplain's rule (Gatherings.tonight), as well as the usual. */
    public static boolean extraFeast(@Nullable UUID village, long day) {
        return village != null && of(village) == Form.CHAPLAIN && day > 0 && day % 7 == 3;
    }

    /** What its form does to the town's spirits (Identity.contentment). */
    static int contentment(UUID village, List<String> good, List<String> bad) {
        Form f = of(village);
        int e = Ethos.lean(village, Ethos.Axis.RANK), d = Ethos.lean(village, Ethos.Axis.FAITH);
        if (f == Form.CHAPLAIN && d >= Ethos.POLE) { good.add("the chapel at the heart of things"); return 2; }
        if (f == Form.COMMUNE && e >= Ethos.POLE) { good.add("all equal in the commune"); return 2; }
        if (f == Form.LORD && e >= Ethos.POLE) { bad.add("a lord over us"); return -3; }
        return 0;
    }

    // ------------------------------------------------------------------ a player who would lead

    /** Why a player may not stand for leader here (Hustings.stand), or null if it may. */
    @Nullable
    public static String standBarred(ServerLevel level, UUID village, Player p) {
        Identity.Rec r = Identity.known(village);
        if (r == null || Identity.neutral()) return null;
        String name = Villages.name(village);
        return switch (r.gov) {
            case LORD -> "There are no elections in " + name + ": it is " + (r.house.isEmpty() ? "its lord's" : r.house + "'s")
                + ". The only ways to the seat are to marry into the house, or for the town to vote its lord out.";
            case CHAPLAIN -> name + " is led by its chaplain, chosen by the chapel, never by a vote.";
            case COUNCIL, GUILD -> Standing.of(village, p.getUUID(), level.getGameTime()).title().atLeast(Standing.Title.HONOURED) ? null
                : r.gov == Form.COUNCIL ? "Only the elders sit on " + name + "'s council; an honoured guest may be asked to sit with them. Be one first."
                : "In " + name + " the masters of the trades choose the guildmaster: be an honoured guest here first.";
            default -> null;
        };
    }

    /** How a player could come to lead here, for the page. */
    static String routes(UUID village, Identity.Rec r) {
        return switch (r.gov) {
            case REEVE, COMMUNE -> "A citizen the town counts a friend may stand at its elections.";
            case COUNCIL -> "An honoured guest may stand before the elders.";
            case GUILD -> "An honoured guest may stand before the masters.";
            case LORD -> "Only by marrying into " + (r.house.isEmpty() ? "the ruling house" : r.house)
                + " (the lord's consort takes the seat at its death), or by a vote of no confidence and then standing.";
            case CHAPLAIN -> "No player leads the chaplain's town; a vote to change its government would open it.";
        };
    }

    /** "Will you marry me?" said to a folk (Identity.talk): the ruling house of a lordship answers; null for anybody else. */
    @Nullable
    static String proposal(VillageFolkEntity f, Player p, String low) {
        if (!(low.contains("marry me") || low.contains("will you marry") || low.contains("wed me"))) return null;
        UUID village = f.ownerId();
        Identity.Rec r = Identity.known(village);
        if (r == null || r.gov != Form.LORD || Identity.neutral()) return null;
        UUID lord = Villages.elder(village);
        boolean isLord = f.getUUID().equals(lord);
        boolean heir = !isLord && lord != null && f.parentIds().contains(lord);
        if (!isLord && !heir) return null;
        if (r.consort != null) return r.consort.equals(p.getUUID()) ? "We're wed already, you and I." : "The house has its consort already.";
        if (f.isBaby()) return "I'm far too young for that!";
        if (f.life().partner() != null) return "I'm promised to " + f.life().partnerName() + " already.";
        if (!Citizens.is(village, p.getUUID())) return "Wed into " + r.house + "? You'd have to live here first.";
        if (!Standing.of(village, p.getUUID(), f.level().getGameTime()).title().atLeast(Standing.Title.HONOURED)) {
            return "You're kind, but the house weds only one the whole town honours.";
        }
        if (f.persona().affinity(p.getUUID()) < 70) return "I like you well enough. Not that well. Not yet.";
        long day = f.level().getDayTime() / 24000L;
        r.consort = p.getUUID();
        r.consortName = p.getName().getString();
        r.consortOf = f.getUUID();
        r.change(day, p.getName().getString() + " was wed into " + r.house + ", to " + f.displayNameCap());
        Identity.dirty();
        Villages.tell(village, day, f.displayNameCap() + " of " + r.house + " and " + p.getName().getString() + " were promised to each other");
        f.persona().remember(day, "I promised myself to " + p.getName().getString(), 10);
        return isLord ? "Yes! Yes, with all my heart. And when I'm gone, the seat of " + r.house + " is yours."
            : "Yes! When I come to the seat you'll be beside me — and after me, it's yours.";
    }

    // ------------------------------------------------------------------ the seat falls empty

    /** The leader is dead (Elections.vacancy): a lord's heir, or the next chaplain, takes the seat; true if it was seen to. */
    public static boolean vacancy(ServerLevel level, UUID village, String who, long day) {
        Identity.Rec r = Identity.known(village);
        if (r == null || Identity.neutral()) return false;
        UUID was = Villages.elder(village);
        if (r.gov == Form.LORD) return succeed(level, village, r, who, was, day);
        if (r.gov == Form.CHAPLAIN) return chaplain(level, village, r, who, was, day);
        return false;
    }

    /**
     * The lord is dead: its consort, if a player wed it; else its eldest grown child; else its partner, to hold the
     * seat; else the town's most esteemed founds a new house. True if somebody took the seat.
     */
    static boolean succeed(ServerLevel level, UUID village, Identity.Rec r, String who, @Nullable UUID was, long day) {
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        if (r.consort != null && was != null && was.equals(r.consortOf)) {
            UUID player = r.consort;
            String name = r.consortName;
            r.consort = null;
            r.consortOf = null;
            HOW.put(village, "consort of " + who);
            Villages.electElder(village, player, name, day, null);
            PlayerLeader.take(level, v, player, name, List.of(), day);
            r.line.add(day + "|" + name + "|consort of " + who);
            r.change(day, name + " took the seat of " + r.house + ", as " + who + "'s consort");
            Identity.dirty();
            return true;
        }
        VillageFolkEntity heir = heir(village, was, who);
        String how;
        if (heir != null) {
            how = "eldest child of " + who;
        } else {
            heir = partnerOf(village, was);
            how = heir == null ? null : who + "'s partner, holding the seat";
        }
        if (heir == null) {
            heir = esteemed(village, was);
            if (heir == null) return false;
            String old = r.house;
            r.house = "the House of " + heir.displayNameCap();
            how = (old.isEmpty() || who.isEmpty() ? "chosen by the town" : "the line of " + old.replace("the House of ", "") + " ended")
                + "; first of " + r.house;
        }
        install(level, village, r, heir, day, how);
        return true;
    }

    /** The chaplain is dead: the chapel chooses the most devout. */
    static boolean chaplain(ServerLevel level, UUID village, Identity.Rec r, String who, @Nullable UUID was, long day) {
        VillageFolkEntity c = devout(village, was);
        if (c == null) return false;
        install(level, village, r, c, day, who.isEmpty() ? "chosen by the chapel" : "chosen by the chapel after " + who);
        return true;
    }

    /** Into the seat: the town's leader, with no mandate (nobody elected it), and its line written down. */
    static void install(ServerLevel level, UUID village, Identity.Rec r, VillageFolkEntity f, long day, String how) {
        HOW.put(village, how);
        Villages.electElder(village, f.getUUID(), f.displayNameCap(), day, f);
        Ledger.note(village, "mandate", "");
        Ledger.note(village, "election.now", "");
        if (r.gov == Form.LORD) r.line.add(day + "|" + f.displayNameCap() + "|" + how);
        while (r.line.size() > 24) r.line.remove(0);
        r.change(day, f.displayNameCap() + " " + (r.gov == Form.LORD ? "became lord, " : "became chaplain, ") + how);
        Identity.dirty();
    }

    /**
     * The chronicle's line for a leader taking the seat (Villages.electElder): "Fen was elected thane" in a town that
     * elects, "Fen succeeded as lord of the House of Bryn, eldest child of Bryn" in a lordship.
     */
    public static String installedLine(UUID village, String name) {
        String how = HOW.remove(village);
        Identity.Rec r = Identity.known(village);
        if (r == null || Identity.neutral()) return name + " was elected " + Homeland.leaderTitle(village);
        return switch (r.gov) {
            case LORD -> name + " took the seat as lord" + (r.house.isEmpty() ? "" : " of " + r.house) + (how == null ? "" : ", " + how);
            case CHAPLAIN -> name + " was made chaplain, to lead the town" + (how == null ? "" : ", " + how);
            default -> name + " was elected " + title(village);
        };
    }

    /** The eldest grown child of the dead lord, living. */
    @Nullable
    static VillageFolkEntity heir(UUID village, @Nullable UUID lord, String lordName) {
        VillageFolkEntity best = null;
        for (VillageFolkEntity f : Identity.grown(village)) {
            if (f.getUUID().equals(lord)) continue;
            boolean child = lord != null && f.parentIds().contains(lord);
            if (!child && !lordName.isEmpty()) {
                for (String p : f.life().parents().split(" and ")) if (p.trim().equals(lordName)) child = true;
            }
            if (child && (best == null || f.ageYears() > best.ageYears())) best = f;
        }
        return best;
    }

    @Nullable
    static VillageFolkEntity partnerOf(UUID village, @Nullable UUID lord) {
        if (lord == null) return null;
        for (VillageFolkEntity f : Identity.grown(village)) if (lord.equals(f.life().partner())) return f;
        return null;
    }

    /** The one the town thinks most of (the council's first), not this one. */
    @Nullable
    static VillageFolkEntity esteemed(UUID village, @Nullable UUID not) {
        for (VillageFolkEntity f : Council.members(village)) if (!f.getUUID().equals(not) && f.isAlive() && !f.isBaby()) return f;
        return null;
    }

    /** The town's most devout grown folk (it cares most for the old ways; the eldest, tied), not this one. */
    @Nullable
    static VillageFolkEntity devout(UUID village, @Nullable UUID not) {
        VillageFolkEntity best = null;
        int top = Integer.MIN_VALUE;
        for (VillageFolkEntity f : Identity.grown(village)) {
            if (f.getUUID().equals(not)) continue;
            int s = Values.weight(f, Values.Value.TRADITION) * 10 + Math.min(9, f.ageYears() / 10);
            if (s > top) { top = s; best = f; }
        }
        return best;
    }

    // ------------------------------------------------------------------ the town's questions

    /** The kind of a question of the town's identity put to the town (Referendums). */
    public static final String KIND = "IDENTITY";

    static void callDepose(ServerLevel level, Villages.Village v, Identity.Rec r, long day) {
        String lord = Villages.elderName(v.id());
        CompoundTag q = Referendums.call(level, v, KIND, "DEPOSE", "no confidence in Lord " + (lord.isEmpty() ? "" : lord + " ") + "of "
                + (r.house.isEmpty() ? Villages.name(v.id()) : r.house), "the townsfolk", "nothing from the stores", "none",
            "an elected leader in the lord's place, if it is carried", day + 1, null);
        r.lastReform = day;
        r.lowDays = 0;
        Villages.tell(v.id(), day, "the town, miserable under its lord, will vote tomorrow on no confidence in " + (lord.isEmpty() ? "it" : lord));
        Market.assemblyNews(v.id(), "Tomorrow the town votes: has it confidence in its lord? Say aye to put the lord out.");
        Identity.dirty();
    }

    static void callReform(ServerLevel level, Villages.Village v, Identity.Rec r, Form to, long day) {
        Referendums.call(level, v, KIND, "GOV:" + to.name(), "being ruled by " + to.words + " instead of " + r.gov.words,
            Villages.elderName(v.id()).isEmpty() ? "the council" : Villages.elderName(v.id()), "nothing from the stores", "none",
            "a town ruled the way it has come to want", day + 1, null);
        r.lastReform = day;
        Villages.tell(v.id(), day, "the town is to vote tomorrow on being ruled by " + to.words);
        Identity.dirty();
    }

    /** How a folk votes on a question of the town's identity (Referendums.judge): its laws, its government, its lord. */
    public static Referendums.Judged judge(ServerLevel level, VillageFolkEntity voter, CompoundTag q) {
        String subject = q.getString("subject");
        if (subject.startsWith("LAW:")) return LawBook.judge(level, voter, subject);
        UUID village = voter.ownerId();
        Identity.Rec r = Identity.known(village);
        double noise = Math.floorMod(Objects.hash(voter.getUUID(), q.getInt("id")), 7) - 3;
        if (r == null) return new Referendums.Judged(false, 0, "I don't know what we're voting on");
        if (subject.equals("DEPOSE")) {
            UUID lord = Villages.elder(village);
            int content = Contentment.score(village);
            double s = (45 - content) * 0.8 - (lord == null ? 0 : voter.life().affinity(lord) * 0.4)
                - Values.weight(voter, Values.Value.TRADITION) * 0.2 + Math.max(0, Ethos.folkLean(voter)[Ethos.Axis.RANK.ordinal()]) * 0.3
                + noise - 3;
            if (lord != null && voter.parentIds().contains(lord) || voter.getUUID().equals(lord)) s -= 60;
            boolean aye = s > 0;
            return new Referendums.Judged(aye, (int) Math.round(s), aye ? "we can't go on like this under " + Villages.elderName(village)
                : (r.house.isEmpty() ? "the lord" : r.house) + " has always led us, and will again");
        }
        Form to = subject.startsWith("GOV:") ? Form.named(subject.substring(4)) : null;
        if (to == null) return new Referendums.Judged(false, 0, "I don't know what we're voting on");
        double[] mine = Ethos.folkLean(voter);
        int[] blend = new int[r.axes.length];
        for (int i = 0; i < blend.length; i++) blend[i] = (int) Math.round(r.axes[i] * 0.5 + mine[i] * 1.5);
        double s = fit(to, blend) - fit(r.gov, blend) - Values.weight(voter, Values.Value.TRADITION) * 0.15 + noise;
        boolean aye = s > 0;
        return new Referendums.Judged(aye, (int) Math.round(s), aye ? to.words + " would suit us better"
            : "we've done well enough as we are");
    }

    /** What the count decided, done (Referendums.decide). */
    public static void decided(ServerLevel level, Villages.Village v, CompoundTag q, long day) {
        String subject = q.getString("subject");
        boolean carried = q.getBoolean("carried");
        if (subject.startsWith("LAW:")) {
            LawBook.decided(level, v, subject, carried, q.getInt("ayes"), q.getInt("nays"), day);
            return;
        }
        Identity.Rec r = Identity.known(v.id());
        if (r == null || !carried) {
            if (r != null) r.change(day, "the town voted against " + q.getString("title") + ", " + q.getInt("nays") + " to " + q.getInt("ayes"));
            return;
        }
        if (subject.equals("DEPOSE")) depose(level, v, r, day, q.getInt("ayes"), q.getInt("nays"));
        else if (subject.startsWith("GOV:")) {
            Form to = Form.named(subject.substring(4));
            if (to != null) set(level, v, r, to, day, "by the town's vote, " + q.getInt("ayes") + " to " + q.getInt("nays"));
        }
    }

    /** The lord voted out: the house is out of the seat, and the town elects its leader in two days. */
    static void depose(ServerLevel level, Villages.Village v, Identity.Rec r, long day, int ayes, int nays) {
        UUID id = v.id();
        String lord = Villages.elderName(id);
        UUID was = Villages.elder(id);
        Form next = Form.REEVE;
        double top = Double.NEGATIVE_INFINITY;
        for (Form f : new Form[]{ Form.REEVE, Form.COUNCIL, Form.GUILD, Form.COMMUNE }) {
            double x = fit(f, r.axes);
            if (x > top) { top = x; next = f; }
        }
        r.line.add(day + "|" + lord + "|deposed by the town's vote");
        if (PlayerLeader.leaderId(id) != null) PlayerLeader.leave(level, v, day, "voted out by the town");
        Ledger.note(id, "elder", "");
        Ledger.note(id, "mandate", "");
        if (was != null) Villages.elderGone(id, was);
        r.gov = next;
        r.govSince = day;
        r.lastReform = day;
        r.nextLawLook = day;
        Ledger.note(id, "election.next", Long.toString(day + 2));
        String line = "the town voted " + lord + " out, " + ayes + " to " + nays + ": " + (r.house.isEmpty() ? "the lord's house" : r.house)
            + " leaves the seat, and " + Villages.name(id) + " is now " + next.words + ", choosing its " + title(id) + " on day " + (day + 3);
        r.change(day, line);
        Villages.tell(id, day, line);
        Identity.dirty();
    }

    /** The town takes up another form of government: its leader installed (or an election called) to suit it. */
    static void set(ServerLevel level, Villages.Village v, Identity.Rec r, Form to, long day, String why) {
        UUID id = v.id();
        Form was = r.gov;
        boolean electedBefore = was != Form.LORD && was != Form.CHAPLAIN;
        r.gov = to;
        r.govSince = day;
        r.lastReform = day;
        r.nextLawLook = day;
        String line = Villages.name(id) + " is now ruled by " + to.words + " (" + why + ")";
        r.change(day, line);
        Villages.tell(id, day, line);
        if (to == Form.LORD) {
            VillageFolkEntity first = Identity.leader(id);
            if (first == null) first = esteemed(id, null);
            if (first != null) {
                r.house = "the House of " + first.displayNameCap();
                install(level, id, r, first, day, "first of " + r.house);
            }
        } else if (to == Form.CHAPLAIN) {
            chaplain(level, id, r, "", null, day);
        } else if (!electedBefore) {
            Ledger.note(id, "elder", "");
            Ledger.note(id, "election.next", Long.toString(day + 2));
        }
        Identity.dirty();
    }

    // ------------------------------------------------------------------ the page, the card, the board

    /** What a folk is in its town's government, for its card, or null. */
    @Nullable
    static String roleOf(UUID village, Identity.Rec r, VillageFolkEntity f) {
        boolean leads = f.getUUID().equals(Villages.elder(village));
        String name = Villages.name(village);
        switch (r.gov) {
            case LORD -> {
                if (leads) return "Lord of " + name + (r.house.isEmpty() ? "" : ", " + r.house);
                UUID lord = Villages.elder(village);
                if (lord != null && f.parentIds().contains(lord)) return "of " + (r.house.isEmpty() ? "the ruling house" : r.house)
                    + (f.getUUID().equals(heirId(village)) ? ", heir to the seat" : "");
                return null;
            }
            case CHAPLAIN -> { return leads ? "the chaplain, who leads " + name : null; }
            case COUNCIL -> {
                for (VillageFolkEntity e : Elections.voters(village)) {
                    if (e == f) return leads ? "speaker of the elders" : "an elder of the council";
                }
                return null;
            }
            case GUILD -> { return leads ? "guildmaster" : f.veteranLevel() >= 10 ? "a master, with a vote in the guild" : null; }
            case COMMUNE -> { return leads ? "the commune's steward" : null; }
            default -> { return leads ? "the elected " + title(village) : null; }
        }
    }

    /** The heir to the seat, as the card names it, or null. */
    @Nullable
    static UUID heirId(UUID village) {
        VillageFolkEntity h = heir(village, Villages.elder(village), Villages.elderName(village));
        return h == null ? null : h.getUUID();
    }

    /** The board's election lines for a town that holds none (Elections.board). */
    public static List<String> board(UUID village) {
        List<String> out = new ArrayList<>();
        Identity.Rec r = Identity.known(village);
        if (r == null) return out;
        String leader = Villages.elderName(village);
        if (r.gov == Form.LORD) {
            out.add("RM|No elections: " + (leader.isEmpty() ? "the seat stands empty" : "Lord " + leader) + (r.house.isEmpty() ? "" : " of " + r.house) + ".");
            VillageFolkEntity h = heir(village, Villages.elder(village), leader);
            if (h != null) out.add("RM|The heir: " + h.displayNameCap() + ".");
        } else {
            out.add("RM|No elections: " + (leader.isEmpty() ? "the chapel has still to choose its chaplain" : leader + " leads as chaplain") + ".");
        }
        return out;
    }

    static CompoundTag report(ServerLevel level, UUID village, Identity.Rec r) {
        CompoundTag t = new CompoundTag();
        t.putString("form", capital(r.gov.words));
        t.putString("phrase", phrase(village, r));
        t.putString("how", r.gov.how);
        t.putString("title", title(village));
        t.putString("leader", Villages.elderName(village));
        t.putLong("since", r.govSince);
        t.putString("house", r.house);
        List<String> line = new ArrayList<>();
        for (String l : r.line) {
            String[] p = l.split("\\|", 3);
            if (p.length == 3) line.add("Day " + (Fame.number(p[0]) + 1) + ": " + p[1] + " — " + p[2]);
        }
        t.put("line", Identity.strings(line));
        int voters = Elections.voters(village).size(), grown = Identity.grown(village).size();
        t.putString("votes", holdsElections(village) ? voters + " of " + grown + " grown folk vote" : "nobody votes for the leader");
        t.putString("election", holdsElections(village) ? Elections.line(village, level.getDayTime() / 24000L) : "");
        t.putString("routes", routes(village, r));
        if (r.consort != null) t.putString("consort", r.consortName);
        return t;
    }

    static String line(ServerLevel level, UUID village, Identity.Rec r) {
        return capital(phrase(village, r)) + " (since day " + (r.govSince + 1) + "): " + r.gov.how + ". Leader: "
            + (Villages.elderName(village).isEmpty() ? "nobody yet" : Villages.elderName(village)) + ", called " + title(village)
            + ". " + routes(village, r) + (r.line.isEmpty() ? "" : " Line: " + String.join("; ", r.line).replace('|', ' '));
    }

    static String capital(String s) {
        return Identity.capital(s);
    }

    // ------------------------------------------------------------------ tests

    /** Tests: the town under this form now, its leader installed (or an election due) to suit it. */
    public static void setForTests(ServerLevel level, Villages.Village v, Form f) {
        Identity.Rec r = Identity.rec(v.id());
        r.seeded = true;
        set(level, v, r, f, level.getDayTime() / 24000L, "for the test");
    }

    /** Tests: the lord's line, "name|how" a line. */
    public static List<String> lineForTests(UUID village) {
        Identity.Rec r = Identity.rec(village);
        List<String> out = new ArrayList<>();
        for (String l : r.line) {
            String[] p = l.split("\\|", 3);
            if (p.length == 3) out.add(p[1] + "|" + p[2]);
        }
        return out;
    }

    /** Tests: how many of the town vote for its leader and on its questions (Elections.voters). */
    public static int votersForTests(UUID village) {
        return Elections.voters(village).size();
    }

    /** Tests: the form that suits these axes best. */
    public static Form chooseForTests(int[] axes) {
        return choose(axes);
    }

    /** Tests: the vote of no confidence called now. Returns the question's number, or -1. */
    public static int deposeForTests(ServerLevel level, Villages.Village v) {
        Identity.Rec r = Identity.rec(v.id());
        callDepose(level, v, r, level.getDayTime() / 24000L);
        for (CompoundTag q : Referendums.open(v.id())) if ("DEPOSE".equals(q.getString("subject"))) return q.getInt("id");
        return -1;
    }
}
