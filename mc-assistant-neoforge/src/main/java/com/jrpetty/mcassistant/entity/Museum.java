package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.goal.Blueprints;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.MuseumRecords;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The museum: where a town puts its rarest finds on show, and keeps its chronicle (Archive).
 *
 * <p><b>The finds.</b> Whatever a folk picks up out of the world (a miner's diamond, a fisher's
 * nautilus shell, a fossil dug out of a tunnel wall, a guard's trophy off a monster) is noted the
 * moment it is in its hands, who found it and at what trade, so that one day a label can say so. The
 * first of every rare kind is told in the chronicle ("Ember the miner found the town's first
 * diamond").
 *
 * <p><b>The building.</b> A town of twenty in the Iron Age with three different finds worth
 * showing plans a museum among its amenities, on a lot facing the square: a hall of dressed stone with
 * a skylight and a double door, up a stair on a plinth behind a portico of four columns under a low
 * pediment, built like any other building out of the stores; its name goes up over the door and the
 * town's banners either side (MuseumFront). A curator looks after
 * it: the folk with the most curiosity and learning in it (a curious nature, a love of reading, the
 * enchanter's trade), or else the eldest, chosen once and told in the chronicle.
 *
 * <p><b>What goes on show.</b> One of every kind: the first diamond, the first emerald, a fossil, a
 * music disc, an enchanted book, a trident, a nautilus shell, the heart of the sea, a totem, a saddle,
 * a rare fish, a monster's skull, chainmail, a rabbit's foot, a ghast's tear, a map of a scout's find,
 * the first iron of the Iron Age... Nothing comes from nowhere. The curator asks for what the stores
 * hold (from then on the stores keep it back from every maker), walks to the stores, takes it out with
 * whatever its place wants (a frame made of sticks and leather, a sign of planks for its label, glass
 * for a case, an armour stand, a jukebox, all made out of the stores by the game's own recipes, or
 * none of it and it waits), walks it over and sets it out: in a frame on the wall, on a stand, in a
 * glass case let into the floor, in the jukebox. Its label says what it is, who found it, at what
 * trade and on what day; so does its name, looked at. The finder is proud of it for days, and says
 * so; the town is the more renowned for it, the rarer the more (Villages.renown).
 *
 * <p><b>Visitors.</b> Folk look round of an evening, stopping before a thing and saying a word about
 * it (its finder most of all). A player is welcomed by the curator and can read every label; on the
 * day of rest the jukebox plays its disc. Something taken away is missed, and said so in the
 * chronicle.
 */
public final class Museum {

    private Museum() {}

    private static final Logger LOG = LogUtils.getLogger();

    /** A town plans a museum from this many folk (and the Iron Age)... */
    public static final int FROM_FOLK = 20;
    /** ...once it has this many different finds worth showing. */
    public static final int FINDS_FOR_A_MUSEUM = 3;
    /** The renown a great work brings: the museum's exhibits are worth one to eight each. */
    public static final int GREAT_WORK_RENOWN = 10;

    // ------------------------------------------------------------------ what is worth showing

    /** Where a thing goes on show. */
    public enum Show { FRAME, CASE, STAND, JUKEBOX }

    private static final Set<Item> HEADS = Set.of(Items.SKELETON_SKULL, Items.WITHER_SKELETON_SKULL, Items.ZOMBIE_HEAD,
        Items.CREEPER_HEAD, Items.PIGLIN_HEAD);
    private static final Set<Item> CHAINMAIL = Set.of(Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE,
        Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS);

    /**
     * A kind of thing worth showing: its label, what it is in words, the renown it brings, where it
     * goes on show, what it is in the stores, what counts as finding one, and whether it is only a
     * museum piece if somebody is known to have found it (bone blocks are made of bone meal too: only
     * one out of the ground is a fossil).
     */
    public enum Kind {
        DIAMOND("Diamond", "the town's first diamond", 3, Show.FRAME, s -> s.is(Items.DIAMOND),
            s -> s.is(Items.DIAMOND) || s.is(Items.DIAMOND_ORE) || s.is(Items.DEEPSLATE_DIAMOND_ORE), false),
        EMERALD("Emerald", "the town's first emerald", 2, Show.FRAME, s -> s.is(Items.EMERALD),
            s -> s.is(Items.EMERALD) || s.is(Items.EMERALD_ORE) || s.is(Items.DEEPSLATE_EMERALD_ORE), false),
        FOSSIL("Fossil", "a fossil, bones out of the deep rock", 3, Show.CASE, s -> s.is(Items.BONE_BLOCK), null, true),
        DISC("Music Disc", "a music disc", 3, Show.JUKEBOX, s -> s.has(DataComponents.JUKEBOX_PLAYABLE), null, false),
        BOOK("Enchanted Book", "an enchanted book", 2, Show.FRAME, s -> s.is(Items.ENCHANTED_BOOK), null, false),
        TRIDENT("Trident", "a trident out of the deep", 4, Show.STAND, s -> s.is(Items.TRIDENT), null, false),
        NAUTILUS("Nautilus Shell", "a nautilus shell", 2, Show.FRAME, s -> s.is(Items.NAUTILUS_SHELL), null, false),
        HEART("Heart of the Sea", "the heart of the sea", 5, Show.FRAME, s -> s.is(Items.HEART_OF_THE_SEA), null, false),
        TOTEM("Totem", "a totem of undying", 5, Show.FRAME, s -> s.is(Items.TOTEM_OF_UNDYING), null, false),
        SADDLE("Saddle", "a saddle", 2, Show.FRAME, s -> s.is(Items.SADDLE), null, false),
        FISH("Tropical Fish", "a rare fish from warm water", 1, Show.FRAME, s -> s.is(Items.TROPICAL_FISH), null, false),
        SKULL("Skull", "a monster's skull", 4, Show.STAND, s -> HEADS.contains(s.getItem()), null, false),
        CHAINMAIL("Chainmail", "a piece of chainmail", 3, Show.STAND, s -> Museum.CHAINMAIL.contains(s.getItem()), null, false),
        RABBIT_FOOT("Rabbit's Foot", "a lucky rabbit's foot", 1, Show.FRAME, s -> s.is(Items.RABBIT_FOOT), null, false),
        GHAST_TEAR("Ghast Tear", "a ghast's tear from the Nether", 2, Show.FRAME, s -> s.is(Items.GHAST_TEAR), null, false),
        AMETHYST("Amethyst", "amethyst out of a geode", 1, Show.FRAME, s -> s.is(Items.AMETHYST_SHARD),
            s -> s.is(Items.AMETHYST_SHARD) || s.is(Items.AMETHYST_CLUSTER), false),
        NAME_TAG("Name Tag", "a name tag", 1, Show.FRAME, s -> s.is(Items.NAME_TAG), null, false),
        GOLDEN_APPLE("Golden Apple", "an enchanted golden apple", 5, Show.FRAME, s -> s.is(Items.ENCHANTED_GOLDEN_APPLE), null, false),
        NETHER_STAR("Nether Star", "a nether star", 6, Show.FRAME, s -> s.is(Items.NETHER_STAR), null, false),
        ELYTRA("Elytra", "a pair of elytra", 6, Show.STAND, s -> s.is(Items.ELYTRA), null, false),
        DRAGON_EGG("Dragon Egg", "the dragon's egg", 8, Show.CASE, s -> s.is(Items.DRAGON_EGG), null, false),
        /** Drawn by the curator from the scouts' atlas: a map of one of their finds. Not out of the stores. */
        MAP("Old Map", "a map of a scout's find", 2, Show.FRAME, s -> s.is(Items.FILLED_MAP), s -> false, false),
        /** The first iron of the Iron Age: an ingot out of the stores, the label the miner's who dug the first ore. */
        IRON("First Iron", "the first iron of the Iron Age", 1, Show.FRAME, s -> s.is(Items.IRON_INGOT),
            s -> s.is(Items.RAW_IRON) || s.is(Items.IRON_ORE) || s.is(Items.DEEPSLATE_IRON_ORE), false);

        public final String label, words;
        public final int renown;
        public final Show show;
        final Predicate<ItemStack> is, found;
        final boolean needsFind;

        Kind(String label, String words, int renown, Show show, Predicate<ItemStack> is, @Nullable Predicate<ItemStack> found,
             boolean needsFind) {
            this.label = label;
            this.words = words;
            this.renown = renown;
            this.show = show;
            this.is = is;
            this.found = found == null ? is : found;
            this.needsFind = needsFind;
        }

        public String key() { return name().toLowerCase(Locale.ROOT); }

        /** Is this the thing to put on show (out of the stores)? */
        public boolean is(ItemStack s) { return !s.isEmpty() && is.test(s); }

        /** The kind a find is, or null if it is nothing worth showing. */
        @Nullable
        public static Kind found(ItemStack s) {
            if (s.isEmpty()) return null;
            for (Kind k : values()) if (k.found.test(s)) return k;
            return null;
        }

        /** The kind a thing in the stores is, if it would go on show. */
        @Nullable
        public static Kind inStores(ItemStack s) {
            if (s.isEmpty()) return null;
            for (Kind k : values()) if (k != MAP && k.is.test(s)) return k;
            return null;
        }

        @Nullable
        public static Kind byKey(String key) {
            for (Kind k : values()) if (k.key().equals(key)) return k;
            return null;
        }
    }

    // ------------------------------------------------------------------ the places

    /**
     * A place something goes on show, in the hall's terms (across, up from its floor, back from its middle;
     * see at() and blueprints/museum.txt), which way it faces, and where its label goes and which way that faces
     * (a wall sign, or a sign standing on the floor).
     */
    public record Place(Show show, int dx, int h, int dz, Blueprints.Way faces, int lx, int lh, int lz,
                        Blueprints.Way labelFaces, boolean standing) {}

    private static final Blueprints.Way RIGHT = Blueprints.Way.RIGHT, LEFT = Blueprints.Way.LEFT,
        FRONT = Blueprints.Way.FRONT, BACK = Blueprints.Way.BACK;

    /** Eight frames on the walls at eye height (a label under each), two cases let into the floor, two
     *  stands by the door, and the jukebox beside the lectern. */
    public static final List<Place> PLACES = List.of(
        new Place(Show.FRAME, -3, 1, 2, RIGHT, -3, 0, 2, RIGHT, false),
        new Place(Show.FRAME, 4, 1, 2, LEFT, 4, 0, 2, LEFT, false),
        new Place(Show.FRAME, -1, 1, 3, FRONT, -1, 0, 3, FRONT, false),
        new Place(Show.FRAME, 2, 1, 3, FRONT, 2, 0, 3, FRONT, false),
        new Place(Show.FRAME, -3, 1, 0, RIGHT, -3, 0, 0, RIGHT, false),
        new Place(Show.FRAME, 4, 1, 0, LEFT, 4, 0, 0, LEFT, false),
        new Place(Show.FRAME, -3, 1, -2, RIGHT, -3, 0, -2, RIGHT, false),
        new Place(Show.FRAME, 4, 1, -2, LEFT, 4, 0, -2, LEFT, false),
        new Place(Show.CASE, -2, -2, 0, FRONT, -2, 0, -1, FRONT, true),
        new Place(Show.CASE, 3, -2, 0, FRONT, 3, 0, -1, FRONT, true),
        new Place(Show.STAND, -3, 0, -3, RIGHT, -2, 1, -3, BACK, false),
        new Place(Show.STAND, 4, 0, -3, LEFT, 3, 1, -3, BACK, false),
        new Place(Show.JUKEBOX, 1, 0, 3, FRONT, 1, 1, 3, FRONT, false));

    /**
     * The hall stands a block up on its plinth and a block back behind its portico (blueprints/museum.txt,
     * drawn by tools/museum_design.py). Every place in this class and in Archive is in the hall's own terms
     * (across from the middle, up from its floor, back from its middle), and at() carries them onto the
     * drawing. A museum built to the first drawing (its hall on the ground, its door on the front row, no
     * portico) keeps its places where they always were: HALLS remembers which drawing each museum was
     * built to, by its anchor, once one door or the other is seen hanging.
     */
    static final int HALL_UP = 1, HALL_BACK = 1;
    private static final int[] GRAND = { HALL_UP, HALL_BACK }, FIRST = { 0, 0 };
    private static final Map<Long, int[]> HALLS = new ConcurrentHashMap<>();

    /** How far up and back this museum's hall stands from its anchor: the new drawing's, unless it is
     *  known to be built to the first. */
    private static int[] hall(Ledger.Building b) {
        return HALLS.getOrDefault(b.anchor().asLong(), GRAND);
    }

    /** Is this museum known to be built to the new drawing, portico and all? */
    static boolean grand(Ledger.Building b) {
        return HALLS.get(b.anchor().asLong()) == GRAND;
    }

    /** Which drawing this museum was built to, by where its door hangs; not settled until one does. */
    static void whichHall(ServerLevel level, Ledger.Building b) {
        long key = b.anchor().asLong();
        if (HALLS.containsKey(key)) return;
        BlockPos grandDoor = b.anchor().relative(b.facing(), HALL_BACK - 4).above(HALL_UP);
        BlockPos firstDoor = b.anchor().relative(b.facing(), -4);
        if (level.getBlockState(grandDoor).getBlock() instanceof DoorBlock) HALLS.put(key, GRAND);
        else if (level.getBlockState(firstDoor).getBlock() instanceof DoorBlock) HALLS.put(key, FIRST);
    }

    /** A spot in the museum, from the hall's across, up and back. */
    public static BlockPos at(Ledger.Building b, int dx, int h, int dz) {
        int[] o = hall(b);
        return b.anchor().relative(b.facing().getClockWise(), dx).relative(b.facing(), dz + o[1]).above(h + o[0]);
    }

    static Direction world(Ledger.Building b, Blueprints.Way way) {
        return Blueprints.world(way, b.facing());
    }

    /** Where something in this place stands. */
    static BlockPos cell(Ledger.Building b, Place p) {
        return at(b, p.dx(), p.h(), p.dz());
    }

    /** Where a visitor stands to look at it. */
    static BlockPos viewFrom(Ledger.Building b, Place p) {
        if (p.show() == Show.CASE) return at(b, p.dx(), 0, p.dz());                    // on the glass, looking down
        BlockPos at = at(b, p.dx(), 0, p.dz());
        return at.relative(world(b, p.faces()), 2);
    }

    /** The middle of what is on show, to look at. */
    static BlockPos lookAt(Ledger.Building b, Place p) {
        return cell(b, p);
    }

    /** The places this kind could go on show, best first. */
    static List<Show> showsFor(Kind k) {
        return switch (k.show) {
            case STAND -> k == Kind.SKULL ? List.of(Show.STAND, Show.CASE, Show.FRAME) : List.of(Show.STAND, Show.FRAME);
            case CASE -> List.of(Show.CASE, Show.FRAME);
            case JUKEBOX -> List.of(Show.JUKEBOX, Show.FRAME);
            case FRAME -> List.of(Show.FRAME);
        };
    }

    /** The village's museum, if it has one standing. */
    @Nullable
    public static Ledger.Building building(UUID village) {
        Ledger.Building found = null;
        for (Ledger.Building b : Ledger.buildings(village)) if (b.structure().equals("museum")) found = b;
        return found;
    }

    // ------------------------------------------------------------------ the finds

    /**
     * A folk has just picked this up out of the world (Economy.gathered): if it is a thing worth
     * showing, note who found it, at what trade and when; the first of a rare kind is told.
     */
    public static void found(VillageFolkEntity f, ItemStack s, int n) {
        if (n <= 0 || s.isEmpty() || f.isShowcase()) return;
        try {
            noteFound(f, s);
        } catch (RuntimeException ex) {
            LOG.warn("[MCA-MUSEUM] noting {}'s find: {}", f.displayNameCap(), ex.toString());
        }
    }

    private static void noteFound(VillageFolkEntity f, ItemStack s) {
        Kind k = Kind.found(s);
        if (k == null || k == Kind.MAP) return;
        UUID village = f.ownerId();
        if (village == null) return;
        long day = f.level().getDayTime() / 24000L;
        String trade = tradeWord(f);
        String how = how(f, k);
        boolean first = MuseumRecords.noteFind(village, k.key(), f.getUUID(), f.displayNameCap(), trade, how, day);
        if (!first || k.renown < 2) return;
        String who = f.displayNameCap() + (trade.isEmpty() ? "" : " the " + trade);
        String what = k.words.startsWith("the town's first") ? k.words : article(k) + ", the first the town has had";
        Villages.tell(village, day, who + " " + how + " " + what);
        f.persona().remember(day, "I " + how + " " + what, 4);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Look at this — " + article(k) + "!", "Well I never. " + capital(article(k)) + "!",
            "Wait till they see this at home!"));
    }

    /** The folk's trade in a word ("miner"), or "" for none. */
    static String tradeWord(VillageFolkEntity f) {
        AssistantEntity.StationTask t = f.stationTask();
        if (f.isBaby() || t == AssistantEntity.StationTask.NONE) return "";
        return t.title.toLowerCase(Locale.ROOT);
    }

    /** How it came by it: "mined", "fished up", "brought home", "found". */
    static String how(VillageFolkEntity f, Kind k) {
        return switch (f.stationTask()) {
            case MINE -> k == Kind.FOSSIL ? "dug out" : "mined";
            case FISH -> "fished up";
            case HUNT -> "brought home";
            case GUARD -> "won";
            default -> "found";
        };
    }

    /** What it is, shortly: "a diamond", "a fossil", "the heart of the sea". */
    static String article(Kind k) {
        String w = k.words.startsWith("the town's first ") ? k.words.substring("the town's first ".length()) : k.words;
        int comma = w.indexOf(',');
        if (comma > 0) w = w.substring(0, comma);
        if (w.startsWith("a ") || w.startsWith("an ") || w.startsWith("the ")) return w;
        return (w.matches("^[aeiou].*") ? "an " : "a ") + w;
    }

    // ------------------------------------------------------------------ the state of things

    /** The curator's errand: a find to set out, or a volume to write. */
    enum Job { SHOW, WRITE, COPY }

    static final class Errand {
        final UUID village, curator;
        final Job job;
        @Nullable final Kind kind;
        int place;
        final int year, volume;
        final long since;
        boolean fetched, started;
        int walkTick = -1000;
        /** The scouts' find a map is drawn of ("label|day"), for a map. */
        String atlasKey = "";
        /** What it is about, in words. */
        String words;
        ItemStack exhibit = ItemStack.EMPTY;
        final List<ItemStack> fittings = new ArrayList<>();

        Errand(UUID village, UUID curator, Job job, @Nullable Kind kind, int place, int year, int volume, long since, String words) {
            this.village = village;
            this.curator = curator;
            this.job = job;
            this.kind = kind;
            this.place = place;
            this.year = year;
            this.volume = volume;
            this.since = since;
            this.words = words;
        }
    }

    private static final Map<UUID, Errand> ERRANDS = new ConcurrentHashMap<>();
    /** The curator on an errand, and its village. */
    private static final Map<UUID, UUID> ON = new ConcurrentHashMap<>();
    /** What the curator has asked the stores for and they keep back, by village: kind, and when. */
    private static final Map<UUID, Map<String, Long>> ASKED = new ConcurrentHashMap<>();
    /** Things put off (no room, nothing to make its fitting of), by village: what, and until when. */
    private static final Map<UUID, Map<String, Long>> PUT_OFF = new ConcurrentHashMap<>();
    /** What the museum is short of just now, in words. */
    private static final Map<UUID, String> SHORT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> TICKED = new ConcurrentHashMap<>(), SCANNED = new ConcurrentHashMap<>(),
        VERIFIED = new ConcurrentHashMap<>(), RESTING = new ConcurrentHashMap<>(), CURATOR_MISSING = new ConcurrentHashMap<>();
    /** The kinds the stores hold that are not on show yet, by village (looked at once a minute). */
    private static final Map<UUID, List<Kind>> IN_STORES = new ConcurrentHashMap<>();
    /** The museums whose double door has been hung as one. */
    private static final Set<Long> DOORS_HUNG = ConcurrentHashMap.newKeySet();
    /** Players welcomed, by village and player, on what day. */
    private static final Map<String, Long> WELCOMED = new ConcurrentHashMap<>();
    /** The game tests' errands done at once, with no walking (as TownJobs' are). */
    private static volatile boolean instant;

    public static void resetForTests() {
        ERRANDS.clear();
        ON.clear();
        ASKED.clear();
        PUT_OFF.clear();
        SHORT.clear();
        TICKED.clear();
        SCANNED.clear();
        VERIFIED.clear();
        RESTING.clear();
        CURATOR_MISSING.clear();
        IN_STORES.clear();
        DOORS_HUNG.clear();
        HALLS.clear();
        MuseumFront.resetForTests();
        WELCOMED.clear();
        VISITS.clear();
        VISITED.clear();
    }

    /** Tests: the curator's errands done at once (true), or on foot (false, as in a real game). */
    public static void instantForTests(boolean on) {
        instant = on;
    }

    /** Tests: the museum's beat, now. */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        TICKED.remove(v.id());
        SCANNED.remove(v.id());
        VERIFIED.remove(v.id());
        RESTING.remove(v.id());
        tick(level, v);
    }

    /** Tests: this folk the curator. */
    public static void appointForTests(UUID village, VillageFolkEntity f) {
        MuseumRecords.Book book = MuseumRecords.book(village);
        book.curator = f.getUUID();
        book.curatorName = f.displayNameCap();
        book.curatorSince = f.level().getDayTime() / 24000L;
        MuseumRecords.touch();
    }

    /** What the curator is about for the museum just now, in words, or null. */
    @Nullable
    public static String doing(UUID village) {
        Errand e = ERRANDS.get(village);
        return e == null ? null : e.words + (e.fetched ? " (carrying it)" : "");
    }

    /** Is this kind on show? */
    public static boolean onShow(UUID village, String kind) {
        for (MuseumRecords.Shown s : MuseumRecords.book(village).shown) if (s.kind.equals(kind)) return true;
        return false;
    }

    /** What is on show. */
    public static List<MuseumRecords.Shown> exhibits(UUID village) {
        return new ArrayList<>(MuseumRecords.book(village).shown);
    }

    /** The renown the museum's collection brings the town: each thing on show, the rarer the more. */
    public static int renown(UUID village) {
        if (!MuseumRecords.has(village)) return 0;
        int n = 0;
        for (MuseumRecords.Shown s : MuseumRecords.book(village).shown) n += s.renown;
        return n;
    }

    /**
     * Has the town got enough worth showing to want a museum: so many different rare things, found by
     * its folk or lying in its stores? (Villages, when it draws up what to build.)
     */
    public static boolean worthAMuseum(UUID village) {
        Set<String> kinds = new HashSet<>();
        if (MuseumRecords.has(village)) {
            for (MuseumRecords.Found f : MuseumRecords.book(village).finds) {
                Kind k = Kind.byKey(f.kind);
                if (k != null && k.renown >= 2) kinds.add(f.kind);
            }
        }
        for (Kind k : IN_STORES.getOrDefault(village, List.of())) if (k.renown >= 2) kinds.add(k.key());
        return kinds.size() >= FINDS_FOR_A_MUSEUM;
    }

    /** Something the stores keep back from makers. */
    public interface KeepSink {
        void keep(Predicate<ItemStack> what, int n, String why);
    }

    /** What the curator has asked for and the stores keep back for it, till it is fetched (Bench). */
    public static void keptBack(UUID village, KeepSink sink) {
        Map<String, Long> asked = ASKED.get(village);
        if (asked == null || asked.isEmpty()) return;
        for (String key : asked.keySet()) {
            Kind k = Kind.byKey(key);
            if (k != null && k != Kind.MAP) sink.keep(k.is, 1, "kept for the museum");
        }
    }

    // ------------------------------------------------------------------ the museum's beat

    /** The museum's beat: run from the folk's own (a few times a minute for the whole village). */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = TICKED.get(id);
        if (!instant && last != null && now - last < 100 && now >= last) return;
        TICKED.put(id, now);
        try {
            beat(level, v, now);
        } catch (RuntimeException ex) {
            LOG.warn("[MCA-MUSEUM] {}: {}", Villages.name(id), ex.toString());
        }
    }

    private static void beat(ServerLevel level, Villages.Village v, long now) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Archive.noteYears(id, day);
        Long scanned = SCANNED.get(id);
        if (scanned == null || now - scanned >= 1200 || now < scanned) {
            SCANNED.put(id, now);
            scan(level, v);
        }
        Ledger.Building b = building(id);
        if (b == null || !level.isLoaded(b.anchor())) return;
        whichHall(level, b);
        MuseumRecords.Book book = MuseumRecords.book(id);
        Errand e = ERRANDS.get(id);
        if (e == null && !book.carrying.isEmpty()) putBack(level, v, book);       // what a curator had in hand when the world stopped
        hangTheDoors(level, b);
        VillageFolkEntity curator = curator(level, v, book, day, now);
        Long verified = VERIFIED.get(id);
        if (verified == null || now - verified >= 1200 || now < verified) {
            VERIFIED.put(id, now);
            verify(level, v, b, book, day);
        }
        music(level, v, b, book, day);
        welcome(level, v, b, curator, day);
        if (e != null && (curator == null || !e.curator.equals(curator.getUUID()) || now - e.since > 9600L || now < e.since)) {
            abandon(level, v, e, curator == null ? "no curator" : "it took too long");
            e = null;
        }
        if (e == null && curator != null && (instant || workTime(level, id))) {
            Long rest = RESTING.get(id);
            if (instant || rest == null || now >= rest) e = next(level, v, b, book, curator, day, now);
        }
        if (e != null && instant) atOnce(level, v, b, e, curator);
        // The front: the museum's name over its door and the town's banners either side, out of the stores
        // (MuseumFront). Not in the game tests' instant museum, whose stores are counted to the item.
        if (!instant) MuseumFront.tick(level, v, b, curator, now);
    }

    /** The curator's hours: by day, not on the day of rest, not with the bell ringing. */
    static boolean workTime(ServerLevel level, UUID id) {
        long t = level.getDayTime() % 24000L;
        if (t < 1000L || t >= 11500L) return false;
        if (RestDay.now(id, level.getDayTime()) != null || Raids.underAlarm(id)) return false;
        return true;
    }

    /** What the stores hold that would go on show and is not on show yet. */
    private static void scan(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        boolean iron = Villages.ageOf(id).ordinal() >= Villages.Age.IRON.ordinal();
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (!(level.getBlockEntity(p) instanceof net.minecraft.world.Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                Kind k = Kind.inStores(c.getItem(i));
                if (k == null || (k == Kind.IRON && !iron)) continue;
                if (k.needsFind && MuseumRecords.firstFind(id, k.key()) == null) continue;
                kinds.add(k);
            }
        }
        List<Kind> out = new ArrayList<>();
        for (Kind k : kinds) if (!onShow(id, k.key())) out.add(k);
        IN_STORES.put(id, out);
    }

    // ------------------------------------------------------------------ the curator

    /** The museum's curator: loaded, or null; chosen if there is none (or it is gone for good). */
    @Nullable
    static VillageFolkEntity curator(ServerLevel level, Villages.Village v, MuseumRecords.Book book, long day, long now) {
        UUID id = v.id();
        if (book.curator != null) {
            if (level.getEntity(book.curator) instanceof VillageFolkEntity f && f.isAlive() && id.equals(f.ownerId())) {
                CURATOR_MISSING.remove(id);
                return f;
            }
            // Not to be seen: away, or asleep out of reach, or gone. Gone for good after a few minutes of it.
            for (AssistantEntity a : Villages.folkOf(id)) if (a.getUUID().equals(book.curator)) return null;
            Long since = CURATOR_MISSING.putIfAbsent(id, now);
            if (since == null || (now - since < 6000L && now >= since && !instant)) return null;
        }
        VillageFolkEntity pick = chooseCurator(id);
        if (pick == null) return null;
        String was = book.curatorName;
        book.curator = pick.getUUID();
        book.curatorName = pick.displayNameCap();
        book.curatorSince = day;
        MuseumRecords.touch();
        CURATOR_MISSING.remove(id);
        Villages.tell(id, day, pick.displayNameCap() + " was made curator of the museum"
            + (was.isEmpty() || was.equals(pick.displayNameCap()) ? "" : ", in " + was + "'s place"));
        pick.persona().remember(day, "I was made curator of the museum", 5);
        FolkTalk.speak(pick, FolkTalk.pick(pick.getRandom(), "Curator of the museum! I'll look after it as if it were my own.",
            "Me, the curator? I'll do it proud."));
        LOG.info("[MCA-MUSEUM] {}: {} is the curator", Villages.name(id), pick.displayNameCap());
        return pick;
    }

    /** The folk with the most curiosity and learning (a curious nature, a reader, the enchanter), else the eldest. */
    @Nullable
    static VillageFolkEntity chooseCurator(UUID village) {
        VillageFolkEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity f) || !f.isAlive() || f.isBaby() || f.isShowcase() || f.isHired()) continue;
            double s = f.ageYears() * 0.5;
            if (f.life().rolled() && f.life().has(Social.Trait.CURIOUS)) s += 30;
            if (f.persona().rolled() && f.persona().hobby() == Persona.Hobby.READING) s += 30;
            if (f.stationTask() == AssistantEntity.StationTask.ENCHANT) s += 15;
            if (f.stationTask() == AssistantEntity.StationTask.GUARD || f.stationTask() == AssistantEntity.StationTask.SCOUT) s -= 40;
            if (f.isElder()) s -= 15;
            if (s > bestScore) { bestScore = s; best = f; }
        }
        return best;
    }

    /** Is this folk the museum's curator? */
    public static boolean isCurator(VillageFolkEntity f) {
        UUID id = f.ownerId();
        return id != null && MuseumRecords.has(id) && f.getUUID().equals(MuseumRecords.book(id).curator) && building(id) != null;
    }

    // ------------------------------------------------------------------ the next piece of work

    @Nullable
    private static Errand next(ServerLevel level, Villages.Village v, Ledger.Building b, MuseumRecords.Book book,
                               VillageFolkEntity curator, long day, long now) {
        UUID id = v.id();
        Bench.Hand hand = Bench.handOf(level, v, curator, "museum");
        // A year of the chronicle waiting to be bound.
        MuseumRecords.Notes notes = Archive.waiting(book);
        if (notes != null && !putOff(id, "write", now)) {
            if (Archive.full(level, b)) {
                shortOf(id, "room in the archive: every shelf is full");
            } else {
                int parts = Archive.layout(Villages.name(id), notes, curator.displayNameCap(), Villages.elderName(id), day).size();
                Bench.Plan plan = Bench.plan(level, v, bookWants(level, b, parts), hand);
                if (plan.ok()) {
                    return begin(new Errand(id, curator.getUUID(), Job.WRITE, null, -1, notes.year, -1, now,
                        "writing up Year " + notes.year + " of the chronicle"), curator,
                        FolkTalk.pick(curator.getRandom(), "Year " + notes.year + " wants writing up for the archive.",
                            "Off to the stores for a book and quill — the chronicle won't write itself."));
                }
                shortOf(id, plan.shortOf + " for a book and quill (Year " + notes.year + ")");
                putOff(id, "write", now, 2400L);
            }
        }
        // A volume taken from the archive, to write out again.
        for (int i = 0; i < book.volumes.size(); i++) {
            MuseumRecords.Volume vol = book.volumes.get(i);
            if (!vol.missing || vol.pages.isEmpty() || putOff(id, "copy", now)) continue;
            Bench.Plan plan = Bench.plan(level, v, bookWants(level, b, 1), hand);
            if (plan.ok()) {
                return begin(new Errand(id, curator.getUUID(), Job.COPY, null, -1, vol.year, i, now,
                    "writing out " + vol.title + " again"), curator, "Somebody's walked off with " + vol.title + ". I'll write it out again.");
            }
            shortOf(id, plan.shortOf + " to write out " + vol.title + " again");
            putOff(id, "copy", now, 2400L);
            break;
        }
        // Something to put on show: the rarest the stores hold.
        List<Kind> wanted = new ArrayList<>(IN_STORES.getOrDefault(id, List.of()));
        if (Villages.ageOf(id).ordinal() >= Villages.Age.IRON.ordinal() && !onShow(id, Kind.MAP.key()) && mapToDraw(id, book) != null) {
            wanted.add(Kind.MAP);
        }
        wanted.sort((a, c) -> Integer.compare(c.renown, a.renown));
        for (Kind k : wanted) {
            if (onShow(id, k.key()) || putOff(id, k.key(), now)) continue;
            if (k != Kind.MAP && Market.stock(level, id, k.is) <= 0) continue;
            int place = freePlace(level, b, book, k);
            if (place < 0) {
                shortOf(id, "room: every place for " + article(k) + " is taken");
                putOff(id, k.key(), now, 6000L);
                continue;
            }
            Bench.Plan plan = Bench.plan(level, v, fittingWants(level, b, PLACES.get(place), k), hand);
            if (!plan.ok()) {
                shortOf(id, plan.shortOf + " to put " + article(k) + " on show" + (plan.why.isEmpty() ? "" : " (" + plan.why + ")"));
                putOff(id, k.key(), now, 2400L);
                continue;
            }
            Errand e = new Errand(id, curator.getUUID(), Job.SHOW, k, place, -1, -1, now,
                k == Kind.MAP ? "drawing a map of the scouts' find for the museum" : "fetching " + article(k) + " for the museum");
            if (k == Kind.MAP) {
                Scouts.Find f = mapToDraw(id, book);
                e.atlasKey = f == null ? "" : mapKey(f);
            } else {
                ASKED.computeIfAbsent(id, x -> new ConcurrentHashMap<>()).put(k.key(), day);
            }
            return begin(e, curator, k == Kind.MAP ? "The scouts' find wants a map drawing, for the museum."
                : FolkTalk.pick(curator.getRandom(), "We'll have " + article(k) + " for the museum, if the stores can spare it!",
                    "There's " + article(k) + " in the stores. That belongs on show."));
        }
        return null;
    }

    private static Errand begin(Errand e, VillageFolkEntity curator, String line) {
        ERRANDS.put(e.village, e);
        ON.put(e.curator, e.village);
        SHORT.remove(e.village);
        if (!instant) FolkTalk.speak(curator, line);
        curator.brain("the museum: " + e.words);
        LOG.info("[MCA-MUSEUM] {}: {} — {}", Villages.name(e.village), curator.displayNameCap(), e.words);
        return e;
    }

    private static boolean putOff(UUID id, String what, long now) {
        Map<String, Long> m = PUT_OFF.get(id);
        Long until = m == null ? null : m.get(what);
        return until != null && now < until && !instant;
    }

    private static void putOff(UUID id, String what, long now, long ticks) {
        PUT_OFF.computeIfAbsent(id, x -> new ConcurrentHashMap<>()).put(what, now + ticks);
    }

    private static void shortOf(UUID id, String words) {
        SHORT.put(id, words);
    }

    /** What a volume wants out of the stores: a book and quill for each book of it, a lectern if none
     *  stands, and a shelf if there is no room for it on the shelves there are. */
    private static List<Bench.Want> bookWants(ServerLevel level, Ledger.Building b, int books) {
        List<Bench.Want> w = new ArrayList<>();
        w.add(Bench.Want.of(Items.WRITABLE_BOOK, Math.max(1, books)));
        if (!Archive.lecternStands(level, b)) w.add(Bench.Want.of(Items.LECTERN, 1));
        else if (Archive.wantsAShelf(level, b) && Archive.shelfToSet(level, b) >= 0) w.add(Bench.Want.of(Items.CHISELED_BOOKSHELF, 1));
        return w;
    }

    private static final List<Item> SIGNS = List.of(Items.OAK_SIGN, Items.SPRUCE_SIGN, Items.BIRCH_SIGN, Items.JUNGLE_SIGN,
        Items.ACACIA_SIGN, Items.DARK_OAK_SIGN, Items.MANGROVE_SIGN, Items.CHERRY_SIGN, Items.BAMBOO_SIGN);

    /** What putting this kind in this place wants out of the stores that is not there already. */
    private static List<Bench.Want> fittingWants(ServerLevel level, Ledger.Building b, Place p, Kind k) {
        List<Bench.Want> w = new ArrayList<>();
        switch (p.show()) {
            case FRAME -> { if (frameAt(level, b, p) == null) w.add(Bench.Want.of(Items.ITEM_FRAME, 1)); }
            case STAND -> { if (standAt(level, b, p) == null) w.add(Bench.Want.of(Items.ARMOR_STAND, 1)); }
            case CASE -> { if (!level.getBlockState(at(b, p.dx(), -1, p.dz())).is(Blocks.GLASS)) w.add(Bench.Want.of(Items.GLASS, 1)); }
            case JUKEBOX -> { if (!(level.getBlockState(cell(b, p)).getBlock() instanceof JukeboxBlock)) w.add(Bench.Want.of(Items.JUKEBOX, 1)); }
        }
        if (!(level.getBlockEntity(at(b, p.lx(), p.lh(), p.lz())) instanceof SignBlockEntity)) {
            w.add(new Bench.Want(s -> s.is(ItemTags.SIGNS), SIGNS, 1, "a sign"));
        }
        if (k == Kind.MAP) w.add(Bench.Want.of(Items.MAP, 1));
        return w;
    }

    /** The first free place this kind could go, or -1. */
    static int freePlace(ServerLevel level, Ledger.Building b, MuseumRecords.Book book, Kind k) {
        Set<Integer> taken = new HashSet<>();
        for (MuseumRecords.Shown s : book.shown) taken.add(s.place);
        boolean block = k == Kind.FOSSIL || k == Kind.SKULL || k == Kind.DRAGON_EGG;
        for (Show show : showsFor(k)) {
            if (show == Show.CASE && !block) continue;
            for (int i = 0; i < PLACES.size(); i++) {
                Place p = PLACES.get(i);
                if (p.show() != show || taken.contains(i) || !placeOpen(level, b, p)) continue;
                return i;
            }
        }
        return -1;
    }

    /** Could something go in this place: its own spot clear (or holding its own fitting), and its label's? */
    static boolean placeOpen(ServerLevel level, Ledger.Building b, Place p) {
        BlockPos c = cell(b, p);
        boolean spot = switch (p.show()) {
            case FRAME -> frameAt(level, b, p) != null
                || (level.getBlockState(c).canBeReplaced() && level.getBlockState(c.relative(world(b, p.faces()).getOpposite())).isSolid());
            case STAND -> standAt(level, b, p) != null || (level.getBlockState(c).canBeReplaced() && level.getBlockState(c.above()).canBeReplaced());
            case CASE -> {
                BlockState glass = level.getBlockState(at(b, p.dx(), -1, p.dz()));
                yield glass.is(Blocks.GLASS) || glass.canBeReplaced() || glass.isSolid();
            }
            case JUKEBOX -> level.getBlockState(c).getBlock() instanceof JukeboxBlock && level.getBlockEntity(c) instanceof JukeboxBlockEntity j
                && j.getTheItem().isEmpty() || level.getBlockState(c).canBeReplaced();
        };
        if (!spot) return false;
        BlockPos l = at(b, p.lx(), p.lh(), p.lz());
        return level.getBlockEntity(l) instanceof SignBlockEntity || level.getBlockState(l).canBeReplaced();
    }

    @Nullable
    static ItemFrame frameAt(ServerLevel level, Ledger.Building b, Place p) {
        Direction d = world(b, p.faces());
        for (ItemFrame f : level.getEntitiesOfClass(ItemFrame.class, new AABB(cell(b, p)), x -> x.isAlive() && x.getDirection() == d)) return f;
        return null;
    }

    @Nullable
    static ArmorStand standAt(ServerLevel level, Ledger.Building b, Place p) {
        for (ArmorStand s : level.getEntitiesOfClass(ArmorStand.class, new AABB(cell(b, p)).inflate(0.2), ArmorStand::isAlive)) return s;
        return null;
    }

    // ------------------------------------------------------------------ the curator's errand, on foot

    /**
     * From the folk's tick: the curator on the museum's errand goes to the stores, takes out what is
     * wanted, carries it to the museum and sets it out. True while it is busy with it (its own trade
     * waits).
     */
    public static boolean hold(VillageFolkEntity f, ServerLevel level) {
        UUID vid = ON.get(f.getUUID());
        if (vid == null) return false;
        Errand e = ERRANDS.get(vid);
        if (e == null || !e.curator.equals(f.getUUID())) {
            ON.remove(f.getUUID());
            return false;
        }
        if (instant || !free(f) || !workTime(level, vid)) return false;
        // Leading a build: the build first, unless the museum has waited a good while on it.
        if (Villages.holdsTheLead(vid, f.getUUID(), level.getGameTime()) && level.getGameTime() - e.since < 2400L) return false;
        Villages.Village v = Villages.get(vid);
        Ledger.Building b = building(vid);
        if (v == null || b == null) {
            if (v != null) abandon(level, v, e, "the museum is gone");
            return false;
        }
        try {
            if (!e.started) {
                e.started = true;
                f.clearQueue();                                   // its own work waits (as for the town's: TownJobs)
            }
            BlockPos to = e.fetched ? target(b, e) : storesSpot(level, v);
            if (near(f, to, e.fetched ? 2 : 4)) {
                f.getNavigation().stop();
                f.getLookControl().setLookAt(to.getX() + 0.5, to.getY() + 0.5, to.getZ() + 0.5);
                if (!e.fetched) {
                    if (!fetch(level, v, b, e, f)) {
                        abandon(level, v, e, "the stores could not run to it");
                        return false;
                    }
                    e.fetched = true;
                    f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                } else {
                    finish(level, v, b, e, f);
                    return true;
                }
            } else if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 60) {
                f.walkTo(to, 0.9D);
                e.walkTick = f.tickCount;
            }
            f.hobbyNow = e.words;
            f.lastLeisureTick = f.tickCount;
            if (e.fetched) carry(f, e);
            return true;
        } catch (RuntimeException ex) {
            LOG.warn("[MCA-MUSEUM] {}'s errand: {}", f.displayNameCap(), ex.toString());
            abandon(level, v, e, "it went wrong");
            return false;
        }
    }

    /** Free to go on the museum's errand: awake, unhurried, not away, not talking to anybody, not at a gathering
     *  or on the town's work, not on the watch. */
    private static boolean free(VillageFolkEntity f) {
        if (!f.isAlive() || f.isBaby() || f.isSleeping() || f.isHired() || f.getTarget() != null) return false;
        if (f.trip() != null || f.expedition() != null || Nether.away(f) || Drover.busy(f)) return false;
        if (f.talkPartner() != null || f.companionPlayer() != null || f.guidePlayer() != null) return false;
        if (Assemblies.attending(f) || Patrols.escorting(f) || TownJobs.busy(f)) return false;
        return !f.onWatch();
    }

    private static boolean near(VillageFolkEntity f, BlockPos to, int r) {
        BlockPos at = f.blockPosition();
        return Math.max(Math.abs(at.getX() - to.getX()), Math.abs(at.getZ() - to.getZ())) <= r && Math.abs(at.getY() - to.getY()) <= 3;
    }

    /** Where it stands to use the stores. */
    static BlockPos storesSpot(ServerLevel level, Villages.Village v) {
        BlockPos s = Storehouses.standingSpot(level, v.id());
        if (s != null) return s;
        List<BlockPos> chests = Villages.storeChests(level, v.id());
        return chests.isEmpty() ? v.centre() : chests.get(0);
    }

    /** Where it goes in the museum with what it carries. */
    static BlockPos target(Ledger.Building b, Errand e) {
        if (e.job == Job.SHOW && e.place >= 0) return viewFrom(b, PLACES.get(e.place));
        return at(b, Archive.LECTERN[0], 0, Archive.LECTERN[2] - 2);
    }

    /** What it has in hand, held up for show as it walks (a copy, for show only, never dropped: Leisure). */
    private static void carry(VillageFolkEntity f, Errand e) {
        ItemStack shown = e.job == Job.SHOW ? e.exhibit : e.fittings.isEmpty() ? ItemStack.EMPTY : e.fittings.get(0);
        if (shown.isEmpty() || !f.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty()) return;
        ItemStack prop = shown.copyWithCount(1);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("mca_prop", true);
        prop.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
        f.setItemSlot(EquipmentSlot.OFFHAND, prop);
        f.propInHand = true;
    }

    /** The errand done at once (the game tests, and /village museum work). */
    private static void atOnce(ServerLevel level, Villages.Village v, Ledger.Building b, Errand e, @Nullable VillageFolkEntity curator) {
        if (!e.fetched) {
            if (!fetch(level, v, b, e, curator)) {
                abandon(level, v, e, "the stores could not run to it");
                return;
            }
            e.fetched = true;
        }
        finish(level, v, b, e, curator);
    }

    /** /village museum work: the curator's next piece of work, done now. Returns what it did, in words. */
    public static String workNow(ServerLevel level, Villages.Village v) {
        boolean was = instant;
        instant = true;
        try {
            int before = renown(v.id()), volumes = MuseumRecords.book(v.id()).volumes.size();
            tickForTests(level, v);
            int after = renown(v.id()), now = MuseumRecords.book(v.id()).volumes.size();
            if (after != before) return "on show now, renown " + before + " -> " + after;
            if (now != volumes) return "a volume bound; the archive holds " + now;
            String s = SHORT.get(v.id());
            return s == null ? "nothing to do" : "short of " + s;
        } finally {
            instant = was;
        }
    }

    /** At the stores: what the errand wants, out of them. False (and nothing taken) if they cannot run to it now. */
    private static boolean fetch(ServerLevel level, Villages.Village v, Ledger.Building b, Errand e, @Nullable VillageFolkEntity f) {
        UUID id = v.id();
        Bench.Hand hand = Bench.handOf(level, v, f, "museum");
        MuseumRecords.Book book = MuseumRecords.book(id);
        if (e.job == Job.SHOW && e.kind != null) {
            Kind k = e.kind;
            Place p = PLACES.get(e.place);
            Bench.Plan plan = Bench.plan(level, v, fittingWants(level, b, p, k), hand);
            if (!plan.ok()) {
                shortOf(id, plan.shortOf + " to put " + article(k) + " on show");
                return false;
            }
            ItemStack thing = ItemStack.EMPTY;
            if (k != Kind.MAP) {
                thing = Crafts.takeOne(level, v, k.is);
                if (thing.isEmpty()) return false;
            }
            if (!Bench.take(level, v, plan, f)) {
                if (!thing.isEmpty()) Crafts.store(level, v, thing);
                return false;
            }
            e.fittings.addAll(made(plan, fittingWants(level, b, p, k)));
            if (k == Kind.MAP) {
                thing = drawMap(level, v, e);
                e.fittings.removeIf(s -> s.is(Items.MAP));
                if (thing.isEmpty()) {
                    for (ItemStack s : e.fittings) Crafts.store(level, v, s);
                    Crafts.store(level, v, new ItemStack(Items.MAP));
                    e.fittings.clear();
                    return false;
                }
            }
            e.exhibit = thing;
            Map<String, Long> asked = ASKED.get(id);
            if (asked != null) asked.remove(k.key());
        } else {
            int books = 1;
            if (e.job == Job.WRITE) {
                MuseumRecords.Notes n = Archive.waiting(book);
                if (n == null || n.year != e.year) return false;
                books = Archive.layout(Villages.name(id), n, f == null ? book.curatorName : f.displayNameCap(), Villages.elderName(id),
                    level.getDayTime() / 24000L).size();
            }
            List<Bench.Want> wants = bookWants(level, b, books);
            Bench.Plan plan = Bench.plan(level, v, wants, hand);
            if (!plan.ok() || !Bench.take(level, v, plan, f)) {
                shortOf(id, (plan.ok() ? "the makings, the stores changed" : plan.shortOf) + " for a book and quill");
                return false;
            }
            e.fittings.addAll(made(plan, wants));
        }
        holding(level, book, e);
        return true;
    }

    /** The things a plan made and took for the wants, in hand: a sign of whatever wood went into it. */
    private static List<ItemStack> made(Bench.Plan plan, List<Bench.Want> wants) {
        List<ItemStack> out = new ArrayList<>();
        for (Bench.Want w : wants) {
            Item it = w.kinds().get(0);
            if (w.kinds().size() > 1) {
                Item chosen = null;
                for (Item t : plan.takes.keySet()) if (w.kinds().contains(t)) { chosen = t; break; }
                if (chosen == null) for (Bench.Step s : plan.steps) if (w.kinds().contains(s.made())) { chosen = s.made(); break; }
                if (chosen != null) it = chosen;
            }
            for (int n = 0; n < w.count(); n++) out.add(new ItemStack(it));
        }
        return out;
    }

    /** What it carries, written down in case the world stops before it is set out. */
    private static void holding(ServerLevel level, MuseumRecords.Book book, Errand e) {
        book.carrying.clear();
        if (!e.exhibit.isEmpty()) book.carrying.add(save(level, e.exhibit));
        for (ItemStack s : e.fittings) if (!s.isEmpty()) book.carrying.add(save(level, s));
        MuseumRecords.touch();
    }

    private static CompoundTag save(ServerLevel level, ItemStack s) {
        return (CompoundTag) s.save(level.registryAccess());
    }

    /** Whatever was in hand when the world stopped, back into the stores. */
    private static void putBack(ServerLevel level, Villages.Village v, MuseumRecords.Book book) {
        for (CompoundTag t : book.carrying) {
            ItemStack s = ItemStack.parseOptional(level.registryAccess(), t);
            if (!s.isEmpty()) Crafts.store(level, v, s);
        }
        book.carrying.clear();
        MuseumRecords.touch();
    }

    /** The errand given up: everything in hand back into the stores. */
    private static void abandon(ServerLevel level, Villages.Village v, Errand e, String why) {
        if (!e.exhibit.isEmpty()) Crafts.store(level, v, e.exhibit);
        for (ItemStack s : e.fittings) if (!s.isEmpty()) Crafts.store(level, v, s);
        e.exhibit = ItemStack.EMPTY;
        e.fittings.clear();
        MuseumRecords.Book book = MuseumRecords.book(v.id());
        book.carrying.clear();
        MuseumRecords.touch();
        ERRANDS.remove(v.id(), e);
        ON.remove(e.curator);
        if (e.kind != null) {
            Map<String, Long> asked = ASKED.get(v.id());
            if (asked != null) asked.remove(e.kind.key());
            putOff(v.id(), e.kind.key(), level.getGameTime(), 2400L);
        }
        RESTING.put(v.id(), level.getGameTime() + 1200L);
        LOG.info("[MCA-MUSEUM] {}: {} given up: {}", Villages.name(v.id()), e.words, why);
    }

    private static void done(ServerLevel level, Villages.Village v, Errand e) {
        ERRANDS.remove(v.id(), e);
        ON.remove(e.curator);
        MuseumRecords.book(v.id()).carrying.clear();
        MuseumRecords.touch();
        RESTING.put(v.id(), level.getGameTime() + 1200L);
    }

    /** In the museum with it: set it out, or bind the volume. */
    private static void finish(ServerLevel level, Villages.Village v, Ledger.Building b, Errand e, @Nullable VillageFolkEntity f) {
        long day = level.getDayTime() / 24000L;
        if (e.job == Job.SHOW) {
            if (!setOut(level, v, b, e, f, day)) {
                // The place was taken or blocked while it walked: another, else everything back.
                int other = e.kind == null ? -1 : freePlace(level, b, MuseumRecords.book(v.id()), e.kind);
                if (other >= 0 && other != e.place) {
                    e.place = other;
                    return;                                      // it walks on to the other place
                }
                abandon(level, v, e, "nowhere to put it");
                return;
            }
        } else if (e.job == Job.WRITE) {
            if (!bind(level, v, b, e, f, day)) {
                abandon(level, v, e, "no room for the volume");
                return;
            }
        } else if (!copy(level, v, b, e, f, day)) {
            abandon(level, v, e, "no room for the copy");
            return;
        }
        done(level, v, e);
        if (f != null && Leisure.isProp(f.getItemBySlot(EquipmentSlot.OFFHAND))) {
            f.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);             // what it carried is set out
            f.propInHand = false;
        }
    }

    // ------------------------------------------------------------------ setting it out

    /** The thing set out in its place with its fittings, its label written, the town told. */
    private static boolean setOut(ServerLevel level, Villages.Village v, Ledger.Building b, Errand e, @Nullable VillageFolkEntity f, long day) {
        Kind k = e.kind;
        if (k == null || e.exhibit.isEmpty() || e.place < 0) return false;
        Place p = PLACES.get(e.place);
        if (!placeOpen(level, b, p)) return false;
        UUID id = v.id();
        MuseumRecords.Shown s = new MuseumRecords.Shown();
        s.kind = k.key();
        s.item = BuiltInRegistries.ITEM.getKey(e.exhibit.getItem()).toString();
        s.label = labelFor(k, e.exhibit, e);
        s.words = k.words;
        s.renown = k.renown;
        s.place = e.place;
        s.shown = day;
        credit(level, id, k, s, e);
        ItemStack thing = e.exhibit.copyWithCount(1);
        thing.set(DataComponents.CUSTOM_NAME, Component.literal(capital(s.words) + ", " + creditWords(s))
            .withStyle(Style.EMPTY.withItalic(false)));
        BlockPos c = cell(b, p);
        Direction faces = world(b, p.faces());
        switch (p.show()) {
            case FRAME -> {
                ItemFrame frame = frameAt(level, b, p);
                if (frame == null) {
                    if (!take(e, Items.ITEM_FRAME)) return false;
                    frame = new ItemFrame(level, c, faces);
                    if (!frame.survives()) {
                        e.fittings.add(new ItemStack(Items.ITEM_FRAME));
                        return false;
                    }
                    fix(frame, "Fixed", true);
                    frame.addTag("mca_museum");
                    level.addFreshEntity(frame);
                }
                frame.setItem(thing, false);
                s.holder = frame.getUUID();
            }
            case STAND -> {
                ArmorStand stand = standAt(level, b, p);
                if (stand == null) {
                    if (!take(e, Items.ARMOR_STAND)) return false;
                    stand = new ArmorStand(level, c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
                    float yaw = faces.toYRot();
                    stand.setYRot(yaw);
                    stand.setYBodyRot(yaw);
                    stand.setYHeadRot(yaw);
                    stand.setShowArms(true);
                    stand.addTag("mca_museum");
                    level.addFreshEntity(stand);
                }
                EquipmentSlot slot = k == Kind.TRIDENT ? EquipmentSlot.MAINHAND : stand.getEquipmentSlotForItem(thing);
                stand.setItemSlot(slot, thing);
                fixStand(stand);
                stand.setCustomName(Component.literal(s.label + " — " + creditWords(s)));
                stand.setCustomNameVisible(false);
                s.holder = stand.getUUID();
            }
            case CASE -> {
                Block block = thing.getItem() instanceof BlockItem bi ? bi.getBlock() : Blocks.AIR;
                if (block == Blocks.AIR) return false;
                BlockPos glass = at(b, p.dx(), -1, p.dz());
                BlockPos pit = c;
                boolean glazing = !level.getBlockState(glass).is(Blocks.GLASS);
                if (glazing && !take(e, Items.GLASS)) return false;
                BlockState was = level.getBlockState(pit);
                if (!was.is(block)) dugOut(level, v, was);                     // the earth dug out goes to the stores
                if (glazing) dugOut(level, v, level.getBlockState(glass));
                level.setBlock(pit, block.defaultBlockState(), 3);
                level.setBlock(glass, Blocks.GLASS.defaultBlockState(), 3);
            }
            case JUKEBOX -> {
                if (!(level.getBlockState(c).getBlock() instanceof JukeboxBlock)) {
                    if (!take(e, Items.JUKEBOX)) return false;
                    level.setBlock(c, Blocks.JUKEBOX.defaultBlockState(), 3);
                }
                if (!(level.getBlockEntity(c) instanceof JukeboxBlockEntity box)) return false;
                box.setTheItem(thing);
            }
        }
        label(level, b, p, labelLines(s), e);
        level.playSound(null, c, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.9F, 1.0F);
        MuseumRecords.Book book = MuseumRecords.book(id);
        book.shown.add(s);
        MuseumRecords.touch();
        e.exhibit = ItemStack.EMPTY;
        for (ItemStack left : e.fittings) if (!left.isEmpty()) Crafts.store(level, v, left);    // what was not wanted after all
        e.fittings.clear();
        IN_STORES.computeIfPresent(id, (x, l) -> { List<Kind> n = new ArrayList<>(l); n.remove(k); return n; });
        Villages.tell(id, day, capital(s.words) + " went on show in the museum (" + creditWords(s) + ")");
        if (f != null) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There. " + capital(article(k)) + ", on show for everybody.",
                "Doesn't that look grand? " + (s.finder.isEmpty() ? "" : s.finder + " will be proud.")));
            f.persona().remember(day, "I put " + article(k) + " on show in the museum", 2);
        }
        if (s.finderId != null && level.getEntity(s.finderId) instanceof VillageFolkEntity finder) {
            finder.persona().remember(day, "My find went on show in the museum: " + s.words, 5);
            FolkTalk.speak(finder, FolkTalk.pick(finder.getRandom(), "My " + k.label.toLowerCase(Locale.ROOT) + "'s in the museum!",
                "They've put my find on show!"));
        }
        LOG.info("[MCA-MUSEUM] {}: {} on show at place {} ({}); renown from the museum now {}", Villages.name(id), s.words,
            e.place, creditWords(s), renown(id));
        return true;
    }

    /** What comes out of the ground where a case is let in, into the stores: earth as dirt, stone as cobble. */
    private static void dugOut(ServerLevel level, Villages.Village v, BlockState st) {
        if (st.isAir() || !st.getFluidState().isEmpty()) return;
        Item it = st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM) || st.is(Blocks.DIRT_PATH) ? Items.DIRT
            : st.is(Blocks.STONE) ? Items.COBBLESTONE : st.is(Blocks.DEEPSLATE) ? Items.COBBLED_DEEPSLATE : st.getBlock().asItem();
        if (it != Items.AIR) Crafts.store(level, v, new ItemStack(it));
    }

    /** A fitting out of what it carries; false if it has none. */
    private static boolean take(Errand e, Item it) {
        for (ItemStack s : e.fittings) {
            if (s.is(it) && !s.isEmpty()) {
                s.shrink(1);
                return true;
            }
        }
        return false;
    }

    /** Who found it, at what trade and when, from the finds noted (the first of its kind); a book by the
     *  enchanter; a map by the scout whose find it is; else it came to the stores. */
    private static void credit(ServerLevel level, UUID id, Kind k, MuseumRecords.Shown s, Errand e) {
        MuseumRecords.Found first = MuseumRecords.firstFind(id, k.key());
        if (k == Kind.MAP) {
            String[] key = e.atlasKey.split("\\|", -1);
            for (Scouts.Find f : Scouts.atlas(id)) {
                if (!mapKey(f).equals(e.atlasKey)) continue;
                s.finder = f.by();
                s.trade = "scout";
                s.how = "found";
                s.found = f.day();
                s.label = shortLabel(f.label());
                s.words = "a map of " + f.label() + ", that a scout found";
                return;
            }
            if (key.length > 0) s.words = "a map of " + key[0];
            return;
        }
        if (first != null) {
            s.finder = first.finder;
            s.finderId = first.finderId;
            s.trade = first.trade;
            s.how = first.how;
            s.found = first.day;
            return;
        }
        if (k == Kind.BOOK) {
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (a instanceof VillageFolkEntity f && f.stationTask() == AssistantEntity.StationTask.ENCHANT) {
                    s.finder = f.displayNameCap();
                    s.finderId = f.getUUID();
                    s.trade = "enchanter";
                    s.how = "made";
                    return;
                }
            }
        }
        s.how = "came to the stores";
    }

    /** "mined by Ember the miner on day 41", "made by Wren the enchanter", "came to the stores". */
    static String creditWords(MuseumRecords.Shown s) {
        if (s.finder.isEmpty()) return s.how.isEmpty() ? "from the stores" : s.how + (s.found >= 0 ? " on day " + s.found : "");
        return s.how + " by " + s.finder + (s.trade.isEmpty() ? "" : " the " + s.trade) + (s.found >= 0 ? " on day " + s.found : "");
    }

    /** The first line of the label: what it is (for a book, its enchantment; for a map, of what). */
    private static String labelFor(Kind k, ItemStack s, Errand e) {
        if (k == Kind.BOOK) {
            ItemEnchantments en = s.get(DataComponents.STORED_ENCHANTMENTS);
            if (en != null && en.size() > 0) {
                var first = en.entrySet().iterator().next();
                String name = net.minecraft.world.item.enchantment.Enchantment.getFullname(first.getKey(), first.getIntValue()).getString();
                if (Archive.px(name) <= SIGN_PX) return name;
            }
        }
        return k.label;
    }

    private static final int SIGN_PX = 88;

    private static String shortLabel(String words) {
        String w = words.replaceFirst("^(a|an|the) ", "");
        w = capital(w);
        return Archive.px(w) <= SIGN_PX ? w : "Old Map";
    }

    /** The label's four lines: what, found by whom, at what trade, on what day. */
    static String[] labelLines(MuseumRecords.Shown s) {
        String l1, l2 = "", l3;
        if (s.finder.isEmpty()) {
            l1 = "from the stores";
            l3 = "shown day " + s.shown;
        } else {
            String verb = s.how.isEmpty() ? "found" : s.how;
            String full = verb + " by " + s.finder;
            l1 = Archive.px(full) <= SIGN_PX ? full : Archive.px("by " + s.finder) <= SIGN_PX ? "by " + s.finder : s.finder;
            l2 = s.trade.isEmpty() ? "" : "the " + s.trade;
            l3 = s.found >= 0 ? "day " + s.found : "shown day " + s.shown;
        }
        return new String[]{ fit(s.label), fit(l1), fit(l2), fit(l3) };
    }

    private static String fit(String s) {
        String t = s;
        while (Archive.px(t) > SIGN_PX && t.length() > 1) t = t.substring(0, t.length() - 1);
        return t;
    }

    /** Its label: a sign on the wall by it, or standing on the floor before it. */
    private static void label(ServerLevel level, Ledger.Building b, Place p, String[] lines, Errand e) {
        BlockPos at = at(b, p.lx(), p.lh(), p.lz());
        Direction faces = world(b, p.labelFaces());
        if (!(level.getBlockEntity(at) instanceof SignBlockEntity)) {
            Item sign = null;
            for (ItemStack s : e.fittings) if (s.is(ItemTags.SIGNS) && !s.isEmpty()) { sign = s.getItem(); s.shrink(1); break; }
            if (sign == null || !level.getBlockState(at).canBeReplaced()) return;          // no sign carried: no label yet
            Block standing = Block.byItem(sign);
            String path = BuiltInRegistries.ITEM.getKey(sign).getPath();
            Block wall = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(path.replace("_sign", "_wall_sign")));
            if (!(standing instanceof StandingSignBlock)) standing = Blocks.OAK_SIGN;
            if (!(wall instanceof WallSignBlock)) wall = Blocks.OAK_WALL_SIGN;
            BlockState st = p.standing()
                ? standing.defaultBlockState().setValue(StandingSignBlock.ROTATION, RotationSegment.convertToSegment(faces))
                : wall.defaultBlockState().setValue(WallSignBlock.FACING, faces);
            level.setBlock(at, st, 3);
        }
        if (level.getBlockEntity(at) instanceof SignBlockEntity sign) TownLife.write(sign, lines);
    }

    /** A frame's flag set, as the game keeps it ("Fixed": not to be broken, turned or emptied). */
    private static void fix(ItemFrame frame, String flag, boolean on) {
        CompoundTag t = new CompoundTag();
        frame.addAdditionalSaveData(t);
        t.putBoolean(flag, on);
        frame.readAdditionalSaveData(t);
    }

    /** A stand nobody can take from or hang things on. */
    private static void fixStand(ArmorStand stand) {
        CompoundTag t = new CompoundTag();
        stand.addAdditionalSaveData(t);
        t.putInt("DisabledSlots", 0x7F7F7F);
        stand.readAdditionalSaveData(t);
    }

    /** The museum's double door hung as one: the two leaves hinged on their outer sides. */
    private static void hangTheDoors(ServerLevel level, Ledger.Building b) {
        if (!DOORS_HUNG.add(b.anchor().asLong())) return;
        BlockPos a = at(b, 0, 0, -4), c = at(b, 1, 0, -4);
        BlockState sa = level.getBlockState(a), sc = level.getBlockState(c);
        if (!(sa.getBlock() instanceof DoorBlock) || !(sc.getBlock() instanceof DoorBlock)) {
            DOORS_HUNG.remove(b.anchor().asLong());                      // not hung yet: look again
            return;
        }
        Direction facing = sa.getValue(DoorBlock.FACING);
        boolean aLeft = c.equals(a.relative(facing.getClockWise()));
        hinge(level, a, aLeft ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT, facing);
        hinge(level, c, aLeft ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT, facing);
    }

    private static void hinge(ServerLevel level, BlockPos lower, DoorHingeSide side, Direction facing) {
        for (BlockPos p : new BlockPos[]{ lower, lower.above() }) {
            BlockState st = level.getBlockState(p);
            if (!(st.getBlock() instanceof DoorBlock)) continue;
            if (st.getValue(DoorBlock.HINGE) == side && st.getValue(DoorBlock.FACING) == facing) continue;
            level.setBlock(p, st.setValue(DoorBlock.HINGE, side).setValue(DoorBlock.FACING, facing), 2 | 16);
        }
    }

    // ------------------------------------------------------------------ the old map

    static String mapKey(Scouts.Find f) {
        return f.label() + "|" + f.day();
    }

    /** A ruin the scouts found that has no map in the museum yet, the oldest first. */
    @Nullable
    static Scouts.Find mapToDraw(UUID village, MuseumRecords.Book book) {
        for (Scouts.Find f : Scouts.atlas(village)) {
            if (f.kind() != Scouts.Kind.RUIN || book.mapped.contains(mapKey(f))) continue;
            return f;
        }
        return null;
    }

    /** The map drawn: an empty map (made out of the stores) filled in round the scouts' find, the find marked. */
    private static ItemStack drawMap(ServerLevel level, Villages.Village v, Errand e) {
        for (Scouts.Find f : Scouts.atlas(v.id())) {
            if (!mapKey(f).equals(e.atlasKey)) continue;
            ItemStack map = net.minecraft.world.item.MapItem.create(level, f.at().getX(), f.at().getZ(), (byte) 1, true, true);
            net.minecraft.world.item.MapItem.renderBiomePreviewMap(level, map);
            net.minecraft.world.level.saveddata.maps.MapItemSavedData.addTargetDecoration(map, f.at(), "+",
                net.minecraft.world.level.saveddata.maps.MapDecorationTypes.RED_X);
            MuseumRecords.book(v.id()).mapped.add(e.atlasKey);
            MuseumRecords.touch();
            return map;
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ the archive's books

    /** The year written up: a book for each book of it, out of the book and quills it carried, and shelved. */
    private static boolean bind(ServerLevel level, Villages.Village v, Ledger.Building b, Errand e, @Nullable VillageFolkEntity f, long day) {
        UUID id = v.id();
        MuseumRecords.Book book = MuseumRecords.book(id);
        MuseumRecords.Notes n = Archive.waiting(book);
        if (n == null || n.year != e.year) return false;
        if (!readyTheArchive(level, b, e)) return false;
        String name = Villages.name(id);
        String author = f != null ? f.displayNameCap() : book.curatorName;
        List<List<String>> books = Archive.layout(name, n, author, Villages.elderName(id), day);
        int quills = 0;
        for (ItemStack s : e.fittings) if (s.is(Items.WRITABLE_BOOK)) quills += s.getCount();
        if (quills < books.size()) return false;
        List<String> placed = new ArrayList<>();
        for (int i = 0; i < books.size(); i++) {
            String title = Archive.title(name, n.year, i + 1, books.size());
            ItemStack vol = Archive.book(title, author, 0, books.get(i));
            String where = Archive.shelveNewest(level, b, book, vol, f);
            if (where.isEmpty()) {
                if (i == 0) return false;
                break;                                                          // the rest wait for a shelf
            }
            take(e, Items.WRITABLE_BOOK);
            MuseumRecords.Volume rec = new MuseumRecords.Volume();
            rec.year = n.year;
            rec.part = i + 1;
            rec.parts = books.size();
            rec.title = title;
            rec.author = author;
            rec.from = n.from;
            rec.to = n.to;
            rec.written = day;
            rec.entries = n.entries.size();
            rec.where = where;
            rec.pages.addAll(books.get(i));
            book.volumes.add(rec);
            placed.add(where);
        }
        book.notes.remove(n);
        for (int i = 0; i < book.volumes.size() - MuseumRecords.MOST_VOLUMES; i++) book.volumes.get(i).pages.clear();
        MuseumRecords.touch();
        for (ItemStack left : e.fittings) if (!left.isEmpty()) Crafts.store(level, v, left);
        e.fittings.clear();
        level.playSound(null, Archive.lecternAt(b), SoundEvents.BOOK_PUT, SoundSource.BLOCKS, 1.0F, 1.0F);
        Villages.tell(id, day, "Year " + n.year + " of the chronicle was written up and put in the museum's archive"
            + (author.isEmpty() ? "" : " by " + author + ", the curator"));
        if (f != null) {
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Year " + n.year + ", all written down. It's on the lectern if you want to read it.",
                "There — another year in the archive."));
            f.persona().remember(day, "I wrote up Year " + n.year + " of the chronicle", 2);
        }
        LOG.info("[MCA-MUSEUM] {}: Year {} bound in {} book(s), {}", name, n.year, placed.size(), placed);
        return true;
    }

    /** A lost volume written out again from the curator's own record of it, a fair copy, and shelved. */
    private static boolean copy(ServerLevel level, Villages.Village v, Ledger.Building b, Errand e, @Nullable VillageFolkEntity f, long day) {
        UUID id = v.id();
        MuseumRecords.Book book = MuseumRecords.book(id);
        if (e.volume < 0 || e.volume >= book.volumes.size()) return false;
        MuseumRecords.Volume vol = book.volumes.get(e.volume);
        if (!vol.missing || vol.pages.isEmpty()) return false;
        if (!readyTheArchive(level, b, e)) return false;
        ItemStack copy = Archive.book(vol.title, vol.author, 1, vol.pages);
        String where = Archive.shelveCopy(level, b, vol, copy, f);
        if (where.isEmpty()) return false;
        take(e, Items.WRITABLE_BOOK);
        vol.where = where;
        vol.missing = false;
        vol.generation = 1;
        MuseumRecords.touch();
        for (ItemStack left : e.fittings) if (!left.isEmpty()) Crafts.store(level, v, left);
        e.fittings.clear();
        Villages.tell(id, day, vol.title + " was written out again for the archive" + (f == null ? "" : " by " + f.displayNameCap()));
        if (f != null) FolkTalk.speak(f, "There. Good as new — well, a fair copy.");
        return true;
    }

    /** The lectern and a shelf it carried, set up first. */
    private static boolean readyTheArchive(ServerLevel level, Ledger.Building b, Errand e) {
        if (!Archive.lecternStands(level, b) && take(e, Items.LECTERN)) Archive.setLectern(level, b);
        if (Archive.wantsAShelf(level, b) && take(e, Items.CHISELED_BOOKSHELF)) {
            int i = Archive.shelfToSet(level, b);
            if (i >= 0) Archive.setShelf(level, b, i);
        }
        archiveSign(level, b, e.village, false);
        return true;
    }

    /** Over the lectern, a word of what it is: a sign of planks the stores can spare, if they can (free, on a stage). */
    private static void archiveSign(ServerLevel level, Ledger.Building b, UUID village, boolean free) {
        if (!Archive.lecternStands(level, b)) return;
        BlockPos sign = at(b, 0, 1, 3);
        if (!(level.getBlockEntity(sign) instanceof SignBlockEntity) && level.getBlockState(sign).canBeReplaced()
                && level.getBlockState(at(b, 0, 1, 4)).isSolid()) {
            Villages.Village v = Villages.get(village);
            if (free || (v != null && Crafts.sign(level, v))) {
                level.setBlock(sign, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, b.facing().getOpposite()), 3);
            }
        }
        if (level.getBlockEntity(sign) instanceof SignBlockEntity s) {
            TownLife.write(s, new String[]{ "The Chronicle", "of", fit(Villages.name(village)), "the archive" });
        }
    }

    // ------------------------------------------------------------------ looking after it

    /** Everything where it should be? What is gone is missed, and told; a volume gone is written out again. */
    private static void verify(ServerLevel level, Villages.Village v, Ledger.Building b, MuseumRecords.Book book, long day) {
        UUID id = v.id();
        if (!level.isPositionEntityTicking(b.anchor())) return;
        for (MuseumRecords.Shown s : new ArrayList<>(book.shown)) {
            if (s.place < 0 || s.place >= PLACES.size()) continue;
            Place p = PLACES.get(s.place);
            boolean there = switch (p.show()) {
                case FRAME -> {
                    ItemFrame f = frameAt(level, b, p);
                    yield f != null && !f.getItem().isEmpty();
                }
                case STAND -> {
                    ArmorStand st = standAt(level, b, p);
                    boolean any = false;
                    if (st != null) for (ItemStack it : st.getAllSlots()) if (!it.isEmpty()) any = true;
                    yield any;
                }
                case CASE -> {
                    Item it = BuiltInRegistries.ITEM.get(ResourceLocation.parse(s.item));
                    yield it instanceof BlockItem bi && level.getBlockState(cell(b, p)).is(bi.getBlock());
                }
                case JUKEBOX -> level.getBlockEntity(cell(b, p)) instanceof JukeboxBlockEntity j && !j.getTheItem().isEmpty();
            };
            if (there) {
                if (s.misses != 0) { s.misses = 0; MuseumRecords.touch(); }
                continue;
            }
            if (++s.misses < 2) { MuseumRecords.touch(); continue; }
            book.shown.remove(s);
            book.lost++;
            MuseumRecords.touch();
            Villages.tell(id, day, capital(s.words) + " went missing from the museum");
            LOG.info("[MCA-MUSEUM] {}: {} is gone from the museum", Villages.name(id), s.words);
        }
        for (MuseumRecords.Volume vol : book.volumes) {
            if (vol.missing || vol.where.isEmpty() || vol.pages.isEmpty()) continue;
            if (Archive.present(level, b, vol)) continue;
            vol.missing = true;
            MuseumRecords.touch();
            Villages.tell(id, day, vol.title + " was taken from the museum's archive");
        }
    }

    /** On the day of rest the jukebox plays its disc, from the morning till the evening. */
    private static void music(ServerLevel level, Villages.Village v, Ledger.Building b, MuseumRecords.Book book, long day) {
        long t = level.getDayTime() % 24000L;
        if (t < 1000L || t >= 12000L || !RestDay.today(v.id(), day)) return;
        for (MuseumRecords.Shown s : book.shown) {
            if (s.place < 0 || s.place >= PLACES.size() || PLACES.get(s.place).show() != Show.JUKEBOX) continue;
            if (level.getBlockEntity(cell(b, PLACES.get(s.place))) instanceof JukeboxBlockEntity box
                    && !box.getTheItem().isEmpty() && !box.getSongPlayer().isPlaying()) {
                box.tryForcePlaySong();
            }
        }
    }

    /** A player come in is welcomed by the curator (once a day). */
    private static void welcome(ServerLevel level, Villages.Village v, Ledger.Building b, @Nullable VillageFolkEntity curator, long day) {
        if (curator == null || curator.isSleeping()) return;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || !inside(b, p.blockPosition()) || curator.distanceToSqr(p) > 14 * 14) continue;
            String key = v.id() + "/" + p.getUUID();
            Long was = WELCOMED.get(key);
            if (was != null && was == day) continue;
            WELCOMED.put(key, day);
            int n = MuseumRecords.book(v.id()).shown.size();
            curator.getLookControl().setLookAt(p, 30.0F, 30.0F);
            FolkTalk.speak(curator, n == 0 ? "Welcome to the museum! Nothing on show yet — but just you wait."
                : "Welcome to the museum of " + Villages.name(v.id()) + "! " + n + (n == 1 ? " thing" : " things")
                    + " on show, and every label says who found it. The chronicle's on the lectern.");
        }
    }

    /** Is this inside the museum's walls? */
    public static boolean inside(Ledger.Building b, BlockPos p) {
        Direction right = b.facing().getClockWise();
        int[] o = hall(b);
        int dx = (p.getX() - b.anchor().getX()) * right.getStepX() + (p.getZ() - b.anchor().getZ()) * right.getStepZ();
        int dz = (p.getX() - b.anchor().getX()) * b.facing().getStepX() + (p.getZ() - b.anchor().getZ()) * b.facing().getStepZ() - o[1];
        int dy = p.getY() - b.anchor().getY() - o[0];
        return dx >= -3 && dx <= 4 && dz >= -3 && dz <= 3 && dy >= -1 && dy <= 4;
    }

    // ------------------------------------------------------------------ visitors

    /** A folk looking round: the things it means to see, the one it is at, and how long it stays. */
    private static final class Visit {
        final List<Integer> exhibits = new ArrayList<>();
        int at;
        int arrived = -1, until;
        boolean said;
    }

    private static final Map<UUID, Visit> VISITS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> VISITED = new ConcurrentHashMap<>();

    /**
     * From a folk's free time (socialise): now and then it goes round the museum, stopping before a
     * thing or two and saying a word about it. The curious and the readers most, finders to see their
     * own finds, the curator to look after it. True while it is there.
     */
    public static boolean visit(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        if (id == null || !MuseumRecords.has(id)) return false;
        long day = level.getDayTime() / 24000L;
        Visit v = VISITS.get(f.getUUID());
        if (v == null) {
            Long last = VISITED.get(f.getUUID());
            if (last != null && last == day) return false;
            VISITED.put(f.getUUID(), day);
            MuseumRecords.Book book = MuseumRecords.book(id);
            Ledger.Building b = building(id);
            if (b == null || book.shown.isEmpty() || !level.isLoaded(b.anchor())) return false;
            int roll = Math.floorMod((int) (day * 37L) + f.getUUID().hashCode(), 12);
            boolean finder = false;
            for (MuseumRecords.Shown s : book.shown) if (f.getUUID().equals(s.finderId)) finder = true;
            int odds = (f.life().rolled() && f.life().has(Social.Trait.CURIOUS) ? 4 : 0)
                + (f.persona().rolled() && f.persona().hobby() == Persona.Hobby.READING ? 4 : 0)
                + (finder ? 3 : 0) + (isCurator(f) ? 6 : 0) + 2;
            if (roll >= odds) return false;
            v = new Visit();
            List<Integer> mine = new ArrayList<>(), rest = new ArrayList<>();
            for (int i = 0; i < book.shown.size(); i++) {
                if (f.getUUID().equals(book.shown.get(i).finderId)) mine.add(i); else rest.add(i);
            }
            java.util.Collections.shuffle(rest, new java.util.Random(f.getUUID().hashCode() ^ day));
            v.exhibits.addAll(mine);
            v.exhibits.addAll(rest);
            while (v.exhibits.size() > 3) v.exhibits.remove(v.exhibits.size() - 1);
            v.until = f.tickCount + 1800;
            VISITS.put(f.getUUID(), v);
        }
        MuseumRecords.Book book = MuseumRecords.book(id);
        Ledger.Building b = building(id);
        if (b == null || v.at >= v.exhibits.size() || f.tickCount > v.until) {
            VISITS.remove(f.getUUID());
            if (v.said) f.persona().remember(day, "I went round the museum", 1);
            return false;
        }
        int index = v.exhibits.get(v.at);
        if (index >= book.shown.size()) {
            v.at++;
            return true;
        }
        MuseumRecords.Shown s = book.shown.get(index);
        if (s.place < 0 || s.place >= PLACES.size()) {
            v.at++;
            return true;
        }
        Place p = PLACES.get(s.place);
        BlockPos spot = viewFrom(b, p);
        f.hobbyNow = "looking round the museum";
        f.lastLeisureTick = f.tickCount;
        if (!near(f, spot, 1)) {
            if (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 100) {
                f.walkTo(spot, 0.7D);
                f.hobbyTick = f.tickCount;
            }
            return true;
        }
        f.getNavigation().stop();
        BlockPos look = lookAt(b, p);
        f.getLookControl().setLookAt(look.getX() + 0.5, look.getY() + 0.5, look.getZ() + 0.5);
        if (v.arrived < 0) v.arrived = f.tickCount;
        if (!v.said && f.tickCount - v.arrived > 60) {
            v.said = true;
            FolkTalk.speak(f, remark(f, s));
        }
        if (f.tickCount - v.arrived > 300) {
            v.at++;
            v.arrived = -1;
        }
        return true;
    }

    /** What a folk says before a thing on show. */
    static String remark(VillageFolkEntity f, MuseumRecords.Shown s) {
        RandomSource r = f.getRandom();
        if (f.getUUID().equals(s.finderId)) {
            return FolkTalk.pick(r, "My " + s.label.toLowerCase(Locale.ROOT) + "! Day " + s.found + ", it was. I'll never forget it.",
                "That's mine, that is. I " + (s.how.isEmpty() ? "found" : s.how) + " it myself.",
                "Look at it there, with my name on it.");
        }
        if (isCurator(f)) return FolkTalk.pick(r, "A little dust on this one. There.", "Straight as a die. Good.",
            "Mind the glass, everybody.");
        String who = s.finder.isEmpty() ? "" : s.finder + " " + (s.how.isEmpty() ? "found" : s.how) + " that. ";
        Kind k = Kind.byKey(s.kind);
        String about = k == null ? "Isn't it something?" : switch (k) {
            case DIAMOND -> "Look at it shine. Our very first diamond.";
            case EMERALD -> "Green as spring, that emerald.";
            case FOSSIL -> "Bones of something enormous, from under the hill.";
            case DISC -> "They say it plays on the day of rest.";
            case BOOK -> "You can almost feel the magic in it.";
            case TRIDENT -> "Pulled up out of the deep, that was.";
            case NAUTILUS -> "A shell from the sea. So pretty.";
            case HEART -> "The heart of the sea! It glows, look.";
            case TOTEM -> "They say it cheats death itself.";
            case SADDLE -> "A saddle, of all things to find!";
            case FISH -> "What a colour on it!";
            case SKULL -> "I wouldn't want to meet the rest of it.";
            case CHAINMAIL -> "Little rings, all linked. Clever.";
            case RABBIT_FOOT -> "That's luck, that is.";
            case GHAST_TEAR -> "A ghast's tear. From the Nether!";
            case MAP -> "That's the way to it, see? The scouts walked all that.";
            case IRON -> "Where the Iron Age began.";
            default -> "Isn't it something?";
        };
        return who + about;
    }

    // ------------------------------------------------------------------ pride

    /** A finder's pride in its find on show: much in the first days, a little always. */
    public static int pride(VillageFolkEntity f, long day) {
        UUID id = f.ownerId();
        if (id == null || !MuseumRecords.has(id)) return 0;
        int best = 0;
        for (MuseumRecords.Shown s : MuseumRecords.book(id).shown) {
            if (!f.getUUID().equals(s.finderId)) continue;
            best = Math.max(best, day - s.shown <= 2 ? 6 : 2);
        }
        return best;
    }

    /** Why it is proud, as it would put it. */
    public static String prideWords(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null) return "";
        for (MuseumRecords.Shown s : MuseumRecords.book(id).shown) {
            if (f.getUUID().equals(s.finderId)) {
                return FolkTalk.pick(f.getRandom(), "My find's on show in the museum — " + s.words + "!",
                    "Have you been to the museum? That's my " + s.label.toLowerCase(Locale.ROOT) + " in there.");
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ in words, for the books and the board

    /** The folk's card: its finds on show. */
    public static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (id == null || !MuseumRecords.has(id)) return "";
        List<String> mine = new ArrayList<>();
        for (MuseumRecords.Shown s : MuseumRecords.book(id).shown) {
            if (f.getUUID().equals(s.finderId)) mine.add(s.words + (s.found >= 0 ? " (day " + s.found + ")" : ""));
        }
        if (mine.isEmpty()) {
            for (MuseumRecords.Found fd : MuseumRecords.book(id).finds) {
                Kind k = Kind.byKey(fd.kind);
                if (k != null && f.getUUID().equals(fd.finderId) && k.renown >= 2) mine.add("found " + article(k) + ", day " + fd.day + " (not on show)");
                if (mine.size() >= 2) break;
            }
        }
        return String.join("; ", mine);
    }

    /** The folk's card: the curator's. */
    public static String curatorLine(VillageFolkEntity f) {
        if (!isCurator(f)) return "";
        MuseumRecords.Book book = MuseumRecords.book(f.ownerId());
        Errand e = ERRANDS.get(f.ownerId());
        return "of the museum, since day " + book.curatorSince + ": " + book.shown.size() + " on show, "
            + Archive.words(book) + (e != null ? "; now " + e.words : "");
    }

    /** A line for the board: what is new in the museum, how much it holds, and its renown. */
    @Nullable
    public static String boardLine(UUID village, long day) {
        if (!MuseumRecords.has(village)) return null;
        MuseumRecords.Book book = MuseumRecords.book(village);
        if (building(village) == null) {
            if (Villages.hasBuilt(village, "museum")) return null;
            return worthAMuseum(village) && Villages.headcount(village) >= FROM_FOLK ? "A museum is wanted: the town has finds worth showing." : null;
        }
        MuseumRecords.Shown newest = null;
        for (MuseumRecords.Shown s : book.shown) if (newest == null || s.shown >= newest.shown) newest = s;
        StringBuilder sb = new StringBuilder("The museum: ").append(book.shown.size()).append(" on show, renown ").append(renown(village));
        if (newest != null && day - newest.shown <= 3) sb.append(". New: ").append(newest.words).append(", ").append(creditWords(newest));
        if (!book.volumes.isEmpty()) sb.append(". The archive: ").append(Archive.words(book));
        return sb.append('.').toString();
    }

    /** The museum, for the town's books (the Museum page). */
    public static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        Ledger.Building b = building(id);
        boolean has = MuseumRecords.has(id);
        MuseumRecords.Book book = has ? MuseumRecords.book(id) : new MuseumRecords.Book();
        out.putBoolean("built", b != null);
        out.putBoolean("wanted", b == null && worthAMuseum(id));
        out.putInt("from_folk", FROM_FOLK);
        out.putString("curator", b == null ? "" : book.curatorName);
        out.putLong("curator_since", book.curatorSince);
        int museum = renown(id);
        out.putInt("renown", Villages.renown(id));
        out.putInt("renown_museum", museum);
        out.putInt("renown_works", Villages.renown(id) - museum);
        out.putInt("places", PLACES.size());
        out.putInt("lost", book.lost);
        ListTag shown = new ListTag();
        for (MuseumRecords.Shown s : book.shown) {
            CompoundTag c = new CompoundTag();
            c.putString("what", s.words);
            c.putString("label", s.label);
            c.putString("item", s.item);
            c.putString("finder", s.finder.isEmpty() ? "—" : s.finder + (s.trade.isEmpty() ? "" : " the " + s.trade));
            c.putString("how", s.how);
            c.putLong("found", s.found);
            c.putLong("shown", s.shown);
            c.putInt("renown", s.renown);
            c.putString("where", s.place >= 0 && s.place < PLACES.size() ? placeWords(PLACES.get(s.place).show()) : "");
            shown.add(c);
        }
        out.put("shown", shown);
        ListTag vols = new ListTag();
        for (MuseumRecords.Volume vol : book.volumes) {
            CompoundTag c = new CompoundTag();
            c.putString("title", vol.title);
            c.putInt("year", vol.year);
            c.putInt("pages", vol.pages.size());
            c.putInt("entries", vol.entries);
            c.putLong("written", vol.written);
            c.putString("author", vol.author);
            c.putString("where", vol.missing ? "taken away" : vol.where.isEmpty() ? "—" : vol.where);
            c.putBoolean("copy", vol.generation > 0);
            vols.add(c);
        }
        out.put("volumes", vols);
        ListTag waiting = new ListTag();
        for (MuseumRecords.Notes n : book.notes) {
            waiting.add(StringTag.valueOf("Year " + n.year + " (days " + n.from + " to " + n.to + "): " + n.entries.size()
                + (n.entries.size() == 1 ? " entry" : " entries") + ", waiting to be bound"));
        }
        long day = level.getDayTime() / 24000L;
        int year = Archive.yearOf(id, day);
        if (year > 0) waiting.add(StringTag.valueOf("Year " + year + " is going on now (day " + day + ")"));
        out.put("waiting", waiting);
        ListTag coming = new ListTag();
        Map<String, Long> asked = ASKED.get(id);
        if (asked != null) for (String k : asked.keySet()) {
            Kind kind = Kind.byKey(k);
            if (kind != null) coming.add(StringTag.valueOf(capital(article(kind)) + ": asked for, kept back from the makers"));
        }
        for (Kind k : IN_STORES.getOrDefault(id, List.of())) {
            if (asked != null && asked.containsKey(k.key())) continue;
            coming.add(StringTag.valueOf(capital(article(k)) + " in the stores (renown " + k.renown + ")"));
        }
        for (MuseumRecords.Found fd : book.finds) {
            Kind k = Kind.byKey(fd.kind);
            if (k == null || onShow(id, fd.kind) || IN_STORES.getOrDefault(id, List.of()).contains(k)) continue;
            if (!fd.equals(MuseumRecords.firstFind(id, fd.kind))) continue;
            coming.add(StringTag.valueOf(capital(article(k)) + ": " + fd.how + " by " + fd.finder + " on day " + fd.day + ", not in the stores now"));
        }
        out.put("coming", coming);
        Errand e = ERRANDS.get(id);
        out.putString("doing", e == null ? "" : book.curatorName + " is " + e.words + (e.fetched ? " (on the way back)" : ""));
        String s = SHORT.get(id);
        out.putString("short", s == null ? "" : s);
        return out;
    }

    static String placeWords(Show s) {
        return switch (s) {
            case FRAME -> "in a frame";
            case CASE -> "under glass";
            case STAND -> "on a stand";
            case JUKEBOX -> "in the jukebox";
        };
    }

    /** /village museum: the museum of the village, in lines. */
    public static List<String> status(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        Ledger.Building b = building(id);
        MuseumRecords.Book book = MuseumRecords.book(id);
        if (b == null) {
            out.add(Villages.name(id) + " has no museum yet" + (worthAMuseum(id) ? "; it has finds enough to want one (from "
                + FROM_FOLK + " folk and the Iron Age)" : "; it wants " + FINDS_FOR_A_MUSEUM + " different finds worth showing first") + ".");
        } else {
            out.add("The museum of " + Villages.name(id) + " at " + b.anchor().toShortString() + ", facing "
                + b.facing().getOpposite().getName() + "; curator " + (book.curatorName.isEmpty() ? "none yet" : book.curatorName)
                + "; renown " + renown(id) + " of the town's " + Villages.renown(id) + ".");
        }
        for (MuseumRecords.Shown s : book.shown) {
            out.add("  " + capital(s.words) + " — " + creditWords(s) + "; " + placeWords(PLACES.get(Math.max(0, Math.min(PLACES.size() - 1, s.place))).show())
                + ", renown " + s.renown + ", on show since day " + s.shown);
        }
        for (MuseumRecords.Volume vol : book.volumes) {
            out.add("  " + vol.title + " by " + vol.author + ", " + vol.pages.size() + " pages, " + (vol.missing ? "taken away" : vol.where));
        }
        for (MuseumRecords.Notes n : book.notes) out.add("  Year " + n.year + ": " + n.entries.size() + " entries waiting to be bound");
        Errand e = ERRANDS.get(id);
        if (e != null) out.add("  Now: " + book.curatorName + " is " + e.words + ".");
        String s = SHORT.get(id);
        if (s != null) out.add("  Short of " + s + ".");
        List<String> stores = new ArrayList<>();
        for (Kind k : IN_STORES.getOrDefault(id, List.of())) stores.add(k.key());
        if (!stores.isEmpty()) out.add("  In the stores to go on show: " + String.join(", ", stores) + ".");
        return out;
    }

    // ------------------------------------------------------------------ the pictures

    /**
     * /village museum stage: a museum set out here for the pictures, as the showcase sets out the
     * buildings (out of a palette, not the stores): its places filled with one of everything, each
     * credited to one of the village's own folk, and the chronicle so far bound into its archive. It is
     * the village's museum from then on. Returns where to look from: "VIEW name x y z at-x at-y at-z".
     */
    public static List<String> stage(ServerLevel level, Villages.Village v, BlockPos at) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Direction back = Direction.NORTH;
        // A forecourt of smooth stone to stand it on, running out to the south (its front) far enough for
        // the picture of the whole front to stand on it, and the air above it cleared, pediment and all.
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-13, -1, -9), at.offset(13, -1, 17))) {
            level.setBlock(p, Blocks.SMOOTH_STONE.defaultBlockState(), 2);
        }
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-13, 0, -9), at.offset(13, 12, 17))) {
            if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        }
        for (net.minecraft.world.entity.Entity old : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, new AABB(at).inflate(14),
                x -> x instanceof ItemFrame || x instanceof ArmorStand)) old.discard();
        com.jrpetty.mcassistant.entity.goal.BuildGoal.stamp(level, "museum", at, back, 13,
            com.jrpetty.mcassistant.Showcase.painter(com.jrpetty.mcassistant.Showcase.OAK));
        Ledger.built(id, "museum", at, back);
        Ledger.Building b = new Ledger.Building("museum", at.immutable(), back);
        HALLS.put(at.asLong(), GRAND);
        DOORS_HUNG.remove(at.asLong());
        hangTheDoors(level, b);
        MuseumFront.put(level, v, b, true);
        MuseumRecords.Book book = MuseumRecords.book(id);
        book.shown.clear();
        VillageFolkEntity curator = curator(level, v, book, day, level.getGameTime());
        // The finders: the village's own folk, a miner, a fisher, a hunter, a guard where it has them.
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a instanceof VillageFolkEntity f && !f.isBaby() && !f.isShowcase()) folk.add(f);
        ItemStack book1 = net.minecraft.world.item.enchantment.EnchantmentHelper.enchantItem(level.getRandom(), new ItemStack(Items.BOOK), 30,
            level.registryAccess(), java.util.Optional.empty());
        Object[][] things = {
            { Kind.DIAMOND, new ItemStack(Items.DIAMOND), "miner", "mined" },
            { Kind.EMERALD, new ItemStack(Items.EMERALD), "miner", "mined" },
            { Kind.BOOK, book1, "enchanter", "made" },
            { Kind.NAUTILUS, new ItemStack(Items.NAUTILUS_SHELL), "fisher", "fished up" },
            { Kind.SADDLE, new ItemStack(Items.SADDLE), "fisher", "fished up" },
            { Kind.TOTEM, new ItemStack(Items.TOTEM_OF_UNDYING), "guard", "won" },
            { Kind.GHAST_TEAR, new ItemStack(Items.GHAST_TEAR), "miner", "brought home" },
            { Kind.FISH, new ItemStack(Items.TROPICAL_FISH), "fisher", "fished up" },
            { Kind.FOSSIL, new ItemStack(Items.BONE_BLOCK), "miner", "dug out" },
            { Kind.SKULL, new ItemStack(Items.SKELETON_SKULL), "guard", "won" },
            { Kind.TRIDENT, new ItemStack(Items.TRIDENT), "guard", "won" },
            { Kind.CHAINMAIL, new ItemStack(Items.CHAINMAIL_CHESTPLATE), "guard", "won" },
            { Kind.DISC, new ItemStack(Items.MUSIC_DISC_CAT), "hunter", "brought home" } };
        int n = 0;
        for (Object[] t : things) {
            Kind k = (Kind) t[0];
            ItemStack thing = (ItemStack) t[1];
            if (thing.isEmpty()) continue;
            VillageFolkEntity finder = folk.isEmpty() ? null : folk.get(n % folk.size());
            if (finder != null && MuseumRecords.firstFind(id, k.key()) == null) {
                MuseumRecords.noteFind(id, k.key(), finder.getUUID(), finder.displayNameCap(), (String) t[2], (String) t[3],
                    Math.max(0, day - 1 - n % 5));
            }
            int place = freePlace(level, b, book, k);
            if (place < 0) continue;
            Errand e = new Errand(id, curator == null ? UUID.randomUUID() : curator.getUUID(), Job.SHOW, k, place, -1, -1,
                level.getGameTime(), "setting out " + article(k));
            e.exhibit = thing.copyWithCount(1);
            for (Item it : new Item[]{ Items.ITEM_FRAME, Items.ARMOR_STAND, Items.GLASS, Items.JUKEBOX, Items.OAK_SIGN }) {
                e.fittings.add(new ItemStack(it));
            }
            e.fetched = true;
            if (setOut(level, v, b, e, curator, day)) n++;
        }
        // The archive: the years gone by, and the one going on now, bound.
        for (int i = 0; i < 2; i++) Archive.setShelf(level, b, i);
        List<MuseumRecords.Notes> years = new ArrayList<>(book.notes);
        long founded = com.jrpetty.mcassistant.village.Chronicle.foundedOn(id);
        int year = Archive.yearOf(id, day);
        if (year > 0 && founded >= 0) {
            MuseumRecords.Notes now = new MuseumRecords.Notes();
            now.year = year;
            now.from = Archive.yearStart(founded, year);
            now.to = now.from + Archive.YEAR_DAYS - 1;
            for (com.jrpetty.mcassistant.village.Chronicle.Entry en : com.jrpetty.mcassistant.village.Chronicle.of(id)) {
                if (en.day() >= now.from && en.day() <= now.to) now.entries.add(en);
            }
            years.add(now);
        }
        String author = curator != null ? curator.displayNameCap() : book.curatorName;
        int bound = 0;
        for (MuseumRecords.Notes notes : years) {
            List<List<String>> books = Archive.layout(Villages.name(id), notes, author, Villages.elderName(id), day);
            for (int i = 0; i < books.size(); i++) {
                String title = Archive.title(Villages.name(id), notes.year, i + 1, books.size());
                String where = Archive.shelveNewest(level, b, book, Archive.book(title, author, 0, books.get(i)), curator);
                if (where.isEmpty()) continue;
                MuseumRecords.Volume rec = new MuseumRecords.Volume();
                rec.year = notes.year;
                rec.part = i + 1;
                rec.parts = books.size();
                rec.title = title;
                rec.author = author;
                rec.from = notes.from;
                rec.to = notes.to;
                rec.written = day;
                rec.entries = notes.entries.size();
                rec.where = where;
                rec.pages.addAll(books.get(i));
                book.volumes.add(rec);
                bound++;
            }
        }
        book.notes.clear();
        MuseumRecords.touch();
        archiveSign(level, b, id, true);
        List<String> out = new ArrayList<>();
        out.add(at.getX() + " " + at.getY() + " " + at.getZ() + " facing " + back.getOpposite().getName() + ", " + n + " on show, "
            + bound + " volumes, for " + Villages.name(id));
        // The whole front from out on the forecourt, a little to the right of the middle: the plinth and its
        // stair, the columns standing out from the wall, the banners, the name over the door, the pediment.
        out.add(view(b, "m1-front", 3.5, 0.5, -16, 0.5, 3.6, -5));
        out.add(view(b, "m2-hall", 0.5, 0, -2.6, 0.5, 1.4, 3));
        out.add(view(b, "m3-frames", 2.0, 0, 1.2, -3, 1.2, 1));
        out.add(view(b, "m4-label", -1.1, -0.7, 2, -3, 0.8, 2));
        out.add(view(b, "m5-case", -2, 1.0, -1.6, -2, -1.6, 0));
        out.add(view(b, "m6-archive", 0.5, -0.3, 0.6, 0.5, 0.8, 3));
        out.add(view(b, "m7-stands", 0.5, 0, 1.5, 0.5, 0.9, -3));
        return out;
    }

    /** "VIEW name x y z tx ty tz": a camera's feet and what it looks at, from the hall's across, up and back. */
    private static String view(Ledger.Building b, String name, double dx, double h, double dz, double tx, double th, double tz) {
        double[] from = spot(b, dx, h, dz), to = spot(b, tx, th, tz);
        return String.format(Locale.ROOT, "VIEW %s %.2f %.2f %.2f %.2f %.2f %.2f", name, from[0], from[1], from[2], to[0], to[1], to[2]);
    }

    private static double[] spot(Ledger.Building b, double dx, double h, double dz) {
        Direction r = b.facing().getClockWise(), f = b.facing();
        int[] o = hall(b);
        h += o[0];
        dz += o[1];
        double x = b.anchor().getX() + 0.5 + r.getStepX() * dx + f.getStepX() * dz;
        double z = b.anchor().getZ() + 0.5 + r.getStepZ() * dx + f.getStepZ() * dz;
        return new double[]{ x, b.anchor().getY() + h, z };
    }

    // ------------------------------------------------------------------ the sea's treasure

    /**
     * Now and then a fisher's line brings up treasure, as a player's does (FishGoal): a saddle, a
     * nautilus shell, a name tag, or an enchanted book. Nothing made: what the water gives.
     */
    public static ItemStack treasure(ServerLevel level, RandomSource r) {
        int roll = r.nextInt(4);
        if (roll == 0) return new ItemStack(Items.SADDLE);
        if (roll == 1) return new ItemStack(Items.NAUTILUS_SHELL);
        if (roll == 2) return new ItemStack(Items.NAME_TAG);
        ItemStack book = net.minecraft.world.item.enchantment.EnchantmentHelper.enchantItem(r, new ItemStack(Items.BOOK), 30,
            level.registryAccess(), java.util.Optional.empty());
        return book.isEmpty() ? new ItemStack(Items.SADDLE) : book;
    }

    static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
