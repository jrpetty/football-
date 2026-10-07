package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [identity] Each town its own law-book. The laws are set at the founding from the town's character and government,
 * and the government keeps them under review (each morning it is due: every two days for a lord, three for an
 * elected leader or the chaplain, four for a guild, six for the elders), changing one at a time where the town's
 * character, or what has happened to it (a raid: a curfew; a war: conscription), has come to want another. Who
 * decides is the government's (Government): a lord, the chaplain or an elected leader by decree, if it agrees with
 * it; the elders or the guild's masters by a vote among themselves (the traders on the tariffs, the hunters on the
 * hunting); a commune by the whole town's vote (Referendums). A player who leads sets them with /village identity law.
 *
 * <p>Every law does something real:
 * <ul>
 * <li><b>The tithe</b> (one coin in twenty to one in five): the tax on wages on payday (Market.taxOn) and the tithe on
 *     savings on the day of rest (Market.tithe), into the treasury. A heavy one is grumbled at.</li>
 * <li><b>Tariffs</b>: outsiders' goods valued a tenth less at the bargaining (TradeTalks), so fewer and smaller
 *     deals to buy in; free trade a little more.</li>
 * <li><b>A curfew</b>: every folk indoors and to bed soon after dusk (VillageFolkEntity.bedtimeTick), the gates shut
 *     earlier, a fifth less crime; the worldly chafe at it.</li>
 * <li><b>Weapons</b>: where the watch alone goes armed, a visitor walking in with a blade or a bow in hand is asked to
 *     put it away, and if it will not, fined (Laws).</li>
 * <li><b>The borders</b>: closed, newcomers are voted down (Newcomers).</li>
 * <li><b>Conscription</b>: in war every fit adult is enrolled in the militia and drills, not a third (Militia).</li>
 * <li><b>The apprentice age</b>: children at a grown-up's side from their first day, their second, or only their
 *     third (VillageFolkEntity.childhood), the mornings before it at school.</li>
 * <li><b>Drink</b>: the tavern open late, shut at nightfall, or a dry town where nothing is sold at the bar (Tavern).</li>
 * <li><b>The day of rest</b>: kept loosely (every other week), as usual, or strictly (a longer service).</li>
 * <li><b>Hunting rights</b>: reserved to the town's hunters (fewer of them, and a visitor who kills the town's game
 *     inside it is fined for poaching), or common to all (a few more hunters).</li>
 * <li><b>Who may own a house</b>: anybody in good standing, citizens only, or nobody: a commune's houses are the
 *     town's (Homes.playerBuys).</li>
 * </ul>
 * A visitor is told the laws that concern it as it walks in ("Thornhurst keeps a curfew; the watch alone goes
 * armed"), and the law-book is on the Identity page and the board.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class LawBook {

    private LawBook() {}

    public enum Law {
        TITHE("the tithe", new String[]{ "one coin in twenty", "one coin in ten", "three coins in twenty", "one coin in five" }),
        TARIFF("trade with outsiders", new String[]{ "free trade", "tariffs on outside goods" }),
        CURFEW("the curfew", new String[]{ "no curfew", "a curfew after dark" }),
        WEAPONS("weapons", new String[]{ "all may go armed", "the watch alone goes armed" }),
        BORDERS("the borders", new String[]{ "open to newcomers", "closed to newcomers" }),
        CONSCRIPTION("conscription", new String[]{ "no conscription", "every fit adult drills in a war" }),
        APPRENTICE("the apprentice age", new String[]{ "apprenticed from the first day", "apprenticed at the usual age", "kept at school longer" }),
        DRINK("drink", new String[]{ "the tavern open late", "the tavern shut at nightfall", "a dry town" }),
        SABBATH("the day of rest", new String[]{ "kept loosely", "kept", "kept strictly" }),
        HUNTING("hunting rights", new String[]{ "common to all", "reserved to the town's hunters" }),
        HOUSES("who may own a house", new String[]{ "anyone in good standing", "citizens only", "nobody: the houses are the town's" });

        public final String name;
        public final String[] options;

        Law(String name, String[] options) {
            this.name = name;
            this.options = options;
        }

        public String option(int v) {
            return options[Math.max(0, Math.min(options.length - 1, v))];
        }

        @Nullable
        public static Law named(String s) {
            try { return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
        }
    }

    /** The plain town's law-book: a tithe of one in ten, and nothing else out of the way. */
    static int[] defaults() {
        return new int[]{ 1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0 };
    }

    /** When each visitor was last asked to put its weapon away (player|town to game time). */
    private static final Map<String, Long> WARNED = new ConcurrentHashMap<>();

    static void resetForTests() {
        WARNED.clear();
    }

    // ------------------------------------------------------------------ reading it

    /**
     * A law of the town's, as it stands: its own, or (a colony of a capital) its capital's, which is the seat of its
     * colonies (Fame.seat); the plain town's for a town not yet seeded, or a test's plain town.
     */
    public static int value(@Nullable UUID village, Law l) {
        if (Identity.neutral()) return defaults()[l.ordinal()];
        Identity.Rec r = Identity.known(village);
        if (r == null) return defaults()[l.ordinal()];
        UUID seat = r.seat;
        if (seat != null) {
            Identity.Rec s = Identity.known(seat);
            if (s != null) return s.laws[l.ordinal()];
        }
        return r.laws[l.ordinal()];
    }

    public static boolean curfew(@Nullable UUID village) { return value(village, Law.CURFEW) == 1; }

    public static boolean watchAlone(@Nullable UUID village) { return value(village, Law.WEAPONS) == 1; }

    public static boolean bordersClosed(@Nullable UUID village) { return value(village, Law.BORDERS) == 1; }

    public static boolean dry(@Nullable UUID village) { return value(village, Law.DRINK) == 2; }

    public static boolean huntingReserved(@Nullable UUID village) { return value(village, Law.HUNTING) == 1; }

    // ------------------------------------------------------------------ what each does

    /** The tax on wages, in the hundred (Market.taxOn): five, ten, fifteen or twenty. */
    public static int taxPercent(@Nullable UUID village) {
        return switch (value(village, Law.TITHE)) {
            case 0 -> 5;
            case 2 -> 15;
            case 3 -> 20;
            default -> 10;
        };
    }

    /** The tithe on the day of rest (PlayerLeader.tithe, where no player leads): the law's share of what a folk holds over a dozen. */
    public static int tithe(@Nullable UUID village, int over, int due) {
        if (Identity.neutral() || Identity.known(village) == null) return due;
        return over <= 0 ? 0 : over * taxPercent(village) / 100;
    }

    /** A folk's hour for bed (VillageFolkEntity.bedtimeTick): under a curfew, soon after dusk whatever its nature. */
    public static long bedtime(@Nullable UUID village, long usual) {
        return curfew(village) ? Math.min(usual, 13050L) : usual;
    }

    /** How many days old a child must be to spend its mornings at a grown-up's side (VillageFolkEntity.childhood). */
    public static int apprenticeFrom(@Nullable UUID village, int usual) {
        return switch (value(village, Law.APPRENTICE)) {
            case 0 -> 0;
            case 2 -> usual + 1;
            default -> usual;
        };
    }

    /** Is the tavern open at this hour (Tavern.evening)? Shut from mid-evening where the law shuts it at nightfall. */
    public static boolean tavernOpen(@Nullable UUID village, long t) {
        int d = value(village, Law.DRINK);
        if (d == 1 && t >= 13500L) return false;
        return !(curfew(village) && t >= 13050L);
    }

    /** Is this its day of rest, by its law (RestDay.today)? Kept loosely, only every other week. */
    public static boolean restKept(@Nullable UUID village, long day) {
        if (value(village, Law.SABBATH) != 0) return true;
        return Math.floorMod(day / 7 + (village == null ? 0 : village.hashCode()), 2) == 0;
    }

    /** When the games begin after the service on the day of rest (RestDay.spend): later, where it is kept strictly. */
    public static long gamesFrom(@Nullable UUID village, long usual) {
        return value(village, Law.SABBATH) == 2 ? usual + 1400L : usual;
    }

    /** The share of the town's able folk its militia enrols at war (Militia): all of them under conscription. */
    public static double militiaShare(@Nullable UUID village, double usual, boolean war) {
        return war && value(village, Law.CONSCRIPTION) == 1 ? 1.0 : usual;
    }

    /** The law's say in a trade's share (Ethos.shareLean): fewer hunters where hunting is reserved, a few more where it is common. */
    static double shareLean(@Nullable UUID village, StationTask t) {
        if (t != StationTask.HUNT || Identity.known(village) == null) return 1.0;
        return huntingReserved(village) ? 0.85 : 1.1;
    }

    /** How a buyer values outsiders' goods at the bargaining (TradeTalks.lay): a tenth less under tariffs. */
    public static double importFactor(@Nullable UUID buyer) {
        if (Identity.neutral() || Identity.known(buyer) == null) return 1.0;
        double x = value(buyer, Law.TARIFF) == 1 ? 0.9 : 1.02;
        return x * (1.0 - 0.1 * Ethos.minus(buyer, Ethos.Axis.TRADE));
    }

    /** Why this player may not buy a house here (Homes.playerBuys), or null if it may. */
    @Nullable
    public static String housesBarred(@Nullable UUID village, boolean citizen) {
        int h = value(village, Law.HOUSES);
        String name = village == null ? "This town" : Villages.name(village);
        if (h == 2) return name + "'s houses are the town's, every one of them: by its law nobody may buy one.";
        if (h == 1 && !citizen) return name + " sells its houses to its citizens only — that's its law. Ask to live here first.";
        return null;
    }

    /** What its laws do to the town's spirits (Identity.contentment). */
    static int contentment(UUID village, List<String> good, List<String> bad) {
        int n = 0;
        int faith = Ethos.lean(village, Ethos.Axis.FAITH);
        if (curfew(village) && faith <= -25) { n -= 2; bad.add("the curfew chafes"); }
        if (dry(village) && faith <= 0) { n -= 2; bad.add("a dry town"); }
        int tithe = value(village, Law.TITHE);
        if (tithe == 3) { n -= 2; bad.add("the tithe bites"); }
        else if (tithe == 0) { n += 1; good.add("a light tithe"); }
        if (value(village, Law.SABBATH) == 2 && faith >= Ethos.POLE) { n += 1; good.add("the day of rest kept as it should be"); }
        return n;
    }

    // ------------------------------------------------------------------ what the town would have

    /** The law-book a town of this character, government and history would have now. */
    static int[] desired(UUID village, Identity.Rec r, long day) {
        int t = r.axes[Ethos.Axis.TRADE.ordinal()], w = r.axes[Ethos.Axis.WAR.ordinal()], f = r.axes[Ethos.Axis.FAITH.ordinal()],
            l = r.axes[Ethos.Axis.LEARNING.ordinal()], d = r.axes[Ethos.Axis.DOORS.ordinal()], y = r.axes[Ethos.Axis.WAYS.ordinal()],
            k = r.axes[Ethos.Axis.RANK.ordinal()];
        Government.Form g = r.gov;
        boolean raided = false;
        for (Identity.Ev e : new Identity.Ev[]{ Identity.Ev.RAID_LOST, Identity.Ev.RAID_HELD }) {
            for (long at : r.days(e)) if (day - at <= 7 && day >= at) raided = true;
        }
        boolean war = !Wars.enemies(village).isEmpty();
        int[] want = new int[Law.values().length];
        int tithe = 1;
        if (k <= -Ethos.POLE || g == Government.Form.LORD) tithe++;
        if (g == Government.Form.COMMUNE) tithe = 2;
        if (t >= Ethos.POLE && g != Government.Form.COMMUNE) tithe--;
        if (g == Government.Form.LORD && k <= -60) tithe = 3;
        want[Law.TITHE.ordinal()] = Math.max(0, Math.min(3, tithe));
        want[Law.TARIFF.ordinal()] = t <= -Ethos.POLE || d <= -50 && t < Ethos.POLE ? 1 : 0;
        boolean curfew = ((w - d) / 2 >= Ethos.POLE || f >= 60 || raided && d < 0) && f > -Ethos.POLE;
        want[Law.CURFEW.ordinal()] = curfew ? 1 : 0;
        want[Law.WEAPONS.ordinal()] = (w <= -Ethos.POLE || d <= -Ethos.POLE || g == Government.Form.LORD && k <= -Ethos.POLE) && w < Ethos.POLE ? 1 : 0;
        want[Law.BORDERS.ordinal()] = d <= -40 ? 1 : 0;
        want[Law.CONSCRIPTION.ordinal()] = w >= 40 || g == Government.Form.LORD && w >= 20 || war && w >= 0 ? 1 : 0;
        want[Law.APPRENTICE.ordinal()] = l <= -Ethos.POLE ? 0 : l >= Ethos.POLE ? 2 : 1;
        want[Law.DRINK.ordinal()] = f >= 50 ? 2 : f >= 25 || curfew ? 1 : 0;
        want[Law.SABBATH.ordinal()] = f >= Ethos.POLE || g == Government.Form.CHAPLAIN ? 2 : f <= -Ethos.POLE || y <= -50 ? 0 : 1;
        want[Law.HUNTING.ordinal()] = g == Government.Form.LORD || k <= -40 ? 1 : 0;
        want[Law.HOUSES.ordinal()] = g == Government.Form.COMMUNE ? 2 : d <= -Ethos.POLE ? 1 : 0;
        return want;
    }

    /** The order the government looks at them in: what is pressing first. */
    private static final Law[] REVIEW = { Law.CURFEW, Law.CONSCRIPTION, Law.BORDERS, Law.WEAPONS, Law.TITHE, Law.DRINK, Law.SABBATH,
        Law.TARIFF, Law.HOUSES, Law.HUNTING, Law.APPRENTICE };

    /** The founding's law-book: what the town's character and government would have. */
    static void found(UUID village, Identity.Rec r, long day) {
        int[] want = desired(village, r, day);
        System.arraycopy(want, 0, r.laws, 0, want.length);
        java.util.Arrays.fill(r.lawSince, day);
        r.nextLawLook = day + 2;
    }

    /** The morning's review, when it is due: one law at a time, put to whoever decides. */
    static void morning(ServerLevel level, Villages.Village v, Identity.Rec r, long day) {
        if (day < r.nextLawLook || PlayerLeader.leaderId(v.id()) != null) return;
        int[] want = desired(v.id(), r, day);
        for (Law l : REVIEW) {
            int i = l.ordinal();
            if (want[i] == r.laws[i] || r.lawRefused.getOrDefault(i, -1L) > day) continue;
            propose(level, v, r, l, want[i], day);
            return;
        }
        r.nextLawLook = day + 1;
    }

    /**
     * A change to the law put to whoever decides: a commune's whole town (a referendum tomorrow), the elders or the
     * masters by a vote among themselves now, a lord, a chaplain or an elected leader by its own lights.
     */
    static void propose(ServerLevel level, Villages.Village v, Identity.Rec r, Law l, int value, long day) {
        UUID id = v.id();
        String what = l.option(value);
        Government.Form g = r.gov;
        r.nextLawLook = day + switch (g) {
            case LORD -> 2;
            case GUILD -> 4;
            case COUNCIL -> 6;
            case COMMUNE -> 3;
            default -> 3;
        };
        if (g == Government.Form.COMMUNE) {
            if (Government.asked(id)) return;
            String by = Villages.elderName(id).isEmpty() ? "the commune" : Villages.elderName(id);
            Referendums.call(level, v, Government.KIND, "LAW:" + l.name() + ":" + value, l.name + ": " + what, by,
                "nothing from the stores", "none", what, day + 1, null);
            String line = "the commune is to vote tomorrow on " + l.name + ": " + what;
            r.change(day, line);
            Villages.tell(id, day, line);
            Market.assemblyNews(id, "Tomorrow the whole town votes on " + l.name + ": " + what + ". Have your say at the board.");
            Identity.dirty();
            return;
        }
        List<VillageFolkEntity> deciders = new ArrayList<>();
        String body;
        VillageFolkEntity leader = Identity.leader(id);
        if (g == Government.Form.COUNCIL || g == Government.Form.GUILD || leader == null) {
            List<VillageFolkEntity> all = Elections.voters(id);
            if (g == Government.Form.GUILD) {
                List<VillageFolkEntity> trade = new ArrayList<>();
                for (VillageFolkEntity f : all) if (concerns(l, f.stationTask())) trade.add(f);
                if (trade.size() >= 2) { all = trade; body = "the " + tradeWord(l) + " of the guild"; }
                else body = "the guild's masters";
            } else body = g == Government.Form.COUNCIL ? "the council of elders" : "the council";
            if (g != Government.Form.COUNCIL && g != Government.Form.GUILD) all = Council.members(id);
            deciders.addAll(all);
            int ayes = 0, nays = 0;
            for (VillageFolkEntity f : deciders) if (judge(level, f, l, value).aye()) ayes++; else nays++;
            if (ayes > nays) enact(level, v, r, l, value, day, body + " voted " + ayes + " to " + nays);
            else refuse(v, r, l, value, day, body + " voted " + ayes + " to " + nays + " against " + what);
            return;
        }
        Referendums.Judged j = judge(level, leader, l, value);
        String who = capital(Government.title(id)) + " " + leader.displayNameCap();
        if (j.aye()) enact(level, v, r, l, value, day, who + " decreed it (" + j.why() + ")");
        else refuse(v, r, l, value, day, who + " would not have " + what + " (" + j.why() + ")");
    }

    /** The trades a law concerns, in a guild republic: the traders on tariffs, the hunters on hunting, the watch on weapons. */
    static boolean concerns(Law l, StationTask t) {
        return switch (l) {
            case TARIFF -> t == StationTask.SHOP || t == StationTask.STORE || t == StationTask.HAUL || t == StationTask.TAILOR
                || t == StationTask.BREW || t == StationTask.SMITH;
            case HUNTING -> t == StationTask.HUNT || t == StationTask.RANCH;
            case WEAPONS, CONSCRIPTION, CURFEW -> t == StationTask.GUARD || t == StationTask.SMITH;
            case DRINK -> t == StationTask.BREW || t == StationTask.COOK;
            default -> true;
        };
    }

    static String tradeWord(Law l) {
        return switch (l) {
            case TARIFF -> "traders";
            case HUNTING -> "hunters and herders";
            case WEAPONS, CONSCRIPTION, CURFEW -> "watch and the smiths";
            case DRINK -> "brewers and cooks";
            default -> "masters";
        };
    }

    /** The law changed: written into the book, the chronicle, the gazette and the morning's assembly. */
    static void enact(ServerLevel level, Villages.Village v, Identity.Rec r, Law l, int value, long day, String by) {
        int i = l.ordinal();
        String was = l.option(r.laws[i]);
        r.laws[i] = value;
        r.lawSince[i] = day;
        r.lastLaw = day;
        String line = Villages.name(v.id()) + "'s law on " + l.name + " is now " + l.option(value) + " (it was " + was + "): " + by;
        r.change(day, line);
        Villages.tell(v.id(), day, line);
        Market.assemblyNews(v.id(), "Hear the law: " + l.name + ", " + l.option(value) + ".");
        Identity.dirty();
    }

    /** Turned down: not put again for a week. */
    static void refuse(Villages.Village v, Identity.Rec r, Law l, int value, long day, String why) {
        r.lawRefused.put(l.ordinal(), day + 7);
        r.change(day, why);
        Identity.dirty();
    }

    // ------------------------------------------------------------------ how a folk weighs a law

    /** A commune's question on a law (Government.judge): "LAW:CURFEW:1". */
    static Referendums.Judged judge(ServerLevel level, VillageFolkEntity voter, String subject) {
        String[] p = subject.split(":");
        Law l = p.length > 1 ? Law.named(p[1]) : null;
        if (l == null || p.length < 3) return new Referendums.Judged(false, 0, "I don't follow the question");
        return judge(level, voter, l, Fame.number(p[2]));
    }

    /**
     * Would this folk have the law so? By what it cares for: the Guardian for a curfew and the walls, the Free Spirit
     * against anything that shuts the tavern, the Merchant for free trade and against a heavy tithe, the Provider for
     * keeping its own; a hunter against its hunting being reserved; and a devout folk (the old ways first) leans the way
     * its leader does.
     */
    static Referendums.Judged judge(ServerLevel level, VillageFolkEntity voter, Law l, int value) {
        int[] w = Values.of(voter);
        double food = w[Values.Value.FOOD.ordinal()], homes = w[Values.Value.HOMES.ordinal()], prog = w[Values.Value.PROGRESS.ordinal()],
            safety = w[Values.Value.SAFETY.ordinal()], wealth = w[Values.Value.WEALTH.ordinal()], leisure = w[Values.Value.LEISURE.ordinal()],
            trad = w[Values.Value.TRADITION.ordinal()];
        UUID village = voter.ownerId();
        int now = village == null ? defaults()[l.ordinal()] : value(village, l);
        double dir = Integer.signum(value - now);
        if (dir == 0) dir = 1;
        double s;
        String yes, no;
        switch (l) {
            case CURFEW -> { s = 0.5 * safety + 0.3 * trad - 0.7 * leisure - 0.2 * wealth; yes = "safe streets after dark"; no = "a curfew? I like my evenings"; }
            case WEAPONS -> { s = 0.3 * leisure + 0.3 * trad + 0.1 * safety - 0.4 * wealth; yes = "steel belongs with the watch"; no = "a body has a right to look after itself"; }
            case BORDERS -> { s = 0.5 * safety + 0.3 * trad - 0.4 * wealth - 0.3 * leisure; yes = "we can't take in all comers"; no = "we were all newcomers once"; }
            case CONSCRIPTION -> { s = 0.6 * safety - 0.4 * leisure - 0.3 * wealth - 0.1 * food; yes = "every hand to the wall in a war"; no = "I'm no soldier, and nor are my neighbours"; }
            case TITHE -> { s = 0.3 * homes + 0.3 * prog - 0.6 * wealth + 0.1 * food; yes = "the town needs its purse"; no = "the tithe's heavy enough"; }
            case TARIFF -> { s = 0.4 * food + 0.3 * trad - 0.6 * wealth; yes = "our own makers first"; no = "free trade fills the market"; }
            case APPRENTICE -> { s = 0.5 * prog - 0.4 * food - 0.2 * wealth; yes = "let them learn their letters first"; no = "a child learns a trade by doing it"; }
            case DRINK -> { s = 0.5 * trad + 0.2 * safety - 0.8 * leisure; yes = "less drink, fewer fights"; no = "leave the tavern be"; }
            case SABBATH -> { s = 0.6 * trad - 0.3 * wealth - 0.2 * prog; yes = "the day of rest is the day of rest"; no = "there's work wants doing"; }
            case HUNTING -> { s = 0.3 * trad + 0.2 * wealth - 0.4 * food; yes = "the game is the town's, not every passer-by's"; no = "the woods are everybody's"; }
            case HOUSES -> { s = 0.4 * trad + 0.3 * safety - 0.4 * wealth - 0.2 * homes; yes = "our houses for our own"; no = "anybody should be able to buy a roof"; }
            default -> { s = 0; yes = "it seems right"; no = "I'd leave it be"; }
        }
        s = s * dir / 2.0;
        if (l == Law.HUNTING && voter.stationTask() == StationTask.HUNT) s += value == 1 ? 15 : -15;
        if (l == Law.CONSCRIPTION && voter.stationTask() == StationTask.GUARD) s += value == 1 ? 10 : -10;
        // A devout folk (the old ways its first care) follows its leader's word.
        if (village != null && Values.top(voter) == Values.Value.TRADITION) {
            VillageFolkEntity leader = Identity.leader(village);
            if (leader != null && leader != voter) s += judgeOwn(leader, l, value) ? 10 : -10;
        }
        s += Math.floorMod(Objects.hash(voter.getUUID(), l, value), 7) - 3;
        boolean aye = s > 0;
        return new Referendums.Judged(aye, (int) Math.round(s), aye ? yes : no);
    }

    private static boolean judgeOwn(VillageFolkEntity f, Law l, int value) {
        int[] w = Values.of(f);
        double safety = w[Values.Value.SAFETY.ordinal()], trad = w[Values.Value.TRADITION.ordinal()], leisure = w[Values.Value.LEISURE.ordinal()],
            wealth = w[Values.Value.WEALTH.ordinal()];
        return (0.5 * safety + 0.5 * trad - 0.5 * leisure - 0.3 * wealth) * (value >= 1 ? 1 : -1) > 0;
    }

    /** A commune's vote on a law counted (Government.decided). */
    static void decided(ServerLevel level, Villages.Village v, String subject, boolean carried, int ayes, int nays, long day) {
        Identity.Rec r = Identity.known(v.id());
        String[] p = subject.split(":");
        Law l = p.length > 1 ? Law.named(p[1]) : null;
        if (r == null || l == null || p.length < 3) return;
        int value = Fame.number(p[2]);
        if (carried) enact(level, v, r, l, value, day, "the whole town voted " + ayes + " to " + nays);
        else refuse(v, r, l, value, day, "the whole town voted " + nays + " to " + ayes + " against " + l.option(value));
    }

    // ------------------------------------------------------------------ keeping it

    /**
     * Every two seconds for a visitor in the town (Identity.inTown): where the watch alone goes armed, one walking
     * about the town with a blade or a bow in hand is asked to put it away (by a guard if one is near); still holding it
     * half a minute later, and seen, it is fined under the town's laws (Laws.offence).
     */
    static void watch(ServerLevel level, Player p, UUID village) {
        if (!watchAlone(village) || Laws.exempt(p) || Citizens.is(village, p.getUUID())) return;
        Villages.Village v = Villages.get(village);
        if (v == null || !p.blockPosition().closerThan(v.centre(), 48)) return;
        if (!weapon(p.getMainHandItem())) return;
        String key = p.getUUID() + "|" + village;
        long now = level.getGameTime();
        Long at = WARNED.get(key);
        if (at == null || now - at > 6000L) {
            WARNED.put(key, now);
            VillageFolkEntity by = Treatment.nearestWatch(level, p, village);
            if (by != null) FolkTalk.speak(by, FolkTalk.pick(by.getRandom(), "Only the watch goes armed in " + Villages.name(village)
                + ". Put that away, if you please.", "Sheathe that, friend — it's the law here.", "Weapons away inside the town. The watch keeps the peace."));
            p.displayClientMessage(net.minecraft.network.chat.Component.literal("The watch alone goes armed in " + Villages.name(village)
                + ": put your weapon away.").withStyle(net.minecraft.ChatFormatting.YELLOW), true);
            return;
        }
        if (now - at < 600L) return;
        WARNED.put(key, now);
        long day = level.getDayTime() / 24000L;
        Laws.offence(level, v, p, "going armed in the town against its law", 3);
        Identity.event(village, Identity.Ev.LAW_BROKEN, day);
    }

    /** A blade, an axe, a bow, a crossbow, a trident or a mace. */
    public static boolean weapon(ItemStack s) {
        return !s.isEmpty() && (s.getItem() instanceof SwordItem || s.getItem() instanceof AxeItem || s.getItem() instanceof BowItem
            || s.getItem() instanceof CrossbowItem || s.getItem() instanceof TridentItem || s.getItem() instanceof MaceItem);
    }

    /** A visitor killing the town's game inside it, where hunting is reserved: poaching, if a folk sees it. */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity().level() instanceof ServerLevel level) || !(e.getEntity() instanceof Animal a)) return;
        if (a instanceof TamableAnimal t && t.isTame()) return;
        if (!(e.getSource().getEntity() instanceof Player p) || Laws.exempt(p)) return;
        BlockPos at = a.blockPosition();
        Villages.Village v = Villages.nearest(level, at, 64);
        if (v == null || !huntingReserved(v.id()) || Citizens.is(v.id(), p.getUUID())) return;
        Laws.offence(level, v, p, "poaching the town's game against its law", 4);
        Identity.event(v.id(), Identity.Ev.LAW_BROKEN, level.getDayTime() / 24000L);
    }

    // ------------------------------------------------------------------ the words for it

    /** The laws a visitor should know, most pressing first, up to so many: "a curfew after dark; the watch alone goes armed". */
    public static String notice(@Nullable UUID village, int most) {
        if (Identity.known(village) == null) return "";
        List<String> out = new ArrayList<>();
        if (watchAlone(village)) out.add("the watch alone goes armed");
        if (curfew(village)) out.add("a curfew after dark");
        if (huntingReserved(village)) out.add("its game reserved to its own hunters");
        int h = value(village, Law.HOUSES);
        if (h == 1) out.add("houses sold to citizens only");
        if (h == 2) out.add("no house for sale to anybody");
        int d = value(village, Law.DRINK);
        if (d == 2) out.add("a dry town");
        if (d == 1) out.add("the tavern shut at nightfall");
        int t = value(village, Law.TITHE);
        if (t != 1) out.add("the tithe " + Law.TITHE.option(t));
        if (bordersClosed(village)) out.add("its borders closed to newcomers");
        if (value(village, Law.TARIFF) == 1) out.add("tariffs on outside goods");
        int s = value(village, Law.SABBATH);
        if (s != 1) out.add("the day of rest " + Law.SABBATH.option(s));
        if (value(village, Law.CONSCRIPTION) == 1) out.add("conscription in war");
        return String.join("; ", out.subList(0, Math.min(most, out.size())));
    }

    /** A folk's grumble at a law, for its card, or null. */
    @Nullable
    static String grumble(@Nullable UUID village, VillageFolkEntity f) {
        Values.Value top = Values.top(f);
        if (curfew(village) && top == Values.Value.LEISURE) return "grumbles at the curfew";
        if (dry(village) && top == Values.Value.LEISURE) return "misses its drink: a dry town";
        if (value(village, Law.TITHE) == 3 && top == Values.Value.WEALTH) return "grumbles at the tithe";
        if (value(village, Law.CONSCRIPTION) == 1 && village != null && !Wars.enemies(village).isEmpty() && f.stationTask() != StationTask.GUARD) {
            return "drills for the war, by law";
        }
        if (huntingReserved(village) && f.stationTask() == StationTask.HUNT) return "hunts the town's reserved game";
        return null;
    }

    static ListTag report(UUID village, Identity.Rec r) {
        ListTag out = new ListTag();
        int[] plain = defaults();
        for (Law l : Law.values()) {
            CompoundTag t = new CompoundTag();
            int v = value(village, l);
            t.putString("law", Identity.capital(l.name));
            t.putString("value", l.option(v));
            t.putLong("since", r.lawSince[l.ordinal()]);
            t.putBoolean("notable", v != plain[l.ordinal()]);
            t.putString("does", does(l, v));
            out.add(t);
        }
        return out;
    }

    /** What a law does, in a few words, for the page. */
    static String does(Law l, int v) {
        return switch (l) {
            case TITHE -> (new int[]{ 5, 10, 15, 20 })[Math.max(0, Math.min(3, v))] + " in a hundred of every wage and of savings, to the treasury";
            case TARIFF -> v == 1 ? "outsiders' goods valued a tenth less in a bargain" : "outsiders' goods bargained for freely";
            case CURFEW -> v == 1 ? "everybody to bed soon after dusk; the gates shut earlier; less crime" : "evenings as late as anybody likes";
            case WEAPONS -> v == 1 ? "a visitor's blade or bow put away, or fined" : "anybody may carry arms";
            case BORDERS -> v == 1 ? "newcomers are voted down" : "newcomers are weighed on their merits";
            case CONSCRIPTION -> v == 1 ? "in war, every fit adult enrolled to drill" : "in war, a third of the town drills";
            case APPRENTICE -> v == 0 ? "children at a grown-up's side from their first day" : v == 2 ? "children's first two mornings their own" : "children apprenticed from their second day";
            case DRINK -> v == 2 ? "nothing sold at the bar" : v == 1 ? "the tavern empties at mid-evening" : "the tavern open till bedtime";
            case SABBATH -> v == 0 ? "a day of rest only every other week" : v == 2 ? "a longer morning service" : "a day of rest every week";
            case HUNTING -> v == 1 ? "fewer hunters; a visitor's kill inside the town is poaching" : "a few more hunters";
            case HOUSES -> v == 2 ? "no house sold, to anybody" : v == 1 ? "houses sold to citizens only" : "houses sold to any friend of the town";
        };
    }

    static String line(UUID village) {
        List<String> out = new ArrayList<>();
        for (Law l : Law.values()) out.add(l.name + ": " + l.option(value(village, l)));
        return String.join("; ", out);
    }

    static String capital(String s) {
        return Identity.capital(s);
    }

    // ------------------------------------------------------------------ the commands and the tests

    /** /village identity law &lt;law&gt; &lt;option&gt;: an operator, or the player who leads the town, sets a law. */
    public static String set(ServerLevel level, Villages.Village v, Law l, int value, String by) {
        Identity.Rec r = Identity.known(v.id());
        if (r == null) return "The town has no law-book yet: its first morning writes it.";
        if (value < 0 || value >= l.options.length) return "No such choice: " + String.join(", ", l.options) + " (0 to " + (l.options.length - 1) + ").";
        enact(level, v, r, l, value, level.getDayTime() / 24000L, by);
        return "The law on " + l.name + " is now " + l.option(value) + ".";
    }

    /** Tests (and /village identity profile): the law-book written afresh to what the town would have now. */
    public static void resetLawsForTests(ServerLevel level, Villages.Village v) {
        Identity.Rec r = Identity.rec(v.id());
        found(v.id(), r, level.getDayTime() / 24000L);
        Identity.dirty();
    }

    /** Tests: a law set outright. */
    public static void setForTests(UUID village, Law l, int value) {
        Identity.Rec r = Identity.rec(village);
        r.laws[l.ordinal()] = value;
        Identity.dirty();
    }

    /** Tests: a change put to whoever decides, now. */
    public static void proposeForTests(ServerLevel level, Villages.Village v, Law l, int value) {
        Identity.Rec r = Identity.rec(v.id());
        r.lawRefused.clear();
        propose(level, v, r, l, value, level.getDayTime() / 24000L);
    }

    /** Tests: the law-book this town would want now. */
    public static int[] desiredForTests(ServerLevel level, UUID village) {
        return desired(village, Identity.rec(village), level.getDayTime() / 24000L);
    }
}
