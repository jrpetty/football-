package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.block.WindowBoxBlock;
import com.jrpetty.mcassistant.item.WindowBoxItem;
import com.jrpetty.mcassistant.item.WorkItems;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [workitems] The window boxes (WindowBoxBlock): flowers under a household's windows.
 * <ul>
 * <li><b>Made</b> at the shop's bench by the recipe for the flower the stores hold (three planks, a block of earth and the
 *     flower: make), so the box has that flower in it; never the last few flowers the households keep for their gardens
 *     and their graves.</li>
 * <li><b>Hung</b> by the household's gardener (the one of it that loves gardening, else whoever of it has the most put
 *     by): a well-off household's house gets one under its windows (StreetFurniture.windowBox, in place of the old pot on
 *     a trapdoor when the stores have a box or the makings of one). The gardener takes the box out of the stores and
 *     walks it home in its hands, and hangs it under the window. A house raised a storey gets its boxes from the builders
 *     as it is finished (TownJobs, "furniture").</li>
 * <li><b>Tended.</b> Of a morning the gardener waters the boxes with a bucket of water out of the stores (the bucket back
 *     in empty); the rain waters them as well. A box nobody has watered for three days wilts; in winter they all die back
 *     to stalks, and in the spring they flower again.</li>
 * <li><b>What they do.</b> A household with its boxes in flower is a little happier (its folk's mood), and its house is
 *     the prettier and the dearer: worth four in the hundred more for each box, two at the most (HousingMarket.worth), and
 *     counted among the town's window boxes (its look).</li>
 * </ul>
 */
public final class WindowBoxes {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private WindowBoxes() {}

    /** A box wilts after so many days unwatered. */
    static final int DRY_DAYS = 3;
    /** How much more a house is worth for each box in flower (percent), and the most boxes that count. */
    static final int WORTH_PERCENT = 4, MOST_COUNTED = 2;
    /** The mood a household's boxes in flower give it. */
    static final int MOOD = 3;
    /** An errand given up after so long (ticks). */
    static final long TOO_LONG = 1600L;

    /** A gardener's errand: to hang a box, or to water one. */
    static final class Errand {
        final BlockPos at;
        final Direction out;
        final boolean hang;
        final ItemStack carried;
        final ItemStack wasInHand;
        final long started;
        final UUID village;
        int walkTick = -1000;

        Errand(UUID village, BlockPos at, Direction out, boolean hang, ItemStack carried, ItemStack wasInHand, long started) {
            this.village = village;
            this.at = at;
            this.out = out;
            this.hang = hang;
            this.carried = carried;
            this.wasInHand = wasInHand;
            this.started = started;
        }
    }

    private static final Map<UUID, Errand> ERRANDS = new ConcurrentHashMap<>();
    /** Each house's boxes in flower (by its anchor): {day counted, boxes}. */
    private static final Map<Long, long[]> AT_HOUSE = new ConcurrentHashMap<>();
    /** The day each town last saw to its boxes (the season, the wilting), and its wants. */
    private static final Map<UUID, Long> TENDED = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> WANTED = new ConcurrentHashMap<>();

    static void resetForTests() {
        ERRANDS.clear();
        AT_HOUSE.clear();
        TENDED.clear();
        WANTED.clear();
    }

    // ------------------------------------------------------------------ made

    /**
     * A box made at the bench by the recipe for a flower the stores can spare (more than the four the households keep):
     * the recipe's own planks, earth and flower out of the stores, and the box with that flower in it into them. What
     * was made, or null.
     */
    @Nullable
    static String make(ServerLevel level, Villages.Village v, @Nullable VillageFolkEntity maker) {
        for (RecipeHolder<CraftingRecipe> h : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe r = h.value();
            ItemStack result = r.getResultItem(level.registryAccess());
            if (!result.is(WorkItems.WINDOW_BOX_ITEM.get())) continue;
            WindowBoxBlock.Flower flower = WindowBoxItem.flower(result);
            if (Market.stock(level, v.id(), s -> s.is(flower.item())) <= StreetFurniture.FLOWERS_KEPT) continue;
            if (!payFor(level, v, r.getIngredients())) continue;
            ItemStack made = result.copy();
            Crafts.store(level, v, made);
            WANTED.remove(v.id());
            if (maker != null) LOG.info("[MCA-WORK] {} made a window box of {} out of the stores", maker.displayNameCap(), flower.plural());
            return "a window box of " + flower.plural() + ", for the houses";
        }
        return null;
    }

    /** A recipe's makings out of the stores, all of them or none: planks sawn from logs if need be. */
    static boolean payFor(ServerLevel level, Villages.Village v, List<Ingredient> parts) {
        int planks = 0;
        List<Ingredient> rest = new ArrayList<>();
        for (Ingredient in : parts) {
            if (in.isEmpty()) continue;
            if (in.test(new ItemStack(Items.OAK_PLANKS)) && in.test(new ItemStack(Items.SPRUCE_PLANKS))) planks++;
            else rest.add(in);
        }
        for (Ingredient in : rest) if (Market.stock(level, v.id(), in) < 1) return false;
        if (planks > 0 && Market.stock(level, v.id(), s -> s.is(ItemTags.PLANKS)) + 4 * Market.stock(level, v.id(), s -> s.is(ItemTags.LOGS)) < planks) return false;
        List<ItemStack> took = new ArrayList<>();
        for (Ingredient in : rest) {
            ItemStack one = Crafts.takeOne(level, v, in);
            if (one.isEmpty()) {
                for (ItemStack t : took) Crafts.store(level, v, t);
                return false;
            }
            took.add(one);
        }
        if (planks > 0 && !Crafts.usePlanks(level, v, planks)) {
            for (ItemStack t : took) Crafts.store(level, v, t);
            return false;
        }
        return true;
    }

    /** Tests: a box made now by the recipe, out of the stores. */
    @Nullable
    public static String makeForTests(ServerLevel level, UUID village) {
        Villages.Village v = Villages.get(village);
        return v == null ? null : make(level, v, null);
    }

    // ------------------------------------------------------------------ hung

    /** Who of a household sees to its garden and its boxes: one that loves gardening, else the one with most put by. */
    @Nullable
    static VillageFolkEntity gardener(UUID village, Homes.Home h) {
        VillageFolkEntity best = null;
        for (VillageFolkEntity f : Homes.loadedMembers(village, h)) {
            if (f.isBaby() || !f.isAlive() || f.isSleeping() || f.trip() != null || f.expedition() != null) continue;
            if (f.persona().rolled() && f.persona().hobby() == Persona.Hobby.GARDENING) return f;
            if (best == null || f.purse() > best.purse()) best = f;
        }
        return best;
    }

    /**
     * A well-off household's window wants a box (StreetFurniture.windowBox): a box out of the stores (made there if they
     * have none and can make one), carried home by its gardener in its hands and hung under the window. True if the
     * gardener is on its way (or one of the household already is): the old pot on a trapdoor is not put up instead.
     */
    public static boolean hang(ServerLevel level, Villages.Village v, Homes.Home h, BlockPos ledge, Direction out) {
        UUID id = v.id();
        for (Errand e : ERRANDS.values()) if (e.hang && e.village.equals(id) && e.at.distSqr(h.anchor) <= 16 * 16) return true;
        if (!level.getBlockState(ledge).isAir()) return false;
        BlockPos wall = ledge.relative(out.getOpposite());
        if (!level.getBlockState(wall).isFaceSturdy(level, wall, out)) return false;
        VillageFolkEntity g = gardener(id, h);
        if (g == null || ERRANDS.containsKey(g.getUUID())) return false;
        if (Market.stock(level, id, s -> s.is(WorkItems.WINDOW_BOX_ITEM.get())) < 1 && make(level, v, null) == null) return false;
        ItemStack box = Crafts.takeOne(level, v, s -> s.is(WorkItems.WINDOW_BOX_ITEM.get()));
        if (box.isEmpty()) return false;
        // In its hands, all the way home.
        ItemStack inHand = g.getMainHandItem().copy();
        g.setItemSlot(EquipmentSlot.MAINHAND, box.copy());
        ERRANDS.put(g.getUUID(), new Errand(id, ledge.immutable(), out, true, box, inHand, level.getGameTime()));
        g.brain("carrying a window box home to hang under the window");
        return true;
    }

    /**
     * A gardener on its errand, a tick of it (VillageFolkEntity.aiStep, through WorkTools.hold): to the box's place, and
     * there it hangs the box (or waters it). True while it is at it.
     */
    public static boolean hold(VillageFolkEntity f) {
        if (ERRANDS.isEmpty()) return false;
        Errand e = ERRANDS.get(f.getUUID());
        if (e == null || !(f.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime();
        if (now - e.started > TOO_LONG || now < e.started || !f.isAlive() || f.isSleeping()) {
            giveUp(level, f, e);
            return false;
        }
        double dx = f.getX() - (e.at.getX() + 0.5), dz = f.getZ() - (e.at.getZ() + 0.5);
        double reach = 3.0;
        if (dx * dx + dz * dz > reach * reach || Math.abs(f.getY() - e.at.getY()) > 3.0) {
            if (f.getNavigation().isDone() || f.tickCount - e.walkTick > 60) {
                BlockPos stand = e.at.relative(e.out);
                f.getNavigation().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 1.0D);
                e.walkTick = f.tickCount;
            }
            f.hobbyNow = e.hang ? "carrying a window box home" : "watering the window boxes";
            return true;
        }
        f.getNavigation().stop();
        f.getLookControl().setLookAt(e.at.getX() + 0.5, e.at.getY() + 0.7, e.at.getZ() + 0.5);
        f.swing(InteractionHand.MAIN_HAND);
        ERRANDS.remove(f.getUUID());
        if (e.hang) {
            if (put(level, e.at, e.out, e.carried)) {
                WorkTools.bump(e.village, "work.boxes");
                FolkTalk.speak(f, FolkTalk.pick(f.getRandom(), "There — that brightens the place up.", "Flowers under the window. Lovely.",
                    "Every house should have a box of flowers."));
                f.brain("hung a window box under the window");
                if (f.persona().rolled() && f.persona().hobby() == Persona.Hobby.GARDENING) f.persona().enjoyedHobby(level.getDayTime() / 24000L);
            } else {
                Crafts.store(level, Villages.get(e.village), e.carried);
            }
        } else {
            watered(level, e.at, f.displayNameCap());
            Villages.Village v = Villages.get(e.village);
            if (v != null) Crafts.store(level, v, new ItemStack(Items.BUCKET));
            f.brain("watered the window boxes");
        }
        f.setItemSlot(EquipmentSlot.MAINHAND, e.wasInHand);
        f.hobbyNow = null;
        return true;
    }

    private static void giveUp(ServerLevel level, VillageFolkEntity f, Errand e) {
        ERRANDS.remove(f.getUUID());
        Villages.Village v = Villages.get(e.village);
        if (v != null) Crafts.store(level, v, e.hang ? e.carried : new ItemStack(Items.WATER_BUCKET));
        f.setItemSlot(EquipmentSlot.MAINHAND, e.wasInHand);
        f.hobbyNow = null;
    }

    /** A box set under a window, its back to the wall, its flower the one it was made with. */
    static boolean put(ServerLevel level, BlockPos at, Direction out, ItemStack box) {
        if (!level.getBlockState(at).isAir()) return false;
        BlockState s = WorkItems.WINDOW_BOX.get().defaultBlockState().setValue(WindowBoxBlock.FACING, out)
            .setValue(WindowBoxBlock.FLOWER, WindowBoxItem.flower(box))
            .setValue(WindowBoxBlock.BLOOM, inFlowerSeason(level, at));
        if (!s.canSurvive(level, at)) return false;
        level.setBlock(at, s, 3);
        level.playSound(null, at, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.9F, 1.1F);
        level.playSound(null, at, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
        WorkSites.water(level, at, level.getDayTime() / 24000L);
        AT_HOUSE.clear();
        return true;
    }

    /** Is it a season for flowers here (the nearest town's year: not winter)? */
    static boolean inFlowerSeason(ServerLevel level, BlockPos at) {
        Villages.Village v = Villages.nearest(level, at);
        return Seasons.season(v == null ? null : v.id(), level.getDayTime() / 24000L) != Seasons.Season.WINTER;
    }

    /** Watered (by a player, or a gardener): the box's day put down, and its flowers up again unless it is winter. True if
     *  it is in flower now. */
    public static boolean watered(ServerLevel level, BlockPos at, String by) {
        WorkSites.water(level, at, level.getDayTime() / 24000L);
        BlockState s = level.getBlockState(at);
        if (!(s.getBlock() instanceof WindowBoxBlock)) return false;
        boolean bloom = inFlowerSeason(level, at);
        if (s.getValue(WindowBoxBlock.BLOOM) != bloom) level.setBlock(at, s.setValue(WindowBoxBlock.BLOOM, bloom), 3);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.getX() + 0.5, at.getY() + 1.1, at.getZ() + 0.5, 4, 0.4, 0.2, 0.2, 0.0);
        AT_HOUSE.clear();
        return bloom;
    }

    /** What a player is told of a box it right-clicks. */
    public static String describe(ServerLevel level, BlockPos at, BlockState s) {
        String f = s.getValue(WindowBoxBlock.FLOWER).plural();
        long last = WorkSites.wateredOn(level, at), day = level.getDayTime() / 24000L;
        if (!inFlowerSeason(level, at)) return "A window box of " + f + ", died back for the winter. They'll be up again in the spring.";
        if (!s.getValue(WindowBoxBlock.BLOOM)) return "A window box of " + f + ", wilting: it wants watering.";
        return "A window box of " + f + " in flower" + (last >= 0 && day - last <= 0 ? ", watered today." : last >= 0 ? ", watered "
            + (day - last == 1 ? "yesterday." : (day - last) + " days ago.") : ".");
    }

    // ------------------------------------------------------------------ the town's rounds

    /**
     * The town's rounds (WorkTools.rounds): once a morning, its boxes seen to: each in flower or not by the season and its
     * watering (the rain waters them), and a gardener sent with a bucket to the first that wants water; and a house raised
     * a storey given its boxes by the builders. What the town wants of boxes is kept for the shop's bench.
     */
    static void rounds(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        long day = level.getDayTime() / 24000L, tod = level.getDayTime() % 24000L;
        int reach = Villages.townReach(id) + 8;
        List<BlockPos> boxes = WorkSites.boxesNear(level, v.centre(), reach);
        if (tod >= 1000L && tod < 9000L && TENDED.getOrDefault(id, -1L) != day) {
            TENDED.put(id, day);
            boolean rain = level.isRaining();
            boolean flowers = Seasons.season(id, day) != Seasons.Season.WINTER;
            BlockPos dry = null;
            for (BlockPos p : boxes) {
                if (!level.isLoaded(p)) continue;
                BlockState s = level.getBlockState(p);
                if (!(s.getBlock() instanceof WindowBoxBlock)) {
                    WorkSites.boxGone(level, p);
                    continue;
                }
                if (rain && level.canSeeSky(p.above())) WorkSites.water(level, p, day);
                long last = WorkSites.wateredOn(level, p);
                boolean bloom = flowers && day - last <= DRY_DAYS;
                if (s.getValue(WindowBoxBlock.BLOOM) != bloom) level.setBlock(p, s.setValue(WindowBoxBlock.BLOOM, bloom), 3);
                if (flowers && day - last >= 1 && dry == null) dry = p;
            }
            AT_HOUSE.clear();
            if (dry != null) waterRound(level, v, dry);
        }
        WANTED.put(id, Math.min(4, wantedNow(level, v)));
        raisedHouses(level, v);
    }

    /** A gardener of the household whose box this is sent with a bucket of water out of the stores. */
    static void waterRound(ServerLevel level, Villages.Village v, BlockPos box) {
        UUID id = v.id();
        Homes.Home home = null;
        for (Homes.Home h : Homes.homes(id).values()) {
            if (Math.abs(h.anchor.getX() - box.getX()) <= 10 && Math.abs(h.anchor.getZ() - box.getZ()) <= 10 && !h.members.isEmpty()) { home = h; break; }
        }
        if (home == null) return;
        VillageFolkEntity g = gardener(id, home);
        if (g == null || ERRANDS.containsKey(g.getUUID())) return;
        ItemStack water = Crafts.takeOne(level, v, s -> s.is(Items.WATER_BUCKET));
        if (water.isEmpty()) return;
        ItemStack inHand = g.getMainHandItem().copy();
        g.setItemSlot(EquipmentSlot.MAINHAND, water.copy());
        BlockState s = level.getBlockState(box);
        Direction out = s.getBlock() instanceof WindowBoxBlock ? s.getValue(WindowBoxBlock.FACING) : Direction.SOUTH;
        ERRANDS.put(g.getUUID(), new Errand(id, box.immutable(), out, false, water, inHand, level.getGameTime()));
        g.brain("off to water the window boxes with a bucket from the stores");
    }

    /** Houses raised a storey (Grow) given their boxes by the builders as they are finished: one a visit, out of the stores. */
    static void raisedHouses(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        if (Market.stock(level, id, s -> s.is(WorkItems.WINDOW_BOX_ITEM.get())) < 1) return;
        for (Homes.Home h : Homes.homes(id).values()) {
            if (Flats.isFlat(h) || !Ledger.grown(id, h.anchor) || Ledger.raising(id, h.anchor)) continue;
            Ledger.Building b = Homes.building(id, h.anchor);
            if (b == null || !level.isLoaded(b.anchor()) || StreetFurniture.boxedCount(level, b) > 0) continue;
            for (StreetFurniture.Box box : StreetFurniture.boxes(b)) {
                if (StreetFurniture.boxed(level, box) || !level.getBlockState(box.ledge()).isAir()) continue;
                if (!TownJobs.atWork(level, v, "furniture", box.ledge(), "hanging window boxes on the raised house")) return;
                ItemStack one = Crafts.takeOne(level, v, s -> s.is(WorkItems.WINDOW_BOX_ITEM.get()));
                if (one.isEmpty()) return;
                if (put(level, box.ledge(), box.out(), one)) {
                    WorkTools.bump(id, "work.boxes");
                    Villages.tell(id, level.getDayTime() / 24000L, "the builders hung window boxes on the house they had raised a storey");
                } else {
                    Crafts.store(level, v, one);
                }
                return;
            }
        }
    }

    /** The boxes the town wants made: one for each well-off house with a window still bare, four at most. */
    static int wantedNow(ServerLevel level, Villages.Village v) {
        UUID id = v.id();
        int n = 0;
        for (Homes.Home h : Homes.homes(id).values()) {
            if (Flats.isFlat(h) || h.members.isEmpty() || !StreetFurniture.wellOff(id, h)) continue;
            Ledger.Building b = Homes.building(id, h.anchor);
            if (b == null || !level.isLoaded(b.anchor())) continue;
            if (StreetFurniture.boxedCount(level, b) >= StreetFurniture.BOXES) continue;
            for (StreetFurniture.Box box : StreetFurniture.boxes(b)) {
                if (!StreetFurniture.boxed(level, box) && level.getBlockState(box.ledge()).isAir()) { n++; break; }
            }
            if (n >= 4) break;
        }
        return n;
    }

    static int wanted(ServerLevel level, Villages.Village v) {
        return WANTED.getOrDefault(v.id(), 0);
    }

    // ------------------------------------------------------------------ what they do

    /** The boxes in flower at this house (by its anchor), counted at most once a day. */
    static int inFlower(ServerLevel level, BlockPos anchor) {
        long day = level.getDayTime() / 24000L;
        long[] c = AT_HOUSE.get(anchor.asLong());
        if (c != null && c[0] == day) return (int) c[1];
        int n = 0;
        for (BlockPos p : WorkSites.boxesNear(level, anchor, 9)) {
            if (Math.abs(p.getY() - anchor.getY()) > 12 || !level.isLoaded(p)) continue;
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof WindowBoxBlock && s.getValue(WindowBoxBlock.BLOOM)) n++;
        }
        AT_HOUSE.put(anchor.asLong(), new long[]{ day, n });
        return n;
    }

    /** Its household's boxes in flower lift a folk's spirits a little (VillageFolkEntity.refreshMood). */
    public static int mood(VillageFolkEntity f, long day, int m, List<Object[]> why) {
        if (!(f.level() instanceof ServerLevel level) || f.isBaby()) return m;
        BlockPos home = Homes.homeOf(f);
        if (home == null || inFlower(level, home) == 0) return m;
        why.add(new Object[]{ "windowbox", MOOD });
        return m + MOOD;
    }

    /** How it puts it. */
    public static String moodWords(VillageFolkEntity f) {
        return FolkTalk.pick(f.getRandom(), "Our window boxes are a picture this week.", "I do like seeing the flowers under our window of a morning.");
    }

    /**
     * What a house is worth for its boxes in flower (HousingMarket.worth): four in the hundred more for each, two at the
     * most. As last counted (the town's rounds, a player's look): no world look here.
     */
    public static double premium(UUID village, BlockPos anchor) {
        long[] c = AT_HOUSE.get(anchor.asLong());
        int n = c == null ? 0 : (int) Math.min(MOST_COUNTED, c[1]);
        return 1.0 + n * WORTH_PERCENT / 100.0;
    }

    /** Tests: the boxes in flower at this house, counted now. */
    public static int inFlowerForTests(ServerLevel level, BlockPos anchor) {
        AT_HOUSE.remove(anchor.asLong());
        return inFlower(level, anchor);
    }

    /** Tests: this folk's errand ({hang?, where}) or null. */
    @Nullable
    public static Object[] errandForTests(VillageFolkEntity f) {
        Errand e = ERRANDS.get(f.getUUID());
        return e == null ? null : new Object[]{ e.hang, e.at };
    }

    /** Tests: the town's morning round of its boxes, now. */
    public static void tendForTests(ServerLevel level, Villages.Village v) {
        TENDED.remove(v.id());
        rounds(level, v);
    }

    /** The boxes' lines for /village items work. */
    static List<String> report(ServerLevel level, Villages.Village v) {
        List<String> out = new ArrayList<>();
        List<BlockPos> boxes = WorkSites.boxesNear(level, v.centre(), Villages.townReach(v.id()) + 8);
        int hung = WorkTools.count(v.id(), "work.boxes");
        if (boxes.isEmpty() && hung == 0) return out;
        int bloom = 0;
        for (BlockPos p : boxes) {
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof WindowBoxBlock && s.getValue(WindowBoxBlock.BLOOM)) bloom++;
        }
        out.add("Window boxes: " + boxes.size() + " under the town's windows, " + bloom + " in flower; " + hung + " hung by the households' gardeners and the builders.");
        return out;
    }
}
