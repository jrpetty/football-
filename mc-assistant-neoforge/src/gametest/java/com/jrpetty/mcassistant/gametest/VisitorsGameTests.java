package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Advancements;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Bard;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.FriendVisits;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.Inn;
import com.jrpetty.mcassistant.entity.KeptGifts;
import com.jrpetty.mcassistant.entity.MapRoom;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Merchants;
import com.jrpetty.mcassistant.entity.Tourists;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Visitors;
import com.jrpetty.mcassistant.entity.WatchDogs;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Visitors and the player (entity/Visitors and its fellows): the travelling bard, tourists, the merchant from
 * afar, friends from other towns, gifts kept on show, the map room, the watch's dogs and the advancements.
 * Each calls the town's own logic straight (the ...ForTests hooks) on a village stood up for it, and measures
 * what came of it: who is on the roll, what moved between the stores, the treasury, the purses and the
 * players, and what was set out in the world.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class VisitorsGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 50000;

    // ------------------------------------------------------------------ helpers

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
        return box;
    }

    private static void put(Container box, ItemStack s) {
        for (int i = 0; i < box.getContainerSize(); i++) {
            if (box.getItem(i).isEmpty()) {
                box.setItem(i, s);
                box.setChanged();
                return;
            }
        }
    }

    private static int stock(ServerLevel level, UUID village, Item item) {
        return Market.stock(level, village, s -> s.is(item));
    }

    /** The bones a town has: in its stores and in its guards' packs. */
    private static int bones(ServerLevel level, UUID village, VillageFolkEntity... guards) {
        int n = stock(level, village, Items.BONE);
        for (VillageFolkEntity g : guards) n += g.countCarried(s -> s.is(Items.BONE));
        return n;
    }

    private static boolean chronicled(UUID village, String words) {
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().contains(words)) return true;
        return false;
    }

    private static boolean remembers(VillageFolkEntity f, String words) {
        return f.persona().memories().stream().anyMatch(m -> m.text().contains(words));
    }

    private static VillageFolkEntity raise(GameTestHelper helper, ServerLevel level, BlockPos at) {
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
        helper.assertTrue(f != null, "a folk stood up at " + at);
        f.ensurePersona();
        return f;
    }

    // ============================================================ vp01: the travelling bard

    /**
     * A town with a tavern, and a town over the hill with a line in its chronicle. A bard comes in from the
     * edge: no resident (no village of its own, not on the roll, not in the headcount). At the tavern it is
     * written up; of an evening it plays, and the two folk in the room hear it, remember it and are the
     * happier; its news is the other town's real line. At night it lays its bedroll in the tavern and sleeps;
     * on its last morning it rolls it up and goes, and the books count it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "vp01_bard")
    public static void vp01_bard(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Visitors.resetForTests();
        final int x = 720000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        Kit.hold(level, x + 320, Z, 16);
        Kit.prepare(level, x + 320, Z, 16);
        BlockPos heart = flat(level, x, Z, 40);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 3000L);
        VillageFolkEntity first = raise(helper, level, heart);
        UUID id = first.ownerId();
        VillageFolkEntity a = raise(helper, level, heart.offset(-4, 0, -5));
        VillageFolkEntity b = raise(helper, level, heart.offset(4, 0, -5));
        BlockPos tav = heart.offset(0, 0, 18);
        BuildGoal.stamp(level, "tavern", tav, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, "tavern", tav, Direction.NORTH);
        Villages.Village other = Villages.found(level, Kit.surface(level, x + 320, Z));
        Villages.tell(other.id(), day - 1, "the smithy went up");
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            int before = Villages.headcount(id);
            VillageFolkEntity bard = Bard.arriveNowForTests(level, v);
            helper.assertTrue(bard != null, "a bard comes in from the edge");
            Kit.log("vp01 the bard " + bard.displayNameCap() + " at " + bard.blockPosition().toShortString() + ", " + Math.round(Math.sqrt(
                bard.blockPosition().distSqr(heart))) + " from the heart; stage " + Visitors.stageForTests(bard));
            helper.assertTrue(Visitors.is(bard) && bard.ownerId() == null, "a visitor, of no village");
            helper.assertTrue(Villages.headcount(id) == before && !Villages.folkOf(id).contains(bard),
                "not on the roll: headcount " + before + " -> " + Villages.headcount(id));
            helper.assertTrue("in".equals(Visitors.stageForTests(bard)), "walking in");
            Visitors.arriveForTests(level, bard);
            helper.assertTrue("stay".equals(Visitors.stageForTests(bard)), "at the tavern: " + Visitors.stageForTests(bard));
            helper.assertTrue(chronicled(id, "travelling bard, came to"), "the chronicle has it come");
            String news = Bard.newsForTests(level, v);
            Kit.log("vp01 the bard's news: " + news);
            helper.assertTrue(news.contains(Villages.name(other.id())) && news.contains("smithy"), "real news of the other town: " + news);
            // The evening: two of the town in the room.
            level.setDayTime(day * 24000L + 13000L);
            BlockPos stage = Bard.stageForTests(id);
            bard.moveTo(stage.getX() + 0.5, stage.getY(), stage.getZ() + 0.5, 0.0F, 0.0F);
            a.moveTo(stage.getX() + 2.5, stage.getY(), stage.getZ() - 1.5, 0.0F, 0.0F);
            b.moveTo(stage.getX() - 1.5, stage.getY(), stage.getZ() - 2.5, 0.0F, 0.0F);
            int heard = Bard.performForTests(level, bard);
            a.refreshMood();
            Kit.log("vp01 heard by " + heard + "; " + a.displayNameCap() + "'s mood " + a.persona().mood() + " because "
                + a.persona().moodWhy() + "; remembers " + remembers(a, "the bard"));
            helper.assertTrue(heard >= 2, "the room heard it: " + heard);
            helper.assertTrue(a.persona().moodWhy().contains("bard"), "the happier for it: " + a.persona().moodWhy());
            helper.assertTrue(remembers(a, "the bard play at the tavern"), "and remembers it");
            helper.assertTrue(Bard.playing(level, id), "the town knows a bard is playing (everybody comes to the tavern)");
            // The night: its bedroll in the tavern.
            level.setDayTime(day * 24000L + 18000L);
            bard.moveTo(tav.getX() + 0.5, tav.getY(), tav.getZ() + 0.5, 0.0F, 0.0F);
            int beds0 = bard.countCarried(s -> s.is(Items.RED_BED));
            Visitors.stayForTests(level, bard);
            BlockPos roll = Bard.bedrollForTests(bard);
            Kit.log("vp01 the bedroll at " + (roll == null ? "none" : roll.toShortString() + " " + level.getBlockState(roll)));
            helper.assertTrue(roll != null && level.getBlockState(roll).getBlock() instanceof BedBlock, "its bedroll laid in the tavern");
            helper.assertTrue(bard.countCarried(s -> s.is(Items.RED_BED)) == beds0 - 1, "out of its own pack");
            bard.moveTo(roll.getX() + 0.5, roll.getY(), roll.getZ() + 0.5, 0.0F, 0.0F);
            Visitors.stayForTests(level, bard);
            helper.assertTrue(bard.isSleeping(), "asleep on it");
            // Its last morning: up, rolled up, and away.
            level.setDayTime((day + 3) * 24000L + 1500L);
            boolean go = Visitors.stayForTests(level, bard);
            Kit.log("vp01 the last morning: going " + go + ", stage " + Visitors.stageForTests(bard) + ", at the bedroll "
                + level.getBlockState(roll));
            helper.assertTrue(go && "out".equals(Visitors.stageForTests(bard)), "it sets off on its last morning");
            helper.assertTrue(level.getBlockState(roll).isAir() && bard.countCarried(s -> s.is(Items.RED_BED)) == beds0,
                "the bedroll rolled up and back in its pack");
            helper.assertTrue(chronicled(id, "went on its way"), "the chronicle has it go");
            Visitors.goneForTests(level, bard);
            List<String> book = Visitors.book(level, id);
            Kit.log("vp01 the books: " + book);
            helper.assertTrue(bard.isRemoved(), "gone");
            helper.assertTrue(book.get(0).contains("Visitors this week: 1") && book.get(0).contains("a bard"), "the books count it: " + book);
            helper.succeed();
        });
    }

    // ============================================================ vp02: tourists

    /**
     * A town of renown (two great works and a museum): a tourist comes with a purse of its own, is not on the
     * roll, has the museum and the monument among its sights, spends at the café out of that purse into the
     * treasury, goes home at dusk (no inn), and the books have the week's visitors and what they spent.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "vp02_tourists")
    public static void vp02_tourists(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Visitors.resetForTests();
        final int x = 722000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = flat(level, x, Z, 40);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 3000L);
        VillageFolkEntity first = raise(helper, level, heart);
        UUID id = first.ownerId();
        VillageFolkEntity cook = raise(helper, level, heart.offset(-4, 0, -5));
        cook.setJob(StationTask.COOK);
        Ledger.built(id, "museum", heart.offset(-16, 0, 10), Direction.NORTH);
        Ledger.built(id, "monument", heart.offset(16, 0, 10), Direction.NORTH);
        Ledger.built(id, "cafe", heart.offset(0, 0, -16), Direction.SOUTH);
        Villages.noteProject(id, "monument", level.getGameTime());
        Villages.noteProject(id, "granary", level.getGameTime());
        // Drinks of the café's (what it sells whenever it has them), and food over a full larder.
        ItemStack cider = com.jrpetty.mcassistant.entity.Cafe.drink(com.jrpetty.mcassistant.entity.Cafe.DRINKS.get(0));
        chestAt(level, heart.offset(3, 0, -3), cider.copy(), cider.copy(), cider.copy(), cider.copy(), new ItemStack(Items.COOKIE, 64),
            new ItemStack(Items.BAKED_POTATO, 64), new ItemStack(Items.BREAD, 64));
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            int draw = Tourists.drawForTests(id);
            helper.assertTrue(draw >= 15, "a town of renown: draw " + draw);
            int before = Villages.headcount(id);
            VillageFolkEntity t = Tourists.arriveNowForTests(level, v);
            helper.assertTrue(t != null, "a tourist comes");
            int purse = t.purse();
            helper.assertTrue(purse >= 4 && purse <= 10 && t.ownerId() == null && Villages.headcount(id) == before,
                "with a small purse of its own (" + purse + "), not on the roll");
            List<String> sights = Tourists.sightsForTests(level, v);
            Kit.log("vp02 draw " + draw + ", the sights " + sights + ", its purse " + purse);
            helper.assertTrue(sights.contains("museum") && sights.contains("monument"), "the museum and the monument to see: " + sights);
            Visitors.arriveForTests(level, t);
            helper.assertTrue(chronicled(id, "a visitor from afar, came to see"), "the chronicle has it come");
            int treasury0 = Ledger.coins(id);
            int spent = Tourists.roundForTests(level, t);
            int treasury1 = Ledger.coins(id);
            Kit.log("vp02 spent " + spent + " (purse " + purse + " -> " + t.purse() + "), the treasury " + treasury0 + " -> " + treasury1);
            helper.assertTrue(spent > 0 && t.purse() == purse - spent, "it spent at the café out of its own purse: " + spent);
            helper.assertTrue(treasury1 > treasury0 && treasury1 - treasury0 <= spent, "into the treasury: " + treasury0 + " -> " + treasury1);
            level.setDayTime(day * 24000L + 11600L);
            boolean go = Visitors.stayForTests(level, t);
            helper.assertTrue(go && "out".equals(Visitors.stageForTests(t)), "no inn: home at dusk");
            int[] week = Visitors.week(id, day);
            List<String> book = Visitors.book(level, id);
            Kit.log("vp02 the week: " + week[0] + " visitors, " + week[1] + " spent; the books " + book);
            helper.assertTrue(week[0] == 1 && week[1] == spent, "the books: one visitor, " + spent + " spent: " + week[0] + "/" + week[1]);
            helper.assertTrue(book.get(0).contains("spent " + spent), "and say so: " + book.get(0));
            Visitors.goneForTests(level, t);
            helper.succeed();
        });
    }

    // ============================================================ vp03: the merchant from afar

    /**
     * A market town with a café and coin in its treasury: the merchant comes with lots its land and its stores
     * have not got; the town buys what it needs off the stall out of the treasury into its stores (the coin to
     * the merchant's purse); a player buys a lot with coin; at dusk the merchant goes.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "vp03_merchant")
    public static void vp03_merchant(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Visitors.resetForTests();
        final int x = 724000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = flat(level, x, Z, 40);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 3000L);
        VillageFolkEntity first = raise(helper, level, heart);
        UUID id = first.ownerId();
        raise(helper, level, heart.offset(-4, 0, -5));
        Ledger.built(id, "market", heart.offset(12, 0, 0), Direction.WEST);
        Ledger.built(id, "cafe", heart.offset(0, 0, -16), Direction.SOUTH);
        Ledger.addCoins(id, 300);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            VillageFolkEntity m = Merchants.arriveNowForTests(level, v);
            helper.assertTrue(m != null, "a merchant comes");
            List<Item> wares = Merchants.stockedForTests(m);
            List<Item> needed = new ArrayList<>();
            for (Item i : wares) if (Merchants.neededForTests(level, v, i)) needed.add(i);
            Kit.log("vp03 the merchant brings " + wares + "; the town needs " + needed);
            helper.assertTrue(!wares.isEmpty() && wares.size() <= 4 && m.ownerId() == null, "a few lots: " + wares);
            for (Item i : wares) helper.assertTrue(stock(level, id, i) == 0, "nothing the stores hold already: " + i);
            int coins0 = Ledger.coins(id), purse0 = m.purse();
            int[] inStores0 = new int[wares.size()];
            for (int k = 0; k < wares.size(); k++) inStores0[k] = stock(level, id, wares.get(k));
            Visitors.arriveForTests(level, m);
            int coins1 = Ledger.coins(id), purse1 = m.purse();
            int bought = 0;
            for (int k = 0; k < wares.size(); k++) if (stock(level, id, wares.get(k)) > inStores0[k]) bought++;
            Kit.log("vp03 the town bought " + bought + " lots: the treasury " + coins0 + " -> " + coins1 + ", the merchant's purse "
                + purse0 + " -> " + purse1);
            helper.assertTrue(coins0 - coins1 == purse1 - purse0, "the coin went from the treasury to the merchant's purse");
            helper.assertTrue(needed.isEmpty() ? bought == 0 : bought >= 1 && bought <= 2, "it bought what it needed (" + needed + "): " + bought);
            helper.assertTrue(chronicled(id, "a merchant from afar, set up at the market"), "the chronicle has its stall");
            // A player buys a lot.
            List<Item> left = Merchants.stockedForTests(m);
            if (!left.isEmpty()) {
                Player p = helper.makeMockPlayer(GameType.SURVIVAL);
                p.getInventory().setItem(30, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 20));
                int purse2 = m.purse();
                String[] said = Merchants.buyForTests(m, p);
                int got = 0;
                for (Item i : left) got += p.getInventory().countItem(i);
                Kit.log("vp03 a player asks: " + said[0] + " / hands over: " + said[1] + "; got " + got + ", coins left "
                    + Market.coinsHeld(p) + ", the merchant's purse " + purse2 + " -> " + m.purse());
                helper.assertTrue(got > 0, "the player has the lot: " + said[1]);
                helper.assertTrue(20 - Market.coinsHeld(p) == m.purse() - purse2 && m.purse() > purse2, "paid into the merchant's purse");
            }
            level.setDayTime(day * 24000L + 12100L);
            helper.assertTrue(Visitors.stayForTests(level, m), "it leaves at dusk");
            Visitors.goneForTests(level, m);
            helper.succeed();
        });
    }

    // ============================================================ vp04: advancements

    /**
     * The page of advancements is loaded, every one of it; granting one grants the root with it; the founder
     * written down at a founding gets "A Village of Your Own"; a town of theirs in the Iron Age gives the Stone
     * and Iron Ages and not the Diamond; becoming a citizen gives "One of Us"; the fair and the cup are there for
     * whoever holds them.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "vp04_advancements")
    public static void vp04_advancements(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Visitors.resetForTests();
        final int x = 726000;
        Kit.hold(level, x, Z, 32);
        Kit.prepare(level, x, Z, 32);
        BlockPos heart = flat(level, x, Z, 24);
        VillageFolkEntity first = raise(helper, level, heart);
        UUID id = first.ownerId();
        helper.runAtTickTime(10, () -> {
            ServerPlayer sp = helper.makeMockServerPlayerInLevel();
            {
                List<String> missing = new ArrayList<>();
                for (String key : Advancements.all()) {
                    if (level.getServer().getAdvancements().get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        McAssistantMod.MODID, key)) == null) missing.add(key);
                }
                Kit.log("vp04 advancements " + Advancements.all().size() + ", missing " + missing);
                helper.assertTrue(missing.isEmpty(), "every advancement is loaded: missing " + missing);
                helper.assertFalse(Advancements.has(sp, Advancements.FOUNDED), "not yet founded anything");
                Advancements.founded(level, sp.getUUID(), id);
                helper.assertTrue(sp.getUUID().equals(Advancements.founder(id)), "the founder written down");
                helper.assertTrue(Advancements.has(sp, Advancements.FOUNDED) && Advancements.has(sp, Advancements.ROOT),
                    "A Village of Your Own, and the page's root with it");
                Villages.ageForTests(id, Villages.Age.IRON);
                Advancements.tickForTests(level, Villages.get(id));
                boolean stone = Advancements.has(sp, Advancements.age(Villages.Age.STONE)),
                    iron = Advancements.has(sp, Advancements.age(Villages.Age.IRON)),
                    diamond = Advancements.has(sp, Advancements.age(Villages.Age.DIAMOND));
                Kit.log("vp04 a town in the Iron Age: stone " + stone + ", iron " + iron + ", diamond " + diamond);
                helper.assertTrue(stone && iron && !diamond, "the ages it has come to, and no further");
                helper.assertFalse(Advancements.has(sp, Advancements.CITIZEN), "not a citizen yet");
                Ledger.addCitizen(id, sp.getUUID(), sp.getName().getString());
                Advancements.tickForTests(level, Villages.get(id));
                helper.assertTrue(Advancements.has(sp, Advancements.CITIZEN), "One of Us");
                helper.assertFalse(Advancements.has(sp, Advancements.TOWN_25), "a town of one is not twenty-five");
                Advancements.wonTheFair(sp);
                Advancements.wonACup(sp);
                helper.assertTrue(Advancements.has(sp, Advancements.FAIR) && Advancements.has(sp, Advancements.CUP),
                    "the fair and the cup, for whoever holds them");
                helper.assertFalse(Advancements.grant(sp, Advancements.FAIR), "granted once only");
            }
            helper.succeed();
        });
    }

    // ============================================================ vp05: the map room

    /**
     * A meeting hall with nine paper, a leather and planks in the stores: the clerk draws the town's map on the
     * paper and hangs it in a frame made of the sticks and leather on the hall's wall, filled in from the ground.
     * A week on, a fresh map on fresh paper, and last week's goes back to the stores. Grown big, the town has
     * a two-by-two of maps in a square on one wall, and the single map and its frame go back to the stores.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "vp05_map_room")
    public static void vp05_map_room(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Visitors.resetForTests();
        final int x = 728000;
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = flat(level, x, Z, 44);
        long day = level.getDayTime() / 24000L + 9;
        level.setDayTime(day * 24000L + 3000L);
        VillageFolkEntity first = raise(helper, level, heart);
        UUID id = first.ownerId();
        raise(helper, level, heart.offset(-4, 0, -5));
        BlockPos hall = heart.offset(0, 0, 18);
        BuildGoal.stamp(level, "hall", hall, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, "hall", hall, Direction.NORTH);
        Container box = chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.PAPER, 9), new ItemStack(Items.LEATHER, 1),
            new ItemStack(Items.OAK_PLANKS, 8));
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            MapRoom.bigForTests(false);
            int paper0 = stock(level, id, Items.PAPER), leather0 = stock(level, id, Items.LEATHER);
            String did = MapRoom.makeNowForTests(level, v);
            List<ItemStack> maps = MapRoom.mapsForTests(level, id);
            Kit.log("vp05 the first map: " + did + "; frames " + MapRoom.framesForTests(id) + "; paper " + paper0 + " -> "
                + stock(level, id, Items.PAPER) + ", leather " + leather0 + " -> " + stock(level, id, Items.LEATHER));
            helper.assertTrue(maps.size() == 1 && maps.get(0).is(Items.FILLED_MAP), "a map hangs in the hall: " + did);
            MapItemSavedData data = MapItem.getSavedData(maps.get(0), level);
            int painted = 0;
            if (data != null) for (byte c : data.colors) if (c != 0) painted++;
            helper.assertTrue(painted > 2000, "filled in from the ground: " + painted + " of 16384 painted");
            helper.assertTrue(paper0 - stock(level, id, Items.PAPER) == 9 && leather0 - stock(level, id, Items.LEATHER) == 1,
                "nine paper for the sheet and the leather for the frame, out of the stores");
            BlockPos at = MapRoom.framesForTests(id).get(0);
            helper.assertTrue(Math.abs(at.getX() - hall.getX()) <= 12 && Math.abs(at.getZ() - hall.getZ()) <= 12, "on the hall's wall: " + at);
            Object firstId = maps.get(0).get(DataComponents.MAP_ID);
            // A week on: a fresh map, and last week's back to the stores.
            MapRoom.drawnOnForTests(id, day - 7);
            put(box, new ItemStack(Items.PAPER, 9));
            String again = MapRoom.makeNowForTests(level, v);
            List<ItemStack> maps2 = MapRoom.mapsForTests(level, id);
            Kit.log("vp05 a week on: " + again + "; filled maps in the stores " + stock(level, id, Items.FILLED_MAP));
            helper.assertTrue(maps2.size() == 1 && !firstId.equals(maps2.get(0).get(DataComponents.MAP_ID)), "a fresh map: " + again);
            helper.assertTrue(stock(level, id, Items.FILLED_MAP) == 1, "last week's back in the stores");
            // Grown big: the two-by-two.
            MapRoom.bigForTests(true);
            put(box, new ItemStack(Items.PAPER, 36));
            put(box, new ItemStack(Items.ITEM_FRAME, 3));
            String big = MapRoom.makeNowForTests(level, v);
            List<BlockPos> frames = MapRoom.framesForTests(id);
            List<ItemStack> four = MapRoom.mapsForTests(level, id);
            Kit.log("vp05 the two-by-two: " + big + "; frames " + frames + "; frames in the stores " + stock(level, id, Items.ITEM_FRAME)
                + ", filled maps " + stock(level, id, Items.FILLED_MAP));
            helper.assertTrue(frames.size() == 4 && four.stream().allMatch(s -> s.is(Items.FILLED_MAP)), "four maps hang: " + big);
            helper.assertTrue(frames.get(0).getY() == frames.get(1).getY() && frames.get(2).getY() == frames.get(3).getY()
                && frames.get(0).getY() == frames.get(2).getY() + 1, "two rows, one above the other: " + frames);
            helper.assertTrue(frames.get(0).distManhattan(frames.get(1)) == 1 && frames.get(2).distManhattan(frames.get(3)) == 1,
                "side by side: " + frames);
            helper.assertTrue(stock(level, id, Items.FILLED_MAP) == 2 && stock(level, id, Items.ITEM_FRAME) == 0,
                "the single map and its frame back to the stores, the frame hung again");
            MapRoom.bigForTests(null);
            helper.succeed();
        });
    }

    // ============================================================ vp06: watch dogs

    /**
     * A watch of two and bones in the stores, a wild wolf by the heart: a guard tames it with a bone from the
     * stores (the rest go back), and it is the watch's dog, named and on the books. At night it sits at home;
     * by day a zombie by the town is growled at and gone for.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "vp06_watch_dogs")
    public static void vp06_watch_dogs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Visitors.resetForTests();
        final int x = 730000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = flat(level, x, Z, 40);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 3000L);
        VillageFolkEntity first = raise(helper, level, heart);
        UUID id = first.ownerId();
        VillageFolkEntity g1 = raise(helper, level, heart.offset(-4, 0, -5));
        VillageFolkEntity g2 = raise(helper, level, heart.offset(4, 0, -5));
        g1.setJob(StationTask.GUARD);
        g2.setJob(StationTask.GUARD);
        chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.BONE, 6));
        Wolf wolf = EntityType.WOLF.create(level);
        wolf.moveTo(heart.getX() + 10.5, heart.getY(), heart.getZ() + 6.5, 0.0F, 0.0F);
        level.addFreshEntity(wolf);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            // The bones the town has, in the stores or already in a guard's pack (the town's own round may have sent a
            // guard out with them in the ticks before this one).
            int bones0 = bones(level, id, g1, g2);
            String did = WatchDogs.tameForTests(level, v);
            List<String> dogs = WatchDogs.dogsForTests(id);
            Kit.log("vp06 " + did + "; the dogs " + dogs + "; the town's bones " + bones0 + " -> " + bones(level, id, g1, g2)
                + " (in the stores " + stock(level, id, Items.BONE) + ")");
            helper.assertTrue(wolf.isTame() && dogs.size() == 1, "the wolf tamed for the watch: " + did);
            UUID guard = wolf.getOwnerUUID();
            VillageFolkEntity g = guard.equals(g1.getUUID()) ? g1 : g2;
            helper.assertTrue(guard.equals(g1.getUUID()) || guard.equals(g2.getUUID()), "its guard is of the watch");
            helper.assertTrue(bones0 - bones(level, id, g1, g2) == 1 && stock(level, id, Items.BONE) == bones0 - 1,
                "one bone used, the rest back in the stores");
            helper.assertTrue(wolf.hasCustomName() && chronicled(id, "tamed a wolf"), "named, and in the chronicle");
            String card = FolkTalk.card(g);
            helper.assertTrue(card.contains("Its dog|"), "on its guard's card: " + card);
            helper.assertTrue(WatchDogs.tameForTests(level, v).contains("has its dogs"), "one dog for two guards at most");
            // Night: home and lying down.
            level.setDayTime(day * 24000L + 18000L);
            BlockPos home = WatchDogs.homeForTests(g);
            wolf.moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0.0F, 0.0F);
            WatchDogs.leadForTests(level, g);
            helper.assertTrue(wolf.isInSittingPose(), "at night it lies down at home");
            // Day: a zombie by the town.
            level.setDayTime((day + 1) * 24000L + 6000L);
            Zombie z = EntityType.ZOMBIE.create(level);
            z.moveTo(wolf.getX() + 6.0, wolf.getY(), wolf.getZ(), 0.0F, 0.0F);
            z.setPersistenceRequired();
            level.addFreshEntity(z);
            WatchDogs.leadForTests(level, g);
            Kit.log("vp06 by day with a zombie by: the dog's target " + wolf.getTarget() + ", sitting " + wolf.isInSittingPose());
            helper.assertTrue(wolf.getTarget() == z && !wolf.isInSittingPose(), "it goes for the zombie");
            z.discard();
            helper.succeed();
        });
    }

    // ============================================================ vp07: friends from other towns

    /**
     * Two towns two hundred and fifty blocks apart, a friend in each: the one sets out to see the other on
     * foot, along the way between the towns; it is away (its card says so); there, it is welcomed and they
     * eat together, both remember it, think the warmer of each other and are the happier; it walks home; both
     * chronicles and the books have it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "vp07_friends")
    public static void vp07_friends(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Visitors.resetForTests();
        final int x = 732000, x2 = 732250;
        Kit.hold(level, x, Z, 32);
        Kit.prepare(level, x, Z, 32);
        Kit.hold(level, x2, Z, 32);
        Kit.prepare(level, x2, Z, 32);
        BlockPos heart = flat(level, x, Z, 24), heart2 = flat(level, x2, Z, 24);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 2000L);
        VillageFolkEntity a = raise(helper, level, heart);
        VillageFolkEntity b = raise(helper, level, heart2);
        helper.assertTrue(!a.ownerId().equals(b.ownerId()), "two towns");
        a.life().feel(b.getUUID(), b.displayNameCap(), 50);
        b.life().feel(a.getUUID(), a.displayNameCap(), 50);
        a.insertGiven(new ItemStack(Items.BREAD, 2));
        b.insertGiven(new ItemStack(Items.BREAD, 2));
        helper.runAtTickTime(10, () -> {
            UUID home = a.ownerId(), there = b.ownerId();
            helper.assertTrue(FriendVisits.tieForTests(a) == b, "a friend in the other town");
            helper.assertTrue(FriendVisits.setOutForTests(level, a), "it sets out");
            helper.assertTrue(FriendVisits.awayForTests(a) && FriendVisits.wayForTests(a) > 5, "away, on the way between the towns: "
                + FriendVisits.wayForTests(a) + " steps");
            String doing = FolkTalk.nowDoing(a);
            helper.assertTrue(doing.contains("Walking over to"), "its card says where: " + doing);
            int aff0 = a.life().affinity(b.getUUID()), bff0 = b.life().affinity(a.getUUID());
            int bread0 = a.countCarried(s -> s.get(DataComponents.FOOD) != null) + b.countCarried(s -> s.get(DataComponents.FOOD) != null);
            helper.assertTrue(FriendVisits.welcomeForTests(level, a), "welcomed");
            int bread1 = a.countCarried(s -> s.get(DataComponents.FOOD) != null) + b.countCarried(s -> s.get(DataComponents.FOOD) != null);
            a.refreshMood();
            Kit.log("vp07 welcomed: affinity " + aff0 + " -> " + a.life().affinity(b.getUUID()) + " / " + bff0 + " -> "
                + b.life().affinity(a.getUUID()) + "; food carried " + bread0 + " -> " + bread1 + "; mood why " + a.persona().moodWhy());
            helper.assertTrue(bread0 - bread1 == 2, "they ate together, a bite each of their own");
            helper.assertTrue(a.life().affinity(b.getUUID()) > aff0 && b.life().affinity(a.getUUID()) > bff0, "the warmer for it");
            helper.assertTrue(remembers(a, "spent the day with") && remembers(b, "came over from"), "both remember it");
            helper.assertTrue(a.persona().moodWhy().contains("visit"), "and are the happier");
            FriendVisits.homeForTests(level, a);
            helper.assertFalse(FriendVisits.awayForTests(a), "home again");
            List<String> book = Visitors.book(level, home);
            Kit.log("vp07 the books: " + book);
            helper.assertTrue(chronicled(home, "walked over to") && chronicled(there, "came to see"), "both chronicles have it");
            helper.assertTrue(book.stream().anyMatch(l -> l.startsWith("Friends' visits this week")), "the books have it: " + book);
            helper.succeed();
        });
    }

    // ============================================================ vp08: gifts kept

    /**
     * A folk moved into a house, with coin of its own, and a frame in the stores: a player gives it a diamond,
     * which is its own and marked as the player's gift; it hangs it in a frame on the wall by its bed (the frame
     * bought out of its purse into the treasury); its card and its words mention it; a music disc later takes
     * the diamond's place, and the diamond goes back to its pack.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "vp08_gifts_kept")
    public static void vp08_gifts_kept(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Visitors.resetForTests();
        final int x = 734000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = flat(level, x, Z, 40);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 3000L);
        VillageFolkEntity f = raise(helper, level, heart);
        UUID id = f.ownerId();
        chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.ITEM_FRAME, 1));
        BlockPos house = heart.offset(-16, 0, 16);
        BuildGoal.stamp(level, "house", house, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, "house", house, Direction.NORTH);
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            Homes.tickForTests(level, v);
            helper.assertTrue(Homes.membersForTests(id, house).contains(f.getUUID()), "it has moved into the house");
            f.earn(10);
            Player p = helper.makeMockPlayer(GameType.SURVIVAL);
            String you = p.getName().getString();
            KeptGifts.receivedForTests(f, new ItemStack(Items.DIAMOND), you);
            helper.assertTrue(f.countCarried(s -> s.is(Items.DIAMOND) && Homes.isKeepsake(s)) == 1, "the diamond is its own");
            int purse0 = f.purse(), coins0 = Ledger.coins(id);
            String did = KeptGifts.showForTests(level, f);
            String shown = KeptGifts.shownForTests(level, f);
            Kit.log("vp08 " + did + "; on show: " + shown + "; purse " + purse0 + " -> " + f.purse() + ", the treasury " + coins0 + " -> "
                + Ledger.coins(id));
            helper.assertTrue(shown != null && shown.contains("diamond"), "the diamond on show: " + did);
            helper.assertTrue(f.countCarried(s -> s.is(Items.DIAMOND)) == 0, "out of its pack and on the wall");
            helper.assertTrue(purse0 - f.purse() > 0 && purse0 - f.purse() == Ledger.coins(id) - coins0
                && stock(level, id, Items.ITEM_FRAME) == 0, "the frame bought out of its purse into the treasury");
            List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, new AABB(house).inflate(8), fr -> fr.getItem().is(Items.DIAMOND));
            helper.assertTrue(frames.size() == 1, "an item frame with the diamond in the house: " + frames.size());
            String card = FolkTalk.card(f);
            helper.assertTrue(card.contains("Keeps|the diamond " + you + " gave it"), "on its card: " + card);
            String said = KeptGifts.mentionForTests(f, p, true);
            Kit.log("vp08 asked about its home: " + said);
            helper.assertTrue(said.contains("diamond you gave me hangs by my bed"), "it mentions it: " + said);
            // A finer gift takes its place.
            KeptGifts.receivedForTests(f, new ItemStack(Items.MUSIC_DISC_CAT), you);
            String again = KeptGifts.showForTests(level, f);
            String now = KeptGifts.shownForTests(level, f);
            Kit.log("vp08 a finer gift: " + again + "; on show: " + now);
            helper.assertTrue(now != null && now.contains("disc"), "the disc on show now: " + now);
            helper.assertTrue(f.countCarried(s -> s.is(Items.DIAMOND)) == 1, "the diamond back in its pack");
            helper.succeed();
        });
    }

    // ============================================================ vp09: a night at the inn

    /**
     * A town with an inn kept by its cook, and a tavern: a tourist comes with the price of a room in its purse as
     * well as its spending money, and at dusk takes a room at the inn (three coins out of its purse into the till)
     * instead of going home; the bard, of a night, takes a room there too rather than laying its bedroll.
     */
    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "vp09_inn")
    public static void vp09_inn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Visitors.resetForTests();
        final int x = 736000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = flat(level, x, Z, 40);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + 3000L);
        VillageFolkEntity cook = raise(helper, level, heart);
        UUID id = cook.ownerId();
        BlockPos inn = heart.offset(-20, 0, 20);
        BuildGoal.stamp(level, "inn", inn, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, "inn", inn, Direction.NORTH);
        Ledger.built(id, "tavern", heart.offset(0, 0, 18), Direction.NORTH);
        Ledger.built(id, "cafe", heart.offset(0, 0, -16), Direction.SOUTH);        // the cook's place of work
        chestAt(level, heart.offset(3, 0, -3), new ItemStack(Items.BREAD, 64), new ItemStack(Items.BAKED_POTATO, 64));
        helper.runAtTickTime(10, () -> {
            Villages.Village v = Villages.get(id);
            // The innkeeper: the café's cook, made one now (a lone founder made a cook at the start is moved, on its
            // first look at the town's trades, to whatever the town is short of; and an inn with nobody to keep it lets
            // no rooms).
            cook.setJob(StationTask.COOK);
            helper.assertTrue(Inn.signAndKeeperForTests(id)[1] == cook, "the cook keeps the inn");
            List<BlockPos> beds = Inn.bedsForTests(level, id);
            helper.assertTrue(beds.size() >= 2, "the inn's beds: " + beds.size());
            VillageFolkEntity t = Tourists.arriveNowForTests(level, v);
            helper.assertTrue(t != null, "a tourist comes");
            int purse = t.purse();
            helper.assertTrue(purse >= 4 + 3 && purse <= 10 + 3, "its spending money and a room's price: " + purse);
            Visitors.arriveForTests(level, t);
            level.setDayTime(day * 24000L + 12000L);
            int till = Ledger.coins(id);
            String why = Visitors.noRoomForTests(level, t);
            boolean goes = Visitors.stayForTests(level, t);
            Kit.log("vp09 the tourist at dusk: going home " + goes + ", lodged " + Inn.lodged(t) + ", purse " + purse + " -> " + t.purse()
                + ", the till " + till + " -> " + Ledger.coins(id) + "; a room to be had: " + (why.isEmpty() ? "yes" : why)
                + "; the keeper " + Inn.signAndKeeperForTests(id)[1]);
            helper.assertTrue(!goes && Inn.lodged(t), "it takes a room for the night instead of going home");
            helper.assertTrue(t.purse() == purse - 3 && Ledger.coins(id) == till + 3, "three coins out of its purse into the till");
            VillageFolkEntity bard = Bard.arriveNowForTests(level, v);
            helper.assertTrue(bard != null, "a bard comes");
            Visitors.arriveForTests(level, bard);
            level.setDayTime(day * 24000L + 18000L);
            int bardPurse = bard.purse(), till2 = Ledger.coins(id);
            Visitors.stayForTests(level, bard);
            Kit.log("vp09 the bard at night: lodged " + Inn.lodged(bard) + ", purse " + bardPurse + " -> " + bard.purse() + ", bedroll "
                + Bard.bedrollForTests(bard) + ", the till " + till2 + " -> " + Ledger.coins(id));
            helper.assertTrue(Inn.lodged(bard) && bard.purse() == bardPurse - 3 && Ledger.coins(id) == till2 + 3,
                "the bard pays for a room at the inn");
            helper.assertTrue(Bard.bedrollForTests(bard) == null, "and lays no bedroll");
            helper.succeed();
        });
    }
}
