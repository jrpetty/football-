package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.ChunkLoad;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Friends from other towns. [batchG] Folk who have a friend or family living in another town (one moved
 * there on the job market, or went out with the founders of a colony, or a child grown and gone) now and
 * then walk over to see them for the day.
 * <ul>
 * <li>Each town sends one at most every three days, in the morning: whoever has the warmest tie to somebody
 *     in a town within five hundred blocks (family first), not the watch, not the leader, not anybody away
 *     on the road already.</li>
 * <li>It goes on foot, as an envoy does: out along its own town's avenue, down the road, in along the
 *     other's (Caravans.way), the ground about it kept awake as it goes.</li>
 * <li>At the other town it finds its friend wherever it is, and is welcomed. They eat together, each a bite
 *     of their own (the host's out of its own pack or its town's stores, the guest's out of its pack), and the
 *     guest keeps its friend company the rest of the day.</li>
 * <li>In the afternoon it walks home. Both remember the day, think the warmer of each other for it (a
 *     friendship kept up across the miles does not fade away), and are the happier for a day or two. Both
 *     towns' chronicles take it down.</li>
 * </ul>
 * While it is away its own day waits (Visitors.drive, from VillageFolkEntity.aiStep), and the town's work
 * does not call on it (TownJobs.fit). An outing is not saved: a folk away when the world is closed simply
 * takes up its own day where it stands when it is opened, and walks home to work.
 */
public final class FriendVisits {

    private FriendVisits() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** How far a friend may live for a day's visit (heart to heart). */
    static final int FAR = 500;
    /** Days between visits out of one town. */
    static final int GAP = 3;

    /** A folk's day away: from where, to where, to see whom, and how far along. */
    static final class Outing {
        final UUID home, town, friend;
        final String friendName, tie;
        final List<BlockPos> way;
        int at;
        /** "going", "with" (at the friend's town), "home". */
        String stage = "going";
        boolean met;
        int tick;
        @Nullable BlockPos window;
        final Visitors.Walk walk = new Visitors.Walk();

        Outing(UUID home, UUID town, UUID friend, String friendName, String tie, List<BlockPos> way) {
            this.home = home;
            this.town = town;
            this.friend = friend;
            this.friendName = friendName;
            this.tie = tie;
            this.way = way;
        }
    }

    private static final Map<UUID, Outing> AWAY = new ConcurrentHashMap<>();
    /** The friend each folk last spent a day with, and the day. */
    private static final Map<UUID, String> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_DAY = new ConcurrentHashMap<>();
    /** The day each town last looked for somebody to send. */
    private static final Map<UUID, Long> LOOKED = new ConcurrentHashMap<>();

    static void resetForTests() {
        AWAY.clear();
        LAST.clear();
        LAST_DAY.clear();
        LOOKED.clear();
    }

    /** Is this folk away for the day at a friend's in another town? */
    static boolean away(VillageFolkEntity f) {
        return !AWAY.isEmpty() && AWAY.containsKey(f.getUUID());
    }

    /** Tests: is it away? */
    public static boolean awayForTests(VillageFolkEntity f) {
        return away(f);
    }

    // ------------------------------------------------------------------ who goes

    /** A friend, or family, in another town: who, which town, what they are to each other, and how warm. */
    record Tie(VillageFolkEntity who, Villages.Village town, String words, int score) {}

    /** The warmest tie this folk has in another town near enough, family first; null if none. */
    @Nullable
    static Tie tieElsewhere(VillageFolkEntity f, Villages.Village home) {
        Tie best = null;
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(home.id()) || !o.dim().equals(home.dim())) continue;
            if (o.centre().distSqr(home.centre()) > (double) FAR * FAR) continue;
            for (AssistantEntity a : Villages.folkOf(o.id())) {
                if (!(a instanceof VillageFolkEntity g) || !g.isAlive()) continue;
                int warmth = f.life().affinity(g.getUUID());
                String words = f.parentIds().contains(g.getUUID()) ? "its parent"
                    : g.parentIds().contains(f.getUUID()) ? "its child"
                    : g.getUUID().equals(f.life().partner()) ? "its partner" : null;
                int score;
                if (words != null) score = 100 + warmth;
                else if (warmth >= Social.FRIEND) {
                    words = "its friend";
                    score = warmth;
                } else continue;
                if (best == null || score > best.score()) best = new Tie(g, o, words, score);
            }
        }
        return best;
    }

    /** Can this folk go for the day: grown, about the town, not the watch, the leader or anybody busy away? */
    static boolean free(VillageFolkEntity f) {
        if (!f.isAlive() || f.isBaby() || f.isSleeping() || f.isHired() || f.isShowcase()) return false;
        if (f.stationTask() == AssistantEntity.StationTask.GUARD || f.isElder()) return false;
        if (f.trip() != null || f.expedition() != null || Nether.away(f) || Scouts.out(f) || away(f)) return false;
        return f.talkPartner() == null && f.companionPlayer() == null && f.guidePlayer() == null;
    }

    /** The town's look each morning (Visitors.tick): somebody to send, every three days at most. */
    static void tick(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        if (t < 1000L || t > 3500L) return;
        Long looked = LOOKED.get(id);
        if (looked != null && looked == day) return;
        LOOKED.put(id, day);
        long last = Bard.parse(Ledger.note(id, "visit/friends"));
        if (last >= 0 && day >= last && day - last < GAP) return;
        if (Raids.underAlarm(id) || Weather.stormy(level) || level.isRaining()) return;
        String sent = send(level, v, day);
        if (sent != null) LOG.info("[MCA-VISIT] {}", sent);
    }

    /** The one with the warmest tie elsewhere sets out. What happened, in words, or null if nobody went. */
    @Nullable
    static String send(ServerLevel level, Villages.Village v, long day) {
        VillageFolkEntity who = null;
        Tie tie = null;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || !free(f)) continue;
            Tie t = tieElsewhere(f, v);
            if (t == null || Raids.underAlarm(t.town().id())) continue;
            if (tie == null || t.score() > tie.score()) { who = f; tie = t; }
        }
        if (who == null) return null;
        setOut(level, who, v, tie, day);
        return who.displayNameCap() + " of " + Villages.name(v.id()) + " walks over to " + Villages.name(tie.town().id())
            + " to see " + tie.words() + " " + tie.who().displayNameCap();
    }

    static void setOut(ServerLevel level, VillageFolkEntity f, Villages.Village home, Tie tie, long day) {
        Outing o = new Outing(home.id(), tie.town().id(), tie.who().getUUID(), tie.who().displayNameCap(), tie.words(),
            Caravans.way(home, tie.town()));
        AWAY.put(f.getUUID(), o);
        f.clearQueue();
        f.getNavigation().stop();
        String town = Villages.name(tie.town().id());
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Off to " + town + " to see " + o.friendName + " — back by dark!",
            "I'm away to " + town + " for the day. " + o.friendName + " won't believe it!"));
        Villages.tell(home.id(), day, f.displayNameCap() + " walked over to " + town + " to see " + tie.words() + " " + o.friendName);
        Ledger.note(home.id(), "visit/friends", Long.toString(day));
        log(home.id(), day, f.displayNameCap() + " to " + town + " (" + o.friendName + ")");
    }

    // ------------------------------------------------------------------ the day

    /** Its day away, every tick (Visitors.drive): there, the visit, and home. True while it is away. */
    static boolean drive(VillageFolkEntity f, ServerLevel level) {
        Outing o = AWAY.get(f.getUUID());
        if (o == null) return false;
        if (!f.isAlive() || f.trip() != null || f.expedition() != null || f.isHired()) {
            end(level, f, o, false);
            return false;
        }
        Player p = f.talkPartner();
        if (p != null) {
            f.getNavigation().stop();
            f.getLookControl().setLookAt(p, 30.0F, 30.0F);
            return true;
        }
        if (++o.tick % 10 != 0) return true;
        keepAwake(level, f, o);
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        if (f.isSleeping()) f.stopSleeping();
        switch (o.stage) {
            case "going" -> {
                if (o.at >= o.way.size()) {
                    o.stage = "with";
                    o.walk.reset();
                    break;
                }
                if (Visitors.walk(f, level, o.way.get(o.at), 4.0, 0.85D, o.walk)) o.at++;
            }
            case "with" -> {
                if (t >= 9500L || t < 1000L) {
                    if (!o.met) LOG.info("[MCA-VISIT] {} never found {} in {}", f.displayNameCap(), o.friendName, Villages.name(o.town));
                    goHome(level, f, o, day);
                    break;
                }
                VillageFolkEntity friend = level.getEntity(o.friend) instanceof VillageFolkEntity g && g.isAlive() ? g : null;
                if (friend == null) {
                    Villages.Village there = Villages.get(o.town);
                    if (there != null) Visitors.walk(f, level, there.centre(), 5.0, 0.6D, o.walk);
                    break;
                }
                if (!o.met) {
                    if (Visitors.walk(f, level, friend.blockPosition(), 2.5, 0.8D, o.walk)) welcome(level, f, friend, o, day);
                } else if (f.distanceToSqr(friend) > 6.0 * 6.0) {
                    Visitors.walk(f, level, friend.blockPosition(), 3.0, 0.8D, o.walk);
                } else {
                    f.getNavigation().stop();
                    f.getLookControl().setLookAt(friend, 20.0F, 20.0F);
                }
            }
            default -> {
                if (o.at >= o.way.size()) {
                    end(level, f, o, true);
                    return false;
                }
                if (Visitors.walk(f, level, o.way.get(o.at), 4.0, 0.85D, o.walk)) o.at++;
            }
        }
        return true;
    }

    /** At last: welcomed, and a bite to eat together. */
    static void welcome(ServerLevel level, VillageFolkEntity f, VillageFolkEntity friend, Outing o, long day) {
        o.met = true;
        String home = Villages.name(o.home), town = Villages.name(o.town);
        String me = f.displayNameCap();
        friend.getNavigation().stop();
        friend.getLookControl().setLookAt(f, 30.0F, 30.0F);
        f.getLookControl().setLookAt(friend, 30.0F, 30.0F);
        FolkTalk.speak(friend, FolkTalk.pick(friend.getRandom(), me + "! You came all the way from " + home + "!",
            "Well, look who it is! Come here, " + me + "!"));
        f.sayLater(FolkTalk.pick(f.getRandom(), "I couldn't stay away. How are you keeping?", "I've missed you. Have you eaten?"), 50);
        // A bite each: the host's out of its own pack or its town's stores, the guest's out of its own pack (or
        // the host's town's, as a host would give it).
        Villages.Village there = Villages.get(o.town);
        ItemStack hostBite = bite(level, friend, there);
        ItemStack guestBite = bite(level, f, null);
        if (guestBite.isEmpty()) guestBite = bite(level, null, there);
        eat(level, friend, hostBite);
        eat(level, f, guestBite);
        f.life().feel(friend.getUUID(), friend.displayNameCap(), 10);
        friend.life().feel(f.getUUID(), me, 10);
        f.persona().remember(day, "spent the day with " + o.friendName + " in " + town, 5);
        friend.persona().remember(day, me + " came over from " + home + " to see me", 5);
        Visitors.visited(f, day);
        Visitors.visited(friend, day);
        LAST.put(f.getUUID(), o.friendName);
        LAST_DAY.put(f.getUUID(), day);
        LAST.put(friend.getUUID(), me);
        LAST_DAY.put(friend.getUUID(), day);
        Villages.tell(o.town, day, me + " of " + home + " came to see " + o.friendName);
        log(o.town, day, me + " from " + home + " (to see " + o.friendName + ")");
        f.refreshMood();
        friend.refreshMood();
    }

    private static final Predicate<ItemStack> FOOD = s -> s.get(DataComponents.FOOD) != null && !Homes.isKeepsake(s);

    /** One bite of food: out of this folk's pack, or (no folk, or none in its pack) the town's stores. */
    private static ItemStack bite(ServerLevel level, @Nullable VillageFolkEntity f, @Nullable Villages.Village town) {
        if (f != null) {
            for (ItemStack s : f.getInventoryItems()) {
                if (!s.isEmpty() && FOOD.test(s)) {
                    ItemStack one = s.copyWithCount(1);
                    s.shrink(1);
                    return one;
                }
            }
        }
        if (town == null) return ItemStack.EMPTY;
        for (BlockPos p : Villages.storeChests(level, town.id())) {
            if (!level.isLoaded(p) || !(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !FOOD.test(s)) continue;
                ItemStack one = s.copyWithCount(1);
                if (TownWork.take(level, town, x -> ItemStack.isSameItemSameComponents(x, one), 1)) return one;
            }
        }
        return ItemStack.EMPTY;
    }

    private static void eat(ServerLevel level, VillageFolkEntity f, ItemStack food) {
        if (food.isEmpty()) return;
        level.playSound(null, f.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.8F, 1.0F);
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, food), f.getX(), f.getY() + 1.6, f.getZ(), 6, 0.2, 0.1, 0.2, 0.05);
        f.heal(2.0F);
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    private static void goHome(ServerLevel level, VillageFolkEntity f, Outing o, long day) {
        o.stage = "home";
        o.walk.reset();
        java.util.Collections.reverse(o.way);
        Villages.Village home = Villages.get(o.home);
        if (home != null) o.way.add(home.centre());
        o.at = 0;
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "I'd best be getting home. Come and see us soon!", "Home before dark. It was so good to see you."));
    }

    /** Home (or the visit given up): the ground let go and its own day taken up again. */
    static void end(ServerLevel level, VillageFolkEntity f, Outing o, boolean home) {
        AWAY.remove(f.getUUID());
        if (o.window != null) ChunkLoad.setLoaded(level, loadKey(f), o.window, 1, false);
        o.window = null;
        if (home) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Home again. What a day!", "There's no place like home — but what a day."));
    }

    private static UUID loadKey(VillageFolkEntity f) {
        return UUID.nameUUIDFromBytes(("mca-friend-visit-" + f.getUUID()).getBytes());
    }

    /** The ground about it kept awake as it goes, as a caravan's is. */
    private static void keepAwake(ServerLevel level, VillageFolkEntity f, Outing o) {
        BlockPos here = f.blockPosition();
        if (o.window != null && o.window.distSqr(here) < 16 * 16) return;
        if (o.window != null) ChunkLoad.setLoaded(level, loadKey(f), o.window, 1, false);
        ChunkLoad.setLoaded(level, loadKey(f), here, 1, true);
        o.window = here.immutable();
    }

    // ------------------------------------------------------------------ what is shown

    /** What it is doing, for its card (FolkTalk.nowDoing), while it is away. */
    @Nullable
    static String doing(VillageFolkEntity f) {
        Outing o = AWAY.get(f.getUUID());
        if (o == null) return null;
        String town = Villages.name(o.town);
        return switch (o.stage) {
            case "going" -> "Walking over to " + town + " to see " + o.friendName;
            case "with" -> o.met ? "Spending the day with " + o.friendName + " in " + town : "Looking for " + o.friendName + " in " + town;
            default -> "Walking home from " + town;
        };
    }

    /** Its card: away now, or the last friend it spent a day with. */
    static String cardLine(VillageFolkEntity f) {
        Outing o = AWAY.get(f.getUUID());
        if (o != null) return "away for the day: " + o.tie.replace("its ", "") + " " + o.friendName + " in " + Villages.name(o.town);
        String last = LAST.get(f.getUUID());
        Long day = LAST_DAY.get(f.getUUID());
        return last == null || day == null ? "" : "a day with " + last + ", from another town, on day " + (day + 1);
    }

    /** The last friend it spent the day with ("" for none). */
    static String lastFriend(VillageFolkEntity f) {
        return LAST.getOrDefault(f.getUUID(), "");
    }

    private static void log(UUID town, long day, String line) {
        String was = Ledger.note(town, "visit/friendlog");
        List<String> all = new ArrayList<>();
        if (was != null && !was.isEmpty()) all.addAll(List.of(was.split(";")));
        all.add(day + "|" + line.replace(";", ",").replace("|", "/"));
        while (all.size() > 8) all.remove(0);
        Ledger.note(town, "visit/friendlog", String.join(";", all));
    }

    /** The books: the week's comings and goings between friends. */
    static List<String> bookLines(ServerLevel level, UUID town, long day) {
        List<String> out = new ArrayList<>();
        List<String> week = new ArrayList<>();
        String s = Ledger.note(town, "visit/friendlog");
        if (s != null && !s.isEmpty()) {
            for (String part : s.split(";")) {
                int bar = part.indexOf('|');
                if (bar <= 0) continue;
                try {
                    if (day - Long.parseLong(part.substring(0, bar)) <= 6) week.add(part.substring(bar + 1));
                } catch (NumberFormatException ignored) { }
            }
        }
        if (!week.isEmpty()) out.add("Friends' visits this week: " + String.join("; ", week) + ".");
        for (Map.Entry<UUID, Outing> e : AWAY.entrySet()) {
            if (!e.getValue().home.equals(town)) continue;
            if (level.getEntity(e.getKey()) instanceof VillageFolkEntity f) out.add("Away today: " + f.displayNameCap() + ", " + doing(f));
        }
        return out;
    }

    // ------------------------------------------------------------------ tests and the operators

    /** /village visitors friend: whoever has the warmest tie elsewhere sets out now. */
    static String sendForTests(ServerLevel level, Villages.Village v) {
        String sent = send(level, v, level.getDayTime() / 24000L);
        return sent == null ? "nobody here has a friend in a town near enough, or nobody is free" : sent;
    }

    /** Tests: the tie this folk has in another town (the friend), or null. */
    @Nullable
    public static VillageFolkEntity tieForTests(VillageFolkEntity f) {
        Villages.Village home = Villages.get(f.ownerId());
        Tie t = home == null ? null : tieElsewhere(f, home);
        return t == null ? null : t.who();
    }

    /** Tests: the folk sets out now to see its warmest tie elsewhere. */
    public static boolean setOutForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village home = Villages.get(f.ownerId());
        Tie t = home == null ? null : tieElsewhere(f, home);
        if (t == null) return false;
        setOut(level, f, home, t, level.getDayTime() / 24000L);
        return true;
    }

    /** Tests: there, by its friend, and welcomed (the walk done at once). */
    public static boolean welcomeForTests(ServerLevel level, VillageFolkEntity f) {
        Outing o = AWAY.get(f.getUUID());
        if (o == null || !(level.getEntity(o.friend) instanceof VillageFolkEntity friend)) return false;
        f.moveTo(friend.getX() + 1.5, friend.getY(), friend.getZ(), f.getYRot(), 0.0F);
        o.stage = "with";
        o.at = o.way.size();
        welcome(level, f, friend, o, level.getDayTime() / 24000L);
        return true;
    }

    /** Tests: home again (the walk back done at once). */
    public static void homeForTests(ServerLevel level, VillageFolkEntity f) {
        Outing o = AWAY.get(f.getUUID());
        if (o == null) return;
        Villages.Village home = Villages.get(o.home);
        if (home != null) f.moveTo(home.centre().getX() + 0.5, home.centre().getY(), home.centre().getZ() + 0.5, f.getYRot(), 0.0F);
        end(level, f, o, true);
    }

    /** Tests: the way it walks, as so many steps. */
    public static int wayForTests(VillageFolkEntity f) {
        Outing o = AWAY.get(f.getUUID());
        return o == null ? 0 : o.way.size();
    }
}
