package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Archery;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Budget;
import com.jrpetty.mcassistant.entity.Drover;
import com.jrpetty.mcassistant.entity.Economy;
import com.jrpetty.mcassistant.entity.Festivals;
import com.jrpetty.mcassistant.entity.Fletchers;
import com.jrpetty.mcassistant.entity.Golems;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WatchKit;
import com.jrpetty.mcassistant.entity.WorkZone;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Crackiness;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [fletcher] [golems] The town's defence: the fletcher and the golem keeper, each behaviour shown happening in the
 * game, out of the town's real stores and by the game's own rules.
 *
 * <ul>
 * <li><b>fd01</b> (the fletcher's place): a Stone Age town wants no fletcher till a guard carries a bow; then it does,
 *     the place goes to the best of the town's own (never the watch), the fletcher's hut goes on the builders' list
 *     and the builders know it; the hut stood up, the fletcher makes its table of two of the stores' flint and four
 *     planks and sets it on the drawing's place.</li>
 * <li><b>fd02</b> (flint from gravel): a hundred blocks of the stores' gravel set down on the sifting floor in front of
 *     the hut and broken with the stores' shovel; what the game's loot gave (a flint about one time in ten) is in the
 *     stores, the rest of the gravel back, the floor bare and the shovel back the worse for it.</li>
 * <li><b>fd03</b> (feathers from the coop): a pen of ten sheep and twelve hens, the town with a fletcher and no
 *     feathers: the rancher culls old hens first (never a sheep while there are hens past the herd), and the feathers
 *     come home in its pack, not kept back as rations.</li>
 * <li><b>fd04</b> (arrows, bows, crossbows): an Iron Age fletcher at its table works the stores' flint, feathers, planks,
 *     string, iron, glowstone, redstone and hay into arrows (four to a flint), bows, a tripwire hook and a crossbow (issued
 *     to the best archer), a target for the range and spectral arrows, every count the recipe's; booked as its making.</li>
 * <li><b>fd05</b> (quivers): a guard's own visit to the stores fills its quiver to thirty-two; after a raid every quiver is
 *     filled at once and the restock is in the chronicle; the raid's reserve is not for sale; and a guard out of arrows
 *     with the stores empty is on the board.</li>
 * <li><b>fd06</b> (practice): the fletcher calls practice at the range on a quiet afternoon; the guards shoot ten real
 *     arrows each at the targets, the arrows are pulled and back in the stores, and each guard's aim is the steadier for
 *     it (its fight's spread narrower), with its best on its card.</li>
 * <li><b>fd07</b> (an iron golem, the game's way): an Iron Age town raided twice in a fortnight wants a golem keeper; the
 *     place goes to one of its own; the golem yard is wanted; the keeper makes four blocks of the stores' iron, stands
 *     them in a T at the square and carves a farm pumpkin on top with the stores' shears, and the game's own check
 *     stands up a player-made iron golem, named; the seeds are in the stores. Carried off, it walks back to its post.</li>
 * <li><b>fd08</b> (mending and losses): the town's old golem taken in hand and named; hurt, it is mended with the
 *     stores' ingots, twenty-five health to an ingot, its cracks gone; on the keeper's round, again; killed, it is
 *     mourned in the chronicle and its iron gathered back into the stores.</li>
 * <li><b>fd09</b> (snow golems): in winter, on a watchtower in a snowy biome, two blocks of snow packed of the stores'
 *     snowballs and a carved pumpkin stand up a snow golem; in a desert town none is built, and why is said; in spring
 *     the snow golem is let go and melts away.</li>
 * <li><b>fd10</b> (iron short): with the watch's armour still wanting the iron, no golem is built and the books say why;
 *     no block of iron is made on the round; with iron enough for both, it is.</li>
 * </ul>
 *
 * <p>Each on its own ground in the band x 1,340,000 to 1,359,999, z 66,000, calling the trades' logic directly where it can
 * and logging what it measures.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class DefenceTradesGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    // ------------------------------------------------------------------ the ground and the town

    /** Flat grass round here, clear air above it. Returns the ground's top (the first free block). */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int rx, int rz) {
        int y = Kit.surface(level, cx, cz).getY();
        for (int x = cx - rx; x <= cx + rx; x++) {
            for (int z = cz - rz; z <= cz + rz; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 20; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    private static List<VillageFolkEntity> raise(GameTestHelper helper, ServerLevel level, BlockPos heart, int n) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.offset(-6 + (i % 5) * 3, 0, 5 + (i / 5) * 3), 0.0F);
            helper.assertTrue(f != null, "a folk of the town");
            out.add(f);
        }
        return out;
    }

    /** A marked store chest at exactly this spot, filled with these. */
    private static Container chestAt(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        return box;
    }

    /** The town's arrows: its stores' and every one carried by its folk. */
    private static int townArrows(ServerLevel level, UUID village, List<VillageFolkEntity> folk) {
        int n = stock(level, village, Items.ARROW);
        for (VillageFolkEntity f : folk) n += f.countCarried(s -> s.is(Items.ARROW));
        return n;
    }

    /** Where the town's arrows are, for the log. */
    private static String arrowsHeld(ServerLevel level, UUID village, List<VillageFolkEntity> folk) {
        StringBuilder b = new StringBuilder("stores " + stock(level, village, Items.ARROW));
        for (VillageFolkEntity f : folk) {
            b.append(", ").append(f.displayNameCap()).append(" (").append(f.stationTask()).append(") ").append(f.countCarried(s -> s.is(Items.ARROW)));
        }
        return b.toString();
    }

    private static int stock(ServerLevel level, UUID village, Item item) {
        return Market.stock(level, village, s -> s.is(item));
    }

    private static int stock(ServerLevel level, UUID village, Predicate<ItemStack> what) {
        return Market.stock(level, village, what);
    }

    /** How much of this every chest round here holds: the stores, and a worker's own chest at its plot. */
    private static int inChests(ServerLevel level, int cx, int cz, int r, Predicate<ItemStack> what) {
        int n = 0;
        for (int chunkX = (cx - r) >> 4; chunkX <= (cx + r) >> 4; chunkX++) {
            for (int chunkZ = (cz - r) >> 4; chunkZ <= (cz + r) >> 4; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) continue;
                for (var be : new ArrayList<>(level.getChunk(chunkX, chunkZ).getBlockEntities().values())) {
                    if (!(be instanceof Container c) || be instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) continue;
                    for (int i = 0; i < c.getContainerSize(); i++) {
                        ItemStack st = c.getItem(i);
                        if (!st.isEmpty() && what.test(st)) n += st.getCount();
                    }
                }
            }
        }
        return n;
    }

    private static boolean told(UUID village, String words) {
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().contains(words)) return true;
        return false;
    }

    private static String chronicle(UUID village) {
        List<String> out = new ArrayList<>();
        for (Chronicle.Entry e : Chronicle.of(village)) out.add(e.text());
        return String.join(" / ", out);
    }

    /** A working day's hour, and fair weather. */
    private static void at(ServerLevel level, long timeOfDay) {
        long day = level.getDayTime() / 24000L + 1;
        level.setDayTime(day * 24000L + timeOfDay);
        level.updateSkyBrightness();
        level.setWeatherParameters(24000, 0, false, false);
    }

    /** Nothing hostile about the test's ground (the world's own spawns in the shade). */
    private static void noMonsters(ServerLevel level, BlockPos at, int r) {
        for (Monster m : level.getEntitiesOfClass(Monster.class, new AABB(at).inflate(r, 32, r))) m.discard();
    }

    /** Its bows and crossbows taken off it, hand and pack. */
    private static void unarmed(VillageFolkEntity f) {
        f.removeMatching(AssistantEntity.RANGED_WEAPON, 64);
        if (AssistantEntity.RANGED_WEAPON.test(f.getMainHandItem())) f.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    private static void trade(VillageFolkEntity f, StationTask t) {
        if (!f.takeUpTrade(t)) f.setJob(t);
    }

    private static Predicate<ItemStack> is(Item item) {
        return s -> s.is(item);
    }

    // ============================================================ fd01: the fletcher's place, its hut and its table

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fd01_fletcher_opens")
    public static void fd01_fletcher_opens(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1340000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 34, 34);
        at(level, 3000L);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 4);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.STONE);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity guard = folk.get(1);
            trade(guard, StationTask.GUARD);
            folk.get(3).setJob(StationTask.NONE);                          // a hand between trades, free to take it up
            for (VillageFolkEntity f : folk) unarmed(f);
            Kit.log("fd01 the town: " + folk.stream().map(f -> f.displayNameCap() + " " + f.stationTask()).toList());
            helper.assertTrue(guard.stationTask() == StationTask.GUARD, "a guard of the watch");
            helper.assertTrue(!Fletchers.wanted(id), "no fletcher wanted while nobody of the watch carries a bow");
            guard.insertItem(new ItemStack(Items.BOW));
            helper.assertTrue(Fletchers.wanted(id), "a Stone Age town whose watch carries a bow wants a fletcher");
            helper.assertTrue(Fletchers.hands(id) == 1, "one fletcher for a small town: " + Fletchers.hands(id));
            List<VillageFolkEntity> shortlist = Fletchers.shortlist(id);
            Kit.log("fd01 the shortlist: " + shortlist.stream().map(f -> f.displayNameCap() + " (" + f.stationTask() + ")").toList());
            helper.assertTrue(!shortlist.isEmpty() && !shortlist.contains(guard), "a shortlist of the town's own, never the watch");
            VillageFolkEntity f = Fletchers.appoint(level, v);
            helper.assertTrue(f != null && f == shortlist.get(0) && f.stationTask() == StationTask.FLETCHER,
                "the best on paper takes the place: " + (f == null ? "nobody" : f.displayNameCap() + " " + f.stationTask()));
            helper.assertTrue(told(id, "took up fletching"), "into the chronicle: " + chronicle(id));
            helper.assertTrue(Fletchers.keeps(id) && Fletchers.appoint(level, v) == null, "one fletcher, not two");
            // Its hut on the builders' list, and the builders know it.
            helper.assertTrue(Fletchers.hutWanted(id), "the fletcher's hut wanted");
            List<String> wanted = Villages.projectsWanted(id);
            Kit.log("fd01 the builders' list: " + wanted);
            helper.assertTrue(wanted.contains(Fletchers.STRUCTURE), "the hut on the builders' list: " + wanted);
            helper.assertTrue(BuildGoal.STRUCTURES.contains(Fletchers.STRUCTURE) && "civic".equals(TownPlan.placeFor(Fletchers.STRUCTURE)),
                "the builders can build it, facing the square with the trades");
            // The hut up; its table made of the stores' flint and planks, and set on the drawing's place.
            BlockPos hutAt = heart.offset(14, 0, -10);
            int put = BuildGoal.stamp(level, Fletchers.STRUCTURE, hutAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, Fletchers.STRUCTURE, hutAt, Direction.NORTH);
            helper.assertTrue(put > 30 && !Fletchers.hutWanted(id), "the hut stands: " + put + " blocks");
            chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.FLINT, 2), new ItemStack(Items.OAK_PLANKS, 8));
            int flintBefore = stock(level, id, Items.FLINT), planksBefore = stock(level, id, s -> s.is(net.minecraft.tags.ItemTags.PLANKS));
            BlockPos spot = Fletchers.tableSpotForTests(id);
            String made = Fletchers.workForTests(f, level, v);
            Kit.log("fd01 the first piece: " + made + "; the table's place " + spot + " holds " + level.getBlockState(spot)
                + "; flint " + stock(level, id, Items.FLINT) + ", planks " + stock(level, id, s -> s.is(net.minecraft.tags.ItemTags.PLANKS)));
            helper.assertTrue(made.contains("fletching table"), "its table made first: " + made);
            helper.assertTrue(level.getBlockState(spot).is(Blocks.FLETCHING_TABLE) && spot.equals(Fletchers.tableForTests(id)),
                "a fletching table on the drawing's place in the hut");
            helper.assertTrue(flintBefore - stock(level, id, Items.FLINT) == 2
                    && planksBefore - stock(level, id, s -> s.is(net.minecraft.tags.ItemTags.PLANKS)) == 4,
                "two flint and four planks out of the stores, the recipe's: flint " + flintBefore + " to " + stock(level, id, Items.FLINT)
                    + ", planks " + planksBefore + " to " + stock(level, id, s -> s.is(net.minecraft.tags.ItemTags.PLANKS)));
            String card = com.jrpetty.mcassistant.entity.FolkTalk.card(f);
            Kit.log("fd01 the fletcher's card: " + card);
            helper.assertTrue(card.contains("Fletcher") && card.contains("Fletching|"), "its card says its trade and its fletching");
            helper.succeed();
        });
    }

    // ============================================================ fd02: flint sifted from gravel

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fd02_flint_from_gravel")
    public static void fd02_flint_from_gravel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1342000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 34, 34);
        at(level, 3000L);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 3);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.STONE);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity f = folk.get(2);
            trade(f, StationTask.FLETCHER);
            BlockPos hutAt = heart.offset(-14, 0, -10);
            BuildGoal.stamp(level, Fletchers.STRUCTURE, hutAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, Fletchers.STRUCTURE, hutAt, Direction.NORTH);
            chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.GRAVEL, 40), new ItemStack(Items.STONE_SHOVEL));
            BlockPos floor = Fletchers.siftSpotForTests(level, id, heart);
            helper.assertTrue(floor != null && floor.distManhattan(hutAt) <= 6, "the sifting floor in front of the hut: " + floor);
            int[] got = Fletchers.siftForTests(level, v, f, floor, 100);
            int flint = stock(level, id, Items.FLINT), gravel = stock(level, id, Items.GRAVEL);
            ItemStack shovel = ItemStack.EMPTY;
            for (BlockPos p : Villages.storeChests(level, id)) {
                if (level.getBlockEntity(p) instanceof Container c) {
                    for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.STONE_SHOVEL)) shovel = c.getItem(i);
                }
            }
            Kit.log("fd02 sifted " + got[0] + " gravel for " + got[1] + " flint; the stores: flint " + flint + ", gravel " + gravel
                + "; the shovel " + (shovel.isEmpty() ? "gone" : shovel.getDamageValue() + " damage") + "; the floor " + level.getBlockState(floor)
                + "; booked " + java.util.Arrays.toString(Economy.todayForTests(id, "flint")));
            helper.assertTrue(got[0] == 100, "a hundred blocks set down and broken: " + got[0]);
            helper.assertTrue(got[1] >= 1 && got[1] <= 30, "about one in ten a flint, by the game's own loot: " + got[1]);
            helper.assertTrue(flint == got[1] && gravel == 40 - got[1], "the flint in the stores, the rest of the gravel back: flint "
                + flint + ", gravel " + gravel);
            helper.assertTrue(level.getBlockState(floor).isAir(), "nothing left lying on the floor");
            helper.assertTrue(!shovel.isEmpty() && shovel.getDamageValue() > 0, "the stores' shovel back, the worse for it");
            String card = Fletchers.cardLine(f);
            helper.assertTrue(card != null && card.contains("flint out of 100 gravel"), "its card counts the sifting: " + card);
            helper.succeed();
        });
    }

    // ============================================================ fd03: feathers from the coop

    @GameTest(template = EMPTY, timeoutTicks = 4600, batch = "fd03_feathers_from_the_coop")
    public static void fd03_feathers_from_the_coop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final long base = 24000L * 3 + 9300;
        level.setDayTime(base);
        level.updateSkyBrightness();
        final int x = 1344000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = flat(level, x, Z, 40, 40);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 2);
        VillageFolkEntity rancher = folk.get(0), fletcher = folk.get(1);
        final UUID id = rancher.ownerId();
        Villages.ageForTests(id, Villages.Age.STONE);
        final BlockPos pen = Kit.surface(level, x + 16, Z);
        for (Animal a : level.getEntitiesOfClass(Animal.class, new AABB(pen).inflate(70, 32, 70))) a.discard();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 5) continue;
                level.setBlock(Kit.surface(level, pen.getX() + dx, pen.getZ() + dz), Blocks.OAK_FENCE.defaultBlockState(), 3);
            }
        }
        trade(fletcher, StationTask.FLETCHER);
        rancher.assignPlot(WorkZone.around(pen, 6, WorkZone.DEFAULT_DEPTH), "the pen");
        rancher.setJob(StationTask.RANCH);
        rancher.getInventoryItems().clear();
        rancher.insertItem(new ItemStack(Items.IRON_SWORD));
        rancher.insertItem(new ItemStack(Items.BREAD, 6));
        rancher.moveTo(pen.getX() + 0.5, pen.getY(), pen.getZ() + 0.5, 0.0F, 0.0F);
        for (int i = 0; i < 22; i++) {
            Animal a = i < 10 ? EntityType.SHEEP.create(level) : EntityType.CHICKEN.create(level);
            if (a == null) continue;
            a.moveTo(pen.getX() + 0.5 + (i % 5) - 2, pen.getY(), pen.getZ() + 0.5 + (i / 5) - 2, 0.0F, 0.0F);
            a.setPersistenceRequired();
            a.addTag(Drover.HERD);
            level.addFreshEntity(a);
        }
        Predicate<ItemStack> feather = s -> s.is(Items.FEATHER);
        Predicate<ItemStack> hen = s -> s.is(Items.CHICKEN) || s.is(Items.COOKED_CHICKEN);
        final boolean[] counted = { false };
        final int[] culls = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            long tod = level.getDayTime() % 24000L;
            if (tod > 10800 || tod < 9100) level.setDayTime(base);
            if (rancher.breakNowForTests()) level.setDayTime(level.getDayTime() + 200);
            if (t % 20 != 0) return;
            noMonsters(level, pen, 40);
            AABB around = new AABB(pen).inflate(8, 6, 8);
            int sheep = level.getEntitiesOfClass(Sheep.class, around, s -> s.isAlive() && !s.isBaby()).size();
            int hens = level.getEntitiesOfClass(Chicken.class, around, c -> c.isAlive() && !c.isBaby()).size();
            if (!counted[0]) {
                if (sheep >= 10 && hens >= 12) {
                    counted[0] = true;
                    Kit.log("fd03 the pen: " + sheep + " sheep, " + hens + " hens; the fletcher wants feathers first: "
                        + Fletchers.henFirst(level, id));
                    helper.assertTrue(Fletchers.henFirst(level, id) == EntityType.CHICKEN, "the town with a fletcher and no feathers culls hens first");
                } else if (t >= 400) {
                    helper.fail("the flock never came into the world: sheep " + sheep + ", hens " + hens);
                }
                if (!counted[0]) return;
            }
            if (sheep < 10) {
                helper.fail("a sheep was culled while there were hens past the herd: " + sheep + " sheep, " + hens + " hens");
                return;
            }
            if (rancher.peekJob() == null && rancher.cullForTests()) culls[0]++;
            int feathers = rancher.countCarried(feather) + inChests(level, x, Z, 48, feather);
            int meat = rancher.countCarried(hen) + inChests(level, x, Z, 48, hen);
            if (t % 200 == 0) Kit.log("fd03 @" + t + ": sheep " + sheep + ", hens " + hens + ", culls " + culls[0] + ", feathers " + feathers
                + ", chicken " + meat + " - " + rancher.debugLine());
            if (feathers > 0 && hens < 12) {
                int keep = rancher.depositReserve(new ItemStack(Items.FEATHER, feathers));
                Kit.log("fd03 feathers home at tick " + t + ": " + feathers + " from " + (12 - hens) + " hens culled (" + culls[0]
                    + " culls), chicken " + meat + "; kept back of them " + keep);
                helper.assertTrue(hens >= 4, "never below the herd the pen keeps: " + hens);
                helper.assertTrue(keep == 0, "the feathers go to the stores, not kept back in the pack: " + keep);
                helper.assertTrue(meat >= 1, "and the hen's meat with them: " + meat);
                helper.succeed();
                return;
            }
            if (t >= 4500) helper.fail("no feathers came home from the coop: " + culls[0] + " culls, " + hens + " hens - " + rancher.debugLine());
        });
    }

    // ============================================================ fd04: arrows, bows and crossbows from the stores

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fd04_arrows_bows_crossbows")
    public static void fd04_arrows_bows_crossbows(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1346000;
        Kit.hold(level, x, Z, 44);
        Kit.prepare(level, x, Z, 44);
        BlockPos heart = flat(level, x, Z, 38, 38);
        at(level, 3000L);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 4);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity f = folk.get(3);
            trade(f, StationTask.FLETCHER);
            VillageFolkEntity g1 = folk.get(1), g2 = folk.get(2);
            trade(g1, StationTask.GUARD);
            trade(g2, StationTask.GUARD);
            for (VillageFolkEntity g : folk) unarmed(g);
            // The watch's quivers already full (fd05 tests the filling): the arrows made here stay in the stores.
            for (VillageFolkEntity g : List.of(g1, g2)) g.insertItem(new ItemStack(Items.ARROW, 32));
            BlockPos hutAt = heart.offset(14, 0, -10);
            BuildGoal.stamp(level, Fletchers.STRUCTURE, hutAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, Fletchers.STRUCTURE, hutAt, Direction.NORTH);
            BlockPos rangeAt = heart.offset(0, 0, 22);
            BuildGoal.stamp(level, Archery.STRUCTURE, rangeAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, Archery.STRUCTURE, rangeAt, Direction.NORTH);
            for (Archery.Lane l : Archery.lanes(Archery.of(id))) level.setBlock(l.target(), Blocks.HAY_BLOCK.defaultBlockState(), 3);
            // What the founding stores already hold (their string, their planks): every count below is past it.
            java.util.Map<Item, Integer> was = new java.util.HashMap<>();
            for (Item it : List.of(Items.ARROW, Items.SPECTRAL_ARROW, Items.BOW, Items.CROSSBOW, Items.TRIPWIRE_HOOK, Items.TARGET, Items.FLINT,
                    Items.FEATHER, Items.STRING, Items.IRON_INGOT, Items.GLOWSTONE_DUST, Items.REDSTONE, Items.HAY_BLOCK, Items.STICK)) {
                was.put(it, stock(level, id, it));
            }
            java.util.function.ToIntFunction<Item> got = it -> stock(level, id, it) - was.get(it);
            chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.FLETCHING_TABLE), new ItemStack(Items.FLINT, 6),
                new ItemStack(Items.FEATHER, 6), new ItemStack(Items.OAK_PLANKS, 32), new ItemStack(Items.STRING, 12),
                new ItemStack(Items.IRON_INGOT, 6), new ItemStack(Items.GLOWSTONE_DUST, 4), new ItemStack(Items.REDSTONE, 4),
                new ItemStack(Items.HAY_BLOCK, 1));
            List<String> pieces = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String made = Fletchers.workForTests(f, level, v);
                if (made.equals("nothing")) break;
                pieces.add(made);
            }
            int arrows = got.applyAsInt(Items.ARROW), bows = got.applyAsInt(Items.BOW), crossbowsInStores = got.applyAsInt(Items.CROSSBOW);
            int crossbowsCarried = 0;
            for (VillageFolkEntity g : List.of(g1, g2)) crossbowsCarried += g.countCarried(s -> s.is(Items.CROSSBOW));
            Kit.log("fd04 the fletcher's pieces: " + pieces);
            Kit.log("fd04 the stores: arrows " + arrows + ", spectral " + got.applyAsInt(Items.SPECTRAL_ARROW) + ", bows " + bows
                + ", crossbows " + crossbowsInStores + " (carried " + crossbowsCarried + "), hooks " + got.applyAsInt(Items.TRIPWIRE_HOOK)
                + ", targets " + got.applyAsInt(Items.TARGET) + "; flint " + got.applyAsInt(Items.FLINT) + ", feathers " + got.applyAsInt(Items.FEATHER)
                + ", string " + got.applyAsInt(Items.STRING) + ", iron " + got.applyAsInt(Items.IRON_INGOT) + ", glowstone "
                + got.applyAsInt(Items.GLOWSTONE_DUST) + ", redstone " + got.applyAsInt(Items.REDSTONE) + ", hay " + got.applyAsInt(Items.HAY_BLOCK)
                + ", sticks " + got.applyAsInt(Items.STICK) + ", planks " + stock(level, id, s -> s.is(net.minecraft.tags.ItemTags.PLANKS))
                + "; booked arrows " + java.util.Arrays.toString(Economy.todayForTests(id, "arrow")));
            helper.assertTrue(level.getBlockState(Fletchers.tableSpotForTests(id)).is(Blocks.FLETCHING_TABLE), "the stores' table set down in the hut");
            // Six flint and six feathers: six crafts, twenty-four arrows (one of them into the spectral arrows).
            helper.assertTrue(got.applyAsInt(Items.FLINT) == 0 && got.applyAsInt(Items.FEATHER) == 0, "every flint and feather made into arrows");
            helper.assertTrue(arrows + 1 == 24 && got.applyAsInt(Items.SPECTRAL_ARROW) == 2,
                "four arrows to a flint, a stick and a feather (24), one of them and four glowstone dust made into two spectral arrows: "
                    + arrows + " arrows, " + got.applyAsInt(Items.SPECTRAL_ARROW) + " spectral");
            helper.assertTrue(got.applyAsInt(Items.GLOWSTONE_DUST) == 0, "the glowstone used");
            helper.assertTrue(bows == 3, "a bow for each guard with none, and a spare: " + bows);
            helper.assertTrue(crossbowsInStores + crossbowsCarried == 1 && crossbowsCarried == 1, "a crossbow, issued to the best archer: carried "
                + crossbowsCarried + ", in the stores " + crossbowsInStores);
            helper.assertTrue(got.applyAsInt(Items.TRIPWIRE_HOOK) == 1, "the hook's twin kept: an ingot, a stick and a plank make two");
            helper.assertTrue(got.applyAsInt(Items.STRING) == 12 - 9 - 2, "three string a bow, two the crossbow: " + got.applyAsInt(Items.STRING));
            helper.assertTrue(got.applyAsInt(Items.IRON_INGOT) == 4, "two ingots: the crossbow's, and the hook's: " + got.applyAsInt(Items.IRON_INGOT));
            helper.assertTrue(got.applyAsInt(Items.TARGET) == 1 && got.applyAsInt(Items.REDSTONE) == 0 && got.applyAsInt(Items.HAY_BLOCK) == 0,
                "a target for the range of four redstone and a bale");
            helper.assertTrue(Economy.todayForTests(id, "arrow")[0] >= 23, "the arrows booked as the town's making today");
            helper.succeed();
        });
    }

    // ============================================================ fd05: the watch's quivers

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fd05_quivers")
    public static void fd05_quivers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1348000;
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = flat(level, x, Z, 34, 34);
        at(level, 3000L);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 4);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.STONE);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity f = folk.get(3), g1 = folk.get(1), g2 = folk.get(2);
            trade(f, StationTask.FLETCHER);
            for (VillageFolkEntity g : List.of(g1, g2)) {
                trade(g, StationTask.GUARD);
                g.removeMatching(s -> s.is(Items.ARROW), 999);
                unarmed(g);
                g.insertItem(new ItemStack(Items.BOW));
                g.insertItem(new ItemStack(Items.ARROW, 3));
            }
            Container box = chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.ARROW, 64), new ItemStack(Items.ARROW, 36));
            helper.assertTrue(Fletchers.quiver(id, 16) == Fletchers.QUIVER, "a town with a fletcher fills a quiver to thirty-two");
            // A guard's own visit to the stores.
            WatchKit.fit(level, v, g1);
            int g1Arrows = g1.countCarried(s -> s.is(Items.ARROW));
            Kit.log("fd05 g1 after its visit to the stores: " + g1Arrows + " arrows; the stores " + stock(level, id, Items.ARROW));
            helper.assertTrue(g1Arrows == Fletchers.QUIVER, "its quiver filled to thirty-two: " + g1Arrows);
            // After a raid: every quiver filled at once, and the restock in the chronicle.
            g1.removeMatching(s -> s.is(Items.ARROW), 30);
            int before = stock(level, id, Items.ARROW);
            Fletchers.raidOverForTests(level, v);
            int a1 = g1.countCarried(s -> s.is(Items.ARROW)), a2 = g2.countCarried(s -> s.is(Items.ARROW)), after = stock(level, id, Items.ARROW);
            Kit.log("fd05 after the raid: g1 " + a1 + ", g2 " + a2 + "; the stores " + before + " -> " + after + "; " + chronicle(id));
            helper.assertTrue(a1 == Fletchers.QUIVER && a2 == Fletchers.QUIVER, "both quivers full again: " + a1 + ", " + a2);
            helper.assertTrue(before - after == (Fletchers.QUIVER - 2) + (Fletchers.QUIVER - 3), "out of the stores' reserve: " + before + " -> " + after);
            helper.assertTrue(told(id, "restocked the watch after the raid"), "the restock in the chronicle: " + chronicle(id));
            // The raid's reserve is not for sale.
            Budget.forget(id);
            int kept = Budget.keep(level, id, new ItemStack(Items.ARROW));
            helper.assertTrue(kept == Fletchers.reserve(id) && kept >= 2 * Fletchers.RESERVE_EACH, "the shop keeps the raid's reserve back: " + kept);
            // The stores empty, and a guard shot out: the board says so.
            for (int i = 0; i < box.getContainerSize(); i++) box.setItem(i, ItemStack.EMPTY);
            box.setChanged();
            g2.removeMatching(s -> s.is(Items.ARROW), 999);
            Fletchers.fillQuivers(level, v);
            String board = Fletchers.boardLine(level, id);
            Kit.log("fd05 the board: " + board);
            helper.assertTrue(board != null && board.contains("out of arrows") && board.contains(g2.displayNameCap()),
                "the board names the guard with an empty quiver and none in the stores: " + board);
            helper.assertTrue(Fletchers.spread(g1, 6.0F) == 6.0F, "an unpractised guard's spread is the plain six");
            helper.succeed();
        });
    }

    // ============================================================ fd06: practice at the range

    @GameTest(template = EMPTY, timeoutTicks = 5200, batch = "fd06_practice")
    public static void fd06_practice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        final int x = 1350000;
        Kit.hold(level, x, Z, 44);
        Kit.prepare(level, x, Z, 44);
        BlockPos heart = flat(level, x, Z, 38, 38);
        final long day = level.getDayTime() / 24000L + 1;
        final long afternoon = day * 24000L + 6500L;
        level.setDayTime(afternoon);
        level.updateSkyBrightness();
        level.setWeatherParameters(24000, 0, false, false);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 4);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        VillageFolkEntity f = folk.get(3), g1 = folk.get(1), g2 = folk.get(2);
        final int[] arrows0 = { 0 };
        final int[][] aim0 = new int[2][];
        final boolean[] started = { false };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            long tod = level.getDayTime() % 24000L;
            if (tod < 6000 || tod > 10000) level.setDayTime(afternoon);
            for (VillageFolkEntity k : folk) if (k.breakNowForTests()) level.setDayTime(level.getDayTime() + 200);
            if (t % 20 == 0) noMonsters(level, heart, 44);
            if (t == 5) {
                trade(f, StationTask.FLETCHER);
                for (VillageFolkEntity g : List.of(g1, g2)) {
                    trade(g, StationTask.GUARD);
                    unarmed(g);
                    g.insertItem(new ItemStack(Items.BOW));
                    g.insertItem(new ItemStack(Items.ARROW, 32));
                }
                BlockPos rangeAt = heart.offset(0, 0, 20);
                BuildGoal.stamp(level, Archery.STRUCTURE, rangeAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
                Ledger.built(id, Archery.STRUCTURE, rangeAt, Direction.NORTH);
                for (Archery.Lane l : Archery.lanes(Archery.of(id))) {
                    level.setBlock(l.hay(), Blocks.HAY_BLOCK.defaultBlockState(), 3);
                    level.setBlock(l.target(), Blocks.TARGET.defaultBlockState(), 3);
                }
                chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.ARROW, 40));
                arrows0[0] = townArrows(level, id, folk);
                Kit.log("fd06 the town's arrows: " + arrowsHeld(level, id, folk));
                aim0[0] = Fletchers.aimForTests(g1);
                aim0[1] = Fletchers.aimForTests(g2);
                return;
            }
            if (t < 5) return;
            for (VillageFolkEntity g : List.of(g1, g2)) if (g.getTarget() != null && !g.getTarget().isAlive()) g.setTarget(null);
            if (!started[0]) {
                // Called as soon as the watch is free of its first words with the town (Archery.free); or the town's own
                // look round has called it already, it being a quiet afternoon (Fletchers.tick).
                String why = Fletchers.practising(id) ? null : Fletchers.callPracticeForTests(level, v);
                if (why == null) {
                    Kit.log("fd06 practice called at tick " + t + "; arrows in the stores " + arrows0[0]);
                    helper.assertTrue(Fletchers.practising(id), "the fletcher calls practice at the range");
                    started[0] = true;
                } else if (t > 400) {
                    helper.fail("no practice: " + why);
                }
                return;
            }
            // The fletcher's own station brain runs its practice; its duty is driven here too, as the brain would.
            if (Fletchers.practising(id)) {
                Fletchers.duty(f, level);
                if (t % 200 == 0) Kit.log("fd06 @" + t + ": " + f.displayNameCap() + " at " + f.blockPosition().toShortString() + "; "
                    + g1.displayNameCap() + " " + g1.hobbyNow() + "; " + g2.displayNameCap() + " " + g2.hobbyNow() + "; arrows: " + arrowsHeld(level, id, folk));
                if (t > 5000) helper.fail("the practice never finished");
                return;
            }
            var results = Fletchers.lastPracticeForTests(id);
            // The town's arrows (the stores and every quiver in it): a quiver topped up out of the stores meanwhile (the
            // watch's kit, a hunter's) is still the town's; practice itself loses none but a stray or two.
            int arrowsNow = townArrows(level, id, folk);
            Kit.log("fd06 the town's arrows after: " + arrowsHeld(level, id, folk));
            int[] a1 = Fletchers.aimForTests(g1), a2 = Fletchers.aimForTests(g2);
            Kit.log("fd06 practice over at tick " + t + ": " + results.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()[1] + " of "
                + e.getValue()[0]).toList() + "; arrows " + arrows0[0] + " -> " + arrowsNow + "; aim g1 " + java.util.Arrays.toString(aim0[0]) + " -> "
                + java.util.Arrays.toString(a1) + ", g2 " + java.util.Arrays.toString(aim0[1]) + " -> " + java.util.Arrays.toString(a2)
                + "; spread g1 " + Fletchers.spread(g1, 6.0F) + "; card: " + Fletchers.aimLine(g1));
            int practised = (a1[0] > aim0[0][0] ? 1 : 0) + (a2[0] > aim0[1][0] ? 1 : 0);
            helper.assertTrue(!results.isEmpty() && practised >= 1, "the watch took its turns at the butts: " + results.keySet());
            VillageFolkEntity shot = a1[0] > aim0[0][0] ? g1 : g2;
            int[] after = shot == g1 ? a1 : a2, was = shot == g1 ? aim0[0] : aim0[1];
            helper.assertTrue(after[2] == Fletchers.PRACTICE_ARROWS, "ten arrows each at the fletcher's practice: " + after[2]);
            helper.assertTrue(after[3] > was[3], "its aim the steadier for it: " + was[3] + " -> " + after[3]);
            helper.assertTrue(Fletchers.spread(shot, 6.0F) < 6.0F, "its spread in a fight the narrower: " + Fletchers.spread(shot, 6.0F));
            String card = Fletchers.aimLine(shot);
            helper.assertTrue(card != null && card.startsWith("best at the butts: ") && card.contains(" of 10"), "its best on its card: " + card);
            helper.assertTrue(arrowsNow >= arrows0[0] - 4 && arrowsNow <= arrows0[0], "the arrows pulled and back to the town: "
                + arrows0[0] + " -> " + arrowsNow);
            helper.succeed();
        });
    }

    // ============================================================ fd07: an iron golem, the game's way, at its post

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "fd07_iron_golem")
    public static void fd07_iron_golem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1352000;
        Kit.hold(level, x, Z, 44);
        Kit.prepare(level, x, Z, 44);
        BlockPos heart = flat(level, x, Z, 38, 38);
        at(level, 3000L);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 4);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        final IronGolem[] golem = { null };
        final BlockPos[] post = { null };
        final long[] carried = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t % 20 == 0) noMonsters(level, heart, 44);
            if (t == 5) {
                long today = level.getDayTime() / 24000L;
                helper.assertTrue(!Golems.wanted(id), "a small Iron Age town never raided wants no golem keeper");
                Golems.raidForTests(id, today - 20);
                Golems.raidForTests(id, today - 9);
                helper.assertTrue(!Golems.wanted(id), "two raids, but not in a fortnight: none yet");
                Golems.raidForTests(id, today);
                helper.assertTrue(Golems.wanted(id) && Golems.raidsLately(id, today) == 2, "two raids in a fortnight: a keeper wanted");
                VillageFolkEntity guard = folk.get(1);
                trade(guard, StationTask.GUARD);
                folk.get(3).setJob(StationTask.NONE);                      // a hand between trades, free to take it up
                List<VillageFolkEntity> shortlist = Golems.shortlist(id);
                VillageFolkEntity k = Golems.appoint(level, v);
                Kit.log("fd07 the shortlist " + shortlist.stream().map(f -> f.displayNameCap() + " " + f.stationTask()).toList() + "; the keeper "
                    + (k == null ? "nobody" : k.displayNameCap()));
                helper.assertTrue(k != null && k != guard && k == shortlist.get(0) && k.stationTask() == StationTask.GOLEMS,
                    "the best of the town's own takes the place, never the watch");
                helper.assertTrue(told(id, "keeping the town's golems"), "into the chronicle: " + chronicle(id));
                helper.assertTrue(Golems.yardWanted(id) && Villages.projectsWanted(id).contains(Golems.STRUCTURE)
                    && BuildGoal.STRUCTURES.contains(Golems.STRUCTURE), "the golem yard on the builders' list: " + Villages.projectsWanted(id));
                BlockPos yardAt = heart.offset(-14, 0, -12);
                BuildGoal.stamp(level, Golems.STRUCTURE, yardAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
                Ledger.built(id, Golems.STRUCTURE, yardAt, Direction.NORTH);
                helper.assertTrue(!Golems.yardWanted(id), "the yard stands");
                // Iron enough for a golem and four over, past what the watch's armour and blades still want (fd10 tests the want).
                int watchIron = Golems.ironForTheWatch(level, v);
                int ingots = 40 + watchIron;
                chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.IRON_INGOT, Math.min(64, ingots)),
                    new ItemStack(Items.IRON_INGOT, Math.max(0, ingots - 64)), new ItemStack(Items.PUMPKIN), new ItemStack(Items.SHEARS));
                Kit.log("fd07 the watch's kit still wants " + watchIron + " ingots; the stores hold " + stock(level, id, Items.IRON_INGOT));
                String said = Golems.buildForTests(level, v, k, "square");
                List<String[]> kept = Golems.keptForTests(id);
                Kit.log("fd07 the build: " + said + "; kept " + kept.stream().map(java.util.Arrays::toString).toList() + "; the stores: ingots "
                    + stock(level, id, Items.IRON_INGOT) + ", blocks " + stock(level, id, Items.IRON_BLOCK) + ", pumpkins " + stock(level, id, Items.PUMPKIN)
                    + ", seeds " + stock(level, id, Items.PUMPKIN_SEEDS) + ", shears " + stock(level, id, Items.SHEARS));
                helper.assertTrue(said.startsWith("raised ") && kept.size() == 1, "a golem raised at the square: " + said);
                String[] g = kept.get(0);
                String[] p = g[4].split(",");
                post[0] = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                golem[0] = level.getEntity(UUID.fromString(g[0])) instanceof IronGolem ig ? ig : null;
                helper.assertTrue(golem[0] != null && golem[0].isAlive(), "an iron golem in the world");
                helper.assertTrue(golem[0].isPlayerCreated(), "made by hand: the game's own pumpkin check stood it up, and it never turns on the town");
                helper.assertTrue(golem[0].hasCustomName() && golem[0].getCustomName().getString().equals(g[1]), "named: " + g[1]);
                helper.assertTrue(golem[0].blockPosition().distManhattan(post[0]) <= 1, "standing where its T stood: " + golem[0].blockPosition() + " / " + post[0]);
                int ironLeft = 0;
                for (BlockPos q : BlockPos.betweenClosed(post[0].offset(-2, 0, -2), post[0].offset(2, 3, 2))) {
                    if (level.getBlockState(q).is(Blocks.IRON_BLOCK) || level.getBlockState(q).is(Blocks.CARVED_PUMPKIN)) ironLeft++;
                }
                helper.assertTrue(ironLeft == 0, "the T and its head taken up into the golem: " + ironLeft + " blocks left");
                helper.assertTrue(stock(level, id, Items.IRON_INGOT) == ingots - 36 && stock(level, id, Items.IRON_BLOCK) == 0,
                    "four blocks of nine of the stores' ingots: " + stock(level, id, Items.IRON_INGOT) + " ingots left of " + ingots);
                helper.assertTrue(stock(level, id, Items.PUMPKIN) == 0 && stock(level, id, Items.PUMPKIN_SEEDS) == 4 && stock(level, id, Items.SHEARS) == 1,
                    "the farm's pumpkin carved where it sat with the stores' shears, four seeds into the stores");
                helper.assertTrue(told(id, "raised an iron golem, " + g[1]), "into the chronicle: " + chronicle(id));
                // Carried off fourteen blocks: it walks back to its post.
                golem[0].teleportTo(post[0].getX() + 14.5, post[0].getY(), post[0].getZ() + 0.5);
                carried[0] = t;
                return;
            }
            if (carried[0] < 0 || golem[0] == null) return;
            if (t % 20 == 0) Golems.tickForTests(level, v);
            double d = Math.sqrt(golem[0].blockPosition().distSqr(post[0]));
            if (t % 100 == 0) Kit.log("fd07 @" + t + ": the golem " + d + " from its post, restricted " + golem[0].hasRestriction());
            if (t - carried[0] > 40 && d <= 6.0) {
                Kit.log("fd07 back at its post at tick " + t + ": " + d);
                helper.assertTrue(golem[0].hasRestriction() && golem[0].getRestrictCenter().equals(post[0]), "its beat is its post");
                String board = Golems.boardLine(level, id);
                helper.assertTrue(board != null && board.contains("at the square"), "the board: " + board);
                helper.succeed();
                return;
            }
            if (t - carried[0] > 1000) helper.fail("the golem never walked back to its post: " + d + " blocks off");
        });
    }

    // ============================================================ fd08: the golem from before, mended, and lost

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fd08_mend_and_mourn")
    public static void fd08_mend_and_mourn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1354000;
        Kit.hold(level, x, Z, 44);
        Kit.prepare(level, x, Z, 44);
        BlockPos heart = flat(level, x, Z, 38, 38);
        at(level, 3000L);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 3);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity k = folk.get(2);
            trade(k, StationTask.GOLEMS);
            noMonsters(level, heart, 44);
            // The town's golem from before, nobody's to look after.
            IronGolem old = EntityType.IRON_GOLEM.create(level);
            old.moveTo(heart.getX() + 6.5, heart.getY(), heart.getZ() + 6.5, 0.0F, 0.0F);
            level.addFreshEntity(old);
            int adopted = Golems.adoptForTests(level, v, k);
            List<String[]> kept = Golems.keptForTests(id);
            Kit.log("fd08 taken in hand: " + adopted + "; " + kept.stream().map(java.util.Arrays::toString).toList());
            helper.assertTrue(adopted == 1 && kept.size() == 1 && kept.get(0)[0].equals(old.getUUID().toString()), "the old golem is the keeper's now");
            helper.assertTrue(old.hasCustomName() && old.getTags().contains(Golems.TAG), "named, and the town's: " + kept.get(0)[1]);
            String name = kept.get(0)[1];
            Container box = chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.IRON_INGOT, 10));
            // Hurt: mended with the stores' ingots, a quarter of it an ingot.
            old.setHealth(30.0F);
            Crackiness.Level hurt = old.getCrackiness();
            int used = Golems.mendForTests(level, v, k, old, 8);
            Kit.log("fd08 mended " + name + " with " + used + " ingots: " + hurt + " -> " + old.getCrackiness() + ", health " + old.getHealth());
            helper.assertTrue(hurt != Crackiness.Level.NONE, "cracked when hurt: " + hurt);
            helper.assertTrue(used == 3 && old.getHealth() == old.getMaxHealth(), "three ingots from thirty: " + used + ", " + old.getHealth());
            helper.assertTrue(old.getCrackiness() == Crackiness.Level.NONE, "its cracks gone as it mended");
            helper.assertTrue(stock(level, id, Items.IRON_INGOT) == 7, "out of the stores: " + stock(level, id, Items.IRON_INGOT));
            // On the keeper's round.
            old.setHealth(50.0F);
            String round = Golems.roundForTests(k, level, v);
            Kit.log("fd08 the keeper's round: " + round + "; health " + old.getHealth() + ", ingots " + stock(level, id, Items.IRON_INGOT));
            helper.assertTrue(round.startsWith("off to mend") && old.getHealth() == old.getMaxHealth() && stock(level, id, Items.IRON_INGOT) == 5,
                "the keeper goes and mends it with two more: " + round);
            // Lost: mourned, and its iron gathered back into the stores.
            BlockPos fell = old.blockPosition();
            old.kill();
            List<String[]> fallen = Golems.fallenForTests(id);
            int lying = level.getEntitiesOfClass(ItemEntity.class, new AABB(fell).inflate(6), e -> e.getItem().is(Items.IRON_INGOT)).stream()
                .mapToInt(e -> e.getItem().getCount()).sum();
            Kit.log("fd08 fallen: " + fallen.stream().map(java.util.Arrays::toString).toList() + "; iron lying " + lying + "; " + chronicle(id));
            helper.assertTrue(fallen.size() == 1 && fallen.get(0)[0].equals(name) && Golems.keptForTests(id).isEmpty(), "the golem's fall in the books");
            helper.assertTrue(told(id, name + " fell at "), "mourned in the chronicle: " + chronicle(id));
            helper.assertTrue(lying >= 3, "its iron lying where it fell: " + lying);
            int ingots = stock(level, id, Items.IRON_INGOT);
            String gather = Golems.roundForTests(k, level, v);
            int back = stock(level, id, Items.IRON_INGOT) - ingots;
            Kit.log("fd08 the round after: " + gather + "; " + back + " ingots back into the stores; " + Golems.fallenForTests(id).stream()
                .map(java.util.Arrays::toString).toList());
            helper.assertTrue(gather.startsWith("off to gather") && back == lying && "true".equals(Golems.fallenForTests(id).get(0)[2]),
                "the keeper gathers its iron into the stores: " + back + " of " + lying);
            helper.succeed();
        });
    }

    // ============================================================ fd09: snow golems in winter, cold and hot

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "fd09_snow_golems")
    public static void fd09_snow_golems(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1356000, hot = 1357000;
        Kit.hold(level, x, Z, 36);
        Kit.prepare(level, x, Z, 36);
        Kit.hold(level, hot, Z, 36);
        Kit.prepare(level, hot, Z, 36);
        BlockPos heart = flat(level, x, Z, 30, 30), heart2 = flat(level, hot, Z, 30, 30);
        at(level, 3000L);
        List<VillageFolkEntity> cold = raise(helper, level, heart, 2), warm = raise(helper, level, heart2, 2);
        UUID id = cold.get(0).ownerId(), id2 = warm.get(0).ownerId();
        helper.assertTrue(id != null && id2 != null && !id.equals(id2), "two towns");
        Villages.Village v = Villages.get(id), v2 = Villages.get(id2);
        helper.runAtTickTime(5, () -> {
            long today = level.getDayTime() / 24000L;
            String[] biomes = { "minecraft:snowy_plains", "minecraft:desert" };
            Villages.Village[] towns = { v, v2 };
            List<VillageFolkEntity> keepers = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Villages.Village t = towns[i];
                Villages.ageForTests(t.id(), Villages.Age.IRON);
                VillageFolkEntity k = (i == 0 ? cold : warm).get(1);
                trade(k, StationTask.GOLEMS);
                keepers.add(k);
                BlockPos h = i == 0 ? heart : heart2;
                BlockPos tower = h.offset(10, 0, 10);
                BuildGoal.stamp(level, "watchtower", tower, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
                Ledger.built(t.id(), "watchtower", tower, Direction.NORTH);
                List<String> said = Kit.command(level, "fillbiome " + (tower.getX() - 8) + " " + (tower.getY() - 4) + " " + (tower.getZ() - 8) + " "
                    + (tower.getX() + 8) + " " + (tower.getY() + 16) + " " + (tower.getZ() + 8) + " " + biomes[i]);
                Kit.log("fd09 fillbiome " + biomes[i] + ": " + said);
                chestAt(level, h.offset(3, 0, -3), new ItemStack(Items.SNOWBALL, 8), new ItemStack(Items.CARVED_PUMPKIN));
                Festivals.turnTo(t.id(), today, 22);
            }
            // The cold town: a snow golem on the tower.
            String built = Golems.snowForTests(level, v, keepers.get(0));
            List<String[]> kept = Golems.keptForTests(id);
            Kit.log("fd09 the cold town: " + built + "; kept " + kept.stream().map(java.util.Arrays::toString).toList()
                + "; snowballs " + stock(level, id, Items.SNOWBALL) + ", pumpkins " + stock(level, id, Items.CARVED_PUMPKIN));
            helper.assertTrue(built.startsWith("built ") && kept.size() == 1 && kept.get(0)[2].equals("SNOW"), "a snow golem built in a cold winter: " + built);
            String[] p = kept.get(0)[4].split(",");
            BlockPos spot = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            List<SnowGolem> up = level.getEntitiesOfClass(SnowGolem.class, new AABB(spot).inflate(2));
            helper.assertTrue(up.size() == 1 && up.get(0).getUUID().toString().equals(kept.get(0)[0]), "standing on the tower's deck: " + up.size());
            helper.assertTrue(spot.getY() >= heart.getY() + 8, "up on the watchtower: " + spot.getY() + " over " + heart.getY());
            helper.assertTrue(!Golems.warm(level, spot), "a snowy biome: it will not melt");
            helper.assertTrue(stock(level, id, Items.SNOWBALL) == 0 && stock(level, id, Items.CARVED_PUMPKIN) == 0,
                "eight of the stores' snowballs packed into two blocks, and the pumpkin");
            helper.assertTrue(told(id, "built a snow golem"), "into the chronicle: " + chronicle(id));
            // The hot town: none, and why.
            String none = Golems.snowForTests(level, v2, keepers.get(1));
            int hotGolems = level.getEntitiesOfClass(SnowGolem.class, new AABB(heart2).inflate(36)).size();
            Kit.log("fd09 the hot town: " + none + "; snow golems " + hotGolems + "; snowballs " + stock(level, id2, Items.SNOWBALL));
            helper.assertTrue(none.contains("too warm") && hotGolems == 0 && Golems.keptForTests(id2).isEmpty(), "none in the desert, and why: " + none);
            helper.assertTrue(stock(level, id2, Items.SNOWBALL) == 8 && stock(level, id2, Items.CARVED_PUMPKIN) == 1, "nothing used for it");
            // Spring: let go, and melted away.
            Festivals.turnTo(id, today, 1);
            Golems.tickForTests(level, v);
            boolean gone = level.getEntitiesOfClass(SnowGolem.class, new AABB(spot).inflate(4), SnowGolem::isAlive).isEmpty();
            Kit.log("fd09 spring: gone " + gone + "; kept " + Golems.keptForTests(id).size() + "; " + chronicle(id));
            helper.assertTrue(gone && Golems.keptForTests(id).isEmpty() && told(id, "melted away"), "let go in the spring, and melted away");
            helper.succeed();
        });
    }

    // ============================================================ fd10: no golem while the watch wants the iron

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "fd10_iron_short")
    public static void fd10_iron_short(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 1358000;
        Kit.hold(level, x, Z, 44);
        Kit.prepare(level, x, Z, 44);
        BlockPos heart = flat(level, x, Z, 38, 38);
        at(level, 3000L);
        List<VillageFolkEntity> folk = raise(helper, level, heart, 4);
        UUID id = folk.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        helper.runAtTickTime(5, () -> {
            VillageFolkEntity k = folk.get(3);
            trade(k, StationTask.GOLEMS);
            for (VillageFolkEntity g : List.of(folk.get(1), folk.get(2))) {
                trade(g, StationTask.GUARD);
                for (EquipmentSlot slot : new EquipmentSlot[]{ EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
                    g.setItemSlot(slot, ItemStack.EMPTY);
                }
            }
            noMonsters(level, heart, 44);
            Container box = chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.IRON_INGOT, 60));
            String noHead = Golems.cannotAfford(level, v);
            int watch = Golems.ironForTheWatch(level, v);
            Kit.log("fd10 the watch's kit wants " + watch + " ingots; without a pumpkin: " + noHead);
            helper.assertTrue(watch > 60 - Golems.IRON_EACH, "two guards with no armour: the watch wants more than a golem would leave: " + watch);
            box.setItem(1, new ItemStack(Items.CARVED_PUMPKIN));
            box.setChanged();
            String said = Golems.buildForTests(level, v, k, "square");
            String round = Golems.roundForTests(k, level, v);
            int golems = level.getEntitiesOfClass(IronGolem.class, new AABB(heart).inflate(40)).size();
            Kit.log("fd10 with 60 ingots: " + said + "; the round: " + round + "; golems " + golems + "; ingots " + stock(level, id, Items.IRON_INGOT)
                + ", blocks " + stock(level, id, Items.IRON_BLOCK) + "; why: " + Golems.whyForTests(id));
            helper.assertTrue(said.startsWith("cannot: short of iron for the watch"), "no golem while the watch wants the iron: " + said);
            helper.assertTrue(round.equals("nothing") && golems == 0, "and none on the keeper's round: " + round);
            helper.assertTrue(stock(level, id, Items.IRON_INGOT) == 60 && stock(level, id, Items.IRON_BLOCK) == 0, "not an ingot of it touched");
            String card = Golems.cardLine(k);
            helper.assertTrue(card != null && card.contains("short of iron for the watch"), "the keeper's card says why: " + card);
            // Iron enough for both: up it goes.
            box.setItem(2, new ItemStack(Items.IRON_INGOT, 64));
            box.setChanged();
            String then = Golems.buildForTests(level, v, k, "square");
            int now = level.getEntitiesOfClass(IronGolem.class, new AABB(heart).inflate(40), IronGolem::isPlayerCreated).size();
            Kit.log("fd10 with " + (60 + 64) + " ingots: " + then + "; golems " + now + "; ingots left " + stock(level, id, Items.IRON_INGOT));
            helper.assertTrue(then.startsWith("raised ") && now == 1, "with iron for the watch and a golem both, the golem: " + then);
            helper.assertTrue(stock(level, id, Items.IRON_INGOT) >= watch, "the watch's iron left in the stores: " + stock(level, id, Items.IRON_INGOT));
            helper.succeed();
        });
    }
}
