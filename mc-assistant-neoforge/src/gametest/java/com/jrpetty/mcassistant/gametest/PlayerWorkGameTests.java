package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.block.StorehouseBlockEntity;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Business;
import com.jrpetty.mcassistant.entity.Businesses;
import com.jrpetty.mcassistant.entity.JobAdverts;
import com.jrpetty.mcassistant.entity.JobCards;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.PayTerms;
import com.jrpetty.mcassistant.entity.PlayerFarms;
import com.jrpetty.mcassistant.entity.PlayerHiring;
import com.jrpetty.mcassistant.entity.PlayerJobs;
import com.jrpetty.mcassistant.entity.PlayerLife;
import com.jrpetty.mcassistant.entity.Provenance;
import com.jrpetty.mcassistant.entity.ShiftRole;
import com.jrpetty.mcassistant.entity.Shifts;
import com.jrpetty.mcassistant.entity.Standing;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WorkLife;
import com.jrpetty.mcassistant.entity.WorkShifts;
import com.jrpetty.mcassistant.entity.WorkWay;
import com.jrpetty.mcassistant.village.Ledger;
import com.jrpetty.mcassistant.village.WorkMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * [wpb] The player at work: every trade a job with a card, both ways of pay, fair targets, the boss, provenance, the
 * way to the work, the new shifts, the player's own farm as a business, and players hiring players.
 * <ul>
 * <li><b>pw01</b>: every folk trade (and the work that isn't one) has a card, a shift a player works it at, a day in steps,
 *     a target, and the advert's page builds with both pay boxes and "What the job involves".</li>
 * <li><b>pw02</b>: each trade's target is reachable by a stand-in worker at par in a normal shift, not met by doing nothing,
 *     and comes down for no work and rain.</li>
 * <li><b>pw03</b>: commission rates, derived from the town's prices, make a three-star day 0.9 to 1.3 times the going wage.</li>
 * <li><b>pw04</b>: an advert applied for on commission: applied, shortlisted, an interview (a rival applied), offered, accepted;
 *     hired on commission at a derived rate; the opening filled by the player.</li>
 * <li><b>pw05</b>: a lone applicant goes straight to an offer; a weak interview against a strong rival is turned down, with why.</li>
 * <li><b>pw06</b>: provenance: only wheat from the job's own shifts counts at the hand-in; bought and another's are refused, with why.</li>
 * <li><b>pw07</b>: taking crops from the town's fields in a folk's sight is a witnessed crime, and the take is not marked.</li>
 * <li><b>pw08</b>: salaried, a week of poor days brings a talk, then a warning, then dismissal with a reason and pay in lieu.</li>
 * <li><b>pw09</b>: on commission, low output is never a warning; misconduct twice is a dismissal.</li>
 * <li><b>pw10</b>: offline days are never absence: a day worked before is paid on the next morning; a long absence ends the
 *     job politely.</li>
 * <li><b>pw11</b>: a good week with the skill for it brings promotion.</li>
 * <li><b>pw12</b>: once hired the way to work is sent (workplace, beam, task), and the arrow can be switched off.</li>
 * <li><b>pw13</b>: the miner's shift: a face marked, cut, counted (ore three), the stone to the stores; its par within the card's.</li>
 * <li><b>pw14</b>: the fisher's shift: the water marked, a rod lent, three fish a task.</li>
 * <li><b>pw15</b>: the workshop hand's shift: makings collected with a deposit, the batch brought in, paid by the piece.</li>
 * <li><b>pw16</b>: a player's farm registered: price before selling, sold to the stores into its till, its books, land rates,
 *     and the profits tax on its week.</li>
 * <li><b>pw19</b>: a player hires a player: the advert on the board, applied for, offered, accepted, paid from the farm's till.</li>
 * <li><b>pw20</b>: no commands: the jobs pages say no "/village" and the old Quit still works.</li>
 * <li><b>pw17</b>: a partnership: a share sold player to player, profit split 60/40, the majority decides.</li>
 * <li><b>pw18</b>: a contract with escrow: bond and price held, goods checked and handed over, paid out; a lapsed one refunds
 *     the buyer with the penalty.</li>
 * <li><b>pw21</b>: a whole firm sold: listed, an offer, accepted, the price to the seller, the firm the buyer's.</li>
 * <li><b>pw22</b>: the owner's tools: a manager keeps it running while the owner is away (else it pauses), a hand let go
 *     after a confirm with severance, a ware's own markup, a firm rescued from the court.</li>
 * </ul>
 * Each on its own ground, x 8,710,000 to 8,800,000 at z 71,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class PlayerWorkGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 71000;

    private record Town(UUID id, Villages.Village v, BlockPos heart, StorehouseBlockEntity store, List<VillageFolkEntity> folk) {}

    private static Town town(GameTestHelper helper, int x, int n) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        Shifts.resetForTests();
        WorkShifts.resetForTests();
        PlayerFarms.resetForTests();
        PlayerHiring.resetForTests();
        level.setDayTime(2000L);
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
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
        StorehouseBlockEntity store = storehouse(helper, level, heart, 8, 8);
        for (BlockPos p : Villages.storeChests(level, id)) {
            if (level.getBlockEntity(p) instanceof ChestBlockEntity c && c != (Object) store) {
                c.clearContent();
                level.removeBlock(p, false);
            }
        }
        Villages.forgetStores(id);
        Villages.ageForTests(id, Villages.Age.IRON);
        Ledger.addCoins(id, 400);
        return new Town(id, Villages.get(id), heart, store, folk);
    }

    private static StorehouseBlockEntity storehouse(GameTestHelper helper, ServerLevel level, BlockPos heart, int dx, int dz) {
        BlockPos origin = Kit.surface(level, heart.getX() + dx, heart.getZ() + dz);
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-1, 0, -1), origin.offset(3, 4, 3))) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
        net.minecraft.world.level.block.state.BlockState unit = StorehouseBlock.loose();
        StorehouseBlock.hintFront(Direction.SOUTH);
        try {
            for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) level.setBlock(origin.offset(x, y, z), unit, 3);
        } finally {
            StorehouseBlock.hintFront(null);
        }
        BlockPos door = origin.offset(StorehouseBlock.doorOffset(Direction.SOUTH));
        helper.assertTrue(level.getBlockEntity(door) instanceof StorehouseBlockEntity s && s.isStore(), "a storehouse stands");
        return (StorehouseBlockEntity) level.getBlockEntity(door);
    }

    private static void fill(UUID id, StorehouseBlockEntity store, ItemStack... goods) {
        for (ItemStack g : goods) {
            ItemStack left = g.copy();
            while (!left.isEmpty()) {
                int slot = -1;
                for (int i = 0; i < store.getContainerSize(); i++) if (store.getItem(i).isEmpty()) { slot = i; break; }
                if (slot < 0) break;
                store.setItem(slot, left.split(left.getMaxStackSize()));
            }
        }
        store.setChanged();
        Villages.forgetStock();
        Villages.forgetStores(id);
        com.jrpetty.mcassistant.entity.Budget.forget(id);
    }

    private static ServerPlayer player(GameTestHelper helper, Town t, BlockPos at, int coins) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.getAbilities().instabuild = false;
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        p.teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.getInventory().clearContent();
        if (coins > 0) p.getInventory().add(new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
        for (VillageFolkEntity f : t.folk()) f.persona().feelFor(p.getUUID(), p.getGameProfile().getName(), 60);
        Standing.stir(t.id(), p.getUUID());
        return p;
    }

    private static int carried(ServerPlayer p, Predicate<ItemStack> what) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && what.test(s)) n += s.getCount();
        return n;
    }

    private static int coins(ServerPlayer p) {
        return carried(p, s -> s.is(McAssistantMod.VILLAGE_COIN.get()));
    }

    private static long day(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }

    private static boolean pageHas(CompoundTag page, String t, String text) {
        ListTag bl = page.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < bl.size(); i++) {
            CompoundTag b = bl.getCompound(i);
            if (!b.getString("t").equals(t)) continue;
            String s = b.getString("text") + b.getString("title") + b.getString("l");
            if (s.contains(text)) return true;
        }
        return false;
    }

    // ============================================================ the cards

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pw01_cards")
    public static void pw01_cards(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8710000, 3);
        helper.runAtTickTime(5, () -> {
            List<String> missing = new ArrayList<>();
            for (StationTask st : StationTask.values()) if (st != StationTask.NONE && JobCards.of(st.name()) == null) missing.add(st.name());
            for (String extra : new String[]{ "BUILD", "BAKE", "WAITER", "WAGON", "COURIER", "CLERK", "TEACHER", "QUARRY" }) if (JobCards.of(extra) == null) missing.add(extra);
            Kit.log("[VT] pw01 cards " + JobCards.all().size() + ", missing " + missing);
            helper.assertTrue(missing.isEmpty(), "every trade has a card: missing " + missing);
            for (JobCards.Card c : JobCards.all()) {
                helper.assertTrue(c.shift() != null && c.day().size() >= 3 && c.target() >= 1 && !c.role().isEmpty() && !c.reportTo().isEmpty(),
                    "a whole card for " + c.key());
            }
            for (ShiftRole r : new ShiftRole[]{ ShiftRole.BUILD, ShiftRole.BAKE, ShiftRole.WAITER, ShiftRole.WAGON, ShiftRole.MINE, ShiftRole.FISH, ShiftRole.CRAFT }) {
                helper.assertTrue(JobCards.forRole(r) != null, "the shift " + r + " is a job's");
            }
            ServerPlayer p = player(helper, t, t.heart(), 0);
            int id = PlayerJobs.openingForTests(t.id(), "town:FARM", "The town's fields", "FARM", 8, day(level));
            PlayerLife.open(p, "jobs", null);
            PlayerLife.act(p, "jobs", "details", com.jrpetty.mcassistant.entity.LifePage.args("ad", "v:" + id));
            CompoundTag page = PlayerLife.pageForTests(p);
            boolean sal = pageHas(page, "box", "How you're paid: on salary"), com = pageHas(page, "box", "How you're paid: on commission"),
                involves = pageHas(page, "h", "What the job involves"), where = pageHas(page, "h", "Where"), rules = pageHas(page, "h", "What earns a warning");
            Kit.log("[VT] pw01 advert page: salary " + sal + ", commission " + com + ", involves " + involves + ", where " + where + ", rules " + rules);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(sal && com && involves && where && rules, "the advert's page has the pay boxes, where, what it involves, and the rules");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "pw02_targets")
    public static void pw02_targets(GameTestHelper helper) {
        helper.runAtTickTime(2, () -> {
            List<String> report = new ArrayList<>();
            for (JobCards.Card c : JobCards.all()) {
                JobCards.Measure m = c.measure();
                // A stand-in worker: each task at its par time (slower than the competent hand's nine tenths), a pause to
                // find the next, for a shift's length.
                double units = 0;
                long tLeft = WorkMath.SHIFT;
                while (tLeft >= m.taskTicks()) { tLeft -= m.taskTicks() + 100; units += m.perTask(); }
                int target = c.target();
                int stars = WorkMath.dayStars(units, target);
                int idle = WorkMath.dayStars(0, target);
                report.add(c.key() + ": target " + target + ", stand-in " + (int) units + " (" + stars + "★)");
                helper.assertTrue(units >= target, c.key() + ": a stand-in at par makes the target (" + units + " of " + target + ")");
                helper.assertTrue(stars >= 3 && idle == 0 && target > 0, c.key() + ": three stars for honest work, none for doing nothing");
                helper.assertTrue(units < target * WorkMath.STAR_AT[3] * 1.6, c.key() + ": not trivially easy");
                helper.assertTrue(WorkMath.scaled(target, 0, 0, 0.5, false) < target || target == 1, c.key() + ": no work to be had lowers the target");
                if (m.outdoors()) helper.assertTrue(WorkMath.scaled(target, 0, 0, 0, true) <= target, c.key() + ": rain lowers it");
                helper.assertTrue(WorkMath.scaled(target, 0, 0, 1.0, false) == 0, c.key() + ": a shift with no work at all asks nothing");
            }
            Kit.log("[VT] pw02 " + String.join("; ", report));
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pw03_commission")
    public static void pw03_commission(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8714000, 3);
        helper.runAtTickTime(5, () -> {
            List<String> report = new ArrayList<>();
            for (JobCards.Card c : JobCards.all()) {
                int wage = PayTerms.goingWage(level, t.id(), c, 0);
                PayTerms.Terms com = PayTerms.commission(level, t.id(), c, wage, c.target(), null, "the employer");
                double day3 = com.base() + com.rate() * com.target();
                report.add(c.key() + " wage " + wage + " rate " + com.rateWords() + " day3 " + String.format(java.util.Locale.ROOT, "%.1f", day3) + (com.capped() ? " capped" : ""));
                helper.assertTrue(day3 >= wage * WorkMath.DAY_LO - 0.01 && day3 <= wage * WorkMath.DAY_HI + 0.01,
                    c.key() + ": a three-star commissioned day within 0.9-1.3x the going wage (" + day3 + " of " + wage + ")");
                helper.assertTrue(!com.why().isEmpty() && com.rate() > 0, c.key() + ": a rate, with why");
            }
            Kit.log("[VT] pw03 " + String.join("; ", report));
            helper.succeed();
        });
    }

    // ============================================================ the adverts

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "pw04_apply")
    public static void pw04_apply(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8718000, 3);
        helper.runAtTickTime(5, () -> {
            ServerPlayer p = player(helper, t, t.heart(), 0);
            int id = PlayerJobs.openingForTests(t.id(), "town:FARM", "The town's fields", "FARM", 8, day(level));
            PlayerJobs.rivalForTests(t.id(), id, t.folk().get(1), 2, day(level));
            String key = "v:" + id;
            helper.assertTrue(JobAdverts.advertKeysForTests(level, t.v(), p).contains(key), "the advert is on the board");
            String said = JobAdverts.applyForTests(level, p, key, true);
            String s1 = JobAdverts.statusForTests(p, key);
            JobAdverts.ageAppsForTests(p, 700);
            JobAdverts.tickForTests(level);
            String s2 = JobAdverts.statusForTests(p, key);
            JobAdverts.ageAppsForTests(p, 300);
            JobAdverts.tickForTests(level);
            String s3 = JobAdverts.statusForTests(p, key);
            String iv = JobAdverts.interviewForTests(level, p, key, 6);
            String s4 = JobAdverts.statusForTests(p, key);
            String acc = JobAdverts.acceptForTests(level, p, key, true);
            CompoundTag j = WorkLife.jobForTests(p);
            String vac = PlayerJobs.vacancyForTests(t.id(), id);
            Kit.log("[VT] pw04 " + said + " | " + s1 + " -> " + s2 + " -> " + s3 + " | " + iv + " -> " + s4 + " | " + acc + " | job " + j + " | vacancy " + vac);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(s1.equals("APPLIED") && s2.equals("SHORTLISTED") && s3.equals("INTERVIEW") && s4.equals("OFFERED"), "applied, shortlisted, interview, offered");
            helper.assertTrue(PlayerJobs.employed(p) && "commission".equals(j.getString("pay")) && j.getDouble("each") > 0, "hired on commission at a derived rate");
            helper.assertTrue(vac.startsWith("FILLED") && vac.contains(p.getGameProfile().getName()), "the opening filled, by the player");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "pw05_offer_or_not")
    public static void pw05_offer_or_not(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8722000, 3);
        helper.runAtTickTime(5, () -> {
            ServerPlayer p = player(helper, t, t.heart(), 0);
            int lone = PlayerJobs.openingForTests(t.id(), "town:HAUL", "The storehouse", "HAUL", 6, day(level));
            JobAdverts.applyForTests(level, p, "v:" + lone, false);
            JobAdverts.ageAppsForTests(p, 700);
            JobAdverts.tickForTests(level);
            JobAdverts.ageAppsForTests(p, 300);
            JobAdverts.tickForTests(level);
            String a = JobAdverts.statusForTests(p, "v:" + lone);
            int hard = PlayerJobs.openingForTests(t.id(), "town:SMELT", "The smithy", "SMELT", 9, day(level));
            PlayerJobs.rivalForTests(t.id(), hard, t.folk().get(2), 25, day(level));
            JobAdverts.applyForTests(level, p, "v:" + hard, false);
            JobAdverts.ageAppsForTests(p, 700);
            JobAdverts.tickForTests(level);
            JobAdverts.ageAppsForTests(p, 300);
            JobAdverts.tickForTests(level);
            String iv = JobAdverts.interviewForTests(level, p, "v:" + hard, 0);
            String b = JobAdverts.statusForTests(p, "v:" + hard);
            Kit.log("[VT] pw05 lone: " + a + "; strong rival, weak interview: " + iv + " -> " + b);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(a.equals("OFFERED"), "a lone applicant is offered the job straight after the shortlist");
            helper.assertTrue(b.equals("TURNED_DOWN") && iv.contains("took"), "turned down with why: " + iv);
            helper.succeed();
        });
    }

    // ============================================================ provenance

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pw06_provenance")
    public static void pw06_provenance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8726000, 3);
        helper.runAtTickTime(5, () -> {
            ServerPlayer p = player(helper, t, t.heart(), 0);
            WorkLife.hireForTests(level, p, t.id(), "town:FARM", "The town's fields", "FARM", 8, true);
            ItemStack mine = new ItemStack(Items.WHEAT, 10);
            Provenance.tag(mine, Provenance.FIELD, WorkLife.employerKey(p), p, "the town's fields");
            ItemStack bought = new ItemStack(Items.WHEAT, 5);
            ItemStack other = new ItemStack(Items.WHEAT, 3);
            Provenance.tag(other, Provenance.FIELD, "firm:somebody-else", p, "another farm");
            p.getInventory().add(mine);
            p.getInventory().add(bought);
            p.getInventory().add(other);
            int had = coins(p);
            String r = WorkLife.handInForTests(level, p);
            int left = carried(p, s -> s.is(Items.WHEAT));
            CompoundTag j = WorkLife.jobForTests(p);
            Kit.log("[VT] pw06 hand-in " + r + "; wheat left " + left + "; coins " + had + " -> " + coins(p) + "; handed " + j.getInt("handed") + "; carry " + j.getDouble("carry"));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(r.startsWith("10|"), "only the job's own ten count: " + r);
            helper.assertTrue(r.contains("bought, found or taken") && r.contains("another employer"), "the rest refused, with why: " + r);
            helper.assertTrue(left == 8, "the refused wheat stays with the player");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pw07_theft")
    public static void pw07_theft(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8730000, 3);
        helper.runAtTickTime(5, () -> {
            BlockPos farm = t.heart().east(12);
            Ledger.built(t.id(), "farm", farm, Direction.NORTH);
            BlockPos plot = Kit.surface(level, farm.getX() + 1, farm.getZ() + 1);
            level.setBlock(plot.below(), Blocks.FARMLAND.defaultBlockState(), 3);
            level.setBlock(plot, ((CropBlock) Blocks.WHEAT).getStateForAge(7), 3);
            VillageFolkEntity watcher = t.folk().get(1);
            watcher.teleportTo(plot.getX() + 3.5, plot.getY(), plot.getZ() + 0.5);
            watcher.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, net.minecraft.world.phys.Vec3.atCenterOf(plot));
            ServerPlayer p = player(helper, t, plot.west(2), 0);
            int before = Provenance.theftsForTests().size();
            String owner = Provenance.ownerForTests(level, plot);
            boolean broke = p.gameMode.destroyBlock(plot);
            List<String> th = Provenance.theftsForTests();
            Kit.log("[VT] pw07 owner " + owner + ", broke " + broke + ", thefts " + th + " trace " + Provenance.traceForTests());
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(th.size() > before && th.get(th.size() - 1).contains("the town's fields"), "taking the town's crop is a theft");
            helper.assertTrue(th.get(th.size() - 1).endsWith("|true"), "and it was witnessed (the folk saw it)");
            helper.succeed();
        });
    }

    // ============================================================ the boss

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pw08_warned_then_fired")
    public static void pw08_warned_then_fired(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8734000, 3);
        helper.runAtTickTime(5, () -> {
            ServerPlayer p = player(helper, t, t.heart(), 0);
            level.setDayTime(20 * 24000L + 2000L);
            long d0 = day(level) - 8;
            WorkLife.hireForTests(level, p, t.id(), "town:FARM", "The town's fields", "FARM", 8, false);
            CompoundTag j0 = WorkLife.jobForTests(p);
            for (long d = d0; d < day(level); d++) WorkLife.dayForTests(p, d, 0, 7, true);
            backdate(p, d0);
            String m1 = WorkLife.morningForTests(level, p, day(level));
            int stage1 = WorkLife.jobForTests(p).getInt("stage");
            level.setDayTime(level.getDayTime() + 2 * 24000L);
            for (long d = day(level) - 2; d < day(level); d++) WorkLife.dayForTests(p, d, 0, 7, true);
            String m2 = WorkLife.morningForTests(level, p, day(level));
            int stage2 = WorkLife.jobForTests(p).getInt("stage");
            level.setDayTime(level.getDayTime() + 2 * 24000L);
            for (long d = day(level) - 2; d < day(level); d++) WorkLife.dayForTests(p, d, 0, 7, true);
            int had = coins(p);
            String m3 = WorkLife.morningForTests(level, p, day(level));
            Kit.log("[VT] pw08 " + m1 + " (stage " + stage1 + ") | " + m2 + " (stage " + stage2 + ") | " + m3 + " | employed " + PlayerJobs.employed(p) + " coins " + had + " -> " + coins(p));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(stage1 == 1, "first a talk, not a warning");
            helper.assertTrue(stage2 == 2 && m2.contains("warning"), "then a formal warning with the reason: " + m2);
            helper.assertTrue(!PlayerJobs.employed(p) && m3.contains("Let go"), "then let go, with why: " + m3);
            helper.assertTrue(coins(p) > had, "a day's pay in lieu of notice");
            helper.succeed();
        });
    }

    /** The job written as if taken on this day. */
    private static void backdate(ServerPlayer p, long since) {
        CompoundTag root = p.getPersistentData().getCompound("mca_earning");
        CompoundTag j = root.getCompound("job");
        j.putLong("since", since);
        j.putLong("reviewed", since);
        root.put("job", j);
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pw09_commission_misconduct")
    public static void pw09_commission_misconduct(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8738000, 3);
        helper.runAtTickTime(5, () -> {
            ServerPlayer p = player(helper, t, t.heart(), 0);
            level.setDayTime(20 * 24000L + 2000L);
            WorkLife.hireForTests(level, p, t.id(), "town:FARM", "The town's fields", "FARM", 8, true);
            backdate(p, day(level) - 8);
            for (long d = day(level) - 8; d < day(level); d++) WorkLife.dayForTests(p, d, 1, 7, true);
            String m1 = WorkLife.morningForTests(level, p, day(level));
            int stage = WorkLife.jobForTests(p).getInt("stage"), strikes = WorkLife.jobForTests(p).getInt("strikes");
            p.teleportTo(p.getX(), p.getY(), p.getZ());
            WorkLife.misconductForTests(level, p, "walking off a shift");
            boolean still = PlayerJobs.employed(p);
            WorkLife.misconductForTests(level, p, "walking off a shift again");
            Kit.log("[VT] pw09 low output: '" + m1 + "' stage " + stage + " strikes " + strikes + "; after one misconduct employed " + still + "; after two " + PlayerJobs.employed(p));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(stage == 0 && strikes == 0, "low output on commission is no warning");
            helper.assertTrue(still && !PlayerJobs.employed(p), "a warning for misconduct, then dismissal");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pw10_offline")
    public static void pw10_offline(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8742000, 3);
        helper.runAtTickTime(5, () -> {
            ServerPlayer p = player(helper, t, t.heart(), 0);
            level.setDayTime(30 * 24000L + 2000L);
            WorkLife.hireForTests(level, p, t.id(), "town:FARM", "The town's fields", "FARM", 8, false);
            backdate(p, day(level) - 9);
            WorkLife.dayForTests(p, day(level) - 8, 9, 7, true);              // worked, then away a week
            int had = coins(p);
            String m = WorkLife.morningForTests(level, p, day(level));
            int paid = coins(p) - had;
            int stage = WorkLife.jobForTests(p).getInt("stage");
            WorkLife.lastSeenForTests(p, System.currentTimeMillis() - 20L * 24 * 3600 * 1000);
            WorkLife.loginForTests(p);
            String hist = p.getPersistentData().getCompound("mca_earning").getList("jobHistory", Tag.TAG_COMPOUND).toString();
            Kit.log("[VT] pw10 '" + m + "' paid " + paid + " stage " + stage + "; after a long absence employed " + PlayerJobs.employed(p) + " " + hist);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(paid == 8, "the day worked before going offline is paid (held pay): " + paid);
            helper.assertTrue(stage == 0, "a week offline is not absence: no talk, no warning");
            helper.assertTrue(!PlayerJobs.employed(p) && hist.contains("no black mark"), "a long absence ends the job politely");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pw11_promotion")
    public static void pw11_promotion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8746000, 3);
        helper.runAtTickTime(5, () -> {
            ServerPlayer p = player(helper, t, t.heart(), 0);
            level.setDayTime(30 * 24000L + 2000L);
            Shifts.xpForTests(p, ShiftRole.FARM, 150);
            WorkLife.hireForTests(level, p, t.id(), "town:FARM", "The town's fields", "FARM", 8, false);
            backdate(p, day(level) - 9);
            for (long d = day(level) - 6; d < day(level); d++) WorkLife.dayForTests(p, d, 12, 7, true);
            String m = WorkLife.morningForTests(level, p, day(level));
            CompoundTag j = WorkLife.jobForTests(p);
            Kit.log("[VT] pw11 '" + m + "' rank " + j.getInt("rank") + " " + WorkLife.rankTitle(j));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(j.getInt("rank") == 1 && m.contains("Promoted"), "promoted after a good week: " + m);
            helper.succeed();
        });
    }

    // ============================================================ the way to work

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pw12_way")
    public static void pw12_way(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8750000, 3);
        helper.runAtTickTime(5, () -> {
            BlockPos farm = t.heart().east(14).south(14);
            Ledger.built(t.id(), "farm", farm, Direction.NORTH);
            BlockPos plot = Kit.surface(level, farm.getX() + 2, farm.getZ() + 2);
            level.setBlock(plot.below(), Blocks.FARMLAND.defaultBlockState(), 3);
            level.setBlock(plot, ((CropBlock) Blocks.WHEAT).getStateForAge(7), 3);
            ServerPlayer p = player(helper, t, plot.north(3), 0);
            WorkLife.hireForTests(level, p, t.id(), "town:FARM", "The town's fields", "FARM", 8, false);
            CompoundTag w1 = WorkWay.wayForTests(p);
            Shifts.secondForTests(level, p);
            CompoundTag w2 = WorkWay.wayForTests(p);
            PlayerLife.open(p, "jobs", null);
            PlayerLife.act(p, "jobs", "arrow", new CompoundTag());
            CompoundTag w3 = WorkWay.wayForTests(p);
            Kit.log("[VT] pw12 hired: " + w1 + " | on shift: " + w2 + " | arrow off: " + w3);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(w1.getBoolean("on") && w1.contains("main") && w1.getCompound("main").getIntArray("at").length == 3, "the workplace marked once hired");
            helper.assertTrue(w2.contains("task") && w2.getCompound("task").getIntArray("at").length == 3, "the task marked on a shift");
            helper.assertTrue(!w3.getBoolean("on"), "the arrow switched off from the page");
            helper.succeed();
        });
    }

    // ============================================================ the new shifts

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pw13_mine")
    public static void pw13_mine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8754000, 3);
        helper.runAtTickTime(5, () -> {
            BlockPos face = t.heart().west(10).above(1);                        // a flat test world: the face built on the ground
            for (BlockPos q : BlockPos.betweenClosed(face.offset(-3, -1, -3), face.offset(3, 3, 3))) level.setBlock(q, Blocks.STONE.defaultBlockState(), 3);
            for (BlockPos q : BlockPos.betweenClosed(face.offset(-1, 0, -1), face.offset(1, 1, 1))) level.setBlock(q, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(face.offset(2, 0, 0), Blocks.IRON_ORE.defaultBlockState(), 3);
            WorkShifts.faceForTests(t.id(), face);
            ServerPlayer p = player(helper, t, face, 0);
            String took = Shifts.take(p, t.id(), ShiftRole.MINE.id, false);
            Shifts.secondForTests(level, p);
            String task = Shifts.taskForTests(p);
            int stores0 = Market.stock(level, t.id(), s -> s.is(Items.COBBLESTONE));
            int cut = WorkShifts.cutFaceForTests(level, p);
            int[] st = Shifts.stateForTests(p);
            int stores1 = Market.stock(level, t.id(), s -> s.is(Items.COBBLESTONE));
            Kit.log("[VT] pw13 took '" + took + "' task '" + task + "' cut " + cut + " state " + java.util.Arrays.toString(st) + " cobble " + stores0 + " -> " + stores1);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(took.isEmpty() && task != null && task.startsWith("Cut"), "a face to cut: " + took + " / " + task);
            helper.assertTrue(cut == WorkShifts.FACE_CELLS && st != null && st[0] == 1, "the face cut: one task done");
            helper.assertTrue(stores1 > stores0, "the stone up to the stores on a salary");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pw14_fish")
    public static void pw14_fish(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8758000, 3);
        helper.runAtTickTime(5, () -> {
            Kit.pond(level, t.heart().getX() - 12, t.heart().getZ() - 12, 3);
            ServerPlayer p = player(helper, t, t.heart().west(8).north(8), 0);
            String took = Shifts.take(p, t.id(), ShiftRole.FISH.id, false);
            Shifts.secondForTests(level, p);
            String task = Shifts.taskForTests(p);
            int rods = carried(p, s -> s.is(Items.FISHING_ROD));
            WorkShifts.catchForTests(level, p, 3);
            int[] st = Shifts.stateForTests(p);
            Shifts.endForTests(level, p);
            int rodsAfter = carried(p, s -> s.is(Items.FISHING_ROD));
            Kit.log("[VT] pw14 took '" + took + "' task '" + task + "' rod lent " + rods + " state " + java.util.Arrays.toString(st) + " rod after " + rodsAfter);
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(took.isEmpty() && task != null && task.startsWith("Catch"), "water to fish: " + task);
            helper.assertTrue(rods == 1 && rodsAfter == 0, "a rod lent for the shift and taken back");
            helper.assertTrue(st != null && st[0] == 1, "three fish a task");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pw15_craft")
    public static void pw15_craft(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8762000, 3);
        helper.runAtTickTime(5, () -> {
            fill(t.id(), t.store(), new ItemStack(Items.OAK_PLANKS, 64));
            ServerPlayer p = player(helper, t, t.store().getBlockPos().south(2), 20);
            String took = Shifts.take(p, t.id(), ShiftRole.CRAFT.id, false);
            Shifts.secondForTests(level, p);
            String task = Shifts.taskForTests(p);
            boolean loaded = Shifts.useForTests(level, p, t.store().getBlockPos());
            int planks = carried(p, s -> s.is(Items.OAK_PLANKS));
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(Items.OAK_PLANKS)) p.getInventory().setItem(i, ItemStack.EMPTY);
            p.getInventory().add(new ItemStack(Items.STICK, 16));                 // made at a crafting table
            boolean in = Shifts.useForTests(level, p, t.store().getBlockPos());
            int[] st = Shifts.stateForTests(p);
            Kit.log("[VT] pw15 took '" + took + "' task '" + task + "' loaded " + loaded + " planks " + planks + " in " + in + " state " + java.util.Arrays.toString(st));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(took.isEmpty() && task != null && task.startsWith("Collect"), "a batch to make: " + task);
            helper.assertTrue(loaded && planks == 8, "the makings collected at the storehouse");
            helper.assertTrue(in && st != null && st[0] == 1, "the batch brought in: the task done");
            helper.succeed();
        });
    }

    // ============================================================ the farm business

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pw16_farm")
    public static void pw16_farm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8766000, 3);
        helper.runAtTickTime(5, () -> {
            com.jrpetty.mcassistant.entity.MarketMaker.liveForTests(true);
            com.jrpetty.mcassistant.entity.MarketMaker.openForTests(level, t.v());
            try {
                BlockPos at = Kit.surface(level, t.heart().getX() + 30, t.heart().getZ() - 30);
                for (int dx = 0; dx < 5; dx++) for (int dz = 0; dz < 5; dz++) {
                    level.setBlock(at.offset(dx, -1, dz), Blocks.FARMLAND.defaultBlockState(), 3);
                    level.setBlock(at.offset(dx, 0, dz), ((CropBlock) Blocks.WHEAT).getStateForAge(7), 3);
                }
                ServerPlayer p = player(helper, t, at.offset(2, 0, 2), 30);
                String reg = PlayerFarms.registerForTests(level, p, "farm", "Test Farm");
                List<PlayerFarms.Farm> farms = PlayerFarms.allForTests();
                helper.assertTrue(farms.size() == 1, "registered: " + reg);
                PlayerFarms.Farm f = farms.get(0);
                ItemStack grown = new ItemStack(Items.WHEAT, 32);
                Provenance.tag(grown, Provenance.OWN, "farm:" + f.id, p, f.name);
                p.getInventory().add(grown);
                p.getInventory().add(new ItemStack(Items.WHEAT, 10));          // bought: not the farm's
                String key = com.jrpetty.mcassistant.entity.Stockroom.key(new ItemStack(Items.WHEAT));
                List<String> q = PlayerFarms.quotesForTests(level, p, f.id, key, 32);
                int treasury = Ledger.coins(t.id());
                String sold = PlayerFarms.sellForTests(level, p, f.id, "stores", key, 64);
                Business b = Businesses.of(t.id(), "pfarm:" + f.id);
                long till = b.till, rev = b.allTime.revenue;
                PlayerFarms.morningForTests(level, f.id, day(level));
                long rates = b.allTime.rent;
                long tax = PlayerFarms.settleWeekForTests(level, f.id);
                Kit.log("[VT] pw16 " + reg + " | quotes " + q + " | " + sold + " | till " + till + " revenue " + rev + " treasury " + treasury + " -> " + Ledger.coins(t.id())
                    + " | rates " + rates + " tax " + tax + " | wheat left " + carried(p, s -> s.is(Items.WHEAT)));
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(!q.isEmpty() && q.get(0).startsWith("stores|") && !q.get(0).startsWith("stores|0|"), "the stores' price shown before selling: " + q);
                helper.assertTrue(rev > 0 && till > 0 && carried(p, s -> s.is(Items.WHEAT)) == 10, "only the farm's own wheat sold, into its till and its books");
                helper.assertTrue(rates > 0, "land rates charged");
                helper.assertTrue(tax > 0, "the profits tax on its week");
            } finally {
                com.jrpetty.mcassistant.entity.MarketMaker.liveForTests(false);
            }
            helper.succeed();
        });
    }

    // ============================================================ players hiring players

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "pw19_player_hires")
    public static void pw19_player_hires(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8778000, 3);
        helper.runAtTickTime(5, () -> {
            BlockPos at = Kit.surface(level, t.heart().getX() + 30, t.heart().getZ() + 30);
            for (int dx = 0; dx < 4; dx++) for (int dz = 0; dz < 4; dz++) level.setBlock(at.offset(dx, -1, dz), Blocks.FARMLAND.defaultBlockState(), 3);
            ServerPlayer owner = player(helper, t, at.offset(1, 0, 1), 30);
            PlayerFarms.registerForTests(level, owner, "farm", "Owner's Farm");
            PlayerFarms.Farm f = PlayerFarms.allForTests().get(0);
            Business b = Businesses.of(t.id(), "pfarm:" + f.id);
            b.till += 5000;
            int post = PlayerHiring.postForTests(level, owner, t.id(), "farm:" + f.id, f.name, "FARM", 7, 0);
            ServerPlayer hand = player(helper, t, t.heart(), 0);
            String key = "p:" + post;
            boolean onBoard = JobAdverts.advertKeysForTests(level, t.v(), hand).contains(key);
            String ap = JobAdverts.applyForTests(level, hand, key, false);
            String offer = PlayerHiring.answerForTests(owner, post, hand.getUUID(), true);
            JobAdverts.tickForTests(level);
            String st = JobAdverts.statusForTests(hand, key);
            String acc = JobAdverts.acceptForTests(level, hand, key, false);
            WorkLife.dayForTests(hand, day(level), 5, 7, true);
            level.setDayTime(level.getDayTime() + 24000L);
            long till0 = b.till;
            int had = coins(hand);
            String m = WorkLife.morningForTests(level, hand, day(level));
            Kit.log("[VT] pw19 on board " + onBoard + " | " + ap + " | " + offer + " | " + st + " | " + acc + " | " + m + " | till " + till0 + " -> " + b.till + " coins " + had + " -> " + coins(hand));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(onBoard, "the player's advert is on the same board");
            helper.assertTrue(st.equals("OFFERED") && PlayerJobs.employed(hand) && WorkLife.employerKey(hand).equals("farm:" + f.id), "offered, accepted, hired by the farm");
            helper.assertTrue(coins(hand) == had + 7 && b.till < till0, "paid out of the farm's till");
            helper.succeed();
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "pw20_no_commands")
    public static void pw20_no_commands(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 8782000, 3);
        helper.runAtTickTime(5, () -> {
            ServerPlayer p = player(helper, t, t.heart(), 0);
            PlayerJobs.openingForTests(t.id(), "town:FARM", "The town's fields", "FARM", 8, day(level));
            StringBuilder all = new StringBuilder();
            for (String tab : new String[]{ "adverts", "job", "apps", "week", "handin" }) {
                PlayerLife.open(p, "jobs", com.jrpetty.mcassistant.entity.LifePage.args("tab", tab));
                all.append(PlayerLife.pageForTests(p));
            }
            WorkLife.hireForTests(level, p, t.id(), "town:FARM", "The town's fields", "FARM", 8, true);
            for (String tab : new String[]{ "job", "week", "handin" }) {
                PlayerLife.open(p, "jobs", com.jrpetty.mcassistant.entity.LifePage.args("tab", tab));
                all.append(PlayerLife.pageForTests(p));
            }
            PlayerLife.open(p, "farm", null);
            all.append(PlayerLife.pageForTests(p));
            PlayerLife.act(p, "jobs", "quit", new CompoundTag());
            Kit.log("[VT] pw20 pages " + all.length() + " chars; employed after Quit " + PlayerJobs.employed(p));
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(!all.toString().contains("/village"), "no commands on the jobs pages");
            helper.assertTrue(!PlayerJobs.employed(p), "the old Quit still leaves at once");
            helper.succeed();
        });
    }
}
