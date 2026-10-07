package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Districts;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [batchF] The quarters' wardens. Each quarter of the town that has homes in it (the market quarter, the
 * craft quarter, the homes quarter: Districts) has a warden: whichever of the folk living there the rest
 * of the town thinks most of (never the elder, who has the whole town to see to, nor the watch). The
 * chronicle says who, and the folk's card.
 *
 * <p>Of an evening, after work, the warden walks its quarter's streets: a round of the street corners in
 * it, looking about at each. A door whose way in a folk cannot walk (DoorWays.walkable) is sent to the
 * town's works and mended; a stretch of street with no light on it after dark is reported, and a hand on
 * the town's works puts up a lamp at its edge (a fence post and a torch out of the stores); anything left
 * lying about (not a player's) it picks up as it goes and carries to the stores at the end of its round.
 * And two neighbours of its quarter who cannot abide each other, or who have had words that day, it goes
 * and talks round: they shake hands, and think a little better of each other for it. What it found and
 * did is kept in the town's books (the News page).
 */
public final class Wardens {

    private Wardens() {}

    /** The quarters that have wardens. */
    static final Districts.District[] QUARTERS = { Districts.District.MARKET, Districts.District.CRAFTS, Districts.District.HOMES };
    /** Block light under which a street counts as dark after nightfall. */
    static final int DARK = 7;
    /** How far round each stop the warden looks. */
    static final int LOOK = 10;

    /** An evening's round: its quarter, its stops, how far on, what it found, and what it has picked up. */
    static final class Walk {
        final Districts.District quarter;
        final List<BlockPos> stops;
        final List<ItemStack> sack = new ArrayList<>();
        final List<String> found = new ArrayList<>();
        int at, since, stopSince;
        boolean settled;
        @Nullable UUID[] quarrel;

        Walk(Districts.District quarter, List<BlockPos> stops, int since) {
            this.quarter = quarter;
            this.stops = stops;
            this.since = this.stopSince = since;
        }
    }

    private static final Map<UUID, Walk> WALKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> APPOINTED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        WALKS.clear();
        APPOINTED.clear();
    }

    // ------------------------------------------------------------------ who

    /** The warden of each quarter, as the town last chose them (and is told in the chronicle when it changes). */
    static Map<Districts.District, VillageFolkEntity> appoint(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Map<Districts.District, VillageFolkEntity> out = new EnumMap<>(Districts.District.class);
        List<VillageFolkEntity> grown = Civics.grown(id);
        if (grown.size() < 4) return out;
        CompoundTag kept = Civics.sub(Civics.town(id), "wardens");
        long day = Civics.day(level);
        for (Districts.District d : QUARTERS) {
            VillageFolkEntity best = null;
            int bestScore = Integer.MIN_VALUE;
            for (VillageFolkEntity f : grown) {
                BlockPos home = Civics.home(f);
                if (home == null || f.isElder() || f.stationTask() == AssistantEntity.StationTask.GUARD) continue;
                if (Quarters.districtOf(id, v.centre(), home) != d) continue;
                int s = 0;
                for (VillageFolkEntity o : grown) if (o != f) s += o.life().affinity(f.getUUID());
                if (f.getUUID().toString().equals(kept.getString(d.name()))) s += 15;      // a warden is not turned out for a point or two
                if (s > bestScore) { bestScore = s; best = f; }
            }
            if (best == null) continue;
            out.put(d, best);
            if (!best.getUUID().toString().equals(kept.getString(d.name()))) {
                kept.putString(d.name(), best.getUUID().toString());
                Civics.changed();
                Villages.tell(id, day, best.displayNameCap() + " was made warden of " + d.words);
                best.persona().remember(day, "the town made me warden of " + d.words, 5);
            }
        }
        return out;
    }

    /** "/village civic wardens": the wardens chosen now, and each sent on its round. */
    public static String appointNow(ServerLevel level, Villages.Village v) {
        List<String> who = new ArrayList<>();
        for (Map.Entry<Districts.District, VillageFolkEntity> e : appoint(level, v).entrySet()) {
            boolean out = startWalk(level, v, e.getValue(), e.getKey());
            who.add(e.getValue().displayNameCap() + " (" + e.getKey().words + (out ? ", out on its round" : "") + ")");
        }
        return who.isEmpty() ? "no quarter has folk enough living in it for a warden" : String.join(", ", who);
    }

    /** The quarter this folk is warden of, or null. */
    @Nullable
    static Districts.District quarterOf(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return null;
        CompoundTag kept = Civics.sub(Civics.town(id), "wardens");
        for (Districts.District d : QUARTERS) if (f.getUUID().toString().equals(kept.getString(d.name()))) return d;
        return null;
    }

    // ------------------------------------------------------------------ the round of the town

    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = Civics.day(level), t = level.getDayTime() % 24000L;
        if (t >= 1000 && APPOINTED.getOrDefault(id, -1L) != day) {
            APPOINTED.put(id, day);
            appoint(level, v);
        }
        if (t >= 11200 && t < 12900) {
            CompoundTag kept = Civics.sub(Civics.town(id), "wardens");
            for (Districts.District d : QUARTERS) {
                VillageFolkEntity w = Civics.find(level, Post.uuid(kept, d.name()));
                if (w == null || WALKS.containsKey(w.getUUID()) || !id.equals(w.ownerId())) continue;
                if (Civics.folk(w.getUUID()).getLong("walked") == day + 1 || !Civics.free(w)) continue;
                startWalk(level, v, w, d);
            }
        }
        lights(level, v);
    }

    /** The round: the street corners of its quarter, a handful, spread round it, and home by the square. */
    static boolean startWalk(ServerLevel level, Villages.Village v, VillageFolkEntity w, Districts.District d) {
        List<BlockPos> stops = stops(level, v, d);
        if (stops.isEmpty()) return false;
        WALKS.put(w.getUUID(), new Walk(d, stops, w.tickCount));
        Civics.folk(w.getUUID()).putLong("walked", Civics.day(level) + 1);
        Civics.changed();
        FolkTalk.speak(w, FolkTalk.pick(w.getRandom(), "Time for my round of " + d.words + ".", "Off round the quarter."));
        return true;
    }

    /** Up to five street corners of the quarter (within the town), spread round it. */
    static List<BlockPos> stops(ServerLevel level, Villages.Village v, Districts.District d) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        List<int[]> corners = TownLife.corners(Villages.townReach(id));
        List<BlockPos> mine = new ArrayList<>();
        for (int[] c : corners) {
            BlockPos p = new BlockPos(heart.getX() + c[0], heart.getY(), heart.getZ() + c[1]);
            if (Quarters.districtOf(id, heart, p) != d) {
                // A corner is on the quarter's edge as often as in it: a step into the quarter from it counts too.
                boolean near = false;
                for (int[] s : new int[][]{ { 4, 4 }, { -4, 4 }, { 4, -4 }, { -4, -4 } }) {
                    if (Quarters.districtOf(id, heart, p.offset(s[0], 0, s[1])) == d) near = true;
                }
                if (!near) continue;
            }
            if (!level.hasChunk(p.getX() >> 4, p.getZ() >> 4)) continue;
            mine.add(new BlockPos(p.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ()));
        }
        if (mine.size() > 5) {
            List<BlockPos> spread = new ArrayList<>();
            for (int i = 0; i < 5; i++) spread.add(mine.get(i * mine.size() / 5));
            mine = spread;
        }
        return mine;
    }

    // ------------------------------------------------------------------ the folk's part

    @Nullable
    static String hold(VillageFolkEntity f, ServerLevel level) {
        Walk w = WALKS.get(f.getUUID());
        if (w == null) return null;
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || f.tickCount - w.since > 4800 || f.tickCount < w.since || Raids.underAlarm(id) || f.isSleeping()) {
            end(level, f, w, v, false);
            return null;
        }
        if (w.quarrel != null) return talkRound(f, level, w);
        if (w.at >= w.stops.size()) {
            // Home by the square, with whatever it picked up, into the stores.
            boolean back = Civics.goTo(f, v.centre(), 4.0, 0.8);
            if (!back && f.tickCount - w.stopSince < 1200) return "back from its round of " + w.quarter.words;
            end(level, f, w, v, back);
            return null;
        }
        BlockPos stop = w.stops.get(w.at);
        if (!Civics.goTo(f, stop, 3.0, 0.75) && f.tickCount - w.stopSince < 900) return "walking " + w.quarter.words + " as its warden";
        inspect(level, v, f, w, stop);
        w.at++;
        w.stopSince = f.tickCount;
        if (!w.settled) {
            UUID[] q = quarrel(level, v, w.quarter, Civics.day(level));
            if (q != null) {
                w.quarrel = q;
                w.settled = true;
            }
        }
        return "walking " + w.quarter.words + " as its warden";
    }

    /** What the warden sees at one stop of its round, and what it does about it. */
    static void inspect(ServerLevel level, Villages.Village v, VillageFolkEntity f, Walk w, BlockPos at) {
        UUID id = v.id();
        BlockPos heart = v.centre();
        // Doors nobody can walk in at: to the town's works.
        for (Ledger.Building b : Ledger.buildings(id)) {
            if (b.anchor().distSqr(at) > (LOOK + 8) * (LOOK + 8) || Quarters.districtOf(id, heart, b) != w.quarter) continue;
            for (DoorWays.Door d : DoorWays.doorsOf(level, b)) {
                if (DoorWays.walkable(level, d)) continue;
                DoorWays.mend(level, v, d, 8);
                String where = Homes.isHome(b.structure()) ? Petitions.where(id, heart, b) : Villages.spoken(b.structure());
                w.found.add("a blocked door at " + where);
                FolkTalk.speak(f, "Nobody can get in at this door. I'll have the works see to it.");
            }
        }
        // A dark stretch of street: a lamp wanted.
        int reach = Villages.townReach(id);
        for (int dx = -LOOK; dx <= LOOK; dx += 3) {
            for (int dz = -LOOK; dz <= LOOK; dz += 3) {
                int x = at.getX() + dx, z = at.getZ() + dz;
                int ox = x - heart.getX(), oz = z - heart.getZ();
                if (Math.max(Math.abs(ox), Math.abs(oz)) > reach || !TownPlan.isStreet(ox, oz)) continue;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (level.getBrightness(LightLayer.BLOCK, p) >= DARK || wanted(id, p)) continue;
                BlockPos edge = edge(level, heart, p);
                if (edge == null) continue;
                Civics.list(Civics.town(id), "dark").add(LongTag.valueOf(edge.asLong()));
                w.found.add("a dark corner of " + w.quarter.words);
                Civics.changed();
            }
        }
        // Litter: picked up as it goes, for the stores (never a player's own).
        AABB box = new AABB(at).inflate(LOOK, 4, LOOK);
        int picked = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box, e -> e.isAlive() && e.onGround() && !Sweepers.playersOwn(e) && e.getAge() > 200)) {
            w.sack.add(e.getItem().copy());
            picked += e.getItem().getCount();
            e.discard();
        }
        if (picked > 0) {
            w.found.add("litter picked up (" + picked + ")");
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Who left this lying about?", "Tidy streets, tidy town."));
        }
    }

    /** Is a lamp wanted near here already? */
    private static boolean wanted(UUID village, BlockPos p) {
        for (Tag t : Civics.list(Civics.town(village), "dark")) {
            if (t instanceof LongTag l && BlockPos.of(l.getAsLong()).distSqr(p) < 7 * 7) return true;
        }
        return false;
    }

    /** Beside the street, off it, where a lamp can stand without being in anybody's way. */
    @Nullable
    private static BlockPos edge(ServerLevel level, BlockPos heart, BlockPos street) {
        for (int r = 1; r <= 3; r++) {
            for (int[] s : new int[][]{ { r, 0 }, { -r, 0 }, { 0, r }, { 0, -r } }) {
                int x = street.getX() + s[0], z = street.getZ() + s[1];
                if (TownPlan.isStreet(x - heart.getX(), z - heart.getZ()) || TownPlan.isSquare(x - heart.getX(), z - heart.getZ())) continue;
                BlockPos p = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (Math.abs(p.getY() - street.getY()) <= 1 && Petitions.clear(level, p) && level.getBlockState(p.above(2)).canBeReplaced()) return p;
            }
        }
        return null;
    }

    /** The lamps the wardens asked for, put up by a hand on the town's works: one a look. */
    static void lights(ServerLevel level, Villages.Village v) {
        ListTag dark = Civics.list(Civics.town(v.id()), "dark");
        if (dark.isEmpty()) return;
        if (!(dark.get(0) instanceof LongTag l)) { dark.remove(0); return; }
        BlockPos at = BlockPos.of(l.getAsLong());
        if (!level.isLoaded(at)) return;
        if (!Petitions.clear(level, at) || level.getBrightness(LightLayer.BLOCK, at) >= DARK + 4) {
            dark.remove(0);                                      // something stands there now, or it is lit already
            Civics.changed();
            return;
        }
        if (!Petitions.afford(level, v, Petitions.Kind.LIGHT)) return;
        if (!TownJobs.atWork(level, v, "wardens", at, "putting up a street lamp the warden asked for")) return;
        if (Petitions.lamp(level, v, at)) {
            dark.remove(0);
            Civics.changed();
            CompoundTag t = Civics.town(v.id());
            t.putInt("lamps", t.getInt("lamps") + 1);
        }
    }

    // ------------------------------------------------------------------ quarrels

    /** Two of this quarter who cannot abide each other (or had words today): {one, the other}, or null. */
    @Nullable
    static UUID[] quarrel(ServerLevel level, Villages.Village v, Districts.District d, long day) {
        List<VillageFolkEntity> here = new ArrayList<>();
        for (VillageFolkEntity f : Civics.grown(v.id())) {
            BlockPos home = Civics.home(f);
            if (home != null && Quarters.districtOf(v.id(), v.centre(), home) == d && !f.isSleeping()) here.add(f);
        }
        for (VillageFolkEntity a : here) {
            for (VillageFolkEntity b : here) {
                if (a == b) continue;
                boolean words = a.quarrelledOn() >= day - 1 && b.quarrelledOn() >= day - 1;
                if (a.life().affinity(b.getUUID()) <= Social.RIVAL || words && a.life().affinity(b.getUUID()) < 0) {
                    return new UUID[]{ a.getUUID(), b.getUUID() };
                }
            }
        }
        return null;
    }

    /** To the two of them, and a word with each: they shake on it. */
    private static String talkRound(VillageFolkEntity w, ServerLevel level, Walk walk) {
        VillageFolkEntity a = Civics.find(level, walk.quarrel[0]), b = Civics.find(level, walk.quarrel[1]);
        if (a == null || b == null || w.tickCount - walk.stopSince > 1200) {
            walk.quarrel = null;
            return "walking " + walk.quarter.words + " as its warden";
        }
        if (w.distanceToSqr(a) > 3.5 * 3.5) {
            Civics.goTo(w, a.blockPosition(), 2.5, 0.85);
            return "going to settle a quarrel between " + a.displayNameCap() + " and " + b.displayNameCap();
        }
        settle(level, w, a, b);
        walk.found.add("settled a quarrel between " + a.displayNameCap() + " and " + b.displayNameCap());
        walk.quarrel = null;
        walk.stopSince = w.tickCount;
        return "settling a quarrel";
    }

    /** The warden's word: both think the better of each other for it, and remember who made the peace. */
    static void settle(ServerLevel level, VillageFolkEntity w, VillageFolkEntity a, VillageFolkEntity b) {
        long day = Civics.day(level);
        w.getLookControl().setLookAt(a, 30.0F, 30.0F);
        FolkTalk.speak(w, FolkTalk.pick(w.getRandom(), "Now then, you two. Neighbours shouldn't be at odds. Shake on it.",
            "Enough of this. Whatever it was, it's not worth it. Shake hands."));
        a.sayLater(FolkTalk.pick(a.getRandom(), "...Fine. Sorry, " + b.displayNameCap() + ".", "All right. For the street's sake."), 40);
        b.sayLater(FolkTalk.pick(b.getRandom(), "Sorry too. Truce?", "Fair enough. Shake."), 80);
        a.life().feel(b.getUUID(), b.displayNameCap(), 15);
        b.life().feel(a.getUUID(), a.displayNameCap(), 15);
        a.life().feel(w.getUUID(), w.displayNameCap(), 4);
        b.life().feel(w.getUUID(), w.displayNameCap(), 4);
        a.persona().remember(day, w.displayNameCap() + " the warden made peace between " + b.displayNameCap() + " and me", 3);
        b.persona().remember(day, w.displayNameCap() + " the warden made peace between " + a.displayNameCap() + " and me", 3);
        CompoundTag me = Civics.folk(w.getUUID());
        me.putInt("settled", me.getInt("settled") + 1);
        Civics.changed();
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, a.getX(), a.getY() + 2.1, a.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER, b.getX(), b.getY() + 2.1, b.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
    }

    /**
     * The round over: what it picked up into the stores, if it has got back to the heart with it (else into its
     * own pack, to go in with its things, and what will not fit set down at its feet); what it found, into the books.
     */
    static void end(ServerLevel level, VillageFolkEntity f, Walk w, @Nullable Villages.Village v, boolean home) {
        WALKS.remove(f.getUUID());
        for (ItemStack s : w.sack) {
            if (home && v != null) TownWork.give(level, v, s);
            ItemStack left = s.isEmpty() ? ItemStack.EMPTY : f.insertItem(s);
            if (!left.isEmpty()) f.spawnAtLocation(left);
        }
        w.sack.clear();
        CompoundTag me = Civics.folk(f.getUUID());
        me.putInt("walks", me.getInt("walks") + 1);
        me.putInt("reports", me.getInt("reports") + w.found.size());
        if (v != null && !w.found.isEmpty()) {
            ListTag log = Civics.list(Civics.town(v.id()), "wardenLog");
            log.add(StringTag.valueOf("Day " + (Civics.day(level) + 1) + ": " + f.displayNameCap() + " (" + w.quarter.words + "): "
                + String.join("; ", w.found)));
            while (log.size() > 8) log.remove(0);
        }
        Civics.changed();
    }

    // ------------------------------------------------------------------ where the player sees it

    static String cardLine(VillageFolkEntity f) {
        Districts.District d = quarterOf(f);
        if (d == null) return "";
        CompoundTag t = Civics.folk(f.getUUID());
        return "warden of " + d.words + " (" + t.getInt("walks") + " evening rounds, " + t.getInt("reports") + " things seen to"
            + (t.getInt("settled") > 0 ? ", " + t.getInt("settled") + " quarrels settled" : "") + ")";
    }

    static List<String> book(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        CompoundTag kept = Civics.sub(Civics.town(village), "wardens");
        List<String> who = new ArrayList<>();
        for (Districts.District d : QUARTERS) {
            String id = kept.getString(d.name());
            if (id.isEmpty()) continue;
            String name = Civics.folk(UUID.fromString(id)).getString("name");
            who.add((name.isEmpty() ? "?" : name) + " (" + d.words + ")");
        }
        if (!who.isEmpty()) out.add("Wardens: " + String.join(", ", who) + ".");
        ListTag log = Civics.list(Civics.town(village), "wardenLog");
        for (int i = log.size() - 1; i >= 0 && i >= log.size() - 4; i--) out.add("  " + log.getString(i));
        return out;
    }

    // ------------------------------------------------------------------ the tests

    /** Tests: the wardens chosen now, by quarter. */
    public static Map<Districts.District, VillageFolkEntity> appointForTests(ServerLevel level, Villages.Village v) {
        return appoint(level, v);
    }

    /** Tests: what a warden sees at this spot of its quarter (and does about it) — what it found. */
    public static List<String> inspectForTests(ServerLevel level, Villages.Village v, VillageFolkEntity f, Districts.District d, BlockPos at) {
        Walk w = new Walk(d, List.of(at), f.tickCount);
        inspect(level, v, f, w, at);
        end(level, f, w, v, true);
        return w.found;
    }

    /** Tests: the lamps the wardens asked for, a look by the town's works now. */
    public static int lightsForTests(ServerLevel level, Villages.Village v) {
        int before = Civics.list(Civics.town(v.id()), "dark").size();
        lights(level, v);
        return before - Civics.list(Civics.town(v.id()), "dark").size();
    }

    /** Tests: the lamps wanted, where. */
    public static List<BlockPos> darkForTests(UUID village) {
        List<BlockPos> out = new ArrayList<>();
        for (Tag t : Civics.list(Civics.town(village), "dark")) if (t instanceof LongTag l) out.add(BlockPos.of(l.getAsLong()));
        return out;
    }

    /** Tests: a quarrel in the quarter, settled now by its warden: the two, or null if none. */
    @Nullable
    public static UUID[] settleForTests(ServerLevel level, Villages.Village v, VillageFolkEntity w, Districts.District d) {
        UUID[] q = quarrel(level, v, d, Civics.day(level));
        if (q == null) return null;
        VillageFolkEntity a = Civics.find(level, q[0]), b = Civics.find(level, q[1]);
        if (a == null || b == null) return null;
        settle(level, w, a, b);
        return q;
    }

    /** Tests: is this warden out on its round? */
    public static boolean walkingForTests(VillageFolkEntity f) {
        return WALKS.containsKey(f.getUUID());
    }
}
