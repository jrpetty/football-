package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.DraughtsBoardBlock;
import com.jrpetty.mcassistant.block.DraughtsBoardBlockEntity;
import com.jrpetty.mcassistant.block.PaperLanternBlock;
import com.jrpetty.mcassistant.block.QuiltBlock;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Buskers;
import com.jrpetty.mcassistant.entity.Culture;
import com.jrpetty.mcassistant.entity.Draughts;
import com.jrpetty.mcassistant.entity.Football;
import com.jrpetty.mcassistant.entity.FootballEntity;
import com.jrpetty.mcassistant.entity.Health;
import com.jrpetty.mcassistant.entity.Kickabout;
import com.jrpetty.mcassistant.entity.KiteEntity;
import com.jrpetty.mcassistant.entity.Kites;
import com.jrpetty.mcassistant.entity.Lanterns;
import com.jrpetty.mcassistant.entity.Lutes;
import com.jrpetty.mcassistant.entity.Makers;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Pastimes;
import com.jrpetty.mcassistant.entity.Persona;
import com.jrpetty.mcassistant.entity.Pitch;
import com.jrpetty.mcassistant.entity.Prices;
import com.jrpetty.mcassistant.entity.Purchases;
import com.jrpetty.mcassistant.entity.Quilts;
import com.jrpetty.mcassistant.entity.RecipeBook;
import com.jrpetty.mcassistant.entity.School;
import com.jrpetty.mcassistant.entity.Seasons;
import com.jrpetty.mcassistant.entity.Slates;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.Tiers;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.item.DyedShapedRecipe;
import com.jrpetty.mcassistant.item.KiteItem;
import com.jrpetty.mcassistant.item.LeisureItems;
import com.jrpetty.mcassistant.item.SlateItem;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [leisure] Home comforts and play (item/LeisureItems, entity/Pastimes): each of the seven made by its maker out of the
 * stores by its real recipe, and used, with its effect seen.
 * <ul>
 * <li><b>ls01</b>: the quilt's recipe wants three colours; the tailor makes one of the town's odd wool; a household buys
 *     it at the shop and lays it on its bed; a night under it is a happier morning (and a warm one in winter, a cold the
 *     shorter); the wedding's gift; a player lays one on a bed.</li>
 * <li><b>ls02</b>: the shop's workshop makes a lute for the town's buskers; a busker with no note block goes out with the
 *     stores' lute, holds it and plays it, and takes coins the readier for it than a note block's busker; home again
 *     the lute goes back; in a town with no maker a busker makes its own and keeps it; a player strums one.</li>
 * <li><b>ls03</b>: the workshop makes a draughts board, set on the tavern's table; two of the town play a real game to its
 *     end, the pieces moving on the board; the winner is the happier, the two the friendlier, the gazette has it; the
 *     sharper player wins the more; a player's challenge settled by skill and dice; the fair's tournament.</li>
 * <li><b>ls04</b>: the kite's recipe takes its dye's colour; the tailor makes a red one; on a kites afternoon a child
 *     flies the stores' kite high over its head, downwind; it is the happier for it; the kite goes back after; a player
 *     flies one and reels it in.</li>
 * <li><b>ls05</b>: the tailor makes a leather football (the Wood Age's); a real ball: it bounces and settles, rolls and
 *     slows, comes off a wall, and a punch kicks it; a player sets it down and picks it up.</li>
 * <li><b>ls06</b>: the children's kickabout: the football out of the stores onto the green, a pass from one to another,
 *     a player joins in; the children the happier; the ball back in the stores; the gazette.</li>
 * <li><b>ls07</b>: the league plays with the leather ball: on the centre spot, a player's goal counted to the player, a
 *     shot that rolls over the line counted; the ball back in the stores, the chronicle and the gazette.</li>
 * <li><b>ls08</b>: the sixteen lanterns' recipes, their light; the tailor makes two; a festival evening strung across the
 *     square out of the stores; the folk by them the happier; taken down after, every thing back.</li>
 * <li><b>ls09</b>: the slate is the Stone Age's; the workshop makes one; the teacher hands it out of the stores at the
 *     lesson, the lesson chalked on it and its chalk worn; a child with a slate learns about a quarter faster; grown
 *     up, the slate goes back; a player chalks on one.</li>
 * <li><b>ls10</b>: the seven in the books (a recipe, an age, a worth, the market's board, a maker named); the town's own
 *     bench makes what a town with neither tailor nor shop wants; the photographs' stage.</li>
 * </ul>
 * Each on its own ground (x 1,260,000 to 1,278,000, z 66,000), what it checks called directly.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class LeisureGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;
    private static final long DAY = 24000L * 3;

    // ------------------------------------------------------------------ the ground and the town

    private record Town(UUID id, Villages.Village v, BlockPos heart, List<Container> stores, List<VillageFolkEntity> folk) {}

    /** Flat grass about the heart, clear air above it. */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        BlockPos mid = Kit.surface(level, cx, cz);
        int y = mid.getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 24; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    /** A town of so many folk at x, in this age, at this time of day: the founders' chest emptied, four marked chests of
     *  stores by the heart for the test to fill, the pastimes' memory clean. */
    private static Town town(GameTestHelper helper, int x, int n, Villages.Age age, long time) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        Pastimes.resetForTests();
        level.setDayTime(time);
        level.updateSkyBrightness();
        level.setWeatherParameters(24000, 0, false, false);
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = flat(level, x, Z, 44);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.south(2 + i), 0.0F);
            helper.assertTrue(f != null && (folk.isEmpty() || folk.get(0).ownerId().equals(f.ownerId())), "folk " + i + " of the town");
            f.ensurePersona();
            folk.add(f);
        }
        UUID id = folk.get(0).ownerId();
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
        List<Container> stores = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            BlockPos at = new BlockPos(heart.getX() + 4 + 2 * k, heart.getY(), heart.getZ() - 4);
            level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
            ZoneChests.mark(level, at);
            stores.add((Container) level.getBlockEntity(at));
        }
        Villages.forgetStores(id);
        Villages.forgetStock();
        Villages.ageForTests(id, age);
        return new Town(id, Villages.get(id), heart, stores, folk);
    }

    /** These into the town's stores, as a player would bring them. */
    private static void stock(Town t, ItemStack... goods) {
        int g = 0;
        for (Container c : t.stores()) {
            for (int i = 0; i < c.getContainerSize() && g < goods.length; i++) {
                if (!c.getItem(i).isEmpty()) continue;
                c.setItem(i, goods[g++].copy());
            }
            c.setChanged();
        }
        if (g < goods.length) throw new IllegalStateException("the test's stores are full: " + (goods.length - g) + " over");
        Villages.forgetStock();
    }

    private static int stores(ServerLevel level, Town t, Predicate<ItemStack> what) {
        Villages.forgetStock();
        return Market.stock(level, t.id(), what);
    }

    private static int stores(ServerLevel level, Town t, Item it) {
        return stores(level, t, s -> s.is(it));
    }

    /** One of these taken out of the stores (a folk fetching it). */
    private static ItemStack fetch(ServerLevel level, Town t, Predicate<ItemStack> what) {
        for (BlockPos p : Villages.storeChests(level, t.id())) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !what.test(s)) continue;
                ItemStack one = s.split(1);
                c.setChanged();
                Villages.forgetStock();
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack[] many(Item it, int n) {
        List<ItemStack> out = new ArrayList<>();
        while (n > 0) {
            int k = Math.min(n, it.getDefaultMaxStackSize());
            out.add(new ItemStack(it, k));
            n -= k;
        }
        return out.toArray(new ItemStack[0]);
    }

    private static ItemStack[] join(ItemStack[]... parts) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack[] p : parts) out.addAll(List.of(p));
        return out.toArray(new ItemStack[0]);
    }

    /** A child of the town, a day old. */
    private static VillageFolkEntity child(GameTestHelper helper, Town t, BlockPos at) {
        ServerLevel level = helper.getLevel();
        VillageFolkEntity kid = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, at.getX(), at.getZ()), 0.0F);
        helper.assertTrue(kid != null && t.id().equals(kid.ownerId()), "a child of the town");
        kid.setChild(true);
        kid.bornDaysAgo(1);
        kid.ensurePersona();
        return kid;
    }

    /** A bed with its foot here, its head this way. Its head. */
    private static BlockPos bed(ServerLevel level, BlockPos foot, Direction facing) {
        BlockState st = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, facing);
        level.setBlock(foot, st.setValue(BedBlock.PART, BedPart.FOOT), 3);
        level.setBlock(foot.relative(facing), st.setValue(BedBlock.PART, BedPart.HEAD), 3);
        return foot.relative(facing);
    }

    /** A building of the town, stamped here facing north and on its books. */
    private static Ledger.Building building(ServerLevel level, UUID village, String structure, BlockPos at) {
        BlockPos p = Kit.surface(level, at.getX(), at.getZ());
        BuildGoal.stamp(level, structure, p, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, structure, p, Direction.NORTH);
        return new Ledger.Building(structure, p, Direction.NORTH);
    }

    /** A stand-in player as a survival player is (its stacks go down). Kit.noLeftoverPlayers after. */
    private static ServerPlayer survivor(GameTestHelper helper) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getAbilities().instabuild = false;
        p.getAbilities().invulnerable = false;
        return p;
    }

    /** The game's own recipe of ours by this name, or null. */
    private static CraftingRecipe recipe(ServerLevel level, String name) {
        Optional<RecipeHolder<?>> h = level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath("mc_assistant", name));
        return h.isPresent() && h.get().value() instanceof CraftingRecipe r ? r : null;
    }

    private static List<ItemStack> stacks(Item... items) {
        List<ItemStack> out = new ArrayList<>();
        for (Item i : items) out.add(i == Items.AIR ? ItemStack.EMPTY : new ItemStack(i));
        return out;
    }

    /** What its spirits are lifted by today, from the pastimes (as refreshMood asks them). */
    private static List<String> moodKeys(VillageFolkEntity f, long day) {
        List<Object[]> why = new ArrayList<>();
        Pastimes.mood(f, day, 0, why);
        List<String> out = new ArrayList<>();
        for (Object[] w : why) out.add((String) w[0]);
        return out;
    }

    private static boolean chronicled(UUID village, String words) {
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().toLowerCase(Locale.ROOT).contains(words.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static void at(ServerLevel level, long time) {
        level.setDayTime(time);
        level.updateSkyBrightness();
    }

    private static void moveTo(VillageFolkEntity f, BlockPos at) {
        f.getNavigation().stop();
        f.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
    }

    // ============================================================ ls01 the patchwork quilt

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ls01_patchwork_quilt")
    public static void ls01_patchwork_quilt(GameTestHelper helper) {
        Town t = town(helper, 1260000, 3, Villages.Age.WOOD, DAY + 2000);
        helper.runAtTickTime(5, () -> {
            ServerLevel level = helper.getLevel();
            Item quilt = LeisureItems.QUILT_ITEM.get();
            VillageFolkEntity tailor = t.folk().get(1), home = t.folk().get(2), spouse = t.folk().get(0);
            tailor.setJob(StationTask.TAILOR);
            // The recipe: six wool in two rows of three, of three colours at the least.
            CraftingRecipe r = recipe(level, "patchwork_quilt");
            helper.assertTrue(r != null, "the quilt's recipe is the game's");
            boolean three = r.matches(CraftingInput.of(3, 2, stacks(Items.RED_WOOL, Items.RED_WOOL, Items.BLUE_WOOL, Items.BLUE_WOOL,
                Items.YELLOW_WOOL, Items.YELLOW_WOOL)), level);
            boolean two = r.matches(CraftingInput.of(3, 2, stacks(Items.RED_WOOL, Items.RED_WOOL, Items.RED_WOOL, Items.BLUE_WOOL,
                Items.BLUE_WOOL, Items.BLUE_WOOL)), level);
            boolean one = r.matches(CraftingInput.of(3, 2, stacks(Items.WHITE_WOOL, Items.WHITE_WOOL, Items.WHITE_WOOL, Items.WHITE_WOOL,
                Items.WHITE_WOOL, Items.WHITE_WOOL)), level);
            Kit.log("ls01 the recipe: three colours " + three + ", two " + two + ", one " + one + "; age " + Tiers.of(level, quilt).label
                + ", worth " + Prices.each(quilt) + "; the benches' ways " + RecipeBook.waysFor(level, quilt).size());
            helper.assertTrue(three && !two && !one, "a quilt of three colours or more, never one or two");
            helper.assertTrue(r.getResultItem(level.registryAccess()).is(quilt), "it makes a quilt");
            helper.assertTrue(!RecipeBook.waysFor(level, quilt).isEmpty(), "the benches can read it");
            helper.assertTrue(Tiers.of(level, quilt) == Villages.Age.WOOD, "the Wood Age's: " + Tiers.of(level, quilt).label);
            helper.assertTrue(Prices.known(quilt) && Prices.each(quilt) > 0 && Market.goodFor(new ItemStack(quilt)) != null, "worth something, on the board");
            // The tailor makes one of the town's odd wool: the colours it has least of first.
            stock(t, new ItemStack(Items.RED_WOOL, 4), new ItemStack(Items.BLUE_WOOL, 4), new ItemStack(Items.YELLOW_WOOL, 2),
                new ItemStack(Items.GREEN_WOOL), new ItemStack(Items.LIME_WOOL));
            int woolBefore = stores(level, t, s -> s.is(ItemTags.WOOL));
            List<Item> six = Quilts.pickForTests(level, t.id());
            Kit.log("ls01 the six the tailor picks: " + six);
            helper.assertTrue(six.size() == 6 && new HashSet<>(six).size() >= 3, "six wool of three colours: " + six);
            helper.assertTrue(six.contains(Items.GREEN_WOOL) && six.contains(Items.LIME_WOOL), "the odd ends first: " + six);
            ItemStack made = Pastimes.makeForTests(level, tailor, quilt);
            int woolAfter = stores(level, t, s -> s.is(ItemTags.WOOL));
            Kit.log("ls01 the tailor made " + made + "; wool " + woolBefore + " -> " + woolAfter + "; quilts " + stores(level, t, quilt));
            helper.assertTrue(made.is(quilt) && stores(level, t, quilt) == 1, "a quilt into the stores");
            helper.assertTrue(woolBefore - woolAfter == 6, "six wool out of the stores for it: " + woolBefore + " -> " + woolAfter);
            helper.assertTrue(stores(level, t, Items.GREEN_WOOL) == 0 && stores(level, t, Items.LIME_WOOL) == 0, "the odd ends used up");
            // A household buys it at the shop, out of its own purse, and lays it on its bed.
            BlockPos head = bed(level, t.heart().offset(-6, 0, 6), Direction.NORTH);
            helper.assertTrue(home.claimBedNear(head) && head.equals(home.bedPos()), "its bed: " + home.bedPos());
            Purchases.openForTests(t.id(), true);
            home.earn(300);
            int purse = home.purse();
            boolean bought = Quilts.buyForTests(level, home);
            Kit.log("ls01 bought: " + bought + "; purse " + purse + " -> " + home.purse() + "; carried " + home.countCarried(s -> s.is(quilt)));
            helper.assertTrue(bought && home.countCarried(s -> s.is(quilt)) == 1 && stores(level, t, quilt) == 0, "bought, to take home");
            helper.assertTrue(home.purse() < purse, "paid for out of its own purse: " + purse + " -> " + home.purse());
            Purchases.openForTests(t.id(), null);
            at(level, DAY + 13500);
            moveTo(home, head.south(2));
            home.clearQueue();
            String doing = Quilts.holdForTests(level, home);
            Kit.log("ls01 at home: " + doing + "; quilted " + Quilts.quiltedForTests(level, head));
            helper.assertTrue(doing != null && doing.contains("laying a patchwork quilt"), "it lays it on the bed: " + doing);
            helper.assertTrue(Quilts.quiltedForTests(level, head) && level.getBlockState(head.south().above()).getBlock() instanceof QuiltBlock,
                "the quilt lies over the bed's foot");
            helper.assertTrue(home.countCarried(s -> s.is(quilt)) == 0, "out of its pack onto the bed");
            helper.assertTrue(Pastimes.cardLine(home) != null && Pastimes.cardLine(home).contains("sleeps under a patchwork quilt"),
                "its card: " + Pastimes.cardLine(home));
            // A night under it: the morning the happier; in winter warm as well, and a cold the shorter for it.
            long day = DAY / 24000L;
            int toWinter = Math.floorMod(21 - Seasons.dayOfYear(t.id(), day + 1), 28);
            long morning = day + 1 + toWinter;
            at(level, (morning - 1) * 24000L + 18000L);
            Health.catchForTests(home, "a test");
            int cold = home.health().coldLeft();
            home.startSleeping(head);
            boolean slept = Quilts.nightForTests(level, home, morning);
            int eased = home.health().coldLeft();
            home.stopSleeping();
            List<String> keys = moodKeys(home, morning);
            Kit.log("ls01 a winter's night under it (" + Seasons.season(t.id(), morning) + "): " + slept + "; cold " + cold + " -> " + eased
                + "; the morning's spirits " + keys + "; says: " + Pastimes.moodWords(home, "warmquilt"));
            helper.assertTrue(slept && keys.contains("quilt") && keys.contains("warmquilt"), "rested and warm: " + keys);
            helper.assertTrue(cold - eased == Quilts.MENDS_WINTER, "a cold a good deal the shorter: " + cold + " -> " + eased);
            helper.assertTrue(!moodKeys(spouse, morning).contains("quilt"), "one who did not sleep under it is not the happier for it");
            // The wedding's gift: a quilt out of the stores to the couple.
            stock(t, new ItemStack(quilt));
            boolean gift = Quilts.giftForTests(level, spouse, home);
            Kit.log("ls01 the wedding's gift: " + gift + "; " + spouse.displayNameCap() + " carries " + spouse.countCarried(s -> s.is(quilt)));
            helper.assertTrue(gift && spouse.countCarried(s -> s.is(quilt)) == 1 && stores(level, t, quilt) == 0, "given at the wedding");
            // A player lays one on a bed of its own.
            BlockPos head2 = bed(level, t.heart().offset(6, 0, 6), Direction.EAST);
            ServerPlayer p = survivor(helper);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(quilt, 2));
            ItemStack held = p.getMainHandItem();
            held.getItem().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(head2), Direction.UP, head2, false)));
            int left = p.getMainHandItem().getCount();
            Kit.noLeftoverPlayers(level);
            BlockState q = level.getBlockState(head2.west().above());
            Kit.log("ls01 a player's bed: " + q + "; left in hand " + left);
            helper.assertTrue(q.getBlock() instanceof QuiltBlock && q.getValue(QuiltBlock.FACING) == Direction.EAST, "laid over the player's bed, its way");
            helper.assertTrue(left == 1, "one out of the player's hand");
            helper.succeed();
        });
    }

    // ============================================================ ls02 the lute

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ls02_lute")
    public static void ls02_lute(GameTestHelper helper) {
        Town t = town(helper, 1262000, 5, Villages.Age.WOOD, DAY + 12500);
        helper.runAtTickTime(5, () -> {
            ServerLevel level = helper.getLevel();
            Item lute = LeisureItems.LUTE.get();
            VillageFolkEntity shop = t.folk().get(1), busker = t.folk().get(2), second = t.folk().get(3), listener = t.folk().get(4);
            shop.setJob(StationTask.SHOP);
            for (VillageFolkEntity f : t.folk()) {
                Culture.hobbyForTests(f, f == busker || f == second ? Persona.Hobby.MUSIC : Persona.Hobby.READING);
                f.removeMatching(s -> s.is(Items.NOTE_BLOCK) || s.is(lute), 64);
            }
            busker.setJob(StationTask.WOOD);
            second.setJob(StationTask.WOOD);
            listener.earn(10);
            helper.assertTrue(Tiers.of(level, lute) == Villages.Age.WOOD && Prices.known(lute) && Market.goodFor(new ItemStack(lute)) != null,
                "the Wood Age's, worth something, on the board");
            // The shop's workshop makes a lute for the buskers with none.
            Map<Item, Integer> want = Pastimes.wantedForTests(level, t.v());
            Kit.log("ls02 wanted: " + want);
            helper.assertTrue(want.getOrDefault(lute, 0) == 2, "a lute for each of the two buskers: " + want);
            // Timber enough that the Wood Age town is not putting every plank by for its age (Bench keeps all the planks
            // while the age is short of timber), as a town settled enough to have buskers has.
            stock(t, join(many(Items.OAK_LOG, 64), many(Items.OAK_PLANKS, 64), many(Items.STICK, 8), many(Items.STRING, 8),
                many(Items.CRAFTING_TABLE, 1)));
            String made = Pastimes.craftForTests(level, shop);
            Kit.log("ls02 the shop's workshop: " + made + "; lutes " + stores(level, t, lute) + ", string " + stores(level, t, Items.STRING));
            helper.assertTrue(made != null && made.contains("lute") && stores(level, t, lute) == 1, "a lute made, into the stores: " + made);
            helper.assertTrue(stores(level, t, Items.STRING) == 5, "three string for its strings: " + stores(level, t, Items.STRING));
            // A busker with no note block out with the stores' lute, lent for the evening (the shop shut: nothing to buy).
            Purchases.openForTests(t.id(), false);
            Buskers.recordForTests(t.id(), busker, 0, 0, 0, "by the well");
            Buskers.recordForTests(t.id(), second, 0, 0, 0, "by the well");
            String out = Buskers.startForTests(level, t.v(), busker);
            String stint = Buskers.stintForTests(busker.getUUID());
            Kit.log("ls02 out: " + out + " " + stint);
            helper.assertTrue(out != null && stint.contains("lute=true") && stint.contains("luteLent=true"), "out with the stores' lute: " + stint);
            helper.assertTrue(stores(level, t, lute) == 0 && busker.countCarried(Lutes::isLute) == 1, "the stores' lute in its hands");
            helper.assertTrue(Lutes.inHandForTests(busker), "held in its hand to play");
            int notes = Buskers.playForTests(level, busker, 32);
            helper.assertTrue(notes == 32, "its tune on the lute: " + notes);
            // The second busker: no lute left to lend, so the stores' note block (the second choice).
            stock(t, new ItemStack(Items.NOTE_BLOCK));
            String out2 = Buskers.startForTests(level, t.v(), second);
            String stint2 = Buskers.stintForTests(second.getUUID());
            Kit.log("ls02 the second: " + out2 + " " + stint2);
            helper.assertTrue(out2 != null && stint2.contains("lute=false") && stint2.contains("lent=true"), "on the stores' note block: " + stint2);
            // The same passer-by, the same mood: a coin for the lute, none for the note block.
            double adj = (listener.life().has(Social.Trait.GENEROUS) ? 0.2 : 0.0) - (listener.life().has(Social.Trait.GRUMPY) ? 0.15 : 0.0);
            double roll = 0.15 + adj + 0.05;
            int lp = listener.purse(), bp = busker.purse();
            boolean forNoteBlock = Buskers.listenForTests(level, second, listener, roll);
            boolean forLute = Buskers.listenForTests(level, busker, listener, roll);
            Kit.log("ls02 a coin at roll " + roll + ": for the note block " + forNoteBlock + ", for the lute " + forLute + "; purses " + lp + "->"
                + listener.purse() + ", busker " + bp + "->" + busker.purse());
            helper.assertTrue(forLute && !forNoteBlock, "a lute fills the hat the readier");
            helper.assertTrue(busker.purse() == bp + 1 && listener.purse() == lp - 1, "a coin out of the listener's purse into the busker's hat");
            // Home: the lent lute and note block back.
            Buskers.endForTests(level, busker);
            Buskers.endForTests(level, second);
            helper.assertTrue(stores(level, t, lute) == 1 && busker.countCarried(Lutes::isLute) == 0, "the stores' lute back in the stores");
            helper.assertTrue(stores(level, t, Items.NOTE_BLOCK) == 1, "and the note block");
            Purchases.openForTests(t.id(), null);
            // A player strums a tune.
            ServerPlayer p = survivor(helper);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(lute));
            p.getMainHandItem().use(level, p, InteractionHand.MAIN_HAND);
            boolean using = p.isUsingItem() && p.getUseItem().is(lute);
            boolean sounded = Lutes.strum(level, p, Lutes.TUNES[0], 0, 100, 1.0F);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(using && sounded, "a player strums it: using " + using + ", a note " + sounded);
            // A town with nobody to make lutes: a busker makes its own of an evening, and keeps it.
            shop.setJob(StationTask.FARM);
            fetch(level, t, Lutes::isLute);
            at(level, DAY + 13600);
            Lutes.tickForTests(level, t.v(), DAY / 24000L);
            VillageFolkEntity maker = busker.countCarried(Lutes::isLute) > 0 ? busker : second.countCarried(Lutes::isLute) > 0 ? second : null;
            Kit.log("ls02 no shop: " + (maker == null ? "nobody" : maker.displayNameCap()) + " made itself a lute; " + Pastimes.status(level, t.v())
                + "; " + Pastimes.shortForTests(t.id(), lute));
            helper.assertTrue(maker != null, "a busker made its own lute");
            helper.assertTrue(Pastimes.cardLine(maker) != null && Pastimes.cardLine(maker).contains("plays a lute of its own"), "its card: " + Pastimes.cardLine(maker));
            helper.assertTrue(stores(level, t, lute) == 0, "its own, not the stores'");
            String own = Buskers.startForTests(level, t.v(), maker);
            String ownStint = Buskers.stintForTests(maker.getUUID());
            helper.assertTrue(own != null && ownStint.contains("lute=true") && ownStint.contains("luteLent=false"), "out with its own lute: " + ownStint);
            Buskers.endForTests(level, maker);
            helper.assertTrue(maker.countCarried(Lutes::isLute) == 1 && stores(level, t, lute) == 0, "and home with it");
            helper.succeed();
        });
    }

    // ============================================================ ls03 the draughts board

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ls03_draughts")
    public static void ls03_draughts(GameTestHelper helper) {
        Town t = town(helper, 1264000, 6, Villages.Age.WOOD, DAY + 2000);
        helper.runAtTickTime(5, () -> {
            ServerLevel level = helper.getLevel();
            Item boardItem = LeisureItems.DRAUGHTS_BOARD_ITEM.get();
            VillageFolkEntity shop = t.folk().get(5);
            shop.setJob(StationTask.SHOP);
            Ledger.Building tav = building(level, t.id(), "tavern", t.heart().offset(-16, 0, 14));
            helper.assertTrue(Tiers.of(level, boardItem) == Villages.Age.WOOD && Prices.known(boardItem), "the Wood Age's, worth something");
            // The workshop makes one for the tavern's table.
            Map<Item, Integer> want = Pastimes.wantedForTests(level, t.v());
            helper.assertTrue(want.getOrDefault(boardItem, 0) == 1, "a board for the tavern: " + want);
            // Timber enough that the age is not holding every plank back (Bench), as a town with a tavern has.
            stock(t, join(many(Items.OAK_LOG, 64), many(Items.OAK_PLANKS, 60), many(Items.INK_SAC, 2), many(Items.BONE_MEAL, 2),
                many(Items.CRAFTING_TABLE, 1)));
            String made = Pastimes.craftForTests(level, shop);
            Kit.log("ls03 the workshop: " + made + "; boards " + stores(level, t, boardItem) + ", ink " + stores(level, t, Items.INK_SAC)
                + ", bone meal " + stores(level, t, Items.BONE_MEAL));
            helper.assertTrue(made != null && made.contains("draughts board") && stores(level, t, boardItem) == 1, "a board made: " + made);
            helper.assertTrue(stores(level, t, Items.INK_SAC) == 1 && stores(level, t, Items.BONE_MEAL) == 1, "the black and the white out of the stores");
            // Set out on the tavern's table, its cloth into the stores.
            BlockPos post = Draughts.tavernTableForTests(level, tav);
            helper.assertTrue(post != null, "the tavern has a table for it");
            Draughts.setUpForTests(level, t.v());
            BlockPos board = post.above();
            Kit.log("ls03 the table at " + post.toShortString() + ": " + level.getBlockState(board) + "; carpets in the stores "
                + stores(level, t, s -> s.is(ItemTags.WOOL_CARPETS)));
            helper.assertTrue(level.getBlockState(board).getBlock() instanceof DraughtsBoardBlock && stores(level, t, boardItem) == 0,
                "the board on the tavern's table");
            // A game between two of the town, at the benches.
            VillageFolkEntity red = t.folk().get(1), black = t.folk().get(2);
            moveTo(red, post.north(2));
            moveTo(black, post.south(2));
            long day = DAY / 24000L;
            int warmth = red.life().affinity(black.getUUID());
            int[] winner = { 0 };
            for (int game = 0; game < 4 && winner[0] == 0; game++) {
                String began = Draughts.beginForTests(level, board, red, black);
                helper.assertTrue(began != null, "a game begun at its benches");
                helper.assertTrue(level.getBlockEntity(board) instanceof DraughtsBoardBlockEntity be0 && Arrays.equals(be0.cells(),
                    DraughtsBoardBlockEntity.start()), "the men set out");
                int plies = Draughts.playForTests(level, board, 400);
                boolean moved = level.getBlockEntity(board) instanceof DraughtsBoardBlockEntity be && !Arrays.equals(be.cells(), DraughtsBoardBlockEntity.start());
                int[] rr = Draughts.recordForTests(t.id(), red.getUUID()), rb = Draughts.recordForTests(t.id(), black.getUUID());
                Kit.log("ls03 game " + (game + 1) + ": " + plies + " moves; " + red.displayNameCap() + " " + Arrays.toString(rr) + ", "
                    + black.displayNameCap() + " " + Arrays.toString(rb));
                helper.assertTrue(Draughts.gameForTests(board) == null && plies > 0 && plies <= Draughts.LONGEST, "played to its end: " + plies);
                helper.assertTrue(moved, "the men moved on the board for all to see");
                helper.assertTrue(rr[0] + rr[1] + rr[2] == game + 1 && rb[0] + rb[1] + rb[2] == game + 1, "each game on their records");
                if (rr[0] > 0) winner[0] = 1;
                else if (rb[0] > 0) winner[0] = 2;
            }
            helper.assertTrue(winner[0] != 0, "a game won in four");
            VillageFolkEntity won = winner[0] == 1 ? red : black, lost = won == red ? black : red;
            Kit.log("ls03 " + won.displayNameCap() + " won: spirits " + moodKeys(won, day) + " (" + Pastimes.moodWords(won, "draughts") + "); "
                + lost.displayNameCap() + " " + moodKeys(lost, day) + "; warmth " + warmth + " -> " + red.life().affinity(black.getUUID()));
            helper.assertTrue(moodKeys(won, day).contains("draughts"), "the winner the happier");
            helper.assertTrue(red.life().affinity(black.getUUID()) >= warmth + 3, "the two the friendlier for a game");
            helper.assertTrue(Pastimes.cardLine(won) != null && Pastimes.cardLine(won).contains("draughts"), "its card: " + Pastimes.cardLine(won));
            String gazette = Pastimes.gazette(level, t.id(), day + 1);
            helper.assertTrue(gazette != null && gazette.contains("at draughts"), "tomorrow's gazette: " + gazette);
            // The sharper player wins the more.
            int[] series = Draughts.seriesForTests(14, 1, 30, 7L), closer = Draughts.seriesForTests(10, 4, 30, 7L);
            Kit.log("ls03 thirty games, the sharpest against a dullard: " + Arrays.toString(series) + "; a sharp one against a middling one: "
                + Arrays.toString(closer));
            helper.assertTrue(series[0] > series[1] * 2, "the cleverer wins the more: " + Arrays.toString(series));
            helper.assertTrue(closer[0] > closer[1], "and the sharper of two ordinary players: " + Arrays.toString(closer));
            // A player's challenge: a short game, then skill and the dice, said aloud.
            ServerPlayer p = survivor(helper);
            p.moveTo(post.getX() + 0.5, post.getY(), post.getZ() + 3.5);
            String said = Draughts.challenge(level, board, p);
            int before = 0;
            VillageFolkEntity opponent = null;
            for (VillageFolkEntity f : t.folk()) {
                if (said.startsWith(f.displayNameCap())) opponent = f;
            }
            helper.assertTrue(opponent != null, "one of the town sits down with the player: " + said);
            int[] o0 = Draughts.recordForTests(t.id(), opponent.getUUID());
            before = o0[0] + o0[1] + o0[2];
            int result = Draughts.challengeOutForTests(level, board);
            int[] o1 = Draughts.recordForTests(t.id(), opponent.getUUID());
            Kit.noLeftoverPlayers(level);
            Kit.log("ls03 the challenge: " + said + "; result " + result + "; " + opponent.displayNameCap() + " " + Arrays.toString(o1));
            helper.assertTrue(result == Draughts.RED || result == Draughts.BLACK || result == 0, "settled: " + result);
            helper.assertTrue(o1[0] + o1[1] + o1[2] == before + 1, "on its record");
            // The fair's tournament: the champion in the chronicle, its purse out of the treasury.
            Ledger.addCoins(t.id(), 10);
            List<String> lines = Draughts.tournamentForTests(level, t.v());
            Kit.log("ls03 the tournament: " + lines);
            helper.assertTrue(lines.size() == 3 && lines.get(2).contains("champion"), "two semi-finals and a final");
            helper.assertTrue(chronicled(t.id(), "won the draughts tournament at the fair"), "the champion in the chronicle");
            helper.succeed();
        });
    }

    // ============================================================ ls04 the kite

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ls04_kite")
    public static void ls04_kite(GameTestHelper helper) {
        Town t = town(helper, 1266000, 2, Villages.Age.WOOD, DAY + 8000);
        VillageFolkEntity[] kids = new VillageFolkEntity[2];
        helper.runAtTickTime(5, () -> {
            ServerLevel level = helper.getLevel();
            Item kite = LeisureItems.KITE.get();
            VillageFolkEntity tailor = t.folk().get(1);
            tailor.setJob(StationTask.TAILOR);
            kids[0] = child(helper, t, t.heart().offset(5, 0, 5));
            kids[1] = child(helper, t, t.heart().offset(-5, 0, 5));
            Kites.windForTests(2.2);
            // Its recipe takes the dye's colour.
            CraftingRecipe r = recipe(level, "kite");
            helper.assertTrue(r != null, "the kite's recipe is the game's");
            CraftingInput blue = CraftingInput.of(3, 3, stacks(Items.AIR, Items.PAPER, Items.AIR, Items.PAPER, Items.STICK, Items.PAPER,
                Items.BLUE_DYE, Items.STICK, Items.STRING));
            ItemStack blueKite = r.matches(blue, level) ? r.assemble(blue, level.registryAccess()) : ItemStack.EMPTY;
            helper.assertTrue(blueKite.is(kite) && KiteItem.colour(blueKite) == DyedShapedRecipe.rgb(DyeColor.BLUE), "a blue dye makes a blue kite");
            helper.assertTrue(Tiers.of(level, kite) == Villages.Age.WOOD && Prices.known(kite), "the Wood Age's, worth something");
            // The tailor makes one for the children, in the colour the stores have the dye for.
            Map<Item, Integer> want = Pastimes.wantedForTests(level, t.v());
            helper.assertTrue(want.getOrDefault(kite, 0) == 1, "a kite between the two children: " + want);
            stock(t, join(many(Items.PAPER, 6), many(Items.STICK, 4), many(Items.STRING, 4), many(Items.RED_DYE, 1), many(Items.CRAFTING_TABLE, 1)));
            ItemStack made = Pastimes.makeForTests(level, tailor, kite);
            Kit.log("ls04 the tailor made " + made + " (" + Integer.toHexString(KiteItem.colour(made)) + "); paper " + stores(level, t, Items.PAPER)
                + ", red dye " + stores(level, t, Items.RED_DYE));
            helper.assertTrue(made.is(kite) && KiteItem.colour(made) == DyedShapedRecipe.rgb(DyeColor.RED), "a red kite");
            helper.assertTrue(stores(level, t, kite) == 1 && stores(level, t, Items.PAPER) == 3 && stores(level, t, Items.RED_DYE) == 0,
                "out of the stores' paper and dye, into the stores");
            // A kites afternoon: the first child takes the stores' kite out, runs to its place, and sends it up.
            Pastimes.afternoonForTests(level, t.id(), Pastimes.KITES);
            String first = Kites.holdForTests(level, kids[0]);
            helper.assertTrue(stores(level, t, kite) == 0 && kids[0].countCarried(Kites::isKite) == 1, "the stores' kite lent to it: " + first);
            moveTo(kids[0], Kites.placeForTests(level, kids[0]));
            String flying = Kites.holdForTests(level, kids[0]);
            KiteEntity k = Kites.flyingForTests(level, kids[0].getUUID());
            String other = Kites.holdForTests(level, kids[1]);
            Kit.log("ls04 " + kids[0].displayNameCap() + ": " + flying + "; the kite " + (k == null ? "none" : k.blockPosition().toShortString())
                + "; " + kids[1].displayNameCap() + ": " + other);
            helper.assertTrue(flying != null && flying.contains("flying a red kite"), "flying it: " + flying);
            helper.assertTrue(k != null && k.colour() == DyedShapedRecipe.rgb(DyeColor.RED), "the red kite up");
            helper.assertTrue(other != null && other.contains("watching the kites"), "the one with none watches: " + other);
        });
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            if (tick > 5 && tick < 60 && tick % 5 == 0 && kids[0] != null) Kites.holdForTests(helper.getLevel(), kids[0]);
        });
        helper.runAtTickTime(60, () -> {
            ServerLevel level = helper.getLevel();
            Item kite = LeisureItems.KITE.get();
            VillageFolkEntity kid = kids[0];
            KiteEntity k = Kites.flyingForTests(level, kid.getUUID());
            helper.assertTrue(k != null, "still up");
            double high = k.getY() - kid.getY(), off = Math.sqrt(k.distanceToSqr(kid.getX(), k.getY(), kid.getZ()));
            long day = DAY / 24000L;
            Kit.log("ls04 the kite " + String.format(Locale.ROOT, "%.1f", high) + " blocks over its head, " + String.format(Locale.ROOT, "%.1f", off)
                + " downwind; spirits " + moodKeys(kid, day) + "; card " + Pastimes.cardLine(kid));
            helper.assertTrue(high > 5.0 && off > 3.0, "high over its head and downwind: " + high + ", " + off);
            helper.assertTrue(moodKeys(kid, day).contains("kite"), "the child the happier for it");
            helper.assertTrue(Pastimes.cardLine(kid) != null && Pastimes.cardLine(kid).contains("flying a red kite"), "its card: " + Pastimes.cardLine(kid));
            String gazette = Pastimes.gazette(level, t.id(), day + 1);
            helper.assertTrue(gazette != null && gazette.contains("kites"), "tomorrow's gazette: " + gazette);
            // The afternoon over: reeled in, and the stores' kite back.
            Pastimes.afternoonForTests(level, t.id(), Pastimes.GAMES);
            Kites.tickForTests(level, t.v());
            helper.assertTrue(Kites.flyingForTests(level, kid.getUUID()) == null && !k.isAlive(), "reeled in");
            helper.assertTrue(stores(level, t, kite) == 1 && kid.countCarried(Kites::isKite) == 0, "the kite back in the stores");
            // A player flies one, and reels it in.
            ServerPlayer p = survivor(helper);
            ItemStack mine = new ItemStack(kite);
            mine.set(DataComponents.DYED_COLOR, new DyedItemColor(DyedShapedRecipe.rgb(DyeColor.YELLOW), true));
            p.setItemInHand(InteractionHand.MAIN_HAND, mine);
            String up = Kites.fromHand(level, p, p.getMainHandItem());
            KiteEntity pk = Kites.flyingForTests(level, p.getUUID());
            String down = Kites.fromHand(level, p, p.getMainHandItem());
            boolean gone = Kites.flyingForTests(level, p.getUUID()) == null;
            Kit.noLeftoverPlayers(level);
            Kites.windForTests(null);
            Kit.log("ls04 a player: " + up + " / " + down);
            helper.assertTrue(up.startsWith("Up it goes") && pk != null && pk.colour() == DyedShapedRecipe.rgb(DyeColor.YELLOW), "a player's kite up: " + up);
            helper.assertTrue(down.contains("reel") && gone, "and reeled in");
            helper.succeed();
        });
    }

    // ============================================================ ls05 the leather football: made, and a real ball

    @GameTest(template = EMPTY, timeoutTicks = 260, batch = "ls05_football")
    public static void ls05_football(GameTestHelper helper) {
        Town t = town(helper, 1268000, 2, Villages.Age.WOOD, DAY + 2000);
        FootballEntity[] ball = new FootballEntity[1];
        BlockPos[] spot = new BlockPos[1];
        double[] track = { Double.MAX_VALUE, -1, 0, 0 };          // the lowest it fell to, the highest after, its x at the kick, the furthest
        boolean[] landed = { false };
        helper.runAtTickTime(5, () -> {
            ServerLevel level = helper.getLevel();
            Item football = LeisureItems.LEATHER_FOOTBALL.get();
            VillageFolkEntity tailor = t.folk().get(1);
            tailor.setJob(StationTask.TAILOR);
            helper.assertTrue(Tiers.of(level, football) == Villages.Age.WOOD, "the Wood Age's (a hunter's hides): " + Tiers.of(level, football).label);
            helper.assertTrue(Prices.known(football) && Market.goodFor(new ItemStack(football)) != null, "worth something, on the board");
            stock(t, join(many(Items.LEATHER, 6), many(Items.WHITE_WOOL, 5), many(Items.CRAFTING_TABLE, 1)));
            ItemStack made = Pastimes.makeForTests(level, tailor, football);
            Kit.log("ls05 the tailor made " + made + "; leather " + stores(level, t, Items.LEATHER) + ", wool " + stores(level, t, Items.WHITE_WOOL));
            helper.assertTrue(made.is(football) && stores(level, t, football) == 1, "a football into the stores");
            helper.assertTrue(stores(level, t, Items.LEATHER) == 2 && stores(level, t, Items.WHITE_WOOL) == 4, "four leather round a wool");
            // A ball dropped from three blocks up, well away from anybody.
            Kit.live(level, t.heart().getX(), Z, 40);
            spot[0] = Kit.surface(level, t.heart().getX() + 20, Z + 20);
            // Its ground made bare: nothing grown or put there by now (a bush or a web holds a ball fast), all along where
            // it will roll and past the wall it will come off.
            for (int dx = -3; dx <= 18; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos c = spot[0].offset(dx, 0, dz);
                    level.setBlock(c.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 2 | 16);
                    for (int dy = 0; dy < 6; dy++) level.setBlock(c.above(dy), Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
            Kit.log("ls05 the drop's ground: " + level.getBlockState(spot[0].below()) + " under " + level.getBlockState(spot[0]));
            ball[0] = FootballEntity.setDown(level, spot[0].getX() + 0.5, spot[0].getY() + 3.0, spot[0].getZ() + 0.5, new ItemStack(football), null);
            helper.assertTrue(ball[0] != null, "a ball set down");
        });
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            FootballEntity b = ball[0];
            if (b == null || tick <= 5) return;
            if (tick < 45) {
                if (tick % 5 == 0) Kit.log("ls05 tick " + tick + ": " + String.format(Locale.ROOT, "%.3f", b.getY() - spot[0].getY()) + " up, going "
                    + b.getDeltaMovement() + ", on the ground " + b.onGround() + ", alive " + b.isAlive() + ", in " + helper.getLevel().getBlockState(b.blockPosition()));
                // Down to the grass (the lowest it has been), then up off it again: the highest it rose over its low point.
                if (b.onGround() || b.getY() - spot[0].getY() < 0.05) landed[0] = true;
                track[0] = Math.min(track[0], b.getY());
                if (landed[0]) track[1] = Math.max(track[1], b.getY() - track[0]);
            }
            if (tick > 130 && tick < 160) track[3] = Math.max(track[3], b.getX());
        });
        helper.runAtTickTime(45, () -> {
            FootballEntity b = ball[0];
            double ground = spot[0].getY();
            Kit.log("ls05 dropped: landed " + landed[0] + ", up again to " + String.format(Locale.ROOT, "%.2f", track[1]) + " off the grass; lying at "
                + String.format(Locale.ROOT, "%.3f", b.getY() - ground) + ", moving " + b.getDeltaMovement());
            helper.assertTrue(landed[0] && track[1] > 0.3 && Math.abs(track[0] - ground) < 0.05, "it came down and bounced: up " + track[1]
                + " off its lowest, " + (track[0] - ground));
            helper.assertTrue(Math.abs(b.getY() - ground) < 0.05 && b.onGround(), "and settled on the grass: " + (b.getY() - ground));
            // Kicked along the grass: it rolls, and slows.
            track[2] = b.getX();
            b.kick(new Vec3(0.5, 0.0, 0.0), null);
        });
        helper.runAtTickTime(55, () -> {
            FootballEntity b = ball[0];
            double went = b.getX() - track[2], speed = b.getDeltaMovement().horizontalDistance();
            Kit.log("ls05 ten ticks after the kick: " + String.format(Locale.ROOT, "%.2f", went) + " blocks, going " + String.format(Locale.ROOT, "%.3f", speed));
            helper.assertTrue(went > 2.0 && speed < 0.4 && speed > 0.0, "rolling, slowing: " + went + ", " + speed);
        });
        helper.runAtTickTime(130, () -> {
            ServerLevel level = helper.getLevel();
            FootballEntity b = ball[0];
            double went = b.getX() - track[2], speed = b.getDeltaMovement().horizontalDistance();
            Kit.log("ls05 at rest: " + String.format(Locale.ROOT, "%.2f", went) + " blocks in all, going " + speed);
            helper.assertTrue(speed == 0.0 && went > 4.0 && went < 9.0, "come to rest on the grass: " + went + ", " + speed);
            // A wall two blocks on: it comes off it.
            BlockPos wall = b.blockPosition().east(2);
            for (int dz = -2; dz <= 2; dz++) for (int dy = 0; dy < 2; dy++) level.setBlock(wall.offset(0, dy, dz), Blocks.STONE.defaultBlockState(), 3);
            track[3] = b.getX();
            b.kick(new Vec3(0.45, 0.0, 0.0), null);
        });
        helper.runAtTickTime(160, () -> {
            ServerLevel level = helper.getLevel();
            FootballEntity b = ball[0];
            Kit.log("ls05 off the wall: furthest " + String.format(Locale.ROOT, "%.2f", track[3]) + ", now " + String.format(Locale.ROOT, "%.2f", b.getX()));
            helper.assertTrue(b.getX() < track[3] - 0.3, "it came back off the wall: " + track[3] + " -> " + b.getX());
            // Punched: kicked the way the player looks, and the touch is the player's.
            ServerPlayer p = survivor(helper);
            p.moveTo(b.getX(), b.getY(), b.getZ() - 2.0, 0.0F, 0.0F);
            b.skipAttackInteraction(p);
            Vec3 v = b.getDeltaMovement();
            UUID by = b.lastKicker();
            // A player sets a ball of its own down, and picks it up again.
            BlockPos ground = Kit.surface(level, t.heart().getX() - 20, Z - 20).below();
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LeisureItems.LEATHER_FOOTBALL.get()));
            p.getMainHandItem().getItem().useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(ground), Direction.UP,
                ground, false)));
            List<FootballEntity> down = level.getEntitiesOfClass(FootballEntity.class, new AABB(ground).inflate(2.0));
            boolean setDown = !down.isEmpty() && p.getMainHandItem().isEmpty();
            boolean pickedUp = false;
            if (!down.isEmpty()) {
                p.setShiftKeyDown(true);
                down.get(0).interact(p, InteractionHand.MAIN_HAND);
                pickedUp = !down.get(0).isAlive() && p.getInventory().countItem(LeisureItems.LEATHER_FOOTBALL.get()) == 1;
            }
            UUID pid = p.getUUID();
            Kit.noLeftoverPlayers(level);
            Kit.log("ls05 punched: " + v + " by " + by + "; set down " + setDown + ", picked up " + pickedUp);
            helper.assertTrue(v.z > 0.6 && v.y > 0.05 && pid.equals(by), "punched the way the player looks, the touch the player's: " + v);
            helper.assertTrue(setDown && pickedUp, "a player's ball set down and picked up");
            helper.succeed();
        });
    }

    // ============================================================ ls06 the children's kickabout

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ls06_kickabout")
    public static void ls06_kickabout(GameTestHelper helper) {
        Town t = town(helper, 1270000, 2, Villages.Age.WOOD, DAY + 8000);
        helper.runAtTickTime(5, () -> {
            ServerLevel level = helper.getLevel();
            Item football = LeisureItems.LEATHER_FOOTBALL.get();
            VillageFolkEntity a = child(helper, t, t.heart().offset(9, 0, 9)), b = child(helper, t, t.heart().offset(-9, 0, 9));
            Map<Item, Integer> want = Pastimes.wantedForTests(level, t.v());
            helper.assertTrue(want.getOrDefault(football, 0) == 1, "a football for the two children's kickabouts: " + want);
            stock(t, new ItemStack(football));
            Pastimes.afternoonForTests(level, t.id(), Pastimes.BALL);
            String sa = Kickabout.holdForTests(level, a);
            String sb = Kickabout.holdForTests(level, b);
            FootballEntity ball = Kickabout.ballForTests(level, t.id());
            Kit.log("ls06 the kickabout: " + sa + " / " + sb + "; the ball " + (ball == null ? "none" : ball.blockPosition().toShortString()));
            helper.assertTrue(ball != null && stores(level, t, football) == 0, "the town's football out of the stores, down on the green");
            helper.assertTrue(ball.town() != null && ball.town().equals(t.id()), "the town's ball");
            // The nearest at the ball passes it on to the other.
            int passes = Kickabout.tallyForTests(t.id())[0];
            a.teleportTo(ball.getX() - 0.7, ball.getY(), ball.getZ());
            b.teleportTo(ball.getX() + 7.0, ball.getY(), ball.getZ());
            String kick = Kickabout.holdForTests(level, a);
            int[] tally = Kickabout.tallyForTests(t.id());
            Vec3 v = ball.getDeltaMovement();
            Kit.log("ls06 " + a.displayNameCap() + ": " + kick + "; " + Arrays.toString(tally) + "; the ball going " + v);
            helper.assertTrue(kick != null && kick.contains("passing") && tally[0] == passes + 1 && tally[1] == 2, "a pass: " + kick);
            helper.assertTrue(v.horizontalDistance() > 0.15 && v.x > 0 && a.getUUID().equals(ball.lastKicker()), "the ball sent toward the other: " + v);
            // A player joins in with a punch at it.
            ServerPlayer p = survivor(helper);
            p.moveTo(ball.getX(), ball.getY(), ball.getZ() - 2.0, 0.0F, 0.0F);
            ball.skipAttackInteraction(p);
            int[] after = Kickabout.tallyForTests(t.id());
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(after[2] == 1, "the player in the kickabout: " + Arrays.toString(after));
            long day = DAY / 24000L;
            helper.assertTrue(moodKeys(a, day).contains("kickabout") && moodKeys(b, day).contains("kickabout"), "the children the happier for it");
            // The afternoon over: the ball back in the stores, the gazette tomorrow.
            Kickabout.endForTests(level, t.id());
            String gazette = Pastimes.gazette(level, t.id(), day + 1);
            Kit.log("ls06 over: footballs in the stores " + stores(level, t, football) + "; " + gazette);
            helper.assertTrue(!ball.isAlive() && stores(level, t, football) == 1, "the ball back in the stores");
            helper.assertTrue(gazette != null && gazette.contains("kickabout"), "tomorrow's gazette: " + gazette);
            helper.succeed();
        });
    }

    // ============================================================ ls07 the league's match with the leather ball

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ls07_match")
    public static void ls07_match(GameTestHelper helper) {
        Town t = town(helper, 1272000, 4, Villages.Age.WOOD, DAY + 4000);
        Ledger.Building[] pitch = new Ledger.Building[1];
        int[] phase = { 0 };
        long[] at = { 0 };
        helper.runAtTickTime(5, () -> {
            ServerLevel level = helper.getLevel();
            pitch[0] = Pitch.putUp(level, t.id(), t.heart().offset(0, 0, 24), Direction.NORTH);
            helper.assertTrue(pitch[0] != null, "the town's pitch");
            stock(t, new ItemStack(LeisureItems.LEATHER_FOOTBALL.get()));
            String on = Football.startForTests(level, t.v(), t.folk().subList(0, 2), "the North End", t.folk().subList(2, 4), "the South End", true);
            helper.assertTrue(on != null && stores(level, t, LeisureItems.LEATHER_FOOTBALL.get()) == 0, "a match, the leather ball out of the stores");
        });
        helper.onEachTick(() -> {
            ServerLevel level = helper.getLevel();
            long tick = helper.getTick();
            if (tick < 30) return;
            Item football = LeisureItems.LEATHER_FOOTBALL.get();
            Ledger.Building b = pitch[0];
            if (phase[0] == 0) {
                helper.assertTrue(Football.kickOffForTests(level, t.id()), "the kick-off");
                helper.assertTrue(Football.ballEntityForTests(level, t.id()) instanceof FootballEntity, "the leather ball, a real one");
                FootballEntity fb = (FootballEntity) Football.ballEntityForTests(level, t.id());
                double[] uw = Pitch.local(b, fb.position());
                helper.assertTrue(Math.abs(uw[0]) < 0.6 && Math.abs(uw[1]) < 0.6, "on the centre spot: " + uw[0] + ", " + uw[1]);
                phase[0] = 1;
                return;
            }
            if (phase[0] == 1) {
                // A player's touch, and the ball in the back of the net: the player's goal.
                FootballEntity fb = (FootballEntity) Football.ballEntityForTests(level, t.id());
                ServerPlayer p = survivor(helper);
                Vec3 near = Pitch.point(b, 0.0, 6.0);
                p.moveTo(near.x, near.y, near.z, 0.0F, 0.0F);
                fb.kick(new Vec3(0.0, 0.0, 0.0), p);
                Vec3 in = Pitch.point(b, 0.2, 7.9);
                fb.moveTo(in.x, in.y + 0.1, in.z);
                fb.setDeltaMovement(Vec3.ZERO);
                Football.refereeForTests(level, t.id());
                int[] score = Football.scoreForTests(t.id());
                double[] back = Pitch.local(b, fb.position());
                Kit.noLeftoverPlayers(level);
                Kit.log("ls07 a player's goal: " + score[0] + "-" + score[1] + "; the ball back at " + back[0] + ", " + back[1]);
                helper.assertTrue(score[0] == 1 && score[1] == 0, "a goal to the home side: " + score[0] + "-" + score[1]);
                helper.assertTrue(Math.abs(back[0]) < 0.6 && Math.abs(back[1]) < 0.6, "the ball back on the centre spot");
                phase[0] = 2;
                at[0] = tick;
                return;
            }
            if (phase[0] == 2) {
                if (tick - at[0] < 5) return;
                // A shot of a home player's from the edge of the box: it rolls over the line by itself.
                FootballEntity fb = (FootballEntity) Football.ballEntityForTests(level, t.id());
                Vec3 from = Pitch.point(b, 0.0, 5.0), to = Pitch.point(b, 0.0, 9.0);
                fb.moveTo(from.x, from.y + 0.05, from.z);
                Vec3 dir = to.subtract(from).normalize();
                fb.kick(new Vec3(dir.x * 0.5, 0.05, dir.z * 0.5), t.folk().get(0));
                phase[0] = 3;
                at[0] = tick;
                return;
            }
            if (phase[0] == 3) {
                int[] score = Football.scoreForTests(t.id());
                if (score == null) { helper.fail("ls07 the match ended early: " + Football.lastForTests(t.id())); return; }
                if (score[0] < 2 && tick - at[0] < 60) return;
                Kit.log("ls07 the shot: " + score[0] + "-" + score[1] + " after " + (tick - at[0]) + " ticks");
                helper.assertTrue(score[0] == 2, "the shot rolled in: " + score[0] + "-" + score[1]);
                Football.endForTests(level, t.id());
                String last = Football.lastForTests(t.id());
                long day = level.getDayTime() / 24000L;
                String gazette = Pastimes.gazette(level, t.id(), day + 1);
                Kit.log("ls07 the final whistle: " + last + "; the gazette: " + gazette);
                helper.assertTrue(last != null && last.contains("test-mock-player") && last.contains(t.folk().get(0).displayNameCap()),
                    "the scorers by name, the player's among them: " + last);
                helper.assertTrue(stores(level, t, football) == 1 && Football.ballEntityForTests(level, t.id()) == null, "the ball back in the stores");
                helper.assertTrue(chronicled(t.id(), "on the football pitch"), "the result in the chronicle");
                helper.assertTrue(gazette != null && gazette.contains("on the football pitch"), "and in tomorrow's gazette");
                helper.succeed();
            }
        });
    }

    // ============================================================ ls08 the paper lanterns

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ls08_lanterns")
    public static void ls08_lanterns(GameTestHelper helper) {
        Town t = town(helper, 1274000, 3, Villages.Age.WOOD, DAY + 2000);
        helper.runAtTickTime(5, () -> {
            ServerLevel level = helper.getLevel();
            VillageFolkEntity tailor = t.folk().get(1);
            tailor.setJob(StationTask.TAILOR);
            // Sixteen colours, sixteen recipes: paper, a torch and a dye make two; a light of twelve; the Wood Age's.
            for (DyeColor c : DyeColor.values()) {
                Item l = LeisureItems.lantern(c);
                CraftingRecipe r = recipe(level, c.getName() + "_paper_lantern");
                ItemStack out = r == null ? ItemStack.EMPTY : r.getResultItem(level.registryAccess());
                helper.assertTrue(out.is(l) && out.getCount() == 2, "the " + c.getName() + " lantern's recipe makes two");
                helper.assertTrue(((net.minecraft.world.item.BlockItem) l).getBlock().defaultBlockState().getLightEmission() == 12, "a light of twelve");
                helper.assertTrue(Tiers.of(level, l) == Villages.Age.WOOD && Prices.known(l) && Market.goodFor(new ItemStack(l)) != null,
                    "the Wood Age's, worth something, on the board: " + c.getName());
            }
            // The tailor makes two red ones.
            Item red = LeisureItems.lantern(DyeColor.RED);
            stock(t, join(many(Items.PAPER, 4), many(Items.TORCH, 12), many(Items.RED_DYE, 2)));
            ItemStack made = Pastimes.makeForTests(level, tailor, red);
            Kit.log("ls08 the tailor made " + made + "; paper " + stores(level, t, Items.PAPER) + ", torches " + stores(level, t, Items.TORCH));
            helper.assertTrue(made.is(red) && stores(level, t, red) == 2, "two red lanterns into the stores");
            helper.assertTrue(stores(level, t, Items.PAPER) == 3 && stores(level, t, Items.TORCH) == 11 && stores(level, t, Items.RED_DYE) == 1,
                "a paper, a torch and a dye out of the stores");
            // A festival evening: strung across the square out of the stores.
            at(level, DAY + 11000);
            for (DyeColor c : Lanterns.COLOURS) if (c != DyeColor.RED) stock(t, new ItemStack(LeisureItems.lantern(c)));
            stock(t, join(many(Items.STRING, 20), many(Items.OAK_FENCE, 16)));
            int lanterns = stores(level, t, LeisureItems::isLantern), string = stores(level, t, Items.STRING), fences = stores(level, t, Items.OAK_FENCE);
            String why = Lanterns.stringUpForTests(level, t.v());
            List<BlockPos> hung = Lanterns.hungForTests(t.id());
            Kit.log("ls08 strung up: " + (why == null ? "yes" : why) + "; " + hung.size() + " hung; lanterns " + lanterns + " -> "
                + stores(level, t, LeisureItems::isLantern) + ", string " + string + " -> " + stores(level, t, Items.STRING));
            helper.assertTrue(why == null && hung.size() >= 3, "lanterns strung across the square: " + why);
            for (BlockPos p : hung) {
                BlockState s = level.getBlockState(p);
                helper.assertTrue(s.getBlock() instanceof PaperLanternBlock && s.getValue(PaperLanternBlock.HANGING) && s.getLightEmission() == 12,
                    "a lantern hanging, lit, at " + p.toShortString());
                helper.assertTrue(level.getBlockState(p.above()).is(Blocks.TRIPWIRE), "from the line of string");
            }
            helper.assertTrue(lanterns - stores(level, t, LeisureItems::isLantern) == hung.size(), "every one out of the stores");
            helper.assertTrue(chronicled(t.id(), "paper lanterns"), "the chronicle has it");
            // The evening: the folk by them the happier.
            at(level, DAY + 12500);
            VillageFolkEntity by = t.folk().get(0);
            moveTo(by, hung.get(0).below(2).south(2));
            Lanterns.enjoyForTests(level, t.v());
            long day = DAY / 24000L;
            Kit.log("ls08 by the lanterns: " + moodKeys(by, day) + " (" + Pastimes.moodWords(by, "lanterns") + ")");
            helper.assertTrue(moodKeys(by, day).contains("lanterns"), "the happier for a lit festival");
            String gazette = Pastimes.gazette(level, t.id(), day + 1);
            helper.assertTrue(gazette != null && gazette.contains("lanterns"), "tomorrow's gazette: " + gazette);
            // The morning after: taken down, every lantern, string and post back in the stores.
            at(level, DAY + 24000 + 2000);
            Lanterns.takeDownForTests(level, t.v());
            boolean clear = true;
            for (BlockPos p : hung) if (!level.getBlockState(p).isAir()) clear = false;
            Kit.log("ls08 taken down: lanterns " + stores(level, t, LeisureItems::isLantern) + " of " + lanterns + ", string " + stores(level, t, Items.STRING)
                + " of " + string + ", fences " + stores(level, t, Items.OAK_FENCE) + " of " + fences);
            helper.assertTrue(clear && Lanterns.hungForTests(t.id()).isEmpty(), "down from the square");
            helper.assertTrue(stores(level, t, LeisureItems::isLantern) == lanterns && stores(level, t, Items.STRING) == string
                && stores(level, t, Items.OAK_FENCE) == fences, "every one back in the stores");
            helper.succeed();
        });
    }

    // ============================================================ ls09 the slate and chalk

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ls09_slate")
    public static void ls09_slate(GameTestHelper helper) {
        Town t = town(helper, 1276000, 2, Villages.Age.STONE, DAY + 2000);
        helper.runAtTickTime(5, () -> {
            ServerLevel level = helper.getLevel();
            Item slate = LeisureItems.SLATE.get();
            VillageFolkEntity teacher = t.folk().get(0), shop = t.folk().get(1);
            shop.setJob(StationTask.SHOP);
            VillageFolkEntity kid = child(helper, t, t.heart().offset(3, 0, 3));
            helper.assertTrue(Tiers.of(level, slate) == Villages.Age.STONE && Prices.known(slate), "the Stone Age's (smooth stone), worth something");
            stock(t, join(many(Items.SMOOTH_STONE, 2), many(Items.STICK, 2), many(Items.BONE_MEAL, 2), many(Items.CRAFTING_TABLE, 1)));
            ItemStack made = Pastimes.makeForTests(level, shop, slate);
            Kit.log("ls09 the workshop made " + made + "; smooth stone " + stores(level, t, Items.SMOOTH_STONE) + ", bone meal " + stores(level, t, Items.BONE_MEAL));
            helper.assertTrue(made.is(slate) && stores(level, t, slate) == 1, "a slate into the stores");
            helper.assertTrue(stores(level, t, Items.SMOOTH_STONE) == 1 && stores(level, t, Items.BONE_MEAL) == 1, "of smooth stone and bone meal");
            // A morning's lessons with no slate, then the teacher hands one out of the stores, and the same again.
            School.leanForTests(kid, StationTask.FARM);
            int x0 = kid.xpInTrade(StationTask.FARM);
            School.lessonForTests(kid, teacher, 8);
            int x1 = kid.xpInTrade(StationTask.FARM);
            Slates.beatForTests(level, kid, teacher, StationTask.FARM);
            ItemStack its = Slates.slateForTests(kid);
            String written = its.isEmpty() ? null : SlateItem.written(its);
            Kit.log("ls09 handed a slate: " + its + " \"" + written + "\", chalk " + (its.isEmpty() ? 0 : its.getMaxDamage() - its.getDamageValue()));
            helper.assertTrue(!its.isEmpty() && stores(level, t, slate) == 0, "the stores' slate handed to the pupil");
            helper.assertTrue(written != null && written.contains("farmer"), "the lesson chalked on it: " + written);
            helper.assertTrue(its.getDamageValue() == 1, "a little of its chalk used");
            helper.assertTrue(kid.getMainHandItem().is(slate), "in its hand at its desk");
            School.lessonForTests(kid, teacher, 8);
            int x2 = kid.xpInTrade(StationTask.FARM);
            int without = x1 - x0, with = x2 - x1;
            double ratio = without > 0 ? with / (double) without : 0;
            Kit.log("ls09 eight beats without a slate " + without + ", with " + with + ": " + String.format(Locale.ROOT, "%.2f", ratio) + " times");
            helper.assertTrue(without > 0 && ratio >= 1.1 && ratio <= 1.5, "about a quarter faster with a slate: " + without + " -> " + with);
            helper.assertTrue(Pastimes.cardLine(kid) != null && Pastimes.cardLine(kid).contains("carries a slate to school"), "its card: " + Pastimes.cardLine(kid));
            // Grown up: the school's slate back in the stores for the next.
            kid.setChild(false);
            Slates.tickForTests(level, t.v());
            helper.assertTrue(stores(level, t, slate) == 1 && kid.countCarried(s -> s.is(slate)) == 0 && !kid.getMainHandItem().is(slate),
                "the slate back in the stores");
            // A player chalks a line on one.
            ServerPlayer p = survivor(helper);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(slate));
            p.getMainHandItem().use(level, p, InteractionHand.MAIN_HAND);
            ItemStack held = p.getMainHandItem();
            String line = SlateItem.written(held);
            int damage = held.getDamageValue();
            Kit.noLeftoverPlayers(level);
            Kit.log("ls09 a player chalks: \"" + line + "\", chalk used " + damage);
            helper.assertTrue(line != null && damage == 1, "a line chalked, a little chalk used");
            helper.succeed();
        });
    }

    // ============================================================ ls10 the books, the town's bench, the stage

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ls10_books_and_stage")
    public static void ls10_books_and_stage(GameTestHelper helper) {
        Town t = town(helper, 1278000, 6, Villages.Age.WOOD, DAY + 8000);
        helper.runAtTickTime(5, () -> {
            ServerLevel level = helper.getLevel();
            // Every one of the seven (each lantern among them): a recipe the benches know, its age, a worth on the board, a maker.
            for (Item it : LeisureItems.all()) {
                String name = new ItemStack(it).getHoverName().getString();
                Villages.Age want = it == LeisureItems.SLATE.get() ? Villages.Age.STONE : Villages.Age.WOOD;
                helper.assertTrue(!RecipeBook.waysFor(level, it).isEmpty(), name + ": a recipe the benches know");
                helper.assertTrue(Tiers.of(level, it) == want, name + ": the " + want.label + ", not the " + Tiers.of(level, it).label);
                helper.assertTrue(Prices.known(it) && Prices.each(it) > 0 && Market.goodFor(new ItemStack(it)) != null, name + ": on the market's board");
                helper.assertTrue(!Makers.of(it).isEmpty(), name + ": its maker named");
            }
            // A town with neither tailor nor shop: its own bench makes the children's football.
            for (VillageFolkEntity f : t.folk()) f.setJob(StationTask.FARM);
            for (int i = 0; i < 3; i++) child(helper, t, t.heart().offset(-4 + 3 * i, 0, 8));
            stock(t, join(many(Items.LEATHER, 6), many(Items.WHITE_WOOL, 5), many(Items.CRAFTING_TABLE, 1)));
            String made = Pastimes.benchForTests(level, t.v());
            Kit.log("ls10 the town's bench: " + made + "; " + Pastimes.status(level, t.v()));
            helper.assertTrue(made != null && made.contains("leather football") && stores(level, t, LeisureItems.LEATHER_FOOTBALL.get()) == 1,
                "the town's bench made the football: " + made);
            // The photographs' stage: the seven on a wall, and in use.
            BlockPos near = t.heart().offset(0, 0, -18);
            List<String> lines = Pastimes.stageForTests(level, t.v(), near);
            Kit.log("ls10 the stage: " + lines);
            String all = String.join("\n", lines);
            int frames = level.getEntitiesOfClass(ItemFrame.class, new AABB(near).inflate(8.0)).size();
            BlockPos foot = Kit.surface(level, near.getX() - 4, near.getZ() + 3);
            helper.assertTrue(all.contains("VIEW showcase") && frames >= 7, "the showcase: " + frames + " frames");
            helper.assertTrue(all.contains("DRAUGHTS") && all.contains("KITE") && all.contains("KICKABOUT"), "the things in use");
            helper.assertTrue(lines.stream().anyMatch(l -> l.startsWith("LANTERNS") && l.endsWith("hung")), "the lanterns strung: " + all);
            helper.assertTrue(Quilts.quiltedForTests(level, foot.below()) || Quilts.quiltedForTests(level, foot), "a quilt on the showcase's bed");
            helper.succeed();
        });
    }
}
