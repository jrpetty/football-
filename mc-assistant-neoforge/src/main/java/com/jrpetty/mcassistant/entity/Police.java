package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [police] The watch as the town's police: one force, the guards, that keeps the walls and keeps the peace, on one
 * roster (Roster).
 *
 * <p>The parts: the day's roster and the captain who draws it (Roster); the watch house, the town's police station,
 * with its front desk, its notice board of the wanted, its records and its cells (WatchHouse); the beats, set routes
 * through the quarters where the trouble is at that hour, and the curfew (Beats); the incidents, a crime reported at a
 * run, a chase through the streets, a fight broken up, a drunk walked home, the first to a fire or a flood, a wound
 * seen to, a search led (Incidents); and the players, warned, fined, barred and, if they will, sworn in (PlayerLaw).
 *
 * <p>This class keeps the watch's books with the world (the rosters, each guard's record, the incidents, the fines,
 * the town's trust in its watch, the prisoners, the wanted and the bounties, the players' records and the special
 * constables), runs the round of the towns, and is where the rest of the mod meets it: the folk's tick and spirits,
 * its card and talk, the board, the gazette, the crier, the town's books (the Watch page) and the commands.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Police extends SavedData {

    private static final String ID = "mc_assistant_police";
    /** The bank holding this much at night has a guard at its door. */
    static final int BANK_GUARD = 100;
    /** What a town's trust in its watch starts at, and drifts back to. */
    static final int TRUST_START = 60;
    /** The log of incidents a town's books keep. */
    static final int LOG_MOST = 96;

    private CompoundTag towns = new CompoundTag(), guards = new CompoundTag(), folk = new CompoundTag(), custody = new CompoundTag();
    @Nullable private static Police loose;

    public Police() {}

    static Police of() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            if (loose == null) loose = new Police();
            return loose;
        }
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Police::new, Police::load, null), ID);
    }

    public static Police load(CompoundTag tag, HolderLookup.Provider registries) {
        Police p = new Police();
        p.towns = tag.getCompound("towns");
        p.guards = tag.getCompound("guards");
        p.folk = tag.getCompound("folk");
        p.custody = tag.getCompound("custody");
        return p;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("towns", towns.copy());
        tag.put("guards", guards.copy());
        tag.put("folk", folk.copy());
        tag.put("custody", custody.copy());
        return tag;
    }

    static void changed() {
        of().setDirty();
    }

    /** A town's own books (made the first time they are asked for). */
    static CompoundTag town(UUID village) {
        Police p = of();
        String key = village.toString();
        if (!p.towns.contains(key, Tag.TAG_COMPOUND)) {
            CompoundTag t = new CompoundTag();
            t.putInt("trust", TRUST_START);
            p.towns.put(key, t);
        }
        return p.towns.getCompound(key);
    }

    /** A guard's record: arrests, chases, fights broken up, folk helped... */
    static CompoundTag guard(UUID g) {
        Police p = of();
        String key = g.toString();
        if (!p.guards.contains(key, Tag.TAG_COMPOUND)) p.guards.put(key, new CompoundTag());
        return p.guards.getCompound(key);
    }

    /** A folk's own dealings with the watch: warnings, fines, nights in the cells. */
    static CompoundTag folk(UUID f) {
        Police p = of();
        String key = f.toString();
        if (!p.folk.contains(key, Tag.TAG_COMPOUND)) p.folk.put(key, new CompoundTag());
        return p.folk.getCompound(key);
    }

    static boolean knownFolk(UUID f) {
        return of().folk.contains(f.toString(), Tag.TAG_COMPOUND);
    }

    /** Everybody in the watch's keeping (WatchHouse), by folk id. */
    static CompoundTag custody() {
        return of().custody;
    }

    /**
     * What is only memory forgotten (Villages.resetForTests: between the tests, and as a world starts). The books kept
     * with the world are not touched; every record is its town's, guard's or folk's own by its id.
     */
    public static void resetForTests() {
        HELD.clear();
        Roster.resetForTests();
        Incidents.resetForTests();
        Beats.resetForTests();
        WatchHouse.resetForTests();
        PlayerLaw.resetForTests();
    }

    private static volatile boolean standDown;

    /**
     * The other features' tests run with the watch stood down (Kit.reset), so a guard there keeps its old round and a
     * witness its old ways, as those tests were written for; the watch's own tests (PoliceGameTests) stand it up again.
     * A world in play always has its watch.
     */
    public static void standDownForTests(boolean down) {
        standDown = down;
        if (down) HELD.clear();
    }

    /** Is the watch on the streets at all (not stood down for another feature's test)? */
    public static boolean active() {
        return !standDown;
    }

    // ------------------------------------------------------------------ the log and the tallies

    /** One incident into the town's books: the day, the hour, what kind, what happened, which guard, any coin. */
    static void log(UUID village, long dayTime, String kind, String text, @Nullable VillageFolkEntity guard, int coins) {
        CompoundTag t = town(village);
        ListTag l = t.getList("log", Tag.TAG_COMPOUND);
        CompoundTag one = new CompoundTag();
        one.putLong("day", dayTime / 24000L);
        one.putLong("t", Math.floorMod(dayTime, 24000L));
        one.putString("kind", kind);
        one.putString("text", text);
        if (guard != null) {
            one.putUUID("guard", guard.getUUID());
            one.putString("guardName", guard.displayNameCap());
        }
        if (coins > 0) one.putInt("coins", coins);
        l.add(one);
        while (l.size() > LOG_MOST) l.remove(0);
        t.put("log", l);
        changed();
    }

    /** The log since a day, the latest first. */
    static List<CompoundTag> logSince(UUID village, long since) {
        List<CompoundTag> out = new ArrayList<>();
        ListTag l = town(village).getList("log", Tag.TAG_COMPOUND);
        for (int i = l.size() - 1; i >= 0; i--) {
            CompoundTag one = l.getCompound(i);
            if (one.getLong("day") >= since) out.add(one);
        }
        return out;
    }

    /** How many of a kind of incident since a day; with {@code coins}, the coin they brought in. */
    static int tally(UUID village, long since, String kind, boolean coins) {
        int n = 0;
        for (CompoundTag one : logSince(village, since)) {
            if (!kind.isEmpty() && !one.getString("kind").equals(kind)) continue;
            n += coins ? one.getInt("coins") : 1;
        }
        return n;
    }

    /** A guard's record, one more of it ("arrests", "fights", "helped", "chases", "lost", "fines", "warnings", "wrong", "responded"). */
    static void count(@Nullable VillageFolkEntity g, String key, int n) {
        if (g == null) return;
        CompoundTag r = guard(g.getUUID());
        r.putInt(key, r.getInt(key) + n);
        r.putString("name", g.displayNameCap());
        changed();
    }

    /** The town's trust in its watch, nought to a hundred. */
    static int trust(UUID village) {
        CompoundTag t = town(village);
        return t.contains("trust") ? t.getInt("trust") : TRUST_START;
    }

    /** Trust won or lost: good police work, or a heavy hand. */
    static void trust(UUID village, int delta) {
        CompoundTag t = town(village);
        t.putInt("trust", Math.max(0, Math.min(100, trust(village) + delta)));
        changed();
    }

    static String trustWord(int t) {
        return t >= 80 ? "trusted by all" : t >= 65 ? "well thought of" : t >= 50 ? "trusted, mostly" : t >= 35 ? "grumbled at" : "resented";
    }

    // ------------------------------------------------------------------ the folk's part

    private record Held(String doing, int tick) {}

    private static final Map<UUID, Held> HELD = new ConcurrentHashMap<>();

    /**
     * From the folk's tick (VillageFolkEntity.aiStep, every few ticks, before the law's other business): a guard on an
     * incident (a report, a chase, a fight, an escort, a fire...) or at an event's post; a prisoner in the cells or on
     * the lead; a folk running to the watch, running from it, in a fight, being walked home, or lying low. True while
     * one of them has it; its own day waits.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        if (standDown) return release(f);
        if (f.isShowcase() || f.ownerId() == null || !f.isAlive()) return release(f);
        listen();
        String doing = WatchHouse.prisonerHold(f, level);
        if (doing == null) doing = Incidents.hold(f, level);
        if (doing == null && f.stationTask() == AssistantEntity.StationTask.GUARD) doing = WatchHouse.escortHold(f, level);
        if (doing == null && f.stationTask() == AssistantEntity.StationTask.GUARD) doing = Incidents.eventPost(f, level);
        if (doing == null) return release(f);
        if (HELD.get(f.getUUID()) == null || f.tickCount - HELD.get(f.getUUID()).tick() > 40) takeOver(f);
        HELD.put(f.getUUID(), new Held(doing, f.tickCount));
        return true;
    }

    /** Whatever it had queued up is dropped, the first time the watch takes it in hand. */
    static void takeOver(VillageFolkEntity f) {
        f.clearQueue();
    }

    /** Is the watch keeping this folk busy just now (between hold's looks)? */
    public static boolean busy(VillageFolkEntity f) {
        Held h = HELD.get(f.getUUID());
        return h != null && f.tickCount >= h.tick() && f.tickCount - h.tick() <= 8;
    }

    /** Called away from its work and its bed by the watch's business (the idle brain plans nothing meanwhile). */
    public static boolean calledAway(VillageFolkEntity f) {
        Held h = HELD.get(f.getUUID());
        return h != null && f.tickCount >= h.tick() && f.tickCount - h.tick() <= 40;
    }

    private static boolean release(VillageFolkEntity f) {
        HELD.remove(f.getUUID());
        return false;
    }

    /** What the watch has it doing, for its card and "What are you up to?", or null. */
    @Nullable
    public static String doingLine(VillageFolkEntity f) {
        Held h = HELD.get(f.getUUID());
        if (h == null || f.tickCount - h.tick() > 40) return null;
        return h.doing();
    }

    /** Is this guard taken up with an incident or a prisoner (not free to be sent after a monster)? */
    public static boolean engaged(VillageFolkEntity g) {
        return !standDown && (Incidents.task(g) != null || WatchHouse.escorting(g) != null);
    }

    /**
     * The guard's duty when its station brain has nothing else (VillageFolkEntity.streetRound, before its old round):
     * the walls and the gate, the beat, the desk, the events, the day off; the cases and the escort walk the beat
     * between their own work. True while the duty has it; false leaves it to the old round (Patrols).
     */
    public static boolean duty(VillageFolkEntity g, ServerLevel level) {
        if (standDown) return false;
        if (g.isHired() || g.ownerId() == null || Patrols.away(g) || Patrols.escorting(g)) return false;
        if (Civics.busy(g)) return true;                                       // out with a search party (Incidents.leadSearch)
        Villages.Village v = Villages.get(g.ownerId());
        if (v == null) return false;
        Roster.Duty d = Roster.dutyOf(level, g);
        if (d == null) return false;
        return switch (d) {
            case WALLS -> Beats.walls(g, level, v);
            case BEAT, CASES, ESCORT -> (d == Roster.Duty.ESCORT && Incidents.caravan(g, level, v)) || Beats.walk(g, level, v);
            case DESK -> WatchHouse.desk(g, level, v) || Beats.walk(g, level, v);
            case EVENT -> Incidents.guardThings(g, level, v) || Beats.walk(g, level, v);
            case REST -> Beats.rest(g, level, v);
            case FIRE, FLOOD -> Incidents.toTheEmergency(g, level, v);
            case BELL -> false;
        };
    }

    /**
     * Its shift (VillageFolkEntity.onShift), as the roster has it: a guard resting today is off, day and night (its
     * day off, and it sleeps the night through); a fire or a flood turns every guard out, whichever watch it keeps. The
     * rest keep the watch's old turns (the night in two watches, each sleeping half). Null to leave it as it was.
     */
    @Nullable
    public static Boolean shift(VillageFolkEntity g) {
        if (standDown) return null;
        if (g.stationTask() != AssistantEntity.StationTask.GUARD || g.ownerId() == null || !(g.level() instanceof ServerLevel level)) return null;
        if (Raids.underAlarm(g.ownerId())) return null;
        Roster.Duty d = Roster.dutyOf(level, g);
        if (d == null) return null;
        return switch (d) {
            case REST -> Boolean.FALSE;
            case FIRE, FLOOD -> Boolean.TRUE;
            default -> null;
        };
    }

    /** A guard on the beat after dark carries its lantern (NightLight leaves it in its hand, the shield in its pack). */
    public static boolean lantern(VillageFolkEntity g) {
        return Beats.lanternOut(g);
    }

    /** The flags its clothes are drawn by (VillageFolkEntity.clientPolice): its duty, the constable's coat, the badge. */
    static int flags(ServerLevel level, VillageFolkEntity g) {
        Roster.Duty d = Roster.dutyOf(level, g);
        if (d == null) return 0;
        int bits = d.ordinal() + 1;
        if (Inquiry.isConstable(g)) bits |= 1 << 4;
        UUID village = g.ownerId();
        if (village != null && g.getUUID().equals(captainId(village))) bits |= 1 << 5;
        if (g.countCarried(s -> s.is(com.jrpetty.mcassistant.item.PoliceItems.CONSTABLE_BADGE.get())) > 0) bits |= 1 << 6;
        if (d.policing() || Incidents.task(g) != null || WatchHouse.escorting(g) != null) bits |= 1 << 7;
        return bits;
    }

    @Nullable
    static UUID captainId(UUID village) {
        CompoundTag t = town(village);
        return t.hasUUID("captain") ? t.getUUID("captain") : null;
    }

    // ------------------------------------------------------------------ the round of the towns

    /** The watch's ear on the casebook: one, for the life of the game (Crime keeps its listeners, and a second would pay twice). */
    private static final Crime.Listener EAR = new Crime.Listener() {
        @Override
        public void closed(UUID village, int caseId, boolean solved, List<UUID> helpers) {
            com.jrpetty.mcassistant.Guard.run("the watch's books on a closed case", () -> closedCase(village, caseId, solved, helpers));
        }
    };

    /** The quests' seam (Crime.listen): a case closed pays the special constables who helped, and gives bail back. */
    static void listen() {
        if (listening) return;
        listening = true;
        Crime.listen(EAR);
    }

    private static volatile boolean listening;

    private static void closedCase(UUID village, int caseId, boolean solved, List<UUID> helpers) {
        if (standDown) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        Villages.Village v = Villages.get(village);
        if (server == null || v == null) return;
        ServerLevel level = server.getLevel(v.dim());
        if (level == null) return;
        if (solved) {
            trust(village, 2);
            PlayerLaw.caseClosed(level, v, caseId, helpers);
        }
        WatchHouse.bailBack(level, v, caseId);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 20 != 5 || standDown) return;
        com.jrpetty.mcassistant.Guard.run("the watch's round", () -> {
            listen();
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Villages.Village v : Villages.every()) {
                    if (!v.dim().equals(level.dimension()) || !level.isLoaded(v.centre())) continue;
                    tick(level, v);
                }
                PlayerLaw.tickPlayers(level);
            }
        });
    }

    /** One look at a town, every second: the roster, the clothes, the watch house, the incidents, the players. */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Villages.headcount(id) == 0) return;
        long now = level.getDayTime(), day = now / 24000L;
        CompoundTag t = town(id);
        if (!t.contains("dawn") || t.getLong("dawn") != day) {
            t.putLong("dawn", day);
            changed();
            com.jrpetty.mcassistant.Guard.run("the watch's morning", () -> daily(level, v, day));
        }
        com.jrpetty.mcassistant.Guard.run("the roster", () -> Roster.today(level, v));
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f)) continue;
            boolean watch = f.stationTask() == AssistantEntity.StationTask.GUARD && !f.isBaby() && !f.isHired();
            f.showPolice(watch ? flags(level, f) : 0);                 // off the watch, out of its kit
        }
        com.jrpetty.mcassistant.Guard.run("the watch house", () -> WatchHouse.tick(level, v));
        com.jrpetty.mcassistant.Guard.run("the incidents", () -> Incidents.tick(level, v));
        com.jrpetty.mcassistant.Guard.run("the beats", () -> Beats.tick(level, v));
        com.jrpetty.mcassistant.Guard.run("the players and the law", () -> PlayerLaw.tick(level, v));
        if (level.getGameTime() % 200 < 20) com.jrpetty.mcassistant.Guard.run("the constable's badge", () -> badges(level, v));
        if (level.getGameTime() % 1200 < 20) com.jrpetty.mcassistant.Guard.run("the watch's first hand", () -> firstHand(level, v, day));
    }

    /**
     * A town of the size that keeps a watch with no guard left at all (the one it had died, or was taken for the ferry or
     * the smithy): the leader asks for a hand, one a day, from a trade that can spare it — never the last at its trade,
     * the storekeeper, the banker or a scout, the old or the leader, nor a food-maker while the town is short. The town's
     * own sums move a hand only out of a trade over its share, and a town of a dozen has none over: its watch stayed
     * empty for good. Only on a full view (the town all loaded), as the sums are. Returns who took it up, or null.
     */
    @Nullable
    static VillageFolkEntity firstHand(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        CompoundTag t = town(id);
        if (t.contains("firstHand") && t.getLong("firstHand") == day) return null;
        if (Villages.loadedCount(id) * 5 < Villages.headcount(id) * 4) return null;
        Map<AssistantEntity.StationTask, Integer> have = new java.util.EnumMap<>(AssistantEntity.StationTask.class);
        List<VillageFolkEntity> all = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || f.isHired()) continue;
            if (f.stationTask() == AssistantEntity.StationTask.GUARD) return null;                // it has a watch
            if (f.stationTask() != AssistantEntity.StationTask.NONE) have.merge(f.stationTask(), 1, Integer::sum);
            all.add(f);
        }
        if (Villages.share(id, AssistantEntity.StationTask.GUARD) > -0.5) return null;   // too small a town for one yet
        boolean hungry = Market.hungry(id);
        VillageFolkEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (VillageFolkEntity f : all) {
            if (f.isBaby() || !f.isAlive() || f.isOld() || f.isElder() || f.isHired() || f.isShowcase() || Patrols.away(f)) continue;
            if (WatchHouse.custodyOf(f.getUUID()) != null) continue;
            AssistantEntity.StationTask job = f.stationTask();
            double score = WarFooting.volunteerScore(f) / 10.0;
            if (job != AssistantEntity.StationTask.NONE) {
                if (job == AssistantEntity.StationTask.STORE || job == AssistantEntity.StationTask.BANK || job == AssistantEntity.StationTask.SCOUT) continue;
                if (have.getOrDefault(job, 0) <= 1) continue;                                  // the last at its trade
                if (WarFooting.FOOD.contains(job) && hungry && !Villages.overStaffed(id, job)) continue;
                score += Villages.share(id, job) * 10.0;                                       // the trade with most to spare
            } else {
                score += 50.0;                                                                 // a hand at nothing
            }
            if (score > bestScore) { bestScore = score; best = f; }
        }
        t.putLong("firstHand", day);
        changed();
        if (best == null) return null;
        AssistantEntity.StationTask was = best.stationTask();
        if (!best.takeUpTrade(AssistantEntity.StationTask.GUARD)) best.setJob(AssistantEntity.StationTask.GUARD);
        if (best.stationTask() != AssistantEntity.StationTask.GUARD) return null;
        best.setAutonomous(true);
        FolkTalk.speak(best, FolkTalk.pick(best.getRandom(), "Somebody has to keep the watch. I will.",
            "No guard in the whole town? Then I'll take it up.", "The " + (was == AssistantEntity.StationTask.NONE ? "work" : was.label) + " can spare me. I'll keep the watch."));
        Villages.tell(id, day, best.displayNameCap() + " took up the watch, the town having no guard"
            + (was == AssistantEntity.StationTask.NONE ? "" : " (it was a " + was.title.toLowerCase(Locale.ROOT) + ")"));
        log(id, level.getDayTime(), "watch", best.displayNameCap() + " took up the watch, the town having no guard", best, 0);
        return best;
    }

    /** The morning: trust drifts back toward its usual, the curfew is weighed, old bounties are let go. */
    static void daily(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        int tr = trust(id);
        if (tr > TRUST_START) trust(id, -1);
        else if (tr < TRUST_START) trust(id, 1);
        // Heavy-handed: many fines on the town's own folk in the last week wear the trust down.
        int fines = tally(id, day - 7, "fine", false);
        if (fines > 4) trust(id, -(fines - 4));
        Beats.weighCurfew(level, v, day);
        PlayerLaw.daily(level, v, day);
    }

    // ------------------------------------------------------------------ the badge, and the cells' iron: the smith's

    /**
     * The smith's turn for the watch (Crafts.smith): the cells' iron bars and doors (WatchHouse), and a Constable's Badge
     * when the town wants one (its constable has none, or a player waits to be sworn in), of an iron ingot and four gold
     * nuggets out of the stores (a gold ingot cut into nine, the rest put by). What it made, or null.
     */
    @Nullable
    public static String smith(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (standDown) return null;
        String cells = WatchHouse.smith(level, v, f);
        if (cells != null) return cells;
        return badgeWanted(level, v) ? makeBadge(level, v) : null;
    }

    /** Does the town want a badge made: a player waiting to be sworn, or a constable without one, and none in the stores? */
    static boolean badgeWanted(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Market.stock(level, id, s -> s.is(com.jrpetty.mcassistant.item.PoliceItems.CONSTABLE_BADGE.get()) && PlayerLaw.badgeTown(s) == null) > 0) return false;
        if (town(id).hasUUID("badgeFor")) return true;
        VillageFolkEntity constable = Inquiry.constable(id);
        return constable != null && constable.countCarried(s -> s.is(com.jrpetty.mcassistant.item.PoliceItems.CONSTABLE_BADGE.get())) == 0;
    }

    /** A badge made of the stores' iron and gold, into the stores. Null if the stores do not run to it. */
    @Nullable
    static String makeBadge(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        // Iron and gold worked together: an Iron Age thing (Tiers), as the constable is an Iron Age town's.
        if (!Tiers.allows(level, Villages.ageOf(id), com.jrpetty.mcassistant.item.PoliceItems.CONSTABLE_BADGE.get())) return null;
        if (Market.stock(level, id, s -> s.is(net.minecraft.world.item.Items.IRON_INGOT)) < 1 + Crafts.IRON_KEPT / 4) return null;
        if (Market.stock(level, id, s -> s.is(net.minecraft.world.item.Items.GOLD_NUGGET)) < 4) {
            if (!Crafts.take(level, v, s -> s.is(net.minecraft.world.item.Items.GOLD_INGOT), 1)) return null;
            Crafts.store(level, v, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_NUGGET, 9));
        }
        if (!Crafts.take(level, v, s -> s.is(net.minecraft.world.item.Items.GOLD_NUGGET), 4)) return null;
        if (!Crafts.take(level, v, s -> s.is(net.minecraft.world.item.Items.IRON_INGOT), 1)) {
            Crafts.store(level, v, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_NUGGET, 4));
            return null;
        }
        Crafts.store(level, v, new net.minecraft.world.item.ItemStack(com.jrpetty.mcassistant.item.PoliceItems.CONSTABLE_BADGE.get()));
        log(id, level.getDayTime(), "house", "a constable's badge was made of the stores' iron and gold", null, 0);
        return "a constable's badge, of iron and gold";
    }

    /**
     * Every second: the town's constable pins on a badge out of the stores if it has none; and a town with no smith has the
     * shop's workshop make the badge it wants (a hand on the town's works, at the workshop).
     */
    static void badges(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        VillageFolkEntity constable = Inquiry.constable(id);
        java.util.function.Predicate<net.minecraft.world.item.ItemStack> unsworn =
            s -> s.is(com.jrpetty.mcassistant.item.PoliceItems.CONSTABLE_BADGE.get()) && PlayerLaw.badgeTown(s) == null;
        if (constable != null && constable.countCarried(s -> s.is(com.jrpetty.mcassistant.item.PoliceItems.CONSTABLE_BADGE.get())) == 0
                && !town(id).hasUUID("badgeFor") && Market.stock(level, id, unsworn) > 0) {
            net.minecraft.world.item.ItemStack badge = Crafts.takeOne(level, v, unsworn);
            if (!badge.isEmpty()) {
                net.minecraft.world.item.ItemStack left = constable.insertItem(badge);
                if (!left.isEmpty()) Crafts.store(level, v, left);
                else {
                    FolkTalk.speak(constable, "The town's badge. I'll wear it well.");
                    log(id, level.getDayTime(), "constable", constable.displayNameCap() + ", the constable, pinned on the town's badge", constable, 0);
                }
            }
        }
        if (!badgeWanted(level, v)) return;
        for (AssistantEntity a : Villages.folkOf(id)) if (a.stationTask() == AssistantEntity.StationTask.SMITH && !a.isBaby()) return;   // the smith's
        BlockPos workshop = Villages.builtAt(id, "workshop");
        if (workshop == null) workshop = Villages.depot(level, id);
        if (workshop == null || !TownJobs.atWork(level, v, "workshop", workshop, "making a constable's badge")) return;
        makeBadge(level, v);
    }

    // ------------------------------------------------------------------ its spirits

    /** Its spirits (VillageFolkEntity.refreshMood): a night in the cells, fined, helped by the watch. */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        if (standDown || !knownFolk(f.getUUID())) return m;
        CompoundTag r = folk(f.getUUID());
        if (WatchHouse.custodyOf(f.getUUID()) != null) { m -= 10; why.add(new Object[]{ "cells", 10 }); }
        long fined = r.contains("finedDay") ? r.getLong("finedDay") : -100;
        if (day - fined <= 1) { m -= 4; why.add(new Object[]{ "fined", 5 }); }
        long helped = r.contains("helpedDay") ? r.getLong("helpedDay") : -100;
        if (day - helped <= 1) { m += 5; why.add(new Object[]{ "helped", 5 }); }
        return m;
    }

    /** How it puts it, asked how it is (FolkTalk.reason). */
    public static String moodWords(VillageFolkEntity f, String why) {
        return switch (why) {
            case "cells" -> FolkTalk.pick(f.getRandom(), "Look where I am. Behind bars, like a common thief.", "Four walls and a bed. It's no life.");
            case "fined" -> "The watch fined me. I'll not forget it in a hurry.";
            case "helped" -> "The watch saw me right when I needed it. Good folk, the watch.";
            default -> "";
        };
    }

    // ------------------------------------------------------------------ where the player sees it

    /** Its card (FolkTalk.card): today's duty and its record, for a guard; the cells, the curfew, a fine, for anybody. */
    public static String cardLine(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (standDown || village == null || f.isShowcase() || !(f.level() instanceof ServerLevel level)) return "";
        List<String> parts = new ArrayList<>();
        String now = doingLine(f);
        if (now != null) parts.add(capital(now));
        if (f.stationTask() == AssistantEntity.StationTask.GUARD && !f.isBaby()) {
            Roster.Duty d = Roster.dutyOf(level, f);
            if (d != null) parts.add("today: " + d.words);
            if (f.getUUID().equals(captainId(village))) parts.add("captain of the watch");
            CompoundTag r = guard(f.getUUID());
            String rec = record(f.getUUID());
            if (!rec.isEmpty()) parts.add(rec);
            if (Roster.bothKinds(village, f.getUUID())) parts.add("walls and streets both this week");
            if (r.getInt("wrong") > 0) parts.add(r.getInt("wrong") + (r.getInt("wrong") == 1 ? " wrong arrest" : " wrong arrests"));
        }
        String held = WatchHouse.cardLine(f);
        if (!held.isEmpty()) parts.add(held);
        if (knownFolk(f.getUUID())) {
            CompoundTag r = folk(f.getUUID());
            if (r.getInt("curfew") > 0) parts.add("caught out after curfew " + times(r.getInt("curfew")));
            if (r.getInt("brawls") > 0) parts.add("in a fight " + times(r.getInt("brawls")));
            if (r.getInt("walkedHome") > 0) parts.add("walked home by the watch " + times(r.getInt("walkedHome")));
        }
        String bounty = PlayerLaw.bountyOn(village, f.getUUID());
        if (bounty != null) parts.add(bounty);
        return String.join("; ", parts);
    }

    /** A guard's record in a line: "3 arrests, 2 cases solved, 4 fights broken up, 6 folk helped". */
    static String record(UUID g) {
        CompoundTag r = guard(g);
        List<String> out = new ArrayList<>();
        int arrests = r.getInt("arrests"), solved = Crime.known(g) ? Crime.folk(g).getInt("solved") : 0;
        if (arrests > 0) out.add(arrests + (arrests == 1 ? " arrest" : " arrests"));
        if (solved > 0) out.add(solved + (solved == 1 ? " case solved" : " cases solved"));
        if (r.getInt("fights") > 0) out.add(r.getInt("fights") + (r.getInt("fights") == 1 ? " fight broken up" : " fights broken up"));
        if (r.getInt("helped") > 0) out.add(r.getInt("helped") + " folk helped");
        if (r.getInt("chases") > 0) out.add(r.getInt("chases") + (r.getInt("chases") == 1 ? " chase won" : " chases won"));
        return String.join(", ", out);
    }

    private static String times(int n) {
        return n == 1 ? "once" : n == 2 ? "twice" : n + " times";
    }

    /** What a guard says it is doing (FolkTalk.doing): its duty today, in its own words; else the old line (Patrols). */
    public static String doing(VillageFolkEntity g) {
        String held = doingLine(g);
        if (held != null) return capital(held) + ".";
        if (standDown || !(g.level() instanceof ServerLevel level)) return Patrols.doing(g);
        Roster.Duty d = Roster.dutyOf(level, g);
        if (d == null) return Patrols.doing(g);
        var r = g.getRandom();
        boolean night = level.isNight();
        return switch (d) {
            case WALLS -> night ? FolkTalk.pick(r, "The night watch on the walls. Quiet so far.", "On the wall till dawn. Nothing moves out there I don't see.")
                : FolkTalk.pick(r, "Wall and gate today. I see who comes and goes.", "On the gate. Strangers stop here and say their business.");
            case BEAT -> night ? FolkTalk.pick(r, "The night beat: the houses, the tavern, the lanes. Lantern and all.", "Walking the homes after dark. Folk sleep easier.")
                : "Walking my beat: " + Beats.routeWords(g) + ".";
            case DESK -> FolkTalk.pick(r, "On the desk at the watch house. Reports, the casebook, the cells.", "Minding the desk. If you've something to report, I'm your guard.");
            case CASES -> FolkTalk.pick(r, "On the cases today. When there's none to work, I walk.", "Investigation. Somebody's got to put the pieces together.");
            case ESCORT -> FolkTalk.pick(r, "Escort and court: prisoners to the council and back, the caravans out.", "Escort duty. Nobody walks to the court on their own.");
            case EVENT -> FolkTalk.pick(r, "Event duty: keeping the crowd orderly, and an eye on the stores.", "Posted for the day's doings. Somebody has to keep order.");
            case REST -> FolkTalk.pick(r, "My day off. The roster says so, and I'm not arguing.", "Resting. The rest of the watch has it today.");
            case BELL -> Patrols.doing(g);
            case FIRE -> "The fire! Out of the way!";
            case FLOOD -> "The river's up. Getting folk to the high ground.";
        };
    }

    /** What the town whispers (FolkTalk.gossipFor): the chase, the arrest, who's in the cells, the curfew. */
    public static List<String> gossip(VillageFolkEntity f) {
        List<String> out = new ArrayList<>();
        UUID village = f.ownerId();
        if (standDown || village == null) return out;
        long day = f.level().getDayTime() / 24000L;
        for (CompoundTag one : logSince(village, day - 2)) {
            String kind = one.getString("kind");
            if (!kind.equals("chase") && !kind.equals("arrest") && !kind.equals("fight") && !kind.equals("break") && !kind.equals("jail")) continue;
            out.add(switch (kind) {
                case "chase" -> "Did you see it? " + one.getString("text") + ". Never seen the like!";
                case "fight" -> one.getString("text") + ". Grown folk, brawling in the street!";
                case "break" -> one.getString("text") + ". Lock your doors tonight.";
                default -> one.getString("text") + ".";
            });
            if (out.size() >= 2) break;
        }
        if (Beats.curfew(village)) out.add("There's a curfew on, you know. The watch will have you home by the tenth bell.");
        if (trust(village) < 40) out.add("The watch has been heavy-handed lately. Folk are muttering.");
        return out;
    }

    /** The board's lines: today's roster, the cells, the wanted, the curfew, the watch's week. */
    public static List<String> board(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        Villages.Village v = Villages.get(village);
        if (standDown || v == null || Patrols.watch(village).isEmpty()) return out;
        CompoundTag t = town(village);
        String captain = t.getString("captainName");
        if (Raids.underAlarm(village)) out.add("RB|The roster stands down: every guard to the walls.");
        else if (Incidents.emergency(village) != null) out.add("RB|The roster stands down: every guard to " + Incidents.emergency(village).words + ".");
        out.add("RN|The watch today" + (captain.isEmpty() ? "" : " (captain " + captain + ")") + ": " + Roster.words(level, v) + ".");
        String cells = WatchHouse.boardLine(level, village);
        if (cells != null) out.add("RM|" + cells);
        out.addAll(PlayerLaw.boardLines(level, village));
        if (Beats.curfew(village)) out.add("RW|Curfew: everybody indoors from the tenth bell to first light, by order of the council.");
        long day = level.getDayTime() / 24000L;
        int arrests = tally(village, day - 6, "arrest", false), fights = tally(village, day - 6, "fight", false),
            helped = tally(village, day - 6, "help", false), fines = tally(village, day - 6, "", true);
        if (arrests + fights + helped + fines > 0) {
            out.add("RG|The watch this week: " + arrests + (arrests == 1 ? " arrest, " : " arrests, ") + fights + (fights == 1 ? " fight" : " fights")
                + " broken up, " + helped + " folk helped, " + fines + " coins in fines. Trust in the watch: " + trustWord(trust(village)) + ".");
        }
        return out;
    }

    /** The gazette's section (Gazette.issueOf): yesterday's chases, arrests and releases; who is in the cells; the curfew. */
    @Nullable
    public static String gazette(ServerLevel level, UUID village, long day) {
        if (standDown) return null;
        List<String> items = new ArrayList<>();
        for (CompoundTag one : logSince(village, day - 1)) {
            if (one.getLong("day") != day - 1) continue;
            String kind = one.getString("kind");
            if (kind.equals("chase") || kind.equals("arrest") || kind.equals("jail") || kind.equals("release") || kind.equals("break")
                    || kind.equals("fight") || kind.equals("fire") || kind.equals("curfew") || kind.equals("constable") || kind.equals("player")) {
                items.add(capital(one.getString("text")) + ".");
            }
        }
        String cells = WatchHouse.boardLine(level, village);
        if (items.isEmpty() && cells == null && !Beats.curfew(village)) return null;
        StringBuilder sb = new StringBuilder("§lThe watch§r");
        for (int i = 0; i < Math.min(5, items.size()); i++) sb.append('\n').append(items.get(i));
        if (cells != null) sb.append('\n').append(cells);
        if (Beats.curfew(village)) sb.append("\nThe curfew holds: indoors by the tenth bell.");
        return sb.toString();
    }

    /** The crier's lines (Crier.script): the wanted, the curfew, the watch's catch. */
    public static List<String> crierLines(ServerLevel level, Villages.Village v, long day) {
        List<String> out = new ArrayList<>();
        if (standDown) return out;
        out.addAll(PlayerLaw.crierLines(v.id()));
        if (Beats.curfew(v.id())) out.add("By order of the council: the curfew holds! Indoors by the tenth bell, every soul!");
        for (CompoundTag one : logSince(v.id(), day - 1)) {
            if (one.getString("kind").equals("chase")) {
                out.add("Hear ye! " + capital(one.getString("text")) + "! Well run, the watch!");
                break;
            }
        }
        return out;
    }

    /** The town's contentment's sense of safety (Contentment): a watch the town trusts, or one it resents. */
    public static int safety(UUID village, List<String> good, List<String> bad) {
        if (standDown || Patrols.watch(village).isEmpty()) return 0;
        int t = trust(village), s = 0;
        if (t >= 75) { s += 2; good.add("the watch keeps the peace"); }
        else if (t >= 60) s += 1;
        else if (t < 40) { s -= 2; bad.add("folk say the watch is heavy-handed"); }
        if (PlayerLaw.wantedAtLarge(village) > 0) { s -= 1; bad.add("a wanted folk is at large"); }
        return s;
    }

    // ------------------------------------------------------------------ the town's books

    /** The Watch page of the town's books (client/WatchPage). */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        if (standDown) return new CompoundTag();
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        CompoundTag out = new CompoundTag();
        List<VillageFolkEntity> watch = Patrols.watch(id);
        CompoundTag t = town(id);
        out.putString("town", Villages.name(id));
        out.putInt("guards", watch.size());
        out.putString("captain", t.getString("captainName"));
        VillageFolkEntity constable = Inquiry.constable(id);
        out.putString("constable", constable == null ? "" : constable.displayNameCap());
        out.putInt("trust", trust(id));
        out.putString("trustWord", trustWord(trust(id)));
        out.putString("house", WatchHouse.statusLine(level, v));
        out.putString("curfew", Beats.curfewLine(id));
        Roster.Duty override = Raids.underAlarm(id) ? Roster.Duty.BELL : Incidents.emergency(id);
        out.putString("override", override == null ? "" : "Every guard to " + override.words + ": the roster waits.");
        // Today's roster, each guard's duty, what it is doing, and its record.
        Map<UUID, Roster.Duty> r = Roster.today(level, v);
        ListTag roster = new ListTag();
        for (VillageFolkEntity g : watch) {
            CompoundTag row = new CompoundTag();
            row.putString("id", g.getStringUUID());
            row.putString("name", g.displayNameCap());
            Roster.Duty d = r.getOrDefault(g.getUUID(), Roster.Duty.BEAT);
            row.putString("duty", d.words);
            row.putString("letter", d.letter);
            row.putBoolean("defence", d.defence);
            row.putBoolean("captain", g.getUUID().equals(captainId(id)));
            row.putBoolean("constable", g == constable);
            row.putBoolean("both", Roster.bothKinds(id, g.getUUID()));
            String now = doingLine(g);
            row.putString("doing", now != null ? now : g.isSleeping() ? "asleep" : d.words);
            CompoundTag rec = guard(g.getUUID());
            row.putInt("arrests", rec.getInt("arrests"));
            row.putInt("solved", Crime.known(g.getUUID()) ? Crime.folk(g.getUUID()).getInt("solved") : 0);
            row.putInt("fights", rec.getInt("fights"));
            row.putInt("helped", rec.getInt("helped"));
            row.putInt("chases", rec.getInt("chases"));
            row.putInt("lost", rec.getInt("lost"));
            row.putInt("wrong", rec.getInt("wrong"));
            roster.add(row);
        }
        out.put("roster", roster);
        out.put("week", Roster.week(id));
        out.putLong("day", day);
        // The beats.
        ListTag beats = new ListTag();
        for (String s : Beats.describe(level, v)) beats.add(StringTag.valueOf(s));
        out.put("beats", beats);
        // The incidents this week.
        ListTag incidents = new ListTag();
        for (CompoundTag one : logSince(id, day - 6)) {
            if (incidents.size() >= 40) break;
            incidents.add(StringTag.valueOf("Day " + one.getLong("day") + ", " + Crime.hour(one.getLong("t")) + ": " + capital(one.getString("text"))
                + (one.getInt("coins") > 0 ? " (" + one.getInt("coins") + " coins)" : "")));
        }
        out.put("incidents", incidents);
        // The cells, the wanted, the special constables, the players.
        ListTag cells = new ListTag();
        for (String s : WatchHouse.prisoners(level, id)) cells.add(StringTag.valueOf(s));
        out.put("cells", cells);
        ListTag wanted = new ListTag();
        for (String s : PlayerLaw.wantedLines(id)) wanted.add(StringTag.valueOf(s));
        out.put("wanted", wanted);
        ListTag constables = new ListTag();
        for (String s : PlayerLaw.constableLines(id)) constables.add(StringTag.valueOf(s));
        out.put("constables", constables);
        // The week and the month, and eight weeks of it for the chart: incidents (crimes reported and disorder), arrests, fines.
        out.putInt("arrestsWeek", tally(id, day - 6, "arrest", false));
        out.putInt("arrestsMonth", tally(id, day - 27, "arrest", false));
        out.putInt("finesWeek", tally(id, day - 6, "", true));
        out.putInt("finesMonth", tally(id, day - 27, "", true));
        out.putInt("fightsWeek", tally(id, day - 6, "fight", false));
        out.putInt("helpedWeek", tally(id, day - 6, "help", false));
        int[] crimes = new int[8], arrests = new int[8], fines = new int[8];
        for (Crime.Case c : Crime.cases(id)) {
            if (c.stage == Crime.Stage.UNNOTICED) continue;
            int w = (int) ((day - c.day) / 7);
            if (w >= 0 && w < 8) crimes[7 - w]++;
        }
        for (CompoundTag one : logSince(id, day - 55)) {
            int w = (int) ((day - one.getLong("day")) / 7);
            if (w < 0 || w >= 8) continue;
            String kind = one.getString("kind");
            if (kind.equals("fight") || kind.equals("curfew") || kind.equals("disorder") || kind.equals("player")) crimes[7 - w]++;
            if (kind.equals("arrest")) arrests[7 - w]++;
            fines[7 - w] += one.getInt("coins");
        }
        out.put("crimes", new IntArrayTag(crimes));
        out.put("arrests", new IntArrayTag(arrests));
        out.put("fines", new IntArrayTag(fines));
        return out;
    }

    // ------------------------------------------------------------------ talk

    /**
     * The watch's own business, before the casebook's (Crime.talk): "I want to report a crime", "swear me in", "can I
     * join the patrol?", "who's on the roster?", "I'll stand bail for Fen", "any bounties?". Null when it is none of it.
     */
    @Nullable
    public static String talk(VillageFolkEntity f, ServerPlayer p, String text) {
        if (standDown) return null;
        String t = " " + text.toLowerCase(Locale.ROOT) + " ";
        boolean watch = f.stationTask() == AssistantEntity.StationTask.GUARD;
        if (t.contains("report a crime") || t.contains("report something") || t.contains("want to report")) return PlayerLaw.report(f, p);
        if (t.contains("swear") || t.contains("sworn") || t.contains("special constable") || t.contains("deputi")) return PlayerLaw.swear(f, p);
        if (t.contains("patrol")) return PlayerLaw.patrol(f, p);
        if (t.contains("bounty") || t.contains("bounties") || t.contains("wanted")) return PlayerLaw.bounties(f, p);
        if (t.contains("bail")) return WatchHouse.bailTalk(f, p, text);
        if (t.contains("roster") || t.contains("on duty") || t.contains("your duty") || t.contains("captain")) {
            if (!(p.level() instanceof ServerLevel level) || f.ownerId() == null) return null;
            Villages.Village v = Villages.get(f.ownerId());
            if (v == null) return null;
            Roster.Duty d = watch ? Roster.dutyOf(level, f) : null;
            String mine = d != null ? " I'm on " + d.words + "." : "";
            String captain = town(v.id()).getString("captainName");
            return "Today's roster" + (captain.isEmpty() ? "" : ", as Captain " + captain + " drew it") + ": " + Roster.words(level, v) + "." + mine;
        }
        if (t.contains("curfew")) return Beats.curfew(f.ownerId()) ? "There's a curfew on: indoors from the tenth bell till first light. The watch will see you home."
            : "No curfew. The council only calls one when the nights get bad.";
        return null;
    }

    // ------------------------------------------------------------------ the commands

    /**
     * /village police: the watch as the town's police. {@code books} opens the town's books at the Watch page. Operators:
     * {@code roster} draws today's roster afresh; {@code stage} sets the pictures' scene (the watch house stamped beside
     * the town with a prisoner in a cell, a guard on the beat, a chase, an arrest on a lead); {@code curfew on|off};
     * {@code chase} and {@code fight} start one among the folk at hand; {@code swear <player>} swears a player in.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("police")
            .executes(ctx -> say(ctx, v -> String.join("\n", lines(ctx.getSource().getLevel(), v))))
            .then(Commands.literal("books").executes(Police::cmdBooks))
            .then(Commands.literal("roster").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, v -> {
                    town(v.id()).remove("roster");
                    Roster.forget(v.id());
                    return "ROSTER " + Roster.words(ctx.getSource().getLevel(), v);
                })))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, v -> String.join("\n", PoliceStage.stage(ctx.getSource().getLevel(), v,
                    BlockPos.containing(ctx.getSource().getPosition()))))))
            .then(Commands.literal("curfew").requires(src -> src.hasPermission(2))
                .then(Commands.literal("on").executes(ctx -> say(ctx, v -> Beats.setCurfew(ctx.getSource().getLevel(), v, true, "by order"))))
                .then(Commands.literal("off").executes(ctx -> say(ctx, v -> Beats.setCurfew(ctx.getSource().getLevel(), v, false, "by order")))))
            .then(Commands.literal("chase").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, v -> PoliceStage.chase(ctx.getSource().getLevel(), v, BlockPos.containing(ctx.getSource().getPosition())))))
            .then(Commands.literal("fight").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, v -> PoliceStage.fight(ctx.getSource().getLevel(), v, BlockPos.containing(ctx.getSource().getPosition())))))
            .then(Commands.literal("swear").requires(src -> src.hasPermission(2))
                .then(Commands.argument("player", StringArgumentType.word())
                    .executes(ctx -> say(ctx, v -> {
                        ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayerByName(StringArgumentType.getString(ctx, "player"));
                        return p == null ? "No such player." : PlayerLaw.swearNow(ctx.getSource().getLevel(), v, p);
                    }))));
    }

    /** The watch in lines: the roster, the cells, the wanted, the curfew, the week. */
    static List<String> lines(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        out.add("The watch of " + Villages.name(id) + ": " + Patrols.watch(id).size() + " guards; trust " + trust(id) + " (" + trustWord(trust(id)) + ").");
        out.add("Roster: " + Roster.words(level, v) + ".");
        out.add(WatchHouse.statusLine(level, v));
        out.addAll(WatchHouse.prisoners(level, id));
        out.addAll(PlayerLaw.wantedLines(id));
        out.add(Beats.curfewLine(id));
        for (String s : Beats.describe(level, v)) out.add(s);
        long day = level.getDayTime() / 24000L;
        for (CompoundTag one : logSince(id, day - 2)) {
            if (out.size() > 30) break;
            out.add("Day " + one.getLong("day") + ": " + one.getString("text"));
        }
        return out;
    }

    private static int cmdBooks(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines(ctx.getSource().getLevel(), v))), false);
            return 1;
        }
        CompoundTag books = Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "Watch");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int say(CommandContext<CommandSourceStack> ctx, java.util.function.Function<Villages.Village, String> what) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        String out = what.apply(v);
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** "Mill Lane", "the East Road", or null off the town's streets: the street a spot is on, by the plan. */
    @Nullable
    static String streetAt(UUID village, BlockPos heart, BlockPos at) {
        int dx = at.getX() - heart.getX(), dz = at.getZ() - heart.getZ();
        int alongX = TownLife.lineAt(dz), alongZ = TownLife.lineAt(dx);
        if (alongX != Integer.MIN_VALUE && (alongX != 0 || Math.abs(dx) > com.jrpetty.mcassistant.village.TownPlan.PLAZA)) {
            return TownLife.streetName(village, true, alongX, dx);
        }
        if (alongZ != Integer.MIN_VALUE && (alongZ != 0 || Math.abs(dz) > com.jrpetty.mcassistant.village.TownPlan.PLAZA)) {
            return TownLife.streetName(village, false, alongZ, dz);
        }
        return null;
    }

    // ------------------------------------------------------------------ the tests

    public static Map<UUID, String> rosterForTests(ServerLevel level, Villages.Village v) {
        Map<UUID, String> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, Roster.Duty> e : Roster.today(level, v).entrySet()) out.put(e.getKey(), e.getValue().name());
        return out;
    }

    /** The roster drawn for that day (and kept as that day's, for the week's grid). */
    public static Map<UUID, String> drawForTests(ServerLevel level, Villages.Village v, long day) {
        Map<UUID, String> out = new LinkedHashMap<>();
        Map<UUID, Roster.Duty> drawn = Roster.draw(level, v, day);
        CompoundTag nr = new CompoundTag();
        for (Map.Entry<UUID, Roster.Duty> e : drawn.entrySet()) {
            out.put(e.getKey(), e.getValue().name());
            nr.putString(e.getKey().toString(), e.getValue().name());
        }
        CompoundTag week = town(v.id()).getCompound("week");
        week.put(Long.toString(day), nr);
        town(v.id()).put("week", week);
        Roster.forget(v.id());
        return out;
    }

    public static boolean bothKindsForTests(UUID village, UUID guard) {
        return Roster.bothKinds(village, guard);
    }

    public static String dutyForTests(ServerLevel level, VillageFolkEntity g) {
        Roster.Duty d = Roster.dutyOf(level, g);
        return d == null ? "" : d.name();
    }

    /** Set a guard's duty for today (the roster as kept). */
    public static void setDutyForTests(ServerLevel level, VillageFolkEntity g, String duty) {
        Villages.Village v = Villages.get(g.ownerId());
        if (v == null) return;
        Roster.forget(v.id());               // the roster read afresh: a guard just appointed is on it before its duty is set
        Roster.today(level, v);
        CompoundTag r = town(v.id()).getCompound("roster");
        r.putString(g.getStringUUID(), duty);
        town(v.id()).put("roster", r);
        Roster.forget(v.id());
        Beats.forget(g);
    }

    public static String taskForTests(VillageFolkEntity f) {
        Incidents.Task t = Incidents.task(f);
        return t == null ? "" : t.kind.name();
    }

    public static int trustForTests(UUID village) {
        return trust(village);
    }

    public static int tallyForTests(UUID village, long since, String kind) {
        return tally(village, since, kind, false);
    }

    public static CompoundTag guardRecordForTests(VillageFolkEntity g) {
        return guard(g.getUUID()).copy();
    }

    public static CompoundTag folkRecordForTests(VillageFolkEntity f) {
        return folk(f.getUUID()).copy();
    }

    public static CompoundTag reportForTests(ServerLevel level, Villages.Village v) {
        return report(level, v);
    }

    /** One look at the town now, as the second's round has it (the roster, the clothes, the house, the incidents). */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        tick(level, v);
    }

    public static String boardForTests(ServerLevel level, UUID village) {
        return String.join("\n", board(level, village));
    }

    public static String cardForTests(VillageFolkEntity f) {
        return cardLine(f);
    }

    /** What the watch has this folk doing just now (its hold), or "". */
    public static String doingForTests(VillageFolkEntity f) {
        String s = doingLine(f);
        return s == null ? "" : s;
    }

    // ---- the watch's first hand, and the operators' stage

    /** The look for the watch's first hand, now (not waiting on the minute): who took up the watch, or null. */
    @Nullable
    public static VillageFolkEntity firstHandForTests(ServerLevel level, Villages.Village v) {
        return firstHand(level, v, level.getDayTime() / 24000L);
    }

    /** /village police stage, from here: what it said (its VIEW lines among it). */
    public static List<String> stageForTests(ServerLevel level, Villages.Village v, BlockPos at) {
        return PoliceStage.stage(level, v, at);
    }

    /** Is a camera with its feet here in the open, with a clear line to the subject (PoliceStage.clearFrom)? */
    public static boolean clearViewForTests(ServerLevel level, BlockPos feet, BlockPos at) {
        return PoliceStage.clearFrom(level, feet, at);
    }

    // ---- the watch house and the cells

    /** The watch house stamped here facing north (its door to the south) and put on the town's books, as its builders would leave it. */
    public static void watchHouseForTests(ServerLevel level, Villages.Village v, BlockPos site) {
        com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, WatchHouse.STRUCTURE, site, net.minecraft.core.Direction.NORTH, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(v.id(), WatchHouse.STRUCTURE, site, net.minecraft.core.Direction.NORTH);
        Villages.builtAtForTests(v.id(), WatchHouse.STRUCTURE, site);
    }

    /** Does the town want a watch house (the Stone Age, a watch of three, none yet)? */
    public static boolean wantsWatchHouseForTests(UUID village) {
        return WatchHouse.wanted(village);
    }

    /** The watch fits out its house now, as its round would: the cells' iron, the notice board, the casebook. */
    public static void fitForTests(ServerLevel level, Villages.Village v) {
        Ledger.Building b = WatchHouse.of(v.id());
        if (b == null) return;
        WatchHouse.fit(level, v, b);
        WatchHouse.noticeBoard(level, v, b);
        WatchHouse.casebook(level, v, b);
    }

    /** Each cell: where a prisoner stands, the bed's foot and head, the door, the passage before it, then its bars. */
    public static List<List<BlockPos>> cellsForTests(UUID village) {
        List<List<BlockPos>> out = new ArrayList<>();
        Ledger.Building b = WatchHouse.of(village);
        if (b == null) return out;
        for (WatchHouse.Cell c : WatchHouse.cells(b)) {
            List<BlockPos> one = new ArrayList<>(List.of(c.inside(), c.bedFoot(), c.bedHead(), c.door(), c.front()));
            one.addAll(c.bars());
            out.add(one);
        }
        return out;
    }

    /** The watch house's spots: "lectern", "desk", "inside", "outside", "wantedA", "wantedB", "roster". */
    @javax.annotation.Nullable
    public static BlockPos watchHouseSpotForTests(UUID village, String which) {
        Ledger.Building b = WatchHouse.of(village);
        if (b == null) return null;
        int[] c = switch (which) {
            case "lectern" -> WatchHouse.LECTERN;
            case "desk" -> WatchHouse.DESK;
            case "inside" -> WatchHouse.INSIDE;
            case "outside" -> WatchHouse.OUTSIDE;
            case "wantedA" -> WatchHouse.WANTED_A;
            case "wantedB" -> WatchHouse.WANTED_B;
            default -> WatchHouse.ROSTER;
        };
        return WatchHouse.at(b, c);
    }

    /** Arrested by this guard now, on this case (0: none), to wait for the council. */
    public static boolean arrestForTests(ServerLevel level, VillageFolkEntity guard, VillageFolkEntity f, int caseId, String why) {
        Villages.Village v = Villages.get(f.ownerId());
        return v != null && WatchHouse.arrest(level, v, guard, f, caseId, why, -1);
    }

    /** Where it is in the watch's keeping: "LED", "CELL", "COURT_WALK", "COURT", "BACK", "HALL", or "" (free). */
    public static String custodyForTests(VillageFolkEntity f) {
        return WatchHouse.stateOf(f.getUUID());
    }

    /** Its custody record, as kept (the cell, the case, until when, fed when), or an empty one. */
    public static CompoundTag custodyRecordForTests(VillageFolkEntity f) {
        CompoundTag t = WatchHouse.custodyOf(f.getUUID());
        return t == null ? new CompoundTag() : t.copy();
    }

    /** A case's worth set for a test (the stolen goods' value, which weighs in the sentence). */
    public static void worthForTests(Crime.Case c, int worth) {
        c.worth = worth;
        Crime.changed();
    }

    // ---- the incidents

    /** Two folk come to blows now, and the nearest guard is called to it. */
    public static void fightForTests(ServerLevel level, VillageFolkEntity a, VillageFolkEntity b) {
        Villages.Village v = Villages.get(a.ownerId());
        if (v != null) Incidents.startFight(level, v, a, b);
    }

    /** Any task the watch has on this folk let go (a test's next round). */
    public static void endTaskForTests(VillageFolkEntity f) {
        Incidents.endTask(f);
    }

    // ---- the beats and the curfew

    /** The chance a deed is done at a spot, against the beat's passing (1: no beat lately). */
    public static double chanceAtForTests(ServerLevel level, UUID village, BlockPos at) {
        return Beats.chanceAt(level, village, at);
    }

    /** What would put a folk off a deed here just now (Mischief's own look: eyes, the watch, the beat, the lamps), or null. */
    @javax.annotation.Nullable
    public static String deterrentForTests(ServerLevel level, VillageFolkEntity f, Crime.Kind kind, BlockPos at) {
        return Mischief.deterrent(level, f, kind, null, at);
    }

    /** The stops of this guard's beat now. */
    public static List<BlockPos> beatForTests(ServerLevel level, VillageFolkEntity g) {
        List<BlockPos> out = new ArrayList<>();
        for (Beats.Stop s : Beats.routeForTests(level, g)) out.add(s.at());
        return out;
    }

    /** The stops of this guard's beat now, by name ("the market, on Mill Lane"). */
    public static List<String> beatNamesForTests(ServerLevel level, VillageFolkEntity g) {
        List<String> out = new ArrayList<>();
        for (Beats.Stop s : Beats.routeForTests(level, g)) out.add(s.name());
        return out;
    }

    /** One step of a guard's duty now, as its station brain takes it (VillageFolkEntity.streetRound). */
    public static boolean dutyStepForTests(ServerLevel level, VillageFolkEntity g) {
        return duty(g, level);
    }

    /** How much the beats' walking of the trouble spots puts off the day's temptations (1: not at all). */
    public static double coverForTests(ServerLevel level, Villages.Village v) {
        return Beats.cover(level, v);
    }

    /** A Constable's Badge made now of the stores' iron and gold, into the stores. */
    public static boolean makeBadgeForTests(ServerLevel level, Villages.Village v) {
        return makeBadge(level, v) != null;
    }

    public static String curfewForTests(ServerLevel level, Villages.Village v, boolean on) {
        return Beats.setCurfew(level, v, on, "by order");
    }

    /** The night beat meets a folk out after curfew: "warned", "fined", or null (it had reason to be out). */
    @javax.annotation.Nullable
    public static String curfewCheckForTests(ServerLevel level, VillageFolkEntity g, VillageFolkEntity f) {
        Villages.Village v = Villages.get(g.ownerId());
        return v == null ? null : Beats.curfewCheck(level, v, g, f);
    }
}
