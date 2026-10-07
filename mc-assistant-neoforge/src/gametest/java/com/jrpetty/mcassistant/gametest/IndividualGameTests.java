package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Dreams;
import com.jrpetty.mcassistant.entity.Fears;
import com.jrpetty.mcassistant.entity.FolkTalk;
import com.jrpetty.mcassistant.entity.Habits;
import com.jrpetty.mcassistant.entity.Individual;
import com.jrpetty.mcassistant.entity.Keepsakes;
import com.jrpetty.mcassistant.entity.Looks;
import com.jrpetty.mcassistant.entity.Manner;
import com.jrpetty.mcassistant.entity.Market;
import com.jrpetty.mcassistant.entity.Persona;
import com.jrpetty.mcassistant.entity.Social;
import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.ZoneChests;
import com.jrpetty.mcassistant.item.IndividualItems;
import com.jrpetty.mcassistant.village.Chronicle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * [individual] Every folk its own person (Looks, Individual, Manner, Fears, Habits, Dreams, Keepsakes): faces that
 * differ where the old ten skins gave the same one, a child's looks taken from its parents (a grandparent's blue eyes
 * coming back), twins alike, a hundred founders with no two alike; grey hair with the years, the stoop and the stick,
 * spectacles made by the smith out of the stores and worn; a scar from a real wound and none from a fall; the tallest
 * still through a door and into a bed, and height taken from the parents; a dream worked towards and come true (the
 * mood and the chronicle); the dark-fearing home before dusk, a fear overcome, the trades a fear will not take; a pipe on
 * the step at dusk, the doorstep tidied; the favourite place gone to in free time; a keepsake carried, kept from the
 * stores and from a player, handed down; and the voice, higher for a child and lower for the old and the big.
 *
 * <p>Each on its own ground, x 1520000 to 1538000 on z 66000, a batch of its own, the logic called directly.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class IndividualGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    private record Town(UUID village, Villages.Village v, BlockPos heart, Container stores, List<VillageFolkEntity> folk) {}

    /** A town of so many folk at x, in this age, its stores emptied and a marked chest of these goods by the heart. */
    private static Town town(GameTestHelper helper, int x, int n, long time, ItemStack... goods) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        Kit.noLeftoverPlayers(level);
        Habits.resetForTests();
        Fears.resetForTests();
        Manner.resetForTests();
        Dreams.resetForTests();
        Keepsakes.resetForTests();
        level.setDayTime(time);
        level.updateSkyBrightness();
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = Kit.surface(level, x, Z);
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, i == 0 ? heart : heart.south(2 + i), 0.0F);
            helper.assertTrue(f != null && (folk.isEmpty() || folk.get(0).ownerId().equals(f.ownerId())), "folk " + i);
            folk.add(f);
        }
        UUID village = folk.get(0).ownerId();
        for (BlockPos p : Villages.storeChests(level, village)) {
            if (level.getBlockEntity(p) instanceof Container c) {
                c.clearContent();
                c.setChanged();
            }
        }
        BlockPos chest = Kit.surface(level, heart.getX() + 4, heart.getZ());
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 3);
        ZoneChests.mark(level, chest);
        Container box = (Container) level.getBlockEntity(chest);
        for (int i = 0; i < goods.length; i++) box.setItem(i, goods[i]);
        Villages.forgetStores(village);
        Villages.forgetStock();
        Villages.ageForTests(village, Villages.Age.IRON);
        for (VillageFolkEntity f : folk) {
            f.ensurePersona();
            f.life().setTraitsForTests(Social.Trait.EASYGOING, Social.Trait.CHEERFUL);
            Individual.ensure(f);
            f.clearQueue();
        }
        return new Town(village, Villages.get(village), heart, box, folk);
    }

    /** These genes on this folk: a skin, a pair of each hair and eye colour, a height. */
    private static void genes(VillageFolkEntity f, boolean male, int tone, int hairA, int hairB, int eyeA, int eyeB, float stature) {
        Individual.ensure(f);
        Looks.Genes g = f.individual().genes;
        g.male = male;
        g.tone = tone;
        g.hairA = hairA;
        g.hairB = hairB;
        g.eyeA = eyeA;
        g.eyeB = eyeB;
        g.stature = stature;
        g.rolled = true;
        Individual.refreshLook(f);
    }

    /** The face the old ten skins gave a folk: by its id alone (client/FolkLooks.skin). */
    private static int oldSkin(UUID id) {
        long bits = id.getLeastSignificantBits() ^ (id.getMostSignificantBits() >>> 7);
        return (int) Math.floorMod(bits ^ (bits >>> 31), 10L);
    }

    /** A folk not in the world: for its genes. */
    private static VillageFolkEntity unborn(ServerLevel level) {
        VillageFolkEntity c = McAssistantMod.VILLAGE_FOLK.get().create(level);
        c.setUUID(UUID.randomUUID());
        return c;
    }

    private static long face(long look) {
        return look & ~(0b111111L << 38) & ~(0b111L << 44) & ~(0b11L << 47);
    }

    private static boolean chronicled(UUID village, String words) {
        for (Chronicle.Entry e : Chronicle.of(village)) if (e.text().toLowerCase(Locale.ROOT).contains(words)) return true;
        return false;
    }

    private static BlockPos bed(ServerLevel level, BlockPos foot) {
        level.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH)
            .setValue(BedBlock.PART, BedPart.FOOT), 3);
        level.setBlock(foot.north(), Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH)
            .setValue(BedBlock.PART, BedPart.HEAD), 3);
        return foot.north();
    }

    // ============================================================ id01: faces, inherited

    /**
     * Two children whose ids the old ten skins put in the same bucket (the same face, for ever) are born to different
     * parents and look different. A child's skin is a tone between its parents' and its hair and eyes are theirs; two
     * brown-eyed parents who each carry blue have a blue-eyed child now and then, as their parents did; twins are alike;
     * and a hundred founders are a hundred faces.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "id01_faces_inherited")
    public static void id01_faces_inherited(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1520000, 4, 6000L);
        VillageFolkEntity a = t.folk().get(0), b = t.folk().get(1), c = t.folk().get(2), d = t.folk().get(3);
        genes(a, true, 1, 7, 7, 5, 5, 0.3F);          // fair, blonde, blue
        genes(b, false, 2, 9, 9, 6, 6, 0.0F);         // fair, flaxen, grey
        genes(c, true, 8, 0, 0, 0, 0, 0.5F);          // dark brown, black, brown
        genes(d, false, 9, 1, 1, 1, 1, -0.2F);        // very dark, dark brown, dark
        // Two children in the old faces' same bucket.
        VillageFolkEntity k1 = null, k2 = null;
        for (int i = 0; i < 400 && k2 == null; i++) {
            VillageFolkEntity k = unborn(level);
            if (k1 == null) k1 = k;
            else if (oldSkin(k.getUUID()) == oldSkin(k1.getUUID())) k2 = k;
        }
        helper.assertTrue(k2 != null, "two ids in one of the old ten faces' buckets");
        Individual.born(k1, a, b);
        Individual.born(k2, c, d);
        long l1 = k1.clientLook(), l2 = k2.clientLook();
        Kit.log("id01 same old face " + oldSkin(k1.getUUID()) + ": " + Looks.describe(l1) + " | " + Looks.describe(l2));
        helper.assertTrue(face(l1) != face(l2), "the same old face, different parents, now different faces");
        // Thirty children of each couple: their skin between their parents', their hair and eyes their parents'.
        int blueOfBrown = 0;
        for (int i = 0; i < 30; i++) {
            VillageFolkEntity x = unborn(level), y = unborn(level);
            Individual.born(x, a, b);
            Individual.born(y, c, d);
            Looks.Genes gx = x.individual().genes, gy = y.individual().genes;
            helper.assertTrue(gx.tone >= 0 && gx.tone <= 3, "a fair couple's child is fair: tone " + gx.tone);
            helper.assertTrue(gy.tone >= 7, "a dark couple's child is dark: tone " + gy.tone);
            helper.assertTrue((gx.hairA == 7 || gx.hairA == 9) && (gx.hairB == 7 || gx.hairB == 9), "its hair is its parents'");
            helper.assertTrue(gy.eyeA <= 1 && gy.eyeB <= 1, "its eyes are its parents'");
        }
        // Brown-eyed parents who both carry blue.
        genes(a, true, 3, 2, 7, 0, 5, 0.0F);
        genes(b, false, 3, 2, 7, 0, 5, 0.0F);
        for (int i = 0; i < 60; i++) {
            VillageFolkEntity x = unborn(level);
            Individual.born(x, a, b);
            if (Looks.eyeColour(x.individual().genes) == 5) blueOfBrown++;
        }
        Kit.log("id01 blue-eyed children of two brown-eyed parents: " + blueOfBrown + " of 60");
        helper.assertTrue(blueOfBrown > 0 && blueOfBrown < 40, "a grandparent's blue eyes come back now and then: " + blueOfBrown);
        // Twins.
        VillageFolkEntity tw1 = unborn(level), tw2 = unborn(level);
        Individual.born(tw1, c, d);
        Individual.born(tw2, c, d);
        Individual.twins(List.of(tw1, tw2));
        helper.assertTrue(face(tw1.clientLook()) == face(tw2.clientLook()), "twins are alike");
        // A hundred founders.
        Set<Long> faces = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            VillageFolkEntity x = unborn(level);
            Looks.founder(x.individual().genes, x.getUUID(), null);
            faces.add(face(Looks.pack(x.individual().genes, 30, false, StationTask.FARM, 1)));
        }
        Kit.log("id01 a hundred founders: " + faces.size() + " faces");
        helper.assertTrue(faces.size() == 100, "a hundred founders, a hundred faces: " + faces.size());
        helper.succeed();
    }

    // ============================================================ id02: the years

    /**
     * Grey at the temples in its fifties and white at the last; a man with the gene going bald; the lines of age; a stoop
     * past seventy-five. An old reader's spectacles made by the smith's hand out of the stores' gold and glass and worn
     * (the five in a hundred at close work); the oldest's walking stick out of the stores.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "id02_the_years")
    public static void id02_the_years(GameTestHelper helper) {
        Town t = town(helper, 1522000, 3, 6000L, new ItemStack(Items.GOLD_NUGGET, 18), new ItemStack(Items.GLASS, 12),
            new ItemStack(Items.STICK, 4), new ItemStack(Items.FEATHER, 4));
        VillageFolkEntity f = t.folk().get(0);
        genes(f, true, 4, 1, 1, 0, 0, 0.0F);
        f.individual().genes.balding = true;
        f.individual().genes.greying = 1;
        f.individual().genes.style = Looks.SHORT;
        int last = -1;
        StringBuilder seen = new StringBuilder();
        for (int age : new int[]{25, 45, 55, 62, 70, 80, 95}) {
            f.setAgeForTests(age);
            Individual.refreshLook(f);
            long look = f.clientLook();
            int grey = Looks.grey(look);
            seen.append(age).append(": grey ").append(grey).append(", lines ").append(Looks.lines(look)).append(", ")
                .append(Looks.STYLE_WORDS[Looks.style(look)]).append(", stoop ").append(Individual.stoopOf(f.clientMarks())).append("; ");
            helper.assertTrue(grey >= last, "grey never goes back: " + seen);
            last = grey;
            if (age == 25) helper.assertTrue(grey == 0 && Looks.lines(look) == 0 && Looks.style(look) == Looks.SHORT, "young: " + seen);
            if (age >= 45) helper.assertTrue(Looks.style(look) == Looks.BALDING, "the gene for it: bald by forty-five: " + seen);
            if (age == 95) helper.assertTrue(grey == 5 && Looks.lines(look) == 3 && Individual.stoopOf(f.clientMarks()) == 3, "very old: " + seen);
        }
        Kit.log("id02 " + seen);
        // Spectacles, for an old reader at close work.
        VillageFolkEntity reader = t.folk().get(1);
        reader.setJob(StationTask.ENCHANT);
        reader.setAgeForTests(70);
        reader.individual().literate = true;
        int nuggets = Market.stock(helper.getLevel(), t.village(), s -> s.is(Items.GOLD_NUGGET));
        helper.assertTrue(Keepsakes.wantsSpectacles(reader), "an old enchanter who reads wants spectacles");
        Keepsakes.comeByForTests(reader);
        int after = Market.stock(helper.getLevel(), t.village(), s -> s.is(Items.GOLD_NUGGET));
        Kit.log("id02 spectacles: worn " + Keepsakes.wearsSpectacles(reader) + "; gold nuggets " + nuggets + " -> " + after
            + "; pace " + Keepsakes.spectaclesPercent(reader));
        helper.assertTrue(Keepsakes.wearsSpectacles(reader), "made by the smith out of the stores, and worn");
        helper.assertTrue(after < nuggets, "out of the stores' gold: " + nuggets + " -> " + after);
        helper.assertTrue(Individual.specsOf(reader.clientMarks()), "on its face");
        helper.assertTrue(Keepsakes.spectaclesPercent(reader) == 5, "close work quicker in them");
        // The oldest's walking stick.
        VillageFolkEntity oldest = t.folk().get(2);
        oldest.setAgeForTests(86);
        Keepsakes.comeByForTests(oldest);
        helper.assertTrue(Keepsakes.hasStick(oldest) && Individual.stickOf(oldest.clientMarks()), "a stick out of the stores, in its hand");
        helper.assertTrue(Manner.gait(oldest) == Manner.OLD, "and the old walk");
        helper.succeed();
    }

    // ============================================================ id03: a scar

    /** A heavy wound from a zombie leaves a scar across its cheek, its story and its card's word for it; a fall does not. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "id03_a_scar")
    public static void id03_a_scar(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1524000, 2, 6000L);
        VillageFolkEntity f = t.folk().get(0), g = t.folk().get(1);
        f.setInvulnerable(false);
        Zombie z = EntityType.ZOMBIE.create(level);
        z.moveTo(f.getX() + 1, f.getY(), f.getZ());
        helper.assertTrue(Individual.scarForTests(f) == 0, "no scar to begin with");
        f.hurt(level.damageSources().mobAttack(z), 8.5F);
        g.hurt(level.damageSources().fall(), 9.0F);
        String looks = Individual.looksLine(f);
        Kit.log("id03 " + f.displayNameCap() + ": " + looks + " | story: " + Individual.about(f));
        helper.assertTrue(Individual.scarForTests(f) > 0, "a zombie's blow left a scar");
        helper.assertTrue(Individual.scarOf(f.clientMarks()) > 0, "drawn on its face");
        helper.assertTrue(looks.contains("scar") && looks.contains("zombie"), "and on its card: " + looks);
        helper.assertTrue(Individual.scarForTests(g) == 0, "a fall leaves none");
        helper.succeed();
    }

    // ============================================================ id04: height

    /**
     * The tallest folk's hitbox is no taller than a villager's: it finds a way through a door two high and lies down in a
     * bed. The shortest's is a touch lower. And height is its parents': the children of two tall folk stand taller than
     * those of two short ones.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "id04_height")
    public static void id04_height(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1526000, 4, 6000L);
        VillageFolkEntity tall = t.folk().get(0), shortest = t.folk().get(1), tall2 = t.folk().get(2), short2 = t.folk().get(3);
        genes(tall, true, 3, 2, 2, 0, 0, 1.0F);
        genes(tall2, false, 3, 2, 2, 0, 0, 1.0F);
        genes(shortest, false, 3, 2, 2, 0, 0, -1.0F);
        genes(short2, true, 3, 2, 2, 0, 0, -1.0F);
        float hTall = Looks.heightOfStep(Looks.heightStepOf(tall.clientLook()));
        float hShort = Looks.heightOfStep(Looks.heightStepOf(shortest.clientLook()));
        Kit.log("id04 heights " + hTall + " and " + hShort + "; hitboxes " + tall.getBbHeight() + " and " + shortest.getBbHeight());
        helper.assertTrue(hTall >= 1.05F && hShort <= 0.93F, "tall and short: " + hTall + ", " + hShort);
        helper.assertTrue(tall.getBbHeight() <= 1.95F + 1e-4F, "the tallest's hitbox no taller than a villager's: " + tall.getBbHeight());
        helper.assertTrue(shortest.getBbHeight() < 1.95F && shortest.getBbHeight() > 1.8F, "the shortest's a touch lower: " + shortest.getBbHeight());
        // A room walled round, three high, its one way out an open door two blocks high in its east wall.
        BlockPos at = Kit.surface(level, t.heart().getX() + 20, t.heart().getZ() + 20);
        for (int dx = -6; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                level.setBlock(at.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 0; dy < 4; dy++) level.setBlock(at.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                boolean wall = (dx == -5 || dx == 0) && Math.abs(dz) <= 2 || Math.abs(dz) == 2 && dx > -5 && dx < 0;
                if (wall) for (int dy = 0; dy < 3; dy++) level.setBlock(at.offset(dx, dy, dz), Blocks.STONE_BRICKS.defaultBlockState(), 3);
            }
        }
        level.setBlock(at, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true).setValue(DoorBlock.FACING, Direction.EAST)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 3);
        level.setBlock(at.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.OPEN, true).setValue(DoorBlock.FACING, Direction.EAST)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 3);
        tall.moveTo(at.getX() - 2.5, at.getY(), at.getZ() + 0.5);
        Path path = tall.getNavigation().createPath(at.east(3), 0);
        boolean viaDoor = false;
        for (int i = 0; path != null && i < path.getNodeCount(); i++) {
            if (path.getNode(i).x == at.getX() && path.getNode(i).z == at.getZ()) viaDoor = true;
        }
        Kit.log("id04 a path out through the door: " + (path == null ? "none" : path.getNodeCount() + " steps, reaches " + path.canReach()
            + ", through the door " + viaDoor));
        helper.assertTrue(path != null && path.canReach() && viaDoor, "the tallest finds its way out through a door two blocks high");
        // A bed, in the room.
        BlockPos head = bed(level, at.offset(-2, 0, 1));
        helper.assertTrue(tall.claimBedNear(head), "a bed claimed");
        tall.startSleeping(tall.bedPos());
        helper.assertTrue(tall.isSleeping(), "and slept in");
        tall.stopSleeping();
        // Children of the tall and of the short.
        double tallKids = 0, shortKids = 0;
        for (int i = 0; i < 24; i++) {
            VillageFolkEntity x = unborn(level), y = unborn(level);
            Individual.born(x, tall, tall2);
            Individual.born(y, shortest, short2);
            tallKids += x.individual().genes.stature;
            shortKids += y.individual().genes.stature;
        }
        Kit.log("id04 the tall couple's children's stature " + tallKids / 24 + ", the short couple's " + shortKids / 24);
        helper.assertTrue(tallKids / 24 - shortKids / 24 > 1.0, "height is inherited");
        helper.succeed();
    }

    // ============================================================ id05: a dream

    /**
     * Worked towards: one who dreams of marrying grows warmer each day to the friend it is fondest of; one who dreams of
     * riches saves; one who dreams of leading stands. Come true (it has seen the sea): its spirits lift a long way, and
     * the chronicle says so.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "id05_a_dream")
    public static void id05_a_dream(GameTestHelper helper) {
        Town t = town(helper, 1528000, 3, 6000L);
        VillageFolkEntity f = t.folk().get(0), g = t.folk().get(1), h = t.folk().get(2);
        Dreams.dreamForTests(f, Persona.Ambition.MARRY);
        f.life().feel(g.getUUID(), g.displayNameCap(), 40);
        g.life().feel(f.getUUID(), f.displayNameCap(), 40);
        int before = f.life().affinity(g.getUUID());
        Dreams.courtForTests(f);
        int after = f.life().affinity(g.getUUID());
        Kit.log("id05 courting: " + before + " -> " + after);
        helper.assertTrue(after > before, "it courts the friend it is fondest of: " + before + " -> " + after);
        Dreams.dreamForTests(g, Persona.Ambition.RICH);
        helper.assertTrue(Dreams.saving(g), "a dream of riches: it saves");
        Dreams.dreamForTests(h, Persona.Ambition.LEAD);
        helper.assertTrue(Dreams.ambitionToLead(h) > 0, "a dream of leading: it stands");
        // Come true (a grumpy, shy one: its spirits have room to rise).
        h.life().setTraitsForTests(Social.Trait.GRUMPY, Social.Trait.SHY);
        Dreams.dreamForTests(h, Persona.Ambition.SEE_THE_SEA);
        h.refreshMood();
        int moodBefore = h.persona().mood();
        h.individual().seenSea = true;
        h.dreamCheckForTests();
        h.refreshMood();
        int moodAfter = h.persona().mood();
        Kit.log("id05 " + h.displayNameCap() + "'s dream: met " + h.persona().ambitionMet() + "; mood " + moodBefore + " -> " + moodAfter);
        helper.assertTrue(h.persona().ambitionMet(), "seen the sea: its dream came true");
        helper.assertTrue(moodAfter - moodBefore >= 15, "a great lift to its spirits: " + moodBefore + " -> " + moodAfter);
        helper.assertTrue(chronicled(t.village(), "dream came true: to see the sea"), "and the chronicle's news");
        helper.succeed();
    }

    // ============================================================ id06: fears

    /**
     * Afraid of the dark: on its way home to its bed before dusk, where one not afraid stays out; over its fear after five
     * brave days, the chronicle saying so. Afraid of deep water: not a fisher. Uneasy in a crowd: not at the feast. And
     * the town talks about it.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "id06_fears")
    public static void id06_fears(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1530000, 3, 11500L);
        VillageFolkEntity f = t.folk().get(0), g = t.folk().get(1), h = t.folk().get(2);
        f.setJob(StationTask.FARM);                                   // not the watch, which keeps its post after dark
        Individual.fearsForTests(f, Fears.Fear.DARK);
        Individual.fearsForTests(g);
        BlockPos head = bed(level, Kit.surface(level, t.heart().getX() - 10, t.heart().getZ() - 6));
        helper.assertTrue(f.claimBedNear(head), "a bed of its own");
        f.clearQueue();
        g.clearQueue();
        boolean home = Fears.homeBeforeDusk(f, level);
        BlockPos to = f.getNavigation().getTargetPos();
        Kit.log("id06 at 11500 the dark-fearing " + f.displayNameCap() + " home: " + home + ", to " + to + " (bed " + f.bedPos() + ")");
        helper.assertTrue(home, "afraid of the dark: off home before dusk");
        helper.assertTrue(to != null && to.distSqr(f.bedPos()) <= 4, "to its bed: " + to);
        helper.assertTrue(!Individual.hold(g, level) || Habits.doingForTests(g) != null, "one not afraid of it stays out");
        long day = level.getDayTime() / 24000L;
        for (int i = 0; i < 4; i++) helper.assertTrue(!Fears.braved(f, Fears.Fear.DARK, day + i), "not over it yet, day " + i);
        helper.assertTrue(Fears.braved(f, Fears.Fear.DARK, day + 4) && !Fears.dreads(f, Fears.Fear.DARK), "over it on the fifth brave day");
        helper.assertTrue(chronicled(t.village(), "no longer afraid of the dark"), "and the chronicle says so");
        Individual.fearsForTests(g, Fears.Fear.DEEP_WATER);
        helper.assertTrue(Fears.shuns(g, StationTask.FISH) && Fears.steer(g, StationTask.FISH) != StationTask.FISH,
            "afraid of deep water: not a fisher, " + Fears.steer(g, StationTask.FISH));
        Individual.fearsForTests(h, Fears.Fear.CROWDS);
        helper.assertTrue(Fears.shunsCrowd(h, "FEAST") && !Fears.shunsCrowd(h, "MORNING"), "uneasy in a crowd: not the feast, still the assembly");
        List<String> said = Individual.gossip(f);
        Kit.log("id06 gossip: " + said);
        helper.assertTrue(said.stream().anyMatch(s -> s.contains(g.displayNameCap()) && s.contains("deep water")), "the town talks of it");
        helper.succeed();
    }

    // ============================================================ id07: a habit

    /**
     * A pipe on the step at dusk: at dusk, off work, it goes out by its door, sits, its pipe in its mouth (the manner the
     * client draws it with); at noon it does not. And the tidy one picks up what lies about by its bed.
     */
    @GameTest(template = EMPTY, timeoutTicks = 500, batch = "id07_a_habit")
    public static void id07_a_habit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1532000, 2, 6000L);
        VillageFolkEntity f = t.folk().get(0), g = t.folk().get(1);
        Individual.habitsForTests(f, Habits.Place.WELL, Habits.Habit.PIPE);
        Individual.fearsForTests(f);
        BlockPos head = bed(level, f.blockPosition().east(3));
        helper.assertTrue(f.claimBedNear(head), "a bed of its own");
        helper.assertTrue(!Individual.hold(f, level) && Habits.doingForTests(f) == null, "no pipe at noon");
        // The tidy one.
        Individual.habitsForTests(g, Habits.Place.WELL, Habits.Habit.TIDY);
        BlockPos gHead = bed(level, g.blockPosition().west(4));
        helper.assertTrue(g.claimBedNear(gHead), "a bed of its own");
        ItemEntity litter = new ItemEntity(level, gHead.getX() + 2.5, gHead.getY() + 0.5, gHead.getZ() + 0.5, new ItemStack(Items.BONE, 3));
        litter.setNoPickUpDelay();
        level.addFreshEntity(litter);
        int put = Habits.tidyForTests(g);
        Kit.log("id07 tidied " + put + " things away; still lying there: " + litter.isAlive());
        helper.assertTrue(put == 3 && !litter.isAlive(), "what lay about by its door put away");
        level.setDayTime(13400L);
        level.updateSkyBrightness();
        int[] ticks = {0};
        helper.onEachTick(() -> {
            if (Habits.doingForTests(f) == null) f.clearQueue();           // its day's work put down: it is free
            if (ticks[0]++ % 2 == 0) Individual.hold(f, level);
            Habits.Habit doing = Habits.doingForTests(f);
            int idle = Manner.idleOf(f.clientManner());
            if (doing == Habits.Habit.PIPE && idle == Manner.PIPE) {
                Kit.log("id07 at dusk " + f.displayNameCap() + " " + Habits.doing(f) + " after " + ticks[0] + " ticks; sitting " + f.getPose());
                helper.succeed();
            } else if (ticks[0] > 440) {
                helper.fail("no pipe at dusk: doing " + doing + ", idle " + idle + ", off work " + f.offWorkNow());
            }
        });
    }

    // ============================================================ id08: a favourite place

    /** In its free time of an evening it makes for its favourite place: the bench by the well. */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "id08_favourite_place")
    public static void id08_favourite_place(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1534000, 1, 13400L);
        VillageFolkEntity f = t.folk().get(0);
        Individual.habitsForTests(f, Habits.Place.WELL);
        f.moveTo(t.heart().getX() + 14.5, Kit.surface(level, t.heart().getX() + 14, t.heart().getZ() + 10).getY(), t.heart().getZ() + 10.5);
        Habits.placeNowForTests(f);
        BlockPos spot = Habits.placeSpotForTests(f);
        boolean going = Habits.favouritePlace(f, level);
        BlockPos to = f.getNavigation().getTargetPos();
        Kit.log("id08 " + f.displayNameCap() + " to its favourite place " + spot + ": going " + going + ", heading for " + to
            + "; card: " + Individual.cardLines(f).stream().filter(l -> l[0].equals("Favourite place")).map(l -> l[1]).findFirst().orElse(""));
        helper.assertTrue(spot != null && going, "it goes to its favourite place in its free time");
        helper.assertTrue(to != null && to.distSqr(spot) <= 9, "to the spot itself: " + to + " for " + spot);
        helper.succeed();
    }

    // ============================================================ id09: a keepsake

    /**
     * Its keepsake (a feather, out of the stores) carried, marked its own: kept back from the stores at a deposit, kept
     * when a player asks for something to remember it by, and handed down to its child when it dies. Its card shows it,
     * with the rest of its life.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "id09_a_keepsake")
    public static void id09_a_keepsake(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Town t = town(helper, 1536000, 2, 6000L, new ItemStack(Items.FEATHER, 3));
        VillageFolkEntity f = t.folk().get(0), heir = t.folk().get(1);
        Keepsakes.kindForTests(f, Keepsakes.Kind.FEATHER, "from the first hen it ever kept");
        Keepsakes.comeByForTests(f);
        ItemStack carried = Keepsakes.carried(f);
        helper.assertTrue(carried != null && Individual.isTreasure(carried), "a feather out of the stores, its keepsake");
        helper.assertTrue(f.depositReserve(carried) == carried.getCount(), "never banked in the stores");
        Player p = helper.makeMockPlayer(GameType.SURVIVAL);
        f.persona().feelFor(p.getUUID(), p.getName().getString(), 70);
        String said = FolkTalk.answer(f, p, TalkTopic.KEEPSAKE, "");
        Kit.log("id09 asked for a keepsake: " + said);
        helper.assertTrue(Keepsakes.carried(f) != null, "not given away");
        helper.assertTrue(said.contains("never part"), "and it says so: " + said);
        String card = FolkTalk.card(f);
        for (String label : new String[]{"Looks|", "Dream|", "Fears|", "Habits|", "Favourite place|", "Keepsake|", "Learning|"}) {
            helper.assertTrue(card.contains(label), "its card's " + label + " line");
        }
        heir.parentIds().add(f.getUUID());
        Individual.died(f);
        ItemStack inherited = Keepsakes.carried(heir);
        Kit.log("id09 handed down: " + (inherited == null ? "nothing" : inherited.getHoverName().getString()) + "; "
            + heir.individual().keepsakeStory);
        helper.assertTrue(inherited != null && heir.individual().keepsake == Keepsakes.Kind.FEATHER
            && heir.individual().keepsakeStory.contains(f.displayNameCap()), "its child carries it after it");
        helper.succeed();
    }

    // ============================================================ id10: a voice

    /**
     * Its own voice: a child's high, a woman's higher than a man's, the old lower, the tall and broad deeper than the
     * short and slight; and how it walks: a child skips.
     */
    @GameTest(template = EMPTY, timeoutTicks = 200, batch = "id10_a_voice")
    public static void id10_a_voice(GameTestHelper helper) {
        Town t = town(helper, 1538000, 6, 6000L);
        VillageFolkEntity child = t.folk().get(0), man = t.folk().get(1), woman = t.folk().get(2), old = t.folk().get(3),
            big = t.folk().get(4), slight = t.folk().get(5);
        genes(child, false, 3, 2, 2, 0, 0, 0.0F);
        child.setChild(true);
        genes(man, true, 3, 2, 2, 0, 0, 0.0F);
        genes(woman, false, 3, 2, 2, 0, 0, 0.0F);
        genes(old, true, 3, 2, 2, 0, 0, 0.0F);
        genes(big, true, 3, 2, 2, 0, 0, 1.0F);
        genes(slight, true, 3, 2, 2, 0, 0, -1.0F);
        big.individual().genes.frame = 1.0F;
        slight.individual().genes.frame = -1.0F;
        for (VillageFolkEntity f : t.folk()) {
            f.individual().genes.voice = 0.0F;
            if (f != child) f.setAgeForTests(f == old ? 82 : 30);
            Individual.refreshLook(f);
        }
        float c = Manner.basePitch(child), m = Manner.basePitch(man), w = Manner.basePitch(woman), o = Manner.basePitch(old),
            b = Manner.basePitch(big), s = Manner.basePitch(slight);
        Kit.log(String.format(Locale.ROOT, "id10 pitch: child %.2f, woman %.2f, man %.2f, old man %.2f, big %.2f, slight %.2f", c, w, m, o, b, s));
        helper.assertTrue(c > w && w > m, "a child higher than a woman, a woman higher than a man");
        helper.assertTrue(o < m, "the old lower");
        helper.assertTrue(b < s, "the big deeper than the slight");
        helper.assertTrue(Manner.gait(child) == Manner.SKIP, "a child skips");
        helper.succeed();
    }
}
