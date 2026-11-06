package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Arms;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Bard;
import com.jrpetty.mcassistant.entity.Buskers;
import com.jrpetty.mcassistant.entity.Culture;
import com.jrpetty.mcassistant.entity.FoundingDay;
import com.jrpetty.mcassistant.entity.Heraldry;
import com.jrpetty.mcassistant.entity.Homeland;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Music;
import com.jrpetty.mcassistant.entity.Persona;
import com.jrpetty.mcassistant.entity.RestDay;
import com.jrpetty.mcassistant.entity.TownCalendar;
import com.jrpetty.mcassistant.entity.VillageBoards;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [arms] The town's arms everywhere, and its street musicians.
 *
 * <ul>
 * <li><b>ab01</b>: a guard's plain shield is given the town's arms: its charges and its field are the town's banner's,
 *     layer for layer, and the stores are down by the six wool and the stick the tailor's banner took and a dye a
 *     charge; the chronicle says the watch first carried the arms.</li>
 * <li><b>ab02</b>: the banner flies either side of the meeting hall's door, woven as drawn, out of the stores' wool and
 *     dye (none of it a banner put by): six wool to every banner hung.</li>
 * <li><b>ab03</b>: the board's header line carries the arms (the field, and each charge's pattern and dye, in order),
 *     the board's page leaves it out of its words; a new age grants the river town a fish, and the line has it.</li>
 * <li><b>ab04</b>: a musician busks by the well on a note block lent out of the stores; two listeners each drop a coin
 *     out of their own purse into its hat (none twice), not a coin made or lost; home, the note block goes back and
 *     its evening is practice.</li>
 * <li><b>ab05</b>: a player right-clicks a busker with a coin: the coin leaves the player and is in the busker's purse;
 *     a folk who is not busking takes none.</li>
 * <li><b>ab06</b>: a busker good enough and drawing a hat is booked by the tavern (the chronicle: "who played for
 *     coppers by the well, now plays the tavern every rest day"); on the evening of the day of rest it goes to play by
 *     the tavern's hearth, paid its fee out of the treasury once.</li>
 * <li><b>ab07</b>: a festival tabard is made of the stores' wool and the town's banner; on Founding Day a folk wears it,
 *     and the next day it is back in the stores.</li>
 * </ul>
 *
 * <p>Each on its own ground in the band x 1,120,000 to 1,139,999, z 66,000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class ArmsBuskersGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    // ------------------------------------------------------------------ the ground and the town

    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        int y = Kit.surface(level, cx, cz).getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 24; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    private record Town(UUID id, BlockPos heart, List<VillageFolkEntity> folk) {}

    /** A town of so many folk on flat ground at this x, at this hour of a day two on, its stores emptied. */
    private static Town town(GameTestHelper helper, ServerLevel level, int x, int n, long hour) {
        Kit.reset(level);
        level.setWeatherParameters(24000, 0, false, false);
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 32);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + hour);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.offset(-6 + i * 3, 0, 7), 0.0F);
            helper.assertTrue(f != null, "a folk of the town");
            folk.add(f);
        }
        UUID id = folk.get(0).ownerId();
        for (VillageFolkEntity f : folk) helper.assertTrue(id != null && id.equals(f.ownerId()), "all of one town");
        return new Town(id, heart, folk);
    }

    private static Villages.Village village(GameTestHelper helper, UUID id) {
        Villages.Village v = Villages.get(id);
        helper.assertTrue(v != null, "the village is on the books");
        return v;
    }

    /** Every store chest of the town emptied (the founders' things), and one marked chest here with these in it. */
    private static Container stores(ServerLevel level, UUID id, BlockPos at, ItemStack... goods) {
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                for (int i = 0; i < c.getContainerSize(); i++) c.setItem(i, ItemStack.EMPTY);
                c.setChanged();
            }
        }
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        Villages.forgetStock();
        Villages.forgetStores(id);
        return box;
    }

    private static Item item(String name) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(name));
    }

    private static Item wool(DyeColor c) {
        return item(c.getName() + "_wool");
    }

    private static Item dye(DyeColor c) {
        return item(c.getName() + "_dye");
    }

    private static int stock(ServerLevel level, UUID id, Item it) {
        Villages.forgetStock();
        return Market.stock(level, id, s -> s.is(it));
    }

    private static boolean told(UUID village, String words) {
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().contains(words)) return true;
        return false;
    }

    /** The makings of the town's banners: the field's wool, sticks, a crafting table, and the dyes (so many of each). */
    private static ItemStack[] makings(Heraldry.Design d, int wool, int dyes) {
        List<ItemStack> out = new ArrayList<>();
        out.add(new ItemStack(wool(d.field()), wool));
        out.add(new ItemStack(Items.STICK, 8));
        out.add(new ItemStack(Items.CRAFTING_TABLE));
        Map<Item, Integer> each = new LinkedHashMap<>();
        for (Heraldry.Layer l : d.layers()) each.put(dye(l.colour()), dyes);
        for (Map.Entry<Item, Integer> e : each.entrySet()) out.add(new ItemStack(e.getKey(), e.getValue()));
        return out.toArray(new ItemStack[0]);
    }

    /** The dyes a banner of these arms takes, by dye. */
    private static Map<Item, Integer> dyesFor(Heraldry.Design d) {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (Heraldry.Layer l : d.layers()) m.merge(dye(l.colour()), 1, Integer::sum);
        return m;
    }

    /** The town's tailor, of a level for banners (ten and over). */
    private static VillageFolkEntity tailor(VillageFolkEntity f) {
        f.setJob(StationTask.TAILOR);
        f.tradeXpForTests(StationTask.TAILOR, AssistantEntity.xpForLevel(12));
        return f;
    }

    /** A river town's arms, chosen now. */
    private static Heraldry.Design arms(GameTestHelper helper, ServerLevel level, Town t) {
        Homeland.setForTests(t.id(), Homeland.Land.RIVER);
        Heraldry.chooseForTests(level, village(helper, t.id()));
        Heraldry.Design d = Heraldry.design(t.id());
        helper.assertTrue(d != null, "the town has its arms");
        return d;
    }

    /** Do these layers show these arms, charge for charge, in order? */
    private static boolean same(BannerPatternLayers layers, Heraldry.Design d) {
        if (layers.layers().size() != d.layers().size()) return false;
        for (int i = 0; i < d.layers().size(); i++) {
            BannerPatternLayers.Layer l = layers.layers().get(i);
            if (l.color() != d.layers().get(i).colour() || !l.pattern().is(d.layers().get(i).charge().key)) return false;
        }
        return true;
    }

    /** The first day of rest from this one (a week after the founding at least). */
    private static long restDay(UUID id, long from) {
        for (long d = from; d < from + 30; d++) if (RestDay.today(id, d)) return d;
        return -1;
    }

    // ============================================================ ab01: the arms on a guard's shield

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ab01_shield")
    public static void ab01_shield(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 1120000, 4, 2000L);
        helper.runAtTickTime(10, () -> {
            UUID id = t.id();
            Villages.Village v = village(helper, id);
            Heraldry.Design d = arms(helper, level, t);
            Kit.log("ab01 the arms: " + d.blazon());
            VillageFolkEntity guard = t.folk().get(1);
            guard.setJob(StationTask.GUARD);
            guard.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            tailor(t.folk().get(2));
            stores(level, id, t.heart().offset(3, 0, -3), makings(d, 12, 2));
            int wool = stock(level, id, wool(d.field())), sticks = stock(level, id, Items.STICK);
            Map<Item, Integer> dyes = new LinkedHashMap<>();
            for (Item dy : dyesFor(d).keySet()) dyes.put(dy, stock(level, id, dy));

            boolean done = Arms.shieldForTests(level, v, guard);
            ItemStack shield = guard.getItemBySlot(EquipmentSlot.OFFHAND);
            BannerPatternLayers on = shield.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
            Kit.log("ab01 shield done=" + done + " base=" + shield.get(DataComponents.BASE_COLOR) + " layers=" + on.layers().size()
                + " short=" + Arms.shortForTests(id) + "; wool " + wool + "->" + stock(level, id, wool(d.field())) + ", sticks " + sticks + "->"
                + stock(level, id, Items.STICK));
            helper.assertTrue(done && shield.is(Items.SHIELD), "the guard's shield was given the arms: " + Arms.shortForTests(id));
            helper.assertTrue(shield.get(DataComponents.BASE_COLOR) == d.field(), "the shield's field is the banner's: " + shield.get(DataComponents.BASE_COLOR));
            helper.assertTrue(same(on, d), "the shield's charges are the banner's, layer for layer: " + on.layers() + " vs " + d.layers());
            helper.assertTrue(Arms.bears(shield, d), "the shield bears the town's arms");
            // From real wool and dye: the banner the tailor wove went onto it.
            helper.assertTrue(wool - stock(level, id, wool(d.field())) == 6, "six wool for the banner: " + wool + " -> " + stock(level, id, wool(d.field())));
            helper.assertTrue(sticks - stock(level, id, Items.STICK) == 1, "and a stick");
            for (Map.Entry<Item, Integer> e : dyesFor(d).entrySet()) {
                int now = stock(level, id, e.getKey());
                helper.assertTrue(dyes.get(e.getKey()) - now == e.getValue(), "a dye a charge: " + e.getKey() + " " + dyes.get(e.getKey()) + " -> " + now);
            }
            helper.assertTrue(told(id, "the watch first carried the town's arms"), "into the chronicle");
            // A shield that bears arms takes no more.
            helper.assertTrue(!Arms.shieldForTests(level, v, guard), "a shield is given the arms once");
            helper.succeed();
        });
    }

    // ============================================================ ab02: a banner flies on the hall

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ab02_hall")
    public static void ab02_hall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 1122000, 4, 2000L);
        BlockPos hallAt = t.heart().offset(0, 0, 16);
        helper.runAtTickTime(10, () -> {
            UUID id = t.id();
            Villages.Village v = village(helper, id);
            Heraldry.Design d = arms(helper, level, t);
            tailor(t.folk().get(1));
            BuildGoal.stamp(level, "hall", hallAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "hall", hallAt, Direction.NORTH);
            stores(level, id, t.heart().offset(3, 0, -3), makings(d, 40, 8));
            int wool = stock(level, id, wool(d.field()));
            String did = Heraldry.putForTests(level, v);
            List<BlockPos> hung = Heraldry.hungForTests(level, id);
            int used = wool - stock(level, id, wool(d.field()));
            Kit.log("ab02 put up: " + did + "; hung " + hung + "; places " + Heraldry.placesForTests(level, id) + "; wool used " + used);
            int atHall = 0;
            for (BlockPos p : hung) {
                BannerBlockEntity be = level.getBlockEntity(p) instanceof BannerBlockEntity b ? b : null;
                helper.assertTrue(be != null && be.getBaseColor() == d.field() && same(be.getPatterns(), d),
                    "a banner of the town's arms at " + p.toShortString());
                if (Math.abs(p.getX() - hallAt.getX()) <= 4 && p.getZ() > hallAt.getZ()) atHall++;
            }
            helper.assertTrue(atHall == 2, "the banner flies either side of the hall's door: " + atHall + " of " + hung);
            helper.assertTrue(used == 6 * hung.size(), "every banner woven of six of the stores' wool: " + used + " for " + hung.size());
            helper.succeed();
        });
    }

    // ============================================================ ab03: the arms on the board

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ab03_board")
    public static void ab03_board(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 1124000, 3, 2000L);
        helper.runAtTickTime(10, () -> {
            UUID id = t.id();
            Villages.Village v = village(helper, id);
            Heraldry.Design d = arms(helper, level, t);
            List<String> lines = VillageBoards.compose(level, id);
            String line = null;
            for (String l : lines) if (l.startsWith("AN|")) line = l;
            Kit.log("ab03 the board's arms: " + line);
            helper.assertTrue(line != null, "the board carries the arms: " + lines);
            String[] p = line.substring(3).split("\\|", -1);
            helper.assertTrue(p.length == 2 && p[0].equals(d.field().getName()), "its field: " + line);
            String[] charges = p[1].split(",");
            helper.assertTrue(charges.length == d.layers().size(), "a charge each: " + line);
            for (int i = 0; i < charges.length; i++) {
                Heraldry.Layer l = d.layers().get(i);
                helper.assertTrue(charges[i].equals(l.charge().key.location() + "=" + l.colour().getName()), "charge " + i + ": " + charges[i] + " for " + l);
            }
            String[] page = VillageBoards.page(lines);
            helper.assertTrue(!page[1].contains("AN|") && !page[1].contains(d.field().getName() + "|"), "the board's page leaves the drawing out");
            helper.assertTrue(page[1].contains("Our banner"), "and says the banner in words");
            // A new age: the river town's arms are granted what it lives by (its fisher, no farmer nor miner), and the
            // board has it.
            t.folk().get(0).setJob(StationTask.FISH);
            for (int i = 1; i < t.folk().size(); i++) t.folk().get(i).setJob(StationTask.WOOD);
            Villages.ageForTests(id, Villages.Age.STONE);
            boolean granted = Arms.newAgeForTests(level, v);
            Heraldry.Design after = Heraldry.design(id);
            String again = Arms.boardLine(id);
            Kit.log("ab03 granted=" + granted + ": " + after.blazon() + "; " + again);
            helper.assertTrue(granted && after.layers().size() == d.layers().size() + 1, "a charge granted for the new age");
            helper.assertTrue(after.layers().get(after.layers().size() - 1).charge() == Heraldry.Charge.FISH, "a fish, for a river town: " + after.layers());
            helper.assertTrue(again != null && again.contains("mc_assistant:fish="), "the board's line has it: " + again);
            helper.assertTrue(told(id, "the town's arms were granted"), "into the chronicle");
            helper.succeed();
        });
    }

    // ============================================================ ab04: a busker plays, and the listeners pay

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ab04_busk")
    public static void ab04_busk(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 1126000, 4, 12500L);
        helper.runAtTickTime(10, () -> {
            UUID id = t.id();
            Villages.Village v = village(helper, id);
            VillageFolkEntity busker = t.folk().get(0);
            busker.setJob(StationTask.WOOD);                         // a musician of the town, never the watch
            Culture.hobbyForTests(busker, Persona.Hobby.MUSIC);
            busker.removeMatching(s -> s.is(Items.NOTE_BLOCK), 64);
            VillageFolkEntity a = t.folk().get(1), b = t.folk().get(2);
            for (VillageFolkEntity f : List.of(a, b)) {
                Culture.hobbyForTests(f, Persona.Hobby.READING);
                f.earn(10);
            }
            stores(level, id, t.heart().offset(3, 0, -3), new ItemStack(Items.NOTE_BLOCK));
            int skill = Buskers.skillForTests(id, busker.getUUID());
            int purse = busker.purse(), pa = a.purse(), pb = b.purse();

            String where = Buskers.startForTests(level, v, busker);
            Kit.log("ab04 out: " + where + " " + Buskers.stintForTests(busker.getUUID()) + "; stores' note blocks " + stock(level, id, Items.NOTE_BLOCK));
            helper.assertTrue(where != null, "the busker is out");
            helper.assertTrue(stock(level, id, Items.NOTE_BLOCK) == 0 && busker.countCarried(s -> s.is(Items.NOTE_BLOCK)) == 1,
                "playing the stores' note block, lent");
            int notes = Buskers.playForTests(level, busker, 64);
            helper.assertTrue(notes == 64, "it plays its tune: " + notes);
            boolean ta = Buskers.listenForTests(level, busker, a, 0.0), tb = Buskers.listenForTests(level, busker, b, 0.0);
            boolean twice = Buskers.listenForTests(level, busker, a, 0.0);
            Kit.log("ab04 tips: " + ta + " " + tb + " again " + twice + "; purses busker " + purse + "->" + busker.purse() + ", " + pa + "->" + a.purse()
                + ", " + pb + "->" + b.purse());
            helper.assertTrue(ta && tb && !twice, "each listener tips once");
            helper.assertTrue(busker.purse() - purse == 2, "two coins in the busker's hat: " + purse + " -> " + busker.purse());
            helper.assertTrue(pa - a.purse() == 1 && pb - b.purse() == 1, "a coin out of each listener's own purse");
            helper.assertTrue(busker.purse() + a.purse() + b.purse() == purse + pa + pb, "not a coin made or lost");
            Buskers.endForTests(level, busker);
            int after = Buskers.skillForTests(id, busker.getUUID());
            Kit.log("ab04 home: skill " + skill + " -> " + after + "; stores' note blocks " + stock(level, id, Items.NOTE_BLOCK));
            helper.assertTrue(stock(level, id, Items.NOTE_BLOCK) == 1 && busker.countCarried(s -> s.is(Items.NOTE_BLOCK)) == 0,
                "the note block back in the stores");
            helper.assertTrue(after > skill, "the evening's practice: " + skill + " -> " + after);
            helper.succeed();
        });
    }

    // ============================================================ ab05: a player tips

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ab05_player_tips")
    public static void ab05_player_tips(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 1128000, 3, 12500L);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, t.id());
            VillageFolkEntity busker = t.folk().get(0), other = t.folk().get(1);
            busker.setJob(StationTask.WOOD);                         // a musician of the town, never the watch
            Culture.hobbyForTests(busker, Persona.Hobby.MUSIC);
            busker.insertItem(new ItemStack(Items.NOTE_BLOCK));
            helper.assertTrue(Buskers.startForTests(level, v, busker) != null, "the busker is out, on its own note block");
            Player you = helper.makeMockPlayer(GameType.SURVIVAL);
            you.setPos(busker.getX() + 1.5, busker.getY(), busker.getZ());
            you.getInventory().clearContent();
            you.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 5));
            int purse = busker.purse(), op = other.purse();
            boolean no = Buskers.tipFrom(other, you, you.getMainHandItem());
            helper.assertTrue(!no && Market.coinsHeld(you) == 5 && other.purse() == op, "a folk not busking takes no coin");
            boolean yes = Buskers.tipFrom(busker, you, you.getMainHandItem());
            Kit.log("ab05 tip: " + yes + "; the player's coins 5 -> " + Market.coinsHeld(you) + "; the busker's purse " + purse + " -> " + busker.purse()
                + "; " + Buskers.stintForTests(busker.getUUID()));
            helper.assertTrue(yes && Market.coinsHeld(you) == 4, "the coin left the player's pack");
            helper.assertTrue(busker.purse() == purse + 1, "and is in the busker's purse");
            helper.assertTrue(Buskers.stintForTests(busker.getUUID()).contains("take=1"), "in its hat for the evening");
            helper.succeed();
        });
    }

    // ============================================================ ab06: the tavern books the best

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ab06_tavern")
    public static void ab06_tavern(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 1130000, 4, 2000L);
        BlockPos tavernAt = t.heart().offset(16, 0, 0);
        helper.runAtTickTime(10, () -> {
            UUID id = t.id();
            Villages.Village v = village(helper, id);
            Villages.ageForTests(id, Villages.Age.STONE);
            VillageFolkEntity busker = t.folk().get(0);
            busker.setJob(StationTask.WOOD);                         // a musician of the town, never the watch
            Culture.hobbyForTests(busker, Persona.Hobby.MUSIC);
            busker.insertItem(new ItemStack(Items.NOTE_BLOCK));
            for (int i = 1; i < t.folk().size(); i++) Culture.hobbyForTests(t.folk().get(i), Persona.Hobby.READING);
            BuildGoal.stamp(level, "tavern", tavernAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "tavern", tavernAt, Direction.NORTH);
            Ledger.addCoins(id, 20);
            // A busker not yet good enough is not booked; with an autumn of evenings behind it, it is.
            Buskers.recordForTests(id, busker, 30, 6, 1, "by the well");
            helper.assertTrue(Buskers.considerForTests(level, v) == null, "not good enough yet");
            Buskers.recordForTests(id, busker, 60, 12, 3, "by the well");
            UUID booked = Buskers.considerForTests(level, v);
            Kit.log("ab06 booked " + booked + " (" + busker.getUUID() + ")");
            helper.assertTrue(busker.getUUID().equals(booked), "the tavern books the town's best busker");
            helper.assertTrue(told(id, busker.displayNameCap() + ", who played for coppers by the well, now plays the tavern every rest day"),
                "the chronicle tells of it");
            // The evening of the day of rest.
            long rest = restDay(id, level.getDayTime() / 24000L + 7);
            helper.assertTrue(rest > 0, "a day of rest to come");
            level.setDayTime(rest * 24000L + 12500L);
            int treasury = Ledger.coins(id), purse = busker.purse();
            boolean out = Buskers.holdForTests(level, v, busker);
            String stint = Buskers.stintForTests(busker.getUUID());
            Kit.log("ab06 the day of rest: out=" + out + " " + stint + "; treasury " + treasury + " -> " + Ledger.coins(id) + ", purse " + purse
                + " -> " + busker.purse() + "; the band playing " + Music.playing(id));
            helper.assertTrue(out && stint != null && stint.startsWith("TAVERN"), "it plays the tavern on the day of rest: " + stint);
            BlockPos stage = Bard.stageForTests(id);
            helper.assertTrue(stage != null && stint.contains("at=" + stage.toShortString()), "by the tavern's hearth: " + stage);
            helper.assertTrue(treasury - Ledger.coins(id) == Buskers.FEE && busker.purse() - purse == Buskers.FEE, "paid its fee out of the treasury");
            helper.assertTrue(Buskers.tavernTonight(level, id) && Buskers.atTheTavern(id), "the tavern's night: the town comes, its own tune waits");
            Buskers.holdForTests(level, v, busker);
            helper.assertTrue(busker.purse() - purse == Buskers.FEE, "paid once an evening");
            helper.succeed();
        });
    }

    // ============================================================ ab07: the festival tabards

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ab07_tabard")
    public static void ab07_tabard(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 1132000, 4, 2000L);
        helper.runAtTickTime(10, () -> {
            UUID id = t.id();
            Villages.Village v = village(helper, id);
            Heraldry.Design d = arms(helper, level, t);
            tailor(t.folk().get(1));
            for (VillageFolkEntity f : t.folk()) f.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            // Tabards are made on a plain day (a festival's is for wearing them).
            long plain = level.getDayTime() / 24000L;
            while (Arms.festivalForTests(id, plain)) plain++;
            level.setDayTime(plain * 24000L + 3000L);
            stores(level, id, t.heart().offset(3, 0, -3), makings(d, 20, 2));
            int wool = stock(level, id, wool(d.field()));
            boolean made = Arms.tabardForTests(level, v, false);
            Villages.forgetStock();
            int tabards = Market.stock(level, id, s -> s.is(McAssistantMod.TABARD.get()) && Arms.bears(s, d));
            Kit.log("ab07 made=" + made + " tabards " + tabards + "; wool " + wool + " -> " + stock(level, id, wool(d.field())) + "; " + Arms.shortForTests(id));
            helper.assertTrue(made && tabards == 1, "a tabard of the town's arms in the stores: " + Arms.shortForTests(id));
            // The tabard is cut in the shape of a chestplate, eight wool (data/mc_assistant/recipe/tabard.json); the banner six.
            helper.assertTrue(wool - stock(level, id, wool(d.field())) == 14, "eight wool for the tabard, six for the banner on it");
            // Founding Day: a folk wears it; the day after, it is back in the stores.
            long founded = FoundingDay.founded(id);
            level.setDayTime((founded + TownCalendar.YEAR_DAYS) * 24000L + 3000L);
            Arms.tickForTests(level, v);
            int wearing = 0;
            for (VillageFolkEntity f : t.folk()) if (f.getItemBySlot(EquipmentSlot.CHEST).is(McAssistantMod.TABARD.get())) wearing++;
            Kit.log("ab07 Founding Day: " + wearing + " wearing");
            helper.assertTrue(wearing == 1, "on Founding Day the town's tabard is worn: " + wearing);
            long after = founded + TownCalendar.YEAR_DAYS + 1;
            while (Arms.festivalForTests(id, after)) after++;
            level.setDayTime(after * 24000L + 3000L);
            Arms.tickForTests(level, v);
            wearing = 0;
            for (VillageFolkEntity f : t.folk()) if (f.getItemBySlot(EquipmentSlot.CHEST).is(McAssistantMod.TABARD.get())) wearing++;
            Villages.forgetStock();
            int back = Market.stock(level, id, s -> s.is(McAssistantMod.TABARD.get()) && Arms.bears(s, d));
            Kit.log("ab07 the day after: " + wearing + " wearing, " + back + " in the stores");
            helper.assertTrue(wearing == 0 && back >= 1, "the day after, it is back in the stores");
            helper.succeed();
        });
    }
}
