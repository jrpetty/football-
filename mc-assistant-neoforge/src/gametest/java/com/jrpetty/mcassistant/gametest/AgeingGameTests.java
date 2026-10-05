package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Birthdays;
import com.jrpetty.mcassistant.entity.Lifespans;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.village.Chronicle;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Growing old slowly (VillageFolkEntity.ageYears, DAYS_A_YEAR): grown folk age a year every fifth
 * day, not two years a day, so a town's founders are not all dead inside a few weeks of play; the
 * founders come to the village eighteen to forty-five, spread out, so they do not grow old together;
 * children grow up in three days as ever. Each runs at once, in the tick it starts (the clock is
 * moved by hand, and put back), on its own ground: x 370000 to 374000, z 50000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class AgeingGameTests {

    private static final String EMPTY = "empty";

    /**
     * Founders raised today are eighteen to forty-five, and fifty days on each is ten years older —
     * not sixty. Each has a season of play in it (a hundred and twenty-five days to four hundred and sixty), and
     * a hundred and fifty folk come into the world grown have years spread over the whole span, on
     * all five days of the year.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ag01_founders_age_slowly")
    public static void ag01_founders_age_slowly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 370000, z = 50000;
        Kit.hold(level, x, z, 32);
        Kit.prepare(level, x, z, 32);
        long start = (level.getDayTime() / 24000L) * 24000L + 6000L;           // a morning
        level.setDayTime(start);
        long day = start / 24000L;
        List<VillageFolkEntity> founders = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x - 8 + i * 4, z + (i % 2) * 4), 0.0F);
            if (f != null) founders.add(f);
        }
        helper.assertTrue(founders.size() >= 3, "a town of founders (" + founders.size() + ")");
        int[] was = new int[founders.size()];
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < founders.size(); i++) {
            VillageFolkEntity f = founders.get(i);
            was[i] = f.ageYears();
            long left = Lifespans.endDay(f) - day;
            seen.append(f.displayNameCap()).append(' ').append(was[i]).append(" (lives to ").append(f.lifespan())
                .append(", ").append(left).append(" days) ");
            helper.assertTrue(was[i] >= 18 && was[i] <= 45, "a founder raised today is eighteen to forty-five: "
                + f.displayNameCap() + " is " + was[i]);
            helper.assertTrue(Birthdays.ageOn(f.bornDay(), day) == was[i], "its birthdays count its years as it does: "
                + Birthdays.ageOn(f.bornDay(), day) + " against " + was[i]);
            // Eighteen to forty-five, living seventy to a hundred (a tenth more with a healer): five days a year.
            helper.assertTrue(left >= 120 && left <= 460, "a founder has a season of play in it, not a few weeks: " + left + " days");
        }
        Kit.log("ag01 the founders on day " + (day + 1) + ": " + seen);
        for (String l : Lifespans.lines(founders.get(0).ownerId(), day)) Kit.log("ag01 " + l);

        // Many come into the world grown: their years spread over eighteen to forty-five, and over all five days of a year.
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        Set<Integer> ages = new HashSet<>(), phases = new HashSet<>();
        for (int i = 0; i < 150; i++) {
            VillageFolkEntity f = McAssistantMod.VILLAGE_FOLK.get().create(level);       // never put in the world
            helper.assertTrue(f != null, "a folk to count");
            int a = f.ageYears();
            lo = Math.min(lo, a);
            hi = Math.max(hi, a);
            ages.add(a);
            phases.add((int) Math.floorMod(day - f.bornDay() - VillageFolkEntity.GROW_DAYS, (long) VillageFolkEntity.DAYS_A_YEAR));
        }
        Kit.log("ag01 a hundred and fifty come grown: " + lo + " to " + hi + ", " + ages.size() + " different ages, on "
            + phases.size() + " days of the year");
        helper.assertTrue(lo >= 18 && hi <= 45, "eighteen to forty-five, every one: " + lo + " to " + hi);
        helper.assertTrue(hi - lo >= 20 && ages.size() >= 20, "and spread out, not all of an age: " + lo + " to " + hi
            + ", " + ages.size() + " different");
        helper.assertTrue(phases.size() == VillageFolkEntity.DAYS_A_YEAR, "their birthdays fall on every day of the year: " + phases);

        // Fifty days on.
        level.setDayTime(start + 50 * 24000L);
        StringBuilder later = new StringBuilder();
        for (int i = 0; i < founders.size(); i++) {
            VillageFolkEntity f = founders.get(i);
            int now = f.ageYears();
            later.append(f.displayNameCap()).append(' ').append(was[i]).append(" -> ").append(now).append("; ");
            helper.assertTrue(now == was[i] + 10, "fifty days on, ten years older (not a hundred): " + f.displayNameCap()
                + " " + was[i] + " -> " + now);
            helper.assertTrue(Birthdays.ageOn(f.bornDay(), day + 50) == now, "and its birthdays agree");
        }
        Kit.log("ag01 fifty days on: " + later);
        level.setDayTime(start);
        helper.succeed();
    }

    /**
     * A folk saved with its years: the same years when it loads. One saved while grown folk aged two
     * years a day (no DaysAYear in its save) keeps the age it had — sixty is sixty, not twenty-five —
     * and ages at the new pace from there; a child saved then is the same child.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ag02_a_save_keeps_its_years")
    public static void ag02_a_save_keeps_its_years(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 371000, z = 50000;
        Kit.hold(level, x, z, 24);
        Kit.prepare(level, x, z, 24);
        long start = (level.getDayTime() / 24000L) * 24000L + 6000L;
        level.setDayTime(start);
        long day = start / 24000L;
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, z), 0.0F);
        helper.assertTrue(f != null, "a founder");
        int age = f.ageYears();
        CompoundTag tag = new CompoundTag();
        f.addAdditionalSaveData(tag);
        tag.remove("VillageCentre");                                           // the copies are not the village's
        helper.assertTrue(tag.getInt("DaysAYear") == VillageFolkEntity.DAYS_A_YEAR, "saved with the pace it ages at: " + tag.getInt("DaysAYear"));
        // (The loaded copies are never put in the world: nothing to take out of it.)
        VillageFolkEntity back = McAssistantMod.VILLAGE_FOLK.get().create(level);
        helper.assertTrue(back != null, "a folk to load into");
        back.readAdditionalSaveData(tag.copy());
        helper.assertTrue(back.bornDay() == f.bornDay() && back.ageYears() == age,
            "the same years after a load: " + back.ageYears() + " against " + age);
        // Saved at two years a day: sixty then (twenty-one days grown), sixty now.
        CompoundTag older = tag.copy();
        older.remove("DaysAYear");
        older.putLong("BornDay", day - VillageFolkEntity.GROW_DAYS - 21);
        VillageFolkEntity old = McAssistantMod.VILLAGE_FOLK.get().create(level);
        old.readAdditionalSaveData(older);
        Kit.log("ag02 " + f.displayNameCap() + " " + age + ", loaded " + back.ageYears() + "; an older save's sixty loads as "
            + old.ageYears() + " (born day " + old.bornDay() + ")");
        helper.assertTrue(old.ageYears() == 60 && old.isOld(), "an older save's sixty-year-old is sixty still: " + old.ageYears());
        // A child saved then: children grow as they did, so it is the same child.
        CompoundTag child = tag.copy();
        child.remove("DaysAYear");
        child.putBoolean("Child", true);
        child.putLong("BornDay", day - 2);
        VillageFolkEntity kid = McAssistantMod.VILLAGE_FOLK.get().create(level);
        kid.readAdditionalSaveData(child);
        helper.assertTrue(kid.isBaby() && kid.bornDay() == day - 2 && kid.ageYears() == 12,
            "a child saved then is the same child: " + kid.isBaby() + ", born " + kid.bornDay() + ", " + kid.ageYears());
        // And from here the old one ages at the new pace.
        level.setDayTime(start + 50 * 24000L);
        int on = old.ageYears();
        level.setDayTime(start);
        Kit.log("ag02 fifty days on the older save's sixty-year-old is " + on);
        helper.assertTrue(on == 70, "fifty days on it is seventy, not a hundred and sixty: " + on);
        helper.succeed();
    }

    /**
     * The years by the day: eighteen the day it is grown and a year more every fifth day; a child
     * six to the day. The round birthdays fifty days apart. A child born today lives two hundred and
     * sixty to four hundred and twenty days. setAgeForTests makes a folk just so old; and a folk dies
     * in its sleep at the end of its years, not a year before (the frailty told first).
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ag03_the_years_by_the_day")
    public static void ag03_the_years_by_the_day(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 372000, z = 50000;
        Kit.hold(level, x, z, 24);
        Kit.prepare(level, x, z, 24);
        helper.assertTrue(VillageFolkEntity.DAYS_A_YEAR == 5, "a year every fifth day");
        helper.assertTrue(VillageFolkEntity.daysOldAt(18) == VillageFolkEntity.GROW_DAYS, "grown at eighteen, three days old");
        for (int y = 18; y <= 130; y++) {
            long d = VillageFolkEntity.daysOldAt(y);
            helper.assertTrue(VillageFolkEntity.grownYears(d) == y, y + " on the day it comes to it, not " + VillageFolkEntity.grownYears(d));
            if (y > 18) helper.assertTrue(VillageFolkEntity.grownYears(d - 1) == y - 1, "and " + (y - 1) + " the day before");
            if (y > 18) helper.assertTrue(d - VillageFolkEntity.daysOldAt(y - 1) == VillageFolkEntity.DAYS_A_YEAR, "five days to a year");
        }
        helper.assertTrue(VillageFolkEntity.childYears(0) == 0 && VillageFolkEntity.childYears(1) == 6
            && VillageFolkEntity.childYears(2) == 12 && VillageFolkEntity.childYears(5) == 17, "a child, six to the day, seventeen at most");
        helper.assertTrue(Birthdays.ageOn(0, 2) == 12 && Birthdays.ageOn(0, 3) == 18 && Birthdays.ageOn(0, 3 + 110) == 40
            && Birthdays.ageOn(0, 3 + 109) == 39, "the birthdays count the same years");
        long shortest = VillageFolkEntity.daysOldAt(70), longest = VillageFolkEntity.daysOldAt(100);
        Kit.log("ag03 one born in the village lives " + shortest + " to " + longest + " days");
        helper.assertTrue(shortest >= 260 && longest <= 420, "a child born today lives two hundred and sixty to four hundred and twenty days: "
            + shortest + " to " + longest);

        long start = (level.getDayTime() / 24000L) * 24000L + 600L;            // first light: the old pass in their sleep then
        level.setDayTime(start);
        long day = start / 24000L;
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, x, z), 0.0F);
        helper.assertTrue(f != null, "a founder");
        UUID village = f.ownerId();
        f.setAgeForTests(40);
        long[] next = Birthdays.next(f, day + 1);
        helper.assertTrue(f.ageYears() == 40 && Birthdays.ageOn(f.bornDay(), day - 1) == 39, "forty today, thirty-nine yesterday: " + f.ageYears());
        helper.assertTrue(next[0] == day + 50 && next[1] == 50, "fifty in fifty days: " + next[1] + " on day " + next[0] + " (today " + day + ")");
        f.setAgeForTests(62);
        helper.assertTrue(f.ageYears() == 62 && f.isOld(), "sixty-two, and old: " + f.ageYears());
        // The end of its years.
        int end = f.lifespan();
        String name = f.displayNameCap();
        f.setAgeForTests(end - 1);
        f.growOldForTests();
        helper.assertTrue(f.isAlive(), "alive a year short of its years (" + (end - 1) + " of " + end + ")");
        f.setAgeForTests(end);
        f.growOldForTests();
        List<String> news = new ArrayList<>();
        for (var e : Chronicle.of(village)) news.add(e.text());
        Kit.log("ag03 " + name + " at " + end + ": alive " + f.isAlive() + "; the news " + news.subList(Math.max(0, news.size() - 3), news.size()));
        helper.assertTrue(!f.isAlive(), "dies at the end of its years");
        helper.assertTrue(news.stream().anyMatch(l -> l.contains(name) && l.contains("very frail")), "the town heard it was very frail first");
        helper.assertTrue(news.stream().anyMatch(l -> l.contains(name) && l.contains("died peacefully in their sleep")), "and that it died in its sleep");
        helper.assertTrue(Ledger.graves(village).stream().anyMatch(g -> g.name().equals(name) && g.cause().equals("of old age")),
            "recorded among the dead, of old age");
        helper.succeed();
    }
}
