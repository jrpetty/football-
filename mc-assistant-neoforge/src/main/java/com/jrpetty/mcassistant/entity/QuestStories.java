package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.QuestBook.Quest;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [quests] The town's stories: the big quests, rarer than favours, that the town's state brings about and that play
 * out over days with its real folk, at real places, with real things.
 *
 * <ul>
 * <li><b>Lost at Dusk</b> (StoryLostChild): a child of a real household does not come home; the clues, the toy in the
 *     grass, the tracks, the child found, its secret, and the way home.</li>
 * <li><b>The Smugglers' Cave</b> (StorySmugglers): goods go out of the stores to a camp in a real cave; a folk flush
 *     with coin, lights at the cave mouth by night, the ledger; turn them in, take a cut, or find the ringleader.</li>
 * <li><b>The Cursed Mine</b> (StoryCursedMine): the miners will not go down; the old miner's tale and journal, the
 *     deep level, the spawner or the monsters, the light, the mine reopened with a feast or a plaque.</li>
 * <li><b>The Stolen Heirloom</b> (StoryHeirloom): a family's ring or locket taken; the chest, the suspects, the
 *     accusation, the recovery (bought, bargained for or demanded), and the reunion.</li>
 * </ul>
 * One story at a time in a town, never two running; at least four days between one ending and the next beginning;
 * and only with a player about the town that the town knows (each story asks its own standing: the smugglers'
 * are only trusted to a friend of the town). A story's journal lists its chapters as they come and ends with a page.
 */
public final class QuestStories {

    private QuestStories() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** Days between one story's end and the next one's beginning, in a town. */
    static final int GAP_DAYS = 4;
    /** A story begins on one look in so many (a look is half a minute), when everything else allows it. */
    static final int ODDS = 24;

    /** A story: a script that also knows how to begin itself out of the town's state. */
    interface Story extends QuestRun.Script {
        String key();

        /**
         * The town as it stands calls for it now: its folk and places chosen (or, for the tests, given), the world
         * made ready (the theft done, the child gone), and the offer made. Null if it cannot, with the reason given
         * to {@link #why(UUID, String)}.
         */
        @Nullable
        Quest begin(ServerLevel level, Villages.Village v, long day, Map<String, Object> given, boolean forced);
    }

    private static final Map<String, Story> STORIES = new LinkedHashMap<>();
    private static final Map<UUID, String> WHY = new ConcurrentHashMap<>();

    static void register() {
        add(StoryLostChild.STORY);
        add(StorySmugglers.STORY);
        add(StoryCursedMine.STORY);
        add(StoryHeirloom.STORY);
    }

    private static void add(Story s) {
        STORIES.put(s.key(), s);
        QuestRun.script("story." + s.key(), s);
    }

    static void resetForTests() {
        WHY.clear();
        StoryLostChild.resetForTests();
    }

    static void why(UUID village, String why) {
        WHY.put(village, why);
    }

    static String why(UUID village) {
        return WHY.getOrDefault(village, "the town's state does not call for it");
    }

    // ------------------------------------------------------------------ beginning

    /** Now and then, a town whose state calls for a story, with a player about it the town knows, has one. */
    static void look(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (QuestBook.story(id) != null || day - QuestBook.storyEnded(id) < GAP_DAYS || Villages.headcount(id) < 6) return;
        Player near = null;
        for (Player p : level.players()) {
            if (p.isSpectator() || p.distanceToSqr(v.centre().getX(), v.centre().getY(), v.centre().getZ()) > 112.0 * 112.0) continue;
            if (Standing.of(id, p.getUUID(), level.getGameTime()).title().atLeast(Standing.Title.VISITOR)) { near = p; break; }
        }
        if (near == null || level.getRandom().nextInt(ODDS) != 0) return;
        List<String> keys = new ArrayList<>(STORIES.keySet());
        java.util.Collections.shuffle(keys, new java.util.Random(level.getGameTime()));
        for (String k : keys) if (begin(level, v, k, near, false) != null) return;
    }

    /** A story begun now, if the town's state allows it (forced: whatever the hour and the odds). */
    @Nullable
    static Quest begin(ServerLevel level, Villages.Village v, String which, @Nullable Player near, boolean forced) {
        return beginWith(level, v, which, Map.of(), forced);
    }

    @Nullable
    private static Quest beginWith(ServerLevel level, Villages.Village v, String which, Map<String, Object> given, boolean forced) {
        QuestRun.script(new Quest());                                   // the scripts registered
        Story s = STORIES.get(which);
        if (s == null) {
            why(v.id(), "there is no story called \"" + which + "\" (" + String.join(", ", STORIES.keySet()) + ")");
            return null;
        }
        if (QuestBook.story(v.id()) != null) {
            why(v.id(), "a story is running there already: " + QuestBook.story(v.id()).title);
            return null;
        }
        try {
            Quest q = s.begin(level, v, QuestRun.day(level), given, forced);
            if (q != null) LOG.info("[MCA-QUESTS] a story begins in {}: \"{}\" (#{})", Villages.name(v.id()), q.title, q.id);
            return q;
        } catch (RuntimeException e) {
            LOG.warn("[MCA-QUESTS] the story {} would not begin in {}: {}", which, Villages.name(v.id()), e.toString());
            why(v.id(), "it went wrong: " + e);
            return null;
        }
    }

    /**
     * Tests: a story begun with its folk and places given ("child", "parent", "camp"... as each story names them),
     * the rest chosen as the town would. The offer, or null (and why()).
     */
    @Nullable
    public static Quest beginForTests(ServerLevel level, Villages.Village v, String which, Map<String, Object> given) {
        return beginWith(level, v, which, given, true);
    }

    /** Tests: why the last story could not begin. */
    public static String whyForTests(UUID village) {
        return why(village);
    }

    /** Tests: is this folk held to its part in a story just now (a miner refusing to go down, a lost child kept lost)? */
    public static boolean heldForTests(VillageFolkEntity f, ServerLevel level) {
        QuestRun.recast();
        return QuestRun.hold(f, level);
    }

    // ------------------------------------------------------------------ who and where

    /** Does any open story have a part for this folk? (The favours leave it be.) */
    static boolean cast(UUID folk) {
        for (Quest q : QuestBook.all()) if (q.open() && q.story() && QuestRun.castOf(q).contains(folk)) return true;
        return false;
    }

    /** Is this place a story's (the cursed mine's spawner): the favours leave it be. */
    static boolean claimed(BlockPos at) {
        String k = Long.toString(at.asLong());
        for (Quest q : QuestBook.all()) if (q.open() && q.story() && k.equals(q.flag("place"))) return true;
        return false;
    }

    @Nullable
    static Object given(Map<String, Object> given, String key) {
        return given == null ? null : given.get(key);
    }

    @Nullable
    static VillageFolkEntity folk(Map<String, Object> given, String key) {
        return given(given, key) instanceof VillageFolkEntity f ? f : null;
    }

    @Nullable
    static BlockPos pos(Map<String, Object> given, String key) {
        return given(given, key) instanceof BlockPos p ? p : null;
    }

    static String text(Map<String, Object> given, String key) {
        return given(given, key) instanceof String s ? s : "";
    }

    static void cast(Quest q, String role, VillageFolkEntity f) {
        q.flags.put("cast." + role, f.getStringUUID());
        q.flags.put("name." + role, f.displayNameCap());
    }

    static String name(Quest q, String role) {
        return q.flag("name." + role);
    }

    @Nullable
    static VillageFolkEntity role(ServerLevel level, Quest q, String role) {
        return Civics.find(level, q.flagId("cast." + role));
    }

    @Nullable
    static UUID roleId(Quest q, String role) {
        return q.flagId("cast." + role);
    }

    public static BlockPos place(Quest q, String key) {
        return BlockPos.of(Long.parseLong(q.flag(key)));
    }

    static void place(Quest q, String key, BlockPos p) {
        q.flags.put(key, Long.toString(p.asLong()));
    }

    /** The ground at a column: the free block above the highest solid one (leaves not counted). */
    static BlockPos surface(ServerLevel level, int x, int z) {
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /** So far of the way from one place to another, on the ground. */
    static BlockPos along(ServerLevel level, BlockPos from, BlockPos to, double part) {
        int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * part);
        int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * part);
        return level.isLoaded(new BlockPos(x, 64, z)) ? surface(level, x, z) : new BlockPos(x, to.getY(), z);
    }

    static String where(BlockPos from, BlockPos to) {
        return QuestMaker.where(from, to);
    }

    static boolean watched(ServerLevel level, BlockPos at, double r) {
        for (Player p : level.players()) if (!p.isSpectator() && p.distanceToSqr(at.getX(), at.getY(), at.getZ()) < r * r) return true;
        return false;
    }

    static RandomSource random(ServerLevel level) {
        return level.getRandom();
    }

    /**
     * A folk keeping at a player's heels (a lost child led home, the cave dweller come down the mine), by night as by
     * day: a folk's own walking with a player gives up at dusk, and these cannot. With the player gone off or out of
     * the world, it waits where it is. True: its day waits on this.
     */
    static boolean follow(VillageFolkEntity f, ServerLevel level, @Nullable UUID player) {
        Player p = player == null ? null : level.getPlayerByUUID(player);
        if (p == null || !p.isAlive() || p.distanceToSqr(f) > 64.0 * 64.0) {
            f.getNavigation().stop();
            return true;
        }
        f.getLookControl().setLookAt(p, 30.0F, 30.0F);
        if (p.distanceToSqr(f) > 3.0 * 3.0) {
            Civics.goTo(f, p.blockPosition(), 2.5, 1.0);
        } else {
            f.getNavigation().stop();
        }
        return true;
    }

    // ------------------------------------------------------------------ marks in the world

    /**
     * A standing sign put up at a place (a sign out of the stores, or two planks), with these four lines: a story's
     * mark left in the world. False if the stores cannot run to one or there is no room.
     */
    static boolean sign(ServerLevel level, Villages.Village v, BlockPos at, String[] lines) {
        if (!level.isLoaded(at)) return false;
        BlockPos spot = null;
        for (BlockPos p : new BlockPos[]{ at, at.east(), at.west(), at.north(), at.south() }) {
            BlockState here = level.getBlockState(p);
            if (here.canBeReplaced() && !level.getBlockState(p.below()).isAir() && level.getFluidState(p).isEmpty()) { spot = p; break; }
        }
        if (spot == null || !Crafts.sign(level, v)) return false;
        level.setBlock(spot, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 0), 3);
        if (level.getBlockEntity(spot) instanceof SignBlockEntity s) TownLife.write(s, lines);
        return true;
    }

    // ------------------------------------------------------------------ the trial

    /** What a trial came to: guilty or not, the fines paid into the treasury, and the court's words. */
    public record Verdict(boolean guilty, int fined, String words) {}

    /**
     * Who hears a charge a story brings: the town's council by default. The court of a later day (petty crime and
     * its detectives) can hear them instead.
     */
    public interface Trial {
        Verdict hear(ServerLevel level, Villages.Village v, List<VillageFolkEntity> accused, String charge, boolean evidence, Player accuser);
    }

    private static volatile Trial trial = QuestStories::council;

    /** [crime] Let another court hear the stories' charges. */
    public static void trials(Trial t) {
        trial = t;
    }

    static Verdict hear(ServerLevel level, Villages.Village v, List<VillageFolkEntity> accused, String charge, boolean evidence, Player accuser) {
        return trial.hear(level, v, accused, charge, evidence, accuser);
    }

    /**
     * The council hears it in the hall: each councillor (the accused apart) votes guilty on the evidence, unless it
     * is a close friend of one of the accused, who votes to let them off. Guilty, each accused is fined twice the
     * share it had (ten coins at least), out of its purse into the treasury, the town thinks the less of it (and a
     * councillor among them loses its place by it), and it does not forget who brought it to book.
     */
    static Verdict council(ServerLevel level, Villages.Village v, List<VillageFolkEntity> accused, String charge, boolean evidence, Player accuser) {
        List<UUID> ids = new ArrayList<>();
        for (VillageFolkEntity a : accused) ids.add(a.getUUID());
        int guilty = 0, off = 0;
        List<String> friends = new ArrayList<>();
        for (VillageFolkEntity m : Council.members(v.id())) {
            if (ids.contains(m.getUUID())) continue;
            boolean friend = false;
            for (UUID a : ids) friend |= m.life().affinity(a) >= Social.CLOSE;
            if (friend) {
                off++;
                friends.add(m.displayNameCap());
            } else if (evidence) guilty++;
            else off++;
        }
        boolean verdict = guilty > off;
        long day = QuestRun.day(level);
        String name = accuser.getName().getString();
        int fined = 0;
        List<String> names = new ArrayList<>();
        for (VillageFolkEntity a : accused) names.add(a.displayNameCap());
        String who = String.join(" and ", names);
        if (verdict) {
            for (VillageFolkEntity a : accused) {
                int fine = Math.min(a.purse(), 10);
                if (fine > 0 && a.spend(fine)) {
                    Ledger.addCoins(v.id(), fine);
                    fined += fine;
                }
                a.persona().feelFor(accuser.getUUID(), name, -40);
                a.persona().remember(day, "I was tried for " + charge + ", and found guilty, on " + name + "'s word", 6);
                for (AssistantEntity o : Villages.folkOf(v.id())) {
                    if (o instanceof VillageFolkEntity g && g != a && !g.isShowcase()) g.life().feel(a.getUUID(), a.displayNameCap(), -20);
                }
            }
            Villages.tell(v.id(), day, who + " were tried before the council for " + charge + " and found guilty, on the evidence " + name + " brought; fined "
                + fined + " coins");
            return new Verdict(true, fined, "The council finds " + who + " guilty of " + charge + ", and fines them " + fined + " coins.");
        }
        for (VillageFolkEntity a : accused) a.persona().feelFor(accuser.getUUID(), name, -20);
        Villages.tell(v.id(), day, who + " were tried before the council for " + charge + ", and let off" + (friends.isEmpty() ? "" : " (" + String.join(", ", friends)
            + " would not hear a word against them)"));
        return new Verdict(false, 0, "The council lets " + who + " off" + (friends.isEmpty() ? "." : ": " + String.join(" and ", friends) + " stood by them."));
    }

    // ------------------------------------------------------------------ what may yet come out

    /** Once a day for a town: a cut a player took to keep quiet may come out (its odds a day in the quest's "odds"). */
    static void secrets(ServerLevel level, Villages.Village v, long day) {
        for (Map.Entry<Integer, Long> e : QuestBook.secrets().entrySet()) {
            Quest q = QuestBook.get(e.getKey());
            if (q == null || !v.id().equals(q.village)) {
                if (q == null) QuestBook.kept(e.getKey());
                continue;
            }
            if (day > e.getValue()) {
                QuestBook.kept(q.id);
                continue;
            }
            if (Long.toString(day).equals(q.flag("secret.day"))) continue;
            q.flags.put("secret.day", Long.toString(day));
            int odds = Math.max(2, QuestRewards.num(q.flag("odds")));
            if (level.getRandom().nextInt(odds) != 0) continue;
            comesOut(level, v, q, day);
        }
    }

    /** It came out: the town thinks the worse of the player, and the giver most. */
    static void comesOut(ServerLevel level, Villages.Village v, Quest q, long day) {
        if (q.player == null) return;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isShowcase()) continue;
            f.persona().feelFor(q.player, q.playerName, f.getUUID().equals(q.giver) ? -25 : -8);
        }
        Standing.stir(v.id(), q.player);
        String what = q.flag("secret");
        Villages.tell(v.id(), day, "it came out that " + q.playerName + " " + (what.isEmpty() ? "kept quiet for a price" : what));
        q.outcome = q.outcome + " — and it came out";
        q.flags.put("gossip", "Did you hear? {who} " + (what.isEmpty() ? "kept quiet for a price" : what) + "! And we trusted them.");
        QuestBook.kept(q.id);
        LOG.info("[MCA-QUESTS] it came out in {} that {} {}", Villages.name(v.id()), q.playerName, what);
    }

    /** Tests: the secret comes out now, as it might any day. */
    public static void comesOutForTests(ServerLevel level, Quest q) {
        Villages.Village v = Villages.get(q.village);
        if (v != null) comesOut(level, v, q, QuestRun.day(level));
    }

    /** The direction for a sign: facing the town from a place. */
    static Direction facing(BlockPos from, BlockPos town) {
        int dx = town.getX() - from.getX(), dz = town.getZ() - from.getZ();
        return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
    }
}
