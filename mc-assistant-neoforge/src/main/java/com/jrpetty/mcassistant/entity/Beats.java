package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [police] The beats, the walls, and the presence of the watch.
 *
 * <p><b>The beat.</b> A guard on the beat walks a set route through the town's quarters, where the trouble is at that
 * hour: by day the market (on market day above all), the square, the stores, the quay and the bank; of an evening the
 * tavern, at closing time; after dark the homes, the stores and the bank, lantern in hand. The places are the street by
 * each building's door (the town's plan), a beat each when there are two or more on it, with the corners of the
 * quarters between them; worked out once for the town as it stands and kept till it grows. At each stop the guard stands
 * a while and looks about, greets the folk passing by name and asks after their troubles, and notes what is amiss: a
 * folk hurt (bound with a bandage), the tavern at closing (calls time, and walks the worse for drink home), the curfew
 * (a warning, then a fine), a wanted face (a chase).
 *
 * <p><b>Presence.</b> Where the beat has passed lately the watch is felt: a folk minded to steal there thinks better of
 * it while the guard's boots are still warm on the stones (Mischief.deterrent: "the beat"), and a town whose beats cover
 * its market, tavern, square and stores has fewer folk tempted at all (Mischief.daily).
 *
 * <p><b>The walls and the gate.</b> By day at a gate, seeing who comes and goes (and shutting it in the face of a player
 * the town has barred); by night up on the wall at a post, as the bell would send it.
 *
 * <p><b>The curfew.</b> A town that has had three crimes or fights after dark in a week has the council call a curfew:
 * indoors from the tenth bell (15000) to first light, the night beat seeing to it; lifted after a quiet week.
 */
final class Beats {

    private Beats() {}

    /** How long the beat's passing is felt (ticks), and how far round a stop. */
    static final int FRESH = 1200, REACH = 16;
    /** The curfew's hours. */
    static final long CURFEW_FROM = 15000L, CURFEW_TO = 23000L;

    enum Phase { DAY, EVENING, NIGHT }

    /** A stop on a beat: where, what it is called, and what it is ("market", "tavern", "homes"...). */
    record Stop(BlockPos at, String name, String kind) {}

    /** A town's beats, as worked out: for how many on the beat, at what hour, over how many buildings. */
    private record Routes(List<List<Stop>> routes, int n, Phase phase, int buildings, long at) {}

    /** Where one guard is on its route. */
    private static final class Walk {
        int stop = -1;
        boolean walking;
        long legStart, until, greeted = -100000L, presenceAt;
        Phase phase;
    }

    private static final Map<String, Routes> ROUTES = new ConcurrentHashMap<>();
    private static final Map<UUID, Walk> WALKS = new ConcurrentHashMap<>();
    /** By town: where the watch has been lately (a block key, rounded to fours) and when. */
    private static final Map<UUID, Map<Long, Long>> PRESENCE = new ConcurrentHashMap<>();
    /** Guards walking with a lantern in hand. */
    private static final Set<UUID> LANTERN = ConcurrentHashMap.newKeySet();
    /** Gates shut in a barred player's face, by town: the gate's inside cell. */
    private static final Map<UUID, Set<Long>> SHUT_FOR = new ConcurrentHashMap<>();
    /** The night's closing time called, by town: the day. */
    private static final Map<UUID, Long> TIME_CALLED = new ConcurrentHashMap<>();
    /** The market's takings looked over, by town: the day. */
    private static final Map<UUID, Long> INSPECTED = new ConcurrentHashMap<>();
    /** By guard: the day off's spot. */
    private static final Map<UUID, BlockPos> REST_AT = new ConcurrentHashMap<>();

    static void resetForTests() {
        ROUTES.clear();
        WALKS.clear();
        PRESENCE.clear();
        LANTERN.clear();
        SHUT_FOR.clear();
        TIME_CALLED.clear();
        INSPECTED.clear();
        REST_AT.clear();
    }

    /** A guard's walk begun afresh (its duty changed): to the stop nearest it first. */
    static void forget(VillageFolkEntity g) {
        WALKS.remove(g.getUUID());
    }

    static Phase phase(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        if (t < 12000L) return Phase.DAY;
        if (t < 16800L) return Phase.EVENING;
        return Phase.NIGHT;
    }

    // ------------------------------------------------------------------ the routes

    /** The street before a building's door (by the town's plan), on the ground. */
    @Nullable
    static BlockPos streetBy(ServerLevel level, Villages.Village v, BlockPos anchor) {
        BlockPos c = v.centre();
        int[] s = Patrols.streetBy(anchor.getX() - c.getX(), anchor.getZ() - c.getZ());
        int x = s == null ? anchor.getX() : c.getX() + s[0], z = s == null ? anchor.getZ() : c.getZ() + s[1];
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (Math.abs(y - c.getY()) > 24) return null;
        return new BlockPos(x, y, z);
    }

    /** The places where trouble is, at this hour: the beat's own stops before its corners. */
    static List<Stop> hotSpots(ServerLevel level, Villages.Village v, Phase phase) {
        UUID id = v.id();
        List<Stop> out = new ArrayList<>();
        BlockPos c = v.centre();
        BlockPos square = new BlockPos(c.getX() + TownPlan.PLAZA - 4, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            c.getX() + TownPlan.PLAZA - 4, c.getZ()), c.getZ());
        BlockPos market = Villages.builtAt(id, "market"), tavern = Villages.builtAt(id, "tavern"), bank = Villages.builtAt(id, "bank");
        BlockPos depot = Villages.depot(level, id);
        if (phase == Phase.DAY) {
            if (market != null) add(level, v, out, market, "the market", "market");
            out.add(new Stop(square, "the square", "square"));
            if (depot != null) add(level, v, out, depot, "the stores", "stores");
            BlockPos quay = quay(id);
            if (quay != null) out.add(new Stop(quay, "the quay", "quay"));
            if (bank != null) add(level, v, out, bank, "the bank", "bank");
        } else if (phase == Phase.EVENING) {
            if (tavern != null) add(level, v, out, tavern, "the tavern", "tavern");
            out.add(new Stop(square, "the square", "square"));
            if (market != null) add(level, v, out, market, "the market", "market");
        } else {
            if (depot != null) add(level, v, out, depot, "the stores", "stores");
            if (bank != null) add(level, v, out, bank, "the bank", "bank");
            if (tavern != null) add(level, v, out, tavern, "the tavern", "tavern");
        }
        // After dark, and of an evening, the homes: a house in each quarter of the town.
        if (phase != Phase.DAY) {
            List<Ledger.Building> houses = new ArrayList<>();
            for (Ledger.Building b : Ledger.buildings(id)) {
                String s = b.structure();
                if (s.equals("house") || s.equals("house2") || s.equals("manor") || s.equals("villa") || s.equals("flats")) houses.add(b);
            }
            boolean[] taken = new boolean[8];
            for (Ledger.Building b : houses) {
                int sector = Patrols.sector(c, b.anchor(), 8);
                if (taken[sector]) continue;
                taken[sector] = true;
                add(level, v, out, b.anchor(), "the homes " + Mischief.compass(b.anchor().getX() - c.getX(), b.anchor().getZ() - c.getZ()) + " of the square", "homes");
            }
        }
        return out;
    }

    private static void add(ServerLevel level, Villages.Village v, List<Stop> out, BlockPos anchor, String name, String kind) {
        BlockPos at = streetBy(level, v, anchor);
        if (at == null) return;
        for (Stop s : out) if (s.at().distManhattan(at) < 5) return;
        String street = Police.streetAt(v.id(), v.centre(), at);
        out.add(new Stop(at, street == null || kind.equals("homes") ? name : name + ", on " + street, kind));
    }

    /** The fleet's quay, as the town keeps it (Fleet: "fleet.quay" x,y,z,...), or null. */
    @Nullable
    static BlockPos quay(UUID village) {
        String s = Ledger.note(village, "fleet.quay");
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        try {
            return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The routes for so many guards on the beat at this hour: the hot spots dealt round them (the square to all), each
     * with the corners of its own slice of the town between, in walking order round the heart. Kept till the town grows,
     * the beat's numbers change or the hour turns.
     */
    static List<List<Stop>> routes(ServerLevel level, Villages.Village v, int n, Phase phase) {
        n = Math.max(1, Math.min(6, n));
        String key = v.id() + "/" + n + "/" + phase;
        int buildings = Ledger.buildings(v.id()).size();
        long now = level.getGameTime();
        Routes r = ROUTES.get(key);
        if (r != null && r.buildings() == buildings && now - r.at() < 24000L && now >= r.at()) return r.routes();
        BlockPos c = v.centre();
        List<Stop> hot = hotSpots(level, v, phase);
        List<List<Stop>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new ArrayList<>());
        int k = 0;
        for (Stop s : hot) {
            if (s.kind().equals("square")) {
                for (List<Stop> route : out) route.add(s);
                continue;
            }
            out.get(k++ % n).add(s);
        }
        List<BlockPos> corners = Patrols.stops(level, v);
        if (!corners.isEmpty()) {
            List<List<BlockPos>> cut = Patrols.cut(c, corners, n);
            for (int i = 0; i < n; i++) {
                List<BlockPos> mine = cut.get(i);
                int step = Math.max(1, mine.size() / 4);
                for (int j = 0; j < mine.size(); j += step) {
                    BlockPos p = mine.get(j);
                    boolean near = false;
                    for (Stop s : out.get(i)) if (s.at().distManhattan(p) < 8) near = true;
                    if (near) continue;
                    String street = Police.streetAt(v.id(), c, p);
                    out.get(i).add(new Stop(p, street == null ? "the " + Mischief.compass(p.getX() - c.getX(), p.getZ() - c.getZ()) + " streets" : street, "street"));
                }
            }
        }
        for (List<Stop> route : out) {
            route.sort(Comparator.comparingDouble(s -> Math.atan2(s.at().getZ() - c.getZ(), s.at().getX() - c.getX())));
        }
        ROUTES.put(key, new Routes(out, n, phase, buildings, now));
        return out;
    }

    /** This guard's route now, or an empty list. */
    static List<Stop> routeOf(ServerLevel level, Villages.Village v, VillageFolkEntity g) {
        List<VillageFolkEntity> on = beatGuards(level, v);
        int k = Math.max(0, on.indexOf(g));
        List<List<Stop>> routes = routes(level, v, Math.max(1, on.size()), phase(level.getDayTime()));
        return routes.isEmpty() ? List.of() : routes.get(k % routes.size());
    }

    /** The guards walking a beat today: the beat's own, then the cases' and the escort's (who walk it between their work). */
    static List<VillageFolkEntity> beatGuards(ServerLevel level, Villages.Village v) {
        List<VillageFolkEntity> out = new ArrayList<>(Roster.on(level, v, Roster.Duty.BEAT));
        if (out.isEmpty()) {
            out.addAll(Roster.on(level, v, Roster.Duty.CASES));
            out.addAll(Roster.on(level, v, Roster.Duty.ESCORT));
            out.addAll(Roster.on(level, v, Roster.Duty.EVENT));
        }
        return out;
    }

    // ------------------------------------------------------------------ walking it

    /** The beat (Police.duty): on to the next stop, a stand and a look about there, the folk greeted, anything amiss seen to. */
    static boolean walk(VillageFolkEntity g, ServerLevel level, Villages.Village v) {
        if (g.movementBlocked() || g.villageCentre() == null) return false;
        List<Stop> route = routeOf(level, v, g);
        if (route.isEmpty()) return false;
        long gt = level.getGameTime();
        Phase phase = phase(level.getDayTime());
        lantern(level, v, g, phase != Phase.DAY && NightLight.dark(level.getDayTime()));
        Walk w = WALKS.computeIfAbsent(g.getUUID(), k -> new Walk());
        if (w.phase != phase || w.stop < 0 || w.stop >= route.size()) {
            w.phase = phase;
            int nearest = 0;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < route.size(); i++) {
                double d = route.get(i).at().distSqr(g.blockPosition());
                if (d < best) { best = d; nearest = i; }
            }
            w.stop = nearest;
            w.walking = true;
            w.legStart = gt;
        }
        Stop s = route.get(w.stop);
        if (gt - w.presenceAt >= 40) {
            w.presenceAt = gt;
            felt(v.id(), g.blockPosition(), gt);
        }
        if (w.walking) {
            // There: at the stop, or as near as its path goes (a stall or a cart stood on the very spot).
            boolean there = Mischief.near(g, s.at(), 2.5) || g.getNavigation().isDone() && Mischief.near(g, s.at(), 4.0) && gt - w.legStart > 20;
            if (!there && gt - w.legStart < 400) {
                Incidents.walk(g, s.at(), 0.75D);
                g.brain("walking the beat to " + s.name());
                return true;
            }
            w.walking = false;
            w.until = gt + 60 + g.getRandom().nextInt(60);
            g.getNavigation().stop();
            felt(v.id(), s.at(), gt);
            atStop(level, v, g, s, w, gt);
            return true;
        }
        if (gt < w.until) {
            if (g.getRandom().nextInt(3) == 0) {
                double a = g.getRandom().nextDouble() * Math.PI * 2.0;
                g.getLookControl().setLookAt(g.getX() + Math.cos(a) * 8.0, g.getEyeY(), g.getZ() + Math.sin(a) * 8.0);
            }
            if (gt - w.greeted > 400) greet(level, v, g, w, gt);
            return true;
        }
        w.stop = (w.stop + 1) % route.size();
        w.walking = true;
        w.legStart = gt;
        return true;
    }

    /** At a stop: what the place wants (closing time at the tavern, the takings on market day), and anything amiss. */
    private static void atStop(ServerLevel level, Villages.Village v, VillageFolkEntity g, Stop s, Walk w, long gt) {
        long now = level.getDayTime(), day = now / 24000L, tod = Math.floorMod(now, 24000L);
        UUID id = v.id();
        if (s.kind().equals("tavern") && tod >= 15000L && tod < 16800L && TIME_CALLED.getOrDefault(id, -1L) != day) {
            TIME_CALLED.put(id, day);
            closingTime(level, v, g, s.at());
        }
        if (s.kind().equals("market") && Market.marketDay(id, day)) inspect(level, v, g);
        // A wanted face about: after it.
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, g.getBoundingBox().inflate(16.0),
                o -> o != g && o.isAlive() && id.equals(o.ownerId()) && PlayerLaw.isWanted(id, o.getUUID()) && WatchHouse.custodyOf(o.getUUID()) == null)) {
            if (!g.hasLineOfSight(o)) continue;
            Incidents.Task t = Incidents.task(o);
            if (t != null && t.kind != Incidents.Kind.HIDE) continue;
            Incidents.startChase(level, v, g, o, PlayerLaw.bountyCase(id, o.getUUID()), "running from the watch", true);
            return;
        }
        // Somebody hurt: seen to.
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, g.getBoundingBox().inflate(10.0),
                o -> o != g && o.isAlive() && !o.isSleeping() && id.equals(o.ownerId()) && o.getHealth() < o.getMaxHealth() * 0.6F
                    && o.getTarget() == null && !Health.laidUp(o) && Incidents.task(o) == null && WatchHouse.custodyOf(o.getUUID()) == null)) {
            if (Incidents.aid(level, v, g, o)) return;
        }
        // After the curfew: whoever is out is sent home.
        if (curfew(id) && tod >= CURFEW_FROM && tod < CURFEW_TO) {
            for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, g.getBoundingBox().inflate(12.0),
                    o -> o != g && o.isAlive() && !o.isSleeping() && !o.isBaby() && id.equals(o.ownerId())
                        && o.stationTask() != AssistantEntity.StationTask.GUARD && Incidents.task(o) == null)) {
                if (curfewCheck(level, v, g, o) != null) return;
            }
        }
        PlayerLaw.onTheBeat(level, v, g);
    }

    /** Morning, Bree. A greeting by name, and a word about its troubles. */
    private static void greet(ServerLevel level, Villages.Village v, VillageFolkEntity g, Walk w, long gt) {
        if (g.getRandom().nextInt(3) != 0) return;
        VillageFolkEntity f = null;
        double best = 7.0 * 7.0;
        for (VillageFolkEntity x : level.getEntitiesOfClass(VillageFolkEntity.class, g.getBoundingBox().inflate(7.0),
                x -> x != g && x.isAlive() && !x.isSleeping() && x.stationTask() != AssistantEntity.StationTask.GUARD && v.id().equals(x.ownerId())
                    && x.talkPartner() == null && Incidents.task(x) == null)) {
            double d = x.distanceToSqr(g);
            if (d < best) { best = d; f = x; }
        }
        if (f == null) return;
        w.greeted = gt;
        String you = f.displayNameCap(), me = g.displayNameCap();
        var r = g.getRandom();
        long day = level.getDayTime() / 24000L;
        g.getLookControl().setLookAt(f, 30.0F, 30.0F);
        f.getLookControl().setLookAt(g, 30.0F, 30.0F);
        if (f.isBaby()) {
            FolkTalk.speak(g, level.isNight() ? "Bed, young " + you + ". The street's mine till morning." : FolkTalk.pick(r, "Mind how you go, " + you + ".",
                "Stay where folk can see you, " + you + "."));
            return;
        }
        String hello = level.isNight() ? "Evening" : level.getDayTime() % 24000L < 6000L ? "Morning" : "Afternoon";
        // Asked after its troubles: what is really the matter with it, if anything.
        boolean robbed = Crime.known(f.getUUID()) && day - Crime.folk(f.getUUID()).getLong("robbedDay") <= 3 && Crime.folk(f.getUUID()).contains("robbedDay");
        int mood = f.persona().mood();
        if (robbed) {
            FolkTalk.speak(g, hello + ", " + you + ". Any trouble since the robbery?");
            f.sayLater(FolkTalk.pick(r, "No. But I lock my door now. Any word on who did it?", "Not since. I'll sleep easier when you've got them."), 35);
            g.sayLater("We're on it, " + you + ". You'll be the first to know.", 80);
        } else if (f.meals().missedInRow() >= 2) {
            FolkTalk.speak(g, hello + ", " + you + ". You look peaky. Eaten today?");
            f.sayLater("Not since yesterday, " + me + ".", 35);
            g.sayLater("Get yourself to the stores. Say the watch sent you.", 80);
        } else if (mood < 40) {
            FolkTalk.speak(g, hello + ", " + you + ". Everything all right?");
            f.sayLater(FolkTalk.pick(r, "Could be better, " + me + ". Could be better.", "Don't ask. But thank you for asking."), 35);
        } else if (Police.trust(v.id()) < 40 && r.nextInt(2) == 0) {
            FolkTalk.speak(g, hello + ", " + you + ".");
            f.sayLater(FolkTalk.pick(r, "Locked anybody up for nothing today, " + me + "?", "Hmph. The watch."), 35);
        } else {
            FolkTalk.speak(g, FolkTalk.pick(r, hello + ", " + you + ". All well at home?", hello + ", " + you + ". Keeping out of trouble?",
                hello + ", " + you + ". Anything I should know about?"));
            f.sayLater(FolkTalk.pick(r, "All well, " + me + ", thanks.", "Quiet as you like, " + me + ".", "Good to see you about, " + me + "."), 35);
        }
    }

    // ------------------------------------------------------------------ the tavern at closing, the curfew

    /** Closing time: called at the tavern's door, and the worse for drink walked home by the guard (one a night). */
    static void closingTime(ServerLevel level, Villages.Village v, VillageFolkEntity g, BlockPos tavern) {
        FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Time, everybody! Home with you.", "Closing time! Off home, the lot of you.",
            "Drink up — the tavern's shutting."));
        long day = level.getDayTime() / 24000L;
        for (VillageFolkEntity o : level.getEntitiesOfClass(VillageFolkEntity.class, g.getBoundingBox().inflate(12.0),
                o -> o != g && o.isAlive() && v.id().equals(o.ownerId()) && "at the tavern".equals(o.hobbyNow()) && Incidents.task(o) == null)) {
            if (!inDrink(o, day)) continue;
            FolkTalk.speak(g, "Come on, " + o.displayNameCap() + ". I'll see you home. Mind the step.");
            o.sayLater(FolkTalk.pick(o.getRandom(), "I'm perfec'ly all right!", "One more song, Bram — jus' one!", "S'that the time already?"), 30);
            Incidents.walkHome(level, v, g, o, "the worse for drink");
            Police.log(v.id(), level.getDayTime(), "disorder", g.displayNameCap() + " walked " + o.displayNameCap() + " home from the tavern at closing, the worse for drink", g, 0);
            return;
        }
    }

    /** The worse for drink: two of the cellar's drinks tonight (Kitchen). */
    static boolean inDrink(VillageFolkEntity f, long day) {
        return Kitchen.today(f, day).drinks >= 2;
    }

    /** Is there a curfew on in the town? */
    static boolean curfew(@Nullable UUID village) {
        return village != null && (Police.town(village).getCompound("curfew").getBoolean("on") || LawBook.curfew(village));   // [identity] or its law-book's
    }

    static String curfewLine(UUID village) {
        CompoundTag c = Police.town(village).getCompound("curfew");
        if (!c.getBoolean("on")) return LawBook.curfew(village) ? "A curfew by the town's law: indoors after dark; the night beat sees to it, a warning, then a fine."
            : "No curfew.";
        return "A curfew since day " + c.getLong("since") + " (" + c.getString("why") + "): indoors from the tenth bell to first light; a warning, then a fine.";
    }

    /** The council calls (or lifts) the curfew. Returns what was done, in words. */
    static String setCurfew(ServerLevel level, Villages.Village v, boolean on, String why) {
        CompoundTag t = Police.town(v.id());
        CompoundTag c = t.getCompound("curfew");
        long day = level.getDayTime() / 24000L;
        if (c.getBoolean("on") == on) return on ? "There is a curfew already." : "There is no curfew.";
        c.putBoolean("on", on);
        c.putLong("since", day);
        c.putString("why", why);
        t.put("curfew", c);
        Police.changed();
        Villages.tell(v.id(), day, on ? "the council called a curfew: indoors from the tenth bell (" + why + ")" : "the council lifted the curfew");
        Police.log(v.id(), level.getDayTime(), "curfew", on ? "the council called a curfew, " + why : "the council lifted the curfew", null, 0);
        return on ? "CURFEW on: " + why : "CURFEW off";
    }

    /** The morning's look (Police.daily): three crimes or fights after dark in a week call a curfew; a quiet week lifts it. */
    static void weighCurfew(ServerLevel level, Villages.Village v, long day) {
        int dark = 0;
        for (Crime.Case c : Crime.cases(v.id())) {
            if (c.stage == Crime.Stage.UNNOTICED || day - c.day > 6) continue;
            if (c.hour >= 13000L || c.hour < 1000L) dark++;
        }
        for (CompoundTag one : Police.logSince(v.id(), day - 6)) {
            String k = one.getString("kind");
            long t = one.getLong("t");
            if ((k.equals("fight") || k.equals("disorder")) && (t >= 13000L || t < 1000L)) dark++;
        }
        CompoundTag c = Police.town(v.id()).getCompound("curfew");
        if (!c.getBoolean("on") && dark >= 3) setCurfew(level, v, true, dark + " crimes and fights after dark this week");
        else if (c.getBoolean("on") && dark == 0 && day - c.getLong("since") >= 7 && !c.getString("why").equals("by order")) setCurfew(level, v, false, "a quiet week");
    }

    /**
     * A folk out after curfew, met by the night beat: a warning and sent home the first time in a week, two coins' fine
     * the next. Null if it has every reason to be out (at home's door, at a gathering, a fire, on the road).
     */
    @Nullable
    static String curfewCheck(ServerLevel level, Villages.Village v, VillageFolkEntity g, VillageFolkEntity f) {
        if (!curfew(v.id())) return null;
        if (Assemblies.attending(f) || FireBrigade.onIt(f) || f.trip() != null || Patrols.away(f) || Health.laidUp(f)) return null;
        BlockPos home = Homes.homeOf(f);
        if (home == null) home = f.bedPos();
        if (home != null && home.distSqr(f.blockPosition()) < 10 * 10) return null;
        if (!NightLight.underSky(level, f)) return null;
        long day = level.getDayTime() / 24000L;
        CompoundTag r = Police.folk(f.getUUID());
        int n = day - r.getLong("curfewDay") > 6 ? 0 : r.getInt("curfewWeek");
        n++;
        r.putInt("curfewWeek", n);
        r.putLong("curfewDay", day);
        r.putInt("curfew", r.getInt("curfew") + 1);
        Police.changed();
        g.getLookControl().setLookAt(f, 30.0F, 30.0F);
        if (n == 1) {
            FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Curfew, " + f.displayNameCap() + ". Off home with you.", "You know there's a curfew, " + f.displayNameCap()
                + ". Home. Now."));
            f.sayLater(FolkTalk.pick(f.getRandom(), "Sorry, sorry. Just going.", "I lost track of the time!"), 30);
            Police.count(g, "warnings", 1);
            Police.log(v.id(), level.getDayTime(), "curfew", g.displayNameCap() + " warned " + f.displayNameCap() + " for being out after curfew", g, 0);
            Incidents.walkHome(level, v, null, f, "after curfew");
            return "warned";
        }
        FolkTalk.speak(g, "Out after curfew again, " + f.displayNameCap() + ". That's two coins. Home.");
        f.sayLater("That's not fair! ...All right.", 30);
        Incidents.fine(level, v, g, f, 2, "being out after curfew");
        Incidents.walkHome(level, v, null, f, "after curfew");
        return "fined";
    }

    // ------------------------------------------------------------------ presence: the beat felt

    private static long key(BlockPos p) {
        return BlockPos.asLong(p.getX() >> 2, 0, p.getZ() >> 2);
    }

    /** The watch was here, now (the beat, or a special constable on patrol). */
    static void felt(UUID village, BlockPos at, long gt) {
        Map<Long, Long> m = PRESENCE.computeIfAbsent(village, k -> new ConcurrentHashMap<>());
        m.put(key(at), gt);
        if (m.size() > 512) m.entrySet().removeIf(e -> gt - e.getValue() > 24000L || gt < e.getValue());
    }

    /** How fresh the watch's passing is round a spot: one just now, nought a minute ago or more (or never). */
    static double freshness(UUID village, BlockPos at, long gt) {
        Map<Long, Long> m = PRESENCE.get(village);
        if (m == null) return 0.0;
        double best = 0.0;
        int r = REACH >> 2;
        int cx = at.getX() >> 2, cz = at.getZ() >> 2;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                Long when = m.get(BlockPos.asLong(cx + dx, 0, cz + dz));
                if (when == null || gt < when) continue;
                best = Math.max(best, 1.0 - (gt - when) / (double) FRESH);
            }
        }
        return Math.max(0.0, best);
    }

    /** The chance a deed is done here, against the beat's passing (one with no beat about; a quarter just after it). */
    static double chanceAt(ServerLevel level, UUID village, BlockPos at) {
        return 1.0 - 0.75 * freshness(village, at, level.getGameTime());
    }

    /**
     * Mischief.deterrent: the beat has passed here lately and its boots are still warm on the stones, and the folk minded
     * to it thinks better of it (always within half a minute of it, less likely as the minute goes). Null otherwise.
     */
    @Nullable
    static String deters(ServerLevel level, VillageFolkEntity f, BlockPos to) {
        UUID village = f.ownerId();
        if (village == null || !Police.active()) return null;
        double fresh = freshness(village, to, level.getGameTime());
        if (fresh <= 0.0) return null;
        return fresh >= 0.5 || f.getRandom().nextDouble() < fresh * 1.5 ? "the beat" : null;
    }

    /**
     * Mischief.daily: a town whose beats walked its market, tavern, square and stores in the last day has fewer folk
     * tempted at all: a third fewer, all four walked.
     */
    static double cover(ServerLevel level, Villages.Village v) {
        if (!Police.active()) return 1.0;
        List<Stop> spots = new ArrayList<>();
        for (Phase p : Phase.values()) spots.addAll(hotSpots(level, v, p));
        if (spots.isEmpty()) return 1.0;
        long gt = level.getGameTime();
        Map<Long, Long> m = PRESENCE.get(v.id());
        if (m == null) return 1.0;
        int walked = 0;
        for (Stop s : spots) {
            // Walked near it (within a few strides, the watch in plain sight of it) in the last day.
            boolean near = false;
            int cx = s.at().getX() >> 2, cz = s.at().getZ() >> 2;
            for (int dx = -2; dx <= 2 && !near; dx++) {
                for (int dz = -2; dz <= 2 && !near; dz++) {
                    Long when = m.get(BlockPos.asLong(cx + dx, 0, cz + dz));
                    if (when != null && gt - when < 24000L && gt >= when) near = true;
                }
            }
            if (near) walked++;
        }
        return 1.0 - 0.35 * walked / spots.size();
    }

    // ------------------------------------------------------------------ the market's takings

    /** On market day, the takings looked over (once): a forged coin among them is found, and the case is the watch's. */
    static void inspect(ServerLevel level, Villages.Village v, VillageFolkEntity g) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        if (INSPECTED.getOrDefault(id, -1L) == day) return;
        INSPECTED.put(id, day);
        int forged = Market.stock(level, id, s -> s.is(com.jrpetty.mcassistant.McAssistantMod.FORGED_COIN.get()) && Crime.stolenCase(s) == 0);
        if (forged == 0) {
            Police.log(id, level.getDayTime(), "market", g.displayNameCap() + " checked the weights and the takings at the market: all true", g, 0);
            return;
        }
        FolkTalk.speak(g, "Hold on. This coin's never the town's. Who's been passing these?");
        for (Crime.Case c : Crime.open(id)) {
            if (c.kind != Crime.Kind.FORGERY) continue;
            if (c.stage == Crime.Stage.UNNOTICED) Crime.report(level, v, c, g.displayNameCap());
            // Cast of copper: a smelter's or a smith's work.
            List<Crime.Near> trades = new ArrayList<>();
            for (Crime.Near n : c.near) if (n.trade().equals("SMELT") || n.trade().equals("SMITH")) trades.add(n);
            Crime.Clue k = new Crime.Clue("market", g.displayNameCap() + " found a forged coin in the market's takings: cast of copper, a smelter's or a smith's work.",
                trades.isEmpty() ? 0.0 : 2.5 / trades.size(), day);
            for (Crime.Near n : trades) k.at(n.id(), n.name());
            c.clues.add(k);
            c.note(day, g.displayNameCap() + " found the forged coin in the market's takings on market day.");
            Crime.changed();
            break;
        }
        Police.log(id, level.getDayTime(), "market", g.displayNameCap() + " found " + forged + (forged == 1 ? " forged coin" : " forged coins")
            + " in the market's takings", g, 0);
    }

    // ------------------------------------------------------------------ the walls and the gate

    /** Wall and gate (Police.duty): a gate by day, a post on the wall by night (as the bell would send it). */
    static boolean walls(VillageFolkEntity g, ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<VillageFolkEntity> on = Roster.on(level, v, Roster.Duty.WALLS);
        int k = Math.max(0, on.indexOf(g));
        lantern(level, v, g, NightLight.dark(level.getDayTime()));
        if (level.isNight()) {
            List<Watch.Post> posts = Watch.posts(level, id);
            if (!posts.isEmpty()) {
                Raids.man(level, g, posts.get(k % posts.size()));
                g.brain("the night watch on the wall");
                return true;
            }
        } else if (g.post() != null) {
            Raids.leavePost(g);
        }
        List<Watch.Gate> gates = Watch.gates(level, id);
        BlockPos spot;
        Direction out;
        if (!gates.isEmpty()) {
            Watch.Gate gate = gates.get(k % gates.size());
            out = gate.out();
            spot = gate.inside().relative(out.getClockWise(), 2);
            PlayerLaw.atTheGate(level, v, g, gate);
        } else {
            out = Direction.from2DDataValue(k % 4);
            int reach = Math.max(TownPlan.PLAZA + 4, Villages.townReach(id) - 2);
            BlockPos c = v.centre().relative(out, reach).relative(out.getClockWise(), TownPlan.AVENUE + 1);
            spot = new BlockPos(c.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()), c.getZ());
        }
        if (!Mischief.near(g, spot, 1.5)) {
            Incidents.walk(g, spot, 0.8D);
            g.brain("to the " + out.getName() + " gate");
            return true;
        }
        g.getNavigation().stop();
        BlockPos look = spot.relative(out, 10);
        if (g.getRandom().nextInt(4) == 0) g.getLookControl().setLookAt(look.getX() + 0.5, look.getY() + 1.5, look.getZ() + 0.5);
        felt(id, spot, level.getGameTime());
        g.brain("on the " + out.getName() + " gate");
        return true;
    }

    /** Is this guard on the wall by the roster's leave (Raids.guardDuty keeps it there between the bells)? */
    static boolean onTheWall(VillageFolkEntity g) {
        return Police.active() && g.level() instanceof ServerLevel level && level.isNight() && Roster.dutyOf(level, g) == Roster.Duty.WALLS;
    }

    /** Shut a gate's doors (or open them again), for one the town has barred. */
    static void gate(ServerLevel level, Villages.Village v, Watch.Gate gate, boolean shut) {
        Set<Long> s = SHUT_FOR.computeIfAbsent(v.id(), k -> ConcurrentHashMap.newKeySet());
        long key = gate.inside().asLong();
        if (shut == s.contains(key)) return;
        if (!shut && (Watch.isShut(v.id()) || level.isNight() || Raids.underAlarm(v.id()))) {
            s.remove(key);
            return;
        }
        for (BlockPos d : gate.doors()) {
            BlockState st = level.getBlockState(d);
            if (!(st.getBlock() instanceof DoorBlock door) || st.getValue(DoorBlock.OPEN) == !shut) continue;
            door.setOpen(null, level, st, d, !shut);
        }
        if (shut) s.add(key);
        else s.remove(key);
    }

    // ------------------------------------------------------------------ the day off

    /** A guard's day off (Police.duty): by day at its ease about the town (home, the park, the tavern of an evening). */
    static boolean rest(VillageFolkEntity g, ServerLevel level, Villages.Village v) {
        if (level.isNight()) return false;
        lantern(level, v, g, false);
        BlockPos spot = REST_AT.get(g.getUUID());
        long day = level.getDayTime() / 24000L;
        if (spot == null || g.getRandom().nextInt(600) == 0) {
            UUID id = v.id();
            List<BlockPos> places = new ArrayList<>();
            BlockPos home = Homes.homeOf(g);
            if (home != null) places.add(home);
            BlockPos park = Villages.builtAt(id, Park.STRUCTURE);
            if (park != null) places.add(park);
            BlockPos cafe = Villages.builtAt(id, "cafe");
            if (cafe != null) places.add(cafe);
            places.add(v.centre().relative(Direction.WEST, 6));
            BlockPos pick = places.get(Math.floorMod(g.getUUID().hashCode() + (int) day, places.size()));
            spot = streetBy(level, v, pick);
            if (spot == null) spot = pick;
            REST_AT.put(g.getUUID(), spot);
        }
        g.hobbyNow = "resting on its day off";
        g.lastLeisureTick = g.tickCount;
        if (!Mischief.near(g, spot, 2.0)) Incidents.walk(g, spot, 0.6D);
        else if (g.getRandom().nextInt(80) == 0) {
            double a = g.getRandom().nextDouble() * Math.PI * 2.0;
            g.getNavigation().moveTo(spot.getX() + 0.5 + Math.cos(a) * 3, spot.getY(), spot.getZ() + 0.5 + Math.sin(a) * 3, 0.5D);
        }
        return true;
    }

    // ------------------------------------------------------------------ the lantern on the night beat

    /** A lantern in its free hand on the night beat (the stores', or its own torch), the shield put in its pack; and away at dawn. */
    static void lantern(ServerLevel level, Villages.Village v, VillageFolkEntity g, boolean want) {
        ItemStack off = g.getItemBySlot(EquipmentSlot.OFFHAND);
        if (want) {
            if (NightLight.isLight(off)) {
                LANTERN.add(g.getUUID());
                return;
            }
            if (g.countCarried(s -> s.is(Items.LANTERN)) == 0) {
                ItemStack l = Crafts.takeOne(level, v, s -> s.is(Items.LANTERN));
                if (!l.isEmpty()) {
                    ItemStack left = g.insertItem(l);
                    if (!left.isEmpty()) Crafts.store(level, v, left);
                }
            }
            if (g.countCarried(NightLight::isLight) == 0) return;
            if (!off.isEmpty()) {
                ItemStack left = g.insertItem(off.copy());
                if (!left.isEmpty()) return;                                      // a full pack: the shield stays
                g.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            }
            LANTERN.add(g.getUUID());
            NightLight.takeOut(g);
            return;
        }
        if (!LANTERN.remove(g.getUUID())) return;
        if (NightLight.isLight(off)) NightLight.putAway(g, off);
        if (g.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) {
            for (ItemStack s : g.getInventoryItems()) {
                if (!s.is(Items.SHIELD)) continue;
                ItemStack one = s.copyWithCount(1);
                if (g.removeMatching(x -> x.is(Items.SHIELD), 1) == 1) g.setItemSlot(EquipmentSlot.OFFHAND, one);
                break;
            }
        }
    }

    /** Is this guard carrying its lantern on the night beat (NightLight leaves it in its hand)? */
    static boolean lanternOut(VillageFolkEntity g) {
        return LANTERN.contains(g.getUUID());
    }

    // ------------------------------------------------------------------ telling

    /** "the market, the square, Mill Lane and the stores": this guard's route now. */
    static String routeWords(VillageFolkEntity g) {
        if (!(g.level() instanceof ServerLevel level) || g.ownerId() == null) return "the town";
        Villages.Village v = Villages.get(g.ownerId());
        if (v == null) return "the town";
        List<String> names = new ArrayList<>();
        for (Stop s : routeOf(level, v, g)) {
            if (names.size() >= 5) break;
            names.add(s.name());
        }
        return names.isEmpty() ? "the town" : Civics.names(names);
    }

    /** The beats in words, for the books: each guard on one, its route at this hour. */
    static List<String> describe(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        Phase phase = phase(level.getDayTime());
        List<VillageFolkEntity> on = beatGuards(level, v);
        List<List<Stop>> routes = routes(level, v, Math.max(1, on.size()), phase);
        for (int i = 0; i < routes.size(); i++) {
            List<String> names = new ArrayList<>();
            for (Stop s : routes.get(i)) names.add(s.name());
            String who = i < on.size() ? on.get(i).displayNameCap() + "'s beat" : "Beat " + (i + 1);
            out.add(who + " (" + phase.name().toLowerCase(java.util.Locale.ROOT) + "): " + (names.isEmpty() ? "the town's streets" : String.join(", ", names)) + ".");
        }
        return out;
    }

    /** Every second (Police.tick): the special constables on patrol felt where they walk. */
    static void tick(ServerLevel level, Villages.Village v) {
        long gt = level.getGameTime();
        for (Player p : level.players()) {
            if (!PlayerLaw.onPatrol(v.id(), p.getUUID())) continue;
            if (p.blockPosition().distSqr(v.centre()) > (double) (Villages.townReach(v.id()) + 16) * (Villages.townReach(v.id()) + 16)) continue;
            felt(v.id(), p.blockPosition(), gt);
        }
    }

    // ------------------------------------------------------------------ the tests

    static List<Stop> routeForTests(ServerLevel level, VillageFolkEntity g) {
        Villages.Village v = g.ownerId() == null ? null : Villages.get(g.ownerId());
        return v == null ? List.of() : routeOf(level, v, g);
    }
}
