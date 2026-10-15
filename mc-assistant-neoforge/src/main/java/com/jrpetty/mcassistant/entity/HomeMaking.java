package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [home-making] Every household furnishes its own home, by its own hands.
 *
 * <p>When a household moves in (a new house or a flat, a manor bought, a couple newly wed, a grown child's first place,
 * a family come from away) it makes a plan for its home, kept in the town's ledger over a restart: what the rooms will
 * take (the drawing says where the floor is, which walls stand, where the door and the beds are), and what this
 * household wants of them by its size, its trades, its tastes and its means.
 * <ul>
 * <li><b>The basics first, for every household however poor:</b> a table and a seat at it for each of them (a fence
 *     post with a board on top, and stairs turned to face it), a chest for their things, a larder barrel, a light, and
 *     a stove where the house has a hearth. A bed each is the house's own (Homes), and whatever the builders already set
 *     in the house (the drawing's chest, its stove, the torch on the bench) counts as there.</li>
 * <li><b>Then their own things, as their means run to it:</b> a water butt (a cauldron) once they are getting by, the
 *     things of each grown member's trade (Decor: the smith's anvil, the farmer's composter, a pick on the miner's
 *     wall), a rug in their colour and a banner in the partner's once they are comfortable, and what they love: a
 *     reader's bookshelf, a musician's note block, a gardener's pot of flowers. The comforts after that (carpets,
 *     paintings, glass, candles: Luxuries) a comfortable folk goes on buying with its savings as before.</li>
 * </ul>
 * Each piece is got honestly, the cheapest honest way to hand: brought from its old home, taken out of the household's
 * own chest, made at a bench by one of them with the skill and the stuff (planks of its own, or bought for it: a
 * woodcutter's household makes its own table and stools), bought at the shop out of its own purse (ShopDesk.folkBuy,
 * the counter every sale goes through), given (a player's present), or, for a household with nothing and nobody to make
 * it, the parish's handout out of the town's stores: nobody is left with an empty home. Nothing comes from nothing.
 *
 * <p>Getting a piece and setting it out is a visible job in a folk's own time (after work, on the rest day): it walks to
 * the shop or its bench, comes home with the piece in its hands and sets it where the plan says, the right way round
 * (seats facing the table, a chest's and a stove's front to the room, nothing in a doorway, across the way through, in
 * front of a stove or a chest, or in the way of a bed), and says so ("There. That's the table sorted.").
 *
 * <p>The town's own furnishing (Interiors, Decor) no longer touches a home with a household in it: it keeps to the
 * halls, the shops and the homes standing empty for their next tenants. A household that moves takes its things with
 * it (what its new home wants is carried over and set out; what it does not is left with the old house for whoever
 * comes next, or put away), the things of one who dies pass to its family, and anything broken or burnt is made good by
 * the household itself on its next round.
 */
public final class HomeMaking {

    private HomeMaking() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    static final long NONE = Long.MIN_VALUE;

    // ------------------------------------------------------------------ what a home wants

    /** The order a household sees to its home in. */
    enum Stage {
        BASIC("the basics"), OWN("its own things");

        final String words;

        Stage(String words) {
            this.words = words;
        }
    }

    /** How a piece stands in a room. */
    enum Shape {
        /** On the floor against a wall, its front to the room: a chest, the larder, the stove, an anvil. */
        WALL,
        /** A post with a board on it, out in the room with room round it for the seats. */
        TABLE,
        /** A stair beside the table, its back away from it: a seat facing the table. */
        SEAT,
        /** A torch on a wall, a lantern on something. */
        LIGHT,
        /** On top of something standing (a barrel, a chest, the bench): a lantern, a pot of flowers. */
        TOP,
        /** A tool of the trade in a frame on the wall. */
        FRAME,
        /** An armour stand against a wall. */
        STAND,
        /** A rug on the floor by the bed. */
        RUG,
        /** A banner on the wall. */
        BANNER
    }

    /** One thing a piece is made of: what will do, the one bought when none is to hand, how many, in words. */
    record Need(Predicate<ItemStack> what, Item sample, int n, String words) {}

    /**
     * A piece of a home's plan: its key, stage and shape, what it is made of, its name, the means a household wants
     * before it goes after it, and its key in the house's book (Decor's: its trade's things, its colours, its luxuries)
     * or null.
     */
    record Spec(String key, Stage stage, Shape shape, List<Need> needs, String words, Wealth.Tier from, @Nullable String book) {}

    static final Need FENCE = new Need(s -> s.is(ItemTags.WOODEN_FENCES), Items.OAK_FENCE, 1, "a fence post");
    static final Need PLATE = new Need(s -> s.is(ItemTags.WOODEN_PRESSURE_PLATES), Items.OAK_PRESSURE_PLATE, 1, "a board for the top");
    static final Need STAIR = new Need(s -> s.is(ItemTags.WOODEN_STAIRS), Items.OAK_STAIRS, 1, "a stair to sit on");
    static final Need CHEST = new Need(s -> s.is(Items.CHEST), Items.CHEST, 1, "a chest");
    static final Need BARREL = new Need(s -> s.is(Items.BARREL), Items.BARREL, 1, "a barrel");
    static final Need LIGHT = new Need(s -> s.is(Items.TORCH) || s.is(Items.LANTERN), Items.TORCH, 1, "a torch");
    static final Need STOVE = new Need(s -> s.is(Items.FURNACE) || s.is(Items.SMOKER), Items.FURNACE, 1, "a stove");
    static final Need CAULDRON = new Need(s -> s.is(Items.CAULDRON), Items.CAULDRON, 1, "a cauldron");
    static final Need SHELF = new Need(s -> s.is(Items.BOOKSHELF), Items.BOOKSHELF, 1, "a bookshelf");
    static final Need NOTES = new Need(s -> s.is(Items.NOTE_BLOCK), Items.NOTE_BLOCK, 1, "a note block");
    static final Need POT = new Need(s -> s.is(Items.FLOWER_POT), Items.FLOWER_POT, 1, "a flower pot");
    static final Need FLOWER = new Need(s -> s.is(ItemTags.SMALL_FLOWERS) && Interiors.potted(s.getItem()) != null, Items.POPPY, 1, "a flower");

    /** The most seats a table is given, whatever the size of the household. */
    static final int MOST_SEATS = 4;

    /** The piece a key names, or null. */
    @Nullable
    static Spec spec(String key) {
        if (key.equals("table")) return new Spec(key, Stage.BASIC, Shape.TABLE, List.of(FENCE, PLATE), "a table", Wealth.Tier.POOR, null);
        if (key.startsWith("seat.")) return new Spec(key, Stage.BASIC, Shape.SEAT, List.of(STAIR), "a seat at the table", Wealth.Tier.POOR, null);
        switch (key) {
            case "chest": return new Spec(key, Stage.BASIC, Shape.WALL, List.of(CHEST), "a chest for their things", Wealth.Tier.POOR, null);
            case "larder": return new Spec(key, Stage.BASIC, Shape.WALL, List.of(BARREL), "a larder barrel", Wealth.Tier.POOR, null);
            case "light": return new Spec(key, Stage.BASIC, Shape.LIGHT, List.of(LIGHT), "a light", Wealth.Tier.POOR, null);
            case "stove": return new Spec(key, Stage.BASIC, Shape.WALL, List.of(STOVE), "a stove", Wealth.Tier.POOR, null);
            case "cauldron": return new Spec(key, Stage.OWN, Shape.WALL, List.of(CAULDRON), "a water butt", Wealth.Tier.GETTING_BY, null);
            case "taste.books": return new Spec(key, Stage.OWN, Shape.WALL, List.of(SHELF), "a reader's bookshelf", Wealth.Tier.COMFORTABLE, "lux.bookshelf");
            case "taste.music": return new Spec(key, Stage.OWN, Shape.WALL, List.of(NOTES), "a note block to play", Wealth.Tier.COMFORTABLE, null);
            case "taste.flowers": return new Spec(key, Stage.OWN, Shape.TOP, List.of(POT, FLOWER), "a pot of flowers", Wealth.Tier.COMFORTABLE, "lux.pot");
            default: break;
        }
        if (key.startsWith("trade.") || key.startsWith("colour.")) {
            String dk = key.startsWith("trade.") ? key.substring(6) : key;
            Decor.Piece p = Decor.piece(dk);
            if (p == null) return null;
            Shape shape = switch (p.shape()) {
                case FLOOR, BARREL -> Shape.WALL;
                case LAMP -> Shape.TOP;
                case FRAME -> Shape.FRAME;
                case STAND -> Shape.STAND;
                case RUG -> Shape.RUG;
                case BANNER -> Shape.BANNER;
            };
            List<Need> needs = new ArrayList<>();
            needs.add(new Need(p.what(), p.item(), 1, p.words()));
            if (p.shape() == Decor.Shape.FRAME && p.inside() != null) needs.add(new Need(p.insideWhat(), p.inside(), 1, "something to hang in it"));
            Wealth.Tier from = key.startsWith("trade.") ? Wealth.Tier.GETTING_BY
                : key.startsWith("colour.banner.") ? Wealth.Tier.WELL_OFF : Wealth.Tier.COMFORTABLE;
            return new Spec(key, Stage.OWN, shape, List.copyOf(needs), p.words(), from, dk);
        }
        return null;
    }

    // ------------------------------------------------------------------ the plan

    /** Where a piece is in the getting of it. */
    enum State { WANTED, WAITING, PLACED, HOUSE, NO_ROOM }

    /** How a piece came to the home (a letter in the ledger, the words on the card). */
    enum How {
        MADE('M', "made"), BOUGHT('B', "bought"), CHEST('C', "out of its own chest"), BROUGHT('F', "brought from its old home"),
        GIFT('G', "a gift"), PARISH('P', "the parish's"), ISSUED('I', "fitted out by the town, before it had a shop"),
        HOUSE('H', "the house's own"), CAME('W', "came with the house"), NONE('-', "");

        final char code;
        final String words;

        How(char code, String words) {
            this.code = code;
            this.words = words;
        }

        static How of(char c) {
            for (How h : values()) if (h.code == c) return h;
            return NONE;
        }

        /** Got by the household's own hands (not the builders', not the last household's). */
        boolean own() {
            return this != HOUSE && this != CAME && this != NONE;
        }
    }

    /** One piece of a household's plan, and where it is in the getting of it. */
    static final class Piece {
        final String key;
        State state = State.WANTED;
        long pos = NONE;
        How how = How.NONE;
        String by = "";
        @Nullable UUID owner;
        long day = -1;
        String note = "";
        /** Who of the household is seeing to it now, and till when (not saved: a restart starts the errand afresh). */
        @Nullable UUID claim;
        long claimUntil;
        /** Not to be looked at again before this game time (waiting on the shop, on coin, on room). */
        long retry;

        Piece(String key) {
            this.key = key;
        }

        @Nullable
        Spec spec() {
            return HomeMaking.spec(key);
        }

        boolean done() {
            return state == State.PLACED || state == State.HOUSE || state == State.NO_ROOM;
        }

        boolean standing() {
            return (state == State.PLACED || state == State.HOUSE) && pos != NONE;
        }
    }

    /** A household's plan for its home. */
    static final class Plan {
        final UUID village;
        final long anchor;
        long since = -1;
        /** Told the chronicle it set about its home; told it the home is set up. */
        boolean told, settled;
        /** The home it came from, to bring its things from (NONE: nowhere). */
        long from = NONE;
        final List<UUID> household = new ArrayList<>();
        final List<Piece> pieces = new ArrayList<>();
        /** Game time of its last look (not saved). */
        long looked = -100000L;

        Plan(UUID village, long anchor) {
            this.village = village;
            this.anchor = anchor;
        }

        @Nullable
        Piece piece(String key) {
            for (Piece p : pieces) if (p.key.equals(key)) return p;
            return null;
        }
    }

    private static final Map<String, Plan> PLANS = new ConcurrentHashMap<>();
    /** The home a household has just moved out of, by the anchor of the one it moved into. */
    private static final Map<String, Long> CAME_FROM = new ConcurrentHashMap<>();

    private static String id(UUID village, long anchor) {
        return village + "/" + anchor;
    }

    private static String note(long anchor) {
        return "homeplan/" + anchor;
    }

    /** A home's plan as the ledger has it (null: none yet). */
    @Nullable
    static Plan load(UUID village, long anchor) {
        String k = id(village, anchor);
        Plan p = PLANS.get(k);
        if (p != null) return p;
        String s = Ledger.note(village, note(anchor));
        if (s == null || s.isEmpty()) return null;
        p = decode(village, anchor, s);
        if (p != null) PLANS.put(k, p);
        return p;
    }

    @Nullable
    private static Plan decode(UUID village, long anchor, String s) {
        try {
            String[] f = s.split(";", -1);
            Plan p = new Plan(village, anchor);
            p.since = Long.parseLong(f[0]);
            int flags = Integer.parseInt(f[1]);
            p.told = (flags & 1) != 0;
            p.settled = (flags & 2) != 0;
            p.from = f[2].isEmpty() ? NONE : Long.parseLong(f[2]);
            if (!f[3].isEmpty()) for (String u : f[3].split(",")) p.household.add(UUID.fromString(u));
            if (f.length > 4 && !f[4].isEmpty()) {
                for (String part : f[4].split("\\|")) {
                    String[] x = part.split("~", -1);
                    if (x.length < 8) continue;
                    Piece q = new Piece(x[0]);
                    q.state = State.valueOf(x[1]);
                    q.pos = x[2].isEmpty() ? NONE : Long.parseLong(x[2]);
                    q.how = How.of(x[3].isEmpty() ? '-' : x[3].charAt(0));
                    q.by = x[4];
                    q.owner = x[5].isEmpty() ? null : UUID.fromString(x[5]);
                    q.day = Long.parseLong(x[6]);
                    q.note = x[7];
                    if (q.state == State.WAITING) q.state = State.WANTED;       // looked at afresh after a restart
                    p.pieces.add(q);
                }
            }
            return p;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String clean(String s) {
        return s.replace("~", "-").replace("|", "/").replace(";", ",");
    }

    static void save(Plan p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.since).append(';').append((p.told ? 1 : 0) | (p.settled ? 2 : 0)).append(';')
            .append(p.from == NONE ? "" : Long.toString(p.from)).append(';');
        for (int i = 0; i < p.household.size(); i++) sb.append(i == 0 ? "" : ",").append(p.household.get(i));
        sb.append(';');
        for (int i = 0; i < p.pieces.size(); i++) {
            Piece q = p.pieces.get(i);
            if (i > 0) sb.append('|');
            sb.append(q.key).append('~').append(q.state == State.WAITING ? State.WANTED : q.state).append('~')
                .append(q.pos == NONE ? "" : Long.toString(q.pos)).append('~').append(q.how.code).append('~').append(clean(q.by)).append('~')
                .append(q.owner == null ? "" : q.owner.toString()).append('~').append(q.day).append('~').append(clean(q.note));
        }
        String now = sb.toString();
        if (!now.equals(Ledger.note(p.village, note(p.anchor)))) Ledger.note(p.village, note(p.anchor), now);   // written only when it changed
        PLANS.put(id(p.village, p.anchor), p);
    }

    public static synchronized void resetForTests() {
        PLANS.clear();
        CAME_FROM.clear();
        HEARTHS.clear();
        JOBS.clear();
        NEXT_LOOK.clear();
        TICKED.clear();
        INSTANT = false;
    }

    // ------------------------------------------------------------------ the household and its means

    static List<VillageFolkEntity> grown(List<VillageFolkEntity> household) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (VillageFolkEntity f : household) if (!f.isBaby()) out.add(f);
        return out;
    }

    /** What the household can run to: the means of the best-off of its grown folk. */
    static Wealth.Tier means(List<VillageFolkEntity> household) {
        Wealth.Tier best = Wealth.Tier.POOR;
        for (VillageFolkEntity f : grown(household)) {
            Wealth.Tier t = Wealth.tier(f);
            if (t.ordinal() > best.ordinal()) best = t;
        }
        return best;
    }

    /** With nothing to buy with: every grown folk of it poor, with next to nothing in its purse. */
    static boolean destitute(List<VillageFolkEntity> household) {
        List<VillageFolkEntity> grown = grown(household);
        if (grown.isEmpty()) return false;
        for (VillageFolkEntity f : grown) if (Wealth.tier(f) != Wealth.Tier.POOR || f.purse() > 4) return false;
        return true;
    }

    /** Is this thing the household's own (a keepsake of one of them)? */
    static boolean ours(ItemStack s, Homes.Home h) {
        UUID o = Homes.ownerOf(s);
        return o != null && h.members.contains(o);
    }

    // ------------------------------------------------------------------ making the plan

    /**
     * What this household wants of its home, in the order it sees to it: the basics, then its own things. The size of
     * the table is the size of the household (four seats at most); a stove only where the house has a hearth.
     */
    static List<String> wanted(ServerLevel level, UUID village, Homes.Home h, List<VillageFolkEntity> household, boolean hearth) {
        List<String> out = new ArrayList<>();
        out.add("table");
        int seats = Math.max(1, Math.min(MOST_SEATS, Math.max(household.size(), h.members.size())));
        for (int i = 0; i < seats; i++) out.add("seat." + i);
        out.add("larder");
        out.add("chest");
        out.add("light");
        if (hearth) out.add("stove");
        if (Villages.ageOf(village).ordinal() >= Villages.Age.IRON.ordinal()) out.add("cauldron");   // iron for a water butt: the Iron Age's
        List<VillageFolkEntity> grown = grown(household);
        Set<StationTask> trades = new HashSet<>();
        for (VillageFolkEntity f : grown) {
            if (!trades.add(f.stationTask())) continue;
            List<Decor.Piece> mine = Decor.piecesFor(f.stationTask());
            if (!mine.isEmpty()) out.add("trade." + mine.get(0).key());
        }
        if (!grown.isEmpty()) {
            DyeColor own = Decor.colour(grown.get(0));
            out.add("colour.rug." + own.getName());
            out.add("colour.banner." + (grown.size() > 1 ? Decor.colour(grown.get(1)) : own).getName());
        }
        boolean books = false, music = false, flowers = false;
        for (VillageFolkEntity f : grown) {
            Persona p = f.persona();
            books |= p.loves() == Persona.Gift.BOOKS || p.hobby() == Persona.Hobby.READING;
            music |= p.loves() == Persona.Gift.MUSIC || p.hobby() == Persona.Hobby.MUSIC;
            flowers |= p.loves() == Persona.Gift.FLOWERS || p.hobby() == Persona.Hobby.GARDENING;
        }
        if (books) out.add("taste.books");
        if (music) out.add("taste.music");
        if (flowers) out.add("taste.flowers");
        return out;
    }

    /**
     * The household's plan for its home, made when it moves in and kept up to date as it changes (a child born wants
     * a seat; a trade taken up its things). A new household in a home another left: what the last one left standing
     * came with the house.
     */
    static Plan planFor(ServerLevel level, Villages.Village v, Homes.Home h) {
        UUID id = v.id();
        long anchor = h.anchor.asLong();
        Plan p = load(id, anchor);
        List<VillageFolkEntity> household = Homes.loadedMembers(id, h);
        long day = level.getDayTime() / 24000L;
        boolean fresh = p == null;
        if (p != null) {
            boolean same = false;
            for (UUID u : p.household) if (h.members.contains(u)) { same = true; break; }
            if (!same) {
                // Somebody new: what the last household left standing is the house's now, to be used.
                Plan next = new Plan(id, anchor);
                for (Piece q : p.pieces) {
                    if (!q.standing()) continue;
                    q.state = State.HOUSE;
                    q.how = How.CAME;
                    q.owner = null;
                    q.note = "";
                    next.pieces.add(q);
                }
                p = next;
                fresh = true;
            }
        }
        if (p == null) p = new Plan(id, anchor);
        if (fresh) {
            p.since = day;
            Long from = CAME_FROM.remove(id(id, anchor));
            p.from = from == null ? NONE : from;
        }
        p.household.clear();
        p.household.addAll(h.members);
        if (!household.isEmpty()) {
            Rooms rooms = rooms(level, id, h);
            List<String> want = wanted(level, id, h, household, hearth(level, id, h, rooms));
            List<Piece> ordered = new ArrayList<>();
            for (String k : want) {
                Piece q = p.piece(k);
                ordered.add(q != null ? q : new Piece(k));
            }
            // What stands that the plan no longer asks for (a seat for one gone, the last household's things) is kept.
            for (Piece q : p.pieces) if (!want.contains(q.key) && q.standing()) ordered.add(q);
            p.pieces.clear();
            p.pieces.addAll(ordered);
        }
        if (fresh && !household.isEmpty() && !p.told) {
            p.told = true;
            Villages.tell(id, day, Homes.names(household) + " set about making a home of " + Homes.address(id, v, h)
                + (p.from != NONE ? ", bringing their things" : ""));
        }
        save(p);
        return p;
    }

    // ------------------------------------------------------------------ the rooms

    /** A home's rooms: its floor, what is kept clear (the doorway and the way in), and its door. */
    record Rooms(List<BlockPos> floor, Set<BlockPos> floorSet, Set<BlockPos> clear, @Nullable BlockPos door, boolean drawn) {}

    /** The rooms of a home: its drawing's (Decor.room), or, for a home with no drawing of its own (a flat), as it stands. */
    static Rooms rooms(ServerLevel level, UUID village, Homes.Home h) {
        Ledger.Building b = Homes.building(village, h.anchor);
        if (b != null && !Flats.isFlat(h)) {
            Decor.Room r = Decor.room(village, b);
            if (!r.floor().isEmpty()) return new Rooms(r.floor(), r.floorSet(), r.clear(), r.door(), true);
        }
        // As it stands: every cell of the home that is roofed over, with a sound floor under it and room for a body.
        List<BlockPos> floor = new ArrayList<>();
        Set<BlockPos> clear = new HashSet<>();
        BlockPos door = null;
        for (BlockPos p : BlockPos.betweenClosed(h.anchor.offset(-6, -1, -6), h.anchor.offset(6, 3, 6))) {
            if (!level.isLoaded(p) || Homes.homeAt(village, p) != h) continue;
            BlockState st = level.getBlockState(p);
            if (st.getBlock() instanceof DoorBlock) {
                if (door == null || p.getY() < door.getY()) door = p.immutable();
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) clear.add(p.offset(dx, 0, dz));
                continue;
            }
            BlockPos below = p.below();
            if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP) || level.canSeeSky(p)) continue;
            if (!level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty() && !ourKind(level.getBlockState(p.above()))) continue;
            if (!st.isAir() && !ourKind(st)) continue;
            floor.add(p.immutable());
        }
        return new Rooms(List.copyOf(floor), Set.copyOf(floor), Set.copyOf(clear), door, false);
    }

    /** The household furniture this class sets out, which a room standing as it is may have in it. */
    private static boolean ourKind(BlockState s) {
        Block b = s.getBlock();
        return b instanceof FenceBlock || b instanceof PressurePlateBlock || b instanceof StairBlock || b instanceof ChestBlock
            || b instanceof BarrelBlock || b instanceof AbstractFurnaceBlock || s.is(BlockTags.WOOL_CARPETS) || s.is(Blocks.CAULDRON)
            || s.is(Blocks.WATER_CAULDRON);
    }

    /** Does the home have a hearth: a stove standing, or one in its drawing, or a chimney? */
    static boolean hearth(ServerLevel level, UUID village, Homes.Home h, Rooms rooms) {
        if (stoveIn(level, rooms) != null) return true;
        Ledger.Building b = Homes.building(village, h.anchor);
        if (b == null || Flats.isFlat(h)) return false;
        String drawing = Homes.drawing(village, h);
        return HEARTHS.computeIfAbsent(h.anchor.asLong() + ":" + drawing + ":" + b.facing(), k -> {
            for (BuildGoal.Placement p : BuildGoal.plan(drawing, b.anchor(), b.facing(), 13)) {
                switch (p.part()) {
                    case FURNACE, SMOKER, CAMPFIRE -> { return true; }
                    case BLOCK -> { if (p.style() == com.jrpetty.mcassistant.entity.goal.Blueprints.Style.BRICK) return true; }
                    default -> { }
                }
            }
            return false;
        });
    }

    /** Whether each drawing has a hearth, worked out once (by anchor, drawing and facing). */
    private static final Map<String, Boolean> HEARTHS = new ConcurrentHashMap<>();

    @Nullable
    private static BlockPos stoveIn(ServerLevel level, Rooms rooms) {
        for (BlockPos c : rooms.floor()) {
            for (BlockPos n : new BlockPos[]{ c, c.north(), c.south(), c.east(), c.west() }) {
                if (!level.isLoaded(n)) continue;
                Block b = level.getBlockState(n).getBlock();
                if (b instanceof AbstractFurnaceBlock || b instanceof CampfireBlock) return n.immutable();
            }
        }
        return null;
    }

    /** A light in the home: a torch, a lantern, a lit candle, by a floor cell or above it. */
    @Nullable
    private static BlockPos lightIn(ServerLevel level, Rooms rooms) {
        for (BlockPos c : rooms.floor()) {
            for (int up = 0; up <= 2; up++) {
                BlockPos a = c.above(up);
                if (level.isLoaded(a) && level.getBlockState(a).getLightEmission() >= 10) return a.immutable();
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos a = c.above().relative(d);
                if (level.isLoaded(a) && level.getBlockState(a).getLightEmission() >= 10) return a.immutable();
            }
        }
        return null;
    }

    /** A table standing in the home: a post with a board or a cloth on it. */
    @Nullable
    private static BlockPos tableIn(ServerLevel level, Rooms rooms) {
        for (BlockPos c : rooms.floor()) {
            if (!level.isLoaded(c) || !(level.getBlockState(c).getBlock() instanceof FenceBlock)) continue;
            BlockState top = level.getBlockState(c.above());
            if (top.getBlock() instanceof PressurePlateBlock || top.is(BlockTags.WOOL_CARPETS)) return c.immutable();
        }
        return null;
    }

    // ------------------------------------------------------------------ keeping the plan true

    /**
     * The plan set against the home as it stands: a piece broken, burnt or carried off is wanted again (the household
     * makes it good); what the house has of the basics already (the builders' chest, stove and torch) is there; the
     * things of one who has died pass to its family. And when the basics are all in, the home is set up.
     */
    static void refresh(ServerLevel level, Villages.Village v, Homes.Home h, Plan p) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Rooms rooms = rooms(level, id, h);
        List<VillageFolkEntity> household = Homes.loadedMembers(id, h);
        Set<Long> used = new HashSet<>();
        for (Piece q : p.pieces) if (q.standing()) used.add(q.pos);
        boolean changed = false;
        for (Piece q : p.pieces) {
            if (q.standing() && level.isLoaded(BlockPos.of(q.pos)) && !present(level, q)) {
                String was = q.how == How.HOUSE ? "the house's own " : "";
                used.remove(q.pos);
                q.state = State.WANTED;
                q.note = "lost; to be made good";
                q.pos = NONE;
                q.how = How.NONE;
                q.retry = 0;
                changed = true;
                Spec s = q.spec();
                for (VillageFolkEntity f : grown(household)) {
                    f.brain("found " + was + (s == null ? q.key : s.words()) + " gone from its home: to be made good");
                    break;
                }
            }
            if (q.state == State.NO_ROOM && q.day < day) { q.state = State.WANTED; q.retry = 0; changed = true; }  // looked for room afresh
            if (q.done() || q.key.startsWith("seat.")) continue;      // seats: by the table, below
            BlockPos there = switch (q.key) {
                case "chest" -> boxOf(level, id, h, HomeStores.Kind.CHEST, used);
                case "larder" -> boxOf(level, id, h, HomeStores.Kind.LARDER, used);
                case "stove" -> stoveIn(level, rooms);
                case "light" -> lightIn(level, rooms);
                case "table" -> tableIn(level, rooms);
                case "cauldron" -> cauldronIn(level, rooms);
                default -> null;
            };
            if (there != null && !used.contains(there.asLong())) {
                q.state = State.HOUSE;
                q.pos = there.asLong();
                q.how = How.HOUSE;
                q.note = "";
                used.add(there.asLong());
                changed = true;
            }
        }
        // Seats standing at the table already (the last household's, or put there by a player) count as there.
        Piece table = p.piece("table");
        if (table != null && table.standing()) {
            BlockPos t = BlockPos.of(table.pos);
            for (Piece q : p.pieces) {
                if (!q.key.startsWith("seat.") || q.done()) continue;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    BlockPos s = t.relative(d);
                    if (used.contains(s.asLong()) || !level.isLoaded(s)) continue;
                    BlockState st = level.getBlockState(s);
                    if (st.getBlock() instanceof StairBlock && st.getValue(StairBlock.HALF) == Half.BOTTOM) {
                        q.state = State.HOUSE;
                        q.pos = s.asLong();
                        q.how = How.HOUSE;
                        used.add(s.asLong());
                        changed = true;
                        break;
                    }
                }
            }
        }
        // The things of one gone pass to its family.
        Set<UUID> living = new HashSet<>();
        for (AssistantEntity a : Villages.folkOf(id)) if (a.isAlive()) living.add(a.getUUID());
        VillageFolkEntity heir = grown(household).isEmpty() ? null : grown(household).get(0);
        for (Piece q : p.pieces) {
            if (q.owner == null || living.contains(q.owner) || !q.standing()) continue;
            if (Villages.folkOf(id).isEmpty()) continue;                       // the town not loaded yet: nobody is known dead
            q.owner = heir == null ? null : heir.getUUID();
            q.note = heir == null ? "left with the house" : "passed to " + heir.displayNameCap();
            changed = true;
        }
        if (!p.settled && basicsDone(p)) {
            p.settled = true;
            changed = true;
            int own = 0;
            List<String> words = new ArrayList<>();
            for (Piece q : p.pieces) {
                Spec s = q.spec();
                if (s == null || s.stage() != Stage.BASIC || q.state != State.PLACED) continue;
                if (q.how.own()) own++;
                if (!q.key.startsWith("seat.") || q.key.equals("seat.0")) words.add(q.key.startsWith("seat.") ? "seats" : s.words().replaceFirst("^an? ", ""));
            }
            if (!household.isEmpty()) {
                Villages.tell(id, day, Homes.names(household) + " have their home at " + Homes.address(id, v, h) + " set up"
                    + (own > 0 ? ": " + String.join(", ", words) + ", got by their own hands" : ""));
                for (VillageFolkEntity f : grown(household)) f.persona().remember(day, "we set up our home ourselves", 4);
            }
        }
        if (changed) save(p);
    }

    @Nullable
    private static BlockPos boxOf(ServerLevel level, UUID village, Homes.Home h, HomeStores.Kind kind, Set<Long> used) {
        HomeStores.resetFor(h);
        for (HomeStores.Box b : HomeStores.of(level, village, h)) {
            if (b.kind() == kind && !used.contains(b.pos().asLong())) return b.pos();
        }
        return null;
    }

    @Nullable
    private static BlockPos cauldronIn(ServerLevel level, Rooms rooms) {
        for (BlockPos c : rooms.floor()) {
            for (BlockPos n : new BlockPos[]{ c, c.north(), c.south(), c.east(), c.west() }) {
                if (!level.isLoaded(n)) continue;
                BlockState s = level.getBlockState(n);
                if (s.is(Blocks.CAULDRON) || s.is(Blocks.WATER_CAULDRON)) return n.immutable();
            }
        }
        return null;
    }

    /** Are the basics in: every one standing (or no room for it), the table and a seat at it among them? */
    static boolean basicsDone(Plan p) {
        boolean table = false, seat = false;
        for (Piece q : p.pieces) {
            Spec s = q.spec();
            if (s == null || s.stage() != Stage.BASIC) continue;
            if (!q.done()) return false;
            if (q.key.equals("table") && q.standing()) table = true;
            if (q.key.startsWith("seat.") && q.standing()) seat = true;
        }
        return table && seat;
    }

    /** Is the piece the plan has at its spot still there? */
    static boolean present(ServerLevel level, Piece q) {
        if (q.pos == NONE) return false;
        BlockPos at = BlockPos.of(q.pos);
        if (!level.isLoaded(at)) return true;
        Spec s = q.spec();
        if (s == null) return true;
        BlockState st = level.getBlockState(at);
        return switch (s.shape()) {
            case TABLE -> st.getBlock() instanceof FenceBlock;
            case SEAT -> st.getBlock() instanceof StairBlock;
            case LIGHT -> st.getLightEmission() >= 10 || st.getBlock() instanceof LanternBlock;
            case FRAME -> !level.getEntitiesOfClass(ItemFrame.class, new AABB(at), e -> e.getTags().contains(Decor.TAG)).isEmpty();
            case STAND -> !level.getEntitiesOfClass(ArmorStand.class, new AABB(at).inflate(0.2), e -> e.getTags().contains(Decor.TAG)).isEmpty();
            case RUG -> st.is(BlockTags.WOOL_CARPETS);
            case BANNER -> st.getBlock() instanceof WallBannerBlock;
            case TOP -> !st.isAir();
            case WALL -> {
                if (q.key.equals("cauldron")) yield st.is(Blocks.CAULDRON) || st.is(Blocks.WATER_CAULDRON);
                if (q.key.equals("stove")) yield st.getBlock() instanceof AbstractFurnaceBlock || st.getBlock() instanceof CampfireBlock;
                if (q.key.equals("chest")) yield st.getBlock() instanceof ChestBlock;
                if (q.key.equals("larder")) yield st.getBlock() instanceof BarrelBlock;
                yield !st.isAir() && s.needs().get(0).what().test(new ItemStack(st.getBlock().asItem()));
            }
        };
    }

    // ------------------------------------------------------------------ where a piece goes

    /** Where a piece goes: the cell, and the way it faces (into the room; a seat, away from its table). */
    record Spot(BlockPos at, Direction facing) {}

    private static boolean sturdyUnder(ServerLevel level, BlockPos c) {
        BlockPos d = c.below();
        return level.getBlockState(d).isFaceSturdy(level, d, Direction.UP);
    }

    private static boolean byADoor(ServerLevel level, BlockPos c) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(c.relative(d)).getBlock() instanceof DoorBlock) return true;
            if (level.getBlockState(c.above().relative(d)).getBlock() instanceof DoorBlock) return true;
        }
        return false;
    }

    /** Something with a front a body stands at to use it (an oven's mouth, a chest's lid, a lectern's page). */
    private static boolean hasAFront(Block b) {
        return b instanceof AbstractFurnaceBlock || b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof LecternBlock
            || b instanceof LoomBlock || b instanceof GrindstoneBlock || b instanceof StonecutterBlock
            || b instanceof net.minecraft.world.level.block.CartographyTableBlock || b instanceof net.minecraft.world.level.block.SmithingTableBlock
            || b instanceof net.minecraft.world.level.block.EnderChestBlock;
    }

    /** Would something here stand in front of a stove's or a chest's front (the cell its user stands in)? */
    private static boolean inFrontOfSomething(ServerLevel level, BlockPos c) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = c.relative(d);
            BlockState s = level.getBlockState(n);
            if (!hasAFront(s.getBlock())) continue;
            Direction face = s.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? s.getValue(BlockStateProperties.HORIZONTAL_FACING)
                : s.hasProperty(BlockStateProperties.FACING) ? s.getValue(BlockStateProperties.FACING) : null;
            if (face == null || face.getAxis().isVertical()) {
                // A chest's lid, a barrel stood on end: a body uses it from any side, but not from where nothing can stand.
                continue;
            }
            if (face == d.getOpposite()) return true;
        }
        return false;
    }

    /**
     * Would something here take the last way into a bed: is this cell beside a bed whose every other free cell of
     * floor alongside it is gone? A bed is got into from the floor beside it or at its foot.
     */
    private static boolean lastWayToABed(ServerLevel level, Rooms rooms, BlockPos c) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = c.relative(d);
            BlockState s = level.getBlockState(n);
            if (!(s.getBlock() instanceof BedBlock)) continue;
            Direction lie = s.getValue(BedBlock.FACING);
            BlockPos other = s.getValue(BedBlock.PART) == BedPart.FOOT ? n.relative(lie) : n.relative(lie.getOpposite());
            int ways = 0;
            for (BlockPos half : new BlockPos[]{ n, other }) {
                for (Direction e : Direction.Plane.HORIZONTAL) {
                    BlockPos w = half.relative(e);
                    if (w.equals(c) || w.equals(n) || w.equals(other)) continue;
                    if (!rooms.floorSet().contains(w) && !Decor.passable(level, w)) continue;
                    if (Decor.passable(level, w) && sturdyUnder(level, w)) ways++;
                }
            }
            if (ways == 0) return true;
        }
        return false;
    }

    /**
     * A cell of floor something may stand in: empty with room over it, on a sound floor, not kept clear (the doorway, the
     * way in), not by a door or a ladder, not in front of a stove or a chest, not the last way to a bed, not where it
     * would cut the room in two, and one the town's gate allows furniture in.
     */
    static boolean free(ServerLevel level, @Nullable Villages.Village v, Rooms rooms, BlockPos c, Set<BlockPos> taken) {
        if (!level.isLoaded(c) || taken.contains(c) || rooms.clear().contains(c)) return false;
        if (!level.getBlockState(c).isAir() || !level.getBlockState(c.above()).isAir() || !sturdyUnder(level, c)) return false;
        if (byADoor(level, c) || inFrontOfSomething(level, c) || lastWayToABed(level, rooms, c)) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) if (level.getBlockState(c.relative(d)).getBlock() instanceof LadderBlock) return false;
        if (Decor.cutsTheWay(level, c)) return false;
        return v == null || TownBlocks.allows(level, v, c, TownBlocks.Why.FURNISH);
    }

    /** The side of a floor cell a wall stands on now (a sound face, a wall of the room and not a piece of furniture). */
    @Nullable
    static Direction wallOf(ServerLevel level, Rooms rooms, BlockPos c) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = c.relative(d);
            if (rooms.floorSet().contains(n)) continue;
            BlockState s = level.getBlockState(n);
            Block b = s.getBlock();
            if (b instanceof BaseEntityBlock || b instanceof CraftingTableBlock || b instanceof DoorBlock || s.is(Blocks.BOOKSHELF)) continue;
            if (s.isFaceSturdy(level, n, d.getOpposite())) return d;
        }
        return null;
    }

    private static int walls(ServerLevel level, Rooms rooms, BlockPos c) {
        int n = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos w = c.relative(d);
            if (!rooms.floorSet().contains(w) && level.getBlockState(w).isFaceSturdy(level, w, d.getOpposite())) n++;
        }
        return n;
    }

    /** Where this piece goes in the home now, or null if there is no room for it. */
    @Nullable
    static Spot spotFor(ServerLevel level, Villages.Village v, Homes.Home h, Plan p, Piece q, Rooms rooms, BlockPos near) {
        Spec s = q.spec();
        if (s == null) return null;
        Set<BlockPos> taken = new HashSet<>();
        for (Piece o : p.pieces) if (o.standing()) taken.add(BlockPos.of(o.pos));
        for (Long at : Decor.book(v.id(), h.anchor).values()) taken.add(BlockPos.of(at));
        return switch (s.shape()) {
            case WALL, STAND -> wallSpot(level, v, rooms, near, taken);
            case TABLE -> tableSpot(level, v, rooms, taken);
            case SEAT -> {
                Piece t = p.piece("table");
                yield t == null || !t.standing() ? null : seatSpot(level, v, rooms, BlockPos.of(t.pos), taken);
            }
            case LIGHT -> torchSpot(level, v, rooms, near, taken);
            case TOP -> topSpot(level, rooms, near, taken);
            case FRAME -> hungSpot(level, v, rooms, near, 1, taken);
            case BANNER -> {
                Spot high = hungSpot(level, v, rooms, near, 2, taken);
                yield high != null ? high : hungSpot(level, v, rooms, near, 1, taken);
            }
            case RUG -> rugSpot(level, v, rooms, near, taken);
        };
    }

    @Nullable
    private static Spot wallSpot(ServerLevel level, Villages.Village v, Rooms rooms, BlockPos near, Set<BlockPos> taken) {
        Spot best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos c : rooms.floor()) {
            if (!free(level, v, rooms, c, taken)) continue;
            Direction wall = wallOf(level, rooms, c);
            if (wall == null) continue;
            double score = c.distSqr(near) - 3.0 * walls(level, rooms, c);
            if (score < bestScore) { bestScore = score; best = new Spot(c.immutable(), wall.getOpposite()); }
        }
        return best;
    }

    /**
     * A table's place: out in the room on the lowest floor, with free floor beside it for its seats; the more seats
     * it can have round it, and the nearer the middle of the room, the better, and never against a bed.
     */
    @Nullable
    private static Spot tableSpot(ServerLevel level, Villages.Village v, Rooms rooms, Set<BlockPos> taken) {
        int lowest = Integer.MAX_VALUE;
        for (BlockPos c : rooms.floor()) lowest = Math.min(lowest, c.getY());
        double cx = 0, cz = 0;
        int n = 0;
        for (BlockPos c : rooms.floor()) if (c.getY() == lowest) { cx += c.getX(); cz += c.getZ(); n++; }
        if (n == 0) return null;
        cx /= n;
        cz /= n;
        Spot best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos c : rooms.floor()) {
            if (c.getY() != lowest || !free(level, v, rooms, c, taken)) continue;
            boolean byBed = false;
            int seats = 0;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos s = c.relative(d);
                if (level.getBlockState(s).getBlock() instanceof BedBlock) byBed = true;
                if (rooms.floorSet().contains(s) && free(level, v, rooms, s, taken)) seats++;
            }
            if (byBed || seats == 0) continue;
            double dx = c.getX() - cx, dz = c.getZ() - cz;
            double score = dx * dx + dz * dz - 2.0 * Math.min(seats, 2);
            if (score < bestScore) { bestScore = score; best = new Spot(c.immutable(), Direction.NORTH); }
        }
        return best;
    }

    /** A seat at the table: a free cell beside it, the stair's back away from the table so its sitter faces it. */
    @Nullable
    private static Spot seatSpot(ServerLevel level, Villages.Village v, Rooms rooms, BlockPos table, Set<BlockPos> taken) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos s = table.relative(d);
            if (!rooms.floorSet().contains(s) || !free(level, v, rooms, s, taken)) continue;
            return new Spot(s.immutable(), d);
        }
        return null;
    }

    /** A torch on a wall, a body's height up, in the air over a floor cell; the nearest the middle of things. */
    @Nullable
    private static Spot torchSpot(ServerLevel level, Villages.Village v, Rooms rooms, BlockPos near, Set<BlockPos> taken) {
        Spot best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos c : rooms.floor()) {
            BlockPos at = c.above();
            if (taken.contains(at) || !level.isLoaded(at) || !level.getBlockState(at).isAir() || byADoor(level, c)) continue;
            if (!TownBlocks.allows(level, v, at, TownBlocks.Why.FURNISH)) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos w = at.relative(d);
                if (rooms.floorSet().contains(w.below())) continue;
                BlockState ws = level.getBlockState(w);
                if (ws.getBlock() instanceof DoorBlock || !ws.isFaceSturdy(level, w, d.getOpposite())) continue;
                BlockState torch = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, d.getOpposite());
                if (!torch.canSurvive(level, at)) continue;
                double score = at.distSqr(near);
                if (score < bestScore) { bestScore = score; best = new Spot(at.immutable(), d.getOpposite()); }
            }
        }
        return best;
    }

    /** The top of something standing in the home with room on it (a barrel, a chest, the bench): for a lamp or a pot. */
    @Nullable
    private static Spot topSpot(ServerLevel level, Rooms rooms, BlockPos near, Set<BlockPos> taken) {
        Spot best = null;
        double bestScore = Double.MAX_VALUE;
        Set<BlockPos> stands = new HashSet<>(rooms.floor());
        for (BlockPos c : rooms.floor()) for (Direction d : Direction.Plane.HORIZONTAL) stands.add(c.relative(d));
        for (BlockPos f : stands) {
            if (!level.isLoaded(f)) continue;
            BlockState under = level.getBlockState(f);
            Block b = under.getBlock();
            if (!(b instanceof BarrelBlock || b instanceof ChestBlock || b instanceof CraftingTableBlock || under.is(Blocks.BOOKSHELF))) continue;
            if (!under.isFaceSturdy(level, f, Direction.UP) && !(b instanceof ChestBlock)) continue;
            BlockPos top = f.above();
            if (taken.contains(top) || rooms.floorSet().contains(top) || !level.getBlockState(top).isAir()) continue;
            if (b instanceof ChestBlock) continue;                    // a chest's lid wants to open
            double score = top.distSqr(near);
            if (score < bestScore) { bestScore = score; best = new Spot(top.immutable(), Direction.NORTH); }
        }
        return best;
    }

    /** A place on a wall so many blocks above the floor for a frame or a banner, with nothing hung there already. */
    @Nullable
    private static Spot hungSpot(ServerLevel level, Villages.Village v, Rooms rooms, BlockPos near, int up, Set<BlockPos> taken) {
        Spot best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos f : rooms.floor()) {
            if (!level.isLoaded(f) || byADoor(level, f)) continue;
            BlockPos at = f.above(up);
            if (taken.contains(at) || !level.getBlockState(at).isAir()) continue;
            if (!level.getEntitiesOfClass(HangingEntity.class, new AABB(at).inflate(0.05)).isEmpty()) continue;
            if (!TownBlocks.allows(level, v, at, TownBlocks.Why.FURNISH)) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos w = at.relative(d);
                if (rooms.floorSet().contains(w.below(up))) continue;
                BlockState ws = level.getBlockState(w);
                if (ws.getBlock() instanceof DoorBlock || !ws.isFaceSturdy(level, w, d.getOpposite())) continue;
                double score = at.distSqr(near);
                if (score < bestScore) { bestScore = score; best = new Spot(at.immutable(), d.getOpposite()); }
            }
        }
        return best;
    }

    /** A cell of floor for a rug: empty, on a sound floor, not the doorway, the nearest the bed. */
    @Nullable
    private static Spot rugSpot(ServerLevel level, Villages.Village v, Rooms rooms, BlockPos near, Set<BlockPos> taken) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos c : rooms.floor()) {
            if (taken.contains(c) || !level.isLoaded(c) || !level.getBlockState(c).isAir() || !sturdyUnder(level, c)) continue;
            if (rooms.door() != null && c.distManhattan(rooms.door()) <= 1 || byADoor(level, c)) continue;
            if (!TownBlocks.allows(level, v, c, TownBlocks.Why.FURNISH)) continue;
            double score = c.distSqr(near);
            if (score < bestScore) { bestScore = score; best = c; }
        }
        return best == null ? null : new Spot(best.immutable(), Direction.NORTH);
    }

    // ------------------------------------------------------------------ getting a piece

    /** One step of a folk's errand for its home. */
    enum Step {
        /** Out of the household's own chest. */
        TAKE,
        /** Taken up in its old home and carried. */
        FETCH,
        /** Planks (or what else it is short of) bought at the shop, to make it of. */
        STUFF,
        /** Made at a bench at home. */
        MAKE,
        /** Bought at the shop. */
        BUY,
        /** The parish's handout, at the stores. */
        PARISH,
        /** Home, and set out. */
        PLACE
    }

    /** A step and the need it is for (null: the whole piece). */
    record Doing(Step step, @Nullable Need need, int n) {}

    /** A folk's errand for its home: the piece, its steps, and how it is going. */
    static final class Job {
        final UUID village;
        final long anchor;
        final String key;
        final Deque<Doing> steps = new ArrayDeque<>();
        final long started;
        int walked = -1000;
        String doing = "";
        /** Where the piece is going, worked out once on the way home (looked at again when it gets there). */
        @Nullable BlockPos placeAt;

        Job(UUID village, long anchor, String key, long started) {
            this.village = village;
            this.anchor = anchor;
            this.key = key;
            this.started = started;
        }
    }

    private static final Map<UUID, Job> JOBS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> NEXT_LOOK = new ConcurrentHashMap<>();
    /** Everything at once, the walking left out (the tests, the showcase). */
    private static boolean INSTANT;

    /** How many of what a need wants this folk carries of its household's own. */
    private static int carrying(VillageFolkEntity f, Homes.Home h, Need need) {
        int n = 0;
        for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && need.what().test(s) && ours(s, h)) n += s.getCount();
        return n;
    }

    /** How many of what a need wants the household's own boxes hold. */
    private static int inBoxes(ServerLevel level, UUID village, Homes.Home h, Predicate<ItemStack> what) {
        int n = 0;
        for (HomeStores.Box b : HomeStores.of(level, village, h)) {
            if (!(level.getBlockEntity(b.pos()) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && what.test(s) && ours(s, h)) n += s.getCount();
            }
        }
        return n;
    }

    /** One of what a need wants as the shop (or the stores behind it) holds it, to ask for at the counter; or the plain one. */
    private static ItemStack shopSample(ServerLevel level, UUID village, Need need) {
        for (Container c : Store.containers(level, village, true)) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && need.what().test(s)) return s.copyWithCount(1);
            }
        }
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty() && need.what().test(s)) return s.copyWithCount(1);
            }
        }
        return new ItemStack(need.sample());
    }

    /** What it keeps in its purse, whatever it buys for its home. */
    static final int KEEP_BY = 2;

    /** May this folk go after this piece now, by its household's means? */
    private static boolean affords(Spec s, List<VillageFolkEntity> household) {
        return means(household).ordinal() >= s.from().ordinal();
    }

    /**
     * The way this folk will get the piece, step by step: each thing it is made of brought from the old home, out of the
     * household's chest, made, bought or given by the parish, the cheapest honest way to hand; then home and set out.
     * Null (and why, in the piece's note) if it cannot be got now.
     */
    @Nullable
    static Job route(ServerLevel level, Villages.Village v, Homes.Home h, Plan p, Piece q, VillageFolkEntity f, List<VillageFolkEntity> household) {
        Spec s = q.spec();
        if (s == null) return null;
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        Job job = new Job(id, h.anchor.asLong(), q.key, level.getGameTime());
        // Brought from the old home: the very piece it had there.
        Piece old = fetchable(level, id, p, q, h);
        if (old != null) {
            job.steps.add(new Doing(Step.FETCH, null, 1));
            job.steps.add(new Doing(Step.PLACE, null, 1));
            job.doing = "bringing " + s.words() + " from its old home";
            return job;
        }
        boolean shop = ShopStock.open(id);
        int purse = f.purse() - KEEP_BY;
        List<String> why = new ArrayList<>();
        String how = "";
        for (Need need : s.needs()) {
            int have = carrying(f, h, need);
            if (have >= need.n()) continue;
            int want = need.n() - have;
            if (inBoxes(level, id, h, need.what()) >= want) {
                job.steps.add(new Doing(Step.TAKE, need, want));
                how = how.isEmpty() ? "fetching " + s.words() + " out of its chest" : how;
                continue;
            }
            // Made: by one with the skill, of what it has (or planks bought for it, when that is the cheaper).
            Joinery.Plan made = Joinery.plan(level, v, h, f, need, want);
            if (made != null && made.planks() == 0) {
                job.steps.add(new Doing(Step.MAKE, need, want));
                how = how.isEmpty() ? "making " + s.words() : how;
                continue;
            }
            ItemStack sample = shopSample(level, id, need);
            int price = shop && ShopStock.count(level, id, need.what()) >= want ? ShopStock.lotPrice(level, id, sample, want) : -1;
            if (made != null && shop) {
                ItemStack planks = Joinery.plankSample(level, id);
                int stuff = ShopStock.count(level, id, s2 -> ItemStack.isSameItemSameComponents(s2, planks)) >= made.planks()
                    ? ShopStock.lotPrice(level, id, planks, made.planks()) : -1;
                if (stuff >= 0 && stuff <= purse && (price < 0 || stuff < price)) {
                    job.steps.add(new Doing(Step.STUFF, need, made.planks()));
                    job.steps.add(new Doing(Step.MAKE, need, want));
                    purse -= stuff;
                    how = how.isEmpty() ? "making " + s.words() + " of planks it bought" : how;
                    continue;
                }
            }
            if (affords(s, household) && price >= 0 && price <= purse) {
                job.steps.add(new Doing(Step.BUY, need, want));
                purse -= price;
                how = how.isEmpty() ? "off to the shop for " + s.words() : how;
                continue;
            }
            // Nobody to make it and nothing to buy it with: the parish sees the basics are there. And a town with no shop yet
            // fits out its households' basics from its stores, as it kits out its hands before there is a counter to buy at.
            if (s.stage() == Stage.BASIC && (!shop || destitute(household) && day - p.since >= 1) && Parish.canGive(level, v, need, want)) {
                job.steps.add(new Doing(Step.PARISH, need, want));
                how = how.isEmpty() ? "fetching " + s.words() + (shop || destitute(household) ? " from the parish" : " from the town's stores") : how;
                continue;
            }
            if (shop && price < 0 && q.retry <= level.getGameTime() && day != q.day) {
                StockKeeper.missed(level, id, sample, want);                   // the shop hears it is wanted, and makes or orders it
            }
            why.add(!affords(s, household) ? "when they can run to it" : !shop ? "no shop open, and nobody to make " + need.words()
                : price < 0 ? "the shop has no " + named(need.sample()) : "short of coin for " + need.words());
            break;
        }
        if (!why.isEmpty()) {
            q.state = State.WAITING;
            q.note = why.get(0);
            q.day = day;
            q.retry = level.getGameTime() + 2400L;
            return null;
        }
        job.steps.add(new Doing(Step.PLACE, null, 1));
        job.doing = how.isEmpty() ? "setting out " + s.words() : how;
        return job;
    }

    /** The piece of the household's own standing in the home it came from, that this piece of the new plan can be. */
    @Nullable
    private static Piece fetchable(ServerLevel level, UUID village, Plan p, Piece q, Homes.Home h) {
        if (p.from == NONE) return null;
        Plan old = load(village, p.from);
        if (old == null) return null;
        Homes.Home oh = Homes.homes(village).get(p.from);
        boolean empty = oh == null || oh.members.isEmpty();
        String kind = q.key.startsWith("seat.") ? "seat." : q.key;
        for (Piece o : old.pieces) {
            if (!o.standing() || o.owner == null || !h.members.contains(o.owner)) continue;
            if (!(kind.endsWith(".") ? o.key.startsWith(kind) : o.key.equals(kind))) continue;
            Spec os = o.spec();
            // The basics stay with whoever of the household stays behind; its own things go with their owner.
            if (!empty && (os == null || os.stage() == Stage.BASIC)) continue;
            if (!level.isLoaded(BlockPos.of(o.pos))) continue;
            return o;
        }
        return null;
    }

    // ------------------------------------------------------------------ the errand

    /**
     * In its own time (VillageFolkEntity.socialise: after work, on its break, on the rest day): the next piece its home
     * wants, got and set out. True while it is about it.
     */
    public static boolean errand(VillageFolkEntity f, ServerLevel level) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || f.isSleeping() || f.isShowcase() || f.isHired()) return false;
        Villages.Village v = Villages.get(village);
        if (v == null) return false;
        Job j = JOBS.get(f.getUUID());
        long now = level.getGameTime();
        if (j == null) {
            if (now < NEXT_LOOK.getOrDefault(f.getUUID(), 0L)) return false;
            NEXT_LOOK.put(f.getUUID(), now + 400L);
            j = next(level, v, f);
            if (j == null) {
                NEXT_LOOK.put(f.getUUID(), now + 1800L);
                return false;
            }
            JOBS.put(f.getUUID(), j);
            f.brain(j.doing);
        }
        if (now - j.started > 9600L) {
            give(level, v, f, j, "it could not get it done");
            return false;
        }
        return step(level, v, f, j);
    }

    /** The next piece this folk's home wants that nobody else of the household is seeing to, and how; or null. */
    @Nullable
    static Job next(ServerLevel level, Villages.Village v, VillageFolkEntity f) {
        UUID id = v.id();
        if (Homes.lodging(f)) return null;                       // a spare bed in another's house is no home to furnish
        Homes.Home h = Homes.homeOf(id, f.getUUID());
        if (h == null || !level.isLoaded(h.anchor) || Homes.seat(h)) return null;   // the leader's hall goes with the office
        if (Ledger.raising(id, h.anchor)) return null;
        Plan p = planFor(level, v, h);
        long now = level.getGameTime();
        if (now - p.looked > 1200L) {
            p.looked = now;
            refresh(level, v, h, p);
        }
        List<VillageFolkEntity> household = Homes.loadedMembers(id, h);
        for (Piece q : p.pieces) {
            if (q.done() || q.retry > now) continue;
            if (q.claim != null && !q.claim.equals(f.getUUID()) && q.claimUntil > now) continue;
            Spec s = q.spec();
            if (s == null) continue;
            if (s.stage() == Stage.OWN && !basicsDone(p)) break;          // the basics first
            if (q.key.startsWith("seat.")) {
                Piece t = p.piece("table");
                if (t == null || !t.standing()) continue;                  // the seats wait for the table
            }
            // Room for it first: nobody fetches a thing home it has nowhere to put.
            Rooms rooms = rooms(level, id, h);
            BlockPos near = f.bedPos() != null ? f.bedPos() : h.anchor;
            if (spotFor(level, v, h, p, q, rooms, near) == null) {
                q.state = State.NO_ROOM;
                q.note = "no room for it";
                q.day = level.getDayTime() / 24000L;
                save(p);
                continue;
            }
            Job j = route(level, v, h, p, q, f, household);
            if (j == null) { save(p); continue; }
            q.state = State.WANTED;
            q.note = "";
            q.claim = f.getUUID();
            q.claimUntil = now + 9600L;
            return j;
        }
        return null;
    }

    /** One look at the errand: walk on, or, there, do the step. True while it is about it. */
    private static boolean step(ServerLevel level, Villages.Village v, VillageFolkEntity f, Job j) {
        Doing d = j.steps.peek();
        if (d == null) { JOBS.remove(f.getUUID()); return false; }
        Homes.Home h = Homes.homes(v.id()).get(j.anchor);
        Plan p = load(v.id(), j.anchor);
        if (h == null || p == null || !h.members.contains(f.getUUID())) { give(level, v, f, j, "it lives elsewhere now"); return false; }
        Piece q = p.piece(j.key);
        if (q == null) { give(level, v, f, j, "nothing wanted"); return false; }
        BlockPos dest = where(level, v, h, p, q, f, d, j);
        if (dest == null) { give(level, v, f, j, "nowhere to go for it"); return false; }
        double reach = d.step() == Step.BUY || d.step() == Step.STUFF ? 10.0 : 3.0;
        if (!INSTANT && f.blockPosition().distSqr(dest) > reach * reach) {
            if (f.getNavigation().isDone() || f.tickCount - j.walked >= 100) {
                f.walkTo(dest, 0.9D);
                j.walked = f.tickCount;
            }
            f.hobbyNow = j.doing;
            f.lastLeisureTick = f.tickCount;
            return true;
        }
        if (!INSTANT && (d.step() == Step.BUY || d.step() == Step.STUFF)) {
            Need need = d.need();
            ServiceQueue.Turn turn = ServiceQueue.turn(f, level, ServiceRole.SHOP, need == null ? "something for its home" : need.words());
            if (turn == ServiceQueue.Turn.BUSY) { f.hobbyNow = j.doing; return true; }
            if (turn == ServiceQueue.Turn.CLOSED) { give(level, v, f, j, "the shop was shut"); return false; }
        }
        if (!INSTANT && d.step() == Step.PLACE && standingIn(level, dest)) {
            // Somebody (itself, as like as not) is standing where the piece goes: it steps aside, and sets it down after.
            BlockPos aside = aside(level, dest);
            if (aside != null && (f.getNavigation().isDone() || f.tickCount - j.walked >= 40)) {
                f.walkTo(aside, 0.8D);
                j.walked = f.tickCount;
            }
            return true;
        }
        if (!INSTANT) f.getNavigation().stop();
        String failed = act(level, v, h, p, q, f, d);
        if (failed != null) { give(level, v, f, j, failed); return false; }
        j.steps.poll();
        if (j.steps.isEmpty()) {
            JOBS.remove(f.getUUID());
            q.claim = null;
            ServiceQueue.leave(f.getUUID());
        }
        return true;
    }

    /** Is anybody standing in this cell (or the one over it, where a table's top goes)? */
    private static boolean standingIn(ServerLevel level, BlockPos at) {
        return !level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new AABB(at).expandTowards(0.0, 1.0, 0.0)).isEmpty();
    }

    /** A free cell of floor beside this one, to step aside to. */
    @Nullable
    private static BlockPos aside(ServerLevel level, BlockPos at) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = at.relative(d);
            if (Decor.passable(level, n) && sturdyUnder(level, n) && !standingIn(level, n)) return n;
        }
        return null;
    }

    /** Where a step is done: the shop's counter, the stores, the old home, the household's chest or bench, the spot. */
    @Nullable
    private static BlockPos where(ServerLevel level, Villages.Village v, Homes.Home h, Plan p, Piece q, VillageFolkEntity f, Doing d, Job j) {
        UUID id = v.id();
        return switch (d.step()) {
            case BUY, STUFF -> {
                ServicePoints.Point point = ServiceQueue.pointFor(f, level, ServiceRole.SHOP);
                if (point == null) point = ServicePoints.nearest(level, id, ServiceRole.SHOP, f.blockPosition());
                yield point != null ? point.queueSpot(level, 0) : Villages.builtAt(id, Store.buildingForShop(id));
            }
            case PARISH -> Neighbourly.storesSpot(level, v);
            case FETCH -> {
                Piece o = fetchable(level, id, p, q, h);
                yield o == null ? h.anchor : BlockPos.of(o.pos);
            }
            case TAKE, MAKE -> {
                BlockPos chest = Homes.chestOf(level, id, h);
                yield chest != null ? chest : h.anchor;
            }
            case PLACE -> {
                if (j.placeAt == null) {
                    Spot s = spotFor(level, v, h, p, q, rooms(level, id, h), f.bedPos() != null ? f.bedPos() : h.anchor);
                    j.placeAt = s != null ? s.at() : h.anchor;
                }
                yield j.placeAt;
            }
        };
    }

    /** Do the step, there. Null if it went; else why not. */
    @Nullable
    private static String act(ServerLevel level, Villages.Village v, Homes.Home h, Plan p, Piece q, VillageFolkEntity f, Doing d) {
        UUID id = v.id();
        Need need = d.need();
        switch (d.step()) {
            case TAKE -> {
                if (need == null || !takeFromBoxes(level, id, h, f, need.what(), d.n())) return "it was not in the chest";
                q.how = q.how == How.NONE ? How.CHEST : q.how;
                return null;
            }
            case FETCH -> {
                Piece o = fetchable(level, id, p, q, h);
                if (o == null || !takeUp(level, v, h, f, o)) return "it was gone from the old home";
                q.how = How.BROUGHT;
                return null;
            }
            case MAKE -> {
                if (need == null) return "nothing to make";
                Joinery.Plan made = Joinery.plan(level, v, h, f, need, d.n());
                if (made == null || made.planks() > 0 || !Joinery.make(level, v, h, f, need, d.n())) return "short of the stuff to make it";
                f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                level.playSound(null, f.blockPosition(), net.minecraft.sounds.SoundEvents.WOOD_PLACE, SoundSource.NEUTRAL, 0.6F, 1.0F);
                if (q.how == How.NONE || q.how == How.CHEST) q.how = How.MADE;
                return null;
            }
            case STUFF, BUY -> {
                if (need == null) return "nothing to buy";
                ItemStack sample = d.step() == Step.STUFF ? Joinery.plankSample(level, id) : shopSample(level, id, need);
                if (INSTANT) {
                    ServicePoints.Point point = ServicePoints.nearest(level, id, ServiceRole.SHOP, f.blockPosition());
                    if (point != null) {
                        BlockPos at = point.queueSpot(level, 0);
                        f.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                    }
                }
                ItemStack got = ShopDesk.folkBuy(level, v, f, sample, d.n());
                if (got == null) return "the shop would not sell it";
                ownIt(f, got, Homes.Source.BOUGHT);
                if (d.step() == Step.BUY && q.how != How.MADE) q.how = How.BOUGHT;
                if (d.step() == Step.STUFF) q.how = How.MADE;
                String what = counted(got.getItem(), got.getCount());
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), cap(what) + " for the house, please.", "That's " + what + " — for our home.",
                    "I'll take " + what + "."));
                return null;
            }
            case PARISH -> {
                boolean parish = ShopStock.open(id) || destitute(Homes.loadedMembers(id, h));
                if (need == null || !Parish.giveOnRelief(level, v, h, f, need, d.n(), parish)) return "the parish had none to give";   // [econ-1a]
                q.how = parish ? How.PARISH : How.ISSUED;
                return null;
            }
            case PLACE -> {
                Rooms rooms = rooms(level, id, h);
                Spot spot = spotFor(level, v, h, p, q, rooms, f.bedPos() != null ? f.bedPos() : h.anchor);
                if (spot == null) {
                    q.state = State.NO_ROOM;
                    q.note = "no room for it";
                    q.day = level.getDayTime() / 24000L;
                    save(p);
                    return "there was no room for it";
                }
                if (!place(level, v, h, p, q, f, spot)) return "it would not go";
                return null;
            }
        }
        return "nothing to do";
    }

    /** The errand given up: the piece waits a while, and whatever it got for it stays in its pack, its own, for next time. */
    private static void give(ServerLevel level, Villages.Village v, VillageFolkEntity f, Job j, String why) {
        JOBS.remove(f.getUUID());
        ServiceQueue.leave(f.getUUID());
        Plan p = load(v.id(), j.anchor);
        Piece q = p == null ? null : p.piece(j.key);
        if (q != null) {
            q.claim = null;
            if (!q.done()) {
                q.state = State.WAITING;
                q.note = why;
                q.day = level.getDayTime() / 24000L;
                q.retry = level.getGameTime() + 2400L;
            }
            save(p);
        }
        f.brain("gave up on its errand for its home: " + why);
    }

    // ------------------------------------------------------------------ the household's own things, handled

    /** What it was handed (bought, made, given): taken out of its pack and put back marked as its own, nothing more. */
    static void ownIt(VillageFolkEntity f, ItemStack got, Homes.Source source) {
        int n = f.removeMatching(s -> ItemStack.isSameItemSameComponents(s, got.copyWithCount(1)) && !Homes.isKeepsake(s), got.getCount());
        int left = n;
        while (left > 0) {
            int k = Math.min(left, got.getMaxStackSize());
            ItemStack mine = got.copyWithCount(k);
            Homes.keepsake(mine, f, source);
            ItemStack over = f.insertItem(mine);
            if (!over.isEmpty() && f.level() instanceof ServerLevel level) {
                Homes.Home h = f.ownerId() == null ? null : Homes.homeOf(f.ownerId(), f.getUUID());
                ItemStack still = h == null ? over : HomeStores.putAway(level, f.ownerId(), h, over, f.isBaby());
                if (!still.isEmpty()) f.spawnAtLocation(still);              // never lost: dropped at its feet, its own
            }
            left -= k;
        }
    }

    /** Out of the household's boxes into its pack: so many of what matches that is the household's own. */
    static boolean takeFromBoxes(ServerLevel level, UUID village, Homes.Home h, VillageFolkEntity f, Predicate<ItemStack> what, int n) {
        int left = n;
        for (HomeStores.Box b : HomeStores.of(level, village, h)) {
            if (left <= 0) break;
            if (!(level.getBlockEntity(b.pos()) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s) || !ours(s, h)) continue;
                int k = Math.min(left, s.getCount());
                ItemStack moved = s.copyWithCount(k);
                ItemStack over = f.insertItem(moved);
                int went = k - over.getCount();
                if (went <= 0) return false;
                s.shrink(went);
                if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                c.setChanged();
                left -= went;
            }
        }
        return left <= 0;
    }

    /** A piece of its old home taken up and carried: the blocks (a table's post and board) into its pack, its owner's still. */
    private static boolean takeUp(ServerLevel level, Villages.Village v, Homes.Home h, VillageFolkEntity f, Piece o) {
        BlockPos at = BlockPos.of(o.pos);
        if (!level.isLoaded(at)) return false;
        UUID id = v.id();
        Spec s = o.spec();
        if (s == null) return false;
        List<ItemStack> got = new ArrayList<>();
        switch (s.shape()) {
            case FRAME -> {
                for (ItemFrame fr : level.getEntitiesOfClass(ItemFrame.class, new AABB(at), e -> e.getTags().contains(Decor.TAG))) {
                    if (!fr.getItem().isEmpty()) got.add(fr.getItem().copy());
                    got.add(new ItemStack(Items.ITEM_FRAME));
                    fr.discard();
                }
            }
            case STAND -> {
                for (ArmorStand st : level.getEntitiesOfClass(ArmorStand.class, new AABB(at).inflate(0.2), e -> e.getTags().contains(Decor.TAG))) {
                    got.add(new ItemStack(Items.ARMOR_STAND));
                    st.discard();
                }
            }
            default -> {
                if (s.shape() == Shape.TABLE) {
                    BlockState top = level.getBlockState(at.above());
                    if (top.getBlock() instanceof PressurePlateBlock || top.is(BlockTags.WOOL_CARPETS)) {
                        got.add(new ItemStack(top.getBlock().asItem()));
                        level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
                BlockState st = level.getBlockState(at);
                if (st.isAir()) return false;
                if (level.getBlockEntity(at) instanceof Container c) {
                    // What is in a chest or a barrel goes with it.
                    for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) got.add(c.getItem(i).copy());
                    c.clearContent();
                }
                if (st.getBlock() instanceof FlowerPotBlock pot && pot.getPotted() != Blocks.AIR) {
                    got.add(new ItemStack(pot.getPotted().asItem()));
                    got.add(new ItemStack(Items.FLOWER_POT));
                } else if (st.is(Blocks.WATER_CAULDRON)) {
                    got.add(new ItemStack(Items.CAULDRON));
                } else {
                    Item it = st.getBlock().asItem();
                    if (it != Items.AIR) got.add(new ItemStack(it));
                }
                level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        VillageFolkEntity known = o.owner == null ? null : Homes.loaded(id, o.owner);
        VillageFolkEntity owner = known != null ? known : f;
        for (ItemStack g : got) {
            if (!Homes.isKeepsake(g)) Homes.keepsake(g, owner, Homes.Source.BOUGHT);
            ItemStack over = f.insertItem(g);
            if (!over.isEmpty()) {
                ItemStack still = HomeStores.putAway(level, id, h, over, false);
                if (!still.isEmpty()) f.spawnAtLocation(still);
            }
        }
        Plan old = planHolding(id, o);
        if (old != null && s.book() != null) {
            BlockPos oldAnchor = BlockPos.of(old.anchor);
            Map<String, Long> book = Decor.book(id, oldAnchor);
            long was = o.pos;
            book.values().removeIf(x -> x == was);
            Decor.save(id, oldAnchor, book);
        }
        if (old != null) {
            old.pieces.remove(o);
            Homes.Home oh = Homes.homes(id).get(old.anchor);
            if (oh != null && !oh.members.isEmpty()) {
                Piece again = new Piece(o.key);                     // wanted again by those it left behind
                again.note = "taken by " + f.displayNameCap() + " when it moved out";
                old.pieces.add(again);
            }
            save(old);
        }
        level.playSound(null, at, net.minecraft.sounds.SoundEvents.WOOD_BREAK, SoundSource.NEUTRAL, 0.6F, 1.0F);
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return !got.isEmpty();
    }

    @Nullable
    private static Plan planHolding(UUID village, Piece o) {
        for (Plan p : PLANS.values()) if (p.village.equals(village) && p.pieces.contains(o)) return p;
        return null;
    }

    // ------------------------------------------------------------------ setting it out

    /** The piece set out at its spot, out of its pack, the right way round; written in the plan and the house's book. */
    static boolean place(ServerLevel level, Villages.Village v, Homes.Home h, Plan p, Piece q, VillageFolkEntity f, Spot spot) {
        Spec s = q.spec();
        if (s == null) return false;
        UUID id = v.id();
        BlockPos at = spot.at();
        if (!TownBlocks.mayPlace(level, v, at, TownBlocks.Why.FURNISH)) return false;
        // Everything it is made of, in its pack, its household's own.
        for (Need need : s.needs()) if (carrying(f, h, need) < need.n()) return false;
        BlockState placed = null;
        switch (s.shape()) {
            case TABLE -> {
                ItemStack post = peek(f, h, FENCE), top = peek(f, h, PLATE);
                Block pb = Block.byItem(post.getItem()), tb = Block.byItem(top.getItem());
                if (pb == Blocks.AIR || tb == Blocks.AIR || !TownBlocks.mayPlace(level, v, at.above(), TownBlocks.Why.FURNISH)) return false;
                level.setBlock(at, Block.updateFromNeighbourShapes(pb.defaultBlockState(), level, at), 3);
                BlockState plate = tb.defaultBlockState();
                if (!plate.canSurvive(level, at.above())) {
                    level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
                    return false;
                }
                level.setBlock(at.above(), plate, 3);
                take(f, h, FENCE);
                take(f, h, PLATE);
                placed = plate;
            }
            case SEAT -> {
                ItemStack st = peek(f, h, STAIR);
                if (!(Block.byItem(st.getItem()) instanceof StairBlock stair)) return false;
                // Its back away from the table (FACING is the side the stair rises to): its sitter faces the table.
                BlockState seat = stair.defaultBlockState().setValue(StairBlock.FACING, spot.facing()).setValue(StairBlock.HALF, Half.BOTTOM)
                    .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
                level.setBlock(at, seat, 3);
                take(f, h, STAIR);
                placed = seat;
            }
            case LIGHT -> {
                ItemStack l = peek(f, h, LIGHT);
                BlockState lamp = l.is(Items.LANTERN) ? Blocks.LANTERN.defaultBlockState()
                    : Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, spot.facing());
                if (!lamp.canSurvive(level, at)) return false;
                level.setBlock(at, lamp, 3);
                take(f, h, LIGHT);
                placed = lamp;
            }
            case TOP -> {
                Need main = s.needs().get(0);
                ItemStack thing = peek(f, h, main);
                BlockState st;
                if (thing.is(Items.FLOWER_POT)) {
                    ItemStack flower = s.needs().size() > 1 ? peek(f, h, s.needs().get(1)) : ItemStack.EMPTY;
                    Block potted = flower.isEmpty() ? null : Interiors.potted(flower.getItem());
                    st = potted != null ? potted.defaultBlockState() : Blocks.FLOWER_POT.defaultBlockState();
                } else {
                    Block b = Block.byItem(thing.getItem());
                    if (b == Blocks.AIR) return false;
                    st = b.defaultBlockState();
                    if (st.hasProperty(LanternBlock.HANGING)) st = st.setValue(LanternBlock.HANGING, false);
                }
                if (!st.canSurvive(level, at)) return false;
                level.setBlock(at, st, 3);
                for (Need need : s.needs()) take(f, h, need);
                placed = st;
            }
            case WALL -> {
                ItemStack thing = peek(f, h, s.needs().get(0));
                Block b = Block.byItem(thing.getItem());
                if (b == Blocks.AIR) return false;
                BlockState st = b.defaultBlockState();
                if (b instanceof BarrelBlock) st = st.setValue(BarrelBlock.FACING, Direction.UP);     // stood on its end, its lid up
                else if (b instanceof AnvilBlock) st = st.setValue(AnvilBlock.FACING, spot.facing().getClockWise());   // along the wall
                else if (st.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) st = st.setValue(BlockStateProperties.HORIZONTAL_FACING, spot.facing());
                else if (st.hasProperty(BlockStateProperties.AXIS)) st = st.setValue(BlockStateProperties.AXIS, Direction.Axis.Y);
                if (!st.canSurvive(level, at)) return false;
                HomeStores.resetFor(h);
                boolean hasChest = HomeStores.box(level, id, h, HomeStores.Kind.CHEST) != null;
                level.setBlock(at, st, 3);
                take(f, h, s.needs().get(0));
                if (level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.BlockEntity be && be instanceof Container) {
                    if (q.key.equals("larder")) HomeStores.rename(be, HomeStores.Kind.LARDER.label);
                    else if (q.key.equals("chest")) HomeStores.rename(be, hasChest ? HomeStores.Kind.WARDROBE.label : HomeStores.Kind.CHEST.label);
                    else HomeStores.rename(be, HomeStores.DISPLAY);           // a trade's barrel on show, no household box
                    HomeStores.resetFor(h);
                }
                placed = st;
            }
            case FRAME -> {
                ItemFrame frame = new ItemFrame(level, at, spot.facing());
                if (!frame.survives()) return false;
                ItemStack inside = s.needs().size() > 1 ? peek(f, h, s.needs().get(1)) : ItemStack.EMPTY;
                frame.setItem(inside.isEmpty() ? ItemStack.EMPTY : inside.copyWithCount(1), false);
                frame.addTag(Decor.TAG);
                level.addFreshEntity(frame);
                frame.playPlacementSound();
                for (Need need : s.needs()) take(f, h, need);
            }
            case STAND -> {
                ArmorStand stand = EntityType.ARMOR_STAND.create(level);
                if (stand == null) return false;
                stand.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, spot.facing().toYRot(), 0.0F);
                stand.addTag(Decor.TAG);
                level.addFreshEntity(stand);
                level.playSound(null, at, net.minecraft.sounds.SoundEvents.ARMOR_STAND_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
                take(f, h, s.needs().get(0));
            }
            case RUG -> {
                ItemStack rug = peek(f, h, s.needs().get(0));
                Block b = Block.byItem(rug.getItem());
                if (b == Blocks.AIR) return false;
                level.setBlock(at, b.defaultBlockState(), 3);
                take(f, h, s.needs().get(0));
                placed = b.defaultBlockState();
            }
            case BANNER -> {
                ItemStack banner = peek(f, h, s.needs().get(0)).copyWithCount(1);
                if (!Decor.hangBanner(level, at, spot.facing(), banner)) return false;
                take(f, h, s.needs().get(0));
            }
        }
        if (placed != null) level.playSound(null, at, placed.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.0F);
        long day = level.getDayTime() / 24000L;
        q.state = State.PLACED;
        q.pos = at.asLong();
        q.by = f.displayNameCap();
        q.owner = f.getUUID();
        q.day = day;
        q.note = "";
        q.claim = null;
        if (q.how == How.NONE) q.how = How.CHEST;
        // Its trade's things, its colours, its books and flowers: in the house's book too, so the house is counted for them.
        if (s.book() != null) {
            Map<String, Long> book = Decor.book(id, h.anchor);
            String k = s.book();
            if (k.startsWith("lux.")) {
                int n = 0;
                while (book.containsKey(k + "." + n)) n++;
                k = k + "." + n;
            }
            book.put(k, at.asLong());
            Decor.save(id, h.anchor, book);
            Ledger.Building b = Homes.building(id, h.anchor);
            if (b != null) Decor.remember(level, id, h, b, book);
        }
        save(p);
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        f.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
        f.persona().remember(day, "I " + (q.how == How.MADE ? "made and set out " : "set out ") + s.words() + " in our home", 2);
        FolkTalk.speak(f, said(f, q, s));
        f.brain("set out " + s.words() + " in its home (" + q.how.words + ")");
        LOG.info("[MCA-HOMEMAKING] {} of {} set out {} ({}) at {}", f.displayNameCap(), Villages.name(id), s.words(), q.how.words, at.toShortString());
        return true;
    }

    private static String said(VillageFolkEntity f, Piece q, Spec s) {
        String thing = s.shape() == Shape.SEAT ? "seat" : s.words().replaceFirst("^(an?|the) ", "");
        if (q.how == How.PARISH) return FolkTalk.pick(f.getRandom(), "There. The parish's " + thing + ", and glad of it.",
            "A " + thing + " from the parish. We'll manage now.");
        if (q.how == How.ISSUED) return FolkTalk.pick(f.getRandom(), "There. The town's " + thing + " — ours now.", "That's the " + thing + " in.");
        if (q.how == How.GIFT) return FolkTalk.pick(f.getRandom(), "There — the " + thing + " we were given. Looks a treat.",
            "Somebody's kindness, right there. That's the " + thing + " in.");
        if (q.how == How.MADE) return FolkTalk.pick(f.getRandom(), "There. That's the " + thing + " sorted — made it myself.",
            "Made with my own hands, that " + thing + ".", "There. Not bad, if I say so myself.");
        if (q.how == How.BROUGHT) return FolkTalk.pick(f.getRandom(), "There — the old " + thing + ", in its new home.",
            "Brought the " + thing + " over. Feels like home already.");
        return FolkTalk.pick(f.getRandom(), "There. That's the " + thing + " sorted.", "That's the " + thing + " in. Coming on, this place.",
            "There we are. A " + thing + " of our own.");
    }

    /** One of what a need wants from its pack, its household's own (not taken). */
    private static ItemStack peek(VillageFolkEntity f, Homes.Home h, Need need) {
        for (ItemStack s : f.getInventoryItems()) if (!s.isEmpty() && need.what().test(s) && ours(s, h)) return s;
        return ItemStack.EMPTY;
    }

    /** One of it out of its pack (used up: set out). */
    private static void take(VillageFolkEntity f, Homes.Home h, Need need) {
        int left = need.n();
        for (ItemStack s : f.getInventoryItems()) {
            if (left <= 0) break;
            if (s.isEmpty() || !need.what().test(s) || !ours(s, h)) continue;
            int k = Math.min(left, s.getCount());
            s.shrink(k);
            left -= k;
        }
    }

    // ------------------------------------------------------------------ the town's hands off

    /**
     * [home-making] Is this building a home with a household in it: furnished by its own household, not the town's
     * hands (Interiors, Decor keep to the halls, the shops, and the homes standing empty for their next tenants)?
     */
    public static boolean householdsOwn(UUID village, BlockPos anchor) {
        Homes.Home h = Homes.homes(village).get(anchor.asLong());
        return h != null && !h.members.isEmpty() && !Homes.seat(h);      // the leader's hall is the town's, and its to furnish
    }

    /**
     * [home-making] The furnishing points of a home for its count (Decor.count): the basics its household set up for
     * itself, out of two as the town's age furnishing was ({in, of}); else what the town's was.
     */
    static int[] progress(ServerLevel level, UUID village, Homes.Home h, int[] towns) {
        if (h.members.isEmpty()) return towns;
        Plan p = load(village, h.anchor.asLong());
        if (p == null) return towns;
        int in = 0, of = 0;
        for (Piece q : p.pieces) {
            Spec s = q.spec();
            if (s == null || s.stage() != Stage.BASIC) continue;
            of++;
            if (q.done()) in++;
        }
        return of == 0 ? towns : new int[]{ in, of };
    }

    // ------------------------------------------------------------------ moving, giving, the town's round

    /**
     * [home-making] Homes.moveIn: this folk has moved from one home to another. Its household's new plan will bring its
     * things over from the old one (what the new home wants of them; the rest is left with the old house).
     */
    static void leaving(UUID village, @Nullable Homes.Home was, Homes.Home to, VillageFolkEntity f) {
        if (was == null || was == to || f.isBaby()) return;
        Plan old = load(village, was.anchor.asLong());
        if (old == null) return;
        boolean has = false;
        for (Piece q : old.pieces) if (q.standing() && f.getUUID().equals(q.owner)) { has = true; break; }
        if (!has) return;
        Plan now = PLANS.get(id(village, to.anchor.asLong()));
        if (now != null && containsAny(now.household, to.members)) now.from = was.anchor.asLong();
        else CAME_FROM.put(id(village, to.anchor.asLong()), was.anchor.asLong());
        if (now != null) save(now);
    }

    private static boolean containsAny(List<UUID> a, List<UUID> b) {
        for (UUID u : a) if (b.contains(u)) return true;
        return false;
    }

    /**
     * [home-making] A player's present (FolkTalk.gift): a piece of furniture (or what makes one) is its household's own,
     * to be set out at home where its plan has room for it. Returns what it says, or null to say what it would have.
     */
    @Nullable
    public static String gifted(VillageFolkEntity f, ItemStack one, String from) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby() || one.isEmpty()) return null;
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        if (h == null || Homes.seat(h)) return null;
        Plan p = f.level() instanceof ServerLevel level && Villages.get(village) != null ? planFor(level, Villages.get(village), h) : load(village, h.anchor.asLong());
        if (p == null) return null;
        for (Piece q : p.pieces) {
            if (q.done()) continue;
            Spec s = q.spec();
            if (s == null) continue;
            for (Need need : s.needs()) {
                if (!need.what().test(one)) continue;
                Homes.keepsake(one, f, Homes.Source.GIFT);
                q.how = How.GIFT;
                q.state = State.WANTED;
                q.retry = 0;
                q.note = "given by " + from;
                save(p);
                NEXT_LOOK.remove(f.getUUID());
                return FolkTalk.pick(f.getRandom(), "Oh, " + counted(one.getItem(), 1) + "! Just what our home wants — " + s.words()
                    + ". I'll set it out tonight.", "For the house? " + cap(s.words()) + " — we'd been needing one. Thank you, " + from + "!");
            }
        }
        // Not a piece it wants, but household stock all the same (food, fuel, candles): its own, to be put by at home.
        if (Housekeeping.stock(one) != null) Homes.keepsake(one, f, Homes.Source.GIFT);
        return null;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** A thing's name as folk say it ("oak fence"), from its own name in the game: the server has no translations. */
    static String named(Item it) {
        return BuiltInRegistries.ITEM.getKey(it).getPath().replace('_', ' ');
    }

    /** "an oak fence", "four oak stairs". */
    static String counted(Item it, int n) {
        String name = named(it);
        if (n == 1) return ("aeiou".indexOf(name.charAt(0)) >= 0 ? "an " : "a ") + name;
        return Decor.number(n) + " " + (name.endsWith("s") ? name : name + "s");
    }

    private static final Map<UUID, Long> TICKED = new ConcurrentHashMap<>();

    /** The town's round (HomeLife.tick): every household's plan made and kept true, a home at a time. */
    static void tick(ServerLevel level, Villages.Village v) {
        long now = level.getGameTime();
        Long last = TICKED.get(v.id());
        if (last != null && now - last < 600L && now >= last) return;
        TICKED.put(v.id(), now);
        for (Homes.Home h : List.copyOf(Homes.homes(v.id()).values())) {
            if (h.members.isEmpty() || Homes.seat(h) || !level.isLoaded(h.anchor) || Ledger.raising(v.id(), h.anchor)) continue;
            if (Homes.loadedMembers(v.id(), h).isEmpty()) continue;
            Plan p = planFor(level, v, h);
            if (now - p.looked > 1200L) {
                p.looked = now;
                refresh(level, v, h, p);
            }
        }
    }

    // ------------------------------------------------------------------ what a player reads

    /** The words for a piece's state. */
    private static String stateWords(Piece q, Spec s) {
        return switch (q.state) {
            case PLACED -> s.words() + " (" + q.how.words + (q.by.isEmpty() ? "" : ", by " + q.by) + ")";
            case HOUSE -> s.words() + " (" + q.how.words + ")";
            case NO_ROOM -> s.words() + " (no room for it)";
            default -> s.words() + (q.note.isEmpty() ? "" : " (" + q.note + ")");
        };
    }

    /** Its card's line: what it is doing for its home, how far along the home is, and what it still wants for it. */
    public static String cardLine(VillageFolkEntity f) {
        UUID village = f.ownerId();
        if (village == null || f.isBaby()) return "";
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        if (h == null) return "";
        Plan p = load(village, h.anchor.asLong());
        if (p == null) return "";
        Job j = JOBS.get(f.getUUID());
        int basics = 0, basicsIn = 0, own = 0, ownIn = 0, byHand = 0;
        List<String> wants = new ArrayList<>();
        for (Piece q : p.pieces) {
            Spec s = q.spec();
            if (s == null) continue;
            if (s.stage() == Stage.BASIC) { basics++; if (q.done()) basicsIn++; }
            else { own++; if (q.done()) ownIn++; }
            if (q.state == State.PLACED && q.how.own()) byHand++;
            if (!q.done() && wants.size() < 3 && !(q.key.startsWith("seat.") && wants.contains("seats"))) {
                wants.add(q.key.startsWith("seat.") ? "seats" : s.words().replaceFirst("^(an?|the) ", "") + (q.note.isEmpty() ? "" : " (" + q.note + ")"));
            }
        }
        StringBuilder sb = new StringBuilder();
        if (j != null) sb.append(cap(j.doing)).append(". ");
        sb.append("The basics ").append(basicsIn).append(" of ").append(basics);
        if (own > 0) sb.append(", its own things ").append(ownIn).append(" of ").append(own);
        if (byHand > 0) sb.append("; ").append(byHand).append(byHand == 1 ? " piece" : " pieces").append(" got by the household itself");
        sb.append(wants.isEmpty() ? "; nothing more wanted." : "; still wants " + String.join(", ", wants) + ".");
        return sb.toString();
    }

    /** For the town's books (Homes page): each home's furnishing, how complete and by whom. */
    public static CompoundTag report(ServerLevel level, UUID village) {
        CompoundTag out = new CompoundTag();
        ListTag houses = new ListTag();
        int set = 0, homes = 0, parish = 0;
        for (Homes.Home h : Homes.homes(village).values()) {
            if (h.members.isEmpty()) continue;
            Plan p = load(village, h.anchor.asLong());
            if (p == null) continue;
            homes++;
            int in = 0, of = 0;
            Map<String, Integer> by = new LinkedHashMap<>();
            List<String> wants = new ArrayList<>();
            for (Piece q : p.pieces) {
                Spec s = q.spec();
                if (s == null) continue;
                of++;
                if (q.done()) in++;
                if (q.state == State.PLACED) by.merge(q.how == How.PARISH ? "the parish" : q.how == How.ISSUED ? "the town" : q.how == How.GIFT ? "gifts" : q.by.isEmpty() ? "?" : q.by, 1, Integer::sum);
                if (q.how == How.PARISH) parish++;
                if (!q.done() && wants.size() < 4) wants.add(s.words());
            }
            if (p.settled) set++;
            List<String> who = new ArrayList<>();
            for (Map.Entry<String, Integer> e : by.entrySet()) who.add(e.getKey() + " " + e.getValue());
            CompoundTag t = new CompoundTag();
            t.putLong("anchor", h.anchor.asLong());
            t.putInt("in", in);
            t.putInt("of", of);
            t.putBoolean("settled", p.settled);
            t.putString("by", String.join(", ", who));
            t.putString("wants", String.join(", ", wants));
            t.putString("stocks", Housekeeping.line(level, village, h));
            t.putString("short", Housekeeping.shortWords(level, village, h));
            houses.add(t);
        }
        out.put("houses", houses);
        out.putInt("homes", homes);
        out.putInt("settled", set);
        out.putInt("parish", parish);
        return out;
    }

    // ------------------------------------------------------------------ tests

    /**
     * Tests: a round of every household's home-making now, the walking left out: each grown folk of each household gets
     * and sets out what it can, piece after piece, as in its own time. Returns how many pieces were set out.
     */
    public static int dayForTests(ServerLevel level, Villages.Village v) {
        INSTANT = true;
        int placed = 0;
        try {
            NEXT_LOOK.clear();
            JOBS.clear();
            for (Homes.Home h : List.copyOf(Homes.homes(v.id()).values())) {
                if (h.members.isEmpty() || Homes.seat(h)) continue;
                Plan p = planFor(level, v, h);
                p.looked = level.getGameTime();
                refresh(level, v, h, p);
                for (VillageFolkEntity f : grown(Homes.loadedMembers(v.id(), h))) {
                    for (Piece q : p.pieces) q.retry = 0;
                    for (int i = 0; i < 24; i++) {
                        Job j = next(level, v, f);
                        if (j == null) break;
                        Piece q = p.piece(j.key);
                        boolean was = q != null && q.state == State.PLACED;
                        JOBS.put(f.getUUID(), j);
                        int guard = 0;
                        while (JOBS.containsKey(f.getUUID()) && guard++ < 12) step(level, v, f, j);
                        if (q != null && q.state == State.PLACED && !was) placed++;
                        JOBS.remove(f.getUUID());
                    }
                }
                refresh(level, v, h, p);
            }
        } finally {
            INSTANT = false;
        }
        return placed;
    }

    /** Tests: a home's plan, as "key=STATE/how@x,y,z" in order. */
    public static List<String> planForTests(UUID village, BlockPos anchor) {
        List<String> out = new ArrayList<>();
        Plan p = load(village, anchor.asLong());
        if (p == null) return out;
        for (Piece q : p.pieces) {
            out.add(q.key + "=" + q.state + "/" + q.how.name().toLowerCase(Locale.ROOT) + (q.pos == NONE ? "" : "@" + BlockPos.of(q.pos).toShortString())
                + (q.note.isEmpty() ? "" : " (" + q.note + ")"));
        }
        return out;
    }

    /** Tests: a piece's spot and how it came ("" if it is not standing). */
    @Nullable
    public static BlockPos pieceForTests(UUID village, BlockPos anchor, String key) {
        Plan p = load(village, anchor.asLong());
        Piece q = p == null ? null : p.piece(key);
        return q == null || !q.standing() ? null : BlockPos.of(q.pos);
    }

    /** Tests: how a piece came to the home ("made", "bought", ...), or "". */
    public static String howForTests(UUID village, BlockPos anchor, String key) {
        Plan p = load(village, anchor.asLong());
        Piece q = p == null ? null : p.piece(key);
        return q == null ? "" : q.how.name().toLowerCase(Locale.ROOT);
    }

    /** Tests: are the home's basics all in? */
    public static boolean basicsForTests(UUID village, BlockPos anchor) {
        Plan p = load(village, anchor.asLong());
        return p != null && basicsDone(p);
    }

    /** Tests: as if a player had handed the folk this (FolkTalk.gift's own path). What it said, or null. */
    @Nullable
    public static String giftForTests(VillageFolkEntity f, ItemStack one, String from) {
        String said = gifted(f, one, from);
        ItemStack left = f.insertItem(one);
        if (!left.isEmpty()) f.spawnAtLocation(left);
        return said;
    }

    /** Tests: move these folk into this home now (Homes.moveIn, their things to be carried over). */
    public static void moveForTests(ServerLevel level, Villages.Village v, List<VillageFolkEntity> who, BlockPos anchor) {
        Homes.Home to = Homes.homes(v.id()).get(anchor.asLong());
        if (to == null) return;
        Homes.moveIn(level, v, to, who, level.getDayTime() / 24000L, false);
    }

    // ------------------------------------------------------------------ the parish

    /**
     * The parish's handout: for a household with nothing to buy with and nobody to make it, the basics out of the town's
     * own stores (or made there and then of the stores' planks at the town's bench), given into its hands, never left
     * to sleep in a bare room. Booked in the ledger: how many things and what they were worth.
     */
    static final class Parish {

        private Parish() {}

        static final String GIVEN = "parish/given", WORTH = "parish/worth", TOLD = "parish/told/";

        static boolean canGive(ServerLevel level, Villages.Village v, Need need, int n) {
            if (Market.stock(level, v.id(), need.what()) >= n) return true;
            Bench.Plan bp = Bench.plan(level, v, need.sample(), n, Bench.handOf(level, v, null, null));
            return bp.ok();
        }

        /** [econ-1a] The parish's gift on the poor relief's account: what it takes of the market, the treasury pays. */
        static boolean giveOnRelief(ServerLevel level, Villages.Village v, Homes.Home h, VillageFolkEntity f, Need need, int n, boolean parish) {
            try (Accounts.Scope relief = Accounts.as(Accounts.Dept.RELIEF, Accounts.Purpose.RELIEF)) {
                return give(level, v, h, f, need, n, parish);
            }
        }

        static boolean give(ServerLevel level, Villages.Village v, Homes.Home h, VillageFolkEntity f, Need need, int n, boolean parish) {
            UUID id = v.id();
            if (Market.stock(level, id, need.what()) < n) {
                Bench.Hand hand = Bench.handOf(level, v, null, null);
                Bench.Plan bp = Bench.plan(level, v, need.sample(), n, hand);
                if (!bp.ok() || Bench.make(level, v, bp, null, hand).isEmpty()) return false;
            }
            List<ItemStack> got = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                ItemStack one = Crafts.takeOne(level, v, need.what());
                if (one.isEmpty()) break;
                got.add(one.copyWithCount(1));
            }
            if (got.size() < n) {
                for (ItemStack back : got) Crafts.store(level, v, back);       // not enough after all: all of it back
                return false;
            }
            double worth = 0;
            for (ItemStack one : got) {
                worth += Prices.each(one.getItem());
                Homes.keepsake(one, f, Homes.Source.GIFT);
                ItemStack over = f.insertGiven(one);
                if (!over.isEmpty()) {
                    ItemStack still = HomeStores.putAway(level, id, h, over, false);
                    if (!still.isEmpty()) f.spawnAtLocation(still);
                }
            }
            Ledger.note(id, GIVEN, Integer.toString(Homes.parse(Ledger.note(id, GIVEN)) + n));
            Ledger.note(id, WORTH, Integer.toString(Homes.parse(Ledger.note(id, WORTH)) + (int) Math.round(worth)));
            long day = level.getDayTime() / 24000L;
            String told = TOLD + h.anchor.asLong();
            if (Ledger.note(id, told) == null) {
                Ledger.note(id, told, Long.toString(day));
                Villages.tell(id, day, parish ? "The parish found " + Homes.names(Homes.loadedMembers(id, h)) + " what their home lacked: nobody in "
                    + Villages.name(id) + " sleeps in a bare room"
                    : Villages.name(id) + " fitted out " + Homes.names(Homes.loadedMembers(id, h)) + "'s home from its stores: there is no shop to buy at yet");
            }
            f.persona().remember(day, (parish ? "the parish gave us " : "the town gave us ") + counted(need.sample(), n) + " for our home", 3);
            LOG.info("[MCA-HOMEMAKING] the parish of {} gave {} {} for its home ({} worth)", Villages.name(id), f.displayNameCap(),
                counted(need.sample(), n), Math.round(worth));
            return true;
        }

        /** Tests and the books: {things given, what they were worth}. */
        static int[] given(UUID village) {
            return new int[]{ Homes.parse(Ledger.note(village, GIVEN)), Homes.parse(Ledger.note(village, WORTH)) };
        }
    }

    /** Tests: why a home's floor will or will not take furniture, cell by cell, counted. */
    public static String roomForTests(ServerLevel level, UUID village, BlockPos anchor) {
        Homes.Home h = Homes.homes(village).get(anchor.asLong());
        Villages.Village v = Villages.get(village);
        if (h == null || v == null) return "no home";
        Rooms r = rooms(level, village, h);
        int lowest = Integer.MAX_VALUE;
        for (BlockPos c : r.floor()) lowest = Math.min(lowest, c.getY());
        Map<String, Integer> why = new LinkedHashMap<>();
        for (BlockPos c : r.floor()) {
            String k = r.clear().contains(c) ? "kept clear" : !level.getBlockState(c).isAir() ? "taken" : !level.getBlockState(c.above()).isAir() ? "no headroom"
                : !sturdyUnder(level, c) ? "no floor" : byADoor(level, c) ? "by a door" : inFrontOfSomething(level, c) ? "in front of something"
                : lastWayToABed(level, r, c) ? "a bed's way in" : Decor.cutsTheWay(level, c) ? "cuts the way" : !TownBlocks.allows(level, v, c, TownBlocks.Why.FURNISH)
                ? "the gate" : "free";
            why.merge(k + (c.getY() == lowest ? "" : " (upstairs)"), 1, Integer::sum);
        }
        return "floor " + r.floor().size() + " drawn " + r.drawn() + ": " + why;
    }

    /** Tests: a thing of the game's own in a colour ("blue" + "_carpet"), as the households want them (Decor). */
    public static Item colouredForTests(DyeColor c, String suffix) {
        return Decor.coloured(c, suffix);
    }

    /** Tests: the parish's handouts {things, worth}. */
    public static int[] parishForTests(UUID village) {
        return Parish.given(village);
    }

    // ------------------------------------------------------------------ joinery: making it themselves

    /**
     * Making a piece at a bench by one of the household with the skill, the game's own recipes the whole way, of its
     * household's own stuff (in its pack and the household's boxes): planks of its logs, sticks of its planks, a fence
     * of planks and sticks, a stair, a slab, a barrel, a chest; a torch of a stick and a coal; a stove of cobble; a
     * cauldron or a bucket of iron by a smith. What a step makes and the next does not use (a log's other planks, the
     * rest of a batch of stairs) stays its own for the next piece. With no bench at home it knocks one up first.
     */
    static final class Joinery {

        private Joinery() {}

        /** What a piece of stuff is. */
        enum Kind {
            LOGS(s -> s.is(ItemTags.LOGS_THAT_BURN)), PLANKS(s -> s.is(ItemTags.PLANKS)), STICKS(s -> s.is(Items.STICK)),
            SLABS(s -> s.is(ItemTags.WOODEN_SLABS)), STAIRS(s -> s.is(ItemTags.WOODEN_STAIRS)), FENCE(s -> s.is(ItemTags.WOODEN_FENCES)),
            PLATE(s -> s.is(ItemTags.WOODEN_PRESSURE_PLATES)), CHEST(s -> s.is(Items.CHEST)), BARREL(s -> s.is(Items.BARREL)),
            BENCH(s -> s.is(Items.CRAFTING_TABLE)), COMPOSTER(s -> s.is(Items.COMPOSTER)), COAL(s -> s.is(ItemTags.COALS)),
            TORCH(s -> s.is(Items.TORCH)), COBBLE(s -> s.is(ItemTags.STONE_CRAFTING_MATERIALS)), FURNACE(s -> s.is(Items.FURNACE)),
            IRON(s -> s.is(Items.IRON_INGOT)), CAULDRON(s -> s.is(Items.CAULDRON)), BUCKET(s -> s.is(Items.BUCKET)),
            NUGGET(s -> s.is(Items.IRON_NUGGET)), LANTERN(s -> s.is(Items.LANTERN)), BOOK(s -> s.is(Items.BOOK)),
            BOOKSHELF(s -> s.is(Items.BOOKSHELF)), REDSTONE(s -> s.is(Items.REDSTONE)), NOTE_BLOCK(s -> s.is(Items.NOTE_BLOCK));

            final Predicate<ItemStack> what;

            Kind(Predicate<ItemStack> what) {
                this.what = what;
            }
        }

        /** Whose hand a recipe wants. */
        enum Skill { ANYONE, WOOD, STONE, IRON }

        record Part(Kind kind, int n) {}

        record Recipe(int out, List<Part> in, Skill skill, boolean bench) {}

        /** The game's recipes, the stuff a step makes first listed first (sticks before the planks a fence also wants). */
        static final Map<Kind, Recipe> RECIPES = new EnumMap<>(Kind.class);

        static {
            RECIPES.put(Kind.PLANKS, new Recipe(4, List.of(new Part(Kind.LOGS, 1)), Skill.ANYONE, false));
            RECIPES.put(Kind.STICKS, new Recipe(4, List.of(new Part(Kind.PLANKS, 2)), Skill.ANYONE, false));
            RECIPES.put(Kind.BENCH, new Recipe(1, List.of(new Part(Kind.PLANKS, 4)), Skill.ANYONE, false));
            RECIPES.put(Kind.SLABS, new Recipe(6, List.of(new Part(Kind.PLANKS, 3)), Skill.WOOD, true));
            RECIPES.put(Kind.STAIRS, new Recipe(4, List.of(new Part(Kind.PLANKS, 6)), Skill.WOOD, true));
            RECIPES.put(Kind.FENCE, new Recipe(3, List.of(new Part(Kind.STICKS, 2), new Part(Kind.PLANKS, 4)), Skill.WOOD, true));
            RECIPES.put(Kind.PLATE, new Recipe(1, List.of(new Part(Kind.PLANKS, 2)), Skill.WOOD, false));
            RECIPES.put(Kind.CHEST, new Recipe(1, List.of(new Part(Kind.PLANKS, 8)), Skill.WOOD, true));
            RECIPES.put(Kind.BARREL, new Recipe(1, List.of(new Part(Kind.SLABS, 2), new Part(Kind.PLANKS, 6)), Skill.WOOD, true));
            RECIPES.put(Kind.COMPOSTER, new Recipe(1, List.of(new Part(Kind.SLABS, 7)), Skill.WOOD, true));
            RECIPES.put(Kind.TORCH, new Recipe(4, List.of(new Part(Kind.STICKS, 1), new Part(Kind.COAL, 1)), Skill.ANYONE, false));
            RECIPES.put(Kind.FURNACE, new Recipe(1, List.of(new Part(Kind.COBBLE, 8)), Skill.STONE, true));
            RECIPES.put(Kind.CAULDRON, new Recipe(1, List.of(new Part(Kind.IRON, 7)), Skill.IRON, true));
            RECIPES.put(Kind.BUCKET, new Recipe(1, List.of(new Part(Kind.IRON, 3)), Skill.IRON, true));
            RECIPES.put(Kind.NUGGET, new Recipe(9, List.of(new Part(Kind.IRON, 1)), Skill.IRON, false));
            RECIPES.put(Kind.LANTERN, new Recipe(1, List.of(new Part(Kind.TORCH, 1), new Part(Kind.NUGGET, 8)), Skill.IRON, true));
            RECIPES.put(Kind.BOOKSHELF, new Recipe(1, List.of(new Part(Kind.BOOK, 3), new Part(Kind.PLANKS, 6)), Skill.WOOD, true));
            RECIPES.put(Kind.NOTE_BLOCK, new Recipe(1, List.of(new Part(Kind.REDSTONE, 1), new Part(Kind.PLANKS, 8)), Skill.WOOD, true));
        }

        /** The kind a need's thing is, if it is one this joinery makes. */
        @Nullable
        static Kind kindOf(Need need) {
            ItemStack s = new ItemStack(need.sample());
            for (Kind k : Kind.values()) if (RECIPES.containsKey(k) && k.what.test(s) && need.what().test(s)) return k;
            return null;
        }

        /** Has this folk the hand for a recipe: a woodworker (the woodcutter, the shopkeeper's bench, the fletcher, a
         *  whittler in its own time), a mason (the miner, the smelter, the smith), a smith for iron; anybody for planks. */
        static boolean skilled(VillageFolkEntity f, Skill s) {
            StationTask t = f.stationTask();
            return switch (s) {
                case ANYONE -> true;
                case WOOD -> t == StationTask.WOOD || t == StationTask.SHOP || t == StationTask.FLETCHER
                    || f.persona().hobby() == Persona.Hobby.WHITTLING;
                case STONE -> t == StationTask.MINE || t == StationTask.SMELT || t == StationTask.SMITH || t == StationTask.CAVE;
                case IRON -> t == StationTask.SMITH;
            };
        }

        /** Making it: so many more planks it must buy first (0: it has everything). */
        record Plan(int planks) {}

        /** The household's own stuff, counted: its pack and its home's boxes. */
        private static Map<Kind, Integer> counts(ServerLevel level, UUID village, Homes.Home h, VillageFolkEntity f) {
            Map<Kind, Integer> out = new EnumMap<>(Kind.class);
            for (Kind k : Kind.values()) out.put(k, 0);
            List<ItemStack> all = new ArrayList<>(f.getInventoryItems());
            for (HomeStores.Box b : HomeStores.of(level, village, h)) {
                if (!(level.getBlockEntity(b.pos()) instanceof Container c)) continue;
                for (int i = 0; i < c.getContainerSize(); i++) all.add(c.getItem(i));
            }
            for (ItemStack s : all) {
                if (s.isEmpty() || !ours(s, h)) continue;
                for (Kind k : Kind.values()) if (k.what.test(s)) { out.merge(k, s.getCount(), Integer::sum); break; }
            }
            return out;
        }

        /** Has the home a bench to make it at (one standing in it, or one of its own to hand)? */
        private static boolean bench(ServerLevel level, UUID village, Homes.Home h, Map<Kind, Integer> c) {
            if (c.getOrDefault(Kind.BENCH, 0) > 0) return true;
            Rooms rooms = rooms(level, village, h);
            for (BlockPos p : rooms.floor()) {
                for (Direction d : Direction.Plane.HORIZONTAL) if (level.getBlockState(p.relative(d)).getBlock() instanceof CraftingTableBlock) return true;
            }
            return false;
        }

        /** Can it make so many of the need, and how short is it of planks (null: not its work, or short of more than planks)? */
        @Nullable
        static Plan plan(ServerLevel level, Villages.Village v, Homes.Home h, VillageFolkEntity f, Need need, int n) {
            Kind k = kindOf(need);
            if (k == null) return null;
            Recipe r = RECIPES.get(k);
            if (r == null || !skilled(f, r.skill())) return null;
            Map<Kind, Integer> c = counts(level, v.id(), h, f);
            boolean bench = bench(level, v.id(), h, c);
            for (int extra = 0; extra <= 64; extra += 2) {
                Map<Kind, Integer> sim = new EnumMap<>(c);
                sim.merge(Kind.PLANKS, extra, Integer::sum);
                if (!bench && needsBench(k) && !ensure(f, sim, Kind.BENCH, 1, null)) continue;
                if (ensure(f, sim, k, n, null)) return new Plan(extra);
            }
            return null;
        }

        private static boolean needsBench(Kind k) {
            Recipe r = RECIPES.get(k);
            if (r == null) return false;
            if (r.bench()) return true;
            for (Part p : r.in()) if (needsBench(p.kind())) return true;
            return false;
        }

        /**
         * So many of a kind to hand, made if need be: counted only ({@code real} null), or made for real out of the
         * household's stuff into this folk's pack.
         */
        private static boolean ensure(VillageFolkEntity f, Map<Kind, Integer> c, Kind k, int n, @Nullable Real real) {
            int have = c.getOrDefault(k, 0);
            if (have >= n) return true;
            Recipe r = RECIPES.get(k);
            if (r == null || !skilled(f, r.skill())) return false;
            int batches = (n - have + r.out() - 1) / r.out();
            for (Part p : r.in()) if (!ensure(f, c, p.kind(), p.n() * batches, real)) return false;
            for (Part p : r.in()) if (c.getOrDefault(p.kind(), 0) < p.n() * batches) return false;
            for (Part p : r.in()) c.merge(p.kind(), -p.n() * batches, Integer::sum);
            c.merge(k, r.out() * batches, Integer::sum);
            if (real != null) real.craft(k, r, batches);
            return true;
        }

        /** The making done for real: what each step uses taken out, what it makes put in its pack, its own. */
        private static final class Real {
            final ServerLevel level;
            final UUID village;
            final Homes.Home h;
            final VillageFolkEntity f;
            /** The wood it works in: the planks it uses first. */
            String wood = "oak";

            Real(ServerLevel level, UUID village, Homes.Home h, VillageFolkEntity f) {
                this.level = level;
                this.village = village;
                this.h = h;
                this.f = f;
            }

            void craft(Kind k, Recipe r, int batches) {
                for (Part p : r.in()) {
                    List<ItemStack> used = takeOwn(p.kind().what, p.n() * batches);
                    if (!used.isEmpty() && (p.kind() == Kind.PLANKS || p.kind() == Kind.LOGS)) wood = woodOf(used.get(0).getItem());
                }
                Item out = switch (k) {
                    case PLANKS -> wooden("_planks");
                    case SLABS -> wooden("_slab");
                    case STAIRS -> wooden("_stairs");
                    case FENCE -> wooden("_fence");
                    case PLATE -> wooden("_pressure_plate");
                    case STICKS -> Items.STICK;
                    case CHEST -> Items.CHEST;
                    case BARREL -> Items.BARREL;
                    case BENCH -> Items.CRAFTING_TABLE;
                    case COMPOSTER -> Items.COMPOSTER;
                    case TORCH -> Items.TORCH;
                    case FURNACE -> Items.FURNACE;
                    case CAULDRON -> Items.CAULDRON;
                    case BUCKET -> Items.BUCKET;
                    case NUGGET -> Items.IRON_NUGGET;
                    case LANTERN -> Items.LANTERN;
                    case BOOKSHELF -> Items.BOOKSHELF;
                    case NOTE_BLOCK -> Items.NOTE_BLOCK;
                    default -> Items.AIR;
                };
                if (out == Items.AIR) return;
                int left = r.out() * batches;
                while (left > 0) {
                    int n = Math.min(left, out.getDefaultMaxStackSize());
                    ItemStack made = new ItemStack(out, n);
                    Homes.keepsake(made, f, Homes.Source.CRAFT);
                    ItemStack over = f.insertItem(made);
                    if (!over.isEmpty()) {
                        ItemStack still = HomeStores.putAway(level, village, h, over, false);
                        if (!still.isEmpty()) f.spawnAtLocation(still);
                    }
                    left -= n;
                }
            }

            Item wooden(String suffix) {
                Item it = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(wood + suffix));
                return it == Items.AIR ? BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace("oak" + suffix)) : it;
            }

            /** So many of what matches, of the household's own, out of its pack first and then the home's boxes. */
            List<ItemStack> takeOwn(Predicate<ItemStack> what, int n) {
                List<ItemStack> out = new ArrayList<>();
                int left = n;
                List<ItemStack> pack = f.getInventoryItems();
                for (int i = 0; i < pack.size() && left > 0; i++) {
                    ItemStack s = pack.get(i);
                    if (s.isEmpty() || !what.test(s) || !ours(s, h)) continue;
                    int k = Math.min(left, s.getCount());
                    out.add(s.copyWithCount(k));
                    s.shrink(k);
                    if (s.isEmpty()) pack.set(i, ItemStack.EMPTY);
                    left -= k;
                }
                for (HomeStores.Box b : HomeStores.of(level, village, h)) {
                    if (left <= 0) break;
                    if (!(level.getBlockEntity(b.pos()) instanceof Container c)) continue;
                    for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
                        ItemStack s = c.getItem(i);
                        if (s.isEmpty() || !what.test(s) || !ours(s, h)) continue;
                        int k = Math.min(left, s.getCount());
                        out.add(s.copyWithCount(k));
                        s.shrink(k);
                        if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
                        c.setChanged();
                        left -= k;
                    }
                }
                return out;
            }
        }

        /** The wood a plank or a log is of: "oak", "dark_oak", "crimson". */
        static String woodOf(Item it) {
            String path = BuiltInRegistries.ITEM.getKey(it).getPath().replace("stripped_", "");
            for (String suffix : new String[]{ "_planks", "_log", "_wood", "_stem", "_hyphae", "_block" }) {
                if (path.endsWith(suffix)) {
                    String base = path.substring(0, path.length() - suffix.length());
                    if (BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(base + "_planks")) != Items.AIR) return base;
                }
            }
            return "oak";
        }

        /** Make it, for real: so many of the need into its pack. False (and nothing used) if it cannot. */
        static boolean make(ServerLevel level, Villages.Village v, Homes.Home h, VillageFolkEntity f, Need need, int n) {
            Kind k = kindOf(need);
            if (k == null) return false;
            Map<Kind, Integer> c = counts(level, v.id(), h, f);
            Map<Kind, Integer> sim = new EnumMap<>(c);
            boolean bench = bench(level, v.id(), h, c);
            if (!bench && needsBench(k) && !ensure(f, sim, Kind.BENCH, 1, null)) return false;
            if (!ensure(f, sim, k, n, null)) return false;
            Real real = new Real(level, v.id(), h, f);
            if (!bench && needsBench(k)) ensure(f, c, Kind.BENCH, 1, real);   // a bench of its own first, and kept
            return ensure(f, c, k, n, real);
        }

        /** The planks the shop has most of, to buy for making. */
        static ItemStack plankSample(ServerLevel level, UUID village) {
            ItemStack best = new ItemStack(Items.OAK_PLANKS);
            int most = -1;
            for (Item it : List.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS,
                    Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS)) {
                int n = ShopStock.count(level, village, s -> s.is(it));
                if (n > most) { most = n; best = new ItemStack(it); }
            }
            return best;
        }
    }
}
