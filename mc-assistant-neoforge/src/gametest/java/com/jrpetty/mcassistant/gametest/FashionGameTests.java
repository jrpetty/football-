package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Annals;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Fashion;
import com.jrpetty.mcassistant.entity.Festivals;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Gazette;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.PriceIndex;
import com.jrpetty.mcassistant.entity.Prices;
import com.jrpetty.mcassistant.entity.Purchases;
import com.jrpetty.mcassistant.entity.RecipeBook;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.Standing;
import com.jrpetty.mcassistant.entity.Style;
import com.jrpetty.mcassistant.entity.Tailoring;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.Tiers;
import com.jrpetty.mcassistant.entity.Values;
import com.jrpetty.mcassistant.entity.VillageBoards;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wealth;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.item.Garment;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.Container;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [fashion] The town's fashions (Fashion, Tailoring, FashionShow): the season's look set by the town's most admired
 * (the wealthiest, the leader's partner, the best liked) in its own colour; its going round, day by day, along the
 * friendships first, a Traditionalist keeping its own; the tailor making the season's scarf out of the stores' real wool,
 * string and a poppy made into red dye, and sending a farm hand for the cornflower a blue one waits on; a folk buying it
 * at the town's price out of its own purse and putting it on (what the client draws changing with it), its old one
 * going to a poor neighbour through the poor box; the new things' recipes, ages and worths; the fashion show's rosette
 * pinned on the best-dressed; and a famous player in dyed leather setting the season's look, then giving a folk a scarf.
 *
 * <p>Each on its own ground, x 1000000 to 1016000 on z 66000, a batch of its own, the logic called directly.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class FashionGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    /** A town of so many folk at x, in this age: its founders' chest emptied, a marked chest of these goods by the heart. */
    private record Town(UUID village, Villages.Village v, BlockPos heart, Container stores, List<VillageFolkEntity> folk) {}

    private static Town town(GameTestHelper helper, int x, int n, Villages.Age age, ItemStack... goods) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        Kit.hold(level, x, Z, 80);
        Kit.prepare(level, x, Z, 80);
        BlockPos heart = Kit.surface(level, x, Z);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.south(2 + i), 0.0F);
            helper.assertTrue(f != null && (folk.isEmpty() || folk.get(0).ownerId().equals(f.ownerId())), "folk " + i);
            folk.add(f);
        }
        UUID village = folk.get(0).ownerId();
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
        BlockPos chest = Kit.surface(level, heart.getX() + 4, heart.getZ());
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        Container box = (Container) level.getBlockEntity(chest);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        Villages.forgetStores(village);
        Villages.forgetStock();
        Villages.ageForTests(village, age);
        for (VillageFolkEntity f : folk) {
            f.ensurePersona();
            f.life().setTraitsForTests(Social.Trait.EASYGOING);
            Values.setForTests(f, Values.Value.LEISURE, 100);        // a Free Spirit, never a Traditionalist by chance
            f.setJob(StationTask.FARM);
        }
        return new Town(village, Villages.get(village), heart, box, folk);
    }

    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        return Market.stock(level, village, what);
    }

    /** All the coin there is in the town: the treasury and every purse. A sale moves it; none is made or lost. */
    private static int money(UUID village) {
        int n = Ledger.coins(village);
        for (AssistantEntity a : Villages.folkOf(village)) if (a instanceof VillageFolkEntity f) n += f.purse();
        return n;
    }

    private static void friends(VillageFolkEntity a, VillageFolkEntity b) {
        a.life().feel(b.getUUID(), b.displayNameCap(), 45);
    }

    private static boolean chronicled(UUID village, String words) {
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().toLowerCase(Locale.ROOT).contains(words)) return true;
        return false;
    }

    private static final int RED = DyeColor.RED.getId(), BLUE = DyeColor.BLUE.getId();

    // ============================================================ the season's look, from the rich and the popular

    /**
     * Five folk: Bram the elected leader, Ada its partner, the wealthiest in town and the best liked (three count her a
     * friend), in crimson of her own; the rest in their own colours. As the season turns the town takes her colour for its
     * own: the season's look is crimson, set by Ada, and why; the chronicle, the board, the gazette and the town's books
     * say so; Ada has the season's long coat put on the tailor's book first; and her card says she set it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fa01_rich_popular_set_the_trend")
    public static void fa01_rich_popular_set_the_trend(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1000000, 5, Villages.Age.IRON, new ItemStack(Items.BOOK), new ItemStack(Items.PAPER, 3), new ItemStack(Items.LEATHER, 2));
        VillageFolkEntity ada = t.folk().get(0), bram = t.folk().get(1), cora = t.folk().get(2), dell = t.folk().get(3), eve = t.folk().get(4);
        ada.rename("Ada");
        bram.rename("Bram");
        long day = level.getDayTime() / 24000L;
        Villages.electElder(t.village(), bram, day);
        ada.life().partnerWith(bram.getUUID(), "Bram");
        bram.life().partnerWith(ada.getUUID(), "Ada");
        ada.earn(300);
        bram.earn(60);
        for (VillageFolkEntity f : List.of(bram, cora, dell)) friends(f, ada);
        Fashion.coloursForTests(ada, DyeColor.RED, DyeColor.WHITE);
        Fashion.coloursForTests(bram, DyeColor.BLUE, DyeColor.WHITE);
        Fashion.coloursForTests(cora, DyeColor.GREEN, DyeColor.YELLOW);
        Fashion.coloursForTests(dell, DyeColor.YELLOW, DyeColor.BLUE);
        Fashion.coloursForTests(eve, DyeColor.PURPLE, DyeColor.YELLOW);
        List<String> setters = Fashion.settersForTests(level, t.v());
        Fashion.newTrendForTests(level, t.v());
        int colour = Fashion.trendColour(t.village());
        String by = Fashion.setBy(t.village());
        String board = String.join(" / ", VillageBoards.compose(level, t.village()));
        String gazette = Fashion.gazette(level, t.v(), day);
        CompoundTag books = Annals.snapshot(level, t.v()).getCompound("fashion");
        List<String> book = Tailoring.bookForTests(t.village());
        Fashion.look(ada);
        String card = Fashion.cardLine(ada);
        Kit.log("fa01 the town looks to: " + setters + "; the season's look: " + Garment.colourWord(colour) + " " + Fashion.trendKind(t.village())
            + ", set by " + by + "; worth: Ada " + Wealth.worth(ada) + ", Bram " + Wealth.worth(bram) + "; the tailor's book " + book
            + "; Ada's card: " + card + "; the books: " + books.getString("colourWord") + " by " + books.getString("setter")
            + "; gazette: " + (gazette == null ? "" : gazette.replace('\n', ' ')));
        helper.assertTrue(!setters.isEmpty() && setters.get(0).startsWith("Ada|"), "Ada is the most admired: " + setters);
        helper.assertTrue(colour == RED, "the season's look is her crimson: " + Garment.colourWord(colour));
        helper.assertTrue(by.startsWith("Ada") && by.contains("wealthiest") && by.contains("leader's partner"), "set by Ada, and why: " + by);
        helper.assertTrue(chronicled(t.village(), "crimson") && chronicled(t.village(), "set by ada"), "the chronicle tells of it");
        helper.assertTrue(board.contains("Fashion: crimson"), "the board says so: " + board);
        helper.assertTrue(gazette != null && gazette.contains("Crimson is all the rage") && gazette.contains("Ada"), "and the gazette: " + gazette);
        helper.assertTrue(books.getInt("colour") == RED && books.getString("setter").equals("Ada"), "and the town's books: " + books);
        helper.assertTrue(book.size() == 1 && book.get(0).startsWith("a crimson") && book.get(0).contains("for Ada"),
            "Ada's own crimson garment is first on the tailor's book: " + book);
        helper.assertTrue(card.contains("Set this") && card.contains("crimson"), "her card says she set it: " + card);
        helper.succeed();
    }

    // ============================================================ it goes round, friends first

    /**
     * Seven folk: Ada in a crimson long coat sets the look; four count her a friend (sociable, cheerful); a stranger
     * knows nobody; a Traditionalist keeps its own. The stores hold the season's crimson scarves. Day by day the
     * friends come round first (a day's pull for a friend is more than the stranger's), want the look, buy it and put it
     * on; the stranger follows later as the town fills up with crimson; the Traditionalist never does. Every day's
     * count logged.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fa02_spreads_along_friendships")
    public static void fa02_spreads_along_friendships(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<ItemStack> goods = new ArrayList<>();
        for (int i = 0; i < 9; i++) goods.add(Garment.WOOL_SCARF.dyed(RED));
        Town t = town(helper, 1002000, 7, Villages.Age.IRON, goods.toArray(new ItemStack[0]));
        VillageFolkEntity ada = t.folk().get(0), stranger = t.folk().get(5), old = t.folk().get(6);
        List<VillageFolkEntity> pals = t.folk().subList(1, 5);
        ada.rename("Ada");
        stranger.rename("Stranger");
        old.rename("Oldways");
        Values.setForTests(old, Values.Value.LEISURE, 0);         // weights top out at 100: the town's Free Spirit weight off
        Values.setForTests(old, Values.Value.TRADITION, 100);
        helper.assertTrue(Fashion.traditional(old), "Oldways is a Traditionalist: " + Values.type(old));
        for (VillageFolkEntity f : t.folk()) f.earn(45);
        for (VillageFolkEntity p : pals) {
            p.life().setTraitsForTests(Social.Trait.SOCIABLE, Social.Trait.CHEERFUL);
            friends(p, ada);
        }
        friends(old, ada);
        Fashion.coloursForTests(ada, DyeColor.RED, DyeColor.WHITE);
        Fashion.wearForTests(level, ada, Garment.LONG_COAT.dyed(RED));
        Fashion.setTrend(level, t.v(), RED, Garment.WOOL_SCARF, "Ada", "the test's");
        long day0 = level.getDayTime() / 24000L;
        List<String> log = new ArrayList<>();
        int firstPal = -1, strangerDay = -1;
        double palPull = 0, strangerPull = 0;
        for (int d = 0; d < 6; d++) {
            Fashion.spreadForTests(level, t.v(), day0 + d);
            if (d == 0) {
                palPull = Fashion.pullForTests(pals.get(0)) + (pals.get(0).style().wants() != null ? 1.0 : 0.0);
                strangerPull = Fashion.pullForTests(stranger) + (stranger.style().wants() != null ? 1.0 : 0.0);
            }
            for (VillageFolkEntity f : t.folk()) if (f.style().wants() != null) Fashion.buyForTests(level, f);
            int palsIn = 0;
            for (VillageFolkEntity p : pals) if (Fashion.inFashion(p)) palsIn++;
            if (palsIn > 0 && firstPal < 0) firstPal = d;
            if (Fashion.inFashion(stranger) && strangerDay < 0) strangerDay = d;
            int[] n = Fashion.counts(t.village());
            log.add("day " + d + ": " + n[0] + "/" + n[2] + " in crimson (friends " + palsIn + "/4, stranger " + Fashion.inFashion(stranger)
                + " pull " + String.format(Locale.ROOT, "%.2f", Fashion.pullForTests(stranger)) + ", Oldways " + Fashion.inFashion(old)
                + " pull " + String.format(Locale.ROOT, "%.2f", Fashion.pullForTests(old)) + ")");
        }
        int palsIn = 0;
        for (VillageFolkEntity p : pals) if (Fashion.inFashion(p)) palsIn++;
        Kit.log("fa02 " + String.join("; ", log) + "; a friend's first day's pull " + String.format(Locale.ROOT, "%.2f", palPull)
            + " against the stranger's " + String.format(Locale.ROOT, "%.2f", strangerPull) + "; friends first in on day " + firstPal
            + ", the stranger on day " + strangerDay + "; what a friend wears: " + Fashion.cardLine(pals.get(0)) + "; Oldways: " + Fashion.cardLine(old));
        helper.assertTrue(palPull > strangerPull, "a friend comes round quicker than a stranger: " + palPull + " against " + strangerPull);
        helper.assertTrue(firstPal >= 0 && firstPal <= 2, "the friends are in it within two days: day " + firstPal);
        helper.assertTrue(palsIn >= 3, "three of the four friends wear it by the sixth day: " + palsIn);
        helper.assertTrue(strangerDay < 0 || strangerDay > firstPal, "the stranger follows later, or not yet: day " + strangerDay);
        helper.assertTrue(!Fashion.inFashion(old), "the Traditionalist keeps its own: " + Fashion.cardLine(old));
        helper.assertTrue(Fashion.counts(t.village())[0] > Fashion.counts(t.village())[2] / 2, "most of the town wears it: " + log);
        helper.succeed();
    }

    // ============================================================ the tailor, from real wool and dye

    /**
     * An Iron Age town whose season's look is a crimson scarf: a folk that wants it finds none and has it put on the
     * tailor's book; the tailor makes it out of the stores' wool and string, and red dye made there and then of a poppy
     * (by the game's recipes): the stores have two fewer wool, one fewer string, one fewer poppy, and a crimson scarf
     * marked with who made it and for whom. A blue one waits on a blue dye, and the stores' books hear of the dye as
     * wanted; a farm hand picks the cornflower growing out past the town, and the tailor makes the blue one of it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fa03_tailor_makes_the_trend")
    public static void fa03_tailor_makes_the_trend(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1004000, 4, Villages.Age.IRON, new ItemStack(Items.WHITE_WOOL, 12), new ItemStack(Items.STRING, 6),
            new ItemStack(Items.POPPY, 2), new ItemStack(Items.CRAFTING_TABLE));
        VillageFolkEntity tailor = t.folk().get(0), buyer = t.folk().get(1), other = t.folk().get(2);
        tailor.setJob(StationTask.TAILOR);
        tailor.rename("Tam");
        buyer.rename("Bea");
        Fashion.setTrend(level, t.v(), RED, Garment.WOOL_SCARF, "Ada", "the test's");
        UUID id = t.village();
        Predicate<ItemStack> redScarf = s -> Garment.of(s) == Garment.WOOL_SCARF && Garment.colourOf(s) == RED;
        int wool = stock(level, id, s -> s.is(ItemTags.WOOL)), string = stock(level, id, s -> s.is(Items.STRING)),
            poppies = stock(level, id, s -> s.is(Items.POPPY));
        Fashion.wantForTests(level, buyer);
        Fashion.look(buyer);
        List<String> book = Tailoring.bookForTests(id);
        String made = Tailoring.workForTests(level, tailor);
        int wool2 = stock(level, id, s -> s.is(ItemTags.WOOL)), string2 = stock(level, id, s -> s.is(Items.STRING)),
            poppies2 = stock(level, id, s -> s.is(Items.POPPY)), scarves = stock(level, id, redScarf);
        ItemStack scarf = ItemStack.EMPTY;
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) if (redScarf.test(c.getItem(i))) scarf = c.getItem(i).copy();
        }
        String lore = String.valueOf(scarf.get(DataComponents.LORE));
        Kit.log("fa03 Bea wants " + buyer.style().wants() + " in " + Garment.colourWord(buyer.style().wantColour()) + "; the book " + book
            + "; the tailor made: " + made + "; wool " + wool + " -> " + wool2 + ", string " + string + " -> " + string2 + ", poppies " + poppies
            + " -> " + poppies2 + ", crimson scarves " + scarves + "; it reads: " + scarf.getHoverName().getString() + ", " + lore);
        helper.assertTrue(book.size() == 1 && book.get(0).contains("crimson scarf for Bea"), "on the tailor's book: " + book);
        helper.assertTrue(made != null && made.contains("crimson scarf"), "the tailor made it: " + made);
        helper.assertTrue(wool2 == wool - 2 && string2 == string - 1, "of the stores' wool and string: wool " + wool + " -> " + wool2
            + ", string " + string + " -> " + string2);
        helper.assertTrue(poppies2 == poppies - 1, "dyed with a poppy made into red dye: " + poppies + " -> " + poppies2);
        helper.assertTrue(scarves == 1 && scarf.is(Garment.WOOL_SCARF.item()), "a crimson scarf in the stores: " + scarf);
        helper.assertTrue(lore.contains("Tam") && lore.contains("Made for Bea"), "with its maker's mark, and for whom: " + lore);
        helper.assertTrue(Tailoring.bookForTests(id).isEmpty(), "off the book: " + Tailoring.bookForTests(id));
        // A blue one: no blue dye in the stores, nor anything to make one of.
        Fashion.setTrend(level, t.v(), BLUE, Garment.WOOL_SCARF, "Ada", "the test's");
        Fashion.wantForTests(level, other);
        Fashion.look(other);
        String none = Tailoring.workForTests(level, tailor);
        List<String> waiting = Tailoring.bookForTests(id);
        // A cornflower growing wild out past the town.
        int reach = Villages.townReach(id);
        BlockPos ground = Kit.surface(level, t.heart().getX() + reach + 4, t.heart().getZ());
        level.setBlock(ground.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        level.setBlock(ground, Blocks.CORNFLOWER.defaultBlockState(), 3);
        Set<Item> flowers = Tailoring.flowersForTests(level, Items.BLUE_DYE);
        int corn = stock(level, id, s -> s.is(Items.CORNFLOWER));
        for (int i = 0; i < 3 && stock(level, id, s -> s.is(Items.CORNFLOWER)) == 0; i++) {
            com.jrpetty.mcassistant.entity.Fashion.tick(level, t.v());
        }
        int corn2 = stock(level, id, s -> s.is(Items.CORNFLOWER));
        boolean picked = level.getBlockState(ground).isAir();
        // The tailor's book waits a moment before it tries a short order again: tried afresh here.
        Tailoring.resetRetryForTests(id);
        String blue = Tailoring.workForTests(level, tailor);
        Kit.log("fa03 a blue one: " + none + "; the book " + waiting + "; blue dye's flowers " + flowers + "; cornflowers in the stores "
            + corn + " -> " + corn2 + " (picked: " + picked + "); then made: " + blue);
        helper.assertTrue(none == null && waiting.size() == 1 && waiting.get(0).contains("short of"), "short of a blue dye: " + waiting);
        helper.assertTrue(flowers.contains(Items.CORNFLOWER), "the game's recipes say a cornflower makes it: " + flowers);
        helper.assertTrue(corn2 == corn + 1, "a hand picked a cornflower into the stores: " + corn + " -> " + corn2 + " (ours: " + picked + ")");
        helper.assertTrue(blue != null && blue.contains("blue scarf"), "and the blue one is made of it: " + blue);
        helper.succeed();
    }

    // ============================================================ bought, and worn

    /**
     * The shop open, a crimson scarf in the stores and the season's look a crimson scarf: Bea, comfortable and in a blue
     * scarf, wants it and buys it at the town's price out of her own purse (the coin into the treasury, none made or
     * lost; the prices hear of the sale). She puts it on: what the client draws of her changes from a blue scarf to a
     * crimson one, and her card says she is in fashion. Her old blue scarf goes through the poor box to Pip, who had none.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fa04_bought_and_worn")
    public static void fa04_bought_and_worn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1006000, 4, Villages.Age.IRON, Garment.WOOL_SCARF.dyed(RED));
        VillageFolkEntity bea = t.folk().get(0), pip = t.folk().get(1);
        bea.rename("Bea");
        pip.rename("Pip");
        UUID id = t.village();
        Purchases.openForTests(id, true);
        bea.earn(45);
        t.folk().get(2).earn(40);                           // the others are comfortable: none of them is the poor box's
        t.folk().get(3).earn(40);
        Fashion.coloursForTests(bea, DyeColor.YELLOW, DyeColor.BLUE);
        Fashion.wearForTests(level, bea, Garment.WOOL_SCARF.dyed(BLUE));
        Fashion.setTrend(level, t.v(), RED, Garment.WOOL_SCARF, "Ada", "the test's");
        Fashion.look(bea);
        long before = bea.clientStyle();
        Fashion.wantForTests(level, bea);
        int purse = bea.purse(), treasury = Ledger.coins(id), coin = money(id);
        int[] tallyBefore = PriceIndex.tallyForTests(id, Garment.WOOL_SCARF.dyed(RED));
        double price = Purchases.priceEach(level, id, Garment.WOOL_SCARF.dyed(RED), bea);
        ItemStack got = Fashion.buyForTests(level, bea);
        Fashion.look(bea);
        Fashion.look(pip);
        long after = bea.clientStyle();
        int[] tallyAfter = PriceIndex.tallyForTests(id, Garment.WOOL_SCARF.dyed(RED));
        String card = Fashion.cardLine(bea), pips = Fashion.cardLine(pip);
        int[] passed = Fashion.passedThisWeek(id, level.getDayTime() / 24000L);
        Kit.log("fa04 Bea bought " + got.getHoverName().getString() + " at " + String.format(Locale.ROOT, "%.2f", price) + ": purse " + purse
            + " -> " + bea.purse() + ", treasury " + treasury + " -> " + Ledger.coins(id) + ", all the coin " + coin + " -> " + money(id)
            + "; the prices' tally " + java.util.Arrays.toString(tallyBefore) + " -> " + java.util.Arrays.toString(tallyAfter)
            + "; drawn: scarf " + Style.scarfColour(before) + " -> " + Style.scarfColour(after) + ", in fashion " + Style.inFashion(before) + " -> "
            + Style.inFashion(after) + "; her card: " + card + "; Pip: " + pips + "; passed on " + java.util.Arrays.toString(passed));
        helper.assertTrue(Garment.of(got) == Garment.WOOL_SCARF && Garment.colourOf(got) == RED, "she bought the crimson scarf: " + got);
        helper.assertTrue(bea.purse() < purse && Ledger.coins(id) > treasury, "out of her purse into the treasury: " + purse + " -> " + bea.purse());
        helper.assertTrue(money(id) == coin, "no coin made or lost: " + coin + " -> " + money(id));
        helper.assertTrue(tallyAfter[1] == tallyBefore[1] + 1, "the town's prices heard of the sale: " + java.util.Arrays.toString(tallyAfter));
        helper.assertTrue(stock(level, id, s -> Garment.of(s) == Garment.WOOL_SCARF && Garment.colourOf(s) == RED) == 0, "out of the stores");
        helper.assertTrue(Style.scarfColour(before) == BLUE && Style.scarfColour(after) == RED, "what is drawn of her changes: blue -> crimson");
        helper.assertTrue(!Style.inFashion(before) && Style.inFashion(after), "and she is in fashion now");
        helper.assertTrue(card.contains("crimson scarf") && card.contains("In fashion"), "her card says so: " + card);
        helper.assertTrue(pip.style().colour(Garment.Slot.NECK) == BLUE && passed[0] == 1, "her old blue scarf went to Pip through the poor box: " + pips);
        helper.succeed();
    }

    // ============================================================ the new things

    /**
     * Every garment is a real item: a recipe the folk's makers can read (RecipeBook), the age its makings put it in
     * (Tiers: a coat of wool the Wood Age's, a leather jacket and a felt hat the Stone Age's, gold buttons the Iron
     * Age's), a worth on the price list (more than its cloth), and, all but the brooch, dyed by the game's own dyeing;
     * a dye's flowers come from the game's recipes too (purple: a red's and a blue's).
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "fa05_new_things_recipes_ages_worths")
    public static void fa05_new_things_recipes_ages_worths(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        List<String> log = new ArrayList<>();
        for (Garment g : Garment.values()) {
            Item it = g.item();
            int ways = RecipeBook.waysFor(level, it).size();
            Villages.Age age = Tiers.of(level, it);
            double worth = Prices.each(it);
            ItemStack dyed = g.dyed(RED);
            boolean dyeable = new ItemStack(it).is(ItemTags.DYEABLE);
            log.add(g.id + ": " + ways + " recipe(s), " + age.label + ", worth " + String.format(Locale.ROOT, "%.2f", worth) + ", "
                + dyed.getHoverName().getString());
            helper.assertTrue(it != Items.AIR, g.id + " is registered");
            helper.assertTrue(ways >= 1, g.id + " has a recipe the makers read");
            helper.assertTrue(age == g.age, g.id + " belongs to " + g.age.label + ", the rule reads " + age.label);
            helper.assertTrue(worth >= 0.5, g.id + " has a worth: " + worth);
            helper.assertTrue(dyeable == g.dyeable(), g.id + " is dyeable as it should be: " + dyeable);
            if (g.dyeable()) {
                helper.assertTrue(Garment.colourOf(dyed) == RED && dyed.has(DataComponents.DYED_COLOR), g.id + " dyed by the game's dyeing: " + dyed);
            }
        }
        double scarf = Prices.each(Garment.WOOL_SCARF.item()), coat = Prices.each(Garment.LONG_COAT.item()), wool = Prices.each(Items.WHITE_WOOL);
        Set<Item> red = Tailoring.flowersForTests(level, Items.RED_DYE), purple = Tailoring.flowersForTests(level, Items.PURPLE_DYE);
        Kit.log("fa05 " + String.join("; ", log) + "; a wool " + String.format(Locale.ROOT, "%.2f", wool) + "; red dye's flowers " + red
            + "; purple's " + purple);
        helper.assertTrue(coat > scarf && scarf > 2 * wool, "a coat is worth more than a scarf, a scarf more than its wool: " + coat + ", " + scarf);
        helper.assertTrue(red.contains(Items.POPPY), "a poppy makes red dye: " + red);
        helper.assertTrue(purple.contains(Items.POPPY) && purple.contains(Items.CORNFLOWER), "purple of a red's and a blue's flowers: " + purple);
        helper.succeed();
    }

    // ============================================================ the fashion show

    /**
     * Three folk at the fair: Ada in the season's crimson long coat, a felt hat and a scarf; Bram in a blue shawl; Cora
     * in nothing of her own. A blue rosette in the stores. The fair's lines end with the parade, and the show held: the
     * rosette goes to Ada, out of the stores and pinned on her (drawn), the chronicle tells of it and her card says so.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fa06_fashion_show_rosette")
    public static void fa06_fashion_show_rosette(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1008000, 3, Villages.Age.IRON, Garment.ROSETTE.dyed(BLUE));
        VillageFolkEntity ada = t.folk().get(0), bram = t.folk().get(1), cora = t.folk().get(2);
        ada.rename("Ada");
        bram.rename("Bram");
        cora.rename("Cora");
        UUID id = t.village();
        Fashion.setTrend(level, t.v(), RED, Garment.LONG_COAT, "Ada", "the test's");
        Fashion.coloursForTests(ada, DyeColor.RED, DyeColor.WHITE);
        Fashion.wearForTests(level, ada, Garment.LONG_COAT.dyed(RED));
        Fashion.wearForTests(level, ada, Garment.FELT_HAT.dyed(RED));
        Fashion.wearForTests(level, ada, Garment.WOOL_SCARF.dyed(DyeColor.WHITE.getId()));
        Fashion.wearForTests(level, bram, Garment.WOOL_SHAWL.dyed(BLUE));
        List<String> lines = Festivals.scriptForTests(level, t.v(), Festivals.Feast.FAIR);
        String held = Fashion.dayNow(level, t.v()).toString();
        String show = com.jrpetty.mcassistant.entity.FashionShow.holdNowForTests(level, t.v());
        Fashion.look(ada);
        int rosettes = stock(level, id, s -> Garment.of(s) == Garment.ROSETTE);
        String card = Fashion.cardLine(ada);
        Kit.log("fa06 the fair's lines: " + lines + "; the show: " + show + "; rosettes left in the stores " + rosettes + "; Ada drawn with a rosette "
            + Style.rosetteColour(ada.clientStyle()) + "; her card: " + card + "; " + held);
        helper.assertTrue(lines.stream().anyMatch(l -> l.contains("the parade")) && lines.stream().anyMatch(l -> l.contains("rosette for the best-dressed goes to Ada")),
            "the fair ends with the parade: " + lines);
        helper.assertTrue(show.startsWith("best-dressed: Ada"), "Ada is the best-dressed: " + show);
        helper.assertTrue(rosettes == 0 && ada.style().worn(Garment.Slot.RIBBON).is(Garment.ROSETTE.item()), "the rosette out of the stores and on her");
        helper.assertTrue(Style.rosetteColour(ada.clientStyle()) == BLUE, "drawn: a blue rosette");
        helper.assertTrue(chronicled(id, "ada was the best-dressed"), "the chronicle tells of it");
        helper.assertTrue(card.contains("Won the rosette"), "her card says so: " + card);
        helper.succeed();
    }

    // ============================================================ a famous player

    /**
     * A player every folk in town thinks the world of (its hero) has been about the town in crimson leather: as the
     * season turns the town takes its fashion from the player, a crimson leather jacket. Then the player gives Cora a
     * blue scarf: she puts it on there and then, and thinks the better of the player for it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fa07_player_sets_and_gives")
    public static void fa07_player_sets_and_gives(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1010000, 5, Villages.Age.IRON);
        UUID id = t.village();
        Player you = helper.makeMockPlayer(GameType.SURVIVAL);
        for (VillageFolkEntity f : t.folk()) f.persona().feelFor(you.getUUID(), you.getName().getString(), 70);
        Standing.stir(id, you.getUUID());
        ItemStack jacket = new ItemStack(Items.LEATHER_CHESTPLATE);
        jacket.set(DataComponents.DYED_COLOR, new DyedItemColor(DyeColor.RED.getTextureDiffuseColor(), true));
        ItemStack boots = DyedItemColor.applyDyes(new ItemStack(Items.LEATHER_BOOTS), List.of((DyeItem) Items.RED_DYE));
        you.setItemSlot(EquipmentSlot.CHEST, jacket);
        you.setItemSlot(EquipmentSlot.FEET, boots);
        Fashion.seenForTests(level, t.v(), you);
        Standing.Title title = Standing.of(id, you.getUUID(), level.getGameTime()).title();
        List<String> setters = Fashion.settersForTests(level, t.v());
        Fashion.newTrendForTests(level, t.v());
        String by = Fashion.setBy(id);
        VillageFolkEntity cora = t.folk().get(2);
        cora.rename("Cora");
        int warmth = cora.persona().affinity(you.getUUID());
        you.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, Garment.WOOL_SCARF.dyed(BLUE));
        String said = FolkTalk.answer(cora, you, TalkTopic.GIFT, "");
        Fashion.look(cora);
        int warmth2 = cora.persona().affinity(you.getUUID());
        Kit.log("fa07 the player is " + title + "; the town looks to " + setters + "; the season's look " + Garment.colourWord(Fashion.trendColour(id))
            + " " + Fashion.trendKind(id) + ", set by " + by + "; Cora on the scarf: " + said + "; her warmth " + warmth + " -> " + warmth2
            + "; drawn scarf " + Style.scarfColour(cora.clientStyle()) + "; the player's hand " + you.getMainHandItem());
        helper.assertTrue(title == Standing.Title.HERO, "the town's hero: " + title);
        helper.assertTrue(!setters.isEmpty() && setters.get(0).startsWith(you.getName().getString() + "|"), "the town looks to the player first: " + setters);
        helper.assertTrue(Fashion.trendColour(id) == RED && Fashion.trendKind(id) == Garment.LEATHER_JACKET, "a crimson leather jacket, after the player");
        helper.assertTrue(by.startsWith(you.getName().getString()), "set by the player: " + by);
        helper.assertTrue(cora.style().colour(Garment.Slot.NECK) == BLUE && Style.scarfColour(cora.clientStyle()) == BLUE, "Cora wears the blue scarf");
        helper.assertTrue(warmth2 > warmth, "and thinks the better of the player: " + warmth + " -> " + warmth2);
        helper.assertTrue(you.getMainHandItem().isEmpty(), "the scarf left the player's hand");
        helper.succeed();
    }
}
