package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.PetBedBlock;
import com.jrpetty.mcassistant.block.PetBowlBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * [pets] The town's pets: its households' dogs and cats, the whole of their lives. Families has a household with
 * children take in a stray it finds near the town, the way a player would; this is everything after that, and every
 * other way a pet comes home.
 *
 * <ul>
 * <li><b>Who has one.</b> A settled household (a home, the town three days founded, not short of food and content enough) wants a pet
 *     if it is that sort of household: six in ten with children, a quarter without, so over the years a third to a half
 *     of the town's homes have one, more where there are children. The town keeps no more than one pet for every two
 *     households and a couple over, twelve at the most, the young and the strays counted in.</li>
 * <li><b>Where it comes from.</b> A stray turns up about the town now and then while a household wants one, as the
 *     game itself brings stray cats to a village with beds in it: a grown-up of that household takes a bit of meat or
 *     fish out of its chest or the stores and coaxes it home. A litter born to another household's pet is shared out
 *     among the households that want one. On market day the merchant from afar may have a pup or a kitten on its lead,
 *     and a household that wants one and has the coin buys it. However it comes, a child of the house names it (a grown-up
 *     if there is no child), out of names that suit (Biscuit, Shadow, Pip, Whiskers, Smudge...), never a name somebody in
 *     the town already has, and the name is on its tag. It is the household's (Families' record, kept in the ledger), and
 *     moves house with it.</li>
 * <li><b>A dog's day.</b> It follows the household's children while they play (Families), and now and then a child
 *     throws a stick for it to fetch; with the children at school it trots along with a grown-up of the house to its
 *     work. At night it sleeps in its dog bed by the door, or at the foot of a sleeping child's bed. It barks at any
 *     monster or raider that comes near after dark, and a bark wakes the nearest guard of the watch and sends it out
 *     (WatchClears): a small help, never a fighter. The household talks about it in the morning.</li>
 * <li><b>A cat's day.</b> It sleeps in its basket or on the bed of a child of the house at night; on a fine afternoon on
 *     the roof in the sun; of a morning and an evening it sits in a window or potters about the house and the garden. A
 *     cat about the house keeps creepers off (they fear cats) and phantoms away, as in the game; a creeper it saw off is
 *     talked of. It calls on the town's fishers, and a fisher with a catch in its pack gives it a fish.</li>
 * <li><b>Food.</b> Its bowl (a pet bowl block, set by the hearth) is filled from the household's chest or the stores
 *     morning and evening: bones and meat for a dog, fish for a cat, often by a child. It eats once a day. Hungry with
 *     an empty bowl, it begs. A household that cannot feed it for three days gives it to a better-off neighbour.</li>
 * <li><b>Care.</b> A sick or a hurt pet is seen to by the town's healer (TownJobs "care", as the folk are: Health),
 *     with a bit of its own food or a drop of honey out of the stores. It grows old (a pet year every five days, as the
 *     folk's) and dies of it in its sleep, a dog at eleven to fifteen, a cat a little older; the household buries it in
 *     the garden under a little sign, with a flower if the stores have one, and the chronicle remembers it.</li>
 * <li><b>Litters.</b> A well-fed pet in its prime has a litter now and then (one to three, never past the town's cap),
 *     with another pet or a stray of its kind in the town; the parents eat first, as the game feeds a pair before they
 *     breed. The young go to households that want one; a player may have one for three coins to the family; one nobody
 *     takes goes off about the town as a stray.</li>
 * <li><b>Its things.</b> The pet bowl, the dog bed, the cat basket, the collar and pet treats are the mod's own, each
 *     with a recipe, so the town's makers know them (Bench, Tiers, Prices): the tailor makes the beds and collars, the
 *     cook the treats, the shop's workshop all of them once there is a shop (Workshop.demand); a household with no maker
 *     to go to knocks its own bowl together out of the stores' planks. A household gets each out of the stores, or buys
 *     it at the shop once there is one (Purchases), carries it home and sets it out, puts the collar on its pet (its dye
 *     is the pet's collar colour) and gives it a treat now and then.</li>
 * <li><b>Players.</b> A player can befriend a household's pet with a treat: it eats from your hand and trots after you a
 *     while, and the family thinks the better of you. A town's stray takes a treat from your hand and goes home with
 *     you; a pup or a kitten from a litter, or the merchant's, is yours for its price in coin held in the hand. Ask any
 *     folk "Your pet?" on its card. A pet that has gone missing (a dog off after a rabbit, or the quest board's "find my
 *     lost dog": {@link #lost}) comes home behind you if you find it and click it.</li>
 * </ul>
 *
 * <p>Families drives each pet every half second ({@link #drive}); a household's errands for its pet run from each folk's
 * own tick ({@link #hold}); the town's part (feeding, the healer, needs, litters, strays, the merchant's pup) every ten
 * seconds ({@link #tick}). What is kept: the ledger's "petcare/", "petother/", "petbowl/", "petbed/", "petgrave/" notes.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class Pets {

    private Pets() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    // ------------------------------------------------------------------ the numbers

    /** A pet ages a year every so many days, as the folk do. */
    static final int DAYS_A_YEAR = VillageFolkEntity.DAYS_A_YEAR;
    /** How old a stray taken in is reckoned to be. */
    static final int GROWN_AT = 2;
    /** Old at, and the years a pet lives at the most (give or take two, by the animal). */
    static final int DOG_OLD = 9, CAT_OLD = 11, DOG_YEARS = 13, CAT_YEARS = 15;
    /** The town's pets at the most, all told. */
    static final int MOST = 12;
    /** Days between a pet's litters, and between any two litters in the town. */
    static final int LITTER_EVERY = 14, TOWN_LITTER_EVERY = 6;
    /** Days a litter's young wait to be wanted; after this a household with room takes one anyway; after that it strays. */
    static final int YOUNG_WANTED = 2, YOUNG_WAIT = 5;
    /** Days without eating before a household that cannot feed its pet gives it to a neighbour. */
    static final int STARVED = 3;
    /** A pup or a kitten from a litter costs a player this; the merchant's costs a household or a player that. */
    static final int PRICE = 3, MERCHANT_PRICE = 6;
    /** Treats before a pet counts a player a friend. */
    static final int FRIENDS_AT = 2;
    /** How long a pet trots after a player after a treat (ticks). */
    static final long FOLLOW_FOR = 1200L;
    /** How near a monster must come to a dog at night for it to bark. */
    static final int BARK_REACH = 16;
    /** How far out a pet that has gone missing is (from the town's edge). */
    static final int LOST_FROM = 24, LOST_TO = 48;
    /** Days between strays turning up while somebody wants one. */
    static final int STRAY_EVERY = 3;
    /** An errand not seen through in this long is let go. */
    static final long ERRAND_TIMEOUT = 6000L;

    static final String[] DOG_NAMES = { "Biscuit", "Shadow", "Pip", "Patch", "Rufus", "Bess", "Scout", "Socks", "Barley", "Pepper",
        "Toffee", "Bonnie", "Rascal", "Sprout", "Teasel", "Bertie", "Muffin", "Pickle", "Dash", "Tess", "Bramley", "Crumble" };
    static final String[] CAT_NAMES = { "Whiskers", "Tibbles", "Smudge", "Mittens", "Sooty", "Marmalade", "Ginger", "Moth", "Thimble",
        "Cinders", "Duchess", "Pudding", "Nutmeg", "Mouse", "Clover", "Pip", "Mog", "Button", "Tabby", "Velvet", "Fudge", "Pebble" };

    static final Predicate<ItemStack> DOG_FOOD = PetBowlBlock::dogFood;
    static final Predicate<ItemStack> CAT_FOOD = PetBowlBlock::catFood;

    static Predicate<ItemStack> foodFor(boolean cat) {
        return cat ? CAT_FOOD : DOG_FOOD;
    }

    static boolean isTreat(ItemStack s) {
        return !s.isEmpty() && s.is(McAssistantMod.PET_TREAT.get());
    }

    // ------------------------------------------------------------------ the care book

    /** What the town knows of one pet: what it is, when it was born and fed, its collar, its friends, its doings. */
    static final class Care {
        final UUID pet;
        String kind = "dog";
        String name = "";
        long born = Long.MIN_VALUE;
        long fedDay = -1, treatDay = -100, litterDay = -100, barkDay = -100, sickDay = -100, rolledDay = -100, tended = -100000L;
        /** The last day the household looked for something to feed it and found nothing (in its chest, the stores or the shop). */
        long noFoodDay = -100;
        boolean sick;
        String collar = "";
        int litters, ribbons, barks, creepers, treats;
        String barkedAt = "";
        /** Missing: the day it went, where it is, whether the quest board holds it there, who found it. */
        long lostDay = -1;
        @Nullable BlockPos lostAt;
        boolean held;
        @Nullable UUID finder;
        final Map<UUID, Integer> friends = new LinkedHashMap<>();
        final Map<UUID, String> friendNames = new HashMap<>();

        Care(UUID pet) {
            this.pet = pet;
        }

        boolean cat() {
            return "cat".equals(kind);
        }
    }

    private static final Map<UUID, Care> CARE = new ConcurrentHashMap<>();

    static Care care(UUID village, UUID pet) {
        return CARE.computeIfAbsent(pet, k -> read(k, Ledger.note(village, "petcare/" + k)));
    }

    static void save(UUID village, Care c) {
        Ledger.note(village, "petcare/" + c.pet, write(c));
    }

    private static String write(Care c) {
        StringBuilder sb = new StringBuilder();
        sb.append("kind=").append(c.kind).append(";name=").append(c.name.replace(";", "").replace("=", ""))
            .append(";born=").append(c.born).append(";fed=").append(c.fedDay).append(";treat=").append(c.treatDay)
            .append(";litter=").append(c.litterDay).append(";bark=").append(c.barkDay).append(";sickday=").append(c.sickDay)
            .append(";sick=").append(c.sick ? 1 : 0).append(";collar=").append(c.collar).append(";litters=").append(c.litters)
            .append(";ribbons=").append(c.ribbons).append(";barks=").append(c.barks).append(";creepers=").append(c.creepers)
            .append(";treats=").append(c.treats).append(";nofood=").append(c.noFoodDay).append(";at=").append(c.barkedAt.replace(";", ""));
        if (c.lostDay >= 0 && c.lostAt != null) {
            sb.append(";lost=").append(c.lostDay).append(";lostat=").append(c.lostAt.asLong()).append(";held=").append(c.held ? 1 : 0);
        }
        if (!c.friends.isEmpty()) {
            List<String> fs = new ArrayList<>();
            for (Map.Entry<UUID, Integer> e : c.friends.entrySet()) {
                fs.add(e.getKey() + "~" + e.getValue() + "~" + c.friendNames.getOrDefault(e.getKey(), "").replaceAll("[~,;=]", ""));
            }
            sb.append(";friends=").append(String.join(",", fs));
        }
        return sb.toString();
    }

    private static Care read(UUID pet, @Nullable String s) {
        Care c = new Care(pet);
        if (s == null || s.isEmpty()) return c;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq), v = part.substring(eq + 1);
            try {
                switch (k) {
                    case "kind" -> c.kind = v;
                    case "name" -> c.name = v;
                    case "born" -> c.born = Long.parseLong(v);
                    case "fed" -> c.fedDay = Long.parseLong(v);
                    case "treat" -> c.treatDay = Long.parseLong(v);
                    case "litter" -> c.litterDay = Long.parseLong(v);
                    case "bark" -> c.barkDay = Long.parseLong(v);
                    case "sickday" -> c.sickDay = Long.parseLong(v);
                    case "sick" -> c.sick = "1".equals(v);
                    case "collar" -> c.collar = v;
                    case "litters" -> c.litters = Integer.parseInt(v);
                    case "ribbons" -> c.ribbons = Integer.parseInt(v);
                    case "barks" -> c.barks = Integer.parseInt(v);
                    case "creepers" -> c.creepers = Integer.parseInt(v);
                    case "treats" -> c.treats = Integer.parseInt(v);
                    case "nofood" -> c.noFoodDay = Long.parseLong(v);
                    case "at" -> c.barkedAt = v;
                    case "lost" -> c.lostDay = Long.parseLong(v);
                    case "lostat" -> c.lostAt = BlockPos.of(Long.parseLong(v));
                    case "held" -> c.held = "1".equals(v);
                    case "friends" -> {
                        for (String f : v.split(",")) {
                            String[] q = f.split("~", -1);
                            if (q.length < 2) continue;
                            UUID u = UUID.fromString(q[0]);
                            c.friends.put(u, Integer.parseInt(q[1]));
                            if (q.length > 2) c.friendNames.put(u, q[2]);
                        }
                    }
                    default -> { }
                }
            } catch (RuntimeException ignored) {
                // a word that will not read: left as it was
            }
        }
        return c;
    }

    // ------------------------------------------------------------------ the town's other animals

    /** An animal of the town's that is no household's yet: a litter's young, a stray hanging about, the merchant's pup. */
    record Other(UUID animal, String role, boolean cat, long since, long home, @Nullable UUID merchant, String mother) {
        static final String YOUNG = "young", STRAY = "stray", MERCHANT = "merchant";
    }

    private static final Map<UUID, Map<UUID, Other>> OTHERS = new ConcurrentHashMap<>();

    static Map<UUID, Other> others(UUID village) {
        return OTHERS.computeIfAbsent(village, k -> {
            Map<UUID, Other> out = new ConcurrentHashMap<>();
            for (Map.Entry<String, String> e : Ledger.notes(k).entrySet()) {
                if (!e.getKey().startsWith("petother/")) continue;
                try {
                    UUID id = UUID.fromString(e.getKey().substring("petother/".length()));
                    String[] q = e.getValue().split("\\|", -1);
                    out.put(id, new Other(id, q[0], "1".equals(q[1]), Long.parseLong(q[2]), Long.parseLong(q[3]),
                        q[4].isEmpty() ? null : UUID.fromString(q[4]), q.length > 5 ? q[5] : ""));
                } catch (RuntimeException ignored) {
                    // a note that will not read is no animal
                }
            }
            return out;
        });
    }

    static void putOther(UUID village, Other o) {
        others(village).put(o.animal(), o);
        Ledger.note(village, "petother/" + o.animal(), o.role() + "|" + (o.cat() ? 1 : 0) + "|" + o.since() + "|" + o.home() + "|"
            + (o.merchant() == null ? "" : o.merchant().toString()) + "|" + o.mother().replace("|", ""));
    }

    static void dropOther(UUID village, UUID animal) {
        if (others(village).remove(animal) != null) Ledger.forget(village, "petother/" + animal);
    }

    // ------------------------------------------------------------------ in memory

    /** A pet trotting after a player (a treat, a pat): who, and till when. */
    private record Follow(UUID player, String name, long until) {}

    private static final Map<UUID, Follow> FOLLOWING = new ConcurrentHashMap<>();
    /** A dog's game of fetch with a child: the child, where the stick landed, how far along it is, and till when. */
    private static final class Fetch {
        final UUID child;
        final BlockPos stick;
        final long until;
        boolean back;

        Fetch(UUID child, BlockPos stick, long until) {
            this.child = child;
            this.stick = stick;
            this.until = until;
        }
    }

    private static final Map<UUID, Fetch> FETCH = new ConcurrentHashMap<>();
    /** A cat's plan for the hour: the window, or a spot about the house and garden, till when. */
    private record CatPlan(boolean window, BlockPos spot, @Nullable BlockPos looking, long until) {}

    private static final Map<UUID, CatPlan> CAT_PLAN = new ConcurrentHashMap<>();
    /** A cat off to a fisher for a fish. */
    private static final Map<UUID, UUID> CAT_FISH = new ConcurrentHashMap<>();
    /** Pets lying still where they are (a bed, the roof, the window): the Rest goal keeps them there. */
    private static final Set<UUID> RESTING = ConcurrentHashMap.newKeySet();
    /** When each dog last barked, and the monsters a bark has already warned the watch of. */
    private static final Map<UUID, Long> BARKED = new ConcurrentHashMap<>();
    private static final Set<UUID> WARNED = ConcurrentHashMap.newKeySet();
    /** Creepers that came near a cat: the creeper, and the cat. */
    private static final Map<UUID, UUID> CREEPERS = new ConcurrentHashMap<>();
    /** Animals dying of old age just now (so their death is put down as that). */
    private static final Set<UUID> OLD_AGE = ConcurrentHashMap.newKeySet();
    /** The day each household last looked at what its pet wants, and at a pet of its own. */
    private static final Map<String, Long> WANT_LOOKED = new ConcurrentHashMap<>();
    /** The day each town last looked for a litter, a stray, homes for its young. */
    private static final Map<UUID, Long> TOWN_LOOKED = new ConcurrentHashMap<>();
    /** The day each household said its morning words about its pet. */
    private static final Map<String, Long> MORNING = new ConcurrentHashMap<>();
    /** The makers' list, worked out at most every ten seconds a town. */
    private static final Map<UUID, Object[]> WANTS = new ConcurrentHashMap<>();
    /** What the household's folk said lately about their pets: for the tests and the log. */
    private static final java.util.Deque<String> SAID = new java.util.concurrent.ConcurrentLinkedDeque<>();
    /** Whoever wants to hear of a lost pet brought home (the quest board). */
    private static final List<BiConsumer<Lost, ServerPlayer>> FOUND = new CopyOnWriteArrayList<>();
    /** What was looked for on a house's floor or roof lately, and found (or not): looked at again after ten seconds. */
    private static final Map<String, Object[]> LOOKED = new ConcurrentHashMap<>();
    /** Whether each town is settled, and when it was last worked out. */
    private static final Map<UUID, long[]> SETTLED = new ConcurrentHashMap<>();
    /** Tests: a household's wish for a pet decided (true/false), and the town taken as settled. */
    private static final Map<String, Boolean> WANT_FOR_TESTS = new ConcurrentHashMap<>();
    @Nullable private static volatile Boolean settledForTests;
    /** Tests: none of the chance events (a pet taken ill, off after a rabbit, a litter, a stray, the merchant's pup, a
     *  household's own take-in): only what the test itself brings about. */
    private static volatile boolean calm;

    public static void resetForTests() {
        CARE.clear();
        OTHERS.clear();
        FOLLOWING.clear();
        FETCH.clear();
        CAT_PLAN.clear();
        CAT_FISH.clear();
        RESTING.clear();
        BARKED.clear();
        WARNED.clear();
        CREEPERS.clear();
        OLD_AGE.clear();
        WANT_LOOKED.clear();
        TOWN_LOOKED.clear();
        MORNING.clear();
        WANTS.clear();
        LOOKED.clear();
        SETTLED.clear();
        ERRANDS.clear();
        SAID.clear();
        WANT_FOR_TESTS.clear();
        settledForTests = null;
        calm = false;
    }

    // ------------------------------------------------------------------ small helpers

    private static void say(VillageFolkEntity f, String text) {
        FolkTalk.speak(f, text);
        heard(f, text);
    }

    private static void sayLater(VillageFolkEntity f, String text, int ticks) {
        f.sayLater(text, ticks);
        heard(f, text);
    }

    private static void heard(VillageFolkEntity f, String text) {
        SAID.addLast(f.displayNameCap() + ": " + text);
        while (SAID.size() > 64) SAID.pollFirst();
    }

    static String kindWord(boolean cat) {
        return cat ? "cat" : "dog";
    }

    static String youngWord(boolean cat) {
        return cat ? "kitten" : "pup";
    }

    /** "Fen and Mabel's": whose household, for a sentence. */
    static String family(UUID village, Homes.Home h) {
        List<String> grown = new ArrayList<>(), all = new ArrayList<>();
        for (VillageFolkEntity m : Homes.loadedMembers(village, h)) {
            all.add(m.displayNameCap());
            if (!m.isBaby()) grown.add(m.displayNameCap());
        }
        List<String> who = grown.isEmpty() ? all : grown;
        if (who.isEmpty()) return "a household's";
        if (who.size() > 2) who = who.subList(0, 2);
        return String.join(" and ", who) + "'s";
    }

    static List<VillageFolkEntity> children(UUID village, Homes.Home h) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (VillageFolkEntity m : Homes.loadedMembers(village, h)) if (m.isBaby() && m.isAlive()) out.add(m);
        return out;
    }

    static List<VillageFolkEntity> grown(UUID village, Homes.Home h) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (VillageFolkEntity m : Homes.loadedMembers(village, h)) if (!m.isBaby() && m.isAlive()) out.add(m);
        return out;
    }

    @Nullable
    static Homes.Home homeOfPet(UUID village, UUID pet) {
        for (Homes.Home h : Homes.homes(village).values()) {
            Families.Pet p = Families.pet(village, h.anchor.asLong());
            if (p != null && p.id().equals(pet)) return h;
        }
        return null;
    }

    /** Which town, and which household, this animal is a pet of: {village, home}, or null. */
    @Nullable
    static Object[] whose(UUID pet) {
        for (Villages.Village v : Villages.every()) {
            Homes.Home h = homeOfPet(v.id(), pet);
            if (h != null) return new Object[]{ v, h };
        }
        return null;
    }

    static int years(Care c, long day) {
        return c.born == Long.MIN_VALUE ? GROWN_AT : (int) Math.max(0, (day - c.born) / DAYS_A_YEAR);
    }

    /** The years this one will live: a dog eleven to fifteen, a cat thirteen to seventeen. */
    static int lifespan(Care c) {
        return (c.cat() ? CAT_YEARS : DOG_YEARS) - 2 + Math.floorMod(c.pet.hashCode(), 5);
    }

    static boolean old(Care c, long day) {
        return years(c, day) >= (c.cat() ? CAT_OLD : DOG_OLD);
    }

    /** "a zombie", "an enderman". */
    static String a(String what) {
        return (what.matches("^[aeiou].*") ? "an " : "a ") + what;
    }

    static String nameOf(Entity e) {
        return e.getType().getDescription().getString().toLowerCase(Locale.ROOT);
    }

    /** The first time a household's pet is seen: what it is, its name, how old (a stray is reckoned two), fed the day it came. */
    static Care know(UUID village, Families.Pet p, TamableAnimal a, long day) {
        Care c = care(village, p.id());
        if (c.born == Long.MIN_VALUE) {
            c.kind = a instanceof Cat ? "cat" : "dog";
            c.name = p.name();
            c.born = a.isBaby() ? Math.min(day, p.since()) : p.since() - (long) GROWN_AT * DAYS_A_YEAR - Math.floorMod(p.id().hashCode(), 2 * DAYS_A_YEAR);
            if (c.fedDay < 0) c.fedDay = Math.max(p.since(), day - 1);
            save(village, c);
        }
        if (!c.name.equals(p.name())) {
            c.name = p.name();
            save(village, c);
        }
        return c;
    }

    // ------------------------------------------------------------------ the pet's own goal: lying still

    /** Keep the pet where it is lying (a bed, the roof, a window): nothing else moves it while it rests. */
    static void harness(TamableAnimal a) {
        Families.harness(a);
        for (WrappedGoal w : a.goalSelector.getAvailableGoals()) if (w.getGoal() instanceof Rest) return;
        a.goalSelector.addGoal(2, new Rest(a));                       // after the game's swimming (1): a pet in water swims
    }

    static final class Rest extends Goal {
        private final TamableAnimal pet;

        Rest(TamableAnimal pet) {
            this.pet = pet;
            setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            return RESTING.contains(pet.getUUID()) && !pet.isInWaterOrBubble();
        }

        @Override
        public boolean canContinueToUse() {
            return RESTING.contains(pet.getUUID()) && !pet.isInWaterOrBubble();
        }

        @Override
        public void start() {
            pet.getNavigation().stop();
        }

        @Override
        public void tick() {
            pet.getNavigation().stop();
        }
    }

    private static void rest(TamableAnimal a, boolean lie) {
        a.getNavigation().stop();
        RESTING.add(a.getUUID());
        if (a instanceof Cat cat && lie) {
            a.setOrderedToSit(false);
            a.setInSittingPose(false);
            cat.setLying(true);
        } else {
            if (a instanceof Cat cat) cat.setLying(false);
            a.setOrderedToSit(true);
            a.setInSittingPose(true);
        }
    }

    private static void wake(TamableAnimal a) {
        RESTING.remove(a.getUUID());
        if (a instanceof Cat cat && cat.isLying()) cat.setLying(false);
        a.setOrderedToSit(false);
        a.setInSittingPose(false);
    }

    /**
     * Off to a spot: walking, and over the last few blocks (a hop up onto a bed, a sill, a roof) set there, as a cat
     * gets up where a path will not take it. True once it is there.
     */
    private static boolean goTo(TamableAnimal a, double x, double y, double z, double speed, double leap) {
        double d = a.distanceToSqr(x, y, z);
        if (d <= 0.7 * 0.7) {
            a.getNavigation().stop();
            return true;
        }
        double flat = (a.getX() - x) * (a.getX() - x) + (a.getZ() - z) * (a.getZ() - z);
        boolean stuck = a.getNavigation().isDone() && d > 3.0 * 3.0 && a.tickCount % 200 < 12;
        if (flat <= leap * leap || d > 48.0 * 48.0 || stuck) {
            wake(a);
            a.getNavigation().stop();
            a.teleportTo(x, y, z);
            return true;
        }
        wake(a);
        if (a.getNavigation().isDone() || a.tickCount % 40 < 10) a.getNavigation().moveTo(x, y, z, speed);
        return false;
    }

    /** At a folk's heels, as a dog walks with somebody: a little behind, and at its side when it stops. */
    private static void heel(TamableAnimal a, Entity who, double speed) {
        wake(a);
        double d = a.distanceToSqr(who);
        if (d > 24.0 * 24.0) {
            a.getNavigation().stop();
            a.teleportTo(who.getX(), who.getY(), who.getZ());
        } else if (d > 3.5 * 3.5) {
            a.getNavigation().moveTo(who, speed);
        } else {
            a.getNavigation().stop();
            a.getLookControl().setLookAt(who, 10.0F, a.getMaxHeadXRot());
        }
    }

    // ------------------------------------------------------------------ who wants one

    /** One for every two households and a couple over (the young and the strays), twelve at the most. */
    static int cap(UUID village) {
        int households = 0;
        for (Homes.Home h : Homes.homes(village).values()) if (!h.members.isEmpty()) households++;
        return Math.min(MOST, Math.max(2, (households + 1) / 2 + 2));
    }

    /** The town's animals all told: the households' pets, the young waiting for homes, the strays, the merchant's. */
    static int count(UUID village) {
        int n = 0;
        for (Homes.Home h : Homes.homes(village).values()) if (Families.pet(village, h.anchor.asLong()) != null) n++;
        return n + others(village).size();
    }

    /** Has the town as many animals as it keeps (Families asks before a household takes in a wild one)? */
    public static boolean full(ServerLevel level, UUID village) {
        return count(village) >= cap(village);
    }

    /** Settled: the town not short of food, and content enough (getting by or better). */
    static boolean settled(ServerLevel level, UUID village) {
        Boolean forced = settledForTests;
        if (forced != null) return forced;
        long now = level.getGameTime();
        long[] seen = SETTLED.get(village);
        if (seen != null && now - seen[0] < 1200L && now >= seen[0]) return seen[1] != 0L;
        // A camp in its first days has other things on its mind than a dog: three days founded, as Health has it.
        long founded = com.jrpetty.mcassistant.village.Chronicle.foundedOn(village);
        boolean ok = founded >= 0 && level.getDayTime() / 24000L - founded >= 3 && Contentment.score(village) >= 40;
        if (ok) for (Villages.Need n : Villages.needs(level, village)) if (n.task() == Villages.Task.FOOD) { ok = false; break; }
        SETTLED.put(village, new long[]{ now, ok ? 1L : 0L });
        return ok;
    }

    /**
     * Does this household want a pet: none now, a grown-up in it, and the sort of household that keeps one (six in ten
     * with children, a quarter without), not one that has had to give one away this fortnight?
     */
    static boolean wants(ServerLevel level, UUID village, Homes.Home h) {
        if (h.members.isEmpty() || Families.pet(village, h.anchor.asLong()) != null) return false;
        Boolean forced = WANT_FOR_TESTS.get(village + "/" + h.anchor.asLong());
        if (forced != null) return forced;
        String gave = Ledger.note(village, "petgave/" + h.anchor.asLong());
        long day = level.getDayTime() / 24000L;
        if (gave != null) {
            try {
                if (day - Long.parseLong(gave) < 14) return false;
            } catch (NumberFormatException ignored) { }
        }
        boolean kids = false, grownUp = false;
        for (UUID m : h.members) {
            VillageFolkEntity f = Homes.loaded(village, m);
            if (f == null) continue;
            if (f.isBaby()) kids = true;
            else grownUp = true;
        }
        if (!grownUp) return false;
        int lean = Math.floorMod(village.hashCode() * 31 + Long.hashCode(h.anchor.asLong()) * 17, 100);
        return lean < (kids ? 60 : 25);
    }

    /** The households that want one, those with children first. */
    static List<Homes.Home> wanting(ServerLevel level, UUID village) {
        List<Homes.Home> out = new ArrayList<>();
        for (Homes.Home h : List.copyOf(Homes.homes(village).values())) {
            if (!level.isLoaded(h.anchor) || !wants(level, village, h)) continue;
            out.add(h);
        }
        out.sort((x, y) -> Integer.compare(children(village, y).size(), children(village, x).size()));
        return out;
    }

    /** A name for a new pet that nobody in the town has, folk or animal. */
    static String freshName(UUID village, boolean cat, RandomSource r) {
        Set<String> taken = new HashSet<>();
        for (AssistantEntity a : Villages.folkOf(village)) taken.add(a.displayNameCap());
        for (Homes.Home h : Homes.homes(village).values()) {
            Families.Pet p = Families.pet(village, h.anchor.asLong());
            if (p != null) taken.add(p.name());
        }
        String[] pool = cat ? CAT_NAMES : DOG_NAMES;
        int start = r.nextInt(pool.length);
        for (int i = 0; i < pool.length; i++) {
            String n = pool[(start + i) % pool.length];
            if (!taken.contains(n)) return n;
        }
        return pool[start] + " the " + (cat ? "Second" : "Younger");
    }

    /**
     * Into a household: tame, its keeper's, named by one of its children (else a grown-up), written down (Families'
     * record and the care book), and told. {@code how} says where it came from ("a stray", "Biscuit's litter").
     */
    static void adopt(ServerLevel level, UUID village, Homes.Home h, TamableAnimal a, long day, String how) {
        List<VillageFolkEntity> members = Homes.loadedMembers(village, h);
        VillageFolkEntity keeper = null;
        for (VillageFolkEntity m : members) if (!m.isBaby() && (keeper == null)) keeper = m;
        UUID keeperId = keeper != null ? keeper.getUUID() : h.members.isEmpty() ? null : h.members.get(0);
        if (keeperId == null) return;
        boolean cat = a instanceof Cat;
        a.setTame(true, true);
        a.setOwnerUUID(keeperId);
        wake(a);
        a.setPersistenceRequired();
        a.setTarget(null);
        a.getNavigation().stop();
        harness(a);
        dropOther(village, a.getUUID());
        RandomSource r = level.getRandom();
        List<VillageFolkEntity> kids = children(village, h);
        VillageFolkEntity namer = !kids.isEmpty() ? kids.get(r.nextInt(kids.size())) : keeper;
        String name = freshName(village, cat, r);
        a.setCustomName(Component.literal(name));
        Families.pet(village, h.anchor.asLong(), new Families.Pet(a.getUUID(), cat ? "cat" : "wolf", name, day, keeperId));
        Care c = care(village, a.getUUID());
        c.kind = kindWord(cat);
        c.name = name;
        if (c.born == Long.MIN_VALUE) c.born = a.isBaby() ? day : day - (long) GROWN_AT * DAYS_A_YEAR;
        c.fedDay = Math.max(c.fedDay, day - 1);
        save(village, c);
        level.broadcastEntityEvent(a, (byte) 7);
        String fam = family(village, h);
        if (namer != null) {
            sayLater(namer, namer.isBaby() ? FolkTalk.pick(r, "Can we call it " + name + "? Please? " + name + "!", name + "! Its name is " + name + "!",
                    "I'm calling it " + name + ".")
                : FolkTalk.pick(r, name + ", I think. Yes — " + name + ".", "We'll call it " + name + "."), 30);
        }
        Villages.tell(village, day, Homes.names(members) + " took in " + (a.isBaby() ? "a " + youngWord(cat) : a(kindWord(cat))) + " (" + how + ")"
            + (namer != null ? "; " + namer.displayNameCap() + " named it " + name : ", named " + name));
        for (VillageFolkEntity m : members) m.persona().remember(day, "we took in " + name + ", our " + kindWord(cat), m.isBaby() ? 7 : 4);
        LOG.info("[MCA-PETS] {} took in {} {} ({}), named {}", fam, cat ? "a cat" : "a dog", a.getUUID(), how, name);
    }

    // ------------------------------------------------------------------ the household's things

    @Nullable
    private static BlockPos noted(ServerLevel level, UUID village, String key, Predicate<BlockState> is) {
        String s = Ledger.note(village, key);
        if (s == null || s.isEmpty()) return null;
        try {
            BlockPos p = BlockPos.of(Long.parseLong(s));
            if (!level.isLoaded(p) || is.test(level.getBlockState(p))) return p;
        } catch (NumberFormatException ignored) { }
        Ledger.forget(village, key);
        return null;
    }

    /** The household's pet bowl, set out at home; one a player put down in the house is taken as it. */
    @Nullable
    static BlockPos bowlOf(ServerLevel level, UUID village, Homes.Home h) {
        String key = "petbowl/" + h.anchor.asLong();
        BlockPos p = noted(level, village, key, st -> st.getBlock() instanceof PetBowlBlock);
        if (p != null) return p;
        p = foundInHouse(level, village, h, st -> st.getBlock() instanceof PetBowlBlock, "bowl");
        if (p != null) Ledger.note(village, key, Long.toString(p.asLong()));
        return p;
    }

    /** The household's dog bed (or, {@code basket}, its cat basket), set out at home. */
    @Nullable
    static BlockPos bedOf(ServerLevel level, UUID village, Homes.Home h, boolean basket) {
        String key = "petbed/" + h.anchor.asLong();
        Predicate<BlockState> is = st -> st.getBlock() instanceof PetBedBlock b && b.basket() == basket;
        BlockPos p = noted(level, village, key, is);
        if (p != null) return p;
        p = foundInHouse(level, village, h, is, basket ? "basket" : "bed");
        if (p != null) Ledger.note(village, key, Long.toString(p.asLong()));
        return p;
    }

    /** One of these anywhere on the house's floor (once a day a house at most). */
    @Nullable
    private static BlockPos foundInHouse(ServerLevel level, UUID village, Homes.Home h, Predicate<BlockState> is, String what) {
        String key = village + "/" + h.anchor.asLong() + "/" + what;
        long now = level.getGameTime();
        Object[] seen = LOOKED.get(key);
        if (seen != null && now - (Long) seen[0] < 200L && now >= (Long) seen[0]) return (BlockPos) seen[1];
        BlockPos found = null;
        Ledger.Building b = Homes.building(village, h.anchor);
        if (b != null && level.isLoaded(h.anchor)) {
            for (BlockPos c : Decor.room(village, b).floor()) {
                if (level.isLoaded(c) && is.test(level.getBlockState(c))) { found = c.immutable(); break; }
            }
        }
        LOOKED.put(key, new Object[]{ now, found });
        return found;
    }

    /**
     * Where a thing goes at home: the bowl near the hearth, the dog bed by the door, the basket under a window (else by
     * the hearth): a cell of floor against a wall, not in the way (Decor's rules); in a flat, beside its chest.
     */
    @Nullable
    static BlockPos spotFor(ServerLevel level, UUID village, Homes.Home h, Item thing) {
        BlockPos hearth = Families.hearth(level, village, h);
        Ledger.Building b = Homes.building(village, h.anchor);
        if (b != null && !Flats.isFlat(h)) {
            Decor.Room room = Decor.room(village, b);
            Set<BlockPos> taken = Decor.reserved(level, village, b);
            for (Long p : Decor.book(village, h.anchor).values()) taken.add(BlockPos.of(p));
            BlockPos bowl = bowlOf(level, village, h), bed = bedOf(level, village, h, true), dogBed = bedOf(level, village, h, false);
            for (BlockPos p : new BlockPos[]{ bowl, bed, dogBed }) {
                if (p == null) continue;
                taken.add(p);
                for (Direction d : Direction.Plane.HORIZONTAL) taken.add(p.relative(d));
            }
            BlockPos near = hearth;
            if (thing == McAssistantMod.DOG_BED_ITEM.get() && room.door() != null) near = room.door();
            if (thing == McAssistantMod.CAT_BED_ITEM.get() && !room.sills().isEmpty()) {
                BlockPos sill = room.sills().values().iterator().next();
                near = sill.below();
            }
            Decor.Spot s = Decor.floorSpot(level, room, near, taken);
            if (s != null) return s.at();
        }
        // A flat, or a house with no floor to spare against its walls: a free cell near the hearth.
        for (int r = 1; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos c = hearth.offset(dx, dy, dz);
                        if (!level.isLoaded(c) || !level.getBlockState(c).isAir() || !level.getBlockState(c.above()).isAir()) continue;
                        if (!level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP)) continue;
                        if (Homes.homeAt(village, c) != h && !Flats.isFlat(h)) continue;
                        if (Decor.cutsTheWay(level, c)) continue;
                        boolean door = false;
                        for (Direction d : Direction.Plane.HORIZONTAL) {
                            if (level.getBlockState(c.relative(d)).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) door = true;
                        }
                        if (!door) return c.immutable();
                    }
                }
            }
        }
        return null;
    }

    /** The collar a pet wears, put on it: its colour shown on the animal (the game's own collar). */
    static void putCollar(TamableAnimal a, DyeColor colour) {
        try {
            java.lang.reflect.Method m = (a instanceof Wolf ? Wolf.class : Cat.class).getDeclaredMethod("setCollarColor", DyeColor.class);
            m.setAccessible(true);
            m.invoke(a, colour);
            return;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // The game's own collar setter out of reach: the animal's saved data says the same thing.
        }
        CompoundTag tag = new CompoundTag();
        a.addAdditionalSaveData(tag);
        tag.putByte("CollarColor", (byte) colour.getId());
        a.readAdditionalSaveData(tag);
    }

    /** The dye nearest a colour. */
    static DyeColor nearestDye(int rgb) {
        DyeColor best = DyeColor.RED;
        long bd = Long.MAX_VALUE;
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        for (DyeColor d : DyeColor.values()) {
            int c = d.getTextureDiffuseColor();
            int dr = ((c >> 16) & 255) - r, dg = ((c >> 8) & 255) - g, db = (c & 255) - b;
            long dist = (long) dr * dr + (long) dg * dg + (long) db * db;
            if (dist < bd) { bd = dist; best = d; }
        }
        return best;
    }

    /** A collar's colour: its dye, else (a plain leather one) the town's colour. */
    static DyeColor collarColour(ItemStack collar, UUID village) {
        DyedItemColor dyed = collar.get(DataComponents.DYED_COLOR);
        return nearestDye(dyed != null ? dyed.rgb() : Villages.colour(village));
    }

    // ------------------------------------------------------------------ the household's errands for its pet

    enum Job { FILL, FEED, PLACE, COLLAR, TREAT, TAKE_IN, BURY, MAKE }

    /** Something one of the household is about for its pet. */
    static final class Errand {
        Job job;
        final UUID village;
        final long anchor;
        @Nullable final UUID animal;
        final long given;
        boolean begun;
        @Nullable Item thing;
        int carried;
        boolean cat;
        @Nullable BlockPos to;
        /** A grave: whose, what, the years, and the words on the sign. */
        String dead = "", deadKind = "";
        int deadYears;
        long died;

        Errand(Job job, UUID village, long anchor, @Nullable UUID animal, long given) {
            this.job = job;
            this.village = village;
            this.anchor = anchor;
            this.animal = animal;
            this.given = given;
        }
    }

    private static final Map<UUID, Errand> ERRANDS = new ConcurrentHashMap<>();

    /** Is any of the household about something for its pet already? */
    private static boolean anyErrand(Homes.Home h) {
        for (UUID m : h.members) if (ERRANDS.containsKey(m)) return true;
        return false;
    }

    /** A member free to see to it: a child for the bowl or a treat if one is free (they love it), else a grown-up. */
    @Nullable
    private static VillageFolkEntity hand(UUID village, Homes.Home h, boolean childOk) {
        VillageFolkEntity best = null;
        for (VillageFolkEntity m : Homes.loadedMembers(village, h)) {
            if (!m.isAlive() || m.isSleeping() || ERRANDS.containsKey(m.getUUID()) || Families.errandForTests(m) != null) continue;
            if (m.isBaby() && !childOk) continue;
            boolean free = m.isBaby() ? Families.childFree(m) && School.doing(m) == null : Families.free(m);
            if (!free) continue;
            if (best == null || m.isBaby() && childOk && !best.isBaby()) best = m;
        }
        return best;
    }

    /** Folk of the household not free just now, but at home: one still sees to the bowl when nobody else can. */
    @Nullable
    private static VillageFolkEntity anyone(UUID village, Homes.Home h) {
        for (VillageFolkEntity m : Homes.loadedMembers(village, h)) {
            if (m.isAlive() && !m.isSleeping() && !m.isBaby() && !ERRANDS.containsKey(m.getUUID()) && Families.errandForTests(m) == null) return m;
        }
        return null;
    }

    private static boolean give(VillageFolkEntity f, Errand e) {
        if (ERRANDS.containsKey(f.getUUID())) return false;
        ERRANDS.put(f.getUUID(), e);
        return true;
    }

    /**
     * From each folk's tick (Families.hold): an errand for its pet, if it has one and the time for it; and the first
     * thing it says in the morning after a night of barking. What it is doing, or null.
     */
    @Nullable
    public static String hold(ServerLevel level, VillageFolkEntity f, UUID village, long t, long day) {
        if (f.tickCount % 20 == 6) morning(level, f, village, t, day, false);
        Errand e = ERRANDS.get(f.getUUID());
        if (e == null) return null;
        long now = level.getGameTime();
        if (now - e.given > ERRAND_TIMEOUT || now < e.given) {
            drop(level, f, e, "it took too long");
            return null;
        }
        boolean free = f.isBaby() ? Families.childFree(f) : Families.free(f);
        if (!free || t < 1000L || t >= 13600L) return null;            // its own time, never the dead of night
        if (!e.begun) {
            if (!begin(level, f, e, day)) {
                drop(level, f, e, null);
                return null;
            }
            e.begun = true;
        }
        return step(level, f, e, day, false);
    }

    /** Out of the household's chest first, then the stores or the shop (Purchases): so many of what matches. */
    private static int fetch(ServerLevel level, Villages.Village v, Homes.Home h, VillageFolkEntity f, Predicate<ItemStack> what, int n,
                             Purchases.Need need) {
        int got = 0;
        BlockPos chest = Homes.chestOf(level, v.id(), h);
        if (chest != null && level.getBlockEntity(chest) instanceof Container c) {
            for (int i = 0; i < c.getContainerSize() && got < n; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s)) continue;
                int k = Math.min(n - got, s.getCount());
                ItemStack left = f.insertGiven(s.copyWithCount(k));
                int took = k - left.getCount();
                s.shrink(took);
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                got += took;
                if (took < k) break;
            }
            c.setChanged();
        }
        if (got < n) got += Purchases.get(level, f, what, n - got, need);
        return got;
    }

    /** What it needs to set out with. False: it cannot. */
    private static boolean begin(ServerLevel level, VillageFolkEntity f, Errand e, long day) {
        Villages.Village v = Villages.get(e.village);
        Homes.Home h = Homes.homes(e.village).get(e.anchor);
        if (v == null || h == null) return false;
        Families.Pet p = Families.pet(e.village, e.anchor);
        RandomSource r = f.getRandom();
        switch (e.job) {
            case FILL, FEED -> {
                if (p == null) return false;
                e.cat = "cat".equals(p.kind());
                int want = 1;
                if (e.job == Job.FILL) {
                    BlockPos bowl = bowlOf(level, e.village, h);
                    if (bowl == null) return false;
                    want = 3 - PetBowlBlock.servings(level.getBlockState(bowl));
                }
                if (want <= 0) return false;
                e.carried = fetch(level, v, h, f, foodFor(e.cat), want, Purchases.Need.TREAT);
                if (e.carried <= 0) {
                    Care c = care(e.village, p.id());
                    c.noFoodDay = day;
                    save(e.village, c);
                    LOG.info("[MCA-PETS] {} found nothing to feed {} with", f.displayNameCap(), p.name());
                    return false;
                }
                return true;
            }
            case PLACE, COLLAR, TREAT -> {
                if (p == null || e.thing == null) return false;
                Item it = e.thing;
                if (e.job == Job.PLACE && (it == McAssistantMod.PET_BOWL_ITEM.get() ? bowlOf(level, e.village, h) != null
                        : bedOf(level, e.village, h, it == McAssistantMod.CAT_BED_ITEM.get()) != null)) return false;
                int got = fetch(level, v, h, f, s -> s.is(it), 1, e.job == Job.TREAT ? Purchases.Need.TREAT : Purchases.Need.LUXURY);
                if (got <= 0) return false;
                e.carried = got;
                if (e.job == Job.PLACE) {
                    e.to = spotFor(level, e.village, h, it);
                    if (e.to == null) return false;              // no room for it: what it got goes back (drop)
                    say(f, FolkTalk.pick(r, "I've got " + p.name() + " " + Bench.words(it, 1) + ". Now, where shall it go?",
                        "There — " + Bench.words(it, 1) + " for " + p.name() + ". Home with it."));
                }
                return true;
            }
            case TAKE_IN -> {
                if (Families.pet(e.village, e.anchor) != null || e.animal == null) return false;
                if (!(level.getEntity(e.animal) instanceof TamableAnimal a) || !a.isAlive() || a.isTame()) return false;
                e.cat = a instanceof Cat;
                e.carried = fetch(level, v, h, f, foodFor(e.cat), 2, Purchases.Need.TREAT);
                if (e.carried <= 0) return false;
                say(f, e.cat ? FolkTalk.pick(r, "That stray cat's still hanging about. A bit of fish, and she's ours.", "Here, puss, puss…")
                    : FolkTalk.pick(r, "That stray dog's been about all week. Let's see if he'll come home with us.", "Here, boy! Come on, then…"));
                for (VillageFolkEntity k : children(e.village, h)) sayLater(k, e.cat ? "A kitty! Can we keep her?" : "A dog! Can we keep him? Please?", 40);
                return true;
            }
            case BURY -> {
                return e.to != null;
            }
            case MAKE -> {
                Bench.Hand hand = Bench.handOf(level, v, f, null);
                Bench.Plan plan = Bench.plan(level, v, McAssistantMod.PET_BOWL_ITEM.get(), 1, hand);
                if (!plan.ok()) return false;
                e.to = Families.hearth(level, e.village, h);
                return true;
            }
        }
        return false;
    }

    /** Walk somewhere, re-pathing now and then; true once within {@code near} of it. */
    private static boolean walk(VillageFolkEntity f, BlockPos to, double near) {
        if (f.distanceToSqr(to.getX() + 0.5, to.getY(), to.getZ() + 0.5) <= near * near) {
            f.getNavigation().stop();
            return true;
        }
        if (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 60 || f.tickCount < f.hobbyTick) {
            f.walkTo(to, 0.9D);
            f.hobbyTick = f.tickCount;
        }
        return false;
    }

    /** One step of the errand: what it is doing, or null once it is done (or let go). */
    @Nullable
    private static String step(ServerLevel level, VillageFolkEntity f, Errand e, long day, boolean there) {
        Villages.Village v = Villages.get(e.village);
        Homes.Home h = Homes.homes(e.village).get(e.anchor);
        if (v == null || h == null && e.job != Job.BURY) {
            drop(level, f, e, "its home is gone");
            return null;
        }
        Families.Pet p = h == null ? null : Families.pet(e.village, e.anchor);
        TamableAnimal a = e.animal != null && level.getEntity(e.animal) instanceof TamableAnimal x && x.isAlive() ? x : null;
        RandomSource r = f.getRandom();
        String pet = p == null ? "the pet" : p.name();
        switch (e.job) {
            case FILL -> {
                BlockPos bowl = h == null ? null : bowlOf(level, e.village, h);
                if (bowl == null) {
                    drop(level, f, e, "the bowl is gone");
                    return null;
                }
                if (!there && !walk(f, bowl, 2.0)) return "taking " + pet + "'s dinner home";
                f.getLookControl().setLookAt(bowl.getX() + 0.5, bowl.getY(), bowl.getZ() + 0.5);
                f.swing(InteractionHand.MAIN_HAND);
                // As much as will go in (fish on meat will not): the rest goes back to the chest with it (drop).
                BlockState st = level.getBlockState(bowl);
                int have = PetBowlBlock.servings(st);
                int room = have > 0 && st.getValue(PetBowlBlock.FISH) != e.cat ? 0 : 3 - have;
                int took = f.removeMatching(foodFor(e.cat), Math.min(e.carried, room));
                int put = PetBowlBlock.fill(level, bowl, took, e.cat);
                e.carried -= took;
                if (a != null && !(a instanceof Cat && ((Cat) a).isLying())) a.getLookControl().setLookAt(f, 30.0F, 30.0F);
                say(f, f.isBaby() ? FolkTalk.pick(r, pet + "! Dinner!", "Here you go, " + pet + ". All for you!", "Who wants their dinner? " + pet + " does!")
                    : FolkTalk.pick(r, "There you are, " + pet + ".", "Your bowl's full, " + pet + ". Don't gobble it.", "Dinner, " + pet + "."));
                LOG.info("[MCA-PETS] {} filled {}'s bowl with {} servings of {}", f.displayNameCap(), pet, put, e.cat ? "fish" : "meat");
                ERRANDS.remove(f.getUUID(), e);
                drop(level, f, e, null);
                return "filling " + pet + "'s bowl";
            }
            case FEED -> {
                if (a == null) {
                    drop(level, f, e, "the pet is not about");
                    return null;
                }
                if (!there && f.distanceToSqr(a) > 2.2 * 2.2) {
                    if (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 30) {
                        f.getNavigation().moveTo(a, 0.9D);
                        f.hobbyTick = f.tickCount;
                    }
                    return "taking " + pet + " its dinner";
                }
                if (f.removeMatching(foodFor(e.cat), 1) == 1) {
                    e.carried = Math.max(0, e.carried - 1);
                    fed(level, e.village, a, day);
                    f.swing(InteractionHand.MAIN_HAND);
                    say(f, FolkTalk.pick(r, "Here, " + pet + ". Out of my hand, then — we've no bowl yet.", "There you go. Good " + (e.cat ? "cat" : "dog") + "."));
                }
                ERRANDS.remove(f.getUUID(), e);
                drop(level, f, e, null);
                return "feeding " + pet + " by hand";
            }
            case PLACE -> {
                if (e.to == null || e.thing == null || h == null) {
                    drop(level, f, e, "nowhere to put it");
                    return null;
                }
                if (!there && !walk(f, e.to, 2.5)) return "carrying " + Bench.words(e.thing, 1) + " home for " + pet;
                BlockState st = level.getBlockState(e.to);
                if (!st.isAir()) {
                    e.to = spotFor(level, e.village, h, e.thing);
                    if (e.to == null || !level.getBlockState(e.to).isAir()) {
                        drop(level, f, e, "no room for it");
                        return null;
                    }
                }
                Item it = e.thing;
                if (f.removeMatching(s -> s.is(it), 1) != 1) {
                    drop(level, f, e, "it lost it on the way");
                    return null;
                }
                e.carried = 0;
                Block block = it == McAssistantMod.PET_BOWL_ITEM.get() ? McAssistantMod.PET_BOWL.get()
                    : it == McAssistantMod.CAT_BED_ITEM.get() ? McAssistantMod.CAT_BED.get() : McAssistantMod.DOG_BED.get();
                level.setBlock(e.to, block.defaultBlockState(), 3);
                level.playSound(null, e.to, block == McAssistantMod.PET_BOWL.get() ? SoundEvents.DECORATED_POT_PLACE : SoundEvents.WOOL_PLACE,
                    SoundSource.BLOCKS, 0.8F, 1.0F);
                Ledger.note(e.village, (block == McAssistantMod.PET_BOWL.get() ? "petbowl/" : "petbed/") + e.anchor, Long.toString(e.to.asLong()));
                f.swing(InteractionHand.MAIN_HAND);
                say(f, block == McAssistantMod.PET_BOWL.get() ? FolkTalk.pick(r, "A bowl of your own, " + pet + ". No more eating off the floor.",
                        "There. That's your bowl, " + pet + ".")
                    : FolkTalk.pick(r, "There, " + pet + " — a bed of your own.", "Your own bed, " + pet + ". Try it out!"));
                for (VillageFolkEntity m : Homes.loadedMembers(e.village, h)) {
                    m.persona().remember(day, "we got " + pet + " " + Bench.words(it, 1), 2);
                }
                LOG.info("[MCA-PETS] {} set out {} for {} at {}", f.displayNameCap(), Bench.words(it, 1), pet, e.to.toShortString());
                ERRANDS.remove(f.getUUID(), e);
                return "setting out " + Bench.words(it, 1) + " for " + pet;
            }
            case COLLAR, TREAT -> {
                if (a == null || e.thing == null) {
                    drop(level, f, e, "the pet is not about");
                    return null;
                }
                boolean collar = e.job == Job.COLLAR;
                if (!there && f.distanceToSqr(a) > 2.2 * 2.2) {
                    if (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 30) {
                        f.getNavigation().moveTo(a, 0.9D);
                        f.hobbyTick = f.tickCount;
                    }
                    return collar ? "taking " + pet + "'s new collar to it" : "taking " + pet + " a treat";
                }
                Item it = e.thing;
                ItemStack one = ItemStack.EMPTY;
                for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && s.is(it)) { one = s.copyWithCount(1); break; }
                if (one.isEmpty() || f.removeMatching(s -> s.is(it), 1) != 1) {
                    drop(level, f, e, "it lost it on the way");
                    return null;
                }
                e.carried = 0;
                f.swing(InteractionHand.MAIN_HAND);
                f.getLookControl().setLookAt(a, 30.0F, 30.0F);
                Care c = care(e.village, a.getUUID());
                if (collar) {
                    DyeColor colour = collarColour(one, e.village);
                    putCollar(a, colour);
                    c.collar = colour.getName();
                    save(e.village, c);
                    say(f, FolkTalk.pick(r, "Hold still, " + pet + "… there. Very smart.", "A new " + colour.getName().replace('_', ' ') + " collar, " + pet
                        + ". Don't you look grand?"));
                } else {
                    c.treatDay = day;
                    c.treats++;
                    a.heal(2.0F);
                    save(e.village, c);
                    level.broadcastEntityEvent(a, (byte) 7);
                    say(f, f.isBaby() ? FolkTalk.pick(r, "Who's a good " + (e.cat ? "kitty" : "boy") + "? You are! Here!", pet + "! I've got you a treat!",
                            "Sit, " + pet + "… good! Treat!")
                        : FolkTalk.pick(r, "Go on, then. Just the one.", "A treat for being good, " + pet + "."));
                }
                ERRANDS.remove(f.getUUID(), e);
                return collar ? "putting a new collar on " + pet : "giving " + pet + " a treat";
            }
            case TAKE_IN -> {
                if (a == null || a.isTame() || h == null || Families.pet(e.village, e.anchor) != null) {
                    drop(level, f, e, "the stray is gone");
                    return null;
                }
                String kind = kindWord(e.cat);
                if (!there && f.distanceToSqr(a) > 2.4 * 2.4) {
                    if (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 30 || f.tickCount < f.hobbyTick) {
                        f.getNavigation().moveTo(a, 0.8D);
                        f.hobbyTick = f.tickCount;
                    }
                    return "going out to the stray " + kind + " with a bit of " + (e.cat ? "fish" : "meat");
                }
                f.getNavigation().stop();
                f.getLookControl().setLookAt(a, 30.0F, 30.0F);
                a.getLookControl().setLookAt(f, 30.0F, 30.0F);
                if (f.removeMatching(foodFor(e.cat), 1) == 1) e.carried = Math.max(0, e.carried - 1);
                f.swing(InteractionHand.MAIN_HAND);
                // A stray that has hung about the town for days, fed scraps at back doors, comes at the first offer.
                adopt(level, e.village, h, a, day, "a stray that had been hanging about the town");
                fed(level, e.village, a, day);
                say(f, e.cat ? FolkTalk.pick(r, "There, puss. You're one of us now.", "Come on, then. Home.")
                    : FolkTalk.pick(r, "Good dog! You're ours now.", "There's a good lad. Come on home."));
                ERRANDS.remove(f.getUUID(), e);
                drop(level, f, e, null);
                return "making friends with a stray " + kind;
            }
            case BURY -> {
                if (e.to == null) {
                    drop(level, f, e, "nowhere to bury it");
                    return null;
                }
                if (!there && !walk(f, e.to, 2.5)) return "carrying " + e.dead + " out to the garden";
                grave(level, v, e, day);
                f.swing(InteractionHand.MAIN_HAND);
                say(f, FolkTalk.pick(r, "Goodbye, " + e.dead + ". Best " + e.deadKind + " there ever was.", "Sleep well, " + e.dead + ".",
                    "There. Under the flowers, where you liked to lie."));
                for (VillageFolkEntity k : h == null ? List.<VillageFolkEntity>of() : children(e.village, h)) {
                    sayLater(k, FolkTalk.pick(r, "I'll miss you, " + e.dead + ".", "*sniff* Bye, " + e.dead + "."), 50);
                }
                ERRANDS.remove(f.getUUID(), e);
                return "burying " + e.dead + " in the garden";
            }
            case MAKE -> {
                if (h == null || e.to == null) {
                    drop(level, f, e, "its home is gone");
                    return null;
                }
                if (!there && !walk(f, e.to, 3.0)) return "going home to make " + pet + " a bowl";
                Bench.Hand hand = Bench.handOf(level, v, f, null);
                Bench.Plan plan = Bench.plan(level, v, McAssistantMod.PET_BOWL_ITEM.get(), 1, hand);
                ItemStack made = plan.ok() ? Bench.make(level, v, plan, f, hand) : ItemStack.EMPTY;
                if (made.isEmpty() || !TownWork.take(level, v, s -> s.is(McAssistantMod.PET_BOWL_ITEM.get()), 1)) {
                    drop(level, f, e, "it could not make one");
                    return null;
                }
                ItemStack left = f.insertGiven(new ItemStack(McAssistantMod.PET_BOWL_ITEM.get()));
                if (!left.isEmpty()) {
                    Crafts.store(level, v, left);
                    drop(level, f, e, "no room in its pack");
                    return null;
                }
                f.swing(InteractionHand.MAIN_HAND);
                level.playSound(null, f.blockPosition(), SoundEvents.WOOD_PLACE, SoundSource.NEUTRAL, 0.7F, 1.0F);
                say(f, FolkTalk.pick(r, "Nobody in the town makes these yet, so I've knocked one together myself.", "There — a bowl for " + pet + ". Not bad, eh?"));
                // ...and on to setting it out.
                e.job = Job.PLACE;
                e.thing = McAssistantMod.PET_BOWL_ITEM.get();
                e.carried = 1;
                e.to = spotFor(level, e.village, h, e.thing);
                if (e.to == null) {
                    drop(level, f, e, "no room for it");
                    return null;
                }
                return "knocking together a bowl for " + pet;
            }
        }
        return null;
    }

    /** Into the household's chest, else the stores. */
    private static void giveBack(ServerLevel level, Villages.Village v, @Nullable Homes.Home h, ItemStack s, int n) {
        if (s.isEmpty() || n <= 0) return;
        ItemStack left = s.copyWithCount(n);
        if (h != null) {
            BlockPos chest = Homes.chestOf(level, v.id(), h);
            if (chest != null && level.getBlockEntity(chest) instanceof Container c) {
                left = Homes.insertInto(c, left);
                c.setChanged();
            }
        }
        if (!left.isEmpty()) Crafts.store(level, v, left);
    }

    /** The errand let go: what it carried for it back where it came from. */
    private static void drop(ServerLevel level, VillageFolkEntity f, Errand e, @Nullable String why) {
        ERRANDS.remove(f.getUUID(), e);
        Villages.Village v = Villages.get(e.village);
        Homes.Home h = Homes.homes(e.village).get(e.anchor);
        if (v != null && e.carried > 0) {
            if (e.job == Job.FILL || e.job == Job.FEED || e.job == Job.TAKE_IN) {
                // Only what it took out for the pet: the bread and the beef it carries for itself are its own.
                Predicate<ItemStack> food = foodFor(e.cat);
                int n = Math.min(e.carried, f.countCarried(food));
                for (int i = 0; i < n; i++) {
                    ItemStack one = ItemStack.EMPTY;
                    for (ItemStack s : f.getInventoryItems()) {
                        if (!s.isEmpty() && food.test(s) && !Homes.isKeepsake(s)) { one = s.split(1); break; }
                    }
                    if (one.isEmpty()) break;
                    giveBack(level, v, h, one, 1);
                }
            } else if (e.thing != null && e.job != Job.MAKE) {
                Item it = e.thing;
                if (f.removeMatching(s -> s.is(it), 1) == 1) giveBack(level, v, h, new ItemStack(it), 1);
            }
        }
        e.carried = 0;
        if (why != null) LOG.info("[MCA-PETS] {} let its {} errand go: {}", f.displayNameCap(), e.job, why);
    }

    /** It has eaten today. */
    static void fed(ServerLevel level, UUID village, TamableAnimal a, long day) {
        Care c = care(village, a.getUUID());
        c.fedDay = day;
        save(village, c);
        a.heal(4.0F);
        level.playSound(null, a.blockPosition(), a instanceof Cat ? SoundEvents.CAT_EAT : SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.7F, 1.0F);
    }

    // ------------------------------------------------------------------ the pet's day (Families.drivePet)

    /**
     * The pet's own business, every half second (Families.drivePet, from whichever of its household is about): what it
     * is doing now, or null to leave it to Families (at a child's heels by day, sitting at home at night).
     */
    @Nullable
    public static String drive(ServerLevel level, UUID village, Homes.Home h, TamableAnimal a) {
        Families.Pet p = Families.pet(village, h.anchor.asLong());
        if (p == null || !a.getUUID().equals(p.id())) return null;
        harness(a);
        String doing = day(level, village, h, a, p);
        if (doing == null) RESTING.remove(a.getUUID());             // Families has it now (a child's heels, the hearth)
        return doing;
    }

    @Nullable
    private static String day(ServerLevel level, UUID village, Homes.Home h, TamableAnimal a, Families.Pet p) {
        long dt = level.getDayTime(), t = dt % 24000L, day = dt / 24000L;
        Care c = know(village, p, a, day);
        boolean cat = a instanceof Cat;
        young(level, village, h, a);
        if (c.lostDay >= 0) return lostStep(level, village, h, a, c, p, day, t);
        String doing = followPlayer(level, a);
        if (doing != null) return doing;
        if (!cat) {
            doing = bark(level, village, a, c, t, day);
            if (doing != null) return doing;
        } else {
            creeperWatch(level, (Cat) a);
        }
        if (c.sick) return sickStep(level, village, h, a, c);
        doing = eatStep(level, village, h, a, c, p, day, t);
        if (doing != null) return doing;
        if (cat) {
            doing = fishStep(level, village, (Cat) a, c, day);
            if (doing != null) return doing;
        }
        doing = begStep(level, village, h, a, c, day, t);
        if (doing != null) return doing;
        return cat ? catDay(level, village, h, (Cat) a, c, t, day) : dogDay(level, village, h, a, c, t);
    }

    /** A litter's young keep near their mother till they go to a home of their own. */
    private static void young(ServerLevel level, UUID village, Homes.Home h, TamableAnimal mother) {
        for (Other o : others(village).values()) {
            if (!Other.YOUNG.equals(o.role()) || o.home() != h.anchor.asLong()) continue;
            if (!(level.getEntity(o.animal()) instanceof TamableAnimal y) || !y.isAlive()) continue;
            harness(y);
            y.setOrderedToSit(false);
            y.setInSittingPose(false);
            double d = y.distanceToSqr(mother);
            if (d > 16.0 * 16.0) y.teleportTo(mother.getX(), mother.getY(), mother.getZ());
            else if (d > 2.5 * 2.5) y.getNavigation().moveTo(mother, 1.15D);
        }
    }

    @Nullable
    private static String followPlayer(ServerLevel level, TamableAnimal a) {
        Follow f = FOLLOWING.get(a.getUUID());
        if (f == null) return null;
        Player p = level.getPlayerByUUID(f.player());
        if (p == null || level.getGameTime() > f.until() || p.distanceToSqr(a) > 32.0 * 32.0) {
            FOLLOWING.remove(a.getUUID());
            return null;
        }
        heel(a, p, 1.15D);
        return "trotting after " + f.name();
    }

    /** At night, or with the bell ringing, a dog barks at a monster near it; the first bark at each wakes the watch. */
    @Nullable
    private static String bark(ServerLevel level, UUID village, TamableAnimal dog, Care c, long t, long day) {
        boolean night = t >= 12500L || t < 500L;
        if (!night && !Raids.underAlarm(village)) return null;
        Villages.Village v = Villages.get(village);
        if (v == null) return null;
        Mob near = null;
        double bd = (double) BARK_REACH * BARK_REACH;
        for (Mob m : level.getEntitiesOfClass(Mob.class, dog.getBoundingBox().inflate(BARK_REACH, 8, BARK_REACH),
                m -> m.isAlive() && (WatchClears.monster(m) || m instanceof Raider))) {
            if (!WatchClears.upAbout(level, v.centre(), m)) continue;
            double d = m.distanceToSqr(dog);
            if (d < bd) { bd = d; near = m; }
        }
        if (near == null) return null;
        RESTING.remove(dog.getUUID());
        dog.setOrderedToSit(false);
        dog.setInSittingPose(false);
        dog.setTarget(null);
        dog.getNavigation().stop();
        dog.getLookControl().setLookAt(near, 30.0F, 30.0F);
        long now = level.getGameTime();
        if (now - BARKED.getOrDefault(dog.getUUID(), -1000L) >= 30L) {
            BARKED.put(dog.getUUID(), now);
            level.playSound(null, dog.blockPosition(), bd < 6.0 * 6.0 ? SoundEvents.WOLF_GROWL : SoundEvents.WOLF_AMBIENT, SoundSource.NEUTRAL,
                1.5F, 0.9F + dog.getRandom().nextFloat() * 0.2F);
        }
        String what = nameOf(near);
        if (WARNED.add(near.getUUID())) {
            if (WARNED.size() > 512) WARNED.clear();
            String sent = warn(level, v, near, now);
            c.barks++;
            c.barkDay = t >= 12000L ? day + 1 : day;          // talked of the morning after
            c.barkedAt = what;
            save(village, c);
            LOG.info("[MCA-PETS] {} barked at {} by {}; {}", c.name, a(what), near.blockPosition().toShortString(), sent);
        }
        return "barking at " + a(what);
    }

    /** The watch hears the bark: the nearest guard free, else the nearest asleep (woken by it), sent after the monster. */
    static String warn(ServerLevel level, Villages.Village v, Mob m, long now) {
        List<VillageFolkEntity> watch = Patrols.watch(v.id());
        if (WatchClears.hunters(watch, m) > 0) return "the watch is after it already";
        int reach = Villages.townReach(v.id());
        boolean bell = Raids.underAlarm(v.id());
        VillageFolkEntity best = null;
        double bd = 96.0 * 96.0;
        for (VillageFolkEntity g : watch) {
            boolean free = WatchClears.free(g, v.centre(), reach, bell)
                || g.isSleeping() && WatchClears.free(g, v.centre(), reach, true);   // asleep: the bark wakes it
            if (!free || WatchClears.hunting(g)) continue;
            double d = g.distanceToSqr(m);
            if (d < bd) { bd = d; best = g; }
        }
        if (best == null) return "no guard to hear it";
        boolean woke = best.isSleeping();
        WatchClears.send(best, m, 8.0, now);
        return best.displayNameCap() + (woke ? " woke and went out after it" : " went after it");
    }

    /** A creeper near a cat: a hiss (the creeper fears it, as in the game), and one seen off is remembered. */
    private static void creeperWatch(ServerLevel level, Cat cat) {
        for (Creeper cr : level.getEntitiesOfClass(Creeper.class, cat.getBoundingBox().inflate(8.0, 4.0, 8.0), Entity::isAlive)) {
            if (CREEPERS.putIfAbsent(cr.getUUID(), cat.getUUID()) == null) {
                cat.hiss();
                cat.getLookControl().setLookAt(cr, 30.0F, 30.0F);
            }
        }
    }

    /** Ill: home to its bed (or the hearth), curled up. */
    private static String sickStep(ServerLevel level, UUID village, Homes.Home h, TamableAnimal a, Care c) {
        BlockPos bed = bedOf(level, village, h, a instanceof Cat);
        BlockPos spot = bed != null ? bed : Families.hearth(level, village, h);
        double y = bed != null ? spot.getY() + 0.3 : spot.getY();
        if (goTo(a, spot.getX() + 0.5, y, spot.getZ() + 0.5, 0.7D, 3.0)) {
            rest(a, true);
            return "poorly, curled up at home";
        }
        return "poorly, on its way home";
    }

    /** Hungry, and something in its bowl: off to it, and a serving eaten. */
    @Nullable
    private static String eatStep(ServerLevel level, UUID village, Homes.Home h, TamableAnimal a, Care c, Families.Pet p, long day, long t) {
        if (c.fedDay >= day || t < 1000L || t >= 13000L) return null;
        BlockPos bowl = bowlOf(level, village, h);
        if (bowl == null || PetBowlBlock.servings(level.getBlockState(bowl)) <= 0) return null;
        BlockPos by = beside(level, bowl);
        if (goTo(a, by.getX() + 0.5, by.getY() + (by.equals(bowl) ? 0.25 : 0.0), by.getZ() + 0.5, 1.1D, 1.6)) {
            a.getLookControl().setLookAt(bowl.getX() + 0.5, bowl.getY(), bowl.getZ() + 0.5);
            if (PetBowlBlock.eat(level, bowl)) {
                fed(level, village, a, day);
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, a.getX(), a.getY() + 0.6, a.getZ(), 4, 0.3, 0.2, 0.3, 0.0);
                LOG.info("[MCA-PETS] {} ate from its bowl ({} left)", c.name, PetBowlBlock.servings(level.getBlockState(bowl)));
            }
            return "eating from its bowl";
        }
        return "off to its bowl";
    }

    /** The floor beside a thing (where a pet stands to eat from a bowl), or the thing itself if there is none. */
    static BlockPos beside(ServerLevel level, BlockPos at) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos c = at.relative(d);
            if (level.getBlockState(c).getCollisionShape(level, c).isEmpty() && level.getBlockState(c.above()).getCollisionShape(level, c.above()).isEmpty()
                    && level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP)) return c;
        }
        return at;
    }

    /** A cat that has not eaten calls on a fisher at work nearby, who spares it a fish from its catch. */
    @Nullable
    private static String fishStep(ServerLevel level, UUID village, Cat cat, Care c, long day) {
        UUID fid = CAT_FISH.get(cat.getUUID());
        if (fid == null) return null;
        if (!(level.getEntity(fid) instanceof VillageFolkEntity fisher) || !fisher.isAlive() || fisher.isSleeping()
                || fisher.countCarried(CAT_FOOD) <= 0 || fisher.distanceToSqr(cat) > 64.0 * 64.0) {
            CAT_FISH.remove(cat.getUUID());
            return null;
        }
        if (cat.distanceToSqr(fisher) > 2.0 * 2.0) {
            wake(cat);
            if (cat.getNavigation().isDone() || cat.tickCount % 40 < 10) cat.getNavigation().moveTo(fisher, 1.1D);
            return "off to beg a fish from " + fisher.displayNameCap();
        }
        CAT_FISH.remove(cat.getUUID());
        if (fisher.removeMatching(CAT_FOOD, 1) == 1) {
            fisher.swing(InteractionHand.MAIN_HAND);
            fed(level, village, cat, day);
            level.broadcastEntityEvent(cat, (byte) 7);
            say(fisher, FolkTalk.pick(cat.getRandom(), "Here, " + c.name + " — one for you. Don't tell the others.",
                "Ha! " + c.name + " again. Go on, then.", "The one that got away, " + c.name + ". Off you go."));
            LOG.info("[MCA-PETS] {} gave {} a fish", fisher.displayNameCap(), c.name);
        }
        return "eating a fish from " + fisher.displayNameCap();
    }

    /** Not fed today by the afternoon, and nothing in its bowl: it follows a grown-up of the house about and begs. */
    @Nullable
    private static String begStep(ServerLevel level, UUID village, Homes.Home h, TamableAnimal a, Care c, long day, long t) {
        if (c.fedDay >= day || t < 9000L || t >= 13000L) return null;
        VillageFolkEntity who = null;
        double bd = 24.0 * 24.0;
        for (VillageFolkEntity m : grown(village, h)) {
            if (m.isSleeping()) continue;
            double d = m.distanceToSqr(a);
            if (d < bd) { bd = d; who = m; }
        }
        if (who == null) return null;
        if (a.distanceToSqr(who) > 1.8 * 1.8) {
            wake(a);
            a.getNavigation().moveTo(who, 1.1D);
        } else {
            rest(a, false);
            a.getLookControl().setLookAt(who, 30.0F, 30.0F);
            if (a.tickCount % 80 < 10) {
                level.playSound(null, a.blockPosition(), a instanceof Cat ? SoundEvents.CAT_BEG_FOR_FOOD : SoundEvents.WOLF_WHINE,
                    SoundSource.NEUTRAL, 0.8F, 1.0F);
            }
        }
        return "begging " + who.displayNameCap() + " for its supper";
    }

    /**
     * A cat's day: asleep in its basket or on a child's bed at night; on the roof in the sun on a fine afternoon; in a
     * window, or pottering about the house and garden, of a morning and an evening.
     */
    @Nullable
    private static String catDay(ServerLevel level, UUID village, Homes.Home h, Cat cat, Care c, long t, long day) {
        boolean night = t >= 12500L || t < 500L;
        long now = level.getGameTime();
        if (night) {
            BlockPos basket = bedOf(level, village, h, true);
            if (basket != null) {
                if (goTo(cat, basket.getX() + 0.5, basket.getY() + 0.32, basket.getZ() + 0.5, 1.0D, 3.0)) {
                    rest(cat, true);
                    return "asleep in its basket";
                }
                return "off to its basket";
            }
            Object[] bed = bedFor(level, village, h);
            if (bed != null) {
                BlockPos foot = (BlockPos) bed[0];
                if (goTo(cat, foot.getX() + 0.5, foot.getY() + 0.5625, foot.getZ() + 0.5, 1.0D, 3.0)) {
                    rest(cat, true);
                    return "asleep on " + bed[1] + "'s bed";
                }
                return "padding off to " + bed[1] + "'s bed";
            }
            RESTING.remove(cat.getUUID());
            return null;
        }
        if (!level.isRaining() && t >= 5000L && t < 9500L) {
            BlockPos roof = roofOf(level, village, h);
            if (roof != null) {
                if (goTo(cat, roof.getX() + 0.5, roof.getY(), roof.getZ() + 0.5, 1.0D, 24.0)) {
                    rest(cat, true);
                    return "asleep on the roof in the sun";
                }
                return "off up onto the roof";
            }
        }
        CatPlan plan = CAT_PLAN.get(cat.getUUID());
        if (plan == null || now > plan.until() || now < plan.until() - 4000L) {
            plan = planFor(level, village, h, cat, now);
            if (plan == null) {
                RESTING.remove(cat.getUUID());
                return null;
            }
            CAT_PLAN.put(cat.getUUID(), plan);
        }
        BlockPos s = plan.spot();
        if (goTo(cat, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 0.8D, plan.window() ? 2.5 : 0.0)) {
            if (plan.window()) {
                rest(cat, false);
                BlockPos w = plan.looking();
                if (w != null) {
                    double dx = w.getX() + 0.5 - cat.getX(), dz = w.getZ() + 0.5 - cat.getZ();
                    float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
                    cat.setYRot(yaw);
                    cat.setYHeadRot(yaw);
                    cat.yBodyRot = yaw;
                    cat.getLookControl().setLookAt(w.getX() + 0.5, w.getY() + 0.5, w.getZ() + 0.5);
                }
                return "sitting in the window";
            }
            rest(cat, false);
            return Homes.homeAt(village, s) == h ? "curled up about the house" : "sunning itself in the garden";
        }
        return plan.window() ? "padding over to the window" : "pottering about the garden";
    }

    /** The window, or a spot about the house and garden, for the next minute or so. */
    @Nullable
    private static CatPlan planFor(ServerLevel level, UUID village, Homes.Home h, Cat cat, long now) {
        RandomSource r = cat.getRandom();
        Ledger.Building b = Homes.building(village, h.anchor);
        if (b != null && !Flats.isFlat(h) && r.nextInt(3) != 0) {
            Decor.Room room = Decor.room(village, b);
            List<Map.Entry<BlockPos, BlockPos>> sills = new ArrayList<>(room.sills().entrySet());
            if (!sills.isEmpty()) {
                Map.Entry<BlockPos, BlockPos> e = sills.get(Math.floorMod(cat.getUUID().hashCode() + (int) (now / 2400L), sills.size()));
                BlockPos w = e.getKey(), sill = e.getValue();
                if (level.isLoaded(sill) && !level.getBlockState(w).isAir()) {
                    BlockPos seat = null;
                    if (level.getBlockState(sill).isAir() && level.getBlockState(sill.below()).isFaceSturdy(level, sill.below(), Direction.UP)) seat = sill;
                    else if (level.getBlockState(sill.below()).isAir() && level.getBlockState(sill.below(2)).isFaceSturdy(level, sill.below(2), Direction.UP)) seat = sill.below();
                    if (seat != null) return new CatPlan(true, seat, w, now + 1200L + r.nextInt(1200));
                }
            }
        }
        BlockPos hearth = Families.hearth(level, village, h);
        for (int tries = 0; tries < 8; tries++) {
            int dx = r.nextInt(17) - 8, dz = r.nextInt(17) - 8;
            BlockPos c = hearth.offset(dx, 0, dz);
            BlockPos ground;
            if (Homes.homeAt(village, c) == h) {
                ground = c;
                for (int k = 0; k < 3 && !level.getBlockState(ground).isAir(); k++) ground = ground.above();
            } else {
                ground = new BlockPos(c.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()), c.getZ());
                if (Math.abs(ground.getY() - hearth.getY()) > 3) continue;
            }
            if (!level.getBlockState(ground).isAir() || !level.getBlockState(ground.below()).isFaceSturdy(level, ground.below(), Direction.UP)) continue;
            if (!level.getBlockState(ground.below()).getFluidState().isEmpty()) continue;
            return new CatPlan(false, ground, null, now + 600L + r.nextInt(900));
        }
        return null;
    }

    /** The bed a pet sleeps on: a child's of the house first (asleep in it or not), else its keeper's. {foot, whose}. */
    @Nullable
    private static Object[] bedFor(ServerLevel level, UUID village, Homes.Home h) {
        List<VillageFolkEntity> order = new ArrayList<>(children(village, h));
        order.addAll(grown(village, h));
        for (VillageFolkEntity m : order) {
            BlockPos head = m.bedPos();
            if (head == null || !level.isLoaded(head)) continue;
            BlockState st = level.getBlockState(head);
            if (!(st.getBlock() instanceof BedBlock)) continue;
            Direction facing = st.getValue(BedBlock.FACING);
            BlockPos foot = st.getValue(BedBlock.PART) == BedPart.HEAD ? head.relative(facing.getOpposite()) : head;
            if (!(level.getBlockState(foot).getBlock() instanceof BedBlock) || !level.getBlockState(foot.above()).isAir()) continue;
            return new Object[]{ foot.immutable(), m.displayNameCap() };
        }
        return null;
    }

    /** The house's roof: its highest point over the rooms, in the open (the ridge, nearest the middle of the house). */
    @Nullable
    static BlockPos roofOf(ServerLevel level, UUID village, Homes.Home h) {
        String key = village + "/" + h.anchor.asLong() + "/roof";
        long now = level.getGameTime();
        Object[] seen = LOOKED.get(key);
        if (seen != null && now - (Long) seen[0] < 600L && now >= (Long) seen[0]) return (BlockPos) seen[1];
        BlockPos roof = roofNow(level, village, h);
        LOOKED.put(key, new Object[]{ now, roof });
        return roof;
    }

    @Nullable
    private static BlockPos roofNow(ServerLevel level, UUID village, Homes.Home h) {
        Ledger.Building b = Homes.building(village, h.anchor);
        if (b == null) return null;
        List<BlockPos> floor = Decor.room(village, b).floor();
        BlockPos best = null;
        for (BlockPos c : floor) {
            if (!level.isLoaded(c)) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
            BlockPos at = new BlockPos(c.getX(), top, c.getZ());
            if (top <= c.getY() + 2) continue;                         // open to the sky over the room: no roof here
            BlockState under = level.getBlockState(at.below());
            if (under.isAir() || !under.getFluidState().isEmpty() || under.getBlock() instanceof net.minecraft.world.level.block.CampfireBlock) continue;
            if (!level.getBlockState(at).isAir() || !level.getBlockState(at.above()).isAir()) continue;
            if (best == null || top > best.getY() || top == best.getY() && at.distSqr(h.anchor) < best.distSqr(h.anchor)) best = at;
        }
        return best;
    }

    /**
     * A dog's day, where Families' does not have it: in its bed by the door at night, or at the foot of a sleeping
     * child's bed; by day a stick to fetch now and then while a child plays, and with the children at school, along
     * with a grown-up of the house to its work.
     */
    @Nullable
    private static String dogDay(ServerLevel level, UUID village, Homes.Home h, TamableAnimal dog, Care c, long t) {
        boolean night = t >= 12500L || t < 500L;
        if (night) {
            BlockPos bed = bedOf(level, village, h, false);
            if (bed != null) {
                if (goTo(dog, bed.getX() + 0.5, bed.getY() + 0.25, bed.getZ() + 0.5, 1.0D, 2.0)) {
                    rest(dog, false);
                    return "asleep in its bed by the door";
                }
                return "off to its bed";
            }
            for (VillageFolkEntity k : children(village, h)) {
                if (!k.isSleeping() || k.bedPos() == null) continue;
                BlockPos spot = footOfBed(level, k.bedPos());
                if (spot == null) continue;
                if (goTo(dog, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 1.0D, 2.0)) {
                    rest(dog, false);
                    return "asleep at the foot of " + k.displayNameCap() + "'s bed";
                }
                return "off to " + k.displayNameCap() + "'s room";
            }
            return null;
        }
        // A child up and about (as Families has it): its heels, and now and then a stick to fetch.
        VillageFolkEntity child = null;
        if (t >= 1000L && t < 12500L) {
            double best = 64.0 * 64.0;
            for (VillageFolkEntity k : Homes.loadedMembers(village, h)) {
                if (!k.isBaby() || k.isSleeping() || School.doing(k) != null) continue;
                double d = k.distanceToSqr(dog);
                if (d < best) { best = d; child = k; }
            }
        }
        if (child != null) return fetchStick(level, dog, child, c, t);
        return walkies(level, village, h, dog, t);
    }

    /** The floor beside the foot of a bed (where a dog lies), or null. */
    @Nullable
    private static BlockPos footOfBed(ServerLevel level, BlockPos head) {
        BlockState st = level.getBlockState(head);
        if (!(st.getBlock() instanceof BedBlock)) return null;
        Direction facing = st.getValue(BedBlock.FACING);
        BlockPos foot = st.getValue(BedBlock.PART) == BedPart.HEAD ? head.relative(facing.getOpposite()) : head;
        for (BlockPos c : new BlockPos[]{ foot.relative(facing.getOpposite()), foot.relative(facing.getClockWise()), foot.relative(facing.getCounterClockWise()) }) {
            if (level.getBlockState(c).isAir() && level.getBlockState(c.above()).isAir()
                    && level.getBlockState(c.below()).isFaceSturdy(level, c.below(), Direction.UP)) return c.immutable();
        }
        return null;
    }

    /** A game of fetch: the child throws, the dog runs out to the stick and brings it back. Null to leave it at its heels. */
    @Nullable
    private static String fetchStick(ServerLevel level, TamableAnimal dog, VillageFolkEntity child, Care c, long t) {
        long now = level.getGameTime();
        Fetch fe = FETCH.get(dog.getUUID());
        if (fe == null) {
            if (t < Families.PLAY_FROM || t >= Families.SUPPER_FROM || dog.distanceToSqr(child) > 6.0 * 6.0
                    || !level.canSeeSky(child.blockPosition()) || dog.getRandom().nextInt(40) != 0) return null;
            float yaw = child.getYRot() * ((float) Math.PI / 180F);
            int reach = 6 + dog.getRandom().nextInt(4);
            int x = (int) Math.floor(child.getX() - Math.sin(yaw) * reach), z = (int) Math.floor(child.getZ() + Math.cos(yaw) * reach);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos stick = new BlockPos(x, y, z);
            if (Math.abs(y - child.getBlockY()) > 2 || !level.getBlockState(stick.below()).getFluidState().isEmpty()) return null;
            fe = new Fetch(child.getUUID(), stick, now + 400L);
            FETCH.put(dog.getUUID(), fe);
            child.swing(InteractionHand.MAIN_HAND);
            say(child, FolkTalk.pick(child.getRandom(), "Fetch, " + c.name + "!", "Go on, " + c.name + " — fetch!", "Ready, " + c.name + "? Fetch!"));
        }
        if (!fe.child.equals(child.getUUID()) || now > fe.until) {
            FETCH.remove(dog.getUUID());
            return null;
        }
        wake(dog);
        if (!fe.back) {
            if (dog.distanceToSqr(fe.stick.getX() + 0.5, fe.stick.getY(), fe.stick.getZ() + 0.5) > 1.5 * 1.5) {
                if (dog.getNavigation().isDone() || dog.tickCount % 20 < 10) {
                    dog.getNavigation().moveTo(fe.stick.getX() + 0.5, fe.stick.getY(), fe.stick.getZ() + 0.5, 1.5D);
                }
            } else {
                fe.back = true;
                level.playSound(null, dog.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.5F, 0.8F);
            }
            return "fetching a stick for " + child.displayNameCap();
        }
        if (dog.distanceToSqr(child) > 2.0 * 2.0) {
            if (dog.getNavigation().isDone() || dog.tickCount % 20 < 10) dog.getNavigation().moveTo(child, 1.4D);
            return "bringing the stick back to " + child.displayNameCap();
        }
        FETCH.remove(dog.getUUID());
        level.broadcastEntityEvent(dog, (byte) 7);                    // a wag and a lick: hearts
        sayLater(child, FolkTalk.pick(child.getRandom(), "Good " + (dog.isBaby() ? "puppy" : "boy") + ", " + c.name + "!", "Again! Again!",
            c.name + " is the best dog in the whole town."), 10);
        return "wagging at " + child.displayNameCap() + " with the stick";
    }

    /** With the children at school (or none of them up), a dog trots along with a grown-up of the house about its day. */
    @Nullable
    private static String walkies(ServerLevel level, UUID village, Homes.Home h, TamableAnimal dog, long t) {
        if (t < 1000L || t >= 12500L) return null;
        Villages.Village v = Villages.get(village);
        if (v == null) return null;
        Families.Pet p = Families.pet(village, h.anchor.asLong());
        VillageFolkEntity best = null;
        for (VillageFolkEntity m : grown(village, h)) {
            if (m.isSleeping() || Patrols.away(m) || m.isHired() || m.distanceToSqr(dog) > 48.0 * 48.0) continue;
            if (m.getY() < v.centre().getY() - 12) continue;            // down the mine: it waits at home
            if (best == null || p != null && m.getUUID().equals(p.keeper())) best = m;
        }
        if (best == null) return null;
        heel(dog, best, 1.15D);
        return "trotting along with " + best.displayNameCap() + (best.offWorkNow() ? "" : " to work");
    }

    // ------------------------------------------------------------------ lost (the quest board's seam)

    /**
     * A pet gone missing: which, whose, its name and kind, its household's home, where it is now (out past the town's
     * edge), the day it went, and whether the quest board is holding it there (else it makes its own way home in a day).
     */
    public record Lost(UUID pet, UUID village, String name, String kind, @Nullable UUID owner, String ownerName, BlockPos home, BlockPos at,
                       long day, boolean held) {}

    /**
     * The quest board's "find my lost dog": one of the town's pets (a dog for choice) goes missing now, off after a
     * rabbit and out past the town's edge, where it waits to be found. Held: it stays out there until a player brings it
     * home (click it, and it follows you) or {@link #release} lets it find its own way. Null if no pet can go.
     */
    @Nullable
    public static Lost lost(ServerLevel level, UUID village) {
        return lose(level, village, null, true);
    }

    /** As {@link #lost(ServerLevel, UUID)}, for this pet in particular. */
    @Nullable
    public static Lost lost(ServerLevel level, UUID village, UUID pet) {
        return lose(level, village, pet, true);
    }

    /** Every pet of the town missing now. */
    public static List<Lost> lostIn(ServerLevel level, UUID village) {
        List<Lost> out = new ArrayList<>();
        for (Homes.Home h : List.copyOf(Homes.homes(village).values())) {
            Families.Pet p = Families.pet(village, h.anchor.asLong());
            if (p == null) continue;
            Care c = care(village, p.id());
            if (c.lostDay >= 0 && c.lostAt != null) out.add(lostOf(level, village, h, p, c));
        }
        return out;
    }

    /** Is this pet missing? */
    public static boolean isLost(UUID pet) {
        Care c = CARE.get(pet);
        return c != null && c.lostDay >= 0;
    }

    /** Hear of every lost pet a player brings home (the quest board pays out on it). */
    public static void onFound(BiConsumer<Lost, ServerPlayer> listener) {
        FOUND.add(listener);
    }

    /** The quest board lets a missing pet go: it makes its own way home tomorrow morning. */
    public static void release(UUID village, UUID pet) {
        Care c = care(village, pet);
        if (c.lostDay < 0) return;
        c.held = false;
        save(village, c);
    }

    private static Lost lostOf(ServerLevel level, UUID village, Homes.Home h, Families.Pet p, Care c) {
        VillageFolkEntity owner = level.getEntity(p.keeper()) instanceof VillageFolkEntity f ? f : null;
        return new Lost(p.id(), village, p.name(), "cat".equals(p.kind()) ? "cat" : "dog", p.keeper(), owner == null ? "" : owner.displayNameCap(),
            h.anchor, c.lostAt == null ? h.anchor : c.lostAt, c.lostDay, c.held);
    }

    @Nullable
    static Lost lose(ServerLevel level, UUID village, @Nullable UUID which, boolean held) {
        Villages.Village v = Villages.get(village);
        if (v == null) return null;
        long day = level.getDayTime() / 24000L;
        Homes.Home pick = null;
        TamableAnimal animal = null;
        for (Homes.Home h : List.copyOf(Homes.homes(village).values())) {
            Families.Pet p = Families.pet(village, h.anchor.asLong());
            if (p == null || which != null && !p.id().equals(which)) continue;
            if (!(level.getEntity(p.id()) instanceof TamableAnimal a) || !a.isAlive() || a.isBaby()) continue;
            if (care(village, p.id()).lostDay >= 0) continue;
            boolean dog = !(a instanceof Cat);
            if (pick == null || dog && animal instanceof Cat) { pick = h; animal = a; }
        }
        if (pick == null) return null;
        Families.Pet p = Families.pet(village, pick.anchor.asLong());
        Care c = know(village, p, animal, day);
        RandomSource r = level.getRandom();
        BlockPos at = null;
        int reach = Villages.townReach(village);
        for (int tries = 0; tries < 24 && at == null; tries++) {
            double ang = r.nextDouble() * Math.PI * 2.0;
            int dist = reach + LOST_FROM + r.nextInt(LOST_TO - LOST_FROM + 1);
            int x = v.centre().getX() + (int) Math.round(Math.cos(ang) * dist), z = v.centre().getZ() + (int) Math.round(Math.sin(ang) * dist);
            if (!level.isLoaded(new BlockPos(x, 64, z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos g = new BlockPos(x, y, z);
            if (!level.getBlockState(g.below()).getFluidState().isEmpty() || !level.getBlockState(g.below()).isFaceSturdy(level, g.below(), Direction.UP)) continue;
            if (Homes.homeAt(village, g) != null) continue;
            at = g;
        }
        if (at == null) return null;
        wake(animal);
        animal.getNavigation().stop();
        animal.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        c.lostDay = day;
        c.lostAt = at;
        c.held = held;
        c.finder = null;
        save(village, c);
        String fam = family(village, pick);
        Villages.tell(village, day, c.name + ", " + fam + " " + kindWord(c.cat()) + ", has gone missing");
        for (VillageFolkEntity m : Homes.loadedMembers(village, pick)) {
            m.persona().remember(day, c.name + " went missing", m.isBaby() ? 6 : 4);
        }
        VillageFolkEntity worried = null;
        for (VillageFolkEntity m : Homes.loadedMembers(village, pick)) if (!m.isSleeping() && (worried == null || m.isBaby())) worried = m;
        if (worried != null) {
            say(worried, worried.isBaby() ? FolkTalk.pick(r, c.name + "'s gone! Has anybody seen " + c.name + "?", "I can't find " + c.name + " anywhere!")
                : FolkTalk.pick(r, "Has anyone seen " + c.name + "? Off after a rabbit, I'll bet.", c.name + " hasn't come home. It's not like " + c.name + "."));
        }
        LOG.info("[MCA-PETS] {} went missing at {} ({})", c.name, at.toShortString(), held ? "held for the quest board" : "home by itself tomorrow");
        return lostOf(level, village, pick, p, c);
    }

    /** A missing pet: out where it went, unless somebody has found it, when it follows them home. */
    private static String lostStep(ServerLevel level, UUID village, Homes.Home h, TamableAnimal a, Care c, Families.Pet p, long day, long t) {
        BlockPos hearth = Families.hearth(level, village, h);
        ServerPlayer finder = c.finder == null ? null : level.getServer().getPlayerList().getPlayer(c.finder);
        if (finder != null && finder.level() == level && finder.distanceToSqr(a) < 48.0 * 48.0) {
            heel(a, finder, 1.2D);
            boolean home = a.distanceToSqr(hearth.getX() + 0.5, hearth.getY(), hearth.getZ() + 0.5) < 8.0 * 8.0;
            for (VillageFolkEntity m : Homes.loadedMembers(village, h)) if (m.distanceToSqr(a) < 5.0 * 5.0) home = true;
            if (home) {
                broughtHome(level, village, h, a, c, p, finder, day);
                return "home again, with " + finder.getName().getString();
            }
            return "following " + finder.getName().getString() + " home";
        }
        if (!c.held && day > c.lostDay && t >= 1000L && t < 4000L) {
            // A night out, and in at the door at breakfast, muddy to the ears.
            Lost was = lostOf(level, village, h, p, c);
            c.lostDay = -1;
            c.lostAt = null;
            c.finder = null;
            save(village, c);
            a.teleportTo(hearth.getX() + 0.5, hearth.getY(), hearth.getZ() + 0.5);
            Villages.tell(village, day, c.name + " came home by itself at breakfast, muddy to the ears");
            for (VillageFolkEntity m : Homes.loadedMembers(village, h)) {
                if (m.distanceToSqr(a) < 16.0 * 16.0) { say(m, FolkTalk.pick(a.getRandom(), c.name + "! Where have you BEEN?", "There you are, you rascal!")); break; }
            }
            LOG.info("[MCA-PETS] {} came home by itself after {} day(s)", was.name(), day - was.day());
            return null;
        }
        BlockPos at = c.lostAt == null ? a.blockPosition() : c.lostAt;
        if (a.distanceToSqr(at.getX() + 0.5, at.getY(), at.getZ() + 0.5) > 4.0 * 4.0) {
            wake(a);
            a.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        }
        rest(a, false);
        if (a.tickCount % 200 < 10) {
            level.playSound(null, a.blockPosition(), a instanceof Cat ? SoundEvents.CAT_STRAY_AMBIENT : SoundEvents.WOLF_WHINE, SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
        return "lost, out past the town's edge";
    }

    private static void broughtHome(ServerLevel level, UUID village, Homes.Home h, TamableAnimal a, Care c, Families.Pet p, ServerPlayer finder, long day) {
        Lost was = lostOf(level, village, h, p, c);
        c.lostDay = -1;
        c.lostAt = null;
        c.finder = null;
        befriend(c, finder, 2);
        save(village, c);
        String who = finder.getName().getString();
        level.broadcastEntityEvent(a, (byte) 7);
        VillageFolkEntity thanks = null;
        for (VillageFolkEntity m : Homes.loadedMembers(village, h)) {
            m.persona().feelFor(finder.getUUID(), who, 8);
            m.persona().remember(day, who + " brought " + c.name + " home when it was lost", m.isBaby() ? 7 : 5);
            if (thanks == null || m.distanceToSqr(a) < thanks.distanceToSqr(a)) thanks = m;
        }
        if (thanks != null) {
            say(thanks, thanks.isBaby() ? FolkTalk.pick(a.getRandom(), c.name + "! You found " + c.name + "! Thank you thank you thank you!",
                    "You brought " + c.name + " home!")
                : FolkTalk.pick(a.getRandom(), c.name + "! Oh, you found " + c.name + "! Thank you, " + who + ".",
                    "Where was the rascal? Thank you, " + who + " — we were worried sick."));
        }
        Villages.tell(village, day, who + " found " + c.name + ", " + family(village, h) + " " + kindWord(c.cat()) + ", and brought it home");
        LOG.info("[MCA-PETS] {} brought {} home", who, c.name);
        for (BiConsumer<Lost, ServerPlayer> l : FOUND) {
            try {
                l.accept(was, finder);
            } catch (RuntimeException e) {
                LOG.warn("[MCA-PETS] a listener to {} brought home failed: {}", c.name, e.toString());
            }
        }
    }

    private static void befriend(Care c, Player p, int treats) {
        c.friends.merge(p.getUUID(), treats, Integer::sum);
        c.friendNames.put(p.getUUID(), p.getName().getString());
    }

    // ------------------------------------------------------------------ the town's round (Families.tick)

    /**
     * Every ten seconds or so (Families.tick): each household's pet seen to (its bowl, the healer, its things, its age),
     * the town's young placed, strays kept about the town and taken in, a litter now and then, the merchant's pup.
     */
    public static void tick(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        demandOnce();
        for (Homes.Home h : List.copyOf(Homes.homes(id).values())) {
            if (h.members.isEmpty() || !level.isLoaded(h.anchor)) continue;
            Families.Pet p = Families.pet(id, h.anchor.asLong());
            if (p == null) {
                graveDue(level, v, h, day, t);
                continue;
            }
            if (!(level.getEntity(p.id()) instanceof TamableAnimal a) || !a.isAlive()) continue;
            Care c = know(id, p, a, day);
            household(level, v, h, p, a, c, day, t);
        }
        creepersSeenOff(level, v, day);
        othersRound(level, v, day, t);
        if (calm) return;
        if (t >= 2000L && t < 10000L && TOWN_LOOKED.getOrDefault(id, -1L) != day) {
            TOWN_LOOKED.put(id, day);
            homesForTheYoung(level, v, day);
            litters(level, v, day);
            strays(level, v, day);
        }
        if (t >= 1000L && t < 11000L) takeIns(level, v, day);
    }

    /** One household's pet seen to. */
    private static void household(ServerLevel level, Villages.Village v, Homes.Home h, Families.Pet p, TamableAnimal a, Care c, long day, long t) {
        UUID id = v.id();
        RandomSource r = level.getRandom();
        boolean cat = c.cat();
        // Old age: in its sleep, at night.
        if (years(c, day) >= lifespan(c) && (t >= 14000L || t < 1000L) && c.lostDay < 0) {
            OLD_AGE.add(a.getUUID());
            a.kill();
            return;
        }
        // Taken ill now and then (more often old, or hungry); seen to by the town's healer.
        if (c.rolledDay != day && t >= 1000L && !calm) {
            c.rolledDay = day;
            int odds = old(c, day) ? 12 : day - c.fedDay >= 2 ? 10 : 40;
            if (!c.sick && r.nextInt(odds) == 0) {
                c.sick = true;
                c.sickDay = day;
                save(id, c);
                VillageFolkEntity m = anyone(id, h);
                if (m != null) say(m, FolkTalk.pick(r, c.name + "'s off " + (cat ? "her" : "his") + " food. I'll ask the healer to look in.",
                    "Poor " + c.name + " isn't right today."));
                LOG.info("[MCA-PETS] {} was taken ill", c.name);
            }
        }
        if ((c.sick || a.getHealth() < a.getMaxHealth() * 0.6F) && level.getGameTime() - c.tended >= 1200L) {
            String what = "seeing to " + c.name + ", " + family(id, h) + " " + kindWord(cat);
            if (TownJobs.atWork(level, v, "care", a.blockPosition(), what, StationTask.BREW)) tend(level, v, h, a, c, day, what);
        }
        // A dog off after a rabbit now and then; home by itself the next morning unless somebody fetches it first.
        if (!cat && !calm && c.lostDay < 0 && !a.isBaby() && t >= 3000L && t < 9000L && r.nextInt(1800) == 0) lose(level, id, p.id(), false);
        if (c.lostDay >= 0) return;
        // A cat calls on the fishers.
        if (cat && c.fedDay < day && t >= 2000L && t < 11000L && !CAT_FISH.containsKey(a.getUUID()) && r.nextInt(4) == 0) {
            for (AssistantEntity x : Villages.folkOf(id)) {
                if (x instanceof VillageFolkEntity fisher && fisher.stationTask() == StationTask.FISH && !fisher.isSleeping()
                        && fisher.countCarried(CAT_FOOD) > 0 && fisher.distanceToSqr(a) < 48.0 * 48.0) {
                    CAT_FISH.put(a.getUUID(), fisher.getUUID());
                    break;
                }
            }
        }
        if (anyErrand(h)) return;
        // Feeding: the bowl topped up morning and evening, or (no bowl yet) a bite by hand.
        BlockPos bowl = bowlOf(level, id, h);
        // By day whoever of the house is free (a child, as often as not, leaving its game for it), and after supper anyone.
        boolean feedingTime = t >= 1000L && t < 11000L || t >= 12600L && t < 13400L;
        if (feedingTime && bowl != null && PetBowlBlock.servings(level.getBlockState(bowl)) < 2) {
            VillageFolkEntity f = hand(id, h, true);
            if (f == null && t >= 12600L) f = anyone(id, h);
            if (f != null && give(f, new Errand(Job.FILL, id, h.anchor.asLong(), a.getUUID(), level.getGameTime()))) return;
        }
        if (bowl == null && c.fedDay < day && feedingTime && t >= 2000L) {
            VillageFolkEntity f = hand(id, h, true);
            if (f != null && give(f, new Errand(Job.FEED, id, h.anchor.asLong(), a.getUUID(), level.getGameTime()))) return;
        }
        // Three days unfed, and nothing in the chest, the stores or the shop to give it: to a better-off neighbour.
        if (day - c.fedDay >= STARVED && day - p.since() >= STARVED && day - c.noFoodDay <= 1) {
            if (giveAway(level, v, h, p, a, c, day)) return;
        }
        // Its things: once a day each household looks at what its pet wants.
        String k = id + "/" + h.anchor.asLong();
        if (t >= 2000L && t < 11000L && WANT_LOOKED.getOrDefault(k, -1L) != day) {
            WANT_LOOKED.put(k, day);
            needs(level, v, h, p, a, c, day);
        }
    }

    /** What the pet still wants (a bowl, a bed, a collar, a treat for the children to give it): the first it can get. */
    private static void needs(ServerLevel level, Villages.Village v, Homes.Home h, Families.Pet p, TamableAnimal a, Care c, long day) {
        UUID id = v.id();
        long now = level.getGameTime(), anchor = h.anchor.asLong();
        boolean cat = c.cat();
        Item bowl = McAssistantMod.PET_BOWL_ITEM.get(), bed = cat ? McAssistantMod.CAT_BED_ITEM.get() : McAssistantMod.DOG_BED_ITEM.get();
        Item collar = McAssistantMod.COLLAR.get(), treat = McAssistantMod.PET_TREAT.get();
        boolean shopping = Purchases.open(id);
        if (bowlOf(level, id, h) == null) {
            if (has(level, v, h, bowl)) {
                VillageFolkEntity f = hand(id, h, false);
                if (f != null) give(f, place(Job.PLACE, id, anchor, a, now, bowl));
                return;
            }
            // Nobody in the town to make one (no shop's workshop): a grown-up of the house knocks one together.
            if (Workshop.keeper(id) == null && !shopping) {
                Bench.Plan plan = Bench.plan(level, v, bowl, 1, Bench.handOf(level, v, null, null));
                VillageFolkEntity f = plan.ok() ? hand(id, h, false) : null;
                if (f != null) {
                    give(f, new Errand(Job.MAKE, id, anchor, a.getUUID(), now));
                    return;
                }
            }
        }
        if (bedOf(level, id, h, cat) == null && has(level, v, h, bed)) {
            VillageFolkEntity f = hand(id, h, false);
            if (f != null) {
                give(f, place(Job.PLACE, id, anchor, a, now, bed));
                return;
            }
        }
        if (c.collar.isEmpty() && has(level, v, h, collar)) {
            VillageFolkEntity f = hand(id, h, false);
            if (f != null) {
                give(f, place(Job.COLLAR, id, anchor, a, now, collar));
                return;
            }
        }
        if (!children(id, h).isEmpty() && day - c.treatDay >= 3 && has(level, v, h, treat)) {
            VillageFolkEntity f = hand(id, h, true);
            if (f != null) give(f, place(Job.TREAT, id, anchor, a, now, treat));
        }
    }

    private static Errand place(Job job, UUID village, long anchor, TamableAnimal a, long now, Item thing) {
        Errand e = new Errand(job, village, anchor, a.getUUID(), now);
        e.thing = thing;
        return e;
    }

    /** Is one of these to be had: in the household's chest, the stores, or the shop? */
    private static boolean has(ServerLevel level, Villages.Village v, Homes.Home h, Item it) {
        BlockPos chest = Homes.chestOf(level, v.id(), h);
        if (chest != null && level.getBlockEntity(chest) instanceof Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(it)) return true;
        }
        return ShopStock.count(level, v.id(), s -> s.is(it)) > 0;
    }

    /**
     * Nothing to feed it with for three days: the household gives it to a neighbour better off (more coin among its
     * folk) with no pet of its own. False if there is none, and it stays, hungry.
     */
    private static boolean giveAway(ServerLevel level, Villages.Village v, Homes.Home h, Families.Pet p, TamableAnimal a, Care c, long day) {
        UUID id = v.id();
        int ours = purse(id, h);
        Homes.Home best = null;
        int bestPurse = ours;
        for (Homes.Home o : List.copyOf(Homes.homes(id).values())) {
            if (o == h || o.members.isEmpty() || Families.pet(id, o.anchor.asLong()) != null || grown(id, o).isEmpty()) continue;
            int pu = purse(id, o);
            if (pu > bestPurse) { bestPurse = pu; best = o; }
        }
        if (best == null) return false;
        String from = family(id, h), to = family(id, best);
        Families.pet(id, h.anchor.asLong(), null);
        VillageFolkEntity keeper = grown(id, best).get(0);
        a.setOwnerUUID(keeper.getUUID());
        Families.pet(id, best.anchor.asLong(), new Families.Pet(a.getUUID(), p.kind(), p.name(), day, keeper.getUUID()));
        c.fedDay = day;                                              // the new household feeds it straight away
        save(id, c);
        Ledger.note(id, "petgave/" + h.anchor.asLong(), Long.toString(day));
        Villages.tell(id, day, from.replace("'s", "") + " could not feed " + c.name + " and gave " + (c.cat() ? "her" : "him") + " to " + to.replace("'s", ""));
        for (VillageFolkEntity m : Homes.loadedMembers(id, h)) m.persona().remember(day, "we had to give " + c.name + " away", m.isBaby() ? 7 : 5);
        for (VillageFolkEntity m : Homes.loadedMembers(id, best)) m.persona().remember(day, "we took in " + c.name + " from the neighbours", 3);
        LOG.info("[MCA-PETS] {} gave {} to {} (purses {} and {})", from, c.name, to, ours, bestPurse);
        return true;
    }

    private static int purse(UUID village, Homes.Home h) {
        int n = 0;
        for (VillageFolkEntity m : Homes.loadedMembers(village, h)) n += m.purse();
        return n;
    }

    /** The healer at its side: a bit of its own food, else a drop of honey, out of the stores; else a kind word. */
    private static void tend(ServerLevel level, Villages.Village v, Homes.Home h, TamableAnimal a, Care c, long day, String what) {
        UUID id = v.id();
        c.tended = level.getGameTime();
        ItemStack got = Crafts.takeOne(level, v, foodFor(c.cat()));
        String with;
        if (!got.isEmpty()) {
            with = c.cat() ? "a bit of fish" : "a bit of meat";
            a.heal(8.0F);
        } else {
            got = Crafts.takeOne(level, v, s -> s.is(Items.HONEY_BOTTLE));
            if (!got.isEmpty()) {
                Crafts.store(level, v, new ItemStack(Items.GLASS_BOTTLE));
                with = "a drop of honey";
                a.heal(6.0F);
            } else {
                with = "a kind word and a scratch behind the ears";
                a.heal(2.0F);
            }
        }
        boolean wasSick = c.sick;
        c.sick = false;
        save(id, c);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, a.getX(), a.getY() + 0.6, a.getZ(), 6, 0.3, 0.3, 0.3, 0.0);
        VillageFolkEntity carer = null;
        double bd = 12.0 * 12.0;
        for (AssistantEntity x : Villages.folkOf(id)) {
            if (!(x instanceof VillageFolkEntity f) || f.isBaby() || !f.isAlive()) continue;
            if (what.equals(TownJobs.doing(f))) { carer = f; break; }
            double d = f.distanceToSqr(a);
            if (d < bd) { bd = d; carer = f; }
        }
        String who = carer == null ? "the town's healer" : carer.displayNameCap();
        if (carer != null) {
            carer.getLookControl().setLookAt(a, 30.0F, 30.0F);
            carer.swing(InteractionHand.MAIN_HAND);
            say(carer, FolkTalk.pick(a.getRandom(), "There, there, " + c.name + ". You'll mend.", "Let's have a look at you, " + c.name + "… nothing a bit of rest won't fix.",
                "Good " + (c.cat() ? "girl" : "boy") + ", " + c.name + ". Here — this'll help."));
            for (VillageFolkEntity m : Homes.loadedMembers(id, h)) m.life().feel(carer.getUUID(), who, 2);
        }
        Ledger.note(id, "pets/tended", Integer.toString(parse(Ledger.note(id, "pets/tended")) + 1));
        LOG.info("[MCA-PETS] {} saw to {} ({}) with {}; health {}/{}", who, c.name, wasSick ? "sick" : "hurt", with, a.getHealth(), a.getMaxHealth());
    }

    private static int parse(@Nullable String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The morning after the dog barked half the night: the household says so, once. */
    @Nullable
    private static String morning(ServerLevel level, VillageFolkEntity f, UUID village, long t, long day, boolean now) {
        if (t < 1000L || t >= 5000L || f.isSleeping()) return null;
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        if (h == null) return null;
        Families.Pet p = Families.pet(village, h.anchor.asLong());
        if (p == null) return null;
        Care c = care(village, p.id());
        if (c.barkDay != day || c.barkedAt.isEmpty()) return null;
        String k = village + "/" + h.anchor.asLong();
        if (MORNING.getOrDefault(k, -1L) == day) return null;
        if (!now && level.getNearestPlayer(f, 24.0) == null && f.getRandom().nextInt(3) != 0) return null;   // said out of earshot as often
        MORNING.put(k, day);
        RandomSource r = f.getRandom();
        String line = f.isBaby() ? FolkTalk.pick(r, c.name + " barked at " + a(c.barkedAt) + " last night! " + (c.cat() ? "She" : "He") + " was SO brave.",
                "Did you hear " + c.name + " last night? There was " + a(c.barkedAt) + "!")
            : FolkTalk.pick(r, c.name + " kept us up barking at " + a(c.barkedAt) + " all night.",
                "Not a wink of sleep — " + c.name + " was barking at " + a(c.barkedAt) + " half the night. Good dog, mind.",
                "The watch came out for " + a(c.barkedAt) + " last night, and " + c.name + " told them where.");
        say(f, line);
        f.persona().remember(day, c.name + " kept us up barking at " + a(c.barkedAt) + " all night", 3);
        return line;
    }

    /** Creepers that came near a cat and went away again without going off: seen off, and remembered. */
    private static void creepersSeenOff(ServerLevel level, Villages.Village v, long day) {
        for (Map.Entry<UUID, UUID> e : List.copyOf(CREEPERS.entrySet())) {
            Entity cr = level.getEntity(e.getKey());
            Entity cat = level.getEntity(e.getValue());
            if (cr == null || !cr.isAlive() || !(cat instanceof Cat cc)) {
                CREEPERS.remove(e.getKey());
                continue;
            }
            if (cr.distanceToSqr(cat) < 12.0 * 12.0) continue;
            CREEPERS.remove(e.getKey());
            Homes.Home h = homeOfPet(v.id(), cc.getUUID());
            if (h == null) continue;
            Care c = care(v.id(), cc.getUUID());
            c.creepers++;
            save(v.id(), c);
            VillageFolkEntity m = anyone(v.id(), h);
            if (m != null && level.getNearestPlayer(m, 24.0) != null) {
                say(m, FolkTalk.pick(level.getRandom(), c.name + " saw a creeper off the step just now. Worth her weight in fish, that cat.",
                    "Did you see that? " + c.name + " just stared down a creeper!"));
            }
            for (VillageFolkEntity x : Homes.loadedMembers(v.id(), h)) x.persona().remember(day, c.name + " saw a creeper off the step", 2);
            LOG.info("[MCA-PETS] {} saw a creeper off ({} in all)", c.name, c.creepers);
        }
    }

    // ------------------------------------------------------------------ death, and remembering

    /** A pet died (old age, a monster, an accident): Families' record let go, the chronicle, and a grave in the garden. */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof TamableAnimal a) || !(a.level() instanceof ServerLevel level)) return;
        if (!(a instanceof Wolf) && !(a instanceof Cat)) return;
        boolean old = OLD_AGE.remove(a.getUUID());
        for (Villages.Village v : Villages.every()) {
            if (others(v.id()).containsKey(a.getUUID())) dropOther(v.id(), a.getUUID());
            Homes.Home h = homeOfPet(v.id(), a.getUUID());
            if (h == null) continue;
            Families.Pet p = Families.pet(v.id(), h.anchor.asLong());
            if (p == null) continue;
            mourn(level, v, h, p, care(v.id(), p.id()), old ? "of old age, asleep at home" : how(event.getSource()), level.getDayTime() / 24000L);
            return;
        }
    }

    private static String how(DamageSource src) {
        Entity by = src.getEntity();
        if (by != null) return "fighting " + a(nameOf(by));
        String id = src.getMsgId();
        if (id.contains("fall")) return "in a fall";
        if (id.contains("drown")) return "in the water";
        if (id.contains("fire") || id.contains("lava")) return "in a fire";
        if (id.contains("explosion")) return "in an explosion";
        return "in an accident";
    }

    static void mourn(ServerLevel level, Villages.Village v, Homes.Home h, Families.Pet p, Care c, String how, long day) {
        UUID id = v.id();
        Families.pet(id, h.anchor.asLong(), null);
        int years = years(c, day);
        String kind = kindWord(c.cat()), fam = family(id, h);
        Villages.tell(id, day, c.name + ", " + fam + " " + kind + ", died " + how + (years > 0 ? ", aged " + years : ""));
        RandomSource r = level.getRandom();
        for (VillageFolkEntity m : Homes.loadedMembers(id, h)) {
            m.persona().remember(day, "we lost " + c.name + ", our " + kind, m.isBaby() ? 8 : 6);
        }
        VillageFolkEntity near = anyone(id, h);
        if (near != null) say(near, FolkTalk.pick(r, "Oh, " + c.name + "…", "Not " + c.name + ". Not our " + c.name + "."));
        // In memoriam, for the books.
        String mem = Ledger.note(id, "pets/memoriam");
        List<String> gone = new ArrayList<>();
        gone.add(c.name.replaceAll("[~,]", "") + "~" + kind + "~" + years + "~" + day);
        if (mem != null && !mem.isEmpty()) for (String m : mem.split(",")) if (gone.size() < 8) gone.add(m);
        Ledger.note(id, "pets/memoriam", String.join(",", gone));
        Ledger.forget(id, "petcare/" + c.pet);
        CARE.remove(c.pet);
        RESTING.remove(c.pet);
        FOLLOWING.remove(c.pet);
        // A grave in the garden: dug when one of the household is free.
        Errand e = new Errand(Job.BURY, id, h.anchor.asLong(), null, level.getGameTime());
        e.dead = c.name;
        e.deadKind = kind;
        e.deadYears = years;
        e.died = day;
        e.to = graveSpot(level, id, h);
        Ledger.note(id, "petgrave/" + h.anchor.asLong(), c.name + "|" + kind + "|" + years + "|" + day + "|" + (e.to == null ? "" : e.to.asLong()) + "|due");
        LOG.info("[MCA-PETS] {} died {}, aged {}", c.name, how, years);
    }

    /** A grave owed a pet of this household: dug when somebody of it is free. */
    private static void graveDue(ServerLevel level, Villages.Village v, Homes.Home h, long day, long t) {
        String s = Ledger.note(v.id(), "petgrave/" + h.anchor.asLong());
        if (s == null || !s.endsWith("|due") || t < 1000L || t >= 12000L || anyErrand(h)) return;
        String[] q = s.split("\\|", -1);
        if (q.length < 6) return;
        VillageFolkEntity f = hand(v.id(), h, false);
        if (f == null) return;
        Errand e = new Errand(Job.BURY, v.id(), h.anchor.asLong(), null, level.getGameTime());
        e.dead = q[0];
        e.deadKind = q[1];
        e.deadYears = parse(q[2]);
        try {
            e.died = Long.parseLong(q[3]);
            e.to = q[4].isEmpty() ? graveSpot(level, v.id(), h) : BlockPos.of(Long.parseLong(q[4]));
        } catch (NumberFormatException ex) {
            e.to = graveSpot(level, v.id(), h);
        }
        if (e.to == null) {
            Ledger.note(v.id(), "petgrave/" + h.anchor.asLong(), s.substring(0, s.length() - 4) + "|none");
            return;
        }
        give(f, e);
    }

    /** A spot in the garden: grass or earth near the house, out of doors, not a path, nobody's house. */
    @Nullable
    static BlockPos graveSpot(ServerLevel level, UUID village, Homes.Home h) {
        BlockPos a = h.anchor;
        for (int r = 5; r <= 10; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = a.getX() + dx, z = a.getZ() + dz;
                    if (!level.isLoaded(new BlockPos(x, a.getY(), z))) continue;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos at = new BlockPos(x, y, z);
                    if (Math.abs(y - a.getY()) > 4) continue;
                    BlockState under = level.getBlockState(at.below());
                    if (!under.is(Blocks.GRASS_BLOCK) && !under.is(Blocks.DIRT) && !under.is(Blocks.PODZOL) && !under.is(Blocks.COARSE_DIRT)) continue;
                    if (!level.getBlockState(at).isAir() && !level.getBlockState(at).canBeReplaced()) continue;
                    if (!level.getBlockState(at.above()).isAir()) continue;
                    if (Homes.homeAt(village, at) != null || Homes.homeAt(village, at.north(2)) != null && Homes.homeAt(village, at.south(2)) != null) continue;
                    boolean side = false;
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockState n = level.getBlockState(at.relative(d).below());
                        if (n.is(Blocks.DIRT_PATH) || level.getBlockState(at.relative(d)).getBlock() instanceof net.minecraft.world.level.block.DoorBlock) side = true;
                    }
                    if (!side) return at.immutable();
                }
            }
        }
        return null;
    }

    /** The grave: a little mound, a sign with its name on it (the stores' sign, or two planks), a flower if there is one. */
    private static void grave(ServerLevel level, Villages.Village v, Errand e, long day) {
        BlockPos at = e.to;
        if (at == null) return;
        String s = Ledger.note(e.village, "petgrave/" + e.anchor);
        if (!level.getBlockState(at).isAir() && !level.getBlockState(at).canBeReplaced()) return;
        if (!Crafts.sign(level, v)) {
            // No sign to be had: a mound and the flower, and the sign when the stores have one.
            LOG.info("[MCA-PETS] no sign in the stores for {}'s grave", e.dead);
        } else {
            BlockPos under = at.below();
            if (level.getBlockState(under).is(Blocks.GRASS_BLOCK) || level.getBlockState(under).is(Blocks.DIRT)) {
                level.setBlock(under, Blocks.COARSE_DIRT.defaultBlockState(), 3);
            }
            BlockPos home = BlockPos.of(e.anchor);
            int rot = Math.floorMod(Math.round((float) (Math.atan2(home.getX() - at.getX(), at.getZ() - home.getZ()) * 8.0 / Math.PI)), 16);
            level.setBlock(at, Blocks.SPRUCE_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rot), 3);
            if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
                TownLife.write(sign, new String[]{ e.dead, e.deadKind.equals("cat") ? "a dear cat" : "a good dog",
                    e.deadYears > 0 ? e.deadYears + " years" : "", "day " + (e.died + 1) });
            }
        }
        // The flower beside it.
        ItemStack flower = Crafts.takeOne(level, v, Families.FLOWER);
        if (!flower.isEmpty() && flower.getItem() instanceof net.minecraft.world.item.BlockItem bi) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos fp = at.relative(d);
                if (level.getBlockState(fp).isAir() && bi.getBlock().defaultBlockState().canSurvive(level, fp)) {
                    level.setBlock(fp, bi.getBlock().defaultBlockState(), 3);
                    flower = ItemStack.EMPTY;
                    break;
                }
            }
            if (!flower.isEmpty()) Crafts.store(level, v, flower);
        }
        if (s != null) {
            String[] q = s.split("\\|", -1);
            if (q.length >= 4) Ledger.note(e.village, "petgrave/" + e.anchor, q[0] + "|" + q[1] + "|" + q[2] + "|" + q[3] + "|" + at.asLong() + "|dug");
        }
        Villages.tell(e.village, day, e.dead + " was buried in the garden by the house, under a little sign");
        LOG.info("[MCA-PETS] {}'s grave dug at {}", e.dead, at.toShortString());
    }

    // ------------------------------------------------------------------ the town's other animals

    /** The young, the strays, the merchant's: kept where they belong, and gone when they go. */
    private static void othersRound(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        int reach = Villages.townReach(id);
        for (Other o : List.copyOf(others(id).values())) {
            Entity e = level.getEntity(o.animal());
            if (e instanceof TamableAnimal a && a.isAlive() && a.isTame() && a.getOwnerUUID() != null
                    && !Other.MERCHANT.equals(o.role()) && !Other.YOUNG.equals(o.role())) {
                dropOther(id, o.animal());                            // a stray somebody took in (a player, Families)
                continue;
            }
            switch (o.role()) {
                case Other.MERCHANT -> {
                    VillageFolkEntity m = o.merchant() != null && level.getEntity(o.merchant()) instanceof VillageFolkEntity x && x.isAlive() ? x : null;
                    if (!(e instanceof TamableAnimal a) || !a.isAlive()) {
                        if (e == null && (m == null || t >= 12000L || day > o.since())) dropOther(id, o.animal());
                        continue;
                    }
                    if (m == null || t >= 12000L || day > o.since()) {
                        // Off with the merchant at dusk, unsold.
                        dropOther(id, o.animal());
                        a.discard();
                        continue;
                    }
                    harness(a);
                    if (a.distanceToSqr(m) > 3.0 * 3.0) a.teleportTo(m.getX() + 0.8, m.getY(), m.getZ() + 0.8);
                    rest(a, false);
                }
                case Other.YOUNG -> {
                    if (!(e instanceof TamableAnimal a) || !a.isAlive()) {
                        if (e == null && day - o.since() > YOUNG_WAIT + 5) dropOther(id, o.animal());   // not seen for a week: gone
                        continue;
                    }
                    if (day - o.since() >= YOUNG_WAIT) {
                        // Nobody would have it: off about the town on its own, a stray.
                        a.setTame(false, true);
                        a.setOwnerUUID(null);
                        wake(a);
                        putOther(id, new Other(o.animal(), Other.STRAY, o.cat(), day, 0L, null, o.mother()));
                        Villages.tell(id, day, "one of " + o.mother() + "'s " + (o.cat() ? "kittens" : "pups") + " found no home and went off about the town as a stray");
                    }
                }
                case Other.STRAY -> {
                    if (!(e instanceof TamableAnimal a) || !a.isAlive()) {
                        if (e == null && day - o.since() > 6) dropOther(id, o.animal());      // gone off for good
                        continue;
                    }
                    if (!a.isPersistenceRequired()) a.setPersistenceRequired();
                    BlockPos c = v.centre();
                    if (Math.max(Math.abs(a.getX() - c.getX()), Math.abs(a.getZ() - c.getZ())) > reach + 24) {
                        a.getNavigation().moveTo(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, 1.0D);
                    }
                }
                default -> { }
            }
        }
    }

    /**
     * Homes for the young: a household that wants one has one of them (a child names it); after a couple of days, any
     * household with no pet and room takes one ("we can't say no to that face"). Returns how many went.
     */
    static int homesForTheYoung(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        int placed = 0;
        for (Other o : List.copyOf(others(id).values())) {
            if (!Other.YOUNG.equals(o.role())) continue;
            if (!(level.getEntity(o.animal()) instanceof TamableAnimal y) || !y.isAlive()) continue;
            if (day - o.since() < 1 && !TownJobs.instantNow()) continue;            // a day with its mother first
            // Friends of the mother's family first, then any household that wants one.
            Homes.Home to = null, mum = Homes.homes(id).get(o.home());
            for (Homes.Home h : wanting(level, id)) {
                if (h.anchor.asLong() == o.home()) continue;
                if (to == null || mum != null && friends(id, h, mum) && !friends(id, to, mum)) to = h;
            }
            if (to == null && day - o.since() >= YOUNG_WANTED) {
                for (Homes.Home h : List.copyOf(Homes.homes(id).values())) {
                    if (h.anchor.asLong() == o.home() || h.members.isEmpty() || Families.pet(id, h.anchor.asLong()) != null || grown(id, h).isEmpty()) continue;
                    if (!level.isLoaded(h.anchor)) continue;
                    to = h;
                    break;
                }
            }
            if (to == null) continue;
            adopt(level, id, to, y, day, "one of " + o.mother() + "'s " + (o.cat() ? "kittens" : "pups"));
            y.teleportTo(Families.hearth(level, id, to).getX() + 0.5, Families.hearth(level, id, to).getY(), Families.hearth(level, id, to).getZ() + 0.5);
            placed++;
        }
        return placed;
    }

    /** Is any of this household a friend of any of that one? */
    static boolean friends(UUID village, Homes.Home a, Homes.Home b) {
        for (VillageFolkEntity m : Homes.loadedMembers(village, a)) {
            for (UUID o : b.members) {
                Social.Bond bond = m.life().bonds.get(o);
                if (bond != null && bond.affinity >= Social.FRIEND) return true;
            }
        }
        return false;
    }

    /**
     * A litter now and then: a household's pet in its prime, fed, well, with another of its kind about the town (a pet
     * of another house, or a stray), not lately had one, and the town short of its cap. A chance in three a day.
     */
    private static void litters(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (day - parseLong(Ledger.note(id, "pets/litterday"), -100L) < TOWN_LITTER_EVERY) return;
        if (cap(id) - count(id) < 1 || level.getRandom().nextInt(3) != 0) return;
        for (Homes.Home h : List.copyOf(Homes.homes(id).values())) {
            Families.Pet p = Families.pet(id, h.anchor.asLong());
            if (p == null || !(level.getEntity(p.id()) instanceof TamableAnimal a) || !a.isAlive() || a.isBaby()) continue;
            Care c = care(id, p.id());
            if (c.sick || c.lostDay >= 0 || day - c.fedDay > 1 || years(c, day) < 2 || old(c, day) || day - c.litterDay < LITTER_EVERY) continue;
            TamableAnimal mate = mateFor(level, id, a);
            if (mate == null) continue;
            litter(level, v, h, a, mate, c, day);
            return;
        }
    }

    /** Another of its kind about the town, grown: a pet of another household, or a stray. */
    @Nullable
    private static TamableAnimal mateFor(ServerLevel level, UUID village, TamableAnimal a) {
        for (Homes.Home h : Homes.homes(village).values()) {
            Families.Pet p = Families.pet(village, h.anchor.asLong());
            if (p == null || p.id().equals(a.getUUID())) continue;
            if (level.getEntity(p.id()) instanceof TamableAnimal m && m.isAlive() && !m.isBaby() && m.getClass() == a.getClass()) return m;
        }
        for (Other o : others(village).values()) {
            if (!Other.STRAY.equals(o.role())) continue;
            if (level.getEntity(o.animal()) instanceof TamableAnimal m && m.isAlive() && !m.isBaby() && m.getClass() == a.getClass()) return m;
        }
        return null;
    }

    /** The litter: one to three, never past the town's cap; the parents fed first. The young, for the households. */
    static List<TamableAnimal> litter(ServerLevel level, Villages.Village v, Homes.Home h, TamableAnimal mother, TamableAnimal mate, Care c, long day) {
        UUID id = v.id();
        List<TamableAnimal> out = new ArrayList<>();
        int room = cap(id) - count(id);
        if (room < 1) return out;
        // The parents eat first, as the game feeds a pair before they breed: out of the mother's bowl, else the stores.
        BlockPos bowl = bowlOf(level, id, h);
        int fedParents = 0;
        for (int i = 0; i < 2; i++) {
            if (bowl != null && PetBowlBlock.eat(level, bowl)) fedParents++;
            else if (!Crafts.takeOne(level, v, foodFor(c.cat())).isEmpty()) fedParents++;
        }
        if (fedParents < 2) {
            LOG.info("[MCA-PETS] {} had no litter: nothing to feed the pair", c.name);
            return out;
        }
        RandomSource r = level.getRandom();
        int n = Math.min(room, 1 + r.nextInt(3));
        for (int i = 0; i < n; i++) {
            AgeableMob baby = mother.getBreedOffspring(level, mate);
            if (!(baby instanceof TamableAnimal y)) continue;
            y.setBaby(true);
            y.moveTo(mother.getX() + (r.nextDouble() - 0.5), mother.getY(), mother.getZ() + (r.nextDouble() - 0.5), r.nextFloat() * 360.0F, 0.0F);
            y.setTame(true, true);
            y.setOwnerUUID(mother.getOwnerUUID());
            y.setPersistenceRequired();
            level.addFreshEntity(y);
            harness(y);
            putOther(id, new Other(y.getUUID(), Other.YOUNG, c.cat(), day, h.anchor.asLong(), null, c.name));
            out.add(y);
        }
        if (out.isEmpty()) return out;
        c.litterDay = day;
        c.litters++;
        save(id, c);
        Ledger.note(id, "pets/litterday", Long.toString(day));
        Ledger.note(id, "pets/litters", Integer.toString(parse(Ledger.note(id, "pets/litters")) + 1));
        String young = out.size() == 1 ? "a " + youngWord(c.cat()) : TownCalendar.inWords(out.size()) + " " + (c.cat() ? "kittens" : "pups");
        Ledger.note(id, "pets/lastlitter", c.name + "'s " + young + ", day " + (day + 1));
        level.broadcastEntityEvent(mother, (byte) 7);
        Villages.tell(id, day, c.name + ", " + family(id, h) + " " + kindWord(c.cat()) + ", had a litter of " + young);
        for (VillageFolkEntity m : Homes.loadedMembers(id, h)) {
            m.persona().remember(day, c.name + " had " + young, m.isBaby() ? 7 : 4);
            if (m.isBaby()) sayLater(m, FolkTalk.pick(r, (c.cat() ? "Kittens" : "Puppies") + "! Can we keep one? Please?", "Look how tiny they are!"), 20 + r.nextInt(40));
        }
        LOG.info("[MCA-PETS] {} had {} with {} (town {} of {})", c.name, young, mate.hasCustomName() ? mate.getName().getString() : "a stray", count(id), cap(id));
        return out;
    }

    private static long parseLong(@Nullable String s, long or) {
        if (s == null || s.isEmpty()) return or;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return or;
        }
    }

    /**
     * A stray turns up about the town while a household wants a pet and the town has room: as the game brings stray
     * cats to a village with beds, and a dog will hang about a town where there are scraps. Every few days at most.
     */
    private static void strays(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (count(id) >= cap(id) || !settled(level, id)) return;
        if (day - parseLong(Ledger.note(id, "pets/strayday"), -100L) < STRAY_EVERY) return;
        for (Other o : others(id).values()) if (!Other.MERCHANT.equals(o.role())) return;   // one about already, or young waiting
        List<Homes.Home> want = wanting(level, id);
        if (want.isEmpty()) return;
        boolean cat = Math.floorMod(Long.hashCode(want.get(0).anchor.asLong()) + (int) day, 2) == 0;
        TamableAnimal a = stray(level, v, cat, null);
        if (a != null) {
            Ledger.note(id, "pets/strayday", Long.toString(day));
            Villages.tell(id, day, "a stray " + kindWord(cat) + " has been seen hanging about the town");
        }
    }

    /** A stray of this kind about the town's edge (or here), wild yet. */
    @Nullable
    static TamableAnimal stray(ServerLevel level, Villages.Village v, boolean cat, @Nullable BlockPos here) {
        UUID id = v.id();
        BlockPos at = here;
        RandomSource r = level.getRandom();
        int reach = Math.max(12, Villages.townReach(id));
        for (int tries = 0; tries < 16 && at == null; tries++) {
            double ang = r.nextDouble() * Math.PI * 2.0;
            int dist = Math.min(40, reach) - r.nextInt(8);
            int x = v.centre().getX() + (int) Math.round(Math.cos(ang) * dist), z = v.centre().getZ() + (int) Math.round(Math.sin(ang) * dist);
            if (!level.isLoaded(new BlockPos(x, 64, z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos g = new BlockPos(x, y, z);
            if (!level.getBlockState(g.below()).getFluidState().isEmpty() || Homes.homeAt(id, g) != null) continue;
            at = g;
        }
        if (at == null) return null;
        TamableAnimal a = cat ? EntityType.CAT.create(level) : EntityType.WOLF.create(level);
        if (a == null) return null;
        a.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, r.nextFloat() * 360.0F, 0.0F);
        a.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        a.setPersistenceRequired();
        level.addFreshEntity(a);
        putOther(id, new Other(a.getUUID(), Other.STRAY, cat, level.getDayTime() / 24000L, 0L, null, ""));
        LOG.info("[MCA-PETS] a stray {} turned up about {} at {}", kindWord(cat), Villages.name(id), at.toShortString());
        return a;
    }

    /** A household that wants a pet sends a grown-up out to a stray about the town, or buys the merchant's. */
    private static void takeIns(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (!settled(level, id)) return;
        for (Other o : List.copyOf(others(id).values())) {
            if (Other.YOUNG.equals(o.role())) continue;
            if (!(level.getEntity(o.animal()) instanceof TamableAnimal a) || !a.isAlive()) continue;
            boolean sought = false;
            for (Errand e : ERRANDS.values()) if (o.animal().equals(e.animal)) sought = true;
            if (sought) continue;
            for (Homes.Home h : wanting(level, id)) {
                if (anyErrand(h)) continue;
                if (Other.MERCHANT.equals(o.role())) {
                    if (buy(level, v, h, a, o, day)) return;
                    continue;
                }
                VillageFolkEntity f = hand(id, h, false);
                if (f == null) continue;
                Errand e = new Errand(Job.TAKE_IN, id, h.anchor.asLong(), a.getUUID(), level.getGameTime());
                if (give(f, e)) {
                    LOG.info("[MCA-PETS] {} of {} sets out to take in the stray {}", f.displayNameCap(), Villages.name(id), kindWord(o.cat()));
                    return;
                }
            }
        }
    }

    /** The merchant's pup bought by a household that wants one: its price out of a grown-up's purse, into the merchant's. */
    private static boolean buy(ServerLevel level, Villages.Village v, Homes.Home h, TamableAnimal a, Other o, long day) {
        UUID id = v.id();
        VillageFolkEntity payer = null;
        for (VillageFolkEntity m : grown(id, h)) if (m.purse() >= MERCHANT_PRICE && (payer == null || m.purse() > payer.purse())) payer = m;
        VillageFolkEntity merchant = o.merchant() != null && level.getEntity(o.merchant()) instanceof VillageFolkEntity m ? m : null;
        if (payer == null || merchant == null || !payer.spend(MERCHANT_PRICE)) return false;
        merchant.earn(MERCHANT_PRICE);
        dropOther(id, a.getUUID());
        adopt(level, id, h, a, day, "bought from the merchant from afar for " + MERCHANT_PRICE + " coins");
        say(merchant, FolkTalk.pick(level.getRandom(), "A fine choice! You'll not regret it.", "Look after the little one, now."));
        say(payer, FolkTalk.pick(level.getRandom(), "Six coins well spent, if you ask me.", "There. Ours now. The children will be over the moon."));
        LOG.info("[MCA-PETS] {} bought the merchant's {} for {}", payer.displayNameCap(), youngWord(o.cat()), MERCHANT_PRICE);
        return true;
    }

    // ------------------------------------------------------------------ the merchant's pup (Merchants.arrived)

    /**
     * On market day the merchant from afar may have a pup or a kitten on its lead, brought from far away as its goods
     * are: when a household wants one and the town has room. A household that wants one and has the coin buys it; a
     * player may; at dusk it goes on with the merchant.
     */
    public static void merchant(ServerLevel level, Villages.Village town, VillageFolkEntity m, long day) {
        UUID id = town.id();
        if (calm || count(id) >= cap(id) || wanting(level, id).isEmpty() || level.getRandom().nextInt(2) != 0) return;
        boolean cat = level.getRandom().nextBoolean();
        TamableAnimal a = cat ? EntityType.CAT.create(level) : EntityType.WOLF.create(level);
        if (a == null) return;
        a.moveTo(m.getX() + 0.8, m.getY(), m.getZ() + 0.8, 0.0F, 0.0F);
        a.finalizeSpawn(level, level.getCurrentDifficultyAt(m.blockPosition()), MobSpawnType.EVENT, null);
        a.setBaby(true);
        a.setTame(true, true);
        a.setOwnerUUID(m.getUUID());
        a.setPersistenceRequired();
        level.addFreshEntity(a);
        harness(a);
        putOther(id, new Other(a.getUUID(), Other.MERCHANT, cat, day, 0L, m.getUUID(), "the merchant"));
        say(m, FolkTalk.pick(level.getRandom(), "And a " + youngWord(cat) + " from over the hills, looking for a good home! Six coins!",
            "Who'll give this little " + youngWord(cat) + " a home? Six coins, and the best friend you'll ever have!"));
        Villages.tell(id, day, "the merchant from afar had a " + youngWord(cat) + " with it, looking for a home");
        LOG.info("[MCA-PETS] the merchant at {} has a {}", Villages.name(id), youngWord(cat));
    }

    // ------------------------------------------------------------------ the makers (Crafts.now, Workshop.demand)

    private static volatile boolean demanded;

    /**
     * The shop's workshop keeps the pets' things on its book once there are pets (and so the shop stocks them). Put on
     * the book at the first town's round, not when this class is first loaded (it is an event subscriber, loaded with
     * the mod before the game's registries are ready).
     */
    static void demandOnce() {
        if (demanded) return;
        demanded = true;
        Workshop.demand("the town's pets", (level, v, want) -> {
            for (Map.Entry<Item, Integer> e : wanted(level, v).entrySet()) want.accept(e.getKey(), e.getValue());
        });
    }

    /**
     * What the town's pets want kept in the stores: a bowl for every household with a pet and no bowl, a bed (a dog's or
     * a cat's) for every one without, a collar for every one without, and a couple of treats for every household with
     * children and a pet. Worked out at most every ten seconds.
     */
    static Map<Item, Integer> wanted(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Object[] cached = WANTS.get(id);
        if (cached != null && now - (Long) cached[0] < 200L && now >= (Long) cached[0]) {
            @SuppressWarnings("unchecked") Map<Item, Integer> m = (Map<Item, Integer>) cached[1];
            return m;
        }
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (Homes.Home h : List.copyOf(Homes.homes(id).values())) {
            Families.Pet p = Families.pet(id, h.anchor.asLong());
            if (p == null || !level.isLoaded(h.anchor)) continue;
            boolean cat = "cat".equals(p.kind());
            if (bowlOf(level, id, h) == null) out.merge(McAssistantMod.PET_BOWL_ITEM.get(), 1, Integer::sum);
            if (bedOf(level, id, h, cat) == null) out.merge(cat ? McAssistantMod.CAT_BED_ITEM.get() : McAssistantMod.DOG_BED_ITEM.get(), 1, Integer::sum);
            if (care(id, p.id()).collar.isEmpty()) out.merge(McAssistantMod.COLLAR.get(), 1, Integer::sum);
            if (!children(id, h).isEmpty()) out.merge(McAssistantMod.PET_TREAT.get(), 2, Integer::sum);
        }
        WANTS.put(id, new Object[]{ now, out });
        return out;
    }

    /** Whose work each thing is: the tailor's beds and collars, the cook's treats; the shop's workshop makes them all. */
    static boolean makes(StationTask t, Item it) {
        if (t == StationTask.SHOP) return true;
        if (t == StationTask.TAILOR) return it == McAssistantMod.DOG_BED_ITEM.get() || it == McAssistantMod.CAT_BED_ITEM.get() || it == McAssistantMod.COLLAR.get();
        if (t == StationTask.COOK) return it == McAssistantMod.PET_TREAT.get();
        return false;
    }

    /**
     * A turn at the pets' things (Crafts.now, one turn in two while any are wanted): the first the stores are short of
     * that is this trade's work and the age allows, made at the bench out of the stores (Bench). What was made, or null.
     */
    @Nullable
    public static String craft(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        if (Math.floorMod(level.getGameTime() / Crafts.EVERY + f.getUUID().hashCode(), 2L) != 0) return null;
        return craftNow(level, v, f);
    }

    @Nullable
    static String craftNow(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        StationTask t = f.stationTask();
        Map<Item, Integer> want = wanted(level, v);
        if (want.isEmpty()) return null;
        Villages.Age age = Villages.ageOf(v.id());
        Bench.Hand hand = null;
        for (Map.Entry<Item, Integer> e : want.entrySet()) {
            Item it = e.getKey();
            if (!makes(t, it) || !Tiers.allows(level, age, it)) continue;
            if (Market.stock(level, v.id(), s -> s.is(it)) + ShopStock.held(level, v.id(), s -> s.is(it)) >= e.getValue()) continue;
            if (hand == null) hand = Bench.handOf(level, v, f, VillageFolkEntity.buildingFor(t));
            Bench.Plan plan = Bench.plan(level, v, it, 1, hand);
            if (!plan.ok()) continue;
            ItemStack out = Bench.make(level, v, plan, f, hand);
            if (out.isEmpty()) continue;
            LOG.info("[MCA-PETS] {} ({}) made {} for the town's pets", f.displayNameCap(), t.title, Bench.words(out.getItem(), out.getCount()));
            return Bench.words(out.getItem(), out.getCount()) + ", for the town's pets";
        }
        return null;
    }

    // ------------------------------------------------------------------ the fair's pet show (Fair.script)

    /**
     * The pet show, after the fair's own classes: every household pet about the town (and a player's own dog or cat
     * brought to the board), judged by how it is kept — fed today, its collar, its own bed, a treat lately, its friends,
     * its prime — the best-kept given a ribbon (a sheet of the stores' paper, lettered) and the chronicle's line.
     */
    static void show(ServerLevel level, Villages.Village v, List<Assemblies.Line> s, RandomSource r, long d) {
        UUID id = v.id();
        List<Object[]> entries = new ArrayList<>();                       // {score, name, words, pet uuid or null, owner (folk/player) uuid, home or null, player?}
        for (Homes.Home h : List.copyOf(Homes.homes(id).values())) {
            Families.Pet p = Families.pet(id, h.anchor.asLong());
            if (p == null || !(level.getEntity(p.id()) instanceof TamableAnimal a) || !a.isAlive()) continue;
            Care c = care(id, p.id());
            if (c.lostDay >= 0) continue;
            double score = 0;
            List<String> why = new ArrayList<>();
            if (c.fedDay >= d) { score += 20; why.add("well fed"); }
            if (!c.collar.isEmpty()) { score += 10; why.add("a " + c.collar.replace('_', ' ') + " collar"); }
            if (bedOf(level, id, h, c.cat()) != null) { score += 6; why.add("a bed of its own"); }
            if (d - c.treatDay <= 3) { score += 5; why.add("spoiled with treats"); }
            int friends = Math.min(5, c.friends.size());
            if (friends > 0) { score += 2 * friends; why.add(friends == 1 ? "a friend among the guests" : friends + " friends among the guests"); }
            int y = years(c, d);
            if (y >= 2 && !old(c, d)) score += 8;
            if (!c.sick && a.getHealth() >= a.getMaxHealth() * 0.9F) score += 6;
            score += (Math.floorMod((p.id().hashCode() * 31L) ^ d, 1000L)) / 100.0;
            entries.add(new Object[]{ score, c.name, family(id, h) + " " + kindWord(c.cat()), p.id(), p.keeper(), h, false,
                why.isEmpty() ? "a cheerful face" : String.join(", ", why) });
        }
        for (ServerPlayer pl : level.players()) {
            for (TamableAnimal a : level.getEntitiesOfClass(TamableAnimal.class, new AABB(v.centre()).inflate(24.0),
                    x -> x.isAlive() && pl.getUUID().equals(x.getOwnerUUID()) && (x instanceof Wolf || x instanceof Cat))) {
                double score = 10 + (a.getHealth() >= a.getMaxHealth() * 0.9F ? 6 : 0) + Math.floorMod(a.getUUID().hashCode() ^ d, 1000L) / 100.0;
                String name = a.hasCustomName() ? a.getName().getString() : pl.getName().getString() + "'s " + (a instanceof Cat ? "cat" : "dog");
                entries.add(new Object[]{ score, name, pl.getName().getString() + "'s " + (a instanceof Cat ? "cat" : "dog"), a.getUUID(), pl.getUUID(),
                    null, true, "brought all the way to the fair" });
            }
        }
        if (entries.isEmpty()) return;
        entries.sort((x, y) -> Double.compare((Double) y[0], (Double) x[0]));
        List<String> names = new ArrayList<>();
        for (Object[] e : entries) names.add(e[1] + " (" + e[2] + ")");
        s.add(new Assemblies.Line(null, "And now — the pet show!", '!', null));
        s.add(new Assemblies.Line(null, entries.size() + (entries.size() == 1 ? " entry: " : " entries: ") + String.join(", ", names) + ".", '?', null));
        Object[] w = entries.get(0);
        s.add(new Assemblies.Line(null, "The ribbon for the best-kept pet goes to " + w[1] + ", " + w[2] + ": " + w[7] + "!", '!',
            () -> award(level, v, w, d)));
    }

    private static void award(ServerLevel level, Villages.Village v, Object[] w, long d) {
        UUID id = v.id();
        String key = "pets/show/" + d;
        if (Ledger.note(id, key) != null) return;
        Ledger.note(id, key, (String) w[1]);
        int year = Seasons.year(id, d);
        ItemStack paper = Crafts.takeOne(level, v, s -> s.is(Items.PAPER) && !s.has(DataComponents.CUSTOM_NAME));
        ItemStack ribbon = ItemStack.EMPTY;
        if (!paper.isEmpty()) {
            ribbon = paper.copyWithCount(1);
            ribbon.set(DataComponents.CUSTOM_NAME, Component.literal("Ribbon: best-kept pet, Year " + year).withStyle(ChatFormatting.BLUE));
            ribbon.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(Villages.name(id) + " fair, day " + (d + 1)),
                Component.literal(w[1] + ", " + w[2]))));
        }
        long day = level.getDayTime() / 24000L;
        boolean player = (Boolean) w[6];
        UUID owner = (UUID) w[4];
        if (player) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
            if (p != null) {
                if (!ribbon.isEmpty() && !p.getInventory().add(ribbon)) p.drop(ribbon, false);
                p.sendSystemMessage(Component.literal(w[1] + " won the ribbon for the best-kept pet at " + Villages.name(id) + "'s fair!")
                    .withStyle(ChatFormatting.GOLD));
            } else if (!ribbon.isEmpty()) {
                Crafts.store(level, v, ribbon);
            }
        } else {
            Homes.Home h = (Homes.Home) w[5];
            UUID pet = (UUID) w[3];
            Care c = care(id, pet);
            c.ribbons++;
            save(id, c);
            VillageFolkEntity keeper = level.getEntity(owner) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
            if (keeper == null && h != null) {
                for (VillageFolkEntity m : grown(id, h)) { keeper = m; break; }
            }
            if (keeper != null && !ribbon.isEmpty()) {
                Homes.keepsake(ribbon, keeper);
                ItemStack left = keeper.insertGiven(ribbon);
                if (!left.isEmpty()) Crafts.store(level, v, left);
            } else if (!ribbon.isEmpty()) {
                Crafts.store(level, v, ribbon);
            }
            if (h != null) {
                for (VillageFolkEntity m : Homes.loadedMembers(id, h)) {
                    m.persona().remember(day, c.name + " won the ribbon for the best-kept pet at the fair", m.isBaby() ? 7 : 5);
                    if (m.isBaby()) sayLater(m, FolkTalk.pick(level.getRandom(), c.name + " won! " + c.name + " WON!", "A blue ribbon for " + c.name + "!"), 30);
                }
            }
            if (level.getEntity(pet) instanceof TamableAnimal a) level.broadcastEntityEvent(a, (byte) 7);
        }
        Ledger.note(id, "pets/showwinner", w[1] + ", " + w[2] + " (Year " + year + ")");
        Villages.tell(id, day, w[1] + ", " + w[2] + ", won the ribbon for the best-kept pet at the fair");
        LOG.info("[MCA-PETS] the pet show at {}: {} ({}) wins", Villages.name(id), w[1], w[2]);
    }

    // ------------------------------------------------------------------ players (right-click a pet)

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getTarget() instanceof TamableAnimal a) || !(event.getLevel() instanceof ServerLevel level)) return;
        if (!(a instanceof Wolf) && !(a instanceof Cat)) return;
        Player p = event.getEntity();
        String said = interact(level, p, a, p.getItemInHand(InteractionHand.MAIN_HAND));
        if (said == null) return;
        p.displayClientMessage(Component.literal(said), false);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /**
     * A player's click on a town's animal: a household's pet befriended with a treat (or patted, or found when lost);
     * a pup, a kitten or the merchant's bought for its price in coin held in the hand; a stray taken home with a treat.
     * What happened, in words, or null (not the town's, or nothing to do: the game's own click goes ahead).
     */
    @Nullable
    static String interact(ServerLevel level, Player p, TamableAnimal a, ItemStack held) {
        long day = level.getDayTime() / 24000L;
        Object[] whose = whose(a.getUUID());
        if (whose != null) {
            Villages.Village v = (Villages.Village) whose[0];
            Homes.Home h = (Homes.Home) whose[1];
            Families.Pet pet = Families.pet(v.id(), h.anchor.asLong());
            if (pet == null) return null;
            Care c = know(v.id(), pet, a, day);
            String fam = family(v.id(), h), kind = kindWord(c.cat());
            if (c.lostDay >= 0) {
                c.finder = p.getUUID();
                save(v.id(), c);
                level.broadcastEntityEvent(a, (byte) 7);
                return c.name + " (" + fam + " " + kind + ") was lost out here! It knows you mean home: lead the way, and it will follow you.";
            }
            if (isTreat(held)) {
                if (!p.getAbilities().instabuild) held.shrink(1);
                befriend(c, p, 1);
                c.treatDay = day;
                c.treats++;
                save(v.id(), c);
                a.heal(2.0F);
                level.broadcastEntityEvent(a, (byte) 7);
                level.playSound(null, a.blockPosition(), c.cat() ? SoundEvents.CAT_EAT : SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL, 0.8F, 1.1F);
                FOLLOWING.put(a.getUUID(), new Follow(p.getUUID(), p.getName().getString(), level.getGameTime() + FOLLOW_FOR));
                for (VillageFolkEntity m : Homes.loadedMembers(v.id(), h)) {
                    if (m.distanceToSqr(a) < 16.0 * 16.0) m.persona().feelFor(p.getUUID(), p.getName().getString(), 2);
                }
                int treats = c.friends.getOrDefault(p.getUUID(), 0);
                return (c.cat() ? c.name + " nibbles the treat out of your hand and purrs." : c.name + " wolfs the treat down and wags at you.")
                    + (treats >= FRIENDS_AT ? " " + c.name + " has taken a shine to you, and trots after you a while." : " (" + fam + " " + kind + ")");
            }
            if (held.isEmpty()) {
                boolean friend = c.friends.getOrDefault(p.getUUID(), 0) >= FRIENDS_AT;
                if (friend) {
                    level.broadcastEntityEvent(a, (byte) 7);
                    FOLLOWING.put(a.getUUID(), new Follow(p.getUUID(), p.getName().getString(), level.getGameTime() + FOLLOW_FOR / 2));
                    return c.cat() ? c.name + " winds round your legs, purring." : c.name + " jumps up at you, tail going like a windmill.";
                }
                return c.name + ", " + fam + " " + kind + ". It sniffs your hand. A pet treat might win it over.";
            }
            return null;
        }
        for (Villages.Village v : Villages.every()) {
            Other o = others(v.id()).get(a.getUUID());
            if (o == null) continue;
            if (Other.STRAY.equals(o.role())) {
                if (!isTreat(held)) return "A stray " + kindWord(o.cat()) + ", thin and wary. It eyes your pockets: a pet treat would win it over.";
                if (!p.getAbilities().instabuild) held.shrink(1);
                String name = byPlayer(level, v, p, a, o, day, "a stray");
                return "The stray takes the treat from your hand — and follows you. You have " + a(kindWord(o.cat())) + "! "
                    + "The children of " + Villages.name(v.id()) + " say its name is " + name + ".";
            }
            int price = Other.MERCHANT.equals(o.role()) ? MERCHANT_PRICE : PRICE;
            String young = youngWord(o.cat());
            if (Market.coinsHeld(p) < price) {
                return Other.MERCHANT.equals(o.role()) ? "The merchant's " + young + ": " + price + " coins to a good home. Hold the coins and click it."
                    : "One of " + o.mother() + "'s " + (o.cat() ? "kittens" : "pups") + ", looking for a home: " + price
                        + " coins to the family. Hold the coins and click it.";
            }
            Market.payOut(p, price);
            if (Other.MERCHANT.equals(o.role())) {
                if (o.merchant() != null && level.getEntity(o.merchant()) instanceof VillageFolkEntity m) {
                    m.earn(price);
                    say(m, "A fine choice! Look after the little one.");
                }
            } else {
                Homes.Home mum = Homes.homes(v.id()).get(o.home());
                VillageFolkEntity payee = null;
                if (mum != null) for (VillageFolkEntity m : grown(v.id(), mum)) if (payee == null) payee = m;
                if (payee != null) {
                    payee.earn(price);
                    payee.persona().feelFor(p.getUUID(), p.getName().getString(), 4);
                    say(payee, FolkTalk.pick(level.getRandom(), "Oh, " + p.getName().getString() + " — you'll give it a good home, I know.",
                        "Thank you! Bring it to visit its mother now and then."));
                }
            }
            String name = byPlayer(level, v, p, a, o, day, Other.MERCHANT.equals(o.role()) ? "the merchant's " + young : "one of " + o.mother() + "'s litter");
            return "You paid " + price + " coins: the " + young + " is yours! It is called " + name + ".";
        }
        return null;
    }

    /** A player takes it home: tame, theirs, named, and out of the town's books (the chronicle remembers). */
    private static String byPlayer(ServerLevel level, Villages.Village v, Player p, TamableAnimal a, Other o, long day, String how) {
        UUID id = v.id();
        dropOther(id, a.getUUID());
        a.tame(p);
        a.setOrderedToSit(false);
        a.setInSittingPose(false);
        RESTING.remove(a.getUUID());
        String name = a.hasCustomName() ? a.getName().getString() : freshName(id, o.cat(), level.getRandom());
        a.setCustomName(Component.literal(name));
        level.broadcastEntityEvent(a, (byte) 7);
        Villages.tell(id, day, "a guest, " + p.getName().getString() + ", took home " + how + ", and named it " + name);
        LOG.info("[MCA-PETS] {} took home {} ({})", p.getName().getString(), name, how);
        return name;
    }

    // ------------------------------------------------------------------ what is shown: the card, talk, the books

    /** The household's pet on a folk's card: its name and kind, its age, how it is kept, its friends; else its young or its grave. */
    public static String cardLine(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isShowcase() || !(f.level() instanceof ServerLevel level)) return "";
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        if (h == null) return "";
        long day = level.getDayTime() / 24000L;
        Families.Pet p = Families.pet(village, h.anchor.asLong());
        List<String> parts = new ArrayList<>();
        if (p != null) {
            Care c = care(village, p.id());
            int y = years(c, day);
            parts.add(p.name() + ", a " + kindWord("cat".equals(p.kind())) + " of " + (y == 0 ? "less than a year" : y + (y == 1 ? " year" : " years"))
                + (old(c, day) ? " (old now)" : ""));
            if (c.lostDay >= 0) parts.add("missing since day " + (c.lostDay + 1));
            else if (c.sick) parts.add("poorly");
            parts.add(c.fedDay >= day ? "fed today" : "not fed yet today");
            if (!c.collar.isEmpty()) parts.add("a " + c.collar.replace('_', ' ') + " collar");
            if (bowlOf(level, village, h) != null) parts.add("its own bowl");
            if (bedOf(level, village, h, "cat".equals(p.kind())) != null) parts.add("cat".equals(p.kind()) ? "its own basket" : "its own bed");
            if (c.litters > 0) parts.add(c.litters == 1 ? "one litter" : c.litters + " litters");
            if (c.ribbons > 0) parts.add(c.ribbons == 1 ? "a ribbon from the fair" : c.ribbons + " ribbons from the fair");
            if (c.barks > 0) parts.add("barked off " + c.barks + (c.barks == 1 ? " monster" : " monsters"));
            if (c.creepers > 0) parts.add("saw off " + c.creepers + (c.creepers == 1 ? " creeper" : " creepers"));
            if (!c.friendNames.isEmpty()) parts.add("friends with " + String.join(", ", c.friendNames.values()));
        }
        int young = 0;
        boolean cat = false;
        for (Other o : others(village).values()) {
            if (Other.YOUNG.equals(o.role()) && o.home() == h.anchor.asLong()) { young++; cat = o.cat(); }
        }
        if (young > 0) parts.add(young + " " + (cat ? (young == 1 ? "kitten" : "kittens") : (young == 1 ? "pup" : "pups")) + " looking for homes ("
            + PRICE + " coins to a good home)");
        if (p == null) {
            String g = Ledger.note(village, "petgrave/" + h.anchor.asLong());
            if (g != null && !g.isEmpty()) {
                String[] q = g.split("\\|", -1);
                if (q.length > 3) parts.add("remembers " + q[0] + ", its old " + q[1] + " (died day " + (parseLong(q[3], 0) + 1) + ")");
            }
        }
        return String.join("; ", parts);
    }

    /** "Your pet?": a folk on its household's pet, its young looking for homes, a pet missing, or the pet it wishes it had. */
    public static String talk(VillageFolkEntity f, Player p, String text) {
        UUID village = f.ownerId();
        RandomSource r = f.getRandom();
        if (village == null || !(f.level() instanceof ServerLevel level)) return "A pet? Not me.";
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        long day = level.getDayTime() / 24000L;
        boolean kid = f.isBaby();
        StringBuilder sb = new StringBuilder();
        Families.Pet pet = h == null ? null : Families.pet(village, h.anchor.asLong());
        if (pet != null) {
            Care c = care(village, pet.id());
            boolean cat = "cat".equals(pet.kind());
            String he = cat ? "she" : "he", his = cat ? "her" : "his";
            int y = years(c, day);
            if (c.lostDay >= 0) {
                BlockPos at = c.lostAt == null ? h.anchor : c.lostAt;
                return (kid ? c.name + "'s lost! " + FolkTalk.cap(he) + " ran off and didn't come back! " : c.name + "'s gone missing. Off after a rabbit, I'd bet. ")
                    + "Last seen out " + Guide.direction(f.blockPosition(), at) + "wards, past the edge of the town. If you find " + (cat ? "her" : "him")
                    + ", " + he + "'ll follow you home.";
            }
            if (kid) {
                sb.append(FolkTalk.pick(r, c.name + " is the BEST " + kindWord(cat) + "! ", c.name + "'s my best friend! ", "Have you met " + c.name + "? "));
                sb.append(cat ? FolkTalk.pick(r, "She sleeps on my bed and purrs like anything. ", "She goes up on the roof and nobody knows how! ")
                    : FolkTalk.pick(r, "He can fetch! I throw the stick and he brings it back. ", "He follows me everywhere, even to the park! "));
            } else {
                sb.append(c.name).append("? Our ").append(kindWord(cat)).append(y > 0 ? ", " + TownCalendar.inWords(y) + (y == 1 ? " year" : " years") + " old" : ", still young")
                    .append(old(c, day) ? " and getting on" : "").append(". ");
                sb.append(c.fedDay >= day ? FolkTalk.pick(r, FolkTalk.cap(he) + "'s had " + his + " dinner, and wants more. ", "Fed and happy. ")
                    : FolkTalk.pick(r, FolkTalk.cap(he) + "'s not had " + his + " dinner yet — " + he + "'ll be at me about it. ", ""));
                if (cat) sb.append(FolkTalk.pick(r, "Asleep on the roof half the day, and on the children's beds the other half. ", "Keeps the creepers off, too. "));
                else sb.append(FolkTalk.pick(r, "Follows the children everywhere. ", "Walks me to work, then trots home again. "));
            }
            if (c.barkDay >= day - 1 && !c.barkedAt.isEmpty()) sb.append(c.name).append(" kept us up barking at ").append(a(c.barkedAt)).append(" last night! ");
            if (c.sick) sb.append(FolkTalk.cap(he)).append("'s poorly just now; the healer's been to see ").append(cat ? "her" : "him").append(". ");
            if (c.ribbons > 0) sb.append(FolkTalk.cap(he)).append(" won a ribbon at the fair, you know. ");
            int friend = c.friends.getOrDefault(p.getUUID(), 0);
            if (friend >= FRIENDS_AT) sb.append(FolkTalk.cap(he)).append("'s taken a real shine to you. ");
            else sb.append(FolkTalk.pick(r, "Give " + (cat ? "her" : "him") + " a pet treat and you'll have a friend for life. ", ""));
        } else if (h != null) {
            String g = Ledger.note(village, "petgrave/" + h.anchor.asLong());
            if (g != null && !g.isEmpty()) {
                String name = g.split("\\|", -1)[0];
                sb.append(kid ? "We had " + name + ", but " + name + " died. I still miss " + name + ". " : "We lost our " + name + ". Best friend we ever had. ");
            } else if (wants(level, village, h)) {
                sb.append(kid ? FolkTalk.pick(r, "I want a dog SO much. Everyone else has one! ", "Can you get me a kitten? Please? ")
                    : FolkTalk.pick(r, "We'd love a dog. The children ask every day. ", "A cat would be nice — keep the mice down. One will turn up. "));
            } else {
                sb.append(FolkTalk.pick(r, "No pets for us. The neighbours' cat visits; that's enough. ", "Not us. Too much hair on everything. "));
            }
        }
        if (h != null) {
            int young = 0;
            boolean cat = false;
            for (Other o : others(village).values()) if (Other.YOUNG.equals(o.role()) && o.home() == h.anchor.asLong()) { young++; cat = o.cat(); }
            if (young > 0) {
                sb.append("We've ").append(young == 1 ? "a " + youngWord(cat) : TownCalendar.inWords(young) + " " + (cat ? "kittens" : "pups"))
                    .append(" looking for homes — ").append(PRICE).append(" coins to a good home. Hold the coins out and click one. ");
            }
        }
        int strays = 0;
        for (Other o : others(village).values()) if (Other.STRAY.equals(o.role())) strays++;
        if (strays > 0 && sb.length() < 200) sb.append("There's a stray about the town, if you've a treat in your pocket.");
        String out = sb.toString().trim();
        return out.isEmpty() ? "A pet? Not me." : out;
    }

    /** Two folk passing the time of day about their pets (Smalltalk): {opener, answer, last word} a line. */
    static List<String[]> chat(VillageFolkEntity a, VillageFolkEntity b, ServerLevel level, RandomSource r) {
        List<String[]> out = new ArrayList<>();
        UUID village = a.ownerId();
        if (village == null) return out;
        long day = level.getDayTime() / 24000L;
        Homes.Home ha = Homes.homeOf(village, a.getUUID()), hb = Homes.homeOf(village, b.getUUID());
        Families.Pet pa = ha == null ? null : Families.pet(village, ha.anchor.asLong());
        Families.Pet pb = hb == null || hb == ha ? null : Families.pet(village, hb.anchor.asLong());
        if (pa != null) {
            Care c = care(village, pa.id());
            if (c.barkDay >= day - 1 && !c.barkedAt.isEmpty()) {
                out.add(new String[]{ c.name + " had us up half the night barking at " + a(c.barkedAt) + ".", "Good dog, that. Better than any lock.",
                    FolkTalk.pick(r, "Tell that to my sleep!", "I suppose.") });
            }
            if (c.litterDay >= day - 2) {
                out.add(new String[]{ "Did you hear? " + c.name + "'s had " + ("cat".equals(pa.kind()) ? "kittens" : "pups") + "!", "Never! I'll have to come and see them.",
                    "Bring a treat. They're greedy little things." });
            }
            if ("cat".equals(pa.kind())) {
                out.add(new String[]{ c.name + " was up on our roof again all afternoon.", "Cats know the best spot in town.", "" });
                if (c.creepers > 0) out.add(new String[]{ c.name + " saw a creeper off the step again.", "Worth her weight in fish, that cat.", "" });
            } else {
                out.add(new String[]{ c.name + " walked me all the way to work this morning.", "And then trotted home again?", "Like clockwork." });
            }
        }
        if (pb != null) {
            Care c = care(village, pb.id());
            out.add(new String[]{ "How's " + c.name + "?", c.fedDay >= day ? "Fat and happy, and spoiled rotten." : "Hungry, as ever.", "Like all of them." });
        }
        if (pa == null && ha != null && !children(village, ha).isEmpty() && wants(level, village, ha)) {
            out.add(new String[]{ "The children are on at me for a dog again.", "Give in. You know you want to.", "Hmm. Perhaps." });
        }
        return out;
    }

    /** The town's books (the News page): the town's pets, their homes and their things, the litters, the show, those lost. */
    public static List<String> book(ServerLevel level, UUID village) {
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        int dogs = 0, cats = 0, homes = 0, bowls = 0, beds = 0, collars = 0, fed = 0, barks = 0, creepers = 0;
        List<String> each = new ArrayList<>();
        for (Homes.Home h : List.copyOf(Homes.homes(village).values())) {
            if (h.members.isEmpty()) continue;
            homes++;
            Families.Pet p = Families.pet(village, h.anchor.asLong());
            if (p == null) continue;
            boolean cat = "cat".equals(p.kind());
            if (cat) cats++;
            else dogs++;
            Care c = care(village, p.id());
            boolean bowl = level.isLoaded(h.anchor) && bowlOf(level, village, h) != null, bed = level.isLoaded(h.anchor) && bedOf(level, village, h, cat) != null;
            if (bowl) bowls++;
            if (bed) beds++;
            if (!c.collar.isEmpty()) collars++;
            if (c.fedDay >= day) fed++;
            barks += c.barks;
            creepers += c.creepers;
            if (each.size() < 10) {
                List<String> how = new ArrayList<>();
                if (c.lostDay >= 0) how.add("missing");
                if (c.sick) how.add("poorly");
                how.add(c.fedDay >= day ? "fed" : "not fed yet");
                if (!c.collar.isEmpty()) how.add(c.collar.replace('_', ' ') + " collar");
                if (bed) how.add("own bed");
                each.add(p.name() + " (" + kindWord(cat) + ", " + years(c, day) + ") — " + family(village, h).replace("'s", "") + ": " + String.join(", ", how));
            }
        }
        int young = 0, strays = 0, merchants = 0;
        for (Other o : others(village).values()) {
            switch (o.role()) {
                case Other.YOUNG -> young++;
                case Other.STRAY -> strays++;
                case Other.MERCHANT -> merchants++;
                default -> { }
            }
        }
        if (dogs + cats + young + strays + merchants == 0) {
            out.add("No pets in town yet." + (homes > 0 ? " A stray turns up now and then while a household wants one." : ""));
        } else {
            out.add((dogs + cats) + " household pets in " + homes + " homes (" + dogs + (dogs == 1 ? " dog, " : " dogs, ") + cats + (cats == 1 ? " cat" : " cats")
                + "); the town keeps " + cap(village) + " at most, all told." + (young > 0 ? " " + young + " young looking for homes." : "")
                + (strays > 0 ? " " + strays + (strays == 1 ? " stray" : " strays") + " about the town." : "")
                + (merchants > 0 ? " The merchant has one for sale." : ""));
            if (dogs + cats > 0) out.add("Fed today: " + fed + " of " + (dogs + cats) + ". Bowls " + bowls + ", beds " + beds + ", collars " + collars + ".");
            out.addAll(each);
        }
        Villages.Village v = Villages.get(village);
        if (v != null) {
            Map<Item, Integer> want = wanted(level, v);
            List<String> w = new ArrayList<>();
            for (Map.Entry<Item, Integer> e : want.entrySet()) {
                int have = Market.stock(level, village, s -> s.is(e.getKey()));
                if (have < e.getValue()) w.add(Bench.words(e.getKey(), e.getValue() - have));
            }
            if (!w.isEmpty()) out.add("Wanted for the pets (the tailor, the cook, the shop): " + String.join(", ", w) + ".");
        }
        int litters = parse(Ledger.note(village, "pets/litters"));
        String last = Ledger.note(village, "pets/lastlitter");
        if (litters > 0) out.add("Litters: " + litters + (last == null ? "" : "; the last, " + last) + ".");
        if (barks > 0 || creepers > 0) {
            out.add("The dogs have barked the watch out to " + barks + (barks == 1 ? " monster" : " monsters") + "; the cats have seen off "
                + creepers + (creepers == 1 ? " creeper." : " creepers."));
        }
        int tended = parse(Ledger.note(village, "pets/tended"));
        if (tended > 0) out.add("The healer has seen to the pets " + tended + (tended == 1 ? " time." : " times."));
        String show = Ledger.note(village, "pets/showwinner");
        if (show != null && !show.isEmpty()) out.add("Best-kept pet at the fair: " + show + ".");
        String mem = Ledger.note(village, "pets/memoriam");
        if (mem != null && !mem.isEmpty()) {
            List<String> gone = new ArrayList<>();
            for (String m : mem.split(",")) {
                String[] q = m.split("~");
                if (q.length >= 4) gone.add(q[0] + " (" + q[1] + ", " + q[2] + ", day " + (parseLong(q[3], 0) + 1) + ")");
                if (gone.size() >= 4) break;
            }
            if (!gone.isEmpty()) out.add("In memory: " + String.join("; ", gone) + ".");
        }
        return out;
    }

    /** Right-click a bowl: whose it is. */
    public static String whoseBowl(Level level, BlockPos pos) {
        return whoseThing(level, pos, "petbowl/", "bowl");
    }

    /** Right-click a bed or a basket: whose it is. */
    public static String whoseBed(Level level, BlockPos pos) {
        return whoseThing(level, pos, "petbed/", "bed");
    }

    private static String whoseThing(Level lvl, BlockPos pos, String prefix, String what) {
        if (!(lvl instanceof ServerLevel level)) return "";
        Villages.Village v = Villages.nearest(level, pos, Villages.VILLAGE_RANGE * 2);
        if (v == null) return "";
        for (Homes.Home h : Homes.homes(v.id()).values()) {
            String s = Ledger.note(v.id(), prefix + h.anchor.asLong());
            if (s == null || parseLong(s, Long.MIN_VALUE) != pos.asLong()) continue;
            Families.Pet p = Families.pet(v.id(), h.anchor.asLong());
            if (p == null) return "It was a " + what + " for " + family(v.id(), h) + " pet.";
            return "It's " + p.name() + "'s " + what + " (" + family(v.id(), h) + " " + kindWord("cat".equals(p.kind())) + ").";
        }
        return "";
    }

    // ------------------------------------------------------------------ /village pets

    /**
     * /village pets: the town's pets (the books' lines, and a "PET kind name x y z home x y z (doing)" line each, for
     * scripts). {@code books} opens the town's books at the News page. Operators: {@code now <stray|takein|litter|fill|
     * merchant|lost|homes>} brings one about now out of the town's own stores and purses; {@code stage} sets the scene for
     * the pictures (a dog at a child's heels, a cat on the roof, a bowl and a bed in a home).
     */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("pets")
            .executes(ctx -> page(ctx))
            .then(Commands.literal("books").executes(Pets::books))
            .then(Commands.literal("now").requires(src -> src.hasPermission(2))
                .then(Commands.argument("what", StringArgumentType.word())
                    .suggests((c, b) -> {
                        for (String s : List.of("stray", "takein", "litter", "fill", "things", "merchant", "lost", "homes")) b.suggest(s);
                        return b.buildFuture();
                    })
                    .executes(ctx -> now(ctx, StringArgumentType.getString(ctx, "what")))))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                List<String> out = stage(ctx.getSource().getLevel(), v);
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                return out.size();
            }));
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int page(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        List<String> lines = new ArrayList<>(book(level, v.id()));
        lines.addAll(lines(level, v));
        String text = String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return lines.size();
    }

    /** "PET dog Biscuit 10 64 -3 home 12 64 -8 (following Kip)", a line a pet, then the others ("STRAY cat 1 2 3"). */
    static List<String> lines(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        for (Homes.Home h : List.copyOf(Homes.homes(id).values())) {
            Families.Pet p = Families.pet(id, h.anchor.asLong());
            if (p == null || !(level.getEntity(p.id()) instanceof TamableAnimal a)) continue;
            BlockPos at = a.blockPosition();
            BlockPos bowl = bowlOf(level, id, h), bed = bedOf(level, id, h, "cat".equals(p.kind()));
            out.add("PET " + kindWord("cat".equals(p.kind())) + " " + p.name().replace(' ', '_') + " " + at.getX() + " " + at.getY() + " " + at.getZ()
                + " home " + h.anchor.getX() + " " + h.anchor.getY() + " " + h.anchor.getZ()
                + " bowl " + (bowl == null ? "none" : bowl.getX() + " " + bowl.getY() + " " + bowl.getZ())
                + " bed " + (bed == null ? "none" : bed.getX() + " " + bed.getY() + " " + bed.getZ())
                + " (" + Families.petDoing(p.id()) + ")");
        }
        for (Other o : others(id).values()) {
            if (!(level.getEntity(o.animal()) instanceof TamableAnimal a)) continue;
            BlockPos at = a.blockPosition();
            out.add(o.role().toUpperCase(Locale.ROOT) + " " + kindWord(o.cat()) + " " + at.getX() + " " + at.getY() + " " + at.getZ());
        }
        return out;
    }

    private static int books(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return page(ctx);
        CompoundTag books = Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "News");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    private static int now(CommandContext<CommandSourceStack> ctx, String what) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        long day = level.getDayTime() / 24000L;
        List<String> said = new ArrayList<>();
        switch (what) {
            case "stray" -> {
                TamableAnimal a = stray(level, v, level.getRandom().nextBoolean(), null);
                said.add(a == null ? "no room for a stray" : "a stray " + (a instanceof Cat ? "cat" : "dog") + " at " + a.blockPosition().toShortString());
            }
            case "takein" -> said.add(takeInNow(level, v, null));
            case "litter" -> {
                List<TamableAnimal> y = litterNow(level, v, null);
                said.add(y.isEmpty() ? "no litter (no pair, no room, or nothing to feed them)" : y.size() + " young born");
            }
            case "fill", "things" -> {
                int n = 0;
                for (Homes.Home h : List.copyOf(Homes.homes(v.id()).values())) {
                    Families.Pet p = Families.pet(v.id(), h.anchor.asLong());
                    if (p == null || !(level.getEntity(p.id()) instanceof TamableAnimal a)) continue;
                    Care c = know(v.id(), p, a, day);
                    if (what.equals("fill")) {
                        VillageFolkEntity f = anyone(v.id(), h);
                        if (f != null && errandNow(level, f, new Errand(Job.FILL, v.id(), h.anchor.asLong(), a.getUUID(), level.getGameTime()))) n++;
                    } else {
                        WANT_LOOKED.remove(v.id() + "/" + h.anchor.asLong());
                        needs(level, v, h, p, a, c, day);
                        for (UUID m : h.members) {
                            if (level.getEntity(m) instanceof VillageFolkEntity f && ERRANDS.containsKey(m) && errandNow(level, f, ERRANDS.get(m))) n++;
                        }
                    }
                }
                said.add(n + " seen to");
            }
            case "merchant" -> {
                VillageFolkEntity m = Merchants.arriveNowForTests(level, v);
                said.add(m == null ? "no merchant came" : "the merchant came: " + m.displayNameCap());
            }
            case "lost" -> {
                Lost l = lost(level, v.id());
                said.add(l == null ? "no pet could go missing" : l.name() + " is lost at " + l.at().toShortString());
            }
            case "homes" -> said.add(homesForTheYoung(level, v, day) + " young found homes");
            default -> said.add("now: stray, takein, litter, fill, things, merchant, lost or homes");
        }
        ctx.getSource().sendSuccess(() -> Component.literal("PETS " + String.join("; ", said)), false);
        return said.size();
    }

    /** An errand seen through at once, as if it were standing there for each step (it does not walk). */
    static boolean errandNow(ServerLevel level, VillageFolkEntity f, Errand e) {
        ERRANDS.put(f.getUUID(), e);
        long day = level.getDayTime() / 24000L;
        if (!e.begun) {
            if (!begin(level, f, e, day)) {
                drop(level, f, e, null);
                return false;
            }
            e.begun = true;
        }
        for (int i = 0; i < 8 && ERRANDS.get(f.getUUID()) == e; i++) step(level, f, e, day, true);
        return true;
    }

    /** The household that wants a pet most takes in the nearest stray now (it is brought one if none is about). */
    static String takeInNow(ServerLevel level, Villages.Village v, @Nullable Homes.Home only) {
        UUID id = v.id();
        List<Homes.Home> want = only != null ? List.of(only) : wanting(level, id);
        if (want.isEmpty()) return "no household wants a pet";
        Homes.Home h = want.get(0);
        TamableAnimal stray = null;
        double bd = Double.MAX_VALUE;
        for (Other o : others(id).values()) {
            if (!Other.STRAY.equals(o.role()) || !(level.getEntity(o.animal()) instanceof TamableAnimal a) || !a.isAlive() || a.isTame()) continue;
            double d = a.distanceToSqr(h.anchor.getX(), h.anchor.getY(), h.anchor.getZ());
            if (d < bd) { bd = d; stray = a; }
        }
        if (stray == null) stray = stray(level, v, Math.floorMod(Long.hashCode(h.anchor.asLong()), 2) == 0, null);
        if (stray == null) return "no stray to take in";
        VillageFolkEntity f = null;
        for (VillageFolkEntity m : grown(id, h)) if (!ERRANDS.containsKey(m.getUUID())) { f = m; break; }
        if (f == null) return "nobody of the household free";
        boolean ok = errandNow(level, f, new Errand(Job.TAKE_IN, id, h.anchor.asLong(), stray.getUUID(), level.getGameTime()));
        Families.Pet p = Families.pet(id, h.anchor.asLong());
        return ok && p != null ? f.displayNameCap() + " took in " + p.name() : "could not take it in (no meat or fish to coax it with)";
    }

    /** A litter now for the first household pet that has a mate about (the town's cap still holds). */
    static List<TamableAnimal> litterNow(ServerLevel level, Villages.Village v, @Nullable TamableAnimal mate) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        for (Homes.Home h : List.copyOf(Homes.homes(id).values())) {
            Families.Pet p = Families.pet(id, h.anchor.asLong());
            if (p == null || !(level.getEntity(p.id()) instanceof TamableAnimal a) || !a.isAlive() || a.isBaby()) continue;
            TamableAnimal m = mate != null ? mate : mateFor(level, id, a);
            if (m == null || m == a || m.getClass() != a.getClass()) continue;
            return litter(level, v, h, a, m, know(id, p, a, day), day);
        }
        return List.of();
    }

    /**
     * For the pictures (smoke): in the town at hand, a household with a child given a dog (a stray taken in now, the
     * meat to coax it out of the stores) and the dog sent to the child; a bowl and a bed set out in its home out of the
     * stores; a household given a cat, and the cat put up on its roof in the sun. VIEW lines for the camera.
     */
    static List<String> stage(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        long day = level.getDayTime() / 24000L;
        Homes.Home dogHome = null, catHome = null;
        for (Homes.Home h : List.copyOf(Homes.homes(id).values())) {
            if (h.members.isEmpty() || !level.isLoaded(h.anchor) || grown(id, h).isEmpty()) continue;
            Families.Pet p = Families.pet(id, h.anchor.asLong());
            boolean kids = !children(id, h).isEmpty();
            if (dogHome == null && kids && (p == null || !"cat".equals(p.kind()))) dogHome = h;
            else if (catHome == null && (p == null || "cat".equals(p.kind())) && h != dogHome && !Flats.isFlat(h)) catHome = h;
        }
        if (dogHome == null) out.add("no household with a child to give a dog");
        else {
            if (Families.pet(id, dogHome.anchor.asLong()) == null) {
                WANT_FOR_TESTS.put(id + "/" + dogHome.anchor.asLong(), true);
                TamableAnimal s = stray(level, v, false, Families.hearth(level, id, dogHome).offset(3, 0, 3));
                out.add("dog: " + (s == null ? "no stray" : takeInNow(level, v, dogHome)));
                WANT_FOR_TESTS.remove(id + "/" + dogHome.anchor.asLong());
            }
            Families.Pet p = Families.pet(id, dogHome.anchor.asLong());
            if (p != null && level.getEntity(p.id()) instanceof TamableAnimal dog) {
                for (Item it : new Item[]{ McAssistantMod.PET_BOWL_ITEM.get(), McAssistantMod.DOG_BED_ITEM.get() }) {
                    VillageFolkEntity f = anyone(id, dogHome);
                    if (f == null) break;
                    if (it == McAssistantMod.PET_BOWL_ITEM.get() ? bowlOf(level, id, dogHome) != null : bedOf(level, id, dogHome, false) != null) continue;
                    boolean ok = errandNow(level, f, place(Job.PLACE, id, dogHome.anchor.asLong(), dog, level.getGameTime(), it));
                    out.add((ok ? "set out " : "could not set out ") + Bench.words(it, 1) + " (from the stores)");
                }
                VillageFolkEntity f = anyone(id, dogHome);
                if (f != null && bowlOf(level, id, dogHome) != null) errandNow(level, f, new Errand(Job.FILL, id, dogHome.anchor.asLong(), dog.getUUID(), level.getGameTime()));
                VillageFolkEntity child = children(id, dogHome).get(0);
                dog.teleportTo(child.getX() + 1.5, child.getY(), child.getZ() + 1.0);
                FOLLOWING.remove(dog.getUUID());
                Families.walkPetForTests(level, child);
                BlockPos c = child.blockPosition();
                out.add("VIEW pets-1-dog-and-child " + (c.getX() + 5) + " " + (c.getY() + 2) + " " + (c.getZ() + 5) + " " + c.getX() + " " + c.getY() + " " + c.getZ());
                BlockPos bowl = bowlOf(level, id, dogHome), bed = bedOf(level, id, dogHome, false);
                BlockPos look = bowl != null ? bowl : bed;
                if (look != null) {
                    BlockPos eye = Families.hearth(level, id, dogHome);
                    if (eye.distSqr(look) < 4) eye = eye.offset(2, 0, 2);
                    out.add("VIEW pets-2-bowl-and-bed " + eye.getX() + " " + (eye.getY() + 1) + " " + eye.getZ() + " " + look.getX() + " " + look.getY() + " " + look.getZ());
                }
            }
        }
        if (catHome == null) out.add("no other household to give a cat");
        else {
            if (Families.pet(id, catHome.anchor.asLong()) == null) {
                WANT_FOR_TESTS.put(id + "/" + catHome.anchor.asLong(), true);
                TamableAnimal s = stray(level, v, true, Families.hearth(level, id, catHome).offset(3, 0, 3));
                out.add("cat: " + (s == null ? "no stray" : takeInNow(level, v, catHome)));
                WANT_FOR_TESTS.remove(id + "/" + catHome.anchor.asLong());
            }
            Families.Pet p = Families.pet(id, catHome.anchor.asLong());
            if (p != null && level.getEntity(p.id()) instanceof Cat cat) {
                BlockPos roof = roofOf(level, id, catHome);
                if (roof != null) {
                    wake(cat);
                    cat.teleportTo(roof.getX() + 0.5, roof.getY(), roof.getZ() + 0.5);
                    rest(cat, true);
                    out.add("VIEW pets-3-cat-on-the-roof " + (roof.getX() + 6) + " " + (roof.getY() + 3) + " " + (roof.getZ() + 6) + " " + roof.getX() + " " + roof.getY() + " "
                        + roof.getZ());
                } else {
                    Object[] bed = bedFor(level, id, catHome);
                    if (bed != null) {
                        BlockPos foot = (BlockPos) bed[0];
                        wake(cat);
                        cat.teleportTo(foot.getX() + 0.5, foot.getY() + 0.5625, foot.getZ() + 0.5);
                        rest(cat, true);
                        out.add("VIEW pets-3-cat-on-a-bed " + (foot.getX() + 2) + " " + (foot.getY() + 2) + " " + (foot.getZ() + 2) + " " + foot.getX() + " " + foot.getY()
                            + " " + foot.getZ());
                    }
                }
            }
        }
        out.addAll(lines(level, v));
        LOG.info("[MCA-PETS] stage at {} on day {}: {}", Villages.name(id), day, out);
        return out;
    }

    // ------------------------------------------------------------------ tests

    /** Tests: what the households have said about their pets lately ("Name: words"). */
    public static List<String> saidForTests() {
        return new ArrayList<>(SAID);
    }

    /** Tests: this household's wish for a pet decided (true, false), or left to its nature (null). */
    public static void wantForTests(UUID village, BlockPos anchor, @Nullable Boolean want) {
        if (want == null) WANT_FOR_TESTS.remove(village + "/" + anchor.asLong());
        else WANT_FOR_TESTS.put(village + "/" + anchor.asLong(), want);
    }

    /** Tests: the town taken as settled (fed and content enough) or not, or as it stands (null). */
    public static void settledForTests(@Nullable Boolean settled) {
        settledForTests = settled;
    }

    /** Tests: the chance events held off (true), or as in the game (false). */
    public static void calmForTests(boolean on) {
        calm = on;
    }

    /** Tests: does the household at this anchor want a pet, and is the town settled? */
    public static boolean wantsForTests(ServerLevel level, UUID village, BlockPos anchor) {
        Homes.Home h = Homes.homes(village).get(anchor.asLong());
        return h != null && wants(level, village, h) && settled(level, village);
    }

    /** Tests: a stray here, now (a cat or a dog), the town's. */
    @Nullable
    public static TamableAnimal strayForTests(ServerLevel level, UUID village, boolean cat, BlockPos at) {
        Villages.Village v = Villages.get(village);
        return v == null ? null : stray(level, v, cat, at);
    }

    /** Tests: the household at this anchor (or the one that wants one most) takes in the nearest stray now. */
    public static String takeInForTests(ServerLevel level, UUID village, @Nullable BlockPos anchor) {
        Villages.Village v = Villages.get(village);
        if (v == null) return "no village";
        return takeInNow(level, v, anchor == null ? null : Homes.homes(village).get(anchor.asLong()));
    }

    /** Tests: the town's own round of strays and take-ins (as Pets.tick has it), now. */
    public static void takeInsForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        if (v != null) takeIns(level, v, level.getDayTime() / 24000L);
    }

    /** Tests: this folk's errand for its pet seen through now ("FILL", "PLACE"...), or null if it had none. */
    @Nullable
    public static String errandNowForTests(ServerLevel level, VillageFolkEntity f) {
        Errand e = ERRANDS.get(f.getUUID());
        if (e == null) return null;
        String job = e.job.name();
        return errandNow(level, f, e) ? job : job + " (could not begin)";
    }

    /** Tests: the kind of errand this folk has for its pet, or null. */
    @Nullable
    public static String errandForTests(VillageFolkEntity f) {
        Errand e = ERRANDS.get(f.getUUID());
        return e == null ? null : e.job.name();
    }

    /** Tests: this member fills its household's pet's bowl now (out of the chest and the stores). */
    public static boolean fillForTests(ServerLevel level, VillageFolkEntity member) {
        UUID village = member.ownerId();
        Homes.Home h = village == null ? null : Homes.homeOf(village, member.getUUID());
        Families.Pet p = h == null ? null : Families.pet(village, h.anchor.asLong());
        if (p == null) return false;
        return errandNow(level, member, new Errand(Job.FILL, village, h.anchor.asLong(), p.id(), level.getGameTime()));
    }

    /** Tests: this member sets out a thing for its pet now (a bowl, a bed, a basket), out of the chest and the stores. */
    public static boolean placeForTests(ServerLevel level, VillageFolkEntity member, Item thing) {
        UUID village = member.ownerId();
        Homes.Home h = village == null ? null : Homes.homeOf(village, member.getUUID());
        Families.Pet p = h == null ? null : Families.pet(village, h.anchor.asLong());
        if (p == null || !(level.getEntity(p.id()) instanceof TamableAnimal a)) return false;
        Job job = thing == McAssistantMod.COLLAR.get() ? Job.COLLAR : thing == McAssistantMod.PET_TREAT.get() ? Job.TREAT : Job.PLACE;
        return errandNow(level, member, place(job, village, h.anchor.asLong(), a, level.getGameTime(), thing));
    }

    /** Tests: the household's round for its pet now (what it wants looked at again today). */
    public static void householdForTests(ServerLevel level, VillageFolkEntity member) {
        UUID village = member.ownerId();
        Villages.Village v = village == null ? null : Villages.get(village);
        Homes.Home h = v == null ? null : Homes.homeOf(village, member.getUUID());
        Families.Pet p = h == null ? null : Families.pet(village, h.anchor.asLong());
        if (p == null || !(level.getEntity(p.id()) instanceof TamableAnimal a)) return;
        WANT_LOOKED.remove(village + "/" + h.anchor.asLong());
        long dt = level.getDayTime();
        household(level, v, h, p, a, know(village, p, a, dt / 24000L), dt / 24000L, dt % 24000L);
    }

    /** Tests: the household's bowl, or null. */
    @Nullable
    public static BlockPos bowlForTests(ServerLevel level, VillageFolkEntity member) {
        UUID village = member.ownerId();
        Homes.Home h = village == null ? null : Homes.homeOf(village, member.getUUID());
        return h == null ? null : bowlOf(level, village, h);
    }

    /** Tests: the household's dog bed (or cat basket), or null. */
    @Nullable
    public static BlockPos bedForTests(ServerLevel level, VillageFolkEntity member, boolean basket) {
        UUID village = member.ownerId();
        Homes.Home h = village == null ? null : Homes.homeOf(village, member.getUUID());
        return h == null ? null : bedOf(level, village, h, basket);
    }

    /** Tests: the roof the household's cat suns itself on, or null. */
    @Nullable
    public static BlockPos roofForTests(ServerLevel level, VillageFolkEntity member) {
        UUID village = member.ownerId();
        Homes.Home h = village == null ? null : Homes.homeOf(village, member.getUUID());
        return h == null ? null : roofOf(level, village, h);
    }

    /** Tests: the pet has not eaten today (its last meal yesterday). */
    public static void hungryForTests(UUID village, UUID pet, long day) {
        Care c = care(village, pet);
        c.fedDay = day - 1;
        save(village, c);
    }

    /** Tests: the day the pet last ate. */
    public static long fedDayForTests(UUID village, UUID pet) {
        return care(village, pet).fedDay;
    }

    /** Tests: the pet's care, as the ledger keeps it. */
    public static String careForTests(UUID village, UUID pet) {
        return write(care(village, pet));
    }

    /** Tests: the pet's age in years and its birth day. */
    public static long[] ageForTests(UUID village, UUID pet, long day) {
        Care c = care(village, pet);
        return new long[]{ years(c, day), c.born };
    }

    /** Tests: the town's animals all told, and its cap. */
    public static int[] countForTests(UUID village) {
        return new int[]{ count(village), cap(village) };
    }

    /** Tests: the young, strays and merchant's animals, as "role:kind:uuid". */
    public static List<String> othersForTests(UUID village) {
        List<String> out = new ArrayList<>();
        for (Other o : others(village).values()) out.add(o.role() + ":" + kindWord(o.cat()) + ":" + o.animal());
        return out;
    }

    /** Tests: a litter now for the household at this anchor, with this mate (nothing if the town is at its cap). */
    public static List<TamableAnimal> litterForTests(ServerLevel level, UUID village, BlockPos anchor, TamableAnimal mate) {
        Villages.Village v = Villages.get(village);
        Homes.Home h = Homes.homes(village).get(anchor.asLong());
        Families.Pet p = h == null ? null : Families.pet(village, h.anchor.asLong());
        if (v == null || p == null || !(level.getEntity(p.id()) instanceof TamableAnimal a)) return List.of();
        long day = level.getDayTime() / 24000L;
        return litter(level, v, h, a, mate, know(village, p, a, day), day);
    }

    /** Tests: homes found for the town's young now. */
    public static int homesForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        return v == null ? 0 : homesForTheYoung(level, v, level.getDayTime() / 24000L);
    }

    /** Tests: the makers' list for the town's pets (item to how many wanted kept). */
    public static Map<Item, Integer> wantedForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        WANTS.remove(village);
        demandOnce();
        return v == null ? Map.of() : wanted(level, v);
    }

    /** Tests: a turn at the pets' things for this maker now (no waiting for its turn). What it made, or null. */
    @Nullable
    public static String craftForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        if (v == null) return null;
        WANTS.remove(v.id());
        return craftNow(level, v, f);
    }

    /** Tests: a player's click on an animal with what it holds: what happened, or null. */
    @Nullable
    public static String interactForTests(ServerLevel level, Player p, TamableAnimal a) {
        return interact(level, p, a, p.getItemInHand(InteractionHand.MAIN_HAND));
    }

    /** Tests: who a pet is following (a player), or null. */
    @Nullable
    public static UUID followingForTests(UUID pet) {
        Follow f = FOLLOWING.get(pet);
        return f == null ? null : f.player();
    }

    /** Tests: the pet show's lines for this town now (a stand-in for the fair's script), and the winner given its ribbon. */
    public static List<String> showForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        List<Assemblies.Line> s = new ArrayList<>();
        long d = level.getDayTime() / 24000L;
        show(level, v, s, level.getRandom(), d);
        for (Assemblies.Line l : s) {
            out.add(l.text());
            if (l.effect() != null) l.effect().run();
        }
        return out;
    }

    /** Tests: what this folk says the morning after its dog barked at night (once a morning), or null. */
    @Nullable
    public static String morningForTests(ServerLevel level, VillageFolkEntity f) {
        if (f.ownerId() == null) return null;
        long dt = level.getDayTime();
        return morning(level, f, f.ownerId(), Math.max(1000L, Math.min(4999L, dt % 24000L)), dt / 24000L, true);
    }

    /** Tests: a dog barks now at whatever monster is near it (night or not), and the watch is warned. What it is doing. */
    @Nullable
    public static String barkForTests(ServerLevel level, UUID village, TamableAnimal dog) {
        Care c = care(village, dog.getUUID());
        long day = level.getDayTime() / 24000L;
        return bark(level, village, dog, c, 13000L, day);
    }
}
