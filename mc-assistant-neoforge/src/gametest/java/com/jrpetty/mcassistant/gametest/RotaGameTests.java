package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.Businesses;
import com.jrpetty.mcassistant.entity.Calendar;
import com.jrpetty.mcassistant.entity.Enterprise;
import com.jrpetty.mcassistant.entity.LifePage;
import com.jrpetty.mcassistant.entity.LifeWeights;
import com.jrpetty.mcassistant.entity.Planner;
import com.jrpetty.mcassistant.entity.PlayerFarms;
import com.jrpetty.mcassistant.entity.PlayerHiring;
import com.jrpetty.mcassistant.entity.PlayerLife;
import com.jrpetty.mcassistant.entity.Rota;
import com.jrpetty.mcassistant.entity.Shifts;
import com.jrpetty.mcassistant.entity.Standing;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.Wealth;
import com.jrpetty.mcassistant.entity.WorkHours;
import com.jrpetty.mcassistant.entity.WorkLife;
import com.jrpetty.mcassistant.entity.WorkShifts;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [rota] Staff rotas (Rota, RotaPage).
 * <ul>
 * <li><b>ro01</b>: a rota set and folk following it: in at its shift's start, out at its end, a split shift's break, a
 *     day off its own, an easygoing hand an hour late; the timesheet clocked; no rota, the employer's hours as before.</li>
 * <li><b>ro02</b>: "Fill the rota for me" covers every opening hour, two days off for everyone.</li>
 * <li><b>ro03</b>: the cover's warnings: "No one on Saturday 14:00–19:00", over-staffing, six days worked.</li>
 * <li><b>ro04</b>: a player hand's shifts on its planner with the toast; a rostered shift missed while online counts as
 *     absence (a talk from the boss), a day off or offline never does.</li>
 * <li><b>ro05</b>: the wage bill scales with the hours rostered, and overtime costs time and a half.</li>
 * <li><b>ro06</b>: a folk firm's rota built by its owner's character, its hands following it, "Who's on today".</li>
 * <li><b>ro07</b>: a day off asked and approved, a swap, the busy week on the fair's week, the deputy filling a gap.</li>
 * <li><b>ro08</b>: the Rota tab: who sets it, the wage bill, the cover, the fill asked twice; a minority partner sees the
 *     edit buttons rested with the reason; no commands.</li>
 * </ul>
 * Each on its own ground, x 14,600,000 to 14,640,000 at z 94,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class RotaGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 94000;
    /** A Tuesday in the third week of a month (not the fair's). */
    private static final long TUESDAY = 28 * 3 + 15;

    private record Town(UUID id, Villages.Village v, BlockPos heart, List<VillageFolkEntity> folk) {}

    private static Town town(GameTestHelper helper, int x, int n) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        Shifts.resetForTests();
        WorkShifts.resetForTests();
        PlayerFarms.resetForTests();
        PlayerHiring.resetForTests();
        Rota.resetForTests();
        level.setDayTime(TUESDAY * 24000L + 2000L);
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = Kit.surface(level, x, Z);
        List<VillageFolkEntity> folk = new ArrayList<>();
        VillageFolkEntity first = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(first != null, "a village");
        first.ensurePersona();
        folk.add(first);
        UUID id = first.ownerId();
        for (int i = 1; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart.south(2 + (i % 5)).east(i / 5), 0.0F);
            helper.assertTrue(f != null && id.equals(f.ownerId()), "folk " + i);
            f.ensurePersona();
            f.setJob(StationTask.NONE);
            folk.add(f);
        }
        Villages.ageForTests(id, Villages.Age.IRON);
        Ledger.addCoins(id, 400);
        WorkHours.forceForTests = true;
        WorkHours.cacheOff = true;
        return new Town(id, Villages.get(id), heart, folk);
    }

    private static void done() {
        WorkHours.forceForTests = false;
        WorkHours.cacheOff = false;
        LifeWeights.resetForTests();
        Rota.resetForTests();
    }

    private static ServerPlayer player(GameTestHelper helper, Town t, BlockPos at) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getAbilities().instabuild = false;
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        p.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.getInventory().clearContent();
        Planner.resetForTests(p.getUUID());
        Standing.stir(t.id(), p.getUUID());
        return p;
    }

    private static Business firm(ServerLevel level, Town t, UUID owner, String name) {
        Business b = Enterprise.foundForTests(level, t.v(), "greengrocer", null);
        b.owners.clear();
        b.owners.add(new Business.Stake(Business.Holder.PLAYER, owner, name, Business.WHOLE));
        b.till = 100000;
        return b;
    }

    private static VillageFolkEntity staff(Town t, Business b, int i) {
        VillageFolkEntity hand = t.folk().get(i);
        hand.setJob(Enterprise.kindOf(t.id(), b).work());
        Enterprise.staffForTests(t.id(), b, hand);
        LifeWeights.setForTests(hand);
        return hand;
    }

    private static void at(ServerLevel level, long day, int hour) {
        level.setDayTime(day * 24000L + Math.floorMod(hour - 6, 24) * 1000L + 10L);
    }

    private static int[] s(int a, int b) {
        return new int[]{ a, b, 0, 0 };
    }

    private static String pageText(CompoundTag page) {
        StringBuilder sb = new StringBuilder(page.getString("title")).append(" | ").append(page.getString("msg"));
        for (Tag tg : page.getList("blocks", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) tg;
            sb.append(" | ").append(c.getString("text")).append(c.getString("l")).append(" ").append(c.getString("s")).append(" ").append(c.getString("r")).append(c.getString("title"));
            for (Tag x : c.getList("lines", Tag.TAG_STRING)) sb.append(" ").append(x.getAsString());
            ListTag b = c.getList("b", Tag.TAG_COMPOUND);
            for (int i = 0; i < b.size(); i++) sb.append(" [").append(b.getCompound(i).getString("l")).append(b.getCompound(i).getBoolean("on") ? "" : " (off: " + b.getCompound(i).getString("why") + ")").append("]");
        }
        return sb.toString();
    }

    // ============================================================ ro01: folk follow the rota

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ro01_folk_follow_rota")
    public static void ro01_folk_follow_rota(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 14600000, 5);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            try {
                Business b = firm(level, t, UUID.randomUUID(), "Absent Owner");
                b.setHours(new Business.Hours(Business.Hours.SIX_DAYS, 7, 19, "test"));
                VillageFolkEntity a = staff(t, b, 1), c = staff(t, b, 2), e = staff(t, b, 3);
                LifeWeights.setForTests(e, LifeWeights.Mark.EASYGOING);
                // No rota: the employer's hours as before.
                at(level, TUESDAY, 10);
                Boolean before10 = WorkHours.shift(a);
                at(level, TUESDAY, 20);
                Boolean before20 = WorkHours.shift(a);
                Rota r = Rota.ensureForTests(level, t.id(), b);
                int tue = Calendar.dow(TUESDAY);
                r.lines.get(a.getUUID()).set(tue, new int[]{ 7, 11, 15, 19 });          // a split shift
                r.lines.get(c.getUUID()).set(tue + 1, s(7, 15));                        // Tuesday off, Wednesday on
                r.lines.get(e.getUUID()).set(tue, s(9, 17));
                at(level, TUESDAY, 8);
                Boolean a8 = WorkHours.shift(a), c8 = WorkHours.shift(c), e8 = WorkHours.shift(e);
                boolean cOff = WorkHours.dayOff(c);
                at(level, TUESDAY, 9);
                Boolean e9 = WorkHours.shift(e);
                at(level, TUESDAY, 10);
                Boolean e10 = WorkHours.shift(e);
                at(level, TUESDAY, 12);
                Boolean a12 = WorkHours.shift(a);
                at(level, TUESDAY, 16);
                Boolean a16 = WorkHours.shift(a);
                at(level, TUESDAY, 19);
                Boolean a19 = WorkHours.shift(a);
                at(level, TUESDAY + 1, 10);
                Boolean c10wed = WorkHours.shift(c);
                List<String> sheet = Rota.timesheet(r, a.getUUID(), 3);
                Kit.log("[VT] ro01 no rota: 10:00 " + before10 + " 20:00 " + before20 + " | a 8:00 " + a8 + " 12:00 " + a12 + " 16:00 " + a16 + " 19:00 " + a19
                    + " | c Tue " + c8 + " dayOff " + cOff + " Wed " + c10wed + " | e (easygoing) 8 " + e8 + " 9 " + e9 + " 10 " + e10 + " | sheet " + sheet);
                helper.assertTrue(before10 == null && Boolean.FALSE.equals(before20), "with no rota, the employer's hours stand");
                helper.assertTrue(Boolean.TRUE.equals(a8) && Boolean.FALSE.equals(a12) && Boolean.TRUE.equals(a16) && Boolean.FALSE.equals(a19),
                    "in for the shift, off in its break, back for the second, gone at its end");
                helper.assertTrue(Boolean.FALSE.equals(c8) && cOff && Boolean.TRUE.equals(c10wed), "a day off its own; at work on its rostered day");
                helper.assertTrue(Boolean.FALSE.equals(e8) && Boolean.FALSE.equals(e9) && Boolean.TRUE.equals(e10), "an easygoing hand an hour late for its shift");
                helper.assertTrue(!sheet.isEmpty() && sheet.get(0).contains("in 0"), "clocked on the timesheet: " + sheet);
            } finally {
                Enterprise.autoForTests(true);
                done();
            }
            helper.succeed();
        });
    }

    // ============================================================ ro02: the fill covers the hours

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ro02_autofill_covers")
    public static void ro02_autofill_covers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 14605000, 5);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            try {
                Business b = firm(level, t, UUID.randomUUID(), "Absent Owner");
                Business.Hours h = new Business.Hours(Business.Hours.SIX_DAYS, 7, 19, "test");
                b.setHours(h);
                for (int i = 1; i <= 3; i++) staff(t, b, i);
                String said = Rota.autoFill(level, t.id(), b, "Owner");
                Rota r = b.rota;
                int[][] cov = Rota.coverage(r);
                int uncovered = 0, most = 0;
                for (int d = 0; d < 7; d++) for (int hr = 7; hr < 19; hr++) if (h.openOn(d) && cov[d][hr] == 0) uncovered++;
                for (Rota.Line l : r.lines.values()) most = Math.max(most, l.daysWorked());
                List<Rota.Warning> ws = Rota.warnings(t.id(), b, r, h);
                StringBuilder grid = new StringBuilder();
                for (Rota.Line l : r.lines.values()) grid.append(l.name).append(" ").append(l.weekHours()).append("h ").append(l.daysWorked()).append("d; ");
                Kit.log("[VT] ro02 " + said + " | uncovered hours " + uncovered + " most days " + most + " | " + grid + " | warnings " + ws);
                helper.assertTrue(r.lines.size() == 3, "everyone on it");
                helper.assertTrue(uncovered == 0, "every opening hour covered: " + uncovered + " uncovered");
                helper.assertTrue(most <= Rota.MOST_DAYS, "two days off for everyone: " + most);
                helper.assertTrue(ws.stream().noneMatch(w -> w.text().startsWith("No one")), "no gaps warned");
                helper.assertTrue(said.contains("Every opening hour is covered"), "and it says so: " + said);
            } finally {
                Enterprise.autoForTests(true);
                done();
            }
            helper.succeed();
        });
    }

    // ============================================================ ro03: gaps warned

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ro03_gap_warnings")
    public static void ro03_gap_warnings(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 14610000, 6);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            try {
                Business b = firm(level, t, UUID.randomUUID(), "Absent Owner");
                Business.Hours h = new Business.Hours(Business.Hours.SIX_DAYS, 7, 19, "test");
                b.setHours(h);
                VillageFolkEntity a = staff(t, b, 1), c = staff(t, b, 2), e = staff(t, b, 3);
                Rota r = Rota.ensureForTests(level, t.id(), b);
                for (int d = 0; d < 5; d++) r.lines.get(a.getUUID()).set(d, s(7, 19));
                r.lines.get(c.getUUID()).set(5, s(7, 14));                       // Saturday till two
                for (int d = 0; d < 6; d++) r.lines.get(e.getUUID()).set(d, s(7, 13)); // six days, and a crowd on Monday with...
                r.lines.get(c.getUUID()).set(0, s(7, 12));
                List<Rota.Warning> ws = Rota.warnings(t.id(), b, r, h);
                List<String> texts = new ArrayList<>();
                for (Rota.Warning w : ws) texts.add(w.text());
                Kit.log("[VT] ro03 " + texts);
                helper.assertTrue(texts.contains("No one on Saturday 14:00–19:00."), "the Saturday afternoon gap, in words");
                helper.assertTrue(texts.stream().anyMatch(x -> x.startsWith("Over-staffed Monday 07:00–12:00")), "three on Monday morning: over-staffed");
                helper.assertTrue(texts.stream().anyMatch(x -> x.contains("works 6 days")), "six days worked: two days off wanted");
                // One on its own, every day: the fill leaves the gaps and says where.
                Business b2 = firm(level, t, UUID.randomUUID(), "Second Owner");
                b2.setHours(new Business.Hours(Business.Hours.ALL_WEEK, 8, 16, "test"));
                staff(t, b2, 4);
                String said = Rota.autoFill(level, t.id(), b2, "Owner");
                List<Rota.Warning> w2 = Rota.warnings(t.id(), b2, b2.rota, b2.hours());
                long gaps = w2.stream().filter(w -> w.text().startsWith("No one on")).count();
                Kit.log("[VT] ro03 alone: " + said + " | " + w2);
                helper.assertTrue(gaps == 2 && said.contains("Still uncovered"), "one hand, seven days: two uncovered days, warned: " + gaps);
            } finally {
                Enterprise.autoForTests(true);
                done();
            }
            helper.succeed();
        });
    }

    // ============================================================ ro04: a player hand

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ro04_player_planner_absence")
    public static void ro04_player_planner_absence(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 14615000, 3);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            try {
                UUID owner = UUID.randomUUID();
                Business b = firm(level, t, owner, "Absent Owner");
                b.setHours(new Business.Hours(Business.Hours.ALL_WEEK, 7, 19, "test"));
                long d0 = TUESDAY + 7;                                            // a week on: six days behind it
                at(level, d0 - 7, 7);
                ServerPlayer p1 = player(helper, t, t.heart()), p2 = player(helper, t, t.heart().east(2));
                WorkLife.hireForTests(level, p1, t.id(), "firm:" + b.id, b.name, "FARM", 8, false);
                WorkLife.hireForTests(level, p2, t.id(), "firm:" + b.id, b.name, "FARM", 8, false);
                Rota r = Rota.ensureForTests(level, t.id(), b);
                for (int d = 0; d < 5; d++) { r.lines.get(p1.getUUID()).set(d, s(8, 16)); r.lines.get(p2.getUUID()).set(d, s(8, 16)); }   // Mon–Fri
                Rota.plannerForTests(level, t.id(), b);
                List<Planner.Entry> plan = Planner.of(p1.getUUID(), d0 - 7);
                long shifts = plan.stream().filter(e -> e.kind().equals("rota") && e.text().startsWith("Shift at " + b.name)).count();
                // The toast ten minutes before the shift.
                level.setDayTime((d0 - 7) * 24000L + 2000L - 100L);
                Rota.tickHandForTests(level, p1);
                boolean toasted = false;
                for (Tag x : WorkLife.jobForTests(p1).getList("days", Tag.TAG_COMPOUND)) if (((CompoundTag) x).getLong("d") == d0 - 7 && ((CompoundTag) x).getBoolean("rt")) toasted = true;
                // Six days: p1 online through each (in town, idle); p2 online only on its days off (offline on its shifts).
                int rostered = 0;
                for (long d = d0 - 6; d < d0; d++) {
                    at(level, d, 11);
                    Rota.tickHandForTests(level, p1);
                    if (Calendar.dow(d) >= 5) Rota.tickHandForTests(level, p2);
                    else rostered++;
                }
                CompoundTag j1 = WorkLife.jobForTests(p1);
                j1.putLong("since", d0 - 8);
                p1.getPersistentData().getCompound("mca_earning").put("job", j1);
                CompoundTag j2 = WorkLife.jobForTests(p2);
                j2.putLong("since", d0 - 8);
                p2.getPersistentData().getCompound("mca_earning").put("job", j2);
                at(level, d0, 7);
                String m1 = WorkLife.morningForTests(level, p1, d0), m2 = WorkLife.morningForTests(level, p2, d0);
                int st1 = WorkLife.jobForTests(p1).getInt("stage"), st2 = WorkLife.jobForTests(p2).getInt("stage");
                String week = Rota.shiftWords(p1, d0);
                Kit.log("[VT] ro04 planner " + shifts + " shifts " + plan + " toast " + toasted + " | rostered days " + rostered + " | p1 '" + m1 + "' stage " + st1
                    + " | p2 (offline on its shifts) '" + m2 + "' stage " + st2 + " | My week: " + week);
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(shifts >= 4, "the coming week's shifts on the planner: " + shifts);
                helper.assertTrue(toasted, "a toast ten minutes before the shift");
                helper.assertTrue(rostered >= 4 && st1 == 1, "rostered shifts missed while online count as absence: a talk from the boss (" + m1 + ")");
                helper.assertTrue(st2 == 0, "offline on its shifts, in town on its days off: no absence");
                helper.assertTrue(week != null && (week.startsWith("Rostered") || week.startsWith("Day off")), "My week reads the rota: " + week);
            } finally {
                Enterprise.autoForTests(true);
                done();
            }
            helper.succeed();
        });
    }

    // ============================================================ ro05: the wage bill

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ro05_wage_bill_scales")
    public static void ro05_wage_bill_scales(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 14620000, 3);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            try {
                Business b = firm(level, t, UUID.randomUUID(), "Absent Owner");
                b.setHours(new Business.Hours(Business.Hours.ALL_WEEK, 6, 22, "test"));
                VillageFolkEntity a = staff(t, b, 1);
                int wage = Math.max(1, Wealth.wage(a));
                Rota r = Rota.ensureForTests(level, t.id(), b);
                Rota.Line l = r.lines.get(a.getUUID());
                for (int d = 0; d < 5; d++) l.set(d, s(8, 12));                      // 20 hours
                int w20 = Rota.scaleWage(t.id(), b, a, 20);
                long bill20 = Rota.bill(t.id(), b, r).coins();
                long till0 = b.till;
                Businesses.paysHand(level, t.v(), a, 20);
                long paid20 = till0 - b.till;
                for (int d = 0; d < 5; d++) l.set(d, s(8, 16));                      // 40 hours
                int w40 = Rota.scaleWage(t.id(), b, a, 20);
                long bill40 = Rota.bill(t.id(), b, r).coins();
                till0 = b.till;
                Businesses.paysHand(level, t.v(), a, 20);
                long paid40 = till0 - b.till;
                for (int d = 0; d < 5; d++) l.set(d, s(8, 20));                      // 60 hours: 12 past the line
                int w60 = Rota.scaleWage(t.id(), b, a, 20);
                Rota.Bill bill60 = Rota.bill(t.id(), b, r);
                Kit.log("[VT] ro05 wage " + wage + " | of 20c a day: 20h " + w20 + ", 40h " + w40 + ", 60h " + w60 + " | paid " + paid20 + " vs " + paid40
                    + " | bill " + bill20 + " / " + bill40 + " / " + bill60.coins() + " (overtime " + bill60.overtime() + ") " + bill60.lines());
                helper.assertTrue(w20 == 10 && w40 == 20, "pay by the hours: half the hours, half the wage");
                helper.assertTrue(w60 == 33, "past the overtime line, time and a half: 48 + 12 × 1.5 = 66 hours' worth: " + w60);
                helper.assertTrue(paid40 > paid20 && paid20 > 0, "the till pays by the hours: " + paid20 + " then " + paid40);
                helper.assertTrue(bill40 > bill20 && bill60.coins() > bill40 && bill60.overtime() > 0, "the weekly bill scales, overtime shown");
            } finally {
                Enterprise.autoForTests(true);
                done();
            }
            helper.succeed();
        });
    }

    // ============================================================ ro06: a folk firm's own

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ro06_folk_firm_auto_rota")
    public static void ro06_folk_firm_auto_rota(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 14625000, 6);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            try {
                Business b = Enterprise.foundForTests(level, t.v(), "greengrocer", null);
                VillageFolkEntity owner = t.folk().get(1);
                b.owners.clear();
                b.owners.add(new Business.Stake(Business.Holder.FOLK, owner.getUUID(), owner.displayNameCap(), Business.WHOLE));
                b.setHours(new Business.Hours(Business.Hours.SIX_DAYS, 7, 19, "set by its owner"));
                staff(t, b, 1);
                LifeWeights.setForTests(owner, LifeWeights.Mark.AMBITIOUS);
                List<VillageFolkEntity> hands = List.of(staff(t, b, 2), staff(t, b, 3), staff(t, b, 4));
                at(level, TUESDAY, 10);
                Rota r = Rota.of(t.id(), b);
                List<String> today = Rota.onToday(t.id(), b, TUESDAY);
                int most = 0;
                for (VillageFolkEntity h : hands) most = Math.max(most, r == null || r.lines.get(h.getUUID()) == null ? 99 : r.lines.get(h.getUUID()).daysWorked());
                // The hands follow it: one off today is off, one on is at work.
                String follow = "";
                boolean ok = true;
                for (VillageFolkEntity h : hands) {
                    int[] sl = r == null ? null : r.slotsOn(h.getUUID(), TUESDAY);
                    if (sl == null) { ok = false; continue; }
                    if (!Rota.works(sl)) { ok &= WorkHours.dayOff(h) && Boolean.FALSE.equals(WorkHours.shift(h)); follow += h.displayNameCap() + " off; "; }
                    else {
                        at(level, TUESDAY, sl[0] + 1);
                        ok &= Boolean.TRUE.equals(WorkHours.shift(h));
                        follow += h.displayNameCap() + " " + Rota.words(sl) + "; ";
                        at(level, TUESDAY, 10);
                    }
                }
                CompoundTag saved = b.saveForTests();
                Business back = Business.loadForTests(saved);
                Kit.log("[VT] ro06 rota " + (r == null ? "none" : r.lines.size() + " lines, set by " + r.setBy + ", auto " + r.auto) + " | most days " + most
                    + " | today " + today + " | " + follow + " | kept " + (back != null && back.rota != null));
                helper.assertTrue(r != null && r.auto && r.lines.size() == 4, "built for its four by its owner");
                helper.assertTrue(r.setBy.contains(owner.displayNameCap()) && r.setBy.contains("ambitious"), "by its owner's character: " + r.setBy);
                helper.assertTrue(r.lines.get(owner.getUUID()).owner && r.lines.get(owner.getUUID()).daysWorked() == 6, "the owner keeps its own hours");
                helper.assertTrue(most <= Rota.MOST_DAYS, "its hands get two days off");
                helper.assertTrue(!today.isEmpty() && today.stream().anyMatch(x -> x.contains(owner.displayNameCap())), "who's on today, for visitors: " + today);
                helper.assertTrue(ok, "its hands follow it: " + follow);
                helper.assertTrue(back != null && back.rota != null && back.rota.lines.size() == 4, "kept with the business");
            } finally {
                Enterprise.autoForTests(true);
                done();
            }
            helper.succeed();
        });
    }

    // ============================================================ ro07: requests, the busy week, the deputy

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ro07_requests_busy_deputy")
    public static void ro07_requests_busy_deputy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 14630000, 6);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            try {
                Business b = firm(level, t, UUID.randomUUID(), "Absent Owner");
                Business.Hours h = new Business.Hours(Business.Hours.SIX_DAYS, 7, 19, "test");
                b.setHours(h);
                VillageFolkEntity a = staff(t, b, 1), c = staff(t, b, 2);
                Rota r = Rota.ensureForTests(level, t.id(), b);
                for (int d = 0; d < 5; d++) { r.lines.get(a.getUUID()).set(d, s(7, 13)); r.lines.get(c.getUUID()).set(d, s(13, 19)); }
                long wed = TUESDAY + 1;
                String asked = Rota.ask(t.id(), b, a.getUUID(), a.displayNameCap(), false, wed, "off", null, "", "the choir");
                int id = r.requests.get(r.requests.size() - 1).id;
                String yes = Rota.answer(level, t.id(), b, id, true, "Owner");
                int[] aWed = r.slotsOn(a.getUUID(), wed), aThu = r.slotsOn(a.getUUID(), wed + 1);
                long thu = wed + 1;
                Rota.ask(t.id(), b, c.getUUID(), c.displayNameCap(), false, thu, "swap", a.getUUID(), a.displayNameCap(), "a swap");
                String swap = Rota.answer(level, t.id(), b, r.requests.get(r.requests.size() - 1).id, true, "Owner");
                int[] aThuAfter = r.slotsOn(a.getUUID(), thu), cThu = r.slotsOn(c.getUUID(), thu);
                // The busy week: saved, used on the fair's week only.
                Rota.saveTemplate(r, "usual");
                r.lines.get(a.getUUID()).set(5, s(7, 19));
                Rota.saveTemplate(r, "busy");
                Rota.loadTemplate(r, "usual", "Owner", TUESDAY);
                long fairSat = TUESDAY + Calendar.daysToFair(TUESDAY), plainSat = TUESDAY + 4;
                int[] onFair = r.slotsOn(a.getUUID(), fairSat), onPlain = r.slotsOn(a.getUUID(), plainSat);
                // The deputy fills a gap with a new hand.
                VillageFolkEntity n = staff(t, b, 3);
                r.lines.get(a.getUUID()).set(5, new int[4]);
                Rota.sync(level, t.id(), b, r);
                String dep = Rota.deputyForTests(level, t.id(), b, c, TUESDAY);
                int nHours = r.lines.get(n.getUUID()).weekHours();
                Kit.log("[VT] ro07 " + asked + " | " + yes + " | a Wed " + Rota.words(aWed) + " Thu " + Rota.words(aThu) + " | " + swap + " a Thu " + Rota.words(aThuAfter)
                    + " c Thu " + Rota.words(cThu) + " | fair Sat " + Rota.words(onFair) + " plain Sat " + Rota.words(onPlain) + " | deputy: " + dep + " (new hand " + nHours + "h)");
                helper.assertTrue(!Rota.works(aWed) && Rota.works(aThu), "the day off approved changes that day only");
                helper.assertTrue(!Rota.works(cThu) && Rota.hours(aThuAfter) == 12, "a swap: the other takes the shift");
                helper.assertTrue(Rota.works(onFair) && !Rota.works(onPlain), "the busy week on the fair's week, the usual otherwise");
                helper.assertTrue(dep != null && nHours > 0, "the deputy rosters the new hand into the gaps: " + dep);
            } finally {
                Enterprise.autoForTests(true);
                done();
            }
            helper.succeed();
        });
    }

    // ============================================================ ro08: the Rota tab

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "ro08_rota_tab")
    public static void ro08_rota_tab(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 14635000, 5);
        helper.runAtTickTime(5, () -> {
            Enterprise.autoForTests(false);
            try {
                ServerPlayer p = player(helper, t, t.heart().east(3));
                ServerPlayer q = player(helper, t, t.heart().east(5));
                Business b = firm(level, t, p.getUUID(), p.getGameProfile().getName());
                b.owners.clear();
                b.owners.add(new Business.Stake(Business.Holder.PLAYER, p.getUUID(), "Owner P", 7000));
                b.owners.add(new Business.Stake(Business.Holder.PLAYER, q.getUUID(), "Partner Q", 3000));
                b.setHours(new Business.Hours(Business.Hours.SIX_DAYS, 7, 19, "test"));
                for (int i = 1; i <= 3; i++) staff(t, b, i);
                PlayerLife.open(p, "firm", LifePage.args("firm", b.id, "tab", "rota"));
                String empty = pageText(PlayerLife.pageForTests(p));
                PlayerLife.act(p, "firm", "rfill", LifePage.args("sure", true));
                String filled = PlayerLife.messageForTests(p);
                String page = pageText(PlayerLife.pageForTests(p));
                PlayerLife.act(p, "firm", "rfill", LifePage.args("sure", false));
                String askTwice = PlayerLife.messageForTests(p);
                UUID first = b.rota.lines.keySet().iterator().next();
                int workDay = 0;
                for (int d = 6; d >= 0; d--) if (Rota.works(b.rota.lines.get(first).day(d))) workDay = d;
                PlayerLife.act(p, "firm", "redit", LifePage.args("w", first.toString()));
                PlayerLife.act(p, "firm", "rpreset", LifePage.args("w", first.toString(), "d", workDay, "p", "morning"));
                String preset = PlayerLife.messageForTests(p);
                String editing = pageText(PlayerLife.pageForTests(p));
                PlayerLife.open(q, "firm", LifePage.args("firm", b.id, "tab", "rota"));
                String partner = pageText(PlayerLife.pageForTests(q));
                PlayerLife.act(q, "firm", "rclear", LifePage.args("sure", true));
                String refused = PlayerLife.messageForTests(q);
                Kit.log("[VT] ro08 empty: " + empty.substring(0, Math.min(500, empty.length())) + " || filled: " + filled + " || ask twice: " + askTwice + " || preset: " + preset
                    + " || partner refused: " + refused);
                Kit.log("[VT] ro08 page: " + page.substring(0, Math.min(1500, page.length())));
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(empty.contains("Fill the rota for me") && empty.contains("Who sets it"), "an empty rota offers the fill");
                helper.assertTrue(filled.startsWith("Filled the rota") && filled.contains("Folk follow it"), "the fill says what happened and what next: " + filled);
                helper.assertTrue(page.contains("Weekly wage bill") && page.contains("Cover by the hour") && page.contains("Who can change it: Owner P"), "the tab's parts");
                helper.assertTrue(askTwice.startsWith("!") && askTwice.contains("Confirm"), "replacing it is asked twice: " + askTwice);
                helper.assertTrue(preset.contains(Calendar.Day.values()[workDay].word) && editing.contains("Editing") && editing.contains("Start −"), "a day set by a preset, the editor shown: " + preset);
                helper.assertTrue(partner.contains("Only the owner or manager sets the rota") && partner.contains("(off: Only the owner or manager sets the rota"),
                    "a minority partner's edit buttons rest, saying why");
                helper.assertTrue(refused.startsWith("!Only the owner or manager"), "and its press is refused with the reason: " + refused);
                helper.assertTrue(!page.contains("/rota") && !page.contains("/firm"), "no commands");
            } finally {
                Enterprise.autoForTests(true);
                done();
            }
            helper.succeed();
        });
    }
}
