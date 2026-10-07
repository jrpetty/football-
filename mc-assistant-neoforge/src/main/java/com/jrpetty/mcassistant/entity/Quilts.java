package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.QuiltBlock;
import com.jrpetty.mcassistant.item.LeisureItems;
import com.jrpetty.mcassistant.item.QuiltRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * [leisure] The patchwork quilt (item/LeisureItems, block/QuiltBlock).
 *
 * <p><b>Made</b> by the tailor out of the town's odd wool: six of it, of three colours or more, the colours the stores
 * have least of first (the odd ends), never the wool the beds wait on (Bench keeps it back); laid in the grid two rows of
 * three and made by the recipe (QuiltRecipe), as a player would.
 *
 * <p><b>Bought.</b> A household that can afford one buys one for a bed with none, of an evening, at the shop (at the
 * town's price, out of its own purse: Purchases), and the one who bought it carries it home and lays it over the bed.
 * The tailor keeps a quilt or two made while households are saving up for one, and one more while a wedding is coming:
 * the town's gift to the couple, given at the wedding before everybody (Assemblies), and laid on their bed.
 *
 * <p><b>Slept under.</b> A folk who sleeps under a quilt wakes the happier for it, the next day through ("I slept like a
 * log"), and in winter the happier again ("snug as anything"); and a cold mends the quicker under one, a good deal
 * quicker in winter. Its card says it sleeps under a patchwork quilt.
 */
public final class Quilts {

    private Quilts() {}

    /** A good night under a quilt; and in winter, warm as well. */
    public static final int RESTED = 4, WARM = 4;
    /** What a night under a quilt takes off a cold (ticks of it): in winter, and the rest of the year. */
    public static final int MENDS_WINTER = 8000, MENDS = 4000;
    /** Quilts kept made for the households that are saving for one, at most. */
    static final int FOR_SALE = 2;

    /** Each folk's last night under a quilt, by the morning it woke to. */
    private static final Map<UUID, Long> NIGHT = new ConcurrentHashMap<>();
    /** Each folk's last night whose warmth went into its cold, by the morning. */
    private static final Map<UUID, Long> EASED = new ConcurrentHashMap<>();
    /** The evening each house last looked at buying a quilt. */
    private static final Map<String, Long> LOOKED = new ConcurrentHashMap<>();
    /** Folk on their way home with a quilt (looked at every time, not once a second). */
    private static final java.util.Set<UUID> WALKING = ConcurrentHashMap.newKeySet();

    static void resetForTests() {
        NIGHT.clear();
        EASED.clear();
        LOOKED.clear();
        WALKING.clear();
    }

    static boolean isQuilt(ItemStack s) {
        return s.is(LeisureItems.QUILT_ITEM.get());
    }

    // ------------------------------------------------------------------ beds

    /** The foot of the bed this is a part of, or null if it is no bed. */
    @Nullable
    static BlockPos footOf(ServerLevel level, @Nullable BlockPos part) {
        if (part == null) return null;
        BlockState s = level.getBlockState(part);
        if (!(s.getBlock() instanceof BedBlock)) return null;
        return s.getValue(BedBlock.PART) == BedPart.FOOT ? part : part.relative(s.getValue(BedBlock.FACING).getOpposite());
    }

    /** Is there a quilt on this bed (any part of it)? */
    static boolean quilted(ServerLevel level, @Nullable BlockPos bedPart) {
        BlockPos foot = footOf(level, bedPart);
        return foot != null && level.getBlockState(foot.above()).getBlock() instanceof QuiltBlock;
    }

    /** A quilt laid on this bed (any part of it), if there is room over its foot: true if it lies there now. */
    static boolean lay(ServerLevel level, BlockPos bedPart) {
        BlockPos foot = footOf(level, bedPart);
        if (foot == null) return false;
        BlockState f = level.getBlockState(foot);
        if (!QuiltBlock.bedFoot(f)) return false;
        BlockPos at = foot.above();
        if (level.getBlockState(at).getBlock() instanceof QuiltBlock) return true;
        if (!level.getBlockState(at).canBeReplaced()) return false;
        level.setBlock(at, LeisureItems.QUILT.get().onBed(f), Block.UPDATE_ALL);
        level.playSound(null, at, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1.0F, 0.9F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.getX() + 0.5, at.getY() - 0.2, at.getZ() + 0.5, 6, 0.4, 0.1, 0.4, 0.0);
        return true;
    }

    // ------------------------------------------------------------------ the town's round, every two seconds

    /**
     * Who is asleep under a quilt tonight (the night it is counted for its morning, and its cold eased once a night);
     * of an evening, a household that can afford one buys one.
     */
    static void tick(ServerLevel level, Villages.Village v, long day, long t) {
        UUID id = v.id();
        if (t >= 13000L || t < 1500L) {
            long morning = t >= 13000L ? day + 1 : day;
            boolean winter = Seasons.season(id, day) == Seasons.Season.WINTER;
            for (AssistantEntity a : Villages.folkOf(id)) {
                if (!(a instanceof VillageFolkEntity f) || !f.isSleeping()) continue;
                BlockPos head = f.getSleepingPos().orElse(null);
                if (!quilted(level, head)) continue;
                night(f, morning, winter);
            }
        }
        if (t >= 12000L && t < 13500L) buy(level, v, day);
    }

    /** A night under a quilt: counted for the morning, and its cold the better for it (once a night). */
    static void night(VillageFolkEntity f, long morning, boolean winter) {
        NIGHT.put(f.getUUID(), morning);
        Long eased = EASED.get(f.getUUID());
        if ((eased == null || eased != morning) && f.health().cold > 0) {
            EASED.put(f.getUUID(), morning);
            f.health().cold = Math.max(1, f.health().cold - (winter ? MENDS_WINTER : MENDS));
        }
    }

    // ------------------------------------------------------------------ bought

    /** The households that would buy a quilt: a bed with none, and somebody in it who can afford one. */
    static List<Object[]> buyers(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        List<Object[]> out = new ArrayList<>();
        ItemStack sample = new ItemStack(LeisureItems.QUILT_ITEM.get());
        for (Homes.Home h : List.copyOf(Homes.homes(id).values())) {
            if (!level.isLoaded(h.anchor)) continue;
            BlockPos bare = null;
            for (BlockPos head : Homes.bedsIn(level, id, h)) if (!quilted(level, head)) { bare = head; break; }
            if (bare == null) continue;
            VillageFolkEntity payer = null;
            for (VillageFolkEntity m : Homes.loadedMembers(id, h)) {
                if (m.isBaby() || !m.isAlive()) continue;
                if (m.countCarried(Quilts::isQuilt) > 0) { payer = null; bare = null; break; }      // one on its way home already
                if (payer == null || m.purse() > payer.purse()) payer = m;
            }
            if (payer == null || bare == null) continue;
            double price = Purchases.priceEach(level, id, sample, payer);
            if (payer.purse() < Math.ceil(price) + 3) continue;                                     // it keeps a little by
            out.add(new Object[]{ h, payer, bare });
        }
        return out;
    }

    /** Of an evening, once a day a house: a household that can afford a quilt buys one, at the shop, to take home. */
    static void buy(ServerLevel level, Villages.Village v, long day) {
        UUID id = v.id();
        if (Market.stock(level, id, Quilts::isQuilt) + ShopStock.held(level, id, Quilts::isQuilt) <= 0) return;
        for (Object[] b : buyers(level, v)) {
            Homes.Home h = (Homes.Home) b[0];
            String key = id + "/" + h.anchor.asLong();
            if (LOOKED.getOrDefault(key, -1L) >= day) continue;
            LOOKED.put(key, day);
            VillageFolkEntity payer = (VillageFolkEntity) b[1];
            if (buyNow(level, v, payer)) return;                                                     // one sale an evening is plenty
        }
    }

    /** This folk buys a quilt (at the town's price) and has it with it to take home. Whether it did. */
    static boolean buyNow(ServerLevel level, Villages.Village v, VillageFolkEntity payer) {
        int before = payer.purse();
        int got = Purchases.get(level, payer, Quilts::isQuilt, 1, Purchases.Need.HOME);
        if (got <= 0) return false;
        for (ItemStack s : payer.getInventoryItems()) if (isQuilt(s) && !Homes.isKeepsake(s)) { Homes.keepsake(s, payer); break; }
        long day = level.getDayTime() / 24000L;
        int paid = Math.max(0, before - payer.purse());
        payer.persona().remember(day, "I bought a patchwork quilt for our bed" + (paid > 0 ? ", " + paid + (paid == 1 ? " coin" : " coins") : ""), 2);
        FolkTalk.speak(payer, FolkTalk.pick(payer.getRandom(), "A patchwork quilt for the bed! I've had my eye on that one.",
            "Look at the colours on this quilt! Home it goes.", "Saved up for this quilt, I did."));
        Pastimes.LOG.info("[MCA-LEISURE] {} of {} bought a patchwork quilt for {} coins", payer.displayNameCap(), Villages.name(v.id()), paid);
        return true;
    }

    /**
     * A folk with a quilt of its own with it, free of an evening (or the day of rest): home to its bed (or a bed of its
     * house with none) and laid over it. What it is doing, or null.
     */
    @Nullable
    static String hold(ServerLevel level, VillageFolkEntity f, UUID village, long t, long day) {
        if (f.countCarried(Quilts::isQuilt) <= 0 || !Families.free(f)) {
            WALKING.remove(f.getUUID());
            return null;
        }
        if (f.tickCount % 20 != 2 && !WALKING.contains(f.getUUID())) return null;
        BlockPos bed = bedFor(level, village, f);
        if (bed == null) return null;
        if (f.blockPosition().distSqr(bed) > 2.5 * 2.5) {
            WALKING.add(f.getUUID());
            if (f.getNavigation().isDone() || f.tickCount - f.hobbyTick > 60 || f.tickCount < f.hobbyTick) {
                f.walkTo(bed, 0.9D);
                f.hobbyTick = f.tickCount;
            }
            return "carrying a patchwork quilt home to the bed";
        }
        WALKING.remove(f.getUUID());
        f.getNavigation().stop();
        if (!lay(level, bed) || f.removeMatching(Quilts::isQuilt, 1) != 1) return null;
        f.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        f.getLookControl().setLookAt(bed.getX() + 0.5, bed.getY() + 0.5, bed.getZ() + 0.5);
        f.persona().remember(day, "I laid a patchwork quilt on our bed", 2);
        FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There. Doesn't that brighten the room?", "Lovely. Can't wait for bedtime.",
            "All those colours! Like a garden on the bed."));
        return "laying a patchwork quilt on the bed";
    }

    /** Where its quilt goes: its own bed if it has none, else a bed of its house with none. */
    @Nullable
    static BlockPos bedFor(ServerLevel level, UUID village, VillageFolkEntity f) {
        BlockPos own = f.bedPos();
        if (own != null && level.getBlockState(own).getBlock() instanceof BedBlock && !quilted(level, own)) return own;
        Homes.Home h = Homes.homeOf(village, f.getUUID());
        if (h == null) return null;
        for (BlockPos head : Homes.bedsIn(level, village, h)) if (!quilted(level, head)) return head;
        return null;
    }

    // ------------------------------------------------------------------ made

    /** How many quilts the town wants in its stores: for the households saving for one, and a wedding's gift. */
    static int wanted(ServerLevel level, Villages.Village v) {
        int n = Math.min(FOR_SALE, buyers(level, v).size());
        if (Gatherings.wedding(v.id()) != null) n++;
        return n;
    }

    /** The wool's sixteen colours. */
    static Item wool(DyeColor c) {
        return switch (c) {
            case WHITE -> net.minecraft.world.item.Items.WHITE_WOOL;
            case ORANGE -> net.minecraft.world.item.Items.ORANGE_WOOL;
            case MAGENTA -> net.minecraft.world.item.Items.MAGENTA_WOOL;
            case LIGHT_BLUE -> net.minecraft.world.item.Items.LIGHT_BLUE_WOOL;
            case YELLOW -> net.minecraft.world.item.Items.YELLOW_WOOL;
            case LIME -> net.minecraft.world.item.Items.LIME_WOOL;
            case PINK -> net.minecraft.world.item.Items.PINK_WOOL;
            case GRAY -> net.minecraft.world.item.Items.GRAY_WOOL;
            case LIGHT_GRAY -> net.minecraft.world.item.Items.LIGHT_GRAY_WOOL;
            case CYAN -> net.minecraft.world.item.Items.CYAN_WOOL;
            case PURPLE -> net.minecraft.world.item.Items.PURPLE_WOOL;
            case BLUE -> net.minecraft.world.item.Items.BLUE_WOOL;
            case BROWN -> net.minecraft.world.item.Items.BROWN_WOOL;
            case GREEN -> net.minecraft.world.item.Items.GREEN_WOOL;
            case RED -> net.minecraft.world.item.Items.RED_WOOL;
            case BLACK -> net.minecraft.world.item.Items.BLACK_WOOL;
        };
    }

    /**
     * Six wool for a quilt, of three colours at the least: the colours the stores have least of first (the odd ends), two
     * of each, and a colour more if that is short. Empty if the stores have fewer than three colours to spare.
     */
    static List<Item> pick(ServerLevel level, UUID village) {
        List<Object[]> have = new ArrayList<>();
        for (DyeColor c : DyeColor.values()) {
            Item w = wool(c);
            int n = Market.stock(level, village, s -> s.is(w) && s.getComponentsPatch().isEmpty());
            if (n > 0) have.add(new Object[]{ w, n });
        }
        if (have.size() < QuiltRecipe.COLOURS) return List.of();
        have.sort(Comparator.comparingInt(o -> (Integer) o[1]));
        List<Item> out = new ArrayList<>();
        for (int round = 0; round < 6 && out.size() < 6; round++) {
            for (Object[] o : have) {
                if (out.size() >= 6) break;
                int used = 0;
                for (Item i : out) if (i == o[0]) used++;
                if (used == round && used < (Integer) o[1]) out.add((Item) o[0]);
            }
        }
        long colours = out.stream().distinct().count();
        return out.size() == 6 && colours >= QuiltRecipe.COLOURS ? out : List.of();
    }

    /** The quilt's recipe as the game has it. */
    @Nullable
    static CraftingRecipe recipe(ServerLevel level) {
        Optional<RecipeHolder<?>> h = level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(com.jrpetty.mcassistant.McAssistantMod.MODID,
            "patchwork_quilt"));
        return h.isPresent() && h.get().value() instanceof CraftingRecipe r && r.getType() == RecipeType.CRAFTING ? r : null;
    }

    /**
     * A quilt made: six of the stores' odd wool (never what the village keeps back for its beds: Bench), laid two rows of
     * three and made by the quilt's recipe; into the stores. Empty if the stores cannot run to it.
     */
    static ItemStack make(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity f, Bench.Hand hand) {
        List<Item> six = pick(level, v.id());
        Item quilt = LeisureItems.QUILT_ITEM.get();
        if (six.isEmpty()) {
            Pastimes.shortOf(v.id(), quilt, "wool of three colours (six of it, two of each)");
            return ItemStack.EMPTY;
        }
        CraftingRecipe recipe = recipe(level);
        if (recipe == null) return ItemStack.EMPTY;
        List<Bench.Want> wants = new ArrayList<>();
        Map<Item, Integer> counts = new java.util.LinkedHashMap<>();
        for (Item w : six) counts.merge(w, 1, Integer::sum);
        for (Map.Entry<Item, Integer> e : counts.entrySet()) wants.add(Bench.Want.of(e.getKey(), e.getValue()));
        Bench.Plan p = Bench.plan(level, v, wants, hand);
        if (!p.ok()) {
            Pastimes.shortOf(v.id(), quilt, p.chain());
            return ItemStack.EMPTY;
        }
        List<ItemStack> grid = new ArrayList<>();
        for (Item w : six) grid.add(new ItemStack(w));
        CraftingInput input = CraftingInput.of(3, 2, grid);
        if (!recipe.matches(input, level)) return ItemStack.EMPTY;
        if (!Bench.take(level, v, p, f)) return ItemStack.EMPTY;
        ItemStack out = recipe.assemble(input, level.registryAccess());
        Crafts.store(level, v, out.copy());
        Pastimes.forgetShort(v.id(), quilt);
        return out;
    }

    // ------------------------------------------------------------------ the wedding's gift (Assemblies)

    /**
     * At a wedding, before the couple are pronounced wed: the town's gift of a quilt out of the stores, if there is one,
     * given to the first of the two to take home and lay on their bed.
     */
    static void weddingGift(ServerLevel level, Assemblies.Assembly a, List<Assemblies.Line> s, RandomSource r) {
        Villages.Village v = Villages.get(a.village);
        if (v == null || a.principals.size() < 2 || Market.stock(level, a.village, Quilts::isQuilt) <= 0) return;
        VillageFolkEntity tailor = null;
        for (AssistantEntity x : Villages.folkOf(a.village)) {
            if (x instanceof VillageFolkEntity t && t.stationTask() == AssistantEntity.StationTask.TAILOR && t.isAlive()) { tailor = t; break; }
        }
        String by = tailor != null ? ", stitched by " + tailor.displayNameCap() : "";
        UUID first = a.principals.get(0), second = a.principals.get(1);
        s.add(new Assemblies.Line(null, "And from all of us, for your new home: a patchwork quilt" + by + "!", '!', () -> give(level, v, first, second)));
    }

    /** The gift handed over: a quilt out of the stores into the first one's hands (it lays it on their bed). */
    static boolean give(ServerLevel level, Villages.Village v, UUID first, UUID second) {
        if (!(level.getEntity(first) instanceof VillageFolkEntity a) || !a.isAlive()) return false;
        ItemStack q = Crafts.takeOne(level, v, Quilts::isQuilt);
        if (q.isEmpty()) return false;
        Homes.keepsake(q, a);
        ItemStack left = a.insertGiven(q);
        if (!left.isEmpty()) {
            Crafts.store(level, v, left);
            return false;
        }
        long day = level.getDayTime() / 24000L;
        a.persona().remember(day, "the town gave us a patchwork quilt at our wedding", 4);
        a.persona().gotAGift(day, "the town");
        if (level.getEntity(second) instanceof VillageFolkEntity b) {
            b.persona().remember(day, "the town gave us a patchwork quilt at our wedding", 4);
            b.persona().gotAGift(day, "the town");
        }
        FolkTalk.speak(a, FolkTalk.pick(a.getRandom(), "A quilt! Oh, it's beautiful — thank you, everybody!", "For us? Look at all the colours!"));
        Pastimes.news(v.id(), day, "quilt:the newly-weds were given a patchwork quilt by the town");
        return true;
    }

    // ------------------------------------------------------------------ spirits and the card

    /** The morning after a night under a quilt: rested (and in winter, warm). */
    static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        Long n = NIGHT.get(f.getUUID());
        if (n == null || n != day) return m;
        why.add(new Object[]{ "quilt", RESTED });
        m += RESTED;
        if (f.ownerId() != null && Seasons.season(f.ownerId(), day) == Seasons.Season.WINTER) {
            why.add(new Object[]{ "warmquilt", WARM });
            m += WARM;
        }
        return m;
    }

    @Nullable
    static String card(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level)) return null;
        if (f.countCarried(Quilts::isQuilt) > 0) return "has a patchwork quilt to lay on its bed";
        return quilted(level, f.bedPos()) ? "sleeps under a patchwork quilt" : null;
    }

    static String status(ServerLevel level, Villages.Village v) {
        int beds = 0, quilts = 0;
        for (Homes.Home h : List.copyOf(Homes.homes(v.id()).values())) {
            if (!level.isLoaded(h.anchor)) continue;
            for (BlockPos head : Homes.bedsIn(level, v.id(), h)) {
                beds++;
                if (quilted(level, head)) quilts++;
            }
        }
        long day = level.getDayTime() / 24000L;
        int slept = 0;
        for (AssistantEntity a : Villages.folkOf(v.id())) {
            Long n = NIGHT.get(a.getUUID());
            if (n != null && n == day) slept++;
        }
        return quilts + " of " + beds + " beds have a quilt; " + slept + " slept under one last night; " + buyers(level, v).size()
            + " households could buy one";
    }

    // ------------------------------------------------------------------ tests

    /** Tests: this folk counted as asleep under its quilt the night before this morning (as the round counts it). */
    public static boolean nightForTests(ServerLevel level, VillageFolkEntity f, long morning) {
        if (!quilted(level, f.getSleepingPos().orElse(f.bedPos()))) return false;
        night(f, morning, f.ownerId() != null && Seasons.season(f.ownerId(), morning) == Seasons.Season.WINTER);
        return true;
    }

    /** Tests: is there a quilt on this bed? */
    public static boolean quiltedForTests(ServerLevel level, BlockPos bedPart) {
        return quilted(level, bedPart);
    }

    /** Tests: the households that would buy one now. */
    public static int buyersForTests(ServerLevel level, Villages.Village v) {
        return buyers(level, v).size();
    }

    /** Tests: this folk buys a quilt now. */
    public static boolean buyForTests(ServerLevel level, VillageFolkEntity f) {
        Villages.Village v = f.ownerId() == null ? null : Villages.get(f.ownerId());
        return v != null && buyNow(level, v, f);
    }

    /** Tests: the folk's look with its quilt (as Families.hold calls it). */
    @Nullable
    public static String holdForTests(ServerLevel level, VillageFolkEntity f) {
        long dt = level.getDayTime();
        WALKING.add(f.getUUID());
        return f.ownerId() == null ? null : hold(level, f, f.ownerId(), dt % 24000L, dt / 24000L);
    }

    /** Tests: the wedding's gift given now to these two. */
    public static boolean giftForTests(ServerLevel level, VillageFolkEntity a, VillageFolkEntity b) {
        Villages.Village v = a.ownerId() == null ? null : Villages.get(a.ownerId());
        return v != null && give(level, v, a.getUUID(), b.getUUID());
    }

    /** Tests: the six wool a quilt would take now. */
    public static List<Item> pickForTests(ServerLevel level, UUID village) {
        return pick(level, village);
    }

    /** Tests: does this predicate's thing stand in for a quilt (the market's)? */
    static Predicate<ItemStack> is() {
        return Quilts::isQuilt;
    }
}
