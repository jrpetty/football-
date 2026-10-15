package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.AssistantConfig;
import com.jrpetty.mcassistant.CameraCommands;
import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.Guard;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.BattleModel;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [war] A war's campaigns: the armies taking the field, from the muster to the march home.
 *
 * <p><b>Taking the field.</b> Once a day of a war (WarAndPeace.warDay), from its second day and with two days'
 * rest after a battle, the side that likes its chances takes the field: the aggressor if it reckons itself a
 * match for them, else the defender if it reckons itself the stronger by a fifth. The other side's Captain,
 * warned by its pickets, marches out to meet it if it reckons itself fit to (two thirds their strength) or has
 * no wall to stand behind; otherwise it holds its walls, and the campaign is a siege.
 *
 * <p><b>The muster.</b> In the afternoon (a war decided in the morning gives everybody, players too, half a day's
 * notice: the horn and a word in the chat of every player near either town, then again as it begins), the
 * army is raised (Armies.raise) and draws up on the square in its ranks facing the enemy; the Captain names
 * its tactics; folk come out to see it off; the horn, and it marches.
 *
 * <p><b>The march.</b> In column, two or three abreast, the banner at its head, along the road to its camp
 * behind its line on the field (Battlefields.choose), at a steady pace (the head waits for the stragglers),
 * with a drum now and then. The head is a reckoning, not a folk: it goes on whether anybody watches or not, and
 * the fighters keep their places behind it where their ground is awake; a fighter left behind where nobody can
 * see it is set down in its place when it is next about.
 *
 * <p><b>The camp.</b> Tents, the fire and the banner (Battlefields.pitch), sentries out, the rest round the fire.
 * An army that is there by the afternoon fights the same day; later, it camps the night and the battle is at
 * dawn, to the drums.
 *
 * <p><b>The battle</b> (Battles): in full in front of any player within view of the field, on paper otherwise,
 * by the same model. <b>The siege</b>: an aggressor that held the field and is after more than satisfaction
 * marches on the town (or comes straight to it if it held its walls): the bell rung there (Raids.alarm, from the
 * side it comes from), a camp before the gate, a ram of three logs (Battlefields.ram) if it brought the logs,
 * and at dawn the gate battered down and the defenders who are left fought before it. A town whose gate is
 * taken makes peace on the aggressor's terms there and then. Without a ram the town is starved out only if its
 * siege stores (WarStores) run out before the besiegers' patience; else they go home.
 *
 * <p><b>Home.</b> The armies march home, the wounded with them and the captives on leads; the supply back into
 * the stores, everybody back to their trades. A fighter far away where nobody is (its ground asleep) is called
 * home: its ground woken for a moment (ChunkLoad), it is set down at home.
 *
 * <p><b>What it costs.</b> Nothing at all with no campaign. With one, a second's look at it (the stage, the
 * column's head, whether anybody watches) and, in a battle, the conductor twice a second (Battles); every
 * fighter's own step is its own tick (Armies.hold). A campaign is kept with the world (Ledger "war.campaign" on
 * the town that took the field): a restart in the middle of a march carries on; in the middle of a battle, the
 * battle is reckoned on paper.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Campaigns {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Campaigns() {}

    /** Where a campaign has got to. */
    public enum Stage { NOTICE, MUSTER, MARCH, CAMP, BATTLE, AFTER, SIEGE_MARCH, SIEGE_CAMP, ASSAULT, HOME, DONE }

    /** Days' rest after a battle before either side takes the field again. */
    static final int REST_DAYS = 2;
    /** The column's pace, blocks a tick (two and a half a second: a steady march). */
    static final double PACE = 0.125;
    /** The muster's notice: the town is told in the morning and draws up in the afternoon, at this hour. */
    static final long MUSTER_HOUR = 6000L;
    /** At least this long between the word going round and the muster (a player's time to get there). */
    static final long NOTICE = 1200L;
    /** How long the army stands drawn up on the square before it marches. */
    static final long MUSTER_TICKS = 900L;
    /** Arrived by this hour, it fights today; later, it camps the night and fights at dawn. */
    static final long LATEST_BATTLE = 8000L;
    /** A battle at dawn begins at this hour (the sun just up). */
    static final long DAWN = 200L;
    /** How long a siege camp sits before the assault (by day). */
    static final long SIEGE_WAIT = 1200L;
    /** How long the march home may take before everybody is simply home. */
    static final long HOME_MOST = 3600L;
    /** How long a fighter's ground is woken to call it on. */
    static final long PULL_TICKS = 200L;

    /** A campaign: the two armies, where they are, and the ground. */
    public static final class Campaign {
        /** The side that took the field, and the other. */
        final UUID a, b;
        final Armies.Army[] armies = new Armies.Army[2];
        Stage stage = Stage.NOTICE;
        long stageAt, nextAt;
        Battlefields.Ground ground;
        /** The defender stays behind its walls: a siege from the start. */
        boolean holdWalls;
        /** The aggressor is after more than the field: a won field goes on to the town. */
        boolean siegeAim;
        /** Each army's road and how far along it its head has got. */
        final List<List<BlockPos>> ways = new ArrayList<>(List.of(new ArrayList<>(), new ArrayList<>()));
        final double[] along = new double[2];
        final Battlefields.Camp[] camps = new Battlefields.Camp[2];
        @Nullable List<BlockPos> ram;
        @Nullable BlockPos gate;
        Direction gateOut = Direction.NORTH;
        int gateHits;
        @Nullable Battles.Battle battle;
        @Nullable BattleModel.Result result;
        /** Which army (0 or 1) won the last fight, -1 none yet. */
        int won = -1;
        /** The last fight was at the gate (an assault), not on the field. */
        boolean atTheGate;
        String name = "";
        long day;
        final UUID ticketOwner;
        final List<long[]> pulls = new ArrayList<>();
        final int stagger;
        /** [war-polish] Staged for the pictures: the furthest of PHASES it has been set at (it waits there); -1 a campaign of its own. */
        int staged = -1;

        Campaign(UUID a, UUID b) {
            this.a = a;
            this.b = b;
            this.ticketOwner = UUID.nameUUIDFromBytes(("campaign/" + a + "/" + b).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            this.stagger = Math.floorMod(a.hashCode() ^ b.hashCode(), 20);
        }

        public UUID attacker() { return a; }
        public UUID defender() { return b; }
        public Stage stage() { return stage; }
        @Nullable public Armies.Army army(int i) { return armies[i]; }
        public Battlefields.Ground ground() { return ground; }
        @Nullable public Battles.Battle battle() { return battle; }
        public String name() { return name; }
        public int won() { return won; }

        Villages.Village town(int i) {
            return Villages.get(i == 0 ? a : b);
        }

        /** Where army i's head is now: along its road. */
        BlockPos head(int i) {
            return pointAlong(ways.get(i), along[i]);
        }
    }

    private static final Map<String, Campaign> ACTIVE = new ConcurrentHashMap<>();
    private static volatile boolean loaded;
    @Nullable private static Boolean watchForTests;
    /** Do the armies take the field at all (the diplomacy's own tests keep them home)? */
    private static volatile boolean field = true;
    private static volatile boolean hurry, hurryBattle;

    public static void resetForTests() {
        ACTIVE.clear();
        loaded = true;
        watchForTests = null;
        hurry = false;
        hurryBattle = false;
        field = true;
        Armies.resetForTests();
        Battles.resetForTests();
    }

    /** A new world (or the same one opened again): the campaigns read again from its books at the first tick. */
    public static void resetSession() {
        ACTIVE.clear();
        loaded = false;
        Armies.resetForTests();
    }

    /** Tests: every field taken as watched (true: in full), or as unwatched (false: on paper); null for the players as they are. */
    public static void watchForTests(@Nullable Boolean on) {
        watchForTests = on;
    }

    /** Tests of the war's diplomacy alone: the armies kept home (true again at the next reset). */
    public static void fieldForTests(boolean on) {
        field = on;
    }

    /** Tests: the waits cut short (the notice, the muster, the camp, the siege) and the march four times the pace. */
    public static void hurryForTests(boolean on) {
        hurry = on;
        hurryBattle = on;
    }

    /** Pictures: the campaign's waits cut short, but its battle fought at its own pace, to be photographed. */
    public static void hurryForPictures() {
        hurry = true;
        hurryBattle = false;
    }

    /** A battle's phase so long, or less for the tests. */
    /** [war-polish] Have the campaigns been read from the books yet (the first tick)? */
    static boolean loaded() {
        return loaded;
    }

    static long soonBattle(long ticks) {
        return hurryBattle ? Math.min(ticks, 60L) : ticks;
    }

    /** Are the waits cut short (the tests, the pictures)? */
    static boolean hurried() {
        return hurryBattle;
    }

    static long soon(long ticks) {
        return hurry ? Math.min(ticks, 60L) : ticks;
    }

    /** The chance in a hundred that one beaten down in battle does not get up (the config's battleDeaths). */
    static int deathPercent() {
        return Battles.deathsForTests != null ? Battles.deathsForTests : AssistantConfig.battleDeaths();
    }

    /** How many of each side's fighters are drawn in full (the config's battleDetail); the rest fight on paper alongside. */
    static int cap() {
        return AssistantConfig.battleDetail();
    }

    /** Is anybody watching this spot: a player (or a spectator) within view of it (the config's battleView)? */
    static boolean watched(ServerLevel level, @Nullable BlockPos at) {
        if (watchForTests != null) return watchForTests;
        if (at == null) return false;
        double r = AssistantConfig.battleView();
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - at.getX(), dz = p.getZ() - at.getZ();
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    /** The campaign between these two, or null. */
    @Nullable
    public static Campaign of(UUID x, UUID y) {
        return ACTIVE.get(Ledger.pair(x, y));
    }

    /** The campaign this town is in, or null. */
    @Nullable
    public static Campaign ofTown(UUID town) {
        for (Campaign c : ACTIVE.values()) if (c.a.equals(town) || c.b.equals(town)) return c;
        return null;
    }

    // ------------------------------------------------------------------ the war's day: does anybody take the field?

    /**
     * [WarAndPeace.warDay] Once a day of a war: whether a side takes the field today, and the campaign begun if
     * so (the muster this afternoon). Whether one began.
     */
    static boolean warDay(ServerLevel level, Villages.Village x, Villages.Village y, long day) {
        if (!WarAndPeace.on() || !field || of(x.id(), y.id()) != null || ofTown(x.id()) != null || ofTown(y.id()) != null) return false;
        long began = Math.max(Wars.since(x.id(), y.id()), 0);
        if (day - began < 1) return false;                                   // the day it was declared, nobody marches yet
        if (day - lastBattle(x.id(), y.id()) < REST_DAYS) return false;
        WarBooks.Book bx = WarBooks.book(x.id(), y.id()), by = WarBooks.book(y.id(), x.id());
        Villages.Village aggressor = by != null && by.aggressor ? y : x, defender = aggressor == x ? y : x;
        double odds = odds(level, aggressor.id(), defender.id(), day);
        Villages.Village takes = null;
        if (odds >= 0.9 || Armies.boldness(Envoys.temper(aggressor.id())) >= 2 && odds >= 0.7) takes = aggressor;
        else if (1.0 / Math.max(0.01, odds(level, defender.id(), aggressor.id(), day)) <= 1.0 / 1.2) takes = defender;
        if (takes == null) return false;
        Villages.Village other = takes == aggressor ? defender : aggressor;
        WarBooks.Book book = WarBooks.book(takes.id(), other.id());
        boolean aim = takes == aggressor && book != null && book.goal != WarAndPeace.Goal.DEFENCE;
        boolean walls = walled(other.id());
        boolean meet = !walls || odds(level, other.id(), takes.id(), day) >= 2.0 / 3.0;
        return begin(level, takes, other, day, !meet, aim) != null;
    }

    /** Our strength against theirs as we reckon it (WarAndPeace). */
    static double odds(ServerLevel level, UUID us, UUID them, long day) {
        return WarAndPeace.own(level, us).strength() / (double) Math.max(1, WarAndPeace.of(level, us, them, day).strength());
    }

    static boolean walled(UUID town) {
        return CastleWall.stands(town) || Villages.hasBuilt(town, "fortify");
    }

    /** The day of the last battle between these two (-100 if none). */
    static long lastBattle(UUID x, UUID y) {
        return WarBooks.num(Ledger.note(x, "wp.battleday/" + y), -100);
    }

    /**
     * A campaign begun: {@code x} takes the field against {@code y}; {@code holdWalls}, y stays behind its walls;
     * {@code siegeAim}, x is after the town. The word goes round and the muster is set for the afternoon.
     */
    @Nullable
    static Campaign begin(ServerLevel level, Villages.Village x, Villages.Village y, long day, boolean holdWalls, boolean siegeAim) {
        Campaign c = new Campaign(x.id(), y.id());
        c.day = day;
        c.holdWalls = holdWalls;
        c.siegeAim = siegeAim || holdWalls;
        Battlefields.Ground g = Battlefields.choose(level, x, y);
        if (g == null && !holdWalls) {                                         // all water between them: no battle on it
            noDryGround(x.id(), y.id(), day);
            return null;
        }
        c.ground = g != null ? g : Battlefields.blind(x, y);                   // a siege is fought at the gate, not on a field
        c.name = "the Battle of " + c.ground.name();
        long now = level.getGameTime(), tod = level.getDayTime() % 24000L;
        c.stage = Stage.NOTICE;
        c.stageAt = now;
        c.nextAt = now + soon(Math.max(NOTICE, MUSTER_HOUR - tod));
        ACTIVE.put(Ledger.pair(x.id(), y.id()), c);
        String xn = Villages.name(x.id()), yn = Villages.name(y.id());
        String what = holdWalls ? xn + " will march on " + yn + ", which means to hold its walls"
            : xn + " will take the field against " + yn + ", and " + yn + " will march out to meet it";
        Villages.tell(x.id(), day, "the council resolved to take the field against " + yn + ": the army musters on the square this afternoon");
        Villages.tell(y.id(), day, "our pickets brought word: " + xn + " is mustering its army; " + (holdWalls ? "we hold our walls" : "we march out to meet it"));
        WarBooks.course(x.id(), y.id(), day, "we resolved to take the field against " + yn);
        WarBooks.course(y.id(), x.id(), day, xn + " took the field against us; " + (holdWalls ? "we held our walls" : "we marched out to meet it"));
        announce(level, c, what + ". The armies muster this afternoon.", true);
        save(c);
        LOG.info("[MCA-WAR] campaign: {} against {} (holds walls {}, after the town {}), field {} at {}", xn, yn, holdWalls, c.siegeAim,
            c.ground.name(), c.ground.centre().toShortString());
        return c;
    }

    /** Word to every player near either town (or the field): a line in the chat, and the horn if {@code horn}. */
    static void announce(ServerLevel level, Campaign c, String line, boolean horn) {
        Component msg = Component.literal("[War] " + line).withStyle(ChatFormatting.GOLD);
        for (ServerPlayer p : level.players()) {
            boolean near = false;
            for (int i = 0; i < 2 && !near; i++) {
                Villages.Village v = c.town(i);
                if (v != null && p.blockPosition().closerThan(v.centre(), 240)) near = true;
            }
            if (!near && c.ground != null && p.blockPosition().closerThan(c.ground.centre(), 240)) near = true;
            if (!near) continue;
            p.sendSystemMessage(msg);
            if (horn) Armies.horn(level, p.blockPosition().offset(8, 2, 0), 1);
        }
    }

    // ------------------------------------------------------------------ the second

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!loaded) {
            loaded = true;
            Guard.run("campaigns load", Campaigns::load);
        }
        if (ACTIVE.isEmpty()) return;
        int tick = event.getServer().getTickCount();
        Guard.run("campaigns", () -> {
            for (Iterator<Campaign> it = ACTIVE.values().iterator(); it.hasNext(); ) {
                Campaign c = it.next();
                ServerLevel level = levelOf(event.getServer(), c);
                if (level == null) continue;
                if (c.battle != null && (tick + c.stagger) % Battles.EVERY == 0) c.battle.tick(level);
                if ((tick + c.stagger) % 20 != 0) continue;
                second(level, c);
            }
        });
    }

    @Nullable
    static ServerLevel levelOf(net.minecraft.server.MinecraftServer server, Campaign c) {
        Villages.Village v = Villages.get(c.a);
        return v == null ? null : server.getLevel(v.dim());
    }

    /** A campaign's second: its pulls let go, its stage seen to, and on to the next when it is time. */
    static void second(ServerLevel level, Campaign c) {
        long now = level.getGameTime();
        for (Iterator<long[]> it = c.pulls.iterator(); it.hasNext(); ) {
            long[] p = it.next();
            if (now < p[1]) continue;
            ChunkLoad.setLoaded(level, c.ticketOwner, BlockPos.of(p[0]), 0, false);
            it.remove();
        }
        if (c.staged >= 0) return;                                             // [war-polish] staged: it waits where it was set
        if (c.stage != Stage.DONE && c.stage != Stage.HOME && !Wars.atWar(c.a, c.b)) {
            // Peace made while the armies were out (a white flag, a player's word): everybody home.
            if (c.battle != null) c.battle.abandon(level);
            c.battle = null;
            goHome(level, c, "peace was made");
        }
        switch (c.stage) {
            case NOTICE -> { if (now >= c.nextAt) muster(level, c); }
            case MUSTER -> { if (now >= c.nextAt) march(level, c); else drawnUp(level, c); }
            case MARCH, SIEGE_MARCH -> marching(level, c);
            case CAMP, SIEGE_CAMP -> camping(level, c);
            case BATTLE, ASSAULT -> fighting(level, c);
            case AFTER -> { if (now >= c.nextAt) afterBattle(level, c); }
            case HOME -> homeward(level, c);
            case DONE -> { }
        }
        if (c.stage == Stage.DONE) {
            for (long[] p : c.pulls) ChunkLoad.setLoaded(level, c.ticketOwner, BlockPos.of(p[0]), 0, false);
            c.pulls.clear();
        } else if (now % 200 < 20) save(c);
    }

    static void stage(Campaign c, Stage s, long now, long until) {
        c.stage = s;
        c.stageAt = now;
        c.nextAt = until;
        if (s != Stage.DONE) {
            save(c);
            return;
        }
        ACTIVE.remove(Ledger.pair(c.a, c.b), c);
        Ledger.forget(c.a, "war.campaign");
    }

    // ------------------------------------------------------------------ the muster

    /** The armies raised and drawn up on their squares (the one holding its walls at its gate, later). */
    static void muster(ServerLevel level, Campaign c) {
        muster(level, c, cap());
    }

    /** As {@link #muster(ServerLevel, Campaign)}, at most {@code detail} a side drawn in full. */
    static void muster(ServerLevel level, Campaign c, int detail) {
        long now = level.getGameTime(), day = level.getDayTime() / 24000L;
        if (!lookAgain(level, c)) {
            noField(level, c);
            return;
        }
        int raised = 0;
        for (int i = 0; i < 2; i++) {
            Villages.Village v = c.town(i);
            if (v == null) continue;
            if (i == 1 && c.holdWalls) continue;                       // it raises its army when the enemy is at the gate
            Armies.Army army = Armies.raise(level, v, i == 0 ? c.b : c.a, false, i == 0 && c.siegeAim, detail);
            if (army == null) continue;
            c.armies[i] = army;
            Armies.enlist(army);
            raised++;
            choose(level, c, i);
            arm(level, army);
            Battles.array(level, army, squareFacing(v, c.town(1 - i)), 0.0);
            Armies.Fighter cap = army.officer(Armies.Rank.CAPTAIN);
            Villages.tell(v.id(), day, "the army mustered on the square: " + army.size() + " strong" + (cap != null ? ", " + cap.what() + " in command" : "")
                + "; " + Villages.name(v.id()) + " draws up in " + army.formation.words + " (" + army.formation.how + ") and " + army.tactic.words
                + "; its missiles " + army.missileWords() + (army.missiles(true) > 0 || army.ranged != com.jrpetty.mcassistant.village.BattleModel.Ranged.MIX
                    ? " (its Captain favours " + army.ranged.words + ")" : ""));           // [war-bows] "12 bows, 8 crossbows"
            WarBooks.course(v.id(), i == 0 ? c.b : c.a, day, "our army mustered, " + army.size() + " strong" + (cap != null ? " under " + cap.what() : "")
                + ", " + army.missileWords());
        }
        WarDress.dress(level, c);                                          // [war-polish] each side in a colour of its own, once
        if (c.armies[0] == null) {
            Villages.tell(c.a, day, "the muster came to nothing: nobody to march");
            stage(c, Stage.DONE, now, now);
            return;
        }
        announce(level, c, Villages.name(c.a) + (c.armies[1] != null ? " and " + Villages.name(c.b) + " are" : " is") + " mustering for war on "
            + (c.armies[1] != null ? "their squares" : "its square") + ". " + view(c), true);
        for (int i = 0; i < 2; i++) {
            if (c.armies[i] == null) continue;
            Villages.Village v = c.town(i);
            if (v != null) WarHorn.signal(level, c.armies[i], v.centre(), WarHorn.Signal.MUSTER);
        }
        stage(c, Stage.MUSTER, now, now + soon(MUSTER_TICKS));
    }

    /**
     * [field] A field chosen blind (its ground asleep when the war's council sat) looked for again at the muster, from
     * whatever ground is to hand now (players about), with the same chooser.
     */
    static boolean lookAgain(ServerLevel level, Campaign c) {
        Villages.Village x = c.town(0), y = c.town(1);
        if (c.ground.known() || c.holdWalls || x == null || y == null) return true;
        Battlefields.Ground g = Battlefields.choose(level, x, y);
        if (g == null) return false;                                           // nothing but water between them
        if (!g.known()) return true;
        c.ground = g;
        c.name = "the Battle of " + g.name();
        LOG.info("[MCA-WAR] {}: the field looked for again at the muster, {} at {}", Villages.name(c.a), g.name(), g.centre().toShortString());
        return true;
    }

    /**
     * No dry ground to fight on between these towns (every field round both is lake, river or sea): the Captains will
     * not take the field, so no battle is fought. Word of it in both towns' chronicles and war books, once in five days.
     */
    static void noDryGround(UUID x, UUID y, long day) {
        String key = "war.nodry/" + y;
        if (day - WarBooks.num(Ledger.note(x, key), -100) < 5) return;
        Ledger.note(x, key, Long.toString(day));
        String xn = Villages.name(x), yn = Villages.name(y);
        Villages.tell(x, day, "our Captains found no dry ground between us and " + yn + " to fight on, only water: the army stays home");
        Villages.tell(y, day, xn + " would take the field against us, but there is only water between us: no battle");
        WarBooks.course(x, y, day, "we found no dry ground to meet " + yn + " on, only water, and kept the army home");
        WarBooks.course(y, x, day, xn + " found no dry ground to meet us on and kept its army home");
        LOG.info("[MCA-WAR] {} against {}: no dry ground to fight on, only water: no battle", xn, yn);
    }

    /** A campaign called off before a blow is struck: no dry ground (the field turned out to be water). Everybody home. */
    static void noField(ServerLevel level, Campaign c) {
        long day = level.getDayTime() / 24000L;
        noDryGround(c.a, c.b, day);
        announce(level, c, "There is no dry ground between " + Villages.name(c.a) + " and " + Villages.name(c.b) + " to fight on, only water: the armies go home.", false);
        if (c.battle != null) c.battle.abandon(level);
        c.battle = null;
        boolean out = c.armies[0] != null || c.armies[1] != null;
        if (out) goHome(level, c, "no dry ground");
        else stage(c, Stage.DONE, level.getGameTime(), level.getGameTime());
    }

    /** The Captain's choice of tactics for army i, by the ground on its side, the odds, its temper and its kit. */
    static void choose(ServerLevel level, Campaign c, int i) {
        Armies.Army a = c.armies[i];
        if (a == null) return;
        Battlefields.Ground g = c.ground;
        long day = level.getDayTime() / 24000L;
        double odds = odds(level, a.town, a.enemy, day);
        boolean rise = (i == 0 ? g.riseA() : g.riseB()) != null, cover = (i == 0 ? g.coverA() : g.coverB()) != null;
        Random rng = new Random(seed(a.town, day));
        a.tactic = BattleModel.choose(a.boldness, a.cunning, odds, rise, cover, a.count(BattleModel.Role.RIDER), a.count(BattleModel.Role.ARCHER), a.size(), rng);
        a.formation = formation(level, c, i, odds, rise);
        Ledger.note(a.town, "war.prefers", a.formation.name());                // the armoury leans to it from now (WarWorks.wants)
    }

    /**
     * The Captain's way of drawing up (BattleModel.prefer): its heart (a Guardian's shield wall), its temper, its
     * battles behind it, the odds and the ground; and if the scouts have seen the other army drawn up before (a
     * battle between them already), what beats that.
     */
    static BattleModel.Formation formation(ServerLevel level, Campaign c, int i, double odds, boolean rise) {
        Armies.Army a = c.armies[i];
        Armies.Fighter cap = a.officer(Armies.Rank.CAPTAIN);
        boolean guardian = cap != null && level.getEntity(cap.id) instanceof VillageFolkEntity e && Values.top(e) == Values.Value.SAFETY;
        int battles = cap == null ? 0 : Armies.battles(a.town, cap.id);
        BattleModel.Formation seen = null;
        String last = Ledger.note(a.town, "war.formation/" + a.enemy);
        if (last != null && !last.isEmpty()) {
            try { seen = BattleModel.Formation.valueOf(last); } catch (IllegalArgumentException ignored) { seen = null; }
        }
        return BattleModel.prefer(guardian, a.boldness, a.cunning, battles, odds, a.count(BattleModel.Role.ARCHER), a.count(BattleModel.Role.RIDER),
            a.size(), rise, seen);
    }

    /**
     * [Battles] A surrender taken at the parley: peace there and then on the stronger's terms (the whole of its goal
     * from a beaten defender; a beaten aggressor withdraws its demands and pays for the trouble), and the mercy
     * remembered: every town thinks the better of the victor, and the spared remember it.
     */
    static void yieldedAtParley(ServerLevel level, Campaign c, UUID strong, UUID weak, String field) {
        long day = level.getDayTime() / 24000L;
        WarAndPeace.Terms t = WarAndPeace.terms(level, c.a, c.b);
        boolean defenderYields = weak.equals(t.defender());
        WarBooks.Book ab = WarBooks.book(t.aggressor(), t.defender());
        int coins = defenderYields && ab != null && (t.goal() == WarAndPeace.Goal.TRIBUTE || t.goal() == WarAndPeace.Goal.REVENGE) ? ab.amount : 0;
        int reparations = defenderYields ? 0 : Math.max(t.reparations(), Math.min(Ledger.coins(weak) / 5, 10));
        WarAndPeace.Terms terms = new WarAndPeace.Terms(t.aggressor(), t.defender(), t.goal(), t.balance(), defenderYields ? 2 : 0, coins, reparations, t.words());
        Villages.tell(strong, day, "at the parley on " + field + ", " + Villages.name(weak) + " yielded and we took its surrender: not a blow struck");
        Villages.tell(weak, day, "at the parley on " + field + ", our Captain yielded to " + Villages.name(strong) + ", and it was taken: we were spared");
        WarAndPeace.makePeace(level, c.a, c.b, day, terms, "at the parley on " + field, null);
        for (Villages.Village o : Villages.every()) if (!o.id().equals(strong) && !o.id().equals(weak)) Ledger.relate(o.id(), strong, 2);
        Bonds.remember(weak, strong, day, 3, "they took our surrender on " + field + " and spared us");
    }

    /** [Battles] A surrender refused at the parley: every town thinks the worse of the victor, and the refused hold it against it. */
    static void refusedAtParley(ServerLevel level, Campaign c, UUID strong, UUID weak) {
        long day = level.getDayTime() / 24000L;
        for (Villages.Village o : Villages.every()) if (!o.id().equals(strong) && !o.id().equals(weak)) Ledger.relate(o.id(), strong, -2);
        WarBooks.wrong(weak, strong, day, WarAndPeace.Wrong.INSULT, "our surrender refused on " + c.ground.name());
        Villages.tell(weak, day, "our Captain offered to yield on " + c.ground.name() + "; " + Villages.name(strong) + " would not have it");
        Villages.tell(strong, day, Villages.name(weak) + " offered to yield on " + c.ground.name() + "; we would not have it");
    }

    static long seed(UUID town, long day) {
        return town.getMostSignificantBits() * 0x9E3779B97F4A7C15L + day * 0xBF58476D1CE4E5B9L + 7L;
    }

    /** Weapons in hand for the field. */
    static void arm(ServerLevel level, Armies.Army a) {
        for (Armies.Fighter f : a.fighters) if (level.getEntity(f.id) instanceof VillageFolkEntity e) Armies.arm(e, f);
    }

    /** The muster's spot: the square, facing the enemy town. */
    static double[] squareFacing(Villages.Village v, @Nullable Villages.Village enemy) {
        double fx = 1, fz = 0;
        if (enemy != null) {
            double dx = enemy.centre().getX() - v.centre().getX(), dz = enemy.centre().getZ() - v.centre().getZ(), d = Math.max(1, Math.sqrt(dx * dx + dz * dz));
            fx = dx / d;
            fz = dz / d;
        }
        return new double[]{ v.centre().getX() + 0.5 + fx * 4, v.centre().getZ() + 0.5 + fz * 4, fx, fz };
    }

    /** Drawn up on the square: the Captain's words once, and folk come out to see them off. */
    static void drawnUp(ServerLevel level, Campaign c) {
        long since = level.getGameTime() - c.stageAt;
        if (since < soon(200L) || since >= soon(200L) + 20) return;               // ten seconds in, once
        for (int i = 0; i < 2; i++) {
            Armies.Army a = c.armies[i];
            if (a == null) continue;
            Armies.Fighter cap = a.officer(Armies.Rank.CAPTAIN);
            if (cap != null && level.getEntity(cap.id) instanceof VillageFolkEntity e) {
                Armies.shout(e, Battles.muster(a, c));
            }
            Villages.Village v = c.town(i);
            if (v != null) seeOff(level, v, a);
        }
    }

    /** Folk out on the square to see the army off: a wave and a word from a few. */
    static void seeOff(ServerLevel level, Villages.Village v, Armies.Army a) {
        int said = 0;
        for (AssistantEntity e : Villages.folkOf(v.id())) {
            if (said >= 6) break;
            if (!(e instanceof VillageFolkEntity f) || Armies.inLine(f) || !f.isAlive() || f.distanceToSqr(v.centre().getX(), f.getY(), v.centre().getZ()) > 40 * 40) continue;
            f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            Armies.shout(f, FolkTalk.pick(f.getRandom(), "Come home safe!", "Give them what for!", "For " + Villages.name(v.id()) + "!",
                "Mind yourselves out there!", "We'll keep the fires lit for you!"));
            said++;
        }
    }

    /** "Watch from the rise at x, z", for the players. */
    static String view(Campaign c) {
        BlockPos v = c.ground.view();
        BlockPos f = c.ground.centre();
        return "The field: " + c.ground.name() + " (" + f.getX() + ", " + f.getZ() + ")" + (v != null ? "; watch from the rise at " + v.getX() + ", " + v.getZ() : "") + ".";
    }

    // ------------------------------------------------------------------ the march

    /** The armies set off for their camps: the horn, the column formed, the road. */
    static void march(ServerLevel level, Campaign c) {
        long now = level.getGameTime();
        for (int i = 0; i < 2; i++) {
            Armies.Army a = c.armies[i];
            Villages.Village v = c.town(i);
            if (a == null || v == null) continue;
            BlockPos to = c.holdWalls ? siegeCamp(level, c) : campSpot(c, i);
            route(c, i, v.centre(), to);
            WarHorn.signal(level, a, v.centre(), WarHorn.Signal.MARCH);
        }
        stage(c, c.holdWalls ? Stage.SIEGE_MARCH : Stage.MARCH, now, now);
        announce(level, c, Villages.name(c.a) + "'s army is on the march" + (c.armies[1] != null ? ", and " + Villages.name(c.b) + "'s" : "")
            + ". " + (c.holdWalls ? Villages.name(c.b) + " has shut its gates." : view(c)), false);
    }

    /** Army i's camp: behind its line, a dozen and more blocks back from where it draws up. */
    static BlockPos campSpot(Campaign c, int i) {
        double back = Battlefields.GAP / 2.0 + 14.0;
        return c.ground.at(i == 0 ? -back : back, 0);
    }

    /** Its road from here to there: out by the avenue, along the road, in. */
    static void route(Campaign c, int i, BlockPos from, BlockPos to) {
        List<BlockPos> way = new ArrayList<>();
        way.add(from);
        BlockPos exit = Roads.exit(from, to);
        if (exit.distSqr(from) > 16) way.add(exit);
        way.add(to);
        c.ways.set(i, way);
        c.along[i] = 0;
    }

    static double length(List<BlockPos> way) {
        double n = 0;
        for (int k = 1; k < way.size(); k++) n += Math.sqrt(flat(way.get(k - 1), way.get(k)));
        return n;
    }

    static double flat(BlockPos p, BlockPos q) {
        double dx = p.getX() - q.getX(), dz = p.getZ() - q.getZ();
        return dx * dx + dz * dz;
    }

    /** The point so far along the way (and the way's last point past its end). */
    static BlockPos pointAlong(List<BlockPos> way, double d) {
        if (way.isEmpty()) return BlockPos.ZERO;
        if (d <= 0) return way.get(0);
        for (int k = 1; k < way.size(); k++) {
            BlockPos p = way.get(k - 1), q = way.get(k);
            double seg = Math.sqrt(flat(p, q));
            if (d <= seg && seg > 0) {
                double t = d / seg;
                return BlockPos.containing(p.getX() + (q.getX() - p.getX()) * t, p.getY(), p.getZ() + (q.getZ() - p.getZ()) * t);
            }
            d -= seg;
        }
        return way.get(way.size() - 1);
    }

    /** The way's heading at so far along it, as a unit vector. */
    static double[] heading(List<BlockPos> way, double d) {
        BlockPos p = pointAlong(way, Math.max(0, d - 2)), q = pointAlong(way, d + 2);
        double dx = q.getX() - p.getX(), dz = q.getZ() - p.getZ(), n = Math.sqrt(dx * dx + dz * dz);
        if (n < 0.01) {
            BlockPos s = way.get(0), e = way.get(way.size() - 1);
            dx = e.getX() - s.getX();
            dz = e.getZ() - s.getZ();
            n = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
        }
        return new double[]{ dx / n, dz / n };
    }

    /**
     * A second of the march: each column's head on along its road (watched, it waits for its stragglers), every
     * fighter given its place in the column behind it; a drum now and then; at the end, the camp.
     */
    static void marching(ServerLevel level, Campaign c) {
        long now = level.getGameTime();
        boolean all = true;
        for (int i = 0; i < 2; i++) {
            Armies.Army a = c.armies[i];
            if (a == null || c.ways.get(i).isEmpty()) continue;
            double len = length(c.ways.get(i));
            if (c.along[i] < len) {
                all = false;
                boolean seen = watched(level, c.head(i));
                if (!seen || closedUp(level, a)) c.along[i] = Math.min(len, c.along[i] + PACE * 20 * (hurry ? 4 : 1));
                if (seen && (now / 20) % 3 == 0) Armies.drum(level, c.head(i), (now / 20) % 6 == 0);
                if (seen && pull(level, c, a, c.head(i))) LOG.debug("[MCA-WAR] called up the stragglers of {}", Villages.name(a.town));
            }
            column(level, c, i);
        }
        if (!all) return;
        boolean siege = c.stage == Stage.SIEGE_MARCH;
        for (int i = 0; i < 2; i++) {
            Armies.Army a = c.armies[i];
            if (a == null) continue;
            if (siege && i == 1) continue;
            double[] h = heading(c.ways.get(i), length(c.ways.get(i)));
            BlockPos at = c.ways.get(i).get(c.ways.get(i).size() - 1);
            double fx = siege ? c.gateOut.getOpposite().getStepX() : (i == 0 ? c.ground.ax() : -c.ground.ax());
            double fz = siege ? c.gateOut.getOpposite().getStepZ() : (i == 0 ? c.ground.az() : -c.ground.az());
            if (Math.abs(fx) + Math.abs(fz) < 0.01) { fx = h[0]; fz = h[1]; }
            ItemStack banner = bannerOf(level, a);
            c.camps[i] = watched(level, at) ? Battlefields.pitch(level, a, at, fx, fz, banner) : null;
            if (c.camps[i] != null && c.camps[i].banner != null) dropBanner(level, a);
            Battles.camp(level, a, at, fx, fz, c.camps[i]);
        }
        long tod = level.getDayTime() % 24000L;
        long until = tod < LATEST_BATTLE ? now + soon(siege ? SIEGE_WAIT : 1200L) : now + soon(24000L - tod + DAWN);
        if (siege) {
            Villages.Village v = c.town(1);
            if (v != null) {
                Raids.alarm(level, v, "the army of " + Villages.name(c.a) + " is at the gate", c.gateOut);
                standDown(level, c, 1);                                            // the beaten field army home, then all who can fight at the gate
                c.armies[1] = defenders(level, c, v);
                WarDress.dress(level, c);                                      // [war-polish] both sides in their colours for the assault
            }
            Armies.Army at = c.armies[0];
            c.ram = c.gate == null ? null : Battlefields.ram(level, at, c.gate, c.gateOut);
            if (c.ram != null) Villages.tell(c.a, level.getDayTime() / 24000L, "our army built a ram of logs before " + Villages.name(c.b) + "'s gate");
        }
        stage(c, siege ? Stage.SIEGE_CAMP : Stage.CAMP, now, until);
        String when = until - now > 6000 ? "at dawn" : "within the hour";
        announce(level, c, (siege ? Villages.name(c.a) + "'s army is camped before the gate of " + Villages.name(c.b)
            : "The armies are camped on " + c.ground.name()) + "; battle " + when + ". " + (siege ? "" : view(c)), false);
    }

    /** Are the column's fighters (those about) in their places, near enough to march on? */
    static boolean closedUp(ServerLevel level, Armies.Army a) {
        int seen = 0, behind = 0;
        for (Armies.Fighter f : a.fighters) {
            if (f.down() || !(level.getEntity(f.id) instanceof VillageFolkEntity e)) continue;
            seen++;
            double dx = e.getX() - f.tx, dz = e.getZ() - f.tz;
            if (dx * dx + dz * dz > 10 * 10) behind++;
        }
        return seen == 0 || behind * 3 <= seen;
    }

    /** Every fighter of army i its place in the column: the banner and the Captain at the head, two or three abreast. */
    static void column(ServerLevel level, Campaign c, int i) {
        Armies.Army a = c.armies[i];
        List<BlockPos> way = c.ways.get(i);
        if (a == null || way.isEmpty()) return;
        List<Armies.Fighter> order = Battles.columnOrder(a);
        order.removeIf(f -> f.order == Armies.Order.CAPTIVE);                // the taken walk with those who took them
        Armies.Army other = c.armies[1 - i];
        if (other != null) for (Armies.Fighter f : other.fighters) if (f.order == Armies.Order.CAPTIVE && !f.dead) order.add(f);
        int abreast = order.size() > 18 ? 3 : 2;
        for (int k = 0; k < order.size(); k++) {
            Armies.Fighter f = order.get(k);
            int row = k / abreast, col = k % abreast;
            double back = c.along[i] - row * 2.0;
            BlockPos p = pointAlong(way, back);
            double[] h = heading(way, Math.max(0, back));
            double across = (col - (abreast - 1) / 2.0) * 1.6;
            f.speed = 0.7;
            if (f.order == Armies.Order.YIELDED) { f.order = Armies.Order.PLACE; f.tended = false; }   // the wounded walk with the column
            f.place(level, p.getX() + 0.5 - h[1] * across, p.getZ() + 0.5 + h[0] * across, Armies.yawTo(h[0], h[1]));
        }
    }

    /** The banner its bearer carries (a copy of it), or null. */
    @Nullable
    static ItemStack bannerOf(ServerLevel level, Armies.Army a) {
        if (a.bearer == null || !(level.getEntity(a.bearer) instanceof VillageFolkEntity e)) return null;
        ItemStack held = e.getMainHandItem();
        return held.getItem() instanceof BannerItem ? held.copy() : null;
    }

    /** The bearer's banner planted in the camp: out of its hand. */
    static void dropBanner(ServerLevel level, Armies.Army a) {
        if (a.bearer != null && level.getEntity(a.bearer) instanceof VillageFolkEntity e && e.getMainHandItem().getItem() instanceof BannerItem) {
            e.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    /** The planted banner back in its bearer's hand (or the supply), the camp struck. */
    static void strike(ServerLevel level, Campaign c, int i) {
        Armies.Army a = c.armies[i];
        if (a == null) return;
        for (Armies.Fighter f : a.fighters) f.sit = false;
        ItemStack banner = Battlefields.strike(level, a, c.camps[i]);
        c.camps[i] = null;
        if (banner.isEmpty()) return;
        if (a.bearer != null && level.getEntity(a.bearer) instanceof VillageFolkEntity e && e.getMainHandItem().isEmpty()) {
            e.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, banner);
        } else {
            Villages.Village v = c.town(i);
            if (v != null) TownWork.give(level, v, Arms.unlend(banner));
        }
    }

    // ------------------------------------------------------------------ the camp

    /** A second in camp: the sentries' beat (Battles.camp gives the places); at the hour, the drums and the lines. */
    static void camping(ServerLevel level, Campaign c) {
        long now = level.getGameTime();
        for (int i = 0; i < 2; i++) if (c.armies[i] != null && (now / 20) % 15 == 0) Battles.sentries(level, c.armies[i], now);
        if (now < c.nextAt) return;
        for (int i = 0; i < 2; i++) strike(level, c, i);
        if (c.stage == Stage.SIEGE_CAMP) {
            assault(level, c);
            return;
        }
        if (c.armies[1] == null) {                                             // nobody came out to meet it
            c.won = 0;
            onTo(level, c);
            return;
        }
        if (!lookAgain(level, c) || Battlefields.wet(level, c.ground)) {      // the last look before a blow: never on water
            noField(level, c);
            return;
        }
        c.battle = Battles.open(level, c, c.ground.centre(), c.ground.ax(), c.ground.az(), false);
        if (c.battle.detailed() && c.ground.known()) Battlefields.clearBrush(level, c.ground);     // [field] the lines' ground trampled clear
        stage(c, Stage.BATTLE, now, now);
    }

    /** The siege's assault: the ram at the gate (or, with none, the blockade weighed), then the fight at the gate. */
    static void assault(ServerLevel level, Campaign c) {
        long now = level.getGameTime();
        Armies.Army at = c.armies[0], def = c.armies[1];
        if (c.ram == null && c.gate != null) {
            Random rng = new Random(seed(c.a, c.day + 99));
            boolean falls = BattleModel.gateFalls(at.power(), def == null ? 0 : def.power(), true, false, (int) WarStores.siegeDays(c.b), 3, rng);
            if (falls) {
                Villages.tell(c.b, level.getDayTime() / 24000L, "the siege stores ran out: the town opened its gate to " + Villages.name(c.a));
                c.won = 0;
                taken(level, c, "starved out");
            } else {
                Villages.tell(c.a, level.getDayTime() / 24000L, "with no ram and " + Villages.name(c.b) + "'s stores holding out, our army gave up the siege");
                c.won = 1;
                goHome(level, c, "the siege was given up");
            }
            return;
        }
        c.gateHits = 0;
        BlockPos front = c.gate == null ? c.head(0) : c.gate.relative(c.gateOut, Battlefields.GAP / 2 + 2);   // the defenders just before their gate
        c.battle = Battles.open(level, c, front, -c.gateOut.getStepX(), -c.gateOut.getStepZ(), true);
        stage(c, Stage.ASSAULT, now, now);
    }

    /** The defenders at their own gate: the army raised there and then, everybody who can fight, no home guard. */
    @Nullable
    static Armies.Army defenders(ServerLevel level, Campaign c, Villages.Village v) {
        Armies.Army a = Armies.raise(level, v, c.a, true, false, cap());
        if (a == null) return null;
        a.tactic = BattleModel.Tactic.SHIELD_WALL;
        Armies.enlist(a);
        arm(level, a);
        BlockPos inside = c.gate == null ? v.centre() : c.gate.relative(c.gateOut.getOpposite(), 4);
        Battles.array(level, a, new double[]{ inside.getX() + 0.5, inside.getZ() + 0.5, c.gateOut.getStepX(), c.gateOut.getStepZ() }, 0.0);
        return a;
    }

    /** Where the besiegers camp: out from the gate that faces them (CastleWall's, else the watch's), else the town's edge. */
    static BlockPos siegeCamp(ServerLevel level, Campaign c) {
        Villages.Village v = c.town(1), from = c.town(0);
        if (v == null || from == null) return c.ground.centre();
        BlockPos best = null;
        Direction out = Direction.NORTH;
        double bd = Double.MAX_VALUE;
        for (CastleWall.Gate g : CastleWall.gates(level, v.id())) {
            if (!g.stands()) continue;
            double d = g.gateway().distSqr(from.centre());
            if (d < bd) { bd = d; best = g.gateway(); out = g.out(); }
        }
        if (best == null) {
            for (Watch.Gate g : Watch.gates(level, v.id())) {
                if (g.doors().isEmpty()) continue;
                double d = g.doors().get(0).distSqr(from.centre());
                if (d < bd) { bd = d; best = g.doors().get(0); out = g.out(); }
            }
        }
        if (best == null) {
            best = Roads.exit(v.centre(), from.centre());
            double dx = from.centre().getX() - v.centre().getX(), dz = from.centre().getZ() - v.centre().getZ();
            out = Direction.getNearest(dx, 0, dz);
            c.gate = null;
        } else c.gate = best;
        c.gateOut = out;
        return best.relative(out, 28);
    }

    // ------------------------------------------------------------------ the battle

    /** A second of the battle: the conductor works twice a second (Battles.Battle.tick); here, only whether it is over. */
    static void fighting(ServerLevel level, Campaign c) {
        Battles.Battle b = c.battle;
        if (b == null) {
            stage(c, Stage.AFTER, level.getGameTime(), level.getGameTime());
            return;
        }
        if (!b.over()) return;
        c.result = b.result();
        c.won = b.winner();
        c.atTheGate = b.assault;
        if (b.withdrew) c.holdWalls = true;
        for (int i = 0; i < 2; i++) {
            Armies.Army a = c.armies[i];
            if (a != null && !b.settled) Ledger.note(a.enemy, "war.formation/" + a.town, a.formation.name());   // how it drew up, seen
        }
        c.battle = null;
        WarDress.undress(level, c);                                            // [war-polish] the battle dress off, once
        Battles.record(level, c, b);
        stage(c, Stage.AFTER, level.getGameTime(), level.getGameTime() + soon(400L));
    }

    /** After the battle: on to the town, or home. */
    static void afterBattle(ServerLevel level, Campaign c) {
        if (c.atTheGate) {
            // The fight at the gate: won, the town is taken; lost, the besiegers go home.
            if (c.won == 0) taken(level, c, "its gate taken");
            else goHome(level, c, "the assault was thrown back");
            return;
        }
        onTo(level, c);
    }

    /** The field won by the aggressor that is after the town: on to the town; else everybody home. */
    static void onTo(ServerLevel level, Campaign c) {
        long now = level.getGameTime();
        if (c.won == 0 && c.siegeAim && Wars.atWar(c.a, c.b) && c.armies[0] != null && c.armies[0].standing() > 0) {
            Armies.Army beaten = c.armies[1];
            if (beaten != null) {
                Villages.Village v = c.town(1);
                if (v != null) sendHome(level, c, 1, v.centre());
            }
            BlockPos to = siegeCamp(level, c);
            route(c, 0, c.head(0), to);
            stage(c, Stage.SIEGE_MARCH, now, now);
            announce(level, c, Villages.name(c.a) + "'s army marches on " + Villages.name(c.b) + ".", true);
            return;
        }
        goHome(level, c, c.won < 0 ? "neither side would give way" : "the field was decided");
    }

    /** A town taken: peace on the aggressor's whole terms, there and then; the bell stops. */
    static void taken(ServerLevel level, Campaign c, String how) {
        long day = level.getDayTime() / 24000L;
        if (c.gate != null) Battles.breakGate(level, c);
        WarAndPeace.Terms t = WarAndPeace.terms(level, c.a, c.b);
        WarAndPeace.Terms whole = new WarAndPeace.Terms(c.a, c.b, t.goal(), Math.max(1.5, t.balance()), 2, t.coins(), 0, t.words());
        bump(c.a, c.b, 2);
        Villages.tell(c.b, day, Villages.name(c.a) + " took the town, " + how + ", and the elder made peace on its terms");
        Villages.tell(c.a, day, "our army took " + Villages.name(c.b) + ", " + how);
        WarAndPeace.makePeace(level, c.a, c.b, day, whole, "with " + Villages.name(c.b) + " taken, " + how, null);
        // A great town, prickly or shrewd, takes a town whose gate it broke for its vassal, whatever it went to war for.
        Envoys.Temper t2 = Envoys.temper(c.a);
        if (t.goal() != WarAndPeace.Goal.SUBJUGATE && t.goal() != WarAndPeace.Goal.INDEPENDENCE && WarAndPeace.great(c.a) && !Vassals.isVassal(c.b)
            && (t2 == Envoys.Temper.PRICKLY || t2 == Envoys.Temper.SHREWD)) Vassals.make(level, c.a, c.b, day, "its gate taken");
        Villages.Village v = c.town(1);
        if (v != null) Raids.allClear(level, v);
        goHome(level, c, "the town was taken");
    }

    /** The war's score between them (the battles each has won, the other's lost): "wp.score/". */
    public static int score(UUID us, UUID them) {
        return (int) WarBooks.num(Ledger.note(us, "wp.score/" + them), 0);
    }

    static void bump(UUID winner, UUID loser, int by) {
        Ledger.note(winner, "wp.score/" + loser, Integer.toString(score(winner, loser) + by));
        Ledger.note(loser, "wp.score/" + winner, Integer.toString(score(loser, winner) - by));
    }

    // ------------------------------------------------------------------ home

    /** Everybody home: the camps struck, the ram taken apart, the bell stopped, the columns on the road home. */
    static void goHome(ServerLevel level, Campaign c, String why) {
        long now = level.getGameTime();
        for (int i = 0; i < 2; i++) {
            strike(level, c, i);
            Villages.Village v = c.town(i);
            if (c.armies[i] != null && v != null) sendHome(level, c, i, v.centre());
        }
        if (c.armies[0] != null) Battlefields.unram(level, c.armies[0], c.ram);
        c.ram = null;
        Villages.Village d = c.town(1);
        if (d != null && c.holdWalls || d != null && c.stage == Stage.SIEGE_CAMP || d != null && c.stage == Stage.ASSAULT) Raids.allClear(level, d);
        LOG.info("[MCA-WAR] campaign of {} against {}: home ({})", Villages.name(c.a), Villages.name(c.b), why);
        stage(c, Stage.HOME, now, now + HOME_MOST);
    }

    /** Army i on the road home from wherever its head is. */
    static void sendHome(ServerLevel level, Campaign c, int i, BlockPos home) {
        Armies.Army a = c.armies[i];
        if (a == null) return;
        BlockPos from = c.ways.get(i).isEmpty() ? home : c.head(i);
        List<BlockPos> way = new ArrayList<>();
        way.add(from);
        way.add(Roads.exit(home, from));
        way.add(home);
        c.ways.set(i, way);
        c.along[i] = 0;
    }

    /** A second of the march home; once home (or past the time), the army stood down. */
    static void homeward(ServerLevel level, Campaign c) {
        long now = level.getGameTime();
        boolean home = true;
        for (int i = 0; i < 2; i++) {
            Armies.Army a = c.armies[i];
            if (a == null || c.ways.get(i).isEmpty()) continue;
            double len = length(c.ways.get(i));
            if (c.along[i] < len) {
                home = false;
                boolean seen = watched(level, c.head(i));
                if (!seen || closedUp(level, a)) c.along[i] = Math.min(len, c.along[i] + PACE * 20 * (hurry ? 4 : 1));
            } else if (!allHome(level, c, a)) {
                home = false;
            }
            column(level, c, i);
        }
        if (!home && now < c.nextAt) return;
        for (int i = 0; i < 2; i++) if (c.armies[i] != null) standDown(level, c, i);
        stage(c, Stage.DONE, now, now);
    }

    /** Is everybody of this army home (or nowhere to be found, its ground asleep: then called home)? */
    static boolean allHome(ServerLevel level, Campaign c, Armies.Army a) {
        Villages.Village v = Villages.get(a.town);
        if (v == null) return true;
        boolean all = true;
        for (Armies.Fighter f : a.fighters) {
            if (f.order == Armies.Order.CAPTIVE) continue;
            if (level.getEntity(f.id) instanceof VillageFolkEntity e) {
                if (e.distanceToSqr(v.centre().getX(), e.getY(), v.centre().getZ()) > 40 * 40) all = false;
            } else if (f.seenAt != null && f.seenAt.distSqr(v.centre()) > 64 * 64) {
                pullAt(level, c, f.seenAt);
                all = false;
            }
        }
        return all;
    }

    /** Stood down: the fighters back to their lives, the captives handed to the barracks, the supply back in the stores. */
    static void standDown(ServerLevel level, Campaign c, int i) {
        Armies.Army a = c.armies[i];
        if (a == null) return;
        Battles.homecoming(level, c, a);
        Armies.unload(level, a);
        Armies.discharge(level, a);
        c.armies[i] = null;
    }

    // ------------------------------------------------------------------ calling the stragglers up

    /**
     * The army's fighters that are nowhere about (their ground asleep since nobody was near) called up to where
     * the army is watched: their ground woken for a few seconds (a ticket of this campaign's), and once they stir
     * their own step sets them down in their places. Whether any were called.
     */
    static boolean pull(ServerLevel level, Campaign c, Armies.Army a, BlockPos near) {
        boolean any = false;
        for (Armies.Fighter f : a.fighters) {
            if (f.dead || f.seenAt == null || level.getEntity(f.id) != null) continue;
            if (f.seenAt.distSqr(near) < 96 * 96) continue;
            any |= pullAt(level, c, f.seenAt);
        }
        return any;
    }

    static boolean pullAt(ServerLevel level, Campaign c, BlockPos at) {
        long key = new BlockPos(at.getX() >> 4 << 4, 0, at.getZ() >> 4 << 4).asLong();
        for (long[] p : c.pulls) if (p[0] == key) return false;
        if (c.pulls.size() >= 8) return false;
        ChunkLoad.setLoaded(level, c.ticketOwner, BlockPos.of(key), 0, true);
        c.pulls.add(new long[]{ key, level.getGameTime() + PULL_TICKS });
        return true;
    }

    // ------------------------------------------------------------------ a player in the ranks

    /**
     * A player joins the town's army in the field: a citizen as a volunteer, anybody else as a sword for hire, paid
     * {@code hire} coins out of the town's treasury if it has them. It fights under the town's Captain: the enemy's
     * fighters take it for one of the enemy, and its blows beat them down (they yield) as their own do. What the
     * Captain says to it.
     */
    public static String join(ServerLevel level, Villages.Village v, ServerPlayer p, boolean citizen, int hire) {
        Campaign c = ofTown(v.id());
        String n = Villages.name(v.id());
        if (c == null) return n + " has no army in the field.";
        UUID side = Armies.sideOf(p);
        if (side != null) return "You already fight for " + Villages.name(side) + ".";
        UUID enemy = c.a.equals(v.id()) ? c.b : c.a;
        if (Ledger.citizen(enemy, p.getUUID())) return "You're a citizen of " + Villages.name(enemy) + ". You'll not fight against your own.";
        long day = level.getDayTime() / 24000L;
        if (!citizen) {
            int paid = QuestRewards.fromTreasury(level, v.id(), hire);
            if (paid <= 0) return n + " cannot pay a sword for hire just now.";
            Dealings.giveCoins(p, paid);
            Villages.tell(v.id(), day, p.getName().getString() + " was hired to fight for the town against " + Villages.name(enemy) + ", for " + hire + " coins");
        } else {
            Villages.tell(v.id(), day, p.getName().getString() + " volunteered to fight for the town against " + Villages.name(enemy));
        }
        Armies.volunteer(p, v.id());
        WarBooks.course(v.id(), enemy, day, p.getName().getString() + (citizen ? " volunteered for our army" : " fought for us for hire"));
        String issued = Armies.issueTo(level, v, p);                          // [war-bows] a bow or a crossbow from the armoury
        String kit = issued == null ? "" : " The armoury gives you " + issued + ".";
        return (citizen ? "Welcome to the ranks. Stand with the line, keep your place, and mind the banner."
            : "There's your pay, out of the treasury. Stand with the line, and fight well.") + kit;
    }

    // ------------------------------------------------------------------ the books (kept with the world)

    /** The campaign written into the books of the town that took the field (Ledger "war.campaign"). */
    static void save(Campaign c) {
        StringBuilder s = new StringBuilder();
        s.append(c.b).append(';').append(c.stage.ordinal()).append(';').append(c.holdWalls ? 1 : 0).append(';').append(c.siegeAim ? 1 : 0)
            .append(';').append(c.won).append(';').append(c.day).append(';');
        Battlefields.Ground g = c.ground;
        s.append(g.centre().asLong()).append(';').append(String.format(Locale.ROOT, "%.4f,%.4f", g.ax(), g.az())).append(';').append(WarBooks.clean(g.name())).append(';');
        for (int i = 0; i < 2; i++) {
            Armies.Army a = c.armies[i];
            if (a == null) { s.append("-;"); continue; }
            List<String> fs = new ArrayList<>();
            for (Armies.Fighter f : a.fighters) fs.add(f.encode());
            s.append(a.tactic.ordinal()).append(':').append(a.formation.ordinal()).append('/').append(a.morale).append('/').append(a.behind).append('/').append(a.behindPower).append('/')
                .append(a.supplyCode()).append('/').append(String.join("&", fs)).append(';');
        }
        List<String> blocks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Battlefields.Camp camp = c.camps[i];
            if (camp == null) continue;
            for (BlockPos p : camp.wool) blocks.add("w" + i + "@" + p.asLong());
            for (BlockPos p : camp.poles) blocks.add("p" + i + "@" + p.asLong());
            if (camp.fire != null) blocks.add("f" + i + "@" + camp.fire.asLong());
        }
        if (c.ram != null) for (BlockPos p : c.ram) blocks.add("r0@" + p.asLong());
        s.append(String.join(",", blocks)).append(';').append(c.gate == null ? "" : Long.toString(c.gate.asLong())).append(';').append(c.gateOut.get3DDataValue());
        Ledger.note(c.a, "war.campaign", s.toString());
    }

    /**
     * Back from the books after a restart: a campaign that was on the road carries on from its stage's start (its
     * column's head back at the road's start: its fighters are where they were); one in the middle of a battle is
     * reckoned on paper; the camps' and the ram's blocks are known, to be taken up.
     */
    static void load() {
        for (Villages.Village v : Villages.every()) {
            String s = Ledger.note(v.id(), "war.campaign");
            if (s == null || s.isEmpty()) continue;
            try {
                Campaign c = decode(v.id(), s);
                if (c != null) {
                    ACTIVE.put(Ledger.pair(c.a, c.b), c);
                    for (Armies.Army a : c.armies) if (a != null) Armies.enlist(a);
                }
            } catch (RuntimeException e) {
                LOG.warn("[MCA-WAR] a campaign in {}'s books would not read; let go", Villages.name(v.id()));
                Ledger.forget(v.id(), "war.campaign");
            }
        }
    }

    @Nullable
    static Campaign decode(UUID a, String s) {
        String[] p = s.split(";", -1);
        if (p.length < 14) return null;
        Campaign c = new Campaign(a, UUID.fromString(p[0]));
        c.stage = Stage.values()[Integer.parseInt(p[1])];
        c.holdWalls = "1".equals(p[2]);
        c.siegeAim = "1".equals(p[3]);
        c.won = Integer.parseInt(p[4]);
        c.day = Long.parseLong(p[5]);
        String[] ax = p[7].split(",");
        c.ground = new Battlefields.Ground(BlockPos.of(Long.parseLong(p[6])), Double.parseDouble(ax[0]), Double.parseDouble(ax[1]), null, null, null, null, null, p[8], false);
        c.name = "the Battle of " + p[8];
        for (int i = 0; i < 2; i++) {
            String code = p[9 + i];
            if (code.equals("-")) continue;
            String[] q = code.split("/", 6);
            if (q.length < 6) continue;
            Armies.Army army = new Armies.Army(i == 0 ? c.a : c.b, i == 0 ? c.b : c.a);
            String[] tf = q[0].split(":");
            army.tactic = BattleModel.Tactic.values()[Integer.parseInt(tf[0])];
            if (tf.length > 1) army.formation = BattleModel.Formation.values()[Integer.parseInt(tf[1])];
            army.morale = Integer.parseInt(q[1]);
            army.behind = Integer.parseInt(q[2]);
            army.behindPower = Integer.parseInt(q[3]);
            army.supplyFrom(q[4]);
            for (String f : q[5].split("&")) {
                Armies.Fighter fi = Armies.Fighter.decode(f);
                if (fi == null) continue;
                army.fighters.add(fi);
                if (fi.rank == Armies.Rank.CAPTAIN) army.captain = fi.id;
                if (fi.rank == Armies.Rank.BEARER) army.bearer = fi.id;
                if (fi.role == BattleModel.Role.MEDIC) army.medic = fi.id;
            }
            c.armies[i] = army;
        }
        for (String b : p[11].split(",")) {
            if (b.length() < 4) continue;
            int i = b.charAt(1) - '0';
            BlockPos pos = BlockPos.of(Long.parseLong(b.substring(3)));
            if (b.charAt(0) == 'r') {
                if (c.ram == null) c.ram = new ArrayList<>();
                c.ram.add(pos);
                continue;
            }
            if (c.camps[i] == null) c.camps[i] = new Battlefields.Camp(pos);
            switch (b.charAt(0)) {
                case 'w' -> c.camps[i].wool.add(pos);
                case 'p' -> c.camps[i].poles.add(pos);
                default -> c.camps[i].fire = pos;
            }
        }
        if (!p[12].isEmpty()) c.gate = BlockPos.of(Long.parseLong(p[12]));
        c.gateOut = Direction.from3DDataValue(Integer.parseInt(p[13]));
        // Its roads begin again from where it stood; a battle under way is reckoned on paper.
        if (c.stage == Stage.BATTLE || c.stage == Stage.ASSAULT) c.stage = Stage.CAMP;
        c.nextAt = 0;
        if (c.stage == Stage.MARCH || c.stage == Stage.SIEGE_MARCH || c.stage == Stage.HOME) {
            for (int i = 0; i < 2; i++) {
                Villages.Village v = c.town(i);
                if (c.armies[i] == null || v == null) continue;
                BlockPos to = c.stage == Stage.HOME ? v.centre() : c.stage == Stage.SIEGE_MARCH ? c.gate == null ? c.ground.centre() : c.gate.relative(c.gateOut, 28) : campSpot(c, i);
                route(c, i, c.stage == Stage.HOME ? c.ground.centre() : v.centre(), to);
            }
        }
        if (c.stage == Stage.CAMP || c.stage == Stage.SIEGE_CAMP) {
            for (int i = 0; i < 2; i++) if (c.armies[i] != null) c.ways.set(i, new ArrayList<>(List.of(c.camps[i] != null ? c.camps[i].centre : campSpot(c, i))));
        }
        return c;
    }

    // ------------------------------------------------------------------ the books' and the board's lines

    /** The campaign in a line or two for the town's war page and its board: the armies, their strength and spirit, where. */
    public static List<String> lines(ServerLevel level, UUID town) {
        List<String> out = new ArrayList<>();
        Campaign c = ofTown(town);
        if (c == null) return out;
        out.add(capital(c.name) + ": " + words(c.stage) + (c.stage == Stage.CAMP || c.stage == Stage.MARCH ? " (" + c.ground.name() + ")" : ""));
        for (int i = 0; i < 2; i++) {
            Armies.Army a = c.armies[i];
            if (a == null) continue;
            Armies.Fighter cap = a.officer(Armies.Rank.CAPTAIN);
            BlockPos h = c.ways.get(i).isEmpty() ? null : c.head(i);
            out.add(Villages.name(a.town) + "'s army: " + a.standing() + " of " + a.size() + " standing, spirit " + spirit(a)
                + (cap != null ? ", " + cap.what() : "") + ", " + a.tactic.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                + (h != null ? " at " + h.getX() + ", " + h.getZ() : ""));
        }
        return out;
    }

    /** A folk's battles for its card: "A veteran of 2 battles, called Brannoc the Bold"; in the field, what it is about. Null if neither. */
    @Nullable
    public static String veteranLine(VillageFolkEntity f) {
        UUID town = f.ownerId();
        if (town == null) return null;
        Armies.Fighter x = Armies.fighter(f.getUUID());
        String now = x == null ? null : "In the field: " + Armies.doing(x);
        int n = Armies.battles(town, f.getUUID());
        if (n <= 0) return now;
        String nick = Ledger.note(town, "war.nick/" + f.getUUID());
        String vet = "A veteran of " + (n == 1 ? "a battle" : n + " battles") + (nick == null || nick.isEmpty() ? "" : ", called " + f.displayNameCap() + " " + nick);
        return now == null ? vet : now + "; " + vet.substring(0, 1).toLowerCase(Locale.ROOT) + vet.substring(1);
    }

    static String spirit(Armies.Army a) {
        int m = a.morale;
        return m >= 70 ? "eager" : m >= 50 ? "steady" : m >= BattleModel.WAVER ? "shaken" : m >= BattleModel.BREAK ? "wavering" : "broken";
    }

    static String words(Stage s) {
        return switch (s) {
            case NOTICE -> "the army musters this afternoon";
            case MUSTER -> "the army is drawn up on the square";
            case MARCH -> "the armies are on the march";
            case CAMP -> "the armies are camped on the field";
            case BATTLE -> "battle is joined";
            case AFTER -> "the battle is over; the wounded are tended";
            case SIEGE_MARCH -> "the army marches on the town";
            case SIEGE_CAMP -> "the town is besieged";
            case ASSAULT -> "the gate is under assault";
            case HOME -> "the armies are on their way home";
            case DONE -> "the armies are home";
        };
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the stage (pictures)

    /** The phases a battle can be staged at for the pictures (/village battle stage). */
    public static final List<String> PHASES = List.of("muster", "march", "lineup", "parley", "horn", "volley", "charge", "clash", "rout", "aftermath");

    /** Fighters a side on the stage. */
    static final int STAGED = 16;                                       // [war-bows] sixteen: a line, and a company of each missile
    /** [field] A staged battle draws every fighter of an admin's big armies in full (two towns of seventy field forty a side). */
    static final int STAGED_DETAIL = 48;
    /** How far east a staged neighbour is raised, with none near (past the founding reach of two villages' range). */
    public static final int NEIGHBOUR = 260;

    /**
     * Pictures: a battle staged between the town and its enemy (else its nearest neighbour; a neighbour raised 260 blocks
     * east with none near), sixteen a side armed for nothing as a showcase where it has not an army of its own on the
     * watch already, at war, with the field at {@code at} (null: the field its Captains choose between them), and set at this
     * phase and held there: drawn up on their squares (muster); in column on the road (march); the lines formed in
     * their formations (lineup); the Captains and their standards met in the middle (parley); the archers' duel
     * (volley); the lines locked (clash); one side broken and running (rout); the medics at work and the captives led
     * off (aftermath); [war-polish] the horn and the battle cry (horn); the lines running in (charge). Every fighter
     * set down in its place at once. What to look at, "VIEW name x y z ax ay az" lines. [war-polish] A battle staged
     * here already is staged on to a later phase (the same armies, one muster, one outcome); an earlier phase starts
     * it afresh.
     */
    public static List<String> stage(ServerLevel level, Villages.Village v, @Nullable BlockPos at, String phase) {
        List<String> out = new ArrayList<>();
        Campaign going = ofTown(v.id());
        if (going != null && going.staged >= 0 && PHASES.indexOf(phase) >= going.staged && going.armies[0] != null && going.armies[1] != null) {
            restage(level, going, phase);
            return staged(level, going, phase, out);
        }
        Villages.Village o = opponent(v);
        if (o == null) {
            // Far enough off (two villages' reach) that the party founds a town of its own rather than joining this one.
            BlockPos there = Battlefields.surface(level, v.centre().offset(NEIGHBOUR, 0, 0));
            com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raiseParty(level, there, 0.0F, STAGED + 4);
            o = Villages.nearest(level, there, Villages.VILLAGE_RANGE * 2);
            if (o != null && o.id().equals(v.id())) o = null;
            if (o == null) {
                out.add("NO-STAGE no neighbour could be raised");
                return out;
            }
        }
        for (Villages.Village t : new Villages.Village[]{ v, o }) {
            Heraldry.chooseForTests(level, t);
            if (WarFooting.militia(t.id()).size() < STAGED + STAGED / 4) showcaseArmy(level, t);   // [field] an army raised already is kept as kitted
        }
        long day = level.getDayTime() / 24000L;
        if (!Wars.atWar(v.id(), o.id())) WarAndPeace.declare(level, v.id(), o.id(), day, WarAndPeace.Goal.TRIBUTE, 20, "twenty coins in tribute");
        Campaign old = of(v.id(), o.id());
        if (old != null) {
            if (old.battle != null) old.battle.abandon(level);
            for (int i = 0; i < 2; i++) if (old.armies[i] != null) Armies.discharge(level, old.armies[i]);
            ACTIVE.remove(Ledger.pair(old.a, old.b));
        }
        double dx = o.centre().getX() - v.centre().getX(), dz = o.centre().getZ() - v.centre().getZ(), n = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        Battlefields.Ground g = at != null ? Battlefields.groundAt(level, at, dx / n, dz / n, Villages.name(v.id()) + " Field")
            : Battlefields.choose(level, v, o, true);                      // [field] the Captains' own choice, its ground made to look at
        if (g == null || Battlefields.wet(level, g)) {                     // never a battle on water, staged or not
            out.add("NO-STAGE no dry ground to fight on between " + Villages.name(v.id()) + " and " + Villages.name(o.id()) + ": only water");
            return out;
        }
        hurryForPictures();
        Campaign c = begin(level, v, o, day, false, false);
        if (c == null) {
            out.add("NO-STAGE no campaign");
            return out;
        }
        c.ground = g;
        c.name = "the Battle of " + c.ground.name();
        muster(level, c, Math.max(cap(), STAGED_DETAIL));
        if (c.armies[0] == null || c.armies[1] == null) {
            out.add("NO-STAGE an army could not be raised");
            return out;
        }
        c.staged = 0;
        restage(level, c, phase);
        return staged(level, c, phase, out);
    }

    /** [field] Who a town stages a battle against: its enemy, else its nearest neighbour within 400 blocks. */
    @Nullable
    static Villages.Village opponent(Villages.Village v) {
        for (UUID e : Wars.enemies(v.id())) {
            Villages.Village o = Villages.get(e);
            if (o != null && o.dim().equals(v.dim())) return o;
        }
        Villages.Village o = null;
        double best = 400.0 * 400.0;
        for (Villages.Village n : Villages.every()) {
            if (n.id().equals(v.id()) || !n.dim().equals(v.dim())) continue;
            double d = n.centre().distSqr(v.centre());
            if (d < best) { best = d; o = n; }
        }
        return o;
    }

    /** [war-polish] The staged campaign brought on to this phase: drawn up on the squares, the columns on the road, or its one battle set at it. */
    static void restage(ServerLevel level, Campaign c, String phase) {
        switch (phase) {
            case "muster" -> { }
            case "march" -> {
                march(level, c);
                for (int i = 0; i < 2; i++) c.along[i] = length(c.ways.get(i)) * 0.45;
                for (int i = 0; i < 2; i++) column(level, c, i);
            }
            default -> {
                if (c.battle == null) {
                    for (int i = 0; i < 2; i++) c.ways.set(i, new ArrayList<>(List.of(campSpot(c, i))));
                    c.battle = Battles.openStaged(level, c, c.ground.centre(), c.ground.ax(), c.ground.az());
                    if (c.ground.known()) Battlefields.clearBrush(level, c.ground);
                    stage(c, Stage.BATTLE, level.getGameTime(), level.getGameTime());
                }
                c.battle.stagePhase(level, phase);
            }
        }
        c.staged = Math.max(c.staged, PHASES.indexOf(phase));
    }

    /** The staged campaign's fighters set down in their places at once, and what to look at. */
    static List<String> staged(ServerLevel level, Campaign c, String phase, List<String> out) {
        for (int i = 0; i < 2; i++) snap(level, c.armies[i]);
        out.add("STAGED " + phase + " " + Villages.name(c.a) + " against " + Villages.name(c.b) + " on " + c.ground.name() + " ("
            + c.armies[0].formation.words + " against " + c.armies[1].formation.words + "), " + c.armies[0].size() + " against " + c.armies[1].size());
        BlockPos f = c.ground.centre();
        out.add("FIELD " + f.getX() + " " + f.getY() + " " + f.getZ());           // [field] where the pictures go, and each town's heart
        for (int i = 0; i < 2; i++) {
            Villages.Village t = c.town(i);
            if (t != null) out.add("TOWN " + Villages.name(t.id()).replace(' ', '_') + " " + t.centre().getX() + " " + t.centre().getY() + " " + t.centre().getZ());
        }
        out.addAll(views(level, c));
        return out;
    }

    /** Twenty of the town's grown folk to the watch (sixteen march; the rest are its home guard), armed in iron for nothing (a showcase), two in five with a bow or a crossbow; folk raised to make them up. */
    static void showcaseArmy(ServerLevel level, Villages.Village t) {
        List<VillageFolkEntity> grown = new ArrayList<>();
        UUID elder = Villages.elder(t.id());
        for (AssistantEntity e : Villages.folkOf(t.id())) {
            if (e instanceof VillageFolkEntity f && !f.isBaby() && f.isAlive() && !f.getUUID().equals(elder) && !f.isShowcase()) grown.add(f);
        }
        int armed = STAGED + STAGED / 4;                                   // a fifth stays home as the home guard
        for (int k = grown.size(); k < armed + 1; k++) {
            VillageFolkEntity f = com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock.raise(level, t.centre(), 0.0F);
            if (f != null) grown.add(f);
        }
        int n = 0;
        for (VillageFolkEntity f : grown) {
            if (n >= armed) break;
            f.setJob(AssistantEntity.StationTask.GUARD);
            f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
            f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE));
            f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new ItemStack(net.minecraft.world.item.Items.IRON_LEGGINGS));
            f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new ItemStack(net.minecraft.world.item.Items.IRON_BOOTS));
            f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
            if (n % 5 == 3 || n % 5 == 1) {                                 // [war-bows] a company of bows and one of crossbows
                f.insertGiven(new ItemStack(n % 5 == 3 ? net.minecraft.world.item.Items.BOW : net.minecraft.world.item.Items.CROSSBOW));
                f.insertGiven(new ItemStack(net.minecraft.world.item.Items.ARROW, 32));
            } else {
                ItemStack shield = new ItemStack(net.minecraft.world.item.Items.SHIELD);
                Heraldry.Design d = Heraldry.design(t.id());
                if (d != null) Arms.emblazon(shield, Heraldry.item(level, t.id(), d));
                f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, shield);
            }
            f.setHealth(f.getMaxHealth());
            n++;
        }
        Heraldry.Design d = Heraldry.design(t.id());
        if (d != null) TownWork.give(level, t, Heraldry.item(level, t.id(), d));        // the standard for its bearer
        TownWork.give(level, t, new ItemStack(net.minecraft.world.item.Items.GOAT_HORN));
        TownWork.give(level, t, new ItemStack(net.minecraft.world.item.Items.LEATHER));
    }

    /** [field] Pictures: every staged battle in this world let go (an older stage left standing would crowd the new one's pictures). How many. */
    public static int unstageAll(ServerLevel level) {
        int n = 0;
        for (Campaign c : new ArrayList<>(ACTIVE.values())) {
            if (c.staged < 0) continue;
            Villages.Village t = c.town(0);
            if (t == null || !t.dim().equals(level.dimension())) continue;
            unstage(level, c.a);
            n++;
        }
        return n;
    }

    /** Pictures: the staged battle let go, the armies home. */
    public static String unstage(ServerLevel level, UUID town) {
        Campaign c = ofTown(town);
        if (c == null) return "No battle staged here.";
        if (c.battle != null) c.battle.abandon(level);
        c.battle = null;
        c.staged = -1;                                                         // [war-polish] its own again, to go home
        goHome(level, c, "the stage struck");
        return "The staged battle is over; the armies go home.";
    }

    /** Every fighter of the army set down in its place now, facing the way it is told (the pictures don't wait). */
    static void snap(ServerLevel level, @Nullable Armies.Army a) {
        if (a == null) return;
        for (Armies.Fighter f : a.fighters) {
            if (!f.placed || !(level.getEntity(f.id) instanceof VillageFolkEntity e)) continue;
            BlockPos to = BlockPos.containing(f.tx, f.ty, f.tz);
            if (!level.isLoaded(to)) continue;
            e.getNavigation().stop();
            Riding.carry(e, f.tx, f.ty, f.tz);
            Armies.face(e, f.yaw);
        }
    }

    // ------------------------------------------------------------------ tests and pictures

    /**
     * Pictures: where to look from for what the campaign is doing now, as "VIEW name x y z ax ay az" (an eye and what it
     * looks at): the army drawn up on the square, the column on the road, the camp, both armies drawn up from the
     * side and from the hill, the Captain with the banner close to, the clash, the ram at the gate.
     */
    public static List<String> views(ServerLevel level, Campaign c) {
        List<String> out = new ArrayList<>();
        Armies.Army a = c.armies[0];
        switch (c.stage) {
            case MUSTER -> {
                Villages.Village v = c.town(0);
                if (v != null && a != null) {
                    double[] o = squareFacing(v, c.town(1));
                    Vec3 at = midst(level, ALL, a);
                    if (at == null) view(level, out, "muster", o[0] + o[2] * 16 - o[3] * 4, o[1] + o[3] * 16 + o[2] * 4, 7, o[0] - o[2] * 4, o[1] - o[3] * 4);
                    else frame(level, out, "muster", at, o[2] * 18 - o[3] * 5, o[3] * 18 + o[2] * 5, 7);
                }
            }
            case MARCH, SIEGE_MARCH, HOME -> {
                if (a != null && !c.ways.get(0).isEmpty()) {
                    double[] d = heading(c.ways.get(0), c.along[0]);
                    Vec3 at = midst(level, ALL, a);                                 // [field] the whole column framed, from ahead and to one side
                    if (at == null) {
                        BlockPos h = c.head(0);
                        at = new Vec3(h.getX() - d[0] * 8, h.getY(), h.getZ() - d[1] * 8);
                    }
                    frame(level, out, "column", at, d[0] * 12 - d[1] * 14, d[1] * 12 + d[0] * 14, 7);
                }
            }
            case CAMP, SIEGE_CAMP -> {
                for (int i = 0; i < 2; i++) {
                    Battlefields.Camp camp = c.camps[i];
                    if (camp != null) view(level, out, "camp" + i, camp.centre.getX() + 12, camp.centre.getZ() + 12, 9, camp.centre.getX(), camp.centre.getZ());
                }
                if (c.stage == Stage.SIEGE_CAMP && c.gate != null) gateView(level, c, out);
            }
            case BATTLE, ASSAULT, AFTER -> {
                Battles.Battle b = c.battle;
                BlockPos m = b == null ? c.ground.centre() : b.centre;
                double ax = b == null ? c.ground.ax() : b.ax, az = b == null ? c.ground.az() : b.az;
                // [field] Each shot framed on the fighters it is of (where they stand now), from a set offset, its view checked clear.
                Vec3 all = midst(level, STANDING, c.armies[0], c.armies[1]);
                Vec3 mid = all != null ? all : Vec3.atBottomCenterOf(Battlefields.surface(level, m));
                frame(level, out, "side", mid, -az * 30, ax * 30, 10);
                if (b != null) {
                    // [war-polish] From a raised point behind each army: over its ranks, to the enemy's.
                    for (int i = 0; i < 2; i++) {
                        double[] o = b.origin(i);
                        Vec3 own = midst(level, STANDING, c.armies[i]);
                        Vec3 at = own != null ? own.add(o[2] * 6, 0, o[3] * 6) : new Vec3(o[0] + o[2] * 6, mid.y, o[1] + o[3] * 6);
                        frame(level, out, "behind" + i, at, -o[2] * 30, -o[3] * 30, 9);
                    }
                }
                BlockPos hill = c.ground.view();
                if (hill != null) frame(level, out, "hill", mid, hill.getX() + 0.5 - mid.x, hill.getZ() + 0.5 - mid.z, 4);
                else frame(level, out, "high", mid, -az * 40 - ax * 30, ax * 40 - az * 30, 26);
                Armies.Fighter cap = a == null ? null : a.officer(Armies.Rank.CAPTAIN);
                if (cap != null && level.getEntity(cap.id) instanceof VillageFolkEntity e) {
                    double fx = -Math.sin(Math.toRadians(e.getYRot())), fz = Math.cos(Math.toRadians(e.getYRot()));
                    view(level, out, "captain", e.getX() + fx * 4 + fz * 1.5, e.getZ() + fz * 4 - fx * 1.5, 1.6, e.getX(), e.getZ());
                }
                Vec3 lines = midst(level, f -> !f.down() && f.role() == BattleModel.Role.LINE, c.armies[0], c.armies[1]);
                frame(level, out, "clash", lines != null ? lines : mid, -az * 18 + ax * 6, ax * 18 + az * 6, 5);
                if (b != null && a != null) {
                    // [war-bows] The crossbow company close to, from its flank (a rank loosing, a rank kneeling to span); the
                    // archers' volley side on, far enough off to see the arc from the bows to where it falls.
                    double[] o = b.origin(0);
                    Armies.Fighter xb = null;
                    for (Armies.Fighter f : a.fighters) if (f.crossbow() && !f.down()) { xb = f; break; }
                    if (xb != null && xb.placed) {
                        view(level, out, "crossbows", xb.tx - o[3] * 7 + o[2] * 5, xb.tz + o[2] * 7 + o[3] * 5, 2.2, xb.tx, xb.tz);
                    }
                    double mx = (o[0] + m.getX()) / 2 - o[2] * 3, mz = (o[1] + m.getZ()) / 2 - o[3] * 3;
                    view(level, out, "arc", mx - o[3] * 26, mz + o[2] * 26, 7, mx, mz);
                }
                view(level, out, "parley", m.getX() - az * 6, m.getZ() + ax * 6, 1.5, m.getX(), m.getZ());   // [war-polish] the envoys close to
                if (c.stage == Stage.ASSAULT && c.gate != null) gateView(level, c, out);
            }
            default -> { }
        }
        return out;
    }

    static void gateView(ServerLevel level, Campaign c, List<String> out) {
        BlockPos g = c.gate;
        Direction o = c.gateOut;
        view(level, out, "gate", g.getX() + o.getStepX() * 16 + o.getClockWise().getStepX() * 7, g.getZ() + o.getStepZ() * 16 + o.getClockWise().getStepZ() * 7,
            5, g.getX(), g.getZ());
    }

    /** One view: an eye {@code up} over the ground at (x, z), looking at the ground at (tx, tz) a little over it (framed: see {@link #frame}). */
    static void view(ServerLevel level, List<String> out, String name, double x, double z, double up, double tx, double tz) {
        BlockPos at = Battlefields.surface(level, BlockPos.containing(tx, 0, tz));
        frame(level, out, name, new Vec3(tx, at.getY(), tz), x - tx, z - tz, up);
    }

    /** Which fighters a shot is of. */
    static final java.util.function.Predicate<Armies.Fighter> ALL = f -> true, STANDING = f -> !f.down();
    /** [field] Turns (degrees) round its target a blocked camera tries, in order, before it rises over the target instead. */
    static final double[] TURNS = { 0, 35, -35, 70, -70, 110, -110, 180 };
    /** The last resort's height over the target: from straight above and a little out, nothing stands between. */
    static final double OVERHEAD = 24.0;

    /** [field] The middle of these armies' fighters (those {@code which} takes) as they stand now, or null with none of them to be seen. */
    @Nullable
    static Vec3 midst(ServerLevel level, java.util.function.Predicate<Armies.Fighter> which, @Nullable Armies.Army... armies) {
        double x = 0, y = 0, z = 0;
        int n = 0;
        for (Armies.Army a : armies) {
            if (a == null) continue;
            for (Armies.Fighter f : a.fighters) {
                if (!which.test(f) || !(level.getEntity(f.id) instanceof VillageFolkEntity e)) continue;
                x += e.getX();
                y += e.getY();
                z += e.getZ();
                n++;
            }
        }
        return n == 0 ? null : new Vec3(x / n, y / n, z / n);
    }

    /**
     * [field] One view framed on {@code target} (feet level): the camera {@code ox, oz} out from it and {@code up} over the
     * higher of the target and the ground or treetops there, looking at it a block up, as "VIEW name x y z tx ty tz". The
     * camera is checked to see its mark (CameraCommands.clearSpot: stepped back or up out of a hill or a wall); one
     * that cannot is turned round the target, and failing every turn it looks down on it from overhead.
     */
    static void frame(ServerLevel level, List<String> out, String name, Vec3 target, double ox, double oz, double up) {
        Vec3 aim = target.add(0, 1.0, 0);
        Vec3 feet = null;
        for (double turn : TURNS) {
            double r = Math.toRadians(turn), cos = Math.cos(r), sin = Math.sin(r);
            double x = aim.x + ox * cos - oz * sin, z = aim.z + ox * sin + oz * cos;
            Vec3 try0 = new Vec3(x, Math.max(treetops(level, x, z), aim.y) + up, z);
            CameraCommands.Spot spot = CameraCommands.clearSpot(level, try0, aim);
            if (spot.how().equals("unloaded")) { feet = try0; break; }            // the pictures' own camera looks again once there
            if (!spot.how().equals("blocked")) { feet = spot.feet(); break; }
        }
        if (feet == null) feet = aim.add(ox * 0.25, OVERHEAD, oz * 0.25);
        out.add("VIEW " + name + " " + Mth.floor(feet.x) + " " + (int) Math.round(feet.y) + " " + Mth.floor(feet.z) + " " + Mth.floor(aim.x) + " "
            + Mth.floor(aim.y) + " " + Mth.floor(aim.z));
    }

    /** The top of whatever stands at (x, z), leaves and all; the floor of the world if its ground is not to hand. */
    static int treetops(ServerLevel level, double x, double z) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        if (!level.hasChunk(bx >> 4, bz >> 4)) return level.getMinBuildHeight();
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx, bz);
    }

    /** Tests and pictures: a campaign begun now between these two, whatever the war's day says. */
    @Nullable
    public static Campaign beginForTests(ServerLevel level, Villages.Village x, Villages.Village y, boolean holdWalls, boolean siegeAim) {
        return begin(level, x, y, level.getDayTime() / 24000L, holdWalls, siegeAim);
    }

    /** Tests and pictures: the campaign's next stage now (a march at its end first). */
    public static void nextForTests(ServerLevel level, Campaign c) {
        c.nextAt = 0;
        second(level, c);
    }

    /** Pictures: a column on the road brought to its end, and the campaign on to its next stage. */
    public static void onForPictures(ServerLevel level, Campaign c) {
        if (c.stage == Stage.MARCH || c.stage == Stage.SIEGE_MARCH || c.stage == Stage.HOME) arriveForTests(c);
        nextForTests(level, c);
    }

    /** Tests: the columns' heads at the end of their roads. */
    public static void arriveForTests(Campaign c) {
        for (int i = 0; i < 2; i++) if (!c.ways.get(i).isEmpty()) c.along[i] = length(c.ways.get(i));
    }

    /** Tests: the campaign as it would be read back from the books. */
    @Nullable
    public static Campaign reloadForTests(Campaign c) {
        save(c);
        return decode(c.a, Ledger.note(c.a, "war.campaign"));
    }

    /** [borders-map] The campaigns under way, for the war room's map table (WarRoom). */
    public static List<Campaign> active() {
        return new ArrayList<>(ACTIVE.values());
    }

    /** Tests: the campaigns under way. */
    public static List<Campaign> activeForTests() {
        return new ArrayList<>(ACTIVE.values());
    }
}
