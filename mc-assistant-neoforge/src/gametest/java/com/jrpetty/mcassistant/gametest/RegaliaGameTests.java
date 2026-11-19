package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Elections;
import com.jrpetty.mcassistant.entity.Government;
import com.jrpetty.mcassistant.entity.Homeland;
import com.jrpetty.mcassistant.entity.Identity;
import com.jrpetty.mcassistant.entity.Regalia;
import com.jrpetty.mcassistant.entity.Vassals;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [regalia19] The town's ruler, dressed for it (entity/Regalia): what each folk wears goes to the client in one synced
 * number (VillageFolkEntity.clientRegalia), and its title on its nameplate.
 * <ul>
 * <li><b>rg19_01</b>: a lordship: the lord wears the crown (a monarch), its heir and its partner the coronet, the rest
 *     nothing; "Lord Alder of Rushscar" on its plate, and "King" once another town is its vassal; the town's two colours
 *     sent with it. The lord dies: the heir succeeds and takes the crown, and the old lord's partner takes off its
 *     coronet.</li>
 * <li><b>rg19_02</b>: an elected leader: a mayor in the cherry groves wears the chain of office, and an election hands it
 *     to the next (the old one plain again, on its own, a few seconds on); a chief in the jungle, an elder on the plains;
 *     the council's speaker an elder and the rest of the council the circlet; the guildmaster, the chaplain.</li>
 * </ul>
 * Each on its own ground in x 4900000-4909999, z 90000, in a batch of its own.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class RegaliaGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 90000;

    private static Villages.Village town(GameTestHelper helper, int x, int n) {
        ServerLevel level = helper.getLevel();
        Kit.hold(level, x, Z, 40);
        Kit.prepare(level, x, Z, 40);
        BlockPos heart = Kit.surface(level, x, Z);
        VillageFolkSpawnerBlock.raiseParty(level, heart, 0.0F, n);
        Villages.Village v = Villages.nearest(level, heart, Villages.VILLAGE_RANGE);
        helper.assertTrue(v != null, "a town at " + x);
        for (VillageFolkEntity f : folk(v.id())) f.ensurePersona();
        return v;
    }

    private static List<VillageFolkEntity> folk(UUID v) {
        List<VillageFolkEntity> out = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v)) if (a instanceof VillageFolkEntity f && !f.isBaby() && f.isAlive()) out.add(f);
        return out;
    }

    private static VillageFolkEntity find(UUID v, UUID who) {
        for (VillageFolkEntity f : folk(v)) if (f.getUUID().equals(who)) return f;
        return null;
    }

    /** The court worked out now and every folk's dress sent. */
    private static void dress(UUID v) {
        Regalia.freshForTests(v);
        for (VillageFolkEntity f : folk(v)) f.showRegalia();
    }

    private static Regalia.Kind worn(VillageFolkEntity f) {
        return Regalia.kindOf(f.clientRegalia());
    }

    private static void start(ServerLevel level) {
        Kit.reset(level);
        level.setDayTime(2000);
        level.updateSkyBrightness();
        Identity.liveForTests();
        Regalia.resetForTests();
    }

    // ============================================================ rg19_01: the crown, the house, the succession

    @GameTest(template = EMPTY, timeoutTicks = 300, batch = "rg19_01_lord")
    public static void rg19_01_lord(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 4900000;
        Villages.Village v = town(helper, x, 7);
        Villages.Village subject = town(helper, x + 400, 4);
        final VillageFolkEntity[] heir = { null }, partner = { null }, other = { null };
        final UUID[] lord = { null };
        helper.runAtTickTime(10, () -> {
            UUID id = v.id();
            Identity.seedForTests(level, v);
            Government.setForTests(level, v, Government.Form.LORD);
            lord[0] = Villages.elder(id);
            helper.assertTrue(lord[0] != null, "a lord in the seat: " + Identity.summary(id));
            VillageFolkEntity lordFolk = find(id, lord[0]);
            List<VillageFolkEntity> others = new ArrayList<>();
            for (VillageFolkEntity f : folk(id)) if (!f.getUUID().equals(lord[0])) others.add(f);
            heir[0] = others.get(0);
            heir[0].parentIds().add(lord[0]);
            heir[0].setAgeForTests(34);
            partner[0] = others.get(1);
            partner[0].life().partnerWith(lord[0], lordFolk.displayNameCap());
            lordFolk.life().partnerWith(partner[0].getUUID(), partner[0].displayNameCap());
            other[0] = others.get(2);
            dress(id);
            String plate = Regalia.plate(lordFolk).getString();
            int p = lordFolk.clientRegalia();
            Kit.log("[VT] rg19_01 the court: lord " + plate + " " + worn(lordFolk) + " colours " + Regalia.mainOf(p) + "/" + Regalia.accentOf(p)
                + "; heir " + Regalia.plate(heir[0]).getString() + " " + worn(heir[0]) + "; partner " + Regalia.plate(partner[0]).getString()
                + " " + worn(partner[0]) + "; another " + worn(other[0]));
            helper.assertTrue(worn(lordFolk) == Regalia.Kind.MONARCH, "the lord wears the crown: " + worn(lordFolk));
            helper.assertTrue(worn(heir[0]) == Regalia.Kind.ROYAL && worn(partner[0]) == Regalia.Kind.ROYAL, "its heir and partner the coronet");
            helper.assertTrue(worn(other[0]) == Regalia.Kind.NONE, "and the rest nothing");
            helper.assertTrue(plate.startsWith("Lord " + lordFolk.displayNameCap() + " of " + Villages.name(id)), "the plate: " + plate);
            helper.assertTrue(Regalia.plate(heir[0]).getString().contains("heir of") && Regalia.plate(partner[0]).getString().contains("consort of"),
                "the heir and the consort named");
            helper.assertTrue(Regalia.mainOf(p) != Regalia.accentOf(p), "two colours of the town's");
            // A vassal town makes the lordship a kingdom.
            String made = Vassals.makeForTests(level, id, subject.id(), level.getDayTime() / 24000L);
            dress(id);
            Kit.log("[VT] rg19_01 a vassal (" + made + "): " + Regalia.plate(lordFolk).getString() + "; " + Regalia.titleOf(lordFolk));
            helper.assertTrue("King".equals(Regalia.titleOf(lordFolk)), "a lord with a vassal is a king: " + Regalia.titleOf(lordFolk));
            lordFolk.kill();
        });
        helper.runAtTickTime(20, () -> {
            UUID id = v.id();
            Elections.tick(level, v);
            dress(id);
            Kit.log("[VT] rg19_01 after the lord's death: elder " + Villages.elderName(id) + "; heir " + worn(heir[0]) + " "
                + Regalia.plate(heir[0]).getString() + "; old partner " + worn(partner[0]) + "; another " + worn(other[0]));
            helper.assertTrue(heir[0].getUUID().equals(Villages.elder(id)), "the heir succeeds");
            helper.assertTrue(worn(heir[0]) == Regalia.Kind.MONARCH, "and takes the crown: " + worn(heir[0]));
            helper.assertTrue(worn(partner[0]) == Regalia.Kind.NONE, "the old lord's partner takes off its coronet: " + worn(partner[0]));
            helper.assertTrue(worn(other[0]) == Regalia.Kind.NONE, "the rest as they were");
            helper.succeed();
        });
    }

    // ============================================================ rg19_02: elected, elders, chiefs, guilds

    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "rg19_02_elected")
    public static void rg19_02_elected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        start(level);
        int x = 4902000;
        Villages.Village v = town(helper, x, 6);
        final VillageFolkEntity[] first = { null }, second = { null };
        helper.runAtTickTime(10, () -> {
            UUID id = v.id();
            long day = level.getDayTime() / 24000L;
            Identity.seedForTests(level, v);
            Government.setForTests(level, v, Government.Form.REEVE);
            Homeland.setForTests(id, Homeland.Land.MEADOW);
            List<VillageFolkEntity> all = folk(id);
            first[0] = all.get(0);
            second[0] = all.get(1);
            Villages.electElder(id, first[0], day);
            dress(id);
            Kit.log("[VT] rg19_02 the mayor: " + Regalia.plate(first[0]).getString() + " " + worn(first[0]));
            helper.assertTrue(worn(first[0]) == Regalia.Kind.ELECTED && "Mayor".equals(Regalia.titleOf(first[0])), "a mayor's chain of office");
            helper.assertTrue(Regalia.plate(first[0]).getString().startsWith("Mayor " + first[0].displayNameCap() + " of "), "on the plate");
            // An election: the chain changes hands, the folk's own ticks seeing to it.
            Villages.electElder(id, second[0], day);
        });
        helper.runAtTickTime(90, () -> {
            UUID id = v.id();
            long day = level.getDayTime() / 24000L;
            Kit.log("[VT] rg19_02 after the election, on their own: new " + worn(second[0]) + ", old " + worn(first[0]));
            helper.assertTrue(worn(second[0]) == Regalia.Kind.ELECTED, "the new mayor wears the chain: " + worn(second[0]));
            helper.assertTrue(worn(first[0]) == Regalia.Kind.NONE, "the old one is plain again: " + worn(first[0]));
            // The land's own titles and dress.
            String[] want = { "JUNGLE:CHIEF:Chief", "PLAINS:ELDER:Elder", "MOUNTAIN:ELECTED:Thane", "BADLANDS:CHIEF:Headman" };
            for (String w : want) {
                String[] p = w.split(":");
                Homeland.setForTests(id, Homeland.Land.valueOf(p[0]));
                dress(id);
                Kit.log("[VT] rg19_02 " + p[0] + ": " + Regalia.plate(second[0]).getString() + " " + worn(second[0]));
                helper.assertTrue(worn(second[0]) == Regalia.Kind.valueOf(p[1]) && p[2].equals(Regalia.titleOf(second[0])), w + ": " + worn(second[0]));
            }
            // A council of elders: its speaker an elder, the rest of the council the circlet.
            Government.setForTests(level, v, Government.Form.COUNCIL);
            Villages.electElder(id, second[0], day);
            dress(id);
            int councillors = 0;
            for (VillageFolkEntity f : folk(id)) if (worn(f) == Regalia.Kind.COUNCILLOR) councillors++;
            Kit.log("[VT] rg19_02 the council: " + Regalia.plate(second[0]).getString() + " " + worn(second[0]) + "; councillors " + councillors);
            helper.assertTrue(worn(second[0]) == Regalia.Kind.ELDER && "Speaker".equals(Regalia.titleOf(second[0])), "the speaker of the elders");
            helper.assertTrue(councillors >= 2, "the council's elders: " + councillors);
            Government.setForTests(level, v, Government.Form.GUILD);
            Villages.electElder(id, second[0], day);
            dress(id);
            helper.assertTrue(worn(second[0]) == Regalia.Kind.ELECTED && "Guildmaster".equals(Regalia.titleOf(second[0])), "the guildmaster");
            int councillorsAfter = 0;
            for (VillageFolkEntity f : folk(id)) if (worn(f) == Regalia.Kind.COUNCILLOR) councillorsAfter++;
            helper.assertTrue(councillorsAfter == 0, "no council in a guild republic: " + councillorsAfter);
            Government.setForTests(level, v, Government.Form.CHAPLAIN);
            dress(id);
            VillageFolkEntity chaplain = find(id, Villages.elder(id));
            Kit.log("[VT] rg19_02 the chaplain: " + (chaplain == null ? "none" : Regalia.plate(chaplain).getString() + " " + worn(chaplain)));
            helper.assertTrue(chaplain != null && worn(chaplain) == Regalia.Kind.ELDER && "Chaplain".equals(Regalia.titleOf(chaplain)), "the chaplain");
            helper.succeed();
        });
    }
}
