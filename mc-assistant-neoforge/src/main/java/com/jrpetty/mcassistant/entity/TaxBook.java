package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
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
 * [civic-land] The town's taxes, set by its council and collected on tax day.
 *
 * <p><b>What it levies.</b> Four things, each at one of four rates: <b>the head tax</b>, nothing to three coins from
 * every grown hand at work each tax day; <b>the share of wages</b>, kept back on payday (the law's tithe, LawBook and
 * Market.taxOn, from one coin in twenty to one in five: the council's sitting is how the law-book's tithe is changed
 * now); <b>the market toll</b>, nothing to fifteen in a hundred of what a folk or a player takes for its own goods (at
 * the auction, at a player's stall); and <b>the war levy</b>, nothing to four coins a head each tax day, collected
 * only while the town is at war. A new town levies only the law's share of the wages.
 *
 * <p><b>The council's sitting.</b> Once a week, on the council's day, the council takes up the taxes at its meeting
 * (Assemblies, the council's script), or quietly in the hall after dark if the meeting did not happen. What is put to
 * it comes from the treasury's needs: the wages paid short or a thin purse, a war, coin put by for something to buy,
 * ask for more; a full treasury, or a town grumbling or worse, for less. Every councillor votes by what it cares for
 * (the Merchant and the Free Spirit against a tax, the Visionary, the Homemaker and in a war the Guardian for it),
 * by what the town needs as it feels it, by how the town is taking it, and by the players it likes who have urged it
 * one way or the other; a devout soul follows its leader; a lord's word counts three times; and a knight of the town
 * (Knights), a player given a seat on the council (Hustings) and a player who leads (PlayerLeader) each cast the vote
 * they have chosen. The motion, the votes and who cast them go into the chronicle and the books.
 *
 * <p><b>Tax day.</b> Once a week a folk is given the job for the day: the town's collector (a shopkeeper, a
 * storekeeper, the banker if it can, the hardest-working else), who walks round the town to every grown folk at its
 * work and takes the head tax and the levy out of its purse into the treasury, and asks every citizen it finds for
 * theirs. A poor folk is let off when the treasury can bear it, else it pays what it can and owes the rest
 * (arrears, taken first next time). Whoever it could not find pays at the hall at the end of the day. In a tax revolt
 * the angry refuse it to its face, and may chase it off (TaxUnrest).
 *
 * <p><b>Players.</b> A citizen owes the head tax and the levy as the folk do (a knight its fee besides), told in chat;
 * it pays any folk ("I'd like to pay my taxes") or with /village taxes pay. Three weeks unpaid and the town thinks
 * the less of it. A player may urge the council to lower the taxes or raise them ("The taxes should be lower"), which
 * sways the councillors who think well of it.
 */
public final class TaxBook {

    private TaxBook() {}

    public enum Lever {
        HEAD("the head tax", "every grown hand at work pays it each tax day"),
        WAGE("the share of wages", "kept back from every wage on payday: the law's tithe"),
        TOLL("the market toll", "a share of what folk and players take for their own goods"),
        LEVY("the war levy", "every grown hand pays it each tax day, while the town is at war");

        public final String words, does;

        Lever(String words, String does) {
            this.words = words;
            this.does = does;
        }

        @Nullable
        public static Lever named(String s) {
            if (s == null) return null;
            String t = s.trim().toUpperCase(java.util.Locale.ROOT);
            if (t.equals("POLL") || t.equals("HEADTAX")) return HEAD;
            if (t.equals("WAGES") || t.equals("TITHE")) return WAGE;
            if (t.equals("MARKET")) return TOLL;
            if (t.equals("WAR")) return LEVY;
            try { return valueOf(t); } catch (IllegalArgumentException e) { return null; }
        }
    }

    /** The head tax, the market toll and the war levy at each of their four rates. */
    static final int[] HEAD_COINS = { 0, 1, 2, 3 }, TOLL_PERCENT = { 0, 5, 10, 15 }, LEVY_COINS = { 0, 1, 2, 4 };
    /** A knight's fee each tax day (Knights). */
    public static final int KNIGHT_FEE = 2;
    /** When tax day's round starts, and when whoever was not found pays at the hall. */
    static final long ROUND_FROM = 2000L, ROUND_TO = 11000L;
    /** The longest the collector looks for one folk before leaving it to pay at the hall (ticks). */
    static final long LOOK_MOST = 900L;
    /** How many days of the books the Taxes page keeps. */
    static final int HISTORY = 21;
    /** The council's day (Assemblies: its meeting the evening of every seventh). */
    static final int COUNCIL_DAY = 3;

    /** What each town's toll has come to that is short of a whole coin, in hundredths. */
    private static final Map<UUID, Integer> TOLL_CARRY = new ConcurrentHashMap<>();
    /** When the collector set out after its present mark (folk id to game time). */
    private static final Map<UUID, Long> AIMED = new ConcurrentHashMap<>();

    static void resetForTests() {
        TOLL_CARRY.clear();
        AIMED.clear();
    }

    // ------------------------------------------------------------------ the rates

    /** The rate a lever stands at, nought to three. */
    public static int rate(UUID village, Lever l) {
        if (l == Lever.WAGE) return LawBook.value(village, LawBook.Law.TITHE);
        return Math.max(0, Math.min(3, CivicLand.town(village).getInt("rate." + l.name())));
    }

    /** The head tax each grown hand pays a tax day, in coin. */
    public static int head(UUID village) {
        return HEAD_COINS[rate(village, Lever.HEAD)];
    }

    /** The war levy each grown hand pays a tax day (nothing at peace). */
    public static int levy(UUID village) {
        return Wars.enemies(village).isEmpty() ? 0 : LEVY_COINS[rate(village, Lever.LEVY)];
    }

    /** The toll, in the hundred. */
    public static int tollPercent(UUID village) {
        return TOLL_PERCENT[rate(village, Lever.TOLL)];
    }

    /** A lever at a rate, in words: "two coins a head each tax day". */
    public static String words(Lever l, int r) {
        r = Math.max(0, Math.min(3, r));
        return switch (l) {
            case HEAD -> r == 0 ? "none" : coins(HEAD_COINS[r]) + " a head each tax day";
            case WAGE -> (new int[]{ 5, 10, 15, 20 })[r] + " in a hundred of every wage";
            case TOLL -> r == 0 ? "none" : TOLL_PERCENT[r] + " in a hundred of takings";
            case LEVY -> r == 0 ? "none" : coins(LEVY_COINS[r]) + " a head each tax day at war";
        };
    }

    static String coins(int n) {
        return n + (n == 1 ? " coin" : " coins");
    }

    /** Can the council move this lever here (the share of wages wants the town's law-book)? */
    static boolean movable(UUID village, Lever l) {
        return l != Lever.WAGE || !Identity.neutral() && Identity.known(village) != null;
    }

    /**
     * A lever set, by the council's vote, a player who leads, or an operator: into the books, the chronicle and the
     * morning assembly. The share of wages is the law-book's tithe, changed there. Returns the rate it stands at.
     */
    public static int set(ServerLevel level, Villages.Village v, Lever l, int value, String by) {
        UUID id = v.id();
        int to = Math.max(0, Math.min(3, value));
        int was = rate(id, l);
        long day = CivicLand.day(level);
        CompoundTag t = CivicLand.town(id);
        t.putLong("since." + l.name(), day);
        if (l == Lever.WAGE) {
            if (!movable(id, l)) return was;
            if (to != was) LawBook.set(level, v, LawBook.Law.TITHE, to, by);
            TaxUnrest.forgetBurden(id);
            CivicLand.changed();
            return rate(id, l);
        }
        t.putInt("rate." + l.name(), to);
        TaxUnrest.forgetBurden(id);
        CivicLand.changed();
        if (to != was) {
            String line = Villages.name(id) + "'s " + l.words + " is now " + words(l, to) + " (it was " + words(l, was) + "): " + by;
            Villages.tell(id, day, line);
            Market.assemblyNews(id, "Hear the council: " + l.words + " is now " + words(l, to) + ".");
        }
        return to;
    }

    // ------------------------------------------------------------------ the day

    /** Is today the town's tax day? Once a week, a day of its own. */
    public static boolean taxDay(UUID village, long day) {
        return Math.floorMod(day + village.hashCode() + 2, 7L) == 0;
    }

    /** The next tax day from today (today itself if it is one). */
    public static long nextTaxDay(UUID village, long day) {
        for (long d = day; d < day + 8; d++) if (taxDay(village, d)) return d;
        return day + 7;
    }

    /** Every five seconds for each town (CivicLand): the books, tax day, and the council's sitting if its meeting missed it. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = CivicLand.day(level), t = CivicLand.time(level);
        CompoundTag tag = CivicLand.town(id);
        // The books: once a morning, after the payday, yesterday's share of the wages and the wages paid.
        if (t >= 600 && tag.getLong("booked") != day + 1) {
            tag.putLong("booked", day + 1);
            Economy.Day y = Economy.yesterdayBooks(id);
            CompoundTag e = entry(id, day - 1);
            e.putInt("wage", y == null ? 0 : y.tax);
            e.putInt("wages", y == null ? 0 : y.wages);
            CivicLand.changed();
        }
        // Tax day.
        if (taxDay(id, day) && t >= ROUND_FROM && t < ROUND_TO && tag.getLong("roundDay") != day + 1 && !Raids.underAlarm(id)) {
            startRound(level, v, day);
            if (TownJobs.instantNow()) closeRound(level, v, false);
        }
        if (tag.getLong("roundDay") == day + 1 && !tag.getBoolean("closed") && (t >= ROUND_TO || !taxDay(id, day))) {
            closeRound(level, v, false);
        }
        // The council's sitting, quietly in the hall, if the evening's meeting did not take the taxes up.
        if (Math.floorMod(day, 7L) == COUNCIL_DAY && t >= 13400 && tag.getLong("sat") != day + 1 && CivicLand.grown(id).size() >= 2) {
            sit(level, v, day, "the council, sitting in the hall after dark,");
        }
    }

    /** The books' entry for this day (made the first time). Days are kept "one ahead" so nought is never a day. */
    static CompoundTag entry(UUID village, long day) {
        ListTag hist = CivicLand.list(CivicLand.town(village), "hist");
        for (int i = 0; i < hist.size(); i++) {
            CompoundTag e = hist.getCompound(i);
            if (e.getLong("d") == day + 1) return e;
        }
        CompoundTag e = new CompoundTag();
        e.putLong("d", day + 1);
        hist.add(e);
        while (hist.size() > HISTORY) hist.remove(0);
        return e;
    }

    static void book(UUID village, long day, String key, int coins) {
        if (coins <= 0) return;
        CompoundTag e = entry(village, day);
        e.putInt(key, e.getInt(key) + coins);
        CivicLand.changed();
    }

    // ------------------------------------------------------------------ tax day: the round

    /** The collector chosen, its round laid out, the citizens told what they owe. */
    static void startRound(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag tag = CivicLand.town(id);
        boolean guarded = TaxUnrest.guardsSent(level, v);                 // (the last round's chasing counts: read before it is cleared)
        tag.putLong("roundDay", day + 1);
        tag.putBoolean("closed", false);
        for (String k : new String[]{ "took", "letOff", "short", "refused", "met", "atHall" }) tag.putInt("r." + k, 0);
        tag.putBoolean("chased", false);
        tag.remove("escort");
        tag.putBoolean("guarded", guarded);
        // Every citizen owes its share (and a knight its fee), whether the collector finds it or not.
        players(level, v, day);
        int due = head(id) + levy(id);
        List<VillageFolkEntity> folk = CivicLand.grown(id);
        VillageFolkEntity collector = due > 0 || anyArrears(id) ? collector(id, folk) : null;
        tag.putString("collector", collector == null ? "" : collector.getUUID().toString());
        ListTag round = new ListTag();
        if (collector != null) {
            // Nearest first, one after another: a walk round the town, not back and forth across it.
            List<VillageFolkEntity> left = new ArrayList<>(folk);
            left.remove(collector);
            left.removeIf(f -> f.stationTask() == AssistantEntity.StationTask.NONE && arrears(id, f.getUUID()) <= 0);
            BlockPos at = collector.blockPosition();
            while (!left.isEmpty()) {
                final BlockPos from = at;
                VillageFolkEntity next = left.stream().min(Comparator.comparingDouble(f -> f.blockPosition().distSqr(from))).orElse(null);
                left.remove(next);
                round.add(StringTag.valueOf(next.getUUID().toString()));
                at = next.blockPosition();
            }
            for (ServerPlayer p : level.players()) {
                if (Citizens.is(id, p.getUUID()) && owedBy(id, p.getUUID()) > 0 && p.blockPosition().closerThan(v.centre(), 80)) {
                    round.add(StringTag.valueOf("p:" + p.getUUID()));
                }
            }
            AIMED.remove(collector.getUUID());
            if (guarded) {
                ListTag escort = new ListTag();
                for (VillageFolkEntity g : Patrols.watch(id)) {
                    if (escort.size() >= 2 || g.isBaby() || !g.isAlive()) continue;
                    escort.add(StringTag.valueOf(g.getUUID().toString()));
                }
                tag.put("escort", escort);
            }
            FolkTalk.speak(collector, FolkTalk.pick(level.getRandom(), "Tax day! Have your coin ready, everybody.",
                "It's tax day — I'll be round. Don't make me chase you.", "Collector for the town, coming round!"));
            collectFrom(level, v, null, collector, guarded);                // its own first, into the bag before anybody else's
            collector.persona().remember(day, "I went round for the taxes on day " + day, 2);
        }
        tag.put("round", round);
        CivicLand.changed();
    }

    /**
     * Who goes round: a shopkeeper, a storekeeper or the banker, the steadiest of them; else the hardest-working grown
     * hand; and in a town where every such hand is one of the angry, one of the watch is sent.
     */
    @Nullable
    static VillageFolkEntity collector(UUID village, List<VillageFolkEntity> folk) {
        VillageFolkEntity c = collector(village, folk, false);
        return c != null ? c : collector(village, folk, true);
    }

    @Nullable
    static VillageFolkEntity collector(UUID village, List<VillageFolkEntity> folk, boolean watch) {
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        UUID elder = Villages.elder(village);
        for (VillageFolkEntity f : folk) {
            if (f.isHired() || f.getUUID().equals(elder) || (f.stationTask() == AssistantEntity.StationTask.GUARD) != watch) continue;
            if (TaxUnrest.aggrieved(village, f)) continue;                    // not one of the angry
            int s = f.veteranLevel();
            switch (f.stationTask()) {
                case BANK -> s += 40;
                case STORE, SHOP -> s += 30;
                case HAUL -> s += 15;
                default -> { }
            }
            if (f.life().has(Social.Trait.HARDWORKING)) s += 8;
            if (f.life().has(Social.Trait.SHY)) s -= 10;
            s += Math.floorMod(Objects.hash(f.getUUID(), village), 5);
            if (s > bestScore) { bestScore = s; best = f; }
        }
        return best;
    }

    /** The collector's part (CivicLand.hold), and its escort's: true while it has the folk in hand. */
    static boolean collecting(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        long t = CivicLand.time(level);
        if (id == null || t < ROUND_FROM || t >= ROUND_TO || !CivicLand.known(id)) return false;   // (cheap looks first: this is every other tick)
        CompoundTag tag = CivicLand.town(id);
        long day = CivicLand.day(level);
        if (tag.getLong("roundDay") != day + 1 || tag.getBoolean("closed")) return false;
        if (f.isSleeping() || Raids.underAlarm(id) || Assemblies.attending(f)) return false;
        UUID collector = CivicLand.uuid(tag.getString("collector"));
        VillageFolkEntity c = CivicLand.find(level, collector);
        // The escort, in a revolt: two of the watch a few steps behind the collector.
        for (String s : CivicLand.read(CivicLand.list(tag, "escort"))) {
            if (!s.equals(f.getUUID().toString())) continue;
            if (c == null) return false;
            Civics.goTo(f, c.blockPosition(), 3.0, 0.9);
            f.hobbyNow = "walking the collector's round";
            return true;
        }
        if (collector == null || !collector.equals(f.getUUID())) return false;
        Villages.Village v = Villages.get(id);
        if (v == null) return false;
        ListTag round = CivicLand.list(tag, "round");
        while (!round.isEmpty()) {
            String next = round.getString(0);
            BlockPos at = null;
            Player p = null;
            VillageFolkEntity o = null;
            if (next.startsWith("p:")) {
                UUID pid = CivicLand.uuid(next.substring(2));
                p = pid == null ? null : level.getPlayerByUUID(pid);
                if (p != null && p.isAlive() && p.blockPosition().closerThan(v.centre(), 96) && owedBy(id, pid) > 0) at = p.blockPosition();
            } else {
                o = CivicLand.find(level, CivicLand.uuid(next));
                if (o != null && !o.isSleeping() && !Nether.away(o) && o.trip() == null) at = o.blockPosition();
            }
            long now = level.getGameTime();
            Long since = AIMED.get(f.getUUID());
            if (at == null || since != null && now - since > LOOK_MOST) {
                round.remove(0);                                    // left to pay at the hall
                AIMED.remove(f.getUUID());
                CivicLand.changed();
                continue;
            }
            if (since == null) AIMED.put(f.getUUID(), now);
            f.hobbyNow = "going round for the taxes";
            f.lastLeisureTick = f.tickCount;
            if (!Civics.goTo(f, at, 2.5, 0.85)) return true;
            AIMED.remove(f.getUUID());
            round.remove(0);
            CivicLand.changed();
            f.getLookControl().setLookAt(p != null ? p : o, 30.0F, 30.0F);
            if (p != null) {
                int owed = owedBy(id, p.getUUID());
                FolkTalk.speak(f, "Tax day, " + p.getName().getString() + ": " + coins(owed) + " from you, if you please. Pay me now, or at the board.");
                return true;
            }
            Outcome out = collectFrom(level, v, f, o, tag.getBoolean("guarded"));
            if (out == Outcome.CHASED) {
                tag.putBoolean("chased", true);
                FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "All right, all right — I'm going!", "You'll answer to the council for this!",
                    "Keep your coin, then! I'm not paid enough for this."));
                Civics.goTo(f, hall(level, v), 1.5, 1.2);
                closeRound(level, v, true);
                return false;
            }
            return true;
        }
        closeRound(level, v, false);
        return false;
    }

    enum Outcome { NONE, PAID, SHORT, LET_OFF, REFUSED, CHASED }

    /** What a folk owes this tax day and from before, taken out of its purse (or let off, or owed, or refused). */
    static Outcome collectFrom(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity collector, VillageFolkEntity f, boolean guarded) {
        UUID id = v.id();
        CompoundTag tag = CivicLand.town(id);
        long day = CivicLand.day(level);
        RandomSource r = level.getRandom();
        int headDue = f.stationTask() == AssistantEntity.StationTask.NONE ? 0 : head(id), levyDue = f.stationTask() == AssistantEntity.StationTask.NONE ? 0 : levy(id);
        int owed = arrears(id, f.getUUID());
        int due = headDue + levyDue;
        if (due + owed <= 0) return Outcome.NONE;
        tag.putInt("r.met", tag.getInt("r.met") + 1);
        // In a revolt the angry will not pay, and the boldest send the collector packing.
        if (!guarded && TaxUnrest.refuses(id, f)) {
            setArrears(id, f.getUUID(), owed + due);
            tag.putInt("r.refused", tag.getInt("r.refused") + 1);
            f.persona().remember(day, "I refused the tax collector", 4);
            boolean chase = collector != null && (tag.getInt("r.refused") >= 2 || f.life().has(Social.Trait.GRUMPY));
            FolkTalk.speak(f, chase ? FolkTalk.pick(r, "Get away with you, tax-man! Not a coin!", "Out! Go and tell the council where to put its taxes!",
                "Clear off, collector, before I lose my temper!") : FolkTalk.pick(r, "Not a coin. Not till the council sees sense.",
                "You'll get nothing from me this week.", "Pay? We're on strike against it!"));
            TaxUnrest.refused(level, v, f);
            return chase ? Outcome.CHASED : Outcome.REFUSED;
        }
        // The poor (a folk of no means, or one whose purse will not run to the week's tax): let off the week's where the
        // treasury can bear it; else what it can, and the rest owed.
        if ((Wealth.tier(f) == Wealth.Tier.POOR || f.purse() < due) && f.purse() < due + owed && due > 0 && mercy(id)) {
            tag.putInt("r.letOff", tag.getInt("r.letOff") + 1);
            CivicLand.sub(tag, "relief").putLong(f.getUUID().toString(), day + 1);
            if (collector != null && r.nextInt(2) == 0) FolkTalk.speak(collector, FolkTalk.pick(r, "Never mind this week. The council lets you off.",
                "Keep it — you need it more than the treasury does."));
            f.persona().remember(day, "the collector let me off my taxes", 2);
            return Outcome.LET_OFF;
        }
        int total = due + owed;
        int pay = Math.min(f.purse(), total);
        if (pay > 0 && f.spend(pay)) {
            Ledger.addCoins(id, pay);
            Economy.tithe(id, pay);
            int levyPart = Math.min(pay, levyDue);
            book(id, day, "levy", levyPart);
            book(id, day, "head", pay - levyPart);
            tag.putInt("r.took", tag.getInt("r.took") + pay);
        } else {
            pay = 0;
        }
        setArrears(id, f.getUUID(), total - pay);
        if (total - pay > 0) {
            tag.putInt("r.short", tag.getInt("r.short") + 1);
            f.persona().remember(day, "I could not pay all my taxes", 3);
        } else {
            f.persona().remember(day, "I paid " + coins(pay) + " to the tax collector", 1);
        }
        TaxUnrest.taxed(f, day);
        if (r.nextInt(3) == 0 && collector != null) {
            int unrest = TaxUnrest.level(id);
            FolkTalk.speak(f, total - pay > 0 ? FolkTalk.pick(r, "That's all I've got. Put the rest on the slate.", "I can't — I'll owe you.")
                : unrest >= 40 ? FolkTalk.pick(r, "Again? We paid last week!", "Bleeding us dry, the council is.", "There. Take it, and much good may it do you.")
                : FolkTalk.pick(r, "There you are. Spend it well.", "For the town, then.", "Fair enough — the walls don't build themselves."));
        }
        return total - pay > 0 ? Outcome.SHORT : Outcome.PAID;
    }

    /** Can the town let its poor off this week: a treasury of three days' wages, or a generous leader? */
    static boolean mercy(UUID village) {
        int bill = Math.max(1, Market.wageBill(village));
        if (Ledger.coins(village) >= 3 * bill) return true;
        VillageFolkEntity leader = Orders.elderOf(village);
        return leader != null && leader.life().has(Social.Trait.GENEROUS);
    }

    /** The day's round over: whoever was not found pays at the hall; into the chronicle. */
    static void closeRound(ServerLevel level, Villages.Village v, boolean chased) {
        UUID id = v.id();
        CompoundTag tag = CivicLand.town(id);
        if (tag.getBoolean("closed")) return;
        tag.putBoolean("closed", true);
        long day = CivicLand.day(level);
        // Everybody the collector did not get to, at the hall (unless it was chased off: then nobody pays the rest).
        if (!chased) {
            int hall = 0;
            java.util.Set<String> met = new java.util.HashSet<>();
            for (String s : CivicLand.read(CivicLand.list(tag, "round"))) met.add(s);
            UUID collector = CivicLand.uuid(tag.getString("collector"));
            boolean anyRound = collector != null;
            for (VillageFolkEntity f : CivicLand.grown(id)) {
                // Those still on the round, or every grown hand if nobody went round.
                if (anyRound && !met.contains(f.getUUID().toString())) continue;
                Outcome o = collectFrom(level, v, null, f, tag.getBoolean("guarded"));
                if (o != Outcome.NONE) hall++;
            }
            tag.putInt("r.atHall", hall);
        }
        tag.put("round", new ListTag());
        tag.remove("escort");
        CivicLand.changed();
        String collectorName = "";
        VillageFolkEntity c = CivicLand.find(level, CivicLand.uuid(tag.getString("collector")));
        if (c != null) collectorName = c.displayNameCap();
        int took = tag.getInt("r.took");
        List<String> parts = new ArrayList<>();
        parts.add(coins(took) + " in head tax" + (levy(id) > 0 ? " and the war levy" : ""));
        if (tag.getInt("r.letOff") > 0) parts.add(tag.getInt("r.letOff") + " poor let off");
        if (tag.getInt("r.short") > 0) parts.add(tag.getInt("r.short") + " short and owing");
        if (tag.getInt("r.refused") > 0) parts.add(tag.getInt("r.refused") + " refused");
        if (took == 0 && tag.getInt("r.met") == 0 && !chased) return;               // nothing levied: no tax day to speak of
        String line = (chased ? collectorName + " was chased off on the tax round: " : collectorName.isEmpty() ? "tax day at the hall: "
            : collectorName + " went round for the taxes: ") + String.join(", ", parts);
        Villages.tell(id, day, line);
        if (chased) Market.assemblyNews(id, "The collector was run off yesterday. The council will hear of it.");
    }

    /** Is anybody of the town in arrears? */
    static boolean anyArrears(UUID village) {
        CompoundTag a = CivicLand.sub(CivicLand.town(village), "arrears");
        for (String k : a.getAllKeys()) if (a.getInt(k) > 0) return true;
        return false;
    }

    public static int arrears(UUID village, UUID folk) {
        return CivicLand.sub(CivicLand.town(village), "arrears").getInt(folk.toString());
    }

    static void setArrears(UUID village, UUID folk, int coins) {
        CompoundTag a = CivicLand.sub(CivicLand.town(village), "arrears");
        if (coins <= 0) a.remove(folk.toString());
        else a.putInt(folk.toString(), Math.min(60, coins));
        CivicLand.changed();
    }

    /** Folk in arrears and the coin they owe, the dead and the departed let go. {count, coins}. */
    static int[] arrearsTotal(UUID village) {
        CompoundTag a = CivicLand.sub(CivicLand.town(village), "arrears");
        java.util.Set<String> here = new java.util.HashSet<>();
        for (AssistantEntity f : Villages.folkOf(village)) here.add(f.getUUID().toString());
        int n = 0, coins = 0;
        for (String k : new ArrayList<>(a.getAllKeys())) {
            if (!here.isEmpty() && !here.contains(k)) { a.remove(k); continue; }
            if (a.getInt(k) <= 0) continue;
            n++;
            coins += a.getInt(k);
        }
        return new int[]{ n, coins };
    }

    /** Where the hall's door is (or the board, or the heart): where one pays at, or runs back to. */
    static BlockPos hall(ServerLevel level, Villages.Village v) {
        Ledger.Building hall = Villages.builtStructure(v.id(), "hall");
        if (hall != null && level.isLoaded(hall.anchor())) {
            BlockPos door = TownLife.fittings(hall).door();
            if (door != null) return door.relative(hall.facing().getOpposite(), 2);
        }
        BlockPos board = VillageBoards.lectern(v.id());
        return board != null ? board : v.centre();
    }

    // ------------------------------------------------------------------ players

    /** Tax day: every citizen owes the head tax and the levy (a knight its fee); told now, or when it next comes on. */
    static void players(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag pay = CivicLand.sub(CivicLand.town(id), "pay");
        int due = head(id) + levy(id);
        for (Map.Entry<UUID, String> c : Ledger.citizens(id).entrySet()) {
            int fee = Knights.isKnight(id, c.getKey()) ? KNIGHT_FEE : 0;
            if (due + fee <= 0) continue;
            CompoundTag me = CivicLand.sub(pay, c.getKey().toString());
            int before = me.getInt("owed");
            me.putString("name", c.getValue());
            me.putInt("owed", Math.min(200, before + due + fee));
            me.putInt("fee", me.getInt("fee") + fee);
            me.putInt("weeks", before > 0 ? me.getInt("weeks") + 1 : 0);
            me.putLong("since", before > 0 ? me.getLong("since") : day + 1);
            CivicLand.tell(level.getServer(), c.getKey(), "Tax day in " + Villages.name(id) + ": you owe " + coins(me.getInt("owed"))
                + (fee > 0 ? " (your knight's fee of " + coins(fee) + " with it)" : "") + ". Pay any of its folk (\"I'd like to pay my taxes\") or /village taxes pay.",
                ChatFormatting.GOLD);
            if (me.getInt("weeks") == 3) {
                for (AssistantEntity a : Villages.folkOf(id)) {
                    if (a instanceof VillageFolkEntity f) f.persona().feelFor(c.getKey(), c.getValue(), -3);
                }
                Standing.stir(id, c.getKey());
                Villages.tell(id, day, c.getValue() + " has not paid their taxes these three weeks");
            }
            Knights.feeArrears(level, id, c.getKey(), me.getInt("weeks"), me.getInt("fee"));
        }
        CivicLand.changed();
    }

    /** What a player owes this town, all told. */
    public static int owedBy(UUID village, UUID player) {
        if (!CivicLand.known(village)) return 0;
        return CivicLand.sub(CivicLand.sub(CivicLand.town(village), "pay"), player.toString()).getInt("owed");
    }

    /** A player pays what it owes this town, out of the coin it carries. What is said. */
    public static String pay(ServerLevel level, UUID village, Player p) {
        int owed = owedBy(village, p.getUUID());
        String town = Villages.name(village);
        if (owed <= 0) return "You owe " + town + " no taxes. Come tax day, you will.";
        int have = Market.coinsHeld(p);
        if (have <= 0) return "You owe " + coins(owed) + " in taxes, and you've no coin on you.";
        int paid = Math.min(have, owed);
        Market.payOut(p, paid);
        Ledger.addCoins(village, paid);
        Economy.tithe(village, paid);
        long day = CivicLand.day(level);
        book(village, day, "players", paid);
        CompoundTag me = CivicLand.sub(CivicLand.sub(CivicLand.town(village), "pay"), p.getUUID().toString());
        me.putInt("owed", owed - paid);
        me.putInt("paidAll", me.getInt("paidAll") + paid);
        if (owed - paid <= 0) {
            me.putInt("weeks", 0);
            me.putInt("fee", 0);
            Knights.taxPaid(village, p.getUUID());
        } else {
            me.putInt("fee", Math.max(0, me.getInt("fee") - paid));
        }
        CivicLand.changed();
        Standing.stir(village, p.getUUID());
        return owed - paid <= 0 ? "That's your taxes paid, " + coins(paid) + ". " + town + " thanks you."
            : coins(paid) + " paid. You still owe " + coins(owed - paid) + ".";
    }

    // ------------------------------------------------------------------ the toll

    /**
     * The market toll on what a folk or a player takes for its own goods (Auctions, PlayerStalls): its share into the
     * treasury, the part of a coin carried to the next sale. Returns the toll, taken out of the seller's takings.
     */
    public static int toll(@Nullable UUID village, int coins) {
        if (village == null || coins <= 0 || !CivicLand.live()) return 0;
        int pct = tollPercent(village);
        if (pct <= 0) return 0;
        int owed = TOLL_CARRY.getOrDefault(village, 0) + coins * pct;
        int toll = Math.min(coins, owed / 100);
        TOLL_CARRY.put(village, owed - toll * 100);
        if (toll <= 0) return 0;
        Ledger.addCoins(village, toll);
        Economy.tithe(village, toll);
        net.minecraft.server.MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        long day = server == null ? 0 : server.overworld().getDayTime() / 24000L;
        book(village, day, "toll", toll);
        return toll;
    }

    // ------------------------------------------------------------------ the council's sitting

    /** What is put to the council: which lever, which way (+1 up, -1 down), to what, and the reason given. */
    record Motion(Lever lever, int dir, int to, String why) {
        String question() {
            return (dir > 0 ? "that " + lever.words + " be raised to " : "that " + lever.words + " be lowered to ") + words(lever, to);
        }
    }

    /** One vote cast: who, aye or no, why; a player's (a knight's, a seated player's, a leader's) or a folk's. */
    record Vote(@Nullable UUID folk, String name, boolean aye, String why, boolean player, int weight) {}

    record Tally(Motion motion, int ayes, int nays, List<Vote> votes) {
        boolean carried() {
            return ayes > nays;
        }
    }

    /** What the town needs of its taxes just now, and what is put to the council; null when the taxes are left be. */
    @Nullable
    static Motion motion(ServerLevel level, Villages.Village v, boolean protest) {
        UUID id = v.id();
        int bill = Math.max(1, Market.wageBill(id));
        int purse = Ledger.coins(id) - Market.saved(id, level.getGameTime());
        double days = purse / (double) bill;
        boolean war = !Wars.enemies(id).isEmpty();
        int share = Market.lastShare(id);
        int unrest = TaxUnrest.level(id);
        // Down: the town angry, or a treasury fuller than it needs; a levy with no war to pay for.
        boolean down = protest || unrest >= TaxUnrest.PROTEST || days >= 14 && !war && share >= 100;
        if (!war && rate(id, Lever.LEVY) > 0 && !down) return new Motion(Lever.LEVY, -1, 0, "the war is over, and the levy with it");
        if (down) {
            for (Lever l : new Lever[]{ Lever.HEAD, Lever.LEVY, Lever.TOLL, Lever.WAGE }) {
                if (l == Lever.LEVY && war && !protest) continue;
                int r = rate(id, l);
                if (r > 0 && movable(id, l)) {
                    return new Motion(l, -1, r - 1, protest ? "the town has made its feelings plain" : unrest >= TaxUnrest.PROTEST
                        ? "the town will not bear it" : "the treasury holds more than it needs");
                }
            }
            return null;
        }
        // Up: wages paid short, a thin purse, a war, coin wanted for something to buy.
        boolean thin = share >= 0 && share < 100 || days < 3;
        boolean plans = Market.saved(id, level.getGameTime()) > Ledger.coins(id) || wonderPlanned(id);
        if (war && rate(id, Lever.LEVY) < 2) return new Motion(Lever.LEVY, 1, rate(id, Lever.LEVY) + 1, "the war must be paid for");
        if (!thin && !plans) return null;
        if (unrest >= TaxUnrest.GRUMBLE + 10) return null;                    // not with the town grumbling already
        String why = share >= 0 && share < 100 ? "the wages were paid short" : days < 3 ? "the treasury is all but empty"
            : wonderPlanned(id) ? "the town has a wonder of the world to raise" : "the town is saving for what it means to buy";
        for (Lever l : new Lever[]{ Lever.HEAD, Lever.TOLL, Lever.WAGE }) {
            int r = rate(id, l);
            if (r < 2 && movable(id, l)) return new Motion(l, 1, r + 1, why);
        }
        return null;
    }

    /** Has the town the plans of a wonder it has not yet laid the dues by for (Wonders): a great work to pay for? */
    static boolean wonderPlanned(UUID village) {
        for (Wonders.Wonder w : Wonders.Wonder.values()) {
            if (CityTree.has(village, w.civic) && !Wonders.paid(village, w) && Wonders.claim(w) == null) return true;
        }
        return false;
    }

    /** The council votes on a motion: its members, the leader, a lord thrice, the knights and seated players. */
    static Tally tally(ServerLevel level, Villages.Village v, Motion m) {
        UUID id = v.id();
        List<Vote> votes = new ArrayList<>();
        List<VillageFolkEntity> council = new ArrayList<>(Council.members(id));
        VillageFolkEntity leader = Orders.elderOf(id);
        if (leader != null && !council.contains(leader)) council.add(0, leader);
        boolean lord = Government.of(id) == Government.Form.LORD;
        for (VillageFolkEntity f : council) {
            Referendums.Judged j = judge(level, v, f, m);
            int weight = lord && leader == f ? 3 : 1;
            votes.add(new Vote(f.getUUID(), f.displayNameCap(), j.aye(), j.why(), false, weight));
        }
        // The players with a say: the knights of the town, a player given a seat, a player who leads (thrice in a lordship's way: its word).
        for (Knights.Say s : Knights.says(id)) {
            if (s.choice() == 0) continue;
            boolean aye = s.choice() == m.dir();
            votes.add(new Vote(null, s.name(), aye, aye ? (m.dir() > 0 ? "the town must be kept" : "the people are taxed enough")
                : (m.dir() > 0 ? "the people are taxed enough" : "the town must be kept"), true, s.weight()));
        }
        int ayes = 0, nays = 0;
        for (Vote x : votes) if (x.aye()) ayes += x.weight(); else nays += x.weight();
        return new Tally(m, ayes, nays, votes);
    }

    /**
     * How a councillor weighs a motion on the taxes, as LawBook weighs a law: by what it cares for (the Merchant and
     * the Free Spirit against a tax, the Visionary, the Homemaker and, at war, the Guardian for it), by what the town
     * needs as it feels it, by how the town is taking it, by the players it likes who urged it, its own purse; a devout
     * folk leans the way its leader does.
     */
    static Referendums.Judged judge(ServerLevel level, Villages.Village v, VillageFolkEntity f, Motion m) {
        UUID id = v.id();
        int[] w = Values.of(f);
        double prog = w[Values.Value.PROGRESS.ordinal()], homes = w[Values.Value.HOMES.ordinal()], safety = w[Values.Value.SAFETY.ordinal()],
            wealth = w[Values.Value.WEALTH.ordinal()], leisure = w[Values.Value.LEISURE.ordinal()], food = w[Values.Value.FOOD.ordinal()];
        boolean war = !Wars.enemies(id).isEmpty();
        // For a tax (dir up): what it would pay for, against what it costs a body.
        double s = 0.3 * prog + 0.25 * homes + 0.1 * food + (war ? 0.5 : 0.1) * safety - 0.55 * wealth - 0.3 * leisure;
        // Its reasons, in its own voice: what it cares for most.
        String yes = switch (Values.top(f)) {
            case PROGRESS -> "the next age won't pay for itself";
            case HOMES -> "houses don't build themselves";
            case SAFETY -> "the watch and the walls want paying for";
            case FOOD -> "a town with an empty purse goes hungry in the end";
            case TRADITION -> "a town pays its way, as it always has";
            default -> "the town needs its purse";
        };
        String no = switch (Values.top(f)) {
            case WEALTH -> "a coin taxed is a coin not spent at the market";
            case LEISURE -> "folk work hard enough without this";
            case FOOD -> "that's bread out of children's mouths";
            case HOMES -> "folk are saving for their own roofs";
            default -> "folk are taxed enough";
        };
        int share = Market.lastShare(id);
        if (share >= 0 && share < 100) { s += 18; yes = "the wages must be paid"; }
        int bill = Math.max(1, Market.wageBill(id));
        if (Ledger.coins(id) < 3 * bill) s += 10;
        if (Ledger.coins(id) >= 14 * bill) { s -= 15; no = "the treasury's full enough"; }
        if (m.lever() == Lever.LEVY && war) { s += 15 + 0.2 * safety; yes = "the war must be paid for"; }
        if (m.lever() == Lever.LEVY && !war) { s -= 30; no = "there's no war to pay for"; }
        int unrest = TaxUnrest.level(id);
        s -= 0.6 * Math.max(0, unrest - 20);
        if (unrest >= TaxUnrest.PROTEST) no = "the town won't stand for more";
        if (Wealth.tier(f) == Wealth.Tier.POOR) { s -= 12; no = "I can barely pay as it is"; }
        if (arrears(id, f.getUUID()) > 0) s -= 8;
        s += urged(id, f);
        s /= 2.0;
        s *= m.dir();
        // A devout soul (the old ways its first care) follows its leader's lead.
        if (Values.top(f) == Values.Value.TRADITION) {
            VillageFolkEntity leader = Orders.elderOf(id);
            if (leader != null && leader != f) s += judgeOwn(level, v, leader, m) ? 10 : -10;
        }
        s += Math.floorMod(Objects.hash(f.getUUID(), m.lever(), m.to()), 7) - 3;
        boolean aye = s > 0;
        String why = m.dir() > 0 ? (aye ? yes : no) : (aye ? no : yes);
        return new Referendums.Judged(aye, (int) Math.round(s), why);
    }

    private static boolean judgeOwn(ServerLevel level, Villages.Village v, VillageFolkEntity leader, Motion m) {
        int[] w = Values.of(leader);
        double s = 0.3 * w[Values.Value.PROGRESS.ordinal()] + 0.3 * w[Values.Value.SAFETY.ordinal()] - 0.5 * w[Values.Value.WEALTH.ordinal()];
        return s * m.dir() > 0;
    }

    /** The players who urged the council one way or the other, as this councillor weighs them (those it likes). */
    static double urged(UUID village, VillageFolkEntity f) {
        CompoundTag u = CivicLand.sub(CivicLand.town(village), "urged");
        double s = 0;
        for (String k : u.getAllKeys()) {
            UUID p = CivicLand.uuid(k);
            if (p == null) continue;
            int aff = f.persona().affinity(p);
            if (aff >= 25) s += 16 * Integer.signum(u.getInt(k)) * (aff >= 60 ? 1.5 : 1.0);
            else if (aff <= -20) s -= 6 * Integer.signum(u.getInt(k));
        }
        return s;
    }

    /**
     * The council's meeting (Assemblies, the council's script): the taxes taken up in turn, the motion read, two or three
     * of them saying how they vote and why, and the count, which carries or loses it as it is read out.
     */
    static void council(ServerLevel level, UUID village, List<Assemblies.Line> s) {
        if (!CivicLand.live()) return;
        Villages.Village v = Villages.get(village);
        if (v == null) return;
        long day = CivicLand.day(level);
        Motion m = motion(level, v, false);
        if (m == null) {
            s.add(new Assemblies.Line(null, "The taxes stand as they are: " + summary(village) + ".", ' ',
                () -> { CivicLand.town(village).putLong("sat", day + 1); CivicLand.changed(); }));
            return;
        }
        Tally t = tally(level, v, m);
        s.add(new Assemblies.Line(null, "On the taxes: it is moved " + m.question() + ", for " + m.why() + ".", '?', null));
        int spoke = 0;
        for (Vote x : t.votes()) {
            if (x.folk() == null || spoke >= 3) continue;
            s.add(new Assemblies.Line(x.folk(), (x.aye() ? "Aye: " : "No: ") + x.why() + ".", ' ', null));
            spoke++;
        }
        String said = t.carried() ? "Carried, " + t.ayes() + " to " + t.nays() + ": " + m.lever().words + " is now " + words(m.lever(), m.to()) + "."
            : "Lost, " + t.nays() + " to " + t.ayes() + ": " + m.lever().words + " stays as it is.";
        s.add(new Assemblies.Line(null, said, t.carried() ? (m.dir() < 0 ? '!' : '?') : '?', () -> enact(level, v, t, day, "the council")));
    }

    /** The council sits on the taxes now, quietly (or as an operator asks): what it decided, in words. */
    public static String sit(ServerLevel level, Villages.Village v, long day, String how) {
        Motion m = motion(level, v, false);
        CompoundTag tag = CivicLand.town(v.id());
        if (m == null) {
            tag.putLong("sat", day + 1);
            CivicLand.changed();
            return "The council left the taxes as they are: " + summary(v.id()) + ".";
        }
        Tally t = tally(level, v, m);
        return enact(level, v, t, day, how);
    }

    /** A count's outcome: the lever moved if it carried, and either way the vote into the books and the chronicle. */
    static String enact(ServerLevel level, Villages.Village v, Tally t, long day, String how) {
        UUID id = v.id();
        CompoundTag tag = CivicLand.town(id);
        tag.putLong("sat", day + 1);
        Motion m = t.motion();
        List<String> ayes = new ArrayList<>(), nays = new ArrayList<>();
        for (Vote x : t.votes()) (x.aye() ? ayes : nays).add(x.name() + (x.weight() > 1 ? " (x" + x.weight() + ")" : ""));
        String knights = knightsLine(t);
        // "the council voted 4 to 2 that the head tax be raised to ...", or "... voted 4 to 2 against a motion that ..."
        String line = how + " voted " + (t.carried() ? t.ayes() + " to " + t.nays() + " " : t.nays() + " to " + t.ayes() + " against a motion ")
            + m.question() + knights;
        CompoundTag vote = new CompoundTag();
        vote.putLong("d", day + 1);
        vote.putString("motion", CivicLand.cap(m.question()));
        vote.putInt("ayes", t.ayes());
        vote.putInt("nays", t.nays());
        vote.putBoolean("carried", t.carried());
        vote.putString("ayeNames", String.join(", ", ayes));
        vote.putString("nayNames", String.join(", ", nays));
        ListTag votes = CivicLand.list(tag, "votes");
        votes.add(vote);
        while (votes.size() > 8) votes.remove(0);
        // The players who urged it have had their say: next week is a new question.
        tag.remove("urged");
        CivicLand.changed();
        if (t.carried()) {
            set(level, v, m.lever(), m.to(), how + " voted " + t.ayes() + " to " + t.nays());
            TaxUnrest.changed(level, v, m.dir(), m.lever());
        } else {
            Villages.tell(id, day, line);
        }
        return CivicLand.cap(line) + ".";
    }

    /** ", Sir Ada voting aye", for the chronicle. */
    static String knightsLine(Tally t) {
        List<String> said = new ArrayList<>();
        for (Vote x : t.votes()) if (x.player()) said.add(x.name() + " voting " + (x.aye() ? "aye" : "no"));
        return said.isEmpty() ? "" : " (" + CivicLand.names(said) + ")";
    }

    /** The taxes in a line: "a head tax of two coins, a tenth of wages, no toll". */
    public static String summary(UUID village) {
        List<String> out = new ArrayList<>();
        for (Lever l : Lever.values()) {
            int r = rate(village, l);
            if (l == Lever.WAGE || r > 0) out.add(l.words + " " + words(l, r));
        }
        return String.join(", ", out);
    }

    // ------------------------------------------------------------------ what folk say, and what players say to them

    /** About the taxes, said to any folk: its answer, or null when the words are about something else. */
    @Nullable
    static String talk(VillageFolkEntity f, Player p, String low) {
        UUID id = f.ownerId();
        if (id == null || !CivicLand.live()) return null;
        boolean taxes = low.contains("tax") || low.contains("levy") || low.contains("toll");
        if (!taxes) return null;
        if (low.contains("pay") && !low.contains("who pays")) {
            return f.level() instanceof ServerLevel level ? pay(level, id, p) : null;
        }
        if (low.contains("lower") || low.contains("too high") || low.contains("too heavy") || low.contains("cut the") || low.contains("less tax")) {
            return urge(f, p, -1);
        }
        if (low.contains("raise") || low.contains("higher") || low.contains("more tax") || low.contains("too low")) {
            return urge(f, p, 1);
        }
        if (low.contains("tithe")) return null;                       // the law's tithe: the leader's and the law-book's words
        return describe(f, p);
    }

    /** "The taxes should be lower": put to the council at its next sitting, heard by the councillors who think well of you. */
    static String urge(VillageFolkEntity f, Player p, int dir) {
        UUID id = f.ownerId();
        CivicLand.sub(CivicLand.town(id), "urged").putInt(p.getUUID().toString(), dir);
        CivicLand.changed();
        boolean member = Council.members(id).contains(f);
        int aff = f.persona().affinity(p.getUUID());
        long next = nextSitting(f.level().getDayTime() / 24000L);
        String when = "the council sits on day " + next;
        if (!member) return "I'll pass it on. " + CivicLand.cap(when) + " — the councillors who think well of you will hear it.";
        if (aff >= 25) return dir < 0 ? "Lower? I'd not say no. I'll say so at the sitting — " + when + "."
            : "More for the treasury? You may be right. I'll say so at the sitting — " + when + ".";
        return "I'll weigh it, for what you've done for us. " + CivicLand.cap(when) + ".";
    }

    static long nextSitting(long day) {
        for (long d = day; d < day + 8; d++) if (Math.floorMod(d, 7L) == COUNCIL_DAY) return d;
        return day + 7;
    }

    /** "What are the taxes here?": the rates, what you owe, the next tax day, and how it feels about them. */
    static String describe(VillageFolkEntity f, Player p) {
        UUID id = f.ownerId();
        long day = f.level().getDayTime() / 24000L;
        StringBuilder sb = new StringBuilder();
        sb.append(Villages.name(id)).append(" levies ").append(summary(id)).append(". ");
        long next = nextTaxDay(id, day);
        sb.append(next == day ? "It's tax day today. " : "The next tax day is day " + next + ". ");
        int owed = owedBy(id, p.getUUID());
        if (owed > 0) sb.append("You owe ").append(coins(owed)).append(". ");
        else if (Citizens.is(id, p.getUUID())) sb.append("You're paid up. ");
        String mine = TaxUnrest.opinion(f);
        if (!mine.isEmpty()) sb.append(mine);
        return sb.toString().trim();
    }

    /**
     * High taxes slow the spending: a folk short of comfort, in a town taxed hard, keeps its coin some days rather than
     * spend it at the market, the shop or the café (VillageFolkEntity). True on the days it holds back.
     */
    public static boolean holdsBack(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || !CivicLand.live() || Wealth.tier(f).ordinal() >= Wealth.Tier.WELL_OFF.ordinal()) return false;
        int burden = TaxUnrest.burden(id);
        int pressure = Math.max(0, burden - 8) * 3 + (arrears(id, f.getUUID()) > 0 ? 25 : 0);
        if (pressure <= 0) return false;
        long day = f.level().getDayTime() / 24000L;
        return Math.floorMod(Objects.hash(f.getUUID(), day, "taxes"), 100) < Math.min(60, pressure);
    }

    // ------------------------------------------------------------------ the books' page

    static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = CivicLand.day(level);
        CompoundTag tag = CivicLand.town(id);
        CompoundTag out = new CompoundTag();
        ListTag rates = new ListTag();
        for (Lever l : Lever.values()) {
            CompoundTag r = new CompoundTag();
            int rate = rate(id, l);
            r.putString("lever", CivicLand.cap(l.words));
            r.putInt("rate", rate);
            r.putString("words", words(l, rate));
            r.putString("does", l.does + (l == Lever.LEVY && Wars.enemies(id).isEmpty() && rate > 0 ? " (not collected: no war)" : ""));
            r.putLong("since", tag.getLong("since." + l.name()) - 1);
            rates.add(r);
        }
        out.put("rates", rates);
        out.putLong("day", day);
        out.putLong("taxDay", nextTaxDay(id, day));
        out.putLong("sitting", nextSitting(day));
        // The take, day by day, oldest first.
        ListTag days = new ListTag();
        int week = 0;
        for (Tag x : CivicLand.list(tag, "hist")) {
            CompoundTag e = ((CompoundTag) x).copy();
            e.putLong("d", e.getLong("d") - 1);
            int all = e.getInt("head") + e.getInt("levy") + e.getInt("toll") + e.getInt("wage") + e.getInt("players");
            e.putInt("all", all);
            if (day - e.getLong("d") < 7) week += all;
            days.add(e);
        }
        out.put("days", days);
        out.putInt("week", week);
        int[] arrears = arrearsTotal(id);
        out.putInt("arrearsFolk", arrears[0]);
        out.putInt("arrearsCoins", arrears[1]);
        List<String> owing = new ArrayList<>();
        CompoundTag a = CivicLand.sub(tag, "arrears");
        for (AssistantEntity f : Villages.folkOf(id)) {
            int n = a.getInt(f.getUUID().toString());
            if (n > 0 && owing.size() < 8) owing.add(f.displayNameCap() + ": " + coins(n));
        }
        CompoundTag pay = CivicLand.sub(tag, "pay");
        for (String k : pay.getAllKeys()) {
            CompoundTag me = pay.getCompound(k);
            if (me.getInt("owed") > 0 && owing.size() < 12) owing.add(me.getString("name") + " (a citizen): " + coins(me.getInt("owed"))
                + (me.getInt("weeks") > 0 ? ", " + me.getInt("weeks") + (me.getInt("weeks") == 1 ? " week" : " weeks") + " behind" : ""));
        }
        out.put("owing", CivicLand.strings(owing));
        // Tax day: who went round, and how it went.
        VillageFolkEntity c = CivicLand.find(level, CivicLand.uuid(tag.getString("collector")));
        boolean today = tag.getLong("roundDay") == day + 1;
        out.putString("collector", c == null ? "" : c.displayNameCap());
        out.putBoolean("roundToday", today);
        out.putBoolean("roundOpen", today && !tag.getBoolean("closed"));
        out.putString("round", tag.getLong("roundDay") <= 0 ? "No tax day yet." : "Last tax day (day " + (tag.getLong("roundDay") - 1) + "): "
            + coins(tag.getInt("r.took")) + " taken, " + tag.getInt("r.letOff") + " let off, " + tag.getInt("r.short") + " short, "
            + tag.getInt("r.refused") + " refused" + (tag.getBoolean("chased") ? "; the collector was chased off" : "")
            + (tag.getBoolean("guarded") ? "; the watch walked the round" : ""));
        out.put("votes", CivicLand.list(tag, "votes").copy());
        return out;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: tax day's round, now, all at once (the collector's walk skipped). */
    public static void collectNowForTests(ServerLevel level, Villages.Village v) {
        long day = CivicLand.day(level);
        startRound(level, v, day);
        CompoundTag tag = CivicLand.town(v.id());
        VillageFolkEntity c = CivicLand.find(level, CivicLand.uuid(tag.getString("collector")));
        for (String s : CivicLand.read(CivicLand.list(tag, "round"))) {
            if (s.startsWith("p:")) continue;
            VillageFolkEntity f = CivicLand.find(level, CivicLand.uuid(s));
            if (f == null) continue;
            if (collectFrom(level, v, c, f, tag.getBoolean("guarded")) == Outcome.CHASED) {
                tag.putBoolean("chased", true);
                closeRound(level, v, true);
                return;
            }
        }
        tag.put("round", new ListTag());
        closeRound(level, v, false);
    }

    /** Tests: the round started (the collector chosen and its list laid out), not walked. The collector's id. */
    @Nullable
    public static UUID startRoundForTests(ServerLevel level, Villages.Village v) {
        startRound(level, v, CivicLand.day(level));
        return CivicLand.uuid(CivicLand.town(v.id()).getString("collector"));
    }

    /** Tests: who went round on the last tax day, or null. */
    @Nullable
    public static UUID collectorForTests(UUID village) {
        return CivicLand.uuid(CivicLand.town(village).getString("collector"));
    }

    /** Tests: how far the round has got: {met, took, left on the list, closed 0/1, chased 0/1}. */
    public static int[] roundForTests(UUID village) {
        CompoundTag tag = CivicLand.town(village);
        return new int[]{ tag.getInt("r.met"), tag.getInt("r.took"), CivicLand.list(tag, "round").size(), tag.getBoolean("closed") ? 1 : 0,
            tag.getBoolean("chased") ? 1 : 0 };
    }

    /** Tests: a folk's arrears set outright. */
    public static void arrearsForTests(UUID village, UUID folk, int coins) {
        setArrears(village, folk, coins);
    }

    /** Tests: a lever set outright. */
    public static void setForTests(UUID village, Lever l, int value) {
        CivicLand.town(village).putInt("rate." + l.name(), Math.max(0, Math.min(3, value)));
        TaxUnrest.forgetBurden(village);
        CivicLand.changed();
    }

    /** Tests: the motion the council would take up now, in words, or "" for none. */
    public static String motionForTests(ServerLevel level, Villages.Village v) {
        Motion m = motion(level, v, false);
        return m == null ? "" : m.question();
    }

    /** Tests: the last vote's {ayes, nays, carried}, and its names ("ayes|nays"). */
    public static String lastVoteForTests(UUID village) {
        ListTag votes = CivicLand.list(CivicLand.town(village), "votes");
        if (votes.isEmpty()) return "";
        CompoundTag v = votes.getCompound(votes.size() - 1);
        return v.getInt("ayes") + "-" + v.getInt("nays") + (v.getBoolean("carried") ? " carried" : " lost") + " | " + v.getString("ayeNames")
            + " | " + v.getString("nayNames");
    }

    /** Tests: the take booked today under this key ("head", "toll", "players"...). */
    public static int bookedForTests(UUID village, long day, String key) {
        return entry(village, day).getInt(key);
    }

    /** Tests: the lines the council's meeting would read on the taxes. */
    public static List<String> councilLinesForTests(ServerLevel level, UUID village) {
        List<Assemblies.Line> s = new ArrayList<>();
        council(level, village, s);
        List<String> out = new ArrayList<>();
        for (Assemblies.Line l : s) {
            out.add(l.text());
        }
        for (Assemblies.Line l : s) if (l.effect() != null) l.effect().run();
        return out;
    }
}
