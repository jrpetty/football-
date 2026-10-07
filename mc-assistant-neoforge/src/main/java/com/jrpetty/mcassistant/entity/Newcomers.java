package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [civic] Newcomers and refugees: folk who come to a town from somewhere else and ask to be taken in.
 *
 * <p><b>Who comes.</b> From the other towns of the world: a household of a town worn out by its war (as weary of it
 * as folk get) or one in famine for two mornings running, once in five days at most, never the leader, the watch or
 * the builder at its build, and never below a town of eight; and, through the seam left for them, a household burnt
 * or flooded out of its home (displaced). From the world outside: now and then (one day in fourteen, no more often
 * than once in ten days) a lone hand or a small family comes to a town of fifteen at peace, fleeing a war, a famine or
 * a flood far away, bringing the trades the town has nobody at. So towns still grow mostly by their own children.
 *
 * <p><b>On the road.</b> Folk of another town really leave it: off its roll (and nobody's, for now), their bed and
 * their plot given up, carrying what is their own. They walk the road to the nearest town at peace (the ground kept
 * awake round each as it goes, as for anybody on the road) and camp at its edge by the way they came in.
 *
 * <p><b>The vote.</b> The town is asked (Referendums) the day they come, or the next if they come after noon; the board
 * says "A family of four from Oakwick, fleeing the war, ask to settle". Each grown folk weighs it by the room the town
 * has (its beds against its people), its food, its mood and its leader's temper, and by its own nature: a generous
 * soul says aye, a wary one nay; a friend among them, aye at once; folk who would gain a trade the town lacks, aye.
 *
 * <p><b>Taken in.</b> They are the town's folk: on its roll, a home found (an empty house, else a bed at the camp, and
 * a house wanted), each at the trade it knows best that the town is short of, its levels and its nature its own.
 * Grateful for the first days; within a week each has either made a friend of a neighbour or had words with one,
 * by their natures. <b>Turned away</b>, they go on to the next town that might have them, and the town they came from
 * thinks the worse of the one that sent them on; with nowhere left, folk of another town go home, war or no war, and
 * folk from outside go back out into the world. The chronicle has all of it, and the books keep a line for each.
 */
public final class Newcomers {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private Newcomers() {}

    /** The mark a newcomer on the road or at a town's edge carries (an entity tag, saved with it). */
    public static final String TAG = "mca_newcomer";
    /** Where it keeps which party it is of, in its own saved data. */
    private static final String DATA = "mca_newcomer";

    /** Why they left. */
    public enum Cause {
        WAR("fleeing the war", "the war"), FAMINE("fleeing the famine", "the famine"), FIRE("burnt out of their homes", "the fire"),
        FLOOD("flooded out of their homes", "the flood"), OUTSIDE("from far away", "trouble far away");

        public final String words, what;

        Cause(String words, String what) {
            this.words = words;
            this.what = what;
        }

        @Nullable
        static Cause named(String s) {
            try {
                return valueOf(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** A town of its own at its lowest: no household leaves one smaller than this. */
    static final int KEEP = Contentment.KEEP_AT_LEAST;
    /** Days between one household's leaving a town and the next's; days between parties from outside to one town. */
    static final int LEAVE_EVERY = 5, OUTSIDE_EVERY = 10;
    /** One day in so many, a party from outside comes to a town that might have it. */
    static final int OUTSIDE_ODDS = 14;
    /** A party camped this many days with nothing decided goes on. */
    static final int CAMP_MOST = 3;

    /** The walk each newcomer is on, and the ground kept awake round it (not saved: taken up afresh). */
    private static final Map<UUID, Visitors.Walk> WALKS = new ConcurrentHashMap<>();
    private static final Map<UUID, BlockPos> WINDOWS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SAID = new ConcurrentHashMap<>();

    static void resetForTests() {
        WALKS.clear();
        WINDOWS.clear();
        SAID.clear();
    }

    // ------------------------------------------------------------------ the parties

    static CompoundTag party(String id) {
        return CivicRecord.sub(CivicRecord.parties(), id);
    }

    static boolean exists(String id) {
        return CivicRecord.parties().contains(id, Tag.TAG_COMPOUND);
    }

    /** Is this folk a newcomer on the road or camped at a town's edge (nobody's yet)? */
    public static boolean is(VillageFolkEntity f) {
        return f.getTags().contains(TAG);
    }

    @Nullable
    static String partyOf(VillageFolkEntity f) {
        String id = f.getPersistentData().getCompound(DATA).getString("party");
        return id.isEmpty() || !exists(id) ? null : id;
    }

    /** The party's grown members' names: "Fen", "Fen and Moss". */
    static List<String> names(CompoundTag p, boolean grownOnly) {
        List<String> out = new ArrayList<>();
        for (Tag t : CivicRecord.list(p, "members")) {
            if (t instanceof CompoundTag m && (!grownOnly || !m.getBoolean("child"))) out.add(m.getString("name"));
        }
        return out;
    }

    static int size(CompoundTag p) {
        return CivicRecord.list(p, "members").size();
    }

    /** "a family of four from Oakwick, fleeing the war"; "Fen, a smith from the far south, fleeing a famine". */
    static String title(CompoundTag p) {
        int n = size(p);
        String from = p.getString("fromName");
        String why = p.getString("causeWords");
        if (n <= 1) {
            ListTag ms = CivicRecord.list(p, "members");
            CompoundTag m = ms.isEmpty() ? new CompoundTag() : ms.getCompound(0);
            String trade = m.getString("trade");
            StationTask t = JobMarket.named(trade);
            return m.getString("name") + ", " + (t == null || t == StationTask.NONE ? "alone" : JobMarket.a(JobMarket.noun(t))) + " from " + from + ", " + why;
        }
        return "a family of " + JobMarket.words(n) + " from " + from + ", " + why;
    }

    // ------------------------------------------------------------------ the town's round

    /** Every five seconds for each town (Referendums.tick): who leaves it, who comes from outside, how its newcomers are settling. */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        CompoundTag town = CivicRecord.town(id);
        if (t >= 2000L && t < 9000L && town.getLong("ncLooked") != day + 1) {
            town.putLong("ncLooked", day + 1);
            CivicRecord.changed();
            com.jrpetty.mcassistant.Guard.run("refugees leaving", () -> leaving(level, v, day));
            com.jrpetty.mcassistant.Guard.run("newcomers from outside", () -> {
                if (level.getRandom().nextInt(OUTSIDE_ODDS) == 0) fromOutside(level, v, day);
            });
            com.jrpetty.mcassistant.Guard.run("newcomers settling", () -> settling(level, v, day));
        }
        // A party camped here too long with nothing put to the town (a question lost to a reset, a vote never held): on it goes.
        // And the books let go of parties long settled, gone home or gone on (the town's own log keeps how each went).
        for (String pid : new ArrayList<>(CivicRecord.parties().getAllKeys())) {
            CompoundTag old = party(pid);
            String was = old.getString("stage");
            if ((was.equals("in") || was.equals("back") || was.equals("gone")) && day - old.getLong("since") > 28) {
                CivicRecord.parties().remove(pid);
                CivicRecord.changed();
            }
        }
        for (String pid : new ArrayList<>(CivicRecord.parties().getAllKeys())) {
            CompoundTag p = party(pid);
            if (!"camp".equals(p.getString("stage")) || !id.toString().equals(p.getString("target"))) continue;
            CompoundTag q = p.getInt("question") > 0 ? Referendums.question(id, p.getInt("question")) : null;
            if (q != null && !q.getBoolean("applied")) continue;
            if (day - p.getLong("arrived") >= CAMP_MOST) turnAway(level, v, pid, day, false);
        }
    }

    /** What would send a household away from this town now, or null: its war worn on it, or famine two mornings running. */
    @Nullable
    static Cause cause(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (!Wars.enemies(id).isEmpty() && WarAndPeace.weariness(id) >= WarAndPeace.WEARY) {
            long since = Long.MAX_VALUE;
            for (UUID e : Wars.enemies(id)) since = Math.min(since, Wars.since(id, e));
            if (day - since >= 4) return Cause.WAR;
        }
        CompoundTag town = CivicRecord.town(id);
        if (Leader.plan(id) == Leader.Plan.FAMINE) {
            if (!town.contains("famineFrom")) {
                town.putLong("famineFrom", day);
                CivicRecord.changed();
            }
            if (day - town.getLong("famineFrom") >= 1) return Cause.FAMINE;
        } else if (town.contains("famineFrom")) {
            town.remove("famineFrom");
            CivicRecord.changed();
        }
        return null;
    }

    /** Once a day: a household of a war-worn or starving town packs up for the nearest town at peace. */
    static void leaving(ServerLevel level, Villages.Village v, long day) {
        Cause c = cause(level, v, day);
        if (c == null) return;
        CompoundTag town = CivicRecord.town(v.id());
        if (town.contains("ncFled") && day - town.getLong("ncFled") < LEAVE_EVERY) return;
        List<VillageFolkEntity> household = household(v.id());
        if (household.isEmpty() || Villages.headcount(v.id()) - household.size() < KEEP) return;
        Villages.Village to = destination(v, c, List.of());
        if (to == null) return;
        town.putLong("ncFled", day);
        CivicRecord.changed();
        send(level, v, to, household, c);
    }

    /**
     * The household that goes: grown folk who are not the leader, nor of the watch, nor at a build's lead, nor away;
     * the one with fewest friends to keep it, a family before a lone hand. It goes with its partner and children.
     */
    static List<VillageFolkEntity> household(UUID village) {
        VillageFolkEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        UUID elder = Villages.elder(village);
        long now = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired()) continue;
            now = f.level().getGameTime();
            if (f.getUUID().equals(elder) || f.stationTask() == StationTask.GUARD || Villages.holdsTheLead(village, f.getUUID(), now)) continue;
            if (f.trip() != null || f.expedition() != null || JobSeekers.busy(f) || Nether.away(f)) continue;
            double s = -f.life().friends().size() * 2 + (f.life().partner() != null ? 5 : 0) + f.getRandom().nextDouble();
            if (s > bestScore) { bestScore = s; best = f; }
        }
        if (best == null) return List.of();
        VillageFolkEntity head = best;
        List<VillageFolkEntity> out = new ArrayList<>(JobSeekers.household(head));
        UUID partner = head.life().partner();
        out.removeIf(m -> !m.isBaby() && m != head && (m.getUUID().equals(elder) || m.stationTask() == StationTask.GUARD));
        if (partner != null && out.stream().noneMatch(m -> m.getUUID().equals(partner))) out.removeIf(VillageFolkEntity::isBaby);
        while (out.size() > 5) out.remove(out.size() - 1);
        return out;
    }

    /** Where they make for: the nearest town at peace (no war of its own, nor with the town they left), not tried already. */
    @Nullable
    static Villages.Village destination(Villages.Village from, Cause c, List<String> tried) {
        for (Villages.Village o : Diplomacy.neighboursOf(from.id())) {
            if (tried.contains(o.id().toString()) || !Wars.enemies(o.id()).isEmpty() || Wars.atWar(from.id(), o.id())) continue;
            if (Ledger.relation(from.id(), o.id()) <= Diplomacy.FEUD) continue;
            if (c == Cause.FAMINE && Leader.plan(o.id()) == Leader.Plan.FAMINE) continue;
            return o;
        }
        return null;
    }

    /**
     * A disaster's homeless (fire or flood, from another helper's work): sent with their households to the nearest town
     * at peace that might take them in, to ask. Returns how many went. The seam for Disasters: call it with the folk a
     * fire or a flood left with no home, after the town has tried to find them beds of its own.
     */
    public static int displaced(ServerLevel level, Villages.Village from, List<VillageFolkEntity> homeless, String cause) {
        Cause c = cause != null && cause.toLowerCase(java.util.Locale.ROOT).contains("flood") ? Cause.FLOOD : Cause.FIRE;
        List<VillageFolkEntity> left = new ArrayList<>(homeless);
        int went = 0;
        while (!left.isEmpty()) {
            VillageFolkEntity head = left.get(0);
            List<VillageFolkEntity> h = new ArrayList<>(JobSeekers.household(head));
            h.retainAll(left);
            if (!h.contains(head)) h.add(0, head);
            left.removeAll(h);
            if (Villages.headcount(from.id()) - h.size() < 3) break;
            Villages.Village to = destination(from, c, List.of());
            if (to == null) break;
            send(level, from, to, h, c);
            went += h.size();
        }
        return went;
    }

    /** A household leaves its town for another, to ask to be taken in: off the old roll, and onto the road. Returns the party's number. */
    static String send(ServerLevel level, Villages.Village from, Villages.Village to, List<VillageFolkEntity> household, Cause c) {
        long day = level.getDayTime() / 24000L;
        String pid = Integer.toString(CivicRecord.nextId());
        CompoundTag p = party(pid);
        p.putString("from", from.id().toString());
        p.putString("fromName", Villages.name(from.id()));
        p.putString("cause", c.name());
        p.putString("causeWords", c.words);
        p.putString("target", to.id().toString());
        p.putString("stage", "road");
        p.putLong("since", day);
        p.putLong("camp", camp(level, to, from.centre()).asLong());
        ListTag members = CivicRecord.list(p, "members");
        List<String> grown = new ArrayList<>();
        int i = 0;
        for (VillageFolkEntity m : household) {
            CompoundTag mt = new CompoundTag();
            mt.putString("id", m.getUUID().toString());
            mt.putString("name", m.displayNameCap());
            mt.putBoolean("child", m.isBaby());
            StationTask best = bestTrade(m);
            mt.putString("trade", best == null ? "" : best.name());
            mt.putInt("level", best == null ? 0 : m.tradeLevel(best));
            mt.putBoolean("head", i == 0);
            members.add(mt);
            if (!m.isBaby()) grown.add(m.displayNameCap());
            leave(level, m, from.id(), pid, day, c, to);
            Weave.oldColours(m, from);                                      // [weave] they go in their old town's colours
            i++;
        }
        CivicRecord.changed();
        String who = JobMarket.join(grown) + (household.size() > grown.size() ? (household.size() - grown.size() == 1 ? " and a child" : " and their children") : "");
        Villages.tell(from.id(), day, who + " left for " + Villages.name(to.id()) + ", " + c.words);
        if (!household.isEmpty()) FolkTalk.speak(household.get(0), switch (c) {
            case WAR -> "We can't stand another day of this war. " + Villages.name(to.id()) + " might take us in.";
            case FAMINE -> "There's nothing left to eat here. We'll ask " + Villages.name(to.id()) + " to take us in.";
            default -> "Everything we had is gone. We'll try " + Villages.name(to.id()) + ".";
        });
        LOG.info("[MCA-CIVIC] {} leave {} ({}) for {}: party {}", who, Villages.name(from.id()), c.words, Villages.name(to.id()), pid);
        return pid;
    }

    /** One folk off its town's roll, carrying what is its own, a newcomer on the road. */
    private static void leave(ServerLevel level, VillageFolkEntity m, UUID from, String pid, long day, Cause c, Villages.Village to) {
        UUID me = m.getUUID();
        m.clearQueue();
        m.getNavigation().stop();
        m.stopFollowing();
        if (m.isSleeping()) m.stopSleeping();
        m.forgetBed();
        m.setWorkZone(null);
        m.setStation(null, StationTask.NONE);           // (a guard's kit, a cave dweller's, back to the stores as it goes)
        m.leftItsPlot();
        handBack(level, m, from);
        Homes.left(from, me);
        Bank.left(from, m, false);
        Villages.recordDeath(from);
        Annals.moved(from, null);
        if (me.equals(Villages.elder(from))) Villages.elderGone(from, me);
        m.leaveTheRoll();
        m.addTag(TAG);
        CompoundTag d = new CompoundTag();
        d.putString("party", pid);
        m.getPersistentData().put(DATA, d);
        if (!m.isBaby()) m.persona().remember(day, "we left " + Villages.name(from) + ", " + c.words + ", for " + Villages.name(to.id()), 8);
        WALKS.remove(me);
    }

    /**
     * What it carries of the town's goods (the day's harvest, a load for the stores) goes into the old town's stores
     * before it goes: it carries away its tools, its keepsakes and a little food for the road, and nothing else.
     */
    private static void handBack(ServerLevel level, VillageFolkEntity m, UUID from) {
        if (m.isBaby() || !Villages.hasStores(level, from)) return;
        net.minecraft.core.NonNullList<ItemStack> pack = m.getInventoryItems();
        int food = 0;
        for (int i = 0; i < pack.size(); i++) {
            ItemStack s = pack.get(i);
            if (s.isEmpty() || Homes.isKeepsake(s) || s.isDamageableItem()) continue;
            if (s.get(net.minecraft.core.component.DataComponents.FOOD) != null && food < 8) {
                food += s.getCount();
                continue;
            }
            pack.set(i, Market.intoStores(level, from, s.copy()));
        }
    }

    /** The trade it knows best (its levels), or its own trade if it has never risen in any. */
    @Nullable
    static StationTask bestTrade(VillageFolkEntity f) {
        StationTask best = null;
        int lv = -1;
        for (StationTask t : StationTask.values()) {
            if (t == StationTask.NONE) continue;
            int l = f.tradeLevel(t);
            if (l > lv) { lv = l; best = t; }
        }
        if (lv <= 0) return f.stationTask() == StationTask.NONE ? null : f.stationTask();
        return best;
    }

    /** Where a party camps at a town's edge: out past its last street the way they come in from, on dry ground. */
    static BlockPos camp(ServerLevel level, Villages.Village to, BlockPos from) {
        BlockPos c = to.centre();
        double dx = from.getX() - c.getX(), dz = from.getZ() - c.getZ();
        double len = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
        int reach = Math.min(110, Villages.townReach(to.id()) + 8);
        for (int out = reach; out >= 12; out -= 4) {
            for (int side : new int[]{ 0, 4, -4, 8, -8 }) {
                int x = c.getX() + (int) Math.round(dx / len * out - dz / len * side);
                int z = c.getZ() + (int) Math.round(dz / len * out + dx / len * side);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos top = new BlockPos(x, y, z);
                if (!level.getFluidState(top.below()).isEmpty() || !level.getBlockState(top.below()).isFaceSturdy(level, top.below(), Direction.UP)) continue;
                return top;
            }
        }
        BlockPos e = Visitors.edge(level, to, level.getRandom());
        return e != null ? e : c.offset((int) Math.round(dx / len * 24), 0, (int) Math.round(dz / len * 24));
    }

    // ------------------------------------------------------------------ from the world outside

    /** Where folk from outside say they come from, and what drove them. */
    private static final String[][] AFAR = {
        { "beyond the hills", "fleeing a war" }, { "the far south", "fleeing a famine" }, { "the lowlands", "flooded out of their homes" },
        { "the east", "burnt out of their town" }, { "over the sea", "fleeing a sickness" } };

    /**
     * A party from the world outside comes to a town that might have it: a town of fifteen at peace, with nobody else
     * camped at its edge, none from outside these ten days. One, two or three of them (a lone hand, a couple, a couple
     * and a child), each bringing a trade the town has nobody at, with a few years at it; carrying a little bread.
     */
    @Nullable
    static String fromOutside(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (Villages.headcount(id) < 15 || Wars.footing(id) != Wars.Footing.PEACE || Raids.underAlarm(id)) return null;
        CompoundTag town = CivicRecord.town(id);
        if (town.contains("ncOutside") && day - town.getLong("ncOutside") < OUTSIDE_EVERY) return null;
        for (String pid : CivicRecord.parties().getAllKeys()) {
            CompoundTag p = party(pid);
            String st = p.getString("stage");
            if (id.toString().equals(p.getString("target")) && (st.equals("road") || st.equals("camp"))) return null;
        }
        return outside(level, v, day, -1);
    }

    /** The party from outside, made: {@code n} of them (or by the odds, given -1). Returns the party's number, or null. */
    static String outside(ServerLevel level, Villages.Village v, long day, int n) {
        RandomSource r = level.getRandom();
        BlockPos edge = Visitors.edge(level, v, r);
        if (edge == null) return null;
        if (n < 1) {
            int roll = r.nextInt(20);
            n = roll < 8 ? 1 : roll < 15 ? 2 : 3;
        }
        String[] afar = AFAR[r.nextInt(AFAR.length)];
        List<StationTask> trades = lacking(v);
        String pid = Integer.toString(CivicRecord.nextId());
        CompoundTag p = party(pid);
        p.putString("from", "");
        p.putString("fromName", afar[0]);
        p.putString("cause", Cause.OUTSIDE.name());
        p.putString("causeWords", afar[1]);
        p.putString("target", v.id().toString());
        p.putString("stage", "road");
        p.putLong("since", day);
        p.putBoolean("outside", true);
        p.putLong("camp", edge.asLong());
        ListTag members = CivicRecord.list(p, "members");
        List<VillageFolkEntity> made = new ArrayList<>();
        BlockPos start = edge.offset((edge.getX() - v.centre().getX()) > 0 ? 12 : -12, 0, (edge.getZ() - v.centre().getZ()) > 0 ? 12 : -12);
        if (!level.hasChunk(start.getX() >> 4, start.getZ() >> 4)) start = edge;
        start = new BlockPos(start.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, start.getX(), start.getZ()), start.getZ());
        for (int i = 0; i < n; i++) {
            boolean child = i == 2;
            VillageFolkEntity f = McAssistantMod.VILLAGE_FOLK.get().create(level);
            if (f == null) break;
            f.moveTo(start.getX() + 0.5 + i, start.getY(), start.getZ() + 0.5, r.nextFloat() * 360F, 0.0F);
            f.rename(Names.freshFor(v.id(), r));
            if (child) {
                f.setChild(true);
                f.bornDaysAgo(1 + r.nextInt(2));
                if (made.size() >= 2) {
                    f.life().roll(r, made.get(0).life(), made.get(1).life());
                    f.life().setParents(made.get(0).displayNameCap(), made.get(1).displayNameCap());
                    f.parentIds().add(made.get(0).getUUID());
                    f.parentIds().add(made.get(1).getUUID());
                } else {
                    f.life().roll(r, null, null);
                }
            } else {
                f.life().roll(r, null, null);
            }
            StationTask t = child ? null : i < trades.size() ? trades.get(i) : StationTask.FARM;
            f.persona().roll(r, f.life(), t == null ? StationTask.NONE : t, day, afar[0]);
            if (t != null) f.schoolXp(t, AssistantEntity.xpForLevel(8 + r.nextInt(9)));
            f.insertGiven(new ItemStack(Items.BREAD, 3 + r.nextInt(4)));
            f.setPersistenceRequired();
            f.addTag(TAG);
            CompoundTag d = new CompoundTag();
            d.putString("party", pid);
            f.getPersistentData().put(DATA, d);
            level.addFreshEntity(f);
            made.add(f);
            CompoundTag mt = new CompoundTag();
            mt.putString("id", f.getUUID().toString());
            mt.putString("name", f.displayNameCap());
            mt.putBoolean("child", child);
            mt.putString("trade", t == null ? "" : t.name());
            mt.putInt("level", t == null ? 0 : f.tradeLevel(t));
            mt.putBoolean("head", i == 0);
            members.add(mt);
        }
        if (made.size() >= 2 && !made.get(0).isBaby() && !made.get(1).isBaby()) {
            made.get(0).life().partnerWith(made.get(1).getUUID(), made.get(1).displayNameCap());
            made.get(1).life().partnerWith(made.get(0).getUUID(), made.get(0).displayNameCap());
        }
        if (made.isEmpty()) {
            CivicRecord.parties().remove(pid);
            return null;
        }
        CivicRecord.town(v.id()).putLong("ncOutside", day);
        CivicRecord.changed();
        LOG.info("[MCA-CIVIC] {} newcomers from {} ({}) make for {}: party {}", made.size(), afar[0], afar[1], Villages.name(v.id()), pid);
        return pid;
    }

    /** The crafts and their workplaces: a smithy wants a smith, a brewery a brewer, the café a cook, the workshop a tailor. */
    private static final Object[][] CRAFTS = {
        { "smithy", StationTask.SMITH, Villages.Age.IRON }, { "brewery", StationTask.BREW, Villages.Age.IRON },
        { "cafe", StationTask.COOK, Villages.Age.STONE }, { "workshop", StationTask.TAILOR, Villages.Age.STONE },
        { "library", StationTask.ENCHANT, Villages.Age.DIAMOND }, { "", StationTask.BEEKEEP, Villages.Age.STONE } };

    /**
     * The trades the town lacks, as a newcomer would bring them: first a craft whose workplace stands empty (a smithy
     * with no smith), then a craft its age allows that nobody works at, then whatever its job market wants hands for.
     */
    static List<StationTask> lacking(Villages.Village v) {
        List<StationTask> out = new ArrayList<>();
        UUID id = v.id();
        Villages.Age age = Villages.ageOf(id);
        for (int pass = 0; pass < 2; pass++) {
            for (Object[] c : CRAFTS) {
                StationTask t = (StationTask) c[1];
                String building = (String) c[0];
                boolean stands = !building.isEmpty() && Villages.hasBuilt(id, building);
                if (out.contains(t) || pass == 0 && !stands) continue;
                if (pass == 1 && age.ordinal() < ((Villages.Age) c[2]).ordinal()) continue;
                if (nobodyAt(id, t)) out.add(t);
            }
        }
        for (JobMarket.Want w : JobMarket.wanted(v)) if (!out.contains(w.trade())) out.add(w.trade());
        out.removeIf(t -> t == StationTask.NONE || t == StationTask.GUARD || t == StationTask.SCOUT || t == StationTask.BANK || t == StationTask.CAVE
            || t == StationTask.NETHER);                         // [nether] picked from the town's own veterans (NetherRunners.appoint)
        return out;
    }

    /** Does nobody of the town work at this trade? */
    static boolean nobodyAt(UUID village, StationTask t) {
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == t) return false;
        return true;
    }

    // ------------------------------------------------------------------ a newcomer's day

    /**
     * From every folk's tick (VillageFolkEntity.aiStep), before anything of a resident's day: a newcomer on the road
     * or camped at a town's edge. True while it is one (its whole day is run from here: nobody's folk goes looking for
     * a village to join).
     */
    public static boolean drive(VillageFolkEntity f) {
        if (!is(f) || !(f.level() instanceof ServerLevel level)) return false;
        String pid = partyOf(f);
        if (pid == null) {
            // Nothing to say where it is going (a party lost): it is nobody's, and finds a town as anybody does.
            f.removeTag(TAG);
            f.getPersistentData().remove(DATA);
            return false;
        }
        CompoundTag p = party(pid);
        String stage = p.getString("stage");
        Player talker = f.talkPartner();
        if (talker != null) {
            f.getNavigation().stop();
            f.getLookControl().setLookAt(talker, 30.0F, 30.0F);
            return true;
        }
        if (f.tickCount % 10 != (Math.floorMod(f.getUUID().hashCode(), 10))) return true;
        UUID target = uuid(p.getString("target"));
        Villages.Village town = target == null ? null : Villages.get(target);
        long day = level.getDayTime() / 24000L;
        switch (stage) {
            case "in" -> {
                // One that was not about when the town took its party in (its ground asleep): taken in now.
                if (town != null) {
                    settle(level, town, f, p, day);
                    if (f.bedPos() == null && Homes.homeOf(town.id(), f.getUUID()) == null) f.claimBedNear(town.centre());
                } else {
                    lost(level, f, p);
                }
                return false;
            }
            case "road", "away" -> {
                if (town == null) { lost(level, f, p); return true; }
                if (f.isSleeping()) f.stopSleeping();
                keepAwake(level, f);
                BlockPos camp = spot(p, f);
                if (Visitors.walk(f, level, camp, 3.0, 0.75D, walk(f))) {
                    arrived(level, town, pid, day);                       // the first of them there asks for them all
                }
                f.hobbyNow = "on the road to " + Villages.name(target);
                f.lastLeisureTick = f.tickCount;
            }
            case "camp" -> {
                keepAwake(level, f);                                     // out past the town's own ground: kept awake to hear the answer
                BlockPos camp = spot(p, f);
                if (Visitors.flat(f.blockPosition(), camp) > 6 * 6) Visitors.walk(f, level, camp, 2.0, 0.6D, walk(f));
                else if (town != null && f.getRandom().nextInt(6) == 0) f.getLookControl().setLookAt(town.centre().getX(), f.getEyeY(), town.centre().getZ());
                if (head(p, f) && town != null && f.getRandom().nextInt(90) == 0) {
                    FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "We only ask for a roof and work.", "Will they have us, do you think?",
                        "We've come a long way. " + Villages.name(town.id()) + " looks a kind sort of place."));
                }
                f.hobbyNow = "waiting at the edge of " + (town == null ? "town" : Villages.name(town.id())) + " to hear if it will take them in";
                f.lastLeisureTick = f.tickCount;
            }
            case "home", "back" -> {
                UUID from = uuid(p.getString("from"));
                Villages.Village home = from == null ? null : Villages.get(from);
                if (home == null) { gone(level, f, p); return true; }
                keepAwake(level, f);
                if (Visitors.walk(f, level, home.centre(), 6.0, 0.75D, walk(f))) homeAgain(level, home, f, p, day);
                f.hobbyNow = "on the road home to " + Villages.name(home.id());
                f.lastLeisureTick = f.tickCount;
            }
            default -> {
                // "gone": back out into the world.
                BlockPos out = BlockPos.of(p.getLong("leaveBy"));
                if (Visitors.walk(f, level, out, 3.0, 0.75D, walk(f)) || f.tickCount - f.getPersistentData().getCompound(DATA).getInt("leftAt") > 6000) gone(level, f, p);
            }
        }
        return true;
    }

    private static boolean head(CompoundTag p, VillageFolkEntity f) {
        for (Tag t : CivicRecord.list(p, "members")) {
            if (t instanceof CompoundTag m && m.getBoolean("head")) return m.getString("id").equals(f.getUUID().toString());
        }
        return true;
    }

    /** Its own place at the camp: a step apart from the others. */
    private static BlockPos spot(CompoundTag p, VillageFolkEntity f) {
        BlockPos c = BlockPos.of(p.getLong("camp"));
        int k = Math.floorMod(f.getUUID().hashCode(), 9);
        return c.offset(k % 3 - 1, 0, k / 3 - 1);
    }

    private static Visitors.Walk walk(VillageFolkEntity f) {
        return WALKS.computeIfAbsent(f.getUUID(), k -> new Visitors.Walk());
    }

    @Nullable
    private static UUID uuid(String s) {
        try {
            return s == null || s.isEmpty() ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID owner(VillageFolkEntity f) {
        return UUID.nameUUIDFromBytes(("mca-newcomer-" + f.getUUID()).getBytes());
    }

    /** The ground round it kept awake as it walks between towns (let go when it gets there). */
    private static void keepAwake(ServerLevel level, VillageFolkEntity f) {
        BlockPos here = f.blockPosition();
        BlockPos w = WINDOWS.get(f.getUUID());
        if (w == null) w = savedWindow(f);                  // kept awake when the world was saved: let go as it moves on
        if (w != null && w.distSqr(here) < 16 * 16) {
            WINDOWS.put(f.getUUID(), w);
            return;
        }
        if (w != null) ChunkLoad.setLoaded(level, owner(f), w, 1, false);
        ChunkLoad.setLoaded(level, owner(f), here, 1, true);
        WINDOWS.put(f.getUUID(), here.immutable());
        CompoundTag d = f.getPersistentData().getCompound(DATA);
        d.putLong("window", here.asLong());
        f.getPersistentData().put(DATA, d);
    }

    private static void release(ServerLevel level, VillageFolkEntity f) {
        BlockPos w = WINDOWS.remove(f.getUUID());
        if (w == null) w = savedWindow(f);
        if (w != null) ChunkLoad.setLoaded(level, owner(f), w, 1, false);
        if (f.getPersistentData().contains(DATA)) {
            CompoundTag d = f.getPersistentData().getCompound(DATA);
            d.remove("window");
            f.getPersistentData().put(DATA, d);
        }
    }

    @Nullable
    private static BlockPos savedWindow(VillageFolkEntity f) {
        CompoundTag d = f.getPersistentData().getCompound(DATA);
        return d.contains("window") ? BlockPos.of(d.getLong("window")) : null;
    }

    /** The party's head is at the camp: the town is asked, today if it is before noon, else tomorrow. */
    static void arrived(ServerLevel level, Villages.Village town, String pid, long day) {
        CompoundTag p = party(pid);
        if (!"road".equals(p.getString("stage")) && !"away".equals(p.getString("stage"))) return;
        p.putString("stage", "camp");
        p.putLong("arrived", day);
        long t = level.getDayTime() % 24000L;
        long voteDay = t < 6000L ? day : day + 1;
        String title = title(p);
        CompoundTag q = Referendums.call(level, town, Referendums.REFUGE, pid, title, Villages.elderName(town.id()), "", "", "", voteDay, null);
        p.putInt("question", q.getInt("id"));
        CivicRecord.changed();
        Villages.tell(town.id(), day, capital(title) + ", came to the edge of town and asked to settle");
        Market.assemblyNews(town.id(), capital(title) + (size(p) > 1 ? ", are camped at the edge of town and ask" : ", is camped at the edge of town and asks")
            + " to settle with us. We vote "
            + (voteDay == day ? "today" : "tomorrow") + " at the board.");
        VillageFolkEntity head = member(level, p, true);
        if (head != null) {
            FolkTalk.speak(head, "We've come from " + p.getString("fromName") + ", " + p.getString("causeWords") + ". We ask to settle here, if "
                + Villages.name(town.id()) + " will have us.");
        }
        LOG.info("[MCA-CIVIC] party {} ({}) camps at {}; the vote on day {}", pid, title, Villages.name(town.id()), voteDay);
    }

    @Nullable
    static VillageFolkEntity member(ServerLevel level, CompoundTag p, boolean head) {
        for (Tag t : CivicRecord.list(p, "members")) {
            if (!(t instanceof CompoundTag m) || head && !m.getBoolean("head")) continue;
            UUID u = uuid(m.getString("id"));
            if (u != null && level.getEntity(u) instanceof VillageFolkEntity f && f.isAlive()) return f;
        }
        return null;
    }

    static List<VillageFolkEntity> members(ServerLevel level, CompoundTag p) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (Tag t : CivicRecord.list(p, "members")) {
            if (!(t instanceof CompoundTag m)) continue;
            UUID u = uuid(m.getString("id"));
            if (u != null && level.getEntity(u) instanceof VillageFolkEntity f && f.isAlive()) out.add(f);
        }
        return out;
    }

    // ------------------------------------------------------------------ the town's vote

    /**
     * How a folk of the town weighs taking a party in: its nature (a generous soul, a sociable one say aye; a grump, a
     * shy one, a Guardian wary of strangers say nay), the trades they would bring that the town lacks (to the Visionary
     * and the Merchant), a friend among them; and the town as it is: the room it has, its food, its mood, and its
     * leader's temper (a warm leader's town welcomes, a wary one's turns away). Aye if it comes out above nothing.
     */
    static Referendums.Judged judge(ServerLevel level, VillageFolkEntity voter, String pid) {
        UUID id = voter.ownerId();
        if (id == null || !exists(pid)) return new Referendums.Judged(false, 0, "I don't know who they are");
        CompoundTag p = party(pid);
        Social.Life l = voter.life();
        int[] w = Values.of(voter);
        double s = 0;
        String bestWhy = null, worstWhy = null;
        double best = 0, worst = 0;
        // Its nature.
        double nature = (l.has(Social.Trait.GENEROUS) ? 18 : 0) + (l.has(Social.Trait.SOCIABLE) ? 8 : 0) + (l.has(Social.Trait.CHEERFUL) ? 6 : 0)
            - (l.has(Social.Trait.GRUMPY) ? 12 : 0) - (l.has(Social.Trait.SHY) ? 8 : 0);
        s += nature;
        if (nature > best) { best = nature; bestWhy = "anybody in trouble deserves a roof"; }
        if (-nature > worst) { worst = -nature; worstWhy = "we don't know the first thing about them"; }
        double wary = -w[Values.Value.SAFETY.ordinal()] * 0.20;
        s += wary;
        if (-wary > worst) { worst = -wary; worstWhy = "strangers at the edge of town — who knows what they'll bring"; }
        // The trades they bring that the town lacks.
        Villages.Village town = Villages.get(id);
        List<StationTask> lack = town == null ? List.of() : lacking(town);
        String brings = null;
        for (Tag t : CivicRecord.list(p, "members")) {
            if (!(t instanceof CompoundTag m)) continue;
            StationTask tr = JobMarket.named(m.getString("trade"));
            if (tr != null && lack.contains(tr)) { brings = JobMarket.noun(tr); break; }
        }
        if (brings != null) {
            double gain = 6 + w[Values.Value.PROGRESS.ordinal()] * 0.12 + w[Values.Value.WEALTH.ordinal()] * 0.12;
            s += gain;
            if (gain > best) { best = gain; bestWhy = "we've no " + brings + ", and they bring one"; }
        }
        // A friend among them.
        for (Tag t : CivicRecord.list(p, "members")) {
            if (!(t instanceof CompoundTag m)) continue;
            UUID u = uuid(m.getString("id"));
            if (u != null && (l.affinity(u) >= 20 || l.parents().contains(m.getString("name")))) {
                s += 30;
                if (30 > best) { best = 30; bestWhy = m.getString("name") + " is a friend of mine — of course they can stay"; }
                break;
            }
        }
        // The town as it is: room, food, mood.
        int n = size(p);
        int spare = Villages.housing(id) - Villages.headcount(id);
        if (spare >= n) {
            s += 6;
            if (6 > best && bestWhy == null) { best = 6; bestWhy = "we've the room"; }
        } else {
            double cramped = 15 + (n - Math.max(0, spare)) * 10;
            s -= cramped;
            if (cramped > worst) { worst = cramped; worstWhy = "we've no room as it is — where would they sleep?"; }
        }
        Leader.Plan plan = Leader.plan(id);
        double food = switch (plan) {
            case FAMINE -> -45;
            case SHORT, WAR -> -18;
            case PLENTY -> 6;
            default -> 0;
        };
        s += food;
        if (-food > worst) { worst = -food; worstWhy = "we can't feed ourselves, let alone more mouths"; }
        int content = Contentment.score(id);
        s += content < 35 ? -8 : content >= 70 ? 4 : 0;
        // The leader's temper.
        Envoys.Temper temper = Envoys.temper(id);
        double led = temper.kindly() ? 10 : temper == Envoys.Temper.WARY ? -10 : temper == Envoys.Temper.PRICKLY ? -16
            : temper == Envoys.Temper.SHREWD ? (brings != null ? 8 : -6) : 0;
        s += led;
        if (led > best) { best = led; bestWhy = "our " + Homeland.leaderTitle(id) + " says we should, and I agree"; }
        if (-led > worst) { worst = -led; worstWhy = "our " + Homeland.leaderTitle(id) + " doesn't trust it, and nor do I"; }
        // [identity] The town's character and its law: an open town takes them in, a closed one keeps to its own (Ethos).
        double ways = Ethos.newcomerLean(id);
        s += ways;
        if (ways > best) { best = ways; bestWhy = Ethos.newcomerWhy(id, true); }
        if (-ways > worst) { worst = -ways; worstWhy = Ethos.newcomerWhy(id, false); }
        // What drove them.
        Cause c = Cause.named(p.getString("cause"));
        s += c == Cause.OUTSIDE ? -2 : c == Cause.FIRE || c == Cause.FLOOD ? 8 : 6;
        if (bestWhy == null) bestWhy = "they've been through " + (c == null ? "enough" : c.what) + "; it's only right";
        s += Math.floorMod(Objects.hash(voter.getUUID(), pid), 7);
        boolean aye = s > 0;
        return new Referendums.Judged(aye, (int) Math.round(s), aye ? bestWhy : worstWhy == null ? "not now" : worstWhy);
    }

    /** The count is in (Referendums.decide): taken in, or turned away. */
    static void decided(ServerLevel level, Villages.Village v, String pid, boolean carried, int ayes, int nays) {
        if (!exists(pid)) return;
        long day = level.getDayTime() / 24000L;
        CompoundTag p = party(pid);
        record(v.id(), p, day, carried ? "taken in" : "turned away", ayes, nays);
        if (carried) takeIn(level, v, pid, day);
        else turnAway(level, v, pid, day, true);
    }

    /** One line for the books: the party, the day, what the town decided and by how much. */
    private static void record(UUID village, CompoundTag p, long day, String verdict, int ayes, int nays) {
        ListTag log = CivicRecord.list(CivicRecord.town(village), "newcomers");
        CompoundTag e = new CompoundTag();
        e.putLong("day", day);
        e.putString("title", title(p));
        e.putString("verdict", verdict);
        e.putInt("ayes", ayes);
        e.putInt("nays", nays);
        List<String> trades = new ArrayList<>();
        for (Tag t : CivicRecord.list(p, "members")) {
            if (!(t instanceof CompoundTag m)) continue;
            StationTask tr = JobMarket.named(m.getString("trade"));
            if (tr != null && tr != StationTask.NONE && !m.getBoolean("child")) trades.add(JobMarket.noun(tr) + " (level " + m.getInt("level") + ")");
        }
        e.putString("trades", String.join(", ", trades));
        log.add(e);
        while (log.size() > 16) log.remove(0);
        CivicRecord.changed();
    }

    /** Taken in: every member of the party a folk of the town, the town that sent them the warmer for it. */
    static void takeIn(ServerLevel level, Villages.Village v, String pid, long day) {
        CompoundTag p = party(pid);
        p.putString("stage", "in");
        p.putLong("inOn", day);
        CivicRecord.changed();
        List<VillageFolkEntity> here = members(level, p);
        List<VillageFolkEntity> grown = new ArrayList<>();
        for (VillageFolkEntity f : here) settle(level, v, f, p, day);
        for (VillageFolkEntity f : here) if (!f.isBaby()) grown.add(f);
        // A home for the household together: an empty house, else beds at the camp and a house wanted.
        if (!grown.isEmpty()) {
            Homes.Home h = Homes.vacancyFor(level, v.id(), here);
            if (h != null) {
                Homes.settle(level, v, h, new ArrayList<>(here), day);
            } else {
                for (VillageFolkEntity f : here) if (f.bedPos() == null) f.claimBedNear(v.centre());
                Villages.request(v.id(), "house");
            }
        }
        UUID from = uuid(p.getString("from"));
        String title = title(p);
        List<String> trades = new ArrayList<>();
        for (VillageFolkEntity f : grown) if (f.stationTask() != StationTask.NONE) trades.add(JobMarket.a(JobMarket.noun(f.stationTask())));
        Villages.tell(v.id(), day, capital(title) + (size(p) > 1 ? ", were" : ", was") + " taken in" + (trades.isEmpty() ? "" : ", bringing " + JobMarket.join(trades)));
        Market.assemblyNews(v.id(), "We have taken in " + title + ". Make them welcome!");
        if (from != null && Villages.get(from) != null) {
            Ledger.relate(from, v.id(), 5);
            Bonds.remember(from, v.id(), day, 5, Villages.name(v.id()) + " took in " + JobMarket.join(names(p, true)) + ", " + p.getString("causeWords"));
            Villages.tell(from, day, Villages.name(v.id()) + " took in " + JobMarket.join(names(p, true)) + ", who left us, " + p.getString("causeWords"));
        }
        VillageFolkEntity head = member(level, p, true);
        if (head != null) FolkTalk.speak(head, FolkTalk.pick(level.getRandom(), "Thank you. You'll not regret it, I promise.",
            "A roof again. Thank you, " + Villages.name(v.id()) + "!", "We'll pull our weight. Thank you, all of you."));
        LOG.info("[MCA-CIVIC] {}: party {} ({}) taken in; trades {}", Villages.name(v.id()), pid, title, trades);
    }

    /** One member of a party taken in: the town's folk now, at the trade it knows best that the town needs. */
    static void settle(ServerLevel level, Villages.Village v, VillageFolkEntity f, CompoundTag p, long day) {
        release(level, f);
        WALKS.remove(f.getUUID());
        f.removeTag(TAG);
        f.getPersistentData().remove(DATA);
        f.getNavigation().stop();
        f.joinVillage(v.id(), v.centre());
        Villages.recordBirth(v.id());
        Annals.moved(null, v.id());
        if (!f.isBaby()) {
            StationTask trade = tradeFor(f, v);
            if (trade != null && trade != StationTask.NONE) {
                f.setStation(f.blockPosition(), trade);
                f.setAutonomous(true);
            }
            Neighbourly.arrived(level, v.id(), f, "taken in, " + p.getString("causeWords"));
            f.persona().remember(day, Villages.name(v.id()) + " took us in, " + p.getString("causeWords"), 9);
        }
        CompoundTag t = CivicRecord.folk(f.getUUID());
        t.putLong("cameOn", day);
        t.putString("from", p.getString("fromName"));
        t.putString("why", p.getString("causeWords"));
        t.putString("town", v.id().toString());
        CivicRecord.changed();
        f.refreshMood();
    }

    /** The trade a newcomer takes up: the one it knows best, if the town lacks it or is short of it; else what the town needs most. */
    @Nullable
    static StationTask tradeFor(VillageFolkEntity f, Villages.Village v) {
        StationTask best = bestTrade(f);
        List<StationTask> lack = lacking(v);
        if (best != null && (nobodyAt(v.id(), best) || lack.contains(best) || JobMarket.shortOf(v.id(), best) >= 0.5)) return best;
        for (StationTask t : lack) if (f.tradeLevel(t) > 0) return t;
        return JobMarket.fitFor(f, v.id());
    }

    /**
     * Turned away (or tired of waiting): the town the party came from thinks the worse of this one; and the party goes
     * on to the next town that might have it, or home (folk of another town), or out into the world (from outside).
     */
    static void turnAway(ServerLevel level, Villages.Village v, String pid, long day, boolean voted) {
        CompoundTag p = party(pid);
        ListTag tried = CivicRecord.list(p, "tried");
        tried.add(StringTag.valueOf(v.id().toString()));
        UUID from = uuid(p.getString("from"));
        String title = title(p);
        Villages.tell(v.id(), day, voted ? "the town turned away " + title : title + " went on, tired of waiting to be heard");
        if (from != null && Villages.get(from) != null && voted) {
            Ledger.relate(from, v.id(), -4);
            Bonds.remember(from, v.id(), day, -4, Villages.name(v.id()) + " turned away " + JobMarket.join(names(p, true)) + ", " + p.getString("causeWords"));
            Villages.tell(from, day, Villages.name(v.id()) + " turned away " + JobMarket.join(names(p, true)) + ", who left us, " + p.getString("causeWords"));
        }
        List<String> triedIds = new ArrayList<>();
        for (Tag t : tried) triedIds.add(t.getAsString());
        if (from != null) triedIds.add(from.toString());
        Cause c = Cause.named(p.getString("cause"));
        Villages.Village next = tried.size() >= 3 ? null : destination(v, c == null ? Cause.OUTSIDE : c, triedIds);
        VillageFolkEntity head = member(level, p, true);
        if (next != null) {
            p.putString("target", next.id().toString());
            p.putString("stage", "away");
            p.putLong("camp", camp(level, next, v.centre()).asLong());
            p.putInt("question", 0);
            if (head != null) FolkTalk.speak(head, "Then we'll try " + Villages.name(next.id()) + ". Good day to you.");
        } else if (from != null && Villages.get(from) != null) {
            p.putString("stage", "home");
            if (head != null) FolkTalk.speak(head, "Nobody will have us. We'll go home, " + (c == null ? "trouble" : c.what) + " or no.");
        } else {
            p.putString("stage", "gone");
            BlockPos away = camp(level, v, v.centre().offset(level.getRandom().nextInt(200) - 100, 0, level.getRandom().nextInt(200) - 100));
            double dx = away.getX() - v.centre().getX(), dz = away.getZ() - v.centre().getZ(), len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
            p.putLong("leaveBy", away.offset((int) (dx / len * 40), 0, (int) (dz / len * 40)).asLong());
            for (VillageFolkEntity f : members(level, p)) {
                CompoundTag d = f.getPersistentData().getCompound(DATA);
                d.putInt("leftAt", f.tickCount);
                f.getPersistentData().put(DATA, d);
            }
            if (head != null) FolkTalk.speak(head, "So be it. We'll keep walking.");
        }
        CivicRecord.changed();
        LOG.info("[MCA-CIVIC] {}: party {} ({}) {} -> {}", Villages.name(v.id()), pid, title, voted ? "turned away" : "gave up waiting",
            p.getString("stage") + (next != null ? " to " + Villages.name(next.id()) : ""));
    }

    /** Home again, with nowhere else to go: back on its own town's roll. */
    static void homeAgain(ServerLevel level, Villages.Village home, VillageFolkEntity f, CompoundTag p, long day) {
        release(level, f);
        WALKS.remove(f.getUUID());
        f.removeTag(TAG);
        f.getPersistentData().remove(DATA);
        f.joinVillage(home.id(), home.centre());
        Villages.recordBirth(home.id());
        Annals.moved(null, home.id());
        if (!f.isBaby()) f.persona().remember(day, "nobody would take us in, and we came home to " + Villages.name(home.id()), 8);
        if (head(p, f)) {
            Villages.tell(home.id(), day, JobMarket.join(names(p, true)) + " came home, no town having taken them in");
            p.putString("stage", "back");
            CivicRecord.changed();
        }
    }

    /** Out of sight, into the world they came from: gone, with what they carried. */
    private static void gone(ServerLevel level, VillageFolkEntity f, CompoundTag p) {
        release(level, f);
        WALKS.remove(f.getUUID());
        LOG.info("[MCA-CIVIC] {} goes back out into the world", f.displayNameCap());
        f.discard();
    }

    /** Its party gone from the books, or its town: it is nobody's, and finds a town as anybody does. */
    private static void lost(ServerLevel level, VillageFolkEntity f, CompoundTag p) {
        release(level, f);
        WALKS.remove(f.getUUID());
        f.removeTag(TAG);
        f.getPersistentData().remove(DATA);
    }

    // ------------------------------------------------------------------ settling in

    /**
     * Once a day for each town: its newcomers of the last week settling in. Between the second day and the sixth, each
     * meets two of its new neighbours properly: alike in nature, they are friends; at odds, they have words, and the
     * newcomer is the sorer for a few days. Either way it is remembered, and the books say how it went.
     */
    static void settling(ServerLevel level, Villages.Village v, long day) {
        List<VillageFolkEntity> locals = new ArrayList<>();
        List<VillageFolkEntity> come = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase()) continue;
            if (CivicRecord.known(f.getUUID()) && CivicRecord.folk(f.getUUID()).contains("cameOn")) come.add(f);
            else locals.add(f);
        }
        if (locals.isEmpty()) return;
        for (VillageFolkEntity f : come) {
            CompoundTag t = CivicRecord.folk(f.getUUID());
            long since = day - t.getLong("cameOn");
            if (since < 2 || since > 6 || !t.getString("fit").isEmpty()) continue;
            VillageFolkEntity a = locals.get(Math.floorMod(Objects.hash(f.getUUID(), day), locals.size()));
            VillageFolkEntity b = locals.get(Math.floorMod(Objects.hash(f.getUUID(), day, 1), locals.size()));
            VillageFolkEntity clash = f.life().clashesWith(a.life()) ? a : f.life().clashesWith(b.life()) ? b : null;
            if (clash != null) {
                f.life().feel(clash.getUUID(), clash.displayNameCap(), -10);
                clash.life().feel(f.getUUID(), f.displayNameCap(), -10);
                f.persona().remember(day, "I had words with " + clash.displayNameCap() + ", who doesn't want newcomers here", 4);
                t.putString("fit", "had words with " + clash.displayNameCap());
                t.putLong("clashOn", day);
                Villages.tell(v.id(), day, f.displayNameCap() + ", lately come from " + t.getString("from") + ", had words with " + clash.displayNameCap());
            } else {
                VillageFolkEntity friend = a;
                f.life().feel(friend.getUUID(), friend.displayNameCap(), 12);
                friend.life().feel(f.getUUID(), f.displayNameCap(), 12);
                f.persona().remember(day, friend.displayNameCap() + " made me welcome here", 4);
                t.putString("fit", "fitted in, friends with " + friend.displayNameCap());
            }
            CivicRecord.changed();
            f.refreshMood();
        }
    }

    /** Its spirits: grateful for its first five days in the town; sore for three after words with a neighbour. */
    static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        if (!CivicRecord.known(f.getUUID())) return m;
        CompoundTag t = CivicRecord.folk(f.getUUID());
        if (!t.contains("cameOn")) return m;
        if (t.contains("clashOn") && day - t.getLong("clashOn") <= 3) {
            why.add(new Object[]{ "clash", 7 });
            return m - 4;
        }
        if (day - t.getLong("cameOn") <= 5) {
            why.add(new Object[]{ "grateful", 8 });
            return m + 5;
        }
        return m;
    }

    static String moodWords(VillageFolkEntity f, String why) {
        CompoundTag t = CivicRecord.known(f.getUUID()) ? CivicRecord.folk(f.getUUID()) : new CompoundTag();
        if (why.equals("clash")) return FolkTalk.pick(f.getRandom(), "Not everybody here wants us, I can tell.",
            "Some folk won't let me forget I'm not from here.");
        return FolkTalk.pick(f.getRandom(), "They took us in when we'd nowhere else to go. I'll not forget it.",
            "A roof, work, neighbours. After " + (t.getString("why").isEmpty() ? "all that" : "what we left behind") + ", it's a lot.");
    }

    // ------------------------------------------------------------------ talk, the card, the board, the books

    /** What a newcomer on the road or at the edge of town says to a player; null for anybody else. */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, TalkTopic topic, String text) {
        if (!is(f)) return null;
        String pid = partyOf(f);
        if (pid == null) return null;
        CompoundTag party = party(pid);
        RandomSource r = f.getRandom();
        UUID target = uuid(party.getString("target"));
        String town = target == null ? "the town" : Villages.name(target);
        String stage = party.getString("stage");
        if (topic == TalkTopic.GIFT || topic == TalkTopic.BYE) return null;
        if (stage.equals("away") || stage.equals("home") || stage.equals("gone")) {
            return stage.equals("home") ? "Nobody would have us. We're going home to " + party.getString("fromName") + "."
                : stage.equals("gone") ? "They wouldn't have us. We'll keep walking." : "The last town wouldn't have us. We're trying " + town + " now.";
        }
        StationTask t = bestTrade(f);
        return switch (topic) {
            case OPEN -> FolkTalk.pick(r, "We've come from " + party.getString("fromName") + ", " + party.getString("causeWords") + ". We're asking "
                + town + " to take us in.", "Hello. We're waiting to hear whether " + town + " will have us.");
            case HOW -> FolkTalk.pick(r, "Tired. Footsore. Hoping.", "Better, now we're somewhere. Ask me again after the vote.");
            case ABOUT, DOING -> (t == null ? "I'll turn my hand to anything" : "I was " + JobMarket.a(JobMarket.noun(t)) + " at home, level "
                + f.tradeLevel(t)) + ". I can work, if they'll let me. " + (stage.equals("camp") ? "The town votes at the board on it." : "We're nearly there.");
            default -> FolkTalk.pick(r, "We only ask for a roof and work.", "It's not for us to say — the town votes on it.");
        };
    }

    /** A newcomer's card (FolkTalk.card): who, from where and why, what it is asking, and its trade. */
    public static String card(VillageFolkEntity f) {
        String pid = partyOf(f);
        if (pid == null) return "Newcomer|on the road, nobody's yet";
        CompoundTag p = party(pid);
        UUID target = uuid(p.getString("target"));
        String town = target == null ? "a town" : Villages.name(target);
        StringBuilder sb = new StringBuilder();
        sb.append("Newcomer|from ").append(p.getString("fromName")).append(", ").append(p.getString("causeWords")).append("; of no town for now");
        String stage = p.getString("stage");
        sb.append("\nAsks|").append(switch (stage) {
            case "camp" -> "to settle in " + town + "; camped at its edge, waiting for the town's vote";
            case "road", "away" -> "on the road to " + town + ", to ask to settle";
            case "home" -> "nothing now: going home, nobody having taken them in";
            default -> "nothing now: going back out into the world";
        });
        StationTask t = bestTrade(f);
        if (!f.isBaby()) sb.append("\nTrade|").append(t == null ? "anything going" : t.title + ", level " + f.tradeLevel(t));
        sb.append("\nWith|").append(JobMarket.join(names(p, false)));
        if (f.persona().rolled()) sb.append("\nNature|").append(f.life().traitsLabel());
        return sb.toString();
    }

    /** A settled newcomer's card line: where it came from, why, and how it is fitting in. */
    static String cardLine(VillageFolkEntity f) {
        if (!CivicRecord.known(f.getUUID())) return "";
        CompoundTag t = CivicRecord.folk(f.getUUID());
        if (!t.contains("cameOn")) return "";
        return "came from " + t.getString("from") + ", " + t.getString("why") + ", day " + (t.getLong("cameOn") + 1)
            + (t.getString("fit").isEmpty() ? "" : "; " + t.getString("fit"));
    }

    /** The board's lines: who is on the road here, and the week's newcomers and how the town decided. */
    static List<String> board(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        for (String pid : CivicRecord.parties().getAllKeys()) {
            CompoundTag p = party(pid);
            String st = p.getString("stage");
            if (village.toString().equals(p.getString("target")) && (st.equals("road") || st.equals("away"))) {
                out.add("RN|On the road here: " + title(p) + ".");
            }
        }
        for (Tag t : CivicRecord.list(CivicRecord.town(village), "newcomers")) {
            if (!(t instanceof CompoundTag e) || day - e.getLong("day") > 6) continue;
            out.add("RM|Newcomers: " + e.getString("title") + " — " + e.getString("verdict") + " (aye " + e.getInt("ayes") + ", nay " + e.getInt("nays") + ").");
        }
        return out;
    }

    /** The books' lines: the newcomers line, then each party and how it went, and how the settled ones are getting on. */
    static List<String> book(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        ListTag log = CivicRecord.list(CivicRecord.town(village), "newcomers");
        int in = 0, away = 0, folk = 0;
        for (Tag t : log) {
            if (!(t instanceof CompoundTag e)) continue;
            if (e.getString("verdict").equals("taken in")) in++; else away++;
        }
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && CivicRecord.known(f.getUUID()) && CivicRecord.folk(f.getUUID()).contains("cameOn")) folk++;
        }
        if (in + away > 0 || folk > 0) {
            out.add("Newcomers: " + in + (in == 1 ? " party" : " parties") + " taken in, " + away + " turned away; " + folk
                + " of our folk came to us that way.");
        }
        for (int i = log.size() - 1, k = 0; i >= 0 && k < 6; i--, k++) {
            if (!(log.get(i) instanceof CompoundTag e)) continue;
            out.add("Day " + (e.getLong("day") + 1) + ": " + e.getString("title") + " — " + e.getString("verdict") + " (aye " + e.getInt("ayes")
                + ", nay " + e.getInt("nays") + ")" + (e.getString("trades").isEmpty() ? "" : "; trades: " + e.getString("trades")) + ".");
        }
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || !CivicRecord.known(f.getUUID())) continue;
            CompoundTag t = CivicRecord.folk(f.getUUID());
            if (!t.contains("cameOn") || level.getDayTime() / 24000L - t.getLong("cameOn") > 14) continue;
            out.add(f.displayNameCap() + " (" + (f.stationTask() == StationTask.NONE ? "no trade yet" : JobMarket.noun(f.stationTask())) + "): came from "
                + t.getString("from") + ", " + t.getString("why") + (t.getString("fit").isEmpty() ? "; settling in." : "; " + t.getString("fit") + "."));
        }
        return out;
    }

    static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ the commands

    /**
     * "/village newcomers": the nearest town's newcomers, the parties on the road to it and how its votes went. For an
     * operator: "outside [n]" sends a party from the world outside to it now; "here" has whoever is on the road to it
     * camp at its edge now (and the town asked); "stage" does both for the pictures, a family of three.
     */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> command() {
        return net.minecraft.commands.Commands.literal("newcomers")
            .executes(ctx -> Referendums.say(ctx, near -> {
                List<String> lines = book(Referendums.level(ctx), near.id());
                List<String> road = board(Referendums.level(ctx), near.id());
                List<String> all = new ArrayList<>(lines);
                for (String b : road) all.add(b.substring(b.indexOf('|') + 1));
                return all.isEmpty() ? "No newcomers yet." : String.join("\n", all);
            }))
            .then(net.minecraft.commands.Commands.literal("outside").requires(src -> src.hasPermission(2))
                .executes(ctx -> Referendums.say(ctx, near -> "OUTSIDE " + outside(Referendums.level(ctx), near,
                    Referendums.level(ctx).getDayTime() / 24000L, -1)))
                .then(net.minecraft.commands.Commands.argument("n", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 3))
                    .executes(ctx -> Referendums.say(ctx, near -> "OUTSIDE " + outside(Referendums.level(ctx), near,
                        Referendums.level(ctx).getDayTime() / 24000L, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "n"))))))
            .then(net.minecraft.commands.Commands.literal("here").requires(src -> src.hasPermission(2))
                .executes(ctx -> Referendums.say(ctx, near -> {
                    StringBuilder sb = new StringBuilder();
                    for (String pid : new ArrayList<>(CivicRecord.parties().getAllKeys())) {
                        CompoundTag p = party(pid);
                        String st = p.getString("stage");
                        if (near.id().toString().equals(p.getString("target")) && (st.equals("road") || st.equals("away"))) {
                            sb.append("CAMPED ").append(pid).append(" question ").append(arriveForTests(Referendums.level(ctx), pid)).append('\n');
                        }
                    }
                    return sb.length() == 0 ? "NONE nobody on the road here" : sb.toString().trim();
                })))
            .then(net.minecraft.commands.Commands.literal("stage").requires(src -> src.hasPermission(2))
                .executes(ctx -> Referendums.say(ctx, near -> String.join("\n", CivicVotesStage.newcomers(Referendums.level(ctx), near)))));
    }

    // ------------------------------------------------------------------ tests and the stage

    /** Tests: these folk leave their town for that one, as refugees of this cause. Returns the party's number. */
    public static String sendForTests(ServerLevel level, Villages.Village from, Villages.Village to, List<VillageFolkEntity> household, Cause c) {
        return send(level, from, to, household, c);
    }

    /** Tests: what would send a household away from this town today (WAR, FAMINE), or "". */
    public static String causeForTests(ServerLevel level, Villages.Village v) {
        Cause c = cause(level, v, level.getDayTime() / 24000L);
        return c == null ? "" : c.name();
    }

    /** Tests: the town's own look today at whether a household leaves (as its daily round has it); the party's number, or "". */
    public static String leavingForTests(ServerLevel level, Villages.Village v) {
        CivicRecord.town(v.id()).remove("ncFled");
        int before = CivicRecord.parties().getAllKeys().size();
        leaving(level, v, level.getDayTime() / 24000L);
        String last = "";
        int best = -1;
        for (String k : CivicRecord.parties().getAllKeys()) {
            try {
                int n = Integer.parseInt(k);
                if (n > best) { best = n; last = k; }
            } catch (NumberFormatException ignored) { }
        }
        return CivicRecord.parties().getAllKeys().size() > before ? last : "";
    }

    /** Tests: a party from outside comes to this town now ({@code n} of them); its number, or null. */
    @Nullable
    public static String outsideForTests(ServerLevel level, Villages.Village v, int n) {
        return outside(level, v, level.getDayTime() / 24000L, n);
    }

    /** Tests: the party is at the camp now (its walk done) and the town is asked; returns the question's number. */
    public static int arriveForTests(ServerLevel level, String pid) {
        CompoundTag p = party(pid);
        UUID target = uuid(p.getString("target"));
        Villages.Village town = target == null ? null : Villages.get(target);
        if (town == null) return -1;
        for (VillageFolkEntity f : members(level, p)) {
            BlockPos s = spot(p, f);
            f.moveTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, f.getYRot(), 0.0F);
            release(level, f);
        }
        arrived(level, town, pid, level.getDayTime() / 24000L);
        return party(pid).getInt("question");
    }

    /** Tests: the party's members, alive and loaded. */
    public static List<VillageFolkEntity> membersForTests(ServerLevel level, String pid) {
        return members(level, party(pid));
    }

    /** Tests: where the party stands ("road", "camp", "in", "away", "home", "gone"), and its title. */
    public static String stageForTests(String pid) {
        CompoundTag p = party(pid);
        return p.getString("stage") + "|" + title(p) + "|" + p.getString("target");
    }

    /** Tests: one look at the settling in of a town's newcomers, as on the day given. */
    public static void settlingForTests(ServerLevel level, Villages.Village v, long day) {
        settling(level, v, day);
    }

    /** Tests: how a settled newcomer is getting on (its card line), or "". */
    public static String settledForTests(VillageFolkEntity f) {
        return cardLine(f);
    }
}
