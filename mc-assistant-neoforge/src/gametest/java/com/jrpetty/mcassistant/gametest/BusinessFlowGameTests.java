package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.BizGuide;
import com.jrpetty.mcassistant.entity.BuildDesk;
import com.jrpetty.mcassistant.entity.BuildOrders;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.Businesses;
import com.jrpetty.mcassistant.entity.CoinCensus;
import com.jrpetty.mcassistant.entity.PlayerFinance;
import com.jrpetty.mcassistant.entity.PlayerLife;
import com.jrpetty.mcassistant.entity.PlayerShops;
import com.jrpetty.mcassistant.entity.PropertyDesk;
import com.jrpetty.mcassistant.entity.PropertyMarket;
import com.jrpetty.mcassistant.entity.Refits;
import com.jrpetty.mcassistant.entity.ShopCustomers;
import com.jrpetty.mcassistant.entity.ShopKeepers;
import com.jrpetty.mcassistant.entity.StartBusiness;
import com.jrpetty.mcassistant.entity.TaxBook;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [biz25] Making a business, the whole way, as a player does it, through the same buttons the screens send
 * (PlayerLife.act): a newcomer starts "a shop of your own" (the counter bought of the town's carpenters), sets it in a room,
 * registers it, stocks it with what a folk needs, and a folk buys at its counter, the coin booked in the player's finances;
 * the first steps tick off one by one, the next always named. Then a keeper on a slow day, the rates, running out of stock;
 * a useless ware nobody buys, too little coin, a missing counter, a charter for a stranger once it has said hello; a shop
 * the town builds; and the building sold with its stock (the shop the seller's no longer) and quoted for a new use.
 * Each test on its own ground (x 5,600,000 to 5,630,000, z 90,000), in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BusinessFlowGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;

    // ------------------------------------------------------------------ the ground and the pieces

    private static List<VillageFolkEntity> town(GameTestHelper helper, ServerLevel level, int x, int n) {
        Kit.reset(level);
        PlayerShops.resetForTests();
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos ground = Kit.surface(level, x, Z);
        int y = ground.getY();
        for (int dx = -24; dx <= 24; dx++) {
            for (int dz = -24; dz <= 24; dz++) {
                level.setBlock(new BlockPos(x + dx, y - 2, Z + dz), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x + dx, y - 1, Z + dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int h = 0; h <= 10; h++) level.setBlock(new BlockPos(x + dx, y + h, Z + dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, new BlockPos(x - 3 + (i % 4) * 2, y, Z - 4 + (i / 4) * 2), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            f.earn(40);
            folk.add(f);
        }
        return folk;
    }

    /** A room east of the heart, its door to the west toward the square (as the player would build it). */
    private static void room(ServerLevel level, BlockPos heart) {
        int x0 = heart.getX() + 5, x1 = heart.getX() + 11, z0 = heart.getZ() - 3, z1 = heart.getZ() + 3, y = heart.getY();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                level.setBlock(new BlockPos(x, y - 1, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, y + 3, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
                boolean wall = x == x0 || x == x1 || z == z0 || z == z1;
                for (int h = 0; h < 3; h++) level.setBlock(new BlockPos(x, y + h, z), wall ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            }
        }
        BlockPos door = new BlockPos(x0, y, heart.getZ());
        level.setBlock(door, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2);
        level.setBlock(door.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
    }

    private static BlockPos counterAt(BlockPos heart) {
        return heart.offset(8, 0, 0);
    }

    private static BlockPos chestAt(BlockPos heart) {
        return heart.offset(10, 0, 2);
    }

    /** The counter set down by the player: the block, and the shop made of it, as the block's placing does. */
    private static void setCounter(ServerLevel level, BlockPos heart, ServerPlayer p) {
        level.setBlockAndUpdate(counterAt(heart), McAssistantMod.SHOP_COUNTER.get().defaultBlockState()
            .setValue(com.jrpetty.mcassistant.block.ShopCounterBlock.FACING, Direction.WEST));
        p.getInventory().clearOrCountMatchingItems(s -> s.is(McAssistantMod.SHOP_COUNTER_ITEM.get()), 1, p.inventoryMenu.getCraftSlots());
        PlayerShops.placed(level, counterAt(heart), p, true);
    }

    private static Container chest(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        Container c = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) c.setItem(i, goods[i].copy());
        return c;
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos at, int coins) {
        @SuppressWarnings("removal")
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.getInventory().clearContent();
        if (coins > 0) p.getInventory().setItem(0, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
        return p;
    }

    private static int coins(ServerPlayer p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(McAssistantMod.VILLAGE_COIN.get())) n += p.getInventory().getItem(i).getCount();
        return n;
    }

    private static int count(Container c, net.minecraft.world.item.Item it) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(it)) n += c.getItem(i).getCount();
        return n;
    }

    /** A farmer with no hoe: it has a real use for a stone hoe now. */
    private static VillageFolkEntity farmerWithoutHoe(VillageFolkEntity f) {
        f.setJob(AssistantEntity.StationTask.FARM);
        f.removeMatching(it -> it.is(net.minecraft.tags.ItemTags.HOES), 64);
        f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        return f;
    }

    private static CompoundTag args(Object... kv) {
        CompoundTag t = new CompoundTag();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] instanceof Integer n) t.putInt((String) kv[i], n);
            else if (kv[i + 1] instanceof Boolean b) t.putBoolean((String) kv[i], b);
            else if (kv[i + 1] instanceof CompoundTag c) t.put((String) kv[i], c);
            else t.putString((String) kv[i], String.valueOf(kv[i + 1]));
        }
        return t;
    }

    private static String page(ServerPlayer p) {
        return PlayerLife.pageForTests(p).toString();
    }

    private static String nextId(ServerLevel level, UUID village, ServerPlayer p) {
        BizGuide.Step n = BizGuide.next(BizGuide.steps(level, Villages.get(village), PlayerShops.of(village, p.getUUID())));
        return n == null ? "" : n.id();
    }

    private static long booked(ServerPlayer p, ServerLevel level, PlayerFinance.Cat cat) {
        long day = level.getDayTime() / 24000L;
        return PlayerFinance.span(p.getUUID(), day, 1).getOrDefault(cat, 0L);
    }

    // ============================================================ the whole way, as a newcomer

    /**
     * A newcomer nobody knows, with thirty coins: Start a business offers a shop of its own first (no charter, so no
     * standing wanted), a building of its own first; it buys a Shop Counter of the town's carpenters (out of its purse into
     * the treasury, booked as goods bought, the town's coin counted the same), sets it in its room, registers the shop,
     * stocks it with stone hoes, and a farmer with no hoe buys one at the counter: the coin in the till and in the player's
     * books as shop takings. Every step on the way, the shop's next step is named, and when it has sold, there is none.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bf01_newcomer_to_first_sale")
    public static void bf01_newcomer_to_first_sale(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 5600000;
        List<VillageFolkEntity> folk = town(helper, level, x, 3);
        level.setDayTime(1000);
        helper.runAtTickTime(10, () -> {
            UUID village = folk.get(0).ownerId();
            BlockPos heart = Kit.surface(level, x, Z);
            VillageFolkEntity farmer = farmerWithoutHoe(folk.get(1));
            ServerPlayer p = player(helper, heart.offset(7, 0, 0), 30);
            // 1. The flow, opened from Your life in town's "Open a shop".
            PlayerLife.open(p, "life", null);
            helper.assertTrue(page(p).contains("Open a shop"), "Your life in town offers a shop: " + page(p));
            PlayerLife.act(p, "life", "start", args("kind", "shop"));
            PlayerLife.Ctx c = PlayerLife.ctx(p);
            helper.assertTrue(c.page.equals("start") && c.str("kind").equals("shop"), "the flow, on a shop of your own: " + c.page + " " + c.args);
            String kinds = page(p);
            helper.assertTrue(kinds.contains("A shop of your own"), "the shop's row heads the kinds");
            helper.assertTrue(kinds.contains("Nobody in") && kinds.contains("say hello"), "a stranger is told up front what a charter wants");
            PlayerLife.act(p, "start", "step", args("s", 2));
            helper.assertTrue(c.str("prem").equals("own") && page(p).contains("A building of your own") && page(p).contains("Have the town build it"),
                "premises: its own building first, the town's builders too");
            PlayerLife.act(p, "start", "step", args("s", 3));
            String confirm = page(p);
            int price = BizGuide.counterPrice(level, village);
            Kit.log("[VT] bf01 counter " + price + "c; confirm page " + confirm.length());
            helper.assertTrue(confirm.contains("To pay now") && confirm.contains("Buy the counter"), "step 3 says what is paid now, and the button");
            // 2. The counter bought.
            int treasury = com.jrpetty.mcassistant.village.Ledger.coins(village);
            long census = CoinCensus.count(village);
            PlayerLife.act(p, "start", "confirm", new CompoundTag());
            Kit.log("[VT] bf01 confirm: " + PlayerLife.messageForTests(p) + "; coins " + coins(p) + "; treasury " + treasury + " -> "
                + com.jrpetty.mcassistant.village.Ledger.coins(village) + "; census " + census + " -> " + CoinCensus.count(village));
            helper.assertTrue(BizGuide.carriesCounter(p) && coins(p) == 30 - price, "the counter in the pack, its price out of the purse");
            helper.assertTrue(com.jrpetty.mcassistant.village.Ledger.coins(village) == treasury + price, "into the treasury");
            helper.assertTrue(CoinCensus.count(village) == census + price * 100L, "the town's coin up by what the player paid in (pennies)");
            helper.assertTrue(booked(p, level, PlayerFinance.Cat.PURCHASES) == -price * 100L, "booked as goods bought: " + booked(p, level, PlayerFinance.Cat.PURCHASES));
            helper.assertTrue(c.args.getInt("step") == 4 && page(p).contains("Set your Shop Counter down"), "then what next, step by step");
            // 3. Set down in its room: the shop, its setup.
            room(level, heart);
            setCounter(level, heart, p);
            PlayerShops.Shop s = PlayerShops.of(village, p.getUUID());
            helper.assertTrue(s != null, "the counter makes a shop");
            helper.assertTrue(nextId(level, village, p).equals("register"), "next: name and register it: " + nextId(level, village, p));
            CompoundTag form = args("name", "Dev's Hoes", "kind", "tools", "opens", "6", "closes", "20", "tell", "yes");
            level.setBlockAndUpdate(heart.offset(10, 0, -2), Blocks.FURNACE.defaultBlockState());   // a tool shop's forge
            PlayerLife.act(p, "shop", "setup", args("f", form));
            Kit.log("[VT] bf01 setup: " + PlayerLife.messageForTests(p));
            helper.assertTrue(s.licensed && s.setUp, "registered");
            helper.assertTrue(nextId(level, village, p).equals("needs"), "next: what it still needs (a chest): " + nextId(level, village, p));
            helper.assertTrue(page(p).contains("Next: Everything a shop needs"), "the shop's screen names the next step");
            // 4. Stocked with what a folk here needs.
            Container box = chest(level, chestAt(heart), new ItemStack(Items.STONE_HOE), new ItemStack(Items.STONE_HOE));
            PlayerShops.resurvey(level, s);
            String now = nextId(level, village, p);
            Kit.log("[VT] bf01 after stocking: next " + now + "; state " + PlayerShops.stateWords(level, s));
            helper.assertTrue(now.equals("sale"), "stocked, open: next is its first sale: " + now);
            PlayerLife.act(p, "shop", "tab:stock", new CompoundTag());
            helper.assertTrue(page(p).contains("buyers in town"), "the Stock tab says how many wares have buyers");
            // 5. A folk buys.
            String bought = ShopCustomers.buyForTests(level, farmer, s);
            Kit.log("[VT] bf01 sale: " + bought + "; till " + s.till + "; booked " + booked(p, level, PlayerFinance.Cat.SHOP));
            helper.assertTrue(bought != null && s.till > 0 && count(box, Items.STONE_HOE) == 1, "a farmer bought a hoe at the counter: " + bought);
            helper.assertTrue(booked(p, level, PlayerFinance.Cat.SHOP) == s.till * 100L, "the takings in the player's books as they came in");
            helper.assertTrue(nextId(level, village, p).isEmpty(), "up and selling: no next step");
            PlayerLife.act(p, "shop", "tab:shop", new CompoundTag());
            helper.assertTrue(!page(p).contains("Getting started"), "the first steps are gone once it sells");
            PlayerLife.open(p, "life", null);
            helper.assertTrue(page(p).contains("Dev's Hoes") && !page(p).contains("Next: "), "Your life in town shows the shop, no step left");
            helper.succeed();
        });
    }

    // ============================================================ a keeper, a slow day, the rates, sold out

    /**
     * The rates out of the till each week (booked as taxes); a keeper taken on through the Keeper tab's button, its sums
     * against the takings said; a slow day with nothing sold leaves its wage owed, and after the third it goes; the last of
     * a ware sold puts it on the sold-out list and the first steps ask for stock again.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bf02_keeper_rates_sold_out")
    public static void bf02_keeper_rates_sold_out(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 5605000;
        List<VillageFolkEntity> folk = town(helper, level, x, 4);
        level.setDayTime(1000);
        helper.runAtTickTime(10, () -> {
            UUID village = folk.get(0).ownerId();
            Villages.Village v = Villages.get(village);
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart.offset(7, 0, 0), 30);
            room(level, heart);
            Container box = chest(level, chestAt(heart), new ItemStack(Items.STONE_HOE));
            level.setBlockAndUpdate(counterAt(heart), McAssistantMod.SHOP_COUNTER.get().defaultBlockState()
                .setValue(com.jrpetty.mcassistant.block.ShopCounterBlock.FACING, Direction.WEST));
            PlayerShops.Shop s = PlayerShops.registerForTests(level, counterAt(heart), p, "general");
            // The rates: due a week on, out of the till.
            TaxBook.set(level, v, TaxBook.Lever.RATES, 2, "the council");
            int rates = TaxBook.ratesCoins(village);
            long day = level.getDayTime() / 24000L;
            s.till = 10;
            s.ratesDay = day - 7;
            PlayerShops.ratesForTests(level, s);
            Kit.log("[VT] bf02 rates " + rates + ": till " + s.till + ", taxes " + booked(p, level, PlayerFinance.Cat.TAXES));
            helper.assertTrue(rates > 0 && s.till == 10 - rates, "the week's rates out of the till: " + s.till);
            helper.assertTrue(booked(p, level, PlayerFinance.Cat.TAXES) == -rates * 100L, "booked as taxes");
            // A keeper, by the Keeper tab's button.
            VillageFolkEntity k = folk.get(3);
            k.setJob(AssistantEntity.StationTask.NONE);
            List<ShopKeepers.Candidate> cs = ShopKeepers.candidates(level, village, 8);
            StringBuilder why = new StringBuilder();
            for (VillageFolkEntity f : folk) why.append(f.displayNameCap()).append(" ").append(f.stationTask()).append(f.isHired() ? " hired" : "").append(f.isShowcase() ? " showcase" : "").append("; ");
            Kit.log("[VT] bf02 candidates " + cs.size() + ": " + why);
            PlayerLife.open(p, "shop", args("tab", "keeper"));
            if (!cs.isEmpty()) {
                helper.assertTrue(page(p).contains("a keeper asks") || page(p).contains("hasn't taken anything"), "the keeper's wage set against the takings: " + page(p));
                PlayerLife.act(p, "shop", "hire", args("u", cs.get(0).folk().getUUID().toString()));   // the Keeper tab's button
            } else {
                ShopKeepers.hireForTests(level, s, k);                       // a test town spares nobody by the labour market's lights
            }
            helper.assertTrue(s.keeper != null, "taken on: " + PlayerLife.messageForTests(p));
            int wage = s.wage;
            // Slow days: nothing sold, the till empty: its wage owed, then it goes.
            s.till = 0;
            ShopKeepers.paydayForTests(level, s);
            helper.assertTrue(s.keeper != null && s.owed == wage, "a slow day: its wage owed: " + s.owed);
            ShopKeepers.paydayForTests(level, s);
            ShopKeepers.paydayForTests(level, s);
            Kit.log("[VT] bf02 after three slow days: keeper " + s.keeper + ", owed " + s.owed);
            helper.assertTrue(s.keeper == null, "three days unpaid and it has gone");
            // Sold out.
            VillageFolkEntity farmer = farmerWithoutHoe(folk.get(1));
            String bought = ShopCustomers.buyForTests(level, farmer, s);
            helper.assertTrue(bought != null && count(box, Items.STONE_HOE) == 0, "its last hoe sold: " + bought);
            helper.assertTrue(s.soldOut.containsKey(com.jrpetty.mcassistant.entity.PlayerStalls.key(new ItemStack(Items.STONE_HOE))), "on the sold-out list");
            VillageFolkEntity second = farmerWithoutHoe(folk.get(2));
            helper.assertTrue(ShopCustomers.buyForTests(level, second, s) == null, "nothing left for the next farmer");
            BizGuide.Step stock = null;
            for (BizGuide.Step st : BizGuide.steps(level, v, s)) if (st.id().equals("stock")) stock = st;
            Kit.log("[VT] bf02 stock step: " + (stock == null ? null : stock.line() + " / " + stock.hint()));
            helper.assertTrue(stock != null && !stock.done() && stock.hint().contains("Put what you'd sell"), "the first steps ask for stock again");
            helper.succeed();
        });
    }

    // ============================================================ the edges

    /**
     * A ware nobody here has a use for: the first steps say so and what the town does want, and no folk buys it. Too
     * little coin for the counter: refused, saying the price, nothing taken. A shop marked by a sign with no counter stays
     * shut and its next step names the counter. A chartered business: a stranger is refused, says hello to a folk (one
     * button), and is chartered.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bf03_edges")
    public static void bf03_edges(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 5610000;
        List<VillageFolkEntity> folk = town(helper, level, x, 10);
        level.setDayTime(1000);
        helper.runAtTickTime(10, () -> {
            UUID village = folk.get(0).ownerId();
            Villages.Village v = Villages.get(village);
            Villages.ageForTests(village, Villages.Age.STONE);
            BlockPos heart = Kit.surface(level, x, Z);
            // Too little coin.
            ServerPlayer poor = player(helper, heart.offset(-6, 0, 6), 1);
            PlayerLife.open(poor, "start", args("kind", "shop"));
            PlayerLife.act(poor, "start", "step", args("s", 3));
            PlayerLife.act(poor, "start", "confirm", new CompoundTag());
            String refused = PlayerLife.messageForTests(poor);
            Kit.log("[VT] bf03 one coin: " + refused);
            helper.assertTrue(refused.startsWith("!") && refused.contains(Integer.toString(BizGuide.counterPrice(level, village))) && coins(poor) == 1
                && !BizGuide.carriesCounter(poor), "too little coin: refused with the price, nothing taken");
            // A sign, no counter.
            ServerPlayer p = player(helper, heart.offset(7, 0, 0), 30);
            room(level, heart);
            BlockPos sign = heart.offset(6, 1, -2);
            level.setBlockAndUpdate(sign, McAssistantMod.SHOP_SIGN.get().defaultBlockState()
                .setValue(com.jrpetty.mcassistant.block.ShopSignBlock.FACING, Direction.SOUTH));
            Container box = chest(level, chestAt(heart), new ItemStack(Items.DEAD_BUSH, 16));
            PlayerShops.placed(level, sign, p, false);
            PlayerShops.Shop s = PlayerShops.of(village, p.getUUID());
            PlayerLife.act(p, "shop", "setup", args("f", args("name", "Bushes", "kind", "general", "opens", "6", "closes", "20")));
            BizGuide.Step next = BizGuide.next(BizGuide.steps(level, v, s));
            Kit.log("[VT] bf03 no counter: next " + (next == null ? null : next.id() + " / " + next.hint()) + "; " + PlayerShops.stateWords(level, s));
            helper.assertTrue(next != null && next.id().equals("needs") && next.hint().contains("counter"), "its next step names the counter");
            helper.assertTrue(PlayerShops.state(level, s) == PlayerShops.State.INCOMPLETE, "shut till it has one");
            // The counter: now the useless ware is what stops it.
            level.setBlockAndUpdate(counterAt(heart), McAssistantMod.SHOP_COUNTER.get().defaultBlockState()
                .setValue(com.jrpetty.mcassistant.block.ShopCounterBlock.FACING, Direction.WEST));
            PlayerShops.placed(level, counterAt(heart), p, true);
            PlayerShops.resurvey(level, s);
            next = BizGuide.next(BizGuide.steps(level, v, s));
            Kit.log("[VT] bf03 dead bushes: next " + (next == null ? null : next.id() + " / " + next.hint()));
            helper.assertTrue(next != null && next.id().equals("stock") && next.hint().contains("Nobody here has a use"), "nobody wants dead bushes, and it says so");
            for (VillageFolkEntity f : folk) helper.assertTrue(ShopCustomers.buyForTests(level, f, s) == null, "no folk buys a thing it has no use for");
            helper.assertTrue(s.till == 0 && count(box, Items.DEAD_BUSH) == 16, "nothing sold");
            // A charter: a stranger says hello, and is chartered.
            ServerPlayer founder = player(helper, folk.get(2).blockPosition(), 30);
            String stranger = StartBusiness.startForTests(founder, "baker", "town");
            Kit.log("[VT] bf03 stranger: " + stranger);
            helper.assertTrue(stranger.startsWith("!") && stranger.contains("knows you"), "a stranger is told to say hello first: " + stranger);
            PlayerLife.act(founder, "start", "hello", new CompoundTag());
            Kit.log("[VT] bf03 hello: " + PlayerLife.messageForTests(founder));
            String ok = StartBusiness.startForTests(founder, "baker", "town");
            Kit.log("[VT] bf03 chartered: " + ok);
            Business b = Businesses.of(village, PlayerLife.ctx(founder).str("firm"));
            helper.assertTrue(!ok.startsWith("!") && b != null && b.ownedBy(founder.getUUID()), "known now, it is chartered: " + ok);
            helper.succeed();
        });
    }

    // ============================================================ the town builds it

    /**
     * A shop of your own the town builds: the flow picks a drawing a plot fits, step 3 gives the deposit, Order it places
     * the builders' order (the deposit out of the purse); built, its counter and chest set in, it is the player's shop and
     * its next step is to register it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "bf04_town_builds_the_shop")
    public static void bf04_town_builds_the_shop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 5615000;
        List<VillageFolkEntity> folk = town(helper, level, x, 6);
        BuildOrders.resetForTests();
        PropertyMarket.resetForTests();
        level.setDayTime(1000);
        helper.runAtTickTime(10, () -> {
            UUID village = folk.get(0).ownerId();
            Villages.Village v = Villages.get(village);
            BlockPos heart = Kit.surface(level, x, Z);
            builderStores(level, heart);
            Villages.forgetStores(village);
            ServerPlayer p = player(helper, heart, 64);
            for (int i = 1; i < 4; i++) p.getInventory().setItem(i, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 64));
            PlayerLife.open(p, "start", args("kind", "shop"));
            PlayerLife.act(p, "start", "step", args("s", 2));
            PlayerLife.act(p, "start", "prem", args("p", "build"));
            PlayerLife.Ctx c = PlayerLife.ctx(p);
            page(p);                                                          // the gallery sets the plot
            Kit.log("[VT] bf04 drawing " + c.str("bp") + " plot " + c.args.contains("px"));
            helper.assertTrue(!c.str("bp").isEmpty() && c.args.contains("px"), "a drawing with a plot chosen for it");
            PlayerLife.act(p, "start", "step", args("s", 3));
            helper.assertTrue(page(p).contains("Order it"), "step 3: order it");
            int had = coins(p);
            PlayerLife.act(p, "start", "confirm", new CompoundTag());
            String id = c.str("order");
            Kit.log("[VT] bf04 ordered " + id + ": " + PlayerLife.messageForTests(p) + "; coins " + had + " -> " + coins(p));
            helper.assertTrue(!id.isEmpty() && coins(p) < had, "ordered, its deposit paid");
            for (int i = 0; i < 20 * 12; i++) {
                CompoundTag o = BuildOrders.orderForTests(id);
                if (o == null || !o.getString("status").equals("open") && !o.getString("status").equals("awaiting")) break;
                if (o.getString("status").equals("awaiting")) BuildOrders.pay(level, p, id);
                BuildOrders.hoursForTests(level, v, 2);
            }
            BuildDesk.Status st = BuildDesk.status(level, id);
            helper.assertTrue(st != null && st.done(), "it stands: " + (st == null ? null : st.stage() + " " + st.waitingOn()));
            BuildDesk.tick(level, v);
            PlayerShops.Shop s = PlayerShops.of(village, p.getUUID());
            helper.assertTrue(s != null, "built, it is the player's shop");
            helper.assertTrue(nextId(level, village, p).equals("register"), "and its next step is to register it: " + nextId(level, village, p));
            helper.succeed();
        });
    }

    /** The town's stores with what the builders lay, as the build orders' tests stock them. */
    private static void builderStores(ServerLevel level, BlockPos heart) {
        net.minecraft.world.item.Item[] goods = { Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS,
            Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_LOG, Items.OAK_LOG, Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE,
            Items.STONE_BRICKS, Items.STONE_BRICKS, Items.GLASS, Items.GLASS_PANE, Items.TORCH, Items.LANTERN, Items.WHITE_WOOL,
            Items.RED_CARPET, Items.POPPY, Items.BARREL, Items.FURNACE, Items.SMOKER, Items.CRAFTING_TABLE, Items.CHEST, Items.OAK_STAIRS,
            Items.OAK_SLAB, Items.OAK_DOOR, Items.OAK_FENCE, Items.SPRUCE_PLANKS, Items.SPRUCE_LOG, Items.SPRUCE_STAIRS, Items.SPRUCE_SLAB };
        List<ItemStack> stacks = new ArrayList<>();
        for (net.minecraft.world.item.Item it : goods) stacks.add(new ItemStack(it, new ItemStack(it).getMaxStackSize()));
        for (int c = 0; c * 27 < stacks.size(); c++) {
            BlockPos chest = heart.offset(-6 + 2 * c, 0, -8);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
            com.jrpetty.mcassistant.entity.ZoneChests.mark(level, chest);
            Container box = (Container) level.getBlockEntity(chest);
            for (int k = 0; k < 27 && c * 27 + k < stacks.size(); k++) box.setItem(k, stacks.get(c * 27 + k));
            box.setChanged();
        }
    }

    // ============================================================ sold with its stock, and repurposed

    /**
     * The shop's building put up for sale with its stock, another player pays the asking price, the seller accepts: the
     * price to the seller, the building the buyer's, the stock left in its chest for the buyer, and the shop the seller's no
     * longer (its till paid out to the seller, its keeper gone). A building is quoted for a new use, with what that use wants.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "bf05_sell_with_stock_repurpose")
    public static void bf05_sell_with_stock_repurpose(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 5620000;
        List<VillageFolkEntity> folk = town(helper, level, x, 4);
        PropertyMarket.resetForTests();
        level.setDayTime(1000);
        helper.runAtTickTime(10, () -> {
            UUID village = folk.get(0).ownerId();
            BlockPos heart = Kit.surface(level, x, Z);
            ServerPlayer p = player(helper, heart.offset(7, 0, 0), 30);
            room(level, heart);
            Container box = chest(level, chestAt(heart), new ItemStack(Items.STONE_HOE, 1), new ItemStack(Items.STONE_HOE, 1), new ItemStack(Items.STONE_HOE, 1));
            level.setBlockAndUpdate(counterAt(heart), McAssistantMod.SHOP_COUNTER.get().defaultBlockState()
                .setValue(com.jrpetty.mcassistant.block.ShopCounterBlock.FACING, Direction.WEST));
            PlayerShops.Shop s = PlayerShops.registerForTests(level, counterAt(heart), p, "general");
            s.till = 7;
            String key = PropertyDesk.register(level, s);
            helper.assertTrue(key != null, "the shop's building is on the register");
            // Repurposed: a quote for a bakery.
            BuildOrders.Quote refit = Refits.quote(level, village, key, "bakery");
            Kit.log("[VT] bf05 refit: " + (refit == null ? null : refit.ok + " " + refit.total + " " + refit.why) + "; needs " + Refits.needs("bakery"));
            helper.assertTrue(refit != null && !Refits.needs("bakery").isEmpty(), "a bakery's refit quoted, with what a bakery wants");
            // Sold with its stock.
            String listed = PropertyMarket.list(level, p, village, key, false, 40, true);
            Kit.log("[VT] bf05 listed: " + listed);
            ServerPlayer buyer = player(helper, heart.offset(-6, 0, 6), 50);
            String offered = PropertyMarket.buy(level, buyer, village, key);
            Kit.log("[VT] bf05 offer: " + offered);
            int had = coins(p);
            long census = CoinCensus.count(village);
            String sold = PropertyMarket.accept(level, p, village, key, 1);
            Kit.log("[VT] bf05 sold: " + sold + "; seller " + had + " -> " + coins(p) + "; buyer " + coins(buyer) + "; census " + census + " -> " + CoinCensus.count(village));
            helper.assertTrue(sold.startsWith("Sold"), "the sale went through: " + sold);
            helper.assertTrue(PropertyMarket.ownedBy(village, buyer.getUUID()).stream().anyMatch(pr -> pr.key().equals(key)), "the building is the buyer's");
            helper.assertTrue(PlayerShops.of(village, p.getUUID()) == null, "the shop is the seller's no longer");
            helper.assertTrue(coins(p) >= had + 7, "its till paid out to the seller (and the price on top or at the hall): " + had + " -> " + coins(p));
            helper.assertTrue(count(box, Items.STONE_HOE) == 3, "its stock stayed in its chest, sold with it");
            helper.assertTrue(sold.contains("closed"), "the seller told its shop is closed: " + sold);
            helper.succeed();
        });
    }
}
