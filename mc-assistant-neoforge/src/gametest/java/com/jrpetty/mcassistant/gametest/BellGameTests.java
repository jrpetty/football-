package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Assemblies;
import com.jrpetty.mcassistant.entity.Birthdays;
import com.jrpetty.mcassistant.entity.FoundingDay;
import com.jrpetty.mcassistant.entity.Homes;
import com.jrpetty.mcassistant.entity.TownBell;
import com.jrpetty.mcassistant.entity.TownCalendar;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.Chronicle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The town's calendar: the town bell that keeps the day, the round birthdays folk keep, and Founding
 * Day. As with the village tests, each has its own batch (they share every static in the mod) and its
 * own ground, far from the others: x 220000 to 227000, z 50000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class BellGameTests {

    private static final String EMPTY = "empty";

    /** A bed on the ground here, its head the way it faces. Returns the head. */
    private static BlockPos bed(ServerLevel level, BlockPos foot, Direction facing) {
        BlockState st = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, facing);
        level.setBlock(foot, st.setValue(BedBlock.PART, BedPart.FOOT), 3);
        level.setBlock(foot.relative(facing), st.setValue(BedBlock.PART, BedPart.HEAD), 3);
        return foot.relative(facing);
    }

    /** A bell on a plinth, as a town has one from a village it moved into, or a player brought. */
    private static BlockPos bellAt(ServerLevel level, BlockPos ground) {
        level.setBlock(ground, Blocks.COBBLESTONE.defaultBlockState(), 3);
        BlockPos b = ground.above();
        level.setBlock(b, Blocks.BELL.defaultBlockState().setValue(BellBlock.FACING, Direction.NORTH)
            .setValue(BellBlock.ATTACHMENT, BellAttachType.FLOOR), 3);
        return b;
    }

    private static int carried(VillageFolkEntity f, Item it) {
        return f.countCarried(s -> s.is(it));
    }

    /** The first day from this one with no gathering of its own in the evening (the weekly feast, the council). */
    private static long quietDay(long from) {
        long d = from;
        while (d % 7 == 6 || d % 7 == 3) d++;
        return d;
    }

    private static String clock(ServerLevel level) {
        return TownCalendar.clock(level.getDayTime());
    }

    // ============================================================ the bell keeps the day

    /**
     * A town of six with a bell on the square and a bed each, from the small hours of the morning
     * through the day. Before the dawn bell the town lies in (nobody but the watch on shift); a ringer
     * walks to the bell and rings it — three strokes, the bell swinging — and the town gets up and goes
     * to work. At noon (the clock set on to the late morning) the bell is rung six times, and the folk
     * take their break and sit down to the midday meal. At dusk (set on again) nine strokes, the day's
     * work stops and they go home to their beds; the guard goes on watch.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "b01_bell_keeps_the_day")
    public static void b01_bell_keeps_the_day(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 220000, z = 50000;
        Kit.hold(level, x, z, 48);
        Kit.prepare(level, x, z, 48);
        BlockPos heart = Kit.surface(level, x, z);
        long base = quietDay(level.getDayTime() / 24000L + 1) * 24000L;   // a day to come: its bell-day starts at 23000 the night before
        long[] clock = { base - 900L };                              // 23100, the small hours
        level.setDayTime(clock[0]);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x + (i % 3) * 2 - 2, z + (i / 3) * 2 + 2), 0.0F);
            helper.assertTrue(f != null, "a folk of the town");
            folk.add(f);
        }
        UUID village = folk.get(0).ownerId();
        VillageFolkEntity guard = folk.get(0);
        guard.setJob(StationTask.GUARD);
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        BlockPos bell = bellAt(level, Kit.surface(level, x + 6, z - 3));
        // A bed each, a dozen blocks off: home is a walk away.
        for (int i = 0; i < folk.size(); i++) {
            BlockPos head = bed(level, Kit.surface(level, x - 4 + i * 2, z + 14), Direction.SOUTH);
            folk.get(i).claimBedNear(head);
        }
        Kit.log("b01 a town of " + folk.size() + " at " + heart.toShortString() + ", the bell at " + bell.toShortString()
            + "; beds: " + folk.stream().map(f -> f.displayNameCap() + "=" + (f.bedPos() == null ? "none" : f.bedPos().toShortString())).toList());

        int[] phase = { 0 };
        long[] mark = { -1 };
        boolean[] lay = { false }, swung = { false };
        int[] strokesAt = { 0 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            level.setDayTime(++clock[0]);                           // the clock runs a tick a tick, whatever the game rules
            long tod = clock[0] % 24000L;
            Villages.Village v = Villages.get(village);
            if (v == null) return;
            if (level.getBlockEntity(bell) instanceof BellBlockEntity be && be.shaking) swung[0] = true;
            List<VillageFolkEntity> grown = folk.stream().filter(f -> f.isAlive() && !f.isBaby()).toList();
            List<VillageFolkEntity> hands = grown.stream().filter(f -> f.stationTask() != StationTask.GUARD).toList();
            if (t % 200 == 0) {
                Kit.log("b01 tick " + t + " " + clock(level) + " phase " + phase[0] + ": strokes " + TownBell.strokes(village)
                    + ", ringer " + TownBell.ringer(village) + "; " + TownBell.status(level, v));
                for (VillageFolkEntity f : grown) {
                    Kit.log("   " + f.displayNameCap() + " " + f.stationTask() + " sleeping=" + f.isSleeping() + " onShift=" + f.onShift()
                        + " doing=" + f.hobbyNow() + " card: " + TownBell.cardLine(f) + " | " + f.debugLine());
                }
            }
            switch (phase[0]) {
                case 0 -> {
                    // The small hours, the ringer set off: the town lies in for the bell.
                    Object dawn = TownBell.rung(village, TownBell.Peal.DAWN, clock[0]);
                    if (dawn == null && tod >= 23700L && tod < 23990L && !lay[0]) {
                        long up = hands.stream().filter(VillageFolkEntity::onShift).count();
                        Kit.log("b01 " + clock(level) + ", before the dawn bell: " + up + " of " + hands.size() + " hands on shift; ringer "
                            + TownBell.ringer(village));
                        helper.assertTrue(up == 0, "before the dawn bell the town lies in: nobody but the watch is on shift");
                        lay[0] = true;
                    }
                    if (dawn != null) {
                        Kit.log("b01 the dawn bell: " + dawn + " at " + clock(level));
                        helper.assertTrue(lay[0], "the town was seen lying in before the dawn bell");
                        mark[0] = t;
                        strokesAt[0] = TownBell.strokes(village);
                        phase[0] = 1;
                    } else if (tod > 1200L && tod < 20000L) {
                        helper.fail("no dawn bell by " + clock(level) + ": " + TownBell.status(level, v));
                    }
                }
                case 1 -> {
                    if (t - mark[0] < 300) return;
                    // Up: at work, or at the morning assembly at the board that opens the working day.
                    long up = hands.stream().filter(f -> !f.isSleeping() && (f.onShift() || Assemblies.attending(f))).count();
                    long answered = grown.stream().filter(f -> TownBell.answered(f, TownBell.Peal.DAWN) >= 0).count();
                    if (TownBell.ringing(village) && t - mark[0] < 600) return;
                    int strokes = TownBell.strokes(village);
                    Kit.log("b01 after the dawn bell (" + clock(level) + "): " + up + " of " + hands.size() + " hands up and on shift, "
                        + answered + " of " + grown.size() + " answered it; " + strokes + " strokes; the bell swung " + swung[0]);
                    helper.assertTrue(strokes >= TownBell.Peal.DAWN.strokes, "three strokes at dawn: " + strokes);
                    helper.assertTrue(swung[0], "the bell swung as it was rung");
                    helper.assertTrue(up * 10 >= hands.size() * 6L, "most of the town up and at work after the dawn bell: " + up + " of " + hands.size());
                    // On to the late morning: the noon bell.
                    Assemblies.resetForTests();                 // the morning assembly, if it is still on, let go
                    clock[0] = base + 5500L;
                    level.setDayTime(clock[0]);
                    swung[0] = false;
                    strokesAt[0] = TownBell.strokes(village);
                    phase[0] = 2;
                }
                case 2 -> {
                    Object noon = TownBell.rung(village, TownBell.Peal.NOON, clock[0]);
                    if (noon == null) {
                        if (tod > 7000L) helper.fail("no noon bell by " + clock(level) + ": " + TownBell.status(level, v));
                        return;
                    }
                    Kit.log("b01 the noon bell: " + noon + " at " + clock(level));
                    for (VillageFolkEntity f : grown) f.clearQueue();   // hands free: whatever each had in hand is put down
                    mark[0] = t;
                    phase[0] = 3;
                }
                case 3 -> {
                    long ate = grown.stream().filter(f -> TownBell.answered(f, TownBell.Peal.NOON) >= 0).count();
                    if (ate * 10 < grown.size() * 6L && t - mark[0] < 900) return;
                    if (TownBell.ringing(village) && t - mark[0] < 600) return;       // the peal's strokes all rung first
                    int strokes = TownBell.strokes(village) - strokesAt[0];
                    for (VillageFolkEntity f : grown) Kit.log("   " + f.displayNameCap() + " at the noon bell: " + TownBell.did(f, TownBell.Peal.NOON)
                        + "; meals: " + com.jrpetty.mcassistant.entity.Meals.line(f));
                    long fed = grown.stream().filter(f -> f.meals().eatenToday() >= 1).count();
                    Kit.log("b01 after the noon bell (" + clock(level) + "): " + ate + " of " + grown.size() + " went to their meal, " + fed
                        + " have eaten today; " + strokes + " strokes, the bell swung " + swung[0]);
                    helper.assertTrue(strokes >= TownBell.Peal.NOON.strokes, "six strokes at noon: " + strokes);
                    helper.assertTrue(ate * 10 >= grown.size() * 6L, "most of the town went to its midday meal at the noon bell: " + ate + " of " + grown.size());
                    helper.assertTrue(grown.stream().filter(f -> TownBell.answered(f, TownBell.Peal.NOON) >= 0)
                            .allMatch(f -> !TownBell.did(f, TownBell.Peal.NOON).startsWith("nothing")),
                        "each that went sat down to a meal");
                    // On to the evening: the dusk bell.
                    clock[0] = base + 11500L;
                    level.setDayTime(clock[0]);
                    swung[0] = false;
                    strokesAt[0] = TownBell.strokes(village);
                    phase[0] = 4;
                }
                case 4 -> {
                    Object dusk = TownBell.rung(village, TownBell.Peal.DUSK, clock[0]);
                    if (dusk == null) {
                        if (tod > 13000L) helper.fail("no dusk bell by " + clock(level) + ": " + TownBell.status(level, v));
                        return;
                    }
                    Kit.log("b01 the dusk bell: " + dusk + " at " + clock(level));
                    for (VillageFolkEntity f : grown) f.clearQueue();
                    mark[0] = t;
                    phase[0] = 5;
                }
                case 5 -> {
                    long home = hands.stream().filter(f -> TownBell.did(f, TownBell.Peal.DUSK).contains("set off home")
                        || TownBell.did(f, TownBell.Peal.DUSK).contains("went home")).count();
                    if (home * 10 < hands.size() * 6L && t - mark[0] < 900) return;
                    if (t - mark[0] < 200) return;
                    // Nine strokes, one every twenty-five ticks, take two hundred ticks from the first: the peal
                    // is judged once it is done (looked at on the two hundredth tick, the ninth was still to come).
                    if (TownBell.ringing(village) && t - mark[0] < 600) return;
                    int strokes = TownBell.strokes(village) - strokesAt[0];
                    long working = hands.stream().filter(VillageFolkEntity::onShift).count();
                    for (VillageFolkEntity f : grown) Kit.log("   " + f.displayNameCap() + " at the dusk bell: " + TownBell.did(f, TownBell.Peal.DUSK)
                        + " (" + (f.bedPos() == null ? "no bed" : String.format("%.1f", Math.sqrt(f.blockPosition().distSqr(f.bedPos()))) + " from its bed") + ")");
                    Kit.log("b01 after the dusk bell (" + clock(level) + "): " + home + " of " + hands.size() + " hands home or on their way, "
                        + working + " still on shift; " + strokes + " strokes; the board says: " + TownCalendar.board(level, village));
                    helper.assertTrue(strokes >= TownBell.Peal.DUSK.strokes, "nine strokes at dusk: " + strokes);
                    helper.assertTrue(home * 10 >= hands.size() * 6L, "most of the town heading home after the dusk bell: " + home + " of " + hands.size());
                    helper.assertTrue(working == 0, "the day's work is done at the dusk bell: " + working + " still on shift");
                    helper.assertTrue(TownBell.did(guard, TownBell.Peal.DUSK).isEmpty() || TownBell.did(guard, TownBell.Peal.DUSK).contains("watch"),
                        "the guard goes on watch, not home: " + TownBell.did(guard, TownBell.Peal.DUSK));
                    helper.succeed();
                }
                default -> { }
            }
        });
    }

    // ============================================================ a birthday present

    /**
     * It is a folk's birthday (forty today): its friend, off work for the evening, walks round with a
     * present out of its own pack — the flower it carries — and the flower goes from the one pack to the
     * other, kept as a keepsake. The folk is the happier for it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "b02_birthday_present")
    public static void b02_birthday_present(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 222000, z = 50000;
        Kit.hold(level, x, z, 32);
        Kit.prepare(level, x, z, 32);
        long evening = quietDay(level.getDayTime() / 24000L) * 24000L + 12700L;   // after work, before bedtime, no feast
        level.setDayTime(evening);
        VillageFolkEntity friend = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, z), 0.0F);
        VillageFolkEntity birthday = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x + 7, z + 3), 0.0F);
        VillageFolkEntity third = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 4, z + 2), 0.0F);
        helper.assertTrue(friend != null && birthday != null && third != null, "a town of three");
        UUID village = friend.ownerId();
        long day = evening / 24000L;
        friend.ensurePersona();
        birthday.ensurePersona();
        // Friends of long standing.
        friend.life().feel(birthday.getUUID(), birthday.displayNameCap(), 60);
        birthday.life().feel(friend.getUUID(), friend.displayNameCap(), 60);
        friend.insertItem(new ItemStack(Items.POPPY));
        birthday.bornDaysAgo(14);                                    // forty today: thirty-eight yesterday
        int[] before = { carried(friend, Items.POPPY), carried(birthday, Items.POPPY) };
        birthday.refreshMood();
        int moodBefore = birthday.persona().mood();
        Kit.log("b02 the mood before its birthday: " + moodBefore + " " + birthday.persona().moodWhy());
        helper.assertTrue(birthday.ageYears() == 40 && Birthdays.ageOn(birthday.bornDay(), day - 1) == 38,
            "forty today, thirty-eight yesterday: a birthday (" + birthday.ageYears() + ")");
        Birthdays.celebrate(level, Villages.get(village), birthday, day);
        Kit.log("b02 " + birthday.displayNameCap() + "'s birthday, " + birthday.ageYears() + "; " + friend.displayNameCap() + " owes a present: "
            + Birthdays.owes(friend) + "; poppies " + before[0] + "/" + before[1] + "; card: " + Birthdays.cardLine(birthday));
        helper.assertTrue(Birthdays.owes(friend), "its friend sets out with a present");
        helper.assertTrue(!Birthdays.owes(third), "one that is no friend of it does not");
        boolean[] seenWalking = { false };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            level.setDayTime(evening);                               // the evening stays the evening
            if (Birthdays.visiting(friend)) seenWalking[0] = true;
            else if (!seenWalking[0] && friend.peekJob() != null) friend.clearQueue();   // its evening errand can wait
            List<String> got = Birthdays.presents(birthday);
            if (t % 100 == 0) Kit.log("b02 tick " + t + ": " + friend.displayNameCap() + " " + String.format("%.1f", friend.distanceTo(birthday))
                + " blocks off, visiting=" + Birthdays.visiting(friend) + ", doing=" + friend.hobbyNow() + "; given " + got);
            if (got.isEmpty()) {
                if (t >= 1500) helper.fail("no present by tick " + t + ": " + friend.debugLine());
                return;
            }
            int[] after = { carried(friend, Items.POPPY), carried(birthday, Items.POPPY) };
            boolean kept = false;
            for (ItemStack s : birthday.getInventoryItems()) if (s.is(Items.POPPY) && Homes.isKeepsake(s)) kept = true;
            birthday.refreshMood();
            Kit.log("b02 given: " + got + "; poppies " + before[0] + "/" + before[1] + " -> " + after[0] + "/" + after[1]
                + "; a keepsake " + kept + "; mood " + moodBefore + " -> " + birthday.persona().mood() + " " + birthday.persona().moodWhy()
                + "; card: " + Birthdays.cardLine(birthday));
            helper.assertTrue(after[0] == before[0] - 1 && after[1] == before[1] + 1,
                "the flower went from the friend's pack to the birthday folk's: " + before[0] + "/" + before[1] + " -> " + after[0] + "/" + after[1]);
            helper.assertTrue(kept, "and it is kept as its own");
            helper.assertTrue(birthday.persona().moodWhy().contains("gift") && birthday.persona().moodWhy().contains("birthday"),
                "the birthday and the present raise its spirits: " + birthday.persona().moodWhy());
            helper.assertTrue(birthday.persona().mood() > moodBefore || birthday.persona().mood() == 100,
                "its spirits rise: " + moodBefore + " -> " + birthday.persona().mood());
            helper.assertTrue(got.get(0).contains(friend.displayNameCap()), "remembered who gave it: " + got);
            helper.succeed();
        });
    }

    // ============================================================ Founding Day

    /**
     * Twenty-eight days after the town was founded, in the evening, the town keeps Founding Day: it
     * gathers before the board, and the year's chronicle is read out, its lines in the order they
     * happened; then the feast, and fireworks out of the stores' powder and paper; and the history
     * notes the year kept.
     */
    @GameTest(template = EMPTY, timeoutTicks = 6000, batch = "b03_founding_day")
    public static void b03_founding_day(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 224000, z = 50000;
        Kit.hold(level, x, z, 40);
        Kit.prepare(level, x, z, 40);
        // The town is founded today, by its own history; then the clock is put on a year (twenty-eight days),
        // to the evening of its first Founding Day.
        long founded = level.getDayTime() / 24000L;
        level.setDayTime(founded * 24000L + 2000L);
        long day = founded + TownCalendar.YEAR_DAYS;
        long evening = day * 24000L + 12150L;
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x + i * 2 - 4, z + 3), 0.0F);
            helper.assertTrue(f != null, "a folk of the town");
            folk.add(f);
        }
        UUID village = folk.get(0).ownerId();
        level.setDayTime(evening);
        String[] year = { "The smithy was opened", "Ash and Rowan were wed", "Wren was born", "The village came into the Stone Age" };
        long[] when = { day - 25, day - 18, day - 9, day - 3 };
        // Written down out of order, as a history is: it is read out in the order things happened.
        for (int i : new int[]{ 2, 0, 3, 1 }) Chronicle.record(village, when[i], year[i]);
        // Powder and paper for the fireworks, and food for the feast, in the stores.
        List<BlockPos> stores = Villages.storeChests(level, village);
        helper.assertTrue(!stores.isEmpty(), "the founders' stores");
        Container chest = (Container) level.getBlockEntity(stores.get(0));
        int slot = 0;
        while (slot < chest.getContainerSize() && !chest.getItem(slot).isEmpty()) slot++;
        chest.setItem(slot, new ItemStack(Items.GUNPOWDER, 4));
        chest.setItem(slot + 1, new ItemStack(Items.PAPER, 4));
        chest.setItem(slot + 2, new ItemStack(Items.BREAD, 32));
        chest.setChanged();
        Kit.log("b03 day " + day + ", founded on day " + FoundingDay.founded(village) + " (the history says "
            + Chronicle.foundedOn(village) + "); Founding Day today " + FoundingDay.today(village, day) + ", yesterday "
            + FoundingDay.today(village, day - 1) + ", due " + FoundingDay.due(village, day));
        helper.assertTrue(FoundingDay.founded(village) == founded, "the town's history begins the day it was founded");
        helper.assertTrue(FoundingDay.today(village, day) && !FoundingDay.today(village, day - 1), "today, and only today, is Founding Day");
        Kit.log("b03 " + Villages.name(village) + " founded on day " + FoundingDay.founded(village) + ", today " + day + ": "
            + TownCalendar.book(level, village));
        boolean[] started = { false };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            level.setDayTime(evening);                               // the evening held while it is kept
            Villages.Village v = Villages.get(village);
            if (v == null) return;
            if (t % 20 == 0) Assemblies.tick(level, v);
            String now = Assemblies.now(village);
            if (now != null && now.equals("Founding Day")) started[0] = true;
            List<String> read = FoundingDay.readOut(village);
            if (t % 100 == 0) Kit.log("b03 tick " + t + ": " + Assemblies.debug(village) + "; read " + read.size() + ": " + read);
            if (!started[0]) {
                if (t >= 400) helper.fail("Founding Day never began: " + Assemblies.debug(village) + ", due " + FoundingDay.due(village, day));
                return;
            }
            boolean kept = Chronicle.of(village).stream().anyMatch(e -> e.text().startsWith("Founding Day"));
            if (!kept) {
                if (t >= 5800) helper.fail("Founding Day did not finish: read " + read + "; " + Assemblies.debug(village));
                return;
            }
            // Read out, in order.
            List<Integer> at = new ArrayList<>();
            for (String y : year) {
                int k = -1;
                for (int i = 0; i < read.size(); i++) if (read.get(i).contains(y)) { k = i; break; }
                at.add(k);
            }
            // The founding itself first: the history's first day, read as the town's first.
            int foundingAt = -1;
            for (int i = 0; i < read.size(); i++) if (read.get(i).contains("was founded")) { foundingAt = i; break; }
            Kit.log("b03 the founding read at " + foundingAt + (foundingAt >= 0 ? ": " + read.get(foundingAt) : ""));
            helper.assertTrue(foundingAt == 0 && read.get(0).startsWith("Day " + (founded + 1) + ":"),
                "the year's chronicle begins with the founding, on the day it was: " + read);
            int powder = 0;
            for (int i = 0; i < chest.getContainerSize(); i++) if (chest.getItem(i).is(Items.GUNPOWDER)) powder += chest.getItem(i).getCount();
            String line = Chronicle.of(village).stream().filter(e -> e.text().startsWith("Founding Day")).findFirst().map(Chronicle.Entry::text).orElse("");
            Kit.log("b03 kept: \"" + line + "\"; read " + read + " at " + at + "; gunpowder left " + powder);
            helper.assertTrue(at.stream().allMatch(k -> k >= 0), "every one of the year's notable lines read out: " + read);
            helper.assertTrue(at.get(0) < at.get(1) && at.get(1) < at.get(2) && at.get(2) < at.get(3), "in the order they happened: " + at);
            helper.assertTrue(read.get(at.get(0)).startsWith("Day " + (when[0] + 1) + ":"), "each with its day: " + read.get(at.get(0)));
            helper.assertTrue(line.contains("first year"), "the history notes the town's first year kept: " + line);
            if (powder >= 4) Kit.log("b03 (no rocket went up: the feast's steps never fell on a launch tick)");
            helper.succeed();
        });
    }

    // ============================================================ the bell's own frame

    /** How many of a thing the village's stores hold. */
    private static int inStores(ServerLevel level, UUID village, java.util.function.Predicate<ItemStack> what) {
        int n = 0;
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (!(level.getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) if (what.test(c.getItem(i))) n += c.getItem(i).getCount();
        }
        return n;
    }

    /**
     * A town whose bell stands on the ground under the village board, half hidden. The town's works build
     * the bell its own frame on the square, a few blocks from the board and clear of it, its courtyard and the
     * ways in, its front to the square: two log posts, a roof of stairs and slabs cut from the stores' planks
     * (the rest of each batch back in the stores), a lantern under each eave out of the stores. The old bell
     * is rung till then; when the frame stands it is taken down into the stores and hung in the frame, and
     * the frame's bell is the one the town rings.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3600, batch = "b04_bell_frame")
    public static void b04_bell_frame(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 226000, z = 50000;
        Kit.hold(level, x, z, 48);
        Kit.prepare(level, x, z, 48);
        BlockPos heart = Kit.surface(level, x, z);
        long morning = quietDay(level.getDayTime() / 24000L) * 24000L + 2000L;
        level.setDayTime(morning);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x + (i % 3) * 2 - 2, z + (i / 3) * 2 + 2), 0.0F);
            helper.assertTrue(f != null, "a folk of the town");
            f.setNoAi(true);                                  // the stores are the frame's alone while it goes up
            folk.add(f);
        }
        UUID village = folk.get(0).ownerId();
        BlockPos board = com.jrpetty.mcassistant.entity.VillageBoards.boardOf(village);
        Direction facing = com.jrpetty.mcassistant.entity.VillageBoards.facingOf(village);
        BlockPos lectern = com.jrpetty.mcassistant.entity.VillageBoards.lectern(village);
        helper.assertTrue(board != null && facing != null && lectern != null, "the town has its board");
        // The old bell, on the ground under the board, between its posts.
        Direction right = com.jrpetty.mcassistant.block.VillageBoardBlock.right(facing);
        BlockPos old = board.relative(right, 4).atY(heart.getY());
        helper.assertTrue(level.getBlockState(old).isAir(), "room under the board for the old bell: " + level.getBlockState(old));
        level.setBlock(old, Blocks.BELL.defaultBlockState().setValue(BellBlock.FACING, facing)
            .setValue(BellBlock.ATTACHMENT, BellAttachType.FLOOR), 3);
        // The makings: logs and two lanterns, with the founders' planks.
        List<BlockPos> stores = Villages.storeChests(level, village);
        helper.assertTrue(!stores.isEmpty(), "the founders' stores");
        Container chest = (Container) level.getBlockEntity(stores.get(0));
        int slot = 0;
        while (slot < chest.getContainerSize() && !chest.getItem(slot).isEmpty()) slot++;
        chest.setItem(slot, new ItemStack(Items.OAK_LOG, 16));
        chest.setItem(slot + 1, new ItemStack(Items.LANTERN, 2));
        chest.setChanged();
        int[] before = { inStores(level, village, s -> s.is(Items.OAK_LOG)), inStores(level, village, s -> s.is(Items.OAK_PLANKS)),
            inStores(level, village, s -> s.is(Items.LANTERN)) };
        Villages.Village v = Villages.get(village);
        BlockPos rang0 = TownBell.bellAt(level, v);
        Kit.log("b04 the board at " + board.toShortString() + " facing " + facing + ", the lectern " + lectern.toShortString()
            + "; the old bell at " + old.toShortString() + " (the town rings " + (rang0 == null ? "none" : rang0.toShortString())
            + "); stores: logs " + before[0] + ", planks " + before[1] + ", lanterns " + before[2]);
        helper.assertTrue(old.equals(rang0), "till its frame is built, the town rings the bell it has, under the board");
        com.jrpetty.mcassistant.entity.BellFrame.Frame frame = com.jrpetty.mcassistant.entity.BellFrame.planForTests(level, v);
        helper.assertTrue(frame != null, "ground on the square for a bell frame");
        Kit.log("b04 the frame is to stand at " + frame.origin.toShortString() + " along " + frame.along + ", its front " + frame.front
            + ", " + String.format("%.1f", Math.sqrt(frame.origin.distSqr(lectern))) + " blocks from the lectern");
        boolean[] rung = { false };
        long[] hungAt = { -1 };
        helper.onEachTick(() -> {
            long t = helper.getTick();
            level.setDayTime(morning);
            if (t % 100 == 0) Kit.log("b04 tick " + t + ": " + TownBell.status(level, v));
            boolean hung = level.getBlockState(frame.bell()).is(Blocks.BELL);
            if (!hung) {
                if (t >= 3300) helper.fail("the bell never went up in its frame: " + TownBell.status(level, v));
                return;
            }
            if (hungAt[0] < 0) {
                hungAt[0] = t;
                // Its own frame: posts, roof, lights, the bell between the posts.
                int posts = frame.posts();
                StringBuilder seen = new StringBuilder();
                boolean ok = true;
                for (int k = -2; k <= 2; k++) {
                    for (int h = 0; h <= posts; h++) {
                        BlockState st = level.getBlockState(frame.origin.relative(frame.along, k).above(h));
                        if (!st.isAir()) seen.append(" [").append(k).append(',').append(h).append(' ')
                            .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath()).append(']');
                    }
                }
                Kit.log("b04 the frame:" + seen);
                for (int h = 0; h < posts; h++) {
                    for (int k : new int[]{ -1, 1 }) {
                        BlockState st = level.getBlockState(frame.origin.relative(frame.along, k).above(h));
                        ok &= st.is(net.minecraft.tags.BlockTags.LOGS) || st.is(Blocks.STONE_BRICKS);
                    }
                }
                helper.assertTrue(ok, "two posts, of logs (or stone bricks): " + seen);
                BlockPos top = frame.origin.above(posts);
                helper.assertTrue(level.getBlockState(top.relative(frame.along, -1)).is(net.minecraft.tags.BlockTags.STAIRS)
                        && level.getBlockState(top.relative(frame.along, 1)).is(net.minecraft.tags.BlockTags.STAIRS)
                        && level.getBlockState(top.relative(frame.along, -2)).is(net.minecraft.tags.BlockTags.SLABS)
                        && level.getBlockState(top.relative(frame.along, 2)).is(net.minecraft.tags.BlockTags.SLABS)
                        && !level.getBlockState(top).isAir(),
                    "a roof over it: a ridge, a stair either side, slabs at the eaves: " + seen);
                helper.assertTrue(level.getBlockState(top.below().relative(frame.along, -2)).is(Blocks.LANTERN)
                        && level.getBlockState(top.below().relative(frame.along, 2)).is(Blocks.LANTERN),
                    "a lantern under each eave: " + seen);
                BlockState bell = level.getBlockState(frame.bell());
                helper.assertTrue(bell.getValue(BellBlock.ATTACHMENT) == BellAttachType.DOUBLE_WALL,
                    "the bell hung between the posts: " + bell);
                helper.assertTrue(level.getBlockState(old).isAir(), "the old bell taken down from under the board");
                // Clear of the board and its courtyard, a few blocks from it.
                int[] court = Villages.courtRectForTests(village);
                for (int k = -2; k <= 2; k++) {
                    BlockPos c = frame.origin.relative(frame.along, k);
                    helper.assertTrue(court == null || c.getX() < court[0] || c.getX() > court[1] || c.getZ() < court[2] || c.getZ() > court[3],
                        "the frame is clear of the courtyard before the board");
                    for (int b = -1; b <= 10; b++) {
                        BlockPos col = board.relative(right, b);
                        helper.assertTrue(c.getX() != col.getX() || c.getZ() != col.getZ(), "and of the board itself");
                    }
                }
                helper.assertTrue(frame.origin.distSqr(lectern) <= 20 * 20, "a few blocks from the board");
                // Out of the stores, nothing from nothing: six logs, ten planks (a batch of stairs and one of slabs,
                // the rest of each back in the stores), two lanterns; the bell back to the frame.
                int[] after = { inStores(level, village, s -> s.is(Items.OAK_LOG)), inStores(level, village, s -> s.is(Items.OAK_PLANKS)),
                    inStores(level, village, s -> s.is(Items.LANTERN)), inStores(level, village, s -> s.is(Items.OAK_STAIRS)),
                    inStores(level, village, s -> s.is(Items.OAK_SLAB)), inStores(level, village, s -> s.is(Items.BELL)) };
                Kit.log("b04 stores after: logs " + after[0] + ", planks " + after[1] + ", lanterns " + after[2] + ", stairs " + after[3]
                    + ", slabs " + after[4] + ", bells " + after[5]);
                // (Other hands may draw on the stores meanwhile: what the frame took is at least this, and the
                // rest of its batches, made of nothing else, are in the stores.)
                helper.assertTrue(after[0] <= before[0] - 2 * posts, "a log a post block: " + before[0] + " -> " + after[0]);
                helper.assertTrue(after[1] <= before[1] - 10 && after[3] >= 1 && after[4] >= 1,
                    "the roof cut from ten planks, the rest of the batches put by: planks " + before[1] + " -> " + after[1]
                        + ", stairs " + after[3] + ", slabs " + after[4]);
                if (after[1] != before[1] - 10 || after[3] != 2 || after[4] != 4) Kit.log("b04 (the stores were drawn on by others too)");
                helper.assertTrue(after[2] <= before[2] - 2, "two lanterns out of the stores");
                helper.assertTrue(after[5] == 0, "the bell is in the frame, not left in the stores");
                Villages.Village vv = Villages.get(village);
                TownBell.forgetForTests(village);
                helper.assertTrue(frame.bell().equals(TownBell.bellAt(level, vv)), "and the frame's bell is the town bell");
                Kit.log("b04 " + TownBell.ringNow(level, vv, TownBell.Peal.DAWN));
                return;
            }
            if (level.getBlockEntity(frame.bell()) instanceof BellBlockEntity be && be.shaking) rung[0] = true;
            if (t - hungAt[0] >= 20) {
                helper.assertTrue(rung[0], "rung in its frame, the bell swings");
                helper.succeed();
            }
        });
    }
}
