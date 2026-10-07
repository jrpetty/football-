package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.SitWhenOrderedToGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Families: what a household does together, over and above sleeping under one roof (Homes).
 *
 * <ul>
 * <li><b>A pet.</b> A household with children takes in an animal from the wild near the town, the
 *     way a player would: one of the grown-ups takes raw cod or salmon (for a stray cat) or bones (for
 *     a wolf) out of the household's chest, else the stores, walks out to it and offers them one at a
 *     time, a chance in three each that it takes to them; a child names it. It is the household's
 *     (its keeper is the one who tamed it, and it moves house with the family): by day it trots after
 *     the household's children, wherever they are playing; at night, and while they are at school or
 *     asleep, it goes home and sits. One a household, no more.</li>
 * <li><b>The children's games.</b> Of an afternoon, after school and the morning's apprenticeship and
 *     before supper, the town's children gather in the park (or, with no park, the square) and play:
 *     tag one afternoon, hide-and-seek the next. In tag whoever is it chases the nearest and the rest run
 *     from it, and the one tagged counts to three; in hide-and-seek the seeker counts to ten aloud at the
 *     den while the others run off and crouch out of sight beside a wall, then hunts them out one by one,
 *     and the first found seeks next.</li>
 * <li><b>Supper at home.</b> At supper a household goes home and eats together round its own table, out
 *     of the household's chest first (then its own pack, then the stores), rather than wherever each of
 *     them happens to be when the hour comes. Meals waits for it, as long as there is time to get home.</li>
 * <li><b>A story at bedtime.</b> Most evenings, after supper, an old folk of the family (a grandparent,
 *     if one is about; else the eldest at home) sits down with the children and tells them a story out
 *     of the town's chronicle: a wedding, a death, a birth, a building opened, a raid beaten off, by the
 *     names of the folk it happened to and the day it was.</li>
 * <li><b>A garden.</b> A household with something put by plants a little flower bed in front of its
 *     house and a sapling to one side of it, once: the flowers and the sapling bought from the stores out
 *     of their purses.</li>
 * <li><b>Remembrance.</b> On the anniversary of a death (the town's year of four weeks: TownCalendar) the
 *     one closest to the dead (its partner, else a child, else a parent) takes a flower from the stores
 *     out to the grave, lays it in front of the stone and says a few words. A couple marks the
 *     anniversary of its wedding the same way: a word to each other, and the day the happier for it.</li>
 * </ul>
 *
 * <p>The town's part runs every ten seconds or so (tick, from a folk's agenda): who takes in a pet, who
 * plants a garden, whose grave is visited, whose anniversary it is. Each folk's part is {@link #hold},
 * from its own tick: an errand in hand, supper, a story, a game. [families]
 */
public final class Families {

    private Families() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** The children's afternoon: after the apprentices' mornings (VillageFolkEntity.childhood) and before supper. */
    static final long PLAY_FROM = 7000L, PLAY_TO = 10800L;
    /** A game lasts this long, and then the other game, if the afternoon has room for it. */
    static final long GAME_LENGTH = 3000L;
    /** Supper at home: from the start of the meal (Meals) to the evening proper. */
    static final long SUPPER_FROM = 11000L, SUPPER_TO = 13000L;
    /**
     * How long a household sits at its table, and how far away a folk will walk home for it: as far as Meals
     * ever sent one home to the household's chest. A hand further out than that eats where it is, sends for
     * a packed lunch, as it always has.
     */
    static final long SUPPER_SIT = 400L;
    static final int SUPPER_REACH = Meals.HOME_REACH;
    /** Supper's last ten minutes: no time left to get home, it eats where it is (Meals). */
    static final int SUPPER_LATE = 600;
    /** A bedtime story: after supper, till well after dark. */
    static final long STORY_FROM = 12000L, STORY_TO = 13800L;
    /** How near home a wild animal is taken in from. */
    static final int PET_REACH = 48;
    /** What a household offers a wild animal at most in one go. */
    static final int OFFERS = 6;
    /** What a household has to have in its purses to buy a garden. */
    static final int GARDEN_SAVINGS = 30;
    /** Flowers in a garden's bed. */
    static final int BED = 3;
    /** An errand given and not seen through in this long is let go (what it carried goes back). */
    static final long ERRAND_TIMEOUT = 12000L;

    // ------------------------------------------------------------------ what each is doing

    /** A folk the family has in hand: what it is doing, and when it was last looked at. */
    private record Held(String doing, int tick) {}

    private static final Map<UUID, Held> HELD = new ConcurrentHashMap<>();
    /** Folk sat down on the floor for a story (Park would stand them up). */
    private static final Set<UUID> SEATED = ConcurrentHashMap.newKeySet();
    /** What was said, the last few dozen lines ("Name: words"): for the tests and the log. */
    private static final Deque<String> SAID = new java.util.concurrent.ConcurrentLinkedDeque<>();
    /** When each village's round was last made. */
    private static final Map<UUID, Long> TICKED = new ConcurrentHashMap<>();

    public static void resetForTests() {
        HELD.clear();
        SEATED.clear();
        SAID.clear();
        TICKED.clear();
        ERRANDS.clear();
        PETS.clear();
        PET_LOOKED.clear();
        PET_SEEN.clear();
        PET_DRIVEN.clear();
        PET_DOING.clear();
        GAMES.clear();
        GAME_LOOKED.clear();
        SUPPER.clear();
        GRACE.clear();
        SUPPED.clear();
        STORIES.clear();
        TELLING.clear();
        TOLD.clear();
        GARDEN_LOOKED.clear();
        KEPT.clear();
        ANNIV.clear();
        Pets.resetForTests();               // [pets] the town's pets: their care, errands, young and strays
    }

    /**
     * From each folk's tick (VillageFolkEntity.aiStep, every fourth tick): the household's pet walked,
     * then whatever the family has this folk doing — supper at home, an errand, a story, a game. True
     * while it has; the rest of its day waits.
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        if (village == null || f.isShowcase() || f.isHired() || !f.isAlive()) return release(f);
        if (f.tickCount % 20 == 2) walkPet(level, f);
        if (f.isSleeping() || Raids.underAlarm(village)) return release(f);
        long dt = level.getDayTime(), t = dt % 24000L, day = dt / 24000L;
        String doing = supper(level, f, village, t, day);          // supper first: an errand waits till after it
        if (doing == null) doing = errand(level, f, t, day);
        if (doing == null) doing = Pets.hold(level, f, village, t, day);   // [pets] the bowl, a bed set out, a treat, a stray, a grave
        if (doing == null) doing = story(level, f, village, t, day);
        if (doing == null && f.isBaby()) doing = play(level, f, village, t, day);
        if (doing == null) return release(f);
        HELD.put(f.getUUID(), new Held(doing, f.tickCount));
        f.hobbyNow = doing;
        f.lastLeisureTick = f.tickCount;
        return true;
    }

    /** Is the family keeping this folk busy just now (between hold's looks)? */
    public static boolean busy(VillageFolkEntity f) {
        Held h = HELD.get(f.getUUID());
        return h != null && f.tickCount >= h.tick() && f.tickCount - h.tick() <= 8;
    }

    /** What the family has it doing now, for its card ("Playing hide-and-seek — hiding"), or null. */
    @Nullable
    public static String doing(VillageFolkEntity f) {
        Held h = HELD.get(f.getUUID());
        if (h == null || f.tickCount - h.tick() > 40 || h.doing().isEmpty()) return null;
        return Character.toUpperCase(h.doing().charAt(0)) + h.doing().substring(1);
    }

    /** Sat on the floor for a story: Park leaves it sat. */
    public static boolean seated(VillageFolkEntity f) {
        return SEATED.contains(f.getUUID());
    }

    private static boolean release(VillageFolkEntity f) {
        if (HELD.remove(f.getUUID()) != null) standUp(f);
        if (f.getPose() == Pose.CROUCHING) f.setPose(Pose.STANDING);
        return false;
    }

    private static void sit(VillageFolkEntity f) {
        f.getNavigation().stop();
        if (f.getPose() != Pose.SITTING) f.setPose(Pose.SITTING);
        SEATED.add(f.getUUID());
    }

    private static void standUp(VillageFolkEntity f) {
        if (SEATED.remove(f.getUUID()) && f.getPose() == Pose.SITTING) f.setPose(Pose.STANDING);
    }

    /** Free for the family: awake, off work (its break, the evening, the day of rest), nothing else in hand. */
    static boolean free(VillageFolkEntity f) {
        if (f.isBaby()) return childFree(f);
        return !f.isSleeping() && f.offWorkNow() && f.peekJob() == null && !TownJobs.busy(f) && !Assemblies.attending(f)
            && !School.teaching(f) && !Birthdays.busy(f) && !Raids.underAlarm(f.ownerId());
    }

    static boolean childFree(VillageFolkEntity f) {
        return f.isBaby() && !f.isSleeping() && !Assemblies.attending(f) && !Raids.underAlarm(f.ownerId());
    }

    /** Said out loud (FolkTalk), and kept for the tests. */
    static void say(VillageFolkEntity f, String text) {
        FolkTalk.speak(f, text);
        heard(f, text);
    }

    static void sayLater(VillageFolkEntity f, String text, int ticks) {
        f.sayLater(text, ticks);
        heard(f, text);
    }

    private static void heard(VillageFolkEntity f, String text) {
        SAID.addLast(f.displayNameCap() + ": " + text);
        while (SAID.size() > 96) SAID.pollFirst();
    }

    /** Walk somewhere, re-pathing now and then; true once within {@code near} of it. */
    private static boolean walk(VillageFolkEntity f, BlockPos to, double speed, double near) {
        if (f.distanceToSqr(to.getX() + 0.5, to.getY(), to.getZ() + 0.5) <= near * near) {
            f.getNavigation().stop();
            return true;
        }
        standUp(f);
        if (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 60 || f.tickCount < f.hobbyTick) {
            f.walkTo(to, speed);
            f.hobbyTick = f.tickCount;
        }
        return false;
    }

    @Nullable
    private static VillageFolkEntity folk(ServerLevel level, @Nullable UUID id) {
        return id != null && level.getEntity(id) instanceof VillageFolkEntity o && o.isAlive() ? o : null;
    }

    private static String key(UUID village, Homes.Home h) {
        return village + "/" + h.anchor.asLong();
    }

    private static List<VillageFolkEntity> children(UUID village, Homes.Home h) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (VillageFolkEntity m : Homes.loadedMembers(village, h)) if (m.isBaby() && m.isAlive()) out.add(m);
        return out;
    }

    private static List<VillageFolkEntity> grown(UUID village, Homes.Home h) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (VillageFolkEntity m : Homes.loadedMembers(village, h)) if (!m.isBaby() && m.isAlive()) out.add(m);
        return out;
    }

    /** The folk's household's home: a home it shares with somebody (a household of one is not a family's). */
    @Nullable
    static Homes.Home household(UUID village, VillageFolkEntity f) {
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        return h == null || h.members.size() < 2 ? null : h;
    }

    /** Where at home the family gathers: the middle of the house (its anchor), or a flat's chest. */
    static BlockPos hearth(ServerLevel level, UUID village, Homes.Home h) {
        if (Flats.isFlat(h)) {
            BlockPos chest = Homes.chestOf(level, village, h);
            if (chest != null) return chest;
        }
        return h.anchor;
    }

    /** Is it at home: in the house, or by its hearth? */
    static boolean atHome(UUID village, Homes.Home h, VillageFolkEntity f, BlockPos hearth) {
        return Homes.homeAt(village, f.blockPosition()) == h || f.blockPosition().distSqr(hearth) <= 4 * 4;
    }

    private static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Does this line name this folk (a whole word, not a part of a longer name)? */
    static boolean mentions(String text, String name) {
        if (name == null || name.isEmpty()) return false;
        int i = text.indexOf(name);
        while (i >= 0) {
            boolean before = i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
            int end = i + name.length();
            boolean after = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (before && after) return true;
            i = text.indexOf(name, i + 1);
        }
        return false;
    }

    /** "a poppy", "an oak sapling". */
    static String a(ItemStack s) {
        return Birthdays.a(s);
    }

    // ------------------------------------------------------------------ the town's round

    /**
     * Every ten seconds or so for each village (a folk's agenda): pets looked for and kept, gardens
     * planned, graves due a visit, couples whose anniversary it is.
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = TICKED.get(id);
        if (last != null && now - last < 200L && now >= last) return;
        TICKED.put(id, now);
        if (Raids.underAlarm(id)) return;
        long dt = level.getDayTime(), day = dt / 24000L, t = dt % 24000L;
        pets(level, v, day, t);
        Pets.tick(level, v, day, t);        // [pets] fed, the healer, their things, litters, strays, the merchant's pup
        gardens(level, v, day, t);
        remembrance(level, v, day, t);
        anniversaries(level, v, day, t);
    }

    // ------------------------------------------------------------------ errands: a pet, a garden, a grave

    enum Kind { PET, GARDEN, GRAVE }

    /** Something one of the family has been given to do for it, in its own time. */
    private static final class Errand {
        final Kind kind;
        final UUID village;
        final long anchor;
        final long day;
        final long given;
        boolean begun;
        // a pet
        @Nullable UUID animal;
        boolean cat;
        Predicate<ItemStack> food = s -> false;
        int carried, offered;
        long lastOffer;
        // a garden
        final List<BlockPos> beds = new ArrayList<>();
        @Nullable BlockPos tree;
        final List<ItemStack> plants = new ArrayList<>();
        final List<BlockPos> spots = new ArrayList<>();
        int done, flowers;
        boolean sapling;
        // a grave
        String dead = "";
        String kin = "";
        int years;
        @Nullable BlockPos lay;
        @Nullable ItemStack flower;

        Errand(Kind kind, UUID village, long anchor, long day, long given) {
            this.kind = kind;
            this.village = village;
            this.anchor = anchor;
            this.day = day;
            this.given = given;
        }
    }

    private static final Map<UUID, Errand> ERRANDS = new ConcurrentHashMap<>();

    /** Is any of these about an errand for the family already? */
    private static boolean anyErrand(List<VillageFolkEntity> who) {
        for (VillageFolkEntity m : who) if (ERRANDS.containsKey(m.getUUID())) return true;
        return false;
    }

    /** The errand's hours: by day, or of an evening (after supper, if it has a family), never in the dead of night. */
    private static boolean errandHours(long t) {
        return t >= 1000L && t < SUPPER_FROM || t >= 12600L && t < 13600L;
    }

    /** One step of this folk's errand, if it has one and the time for it: what it is doing, or null. */
    @Nullable
    private static String errand(ServerLevel level, VillageFolkEntity f, long t, long day) {
        Errand e = ERRANDS.get(f.getUUID());
        if (e == null) return null;
        long now = level.getGameTime();
        if (now - e.given > ERRAND_TIMEOUT || now < e.given || !e.begun && e.day != day) {
            drop(level, f, e, null);
            return null;
        }
        if (!free(f) || !errandHours(t)) return null;           // waits for its own time
        if (!e.begun) {
            if (!begin(level, f, e, day)) {
                drop(level, f, e, null);
                return null;
            }
            e.begun = true;
        }
        return switch (e.kind) {
            case PET -> petStep(level, f, e, day, false);
            case GARDEN -> gardenStep(level, f, e, day, false);
            case GRAVE -> graveStep(level, f, e, day, false);
        };
    }

    /** What it needs to set out with, from the chest, the stores or its own purse. False: it cannot. */
    private static boolean begin(ServerLevel level, VillageFolkEntity f, Errand e, long day) {
        Villages.Village v = Villages.get(e.village);
        if (v == null) return false;
        Homes.Home h = Homes.homes(e.village).get(e.anchor);
        RandomSource r = f.getRandom();
        switch (e.kind) {
            case PET -> {
                if (h == null || pet(e.village, e.anchor) != null) return false;
                // Out of the household's chest first, then the stores: a few to offer, one at a time.
                BlockPos chest = Homes.chestOf(level, e.village, h);
                if (chest != null && level.getBlockEntity(chest) instanceof Container c) {
                    for (int i = 0; i < c.getContainerSize() && e.carried < OFFERS; i++) {
                        ItemStack s = c.getItem(i);
                        if (s.isEmpty() || !e.food.test(s)) continue;
                        int n = Math.min(OFFERS - e.carried, s.getCount());
                        ItemStack left = f.insertGiven(s.copyWithCount(n));
                        int took = n - left.getCount();
                        s.shrink(took);
                        if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                        e.carried += took;
                    }
                    c.setChanged();
                }
                while (e.carried < OFFERS) {
                    ItemStack one = Crafts.takeOne(level, v, e.food);
                    if (one.isEmpty()) break;
                    ItemStack left = f.insertGiven(one);
                    if (!left.isEmpty()) {
                        Crafts.store(level, v, left);
                        break;
                    }
                    e.carried++;
                }
                if (e.carried <= 0) return false;
                List<VillageFolkEntity> kids = children(e.village, h);
                say(f, e.cat ? FolkTalk.pick(r, "There's a stray cat out there. A bit of fish might bring it home.",
                        "Let's see if that cat will come home with us.")
                    : FolkTalk.pick(r, "That wolf's been hanging about. A few bones, and we'll see.",
                        "A dog for the children — if it'll have us."));
                if (!kids.isEmpty()) sayLater(kids.get(r.nextInt(kids.size())), e.cat ? "A cat? Can we keep it?" : "A dog! Can we keep it?", 40);
                return true;
            }
            case GARDEN -> {
                if (h == null || gardened(e.village, e.anchor)) return false;
                List<VillageFolkEntity> payers = new ArrayList<>(grown(e.village, h));
                payers.remove(f);
                payers.add(0, f);
                for (int i = 0; i < e.beds.size(); i++) {
                    ItemStack fl = buy(level, v, payers, FLOWER);
                    if (fl.isEmpty()) break;
                    e.plants.add(fl);
                    e.spots.add(e.beds.get(i));
                }
                ItemStack tree = e.tree == null ? ItemStack.EMPTY : buy(level, v, payers, SAPLING);
                if (!tree.isEmpty()) {
                    e.plants.add(tree);
                    e.spots.add(e.tree);
                }
                if (e.plants.isEmpty()) return false;
                for (ItemStack s : e.plants) {
                    ItemStack left = f.insertGiven(s.copy());
                    if (!left.isEmpty()) Crafts.store(level, v, left);
                }
                say(f, FolkTalk.pick(r, "A few flowers by the door, and a tree. We've the coin for it now.",
                    "Time we had a garden. I've bought the flowers.", "Flowers for the front of the house — and a little tree."));
                return true;
            }
            case GRAVE -> {
                if (e.lay == null) {
                    // No graveyard to go to: it remembers them where it stands.
                    remembered(level, f, e, day);
                    ERRANDS.remove(f.getUUID(), e);
                    return false;
                }
                // A flower of its own (one in its pack already), else one from the stores: the town gives its
                // dead their flowers, as it gives them their stones (Graves).
                for (ItemStack s : f.getInventoryItems()) {
                    if (!s.isEmpty() && FLOWER.test(s) && !Homes.isKeepsake(s)) { e.flower = s.copyWithCount(1); break; }
                }
                if (e.flower == null) {
                    ItemStack one = Crafts.takeOne(level, v, FLOWER);
                    if (!one.isEmpty()) {
                        ItemStack left = f.insertGiven(one.copy());
                        if (left.isEmpty()) e.flower = one;
                        else Crafts.store(level, v, left);
                    }
                }
                return true;
            }
        }
        return false;
    }

    /** The errand let go: what it carried for it back to the household's chest (else the stores). */
    private static void drop(ServerLevel level, VillageFolkEntity f, Errand e, @Nullable String why) {
        ERRANDS.remove(f.getUUID(), e);
        if (!e.begun) return;
        Villages.Village v = Villages.get(e.village);
        Homes.Home h = Homes.homes(e.village).get(e.anchor);
        Container c = null;
        if (h != null) {
            BlockPos chest = Homes.chestOf(level, e.village, h);
            if (chest != null && level.getBlockEntity(chest) instanceof Container box) c = box;
        }
        List<ItemStack> back = new ArrayList<>();
        switch (e.kind) {
            case PET -> {
                int n = Math.min(e.carried - e.offered, f.countCarried(e.food));
                for (int i = 0; i < n; i++) {
                    var pack = f.getInventoryItems();
                    for (int k = 0; k < pack.size(); k++) {
                        if (pack.get(k).isEmpty() || !e.food.test(pack.get(k))) continue;
                        back.add(pack.get(k).split(1));
                        break;
                    }
                }
            }
            case GARDEN -> {
                for (int i = e.done; i < e.plants.size(); i++) {
                    Item item = e.plants.get(i).getItem();
                    if (f.removeMatching(s -> s.is(item), 1) == 1) back.add(new ItemStack(item));
                }
            }
            case GRAVE -> { }                    // a flower not laid stays in its pack: it is its own, or the town's gift
        }
        for (ItemStack s : back) {
            ItemStack left = c == null ? s : Homes.insertInto(c, s);
            if (!left.isEmpty() && v != null) Crafts.store(level, v, left);
        }
        if (c != null) c.setChanged();
        if (why != null) LOG.info("[MCA-FAMILY] {} let its {} errand go: {}", f.displayNameCap(), e.kind, why);
    }

    // ------------------------------------------------------------------ a pet

    /** A household's pet: which animal, what kind ("cat", "wolf"), its name, the day it came home, and its keeper. */
    record Pet(UUID id, String kind, String name, long since, UUID keeper) {}

    private static final Pet NO_PET = new Pet(new UUID(0L, 0L), "", "", -1, new UUID(0L, 0L));
    /** The pets, by village and home (the ledger's "pet/<home>" notes, read once). */
    private static final Map<String, Pet> PETS = new ConcurrentHashMap<>();
    /** The day each household last looked for a pet. */
    private static final Map<String, Long> PET_LOOKED = new ConcurrentHashMap<>();
    /** The day each pet was last seen about. */
    private static final Map<UUID, Long> PET_SEEN = new ConcurrentHashMap<>();
    /** When each pet was last walked (one member walks it for the household). */
    private static final Map<UUID, Long> PET_DRIVEN = new ConcurrentHashMap<>();
    /** What each pet is doing. */
    private static final Map<UUID, String> PET_DOING = new ConcurrentHashMap<>();

    private static final String[] CAT_NAMES = { "Whiskers", "Tibbles", "Smudge", "Mittens", "Pip", "Sooty", "Marmalade", "Tansy", "Ginger", "Moth" };
    private static final String[] DOG_NAMES = { "Biscuit", "Patch", "Rover", "Bramble", "Scout", "Shadow", "Bess", "Rufus", "Nell", "Socks" };

    @Nullable
    static Pet pet(UUID village, long anchor) {
        String k = village + "/" + anchor;
        Pet p = PETS.computeIfAbsent(k, x -> {
            String s = Ledger.note(village, "pet/" + anchor);
            if (s == null || s.isEmpty()) return NO_PET;
            try {
                String[] q = s.split("\\|", -1);
                return new Pet(UUID.fromString(q[0]), q[1], q[2], Long.parseLong(q[3]), UUID.fromString(q[4]));
            } catch (RuntimeException ex) {
                return NO_PET;
            }
        });
        return p == NO_PET ? null : p;
    }

    static void pet(UUID village, long anchor, @Nullable Pet p) {
        PETS.put(village + "/" + anchor, p == null ? NO_PET : p);
        if (p == null) Ledger.forget(village, "pet/" + anchor);
        else Ledger.note(village, "pet/" + anchor, p.id() + "|" + p.kind() + "|" + p.name().replace("|", "") + "|" + p.since() + "|" + p.keeper());
    }

    private static final Predicate<ItemStack> FISH = s -> s.is(Items.COD) || s.is(Items.SALMON);
    private static final Predicate<ItemStack> BONES = s -> s.is(Items.BONE);

    /** The household's pets: kept (moved with the family, let go when gone for days), and a new one looked for. */
    static void pets(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        for (Homes.Home h : List.copyOf(Homes.homes(id).values())) {
            if (h.members.isEmpty() || !level.isLoaded(h.anchor)) continue;
            Pet p = pet(id, h.anchor.asLong());
            if (p != null) {
                keep(level, v, h, p, day);
                continue;
            }
            if (t < 1000L || t >= 12500L || level.isRaining()) continue;
            String k = key(id, h);
            if (PET_LOOKED.getOrDefault(k, -1L) == day) continue;
            List<VillageFolkEntity> members = Homes.loadedMembers(id, h);
            if (children(id, h).isEmpty() || anyErrand(members)) continue;
            List<VillageFolkEntity> grown = grown(id, h);
            if (grown.isEmpty()) continue;
            if (Pets.full(level, id)) continue;                          // [pets] the town keeps no more than it can
            PET_LOOKED.put(k, day);
            startPet(level, v, h, day);
        }
    }

    /** A wild animal near home, the food for it, and a grown-up of the household to take it to it. */
    private static boolean startPet(ServerLevel level, Villages.Village v, Homes.Home h, long day) {
        UUID id = v.id();
        TamableAnimal wild = wildNear(level, h.anchor, v.centre());
        if (wild == null) return false;
        boolean cat = wild instanceof Cat;
        Predicate<ItemStack> food = cat ? FISH : BONES;
        int have = Market.stock(level, id, food);
        BlockPos chest = Homes.chestOf(level, id, h);
        if (chest != null && level.getBlockEntity(chest) instanceof Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) if (food.test(c.getItem(i))) have += c.getItem(i).getCount();
        }
        if (have <= 0) return false;
        VillageFolkEntity tamer = null;
        for (VillageFolkEntity g : grown(id, h)) {
            if (tamer == null || free(g) && !free(tamer)) tamer = g;
        }
        if (tamer == null) return false;
        Errand e = new Errand(Kind.PET, id, h.anchor.asLong(), day, level.getGameTime());
        e.animal = wild.getUUID();
        e.cat = cat;
        e.food = food;
        ERRANDS.put(tamer.getUUID(), e);
        LOG.info("[MCA-FAMILY] {} of {} sets out to take in a {} with {}", tamer.displayNameCap(), Villages.name(id), cat ? "cat" : "wolf",
            cat ? "fish" : "bones");
        return true;
    }

    /** The nearest wild cat or wolf near home (or the heart of the town), not already being tamed. */
    @Nullable
    static TamableAnimal wildNear(ServerLevel level, BlockPos home, BlockPos heart) {
        Set<UUID> sought = new HashSet<>();
        for (Errand e : ERRANDS.values()) if (e.animal != null) sought.add(e.animal);
        TamableAnimal best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos around : new BlockPos[]{ home, heart }) {
            AABB box = new AABB(around).inflate(PET_REACH, 16, PET_REACH);
            for (TamableAnimal a : level.getEntitiesOfClass(TamableAnimal.class, box,
                    x -> x.isAlive() && !x.isTame() && (x instanceof Wolf || x instanceof Cat) && x.getOwnerUUID() == null)) {
                if (sought.contains(a.getUUID())) continue;
                double d = a.distanceToSqr(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
                if (d < bestD) { bestD = d; best = a; }
            }
            if (best != null) break;
        }
        return best;
    }

    /** One step of taming: out to the animal, and a bone (or a fish) held out every second and a half. */
    @Nullable
    private static String petStep(ServerLevel level, VillageFolkEntity f, Errand e, long day, boolean there) {
        Entity seen = e.animal == null ? null : level.getEntity(e.animal);
        if (!(seen instanceof TamableAnimal a) || !a.isAlive() || a.isTame() || pet(e.village, e.anchor) != null) {
            drop(level, f, e, "the animal is gone");
            return null;
        }
        String kind = e.cat ? "cat" : "wolf";
        if (!there && f.distanceToSqr(a) > 2.4 * 2.4) {
            standUp(f);
            if (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 30 || f.tickCount < f.hobbyTick) {
                f.getNavigation().moveTo(a, 0.8D);
                f.hobbyTick = f.tickCount;
            }
            return "going out to a stray " + kind + " with " + (e.cat ? "a bit of fish" : "a few bones");
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(a, 30.0F, 30.0F);
        a.getNavigation().stop();
        a.getLookControl().setLookAt(f, 30.0F, 30.0F);
        long now = level.getGameTime();
        if (!there && now - e.lastOffer < 30L) return "coaxing a stray " + kind;
        e.lastOffer = now;
        if (f.removeMatching(e.food, 1) < 1) {
            say(f, FolkTalk.pick(f.getRandom(), "Nothing left to give it. Another day, perhaps.", "It won't come. Not today."));
            drop(level, f, e, "nothing left to offer");
            return null;
        }
        e.offered++;
        f.swing(InteractionHand.MAIN_HAND);
        // As a player tames one: a chance in three, each bone or fish.
        if (f.getRandom().nextInt(3) == 0) {
            tamed(level, f, e, a, day);
            drop(level, f, e, null);                 // what is left over goes back to the chest
            return "making friends with a " + kind;
        }
        level.broadcastEntityEvent(a, (byte) 6);    // the smoke: not yet
        if (e.offered >= e.carried) {
            say(f, FolkTalk.pick(f.getRandom(), "That's the last of them, and it still won't come.", "Ah well. It's wild yet."));
            drop(level, f, e, "it would not take to them");
            return null;
        }
        return "coaxing a stray " + kind;
    }

    /** Tamed: the household's now, named by one of its children. */
    private static void tamed(ServerLevel level, VillageFolkEntity f, Errand e, TamableAnimal a, long day) {
        UUID id = e.village;
        Homes.Home h = Homes.homes(id).get(e.anchor);
        if (h == null || pet(id, e.anchor) != null) return;          // one a household
        a.setTame(true, true);
        a.setOwnerUUID(f.getUUID());
        a.setOrderedToSit(false);
        a.setInSittingPose(false);
        a.setPersistenceRequired();
        a.setTarget(null);
        a.getNavigation().stop();
        harness(a);
        level.broadcastEntityEvent(a, (byte) 7);    // the hearts
        RandomSource r = f.getRandom();
        List<VillageFolkEntity> kids = children(id, h);
        VillageFolkEntity namer = kids.isEmpty() ? f : kids.get(r.nextInt(kids.size()));
        String[] names = e.cat ? CAT_NAMES : DOG_NAMES;
        String name = names[r.nextInt(names.length)];
        a.setCustomName(Component.literal(name));
        String kind = e.cat ? "cat" : "wolf";
        pet(id, e.anchor, new Pet(a.getUUID(), kind, name, day, f.getUUID()));
        PET_SEEN.put(a.getUUID(), day);
        say(f, e.cat ? FolkTalk.pick(r, "There, puss. You're one of us now.", "She'll come home with us. Good cat.")
            : FolkTalk.pick(r, "Good dog! You're ours now.", "There's a good lad. Come on home."));
        if (namer != f) sayLater(namer, FolkTalk.pick(r, "Can we call it " + name + "? Please?", name + "! Let's call it " + name + "!"), 40);
        Villages.tell(id, day, Homes.names(Homes.loadedMembers(id, h)) + " took in a " + (e.cat ? "stray cat" : "wolf") + " from the wild; "
            + namer.displayNameCap() + " named it " + name);
        for (VillageFolkEntity m : Homes.loadedMembers(id, h)) m.persona().remember(day, "we took in " + name + ", our " + kind, m.isBaby() ? 7 : 4);
        LOG.info("[MCA-FAMILY] {} tamed a {} ({}) for its household after {} offered", f.displayNameCap(), kind, name, e.offered);
    }

    /** A pet looked after: moved with its family, let go of when it has not been seen for days. */
    private static void keep(ServerLevel level, Villages.Village v, Homes.Home h, Pet p, long day) {
        UUID id = v.id();
        Homes.Home kh = Homes.homeOf(id, p.keeper());
        if (kh != null && kh != h) {
            // Its keeper's family moved house: so does the pet.
            pet(id, h.anchor.asLong(), null);
            if (pet(id, kh.anchor.asLong()) == null) pet(id, kh.anchor.asLong(), p);
            return;
        }
        if (kh == null && !h.members.isEmpty()) {
            p = new Pet(p.id(), p.kind(), p.name(), p.since(), h.members.get(0));       // its keeper gone: the rest of the family keep it
            pet(id, h.anchor.asLong(), p);
        }
        Entity e = level.getEntity(p.id());
        if (e instanceof TamableAnimal a && a.isAlive()) {
            PET_SEEN.put(p.id(), day);
            return;
        }
        Long seen = PET_SEEN.putIfAbsent(p.id(), day);
        if (seen != null && day - seen > 3) {
            pet(id, h.anchor.asLong(), null);
            PET_SEEN.remove(p.id());
            Villages.tell(id, day, p.name() + ", " + Homes.names(Homes.loadedMembers(id, h)) + "'s " + p.kind() + ", never came home");
        }
    }

    /** Every second or so, by whichever of the household is about: the pet walked. */
    private static void walkPet(ServerLevel level, VillageFolkEntity member) {
        UUID village = member.ownerId();
        Homes.Home h = village == null ? null : Homes.homeOf(village, member.getUUID());
        if (h == null) return;
        Pet p = pet(village, h.anchor.asLong());
        if (p == null) return;
        long now = level.getGameTime();
        Long last = PET_DRIVEN.get(p.id());
        if (last != null && now - last < 10L && now >= last) return;
        PET_DRIVEN.put(p.id(), now);
        if (level.getEntity(p.id()) instanceof TamableAnimal a && a.isAlive()) drivePet(level, village, h, a);
    }

    /**
     * The pet's day: after the nearest of the household's children that is up and about (not at school,
     * not asleep) by day; home to sit, at night or with none of them out. A child gone a long way off has
     * the pet turn up at its heels (as a tame animal does its owner's); one shut out of the house is let in.
     */
    static String drivePet(ServerLevel level, UUID village, Homes.Home h, TamableAnimal a) {
        harness(a);
        // [pets] Its own business first: a meal, a bark, a bed, the roof, a stick to fetch (Pets); else as below.
        String own = Pets.drive(level, village, h, a);
        if (own != null) {
            PET_DOING.put(a.getUUID(), own);
            return own;
        }
        long t = level.getDayTime() % 24000L;
        VillageFolkEntity child = null;
        if (t >= 1000L && t < 12500L) {
            double best = 64.0 * 64.0;
            for (VillageFolkEntity k : Homes.loadedMembers(village, h)) {
                if (!k.isBaby() || k.isSleeping() || School.doing(k) != null) continue;
                double d = k.distanceToSqr(a);
                if (d < best) { best = d; child = k; }
            }
        }
        String doing;
        if (child != null) {
            a.setOrderedToSit(false);
            a.setInSittingPose(false);
            double d = a.distanceToSqr(child);
            // Far behind, or shut in the house (a wolf cannot open a door): at the child's heels.
            if (d > 24.0 * 24.0 || d > 6.0 * 6.0 && a.getNavigation().isDone() && a.tickCount % 200 < 10) {
                a.getNavigation().stop();
                a.teleportTo(child.getX(), child.getY(), child.getZ());
            } else if (d > 3.5 * 3.5) {
                a.getNavigation().moveTo(child, 1.2D);
            } else {
                a.getNavigation().stop();
                a.getLookControl().setLookAt(child, 10.0F, a.getMaxHeadXRot());
            }
            doing = "following " + child.displayNameCap();
        } else {
            BlockPos home = hearth(level, village, h);
            double d = a.distanceToSqr(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
            if (d > 2.5 * 2.5) {
                a.setOrderedToSit(false);
                a.setInSittingPose(false);
                if (d > 48.0 * 48.0 || d > 4.0 * 4.0 && a.getNavigation().isDone() && a.tickCount % 200 < 10) {
                    a.getNavigation().stop();
                    a.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
                } else {
                    a.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.0D);
                }
                doing = "on its way home";
            } else {
                a.getNavigation().stop();
                a.setOrderedToSit(true);
                a.setInSittingPose(true);
                doing = "sitting at home";
            }
        }
        PET_DOING.put(a.getUUID(), doing);
        return doing;
    }

    /** [pets] What the pet is doing, for Pets' lines ("about" when nobody has walked it lately). */
    static String petDoing(UUID pet) {
        return PET_DOING.getOrDefault(pet, "about");
    }

    /**
     * Vanilla's sitting goal sits a tame animal down for good when its owner is not a player in the game
     * (a folk never is), and stops every walk it starts. A household's pet sits when it is told to and not
     * otherwise.
     */
    static void harness(TamableAnimal a) {
        boolean vanilla = false, ours = false;
        int priority = 2;
        for (WrappedGoal w : a.goalSelector.getAvailableGoals()) {
            if (w.getGoal() instanceof HouseholdSit) ours = true;
            else if (w.getGoal() instanceof SitWhenOrderedToGoal) {
                vanilla = true;
                priority = w.getPriority();
            }
        }
        if (vanilla) a.goalSelector.removeAllGoals(g -> g instanceof SitWhenOrderedToGoal);
        if (!ours) a.goalSelector.addGoal(priority, new HouseholdSit(a));
    }

    /** Sit when told to (at home), and only then. */
    static final class HouseholdSit extends Goal {
        private final TamableAnimal pet;

        HouseholdSit(TamableAnimal pet) {
            this.pet = pet;
            setFlags(EnumSet.of(Goal.Flag.JUMP, Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return pet.isTame() && pet.isOrderedToSit() && !pet.isInWaterOrBubble() && pet.onGround();
        }

        @Override
        public boolean canContinueToUse() {
            return pet.isOrderedToSit();
        }

        @Override
        public void start() {
            pet.getNavigation().stop();
            pet.setInSittingPose(true);
        }

        @Override
        public void stop() {
            pet.setInSittingPose(false);
        }
    }

    // ------------------------------------------------------------------ the children's games

    /** An afternoon's game: tag or hide-and-seek, where, who is playing and who is it. */
    private static final class Game {
        final long day;
        final boolean tag;
        final BlockPos ground;
        final String where;
        final List<UUID> players = new ArrayList<>();
        UUID it;
        long until, frozenUntil, phaseSince;
        boolean seeking, ended;
        int counted, tags, finds, rounds;
        final Map<UUID, BlockPos> hides = new HashMap<>();
        final List<UUID> found = new ArrayList<>();

        Game(long day, boolean tag, BlockPos ground, String where) {
            this.day = day;
            this.tag = tag;
            this.ground = ground;
            this.where = where;
        }
    }

    private static final Map<UUID, Game> GAMES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> GAME_LOOKED = new ConcurrentHashMap<>();

    private static final String[] COUNT = { "One… two…", "Three… four…", "Five… six…", "Seven… eight…", "Nine… ten! Coming, ready or not!" };

    /** A child's afternoon: in the game, if there is one. What it is doing, or null. */
    @Nullable
    private static String play(ServerLevel level, VillageFolkEntity child, UUID village, long t, long day) {
        if (t < PLAY_FROM || t >= PLAY_TO || level.isRaining() || !childFree(child) || School.doing(child) != null) return null;
        Game g = game(level, child, village, t, day);
        if (g == null) return null;
        if (level.getGameTime() >= g.until) {
            if (!g.ended) {
                g.ended = true;
                say(child, FolkTalk.pick(child.getRandom(), "That's enough — I'm puffed out!", "Same again tomorrow?", "Home time!"));
            }
            return null;
        }
        if (!g.players.contains(child.getUUID())) {
            if (child.blockPosition().distSqr(g.ground) > 64 * 64) return null;
            g.players.add(child.getUUID());                         // a late one joins in (in hide-and-seek it hides at once)
        }
        child.persona().remember(day, "we played " + (g.tag ? "tag" : "hide-and-seek") + " in " + g.where, 1);
        return g.tag ? tag(level, child, g) : hideAndSeek(level, child, g);
    }

    /** Today's game, or one started now if the children are about to play it. */
    @Nullable
    private static Game game(ServerLevel level, VillageFolkEntity child, UUID village, long t, long day) {
        Game g = GAMES.get(village);
        long now = level.getGameTime();
        if (g != null && g.day == day && now < g.until) return g;
        if (g != null && g.day == day && t >= PLAY_TO - 1200L) return g;       // the afternoon's games are over
        Long looked = GAME_LOOKED.get(village);
        if (looked != null && now - looked < 200L && now >= looked) return g != null && g.day == day ? g : null;
        GAME_LOOKED.put(village, now);
        Villages.Village v = Villages.get(village);
        if (v == null) return null;
        boolean tag = g != null && g.day == day ? !g.tag : Math.floorMod(day + village.hashCode(), 2L) == 0;
        Game n = start(level, v, day, tag, t);
        return n != null ? n : g != null && g.day == day ? g : null;
    }

    /** The park, else the square: where the children play. */
    private static Object[] playground(UUID village, Villages.Village v) {
        Ledger.Building park = Park.nearest(village, v.centre(), 80);
        if (park != null) return new Object[]{ Park.layout(park).centre(), "the park" };
        return new Object[]{ v.centre(), "the square" };
    }

    /** A game started: the children free and near enough to play, and one of them it. Null with fewer than two. */
    @Nullable
    private static Game start(ServerLevel level, Villages.Village v, long day, boolean tag, long t) {
        UUID village = v.id();
        Object[] pg = playground(village, v);
        BlockPos ground = (BlockPos) pg[0];
        List<VillageFolkEntity> kids = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity k) || !k.isBaby() || !childFree(k) || k.isShowcase() || School.doing(k) != null) continue;
            if (k.blockPosition().distSqr(ground) > 48 * 48) continue;
            kids.add(k);
        }
        if (kids.size() < 2) return null;
        long now = level.getGameTime();
        Game g = new Game(day, tag, ground, (String) pg[1]);
        for (VillageFolkEntity k : kids) g.players.add(k.getUUID());
        VillageFolkEntity it = kids.get(level.getRandom().nextInt(kids.size()));
        g.it = it.getUUID();
        g.until = now + Math.max(600L, Math.min(GAME_LENGTH, PLAY_TO - t));
        g.phaseSince = now;
        GAMES.put(village, g);
        RandomSource r = it.getRandom();
        say(it, tag ? FolkTalk.pick(r, "Tag! I'm it — run!", "Who's playing tag? I'm it!")
            : FolkTalk.pick(r, "Hide-and-seek! I'll count — go and hide!", "Let's play hide-and-seek! I'm seeking!"));
        LOG.info("[MCA-FAMILY] {} children of {} play {} in {}, {} it", kids.size(), Villages.name(village), tag ? "tag" : "hide-and-seek",
            g.where, it.displayNameCap());
        return g;
    }

    /** The other children in the game, about the playground. */
    private static List<VillageFolkEntity> playing(ServerLevel level, Game g, VillageFolkEntity but) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (UUID u : g.players) {
            if (u.equals(but.getUUID())) continue;
            VillageFolkEntity k = folk(level, u);
            if (k != null && k.isBaby() && !k.isSleeping() && k.blockPosition().distSqr(g.ground) <= 18 * 18) out.add(k);
        }
        return out;
    }

    /** Tag: whoever is it runs at the nearest; the rest keep out of its reach, inside the playground. */
    private static String tag(ServerLevel level, VillageFolkEntity child, Game g) {
        long now = level.getGameTime();
        RandomSource r = child.getRandom();
        if (child.getPose() == Pose.CROUCHING) child.setPose(Pose.STANDING);     // up out of a hiding place from the last game
        if (child.blockPosition().distSqr(g.ground) > 12 * 12) {
            walk(child, g.ground, 1.1D, 3.0);
            return "running to " + g.where + " to play tag";
        }
        boolean throttle = (child.tickCount + child.getId()) % 8 < 4;
        if (child.getUUID().equals(g.it)) {
            if (now < g.frozenUntil) {
                child.getNavigation().stop();
                return "playing tag — it, counting to three";
            }
            VillageFolkEntity prey = null;
            double best = Double.MAX_VALUE;
            for (VillageFolkEntity k : playing(level, g, child)) {
                double d = k.distanceToSqr(child);
                if (d < best) { best = d; prey = k; }
            }
            if (prey == null) {
                wander(child, g, 0.9D);
                return "playing tag — it, and nobody to catch";
            }
            if (best < 1.7 * 1.7) {
                g.it = prey.getUUID();
                g.frozenUntil = now + 40L;
                g.tags++;
                child.swing(InteractionHand.MAIN_HAND);
                child.getNavigation().stop();
                say(child, FolkTalk.pick(r, "Tag! You're it!", "Got you, " + prey.displayNameCap() + "!", "You're it, " + prey.displayNameCap() + "!"));
                sayLater(prey, FolkTalk.pick(r, "Aw! One, two, three — here I come!", "No fair! Right, you're for it!", "Not again!"), 30);
                child.life().feel(prey.getUUID(), prey.displayNameCap(), 1);
                prey.life().feel(child.getUUID(), child.displayNameCap(), 1);
                return "playing tag — just tagged " + prey.displayNameCap();
            }
            if (throttle || child.getNavigation().isDone()) child.getNavigation().moveTo(prey, 1.3D);
            return "playing tag — it, after " + prey.displayNameCap();
        }
        VillageFolkEntity chaser = folk(level, g.it);
        if (chaser != null && now >= g.frozenUntil && chaser.distanceToSqr(child) < 7.0 * 7.0) {
            if (throttle || child.getNavigation().isDone()) {
                double dx = child.getX() - chaser.getX(), dz = child.getZ() - chaser.getZ();
                double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
                double tx = child.getX() + dx / len * 6.0, tz = child.getZ() + dz / len * 6.0;
                if (Math.hypot(tx - g.ground.getX(), tz - g.ground.getZ()) > 10.0) {
                    // Backed up against the edge: dodge sideways along it.
                    double sx = -dz / len, sz = dx / len;
                    if (r.nextBoolean()) { sx = -sx; sz = -sz; }
                    tx = child.getX() + sx * 6.0;
                    tz = child.getZ() + sz * 6.0;
                }
                BlockPos to = child.surfaceAt((int) Math.floor(tx), (int) Math.floor(tz));
                if (to != null) child.walkTo(to, 1.2D);
            }
            if (r.nextInt(50) == 0) say(child, FolkTalk.pick(r, "Can't catch me!", "Too slow!", "Over here!"));
            return "playing tag — running from " + chaser.displayNameCap();
        }
        if (child.getNavigation().isDone() && r.nextInt(3) == 0) wander(child, g, 0.9D);
        return "playing tag in " + g.where;
    }

    /** A few steps somewhere about the playground. */
    private static void wander(VillageFolkEntity child, Game g, double speed) {
        RandomSource r = child.getRandom();
        BlockPos to = child.surfaceAt(g.ground.getX() + r.nextInt(17) - 8, g.ground.getZ() + r.nextInt(17) - 8);
        if (to != null) child.walkTo(to, speed);
    }

    /**
     * Hide-and-seek: the seeker counts to ten at the den, eyes shut, while the rest run off and crouch out of
     * sight beside a wall; then it hunts them out one by one, and the first it found seeks next.
     */
    private static String hideAndSeek(ServerLevel level, VillageFolkEntity child, Game g) {
        long now = level.getGameTime();
        RandomSource r = child.getRandom();
        boolean seeker = child.getUUID().equals(g.it);
        if (!g.seeking) {
            if (seeker) {
                if (child.getPose() == Pose.CROUCHING) child.setPose(Pose.STANDING);
                if (child.blockPosition().distSqr(g.ground) > 4 * 4) {
                    walk(child, g.ground, 1.0D, 2.0);
                    g.phaseSince = now;                              // the count starts at the den
                    return "going to " + g.where + " to count";
                }
                child.getNavigation().stop();
                child.getLookControl().setLookAt(g.ground.getX() + 0.5, g.ground.getY() - 1.0, g.ground.getZ() + 0.5);
                int n = (int) ((now - g.phaseSince) / 40L);          // two numbers every two seconds
                if (n > g.counted && n <= COUNT.length) {
                    g.counted = n;
                    say(child, COUNT[n - 1]);
                }
                if (n >= COUNT.length) {
                    g.seeking = true;
                    g.phaseSince = now;
                }
                return "playing hide-and-seek — counting to ten (no peeking!)";
            }
            return hide(level, child, g);
        }
        if (seeker) {
            if (child.getPose() == Pose.CROUCHING) child.setPose(Pose.STANDING);
            VillageFolkEntity next = null;
            double best = Double.MAX_VALUE;
            for (VillageFolkEntity k : playing(level, g, child)) {
                if (g.found.contains(k.getUUID())) continue;
                double d = k.distanceToSqr(child);
                if (d < best) { best = d; next = k; }
            }
            if (next == null || now - g.phaseSince > 1600L) {
                if (next != null) say(child, "I give up! Come out, come out, wherever you are!");
                round(level, g, now);
                return "playing hide-and-seek";
            }
            if (best < 2.5 * 2.5) {
                g.found.add(next.getUUID());
                g.finds++;
                child.swing(InteractionHand.MAIN_HAND);
                say(child, FolkTalk.pick(r, "Found you, " + next.displayNameCap() + "!", "There you are, " + next.displayNameCap() + "!",
                    "I see you, " + next.displayNameCap() + "!"));
                sayLater(next, FolkTalk.pick(r, "Aw! I thought I'd hidden well.", "No fair — you peeked!", "Oh, bother!"), 30);
                if (next.getPose() == Pose.CROUCHING) next.setPose(Pose.STANDING);
                child.life().feel(next.getUUID(), next.displayNameCap(), 1);
                next.life().feel(child.getUUID(), child.displayNameCap(), 1);
                return "playing hide-and-seek — found " + next.displayNameCap();
            }
            if ((child.tickCount + child.getId()) % 10 < 4 || child.getNavigation().isDone()) child.getNavigation().moveTo(next, 1.0D);
            return "playing hide-and-seek — seeking";
        }
        if (g.found.contains(child.getUUID())) {
            if (child.getPose() == Pose.CROUCHING) child.setPose(Pose.STANDING);
            walk(child, g.ground, 0.9D, 2.5);
            return "playing hide-and-seek — found, and waiting at the den";
        }
        return hide(level, child, g);
    }

    /** A hider: off to its hiding place, then crouched there, keeping still (a giggle if the seeker comes close). */
    private static String hide(ServerLevel level, VillageFolkEntity child, Game g) {
        BlockPos spot = g.hides.get(child.getUUID());
        if (spot == null) {
            spot = hidingPlace(level, child, g);
            g.hides.put(child.getUUID(), spot);
        }
        if (!walk(child, spot, 1.15D, 1.5)) {
            if (child.getPose() == Pose.CROUCHING) child.setPose(Pose.STANDING);
            return "playing hide-and-seek — running off to hide";
        }
        if (child.getPose() != Pose.CROUCHING) child.setPose(Pose.CROUCHING);
        VillageFolkEntity s = folk(level, g.it);
        if (s != null) child.getLookControl().setLookAt(s, 20.0F, 20.0F);
        if (g.seeking && s != null && s.distanceToSqr(child) < 6.0 * 6.0 && child.getRandom().nextInt(40) == 0) {
            say(child, FolkTalk.pick(child.getRandom(), "Shh!", "Hee hee…", "Don't see me, don't see me…"));
        }
        return "playing hide-and-seek — hiding";
    }

    /** Somewhere to hide: six to sixteen blocks from the den, beside a wall if there is one, away from the others. */
    private static BlockPos hidingPlace(ServerLevel level, VillageFolkEntity child, Game g) {
        RandomSource r = child.getRandom();
        BlockPos best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < 16; i++) {
            double ang = r.nextDouble() * Math.PI * 2.0, rad = 6.0 + r.nextDouble() * 10.0;
            BlockPos p = child.surfaceAt(g.ground.getX() + (int) Math.round(Math.cos(ang) * rad), g.ground.getZ() + (int) Math.round(Math.sin(ang) * rad));
            if (p == null || Math.abs(p.getY() - g.ground.getY()) > 4 || !level.getBlockState(p).isAir()) continue;
            int score = 0;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (level.getBlockState(p.relative(d)).isSolid()) score += 2;
                if (level.getBlockState(p.above().relative(d)).isSolid()) score += 2;
            }
            for (BlockPos o : g.hides.values()) if (o.distSqr(p) < 3 * 3) score -= 6;
            if (score > bestScore) { bestScore = score; best = p; }
        }
        return best != null ? best : child.blockPosition();
    }

    /** Everybody found (or the seeker gave up): the first found seeks next. */
    private static void round(ServerLevel level, Game g, long now) {
        if (!g.found.isEmpty()) g.it = g.found.get(0);
        for (UUID u : g.players) {
            VillageFolkEntity k = folk(level, u);
            if (k != null && k.getPose() == Pose.CROUCHING) k.setPose(Pose.STANDING);
        }
        g.seeking = false;
        g.phaseSince = now;
        g.counted = 0;
        g.hides.clear();
        g.found.clear();
        g.rounds++;
    }

    // ------------------------------------------------------------------ supper at home

    /** Where a folk is for supper (Meals): no household to eat with, on its way home to the table, or at it. */
    public enum Table { NONE, WAIT, HOME }

    /** Each folk's supper at home today: when it set off, when it sat down, done. */
    private static final class Seat {
        final long day, since;
        long sat = -1;
        boolean done;

        Seat(long day, long since) {
            this.day = day;
            this.since = since;
        }
    }

    private static final Map<UUID, Seat> SUPPER = new ConcurrentHashMap<>();
    /** The household's first word at the table, said once a day. */
    private static final Map<String, Long> GRACE = new ConcurrentHashMap<>();
    /** The day each folk last ate its supper at home. */
    private static final Map<UUID, Long> SUPPED = new ConcurrentHashMap<>();

    /**
     * Meals, at supper: is this folk one of a household, and is it at home? A guard keeps its watch and
     * a hired hand is its employer's; one too far out, and anybody in the meal's last ten minutes, eats
     * where it is (NONE). A household's folk eats at home (HOME), and on its way, or still at its work,
     * the meal waits for the table (WAIT).
     */
    public static Table table(ServerLevel level, VillageFolkEntity f, UUID village, int tod, int to) {
        if (tod >= to - SUPPER_LATE || f.isHired() || f.stationTask() == AssistantEntity.StationTask.GUARD) return Table.NONE;
        Homes.Home h = household(village, f);
        if (h == null || !level.isLoaded(h.anchor) || f.blockPosition().distSqr(h.anchor) > (double) SUPPER_REACH * SUPPER_REACH) return Table.NONE;
        return atHome(village, h, f, hearth(level, village, h)) ? Table.HOME : Table.WAIT;
    }

    /** It ate its supper at home (Meals). */
    static void supped(VillageFolkEntity f, long day) {
        SUPPED.put(f.getUUID(), day);
    }

    /** Supper: home to the table, sat down to it with the family, and up again once it has eaten. */
    @Nullable
    private static String supper(ServerLevel level, VillageFolkEntity f, UUID village, long t, long day) {
        if (t < SUPPER_FROM || t >= SUPPER_TO) return null;
        if (!free(f) || f.stationTask() == AssistantEntity.StationTask.GUARD) return null;
        Homes.Home h = household(village, f);
        if (h == null || !level.isLoaded(h.anchor) || f.blockPosition().distSqr(h.anchor) > (double) SUPPER_REACH * SUPPER_REACH) return null;
        long now = level.getGameTime();
        Seat s = SUPPER.get(f.getUUID());
        if (s == null || s.day != day) {
            if (Meals.hadToday(f, Meals.Meal.SUPPER, day) && !atHome(village, h, f, hearth(level, village, h))) return null;   // had it at its work
            s = new Seat(day, now);
            SUPPER.put(f.getUUID(), s);
        }
        if (s.done) return null;
        BlockPos table = hearth(level, village, h);
        if (s.sat < 0) {
            if (!atHome(village, h, f, table) || f.distanceToSqr(table.getX() + 0.5, table.getY(), table.getZ() + 0.5) > 3.0 * 3.0) {
                if (now - s.since > 1600L) {                     // it cannot get home: it eats where it is (Meals)
                    s.done = true;
                    return null;
                }
                walk(f, table, 0.9D, 2.0);
                return "home for supper";
            }
            s.sat = now;
            f.getNavigation().stop();
            Meals.tick(f);                                       // sat down to it: out of the household's chest first
            grace(level, village, h, f, day);
        }
        f.getNavigation().stop();
        VillageFolkEntity by = null;
        double near = 6.0 * 6.0;
        for (VillageFolkEntity m : Homes.loadedMembers(village, h)) {
            if (m == f || m.isSleeping()) continue;
            double d = m.distanceToSqr(f);
            if (d < near) { near = d; by = m; }
        }
        if (by != null) f.getLookControl().setLookAt(by, 30.0F, 30.0F);
        if (now - s.sat >= SUPPER_SIT && Meals.hadToday(f, Meals.Meal.SUPPER, day) || now - s.sat >= 3L * SUPPER_SIT) {
            s.done = true;
            f.persona().remember(day, "supper at home with the family", 1);
            return null;
        }
        return "at supper with the family";
    }

    /** The first of the household to sit down says so. */
    private static void grace(ServerLevel level, UUID village, Homes.Home h, VillageFolkEntity f, long day) {
        String k = key(village, h);
        if (GRACE.getOrDefault(k, -1L) == day) return;
        GRACE.put(k, day);
        RandomSource r = f.getRandom();
        if (f.isBaby()) {
            say(f, FolkTalk.pick(r, "What's for supper? I'm starving!", "Is it supper yet?"));
            return;
        }
        String what = f.meals().lastWhat();
        say(f, FolkTalk.pick(r, "Supper's on — sit down, everyone!", "Come and eat, all of you.",
            what.isEmpty() ? "Supper at home: the best part of the day." : what + " tonight, out of our own larder."));
    }

    // ------------------------------------------------------------------ a story at bedtime

    /**
     * A night's story in one house: who tells it (nobody yet, while the children wait up for one of the
     * family to come in from work), where, what, and how far it has got.
     */
    private static final class Story {
        final long day;
        @Nullable UUID teller;
        final BlockPos at;
        String about = "";
        final long planned;
        long assigned = -1, looked;
        final List<String> lines = new ArrayList<>();
        int next;
        long arrived = -1, lastLine;
        boolean over;
        final Set<UUID> heard = new HashSet<>();

        Story(long day, BlockPos at, long planned, boolean none) {
            this.day = day;
            this.at = at;
            this.planned = planned;
            this.looked = planned;
            this.over = none;
        }
    }

    private static final Map<String, Story> STORIES = new ConcurrentHashMap<>();
    /** Who is telling tonight's story in which house. */
    private static final Map<UUID, String> TELLING = new ConcurrentHashMap<>();
    /** What each house has heard lately, so the same story is not told every night. */
    private static final Map<String, Deque<String>> TOLD = new ConcurrentHashMap<>();

    /** Three nights in four, a story. */
    static boolean storyNight(UUID village, Homes.Home h, long day) {
        return Math.floorMod(day * 31L + h.anchor.asLong() + village.hashCode(), 4L) != 0;
    }

    @Nullable
    private static String story(ServerLevel level, VillageFolkEntity f, UUID village, long t, long day) {
        if (t < STORY_FROM || t >= STORY_TO) return null;
        String k = TELLING.get(f.getUUID());
        Story s = k == null ? null : STORIES.get(k);
        if (s != null && s.day == day && !s.over && f.getUUID().equals(s.teller)) return tell(level, f, s, day);
        if (!f.isBaby() || !childFree(f)) return null;
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        if (h == null) return null;
        k = key(village, h);
        s = STORIES.get(k);
        long now = level.getGameTime();
        if (s == null || s.day != day) {
            if (Meals.Meal.at((int) t) == Meals.Meal.SUPPER && !Meals.hadToday(f, Meals.Meal.SUPPER, day) && t < SUPPER_TO) return null;   // after supper
            s = plan(level, village, h, day, false);
            STORIES.put(k, s);
        }
        if (s.over) return null;
        if (s.teller == null) {
            // Waiting up for one of the family to come in from work and tell it; past bedtime, no story tonight.
            if (t >= SUPPER_TO || now - s.planned > 2400L) {
                s.over = true;
                return null;
            }
            if (now - s.looked >= 200L || now < s.looked) {
                s.looked = now;
                VillageFolkEntity tl = teller(level, village, h);
                if (tl != null) assign(village, h, s, tl, day, now);
            }
            if (s.teller == null) {
                if (walk(f, s.at, 0.9D, 2.2)) sit(f);
                return "waiting up for a story";
            }
        }
        return listen(level, f, s);
    }

    /** Tonight's story in this house (none, one night in four or with no child up): a teller, if one is free yet. */
    private static Story plan(ServerLevel level, UUID village, Homes.Home h, long day, boolean force) {
        BlockPos at = hearth(level, village, h);
        long now = level.getGameTime();
        boolean kids = false;
        for (VillageFolkEntity k : children(village, h)) if (!k.isSleeping()) kids = true;
        if (!kids || !force && !storyNight(village, h, day)) return new Story(day, at, now, true);
        Story s = new Story(day, at, now, false);
        VillageFolkEntity tl = teller(level, village, h);
        if (tl != null) assign(village, h, s, tl, day, now);
        return s;
    }

    /** The teller found: the page of the chronicle it will tell, and the story's lines. */
    private static void assign(UUID village, Homes.Home h, Story s, VillageFolkEntity teller, long day, long now) {
        String k = key(village, h);
        Chronicle.Entry e = entry(village, h, teller, k, day);
        if (e == null) {
            s.over = true;                                   // a town with nothing in its chronicle yet
            return;
        }
        List<VillageFolkEntity> kids = new ArrayList<>();
        for (VillageFolkEntity c : children(village, h)) if (!c.isSleeping()) kids.add(c);
        s.teller = teller.getUUID();
        s.about = e.text();
        s.assigned = now;
        s.lines.clear();
        s.lines.addAll(lines(village, teller, kids, e, day, teller.getRandom()));
        TELLING.put(teller.getUUID(), k);
        Deque<String> told = TOLD.computeIfAbsent(k, x -> new ArrayDeque<>());
        told.addLast(e.text());
        while (told.size() > 6) told.pollFirst();
        LOG.info("[MCA-FAMILY] {} tells the children of {} a story: day {}, {}", teller.displayNameCap(), Homes.names(Homes.loadedMembers(village, h)),
            e.day() + 1, e.text());
    }

    /**
     * Who tells it: an old folk of the family first (a grandparent from another house, or the eldest at home
     * once old), then a grandparent of any age, then the parents; awake, free and within reach of the house.
     */
    @Nullable
    private static VillageFolkEntity teller(ServerLevel level, UUID village, Homes.Home h) {
        List<VillageFolkEntity> grown = grown(village, h);
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity o) || o.isBaby() || o.isShowcase() || o.isHired() || !free(o)) continue;
            if (o.blockPosition().distSqr(h.anchor) > 64 * 64) continue;
            boolean home = grown.contains(o), gran = false;
            if (!home) {
                for (VillageFolkEntity p : grown) if (Homes.childOf(p, o)) { gran = true; break; }
                if (!gran) continue;
            }
            if (ERRANDS.containsKey(o.getUUID()) || TELLING.containsKey(o.getUUID()) && STORIES.get(TELLING.get(o.getUUID())) instanceof Story s
                    && !s.over && s.day == level.getDayTime() / 24000L) continue;
            int score = (o.ageYears() >= VillageFolkEntity.OLD_AT ? 1000 : 0) + (gran ? 200 : 0) + o.ageYears();
            if (score > bestScore) { bestScore = score; best = o; }
        }
        return best;
    }

    /**
     * The page of the chronicle to tell: one that names folk (the family's own first) and matters (a
     * wedding, a death, a birth, a building opened...), not told in this house lately.
     */
    @Nullable
    private static Chronicle.Entry entry(UUID village, Homes.Home h, VillageFolkEntity teller, String k, long day) {
        Set<String> names = new HashSet<>(), kin = new HashSet<>();
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity o) names.add(o.displayNameCap());
        for (Ledger.Grave g : Ledger.graves(village)) names.add(g.name());
        for (VillageFolkEntity m : Homes.loadedMembers(village, h)) {
            kin.add(m.displayNameCap());
            for (String p : m.life().parents().split(" and ")) if (!p.isBlank()) kin.add(p.trim());
        }
        kin.add(teller.displayNameCap());
        Deque<String> told = TOLD.getOrDefault(k, new ArrayDeque<>());
        Chronicle.Entry best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Chronicle.Entry e : Chronicle.of(village)) {
            if (e.day() >= day || e.text().isEmpty() || e.text().startsWith("Founding Day")) continue;
            String text = e.text();
            int w = FoundingDay.weight(text);
            boolean person = false, family = false;
            for (String n : names) if (mentions(text, n)) { person = true; if (kin.contains(n)) family = true; }
            if (w < 3 && !person) continue;
            int score = w * 2 + (person ? 6 : 0) + (family ? 6 : 0) - (told.contains(text) ? 100 : 0)
                + Math.floorMod(text.hashCode() + (int) day * 7, 5);
            if (score > bestScore) { bestScore = score; best = e; }
        }
        return best;
    }

    /** The story, a line at a time: the opening, the day and what happened, a word about it, the end. */
    static List<String> lines(UUID village, VillageFolkEntity teller, List<VillageFolkEntity> kids, Chronicle.Entry e, long day, RandomSource r) {
        List<String> out = new ArrayList<>();
        String text = e.text().trim();
        if (text.endsWith(".") || text.endsWith("!")) text = text.substring(0, text.length() - 1);
        String low = text.toLowerCase(Locale.ROOT);
        String you = kids.size() == 1 ? kids.get(0).displayNameCap() : "little ones";
        long when = e.day() + 1;
        out.add(FolkTalk.pick(r, "Come and sit with me, " + you + ", and I'll tell you a story. A true one, out of the town's chronicle.",
            "Settle down, " + you + ". Shall I tell you what happened on day " + when + "?",
            "Gather round, " + you + ". Do you know what happened here on day " + when + "?"));
        boolean before = true;
        for (VillageFolkEntity k : kids) if (k.bornDay() != VillageFolkEntity.UNKNOWN && k.bornDay() <= e.day()) before = false;
        long ago = Math.max(1, day - e.day());
        String agoWords = before ? "before any of you were born" : ago <= 2 ? "only the other day" : "when you were small";
        out.add("It was day " + when + ", " + agoWords + ". " + cap(text) + ".");
        String born = null;
        for (VillageFolkEntity k : kids) if (mentions(text, k.displayNameCap())) { born = k.displayNameCap(); break; }
        String flourish;
        if (low.contains(" were wed")) {
            String who = text.substring(0, low.indexOf(" were wed"));
            flourish = "The bell rang, " + who + " said their vows at the heart of the village, and we all danced till dark.";
        } else if (low.contains(" died") || low.contains("passed away")) {
            int i = low.contains(" died") ? low.indexOf(" died") : low.indexOf(" passed away");
            String who = text.substring(0, i);
            flourish = "We all miss " + who + ". There's a stone for them in the graveyard; take them a flower one day.";
        } else if (born != null && (low.contains("had a child") || low.contains("had twins") || low.contains("had triplets") || low.contains("born"))) {
            flourish = "That was you, " + born + "! Such a tiny thing you were — and how you cried!";
        } else if (low.contains("had a child") || low.contains("had twins") || low.contains("had triplets") || low.contains("born")) {
            flourish = "Such a fuss we all made of that baby. The whole town came round to see.";
        } else if (low.contains("grew up")) {
            flourish = "One day you'll grow up too, and learn a trade of your own.";
        } else if (low.contains("came into")) {
            flourish = "Fireworks over the heart of the village — oh, you should have seen it!";
        } else if (low.contains("raid") || low.contains("beat off") || low.contains("raiders")) {
            flourish = "We barred the doors, and the watch held the wall till morning. Brave as lions, they were.";
        } else if (low.contains("elected") || low.contains("leader") || low.contains("elder")) {
            flourish = "The whole town cast its votes at the board, and we cheered the winner home.";
        } else if (low.contains("founded")) {
            flourish = "There was nothing here then but grass and the wind, and a handful of us with our packs.";
        } else if (low.contains("saved")) {
            flourish = "A real hero. The town's never forgotten it.";
        } else if (low.contains("opened") || low.contains("built") || low.contains("raised")) {
            flourish = "We carried every stone of it ourselves, and the whole town came out to see it.";
        } else if (low.contains("turned")) {
            flourish = "We all brought presents, and there was cake for everyone.";
        } else {
            flourish = "I remember it as if it were yesterday.";
        }
        out.add(flourish);
        if (mentions(text, teller.displayNameCap())) out.add(FolkTalk.pick(r, "And that was me, if you please! I was there.", "I should know — I was there myself."));
        out.add(FolkTalk.pick(r, "And that's a true story, every word. Now, off to bed with you.", "And that's how it happened. Bedtime now.",
            "The end. Sleep tight, all of you."));
        return out;
    }

    /** The teller: home, sat down, waits for the children, then the story a line at a time. */
    @Nullable
    private static String tell(ServerLevel level, VillageFolkEntity teller, Story s, long day) {
        long now = level.getGameTime();
        if (!free(teller) || now - s.assigned > 2400L || s.next == 0 && s.arrived < 0 && now - s.assigned > 1200L) {
            end(teller, s);
            return null;
        }
        if (s.arrived < 0) {
            if (!walk(teller, s.at, 0.9D, 1.5)) return "on the way to tell the children a story";
            s.arrived = now;
        }
        sit(teller);
        List<VillageFolkEntity> near = new ArrayList<>();
        Homes.Home h = null;
        String k = TELLING.get(teller.getUUID());
        for (Homes.Home x : Homes.homes(teller.ownerId()).values()) if (key(teller.ownerId(), x).equals(k)) { h = x; break; }
        if (h != null) for (VillageFolkEntity c : children(teller.ownerId(), h)) if (!c.isSleeping() && c.distanceToSqr(teller) < 4.0 * 4.0) near.add(c);
        if (!near.isEmpty()) teller.getLookControl().setLookAt(near.get(0), 30.0F, 30.0F);
        if (s.next == 0 && near.isEmpty()) {
            if (now - s.arrived > 600L) {                                // nobody came
                end(teller, s);
                return null;
            }
            return "waiting for the children: story time";
        }
        if (now - s.lastLine >= 100L) {
            say(teller, s.lines.get(s.next));
            s.next++;
            s.lastLine = now;
            for (VillageFolkEntity c : near) s.heard.add(c.getUUID());
            if (s.next >= s.lines.size()) {
                RandomSource r = teller.getRandom();
                long when = Math.max(0, day);
                for (VillageFolkEntity c : near) {
                    c.persona().remember(when, teller.displayNameCap() + " told us a story: " + s.about, 2);
                    c.life().feel(teller.getUUID(), teller.displayNameCap(), 2);
                    teller.life().feel(c.getUUID(), c.displayNameCap(), 1);
                }
                if (!near.isEmpty()) sayLater(near.get(r.nextInt(near.size())), FolkTalk.pick(r, "Another one, please!",
                    "Goodnight, " + teller.displayNameCap() + "!", "Was it really like that?"), 40);
                teller.persona().remember(when, "I told the children a story: " + s.about, 1);
                end(teller, s);
            }
        }
        return "telling the children a story";
    }

    private static void end(VillageFolkEntity teller, Story s) {
        s.over = true;
        TELLING.remove(teller.getUUID());
        standUp(teller);
    }

    /** A child: home to the teller, and sat at its feet till the story is done. */
    @Nullable
    private static String listen(ServerLevel level, VillageFolkEntity child, Story s) {
        VillageFolkEntity teller = folk(level, s.teller);
        if (teller == null) return null;
        long now = level.getGameTime();
        if (now - s.assigned > 2400L) return null;
        BlockPos to = s.arrived >= 0 ? teller.blockPosition() : s.at;
        if (!walk(child, to, 1.0D, 2.2)) return "going home for a story";
        sit(child);
        child.getLookControl().setLookAt(teller, 30.0F, 30.0F);
        return "listening to " + teller.displayNameCap() + "'s story";
    }

    // ------------------------------------------------------------------ a garden

    static final Predicate<ItemStack> FLOWER = s -> s.is(ItemTags.SMALL_FLOWERS) && !s.is(Items.WITHER_ROSE);
    /** A sapling that grows on its own into a tree a house can stand beside (not the two-by-two kinds, not mangrove). */
    static final Predicate<ItemStack> SAPLING = s -> s.is(ItemTags.SAPLINGS) && !s.is(Items.DARK_OAK_SAPLING) && !s.is(Items.MANGROVE_PROPAGULE);

    private static final Map<String, Long> GARDEN_LOOKED = new ConcurrentHashMap<>();

    /** Has this house had its garden? */
    static boolean gardened(UUID village, long anchor) {
        String s = Ledger.note(village, "garden/" + anchor);
        return s != null && !s.isEmpty();
    }

    /** Households with something put by and no garden: one of them buys and plants it. */
    static void gardens(ServerLevel level, Villages.Village v, long day, long t) {
        if (t < 2000L || t >= 12500L || level.isRaining()) return;
        UUID id = v.id();
        for (Homes.Home h : List.copyOf(Homes.homes(id).values())) {
            if (h.members.isEmpty() || Flats.isFlat(h) || h.tenure == Homes.Tenure.PLAYER || !level.isLoaded(h.anchor)) continue;
            if (gardened(id, h.anchor.asLong())) continue;
            String k = key(id, h);
            if (GARDEN_LOOKED.getOrDefault(k, -1L) == day) continue;
            GARDEN_LOOKED.put(k, day);
            planGarden(level, v, h, day);
        }
    }

    /** The garden planned and given to whoever of the household has the most put by: false if it cannot be, today. */
    private static boolean planGarden(ServerLevel level, Villages.Village v, Homes.Home h, long day) {
        UUID id = v.id();
        List<VillageFolkEntity> grown = grown(id, h);
        if (grown.isEmpty() || anyErrand(Homes.loadedMembers(id, h))) return false;
        int savings = 0;
        VillageFolkEntity gardener = null;
        for (VillageFolkEntity g : grown) {
            savings += g.purse();
            if (gardener == null || g.purse() > gardener.purse()) gardener = g;
        }
        if (savings < GARDEN_SAVINGS) return false;
        if (Market.stock(level, id, FLOWER) < 1 || Market.stock(level, id, SAPLING) < 1) return false;
        Object[] spots = gardenSpots(level, id, h);
        if (spots == null) return false;
        Errand e = new Errand(Kind.GARDEN, id, h.anchor.asLong(), day, level.getGameTime());
        @SuppressWarnings("unchecked") List<BlockPos> beds = (List<BlockPos>) spots[0];
        e.beds.addAll(beds);
        e.tree = (BlockPos) spots[1];
        ERRANDS.put(gardener.getUUID(), e);
        LOG.info("[MCA-FAMILY] {} of {} is to plant a garden by its house ({} coins put by)", gardener.displayNameCap(), Villages.name(id), savings);
        return true;
    }

    /**
     * Where a house's garden goes: a bed of flowers along the front, either side of the way to the door
     * (the side with more room), and a sapling off one side, clear of the walls and of the neighbours, with
     * room over it to grow. {beds, tree}, or null if the house has no ground for one.
     */
    @Nullable
    static Object[] gardenSpots(ServerLevel level, UUID village, Homes.Home h) {
        Ledger.Building b = Homes.building(village, h.anchor);
        if (b == null) return null;
        Direction back = b.facing(), front = back.getOpposite(), right = front.getClockWise();
        int[] half = BuildGoal.footprint(Homes.drawing(village, h));
        int r = Math.max(half[0], half[1]);
        List<BlockPos> beds = new ArrayList<>();
        for (int side : new int[]{ -1, 1 }) {
            List<BlockPos> row = new ArrayList<>();
            for (int a = 2; a <= 4 && row.size() < BED; a++) {
                BlockPos p = plot(level, village, h, r, h.anchor.relative(front, r + 1).relative(right, side * a), false);
                if (p != null) row.add(p);
            }
            if (row.size() > beds.size()) beds = row;
            if (beds.size() >= BED) break;
        }
        BlockPos tree = null;
        search:
        for (int side : new int[]{ 1, -1 }) {
            for (int d = r + 3; d <= r + 4; d++) {
                for (int fwd : new int[]{ 0, 2, -2 }) {
                    BlockPos p = plot(level, village, h, r, h.anchor.relative(right, side * d).relative(front, fwd), true);
                    if (p != null) { tree = p; break search; }
                }
            }
        }
        if (beds.isEmpty() || tree == null) return null;
        return new Object[]{ beds, tree };
    }

    /** A spot of open ground near here, at about the house's floor: earth under it, room over it, nobody's building on it. */
    @Nullable
    private static BlockPos plot(ServerLevel level, UUID village, Homes.Home h, int r, BlockPos near, boolean tree) {
        if (!level.isLoaded(near)) return null;
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos p = near.above(dy);
            BlockState st = level.getBlockState(p);
            boolean open = st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty() && !st.is(BlockTags.FLOWERS) && !st.is(BlockTags.SAPLINGS);
            if (!open || !level.getBlockState(p.below()).is(BlockTags.DIRT)) continue;
            int clear = tree ? 5 : 1;
            boolean room = true;
            for (int k = 1; k <= clear; k++) if (!level.getBlockState(p.above(k)).isAir()) { room = false; break; }
            if (!room) return null;
            if (Math.max(Math.abs(p.getX() - h.anchor.getX()), Math.abs(p.getZ() - h.anchor.getZ())) <= r) return null;
            for (Ledger.Building o : Ledger.buildings(village)) {
                if (o.anchor().equals(h.anchor)) continue;
                int[] oh = BuildGoal.footprint(o.structure());
                int or = Math.max(oh[0], oh[1]) + (tree ? 3 : 1);
                if (Math.abs(p.getX() - o.anchor().getX()) <= or && Math.abs(p.getZ() - o.anchor().getZ()) <= or
                        && Math.abs(p.getY() - o.anchor().getY()) <= 12) return null;
            }
            return p.immutable();
        }
        return null;
    }

    /** One of these out of the stores, paid for out of the first purse that runs to it (into the treasury). */
    static ItemStack buy(ServerLevel level, Villages.Village v, List<VillageFolkEntity> payers, Predicate<ItemStack> what) {
        if (Market.stock(level, v.id(), what) <= 0) return ItemStack.EMPTY;
        ItemStack got = Crafts.takeOne(level, v, what);
        if (got.isEmpty()) return ItemStack.EMPTY;
        for (VillageFolkEntity f : payers) {
            int mine = Purchases.coinPrice(level, v.id(), got, f);           // [econ-prices] the town's price for the one
            if (!f.spend(mine)) continue;
            Ledger.addCoins(v.id(), mine);
            Economy.spentInTown(v.id(), mine);                              // [econ-prices] it was left out of the books
            PriceIndex.bought(v.id(), got, 1);
            Stockroom.sold(level, v.id(), Stockroom.Seller.STORES, got, 1, mine);
            return got;
        }
        Crafts.store(level, v, got);                                 // too dear: back it goes
        return ItemStack.EMPTY;
    }

    /** One step of the garden: to the next spot, and into the ground with it. */
    @Nullable
    private static String gardenStep(ServerLevel level, VillageFolkEntity f, Errand e, long day, boolean there) {
        if (e.done >= e.spots.size()) {
            finishGarden(level, f, e, day);
            return null;
        }
        BlockPos spot = e.spots.get(e.done);
        if (!there && !walk(f, spot, 0.8D, 2.0)) return "planting a garden by the house";
        f.getLookControl().setLookAt(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        ItemStack s = e.plants.get(e.done);
        Item item = s.getItem();
        Block block = Block.byItem(item);
        BlockState st = level.getBlockState(spot);
        boolean open = st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty() && !st.is(BlockTags.FLOWERS) && !st.is(BlockTags.SAPLINGS);
        if (block != Blocks.AIR && open && block.defaultBlockState().canSurvive(level, spot) && f.removeMatching(x -> x.is(item), 1) == 1) {
            if (!st.isAir()) level.destroyBlock(spot, false);
            level.setBlock(spot, block.defaultBlockState(), 3);
            level.playSound(null, spot, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, spot.getX() + 0.5, spot.getY() + 0.5, spot.getZ() + 0.5, 4, 0.3, 0.2, 0.3, 0.0);
            f.swing(InteractionHand.MAIN_HAND);
            if (s.is(ItemTags.SAPLINGS)) e.sapling = true;
            else e.flowers++;
            e.done++;
            return "planting a garden by the house";
        }
        // Somebody has been here first: what was for this spot goes back with the rest.
        e.plants.add(e.plants.remove(e.done));
        e.spots.remove(e.done);
        if (e.done >= e.spots.size()) {
            finishGarden(level, f, e, day);
            return null;
        }
        return "planting a garden by the house";
    }

    private static void finishGarden(ServerLevel level, VillageFolkEntity f, Errand e, long day) {
        Ledger.note(e.village, "garden/" + e.anchor, day + "|" + e.flowers + "|" + (e.sapling ? 1 : 0));
        drop(level, f, e, null);                                     // anything not planted, back in the chest
        Homes.Home h = Homes.homes(e.village).get(e.anchor);
        RandomSource r = f.getRandom();
        say(f, FolkTalk.pick(r, "There. Flowers by the door, and a tree that'll shade the house one day.",
            "That's the garden in. Doesn't the house look better for it?", "A garden of our own, at last."));
        if (h != null) for (VillageFolkEntity m : Homes.loadedMembers(e.village, h)) m.persona().remember(day, "we planted a garden by the house", 3);
        f.persona().enjoyedHobby(day);
        LOG.info("[MCA-FAMILY] {} planted a garden: {} flowers{}", f.displayNameCap(), e.flowers, e.sapling ? " and a sapling" : "");
    }

    // ------------------------------------------------------------------ remembrance

    /** The day each grave was last visited on its anniversary ("village/index"). */
    private static final Map<String, Long> KEPT = new ConcurrentHashMap<>();

    /** A year to the day since: the town's year (TownCalendar, four weeks). */
    static boolean anniversary(long from, long day) {
        return from >= 0 && day > from && (day - from) % TownCalendar.YEAR_DAYS == 0;
    }

    /** The dead whose anniversary it is: the nearest of their family takes a flower out to the grave. */
    static void remembrance(ServerLevel level, Villages.Village v, long day, long t) {
        if (t < 1000L || t >= 12000L) return;
        UUID id = v.id();
        List<Ledger.Grave> dead = Ledger.graves(id);
        for (int i = 0; i < dead.size(); i++) {
            Ledger.Grave g = dead.get(i);
            if (!anniversary(g.died(), day)) continue;
            String k = id + "/" + i;
            if (KEPT.getOrDefault(k, -1L) == day) continue;
            String[] rel = new String[1];
            VillageFolkEntity kin = kin(id, g, rel);
            if (kin == null) {
                KEPT.put(k, day);
                continue;
            }
            if (ERRANDS.containsKey(kin.getUUID())) continue;        // the next round
            KEPT.put(k, day);
            Errand e = new Errand(Kind.GRAVE, id, -1L, day, level.getGameTime());
            e.dead = g.name();
            e.kin = rel[0];
            e.years = (int) ((day - g.died()) / TownCalendar.YEAR_DAYS);
            BlockPos[] at = Graves.graveOf(id, i);
            e.lay = at == null ? null : at[1];
            ERRANDS.put(kin.getUUID(), e);
        }
    }

    /** The closest of the dead's family still living: its partner, a child (the grown first), a parent. */
    @Nullable
    static VillageFolkEntity kin(UUID village, Ledger.Grave g, String[] rel) {
        VillageFolkEntity partner = null, grownChild = null, child = null, parent = null;
        Set<String> parents = new HashSet<>();
        for (String p : g.parents().split(" and ")) if (!p.isBlank()) parents.add(p.trim());
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || f.isShowcase() || f.isHired() || !f.isAlive()) continue;
            String name = f.displayNameCap();
            if (!g.partner().isEmpty() && name.equals(g.partner()) && !f.isBaby()) partner = f;
            boolean mine = false;
            for (String p : f.life().parents().split(" and ")) if (p.trim().equals(g.name())) mine = true;
            if (mine) {
                if (!f.isBaby()) { if (grownChild == null || f.ageYears() > grownChild.ageYears()) grownChild = f; }
                else if (child == null) child = f;
            }
            if (parents.contains(name)) parent = f;
        }
        if (partner != null) { rel[0] = "partner"; return partner; }
        if (grownChild != null) { rel[0] = "child"; return grownChild; }
        if (parent != null) { rel[0] = "parent"; return parent; }
        if (child != null) { rel[0] = "child"; return child; }
        return null;
    }

    /** Out to the grave, and the flower laid in front of the stone. */
    @Nullable
    private static String graveStep(ServerLevel level, VillageFolkEntity f, Errand e, long day, boolean there) {
        if (e.lay == null) {
            drop(level, f, e, null);
            return null;
        }
        if (!there && !walk(f, e.lay, 0.7D, 2.0)) return "taking a flower to " + e.dead + "'s grave";
        f.getLookControl().setLookAt(e.lay.getX() + 0.5, e.lay.getY(), e.lay.getZ() + 0.5);
        if (e.flower != null) {
            Item item = e.flower.getItem();
            if (f.removeMatching(x -> x.is(item), 1) == 1) {
                Block block = Block.byItem(item);
                BlockState st = level.getBlockState(e.lay);
                if (block != Blocks.AIR && (st.isAir() || st.canBeReplaced() && st.getFluidState().isEmpty())
                        && block.defaultBlockState().canSurvive(level, e.lay)) {
                    if (!st.isAir()) level.destroyBlock(e.lay, false);
                    level.setBlock(e.lay, block.defaultBlockState(), 3);
                }
                level.sendParticles(ParticleTypes.CHERRY_LEAVES, e.lay.getX() + 0.5, e.lay.getY() + 0.6, e.lay.getZ() + 0.5, 6, 0.3, 0.2, 0.3, 0.0);
                f.swing(InteractionHand.MAIN_HAND);
            } else {
                e.flower = null;
            }
        }
        remembered(level, f, e, day);
        e.flower = null;
        ERRANDS.remove(f.getUUID(), e);
        return "at " + e.dead + "'s grave";
    }

    /** What is said at the grave (or, with no grave to go to, where it stands), and remembered. */
    private static void remembered(ServerLevel level, VillageFolkEntity f, Errand e, long day) {
        RandomSource r = f.getRandom();
        String name = e.dead;
        String since = e.years <= 1 ? "A year today" : cap(TownCalendar.inWords(e.years)) + " years today";
        String brought = e.flower == null ? "" : " I've brought you " + a(e.flower) + ".";
        String line = switch (e.kin) {
            case "partner" -> FolkTalk.pick(r, since + ", " + name + "." + brought, "I still talk to you, " + name + ", you know. Every day." + brought,
                since + " since I lost you, " + name + "." + brought);
            case "parent" -> FolkTalk.pick(r, "My " + name + ". " + since + "." + brought, since + ", " + name + ". You should have outlived us all." + brought);
            default -> FolkTalk.pick(r, since + ", " + name + "." + brought + " We're all doing well.", "I wish you could see the town now, " + name + "." + brought,
                since + ". I miss you, " + name + "." + brought);
        };
        say(f, line);
        f.persona().remember(day, e.flower == null ? "I remembered " + name + ", a year on" : "I took a flower to " + name + "'s grave", 4);
        LOG.info("[MCA-FAMILY] {} remembered {} ({} years){}", f.displayNameCap(), name, e.years, e.flower == null ? "" : " with " + a(e.flower));
    }

    /** The wedding held (Gatherings): the day kept, for its anniversaries. */
    public static void wed(UUID village, UUID a, UUID b, long day) {
        Ledger.note(village, "wed/" + a, Long.toString(day));
        Ledger.note(village, "wed/" + b, Long.toString(day));
    }

    /** The day this folk was wed (to its partner now), or -1: the town's note, else its own memory of it. */
    static long wedOn(VillageFolkEntity f) {
        UUID v = f.ownerId();
        String s = v == null ? null : Ledger.note(v, "wed/" + f.getUUID());
        if (s != null && !s.isEmpty()) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) { }
        }
        for (Persona.Memory m : f.persona().memories()) if (m.text().startsWith("we were wed")) return m.day();
        return -1;
    }

    /** Whose wedding anniversary it is today, and how many years. */
    private record Anniversary(long day, int years) {}

    private static final Map<UUID, Anniversary> ANNIV = new ConcurrentHashMap<>();

    /** Couples whose wedding day it is: when the two of them are together (or by evening, apart), it is marked. */
    static void anniversaries(ServerLevel level, Villages.Village v, long day, long t) {
        if (t < 1000L || t >= 13600L) return;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired()) continue;
            UUID pid = f.life().partner();
            if (pid == null || f.getUUID().compareTo(pid) > 0) continue;            // each couple once
            VillageFolkEntity p = folk(level, pid);
            if (p == null || p.isBaby()) continue;
            Anniversary done = ANNIV.get(f.getUUID());
            if (done != null && done.day() == day) continue;
            long w = wedOn(f);
            if (w < 0) w = wedOn(p);
            if (!anniversary(w, day)) continue;
            boolean together = !f.isSleeping() && !p.isSleeping() && f.distanceToSqr(p) <= 24.0 * 24.0;
            if (!together && t < 13000L) continue;
            mark(level, f, p, (int) ((day - w) / TownCalendar.YEAR_DAYS), day, together);
        }
    }

    private static void mark(ServerLevel level, VillageFolkEntity f, VillageFolkEntity p, int years, long day, boolean together) {
        ANNIV.put(f.getUUID(), new Anniversary(day, years));
        ANNIV.put(p.getUUID(), new Anniversary(day, years));
        String nth = TownCalendar.ordinal(years);
        RandomSource r = f.getRandom();
        if (together) {
            f.getLookControl().setLookAt(p, 30.0F, 30.0F);
            say(f, FolkTalk.pick(r, "Happy anniversary, " + p.displayNameCap() + "! Our " + nth + " year wed, today.",
                p.displayNameCap() + " — do you know what day it is? Our anniversary: " + (years == 1 ? "a year" : TownCalendar.inWords(years) + " years")
                    + " since we were wed."));
            sayLater(p, FolkTalk.pick(r, "As if I'd forget! Happy anniversary, love.", (years == 1 ? "A whole year" : cap(TownCalendar.inWords(years)) + " years")
                + " already? Happy anniversary — I'd marry you all over again."), 40);
            level.sendParticles(ParticleTypes.HEART, f.getX(), f.getY() + 2.1, f.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
            level.sendParticles(ParticleTypes.HEART, p.getX(), p.getY() + 2.1, p.getZ(), 3, 0.3, 0.2, 0.3, 0.0);
        }
        f.persona().remember(day, "our " + nth + " wedding anniversary, " + p.displayNameCap() + " and I", 4);
        p.persona().remember(day, "our " + nth + " wedding anniversary, " + f.displayNameCap() + " and I", 4);
        f.life().feel(p.getUUID(), p.displayNameCap(), 3);
        p.life().feel(f.getUUID(), f.displayNameCap(), 3);
        f.refreshMood();
        p.refreshMood();
        LOG.info("[MCA-FAMILY] {} and {} marked their {} wedding anniversary{}", f.displayNameCap(), p.displayNameCap(), nth, together ? "" : " (apart)");
    }

    /** On its wedding anniversary a folk is the happier for the day (VillageFolkEntity.refreshMood). */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        Anniversary a = ANNIV.get(f.getUUID());
        if (a == null || a.day() != day) return m;
        // The first thing it speaks of today (a card shows the weightiest three reasons), as a birthday is.
        why.add(new Object[]{ "anniversary", 12 });
        return m + 4;
    }

    /** How it puts it, asked how it is. */
    public static String moodWords(VillageFolkEntity f) {
        Anniversary a = ANNIV.get(f.getUUID());
        if (a == null) return "";
        String who = f.life().partnerName();
        return "It's our wedding anniversary today" + (who.isEmpty() ? "" : " — " + who + " and I") + ", " + (a.years() == 1 ? "a year" : TownCalendar.inWords(a.years()) + " years") + " wed!";
    }

    // ------------------------------------------------------------------ the card

    /** The household on a folk's card: its pet, its garden, the next wedding anniversary. */
    public static String cardLine(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isShowcase()) return "";
        List<String> parts = new ArrayList<>();
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        if (h != null) {
            Pet p = pet(village, h.anchor.asLong());
            if (p != null) {
                String doing = PET_DOING.get(p.id());
                parts.add(p.name() + ", the family's " + p.kind() + " (since day " + (p.since() + 1) + ")" + (doing == null ? "" : ", " + doing));
            }
            if (gardened(village, h.anchor.asLong())) parts.add("a garden by the house");
        }
        long day = f.level().getDayTime() / 24000L;
        Anniversary a = ANNIV.get(f.getUUID());
        if (a != null && a.day() == day) parts.add("wedding anniversary today");
        else if (f.life().partner() != null) {
            long w = wedOn(f);
            if (w >= 0) {
                long next = w + TownCalendar.YEAR_DAYS * Math.max(1, (day - w + TownCalendar.YEAR_DAYS - 1) / TownCalendar.YEAR_DAYS);
                parts.add("wed on day " + (w + 1) + ", next anniversary in " + (next - day) + (next - day == 1 ? " day" : " days"));
            }
        }
        return String.join("; ", parts);
    }

    // ------------------------------------------------------------------ the sights (/village sights)

    /**
     * Every household for /village sights, a line each: "HOME x y z kids N grown N chest x y z|none
     * garden yes|no pet kind name x y z|none (doing)". The pictures find a family to look in on with it.
     */
    public static List<String> sightsLines(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        for (Homes.Home h : List.copyOf(Homes.homes(id).values())) {
            if (h.members.isEmpty() || !level.isLoaded(h.anchor)) continue;
            BlockPos a = h.anchor, chest = Homes.chestOf(level, id, h);
            Pet p = pet(id, a.asLong());
            Entity pe = p == null ? null : level.getEntity(p.id());
            String pet = pe == null ? "none"
                : p.kind() + " " + p.name() + " " + pe.blockPosition().getX() + " " + pe.blockPosition().getY() + " " + pe.blockPosition().getZ()
                    + " (" + PET_DOING.getOrDefault(p.id(), "about") + ")";
            out.add("HOME " + a.getX() + " " + a.getY() + " " + a.getZ() + " kids " + children(id, h).size() + " grown " + grown(id, h).size()
                + " chest " + (chest == null ? "none" : chest.getX() + " " + chest.getY() + " " + chest.getZ())
                + " garden " + (gardened(id, a.asLong()) ? "yes" : "no") + " pet " + pet);
        }
        return out;
    }

    /**
     * For /village sights pet and garden (operators, and the pictures): the households' looks for a pet, or
     * the first gardenless house's garden, made now whatever the hour, and each errand seen through at
     * once. The fish, the bones, the flowers and the sapling still come out of the house chest and the
     * stores, and the garden is still paid for: only the walk is skipped. What was done, a line each.
     */
    public static List<String> sightsNow(ServerLevel level, Villages.Village v, boolean pets) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        if (pets) {
            petsForTests(level, v);
        } else {
            for (Homes.Home h : List.copyOf(Homes.homes(id).values())) {
                if (h.members.isEmpty() || Flats.isFlat(h) || h.tenure == Homes.Tenure.PLAYER || !level.isLoaded(h.anchor)) continue;
                if (gardened(id, h.anchor.asLong())) continue;
                if (planGarden(level, v, h, level.getDayTime() / 24000L)) break;
            }
        }
        Kind want = pets ? Kind.PET : Kind.GARDEN;
        for (Map.Entry<UUID, Errand> en : List.copyOf(ERRANDS.entrySet())) {
            if (en.getValue().kind != want || !id.equals(en.getValue().village)) continue;
            VillageFolkEntity f = folk(level, en.getKey());
            if (f == null) continue;
            BlockPos a = BlockPos.of(en.getValue().anchor);
            boolean did = errandNowForTests(level, f);
            out.add((pets ? "PET-ERRAND " : "GARDEN-ERRAND ") + f.displayNameCap() + " home " + a.getX() + " " + a.getY() + " " + a.getZ()
                + (did ? " done" : " could not begin"));
        }
        if (out.isEmpty()) out.add(pets ? "no household could take in a pet now (no stray cat or wolf near, or no fish or bones put by)"
            : "no household could plant a garden now (too little put by, or no flower and sapling in the stores)");
        return out;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: what the family's folk have said lately ("Name: words"). */
    public static List<String> saidForTests() {
        return new ArrayList<>(SAID);
    }

    /** Tests: the household chest of this folk's home, or null. */
    @Nullable
    public static BlockPos homeChestForTests(ServerLevel level, VillageFolkEntity f) {
        Homes.Home h = f.ownerId() == null ? null : Homes.homeOf(f.ownerId(), f.getUUID());
        return h == null ? null : Homes.chestOf(level, f.ownerId(), h);
    }

    /** Tests: the pets' round now (each household looks again today). */
    public static void petsForTests(ServerLevel level, Villages.Village v) {
        PET_LOOKED.clear();
        long dt = level.getDayTime();
        pets(level, v, dt / 24000L, dt % 24000L);
    }

    /** Tests: the kind of errand this folk has for its family (PET, GARDEN, GRAVE), or null. */
    @Nullable
    public static String errandForTests(VillageFolkEntity f) {
        Errand e = ERRANDS.get(f.getUUID());
        return e == null ? null : e.kind.name();
    }

    /** Tests: whose grave this folk's errand is for, or null. */
    @Nullable
    public static String graveForTests(VillageFolkEntity f) {
        Errand e = ERRANDS.get(f.getUUID());
        return e == null || e.kind != Kind.GRAVE ? null : e.dead;
    }

    /**
     * Tests: the folk's errand seen through at once, as if it were standing there for each step (it does
     * not walk): the food taken and offered, the garden bought and planted, the flower laid. False if it
     * had none, or could not begin it.
     */
    public static boolean errandNowForTests(ServerLevel level, VillageFolkEntity f) {
        Errand e = ERRANDS.get(f.getUUID());
        if (e == null) return false;
        long day = level.getDayTime() / 24000L;
        if (!e.begun) {
            if (!begin(level, f, e, day)) {
                drop(level, f, e, null);
                return false;
            }
            e.begun = true;
        }
        for (int i = 0; i < 64 && ERRANDS.get(f.getUUID()) == e; i++) {
            switch (e.kind) {
                case PET -> petStep(level, f, e, day, true);
                case GARDEN -> gardenStep(level, f, e, day, true);
                case GRAVE -> graveStep(level, f, e, day, true);
            }
        }
        return true;
    }

    /** Tests: the pet of the household at this anchor, or null. */
    @Nullable
    public static UUID petForTests(UUID village, BlockPos anchor) {
        Pet p = pet(village, anchor.asLong());
        return p == null ? null : p.id();
    }

    /** Tests: the pet walked now (by this member of its household): what it is doing, or null. */
    @Nullable
    public static String walkPetForTests(ServerLevel level, VillageFolkEntity member) {
        UUID village = member.ownerId();
        Homes.Home h = village == null ? null : Homes.homeOf(village, member.getUUID());
        Pet p = h == null ? null : pet(village, h.anchor.asLong());
        if (p == null || !(level.getEntity(p.id()) instanceof TamableAnimal a)) return null;
        return drivePet(level, village, h, a);
    }

    /** Tests: where the household's pet sits at home (the hearth). */
    @Nullable
    public static BlockPos hearthForTests(ServerLevel level, VillageFolkEntity member) {
        UUID village = member.ownerId();
        Homes.Home h = village == null ? null : Homes.homeOf(village, member.getUUID());
        return h == null ? null : hearth(level, village, h);
    }

    /** Tests: a game started now in this village (tag or hide-and-seek), whatever the hour: false with fewer than two children. */
    public static boolean gameForTests(ServerLevel level, UUID village, boolean tag) {
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        GAMES.remove(village);
        long dt = level.getDayTime();
        return start(level, v, dt / 24000L, tag, PLAY_FROM) != null;
    }

    /** Tests: today's game: "tag|hide", where, players, who is it, tags, finds, rounds, counted, seeking. */
    @Nullable
    public static String gameStateForTests(ServerLevel level, UUID village) {
        Game g = GAMES.get(village);
        if (g == null) return null;
        VillageFolkEntity it = folk(level, g.it);
        return (g.tag ? "tag" : "hide") + "|" + g.where + "|players=" + g.players.size() + "|it=" + (it == null ? "?" : it.displayNameCap())
            + "|tags=" + g.tags + "|finds=" + g.finds + "|rounds=" + g.rounds + "|counted=" + g.counted + "|seeking=" + g.seeking
            + "|hides=" + g.hides.size();
    }

    /** Tests: the game's playground. */
    @Nullable
    public static BlockPos playgroundForTests(UUID village) {
        Game g = GAMES.get(village);
        return g == null ? null : g.ground;
    }

    /** Tests: tonight's story in this child's house planned now (a story night or not): its lines, or empty. */
    public static List<String> storyForTests(ServerLevel level, VillageFolkEntity child) {
        UUID village = child.ownerId();
        Homes.Home h = village == null ? null : Homes.homeOf(village, child.getUUID());
        if (h == null) return List.of();
        Story s = plan(level, village, h, level.getDayTime() / 24000L, true);
        STORIES.put(key(village, h), s);
        return new ArrayList<>(s.lines);
    }

    /** Tests: who is telling tonight's story in this child's house, or null. */
    @Nullable
    public static UUID tellerForTests(VillageFolkEntity child) {
        UUID village = child.ownerId();
        Homes.Home h = village == null ? null : Homes.homeOf(village, child.getUUID());
        Story s = h == null ? null : STORIES.get(key(village, h));
        return s == null ? null : s.teller;
    }

    /** Tests: how many lines of tonight's story in this child's house have been told. */
    public static int storyToldForTests(VillageFolkEntity child) {
        UUID village = child.ownerId();
        Homes.Home h = village == null ? null : Homes.homeOf(village, child.getUUID());
        Story s = h == null ? null : STORIES.get(key(village, h));
        return s == null ? 0 : s.next;
    }

    /** Tests: where this folk is for supper now (Meals' view). */
    public static Table tableForTests(ServerLevel level, VillageFolkEntity f) {
        int tod = (int) (level.getDayTime() % 24000L);
        return f.ownerId() == null ? Table.NONE : table(level, f, f.ownerId(), tod, 13400);
    }

    /** Tests: did this folk eat its supper at home today? */
    public static boolean suppedAtHomeForTests(VillageFolkEntity f) {
        Long d = SUPPED.get(f.getUUID());
        return d != null && d == f.level().getDayTime() / 24000L;
    }

    /** Tests: the garden of the house at this anchor planned (whoever has the most put by to plant it), whatever the hour. */
    public static boolean planGardenForTests(ServerLevel level, Villages.Village v, BlockPos anchor) {
        Homes.Home h = Homes.homes(v.id()).get(anchor.asLong());
        return h != null && !gardened(v.id(), anchor.asLong()) && planGarden(level, v, h, level.getDayTime() / 24000L);
    }

    /** Tests: has the house at this anchor had its garden? */
    public static boolean gardenedForTests(UUID village, BlockPos anchor) {
        return gardened(village, anchor.asLong());
    }

    /** Tests: the anniversaries of the dead looked at now. */
    public static void remembranceForTests(ServerLevel level, Villages.Village v) {
        long dt = level.getDayTime();
        remembrance(level, v, dt / 24000L, Math.max(1000L, Math.min(11999L, dt % 24000L)));
    }

    /** Tests: the wedding anniversaries looked at now. */
    public static void anniversariesForTests(ServerLevel level, Villages.Village v) {
        long dt = level.getDayTime();
        anniversaries(level, v, dt / 24000L, Math.max(1000L, Math.min(13599L, dt % 24000L)));
    }
}
