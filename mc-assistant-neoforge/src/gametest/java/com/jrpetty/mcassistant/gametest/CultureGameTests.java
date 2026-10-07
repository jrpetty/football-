package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Assemblies;
import com.jrpetty.mcassistant.entity.Cafe;
import com.jrpetty.mcassistant.entity.Crier;
import com.jrpetty.mcassistant.entity.Culture;
import com.jrpetty.mcassistant.entity.FoundingDay;
import com.jrpetty.mcassistant.entity.Gatherings;
import com.jrpetty.mcassistant.entity.Heraldry;
import com.jrpetty.mcassistant.entity.Homeland;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Music;
import com.jrpetty.mcassistant.entity.Paintings;
import com.jrpetty.mcassistant.entity.Persona;
import com.jrpetty.mcassistant.entity.Plaques;
import com.jrpetty.mcassistant.entity.RestDay;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.Theatre;
import com.jrpetty.mcassistant.entity.Traditions;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.TownPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Culture and identity [batchD]: the town's banner and motto, its customs, the theatre, the band and the
 * choir, its pictures and its plaques.
 *
 * <ul>
 * <li><b>ci01</b>: a river town's banner is drawn once (a pale blue field, the river's bend and two charges
 *     more) and kept, whoever leads it after; two banners are woven of the stores' banner and dyes and hung
 *     either side of the meeting hall's door, patterned as drawn; the shop's sign goes up, a stranger is
 *     refused a copy and a citizen buys one, the coin into the treasury.</li>
 * <li><b>ci02</b>: the motto ("By the river, ...") is carved over the hall's door on a sign out of the stores,
 *     and the crier cries it on a feast day (the weekly feast) but not on a plain one.</li>
 * <li><b>ci03</b>: the founding, a raid with one fallen and the first diamond become customs, a great storm
 *     after them not (three at most); on Founding Day lanterns go round the square out of the stores and are
 *     taken in again the next morning; the crier cries the silence the day before and on the day; at the
 *     dusk bell the town keeps a minute's silence; on Diamond Night the eldest gives the toast and a drink each
 *     comes out of the stores, the bottles back.</li>
 * <li><b>ci04</b>: an Iron Age town with players wants a theatre; stamped, it has a stage and benches; tonight's
 *     play is cast from the readers, musicians and whittlers, the rest of the town take the benches, every line
 *     is said in turn, it goes into the chronicle, and the next play is another story.</li>
 * <li><b>ci05</b>: on the evening of the day of rest the band goes to the tavern only with note blocks out of
 *     the stores, one each; it plays together, and the note blocks go back after; at a feast it plays there.</li>
 * <li><b>ci06</b>: at the morning service of the day of rest the musicians and a sociable cheerful folk stand
 *     before the chapel's altar and sing the town's hymn.</li>
 * <li><b>ci07</b>: a whittler paints a picture of eight of the stores' sticks and a wool, signed, into the stores,
 *     bought off it out of the treasury; another with no makings paints nothing; the town's works hang the
 *     pictures in the tavern, two at most.</li>
 * <li><b>ci08</b>: plaques go up at the founding spot (a post and a sign), on the first house beside its door, where
 *     a hero fell and at the record harvest's field, out of the stores, none of them on a street.</li>
 * </ul>
 *
 * <p>Each runs on its own ground in the band x 660,000 to 679,999, z 50,000, in a batch of its own, calling the
 * town's logic directly and logging what it sees.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class CultureGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 50000;

    /** Flat grass round here, clear air above it. Returns the ground's top (the first air). */
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

    /** A marked store chest at exactly this spot, filled with these. */
    private static Container chestAt(ServerLevel level, BlockPos at, ItemStack... goods) {
        level.setBlock(at, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, at);
        Container box = (Container) level.getBlockEntity(at);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        box.setChanged();
        Villages.forgetStock();
        return box;
    }

    private static void put(Container box, ItemStack s) {
        for (int i = 0; i < box.getContainerSize(); i++) {
            if (box.getItem(i).isEmpty()) {
                box.setItem(i, s);
                box.setChanged();
                Villages.forgetStock();
                return;
            }
        }
    }

    private static Item item(String name) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(name));
    }

    private static boolean told(UUID village, String words) {
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().contains(words)) return true;
        return false;
    }

    private record Town(UUID id, BlockPos heart, List<VillageFolkEntity> folk) {}

    /** A town of so many folk on flat ground at this x, at this hour of a day two on. */
    private static Town town(GameTestHelper helper, ServerLevel level, int x, int n, int r, long hour) {
        Kit.reset(level);
        level.setWeatherParameters(24000, 0, false, false);           // clear skies: nobody in out of a storm
        Kit.hold(level, x, Z, r + 8);
        Kit.prepare(level, x, Z, r + 8);
        BlockPos heart = flat(level, x, Z, r);
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

    /** The first day from this one that is the town's day of rest (a week after its founding at least). */
    private static long restDay(UUID id, long from) {
        for (long d = from; d < from + 30; d++) if (RestDay.today(id, d)) return d;
        return -1;
    }

    // ============================================================ ci01: the banner

    /**
     * A river town's banner, drawn once and kept; woven of the stores' banner and dyes and hung either side of
     * the hall's door as drawn; a copy at the shop's sign for a citizen and nobody else.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ci01_banner")
    public static void ci01_banner(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 660000, 5, 32, 2000L);
        UUID id = t.id();
        Homeland.setForTests(id, Homeland.Land.RIVER);
        BlockPos hallAt = t.heart().offset(0, 0, 16), shopAt = t.heart().offset(-16, 0, -14);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            Heraldry.chooseForTests(level, v);
            Heraldry.Design d = Heraldry.design(id);
            helper.assertTrue(d != null, "the banner is drawn once the town's land has been looked over");
            Kit.log("ci01 the banner of " + Villages.name(id) + ": " + d.blazon() + " " + d.layers());
            helper.assertTrue(d.field() == DyeColor.LIGHT_BLUE, "a river town's field is the river's pale blue: " + d.field());
            helper.assertTrue(d.layers().size() >= 2 && d.layers().size() <= 3, "two or three charges: " + d.layers());
            helper.assertTrue(d.layers().get(0).charge() == Heraldry.Charge.BEND, "the river's bend across it first: " + d.layers());
            for (Heraldry.Layer l : d.layers()) helper.assertTrue(l.colour() != d.field(), "every charge shows on the field: " + l);
            helper.assertTrue(told(id, "chose the town's banner"), "into the town's history");
            // Fixed for the town: its folk of another nature now, and the banner is the same.
            for (VillageFolkEntity f : t.folk()) f.life().setTraitsForTests(Social.Trait.GRUMPY, Social.Trait.SHY);
            Heraldry.chooseForTests(level, v);
            helper.assertTrue(d.equals(Heraldry.design(id)), "the banner is the town's for good: " + Heraldry.design(id));

            // The meeting hall, and in the stores the makings of the banners: the field's banner and a dye a charge.
            BuildGoal.stamp(level, "hall", hallAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "hall", hallAt, Direction.NORTH);
            Item field = item(d.field().getName() + "_banner");
            Container box = chestAt(level, t.heart().offset(3, 0, -3), new ItemStack(field, 3), new ItemStack(Items.OAK_SIGN, 4));
            for (Heraldry.Layer l : d.layers()) put(box, new ItemStack(item(l.colour().getName() + "_dye"), 2));
            String did = Heraldry.putForTests(level, v);
            List<BlockPos> hung = Heraldry.hungForTests(level, id);
            Kit.log("ci01 put up: " + did + "; hung at " + hung + "; places " + Heraldry.placesForTests(level, id));
            helper.assertTrue(hung.size() == 2, "a banner either side of the hall's door: " + Heraldry.placesForTests(level, id));
            for (BlockPos p : hung) {
                BannerBlockEntity be = level.getBlockEntity(p) instanceof BannerBlockEntity b ? b : null;
                helper.assertTrue(be != null && be.getBaseColor() == d.field(), "a banner of the field's colour at " + p.toShortString());
                helper.assertTrue(be.getPatterns().layers().size() == d.layers().size(), "woven as drawn: " + be.getPatterns().layers().size()
                    + " charges of " + d.layers().size());
                int across = Math.abs(p.getX() - hallAt.getX());
                helper.assertTrue(across == 2 && p.getZ() > hallAt.getZ(), "either side of the door, out front: " + p.toShortString());
            }
            helper.assertTrue(Market.stock(level, id, s -> s.is(field)) == 1, "two of the stores' banners went into them: "
                + Market.stock(level, id, s -> s.is(field)) + " left of 3");

            // The shop's sign, and a copy: refused to a stranger, sold to a citizen.
            BuildGoal.stamp(level, "shop", shopAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "shop", shopAt, Direction.NORTH);
            for (Heraldry.Layer l : d.layers()) put(box, new ItemStack(item(l.colour().getName() + "_dye"), 2));
            BlockPos sign = Heraldry.shopSignForTests(level, v);
            helper.assertTrue(sign != null && level.getBlockEntity(sign) instanceof SignBlockEntity, "the shop's sign for the banner is up");
            String first = ((SignBlockEntity) level.getBlockEntity(sign)).getFrontText().getMessage(0, false).getString();
            helper.assertTrue("Our banner".equals(first), "it reads \"Our banner\": " + first);
            Player you = helper.makeMockPlayer(GameType.SURVIVAL);
            you.setPos(t.heart().getX() + 0.5, t.heart().getY(), t.heart().getZ() + 0.5);
            you.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 40));
            String no = Heraldry.sell(level, v, you);
            Kit.log("ci01 a stranger: " + no);
            helper.assertTrue(Market.coinsHeld(you) == 40 && no.contains("citizens"), "no banner for a stranger: " + no);
            Ledger.addCitizen(id, you.getUUID(), you.getName().getString());
            int treasury = Ledger.coins(id);
            String yes = Heraldry.sell(level, v, you);
            int paid = 40 - Market.coinsHeld(you);
            ItemStack copy = ItemStack.EMPTY;
            for (int i = 0; i < you.getInventory().getContainerSize(); i++) {
                ItemStack s = you.getInventory().getItem(i);
                if (s.is(field)) copy = s;
            }
            BannerPatternLayers layers = copy.get(DataComponents.BANNER_PATTERNS);
            Kit.log("ci01 a citizen: " + yes + "; paid " + paid + ", the treasury " + treasury + " -> " + Ledger.coins(id));
            helper.assertTrue(!copy.isEmpty() && layers != null && layers.layers().size() == d.layers().size(), "the citizen has its copy, woven: " + copy);
            helper.assertTrue(paid > 0 && Ledger.coins(id) - treasury == paid, "paid for, into the treasury: " + paid);
            helper.assertTrue(Market.stock(level, id, s -> s.is(field)) == 0, "made of the stores' last banner");
            helper.succeed();
        });
    }

    // ============================================================ ci02: the motto

    /** The motto carved over the hall's door, and cried on a feast day, not on a plain one. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ci02_motto")
    public static void ci02_motto(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 662000, 5, 32, 2000L);
        UUID id = t.id();
        Homeland.setForTests(id, Homeland.Land.RIVER);
        BlockPos hallAt = t.heart().offset(0, 0, 16);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            Heraldry.chooseForTests(level, v);
            String motto = Heraldry.motto(id);
            Kit.log("ci02 the motto of " + Villages.name(id) + ": " + motto);
            helper.assertTrue(motto != null && motto.startsWith("By the river, "), "a river town's motto begins with its river: " + motto);
            BuildGoal.stamp(level, "hall", hallAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "hall", hallAt, Direction.NORTH);
            chestAt(level, t.heart().offset(3, 0, -3), new ItemStack(Items.OAK_SIGN, 2));
            String did = Heraldry.putForTests(level, v);
            String[] carved = Heraldry.carvedForTests(level, id);
            Kit.log("ci02 put up: " + did + "; over the door: " + (carved == null ? "nothing" : String.join(" / ", carved)));
            helper.assertTrue(carved != null, "a sign over the hall's door");
            String said = String.join(" ", carved).trim().replaceAll(" +", " ");
            helper.assertTrue(said.equals(motto), "the motto carved on it: " + said);
            helper.assertTrue(Market.stock(level, id, s -> s.is(ItemTags.SIGNS)) == 1, "the sign came out of the stores");
            helper.assertTrue(told(id, "was carved over the door"), "into the town's history");

            // A feast day (the weekly feast): the crier cries the motto.
            long today = level.getDayTime() / 24000L, feast = -1, plain = -1;
            for (long d = today + 1; d < today + 15 && (feast < 0 || plain < 0); d++) {
                if (FoundingDay.today(id, d) || RestDay.today(id, d)) continue;
                Gatherings.Kind k = Gatherings.tonight(id, d);
                if (k == Gatherings.Kind.FEAST && feast < 0) feast = d;
                if (k == null && plain < 0) plain = d;
            }
            helper.assertTrue(feast > 0 && plain > 0, "a feast day and a plain one: " + feast + ", " + plain);
            level.setDayTime(feast * 24000L + 6050L);
            List<String> lines = Traditions.crierLines(level, v, feast);
            Kit.log("ci02 the feast day's lines: " + lines);
            helper.assertTrue(lines.stream().anyMatch(l -> l.contains(motto)), "the motto on a feast day: " + lines);
            boolean begun = Crier.beginForTests(level, v);
            List<String> cried = Crier.linesForTests(id);
            Kit.log("ci02 the crier (" + begun + "): " + cried);
            helper.assertTrue(begun && cried.stream().anyMatch(l -> l.contains(motto)), "the crier cries it with the news: " + cried);
            level.setDayTime(plain * 24000L + 6050L);
            List<String> plainLines = Traditions.crierLines(level, v, plain);
            helper.assertTrue(plainLines.stream().noneMatch(l -> l.contains(motto)), "not on a plain day: " + plainLines);
            helper.succeed();
        });
    }

    // ============================================================ ci03: the customs

    /** Three great days become customs (the fourth not); lanterns, the silence and the toast kept on their days. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ci03_customs")
    public static void ci03_customs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 664000, 5, 28, 2000L);
        UUID id = t.id();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            // Forty days on, so the town's made-up history (founded twenty days back) begins after the world's day 0.
            level.setDayTime((level.getDayTime() / 24000L + 40) * 24000L + 2000L);
            level.updateSkyBrightness();
            long today = level.getDayTime() / 24000L, f0 = today - 20;
            FoundingDay.foundedForTests(id, f0);
            String name = Villages.name(id);
            Villages.tell(id, f0 + 3, "6 raiders came at the north gate in the night; the watch killed 4 of them, and 1 of the village fell");
            Villages.tell(id, f0 + 6, "Ember the miner mined the town's first diamond");
            Villages.tell(id, f0 + 8, "a great storm broke over " + name);
            List<Traditions.Custom> customs = Traditions.adoptForTests(level, v);
            Kit.log("ci03 customs: " + customs);
            helper.assertTrue(customs.size() == 3, "three customs at most: " + customs);
            helper.assertTrue(customs.get(0).why() == Traditions.Why.FOUNDING && customs.get(0).how() == Traditions.How.LANTERNS,
                "the founding first, with lanterns: " + customs.get(0));
            helper.assertTrue(customs.stream().anyMatch(c -> c.why() == Traditions.Why.RAID && c.how() == Traditions.How.SILENCE),
                "the raid with one fallen, a silence: " + customs);
            helper.assertTrue(customs.stream().anyMatch(c -> c.why() == Traditions.Why.DIAMOND && c.how() == Traditions.How.TOAST),
                "the first diamond, a toast: " + customs);
            helper.assertTrue(customs.stream().noneMatch(c -> c.why() == Traditions.Why.STORM), "the storm, a fourth, is not taken up");

            // Founding Day: lanterns round the square before dusk, out of the stores; taken in the next morning.
            long founding = f0 + TownCalendarYear.DAYS;
            level.setDayTime(founding * 24000L + 9500L);
            level.updateSkyBrightness();
            chestAt(level, t.heart().offset(3, 0, -3), new ItemStack(Items.LANTERN, 8));
            int inStores = Market.stock(level, id, s -> s.is(Items.LANTERN)), before = lanternsAbout(level, t.heart());
            int lit = Traditions.lanternsForTests(level, v);
            int left = Market.stock(level, id, s -> s.is(Items.LANTERN));
            int standing = lanternsAbout(level, t.heart()) - before;
            Kit.log("ci03 Founding Day: " + lit + " lanterns lit, " + left + " of " + inStores + " left in the stores, " + standing
                + " more standing on the square (" + before + " there before)");
            helper.assertTrue(lit >= 4 && left == inStores - lit && standing == lit, "lanterns round the square out of the stores: " + lit + ", " + left);
            level.setDayTime((founding + 1) * 24000L + 2000L);
            level.updateSkyBrightness();
            int still = Traditions.takeInForTests(level, v);
            helper.assertTrue(still == 0 && Market.stock(level, id, s -> s.is(Items.LANTERN)) == inStores
                && lanternsAbout(level, t.heart()) == before, "taken in the next morning, back into the stores: "
                + still + " out, " + Market.stock(level, id, s -> s.is(Items.LANTERN)) + " in");

            // The crier the day before the silence and on its day.
            long silence = f0 + 3 + TownCalendarYear.DAYS;
            List<String> eve = Traditions.crierLines(level, v, silence - 1), on = Traditions.crierLines(level, v, silence);
            Kit.log("ci03 the crier: the day before " + eve + "; on the day " + on);
            helper.assertTrue(eve.stream().anyMatch(l -> l.startsWith("Tomorrow is the Watch's Silence")), "cried the day before: " + eve);
            helper.assertTrue(on.stream().anyMatch(l -> l.startsWith("Today we keep the Watch's Silence")), "and on the day: " + on);

            // The silence: just after the dusk bell everybody stops where it is, a minute; then the bell, and the history.
            level.setDayTime(silence * 24000L + 12150L);
            level.updateSkyBrightness();
            int held = 0;
            for (VillageFolkEntity f : t.folk()) {
                if (Culture.hold(f, level) && "SILENCE".equals(Culture.roleForTests(f))) held++;
                Kit.log("   " + f.displayNameCap() + ": " + Culture.roleForTests(f) + " / " + f.hobbyNow());
            }
            helper.assertTrue(held >= 3 && Traditions.silentForTests(id) == held, "the town keeps the silence: " + held + " of " + t.folk().size());
            level.setDayTime(silence * 24000L + 13350L);
            level.updateSkyBrightness();
            Traditions.tickForTests(level, v);
            helper.assertTrue(told(id, "the town kept the Watch's Silence"), "into the history once the minute is over");

            // Diamond Night: the eldest's toast, a drink each out of the stores, the bottles back.
            long toast = f0 + 6 + TownCalendarYear.DAYS;
            level.setDayTime(toast * 24000L + 13500L);
            level.updateSkyBrightness();
            Container drinks = chestAt(level, t.heart().offset(-3, 0, -3));
            for (int i = 0; i < 3; i++) put(drinks, Cafe.drink(Cafe.DRINKS.get(0)));
            int bottlesBefore = Market.stock(level, id, s -> s.is(Items.GLASS_BOTTLE));
            boolean given = Traditions.toastNowForTests(level, v);
            int[] tt = Traditions.toastForTests(id);
            int bottles = Market.stock(level, id, s -> s.is(Items.GLASS_BOTTLE)) - bottlesBefore;
            Kit.log("ci03 the toast: given " + given + ", " + tt[1] + " there, " + tt[2] + " drinks poured, " + bottles + " bottles back");
            helper.assertTrue(given && tt[1] >= t.folk().size(), "the toast given with the town there: " + tt[1]);
            helper.assertTrue(tt[2] == 3 && bottles == 3, "a drink each while the stores had them, the bottles back: " + tt[2] + ", " + bottles);
            helper.assertTrue(told(id, "the town kept Diamond Night with a toast"), "into the history");
            helper.succeed();
        });
    }

    /** The lanterns standing on the ground about the heart of the town. */
    private static int lanternsAbout(ServerLevel level, BlockPos heart) {
        int n = 0;
        for (BlockPos p : BlockPos.betweenClosed(heart.offset(-6, -2, -6), heart.offset(6, 2, 6))) {
            if (level.getBlockState(p).is(Blocks.LANTERN)) n++;
        }
        return n;
    }

    /** The town's year, as the calendar counts it. */
    private static final class TownCalendarYear {
        static final int DAYS = com.jrpetty.mcassistant.entity.TownCalendar.YEAR_DAYS;
    }

    // ============================================================ ci04: the theatre

    /** The theatre wanted, stamped, a play cast and played to the town on its benches, the next another story. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ci04_theatre")
    public static void ci04_theatre(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 666000, 6, 30, 13500L);
        UUID id = t.id();
        BlockPos at = t.heart().offset(0, 0, -16);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            Villages.ageForTests(id, Villages.Age.IRON);
            Persona.Hobby[] h = { Persona.Hobby.READING, Persona.Hobby.MUSIC, Persona.Hobby.WHITTLING, Persona.Hobby.FISHING,
                Persona.Hobby.CARDS, Persona.Hobby.WALKING };
            List<String> players = new ArrayList<>();
            for (int i = 0; i < t.folk().size(); i++) {
                Culture.hobbyForTests(t.folk().get(i), h[i]);
                if (i < 3) players.add(t.folk().get(i).displayNameCap());
            }
            helper.assertTrue(Theatre.wanted(id, 20) && !Theatre.wanted(id, 19), "an Iron Age town of twenty with players wants a theatre");
            String why = Villages.whyBuild(id, Theatre.STRUCTURE);
            Kit.log("ci04 why: " + why);
            helper.assertTrue(why.contains("theatre") && why.contains("3 players"), "and says why: " + why);
            BuildGoal.stamp(level, Theatre.STRUCTURE, at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, Theatre.STRUCTURE, at, Direction.NORTH);
            helper.assertTrue(level.getBlockState(at.offset(-1, 0, 1)).getBlock() instanceof StairBlock
                && level.getBlockState(at.offset(1, 0, 3)).getBlock() instanceof StairBlock, "benches before the stage");
            helper.assertTrue(level.getBlockState(at.offset(0, 0, -3)).isSolid() && level.getBlockState(at.offset(0, 1, -3)).isAir(),
                "a raised stage to stand on");

            long day = level.getDayTime() / 24000L;
            Villages.tell(id, day - 2, "Ada and Bert were wed");
            String first = Theatre.beginForTests(level, v);
            List<String> cast = Theatre.castForTests(id), script = Theatre.scriptForTests(id);
            Kit.log("ci04 tonight: “" + first + "” with " + cast + ": " + script);
            helper.assertTrue(first != null && cast.size() >= 2 && cast.size() <= 3, "a play cast: " + first + " " + cast);
            for (String c : cast) helper.assertTrue(players.contains(c), "a reader, a musician or a whittler: " + c + " of " + players);
            helper.assertTrue(!script.isEmpty(), "and written");

            Theatre.stepForTests(level, v, true);                          // the players at their marks: the play begins
            int audience = 0;
            for (VillageFolkEntity f : t.folk()) {
                boolean held = Culture.hold(f, level);
                String role = Culture.roleForTests(f);
                Kit.log("   " + f.displayNameCap() + ": " + role + " / " + f.hobbyNow());
                if (cast.contains(f.displayNameCap())) helper.assertTrue(held && "ACTOR".equals(role), "a player on the stage: " + f.displayNameCap());
                else if (held && "AUDIENCE".equals(role)) audience++;
            }
            helper.assertTrue(audience >= 2, "the rest of the town comes to watch: " + audience);
            Theatre.stepForTests(level, v, true);                          // every line, and the bows
            List<String> said = Theatre.saidForTests(id);
            Kit.log("ci04 said: " + said + "; " + Theatre.phaseForTests(id));
            helper.assertTrue(said.size() == script.size(), "every line said: " + said.size() + " of " + script.size());
            for (int i = 0; i < said.size(); i++) helper.assertTrue(said.get(i).endsWith(script.get(i)), "in turn: " + said.get(i));
            helper.assertTrue(Theatre.phaseForTests(id).startsWith("BOWS"), "the bows: " + Theatre.phaseForTests(id));
            helper.assertTrue(told(id, "the players put on “" + first + "” at the theatre"), "into the town's history");
            Theatre.stepForTests(level, v, true);                          // the house goes home
            String second = Theatre.beginForTests(level, v);
            Kit.log("ci04 the next play: “" + second + "”");
            helper.assertTrue(second != null && !second.equals(first), "the next play is another story: " + second);
            List<String> wedding = Theatre.writeForTests(id, day - 2, "Ada and Bert were wed");
            helper.assertTrue(wedding.get(0).equals("The Wedding of Ada and Bert")
                && wedding.stream().anyMatch(l -> l.contains("Bert...")), "a wedding played by its couple: " + wedding);
            helper.succeed();
        });
    }

    // ============================================================ ci05: the band

    /** The band at the tavern on the day of rest only with the stores' note blocks; it plays; the note blocks go back. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ci05_band")
    public static void ci05_band(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 668000, 5, 30, 2000L);
        UUID id = t.id();
        BlockPos tavernAt = t.heart().offset(16, 0, 0);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            Villages.ageForTests(id, Villages.Age.STONE);
            long rest = restDay(id, level.getDayTime() / 24000L + 7);
            helper.assertTrue(rest > 0, "a day of rest to come");
            level.setDayTime(rest * 24000L + 13000L);
            for (int i = 0; i < t.folk().size(); i++) {
                Culture.hobbyForTests(t.folk().get(i), i >= 1 && i <= 3 ? Persona.Hobby.MUSIC : Persona.Hobby.READING);
                t.folk().get(i).removeMatching(s -> s.is(Items.NOTE_BLOCK), 64);
            }
            BuildGoal.stamp(level, "tavern", tavernAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "tavern", tavernAt, Direction.NORTH);
            List<String> none = Music.startForTests(level, v);
            Kit.log("ci05 with no note blocks: " + none + " (" + Music.shortForTests(id) + ")");
            helper.assertTrue(none.isEmpty() && String.valueOf(Music.shortForTests(id)).contains("note blocks"), "no note blocks, no band");

            chestAt(level, t.heart().offset(3, 0, -3), new ItemStack(Items.NOTE_BLOCK, 2));
            List<String> band = Music.startForTests(level, v);
            Kit.log("ci05 the band: " + band + " — " + Music.bandForTests(id));
            helper.assertTrue(band.size() == 2, "two of the three musicians, one note block each: " + band);
            helper.assertTrue(Market.stock(level, id, s -> s.is(Items.NOTE_BLOCK)) == 0, "the stores' note blocks lent out");
            helper.assertTrue(Music.playing(id), "the band has the tavern (its own tune waits)");
            List<VillageFolkEntity> players = new ArrayList<>();
            for (VillageFolkEntity f : t.folk()) if (band.contains(f.displayNameCap())) players.add(f);
            for (VillageFolkEntity f : players) {
                helper.assertTrue(f.countCarried(s -> s.is(Items.NOTE_BLOCK)) == 1, "a note block in its pack: " + f.displayNameCap());
            }
            for (int i = 0; i < players.size(); i++) {
                VillageFolkEntity f = null;
                for (VillageFolkEntity g : players) if (band.indexOf(g.displayNameCap()) == i) f = g;
                BlockPos p = Music.placeForTests(id, i);
                f.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.0F, 0.0F);
                helper.assertTrue(Culture.hold(f, level) && "BAND".equals(Culture.roleForTests(f)), "in the band: " + f.displayNameCap()
                    + " " + Culture.roleForTests(f));
            }
            for (int k = 0; k < 16; k++) Music.beatForTests(level, v);
            String played = Music.bandForTests(id);
            Kit.log("ci05 after sixteen beats: " + played);
            int beats = Integer.parseInt(played.replaceAll(".*played=(\\d+).*", "$1"));
            helper.assertTrue(played.startsWith("TAVERN") && beats >= 16, "the band plays together at the tavern: " + played);
            Music.endForTests(level, v);
            helper.assertTrue(Market.stock(level, id, s -> s.is(Items.NOTE_BLOCK)) == 2, "the note blocks back in the stores: "
                + Market.stock(level, id, s -> s.is(Items.NOTE_BLOCK)));
            for (VillageFolkEntity f : players) helper.assertTrue(f.countCarried(s -> s.is(Items.NOTE_BLOCK)) == 0, "none kept: " + f.displayNameCap());

            // A feast: the band plays there.
            boolean feast = Assemblies.startNow(level, v, Assemblies.Kind.FEAST);
            List<String> atFeast = Music.startForTests(level, v);
            Kit.log("ci05 a feast (" + feast + "): " + atFeast + " — " + Music.bandForTests(id));
            if (feast) helper.assertTrue(String.valueOf(Music.bandForTests(id)).startsWith("GATHERING"), "the band at the feast: " + Music.bandForTests(id));
            Music.endForTests(level, v);
            helper.succeed();
        });
    }

    // ============================================================ ci06: the choir

    /** The choir before the chapel's altar at the morning service of the day of rest, singing the town's hymn. */
    @GameTest(template = EMPTY, timeoutTicks = 700, batch = "ci06_choir")
    public static void ci06_choir(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 670000, 6, 34, 2000L);
        UUID id = t.id();
        BlockPos chapelAt = t.heart().offset(0, 0, -20);
        List<String> voices = new ArrayList<>();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            Villages.ageForTests(id, Villages.Age.STONE);
            long rest = restDay(id, level.getDayTime() / 24000L + 7);
            helper.assertTrue(rest > 0, "a day of rest to come");
            level.setDayTime(rest * 24000L + 1500L);
            Culture.hobbyForTests(t.folk().get(1), Persona.Hobby.MUSIC);
            Culture.hobbyForTests(t.folk().get(2), Persona.Hobby.MUSIC);
            Culture.hobbyForTests(t.folk().get(3), Persona.Hobby.CARDS);
            t.folk().get(3).life().setTraitsForTests(Social.Trait.SOCIABLE, Social.Trait.CHEERFUL);
            for (int i : new int[]{ 0, 4, 5 }) {
                Culture.hobbyForTests(t.folk().get(i), Persona.Hobby.FISHING);
                t.folk().get(i).life().setTraitsForTests(Social.Trait.SHY, Social.Trait.HARDWORKING);
            }
            BuildGoal.stamp(level, "chapel", chapelAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "chapel", chapelAt, Direction.NORTH);
            voices.addAll(Music.choirForTests(level, v));
            Kit.log("ci06 the choir: " + voices);
            List<String> fit = List.of(t.folk().get(1).displayNameCap(), t.folk().get(2).displayNameCap(), t.folk().get(3).displayNameCap());
            helper.assertTrue(voices.size() >= 2 && fit.containsAll(voices),
                "the musicians and the sociable cheerful one, nobody else: " + voices + " of " + fit);
            for (int i = 0; i < voices.size(); i++) {
                BlockPos p = Music.choirPlaceForTests(id, i);
                for (VillageFolkEntity f : t.folk()) {
                    if (f.displayNameCap().equals(voices.get(i))) f.moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.0F, 0.0F);
                }
                helper.assertTrue(p.distManhattan(chapelAt.offset(0, 0, -4)) <= 3, "before the altar: " + p.toShortString());
            }
        });
        helper.runAtTickTime(450, () -> {
            List<String> sung = Music.sungForTests(id);
            Kit.log("ci06 sung: " + sung + "; time " + level.getDayTime() % 24000L);
            for (VillageFolkEntity f : t.folk()) Kit.log("   " + f.displayNameCap() + ": " + Culture.roleForTests(f) + " / " + f.hobbyNow()
                + " at " + f.blockPosition().toShortString());
            helper.assertTrue(!sung.isEmpty(), "the choir sings: " + sung);
            helper.assertTrue(sung.get(0).contains(Villages.name(id)), "the town's own hymn: " + sung.get(0));
            int singing = 0;
            for (VillageFolkEntity f : t.folk()) if ("CHOIR".equals(Culture.roleForTests(f))) singing++;
            helper.assertTrue(singing >= 2, "its voices at their places: " + singing);
            helper.succeed();
        });
    }

    // ============================================================ ci07: paintings

    /** A whittler paints of the stores' sticks and wool, signed, bought for the shop; pictures hung in the tavern. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ci07_paintings")
    public static void ci07_paintings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 672000, 4, 30, 13000L);
        UUID id = t.id();
        BlockPos tavernAt = t.heart().offset(16, 0, 0);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            VillageFolkEntity painter = t.folk().get(1), other = t.folk().get(2);
            for (VillageFolkEntity f : List.of(painter, other)) {
                Culture.hobbyForTests(f, Persona.Hobby.WHITTLING);
                f.removeMatching(s -> s.is(Items.STICK) || s.is(ItemTags.WOOL), 999);
            }
            Ledger.addCoins(id, 50);
            Container box = chestAt(level, t.heart().offset(3, 0, -3), new ItemStack(Items.STICK, 8), new ItemStack(Items.RED_WOOL, 1));
            int purse = painter.purse(), treasury = Ledger.coins(id);
            boolean done = Paintings.paintForTests(painter, level, v);
            ItemStack picture = ItemStack.EMPTY;
            for (BlockPos p : Villages.storeChests(level, id)) {
                if (!(level.getBlockEntity(p) instanceof Container c)) continue;
                for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(Items.PAINTING)) picture = c.getItem(i);
            }
            String[] about = Paintings.aboutForTests(picture);
            int paid = treasury - Ledger.coins(id);
            Kit.log("ci07 painted: " + done + "; " + (about == null ? "nothing" : about[0] + " by " + about[1]) + "; paid " + paid);
            helper.assertTrue(done && about != null && about[1].equals(painter.displayNameCap()), "a picture signed by its painter");
            helper.assertTrue(Market.stock(level, id, s -> s.is(Items.STICK)) == 0 && Market.stock(level, id, s -> s.is(ItemTags.WOOL)) == 0,
                "made of the stores' eight sticks and a wool");
            helper.assertTrue(paid > 0 && painter.purse() - purse == paid, "bought off it out of the treasury: " + paid);
            helper.assertTrue(told(id, "painted the town's first picture"), "into the town's history");
            helper.assertTrue(!Paintings.paintForTests(other, level, v) && Market.stock(level, id, s -> s.is(Items.PAINTING)) == 1,
                "no makings, no picture");

            BuildGoal.stamp(level, "tavern", tavernAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "tavern", tavernAt, Direction.NORTH);
            int hung = Paintings.hangForTests(level, v, "tavern");
            int entities = level.getEntitiesOfClass(Painting.class, new AABB(tavernAt).inflate(7)).size();
            Kit.log("ci07 in the tavern: " + hung + " (" + entities + " paintings about it)");
            helper.assertTrue(hung == 1 && entities == 1 && Market.stock(level, id, s -> s.is(Items.PAINTING)) == 0,
                "the picture hung in the tavern, out of the stores");
            // Two more pictures into the stores (the same chest the makings came out of, a store for certain): the tavern
            // takes one, up to its two, and the other stays in the stores; and it takes no more after that.
            put(box, new ItemStack(Items.PAINTING, 2));
            int stocked = Market.stock(level, id, s -> s.is(Items.PAINTING)), places = Paintings.placesForTests(level, v, "tavern");
            int now = Paintings.hangForTests(level, v, "tavern");
            int left = Market.stock(level, id, s -> s.is(Items.PAINTING));
            Kit.log("ci07 with " + stocked + " more in the stores and " + places + " places on the tavern's walls: " + now + " hang there, "
                + left + " left in the stores");
            helper.assertTrue(now == 2 && left == stocked - 1, "the tavern hung one more, up to its two: " + now + " hang, "
                + left + " of " + stocked + " left in the stores, " + places + " places free on its walls");
            int again = Paintings.hangForTests(level, v, "tavern");
            helper.assertTrue(again == 2 && Market.stock(level, id, s -> s.is(Items.PAINTING)) == left, "and no more than two: " + again);
            helper.succeed();
        });
    }

    // ============================================================ ci08: plaques

    /** Plaques at the founding spot, on the first house, where a hero fell and at the record harvest; none on a street. */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ci08_plaques")
    public static void ci08_plaques(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 674000, 4, 30, 3000L);
        UUID id = t.id();
        BlockPos houseAt = t.heart().offset(-14, 0, 14);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            // Ten days on, so the town (founded five days back) was founded after the world's day 0.
            level.setDayTime((level.getDayTime() / 24000L + 10) * 24000L + 3000L);
            level.updateSkyBrightness();
            long day = level.getDayTime() / 24000L;
            FoundingDay.foundedForTests(id, day - 5);
            BuildGoal.stamp(level, "house", houseAt, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "house", houseAt, Direction.NORTH);
            chestAt(level, t.heart().offset(3, 0, -3), new ItemStack(Items.OAK_SIGN, 8), new ItemStack(Items.OAK_FENCE, 6));
            VillageFolkEntity hero = t.folk().get(3);
            hero.moveTo(t.heart().getX() - 9.5, t.heart().getY(), t.heart().getZ() + 11.5, 0.0F, 0.0F);
            Plaques.fell(hero, "when the raiders came", day);
            Plaques.harvestForTests(level, v, 46, day - 1, "Bert", t.heart().offset(12, 0, -12));
            List<Plaques.Plaque> ps = Plaques.putForTests(level, v);
            for (Plaques.Plaque p : ps) Kit.log("ci08 " + p.site() + " at " + p.at().toShortString() + " up=" + p.up() + ": " + String.join(" / ", p.lines()));
            Kit.log("ci08 waiting on: " + Plaques.shortForTests(id));
            for (Plaques.Site s : Plaques.Site.values()) {
                if (s == Plaques.Site.MEMORIAL) continue;      // [war-peace] a war's memorial comes only with a peace (WarAndPeaceGameTests wp10)
                helper.assertTrue(ps.stream().anyMatch(p -> p.site() == s && p.up()), "a plaque up for " + s + ": " + ps.size() + " plaques");
            }
            for (Plaques.Plaque p : ps) {
                if (p.wall()) {
                    helper.assertTrue(level.getBlockState(p.at()).getBlock() instanceof WallSignBlock, "a sign on the house's wall: " + p.at());
                    continue;
                }
                helper.assertTrue(level.getBlockState(p.at()).getBlock() instanceof FenceBlock
                    && level.getBlockState(p.at().above()).getBlock() instanceof StandingSignBlock, "a post and a sign: " + p.at());
                int dx = p.at().getX() - t.heart().getX(), dz = p.at().getZ() - t.heart().getZ();
                helper.assertTrue(!TownPlan.isStreet(dx, dz) && !level.getBlockState(p.at().below()).is(Blocks.DIRT_PATH),
                    "never on a street: " + p.site() + " at " + dx + ", " + dz);
                SignBlockEntity sign = (SignBlockEntity) level.getBlockEntity(p.at().above());
                String words = "";
                for (int i = 0; i < 4; i++) words += sign.getFrontText().getMessage(i, false).getString() + " ";
                switch (p.site()) {
                    case FOUNDING -> helper.assertTrue(words.contains(Villages.name(id)) && words.contains("was founded"), "the founding: " + words);
                    case FELL -> helper.assertTrue(words.contains(hero.displayNameCap()) && p.at().distManhattan(hero.blockPosition()) <= 12,
                        "where the hero fell: " + words + " at " + p.at());
                    case HARVEST -> helper.assertTrue(words.contains("Record harvest") && words.contains("46"), "the record harvest: " + words);
                    default -> { }
                }
            }
            helper.assertTrue(told(id, "a plaque was put up at the town's first house"), "into the town's history");
            helper.succeed();
        });
    }
}
