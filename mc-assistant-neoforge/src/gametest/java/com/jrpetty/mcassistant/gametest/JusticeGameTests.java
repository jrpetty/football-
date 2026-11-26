package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.JusticeConfig;
import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Assize;
import com.jrpetty.mcassistant.entity.Belonging;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Jail;
import com.jrpetty.mcassistant.entity.Jury;
import com.jrpetty.mcassistant.entity.Justice;
import com.jrpetty.mcassistant.entity.Laws;
import com.jrpetty.mcassistant.entity.PlayerLaw;
import com.jrpetty.mcassistant.entity.PlayerLife;
import com.jrpetty.mcassistant.entity.Restraint;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [wpe] Justice: crimes known only by witnesses and evidence, the apology for an accident, arrest in irons and the walk
 * to the jail, the jail and release, the court with its jury, and a witness silenced (or not). Each on its own ground
 * (x 9,600,000 to 9,640,000, z 93,500), a batch each.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class JusticeGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 93500;

    /** A town: its heart, its folk, its guard, its village. */
    record Town(BlockPos heart, Villages.Village v, UUID id, List<VillageFolkEntity> folk, VillageFolkEntity guard) {}

    /** Flat ground (stone if asked, so nobody leaves prints), a town, so many folk, a guard. */
    private static Town town(GameTestHelper helper, ServerLevel level, int x, int folk, boolean stone) {
        Kit.reset(level);
        Kit.hold(level, x, Z, 60);
        Kit.prepare(level, x, Z, 60);
        BlockPos ground = Kit.surface(level, x, Z);
        int y = ground.getY();
        BlockState floor = stone ? Blocks.STONE.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
        for (int dx = -45; dx <= 45; dx++) {
            for (int dz = -30; dz <= 30; dz++) {
                level.setBlock(new BlockPos(x + dx, y - 2, Z + dz), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x + dx, y - 1, Z + dz), floor, 2);
                for (int h = 0; h <= 12; h++) level.setBlock(new BlockPos(x + dx, y + h, Z + dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 3000L);
        List<VillageFolkEntity> out = new ArrayList<>();
        for (int i = 0; i < folk; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, new BlockPos(x - 2 + i * 3, y, Z + 3), 0.0F);
            helper.assertTrue(f != null, "folk " + i + " raised");
            f.ensurePersona();
            f.setNoAi(true);
            f.setJob(StationTask.FARM);
            out.add(f);
        }
        VillageFolkEntity g = VillageFolkSpawnerBlock.raise(level, new BlockPos(x - 12, y, Z + 6), 0.0F);
        helper.assertTrue(g != null, "the guard raised");
        g.ensurePersona();
        g.setJob(StationTask.GUARD);
        g.setWorkZone(com.jrpetty.mcassistant.entity.WorkZone.around(g.blockPosition(), 8, com.jrpetty.mcassistant.entity.WorkZone.DEFAULT_DEPTH));
        g.keepTradeForTests();
        g.setShift(AssistantEntity.Shift.ALWAYS);
        UUID id = out.isEmpty() ? g.ownerId() : out.get(0).ownerId();
        Villages.Village v = Villages.get(id);
        Justice.hurryForTests(true);
        Justice.creativeUnderLawForTests = true;
        JusticeConfig.jailDayMinutesForTests = 0.05;
        JusticeConfig.courtDelayForTests = 0.5;
        return new Town(new BlockPos(x, y, Z), v, id, out, g);
    }

    private static ServerPlayer player(GameTestHelper helper, BlockPos at, int coins) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);          // the stand-in is made creative, which the law exempts
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.getInventory().clearContent();
        if (coins > 0) p.getInventory().setItem(0, new ItemStack(McAssistantMod.VILLAGE_COIN.get(), coins));
        Belonging.resetForTests(p.getUUID());
        Justice.forgetForTests(p.getUUID());
        return p;
    }

    /** What the client would do with the lead's pull: the mock player moved as the server pushes it. */
    private static void step(ServerPlayer p) {
        if (Restraint.restrained(p)) {
            Vec3 d = p.getDeltaMovement();
            p.move(MoverType.SELF, new Vec3(d.x, 0, d.z));
        }
    }

    private static void burgle(ServerLevel level, Town t, ServerPlayer p) {
        p.getInventory().add(new ItemStack(Items.BREAD, 3));
        PlayerLaw.taken(p, Map.of(Items.BREAD, 3));
        Laws.offence(level, t.v(), p, "taking 3 bread from a house", 4);
    }

    private static Justice.JCase latest(UUID player, Justice.Charge charge) {
        for (Justice.JCase c : Justice.against(player)) if (c.charge == charge || c.extra.contains(charge)) return c;
        return null;
    }

    private static void log(String tag, UUID player) {
        for (Justice.JCase c : Justice.against(player)) Kit.log(tag + " " + Justice.caseLine(c));
    }

    private static void kill(VillageFolkEntity f, ServerPlayer p) {
        f.hurt(p.damageSources().playerAttack(p), 1000.0F);
    }

    // ============================================================ ju01: a witnessed theft: arrest, irons, the walk to jail

    /**
     * A player takes bread from a house in front of a folk. The folk tells the watch; the case is weighed (an eyewitness is
     * enough) and a warrant goes out; the guard walks to the player, puts the irons on (it is restrained: slowed, no
     * blows), the notice names the charge, the place and the witness, the stolen bread is found on it, and it is walked on
     * the lead (never jumping) to a cell of the jailhouse, the door shut, held for trial.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "ju01_arrest")
    public static void ju01_arrest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 9600000;
        Town t = town(helper, level, x, 1, false);
        Ledger.Building jail = Jail.jailhouseForTests(level, t.v(), t.heart().offset(22, 0, 0));
        helper.assertTrue(jail != null, "the jailhouse stands");
        Kit.noLeftoverPlayers(level);
        ServerPlayer p = player(helper, t.heart().offset(1, 0, 0), 5);
        VillageFolkEntity w = t.folk().get(0);
        int[] phase = { 0 };
        double[] walked = { 0, 0 };
        Vec3[] last = { null };
        helper.runAtTickTime(20, () -> {
            burgle(level, t, p);
            Justice.JCase c = latest(p.getUUID(), Justice.Charge.BURGLARY);
            helper.assertTrue(c != null && !c.witnesses.isEmpty() && c.witnesses.get(0).id.equals(w.getUUID()), "a case of burglary, the folk its witness");
            helper.assertTrue(Justice.stolenCase(p.getInventory().getItem(1)) == c.id || Justice.stolenCase(p.getInventory().getItem(0)) == c.id
                || p.getInventory().items.stream().anyMatch(s -> Justice.stolenCase(s) == c.id), "the bread marked as stolen");
            Justice.reportNowForTests(t.id());
            Justice.roundForTests(level, t.v());
            log("ju01 reported:", p.getUUID());
            helper.assertTrue(c.stage == Justice.Stage.WARRANT, "an eyewitness is enough for a warrant: " + c.stage);
            Justice.roundForTests(level, t.v());
            helper.assertTrue("GO".equals(Justice.officerTaskForTests(t.guard())), "the guard goes with the warrant: " + Justice.officerTaskForTests(t.guard()));
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            long tick = helper.getTick();
            if (phase[0] == 0) return;
            step(p);
            if (last[0] != null) {
                double d = p.position().distanceTo(last[0]);
                if (Restraint.restrained(p)) walked[0] += d;
                walked[1] = Math.max(walked[1], Restraint.restrained(p) ? d : 0);
            }
            last[0] = p.position();
            if (phase[0] == 1 && Restraint.restrained(p)) {
                phase[0] = 2;
                CompoundTag page = PlayerLife.pageForTests(p);
                String text = page.toString();
                Kit.log("ju01 arrested at tick " + tick + "; the notice: " + text.substring(0, Math.min(600, text.length())));
                helper.assertTrue(text.contains("You are under arrest for burglary") && text.contains("witness: " + w.displayNameCap()), "the notice names the charge and the witness");
                helper.assertTrue(p.getInventory().items.stream().noneMatch(s -> s.is(Items.BREAD)), "the stolen bread taken in the search");
                helper.assertTrue(p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).getValue() < 0.07, "slowed in irons");
            }
            if (phase[0] == 2 && tick % 100 == 0) {
                var nav = t.guard().getNavigation().getPath();
                Kit.log("ju01 t" + tick + " guard " + t.guard().blockPosition().toShortString() + " " + Justice.officerTaskForTests(t.guard())
                    + " nav " + (nav == null ? "none" : nav.getTarget().toShortString() + " done " + nav.isDone())
                    + " hobby " + t.guard().hobbyNow() + " goals " + BlinkGameTests.running(t.guard()) + " brain " + t.guard().brainNoteForBooks() + "; player " + p.blockPosition().toShortString() + " d " + String.format("%.1f", t.guard().distanceTo(p)));
            }
            if (phase[0] == 2 && Jail.held(p.getUUID())) {
                Justice.JCase c = latest(p.getUUID(), Justice.Charge.BURGLARY);
                CompoundTag cu = Jail.custodyForTests(p.getUUID());
                boolean inCell = false;
                for (BlockPos in : Jail.cellsForTests(jail)) if (p.position().distanceTo(Vec3.atBottomCenterOf(in)) < 1.3) inCell = true;
                Kit.noLeftoverPlayers(level);
                Kit.log("ju01 held at tick " + tick + ": walked " + String.format("%.1f", walked[0]) + " on the lead, the longest step " + String.format("%.2f", walked[1])
                    + "; custody " + cu + "; " + Justice.caseLine(c));
                helper.assertTrue(inCell, "in a cell of the jailhouse");
                helper.assertTrue(walked[0] > 10, "walked there on the lead: " + walked[0]);
                helper.assertTrue(walked[1] < 1.6, "never jumped: " + walked[1]);
                helper.assertTrue(!Restraint.restrained(p), "the irons off in the cell");
                helper.assertTrue(c.stage == Justice.Stage.REMAND && cu.getBoolean("remand"), "held for trial");
                helper.succeed();
            }
            if (tick > 2900) { Kit.noLeftoverPlayers(level); log("ju01 stuck:", p.getUUID()); helper.fail("ju01 never held: phase " + phase[0] + ", guard " + Justice.officerTaskForTests(t.guard())
                + " at " + t.guard().blockPosition().toShortString() + ", the player at " + p.blockPosition().toShortString()); }
        });
    }

    // ============================================================ ju02: nobody saw: no arrest

    /** The same theft with nobody near enough to see it: missed and reported, but nothing points at anybody: unsolved. */
    @GameTest(template = EMPTY, timeoutTicks = 600, batch = "ju02_unseen")
    public static void ju02_unseen(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 9603000;
        Town t = town(helper, level, x, 1, false);
        t.folk().get(0).moveTo(t.heart().getX() - 40, t.heart().getY(), t.heart().getZ() - 25);
        t.guard().moveTo(t.heart().getX() - 40, t.heart().getY(), t.heart().getZ() + 25);
        Kit.noLeftoverPlayers(level);
        ServerPlayer p = player(helper, t.heart().offset(30, 0, 0), 0);
        helper.runAtTickTime(20, () -> {
            burgle(level, t, p);
            Justice.JCase c = latest(p.getUUID(), Justice.Charge.BURGLARY);
            helper.assertTrue(c != null && c.witnesses.isEmpty(), "a case, with no witness");
            for (int i = 0; i < 4; i++) { Justice.reportNowForTests(t.id()); Justice.roundForTests(level, t.v()); }
            log("ju02", p.getUUID());
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(c.stage == Justice.Stage.UNSOLVED, "unsolved: " + c.stage);
            helper.assertTrue(Justice.officerTaskForTests(t.guard()).isEmpty() && !Restraint.restrained(p) && !Jail.held(p.getUUID()), "nobody came for it");
            helper.succeed();
        });
    }

    // ============================================================ ju03: an accident, and an apology

    /**
     * A light blow to a folk, once: no crime yet. The talk menu offers Apologise (and paying the healer) to that folk; the
     * player pays the healer and says sorry, the folk forgives it, and nothing is opened against the player.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ju03_apology")
    public static void ju03_apology(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 9606000;
        Town t = town(helper, level, x, 1, false);
        VillageFolkEntity f = t.folk().get(0);
        f.life().setTraitsForTests(Social.Trait.CHEERFUL, Social.Trait.GENEROUS);
        Kit.noLeftoverPlayers(level);
        ServerPlayer p = player(helper, f.blockPosition().offset(1, 0, 0), 10);
        helper.runAtTickTime(20, () -> {
            float before = f.getHealth();
            f.hurt(p.damageSources().playerAttack(p), 2.0F);
            boolean owed = Justice.hurtOwedForTests(p.getUUID(), f.getUUID());
            String flags = FolkTalk.talkContext(f, p);
            helper.assertTrue(owed && flags.contains("hurtme"), "an apology owed, the talk menu offers it: " + flags);
            helper.assertTrue(Justice.against(p.getUUID()).isEmpty(), "no case for an accident");
            String said = Justice.apologiseForTests(f, p, true);
            CompoundTag r = Justice.record(p.getUUID());
            Kit.noLeftoverPlayers(level);
            Kit.log("ju03 hurt " + before + " -> healed " + f.getHealth() + "; said: " + said + "; record " + r);
            helper.assertTrue(r.getInt("forgiven") == 1 && r.getInt("complaints") == 0, "forgiven: " + said);
            helper.assertTrue(Justice.against(p.getUUID()).isEmpty() && !Justice.hurtOwedForTests(p.getUUID(), f.getUUID()), "nothing against it");
            helper.assertTrue(p.getInventory().countItem(McAssistantMod.VILLAGE_COIN.get()) < 10, "the healer paid");
            helper.succeed();
        });
    }

    // ============================================================ ju04: harm again and again

    /**
     * An accident left unapologised is a complaint; a second is an assault charge. Two blows on the same folk in a row are
     * no accident: an assault at once, its victim a witness.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ju04_escalate")
    public static void ju04_escalate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 9609000;
        Town t = town(helper, level, x, 3, false);
        VillageFolkEntity a = t.folk().get(0), b = t.folk().get(1), c = t.folk().get(2);
        Kit.noLeftoverPlayers(level);
        ServerPlayer p = player(helper, a.blockPosition().offset(0, 0, -2), 0);
        helper.runAtTickTime(20, () -> {
            a.hurt(p.damageSources().playerAttack(p), 1.5F);
            Justice.expireApologiesForTests(level);
            helper.assertTrue(Justice.record(p.getUUID()).getInt("complaints") == 1 && Justice.against(p.getUUID()).isEmpty(), "a complaint, not yet a charge");
        });
        helper.runAtTickTime(40, () -> {
            b.hurt(p.damageSources().playerAttack(p), 1.5F);
            Justice.expireApologiesForTests(level);
            Justice.JCase k = latest(p.getUUID(), Justice.Charge.ASSAULT);
            log("ju04 two complaints:", p.getUUID());
            helper.assertTrue(k != null && k.minor && k.victim.equals(b.getUUID()), "the second complaint an assault charge");
        });
        helper.runAtTickTime(60, () -> c.hurt(p.damageSources().playerAttack(p), 1.5F));
        helper.runAtTickTime(80, () -> {
            c.hurt(p.damageSources().playerAttack(p), 1.5F);
            Justice.JCase k = null;
            for (Justice.JCase o : Justice.against(p.getUUID())) if (o.charge == Justice.Charge.ASSAULT && c.getUUID().equals(o.victim)) k = o;
            log("ju04 the blows:", p.getUUID());
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(k != null && !k.minor && k.intent >= 1, "two blows in a row: an assault");
            helper.assertTrue(k.witnesses.stream().anyMatch(w -> w.victim && w.id.equals(c.getUUID())), "its victim a witness");
            helper.succeed();
        });
    }

    // ============================================================ ju05: a killing goes to trial

    /**
     * A player kills a folk in front of a witness. It is held, and the court sits in the courthouse: the charge read, the
     * witness heard, its account ("It was an accident"), the verdict. A killing is never let go without the court; the
     * court finds it guilty and, with no sign of intent, of causing death by accident: compensation to the family and a
     * fine, and the conviction on its record.
     */
    @GameTest(template = EMPTY, timeoutTicks = 3000, batch = "ju05_killing")
    public static void ju05_killing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 9612000;
        Town t = town(helper, level, x, 2, true);
        Ledger.Building jail = Jail.jailhouseForTests(level, t.v(), t.heart().offset(22, 0, 0));
        Ledger.Building court = Assize.courthouseForTests(level, t.v(), t.heart().offset(-24, 0, 0));
        helper.assertTrue(jail != null && court != null, "the jailhouse and the courthouse stand");
        VillageFolkEntity victim = t.folk().get(0), w = t.folk().get(1);
        Kit.noLeftoverPlayers(level);
        ServerPlayer p = player(helper, victim.blockPosition().offset(0, 0, -2), 50);
        int[] id = { -1 };
        helper.runAtTickTime(20, () -> {
            kill(victim, p);
            Justice.JCase c = latest(p.getUUID(), Justice.Charge.MANSLAUGHTER);
            if (c == null) c = latest(p.getUUID(), Justice.Charge.MURDER);
            helper.assertTrue(c != null && c.charge.killing(), "a killing is a case");
            id[0] = c.id;
            Justice.reportNowForTests(t.id());
            Justice.roundForTests(level, t.v());
            helper.assertTrue(c.stage == Justice.Stage.WARRANT, "a warrant: " + c.stage);
            Jail.lockUpForTests(level, t.v(), p, c.id);
            helper.assertTrue(Jail.held(p.getUUID()) && c.stage == Justice.Stage.REMAND, "held for trial");
            String d = Assize.defendForTests(p, c.id, "account", "accident");
            Kit.log("ju05 defence: " + d);
            Assize.sitNowForTests(level, c.id);
        });
        helper.onEachTick(() -> {
            step(p);
            if (id[0] < 0) return;
            Justice.JCase c = Justice.get(id[0]);
            if (c.stage == Justice.Stage.CONVICTED || c.stage == Justice.Stage.ACQUITTED) {
                for (String l : c.transcript) Kit.log("ju05 court: " + l);
                log("ju05", p.getUUID());
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(c.transcript.stream().anyMatch(l -> l.contains("stand charged with")), "the charge read");
                helper.assertTrue(c.transcript.stream().anyMatch(l -> l.startsWith(w.displayNameCap())), "the witness heard");
                helper.assertTrue(c.transcript.stream().anyMatch(l -> l.contains("It was an accident")), "the account heard");
                helper.assertTrue(c.stage == Justice.Stage.CONVICTED && c.finding.contains("accident"), "guilty, found an accident: " + c.finding);
                helper.assertTrue(c.sentence.contains("compensation") && !Justice.convictions(p.getUUID()).isEmpty(), "compensation, and on the record: " + c.sentence);
                helper.succeed();
            }
            if (helper.getTick() > 2900) { Kit.noLeftoverPlayers(level); log("ju05 stuck:", p.getUUID()); helper.fail("ju05 no verdict: " + c.stage + " " + Assize.phaseForTests(t.id())); }
        });
    }

    // ============================================================ ju06: served, released, belongings back

    /**
     * A player locked up gives up what it carries at the door; sentenced, its time runs while it is online (and only a
     * quarter of it while it is away, never the whole), and at the end the door opens and everything is handed back.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ju06_release")
    public static void ju06_release(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 9615000;
        Town t = town(helper, level, x, 1, false);
        Ledger.Building jail = Jail.jailhouseForTests(level, t.v(), t.heart().offset(22, 0, 0));
        Kit.noLeftoverPlayers(level);
        ServerPlayer p = player(helper, Jail.cellsForTests(jail).get(0), 0);
        p.getInventory().add(new ItemStack(Items.APPLE, 5));
        p.getInventory().add(new ItemStack(Items.IRON_SWORD));
        int[] id = { -1 };
        helper.runAtTickTime(10, () -> {
            id[0] = Justice.openForTests(level, t.v(), Justice.Charge.BURGLARY, p, null);
            Jail.lockUpForTests(level, t.v(), p, id[0]);
            helper.assertTrue(p.getInventory().countItem(Items.APPLE) == 0 && p.getInventory().countItem(Items.IRON_SWORD) == 0, "its things taken at the door");
            JusticeConfig.offlineRateForTests = 0.25;
            Jail.sentenceForTests(level, t.v(), p, id[0], 600);
            double before = Jail.custodyForTests(p.getUUID()).getDouble("left");
            Jail.backForTests(level, p, 400_000L);                         // away 400 seconds: a quarter counts
            double after = Jail.custodyForTests(p.getUUID()).getDouble("left");
            Kit.log("ju06 left " + before + " -> " + after + " after 400s away");
            helper.assertTrue(Jail.held(p.getUUID()) && before - after > 90 && before - after < 110, "a quarter of the time away counted, still held");
            JusticeConfig.offlineRateForTests = 0.0;
            Jail.backForTests(level, p, 400_000L);
            helper.assertTrue(Math.abs(Jail.custodyForTests(p.getUUID()).getDouble("left") - after) < 0.01, "at nought, frozen while away");
            Jail.serveForTests(p.getUUID(), 1.5);
        });
        helper.onEachTick(() -> {
            if (id[0] < 0 || helper.getTick() < 20) return;
            if (!Jail.held(p.getUUID())) {
                Kit.noLeftoverPlayers(level);
                Kit.log("ju06 released at tick " + helper.getTick() + ": apples " + p.getInventory().countItem(Items.APPLE) + ", sword " + p.getInventory().countItem(Items.IRON_SWORD));
                helper.assertTrue(p.getInventory().countItem(Items.APPLE) == 5 && p.getInventory().countItem(Items.IRON_SWORD) == 1, "its things handed back");
                helper.assertTrue(Jail.custodyForTests(p.getUUID()).isEmpty(), "its custody over");
                helper.succeed();
            }
            if (helper.getTick() > 300) { Kit.noLeftoverPlayers(level); helper.fail("ju06 never released"); }
        });
    }

    // ============================================================ ju07: resisting arrest

    /** The guard comes with a warrant; the player strikes it (resisting), then runs off out of reach: a wanted notice. */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "ju07_resist")
    public static void ju07_resist(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 9618000;
        Town t = town(helper, level, x, 1, false);
        Jail.jailhouseForTests(level, t.v(), t.heart().offset(22, 0, 0));
        Kit.noLeftoverPlayers(level);
        ServerPlayer p = player(helper, t.heart().offset(1, 0, 0), 0);
        int[] phase = { 0 };
        helper.runAtTickTime(20, () -> {
            burgle(level, t, p);
            Justice.reportNowForTests(t.id());
            Justice.roundForTests(level, t.v());
            Justice.roundForTests(level, t.v());
            helper.assertTrue("GO".equals(Justice.officerTaskForTests(t.guard())), "the guard goes for it");
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            if (phase[0] == 1 && t.guard().distanceTo(p) < 6) {
                t.guard().hurt(p.damageSources().playerAttack(p), 1.0F);
                Justice.JCase c = latest(p.getUUID(), Justice.Charge.BURGLARY);
                helper.assertTrue(c.extra.contains(Justice.Charge.RESISTING), "striking the guard is resisting arrest");
                p.teleportTo(t.heart().getX() + 44.5, t.heart().getY(), t.heart().getZ() + 0.5);   // off at a run
                phase[0] = 2;
            }
            if (phase[0] == 2 && Justice.officerTaskForTests(t.guard()).isEmpty()) {
                Justice.JCase c = latest(p.getUUID(), Justice.Charge.BURGLARY);
                List<String> board = Justice.boardLines(level, t.id());
                log("ju07", p.getUUID());
                Kit.log("ju07 board: " + board);
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(c.stage == Justice.Stage.WARRANT && c.notes.stream().anyMatch(n -> n.startsWith("wanted")), "still wanted");
                helper.assertTrue(board.stream().anyMatch(l -> l.contains("WANTED") && l.contains(c.accusedName)), "its name on the board");
                helper.assertTrue(!Restraint.restrained(p), "not in irons");
                helper.succeed();
            }
            if (helper.getTick() > 1500) { Kit.noLeftoverPlayers(level); helper.fail("ju07 phase " + phase[0] + " guard " + Justice.officerTaskForTests(t.guard())); }
        });
    }

    // ============================================================ ju08: the jail goes up at the threshold

    /**
     * A Stone Age town with a watch wants no jailhouse; nor an Iron Age one too small; a sworn special constable makes it
     * want one; built, it wants no more, and its three cells have iron bars, an iron door and a bucket.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "ju08_jail_built")
    public static void ju08_jail_built(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 9621000;
        Town t = town(helper, level, x, 2, false);
        helper.runAtTickTime(10, () -> {
            Villages.ageForTests(t.id(), Villages.Age.STONE);
            boolean stone = Jail.wantedForTests(t.id());
            Villages.ageForTests(t.id(), Villages.Age.IRON);
            boolean small = Jail.wantedForTests(t.id());
            Jail.swornForTests(t.id());
            boolean sworn = Jail.wantedForTests(t.id());
            boolean listed = Villages.projectsWanted(t.id()).contains(Jail.STRUCTURE);
            Ledger.Building b = Jail.jailhouseForTests(level, t.v(), t.heart().offset(22, 0, 0));
            boolean after = Jail.wantedForTests(t.id());
            int iron = 0, doors = 0, buckets = 0;
            for (BlockPos in : Jail.cellsForTests(b)) {
                for (int dx = -2; dx <= 2; dx++) for (int dz = -3; dz <= 2; dz++) for (int dy = 0; dy <= 2; dy++) {
                    BlockState s = level.getBlockState(in.offset(dx, dy, dz));
                    if (s.is(Blocks.IRON_BARS)) iron++;
                    if (s.is(Blocks.IRON_DOOR)) doors++;
                    if (s.is(Blocks.CAULDRON)) buckets++;
                }
            }
            Kit.log("ju08 stone " + stone + ", small iron " + small + ", sworn " + sworn + " (listed " + listed + "), built " + after + "; bars " + iron + " doors " + doors + " buckets " + buckets);
            helper.assertTrue(!stone && !small && sworn && listed && !after, "wanted only once a constable is sworn, and not once built");
            helper.assertTrue(iron >= 12 && doors >= 6 && buckets >= 3, "three cells of iron, each with a bucket");
            helper.succeed();
        });
    }

    // ============================================================ ju09: a player on the jury

    /** A citizen on the jury roll is called to the court's sitting, hears the case, and its verdict is counted. */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "ju09_juror")
    public static void ju09_juror(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 9624000;
        Town t = town(helper, level, x, 2, false);
        Kit.noLeftoverPlayers(level);
        ServerPlayer p = player(helper, t.heart().offset(1, 0, 0), 0);
        ServerPlayer j = player(helper, t.heart().offset(-1, 0, -1), 0);
        Ledger.addCitizen(t.id(), j.getUUID(), j.getGameProfile().getName());
        Belonging.setJuryRoll(t.id(), j.getUUID(), true);
        int[] id = { -1 };
        boolean[] voted = { false };
        helper.runAtTickTime(20, () -> {
            burgle(level, t, p);
            Justice.JCase c = latest(p.getUUID(), Justice.Charge.BURGLARY);
            Justice.reportNowForTests(t.id());
            Justice.roundForTests(level, t.v());
            Jail.lockUpForTests(level, t.v(), p, c.id);
            Assize.defendForTests(p, c.id, "account", "elsewhere");
            Assize.sitNowForTests(level, c.id);
            id[0] = c.id;
        });
        helper.onEachTick(() -> {
            step(p);
            if (id[0] < 0) return;
            Justice.JCase c = Justice.get(id[0]);
            if (!voted[0] && Jury.calledTo(j.getUUID()) == id[0] && Jury.waiting(id[0])) {
                List<String> heard = Jury.hearing(id[0]);
                String said = Jury.vote(j, id[0], true);
                Kit.log("ju09 the juror heard " + heard + " and voted: " + said);
                helper.assertTrue(heard.stream().anyMatch(l -> l.contains("burglary")), "the juror hears the charge");
                voted[0] = true;
            }
            if (c.stage == Justice.Stage.CONVICTED || c.stage == Justice.Stage.ACQUITTED) {
                for (String l : c.transcript) Kit.log("ju09 court: " + l);
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(voted[0], "the juror was called and voted");
                helper.assertTrue(c.stage == Justice.Stage.CONVICTED, "guilty: " + c.stage);
                helper.succeed();
            }
            if (helper.getTick() > 2300) { Kit.noLeftoverPlayers(level); helper.fail("ju09 " + c.stage + " " + Assize.phaseForTests(t.id()) + " called " + Jury.calledTo(j.getUUID())); }
        });
    }

    // ============================================================ ju10: the only witness, silenced unseen

    /**
     * A folk alone sees a player's burglary and has told nobody; the player kills it with nobody to see, on stone (no
     * prints) and empty-handed. What it saw dies with it: the burglary goes unsolved, and so does the killing.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ju10_silenced")
    public static void ju10_silenced(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 9627000;
        Town t = town(helper, level, x, 1, true);
        t.guard().moveTo(t.heart().getX() - 40, t.heart().getY(), t.heart().getZ() + 25);
        VillageFolkEntity w = t.folk().get(0);
        Kit.noLeftoverPlayers(level);
        ServerPlayer p = player(helper, w.blockPosition().offset(0, 0, -2), 0);
        helper.runAtTickTime(20, () -> {
            burgle(level, t, p);
            Justice.holdTonguesForTests(t.id());
            kill(w, p);
            for (int i = 0; i < 4; i++) { Justice.reportNowForTests(t.id()); Justice.roundForTests(level, t.v()); }
            Justice.JCase b = latest(p.getUUID(), Justice.Charge.BURGLARY);
            Justice.JCase k = null;
            for (Justice.JCase c : Justice.against(p.getUUID())) if (c.charge.killing()) k = c;
            log("ju10", p.getUUID());
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(b != null && b.witnesses.get(0).lost, "the witness's testimony lost with it");
            helper.assertTrue(b.stage == Justice.Stage.UNSOLVED, "the burglary unsolved: " + b.stage);
            helper.assertTrue(k != null && k.stage == Justice.Stage.UNSOLVED, "the killing unsolved too");
            helper.assertTrue(Justice.officerTaskForTests(t.guard()).isEmpty(), "nobody sent for it");
            helper.succeed();
        });
    }

    // ============================================================ ju11: the witness had told the watch

    /** The witness told the watch first; then it is killed. Its statement stands on the file, and the warrant with it. */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "ju11_told_first")
    public static void ju11_told_first(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 9630000;
        Town t = town(helper, level, x, 1, true);
        VillageFolkEntity w = t.folk().get(0);
        Kit.noLeftoverPlayers(level);
        ServerPlayer p = player(helper, w.blockPosition().offset(0, 0, -2), 0);
        helper.runAtTickTime(20, () -> {
            burgle(level, t, p);
            Justice.reportNowForTests(t.id());
            Justice.roundForTests(level, t.v());
            Justice.JCase b = latest(p.getUUID(), Justice.Charge.BURGLARY);
            helper.assertTrue(b.stage == Justice.Stage.WARRANT, "reported: a warrant");
            kill(w, p);
            Justice.roundForTests(level, t.v());
            Justice.reportNowForTests(t.id());
            Justice.roundForTests(level, t.v());
            log("ju11", p.getUUID());
            Justice.JCase k = null;
            for (Justice.JCase c : Justice.against(p.getUUID())) if (c.charge.killing()) k = c;
            Kit.noLeftoverPlayers(level);
            helper.assertTrue(b.stage.named() && b.score() >= 3, "the case proceeds on its statement: " + b.stage);
            helper.assertTrue(k != null && k.evidence.stream().anyMatch(e -> e.kind.startsWith("MOTIVE") && e.found), "and the killing has a motive the watch knows");
            helper.succeed();
        });
    }

    // ============================================================ ju12: the witness killing seen

    /**
     * The witness to a burglary told nobody, but another folk sees the player kill it. That witness tells the watch: a
     * warrant for the killing; the guard arrests the player, finds the stolen bread on it, and it is charged with both.
     */
    @GameTest(template = EMPTY, timeoutTicks = 2400, batch = "ju12_seen_killing")
    public static void ju12_seen_killing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        final int x = 9633000;
        Town t = town(helper, level, x, 2, true);
        Jail.jailhouseForTests(level, t.v(), t.heart().offset(22, 0, 0));
        VillageFolkEntity w = t.folk().get(0), h = t.folk().get(1);
        h.moveTo(t.heart().getX() + 30, t.heart().getY(), t.heart().getZ() + 3);   // away at first: it does not see the burglary
        t.guard().moveTo(t.heart().getX() - 40, t.heart().getY(), t.heart().getZ() + 25);   // nor the guard
        Kit.noLeftoverPlayers(level);
        ServerPlayer p = player(helper, w.blockPosition().offset(0, 0, -2), 0);
        int[] phase = { 0 };
        helper.runAtTickTime(20, () -> {
            burgle(level, t, p);
            Justice.holdTonguesForTests(t.id());
            Justice.JCase b = latest(p.getUUID(), Justice.Charge.BURGLARY);
            helper.assertTrue(b.witnesses.size() == 1 && b.witnesses.get(0).id.equals(w.getUUID()), "one witness to the burglary");
            h.moveTo(w.getX() + 3, w.getY(), w.getZ());                    // now it is about
            kill(w, p);
            Justice.reportNowForTests(t.id());
            Justice.roundForTests(level, t.v());
            Justice.roundForTests(level, t.v());
            log("ju12 the killing seen:", p.getUUID());
            phase[0] = 1;
        });
        helper.onEachTick(() -> {
            step(p);
            if (phase[0] == 1 && Restraint.restrained(p)) {
                Justice.JCase k = null;
                for (Justice.JCase c : Justice.against(p.getUUID())) if (c.charge.killing()) k = c;
                Justice.JCase b = latest(p.getUUID(), Justice.Charge.BURGLARY);
                log("ju12 arrested:", p.getUUID());
                Kit.noLeftoverPlayers(level);
                helper.assertTrue(k != null && k.witnesses.stream().anyMatch(x2 -> x2.id.equals(h.getUUID()) && x2.reported), "the killing seen and told");
                helper.assertTrue(b.joinedTo == k.id && b.evidence.stream().anyMatch(e -> e.kind.equals("GOODS")), "the stolen bread found: the burglary joined");
                helper.assertTrue(k.chargeWords().length() > 0 && k.joined.contains(b.id), "arrested for both");
                helper.succeed();
            }
            if (helper.getTick() > 2300) { Kit.noLeftoverPlayers(level); log("ju12 stuck:", p.getUUID()); helper.fail("ju12 no arrest; guard " + Justice.officerTaskForTests(t.guard())); }
        });
    }
}
