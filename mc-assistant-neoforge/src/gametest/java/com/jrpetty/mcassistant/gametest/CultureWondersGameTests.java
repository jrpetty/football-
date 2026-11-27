package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Culture;
import com.jrpetty.mcassistant.entity.Leader;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Persona;
import com.jrpetty.mcassistant.entity.Playbill;
import com.jrpetty.mcassistant.entity.Playwright;
import com.jrpetty.mcassistant.entity.RestDay;
import com.jrpetty.mcassistant.entity.Theatre;
import com.jrpetty.mcassistant.entity.TownWonders;
import com.jrpetty.mcassistant.entity.Troupe;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [culture-wonders] The theatre and the town's own wonders.
 *
 * <ul>
 * <li><b>cw01</b>: the Battle of Ashby Field, lost, with its Captain and its dead out of the chronicle, is written as a
 *     tragedy in three acts (a fight, a death on the boards), a battle won as a history, a siege as a tragedy; played the
 *     evening after it, the company's wardrobe is made up out of the stores, the Captain's player wears its helmet,
 *     the house pays for its seats, a death is played, and at the bows the review, the takings and the real Captain's
 *     memory of seeing itself played; the costumes come off after.</li>
 * <li><b>cw02</b>: rain puts the open-air play off to the next evening (twice), the playhouse is wanted for it, and with
 *     the playhouse standing the play goes on in the rain under its roof.</li>
 * <li><b>cw03</b>: a player dies mid-play and another of the company stands in; the next play is given in its memory; with
 *     nobody left to play a part, the play is stopped and the town told.</li>
 * <li><b>cw04</b>: the playbill goes up out of the stores; a player buys a seat for a coin (the programme in hand, the coin
 *     in the treasury), asks for a play of their own deeds and gets "The Ballad of ..." next; the review is in the gazette.</li>
 * <li><b>cw05</b>: a town too small is told why it has no wonder; by decree it raises the Great Belfry on its own lot: the
 *     stages begin each morning out of the wonder fund, a day's courses and no more, the blocks out of the stores, the
 *     scaffold up as it rises and down at the end (its fences back), the plaque, the opening with its lines, and after it
 *     the belfry stands with its bell, in the register, with its renown.</li>
 * <li><b>cw06</b>: the Great Minster stops while the town is short of food and goes on after; a player gives coin and
 *     stone (named among the givers); it survives a save and load; short of stone it waits; a town taken in war has
 *     its last courses pulled down and half its fund spent on the peace.</li>
 * <li><b>cw07</b>: the Grand Amphitheatre (stamped) is the players' house in fine weather and not in the rain; the
 *     wonders' drawings stand up as drawn; the playwright's fire, flood and election plays.</li>
 * </ul>
 *
 * <p>Each on its own ground in the band x 2,260,000 to 2,279,999, z 70,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class CultureWondersGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 70000;

    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        int y = Kit.surface(level, cx, cz).getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 30; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
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

    private static boolean told(UUID village, String words) {
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().contains(words)) return true;
        return false;
    }

    private record Town(UUID id, BlockPos heart, List<VillageFolkEntity> folk) {}

    private static Town town(GameTestHelper helper, ServerLevel level, int x, int n, int r, long hour) {
        Kit.reset(level);
        rain(level, false);
        Kit.hold(level, x, Z, r + 8);
        Kit.prepare(level, x, Z, r + 8);
        BlockPos heart = flat(level, x, Z, r);
        long day = level.getDayTime() / 24000L + 2;
        level.setDayTime(day * 24000L + hour);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.offset(-6 + i * 2, 0, 7), 0.0F);
            helper.assertTrue(f != null, "a folk of the town");
            folk.add(f);
        }
        UUID id = folk.get(0).ownerId();
        for (VillageFolkEntity f : folk) helper.assertTrue(id != null && id.equals(f.ownerId()), "all of one town");
        return new Town(id, heart, folk);
    }

    /** Rain on or off now (the rain's level set too: it otherwise comes on over a few seconds). */
    private static void rain(ServerLevel level, boolean on) {
        level.setWeatherParameters(on ? 0 : 24000, on ? 6000 : 0, on, false);
        level.setRainLevel(on ? 1.0F : 0.0F);
        level.setThunderLevel(0.0F);
    }

    private static Villages.Village village(GameTestHelper helper, UUID id) {
        Villages.Village v = Villages.get(id);
        helper.assertTrue(v != null, "the village is on the books");
        return v;
    }

    /** The first so many folk made players (a reader, a musician, a whittler...), the rest fishers. */
    private static List<String> players(Town t, int n) {
        Persona.Hobby[] h = { Persona.Hobby.READING, Persona.Hobby.MUSIC, Persona.Hobby.WHITTLING, Persona.Hobby.READING };
        List<String> out = new ArrayList<>();
        for (int i = 0; i < t.folk().size(); i++) {
            Culture.hobbyForTests(t.folk().get(i), i < n ? h[i % h.length] : Persona.Hobby.FISHING);
            if (i < n) out.add(t.folk().get(i).displayNameCap());
        }
        return out;
    }

    private static VillageFolkEntity named(Town t, String name) {
        for (VillageFolkEntity f : t.folk()) if (f.displayNameCap().equals(name)) return f;
        return null;
    }

    private static long restDay(UUID id, long from) {
        for (long d = from; d < from + 30; d++) if (RestDay.today(id, d)) return d;
        return -1;
    }

    private static BlockPos theatre(ServerLevel level, UUID id, BlockPos at) {
        BuildGoal.stamp(level, Theatre.STRUCTURE, at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
        Ledger.built(id, Theatre.STRUCTURE, at, Direction.NORTH);
        return at;
    }

    // ============================================================ cw01: the battle on the stage

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "cw01_battle")
    public static void cw01_battle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 2260000, 6, 30, 13500L);
        UUID id = t.id();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            Villages.ageForTests(id, Villages.Age.IRON);
            List<String> cast = players(t, 3);
            VillageFolkEntity captain = t.folk().get(4);
            long day = level.getDayTime() / 24000L;
            String battle = "The Battle of Ashby Field: we were driven off the field against Brook (3 of ours down, 5 of theirs)";
            Villages.tell(id, day - 1, "the army mustered on the square: 6 strong, Captain " + captain.displayNameCap() + " in command");
            Villages.tell(id, day - 1, "Sergeant Tom fell at the Battle of Ashby Field");
            Villages.tell(id, day - 1, battle);

            List<String> lost = Playwright.writeForTests(id, day - 1, battle);
            Kit.log("cw01 the lost battle: " + lost);
            helper.assertTrue(lost.get(0).equals("The Battle of Ashby Field, a tragedy in three acts"), "a tragedy in three acts: " + lost.get(0));
            helper.assertTrue(lost.stream().anyMatch(s -> s.startsWith("FIGHT")), "the swords out");
            helper.assertTrue(lost.contains("FALL Sergeant Tom"), "the town's own dead fall on the stage, by name");
            helper.assertTrue(lost.contains("PART Captain " + captain.displayNameCap() + " SOLDIER real"), "the Captain the muster named, a soldier, a real folk");
            helper.assertTrue(lost.stream().anyMatch(s -> s.contains("a soldier of Brook") && s.startsWith("PART")), "the enemy is Brook's");
            helper.assertTrue(lost.stream().anyMatch(s -> s.contains("3 of ours down")), "the counts are the battle's own");
            List<String> won = Playwright.writeForTests(id, day - 5, "The Battle of Kell Ford: we held the field against Brook (0 of ours down, 4 of theirs)");
            helper.assertTrue(won.get(0).equals("The Battle of Kell Ford, a history in three acts") && won.stream().noneMatch(s -> s.startsWith("FALL"))
                && won.stream().anyMatch(s -> s.contains("Huzzah")), "a battle won with nobody lost is a history: " + won);
            List<String> siege = Playwright.writeForTests(id, day - 9, "Brook took the town, its gate taken, and the elder made peace on its terms");
            helper.assertTrue(siege.get(0).startsWith("The Siege of " + Villages.name(id) + ", a tragedy"), "a town taken is a siege: " + siege.get(0));

            String why = Theatre.occasionForTests(id, day);
            helper.assertTrue(why != null && why.contains("The Battle of Ashby Field"), "the evening after the battle is a night for its play: " + why);

            theatre(level, id, t.heart().offset(0, 0, -16));
            chestAt(level, t.heart().offset(3, 0, -3), new ItemStack(Items.LEATHER, 40), new ItemStack(Items.OAK_PLANKS, 12),
                new ItemStack(Items.STICK, 6), new ItemStack(Items.OXEYE_DAISY, 2), new ItemStack(Items.BOOK, 2), new ItemStack(Items.RED_DYE, 2));
            for (VillageFolkEntity f : t.folk()) f.earn(10);
            String title = Theatre.beginForTests(level, v);
            List<String> on = Theatre.castForTests(id);
            Kit.log("cw01 tonight: " + title + " with " + on + "; the wardrobe " + Troupe.wardrobeForTests(id));
            helper.assertTrue("The Battle of Ashby Field".equals(title), "the battle is tonight's play: " + title);
            helper.assertTrue(on.size() == 3 && cast.containsAll(on), "the company plays it: " + on);
            helper.assertTrue(Troupe.wardrobeForTests(id).contains("SWORD=") && Troupe.wardrobeForTests(id).contains("HELMET="),
                "swords and helmets made for it out of the stores: " + Troupe.wardrobeForTests(id));
            helper.assertTrue(Market.stock(level, id, s -> s.is(Items.LEATHER)) < 40, "the leather came out of the stores");

            Theatre.stepForTests(level, v, true);                           // to their marks, and dressed
            VillageFolkEntity cap = named(t, on.get(1));
            helper.assertTrue(cap != null && Troupe.costumeForTests(cap.getItemBySlot(EquipmentSlot.HEAD)),
                "the Captain's player in its helmet: " + (cap == null ? "?" : cap.getItemBySlot(EquipmentSlot.HEAD)));
            int audience = 0;
            for (VillageFolkEntity f : t.folk()) {
                boolean held = Culture.hold(f, level);
                if (held && "AUDIENCE".equals(Culture.roleForTests(f))) audience++;
            }
            String show = Theatre.showForTests(id);
            Kit.log("cw01 the house: " + audience + "; " + show);
            helper.assertTrue(audience >= 2, "the town comes to watch: " + audience);
            helper.assertTrue(Integer.parseInt(show.split("\\|")[4]) >= 1 && Integer.parseInt(show.split("\\|")[3]) >= 1, "seats paid for: " + show);
            boolean died = false;
            for (int i = 0; i < 40 && !died; i++) {
                Theatre.beatsForTests(level, v, 1);
                if (!Theatre.showForTests(id).contains("|[]|")) died = true;
            }
            helper.assertTrue(died, "a death played on the boards: " + Theatre.showForTests(id));
            Theatre.stepForTests(level, v, true);                           // the rest, and the bows
            show = Theatre.showForTests(id);
            Kit.log("cw01 the bows: " + Theatre.phaseForTests(id) + "; " + show);
            helper.assertTrue(Theatre.phaseForTests(id).startsWith("BOWS"), "the bows");
            helper.assertTrue(told(id, "the players put on “The Battle of Ashby Field”"), "into the chronicle");
            helper.assertTrue(show.split("\\|").length > 7 && show.split("\\|")[7].contains("Ashby"), "reviewed: " + show);
            boolean remembers = captain.persona().memories().stream().anyMatch(m -> m.text().contains("the players played me"));
            helper.assertTrue(remembers, "the real Captain remembers seeing itself played");
            helper.assertTrue(Troupe.costumeForTests(cap.getItemBySlot(EquipmentSlot.HEAD)), "still in costume for the bows");
            Theatre.stepForTests(level, v, true);                           // the house goes home
            helper.assertTrue(!Troupe.costumeForTests(cap.getItemBySlot(EquipmentSlot.HEAD)), "the costume back in the wardrobe after");
            helper.succeed();
        });
    }

    // ============================================================ cw02: the rain and the playhouse

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "cw02_rain")
    public static void cw02_rain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 2262000, 5, 34, 2000L);
        UUID id = t.id();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            Villages.ageForTests(id, Villages.Age.IRON);
            players(t, 3);
            theatre(level, id, t.heart().offset(0, 0, -16));
            long rest = restDay(id, level.getDayTime() / 24000L + 1);
            helper.assertTrue(rest > 0, "a day of rest to come");
            level.setDayTime(rest * 24000L + 13200L);
            rain(level, true);
            helper.assertTrue(Theatre.venueForTests(level, id, true).isEmpty(), "nowhere to play in the rain: the theatre has no roof");
            Theatre.tickForTests(level, v);
            helper.assertTrue(told(id, "the play was put off for the rain"), "put off, and the town told");
            helper.assertTrue(Theatre.phaseForTests(id) == null, "no play tonight");
            String next = Theatre.occasionForTests(id, rest + 1);
            helper.assertTrue("the play the rain put off".equals(next), "the next evening is its night: " + next);
            level.setDayTime((rest + 1) * 24000L + 13200L);
            Theatre.tickForTests(level, v);                                 // rained off again
            helper.assertTrue(Theatre.playhouseWanted(id), "twice rained off: a playhouse wanted");
            String why = Villages.whyBuild(id, Theatre.PLAYHOUSE);
            Kit.log("cw02 why: " + why);
            helper.assertTrue(why.contains("roof") && why.contains("2 plays"), "and why: " + why);
            BlockPos house = t.heart().offset(18, 0, 18);
            BuildGoal.stamp(level, Theatre.PLAYHOUSE, house, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, Theatre.PLAYHOUSE, house, Direction.NORTH);
            helper.assertTrue(!Theatre.playhouseWanted(id), "not wanted once it stands");
            helper.assertTrue("the playhouse".equals(Theatre.venueForTests(level, id, true)), "the playhouse in the rain");
            level.setDayTime((rest + 2) * 24000L + 13200L);
            Theatre.tickForTests(level, v);
            String show = Theatre.showForTests(id);
            Kit.log("cw02 in the rain: " + show);
            helper.assertTrue(show.contains("the playhouse"), "the play goes on under the roof: " + show);
            rain(level, false);
            helper.succeed();
        });
    }

    // ============================================================ cw03: the understudy, and a play stopped

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "cw03_understudy")
    public static void cw03_understudy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 2264000, 7, 30, 13500L);
        UUID id = t.id();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            Villages.ageForTests(id, Villages.Age.IRON);
            List<String> company = players(t, 4);
            theatre(level, id, t.heart().offset(0, 0, -16));
            long day = level.getDayTime() / 24000L;
            Villages.tell(id, day - 2, "Ada and Bert were wed");
            String title = Theatre.beginForTests(level, v);
            List<String> cast = Theatre.castForTests(id);
            helper.assertTrue(title != null && cast.size() == 3, "three of the four players cast: " + cast);
            Theatre.stepForTests(level, v, true);
            VillageFolkEntity lost = named(t, cast.get(1));
            lost.hurt(level.damageSources().generic(), Float.MAX_VALUE);
            helper.assertTrue(!lost.isAlive(), "a player lost mid-play");
            Theatre.stepForTests(level, v, true);
            String show = Theatre.showForTests(id);
            Kit.log("cw03 played on: " + show);
            helper.assertTrue(show.contains("stood in for " + cast.get(1)), "the fourth player stood in: " + show);
            helper.assertTrue(Theatre.phaseForTests(id).startsWith("BOWS"), "and the play went on to the bows");
            helper.assertTrue(told(id, "in memory of " + cast.get(1)), "given in memory of the player lost");
            Theatre.stepForTests(level, v, true);

            // Again, and this time every player lost: nobody to play the parts.
            String again = Theatre.beginForTests(level, v);
            List<String> second = Theatre.castForTests(id);
            helper.assertTrue(again != null && second.size() == 3, "the three left cast: " + second);
            Theatre.stepForTests(level, v, true);
            for (String n : company) {
                VillageFolkEntity f = named(t, n);
                if (f != null && f.isAlive()) f.hurt(level.damageSources().generic(), Float.MAX_VALUE);
            }
            Theatre.beatsForTests(level, v, 3);
            Kit.log("cw03 stopped: " + Theatre.showForTests(id));
            helper.assertTrue(told(id, "was stopped half way"), "the play stopped, and the town told");
            helper.succeed();
        });
    }

    // ============================================================ cw04: the playbill, a seat, a play asked for

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "cw04_playbill")
    public static void cw04_playbill(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 2266000, 5, 30, 2000L);
        UUID id = t.id();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            Villages.ageForTests(id, Villages.Age.IRON);
            players(t, 3);
            theatre(level, id, t.heart().offset(0, 0, -16));
            chestAt(level, t.heart().offset(3, 0, -3), new ItemStack(Items.OAK_SIGN, 2), new ItemStack(Items.BOOK, 2));
            BlockPos sign = Playbill.putForTests(level, v);
            helper.assertTrue(sign != null && level.getBlockState(sign).getBlock() instanceof StandingSignBlock, "the playbill stands before the theatre");
            SignBlockEntity be = (SignBlockEntity) level.getBlockEntity(sign);
            String first = be.getFrontText().getMessage(0, false).getString(), second = be.getFrontText().getMessage(1, false).getString();
            Kit.log("cw04 the playbill: " + first + " / " + second);
            helper.assertTrue("PLAYBILL".equals(first) && !second.isEmpty(), "with the coming play on it");

            Player you = helper.makeMockPlayer(GameType.SURVIVAL);
            you.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 5));
            int treasury = Ledger.coins(id);
            String bought = Playbill.buy(level, v, you);
            Kit.log("cw04 a seat: " + bought);
            helper.assertTrue(Market.coinsHeld(you) == 4 && Ledger.coins(id) == treasury + 1, "a coin from the player into the treasury");
            boolean programme = false;
            for (int i = 0; i < you.getInventory().getContainerSize(); i++) if (you.getInventory().getItem(i).is(Items.WRITTEN_BOOK)) programme = true;
            helper.assertTrue(programme, "the programme in hand");

            String name = you.getName().getString();
            String none = Playbill.request(level, v, you);
            helper.assertTrue(none.contains("found nothing"), "no deeds yet, no play: " + none);
            long day = level.getDayTime() / 24000L;
            Villages.tell(id, day - 1, name + " stood with the village against the raiders");
            String asked = Playbill.request(level, v, you);
            Kit.log("cw04 asked: " + asked);
            helper.assertTrue(asked.contains("The Ballad of " + name), "the company will play the player's deeds: " + asked);
            level.setDayTime(day * 24000L + 13500L);
            String title = Theatre.beginForTests(level, v);
            helper.assertTrue(("The Ballad of " + name).equals(title), "and puts it on next: " + title);
            List<String> lines = Theatre.scriptForTests(id);
            helper.assertTrue(lines.stream().anyMatch(l -> l.contains("stood with the village against the raiders")), "the deed told: " + lines);
            Theatre.stepForTests(level, v, true);
            for (VillageFolkEntity f : t.folk()) Culture.hold(f, level);
            Theatre.stepForTests(level, v, true);
            helper.assertTrue(Playbill.requested(id) == null, "the request kept, and done with");
            String gazette = Playbill.gazette(id, day + 1);
            Kit.log("cw04 the gazette: " + gazette);
            helper.assertTrue(gazette != null && gazette.contains("At the theatre") && gazette.contains("The Ballad of " + name), "reviewed in the gazette");
            helper.succeed();
        });
    }

    // ============================================================ cw05: the Great Belfry, stage by stage

    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "cw05_belfry")
    public static void cw05_belfry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 2268000, 6, 52, 2000L);
        UUID id = t.id();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            Villages.ageForTests(id, Villages.Age.IRON);
            String no = TownWonders.whyNotForTests(level, id);
            Kit.log("cw05 why not: " + no);
            helper.assertTrue(no.contains("thirty folk"), "a town of six has no wonder: " + no);
            Ledger.addCoins(id, 400);
            Villages.bedsForTests(id, level.getGameTime(), 12);
            BlockPos s = t.heart().offset(4, 0, -4);
            chestAt(level, s, stacks(Items.STONE_BRICKS, 12));
            chestAt(level, s.offset(2, 0, 0), stacks(Items.COBBLESTONE, 6));
            chestAt(level, s.offset(4, 0, 0), new ItemStack(Items.GLASS_PANE, 32), new ItemStack(Items.LANTERN, 16), new ItemStack(Items.GOLD_INGOT, 27),
                new ItemStack(Items.LADDER, 32), new ItemStack(Items.BELL), new ItemStack(Items.OAK_FENCE, 64), new ItemStack(Items.OAK_DOOR, 2),
                new ItemStack(Items.OAK_SIGN, 2), new ItemStack(Items.OAK_LOG, 16));
            int bricks = Market.stock(level, id, x -> x.is(Items.STONE_BRICKS));
            String started = TownWonders.startNow(level, v, "belfry");
            Kit.log("cw05 by decree: " + started + " -> " + TownWonders.stateForTests(id));
            helper.assertTrue(started.contains("the Great Belfry begun"), "begun on its own lot: " + started);
            helper.assertTrue(Villages.sitesOf(id).containsKey(TownWonders.BELFRY), "its ground spoken for");
            helper.assertTrue(told(id, "into the wonder fund"), "a quarter of the treasury into the fund");

            long day = level.getDayTime() / 24000L;
            level.setDayTime((day + 1) * 24000L + 2000L);
            TownWonders.morningForTests(level, v);
            String st = TownWonders.stateForTests(id);
            helper.assertTrue(st.startsWith("belfry|building|0|"), "the footings begun the next morning: " + st);
            int first = TownWonders.buildForTests(level, v, t.folk(), 400);
            Kit.log("cw05 the footings: " + first + " -> " + TownWonders.stateForTests(id));
            helper.assertTrue(first >= 49, "the footings laid: " + first);
            level.setDayTime((day + 2) * 24000L + 2000L);
            TownWonders.morningForTests(level, v);                            // the footings' hands paid; the walls tomorrow
            level.setDayTime((day + 3) * 24000L + 2000L);
            TownWonders.morningForTests(level, v);
            st = TownWonders.stateForTests(id);
            helper.assertTrue(st.startsWith("belfry|building|1|"), "the walls begun: " + st);
            int today = TownWonders.buildForTests(level, v, t.folk(), 400);
            Kit.log("cw05 a day's courses: " + today + " -> " + TownWonders.stateForTests(id));
            helper.assertTrue(today == 60, "sixty blocks a day and no more (the mortar sets overnight): " + today);
            helper.assertTrue(Market.stock(level, id, x -> x.is(Items.STONE_BRICKS)) < bricks, "the stone out of the stores");
            TownWonders.tickForTests(level, v);
            TownWonders.tickForTests(level, v);
            List<BlockPos> scaffold = TownWonders.scaffoldForTests(id);
            helper.assertTrue(!scaffold.isEmpty(), "the scaffold going up round it");
            BlockPos plaque = TownWonders.plaqueForTests(id);
            helper.assertTrue(plaque != null && level.getBlockState(plaque).getBlock() instanceof StandingSignBlock, "the plaque before the site");

            String finished = TownWonders.finishNow(level, v, false);
            Kit.log("cw05 finished: " + finished + " -> " + TownWonders.stateForTests(id));
            helper.assertTrue(finished.contains("opening"), "every stage laid out of the stores: " + finished);
            for (BlockPos p : scaffold) helper.assertTrue(!(level.getBlockState(p).getBlock() instanceof FenceBlock), "the scaffold down: " + p);
            helper.assertTrue(TownWonders.standing(id, TownWonders.BELFRY) != null, "into the town's register");
            int renown = Villages.renown(id);
            List<String> opening = TownWonders.openForTests(level, v);
            Kit.log("cw05 the opening: " + opening);
            helper.assertTrue(opening.stream().anyMatch(l -> l.contains("I declare the Great Belfry open!")), "declared open");
            helper.assertTrue(TownWonders.raisedForTests(id).contains("the Great Belfry"), "raised");
            helper.assertTrue(Villages.renown(id) >= TownWonders.RENOWN, "and its renown: " + renown + " -> " + Villages.renown(id));
            helper.assertTrue(told(id, "the town's own wonder"), "into the chronicle");
            BlockPos bell = TownWonders.standing(id, TownWonders.BELFRY).anchor().above(18);
            helper.assertTrue(level.getBlockState(bell).getBlock() instanceof BellBlock, "the great bell in its chamber: " + level.getBlockState(bell));
            helper.assertTrue(TownWonders.stateForTests(id).isEmpty(), "nothing under way now");
            helper.succeed();
        });
    }

    private static ItemStack[] stacks(net.minecraft.world.item.Item item, int n) {
        ItemStack[] out = new ItemStack[n];
        for (int i = 0; i < n; i++) out[i] = new ItemStack(item, 64);
        return out;
    }

    // ============================================================ cw06: the Great Minster's troubles

    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "cw06_minster")
    public static void cw06_minster(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 2270000, 6, 52, 2000L);
        UUID id = t.id();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            Villages.ageForTests(id, Villages.Age.IRON);
            Ledger.addCoins(id, 400);
            Villages.bedsForTests(id, level.getGameTime(), 12);
            chestAt(level, t.heart().offset(4, 0, -4), new ItemStack(Items.STONE_BRICKS, 64), new ItemStack(Items.OAK_SIGN, 2));
            String started = TownWonders.startNow(level, v, "minster");
            Kit.log("cw06 begun: " + started);
            helper.assertTrue(started.contains("the Great Minster begun"), "begun: " + started);
            long day = level.getDayTime() / 24000L;
            Leader.booksForTests(id, new Leader.Books(10, 2, 20, 2, 20, 0.5, Leader.Plan.FAMINE, day));
            level.setDayTime((day + 1) * 24000L + 2000L);
            TownWonders.morningForTests(level, v);
            String st = TownWonders.stateForTests(id);
            Kit.log("cw06 hungry: " + st);
            helper.assertTrue(st.contains("short of food"), "stopped while the town is short of food: " + st);
            helper.assertTrue(told(id, "was stopped: the town is short of food"), "and the town told");
            helper.assertTrue(!TownWonders.hold(t.folk().get(1), level), "nobody called to it");
            Leader.booksForTests(id, new Leader.Books(400, 40, 20, 40, 20, 20.0, Leader.Plan.PLENTY, day + 1));
            TownWonders.morningForTests(level, v);
            helper.assertTrue(told(id, "went on again"), "and on again with food to spare");

            Player you = helper.makeMockPlayer(GameType.SURVIVAL);
            you.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), 30));
            String gave = TownWonders.donate(level, v, you, 10);
            Kit.log("cw06 the player gives: " + gave);
            helper.assertTrue(Market.coinsHeld(you) == 20 && gave.contains("the fund holds"), "ten coins from the player into the fund");
            ItemStack stone = new ItemStack(Items.STONE_BRICKS, 64);
            String goods = TownWonders.giveGoods(level, v, you, stone);
            Kit.log("cw06 and stone: " + goods);
            helper.assertTrue(goods.contains("64") && Market.stock(level, id, x -> x.is(Items.STONE_BRICKS)) >= 120, "sixty-four stone into the stores");
            helper.assertTrue(told(id, you.getName().getString() + " gave 64"), "named for it");

            String state = TownWonders.stateForTests(id);
            TownWonders.reloadForTests(level);
            Villages.forgetStock();
            TownWonders.tickForTests(level, v);
            Kit.log("cw06 after a save and a load: " + TownWonders.stateForTests(id));
            helper.assertTrue(TownWonders.stateForTests(id).equals(state), "the same after a restart: " + state + " / " + TownWonders.stateForTests(id));
            helper.assertTrue(Villages.sitesOf(id).containsKey(TownWonders.MINSTER), "its ground still spoken for");

            String laid = TownWonders.stageNow(level, v, false);
            Kit.log("cw06 the footings from the stores: " + laid + " -> " + TownWonders.stateForTests(id));
            String walls = TownWonders.stageNow(level, v, false);
            Kit.log("cw06 the walls, short of stone: " + walls + " -> " + TownWonders.stateForTests(id));
            helper.assertTrue(TownWonders.stateForTests(id).contains("the stores are out of it"), "short of stone, it waits: " + TownWonders.stateForTests(id));

            int fund = Integer.parseInt(TownWonders.stateForTests(id).split("\\|")[4]);
            int before = Integer.parseInt(TownWonders.stateForTests(id).split("\\|")[3].split("/")[0]);
            TownWonders.sacked(level, id, UUID.randomUUID(), level.getDayTime() / 24000L);
            String after = TownWonders.stateForTests(id);
            Kit.log("cw06 sacked: " + after);
            int now = Integer.parseInt(after.split("\\|")[3].split("/")[0]);
            helper.assertTrue(now < before, "its last courses pulled down: " + before + " -> " + now);
            helper.assertTrue(Integer.parseInt(after.split("\\|")[4]) == fund - fund / 2, "half its fund spent on the peace");
            helper.assertTrue(told(id, "pulled down"), "and the town told");
            helper.succeed();
        });
    }

    // ============================================================ cw07: the amphitheatre, the drawings, more plays

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "cw07_amphitheatre")
    public static void cw07_amphitheatre(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, level, 2272000, 6, 40, 13500L);
        UUID id = t.id();
        helper.runAtTickTime(10, () -> {
            Villages.Village v = village(helper, id);
            Villages.ageForTests(id, Villages.Age.IRON);
            players(t, 3);
            BlockPos at = t.heart().offset(0, 0, -24);
            BuildGoal.stamp(level, TownWonders.AMPHITHEATRE, at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, TownWonders.AMPHITHEATRE, at, Direction.NORTH);
            helper.assertTrue("the Grand Amphitheatre".equals(Theatre.venueForTests(level, id, false)), "the players' house in fine weather");
            helper.assertTrue(Theatre.venueForTests(level, id, true).isEmpty(), "open to the sky: not in the rain");
            String title = Theatre.beginForTests(level, v);
            Theatre.stepForTests(level, v, true);
            int seated = 0;
            for (VillageFolkEntity f : t.folk()) if (Culture.hold(f, level) && "AUDIENCE".equals(Culture.roleForTests(f))) seated++;
            Kit.log("cw07 at the amphitheatre: " + title + ", " + seated + " in the house; " + Theatre.showForTests(id));
            helper.assertTrue(title != null && Theatre.showForTests(id).contains("the Grand Amphitheatre") && seated >= 2, "a play there, watched");

            BlockPos m = t.heart().offset(30, 0, 10), b = t.heart().offset(-30, 0, 10);
            BuildGoal.stamp(level, TownWonders.MINSTER, m, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            BuildGoal.stamp(level, TownWonders.BELFRY, b, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            helper.assertTrue(level.getBlockState(b.above(18)).getBlock() instanceof BellBlock, "the belfry's bell where it is drawn");
            helper.assertTrue(level.getBlockState(m.offset(0, 25, 8)).is(Blocks.LANTERN), "the minster's spire lit at its tip: " + level.getBlockState(m.offset(0, 25, 8)));

            long day = level.getDayTime() / 24000L;
            List<String> fire = Playwright.writeForTests(id, day, "Fire at the bakery! (a spark from the oven)");
            List<String> flood = Playwright.writeForTests(id, day, "the Wend is rising fast in the rain");
            List<String> vote = Playwright.writeForTests(id, day, "Ada was elected elder for the fields (4 to 2)");
            Kit.log("cw07 more plays: " + fire.get(0) + " / " + flood.get(0) + " / " + vote.get(0));
            helper.assertTrue(fire.get(0).startsWith("The Fire") && flood.get(0).startsWith("When the Wend Rose")
                && vote.get(0).startsWith("The Election of Ada, a comedy"), "the fire, the flood and the election, each its own play");
            helper.succeed();
        });
    }
}
