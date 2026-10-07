package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.Mishap;
import com.jrpetty.mcassistant.entity.Raids;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WatchClears;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [watch-clears] The watch clears the town, and its folk keep out of harm's way (WatchClears). A night's monsters
 * put among a town's folk are all killed by its watch and nobody dies; a zombie left in the shade at noon, away from
 * everybody, is hunted down all the same; a folk with a zombie by it goes indoors and comes out once it is gone; a
 * child never stands out in the open with a monster near; and a raiding band comes at the town's edge, two guards go
 * after each raider among the folk, and the books say what the watch killed and who fell where, doing what.
 *
 * <p>Each test has its own batch and its own ground: x 900000 to 910000, z 66000.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class WatchClearsGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    // ------------------------------------------------------------------ helpers

    /** Level, open ground: a few blocks of earth under it and nothing over it. */
    private static BlockPos flat(ServerLevel level, int cx, int cz, int r) {
        int y = Kit.surface(level, cx, cz).getY();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                for (int dy = -3; dy < 0; dy++) {
                    level.setBlock(new BlockPos(x, y + dy, z), dy == -1 ? Blocks.GRASS_BLOCK.defaultBlockState()
                        : Blocks.DIRT.defaultBlockState(), 2 | 16);
                }
                for (int dy = 0; dy < 12; dy++) {
                    BlockPos p = new BlockPos(x, y + dy, z);
                    if (!level.getBlockState(p).isAir()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2 | 16);
                }
            }
        }
        return new BlockPos(cx, y, cz);
    }

    /** A guard in the town's kit: an iron suit, a diamond blade and a shield, a bow and arrows, and bread. */
    private static VillageFolkEntity guard(ServerLevel level, BlockPos at) {
        VillageFolkEntity g = VillageFolkSpawnerBlock.raise(level, at, 0.0F);
        if (g == null) return null;
        g.setJob(StationTask.GUARD);
        g.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        g.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        g.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        g.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        g.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        g.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        g.insertItem(new ItemStack(Items.BOW));
        g.insertItem(new ItemStack(Items.ARROW, 48));
        g.insertItem(new ItemStack(Items.BREAD, 16));
        return g;
    }

    /** A monster stood on the ground here, to stay; a carved pumpkin on its head where the sun would burn it. */
    private static Mob monster(ServerLevel level, EntityType<? extends Mob> type, int x, int z, boolean ai) {
        Mob m = type.create(level);
        if (m == null) return null;
        BlockPos at = Kit.surface(level, x, z);
        m.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        m.setPersistenceRequired();
        if (m instanceof Zombie || m instanceof net.minecraft.world.entity.monster.AbstractSkeleton) {
            m.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        }
        if (m instanceof net.minecraft.world.entity.monster.AbstractSkeleton) m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        m.setNoAi(!ai);
        level.addFreshEntity(m);
        return m;
    }

    /**
     * A small stone house: seven square, walls three high, a flat roof, an open doorway on the {@code door} side,
     * and on the town's books as a house. Returns the middle of its floor (its anchor).
     */
    private static BlockPos house(ServerLevel level, UUID village, int cx, int cz, Direction door) {
        BlockPos floor = Kit.surface(level, cx, cz);
        int y = floor.getY();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                boolean wall = Math.abs(dx) == 3 || Math.abs(dz) == 3;
                for (int dy = 0; dy < 3; dy++) {
                    level.setBlock(new BlockPos(cx + dx, y + dy, cz + dz), wall ? Blocks.STONE_BRICKS.defaultBlockState()
                        : Blocks.AIR.defaultBlockState(), 3);
                }
                level.setBlock(new BlockPos(cx + dx, y + 3, cz + dz), Blocks.STONE_BRICKS.defaultBlockState(), 3);
            }
        }
        BlockPos gap = new BlockPos(cx, y, cz).relative(door, 3);
        level.setBlock(gap, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(gap.above(), Blocks.AIR.defaultBlockState(), 3);
        BlockPos anchor = new BlockPos(cx, y, cz);
        Ledger.built(village, "house", anchor, door);
        return anchor;
    }

    /** Inside the house about this anchor (within its walls and under its roof). */
    private static boolean inside(ServerLevel level, LivingEntity e, BlockPos anchor) {
        return Math.abs(e.getX() - (anchor.getX() + 0.5)) < 3.0 && Math.abs(e.getZ() - (anchor.getZ() + 0.5)) < 3.0
            && WatchClears.roofedForTests(level, e.blockPosition());
    }

    private static String at(Entity e, BlockPos heart) {
        return (e.getBlockX() - heart.getX()) + "," + (e.getBlockZ() - heart.getZ());
    }

    private static String target(Mob m) {
        LivingEntity t = m.getTarget();
        return t == null ? "none" : t.getType().toShortString();
    }

    // ============================================================ wc01: the night's monsters, all killed

    /**
     * Dusk in a town of five, three of them the watch in its kit: four zombies, two skeletons, a spider and a
     * creeper are put about it, eighteen to thirty blocks from the heart, none of them by anybody. The watch goes
     * after every one (the creeper with a bow), they are all dead within three minutes, and no folk dies.
     */
    @GameTest(template = EMPTY, timeoutTicks = 4000, batch = "wc01_clears_the_night")
    public static void wc01_clears_the_night(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 900000;
        Kit.hold(level, x, Z, 64);
        Kit.prepare(level, x, Z, 64);
        BlockPos heart = flat(level, x, Z, 40);
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 14000L);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity b = VillageFolkSpawnerBlock.raise(level, heart.offset(-2, 0, 2), 0.0F);
        List<VillageFolkEntity> watch = new ArrayList<>();
        for (int i = 0; i < 3; i++) watch.add(guard(level, heart.offset(2 + i, 0, -2)));
        helper.assertTrue(a != null && b != null && watch.stream().allMatch(g -> g != null), "a town of five, three of them guards");
        UUID village = a.ownerId();
        List<VillageFolkEntity> everyone = new ArrayList<>(watch);
        everyone.add(a);
        everyone.add(b);
        List<Mob> night = new ArrayList<>();
        helper.runAtTickTime(20, () -> {
            int[][] spots = { { 20, 6 }, { -22, 4 }, { 6, -24 }, { -8, 26 }, { -18, -18 }, { 18, 20 }, { 24, -14 }, { 28, -4 } };
            EntityType<?>[] kinds = { EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.ZOMBIE, EntityType.ZOMBIE,
                EntityType.SKELETON, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER };
            for (int i = 0; i < spots.length; i++) {
                @SuppressWarnings("unchecked")
                Mob m = monster(level, (EntityType<? extends Mob>) kinds[i], x + spots[i][0], Z + spots[i][1], true);
                if (m != null) night.add(m);
            }
            List<Mob> about = WatchClears.aboutForTests(level, village);
            Kit.log("wc01 the night's monsters: " + night.size() + " put out, " + about.size() + " about the town by its own count; reach "
                + Villages.townReach(village));
            helper.assertTrue(night.size() == 8 && about.size() >= 8, "eight monsters about the town: " + night.size() + ", " + about.size());
            WatchClears.tickForTests(level, village);
            int sent = 0;
            for (VillageFolkEntity g : watch) if (WatchClears.huntedByForTests(g) != null) sent++;
            Kit.log("wc01 the watch's first look round: " + sent + " of 3 guards sent out");
            helper.assertTrue(sent == 3, "every guard of the watch sent after one: " + sent);
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            if (t <= 20 || night.isEmpty()) return;
            for (VillageFolkEntity f : everyone) {
                if (!f.isAlive()) helper.fail("wc01 " + f.displayNameCap() + " died at tick " + t + ": " + f.debugLine());
            }
            int left = 0;
            for (Mob m : night) if (m.isAlive()) left++;
            if (t % 200 == 0 || left == 0) {
                StringBuilder sb = new StringBuilder("wc01 tick " + t + ": " + left + " left");
                for (Mob m : night) {
                    if (m.isAlive()) sb.append(" | ").append(m.getType().toShortString()).append(" at ").append(at(m, heart))
                        .append(" hp ").append((int) m.getHealth()).append(" after ").append(target(m));
                }
                for (VillageFolkEntity g : watch) {
                    sb.append(" || ").append(g.displayNameCap()).append(" at ").append(at(g, heart)).append(" hp ").append((int) g.getHealth())
                        .append(" after ").append(target(g)).append(" holding ").append(g.getMainHandItem().getItem());
                }
                Kit.log(sb.toString());
            }
            if (left == 0) {
                int[] week = WatchClears.weekForTests(level, village);
                Kit.log("wc01 all eight dead at tick " + t + " (" + (t / 20) + " s); the tally: the watch " + week[0] + ", the golem "
                    + week[1] + ", otherwise " + week[2] + ", folk lost " + week[3] + "; the status: "
                    + WatchClears.statusLine(level, Villages.get(village)));
                helper.assertTrue(week[0] >= 5, "the watch killed most of them: " + week[0]);
                helper.assertTrue(week[3] == 0, "no folk lost: " + week[3]);
                helper.succeed();
                return;
            }
            if (t >= 3800) helper.fail("wc01 " + left + " of the night's monsters still alive after " + (t / 20) + " s");
        });
    }

    // ============================================================ wc02: hunted down in the shade

    /**
     * Noon. A zombie that lived through the night under a stone canopy, twenty-five blocks from anybody (no folk
     * within the sixteen blocks that used to send a guard): it is about the town by the watch's count, a guard is
     * sent after it, and it is dead, by the guard, within a minute.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "wc02_hunted_in_the_shade")
    public static void wc02_hunted_in_the_shade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 902000;
        Kit.hold(level, x, Z, 56);
        Kit.prepare(level, x, Z, 56);
        BlockPos heart = flat(level, x, Z, 36);
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 6000L);
        VillageFolkEntity citizen = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity g = guard(level, heart.offset(-3, 0, -3));
        helper.assertTrue(citizen != null && g != null, "a town of two, one of them the watch");
        UUID village = citizen.ownerId();
        citizen.setNoAi(true);                                  // it stays at the heart, well away from the zombie
        // The shade: a stone canopy four blocks up, five across, over open ground.
        int sx = x + 24, sz = Z + 10;
        int sy = Kit.surface(level, sx, sz).getY();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) level.setBlock(new BlockPos(sx + dx, sy + 3, sz + dz), Blocks.STONE.defaultBlockState(), 3);
        }
        Mob[] shade = new Mob[1];
        helper.runAtTickTime(20, () -> {
            // No pumpkin: the canopy is all that keeps the sun off it. Still, in its corner.
            Mob m = EntityType.ZOMBIE.create(level);
            m.moveTo(sx + 0.5, sy, sz + 0.5, 0.0F, 0.0F);
            m.setPersistenceRequired();
            m.setNoAi(true);
            level.addFreshEntity(m);
            shade[0] = m;
            boolean sky = level.canSeeSky(m.blockPosition());
            boolean counted = WatchClears.aboutForTests(level, village).contains(m);
            Kit.log("wc02 the zombie in the shade at " + at(m, heart) + " (sky over it " + sky + "), " + String.format("%.1f", m.distanceTo(citizen))
                + " from the citizen; about the town by the watch's count " + counted);
            helper.assertTrue(!sky && counted, "a zombie in the shade, about the town");
            WatchClears.tickForTests(level, village);
            UUID hunted = WatchClears.huntedByForTests(g);
            Kit.log("wc02 the guard sent after it: " + m.getUUID().equals(hunted) + "; " + g.debugLine());
            helper.assertTrue(m.getUUID().equals(hunted) && g.getTarget() == m, "the guard is sent after it, nobody near it or no");
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            Mob m = shade[0];
            if (m == null || t <= 20) return;
            if (t % 100 == 0) {
                Kit.log("wc02 tick " + t + ": the zombie hp " + (int) m.getHealth() + ", the guard at " + at(g, heart) + " "
                    + String.format("%.1f", g.distanceTo(m)) + " off, after " + target(g));
            }
            if (!m.isAlive()) {
                int[] week = WatchClears.weekForTests(level, village);
                Kit.log("wc02 dead at tick " + t + " (" + (t / 20) + " s), last hurt by " + (m.getLastHurtByMob() == null ? "nobody"
                    : m.getLastHurtByMob().getName().getString()) + "; the watch's tally " + week[0]);
                helper.assertTrue(m.getLastHurtByMob() == g && week[0] == 1, "killed by the guard, and counted to the watch: " + week[0]);
                helper.succeed();
                return;
            }
            if (t >= 1500) helper.fail("wc02 the zombie in the shade still alive after " + (t / 20) + " s: " + g.debugLine());
        });
    }

    // ============================================================ wc03: indoors, and out again

    /**
     * A town with no watch: a folk at the heart with a zombie seven blocks off. It goes into the house eight blocks
     * the other way (not towards the zombie), stays in while the zombie is there, out of its sight in the street, and
     * comes out again once it has gone.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "wc03_folk_go_indoors")
    public static void wc03_folk_go_indoors(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 904000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = flat(level, x, Z, 32);
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 6000L);
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null, "a folk");
        UUID village = f.ownerId();
        BlockPos home = house(level, village, x - 8, Z, Direction.EAST);
        Mob[] z = new Mob[1];
        long[] in = { -1 }, gone = { -1 };
        helper.runAtTickTime(20, () -> {
            f.moveTo(heart.getX() + 0.5, heart.getY(), heart.getZ() + 0.5, 0.0F, 0.0F);
            f.getNavigation().stop();
            f.clearQueue();
            z[0] = monster(level, EntityType.ZOMBIE, x + 7, Z, false);
            Kit.log("wc03 a zombie at " + at(z[0], heart) + ", the house at " + (home.getX() - heart.getX()) + "," + (home.getZ() - heart.getZ()));
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            Mob m = z[0];
            if (m == null || t <= 20) return;
            BlockPos cover = WatchClears.coverForTests(f);
            if (t % 50 == 0) {
                Kit.log("wc03 tick " + t + ": the folk at " + at(f, heart) + ", cover " + (cover == null ? "none" : (cover.getX() - heart.getX())
                    + "," + (cover.getZ() - heart.getZ())) + ", inside " + inside(level, f, home) + ", " + f.debugLine());
            }
            if (m.isAlive()) {
                if (t == 240) helper.assertTrue(cover != null, "a zombie seven blocks off: it takes cover");
                if (in[0] < 0 && inside(level, f, home)) {
                    in[0] = t;
                    Kit.log("wc03 indoors at tick " + t + " (" + ((t - 20) / 20.0) + " s after the zombie came)");
                }
                if (in[0] >= 0 && t > in[0] + 10) {
                    helper.assertTrue(inside(level, f, home), "and it stays in while the zombie is there (tick " + t + ")");
                }
                if (in[0] >= 0 && t >= in[0] + 200) {
                    m.discard();                                // gone (the watch, or the sun)
                    gone[0] = t;
                }
                if (t >= 1000 && in[0] < 0) helper.fail("wc03 never got indoors: " + f.debugLine());
                return;
            }
            if (cover == null) {
                Kit.log("wc03 out again at tick " + t + ", " + (t - gone[0]) + " ticks after the zombie went; " + f.debugLine());
                helper.succeed();
                return;
            }
            if (t - gone[0] > 300) helper.fail("wc03 still under cover " + (t - gone[0]) + " ticks after the zombie went");
        });
    }

    // ============================================================ wc04: children first

    /**
     * A child and a grown folk at the heart, a house eight blocks one way and a zombie twelve the other: past the
     * ten that send a grown folk in, inside the sixteen that send a child. The child goes in (the grown folk does
     * not need to), and from then until the zombie has gone the child is never out in the open with it near.
     */
    @GameTest(template = EMPTY, timeoutTicks = 1600, batch = "wc04_children_first")
    public static void wc04_children_first(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 906000;
        Kit.hold(level, x, Z, 48);
        Kit.prepare(level, x, Z, 48);
        BlockPos heart = flat(level, x, Z, 32);
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 6000L);
        VillageFolkEntity grown = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity kid = VillageFolkSpawnerBlock.raise(level, heart.offset(1, 0, 1), 0.0F);
        helper.assertTrue(grown != null && kid != null, "a folk and a child");
        kid.setChild(true);
        kid.bornDaysAgo(1);
        grown.setNoAi(true);                                    // it stands where it is: only the child's way is watched
        UUID village = grown.ownerId();
        BlockPos home = house(level, village, x - 8, Z, Direction.EAST);
        Mob[] z = new Mob[1];
        long[] in = { -1 }, gone = { -1 };
        int[] outWithIt = { 0 };
        helper.runAtTickTime(20, () -> {
            kid.moveTo(heart.getX() + 1.5, heart.getY(), heart.getZ() + 0.5, 0.0F, 0.0F);
            kid.getNavigation().stop();
            z[0] = monster(level, EntityType.ZOMBIE, x + 13, Z, false);
            Kit.log("wc04 a zombie at " + at(z[0], heart) + ": " + String.format("%.1f", z[0].distanceTo(kid)) + " from the child, "
                + String.format("%.1f", z[0].distanceTo(grown)) + " from the grown folk");
        });
        helper.onEachTick(() -> {
            long t = helper.getTick();
            Mob m = z[0];
            if (m == null || t <= 20) return;
            if (t % 50 == 0) {
                Kit.log("wc04 tick " + t + ": the child at " + at(kid, heart) + " inside " + inside(level, kid, home) + ", cover "
                    + (WatchClears.coverForTests(kid) != null) + "; the grown folk's cover " + (WatchClears.coverForTests(grown) != null));
            }
            if (m.isAlive()) {
                if (in[0] < 0 && inside(level, kid, home)) {
                    in[0] = t;
                    Kit.log("wc04 the child indoors at tick " + t + " (" + ((t - 20) / 20.0) + " s)");
                }
                // From the moment it is in until the zombie goes, never out in the open with it within sixteen blocks.
                if (in[0] >= 0 && !WatchClears.roofedForTests(level, kid.blockPosition()) && kid.distanceTo(m) <= 16.0F) outWithIt[0]++;
                if (t == 300) {
                    helper.assertTrue(WatchClears.coverForTests(kid) != null, "the child takes cover from a zombie twelve blocks off");
                    helper.assertTrue(WatchClears.coverForTests(grown) == null, "a grown folk twelve blocks off goes on with its day");
                }
                if (in[0] >= 0 && t >= in[0] + 300) {
                    m.discard();
                    gone[0] = t;
                }
                if (t >= 1000 && in[0] < 0) helper.fail("wc04 the child never got indoors: " + kid.debugLine());
                return;
            }
            if (WatchClears.coverForTests(kid) == null) {
                Kit.log("wc04 the child out again " + (t - gone[0]) + " ticks after the zombie went; out in the open with it near "
                    + outWithIt[0] + " ticks after it first got in");
                helper.assertTrue(outWithIt[0] == 0, "a child never outside with a monster near: " + outWithIt[0] + " ticks");
                helper.succeed();
                return;
            }
            if (t - gone[0] > 300) helper.fail("wc04 the child still under cover " + (t - gone[0]) + " ticks after the zombie went");
        });
    }

    // ============================================================ wc05: the band, the hunt and the books

    /**
     * A Diamond Age town of six, four of them the watch. Its raiding band is set down at the town's edge, not in
     * its streets; two of the band come among the folk and each gets two guards, while the rest, still out at the
     * edge, are left to come to the wall. A folk killed by a vindicator with the bell ringing goes in the books as
     * killed by a vindicator when the raiders came, with where and doing what, and counts toward the watch's
     * share; one in lava with the bell ringing is in lava, not "when the raiders came". Monsters killed by a guard
     * and by the golem are counted; the status and /village monsters say so.
     */
    @GameTest(template = EMPTY, timeoutTicks = 400, batch = "wc05_band_and_books")
    public static void wc05_band_and_books(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        final int x = 908000;
        Kit.hold(level, x, Z, 72);
        Kit.prepare(level, x, Z, 72);
        BlockPos heart = flat(level, x, Z, 40);
        level.setDayTime((level.getDayTime() / 24000L + 1) * 24000L + 14000L);
        VillageFolkEntity a = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        VillageFolkEntity b = VillageFolkSpawnerBlock.raise(level, heart.offset(3, 0, 3), 0.0F);
        List<VillageFolkEntity> watch = new ArrayList<>();
        for (int i = 0; i < 4; i++) watch.add(guard(level, heart.offset(-4 + 2 * i, 0, -3)));
        helper.assertTrue(a != null && b != null && watch.stream().allMatch(g -> g != null), "a town of six, four of them guards");
        UUID village = a.ownerId();
        Villages.Village v = Villages.get(village);
        Villages.ageForTests(village, Villages.Age.DIAMOND);
        helper.runAtTickTime(10, () -> {
            int reach = Villages.townReach(village);
            Raids.raidNow(level, v);
            List<Mob> band = new ArrayList<>();
            for (UUID u : Raids.band(village)) if (level.getEntity(u) instanceof Mob m) band.add(m);
            int nearest = Integer.MAX_VALUE;
            for (Mob m : band) nearest = Math.min(nearest, Math.max(Math.abs(m.getBlockX() - heart.getX()), Math.abs(m.getBlockZ() - heart.getZ())));
            Kit.log("wc05 the band: " + band.size() + " (" + (band.isEmpty() ? "" : band.get(0).getType().toShortString()) + "...), the nearest "
                + nearest + " blocks from the heart; the town reaches " + reach + "; the bell " + Raids.underAlarm(village));
            helper.assertTrue(band.size() >= 3 && Raids.underAlarm(village), "a raiding band, and the bell");
            helper.assertTrue(nearest >= reach, "the band comes at the town's edge, not into its streets: " + nearest + " against " + reach);
            // Two of them come among the folk.
            Mob one = band.get(0), two = band.get(1);
            one.moveTo(heart.getX() + 8.5, heart.getY(), heart.getZ() + 6.5, 0.0F, 0.0F);
            two.moveTo(heart.getX() - 9.5, heart.getY(), heart.getZ() + 7.5, 0.0F, 0.0F);
            one.setNoAi(true);
            two.setNoAi(true);
            for (Mob m : band) if (m != one && m != two) m.setNoAi(true);
            WatchClears.tickForTests(level, village);
            int onOne = 0, onTwo = 0, onRest = 0;
            for (VillageFolkEntity g : watch) {
                if (g.getTarget() == one) onOne++;
                else if (g.getTarget() == two) onTwo++;
                else if (g.getTarget() != null) onRest++;
            }
            Kit.log("wc05 the watch's look round: " + onOne + " guards after the first raider among the folk, " + onTwo
                + " after the second, " + onRest + " after the band still out at the edge");
            helper.assertTrue(onOne == 2 && onTwo == 2, "two guards to each raider among the folk: " + onOne + ", " + onTwo);
            helper.assertTrue(onRest == 0, "none sent out to the edge with the bell ringing: " + onRest);

            // A folk killed by a vindicator, the bell ringing; another in lava.
            Mob axe = EntityType.VINDICATOR.create(level);
            axe.moveTo(b.getX() + 1.0, b.getY(), b.getZ(), 0.0F, 0.0F);
            level.addFreshEntity(axe);
            String bName = b.displayNameCap();
            b.hurt(level.damageSources().mobAttack(axe), 1000.0F);
            a.hurt(level.damageSources().lava(), 1000.0F);
            axe.discard();
            List<WatchClears.Fallen> fallen = WatchClears.fallenForTests(village);
            Kit.log("wc05 the fallen: " + fallen);
            WatchClears.Fallen byAxe = fallen.stream().filter(fl -> fl.name().equals(bName)).findFirst().orElse(null);
            helper.assertTrue(byAxe != null && byAxe.how().contains("vindicator") && byAxe.how().contains("raiders came"),
                "killed by a vindicator with the bell ringing: " + (byAxe == null ? "not written down" : byAxe.how()));
            helper.assertTrue(!byAxe.where().isEmpty() && !byAxe.doing().isEmpty(), "where it fell and what it was doing: " + byAxe.where()
                + "; " + byAxe.doing());
            WatchClears.Fallen inLava = fallen.stream().filter(fl -> !fl.name().equals(bName)).findFirst().orElse(null);
            helper.assertTrue(inLava != null && inLava.how().equals("in lava"), "lava is lava, bell or no bell: "
                + (inLava == null ? "not written down" : inLava.how()));
            double wanted = Mishap.watch(village, 1.0, 15, level.getGameTime());
            helper.assertTrue(wanted >= 2.0, "a folk lost to the raiders: the watch wants more hands: " + wanted);

            // Monsters killed by a guard and by the golem, about the town.
            Mob z1 = monster(level, EntityType.ZOMBIE, x + 12, Z - 12, false);
            Mob z2 = monster(level, EntityType.ZOMBIE, x - 12, Z - 12, false);
            IronGolem golem = EntityType.IRON_GOLEM.create(level);
            golem.moveTo(heart.getX() - 12.5, heart.getY(), heart.getZ() - 14.5, 0.0F, 0.0F);
            level.addFreshEntity(golem);
            z1.hurt(level.damageSources().mobAttack(watch.get(0)), 1000.0F);
            z2.hurt(level.damageSources().mobAttack(golem), 1000.0F);
            golem.discard();
            int[] week = WatchClears.weekForTests(level, village);
            String status = WatchClears.statusLine(level, v);
            List<String> books = WatchClears.booksForTests(level, village);
            List<String> said = Kit.command(level, "execute positioned " + x + " " + heart.getY() + " " + Z + " run village monsters");
            Kit.log("wc05 the tally: the watch " + week[0] + ", the golem " + week[1] + ", folk lost " + week[3] + "; the status: " + status);
            Kit.log("wc05 the books: " + books);
            Kit.log("wc05 /village monsters: " + said);
            helper.assertTrue(week[0] == 1 && week[1] == 1 && week[3] == 1, "one to the watch, one to the golem, one folk lost: "
                + week[0] + ", " + week[1] + ", " + week[3]);
            helper.assertTrue(status.startsWith("monsters about: ") && status.contains("the watch has killed 1 this week and the golem 1"),
                "the status says it: " + status);
            helper.assertTrue(books.stream().anyMatch(l -> l.contains("still about the town")) && books.stream().anyMatch(l -> l.contains(bName)),
                "the books: the monsters still about, and who fell where: " + books);
            String all = String.join(" ", said);
            helper.assertTrue(all.contains("MONSTERS") && all.contains(bName), "/village monsters says it all: " + all);
            for (UUID u : Raids.band(village)) if (level.getEntity(u) instanceof Mob m) m.discard();
            helper.succeed();
        });
    }
}
