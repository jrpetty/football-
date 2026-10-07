package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageBoardBlock;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * The town's arms on everything [arms]. Heraldry draws them (the field of its land, a charge for the land, one
 * for its name, one for whoever led it) and hangs them on the hall, the gates, the market and the theatre; this
 * carries them everywhere else the town goes, every piece the tailor's banner, woven of the stores' wool and dye
 * at the loom, never one out of nothing:
 * <ul>
 * <li><b>Flown</b> on the corner towers of the wall (the "fortify" ring's squat towers), and on two poles at the
 *     ends of the board, facing the square, besides Heraldry's places.</li>
 * <li><b>On the watch's shields.</b> A guard's plain shield (the town's issue, WatchKit) is given the arms as a
 *     player gives a shield a banner at the crafting table: the tailor weaves the banner (six wool and a stick, a
 *     dye a charge) and it goes onto the shield, used up. A shield keeps the arms it was given.</li>
 * <li><b>On the road.</b> Whoever goes out of the town on its business (a caravan, a trade run, an envoy) carries
 *     its banner in its free hand: one of the town's woven banners out of the stores, or one woven then, and back
 *     into the stores when it comes home. (The guards lent to an ally's walls go as the watch, under no banner.)</li>
 * <li><b>The war banner</b> (WarBanner) is the stores' cloth with the town's charges woven on it, a dye a charge.</li>
 * <li><b>Festival tabards.</b> The tailor keeps the town a set of tabards (seven wool cut like a tunic, the arms put
 *     on it with a banner at the crafting table, as a shield's are): on a festival's day and on Founding Day they
 *     are lent out of the stores in the morning to the grown folk who are not the watch, worn over their clothes
 *     (FolkRenderer's tabard), and taken back in at night.</li>
 * <li><b>A citizen's banner.</b> A player made a citizen is given one, woven out of the stores; if the stores
 *     cannot run to it, the shop's sign gives it free once they can.</li>
 * <li><b>Grants.</b> A great day may add a charge, six at most, as the loom allows: the first new age a charge for
 *     what the town lives by (a fish for its fishers, a pick for its miners, a sheaf for its farmers: the mod's own
 *     patterns, their pattern made at the loom of paper and the thing), later ages one of their own, and a war won a
 *     red saltire. A new leader may give the town a new motto, out of its own heart. The banners already up come
 *     down a place at a time and the new arms go up (Heraldry.hang); the chronicle takes it all down.</li>
 * <li><b>The board</b> draws the arms in its header, either side of the town's name (a line "AN|..." the
 *     renderer reads: VillageBoardRenderer, client/BoardArms).</li>
 * </ul>
 */
public final class Arms {

    private Arms() {}

    /** As many charges as the loom will weave on one banner. */
    static final int MOST = 6;
    /** The mark on a banner or a tabard lent out of the stores, so it goes back into them. */
    static final String LENT = "mca_arms_lent";
    /** The tabards a town keeps for its festivals, at most. */
    static final int TABARDS = 6;

    /** What the town's arms wait on, in words, by town (the books). */
    private static final Map<UUID, String> SHORT = new ConcurrentHashMap<>();
    /** When each town's arms are next looked at, and its slower works (a shield, a tabard). */
    private static final Map<UUID, Long> DUE = new ConcurrentHashMap<>(), SLOW = new ConcurrentHashMap<>();

    static void resetForTests() {
        SHORT.clear();
        DUE.clear();
        SLOW.clear();
    }

    // ------------------------------------------------------------------ the charges a town is granted

    /** The pattern the loom wants for one of the mod's charges (a fish, a pick, a sheaf); null for the rest. */
    @Nullable
    static Item patternItem(Heraldry.Charge c) {
        return switch (c) {
            case FISH -> McAssistantMod.FISH_PATTERN.get();
            case PICK -> McAssistantMod.PICK_PATTERN.get();
            case SHEAF -> McAssistantMod.SHEAF_PATTERN.get();
            default -> null;
        };
    }

    /**
     * The patterns these arms want on the loom, to hand: in the stores already (a pattern is not used up at the
     * loom, so the one made is kept there), or made now at the tailor's hand of the stores' paper and a fish, a
     * pickaxe or wheat (Bench). Null when they are, else what is short, in words.
     */
    @Nullable
    static String patternsToHand(ServerLevel level, Villages.Village v, Heraldry.Design d, Bench.Hand hand) {
        for (Heraldry.Layer l : d.layers()) {
            Item it = patternItem(l.charge());
            if (it == null || Crafts.stock(level, v, s -> s.is(it)) > 0) continue;
            Bench.Plan p = Bench.plan(level, v, it, 1, hand);
            if (!p.ok()) return "the " + l.charge().noun + " pattern for the loom (" + p.shortOf + ")";
            if (Bench.make(level, v, p, Heraldry.tailor(v.id()), hand).isEmpty()) return "the " + l.charge().noun + " pattern for the loom";
        }
        return null;
    }

    /** A plan that cannot be worked, short of this. */
    static Bench.Plan shortOf(String what) {
        return new Bench.Plan(null, 0, List.of(), Map.of(), Map.of(), List.of(), what, null, 0, "");
    }

    /** What the town lives by: the most of its fishers, miners and farmers; with none of them, its land's. */
    static Heraldry.Charge livesBy(UUID village) {
        int fish = 0, mine = 0, farm = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            switch (a.stationTask()) {
                case FISH -> fish++;
                case MINE -> mine++;
                case FARM, RANCH -> farm++;
                default -> { }
            }
        }
        if (fish + mine + farm > 0) return fish > mine && fish > farm ? Heraldry.Charge.FISH : mine > farm ? Heraldry.Charge.PICK : Heraldry.Charge.SHEAF;
        return switch (Homeland.of(village)) {
            case COAST, RIVER, SWAMP -> Heraldry.Charge.FISH;
            case MOUNTAIN, BADLANDS -> Heraldry.Charge.PICK;
            default -> Heraldry.Charge.SHEAF;
        };
    }

    /** Gold on a dark field, black on a light one. */
    static DyeColor metal(DyeColor field) {
        return switch (field) {
            case WHITE, YELLOW, LIME, PINK, LIGHT_BLUE, LIGHT_GRAY, ORANGE, MAGENTA -> DyeColor.BLACK;
            default -> DyeColor.YELLOW;
        };
    }

    /** The town's arms as they were before each grant, the oldest first. */
    static List<Heraldry.Design> was(UUID village) {
        List<Heraldry.Design> out = new ArrayList<>();
        String s = Ledger.note(village, "culture.arms.was");
        if (s == null || s.isEmpty()) return out;
        for (String e : s.split(";")) {
            Heraldry.Design d = Heraldry.Design.decode(e);
            if (d != null) out.add(d);
        }
        return out;
    }

    /** The grants so far, in words, the oldest first ("day 30: a fish, for its fishers..."). */
    static List<String> grants(UUID village) {
        String s = Ledger.note(village, "culture.arms.grants");
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        Collections.addAll(out, s.split("\n"));
        return out;
    }

    /**
     * A charge granted the town's arms for a great day, woven on last, in a colour that shows on the field; the
     * old arms kept in the books (their banners come down: Heraldry.hang), the chronicle told. False if the arms
     * are full (six charges) or already bear it.
     */
    static boolean grant(ServerLevel level, Villages.Village v, Heraldry.Charge c, DyeColor colour, String why) {
        UUID id = v.id();
        Heraldry.Design d = Heraldry.design(id);
        if (d == null || d.layers().size() >= MOST) return false;
        for (Heraldry.Layer l : d.layers()) if (l.charge() == c) return false;
        DyeColor shows = Heraldry.contrast(colour, d.field());
        Heraldry.Design next = d.with(new Heraldry.Layer(c, shows));
        String old = Ledger.note(id, "culture.arms.was");
        Ledger.note(id, "culture.arms.was", (old == null || old.isEmpty() ? "" : old + ";") + d.encode());
        Ledger.note(id, "culture.banner", next.encode());
        long day = level.getDayTime() / 24000L;
        String col = Heraldry.colourWord(shows);
        String text = "the town's arms were granted " + (c.one ? ("aeiou".indexOf(col.charAt(0)) >= 0 ? "an " : "a ") : "") + col + " " + c.noun
            + ", " + why;
        Villages.tell(id, day, text);
        String history = Ledger.note(id, "culture.arms.grants");
        Ledger.note(id, "culture.arms.grants", (history == null || history.isEmpty() ? "" : history + "\n") + "day " + day + ": " + text);
        Heraldry.soon(id);
        return true;
    }

    /**
     * The town's look for great days (each time its arms are looked at): a new age grants a charge, the first
     * what the town lives by, then the age's own; a new leader may give the town a new motto. What was seen last is
     * kept in the books, so a restart grants nothing twice.
     */
    static void greatDays(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Heraldry.design(id) == null) return;
        Villages.Age age = Villages.ageOf(id);
        String seen = Ledger.note(id, "culture.arms.age");
        if (seen == null || seen.isEmpty()) {
            Ledger.note(id, "culture.arms.age", Integer.toString(age.ordinal()));
        } else if (age.ordinal() > Culture.num(seen, age.ordinal())) {
            Ledger.note(id, "culture.arms.age", Integer.toString(age.ordinal()));
            newAge(level, v, age);
        }
        UUID elder = Villages.elder(id);
        String was = Ledger.note(id, "culture.arms.leader");
        if (elder != null && (was == null || was.isEmpty())) {
            Ledger.note(id, "culture.arms.leader", elder.toString());
        } else if (elder != null && !was.equals(elder.toString())) {
            Ledger.note(id, "culture.arms.leader", elder.toString());
            newMotto(level, v);
        }
    }

    /** A new age's grant: what the town lives by, if its arms do not bear it yet; else the age's own charge. */
    static boolean newAge(ServerLevel level, Villages.Village v, Villages.Age age) {
        UUID id = v.id();
        Heraldry.Design d = Heraldry.design(id);
        if (d == null) return false;
        boolean bearsTrade = false;
        for (Heraldry.Layer l : d.layers()) bearsTrade |= patternItem(l.charge()) != null;
        if (!bearsTrade) {
            Heraldry.Charge c = livesBy(id);
            String who = c == Heraldry.Charge.FISH ? "its fishers" : c == Heraldry.Charge.PICK ? "its miners" : "its farmers";
            return grant(level, v, c, metal(d.field()), "for " + who + ", on coming into " + age.label);
        }
        Heraldry.Charge[] tries;
        DyeColor colour;
        switch (age) {
            case IRON -> { tries = new Heraldry.Charge[]{ Heraldry.Charge.BORDER, Heraldry.Charge.CHIEF, Heraldry.Charge.BASE }; colour = DyeColor.LIGHT_GRAY; }
            case DIAMOND -> { tries = new Heraldry.Charge[]{ Heraldry.Charge.LOZENGE, Heraldry.Charge.ROUNDEL }; colour = DyeColor.CYAN; }
            case NETHER -> { tries = new Heraldry.Charge[]{ Heraldry.Charge.BASE, Heraldry.Charge.CHIEF }; colour = DyeColor.BLACK; }
            default -> { tries = new Heraldry.Charge[]{ Heraldry.Charge.CHIEF, Heraldry.Charge.BASE }; colour = DyeColor.GRAY; }
        }
        for (Heraldry.Charge c : tries) if (grant(level, v, c, colour, "on coming into " + age.label)) return true;
        return false;
    }

    /**
     * Peace made (WarAndPeace.makePeace): the winner's arms granted a red saltire (a cross, a chevron if it
     * bears one) for the war won. {@code winner} null: neither won.
     */
    public static void peace(ServerLevel level, @Nullable UUID winner, UUID loser) {
        Villages.Village v = winner == null ? null : Villages.get(winner);
        if (v == null) return;
        String why = "for the war with " + Villages.name(loser) + " won";
        for (Heraldry.Charge c : new Heraldry.Charge[]{ Heraldry.Charge.SALTIRE, Heraldry.Charge.CROSS, Heraldry.Charge.CHEVRON }) {
            if (grant(level, v, c, DyeColor.RED, why)) return;
        }
    }

    /** A new leader's motto, out of its own heart, if it is not the town's already. Whether it changed. */
    static boolean newMotto(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        String old = Heraldry.motto(id), now = Heraldry.mottoFor(id);
        if (old == null || now.equals(old)) return false;
        Ledger.note(id, "culture.motto", now);
        VillageFolkEntity elder = Orders.elderOf(id);
        long day = level.getDayTime() / 24000L;
        String text = (elder == null ? "the new leader" : "the new leader, " + elder.displayNameCap() + ",") + " gave the town a new motto: “"
            + now + "” (it was “" + old + "”)";
        Villages.tell(id, day, text);
        String history = Ledger.note(id, "culture.arms.grants");
        Ledger.note(id, "culture.arms.grants", (history == null || history.isEmpty() ? "" : history + "\n") + "day " + day + ": " + text);
        if (elder != null) elder.persona().remember(day, "I gave the town its new motto", 3);
        return true;
    }

    // ------------------------------------------------------------------ the banner as a thing

    /** Does this banner (its field and its charges, in order) show these arms? */
    static boolean bears(DyeColor base, BannerPatternLayers layers, Heraldry.Design d) {
        if (base != d.field() || layers.layers().size() != d.layers().size()) return false;
        for (int i = 0; i < d.layers().size(); i++) {
            BannerPatternLayers.Layer l = layers.layers().get(i);
            Heraldry.Layer want = d.layers().get(i);
            if (l.color() != want.colour() || !l.pattern().is(want.charge().key)) return false;
        }
        return true;
    }

    /** Does this banner, shield or tabard bear these arms? */
    public static boolean bears(ItemStack s, Heraldry.Design d) {
        if (s.isEmpty()) return false;
        DyeColor base = s.getItem() instanceof BannerItem bi ? bi.getColor() : s.get(DataComponents.BASE_COLOR);
        return base != null && bears(base, s.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY), d);
    }

    /** No arms on it yet (a shield or a tabard as it comes off the bench). */
    static boolean plain(ItemStack s) {
        return s.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY).layers().isEmpty() && !s.has(DataComponents.BASE_COLOR);
    }

    /** A banner's arms put on a shield or a tabard, as the crafting table does it: its charges, and its field. */
    static void emblazon(ItemStack on, ItemStack banner) {
        on.set(DataComponents.BANNER_PATTERNS, banner.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY));
        on.set(DataComponents.BASE_COLOR, banner.getItem() instanceof BannerItem bi ? bi.getColor() : DyeColor.WHITE);
    }

    /** One banner of the town's arms woven now at the tailor's hand, out of the stores; null (and why kept) if they cannot run to it. */
    @Nullable
    static ItemStack weave(ServerLevel level, Villages.Village v, Heraldry.Design d, String forWhat) {
        UUID id = v.id();
        Bench.Plan plan = Heraldry.plan(level, v, d);
        if (!plan.ok()) {
            SHORT.put(id, forWhat + " waits on " + plan.shortOf + (plan.why.isEmpty() ? "" : " (" + plan.why + ")"));
            return null;
        }
        if (!Bench.take(level, v, plan, Heraldry.tailor(id))) return null;
        SHORT.remove(id);
        return Heraldry.item(level, id, d);
    }

    /** One of what matches, out of the stores as it is (its arms and its marks), booked out to {@code who}; or null. */
    @Nullable
    static ItemStack takeOne(ServerLevel level, Villages.Village v, Predicate<ItemStack> what, String who) {
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (!st.isEmpty() && what.test(st)) {
                    ItemStack got = Workshop.takeOut(level, v.id(), new Workshop.Found(c, i, 0), who);
                    Villages.forgetStock();
                    return got;
                }
            }
        }
        return null;
    }

    static ItemStack lend(ItemStack s) {
        CustomData.update(DataComponents.CUSTOM_DATA, s, t -> t.putBoolean(LENT, true));
        return s;
    }

    static boolean lent(ItemStack s) {
        CustomData data = s.isEmpty() ? null : s.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(LENT);
    }

    static ItemStack unlend(ItemStack s) {
        if (!lent(s)) return s;
        CompoundTag tag = s.get(DataComponents.CUSTOM_DATA).copyTag();
        tag.remove(LENT);
        if (tag.isEmpty()) s.remove(DataComponents.CUSTOM_DATA);
        else s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    // ------------------------------------------------------------------ where it flies

    /** The wall's corner towers (a banner on the outside, below the top), and a pole at each end of the board. */
    static List<Heraldry.Place> places(ServerLevel level, UUID village) {
        List<Heraldry.Place> out = new ArrayList<>();
        BlockPos a = Watch.wall(village);
        if (a != null) {
            for (Direction side : new Direction[]{ Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST }) {
                Direction along = side.getClockWise();
                BlockPos corner = a.relative(side, Watch.R).relative(along, Watch.R);
                if (!level.isLoaded(corner)) continue;
                BlockPos top = Watch.wallTop(level, corner.getX(), corner.getZ(), a.getY());
                if (top == null) continue;
                String quarter = (side.getAxis() == Direction.Axis.Z ? side : along).getName() + "-"
                    + (side.getAxis() == Direction.Axis.Z ? along : side).getName();
                out.add(new Heraldry.Place(new Culture.Spot(top.below().relative(side).immutable(), side), "the " + quarter + " tower"));
            }
        }
        // The board's poles are a town's, not a camp's: they go up once it has come into the Stone Age.
        BlockPos foot = VillageBoards.lectern(village);
        Direction f = VillageBoards.facingOf(village);
        if (foot != null && f != null && f.getAxis().isHorizontal() && level.isLoaded(foot)
                && Villages.ageOf(village).ordinal() >= Villages.Age.STONE.ordinal()) {
            Direction right = VillageBoardBlock.right(f);
            int first = -VillageBoardBlock.WIDE / 2, last = first + VillageBoardBlock.WIDE - 1;
            for (int along : new int[]{ first - 1, last + 1 }) {
                BlockPos spot = poleAt(level, foot.relative(right, along), foot.getY());
                if (spot != null) out.add(new Heraldry.Place(new Culture.Spot(spot, f), "the board", true));
            }
        }
        return out;
    }

    /** The foot of a pole on this column, near {@code y}: firm ground under it, not the road. Null if not. */
    @Nullable
    static BlockPos poleAt(ServerLevel level, BlockPos col, int y) {
        if (!level.isLoaded(col)) return null;
        BlockPos spot = new BlockPos(col.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col.getX(), col.getZ()), col.getZ());
        if (spot.getY() < y - 4 || spot.getY() > y + 1) return null;
        BlockPos below = spot.below();
        BlockState under = level.getBlockState(below);
        if (!under.isFaceSturdy(level, below, Direction.UP) || under.is(Blocks.DIRT_PATH)) return null;
        return spot.immutable();
    }

    /** Room for a pole: air (or grass, a flower) here and over it, no water. */
    static boolean poleGround(ServerLevel level, BlockPos at) {
        for (BlockPos p : new BlockPos[]{ at, at.above() }) {
            BlockState st = level.getBlockState(p);
            if (!st.isAir() && (!st.canBeReplaced() || !level.getFluidState(p).isEmpty())) return false;
        }
        return true;
    }

    /** The banner set up on its own pole, its cloth turned the way {@code facing} looks. */
    static boolean plant(ServerLevel level, BlockPos at, Direction facing, ItemStack banner) {
        DyeColor c = banner.getItem() instanceof BannerItem bi ? bi.getColor() : DyeColor.WHITE;
        if (!level.getBlockState(at.above()).isAir()) level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), 3);
        BlockState st = BannerBlock.byColor(c).defaultBlockState().setValue(BannerBlock.ROTATION, facing.get2DDataValue() * 4);
        level.setBlock(at, st, 3);
        if (level.getBlockEntity(at) instanceof BannerBlockEntity be) {
            be.applyComponentsFromItemStack(banner);
            be.setChanged();
        }
        level.playSound(null, at, st.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8F, 1.0F);
        return true;
    }

    /** Is the banner here the town's, of arms it has had before a grant (not as they are now)? */
    static boolean outworn(ServerLevel level, UUID village, BlockPos at, Heraldry.Design now) {
        if (!(level.getBlockEntity(at) instanceof BannerBlockEntity be)) return false;
        if (bears(be.getBaseColor(), be.getPatterns(), now)) return false;
        for (Heraldry.Design was : was(village)) if (bears(be.getBaseColor(), be.getPatterns(), was)) return true;
        return false;
    }

    /** A banner taken down as it is, into the stores (a keepsake of the old arms). */
    static void takeDown(ServerLevel level, Villages.Village v, BlockPos at) {
        if (!(level.getBlockEntity(at) instanceof BannerBlockEntity be)) return;
        ItemStack old = be.getItem();
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        Crafts.store(level, v, old);
    }

    // ------------------------------------------------------------------ the watch's shields

    /** A guard's shield with no arms on it, in its hand or its pack; null if it has none. */
    @Nullable
    static ItemStack plainShield(VillageFolkEntity g) {
        ItemStack off = g.getItemBySlot(EquipmentSlot.OFFHAND);
        if (off.getItem() instanceof ShieldItem && plain(off)) return off;
        for (ItemStack s : g.getInventoryItems()) if (s.getItem() instanceof ShieldItem && plain(s)) return s;
        return null;
    }

    /**
     * The town's arms on a guard's plain shield, at the stores (WatchKit.fit, and the town's look): the tailor
     * weaves the banner out of the stores' wool, a stick and a dye a charge, and it goes onto the shield as at the
     * crafting table, used up. Whether it was done.
     */
    public static boolean shield(ServerLevel level, Villages.Village v, VillageFolkEntity g) {
        UUID id = v.id();
        Heraldry.Design d = Heraldry.design(id);
        if (d == null || g.stationTask() != AssistantEntity.StationTask.GUARD || g.isBaby() || g.isShowcase() || !g.isAlive()) return false;
        ItemStack shield = plainShield(g);
        if (shield == null) return false;
        ItemStack banner = weave(level, v, d, g.displayNameCap() + "'s shield");
        if (banner == null) return false;
        emblazon(shield, banner);
        if (g.getItemBySlot(EquipmentSlot.OFFHAND) == shield) g.setItemSlot(EquipmentSlot.OFFHAND, shield);
        long day = level.getDayTime() / 24000L;
        int n = (int) Culture.num(String.valueOf(Ledger.note(id, "culture.arms.shields")), 0) + 1;
        Ledger.note(id, "culture.arms.shields", Integer.toString(n));
        VillageFolkEntity tailor = Heraldry.tailor(id);
        if (n == 1) {
            Villages.tell(id, day, "the watch first carried the town's arms on its shields: " + g.displayNameCap() + "'s"
                + (tailor != null ? ", the banner for it woven by " + tailor.displayNameCap() + " the tailor" : ""));
        }
        g.persona().remember(day, "the town's arms were put on my shield", 2);
        FolkTalk.speak(g, FolkTalk.pick(g.getRandom(), "Our arms on my shield! I'll carry them proud.",
            "There: the arms of " + Villages.name(id) + ", for all to see.", "Let them come at the arms of " + Villages.name(id) + "."));
        return true;
    }

    /** The town's look at the watch: one guard by the heart with a plain shield given the arms. */
    static void shields(ServerLevel level, Villages.Village v) {
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof VillageFolkEntity g) || g.stationTask() != AssistantEntity.StationTask.GUARD) continue;
            if (g.distanceToSqr(v.centre().getX() + 0.5, g.getY(), v.centre().getZ() + 0.5) > 24.0 * 24.0) continue;
            if (plainShield(g) == null) continue;
            shield(level, v, g);
            return;
        }
    }

    /** How many of the watch carry the town's arms (as they are now, or as they were) on a shield, of how many. */
    static int[] watchUnderArms(UUID village) {
        int under = 0, all = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (!(a instanceof VillageFolkEntity g) || g.stationTask() != AssistantEntity.StationTask.GUARD) continue;
            all++;
            ItemStack off = g.getItemBySlot(EquipmentSlot.OFFHAND);
            boolean has = off.getItem() instanceof ShieldItem && !plain(off);
            for (ItemStack s : g.getInventoryItems()) has |= s.getItem() instanceof ShieldItem && !plain(s);
            if (has) under++;
        }
        return new int[]{ under, all };
    }

    // ------------------------------------------------------------------ on the road

    /**
     * The banner carried on the road: whoever leads a party out (the first of it the town looks at) takes one of
     * the town's woven banners out of the stores into its free hand, or one woven then; home again, it goes back.
     */
    static void carry(ServerLevel level, Villages.Village v, Heraldry.Design d) {
        UUID id = v.id();
        Set<Caravans.Trip> served = Collections.newSetFromMap(new IdentityHashMap<>());
        List<VillageFolkEntity> setting = new ArrayList<>();
        double home = 48.0 * 48.0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || !f.isAlive()) continue;
            ItemStack off = f.getItemBySlot(EquipmentSlot.OFFHAND);
            boolean carrying = off.getItem() instanceof BannerItem && lent(off);
            if (f.trip() != null) {
                Caravans.Trip t = f.trip();
                if (carrying) served.add(t);
                else if (off.isEmpty() && t.from.equals(id) && !t.back && t.errand != Envoys.Errand.WAR
                    && f.distanceToSqr(v.centre().getX(), f.getY(), v.centre().getZ()) < home) setting.add(f);
                continue;
            }
            if (carrying && f.distanceToSqr(v.centre().getX(), f.getY(), v.centre().getZ()) < home) {
                f.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
                Workshop.backIntoStores(level, v, unlend(off.copy()), f.displayNameCap());
            }
        }
        for (VillageFolkEntity f : setting) {
            if (served.contains(f.trip())) continue;
            ItemStack banner = takeOne(level, v, s -> s.getItem() instanceof BannerItem && bears(s, d), f.displayNameCap());
            if (banner == null) banner = weave(level, v, d, "the banner for the road");
            if (banner == null) continue;
            f.setItemSlot(EquipmentSlot.OFFHAND, lend(banner));
            served.add(f.trip());
            int n = (int) Culture.num(String.valueOf(Ledger.note(id, "culture.arms.road")), 0) + 1;
            Ledger.note(id, "culture.arms.road", Integer.toString(n));
            Caravans.Trip t = f.trip();
            String where = Villages.name(t.destination());
            if (n == 1) {
                Villages.tell(id, level.getDayTime() / 24000L, f.displayNameCap() + " carried the town's banner on the road for the first time, to " + where);
            }
            FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Under our banner, to " + where + "!", "They'll see us coming, flying the arms of "
                + Villages.name(id) + "."));
        }
    }

    // ------------------------------------------------------------------ the war banner

    /**
     * The war banner hung for a war (WarBanner.hang): the town's charges woven onto its cloth at the loom, a dye a
     * charge out of the stores (a charge the cloth's own colour in one that shows on it). Whether they were; with
     * no dyes it flies plain, as it always did.
     */
    public static boolean warArms(ServerLevel level, Villages.Village v, BlockPos at) {
        UUID id = v.id();
        Heraldry.Design d = Heraldry.design(id);
        if (d == null || !(level.getBlockEntity(at) instanceof BannerBlockEntity be) || !be.getPatterns().layers().isEmpty()) return false;
        DyeColor cloth = be.getBaseColor();
        List<Heraldry.Layer> layers = new ArrayList<>();
        for (Heraldry.Layer l : d.layers()) layers.add(new Heraldry.Layer(l.charge(), Heraldry.contrast(l.colour(), cloth)));
        Heraldry.Design war = new Heraldry.Design(cloth, List.copyOf(layers));
        Bench.Hand hand = Bench.handOf(level, v, Heraldry.tailor(id), "workshop");
        if (patternsToHand(level, v, war, hand) != null) return false;
        Map<Item, Integer> dyes = new LinkedHashMap<>();
        for (Heraldry.Layer l : war.layers()) dyes.merge(Heraldry.dyeItem(l.colour()), 1, Integer::sum);
        List<Bench.Want> wants = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : dyes.entrySet()) wants.add(Bench.Want.of(e.getKey(), e.getValue()));
        Bench.Plan plan = Bench.plan(level, v, wants, hand);
        if (!plan.ok() || !Bench.take(level, v, plan, Heraldry.tailor(id))) return false;
        ItemStack woven = Heraldry.item(level, id, war);
        woven.set(DataComponents.ITEM_NAME, Component.literal("War banner of " + Villages.name(id)));
        be.applyComponentsFromItemStack(woven);
        be.setChanged();
        BlockState st = level.getBlockState(at);
        level.sendBlockUpdated(at, st, st, 3);
        return true;
    }

    // ------------------------------------------------------------------ the festival tabards

    static boolean tabard(ItemStack s) {
        return s.is(McAssistantMod.TABARD.get());
    }

    /** Is today a festival's day, or Founding Day? */
    static boolean festival(UUID village, long day) {
        return Festivals.today(village, day) != null || FoundingDay.today(village, day);
    }

    /** What day it is, for the tabards: "the May dance", "Founding Day". */
    static String festivalWords(UUID village, long day) {
        Festivals.Feast f = Festivals.today(village, day);
        return f != null ? f.words : "Founding Day";
    }

    /** May this folk wear a festival tabard: grown, not the watch, nothing on its chest, not away? */
    static boolean wearer(VillageFolkEntity f) {
        return f.isAlive() && !f.isBaby() && !f.isShowcase() && f.stationTask() != AssistantEntity.StationTask.GUARD
            && f.stationTask() != AssistantEntity.StationTask.CAVE && f.trip() == null && f.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
    }

    /** The tabards the town keeps: one for each grown folk not of the watch, six at most. */
    static int tabardsWanted(UUID village) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && !f.isBaby() && f.stationTask() != AssistantEntity.StationTask.GUARD) n++;
        }
        return Math.min(TABARDS, n);
    }

    /** The town's tabards of its arms as they are: in the stores and on its folk's backs. */
    static int tabardsHeld(ServerLevel level, Villages.Village v, Heraldry.Design d) {
        int n = Crafts.stock(level, v, s -> tabard(s) && bears(s, d));
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            if (a instanceof VillageFolkEntity f && tabard(f.getItemBySlot(EquipmentSlot.CHEST)) && bears(f.getItemBySlot(EquipmentSlot.CHEST), d)) n++;
        }
        return n;
    }

    /**
     * A festival tabard made, if the town keeps fewer than it wants and it is no festival today: seven wool cut
     * like a tunic (the recipe) and the town's banner woven to go on it, out of the stores, at the tailor's hand;
     * the arms put on it as at the crafting table, and into the stores. True while a hand is on its way.
     */
    static boolean makeTabard(ServerLevel level, Villages.Village v, Heraldry.Design d, boolean free) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        if (festival(id, day) || tabardsHeld(level, v, d) >= tabardsWanted(id)) return false;
        VillageFolkEntity tailor = Heraldry.tailor(id);
        Bench.Hand hand = Bench.handOf(level, v, tailor, "workshop");
        if (!free) {
            String loom = patternsToHand(level, v, d, hand);
            if (loom != null) {
                SHORT.put(id, "a festival tabard waits on " + loom);
                return false;
            }
            List<Bench.Want> wants = new ArrayList<>(Heraldry.wants(d));
            wants.add(Bench.Want.of(McAssistantMod.TABARD.get(), 1));
            Bench.Plan plan = Bench.plan(level, v, wants, hand);
            if (!plan.ok()) {
                SHORT.put(id, "a festival tabard waits on " + plan.shortOf);
                return false;
            }
            if (!TownJobs.atWork(level, v, Heraldry.WORKS, v.centre(), "cutting a festival tabard of the town's arms")) return true;
            if (!Bench.take(level, v, plan, tailor)) return false;
            SHORT.remove(id);
        }
        ItemStack t = new ItemStack(McAssistantMod.TABARD.get());
        emblazon(t, Heraldry.item(level, id, d));
        t.set(DataComponents.ITEM_NAME, Component.literal("Tabard of " + Villages.name(id)));
        Crafts.store(level, v, t);
        int n = (int) Culture.num(String.valueOf(Ledger.note(id, "culture.arms.tabards")), 0) + 1;
        Ledger.note(id, "culture.arms.tabards", Integer.toString(n));
        if (n == 1) {
            Villages.tell(id, day, "the first festival tabard of the town's arms was made" + (tailor != null ? ", by " + tailor.displayNameCap() + " the tailor" : ""));
        }
        return true;
    }

    /**
     * The tabards on and off: on a festival's day, from the morning to the night, one each out of the stores for
     * the grown folk not of the watch, the first come first served; out of it, those lent go back into the stores
     * (one put in its pack by a folk who has since put armour on, too).
     */
    static void dress(ServerLevel level, Villages.Village v, Heraldry.Design d) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L, t = level.getDayTime() % 24000L;
        boolean on = festival(id, day) && t >= 1000L && t < 18000L;
        double home = 64.0 * 64.0;
        int lent = 0;
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || !f.isAlive()) continue;
            boolean near = f.distanceToSqr(v.centre().getX(), f.getY(), v.centre().getZ()) < home;
            ItemStack chest = f.getItemBySlot(EquipmentSlot.CHEST);
            if (!on && near) {
                if (tabard(chest) && lent(chest)) {
                    f.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
                    Workshop.backIntoStores(level, v, unlend(chest.copy()), f.displayNameCap());
                }
                var pack = f.getInventoryItems();
                for (int i = 0; i < pack.size(); i++) {
                    if (!tabard(pack.get(i)) || !lent(pack.get(i))) continue;
                    ItemStack back = unlend(pack.get(i).copy());
                    pack.set(i, ItemStack.EMPTY);
                    Workshop.backIntoStores(level, v, back, f.displayNameCap());
                }
                continue;
            }
            if (!on || !near || !wearer(f) || lent >= TABARDS) continue;
            ItemStack got = takeOne(level, v, s -> tabard(s) && !plain(s), f.displayNameCap());
            if (got == null) return;                                   // none left in the stores
            f.setItemSlot(EquipmentSlot.CHEST, lend(got));
            lent++;
            if (f.getRandom().nextInt(3) == 0) {
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "The town's colours, for " + festivalWords(id, day) + "!",
                    "How do I look? Every inch a " + Villages.name(id) + " " + (f.persona().rolled() ? "native" : "one") + ".",
                    "Out comes the tabard: it must be " + festivalWords(id, day) + "."));
            }
        }
    }

    // ------------------------------------------------------------------ a citizen's banner

    /**
     * A player made a citizen (Citizens.ask): the town's banner, woven now out of the stores, into its hands; if the
     * stores cannot run to it, it is owed, and the shop's sign gives it free once they can. What the folk adds to
     * its welcome.
     */
    public static String citizen(ServerLevel level, UUID village, Player p) {
        Villages.Village v = Villages.get(village);
        Heraldry.Design d = Heraldry.design(village);
        if (v == null || d == null) return "";
        ItemStack banner = weave(level, v, d, "a new citizen's banner");
        if (banner != null) {
            given(level, v, p, banner, "on being made a citizen");
            return " And here is the banner of " + Villages.name(village) + ", yours to fly.";
        }
        Ledger.note(village, "culture.arms.owed/" + p.getUUID(), "1");
        return " The tailor owes you the town's banner: it's yours at the shop's sign once the stores run to it.";
    }

    /** Is this player owed the town's banner (made a citizen when the stores could not run to it)? */
    static boolean owed(UUID village, UUID player) {
        return "1".equals(Ledger.note(village, "culture.arms.owed/" + player));
    }

    /** The town's banner given a citizen: into its pack (or at its feet), the debt cleared, the chronicle told. */
    static void given(ServerLevel level, Villages.Village v, Player p, ItemStack banner, String how) {
        Ledger.forget(v.id(), "culture.arms.owed/" + p.getUUID());
        if (!p.getInventory().add(banner)) p.drop(banner, false);
        Villages.tell(v.id(), level.getDayTime() / 24000L, p.getName().getString() + " was given the town's banner " + how);
    }

    // ------------------------------------------------------------------ the town's look

    /**
     * Every second (Culture.tick), every two seconds' worth: the great days looked for, the banners carried on the
     * road, the tabards on or off; and every twenty, by day, a guard's shield given the arms and a tabard made.
     */
    static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        if (now < DUE.getOrDefault(id, 0L)) return;
        DUE.put(id, now + 40L);
        greatDays(level, v);
        Heraldry.Design d = Heraldry.design(id);
        if (d == null) return;
        carry(level, v, d);
        dress(level, v, d);
        long t = level.getDayTime() % 24000L;
        if (now < SLOW.getOrDefault(id, 0L) || t >= 12500L && t < 23500L) return;
        SLOW.put(id, now + 400L);
        shields(level, v);
        makeTabard(level, v, d, false);
    }

    // ------------------------------------------------------------------ what the player reads

    /**
     * The board's line for the arms in its header, null before they are drawn: "AN|", the field, "|", and each charge
     * as its pattern and its dye ("minecraft:stripe_downright=blue"), in the order the loom weaves them. The client
     * draws it from that alone (client/BoardArms).
     */
    @Nullable
    public static String boardLine(UUID village) {
        Heraldry.Design d = Heraldry.design(village);
        if (d == null) return null;
        List<String> charges = new ArrayList<>();
        for (Heraldry.Layer l : d.layers()) charges.add(l.charge().key.location() + "=" + l.colour().getName());
        return "AN|" + d.field().getName() + "|" + String.join(",", charges);
    }

    /** The folk's card: the arms on its shield, its tabard, the banner it carries; or null. */
    @Nullable
    static String cardLine(VillageFolkEntity f) {
        ItemStack off = f.getItemBySlot(EquipmentSlot.OFFHAND), chest = f.getItemBySlot(EquipmentSlot.CHEST);
        if (off.getItem() instanceof BannerItem && lent(off)) return "carries the town's banner on the road";
        if (tabard(chest) && !plain(chest)) return "in the town's tabard for the festival";
        if (f.stationTask() == AssistantEntity.StationTask.GUARD) {
            boolean arms = off.getItem() instanceof ShieldItem && !plain(off);
            for (ItemStack s : f.getInventoryItems()) arms |= s.getItem() instanceof ShieldItem && !plain(s);
            if (arms) return "carries the town's arms on its shield";
        }
        return null;
    }

    /** The Culture page's arms: where they fly beyond the hall, on the watch, the road, the tabards, the grants. */
    static CompoundTag report(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        CompoundTag out = new CompoundTag();
        List<String> lines = new ArrayList<>();
        Heraldry.Design d = Heraldry.design(id);
        if (d == null) {
            lines.add("no arms yet: the founders draw them once the town's land has been looked over");
            out.put("lines", Culture.strings(lines));
            return out;
        }
        int towers = 0, towersUp = 0, poles = 0, polesUp = 0;
        for (Heraldry.Place p : places(level, id)) {
            boolean up = Heraldry.up(level, p.spot().at());
            if (p.pole()) { poles++; if (up) polesUp++; } else { towers++; if (up) towersUp++; }
        }
        if (towers > 0) lines.add("flown on the wall's towers: " + towersUp + " of " + towers);
        if (poles > 0) lines.add("flown on poles by the board: " + polesUp + " of " + poles);
        int[] w = watchUnderArms(id);
        if (w[1] > 0) lines.add(w[0] + " of the watch's " + w[1] + " carry the arms on their shields");
        int road = (int) Culture.num(String.valueOf(Ledger.note(id, "culture.arms.road")), 0);
        lines.add(road == 0 ? "no caravan or envoy has gone out under the town's banner yet" : "carried on the road " + road
            + (road == 1 ? " time" : " times") + ", by the caravans and the envoys");
        long day = level.getDayTime() / 24000L;
        int held = tabardsHeld(level, v, d), wanted = tabardsWanted(id);
        lines.add(held + " of " + wanted + " festival tabards kept" + (festival(id, day) ? "; worn today, for " + festivalWords(id, day) + "!" : ""));
        for (String g : grants(id)) lines.add(g);
        String s = SHORT.get(id);
        if (s != null) lines.add("waiting on " + s);
        out.put("lines", Culture.strings(lines));
        out.putInt("charges", d.layers().size());
        return out;
    }

    /** /village arms: the arms in words, where they fly and what they wait on, the watch, the road, the tabards, the grants. */
    public static List<String> status(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        Heraldry.Design d = Heraldry.design(id);
        out.add("The arms of " + Villages.name(id) + ": " + (d == null ? "not yet drawn" : d.blazon()) + (Heraldry.motto(id) == null ? ""
            : "; motto “" + Heraldry.motto(id) + "”"));
        for (String p : Heraldry.placesForTests(level, id)) out.add("  " + p);
        for (String l : Culture.strings(report(level, v), "lines")) out.add("  " + l);
        return out;
    }

    // ------------------------------------------------------------------ the tests and the pictures

    /** Tests: the arms put on this guard's shield now, out of the stores. */
    public static boolean shieldForTests(ServerLevel level, Villages.Village v, VillageFolkEntity g) {
        return shield(level, v, g);
    }

    /** Tests: a new age's grant now. */
    public static boolean newAgeForTests(ServerLevel level, Villages.Village v) {
        return newAge(level, v, Villages.ageOf(v.id()));
    }

    /** Tests: the town's look at its arms now (great days, the road, the tabards, a shield and a tabard made). */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        DUE.remove(v.id());
        SLOW.remove(v.id());
        tick(level, v);
    }

    /** Tests: a festival tabard made now (out of the stores, unless {@code free}). */
    public static boolean tabardForTests(ServerLevel level, Villages.Village v, boolean free) {
        Heraldry.Design d = Heraldry.design(v.id());
        return d != null && makeTabard(level, v, d, free);
    }

    /** Tests: is this a day the festival tabards are worn (a festival's, or Founding Day)? */
    public static boolean festivalForTests(UUID village, long day) {
        return festival(village, day);
    }

    /** Tests: what the arms wait on, or null. */
    @Nullable
    public static String shortForTests(UUID village) {
        return SHORT.get(village);
    }

    /**
     * /village arms now: every piece the stores run to, now, the hands' walk to it skipped (the town's works done at
     * once, as the tests have them): the banners, a shield for each guard, the festival tabards.
     */
    public static String now(ServerLevel level, Villages.Village v) {
        boolean was = TownJobs.instantNow();
        TownJobs.instantForTests(true);
        try {
            String hung = Heraldry.putForTests(level, v);
            int shields = 0;
            for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity g && shield(level, v, g)) shields++;
            int tabards = 0;
            Heraldry.Design d = Heraldry.design(v.id());
            while (d != null && tabards < TABARDS && makeTabard(level, v, d, false)) tabards++;
            return "banners " + (hung.isEmpty() ? "nothing more to hang" : hung) + "; shields " + shields + "; tabards " + tabards
                + (SHORT.get(v.id()) == null ? "" : "; waiting on " + SHORT.get(v.id()));
        } finally {
            TownJobs.instantForTests(was);
        }
    }

    /** The pictures' lineup's tag: put away with the next stage. */
    static final String LINEUP = "arms_lineup";

    /**
     * The pictures' stage (/village arms stage): on the nearest dry, open ground, in a row facing south, a guard in
     * iron with the town's arms on its shield, a carrier with the town's banner in hand, two folk in the festival
     * tabard, and the banner on its pole at the end of the row: a showcase's, for nothing, as the watch's lineup
     * is. Returns "VIEW arms-lineup ex ey ez ax ay az", or why not.
     */
    public static List<String> stage(ServerLevel level, Villages.Village v, BlockPos at) {
        List<Entity> old = new ArrayList<>();
        for (Entity e : level.getAllEntities()) if (e.getTags().contains(LINEUP)) old.add(e);
        for (Entity e : old) e.discard();
        Heraldry.chooseForTests(level, v);
        Heraldry.Design d = Heraldry.design(v.id());
        if (d == null) return List.of("arms none: the town has no arms yet");
        BlockPos ground = WatchKit.stageGround(level, at);
        if (ground == null) return List.of("arms none: no dry, open ground near");
        ItemStack banner = Heraldry.item(level, v.id(), d);
        String[] names = { "The watch", "The road", "Festival", "Festival" };
        AssistantEntity.StationTask[] trades = { AssistantEntity.StationTask.GUARD, AssistantEntity.StationTask.HAUL,
            AssistantEntity.StationTask.FARM, AssistantEntity.StationTask.TAILOR };
        for (int i = 0; i < names.length; i++) {
            VillageFolkEntity g = McAssistantMod.VILLAGE_FOLK.get().create(level);
            if (g == null) continue;
            g.moveTo(ground.getX() + 0.5 + i * 1.5, ground.getY(), ground.getZ() + 0.5, 0.0F, 0.0F);
            g.setYHeadRot(0.0F);
            g.setYBodyRot(0.0F);
            g.makeShowcase(trades[i]);
            switch (i) {
                case 0 -> {
                    g.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                    g.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    g.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                    g.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
                    g.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                    ItemStack shield = new ItemStack(Items.SHIELD);
                    emblazon(shield, banner);
                    g.setItemSlot(EquipmentSlot.OFFHAND, shield);
                }
                case 1 -> g.setItemSlot(EquipmentSlot.OFFHAND, banner.copy());
                default -> {
                    ItemStack t = new ItemStack(McAssistantMod.TABARD.get());
                    emblazon(t, banner);
                    g.setItemSlot(EquipmentSlot.CHEST, t);
                }
            }
            g.rename(names[i]);
            g.addTag(LINEUP);
            level.addFreshEntity(g);
        }
        BlockPos pole = ground.east(7);
        if (poleGround(level, pole)) plant(level, pole, Direction.SOUTH, banner.copy());
        double mx = ground.getX() + 0.5 + 2.25 + 0.75, mz = ground.getZ() + 0.5;
        return List.of(String.format(Locale.ROOT, "VIEW arms-lineup %.2f %.2f %.2f %.2f %.2f %.2f", mx, ground.getY() + 1.7, mz + 3.6,
            mx, ground.getY() + 1.1, mz));
    }

    /**
     * Where to stand to see the board's header (/village arms board): out in front of its face, level with its top
     * row, looking at the middle of the name. "VIEW board ex ey ez ax ay az", or why not.
     */
    public static String boardView(UUID village) {
        BlockPos a = VillageBoards.boardOf(village);
        Direction f = VillageBoards.facingOf(village);
        if (a == null || f == null) return "board none: the town's board is not known yet (it says who it is every five seconds)";
        Direction right = VillageBoardBlock.right(f);
        double cx = a.getX() + 0.5 + right.getStepX() * (VillageBoardBlock.WIDE / 2.0 - 0.5);
        double cz = a.getZ() + 0.5 + right.getStepZ() * (VillageBoardBlock.WIDE / 2.0 - 0.5);
        double top = a.getY() + VillageBoardBlock.HIGH - 0.6;
        return String.format(Locale.ROOT, "VIEW board %.2f %.2f %.2f %.2f %.2f %.2f", cx + f.getStepX() * 6.5, top - 0.4, cz + f.getStepZ() * 6.5,
            cx, top, cz);
    }
}
