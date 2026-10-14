package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.client.OutfitIndex;
import com.jrpetty.mcassistant.client.TalkMenu;
import com.jrpetty.mcassistant.client.TalkMenu.Context;
import com.jrpetty.mcassistant.client.TalkMenu.Entry;
import com.jrpetty.mcassistant.entity.AdminTools;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Cartographers;
import com.jrpetty.mcassistant.entity.Crafts;
import com.jrpetty.mcassistant.entity.Divers;
import com.jrpetty.mcassistant.entity.EmeraldTrader;
import com.jrpetty.mcassistant.entity.Ferries;
import com.jrpetty.mcassistant.entity.Fleet;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.OutdoorBases;
import com.jrpetty.mcassistant.entity.OutdoorShifts;
import com.jrpetty.mcassistant.entity.OutdoorStage;
import com.jrpetty.mcassistant.entity.OutdoorTalk;
import com.jrpetty.mcassistant.entity.OutdoorWeather;
import com.jrpetty.mcassistant.entity.Raids;
import com.jrpetty.mcassistant.entity.Seasons;
import com.jrpetty.mcassistant.entity.ShiftRole;
import com.jrpetty.mcassistant.entity.Shifts;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkStock;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Nameable;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [roles-d] The outdoor and resource trades finished (OutdoorTalk, OutdoorBases, OutdoorWeather, OutdoorShifts, and the
 * hooks in Ferries, Fleet, Engineers and Golems).
 *
 * <ul>
 * <li><b>od01</b>: every trade but the crafts (Batch B's) has an option of its own in the talk menu, suggested to a folk of
 *     it, with its words and tooltip in the language file, and its words are understood by that trade; every trade has
 *     its own outfit and an admin "people trade" word.</li>
 * <li><b>od02</b>: a player buys fresh fish from the fisher: the fish leave the stores, the coin goes into the treasury;
 *     the farmer says what is growing; with no hides in the stores the hunter says it can't spare any and takes nothing;
 *     a fisher with no fleet says so, and with a fleet books the player into its boat.</li>
 * <li><b>od03</b>: a player asks the ferryman to take it across: handed in behind it, rowed over, set down on the far
 *     landing, a coin of fare paid.</li>
 * <li><b>od04</b>: a golem for the player's base: short of iron, told so and nothing taken; with four blocks' worth and a
 *     pumpkin, an iron golem stands up beside it, the player's iron gone, the keeper's fee paid, the golem the player's.</li>
 * <li><b>od05</b>: the hunter's lodge and the barn: wanted when they should be; their chests are their trades' makings;
 *     the hunter loads its game into the smoker with the chest's coal and takes the smoked meat out; the rancher fetches
 *     the herd's feed from the barn; the scouts' desk at the map room's table.</li>
 * <li><b>od06</b>: the woodcutter and the hunter come in out of the rain (and a hunting party is refused or called off
 *     by it), and in at the bell; the beekeeper leaves the hives' honey in winter.</li>
 * <li><b>od07</b>: the paid shifts: a woodcutter's hand fells a marked tree into the stores and plants it back; a
 *     shearer shears a marked sheep of the flock, the wool to the stores.</li>
 * <li><b>od08</b>: "/village outdoor stage" sets out every view for the pictures; "/village outdoor" reads the books.</li>
 * </ul>
 * Band x 2,020,000 to 2,039,999, z 66,000; a batch each.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class OutdoorTradesGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;
    private static final long DAY = 24000L * 4;

    // ------------------------------------------------------------------ helpers

    /** Flat grass round here, clear air above it. Returns the ground's top (the first air). */
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

    /** A clean slate and a village of these trades (the first the founder) on flat ground at x, clear weather, at this time. */
    private static List<VillageFolkEntity> town(GameTestHelper helper, int x, long time, StationTask... trades) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        Shifts.resetForTests();
        level.setDayTime(time);
        level.setWeatherParameters(24000, 0, false, false);
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = flat(level, x, Z, 40);
        List<VillageFolkEntity> out = new ArrayList<>();
        for (int i = 0; i < trades.length; i++) {
            BlockPos at = i == 0 ? heart : Kit.surface(level, x + 2 + (i % 5), Z + 2 + i / 5);
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
            helper.assertTrue(f != null && (out.isEmpty() || out.get(0).ownerId().equals(f.ownerId())), "folk " + i + " of the village");
            if (i > 0) f.setAgeForTests(30 + i);
            f.insertItem(new ItemStack(Items.COOKED_BEEF, 16));
            out.add(f);
        }
        return out;
    }

    /** Each folk to its trade, now (in the test's first tick: a trade set at the raising may be traded away before it). */
    private static void jobs(List<VillageFolkEntity> folk, StationTask... trades) {
        for (int i = 0; i < trades.length; i++) if (trades[i] != StationTask.NONE) folk.get(i).setJob(trades[i]);
    }

    private static Container chestAt(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        Villages.forgetStock();
        return box;
    }

    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        Villages.forgetStock();
        return Market.stock(level, village, what);
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos at, int coins) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getAbilities().instabuild = false;
        p.setGameMode(GameType.SURVIVAL);
        p.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        if (coins > 0) p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
        return p;
    }

    private static int carried(ServerPlayer p, Predicate<ItemStack> what) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && what.test(s)) n += s.getCount();
        return n;
    }

    private static BlockPos stamp(ServerLevel level, UUID village, String structure, BlockPos at) {
        BlockPos p = Kit.surface(level, at.getX(), at.getZ());
        BuildGoal.stamp(level, structure, p, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(village, structure, p, Direction.NORTH);
        return p;
    }

    private static BlockPos part(String structure, BlockPos anchor, BuildGoal.Part part) {
        for (BuildGoal.Placement p : BuildGoal.plan(structure, anchor, Direction.NORTH, 13)) if (p.part() == part) return p.pos();
        return null;
    }

    private static String say(VillageFolkEntity f, ServerPlayer p, String words) {
        return FolkTalk.answer(f, p, TalkTopic.SAY, words);
    }

    // ============================================================ od01: every trade's own option

    /** The crafts' own options are Batch B's (roles-b): the tailor, the brewer, the enchanter and the beekeeper. */
    private static final Set<StationTask> CRAFTS = EnumSet.of(StationTask.TAILOR, StationTask.BREW, StationTask.ENCHANT, StationTask.BEEKEEP);
    /** This batch's trades, whose options' words OutdoorTalk (or the emerald trader's and the diver's own talk) hears. */
    private static final Set<StationTask> OUTDOOR = EnumSet.of(StationTask.FARM, StationTask.WOOD, StationTask.FISH, StationTask.HUNT,
        StationTask.RANCH, StationTask.FERRY, StationTask.GOLEMS, StationTask.REDSTONE, StationTask.MINE, StationTask.FLETCHER,
        StationTask.EMERALD, StationTask.DIVER);

    @GameTest(template = EMPTY, timeoutTicks = 40, batch = "od01_menu")
    public static void od01_menu(GameTestHelper helper) {
        List<String> missing = new ArrayList<>(), unheard = new ArrayList<>();
        Set<String> ids = new java.util.HashSet<>(TalkMenu.ids());
        for (StationTask t : StationTask.values()) {
            if (t == StationTask.NONE || CRAFTS.contains(t)) continue;
            Context c = Context.of("v1 trade=" + t.name() + " standing=FRIEND", 0, false, false, false);
            List<Entry> own = TalkMenu.suggested(c);
            if (own.isEmpty()) { missing.add(t.name()); continue; }
            Entry e = own.get(own.size() - 1);
            helper.assertTrue(ids.contains(e.id()), t + "'s option is in the menu: " + e.id());
            if (OUTDOOR.contains(t) && (e.kind() == TalkMenu.Kind.SAY || e.kind() == TalkMenu.Kind.PROMPT)) {
                boolean heard = switch (t) {
                    case EMERALD -> EmeraldTrader.meant(e.text());
                    case DIVER -> Divers.meant(e.text());
                    default -> OutdoorTalk.ask(t, e.text()) != null;
                };
                if (!heard) unheard.add(t + ": \"" + e.text() + "\"");
            }
        }
        // Every phrase of the outdoor trades' own options is heard by its trade.
        String[][] phrases = {
            { "FARM", "What's growing in your fields?" }, { "FARM", "Could I buy some of your produce?" }, { "WOOD", "I'd like a timber order" },
            { "FISH", "Could I buy some fresh fish?" }, { "FISH", "Can I come out on the boat with you?" },
            { "HUNT", "Can I come hunting with you?" }, { "HUNT", "Could I buy leather and feathers?" }, { "RANCH", "Could I buy some wool?" },
            { "RANCH", "Could I buy a horse?" }, { "FERRY", "Take me across, please" }, { "GOLEMS", "Could you build a golem for my base?" },
            { "REDSTONE", "Could you light my street?" }, { "MINE", "Could I buy some coal or ore?" }, { "FLETCHER", "Could I buy some arrows?" } };
        Set<String> said = TalkMenu.phrases();
        for (String[] ph : phrases) {
            helper.assertTrue(said.contains(ph[1].toLowerCase(java.util.Locale.ROOT)), "the menu says \"" + ph[1] + "\"");
            if (OutdoorTalk.ask(StationTask.valueOf(ph[0]), ph[1]) == null) unheard.add(ph[0] + ": \"" + ph[1] + "\"");
        }
        helper.assertTrue(OutdoorTalk.ask(StationTask.FARM, "Could I have 8 bread?") == null, "the stores' words are not the farmer's");
        helper.assertTrue(OutdoorTalk.ask(StationTask.RANCH, "Tell me about your horse") == null
            && OutdoorTalk.ask(StationTask.HUNT, "Where do you hide when it rains?") == null
            && OutdoorTalk.ask(StationTask.REDSTONE, "Do you like lamps?") == null
            && OutdoorTalk.ask(StationTask.WOOD, "What wood is best?") == null, "talk of a thing buys nothing: a sale is asked for");
        helper.assertTrue(Divers.meant("Have you got my clay yet?") && Divers.meant("Could you dive for 16 clay?"), "the diver's buttons are its words");
        helper.assertTrue(EmeraldTrader.meant("What do the villagers sell?") && EmeraldTrader.meant("Find us a Mending book"),
            "the emerald trader's buttons are its words");
        // Every trade wears its own clothes and answers to an admin word.
        List<String> plain = new ArrayList<>();
        for (StationTask t : StationTask.values()) {
            if (t == StationTask.NONE) continue;
            int o = OutfitIndex.of(t.ordinal());
            if (o <= 0 || o >= OutfitIndex.NAMES.length) plain.add(t.name());
            helper.assertTrue(AdminTools.trade(t.name().toLowerCase(java.util.Locale.ROOT)) == t, "an admin word for " + t);
        }
        Kit.log("od01 missing " + missing + "; unheard " + unheard + "; plain " + plain);
        helper.assertTrue(missing.isEmpty(), "every trade but the crafts has an option of its own: missing " + missing);
        helper.assertTrue(unheard.isEmpty(), "every option's words are heard by its trade: " + unheard);
        helper.assertTrue(plain.isEmpty(), "every trade its own outfit: " + plain);
        helper.succeed();
    }

    // ============================================================ od02: fish, the field, hides, the boat

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "od02_sales")
    public static void od02_sales(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2020000;
        StationTask[] trades = { StationTask.FARM, StationTask.FISH, StationTask.HUNT, StationTask.NONE };
        List<VillageFolkEntity> folk = town(helper, x, DAY + 2000, trades);
        UUID id = folk.get(0).ownerId();
        VillageFolkEntity farmer = folk.get(0), fisher = folk.get(1), hunter = folk.get(2);
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            jobs(folk, trades);
            chestAt(level, heart.offset(-4, 0, -4), new ItemStack(Items.COD, 64), new ItemStack(Items.COD, 64), new ItemStack(Items.COD, 64),
                new ItemStack(Items.COD, 64), new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64),
                new ItemStack(Items.WHEAT, 64), new ItemStack(Items.WHEAT, 64), new ItemStack(Items.WHEAT, 64));
            com.jrpetty.mcassistant.entity.Budget.forget(id);
            // The farmer's field: wheat, two rows of it ripe.
            BlockPos field = heart.offset(16, 0, 0);
            farmer.assignPlot(WorkZone.around(field, 4, WorkZone.DEFAULT_DEPTH), "The field");
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos c = field.offset(dx, 0, dz);
                    level.setBlock(c.below(), Blocks.FARMLAND.defaultBlockState(), 3);
                    level.setBlock(c, ((CropBlock) Blocks.WHEAT).getStateForAge(dz <= -1 ? 7 : 3), 3);
                }
            }
            ServerPlayer pl = player(helper, fisher.blockPosition().east(), 60);
            int cod0 = stock(level, id, s -> s.is(Items.COD)), treasury0 = Ledger.coins(id), coins0 = Market.coinsHeld(pl);
            String fish = say(fisher, pl, "Could I buy some fresh fish?");
            int cod1 = stock(level, id, s -> s.is(Items.COD)), bought = carried(pl, s -> s.is(Items.COD));
            int spent = coins0 - Market.coinsHeld(pl), treasury1 = Ledger.coins(id);
            String growing = say(farmer, pl, "What's growing in your fields?");
            int coins1 = Market.coinsHeld(pl);
            String hides = say(hunter, pl, "Could I buy leather and feathers?");
            String noFleet = say(fisher, pl, "Can I come out on the boat with you?");
            Fleet.fromForTests(1);
            Fleet.quayForTests(Villages.get(id), new com.jrpetty.mcassistant.entity.Waterfront.Dock(heart.offset(0, -1, 20), Direction.SOUTH, 6));
            String boat = say(fisher, pl, "Can I come out on the boat with you?");
            String booked = Fleet.guestForTests(fisher);
            Fleet.fromForTests(-1);
            Kit.log("od02 fish: '" + fish + "' (cod " + cod0 + " -> " + cod1 + ", bought " + bought + ", " + spent + " coins, treasury " + treasury0
                + " -> " + treasury1 + "); growing: '" + growing + "'; hides: '" + hides + "'; no fleet: '" + noFleet + "'; fleet: '" + boat
                + "' booked '" + booked + "'");
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(bought > 0 && cod1 == cod0 - bought, "the fish out of the stores into the player's pack: " + bought + ", " + cod0 + " -> " + cod1);
            helper.assertTrue(spent > 0 && treasury1 - treasury0 == spent, "paid for, into the treasury: " + spent + " / " + (treasury1 - treasury0));
            helper.assertTrue(growing.contains("wheat") && growing.contains("ripe"), "the farmer tells what is growing and what is ripe: " + growing);
            helper.assertTrue(hides.contains("can't spare") && Market.coinsHeld(pl) == coins1, "no hides in the stores: told so, nothing taken: " + hides);
            helper.assertTrue(noFleet.contains("no fleet"), "no fleet: the fisher says so: " + noFleet);
            helper.assertTrue(booked.startsWith(pl.getName().getString()), "with a fleet, the player booked into its boat: " + booked + " / " + boat);
            helper.succeed();
        });
    }

    // ============================================================ od03: across on the ferry

    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "od03_ferry")
    public static void od03_ferry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2022000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 2000, StationTask.NONE, StationTask.NONE);
        UUID id = folk.get(0).ownerId();
        VillageFolkEntity man = folk.get(1);
        BlockPos heart = Kit.surface(level, x, Z);
        int y = heart.getY(), x0 = x + 24;
        for (int dx = 0; dx < 10; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                level.setBlock(new BlockPos(x0 + dx, y - 1, Z + dz), Blocks.WATER.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x0 + dx, y - 2, Z + dz), Blocks.WATER.defaultBlockState(), 2);
            }
        }
        Ferries.weatherForTests(false);
        Ferries.Crossing c = Ferries.setForTests(id, new BlockPos(x0 - 1, y - 1, Z), new BlockPos(x0 + 10, y - 1, Z), Direction.EAST, y - 1, "the far bank");
        Ferries.appointForTests(level, Villages.get(id), man);
        Ferries.buildFreeForStage(level, Villages.get(id), c);
        Boat boat = Ferries.boatForTests(level, id);
        helper.assertTrue(c.state() == Ferries.State.RUNNING && boat != null && man.stationTask() == StationTask.FERRY, "the ferry running: " + c.state());
        BlockPos stand = c.bankA().relative(c.way(), 3).above();
        man.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 0.0F, 0.0F);
        ServerPlayer pl = player(helper, stand.west(), 5);
        int coins0 = Market.coinsHeld(pl), fares0 = c.fares;
        String[] asked = { null };
        boolean[] done = { false };
        helper.onEachTick(() -> {
            if (done[0]) return;
            if (man.breakNowForTests()) level.setDayTime(level.getDayTime() + 200);
            Boat b = Ferries.boatForTests(level, id);
            if (asked[0] == null) {
                if (b == null || man.getVehicle() != b) return;
                pl.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
                asked[0] = say(man, pl, "Take me across, please");
                Kit.log("od03 asked at tick " + helper.getTick() + ": '" + asked[0] + "'; riding " + (pl.getVehicle() != null));
                return;
            }
            if (helper.getTick() % 100 == 0) {
                Kit.log("od03 tick " + helper.getTick() + ": boat " + (b == null ? "none" : b.blockPosition().toShortString() + " with " + b.getPassengers().size())
                    + "; the player at " + pl.blockPosition().toShortString() + " riding " + (pl.getVehicle() != null) + "; the ferryman riding "
                    + (man.getVehicle() != null) + "; crossings " + c.crossings);
            }
            if (pl.getVehicle() != null || c.crossings == 0) return;
            done[0] = true;
            Kit.log("od03 across at tick " + helper.getTick() + ": the player at " + pl.blockPosition().toShortString() + "; coins " + coins0 + " -> "
                + Market.coinsHeld(pl) + "; fares " + fares0 + " -> " + c.fares + ", players " + c.players);
            Kit.noLeftoverPlayers(level);
            Ferries.weatherForTests(null);
            helper.assertTrue(asked[0].contains("In you get"), "handed in behind the ferryman: " + asked[0]);
            helper.assertTrue(pl.getX() > x0 + 5, "set down on the far landing: " + pl.blockPosition().toShortString());
            helper.assertTrue(Market.coinsHeld(pl) == coins0 - Ferries.FARE && c.fares == fares0 + Ferries.FARE, "a coin of fare paid");
            helper.succeed();
        });
    }

    // ============================================================ od04: a golem for the player's base

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "od04_golem")
    public static void od04_golem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2024000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 3000, StationTask.NONE, StationTask.NONE);
        VillageFolkEntity keeper = folk.get(1);
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            jobs(folk, StationTask.NONE, StationTask.GOLEMS);
            ServerPlayer pl = player(helper, heart.offset(10, 0, 10), 30);
            pl.setYRot(0.0F);
            pl.getInventory().add(new ItemStack(Items.IRON_INGOT, 20));
            int purse0 = keeper.purse();
            String shy = say(keeper, pl, "Could you build a golem for my base?");
            int ingots0 = carried(pl, s -> s.is(Items.IRON_INGOT));
            pl.getInventory().add(new ItemStack(Items.IRON_BLOCK, 2));
            pl.getInventory().add(new ItemStack(Items.CARVED_PUMPKIN));
            String built = say(keeper, pl, "Could you build a golem for my base?");
            List<IronGolem> golems = level.getEntitiesOfClass(IronGolem.class, new AABB(pl.blockPosition()).inflate(8));
            boolean ours = !golems.isEmpty() && golems.get(0).getTags().contains(OutdoorTalk.PLAYERS_GOLEM) && golems.get(0).isPlayerCreated();
            int ingots1 = carried(pl, s -> s.is(Items.IRON_INGOT)), blocks1 = carried(pl, s -> s.is(Items.IRON_BLOCK)),
                pumpkins = carried(pl, s -> s.is(Items.CARVED_PUMPKIN));
            Kit.log("od04 short: '" + shy + "' (ingots " + ingots0 + "); built: '" + built + "'; golems " + golems.size() + " ours " + ours
                + "; left: ingots " + ingots1 + ", blocks " + blocks1 + ", pumpkins " + pumpkins + "; coins " + Market.coinsHeld(pl)
                + "; keeper's purse " + purse0 + " -> " + keeper.purse());
            for (IronGolem g : golems) g.discard();
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(shy.contains("20 ingots") && ingots0 == 20, "short of iron: told so, nothing taken: " + shy);
            helper.assertTrue(golems.size() == 1 && ours, "an iron golem stands up, the player's own: " + golems.size());
            helper.assertTrue(ingots1 == 2 && blocks1 == 0 && pumpkins == 0, "two blocks and eighteen ingots of the player's iron, and its pumpkin, gone");
            helper.assertTrue(Market.coinsHeld(pl) == 30 - 10 && keeper.purse() == purse0 + 10, "the keeper's fee paid to the keeper");
            helper.succeed();
        });
    }

    // ============================================================ od05: the lodge, the barn, the scouts' desk

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "od05_bases")
    public static void od05_bases(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2026000;
        StationTask[] trades = new StationTask[OutdoorBases.BARN_FROM];
        java.util.Arrays.fill(trades, StationTask.NONE);
        trades[1] = StationTask.HUNT;
        trades[2] = StationTask.RANCH;
        trades[3] = StationTask.SCOUT;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 9500, new StationTask[trades.length]);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        VillageFolkEntity hunter = folk.get(1), rancher = folk.get(2);
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            jobs(folk, trades);
            Villages.ageForTests(id, Villages.Age.STONE);
            boolean wantedBefore = OutdoorBases.lodgeWanted(id), barnBefore = OutdoorBases.barnWanted(id);
            BlockPos lodge = stamp(level, id, OutdoorBases.LODGE, heart.offset(-20, 0, -16));
            boolean wantedAfter = OutdoorBases.lodgeWanted(id);
            BlockPos chest = WorkStock.roundForTests(level, v, StationTask.HUNT);
            String named = chest != null && level.getBlockEntity(chest) instanceof Nameable n && n.getCustomName() != null ? n.getCustomName().getString() : "";
            Container box = (Container) level.getBlockEntity(chest);
            box.setItem(20, new ItemStack(Items.COAL, 8));
            box.setChanged();
            BlockPos smoker = part(OutdoorBases.LODGE, lodge, BuildGoal.Part.SMOKER);
            hunter.removeMatching(s -> s.is(Items.BEEF), 64);
            hunter.insertItem(new ItemStack(Items.BEEF, 8));
            hunter.getNavigation().stop();
            hunter.moveTo(smoker.getX() + 0.5, smoker.getY(), smoker.getZ() + 1.5, 0.0F, 0.0F);
            boolean went = OutdoorBases.hunterForTests(hunter, level);
            AbstractFurnaceBlockEntity sm = (AbstractFurnaceBlockEntity) level.getBlockEntity(smoker);
            int in = sm.getItem(0).is(Items.BEEF) ? sm.getItem(0).getCount() : 0, fuel = sm.getItem(1).getCount();
            int beefLeft = hunter.countCarried(s -> s.is(Items.BEEF));
            // The smoker's work done (the game's own smoking, a minute and more for eight): taken out to its pack.
            sm.setItem(0, ItemStack.EMPTY);
            sm.setItem(2, new ItemStack(Items.COOKED_BEEF, 8));
            int cooked0 = hunter.countCarried(s -> s.is(Items.COOKED_BEEF));
            OutdoorBases.hunterForTests(hunter, level);
            int cooked1 = hunter.countCarried(s -> s.is(Items.COOKED_BEEF));
            // The barn, once the pen stands: the rancher's feed fetched from its chest.
            Ledger.built(id, "pen", heart.offset(20, 0, 16), Direction.NORTH);
            boolean barnWanted = OutdoorBases.barnWanted(id);
            stamp(level, id, OutdoorBases.BARN, heart.offset(20, 0, -16));
            BlockPos feed = WorkStock.roundForTests(level, v, StationTask.RANCH);
            Container feedBox = (Container) level.getBlockEntity(feed);
            feedBox.setItem(20, new ItemStack(Items.WHEAT, 32));
            feedBox.setChanged();
            rancher.removeMatching(s -> s.is(Items.WHEAT) || s.is(Items.WHEAT_SEEDS) || s.is(Items.CARROT) || s.is(Items.POTATO)
                || s.is(Items.BEETROOT) || s.is(Items.BEETROOT_SEEDS), 999);
            rancher.getNavigation().stop();
            rancher.moveTo(feed.getX() + 0.5, feed.getY(), feed.getZ() + 1.5, 0.0F, 0.0F);
            boolean fetched = OutdoorBases.rancherForTests(rancher, level);
            int wheat = rancher.countCarried(s -> s.is(Items.WHEAT)), wheatLeft = 0;
            for (int i = 0; i < feedBox.getContainerSize(); i++) if (feedBox.getItem(i).is(Items.WHEAT)) wheatLeft += feedBox.getItem(i).getCount();
            // The scouts' desk at the map room's table.
            stamp(level, id, Cartographers.STRUCTURE, heart.offset(0, 0, 24));
            OutdoorBases.Desk desk = OutdoorBases.scoutDesk(level, id);
            Kit.log("od05 lodge wanted " + wantedBefore + " -> " + wantedAfter + "; chest '" + named + "'; went " + went + "; smoker in " + in + " beef, fuel "
                + fuel + ", beef left " + beefLeft + "; cooked " + cooked0 + " -> " + cooked1 + "; barn wanted " + barnBefore + " -> " + barnWanted
                + "; fetched " + fetched + ": wheat " + wheat + ", left in the chest " + wheatLeft + "; desk " + desk);
            helper.assertTrue(wantedBefore && !wantedAfter, "the lodge wanted with a hunter in a town of ten, and not once it stands");
            helper.assertTrue(named.equals("Hunter's lodge: makings"), "its chest the hunter's makings: '" + named + "'");
            helper.assertTrue(in == 8 && fuel == 4 && beefLeft == 0, "the game into the smoker with the chest's coal under it: " + in + "/" + fuel + "/" + beefLeft);
            helper.assertTrue(cooked1 - cooked0 == 8 && sm.getItem(2).isEmpty(), "the smoked meat out into its pack");
            helper.assertTrue(!barnBefore && barnWanted, "the barn wanted once the pen stands");
            helper.assertTrue(fetched && wheat == OutdoorBases.FEED_TAKE && wheatLeft == 32 - wheat, "the herd's feed fetched from the barn: " + wheat);
            helper.assertTrue(desk != null && level.getBlockState(desk.table()).is(Blocks.CARTOGRAPHY_TABLE), "the scouts' desk at the map room's table");
            helper.succeed();
        });
    }

    // ============================================================ od06: the rain, the bell, the winter

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "od06_weather")
    public static void od06_weather(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2028000;
        StationTask[] trades = { StationTask.FARM, StationTask.WOOD, StationTask.HUNT, StationTask.BEEKEEP };
        List<VillageFolkEntity> folk = town(helper, x, DAY + 2000, trades);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        VillageFolkEntity farmer = folk.get(0), wood = folk.get(1), hunter = folk.get(2), bees = folk.get(3);
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            jobs(folk, trades);
            try {
                ServerPlayer pl = player(helper, hunter.blockPosition().east(), 0);
                OutdoorWeather.rainForTests(false);
                String dryWhy = wood.offWorkWhy();
                String party = say(hunter, pl, "Can I come hunting with you?");
                boolean partying = OutdoorTalk.partying(hunter);
                OutdoorWeather.rainForTests(true);
                boolean woodIn = OutdoorWeather.keepsIn(wood) && !wood.onShift() && wood.offWorkWhy().equals("the rain");
                boolean hunterIn = OutdoorWeather.keepsIn(hunter) && !hunter.onShift();
                boolean farmerOut = !OutdoorWeather.keepsIn(farmer) && !farmer.offWorkWhy().equals("the rain");
                BlockPos ground = OutdoorTalk.huntingWith(hunter, level);
                boolean calledOff = ground == null && !OutdoorTalk.partying(hunter);
                String wet = say(hunter, pl, "Can I come hunting with you?");
                OutdoorWeather.rainForTests(false);
                Raids.warAlarm(level, v, "a test", null, 400L);
                boolean bell = !hunter.onShift() && hunter.offWorkWhy().equals("the alarm");
                Raids.allClear(level, v);
                // The beekeeper's winter: the honey left in the hives.
                BlockPos meadow = heart.offset(-16, 0, 12);
                bees.assignPlot(WorkZone.around(meadow, 4, WorkZone.DEFAULT_DEPTH), "The hives");
                BlockPos h1 = meadow.east(), h2 = meadow.west();
                for (BlockPos h : new BlockPos[]{ h1, h2 }) level.setBlock(h, Blocks.BEEHIVE.defaultBlockState().setValue(BeehiveBlock.HONEY_LEVEL, 5), 3);
                chestAt(level, heart.offset(-4, 0, -4), new ItemStack(Items.SHEARS), new ItemStack(Items.GLASS_BOTTLE, 16), new ItemStack(Items.OAK_PLANKS, 32));
                long today = level.getDayTime() / 24000L;
                long winter = Seasons.dayOf(id, today, 3 * Seasons.DAYS + 1);
                if (winter < today) winter += 4L * Seasons.DAYS;
                level.setDayTime(winter * 24000L + 3000L);
                boolean isWinter = OutdoorWeather.winter(level, id);
                for (int i = 0; i < 4; i++) Crafts.now(bees, level, v);
                int honey = level.getBlockState(h1).getValue(BeehiveBlock.HONEY_LEVEL) + level.getBlockState(h2).getValue(BeehiveBlock.HONEY_LEVEL);
                long spring = winter - (3L * Seasons.DAYS + 1) + 4L * Seasons.DAYS;   // the first day of the next year: spring
                level.setDayTime(spring * 24000L + 3000L);
                boolean springOn = !OutdoorWeather.winter(level, id)
                    && !OutdoorWeather.hivesInWinter(level, v, bees, List.of(h1, h2));
                Kit.log("od06 dry: '" + dryWhy + "'; party '" + party + "' (" + partying + "); rain: wood in " + woodIn + ", hunter in " + hunterIn
                    + ", farmer out " + farmerOut + ", party called off " + calledOff + ", asked again '" + wet + "'; bell " + bell + "; winter " + isWinter
                    + ": honey left " + honey + " of 10; spring on " + springOn);
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(!dryWhy.equals("the rain") && partying, "dry: the woodcutter not kept in, the hunter takes the player out: " + party);
                helper.assertTrue(woodIn && hunterIn && farmerOut, "the rain keeps the woodcutter and the hunter in, not the farmer");
                helper.assertTrue(calledOff && wet.contains("Not in this"), "the rain calls the hunt off, and none is begun in it: " + wet);
                helper.assertTrue(bell, "the bell calls the hunter in");
                helper.assertTrue(isWinter && honey == 10, "winter: the hives' honey left for the bees: " + honey);
                helper.assertTrue(springOn, "come spring the beekeeper's round is on again");
            } finally {
                OutdoorWeather.rainForTests(null);
            }
            helper.succeed();
        });
    }

    // ============================================================ od07: the woodcutter's hand and the shearer

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "od07_shifts")
    public static void od07_shifts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2030000;
        StationTask[] trades = { StationTask.NONE, StationTask.WOOD, StationTask.RANCH };
        List<VillageFolkEntity> folk = town(helper, x, DAY + 2000, trades);
        UUID id = folk.get(0).ownerId();
        VillageFolkEntity wood = folk.get(1);
        BlockPos heart = Kit.surface(level, x, Z);
        helper.runAtTickTime(5, () -> {
            jobs(folk, trades);
            Ledger.addCoins(id, 500);
            chestAt(level, heart.offset(-4, 0, -4), new ItemStack(Items.OAK_SAPLING, 4), new ItemStack(Items.BREAD, 32));
            // A grown oak in the woodcutter's wood: five logs on the grass, a crown of leaves.
            BlockPos woods = heart.offset(22, 0, 0);
            wood.assignPlot(WorkZone.around(woods, 8, WorkZone.DEFAULT_DEPTH), "The wood");
            BlockPos base = Kit.surface(level, woods.getX() + 2, woods.getZ() + 2);
            for (int h = 0; h < 5; h++) level.setBlock(base.above(h), Blocks.OAK_LOG.defaultBlockState(), 3);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int h = 3; h <= 5; h++) {
                BlockPos l = base.offset(dx, h, dz);
                if (level.getBlockState(l).isAir()) level.setBlock(l, Blocks.OAK_LEAVES.defaultBlockState().setValue(
                    net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true), 3);
            }
            ServerPlayer pl = player(helper, base.west(2), 0);
            Kit.log("od07 the woodcutter " + wood.stationTask() + "; the trunk found at " + OutdoorShifts.trunkBaseForTests(level, base.getX(), base.getZ()));
            String took = Shifts.take(pl, id, ShiftRole.WOOD.id, false);
            Shifts.secondForTests(level, pl);
            String task = Shifts.taskForTests(pl);
            int logs0 = stock(level, id, s -> s.is(Items.OAK_LOG));
            for (int h = 0; h < 5; h++) pl.gameMode.destroyBlock(base.above(h));
            int logs1 = stock(level, id, s -> s.is(Items.OAK_LOG)), onGround = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new AABB(base).inflate(6), e -> e.getItem().is(Items.OAK_LOG)).size();
            Shifts.secondForTests(level, pl);
            String plant = Shifts.taskForTests(pl);
            int saplings = carried(pl, s -> s.is(Items.OAK_SAPLING));
            level.setBlock(base, Blocks.OAK_SAPLING.defaultBlockState(), 3);
            OutdoorShifts.onPlace(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, base), level.getBlockState(base.below()), pl));
            int[] st = Shifts.stateForTests(pl);
            Shifts.endForTests(level, pl);
            // The shearer: a sheep of the flock at the pen, shorn with the player's shears.
            BlockPos pen = heart.offset(-20, 0, 14);
            Ledger.built(id, "pen", pen, Direction.NORTH);
            Sheep sh = EntityType.SHEEP.create(level);
            sh.moveTo(pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
            sh.addTag(com.jrpetty.mcassistant.entity.Drover.HERD);
            level.addFreshEntity(sh);
            ServerPlayer shearer = player(helper, pen.east(2), 0);
            shearer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHEARS));
            String tookShear = Shifts.take(shearer, id, ShiftRole.SHEAR.id, false);
            Shifts.secondForTests(level, shearer);
            String shearTask = Shifts.taskForTests(shearer);
            int wool0 = stock(level, id, s -> s.is(Items.WHITE_WOOL));
            shearer.interactOn(sh, InteractionHand.MAIN_HAND);
            int wool1 = stock(level, id, s -> s.is(Items.WHITE_WOOL));
            int[] st2 = Shifts.stateForTests(shearer);
            Kit.log("od07 wood: took '" + took + "', task '" + task + "'; logs in the stores " + logs0 + " -> " + logs1 + ", on the ground " + onGround
                + "; then '" + plant + "', saplings " + saplings + "; state " + java.util.Arrays.toString(st) + "; shear: took '" + tookShear + "', task '"
                + shearTask + "', wool " + wool0 + " -> " + wool1 + ", sheared " + sh.isSheared() + "; state " + java.util.Arrays.toString(st2));
            sh.discard();
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(took.isEmpty() && task != null && task.startsWith("Fell"), "a marked tree to fell: " + took + " / " + task);
            helper.assertTrue(logs1 - logs0 == 5 && onGround == 0, "every log into the stores, none to the ground: " + (logs1 - logs0));
            helper.assertTrue(plant != null && plant.contains("plant") && saplings == 1, "then a sapling from the stores to plant: " + plant);
            helper.assertTrue(st != null && st[0] >= 1, "planted back: the task done and paid");
            helper.assertTrue(tookShear.isEmpty() && shearTask != null && shearTask.contains("sheep"), "a marked sheep to shear: " + shearTask);
            helper.assertTrue(sh.isSheared() && wool1 > wool0 && st2 != null && st2[0] >= 1, "shorn, the wool into the stores, the task paid");
            helper.succeed();
        });
    }

    // ============================================================ od08: the stage for the pictures, and the books

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "od08_stage")
    public static void od08_stage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = 2032000;
        List<VillageFolkEntity> folk = town(helper, x, DAY + 3000, StationTask.NONE, StationTask.NONE);
        UUID id = folk.get(0).ownerId();
        helper.runAtTickTime(5, () -> {
            Kit.hold(level, x + 200, Z, 64);
            Kit.prepare(level, x + 200, Z, 64);
            List<String> views = OutdoorStage.stage(level, Kit.surface(level, x + 200, Z));
            List<String> names = new ArrayList<>();
            for (String v : views) if (v.startsWith("VIEW ")) names.add(v.split(" ")[1]);
            List<IronGolem> golems = level.getEntitiesOfClass(IronGolem.class, new AABB(new BlockPos(x + 200, -64, Z)).inflate(48, 400, 48));
            List<String> books = OutdoorStage.lines(level, id);
            Kit.log("od08 views " + names + "; golems " + golems.size() + "; the books " + books);
            for (net.minecraft.world.entity.Entity e : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class,
                    new AABB(new BlockPos(x + 200, -64, Z)).inflate(48, 400, 48), e -> e.getTags().contains("folk_lineup"))) e.discard();
            helper.assertTrue(names.containsAll(List.of("trades", "lodge-front", "lodge-inside", "barn", "golem")), "every view staged: " + names);
            helper.assertTrue(golems.size() == 1, "the player's golem stood up on the stage: " + golems.size());
            helper.assertTrue(books.size() >= 3 && books.get(0).startsWith("Hunter's lodge"), "the town's outdoor books in words: " + books);
            helper.succeed();
        });
    }
}
