package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.Quill;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [fireworks] The fireworks maker: gunpowder, paper and dye out of the town's stores, rolled into stars and filled into
 * rockets by the game's own recipes, for its festivals, its weddings and its victories, and plain rockets for the
 * players' elytra (the displays themselves are FireworkShows').
 *
 * <ul>
 * <li><b>When a town takes one up.</b> A Stone Age town of eight or more that has kept a couple of its festivals (the
 *     May dance, the bonfire, the fair, the harvest, Founding Day, a new age seen in) and has gunpowder put by: the
 *     watch's creepers, the hunter's nights, the Nether runners' ghasts. The town says so in its chronicle, and wants
 *     a powder hut (blueprints/powderhut.txt) out at the edge of the town, away from the houses: stone walls, a stone
 *     roof, nothing in it that burns and nothing in it ever lit. When it stands, the town chooses one of its own for
 *     the trade (appoint): a cheerful, curious hand that loves the night sky, from a trade with hands to spare.</li>
 * <li><b>The hut.</b> The maker fits it out: its two chests named the Powder Hut's (the town's goods in them go back to
 *     the stores, and the stores never count them again), the cauldron kept full of water from the stores' bucket (the
 *     bucket back to the stores), and its sign over the door: "POWDER HUT, no naked flames". The powder chest holds the
 *     day's working gunpowder, a dozen, never more than sixteen: the rest goes back to the stores.</li>
 * <li><b>Stars and rockets.</b> A star is gunpowder and dyes, with a shape and an effect if the town has the makings:
 *     a fire charge for a large ball (blaze powder, coal and gunpowder), a gold nugget for a star (out of an ingot, if
 *     there are no nuggets), a feather for a burst, a creeper head if the stores ever hold one, glowstone dust for a
 *     twinkle, a diamond for a trail (only a rich town spends one), and a second dye for the colour it fades to. The
 *     dyes are the stores' own, or made there and then by the recipes from the flowers, the lapis, the ink sacs, the
 *     cocoa, the bone meal (a bone if need be), and mixed (red and yellow for orange, blue and white for light blue...).
 *     A rocket is a sheet of paper (three of sugar cane, if the stores have none), one to three gunpowder for its
 *     flight, and its stars: three rockets a filling, every one of them the game's own firework rocket, with its
 *     flight and its stars in it, into the stores.</li>
 * <li><b>Designs for each occasion</b> (design): the festivals and Founding Day in the town's colours (its arms: the
 *     field and the charges); a wedding in the couple's own colours, fading to white; a victory in the town's colours
 *     with large balls and crackle, high; Remembrance in white alone. The maker looks ahead (next): a wedding
 *     pledged, a victory's feast called, Remembrance Day, Founding Day or a festival within three days, and otherwise
 *     a few in the town's colours for the night nobody saw coming; and then the elytra rockets.</li>
 * <li><b>Elytra rockets for players.</b> Plain rockets of paper and one, two or three gunpowder, kept in stock while the
 *     town has gunpowder (sixteen, twelve and eight), sold at the shop by the eight, priced by how long they fly
 *     (Market.GOODS), and by the maker itself at the hut to a player who asks.</li>
 * <li><b>The watch's powder.</b> A creeper the town's folk kill before it blows drops its gunpowder; whoever killed it
 *     goes over, picks it up and takes it to the stores (fetch, LivingDropsEvent).</li>
 * </ul>
 * Everything in and out of the stores while it works is its making (Economy.openCraft): the Production page and the
 * trade's book count its rockets, and its own tally (the stars, the rockets, the displays) is kept in the town's books
 * (Ledger "fw.").
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class FireworksMaker {

    private FireworksMaker() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    public static final String STRUCTURE = "powderhut";
    public static final StationTask TRADE = StationTask.FIREWORKS;
    /** A town keeps a fireworks maker from this many folk. */
    public static final int FROM = 8;
    /** Festivals a town has kept before it wants fireworks of its own. */
    public static final int FESTIVALS = 2;
    /** The hut's working gunpowder, and the most it may ever hold: the rest is the stores'. */
    public static final int HUT_KEEP = 12, HUT_MOST = 16;
    /** Elytra rockets kept in the stores, by flight (one to three). */
    static final int[] ELYTRA = { 0, 16, 12, 8 };
    /** Rockets in the town's colours kept ready for a celebration nobody saw coming. */
    static final int STANDING = 6;
    /** A piece of work every so often (ticks), at a new hand's pace. */
    static final int EVERY = 300;
    /** How far ahead the maker makes for an occasion (days). */
    static final int AHEAD = 3;
    /** What the hut's chests are called: not the town's stores. */
    public static final String HUT_NAME = "Powder Hut";
    /** A town this rich in coin, or with this many diamonds put by, spends one on a trail. */
    static final int RICH_COINS = 250, RICH_DIAMONDS = 6;
    /** For the game tests: enough diamonds put by that the town counts as rich. */
    public static final int RICH_DIAMONDS_FOR_TESTS = RICH_DIAMONDS;
    /** A creeper's powder not picked up in this long is given up (the game takes it in five minutes). */
    static final long FETCH_FOR = 2400L;

    // ------------------------------------------------------------------ the state

    private static final Map<UUID, Integer> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> TICKED = new ConcurrentHashMap<>();
    /** Each town's hut, by its anchor (for inHut: no world, no ledger walk). */
    private static final Map<UUID, BlockPos> HUTS = new ConcurrentHashMap<>();
    private static final Map<Long, Fit> FITS = new ConcurrentHashMap<>();
    /** What one of a thing makes on the crafting table by itself, if it is a dye (a flower, an ink sac...). */
    private static final Map<Item, Optional<ItemStack>> YIELDS = new ConcurrentHashMap<>();
    /** The watch's powder runs: by the folk sent for it. */
    private static final Map<UUID, Run> RUNS = new ConcurrentHashMap<>();
    /** Tests: festivals kept, as though the town had kept them. */
    private static final Map<UUID, Integer> FESTIVALS_FOR_TESTS = new ConcurrentHashMap<>();
    /** What the town's books said the maker last did, for its card and its talk. */
    private static final Map<UUID, String> LAST_MADE = new ConcurrentHashMap<>();

    public static void resetForTests() {
        LAST.clear();
        TICKED.clear();
        HUTS.clear();
        FITS.clear();
        RUNS.clear();
        FESTIVALS_FOR_TESTS.clear();
        LAST_MADE.clear();
    }

    // ------------------------------------------------------------------ when a town wants one

    /** Has the town taken up fireworks (kept: a town that has once wanted a maker goes on wanting one)? */
    public static boolean opened(@Nullable UUID village) {
        if (village == null) return false;
        String n = Ledger.note(village, "fw.open");
        return n != null && !n.isEmpty();
    }

    /**
     * The festivals the town has kept: each of the year's festivals it has kept at least once and Founding Day, from the
     * festivals' own books (Festivals, TownCalendar), or the count kept here (FireworkShows.afterSpeech: the festivals,
     * Founding Days and new ages seen in), whichever is more.
     */
    public static int festivalsKept(UUID village) {
        Integer t = FESTIVALS_FOR_TESTS.get(village);
        if (t != null) return t;
        int books = 0;
        for (Festivals.Feast f : Festivals.Feast.values()) if (Festivals.keptOn(village, f) != Long.MIN_VALUE) books++;
        if (TownCalendar.foundingKept(village) >= 0) books++;
        return Math.max(books, (int) num(Ledger.note(village, "fw.festivals"), 0));
    }

    /** A festival, Founding Day or a new age kept (FireworkShows.afterSpeech): one more toward a maker. */
    static void festivalKept(UUID village) {
        Ledger.note(village, "fw.festivals", Long.toString(num(Ledger.note(village, "fw.festivals"), 0) + 1));
    }

    /** Tests: the town has kept so many festivals. */
    public static void festivalsForTests(UUID village, int n) {
        FESTIVALS_FOR_TESTS.put(village, n);
    }

    /**
     * The trade opens: a Stone Age town of eight that has kept its festivals, with gunpowder in the stores. Once, into
     * the chronicle; and from then on the town wants its powder hut and a maker for it.
     */
    static boolean checkOpen(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (opened(id)) return true;
        if (Villages.ageOf(id).ordinal() < Villages.Age.STONE.ordinal() || Villages.headcount(id) < FROM) return false;
        int kept = festivalsKept(id);
        if (kept < FESTIVALS) return false;
        int powder = Market.stock(level, id, s -> s.is(Items.GUNPOWDER));
        if (powder <= 0) return false;
        Ledger.note(id, "fw.open", Long.toString(day));
        Villages.tell(id, day, "with " + kept + " festivals kept and " + powder + " gunpowder in the stores, " + Villages.name(id)
            + " wants fireworks of its own: a powder hut out at the edge of the town, and a fireworks maker for it");
        LOG.info("[MCA-FIREWORKS] {}: the trade opens ({} festivals kept, {} gunpowder)", Villages.name(id), kept, powder);
        return true;
    }

    /** Is the trade ready for a hand (Villages.craftReady): opened, and its powder hut standing? */
    public static boolean ready(@Nullable UUID village) {
        return village != null && opened(village) && hut(village) != null;
    }

    /** Does the town want its powder hut (Villages.projectsWantedInOrder): the trade opened, eight folk, and none yet? */
    public static boolean hutWanted(@Nullable UUID village) {
        if (village == null || !opened(village) || Villages.headcount(village) < FROM) return false;   // (a town shrunk since: none)
        return !Villages.hasBuilt(village, STRUCTURE) && hut(village) == null;
    }

    /** Why the town wants one (Villages.whyBuild). */
    public static String why(UUID village) {
        if (!opened(village)) return "a powder hut comes when the town takes up fireworks: a few festivals kept, and gunpowder put by";
        return "a powder hut out at the edge of the town, away from the houses, for the fireworks maker: stone walls and a stone"
            + " roof, a crafting table, the powder chest and a cauldron of water, for the town's festivals, weddings and victories";
    }

    /** The town's powder hut (the first, if it has built more), or null. */
    @Nullable
    public static Ledger.Building hut(@Nullable UUID village) {
        if (village == null) return null;
        Ledger.Building b = Villages.builtStructure(village, STRUCTURE);
        if (b != null) HUTS.put(village, b.anchor());
        else HUTS.remove(village);
        return b;
    }

    /** Is this inside the town's powder hut (its walls and roof)? From what is remembered of where it stands: no world. */
    public static boolean inHut(@Nullable UUID village, BlockPos p) {
        BlockPos a = village == null ? null : HUTS.get(village);
        if (a == null) return false;
        return Math.abs(p.getX() - a.getX()) <= 4 && Math.abs(p.getZ() - a.getZ()) <= 4 && p.getY() >= a.getY() - 1 && p.getY() <= a.getY() + 4;
    }

    /** The town's fireworks maker, or null. */
    @Nullable
    public static VillageFolkEntity maker(@Nullable UUID village) {
        for (AssistantEntity a : Villages.folkOf(village)) {
            if (a instanceof VillageFolkEntity f && f.stationTask() == TRADE && f.isAlive() && !f.isBaby() && !f.isShowcase()) return f;
        }
        return null;
    }

    // ------------------------------------------------------------------ the hut, as drawn

    /** The hut's fittings: the bench, the door and which way it opens, the cauldron, the powder chest and the stock chest. */
    record Fit(BlockPos bench, @Nullable BlockPos door, Direction front, @Nullable BlockPos cauldron,
               @Nullable BlockPos powder, @Nullable BlockPos stock) {}

    static Fit fit(Ledger.Building b) {
        return FITS.computeIfAbsent(b.anchor().asLong(), k -> {
            BlockPos bench = b.anchor(), door = null, cauldron = null;
            List<BlockPos> chests = new ArrayList<>();
            for (BuildGoal.Placement p : BuildGoal.plan(STRUCTURE, b.anchor(), b.facing(), 13)) {
                switch (p.part()) {
                    case CRAFTING_TABLE -> bench = p.pos();
                    case DOOR -> door = p.pos();
                    case CAULDRON -> cauldron = p.pos();
                    case CHEST -> chests.add(p.pos());
                    default -> { }
                }
            }
            // The powder chest is the one in the back corner, as far from the door as the hut allows.
            BlockPos from = door != null ? door : b.anchor();
            chests.sort(Comparator.comparingDouble((BlockPos p) -> -p.distSqr(from)));
            return new Fit(bench, door, b.facing().getOpposite(), cauldron, chests.isEmpty() ? null : chests.get(0),
                chests.size() > 1 ? chests.get(1) : null);
        });
    }

    @Nullable
    static Container container(ServerLevel level, @Nullable BlockPos p) {
        return p != null && level.isLoaded(p) && level.getBlockEntity(p) instanceof Container c ? c : null;
    }

    /** How much of what matches a container holds. */
    static int count(@Nullable Container c, Predicate<ItemStack> what) {
        if (c == null) return 0;
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        return n;
    }

    /** So many of what matches out of a container (fewer if it has fewer): how many came out. */
    static int takeFrom(@Nullable Container c, Predicate<ItemStack> what, int n) {
        if (c == null || n <= 0) return 0;
        int got = 0;
        for (int i = 0; i < c.getContainerSize() && got < n; i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || !what.test(s)) continue;
            int k = Math.min(n - got, s.getCount());
            s.shrink(k);
            if (s.isEmpty()) c.setItem(i, ItemStack.EMPTY);
            got += k;
        }
        if (got > 0) c.setChanged();
        return got;
    }

    /** Into a container: what would not go in. */
    static ItemStack putInto(@Nullable Container c, ItemStack s) {
        if (c == null || s.isEmpty()) return s;
        ItemStack left = s.copy();
        for (int i = 0; i < c.getContainerSize() && !left.isEmpty(); i++) {
            ItemStack in = c.getItem(i);
            if (!in.isEmpty() && ItemStack.isSameItemSameComponents(in, left) && in.getCount() < in.getMaxStackSize()) {
                int k = Math.min(left.getCount(), in.getMaxStackSize() - in.getCount());
                in.grow(k);
                left.shrink(k);
            }
        }
        for (int i = 0; i < c.getContainerSize() && !left.isEmpty(); i++) {
            if (c.getItem(i).isEmpty()) {
                c.setItem(i, left.copy());
                left = ItemStack.EMPTY;
            }
        }
        c.setChanged();
        return left;
    }

    /** The gunpowder in the hut's powder chest. */
    public static int hutPowder(ServerLevel level, UUID village) {
        Ledger.Building b = hut(village);
        return b == null ? 0 : count(container(level, fit(b).powder()), s -> s.is(Items.GUNPOWDER));
    }

    /**
     * The hut fitted out by its maker: its chests the hut's own (named so, the town's goods in them back to the stores
     * first: the stores never count the powder chest), the cauldron kept full, and the sign over the door. Returns what
     * was seen to, in words, or null.
     */
    @Nullable
    static String fitOut(ServerLevel level, Villages.Village v, Ledger.Building b, @Nullable VillageFolkEntity maker) {
        Fit fit = fit(b);
        List<String> did = new ArrayList<>();
        for (BlockPos p : new BlockPos[]{ fit.powder(), fit.stock() }) {
            if (p == null || !level.isLoaded(p) || !(level.getBlockEntity(p) instanceof ChestBlockEntity chest)) continue;
            Component name = chest.getCustomName();
            if (name != null && HUT_NAME.equals(name.getString())) continue;
            // Named the hut's first (so the stores no longer count it), then the town's goods out of it to the stores.
            List<ItemStack> goods = new ArrayList<>();
            for (int i = 0; i < chest.getContainerSize(); i++) {
                ItemStack s = chest.getItem(i);
                if (s.isEmpty()) continue;
                goods.add(s.copy());
                chest.setItem(i, ItemStack.EMPTY);
            }
            chest.applyComponents(net.minecraft.core.component.DataComponentMap.builder()
                .set(DataComponents.CUSTOM_NAME, Component.literal(HUT_NAME)).build(), net.minecraft.core.component.DataComponentPatch.EMPTY);
            chest.setChanged();
            Villages.forgetStores(v.id());
            for (ItemStack s : goods) Crafts.store(level, v, s);
            did.add("its chests");
        }
        String water = keepTheCauldron(level, v, fit);
        if (water != null) did.add(water);
        if (hangTheSign(level, v, b, fit, maker)) did.add("its sign");
        return did.isEmpty() ? null : String.join(", ", did);
    }

    /**
     * The hut's cauldron: put back out of the stores if it has gone, and kept full of water: a water bucket from the
     * stores, or an empty one filled at the town's well, and the bucket back to the stores. Returns what was done.
     */
    @Nullable
    static String keepTheCauldron(ServerLevel level, Villages.Village v, Fit fit) {
        BlockPos at = fit.cauldron();
        if (at == null || !level.isLoaded(at)) return null;
        BlockState st = level.getBlockState(at);
        if (st.isAir()) {
            if (!TownWork.take(level, v, s -> s.is(Items.CAULDRON), 1)) return null;
            level.setBlock(at, Blocks.CAULDRON.defaultBlockState(), 3);
            st = level.getBlockState(at);
        }
        boolean empty = st.is(Blocks.CAULDRON);
        boolean low = st.is(Blocks.WATER_CAULDRON) && st.getValue(LayeredCauldronBlock.LEVEL) < 3;
        if (!empty && !low) return null;
        boolean filled = TownWork.take(level, v, s -> s.is(Items.WATER_BUCKET), 1);
        if (!filled && Villages.builtAt(v.id(), "well") != null) filled = TownWork.take(level, v, s -> s.is(Items.BUCKET), 1);   // filled at the well
        if (!filled) return null;
        level.setBlock(at, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3), 3);
        level.playSound(null, at, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.8F, 1.0F);
        Crafts.store(level, v, new ItemStack(Items.BUCKET));
        return "the cauldron filled";
    }

    /** Is the hut's cauldron there, and full? */
    public static boolean cauldronFull(ServerLevel level, UUID village) {
        Ledger.Building b = hut(village);
        BlockPos at = b == null ? null : fit(b).cauldron();
        if (at == null || !level.isLoaded(at)) return false;
        BlockState st = level.getBlockState(at);
        return st.is(Blocks.WATER_CAULDRON) && st.getValue(LayeredCauldronBlock.LEVEL) >= 3;
    }

    /** Where the hut's sign hangs: on the wall over its door, outside. */
    @Nullable
    public static BlockPos signAt(@Nullable UUID village) {
        Ledger.Building b = hut(village);
        if (b == null) return null;
        Fit fit = fit(b);
        return fit.door() == null ? null : fit.door().above(2).relative(fit.front());
    }

    /** The sign over the door, out of the stores (a sign, or two planks), its words kept to the truth. */
    static boolean hangTheSign(ServerLevel level, Villages.Village v, Ledger.Building b, Fit fit, @Nullable VillageFolkEntity maker) {
        if (fit.door() == null) return false;
        BlockPos wall = fit.door().above(2), at = wall.relative(fit.front());
        if (!level.isLoaded(at)) return false;
        BlockState st = level.getBlockState(at);
        boolean hung = false;
        if (!(st.getBlock() instanceof WallSignBlock)) {
            if (!st.isAir() || !level.getBlockState(wall).isSolid() || !Crafts.sign(level, v)) return false;
            level.setBlock(at, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, fit.front()), 3);
            hung = true;
        }
        if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
            String who = maker == null ? "" : maker.displayNameCap();
            TownLife.write(sign, new String[]{ "POWDER HUT", "Fireworks", "No naked flames", who.length() > 15 ? who.substring(0, 15) : who });
        }
        return hung;
    }

    // ------------------------------------------------------------------ the town's look, every few seconds

    /**
     * Every few seconds for each village (VillageFolkEntity, beside the other towns' looks; at most every ten seconds
     * here): the trade opened when it is due, the hut remembered, a maker chosen when the hut stands and there is none.
     */
    public static void tick(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long now = level.getGameTime();
        Long last = TICKED.get(id);
        if (last != null && now - last < 200L && now >= last) return;
        TICKED.put(id, now);
        long day = level.getDayTime() / 24000L;
        if (!checkOpen(level, v, day)) return;
        Ledger.Building b = hut(id);
        if (b == null) return;
        appoint(level, v, day);
        FireworkShows.countStock(level, v);
    }

    /** Tests: the town's look at its fireworks, now (the trade opened, the hut, the maker chosen). */
    public static void tickForTests(ServerLevel level, Villages.Village v) {
        TICKED.remove(v.id());
        tick(level, v);
    }

    /** Tests: the hut's fittings as drawn: {bench, door, cauldron, powder chest, stock chest} (any may be null). */
    public static BlockPos[] fittingsForTests(UUID village) {
        Ledger.Building b = hut(village);
        if (b == null) return new BlockPos[5];
        Fit f = fit(b);
        return new BlockPos[]{ f.bench(), f.door(), f.cauldron(), f.powder(), f.stock() };
    }

    /** Tests: what the master would write in the trade's book now. */
    public static List<String> bookNotesForTests(ServerLevel level, Villages.Village v) {
        return bookNotes(level, v);
    }

    // ------------------------------------------------------------------ the right hand for it

    /** How well suited this folk is to the powder hut: its nature (Skill), the night sky in its heart, its hand at it. */
    static int suits(VillageFolkEntity f) {
        int s = f.tradeLevel(TRADE) * 6;
        for (Social.Trait t : f.life().traits()) s += Skill.fit(t, TRADE).percent();
        if (f.persona().rolled()) {
            Persona.Hobby h = f.persona().hobby();
            if (h == Persona.Hobby.STARGAZING) s += 10;                 // it loves the night sky: who better
            else if (h == Persona.Hobby.MUSIC) s += 4;                   // a show is a show
            else if (h == Persona.Hobby.WHITTLING) s += 3;               // neat fingers
        }
        return s;
    }

    private static int hands(UUID village, StationTask t) {
        int n = 0;
        for (AssistantEntity a : Villages.folkOf(village)) if (a.stationTask() == t) n++;
        return n;
    }

    /**
     * The hut standing with nobody at it: the best suited hand the town can spare takes it up (suits) — from a trade
     * with hands to spare first, never the watch, the storekeeper, the banker, the cave team, the ferryman or a craft's
     * only hand. Into the chronicle, and into its own memory.
     * <p>The interview seam: when the town's interviews come (Interviews, not yet in the town), the maker's place is put
     * to them here, the candidates below as its list; till then the town chooses as the bank chooses its banker.
     */
    static void appoint(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        Ledger.Building b = hut(id);
        if (b == null || maker(id) != null || !Villages.wants(id, TRADE)) return;
        VillageFolkEntity best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int pass = 0; pass < 2 && best == null; pass++) {
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (!(a instanceof VillageFolkEntity f) || f.isBaby() || f.isShowcase() || f.isHired() || !f.isAlive()) continue;
                if (f.trip() != null || f.expedition() != null) continue;
                StationTask t = f.stationTask();
                if (t == StationTask.GUARD || t == StationTask.STORE || t == StationTask.BANK || t == StationTask.CAVE
                    || t == StationTask.FERRY || t == StationTask.SCOUT || t == StationTask.NETHER) continue;   // [nether]
                boolean spare = t == StationTask.NONE || Villages.overStaffed(id, t) || !t.isCraft() && hands(id, t) >= 3;
                if (pass == 0 ? !spare : t != StationTask.NONE && hands(id, t) < 2) continue;   // never a trade's only hand
                int score = suits(f);
                if (score > bestScore) { bestScore = score; best = f; }
            }
        }
        if (best == null) return;
        take(level, v, best, b, day);
    }

    /** It takes up the trade at the hut: its station at the bench, the hut its ground. */
    static void take(ServerLevel level, Villages.Village v, VillageFolkEntity f, Ledger.Building b, long day) {
        StationTask was = f.stationTask();
        Fit fit = fit(b);
        f.setStation(fit.bench(), TRADE);
        f.assignPlot(WorkZone.around(b.anchor(), 5, WorkZone.DEFAULT_DEPTH), "The Powder Hut");
        List<String> why = new ArrayList<>();
        for (Social.Trait t : f.life().traits()) {
            Skill.Fit k = Skill.fit(t, TRADE);
            if (k.percent() > 0 && !k.why().isEmpty()) why.add(t.label);
        }
        if (f.persona().rolled() && f.persona().hobby() == Persona.Hobby.STARGAZING) why.add("a stargazer");
        Villages.tell(v.id(), day, f.displayNameCap() + (why.isEmpty() ? "" : " (" + String.join(", ", why) + ")")
            + (was == StationTask.NONE ? " took up" : " gave up " + was.label + " for") + " the fireworks, at the powder hut");
        f.persona().remember(day, "I became the town's fireworks maker", 6);
        FolkTalk.speak(f, FolkTalk.pick(level.getRandom(), "Fireworks! I'll make this town's sky something to see.",
            "The powder hut's mine. Stars, rockets, and nobody lights a pipe within twenty yards.",
            "Gunpowder, paper and dye. Leave it with me."));
        LOG.info("[MCA-FIREWORKS] {}: {} took up the fireworks (was {})", Villages.name(v.id()), f.displayNameCap(), was);
    }

    // ------------------------------------------------------------------ the designs

    /** One star of a design: its colours, the colours it fades to, its shape, a trail, a twinkle. */
    public record Star(List<DyeColor> colours, List<DyeColor> fades, FireworkExplosion.Shape shape, boolean trail, boolean twinkle) {}

    /**
     * A rocket's design: what it is for (null: an elytra rocket), its key (one design to an occasion and a couple), its
     * flight, its stars, the palette a display looks for in the stores, and how many the occasion wants ready.
     */
    public record Design(@Nullable FireworkShows.Occasion occasion, String key, int flight, List<Star> stars,
                         List<DyeColor> palette, int wanted, String words) {}

    /** Colours that do not show against the night: left out of a palette when there are others. */
    static boolean dull(DyeColor c) {
        return c == DyeColor.BLACK || c == DyeColor.GRAY || c == DyeColor.BROWN;
    }

    /** The town's colours: its arms' field and charges (Heraldry: drawn now, if it has not drawn them yet), bright first, three at most. */
    public static List<DyeColor> townColours(UUID village) {
        Heraldry.Design d = Heraldry.design(village);
        if (d == null) {
            // No banner chosen yet: the colours as first drawn, kept (a drawing follows who leads, and the rockets made
            // for a night should not change colour under the maker's hands), till the town chooses its arms.
            String kept = Ledger.note(village, "fw.colours");
            if (kept != null && !kept.isEmpty()) {
                List<DyeColor> out = new ArrayList<>();
                for (String c : kept.split(",")) {
                    DyeColor dc = DyeColor.byName(c, null);
                    if (dc != null) out.add(dc);
                }
                if (!out.isEmpty()) return List.copyOf(out);
            }
            List<DyeColor> drawn = colours(Heraldry.draw(village));
            List<String> names = new ArrayList<>();
            for (DyeColor c : drawn) names.add(c.getName());
            Ledger.note(village, "fw.colours", String.join(",", names));
            return drawn;
        }
        return colours(d);
    }

    private static List<DyeColor> colours(Heraldry.Design d) {
        LinkedHashSet<DyeColor> all = new LinkedHashSet<>();
        all.add(d.field());
        for (Heraldry.Layer l : d.layers()) all.add(l.colour());
        return palette(all);
    }

    private static List<DyeColor> palette(Set<DyeColor> all) {
        List<DyeColor> out = new ArrayList<>();
        for (DyeColor c : all) if (!dull(c)) out.add(c);
        if (out.isEmpty()) out.addAll(List.of(DyeColor.WHITE, DyeColor.YELLOW));
        return out.size() > 3 ? List.copyOf(out.subList(0, 3)) : List.copyOf(out);
    }

    /** A folk's own colour: the main colour of its style (Style), else its favourite (Decor.colour). */
    static DyeColor colourOf(VillageFolkEntity f) {
        if (f.style() != null && f.style().rolled()) return DyeColor.byId(f.style().main());
        return Decor.colour(f);
    }

    /** The couple's colours, each its own, and white if they love the same one. */
    static List<DyeColor> coupleColours(ServerLevel level, Gatherings.Wedding w) {
        LinkedHashSet<DyeColor> all = new LinkedHashSet<>();
        for (UUID u : new UUID[]{ w.a(), w.b() }) {
            if (level.getEntity(u) instanceof VillageFolkEntity f) all.add(colourOf(f));
        }
        if (all.size() < 2) all.add(DyeColor.WHITE);
        if (all.size() < 2) all.add(DyeColor.PINK);
        return palette(all);
    }

    /** "green and yellow", "red, white and blue". */
    public static String colourWords(List<DyeColor> cs) {
        List<String> w = new ArrayList<>();
        for (DyeColor c : cs) w.add(c.getName().replace('_', ' '));
        if (w.isEmpty()) return "no colour";
        if (w.size() == 1) return w.get(0);
        return String.join(", ", w.subList(0, w.size() - 1)) + " and " + w.get(w.size() - 1);
    }

    /**
     * The design for an occasion in this town: the festivals and the age seen in in its colours, stars with a twinkle;
     * Founding Day the same, higher, with a trail and a second star in gold; a wedding in the couple's colours, star
     * shaped, fading to white; a victory in its colours, large balls and crackle, the highest; Remembrance in white,
     * plain. Null for a wedding with no couple to be wed.
     */
    @Nullable
    public static Design design(ServerLevel level, UUID village, FireworkShows.Occasion o) {
        List<DyeColor> town = townColours(village);
        return switch (o) {
            case FESTIVAL, CELEBRATION, HONOUR, SALUTE -> new Design(o, "town", 2,
                List.of(new Star(town, List.of(), FireworkExplosion.Shape.STAR, false, true)), town, o.rockets,
                "in the town's colours (" + colourWords(town) + ")");
            case FOUNDING -> new Design(o, "founding", 2,
                List.of(new Star(town, List.of(DyeColor.WHITE), FireworkExplosion.Shape.BURST, true, true),
                    new Star(List.of(DyeColor.YELLOW), List.of(), FireworkExplosion.Shape.SMALL_BALL, false, true)),
                withGold(town), o.rockets, "in the town's colours (" + colourWords(town) + ") and gold");
            case WEDDING -> {
                Gatherings.Wedding w = Gatherings.wedding(village);
                if (w == null) yield null;
                List<DyeColor> two = coupleColours(level, w);
                yield new Design(o, "wedding:" + w.a() + ":" + w.b(), 2,
                    List.of(new Star(two, List.of(DyeColor.WHITE), FireworkExplosion.Shape.STAR, false, true)),
                    two, o.rockets, "in " + w.names() + "'s colours (" + colourWords(two) + "), fading to white");
            }
            case VICTORY -> new Design(o, "victory", 3,
                List.of(new Star(town, List.of(), FireworkExplosion.Shape.LARGE_BALL, false, true),
                    new Star(town, List.of(), FireworkExplosion.Shape.BURST, true, true)),
                town, o.rockets, "in the town's colours (" + colourWords(town) + "), large balls and crackle");
            case REMEMBRANCE -> new Design(o, "remembrance", 2,
                List.of(new Star(List.of(DyeColor.WHITE), List.of(), FireworkExplosion.Shape.SMALL_BALL, false, false)),
                List.of(DyeColor.WHITE), o.rockets, "in white alone");
        };
    }

    private static List<DyeColor> withGold(List<DyeColor> town) {
        if (town.contains(DyeColor.YELLOW)) return town;
        List<DyeColor> out = new ArrayList<>(town);
        out.add(DyeColor.YELLOW);
        return List.copyOf(out);
    }

    /** A plain rocket for an elytra, of this flight: paper and gunpowder, nothing else. */
    public static Design elytraDesign(int flight) {
        return new Design(null, "elytra" + flight, flight, List.of(), List.of(), ELYTRA[Math.max(1, Math.min(3, flight))],
            "for a player's elytra, flight " + flight);
    }

    /** Is this a plain rocket (no stars in it), for an elytra? */
    public static boolean elytra(ItemStack s) {
        if (!s.is(Items.FIREWORK_ROCKET)) return false;
        Fireworks fw = s.get(DataComponents.FIREWORKS);
        return fw == null || fw.explosions().isEmpty();
    }

    /** Is this a plain rocket of this flight? */
    public static boolean elytra(ItemStack s, int flight) {
        if (!elytra(s)) return false;
        Fireworks fw = s.get(DataComponents.FIREWORKS);
        return (fw == null ? 1 : fw.flightDuration()) == flight;
    }

    /** One plain rocket of this flight, as the recipe makes it (for the counter and the tests). */
    public static ItemStack elytraRocket(int flight) {
        ItemStack s = new ItemStack(Items.FIREWORK_ROCKET);
        s.set(DataComponents.FIREWORKS, new Fireworks(flight, List.of()));
        return s;
    }

    /** A rocket for a display: one with stars in it. */
    public static boolean display(ItemStack s) {
        if (!s.is(Items.FIREWORK_ROCKET)) return false;
        Fireworks fw = s.get(DataComponents.FIREWORKS);
        return fw != null && !fw.explosions().isEmpty();
    }

    /** A display rocket all of whose stars are in the palette's colours. */
    public static boolean fits(ItemStack s, List<DyeColor> palette) {
        if (!display(s)) return false;
        Set<Integer> ok = new java.util.HashSet<>();
        for (DyeColor c : palette) ok.add(c.getFireworkColor());
        for (FireworkExplosion e : s.get(DataComponents.FIREWORKS).explosions()) {
            for (int c : e.colors()) if (!ok.contains(c)) return false;
        }
        return true;
    }

    /** How many rockets of a design the stores hold (by its palette; an elytra design by its flight). */
    static int stock(ServerLevel level, UUID village, Design d) {
        if (d.occasion() == null) return Market.stock(level, village, s -> elytra(s, d.flight()));
        return Market.stock(level, village, s -> fits(s, d.palette()));
    }

    // ------------------------------------------------------------------ what to make next

    /** A piece of work wanted: the design, how many are wanted ready, how many there are, and for what. */
    public record Order(Design design, int want, int have, String forWhat) {}

    /** What the maker makes next (the first of orders), or null when nothing is wanted. */
    @Nullable
    public static Order next(ServerLevel level, Villages.Village v) {
        List<Order> all = orders(level, v);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Everything wanted ready and not yet ready, most pressing first: for a wedding pledged; a victory's feast;
     * Remembrance Day, Founding Day or a festival within a few days; a few in the town's colours always; then the elytra
     * rockets, the emptiest shelf first, while the town has gunpowder. A town short of the dyes for its colours still
     * gets its elytra rockets (now tries them in turn).
     */
    public static List<Order> orders(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L;
        List<Order> out = new ArrayList<>();
        List<Object[]> wants = new ArrayList<>();                               // {occasion, forWhat}
        Gatherings.Wedding w = Gatherings.wedding(id);
        if (w != null) wants.add(new Object[]{ FireworkShows.Occasion.WEDDING, "the wedding of " + w.names() });
        long won = victoryDay(id);
        if (won >= day) wants.add(new Object[]{ FireworkShows.Occasion.VICTORY, "the victory over " + victoryFoe(id) });
        long rem = remembranceIn(id, day);
        if (rem >= 0 && rem <= AHEAD) wants.add(new Object[]{ FireworkShows.Occasion.REMEMBRANCE, "Remembrance Day" });
        long founding = FoundingDay.next(id, day);
        if (founding >= 0 && founding - day <= AHEAD) wants.add(new Object[]{ FireworkShows.Occasion.FOUNDING, "Founding Day" });
        Festivals.Feast feast = festivalWithin(id, day, AHEAD);
        if (feast != null) wants.add(new Object[]{ FireworkShows.Occasion.FESTIVAL, feast.words });
        for (Object[] o : wants) {
            Design d = design(level, id, (FireworkShows.Occasion) o[0]);
            if (d == null) continue;
            int have = stock(level, id, d);
            if (have < d.wanted()) out.add(new Order(d, d.wanted(), have, (String) o[1]));
        }
        Design standing = design(level, id, FireworkShows.Occasion.FESTIVAL);
        int have = stock(level, id, standing);
        if (have < STANDING) out.add(new Order(standing, STANDING, have, "a night nobody saw coming"));
        // The elytra rockets, while the town has gunpowder: the stores' and the hut's together.
        int powder = Market.stock(level, id, s -> s.is(Items.GUNPOWDER)) + hutPowder(level, id);
        if (powder < 4) return out;
        List<Order> wings = new ArrayList<>();
        for (int flight = 1; flight <= 3; flight++) {
            Design d = elytraDesign(flight);
            int n = stock(level, id, d);
            if (n < d.wanted()) wings.add(new Order(d, d.wanted(), n, "the elytra rockets at the shop"));
        }
        wings.sort(Comparator.comparingDouble(o -> o.have() / (double) o.want()));
        out.addAll(wings);
        return out;
    }

    /** The next of the year's gathered festivals within so many days, or null. */
    @Nullable
    static Festivals.Feast festivalWithin(UUID village, long day, int days) {
        Festivals.Feast best = null;
        long soonest = Long.MAX_VALUE;
        for (Festivals.Feast f : new Festivals.Feast[]{ Festivals.Feast.MAYPOLE, Festivals.Feast.BONFIRE, Festivals.Feast.FAIR,
                Festivals.Feast.HARVEST, Festivals.Feast.MIDWINTER }) {
            long d = Festivals.next(village, day, f);
            if (d >= day && d - day <= days && d < soonest) { soonest = d; best = f; }
        }
        return best;
    }

    /** Days to the town's next Remembrance Day, or -1 if it keeps none. */
    static long remembranceIn(UUID village, long day) {
        long best = -1;
        for (Traditions.Custom c : Traditions.customs(village)) {
            if (c.why() != Traditions.Why.WAR) continue;
            long years = Math.max(0, (day - c.day() + TownCalendar.YEAR_DAYS - 1) / TownCalendar.YEAR_DAYS);
            long next = c.day() + years * TownCalendar.YEAR_DAYS;
            if (next < day) next += TownCalendar.YEAR_DAYS;
            if (next == c.day()) next += TownCalendar.YEAR_DAYS;
            long in = next - day;
            if (best < 0 || in < best) best = in;
        }
        return best;
    }

    // ------------------------------------------------------------------ victories

    /**
     * [war-peace] The town won its war (WarAndPeace.makePeace): its feast for the peace, on this day, is a victory, and
     * the maker makes for it.
     */
    public static void victory(UUID winner, UUID loser, long feastDay) {
        if (feastDay < 0) return;
        Ledger.note(winner, "fw.victory", feastDay + "|" + Villages.name(loser));
    }

    /** The day of the town's victory feast, or -1. */
    public static long victoryDay(UUID village) {
        String n = Ledger.note(village, "fw.victory");
        if (n == null || n.isEmpty()) return -1;
        return num(n.split("\\|", 2)[0], -1);
    }

    /** Whom the town beat. */
    static String victoryFoe(UUID village) {
        String n = Ledger.note(village, "fw.victory");
        return n == null || !n.contains("|") ? "our enemies" : n.split("\\|", 2)[1];
    }

    // ------------------------------------------------------------------ the maker's day

    /** A piece of the maker's work at the hut, when one is due (AssistantEntity's station work: fireworksWork). */
    public static boolean work(VillageFolkEntity f, ServerLevel level) {
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null) return false;
        Ledger.Building b = hut(id);
        if (b == null) {
            f.brain("no powder hut yet");
            return false;
        }
        if (FireworkShows.crewing(f)) return false;
        Fit fit = fit(b);
        if (!level.isLoaded(fit.bench())) return false;
        int last = LAST.getOrDefault(f.getUUID(), -100000);
        int every = f.pacedTicks(EVERY, 100);
        if (f.tickCount - last < every && f.tickCount >= last) return false;
        // At the bench, or on the way to it.
        BlockPos stand = fit.bench().relative(fit.front());
        if (f.blockPosition().distSqr(stand) > 2.5 * 2.5) {
            if (f.getNavigation().isDone()) f.walkTo(stand, 0.9D);
            f.brain("to the powder hut's bench");
            return true;
        }
        LAST.put(f.getUUID(), f.tickCount);
        f.getLookControl().setLookAt(fit.bench().getX() + 0.5, fit.bench().getY() + 1.0, fit.bench().getZ() + 0.5);
        return now(f, level, v, b) != null;
    }

    /**
     * The piece of work, now: the hut seen to (fitOut), its working powder kept (keepPowder), and the next order made
     * (next, make). What was made, in words, or null when there was nothing it could make.
     */
    @Nullable
    public static String now(VillageFolkEntity f, ServerLevel level, Villages.Village v, Ledger.Building b) {
        UUID id = v.id();
        fitOut(level, v, b, f);
        keepPowder(level, v, b);
        List<Order> wants = orders(level, v);
        if (wants.isEmpty()) {
            f.brain("nothing wanted at the powder hut");
            return null;
        }
        // The most pressing it has the makings for: a town short of a dye for its colours still gets on with the rest.
        Made m = null;
        Order o = null;
        String shortOf = null;
        for (Order w : wants.subList(0, Math.min(3, wants.size()))) {
            Made tried;
            Economy.openCraft(id, TRADE);
            try {
                tried = make(level, v, b, w.design());
            } finally {
                Economy.closeCraft();
            }
            if (tried.rockets() > 0) {
                m = tried;
                o = w;
                break;
            }
            if (shortOf == null) shortOf = tried.words() + " for " + w.forWhat();
        }
        if (m == null) {
            f.brain("short of " + shortOf);
            LAST_MADE.put(id, "short of " + shortOf);
            return null;
        }
        f.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, f.blockPosition(), SoundEvents.VILLAGER_WORK_FLETCHER, SoundSource.NEUTRAL, 0.7F, 1.1F);
        Fit fit = fit(b);
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.GUNPOWDER)),
            fit.bench().getX() + 0.5, fit.bench().getY() + 1.1, fit.bench().getZ() + 0.5, 6, 0.2, 0.05, 0.2, 0.02);
        f.note(AssistantEntity.Deed.THINGS_MADE, m.rockets());
        tally(id, 0, m.stars());
        tally(id, o.design().occasion() == null ? 2 : 1, m.rockets());
        String said = m.rockets() + " rockets " + m.words() + " for " + o.forWhat();
        LAST_MADE.put(id, said);
        f.brain("made " + said);
        if (f.getRandom().nextInt(3) == 0) {
            FolkTalk.speak(f, o.design().occasion() == null
                ? FolkTalk.pick(f.getRandom(), "Three more for the shop. They'll fly " + flightWords(o.design().flight()) + ".",
                    "Rockets for the travellers' wings. Paper, powder, and a twist at the top.")
                : FolkTalk.pick(f.getRandom(), "Three more for " + o.forWhat() + ".", "There: " + m.words() + ". Wait till you see them go up.",
                    "Easy with the powder... there. " + capital(o.forWhat()) + " will have a sky to remember."));
        }
        return said;
    }

    static String flightWords(int flight) {
        return flight <= 1 ? "a short hop" : flight == 2 ? "a fair way" : "a long way";
    }

    // ------------------------------------------------------------------ the powder

    /**
     * The hut's working gunpowder kept to a dozen out of the stores, and never more than sixteen in the hut: the rest is
     * carried back to the stores. What is not gunpowder in the powder chest goes to the stock chest, or the stores.
     */
    public static void keepPowder(ServerLevel level, Villages.Village v, Ledger.Building b) {
        Fit fit = fit(b);
        Container powder = container(level, fit.powder());
        if (powder == null) return;
        for (int i = 0; i < powder.getContainerSize(); i++) {
            ItemStack s = powder.getItem(i);
            if (s.isEmpty() || s.is(Items.GUNPOWDER)) continue;
            ItemStack left = putInto(container(level, fit.stock()), s.copy());
            if (!left.isEmpty()) Crafts.store(level, v, left);
            powder.setItem(i, ItemStack.EMPTY);
            powder.setChanged();
        }
        int have = count(powder, s -> s.is(Items.GUNPOWDER));
        if (have > HUT_MOST) {
            int back = takeFrom(powder, s -> s.is(Items.GUNPOWDER), have - HUT_KEEP);
            Crafts.store(level, v, new ItemStack(Items.GUNPOWDER, back));
            return;
        }
        if (have >= HUT_KEEP) return;
        int stores = Market.stock(level, v.id(), s -> s.is(Items.GUNPOWDER));
        int fetch = Math.min(HUT_KEEP - have, stores);
        if (fetch <= 0) return;
        Economy.openCraft(v.id(), TRADE);
        try {
            if (!TownWork.take(level, v, s -> s.is(Items.GUNPOWDER), fetch)) return;
        } finally {
            Economy.closeCraft();
        }
        ItemStack left = putInto(powder, new ItemStack(Items.GUNPOWDER, fetch));
        if (!left.isEmpty()) Crafts.store(level, v, left);
    }

    // ------------------------------------------------------------------ the making

    /** What a piece of work made: rockets (nought: none, and why in the words), stars rolled, gunpowder used. */
    public record Made(int rockets, int stars, int powder, String words, ItemStack rocket) {}

    /** The game's own crafting recipe for these, laid on the table: what it makes, or nothing. */
    public static ItemStack craft(ServerLevel level, List<ItemStack> grid) {
        List<ItemStack> nine = new ArrayList<>(9);
        for (ItemStack s : grid) nine.add(s.copyWithCount(1));
        while (nine.size() < 9) nine.add(ItemStack.EMPTY);
        if (nine.size() > 9) return ItemStack.EMPTY;
        CraftingInput in = CraftingInput.of(3, 3, nine);
        Optional<RecipeHolder<CraftingRecipe>> r = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, in, level);
        return r.isEmpty() ? ItemStack.EMPTY : r.get().value().assemble(in, level.registryAccess());
    }

    /**
     * One filling of rockets to a design, by the recipes: the paper, the gunpowder for its flight out of the hut, and
     * its stars (rolled now, or one waiting in the stock chest); three rockets into the stores. With anything short,
     * what was taken goes back where it came from and nothing is made.
     */
    public static Made make(ServerLevel level, Villages.Village v, Ledger.Building b, Design d) {
        Fit fit = fit(b);
        Container powder = container(level, fit.powder()), stock = container(level, fit.stock());
        int need = d.flight() + d.stars().size();
        if (count(powder, s -> s.is(Items.GUNPOWDER)) < need) return new Made(0, 0, 0, "gunpowder in the hut", ItemStack.EMPTY);
        ItemStack paper = paper(level, v);
        if (paper.isEmpty()) return new Made(0, 0, 0, "paper (or sugar cane)", ItemStack.EMPTY);
        List<ItemStack> stars = new ArrayList<>();
        int rolled = 0;
        List<String> how = new ArrayList<>();
        for (Star plan : d.stars()) {
            ItemStack waiting = waitingStar(stock, plan);
            if (!waiting.isEmpty()) {
                stars.add(waiting);
                continue;
            }
            ItemStack star = star(level, v, powder, plan, how);
            if (star.isEmpty()) {
                // Short of the makings: the stars rolled so far wait in the stock chest for the next filling.
                for (ItemStack s : stars) {
                    ItemStack left = putInto(stock, s);
                    if (!left.isEmpty()) Crafts.store(level, v, left);
                }
                Crafts.store(level, v, paper);
                return new Made(0, rolled, rolled, "dye for " + colourWords(plan.colours()), ItemStack.EMPTY);
            }
            stars.add(star);
            rolled++;
        }
        int flight = takeFrom(powder, s -> s.is(Items.GUNPOWDER), d.flight());
        List<ItemStack> grid = new ArrayList<>();
        grid.add(paper);
        for (int i = 0; i < flight; i++) grid.add(new ItemStack(Items.GUNPOWDER));
        grid.addAll(stars);
        ItemStack rockets = flight == d.flight() ? craft(level, grid) : ItemStack.EMPTY;
        if (rockets.isEmpty() || !rockets.is(Items.FIREWORK_ROCKET)) {
            putInto(powder, new ItemStack(Items.GUNPOWDER, flight));
            for (ItemStack s : stars) {
                ItemStack left = putInto(stock, s);
                if (!left.isEmpty()) Crafts.store(level, v, left);
            }
            Crafts.store(level, v, paper);
            return new Made(0, rolled, rolled, "the rocket would not take", ItemStack.EMPTY);
        }
        ItemStack one = rockets.copyWithCount(1);
        int n = rockets.getCount();
        Crafts.store(level, v, rockets);
        String words = d.occasion() == null ? "of flight " + d.flight()
            : "of " + colourWords(coloursOf(one)) + (how.isEmpty() ? "" : ", " + String.join(", ", new LinkedHashSet<>(how)));
        return new Made(n, rolled, rolled + flight, words, one);
    }

    /** A star waiting in the hut's stock chest that will do for this one of a design (its colours), taken out; or nothing. */
    static ItemStack waitingStar(@Nullable Container stock, Star plan) {
        if (stock == null) return ItemStack.EMPTY;
        Set<Integer> want = new java.util.HashSet<>();
        for (DyeColor c : plan.colours()) want.add(c.getFireworkColor());
        for (int i = 0; i < stock.getContainerSize(); i++) {
            ItemStack s = stock.getItem(i);
            if (!s.is(Items.FIREWORK_STAR)) continue;
            FireworkExplosion e = s.get(DataComponents.FIREWORK_EXPLOSION);
            if (e == null || e.colors().isEmpty() || !want.containsAll(e.colors())) continue;
            ItemStack one = s.split(1);
            stock.setChanged();
            return one;
        }
        return ItemStack.EMPTY;
    }

    /** The colours of a rocket's stars, in order. */
    public static List<DyeColor> coloursOf(ItemStack rocket) {
        LinkedHashSet<DyeColor> out = new LinkedHashSet<>();
        Fireworks fw = rocket.get(DataComponents.FIREWORKS);
        if (fw == null) return List.of();
        for (FireworkExplosion e : fw.explosions()) for (int c : e.colors()) {
            DyeColor d = DyeColor.byFireworkColor(c);
            if (d != null) out.add(d);
        }
        return List.copyOf(out);
    }

    /**
     * A star rolled to a plan, by the recipe: a gunpowder from the hut, a dye of each colour the town can find or make,
     * the shape's makings if it has them (else the next best, else a small ball), a diamond for a trail if the town is
     * rich, glowstone for a twinkle if it has any; then the fade, a second time on the table. Empty (and everything back)
     * when it has not a single dye of the plan's colours.
     */
    static ItemStack star(ServerLevel level, Villages.Village v, @Nullable Container powder, Star plan, List<String> how) {
        List<ItemStack> dyes = new ArrayList<>();
        for (DyeColor c : plan.colours()) {
            ItemStack dye = dye(level, v, c, 0);
            if (!dye.isEmpty()) dyes.add(dye);
            if (dyes.size() >= 5) break;
        }
        if (dyes.isEmpty()) return ItemStack.EMPTY;
        if (takeFrom(powder, s -> s.is(Items.GUNPOWDER), 1) < 1) {
            for (ItemStack d : dyes) Crafts.store(level, v, d);
            return ItemStack.EMPTY;
        }
        List<ItemStack> grid = new ArrayList<>();
        grid.add(new ItemStack(Items.GUNPOWDER));
        grid.addAll(dyes);
        List<ItemStack> extras = new ArrayList<>();
        ItemStack shape = shape(level, v, powder, plan.shape(), how);
        if (!shape.isEmpty()) extras.add(shape);
        if (plan.trail() && rich(level, v)) {
            ItemStack diamond = Crafts.takeOne(level, v, s -> s.is(Items.DIAMOND));
            if (!diamond.isEmpty()) { extras.add(diamond); how.add("with a trail"); }
        }
        if (plan.twinkle()) {
            ItemStack glow = Crafts.takeOne(level, v, s -> s.is(Items.GLOWSTONE_DUST));
            if (!glow.isEmpty()) { extras.add(glow); how.add(plan.shape() == FireworkExplosion.Shape.LARGE_BALL ? "crackling" : "twinkling"); }
        }
        grid.addAll(extras);
        ItemStack star = craft(level, grid);
        if (star.isEmpty() || !star.is(Items.FIREWORK_STAR)) {
            putInto(powder, new ItemStack(Items.GUNPOWDER));
            for (ItemStack s : dyes) Crafts.store(level, v, s);
            for (ItemStack s : extras) Crafts.store(level, v, s);
            return ItemStack.EMPTY;
        }
        star = star.copyWithCount(1);
        // The fade: the star and the dyes it fades to, on the table again.
        if (!plan.fades().isEmpty()) {
            List<ItemStack> fade = new ArrayList<>();
            for (DyeColor c : plan.fades()) {
                ItemStack d = dye(level, v, c, 0);
                if (!d.isEmpty()) fade.add(d);
            }
            if (!fade.isEmpty()) {
                List<ItemStack> g = new ArrayList<>();
                g.add(star);
                g.addAll(fade);
                ItemStack faded = craft(level, g);
                if (faded.is(Items.FIREWORK_STAR)) {
                    star = faded.copyWithCount(1);
                    how.add("fading to " + colourWords(plan.fades()));
                } else {
                    for (ItemStack s : fade) Crafts.store(level, v, s);
                }
            }
        }
        return star;
    }

    /** Rich enough to spend a diamond on a trail: the treasury well off, or diamonds put by. */
    static boolean rich(ServerLevel level, Villages.Village v) {
        return Ledger.coins(v.id()) >= RICH_COINS || Market.stock(level, v.id(), s -> s.is(Items.DIAMOND)) >= RICH_DIAMONDS;
    }

    /**
     * The makings of a shape, out of the stores: a fire charge for a large ball (made of blaze powder, coal and a
     * gunpowder from the hut, three a time, the two over to the stores), a gold nugget for a star (an ingot cut into
     * nine if there are none), a feather for a burst, a creeper head (only if the stores hold one). Short of what the
     * plan wants, the next best; with nothing, a small ball (no makings at all).
     */
    static ItemStack shape(ServerLevel level, Villages.Village v, @Nullable Container powder, FireworkExplosion.Shape want, List<String> how) {
        List<FireworkExplosion.Shape> order = switch (want) {
            case LARGE_BALL -> List.of(FireworkExplosion.Shape.LARGE_BALL, FireworkExplosion.Shape.BURST, FireworkExplosion.Shape.STAR);
            case STAR -> List.of(FireworkExplosion.Shape.STAR, FireworkExplosion.Shape.BURST);
            case BURST -> List.of(FireworkExplosion.Shape.BURST, FireworkExplosion.Shape.STAR);
            case CREEPER -> List.of(FireworkExplosion.Shape.CREEPER, FireworkExplosion.Shape.STAR, FireworkExplosion.Shape.BURST);
            default -> List.of();
        };
        for (FireworkExplosion.Shape s : order) {
            ItemStack got = switch (s) {
                case LARGE_BALL -> fireCharge(level, v, powder);
                case STAR -> goldNugget(level, v);
                case BURST -> Crafts.takeOne(level, v, x -> x.is(Items.FEATHER));
                case CREEPER -> Crafts.takeOne(level, v, x -> x.is(Items.CREEPER_HEAD));
                default -> ItemStack.EMPTY;
            };
            if (!got.isEmpty()) {
                how.add(switch (s) {
                    case LARGE_BALL -> "large balls";
                    case STAR -> "star-shaped";
                    case BURST -> "bursting";
                    case CREEPER -> "creeper-faced";
                    default -> "round";
                });
                return got;
            }
        }
        return ItemStack.EMPTY;
    }

    /** A fire charge: the stores', or blaze powder, coal (or charcoal) and a gunpowder from the hut, by the recipe. */
    static ItemStack fireCharge(ServerLevel level, Villages.Village v, @Nullable Container powder) {
        ItemStack have = Crafts.takeOne(level, v, s -> s.is(Items.FIRE_CHARGE));
        if (!have.isEmpty()) return have;
        if (count(powder, s -> s.is(Items.GUNPOWDER)) < 2) return ItemStack.EMPTY;      // the star's own comes first
        if (Market.stock(level, v.id(), s -> s.is(Items.BLAZE_POWDER)) < 1
            || Market.stock(level, v.id(), s -> s.is(Items.COAL) || s.is(Items.CHARCOAL)) < 1) return ItemStack.EMPTY;
        ItemStack blaze = Crafts.takeOne(level, v, s -> s.is(Items.BLAZE_POWDER));
        ItemStack coal = Crafts.takeOne(level, v, s -> s.is(Items.COAL) || s.is(Items.CHARCOAL));
        takeFrom(powder, s -> s.is(Items.GUNPOWDER), 1);
        ItemStack out = craft(level, List.of(blaze, coal, new ItemStack(Items.GUNPOWDER)));
        if (!out.is(Items.FIRE_CHARGE)) {
            Crafts.store(level, v, blaze);
            Crafts.store(level, v, coal);
            putInto(powder, new ItemStack(Items.GUNPOWDER));
            return ItemStack.EMPTY;
        }
        if (out.getCount() > 1) Crafts.store(level, v, out.copyWithCount(out.getCount() - 1));
        return out.copyWithCount(1);
    }

    /** A gold nugget: the stores', or an ingot cut into nine by the recipe (eight back to the stores). */
    static ItemStack goldNugget(ServerLevel level, Villages.Village v) {
        ItemStack have = Crafts.takeOne(level, v, s -> s.is(Items.GOLD_NUGGET));
        if (!have.isEmpty()) return have;
        ItemStack ingot = Crafts.takeOne(level, v, s -> s.is(Items.GOLD_INGOT));
        if (ingot.isEmpty()) return ItemStack.EMPTY;
        ItemStack out = craft(level, List.of(ingot));
        if (!out.is(Items.GOLD_NUGGET)) {
            Crafts.store(level, v, ingot);
            return ItemStack.EMPTY;
        }
        if (out.getCount() > 1) Crafts.store(level, v, out.copyWithCount(out.getCount() - 1));
        return out.copyWithCount(1);
    }

    /** A sheet of paper: the stores', or three of their sugar cane made into three by the recipe (two back). */
    static ItemStack paper(ServerLevel level, Villages.Village v) {
        ItemStack have = Crafts.takeOne(level, v, s -> s.is(Items.PAPER));
        if (!have.isEmpty()) return have;
        if (Market.stock(level, v.id(), s -> s.is(Items.SUGAR_CANE)) < 3 || !TownWork.take(level, v, s -> s.is(Items.SUGAR_CANE), 3)) {
            return ItemStack.EMPTY;
        }
        ItemStack cane = new ItemStack(Items.SUGAR_CANE);
        ItemStack out = craft(level, List.of(cane, cane, cane));
        if (!out.is(Items.PAPER)) {
            Crafts.store(level, v, new ItemStack(Items.SUGAR_CANE, 3));
            return ItemStack.EMPTY;
        }
        if (out.getCount() > 1) Crafts.store(level, v, out.copyWithCount(out.getCount() - 1));
        return out.copyWithCount(1);
    }

    // ------------------------------------------------------------------ the dyes

    /** Two dyes that make a third on the table: orange of red and yellow, and the rest. */
    private static final Map<DyeColor, DyeColor[][]> MIXES = new HashMap<>();

    static {
        MIXES.put(DyeColor.ORANGE, new DyeColor[][]{ { DyeColor.RED, DyeColor.YELLOW } });
        MIXES.put(DyeColor.PINK, new DyeColor[][]{ { DyeColor.RED, DyeColor.WHITE } });
        MIXES.put(DyeColor.LIME, new DyeColor[][]{ { DyeColor.GREEN, DyeColor.WHITE } });
        MIXES.put(DyeColor.LIGHT_BLUE, new DyeColor[][]{ { DyeColor.BLUE, DyeColor.WHITE } });
        MIXES.put(DyeColor.PURPLE, new DyeColor[][]{ { DyeColor.RED, DyeColor.BLUE } });
        MIXES.put(DyeColor.CYAN, new DyeColor[][]{ { DyeColor.BLUE, DyeColor.GREEN } });
        MIXES.put(DyeColor.MAGENTA, new DyeColor[][]{ { DyeColor.PURPLE, DyeColor.PINK } });
        MIXES.put(DyeColor.GRAY, new DyeColor[][]{ { DyeColor.BLACK, DyeColor.WHITE } });
        MIXES.put(DyeColor.LIGHT_GRAY, new DyeColor[][]{ { DyeColor.GRAY, DyeColor.WHITE } });
    }

    /** What one of this thing makes on the table by itself, if a dye: looked up once (a flower, lapis, an ink sac...). */
    static ItemStack yieldOf(ServerLevel level, Item it) {
        return YIELDS.computeIfAbsent(it, k -> {
            ItemStack out = craft(level, List.of(new ItemStack(k)));
            return out.getItem() instanceof DyeItem ? Optional.of(out.copy()) : Optional.empty();
        }).map(ItemStack::copy).orElse(ItemStack.EMPTY);
    }

    /** Could this be a dye's making (a flower, lapis, an ink sac, cocoa, bone meal, a beetroot...)? Before the recipe is asked. */
    static boolean dyeish(ItemStack s) {
        return s.is(ItemTags.FLOWERS) || s.is(Items.LAPIS_LAZULI) || s.is(Items.INK_SAC) || s.is(Items.COCOA_BEANS)
            || s.is(Items.BONE_MEAL) || s.is(Items.BEETROOT) || s.is(Items.GLOW_INK_SAC);
    }

    /**
     * A dye of this colour: the stores' own; else made now from what the stores hold (a flower, lapis, an ink sac,
     * cocoa, bone meal; a bone ground to meal for white), by the recipe, whatever it makes over back to the stores; else
     * mixed of two it can get. Empty if it can get none.
     */
    static ItemStack dye(ServerLevel level, Villages.Village v, DyeColor c, int depth) {
        Item want = DyeItem.byColor(c);
        ItemStack got = Crafts.takeOne(level, v, s -> s.is(want));
        if (!got.isEmpty()) return got;
        // What the stores hold that makes this dye by itself, the cheapest first.
        Map<Item, Integer> kinds = new HashMap<>();
        for (BlockPos p : Villages.storeChests(level, v.id())) {
            if (!(level.getBlockEntity(p) instanceof Container box)) continue;
            for (int i = 0; i < box.getContainerSize(); i++) {
                ItemStack s = box.getItem(i);
                if (!s.isEmpty() && dyeish(s)) kinds.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        List<Item> sources = new ArrayList<>(kinds.keySet());
        sources.sort(Comparator.comparingDouble(Prices::each));
        for (Item src : sources) {
            ItemStack y = yieldOf(level, src);
            if (y.isEmpty() || !y.is(want)) continue;
            ItemStack one = Crafts.takeOne(level, v, s -> s.is(src));
            if (one.isEmpty()) continue;
            ItemStack out = craft(level, List.of(one));
            if (!out.is(want)) {
                Crafts.store(level, v, one);
                continue;
            }
            if (out.getCount() > 1) Crafts.store(level, v, out.copyWithCount(out.getCount() - 1));
            return out.copyWithCount(1);
        }
        // White from a bone: ground to meal on the table, the meal to dye.
        if (c == DyeColor.WHITE) {
            ItemStack bone = Crafts.takeOne(level, v, s -> s.is(Items.BONE));
            if (!bone.isEmpty()) {
                ItemStack meal = craft(level, List.of(bone));
                if (meal.is(Items.BONE_MEAL)) {
                    ItemStack white = craft(level, List.of(meal.copyWithCount(1)));
                    if (white.is(want)) {
                        if (meal.getCount() > 1) Crafts.store(level, v, meal.copyWithCount(meal.getCount() - 1));
                        if (white.getCount() > 1) Crafts.store(level, v, white.copyWithCount(white.getCount() - 1));
                        return white.copyWithCount(1);
                    }
                    Crafts.store(level, v, meal);
                } else {
                    Crafts.store(level, v, bone);
                }
            }
        }
        // Mixed of two.
        if (depth < 2) {
            for (DyeColor[] mix : MIXES.getOrDefault(c, new DyeColor[0][])) {
                ItemStack a = dye(level, v, mix[0], depth + 1);
                if (a.isEmpty()) continue;
                ItemStack b = dye(level, v, mix[1], depth + 1);
                if (b.isEmpty()) {
                    Crafts.store(level, v, a);
                    continue;
                }
                ItemStack out = craft(level, List.of(a, b));
                if (!out.is(want)) {
                    Crafts.store(level, v, a);
                    Crafts.store(level, v, b);
                    continue;
                }
                if (out.getCount() > 1) Crafts.store(level, v, out.copyWithCount(out.getCount() - 1));
                return out.copyWithCount(1);
            }
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------ the watch's powder

    /**
     * A run for a creeper's gunpowder: the drops, since when, and the gunpowder the folk carried when it began (what it
     * carries more at the end is what it picked up, by its own hand here or its pack's sweep as it walked by).
     */
    static final class Run {
        final UUID village;
        final List<ItemEntity> drops = new ArrayList<>();
        final long since;
        final int carried;

        Run(UUID village, long since, int carried) {
            this.village = village;
            this.since = since;
            this.carried = carried;
        }
    }

    /**
     * A creeper killed by one of a town's folk before it blew (the watch's arrows, a sword): its gunpowder is the town's,
     * and whoever killed it goes for it (fetch). Nothing is made here: the drops are the game's own.
     */
    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Creeper) || !(event.getEntity().level() instanceof ServerLevel level)) return;
        Entity killer = event.getSource().getEntity();
        if (!(killer instanceof VillageFolkEntity f) || f.ownerId() == null || f.isShowcase()) return;
        List<ItemEntity> powder = new ArrayList<>();
        for (ItemEntity e : event.getDrops()) if (e.getItem().is(Items.GUNPOWDER)) powder.add(e);
        if (powder.isEmpty()) return;
        Run r = RUNS.computeIfAbsent(f.getUUID(), k -> new Run(f.ownerId(), level.getGameTime(), f.countCarried(s -> s.is(Items.GUNPOWDER))));
        r.drops.addAll(powder);
        for (ItemEntity e : powder) e.setExtendedLifetime();             // kept till it is fetched, not lost to the clock
    }

    /** Is this folk on its way for a creeper's powder (VillageFolkEntity.calledAway)? */
    public static boolean fetching(VillageFolkEntity f) {
        Run r = RUNS.get(f.getUUID());
        return r != null && !r.drops.isEmpty();
    }

    /**
     * From the folk's tick (VillageFolkEntity.aiStep): to the creeper's gunpowder, picked up, and the run to the stores
     * queued (its deposit: the powder goes in with the rest of what it carries). Not while it is fighting, hunting or the
     * bell is ringing: the powder waits. True while it is on its way.
     */
    public static boolean fetch(VillageFolkEntity f, ServerLevel level) {
        Run r = RUNS.get(f.getUUID());
        if (r == null) return false;
        if (f.getTarget() != null || WatchClears.hunting(f) || Raids.underAlarm(r.village) || f.isSleeping()) return false;
        r.drops.removeIf(e -> !e.isAlive() || e.getItem().isEmpty());
        long now = level.getGameTime();
        if (r.drops.isEmpty() || now - r.since > FETCH_FOR) {
            RUNS.remove(f.getUUID());
            int got = f.countCarried(s -> s.is(Items.GUNPOWDER)) - r.carried;
            if (got > 0) {
                tally(r.village, 5, got);                                 // the watch's powder, on the trade's books
                f.enqueue(f.storesDeposit());
                f.brain("taking the creeper's gunpowder to the stores");
                if (f.getRandom().nextInt(2) == 0) FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "Powder for the fireworks.",
                    "That creeper won't be needing this.", "Into the stores with you."));
            }
            return false;
        }
        ItemEntity d = r.drops.get(0);
        for (ItemEntity e : r.drops) if (e.distanceToSqr(f) < d.distanceToSqr(f)) d = e;
        if (d.distanceToSqr(f) <= 2.25) {
            ItemStack picked = d.getItem().copy();
            ItemStack left = f.insertItem(d.getItem());
            int n = picked.getCount() - left.getCount();
            if (n > 0) Economy.gathered(f, picked, n);
            if (left.isEmpty()) d.discard(); else d.setItem(left);
            f.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        if (f.getNavigation().isDone() || f.tickCount % 20 == 0) f.walkTo(d.blockPosition(), 1.0D);
        f.hobbyNow = "fetching a creeper's gunpowder";
        return true;
    }

    /** Tests: the folk's run for its creeper's powder, looked at now. */
    public static boolean fetchForTests(VillageFolkEntity f, ServerLevel level) {
        return fetch(f, level);
    }

    // ------------------------------------------------------------------ the tally

    /** The trade's own books: stars, display rockets, elytra rockets, displays, rockets fired, gunpowder fetched from creepers. */
    public static long[] tally(UUID village) {
        long[] t = new long[6];
        String n = Ledger.note(village, "fw.tally");
        if (n != null && !n.isEmpty()) {
            String[] p = n.split(",");
            for (int i = 0; i < Math.min(p.length, t.length); i++) t[i] = num(p[i], 0);
        }
        return t;
    }

    static void tally(UUID village, int which, long n) {
        if (n <= 0) return;
        long[] t = tally(village);
        t[which] += n;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(t[i]);
        }
        Ledger.note(village, "fw.tally", sb.toString());
    }

    static long num(@Nullable String s, long or) {
        if (s == null || s.isEmpty()) return or;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return or;
        }
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ where the town sees it

    /** What the maker is about, for "What are you up to?" (FolkTalk.doing). */
    static String doing(VillageFolkEntity f, RandomSource r) {
        UUID id = f.ownerId();
        if (FireworkShows.crewing(f)) return "Setting off the fireworks! Stand well back, and keep your eyes up.";
        if (id == null || hut(id) == null) return "Waiting on the powder hut. Nobody makes fireworks in a house with a hearth.";
        String last = LAST_MADE.get(id);
        if (last != null && last.startsWith("short of")) return "At the powder hut, but I'm " + last + ". Bring me some and I'll make you a sky.";
        if (last != null && r.nextBoolean()) return "At the powder hut. Just finished " + last + ".";
        return FolkTalk.pick(r, "At the powder hut, rolling stars: a pinch of powder, a dye, a gold nugget for the shape.",
            "Filling rockets at the hut. Paper, powder, a star or two, and a steady hand.",
            "Keeping the powder dry and the cauldron full. You don't rush this trade.");
    }

    /** The maker's card line: what it has made, and what is ready. Null for anybody else. */
    @Nullable
    public static String cardLine(VillageFolkEntity f) {
        UUID id = f.ownerId();
        if (f.stationTask() != TRADE || id == null) return null;
        long[] t = tally(id);
        String last = LAST_MADE.get(id);
        return t[1] + " display rockets and " + t[2] + " elytra rockets made, " + t[0] + " stars rolled; " + t[3]
            + (t[3] == 1 ? " display" : " displays") + ", " + t[4] + " rockets fired" + (last == null ? "" : ". Last: " + last);
    }

    /**
     * What the master writes in the trade's book (TradeBooks.notes): its real numbers out of the town's books (the stars,
     * the rockets, the displays, the rockets fired, the watch's powder), the hut's rule, the town's colours, and what the
     * gazette last said of a display.
     */
    static List<String> bookNotes(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<String> out = new ArrayList<>();
        long[] t = tally(id);
        if (t[1] + t[2] > 0) out.add("Between us we've rolled " + Quill.number(t[0]) + " stars and filled " + Quill.number(t[1])
            + " rockets for the town's displays, and " + Quill.number(t[2]) + " plain ones for the travellers' wings.");
        if (t[3] > 0) out.add("We've put on " + Quill.count(t[3], "display", "displays") + ", " + Quill.number(t[4])
            + " rockets up in all, about " + Math.round(t[4] / (double) t[3]) + " a night. A finale wants three at once at the least.");
        if (t[5] > 0) out.add("The watch has brought in " + Quill.number(t[5])
            + " gunpowder from the creepers it shot before they blew. Thank a guard when you see one.");
        out.add("The hut keeps " + HUT_KEEP + " gunpowder to work from and never more than " + HUT_MOST
            + ". The rest stays in the stores, and the cauldron stays full.");
        out.add("Our colours are " + colourWords(townColours(id)) + ", off the town's arms. That's what the sky wears on Founding Day.");
        String n = Ledger.note(id, "fw.review");
        if (n != null && n.contains("|")) out.add("The last the gazette said of us: \"" + n.split("\\|", 2)[1] + "\"");
        return out;
    }

    /** Is a typed line about fireworks (FolkTalk.answer)? */
    public static boolean meant(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("rocket") || t.contains("elytra") || t.contains("firework");
    }

    /**
     * A player asks about fireworks: for elytra rockets ("rockets for my elytra", "flight three"), sold by the maker at
     * its hut or the shopkeeper behind the counter, by the eight, out of the stores (Market.buy); or about the next
     * display. Null for a line that is not about fireworks.
     */
    @Nullable
    public static String talk(VillageFolkEntity f, Player p, String text) {
        if (text == null || text.isEmpty() || !meant(text)) return null;
        UUID id = f.ownerId();
        Villages.Village v = id == null ? null : Villages.get(id);
        if (v == null || !(f.level() instanceof ServerLevel level)) return "Fireworks? Not here. We've no maker.";
        String t = text.toLowerCase(Locale.ROOT);
        boolean buying = t.contains("elytra") || t.contains("buy") || t.contains("sell") || t.contains("flight");
        if (!buying) return FireworkShows.talk(level, v, f);
        boolean seller = f.stationTask() == TRADE || f.stationTask() == StationTask.SHOP;
        if (!seller) {
            VillageFolkEntity m = maker(id);
            return m == null ? "Nobody here makes rockets. Ask at the shop, if we have one."
                : "Rockets for your wings? " + m.displayNameCap() + " makes them, at the powder hut out at the edge of the town. Or try the shop.";
        }
        int flight = t.contains("three") || t.contains(" 3") || t.contains("long") ? 3 : t.contains("two") || t.contains(" 2") ? 2 : 1;
        return sellElytra(level, v, p, flight);
    }

    /** A lot of elytra rockets of this flight to a player, at the town's price for the duration (Market.buy). */
    public static String sellElytra(ServerLevel level, Villages.Village v, Player p, int flight) {
        return Market.buy(level, v, p, elytraRocket(flight));
    }
}
