package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.Apprenticeship;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Assemblies;
import com.jrpetty.mcassistant.entity.Blink;
import com.jrpetty.mcassistant.entity.Courtship;
import com.jrpetty.mcassistant.entity.FolkLines;
import com.jrpetty.mcassistant.entity.FolkSkills;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Gatherings;
import com.jrpetty.mcassistant.entity.Lineage;
import com.jrpetty.mcassistant.entity.PlayerFamily;
import com.jrpetty.mcassistant.entity.School;
import com.jrpetty.mcassistant.entity.SchoolDays;
import com.jrpetty.mcassistant.entity.Standing;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.TownOpinion;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WeddingDecor;
import com.jrpetty.mcassistant.entity.Witness;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [featfolk] The town's lines: families and generations, apprenticeships, what folk say of a player and only from what
 * they saw, the town's respect, the church wedding, a player's own family, and the school day.
 *
 * <ul>
 * <li><b>fl01</b>: two folk fond of each other walk out, walk twice and one gives a flower; at the end of the second walk
 *     one asks and the town has a wedding; a child is born at home, learns its mother's trade at her side, grows up and
 *     takes the trade as a journeyman; its mother, old, retires and hands it her post, and she is retired.</li>
 * <li><b>fl02</b>: an apprentice under a master with a knack qualifies as a journeyman when it grows up and is taught the
 *     knack; one who had not come far enough stays an apprentice and qualifies at work beside its master; a journeyman of
 *     the master's level is a master.</li>
 * <li><b>fl03</b>: only witnesses (in sight and range) remember a deed; a folk who saw nothing says nothing of the player
 *     in its own words, only a witness's word as theirs; hearsay is never passed on to another folk; every line said of
 *     the player is a witness's own or names a real witness.</li>
 * <li><b>fl04</b>: the town's opinion and respect come from its witnesses: the reasons in their words and names, a
 *     witness's opinion outweighing a folk's who never met the player, and the respect rising with witnessed deeds.</li>
 * <li><b>fl05</b>: a wedding in a poor town is dressed with wildflowers, in a rich one with a carpet, lanterns, an arch and
 *     a feast table out of the stores, paid for by the couple; guests sit; the rings and the kiss are said; it all comes
 *     down again into the stores.</li>
 * <li><b>fl06</b>: a player courts a folk (walk out, a walk, a supper, a gift), proposes, the town holds the wedding, they
 *     are wed; they try for a baby, the news, the birth at home, the player names it, and it grows up.</li>
 * <li><b>fl07</b>: a town with children wants a school; with a schoolhouse it hires a teacher and the children attend and
 *     learn, with a player watching and nobody seen to jump; a town with no school teaches its children at home.</li>
 * </ul>
 * Each on its own ground: x 7,400,000 to 7,430,000 (5000 apart), z 130,000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class FolkLinesGameTests {

    private static final String EMPTY = "empty";
    private static final long DAY = 24000L * 4;
    private static final int Z = 130000;

    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        BlockPos mid = Kit.surface(level, cx, cz);
        int y = mid.getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 24; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    private static VillageFolkEntity founder(GameTestHelper helper, int x, int z, long time) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Witness.resetForTests();
        Courtship.resetForTests();
        WeddingDecor.resetForTests();
        SchoolDays.resetForTests();
        level.setDayTime(time);
        level.setWeatherParameters(24000, 0, false, false);
        Kit.hold(level, x, z, 56);
        Kit.prepare(level, x, z, 56);
        BlockPos heart = flat(level, x, z, 48);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a village");
        f.ensurePersona();
        return f;
    }

    private static VillageFolkEntity another(GameTestHelper helper, BlockPos at, UUID village) {
        ServerLevel level = helper.getLevel();
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, Kit.surface(level, at.getX(), at.getZ()), 0.0F);
        helper.assertTrue(f != null && village.equals(f.ownerId()), "another folk of the village");
        f.ensurePersona();
        return f;
    }

    private static ServerPlayer player(GameTestHelper helper, ServerLevel level, BlockPos at) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(p.connection.getConnection());
        p.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        return p;
    }

    private static void letGo(ServerLevel level, ServerPlayer p) {
        Blink.unwatchForTests(p);
        level.getServer().getPlayerList().remove(p);
    }

    private static boolean remembers(VillageFolkEntity f, String words) {
        for (com.jrpetty.mcassistant.entity.Persona.Memory m : f.persona().memories()) if (m.text().contains(words)) return true;
        return false;
    }

    private static void freeOfEachOther(VillageFolkEntity a, VillageFolkEntity b) {
        a.life().widowed();
        b.life().widowed();
        a.life().setParents("Nobody", "Here");
        b.life().setParents("Someone", "Else");
    }

    // ============================================================ fl01: courting, a wedding, a child, a trade handed down

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "fl01_family")
    public static void fl01_courtshipToTrade(GameTestHelper helper) {
        int x = 7400000;
        VillageFolkEntity mother = founder(helper, x, Z, DAY + 9000);
        ServerLevel level = helper.getLevel();
        UUID id = mother.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity father = another(helper, heart.east(2), id);
        helper.runAtTickTime(10, () -> {
            freeOfEachOther(mother, father);
            mother.life().feel(father.getUUID(), father.displayNameCap(), 50 - mother.life().affinity(father.getUUID()));
            father.life().feel(mother.getUUID(), mother.displayNameCap(), 50 - father.life().affinity(mother.getUUID()));
            helper.assertTrue(Courtship.notice(mother, father, id), "two fond of each other start walking out");
            helper.assertTrue(Courtship.courting(mother, father), "they are courting each other");
            helper.assertTrue(!Courtship.ready(mother, father), "not ready to wed before any walks");
            Courtship.walkedForTests(mother, father, true);
            helper.assertTrue(mother.lines().walks() == 1 && mother.lines().gifts() == 1, "a walk had and a flower given: " + mother.lines().walks());
            helper.assertTrue(remembers(father, "on our walk"), "the flower is remembered");
            mother.life().feel(father.getUUID(), father.displayNameCap(), 90);
            father.life().feel(mother.getUUID(), mother.displayNameCap(), 90);
            Courtship.walkedForTests(mother, father, false);
            long day = level.getDayTime() / 24000L;
            Kit.log("[VT] fl01 after two walks: partner " + mother.life().partner() + " tonight " + Gatherings.tonight(id, day));
            helper.assertTrue(father.getUUID().equals(mother.life().partner()) && mother.getUUID().equals(father.life().partner()),
                "at the end of the second walk one asks the other");
            helper.assertTrue(Gatherings.tonight(id, day) == Gatherings.Kind.WEDDING, "and the town holds a wedding");
            helper.assertTrue(mother.lines().courting() == null, "the walking out is over");
            // A child, born at home (by its mother's bed, where she is near it).
            BlockPos bed = heart.offset(-6, 0, 6);
            level.setBlock(bed, Blocks.RED_BED.defaultBlockState().setValue(net.minecraft.world.level.block.BedBlock.PART,
                net.minecraft.world.level.block.state.properties.BedPart.HEAD), 3);
            mother.takeBedForTests(bed);
            mother.insertItem(new ItemStack(Items.BREAD, 4));
            father.insertItem(new ItemStack(Items.BREAD, 4));
            VillageFolkEntity child = mother.raiseChildWith(father);
            helper.assertTrue(child != null && child.isBaby(), "a child born");
            helper.assertTrue(child.blockPosition().distSqr(bed) <= 4.0, "born at home, by its mother's bed: " + child.blockPosition() + " bed " + bed);
            // Its mother's trade, learned at her side.
            mother.setStation(heart.offset(10, 0, 0), StationTask.FARM);
            mother.tradeXpForTests(StationTask.FARM, AssistantEntity.xpForLevel(10));
            child.apprenticeForTests(StationTask.FARM);
            Apprenticeship.lessonsForTests(child, mother, 40);
            helper.assertTrue(child.lines().skill() >= Apprenticeship.QUALIFY, "its skill rises at its master's side: " + child.lines().skill());
            child.childhoodForTests(VillageFolkEntity.GROW_DAYS);
            Kit.log("[VT] fl01 grown: " + child.stationTask() + " rank " + child.lines().rank() + " skill " + child.lines().skill());
            helper.assertTrue(!child.isBaby() && child.stationTask() == StationTask.FARM, "grown up, it takes up its mother's trade: " + child.stationTask());
            helper.assertTrue(Apprenticeship.JOURNEYMAN.equals(child.lines().rank()), "as a journeyman: " + child.lines().rank());
            // Its mother, old, hands it her post and retires.
            child.setStation(heart.offset(-12, 0, 0), StationTask.FARM);
            mother.setAgeForTests(Lineage.RETIRE_AT + 1);
            BlockPos post = mother.stationPos();
            com.jrpetty.mcassistant.entity.WorkZone ground = mother.workZone();
            Lineage.daily(level, mother, level.getDayTime() / 24000L);
            Kit.log("[VT] fl01 retired " + mother.lines().retired() + " heir post " + child.stationPos() + " inherited " + child.lines().inherited());
            helper.assertTrue(mother.lines().retired() && mother.stationTask() == StationTask.NONE, "the old mother retires");
            helper.assertTrue((ground != null ? ground.center().equals(child.workZone() == null ? null : child.workZone().center()) : post.equals(child.stationPos()))
                && child.stationTask() == StationTask.FARM, "her post and ground are her child's now");
            helper.assertTrue(!child.lines().inherited().isEmpty() && Lineage.cardLine(mother) != null, "the card says what was handed down");
            helper.assertTrue("Retired".equals(mother.tradeTitle()), "she is retired: " + mother.tradeTitle());
            // Kept across a save.
            CompoundTag t = child.lines().save();
            FolkLines back = new FolkLines();
            back.load(t);
            helper.assertTrue(back.rank().equals(child.lines().rank()) && back.inherited().size() == child.lines().inherited().size(), "kept across a save");
            helper.succeed();
        });
    }

    // ============================================================ fl02: an apprentice qualifies

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "fl02_apprentice")
    public static void fl02_apprenticeQualifies(GameTestHelper helper) {
        int x = 7405000;
        VillageFolkEntity master = founder(helper, x, Z, DAY + 3000);
        ServerLevel level = helper.getLevel();
        UUID id = master.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity other = another(helper, heart.east(2), id);
        helper.runAtTickTime(10, () -> {
            master.setStation(heart.offset(8, 0, 0), StationTask.MINE);
            master.tradeXpForTests(StationTask.MINE, AssistantEntity.xpForLevel(14));
            FolkSkills.grant(level, master, FolkSkills.Knack.STEADY_HANDS);
            freeOfEachOther(master, other);
            master.insertItem(new ItemStack(Items.BREAD, 4));
            other.insertItem(new ItemStack(Items.BREAD, 4));
            VillageFolkEntity a = master.raiseChildWith(other);
            master.insertItem(new ItemStack(Items.BREAD, 4));
            other.insertItem(new ItemStack(Items.BREAD, 4));
            VillageFolkEntity b = master.raiseChildWith(other);
            helper.assertTrue(a != null && b != null, "two children: " + a + " " + b);
            int lesson = 1 + master.veteranLevel() / 5 + 1;
            a.apprenticeForTests(StationTask.MINE);
            b.apprenticeForTests(StationTask.MINE);
            Apprenticeship.lessonsForTests(a, master, 60 / lesson + 2);
            Apprenticeship.lessonsForTests(b, master, 2);
            helper.assertTrue(a.lines().skill() >= Apprenticeship.QUALIFY && b.lines().skill() < Apprenticeship.QUALIFY,
                "one far on, one not: " + a.lines().skill() + " / " + b.lines().skill());
            a.childhoodForTests(VillageFolkEntity.GROW_DAYS);
            b.childhoodForTests(VillageFolkEntity.GROW_DAYS);
            Kit.log("[VT] fl02 a " + a.lines().rank() + " " + a.stationTask() + " taught " + a.lines().taught() + "; b " + b.lines().rank() + " " + b.lines().skill());
            helper.assertTrue(Apprenticeship.JOURNEYMAN.equals(a.lines().rank()), "the one far on qualifies as a journeyman: " + a.lines().rank());
            helper.assertTrue(a.knacks().has(FolkSkills.Knack.STEADY_HANDS) && "steady_hands".equals(a.lines().taught()),
                "and its master passes on its specialisation");
            helper.assertTrue(master.lines().pupils().contains(a.displayNameCap()), "the master counts it among those it brought on");
            if (b.stationTask() == StationTask.MINE) {
                helper.assertTrue(Apprenticeship.APPRENTICE.equals(b.lines().rank()), "the other is still an apprentice: " + b.lines().rank());
                long day = level.getDayTime() / 24000L;
                for (int i = 0; i < 12 && Apprenticeship.APPRENTICE.equals(b.lines().rank()); i++) Apprenticeship.daily(level, b, day + i);
                helper.assertTrue(Apprenticeship.JOURNEYMAN.equals(b.lines().rank()), "and qualifies at work beside its master: " + b.lines().skill());
            }
            a.tradeXpForTests(StationTask.MINE, AssistantEntity.xpForLevel(Apprenticeship.MASTER_LEVEL));
            Apprenticeship.daily(level, a, level.getDayTime() / 24000L);
            helper.assertTrue(Apprenticeship.MASTER.equals(a.lines().rank()), "a journeyman at the master's level is a master: " + a.lines().rank());
            CompoundTag rep = Apprenticeship.report(id);
            helper.assertTrue(rep.getList("trades", 10).size() >= 1 && !rep.getList("lines", 8).isEmpty(), "the board has the trade and who taught whom: " + rep);
            helper.assertTrue(Apprenticeship.cardLine(a) != null, "its card says its place in the line");
            helper.succeed();
        });
    }

    // ============================================================ fl03: only witnesses speak

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "fl03_witness")
    public static void fl03_onlyWitnesses(GameTestHelper helper) {
        int x = 7410000;
        VillageFolkEntity a = founder(helper, x, Z, DAY + 5000);
        ServerLevel level = helper.getLevel();
        UUID id = a.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity b = another(helper, heart.east(3), id);
        VillageFolkEntity c = another(helper, heart.east(38), id);
        VillageFolkEntity d = another(helper, heart.east(42).south(3), id);
        ServerPlayer[] you = new ServerPlayer[1];
        helper.runAtTickTime(10, () -> {
            a.moveTo(heart.getX() + 0.5, heart.getY(), heart.getZ() + 0.5);
            b.moveTo(heart.getX() + 3.5, heart.getY(), heart.getZ() + 0.5);
            c.moveTo(heart.getX() + 38.5, heart.getY(), heart.getZ() + 0.5);
            d.moveTo(heart.getX() + 42.5, heart.getY(), heart.getZ() + 3.5);
            you[0] = player(helper, level, heart.south(4));
        });
        helper.runAtTickTime(14, () -> {
            ServerPlayer p = you[0];
            Witness.resetForTests();
            List<VillageFolkEntity> saw = Witness.seen(level, p, Witness.Kind.DEFENDED, "", null, 3);
            Kit.log("[VT] fl03 saw: " + saw.size() + " a " + saw.contains(a) + " b " + saw.contains(b) + " c " + saw.contains(c));
            helper.assertTrue(saw.contains(a) && saw.contains(b), "the two near the player with sight of it saw it");
            helper.assertTrue(!saw.contains(c) && !saw.contains(d) && c.lines().deeds().isEmpty() && d.lines().deeds().isEmpty(),
                "the two out of range saw nothing and remember nothing");
            Witness.dealt(a, p, Witness.Kind.TRADED, "bread");
            // Nothing of its own: it says nothing of the player in its own words.
            helper.assertTrue(Witness.aboutYou(c, p) == null && Witness.aboutYou(d, p) == null, "no contact, no claims");
            helper.assertTrue(!Witness.passOn(c, d, true, true), "a non-witness has nothing to pass on");
            // Talked to, asked for gossip and how the town sees the player: every word of the player is a witness's.
            for (int i = 0; i < 6; i++) {
                FolkTalk.answer(c, p, TalkTopic.GOSSIP, "");
                FolkTalk.answer(c, p, TalkTopic.REPUTE, "");
                FolkTalk.answer(c, p, TalkTopic.OPEN, "");
            }
            // A witness passes it on; the listener has it as the witness's word, and passes it on to nobody.
            helper.assertTrue(Witness.passOn(a, c, true, true), "a witness tells another folk");
            FolkLines.Deed heard = null;
            for (FolkLines.Deed x2 : c.lines().deeds()) if (x2.kind != Witness.Kind.TALKED) heard = x2;
            helper.assertTrue(heard != null && !heard.firstHand() && a.getUUID().equals(heard.source), "the listener has it on the witness's word");
            String told = Witness.aboutYou(c, p);
            Kit.log("[VT] fl03 hearsay to the player: " + told);
            helper.assertTrue(told != null && told.contains(a.displayNameCap()), "and says so, naming the witness: " + told);
            helper.assertTrue(!Witness.passOn(c, d, true, true) && d.lines().deeds().isEmpty(), "hearsay is never passed on to another folk");
            // The rule, over everything said: a speaker's own deed, or a real witness's word.
            int checked = 0;
            for (Witness.Said s : Witness.saidForTests()) {
                if (!s.player().equals(p.getUUID())) continue;
                checked++;
                VillageFolkEntity by = s.speaker().equals(a.getUUID()) ? a : s.speaker().equals(b.getUUID()) ? b : s.speaker().equals(c.getUUID()) ? c : d;
                if (s.source() == null) {
                    boolean own = false;
                    for (FolkLines.Deed x2 : by.lines().deeds()) own |= x2.firstHand() && x2.kind == s.kind() && x2.player.equals(p.getUUID());
                    helper.assertTrue(own, s.speakerName() + " said of the player what it never saw: " + s.line());
                } else {
                    VillageFolkEntity w = s.source().equals(a.getUUID()) ? a : s.source().equals(b.getUUID()) ? b : null;
                    helper.assertTrue(w != null, s.speakerName() + " named a witness who saw nothing: " + s.line());
                    boolean real = false;
                    for (FolkLines.Deed x2 : w.lines().deeds()) real |= x2.firstHand() && x2.kind == s.kind();
                    helper.assertTrue(real, "the witness named really saw it: " + s.line());
                    helper.assertTrue(by != w, "hearsay is not the witness's own");
                }
            }
            helper.assertTrue(checked >= 2, "lines were said of the player: " + checked);
            // A line naming the player, with no contact behind it, is screened out.
            List<String> said = new ArrayList<>(List.of("Did you hear? " + p.getName().getString() + " built the well.", "Rain again."));
            helper.assertTrue(Witness.screen(d, p, said).size() == 1, "a folk with no contact does not repeat news of the player");
            letGo(level, p);
            helper.succeed();
        });
    }

    // ============================================================ fl04: the town's opinion, from its witnesses

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "fl04_opinion")
    public static void fl04_townOpinion(GameTestHelper helper) {
        int x = 7415000;
        VillageFolkEntity a = founder(helper, x, Z, DAY + 5000);
        ServerLevel level = helper.getLevel();
        UUID id = a.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity b = another(helper, heart.east(3), id);
        VillageFolkEntity c = another(helper, heart.east(40), id);
        ServerPlayer[] you = new ServerPlayer[1];
        helper.runAtTickTime(10, () -> {
            a.moveTo(heart.getX() + 0.5, heart.getY(), heart.getZ() + 0.5);
            b.moveTo(heart.getX() + 3.5, heart.getY(), heart.getZ() + 0.5);
            c.moveTo(heart.getX() + 40.5, heart.getY(), heart.getZ() + 0.5);
            you[0] = player(helper, level, heart.south(4));
        });
        helper.runAtTickTime(14, () -> {
            ServerPlayer p = you[0];
            String who = p.getName().getString();
            String before = TownOpinion.ratingOf(id, p.getUUID(), level.getGameTime());
            // One who never met the player thinks badly of it (the town's word, no contact); it counts for little.
            c.persona().feelFor(p.getUUID(), who, -40);
            Witness.seen(level, p, Witness.Kind.DEFENDED, "", null, 0);
            a.persona().feelFor(p.getUUID(), who, 45);
            b.persona().feelFor(p.getUUID(), who, 35);
            Witness.dealt(b, p, Witness.Kind.SHORTED, "bread");
            Standing.stir(id, p.getUUID());
            TownOpinion.View v = TownOpinion.of(id, p.getUUID());
            List<String> lines = new ArrayList<>();
            for (TownOpinion.Reason r : v.reasons()) lines.add(TownOpinion.line(r));
            Kit.log("[VT] fl04 reasons: " + lines + " witnesses " + v.witnesses());
            helper.assertTrue(v.witnesses() == 2, "two witnesses: " + v.witnesses());
            boolean saw = false, shorted = false;
            for (TownOpinion.Reason r : v.reasons()) {
                if (r.text().equals("Saw you defend the square from a raid") && r.witnesses().contains(a.displayNameCap()) && r.witnesses().contains(b.displayNameCap())) saw = true;
                if (r.text().startsWith("Says you tried to short them on bread") && r.witnesses().equals(List.of(b.displayNameCap()))) shorted = true;
            }
            helper.assertTrue(saw, "the reason in the witnesses' words, by name: " + lines);
            helper.assertTrue(shorted, "the one shorted says so: " + lines);
            for (TownOpinion.Reason r : v.reasons()) helper.assertTrue(!r.witnesses().contains(c.displayNameCap()), "the one who saw nothing gives no reason");
            Standing.View s = Standing.of(id, p.getUUID(), level.getGameTime());
            Kit.log("[VT] fl04 standing " + s.score() + " " + s.title() + " (unweighted would be " + ((45 + 35 - 40) / 3) + ")");
            helper.assertTrue(s.score() > (45 + 35 - 40) / 3, "the witnesses outweigh the one who never met the player: " + s.score());
            helper.assertTrue(TownOpinion.weight(a, p.getUUID()) == 4 && TownOpinion.weight(c, p.getUUID()) == 1, "a witness counts four times");
            // More witnessed good: respect rises.
            String mid = TownOpinion.ratingOf(id, p.getUUID(), level.getGameTime());
            Witness.seen(level, p, Witness.Kind.RESCUED, "a zombie", c, 20);
            Standing.stir(id, p.getUUID());
            int after = Standing.of(id, p.getUUID(), level.getGameTime()).score();
            Kit.log("[VT] fl04 respect " + before + " -> " + mid + " -> " + TownOpinion.ratingOf(id, p.getUUID(), level.getGameTime()) + " (" + after + ")");
            helper.assertTrue(after > s.score(), "respect moves with witnessed deeds: " + s.score() + " -> " + after);
            CompoundTag rep = TownOpinion.report(id, p.getUUID(), level.getGameTime());
            helper.assertTrue(!rep.getString("rating").isEmpty() && !rep.getList("good", 8).isEmpty() && !rep.getList("bad", 8).isEmpty(),
                "the board's page has the rating and the reasons both ways: " + rep);
            letGo(level, p);
            helper.succeed();
        });
    }

    // ============================================================ fl05: a church wedding, dressed for what the town has

    @GameTest(template = EMPTY, timeoutTicks = 1200, batch = "fl05_wedding")
    public static void fl05_churchWedding(GameTestHelper helper) {
        int x = 7420000;
        VillageFolkEntity a = founder(helper, x, Z, DAY + 10000);
        ServerLevel level = helper.getLevel();
        UUID id = a.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity b = another(helper, heart.east(2), id);
        List<VillageFolkEntity> guests = new ArrayList<>();
        for (int i = 0; i < 4; i++) guests.add(another(helper, heart.offset(-3 - i, 0, 4), id));
        helper.runAtTickTime(10, () -> {
            freeOfEachOther(a, b);
            long day = level.getDayTime() / 24000L;
            Gatherings.pledged(id, a, b, day);
            // Poor: wildflowers, nothing out of the stores.
            Ledger.takeCoins(id, Ledger.coins(id));
            a.earn(60);
            b.earn(60);
            int put = WeddingDecor.dressNowForTests(level, v);
            List<String> what = WeddingDecor.placedForTests(id);
            Kit.log("[VT] fl05 poor: " + WeddingDecor.tierForTests(id) + " " + put + " " + what);
            helper.assertTrue(WeddingDecor.tierForTests(id) == WeddingDecor.Tier.PLAIN && what.contains("wildflowers") && !what.contains("carpet"),
                "a poor town's wedding: wildflowers: " + what);
            WeddingDecor.tidyNowForTests(level, v);
            // Rich: the stores stocked, the treasury full.
            Ledger.addCoins(id, 2000);
            Object[][] stock = { { Items.LANTERN, 16 }, { Items.RED_CARPET, 16 }, { Items.FLOWER_POT, 16 }, { Items.WHITE_BANNER, 4 },
                { Items.OAK_LEAVES, 16 }, { Items.PINK_STAINED_GLASS, 8 }, { Items.OAK_SLAB, 8 }, { Items.CAKE, 3 }, { Items.NOTE_BLOCK, 1 }, { Items.CANDLE, 8 } };
            for (Object[] s : stock) com.jrpetty.mcassistant.entity.Homes.storeForTests(level, v, new ItemStack((net.minecraft.world.item.Item) s[0], (Integer) s[1]));
            int purses = a.purse() + b.purse(), coins = Ledger.coins(id);
            int lanterns = com.jrpetty.mcassistant.entity.Market.stock(level, id, s -> s.is(Items.LANTERN));
            put = WeddingDecor.dressNowForTests(level, v);
            what = WeddingDecor.placedForTests(id);
            Kit.log("[VT] fl05 rich: " + WeddingDecor.tierForTests(id) + " " + put + " " + what + "; purses " + purses + "->" + (a.purse() + b.purse()));
            helper.assertTrue(WeddingDecor.tierForTests(id) == WeddingDecor.Tier.GRAND, "a rich town's wedding is grand");
            helper.assertTrue(what.contains("carpet") && what.contains("lanterns") && what.contains("an arch of flowers") && what.contains("a feast table"),
                "a carpet, lanterns, an arch and a feast table: " + what);
            helper.assertTrue(com.jrpetty.mcassistant.entity.Market.stock(level, id, s -> s.is(Items.LANTERN)) < lanterns, "out of the stores");
            helper.assertTrue(a.purse() + b.purse() < purses && Ledger.coins(id) > coins, "paid for by the couple, into the treasury");
            // The ceremony: guests take their places.
            helper.assertTrue(Assemblies.startNow(level, v, Assemblies.Kind.WEDDING), "the wedding begins");
        });
        int[] seated = new int[1];
        for (int t = 100; t <= 1000; t += 100) {
            helper.runAtTickTime(t, () -> {
                int[] prog = Assemblies.progress(id);
                if (prog != null) seated[0] = Math.max(seated[0], prog[1]);
                if (prog != null) Kit.log("[VT] fl05 assembly: " + Assemblies.stateForTests(id, level.getGameTime()) + " " + java.util.Arrays.toString(prog));
            });
        }
        helper.runAtTickTime(1100, () -> {
            int n = WeddingDecor.tidyNowForTests(level, v);
            Kit.log("[VT] fl05 taken down: " + n + "; stores lanterns " + com.jrpetty.mcassistant.entity.Market.stock(level, id, s -> s.is(Items.LANTERN)));
            helper.assertTrue(n > 0 && WeddingDecor.placedForTests(id).isEmpty(), "taken down after");
            helper.assertTrue(com.jrpetty.mcassistant.entity.Market.stock(level, id, s -> s.is(Items.LANTERN)) >= 1, "and back into the stores");
            helper.assertTrue(seated[0] > 0, "guests came and took their places: " + seated[0]);
            helper.succeed();
        });
    }

    // ============================================================ fl06: a player's courtship, wedding and child

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "fl06_player")
    public static void fl06_playerFamily(GameTestHelper helper) {
        int x = 7425000;
        VillageFolkEntity f = founder(helper, x, Z, DAY + 5000);
        ServerLevel level = helper.getLevel();
        UUID id = f.ownerId();
        BlockPos heart = Kit.surface(level, x, Z);
        ServerPlayer[] you = new ServerPlayer[1];
        helper.runAtTickTime(10, () -> you[0] = player(helper, level, heart.south(3)));
        helper.runAtTickTime(14, () -> {
            ServerPlayer p = you[0];
            String who = p.getName().getString();
            f.life().widowed();
            PlayerFamily.forgetForTests(id, p.getUUID());
            // Room for a child: a house of four beds.
            BlockPos hp = Kit.surface(level, heart.getX() - 16, heart.getZ() + 16);
            BuildGoal.stamp(level, "house", hp, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "house", hp, Direction.NORTH);
            Villages.recountBeds(id);
            // Not respected yet: no.
            String no = PlayerFamily.talk(f, p, "Will you walk out with me?");
            helper.assertTrue(PlayerFamily.courting(id, p.getUUID()) == null, "nobody walks out with a player the town does not respect: " + no);
            Witness.seen(level, p, Witness.Kind.DEFENDED, "", null, 0);
            f.persona().feelFor(p.getUUID(), who, 40);
            Witness.dealt(f, p, Witness.Kind.GIFT, "a poppy");
            Standing.stir(id, p.getUUID());
            String yes = PlayerFamily.talk(f, p, "Will you walk out with me?");
            Kit.log("[VT] fl06 walk out: " + no + " / " + yes);
            helper.assertTrue(f.getUUID().equals(PlayerFamily.courting(id, p.getUUID())), "walking out: " + yes);
            helper.assertTrue(yes.contains("Fondness"), "the reply shows how fond it is");
            helper.assertTrue(FolkTalk.talkContext(f, p).contains("pcourting"), "the talk screen shows the courting buttons");
            String early = PlayerFamily.talk(f, p, "Will you marry me?");
            helper.assertTrue(PlayerFamily.spouse(id, p.getUUID()) == null && !early.isEmpty(), "too soon to propose: " + early);
            PlayerFamily.talk(f, p, "Take a walk with me");
            level.setDayTime(level.getDayTime() + 24000L);
            PlayerFamily.talk(f, p, "Take a walk with me");
            p.getInventory().add(new ItemStack(com.jrpetty.mcassistant.McAssistantMod.VILLAGE_COIN.get(), 20));
            String sup = PlayerFamily.talk(f, p, "Supper with me tonight?");
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.POPPY, 2));
            FolkTalk.answer(f, p, TalkTopic.GIFT, "");
            f.persona().feelFor(p.getUUID(), who, 80);
            Kit.log("[VT] fl06 progress: " + PlayerFamily.progress(id, p.getUUID(), f) + "; supper " + sup);
            helper.assertTrue(PlayerFamily.proposable(f, p), "two walks, a supper and a gift: ready: " + PlayerFamily.progress(id, p.getUUID(), f));
            helper.assertTrue(FolkTalk.talkContext(f, p).contains("proposable"), "the proposal button shows");
            String sure = PlayerFamily.talk(f, p, "Will you marry me?");
            helper.assertTrue(PlayerFamily.spouse(id, p.getUUID()) == null && Gatherings.tonight(id, level.getDayTime() / 24000L) != Gatherings.Kind.WEDDING
                && sure.contains("again"), "a proposal is confirmed first: " + sure);
            String said = PlayerFamily.talk(f, p, "Will you marry me?");
            long day = level.getDayTime() / 24000L;
            helper.assertTrue(Gatherings.tonight(id, day) == Gatherings.Kind.WEDDING, "the town holds the wedding: " + said);
            PlayerFamily.wed(level, id, f.getUUID(), p.getUUID());
            helper.assertTrue(PlayerFamily.isSpouse(f, p) && FolkTalk.talkContext(f, p).contains("spouse"), "wed");
            String home = PlayerFamily.talk(f, p, "How was your day, love?");
            helper.assertTrue(home != null && !home.startsWith("My day? Fine"), "it talks as a spouse: " + home);
            // A baby: asked, the news, the birth at home, named, and growing up.
            String baby = PlayerFamily.talk(f, p, "Shall we try for a baby?");
            helper.assertTrue(PlayerFamily.trying(id, p.getUUID()), "trying for a baby: " + baby);
            PlayerFamily.conceiveForTests(level, f, p);
            helper.assertTrue(PlayerFamily.pregnantDay(id, p.getUUID()) >= 0, "expecting");
            VillageFolkEntity child = PlayerFamily.bornForTests(level, f, p);
            helper.assertTrue(child != null && child.isBaby() && child.parentIds().contains(f.getUUID()) && child.parentIds().contains(p.getUUID()),
                "a child born to them both");
            helper.assertTrue(FolkTalk.talkContext(f, p).contains("naming"), "the naming button shows");
            String first = PlayerFamily.talk(f, p, "Let's call the baby Wren");
            helper.assertTrue(!child.displayNameCap().startsWith("Wren"), "a name is confirmed first: " + first);
            String named = PlayerFamily.talk(f, p, "Let's call the baby Wren");
            Kit.log("[VT] fl06 named: " + named + " -> " + child.displayNameCap());
            helper.assertTrue(child.displayNameCap().startsWith("Wren"), "named by the player: " + child.displayNameCap());
            helper.assertTrue(PlayerFamily.stage(child).equals("a baby"), "a baby: " + PlayerFamily.stage(child));
            child.bornDaysAgo(2);
            helper.assertTrue(PlayerFamily.stage(child).equals("a youngster") || PlayerFamily.stage(child).equals("a child"), "older: " + PlayerFamily.stage(child));
            child.childhoodForTests(VillageFolkEntity.GROW_DAYS);
            helper.assertTrue(!child.isBaby() && PlayerFamily.stage(child).equals("grown"), "grown up");
            helper.assertTrue(PlayerFamily.children(level, id, p.getUUID()).contains(child), "on the player's family");
            letGo(level, p);
            helper.succeed();
        });
    }

    // ============================================================ fl07: the school

    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "fl07_school")
    public static void fl07_school(GameTestHelper helper) {
        int x = 7430000;
        VillageFolkEntity mother = founder(helper, x, Z, DAY + School.LESSON_FROM - 400);
        ServerLevel level = helper.getLevel();
        UUID id = mother.ownerId();
        Villages.Village v = Villages.get(id);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkEntity father = another(helper, heart.east(2), id);
        VillageFolkEntity[] kids = new VillageFolkEntity[3];
        ServerPlayer[] you = new ServerPlayer[1];
        helper.runAtTickTime(10, () -> {
            freeOfEachOther(mother, father);
            for (int i = 0; i < 3; i++) {
                mother.insertItem(new ItemStack(Items.BREAD, 4));
                father.insertItem(new ItemStack(Items.BREAD, 4));
                kids[i] = mother.raiseChildWith(father);
                helper.assertTrue(kids[i] != null, "a child");
                kids[i].bornDaysAgo(1);
            }
            helper.assertTrue(School.wanted(id, School.FOLK), "a town of ten with three children wants a school");
            // No school yet: taught at home.
            mother.setStation(heart.offset(8, 0, 0), StationTask.FARM);
            father.setStation(heart.offset(-8, 0, 0), StationTask.FARM);
            mother.tradeXpForTests(StationTask.FARM, AssistantEntity.xpForLevel(6));
            level.setDayTime(DAY + School.LESSON_FROM + 200);
        });
        int[] before = new int[3];
        helper.runAtTickTime(20, () -> {
            VillageFolkEntity k = kids[0];
            mother.setAgeForTests(70);
            mother.lines().deeds();                 // (a touch)
            for (int i = 0; i < 3; i++) before[i] = kids[i].xpInTrade(StationTask.FARM) + kids[i].xpInTrade(StationTask.MINE) + kids[i].xpInTrade(StationTask.WOOD);
            boolean home = SchoolDays.homeLesson(k, level);
            Kit.log("[VT] fl07 home lesson: " + home + " " + k.hobbyNow());
            helper.assertTrue(home || SchoolDays.educationLine(level, id).contains("taught at home"), "with no school, taught at home: " + SchoolDays.educationLine(level, id));
            // The schoolhouse, built; a teacher hired.
            BlockPos at = Kit.surface(level, heart.getX() + 14, heart.getZ() - 14);
            BuildGoal.stamp(level, "school", at, Direction.NORTH, 13, Showcase.painter(Showcase.OAK));
            Ledger.built(id, "school", at, Direction.NORTH);
            helper.assertTrue(School.stands(id), "the school stands");
            VillageFolkEntity teacher = School.teacher(level, id, true);
            Kit.log("[VT] fl07 teacher: " + (teacher == null ? "none" : teacher.displayNameCap()) + "; " + SchoolDays.educationLine(level, id));
            helper.assertTrue(teacher != null && School.isTeacher(teacher), "a teacher hired");
            School.callLesson(level, id, 1400);
            Blink.reset();
            Blink.setOn(true);
            you[0] = player(helper, level, at.south(10));
            Blink.watchForTests(you[0]);
        });
        helper.runAtTickTime(1500, () -> {
            int rose = 0;
            for (int i = 0; i < 3; i++) {
                int now = kids[i].xpInTrade(StationTask.FARM) + kids[i].xpInTrade(StationTask.MINE) + kids[i].xpInTrade(StationTask.WOOD);
                int all = 0;
                for (StationTask t : StationTask.values()) all += kids[i].xpInTrade(t);
                Kit.log("[VT] fl07 child " + i + " " + kids[i].hobbyNow() + " xp " + before[i] + " -> " + all);
                if (all > before[i]) rose++;
            }
            long jumps = Blink.seenJumps();
            Kit.log("[VT] fl07 seen jumps " + jumps + "\n" + Blink.report());
            letGo(level, you[0]);
            Blink.setOn(false);
            helper.assertTrue(rose >= 2, "the children attend and learn: " + rose + " of 3");
            helper.assertTrue(jumps == 0, "nobody seen to jump: " + Blink.seenLines());
            helper.succeed();
        });
    }
}
