package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.item.IndividualItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [individual] The smoke's stage for every folk its own person (/village individual stage): a crowd of eighteen of
 * every age stood up in two rows on a lawn in clear air (the back row a step up), each a face of its own: children
 * at three ages of growing, the young, the middle-aged greying, the old in spectacles, a scarred guard, one with an
 * eyepatch, a sooty smith, a sunburnt farmer, the oldest bent over their sticks. Beside them a family of five, made by
 * the same sums the town's births are (Looks.child): two parents, their eldest and a pair of twins. And past them a
 * well and a bench, an old folk sat on it with its pipe going, for a picture at dusk. Each stood-up folk is a
 * showcase (it lives no life, and goes with /kill @e[tag=folk_lineup]); its work hat off, to show its hair.
 */
public final class IndividualStage {

    private IndividualStage() {}

    /** The ones sat smoking on the stage: the smoke goes up from their pipes. */
    private static final Map<UUID, Boolean> PIPERS = new ConcurrentHashMap<>();
    /** Marks given for the stage (a farmer's sunburn in a world that is not in summer, a smith's soot at dusk). */
    static final Map<UUID, Integer> FORCED = new ConcurrentHashMap<>();

    /** One of the crowd: who, its years and trade, and its looks. */
    private record Who(String name, boolean male, int age, StationTask trade, int tone, int hair, int eye, int curl, int style,
                       int facial, int face, int nose, int eyeShape, int brows, float stature, float frame, String extra) {}

    private static final Who[] CROWD = {
        // the back row, a step up
        new Who("Rowan", false, 19, StationTask.TAILOR, 1, 7, 5, 0, Looks.LONG, 0, 0, 2, 1, 2, 0.4F, -0.5F, "rosy"),
        new Who("Tamsin", false, 30, StationTask.COOK, 6, 1, 0, 1, Looks.BRAID, 0, 4, 0, 5, 0, 0.0F, 0.0F, ""),
        new Who("Garth", true, 34, StationTask.SMITH, 3, 4, 2, 1, Looks.SHORT, Looks.BEARD, 2, 1, 4, 1, 0.8F, 0.9F, "soot"),
        new Who("Brannoc", true, 41, StationTask.GUARD, 5, 1, 0, 0, Looks.CROP, Looks.SIDEBURNS, 3, 3, 4, 3, 0.6F, 0.5F, "scar"),
        new Who("Imelda", false, 45, StationTask.BREW, 9, 0, 2, 2, Looks.WILD, 0, 1, 1, 0, 0, 0.2F, 0.1F, ""),
        new Who("Osric", true, 50, StationTask.FISH, 2, 3, 4, 1, Looks.PONYTAIL, Looks.MOUSTACHE, 0, 3, 3, 0, 0.3F, 0.2F, ""),
        new Who("Petra", false, 57, StationTask.STORE, 0, 6, 6, 0, Looks.BOB, 0, 0, 2, 5, 2, -0.2F, -0.3F, "specs freckles"),
        new Who("Cuthbert", true, 62, StationTask.WOOD, 4, 2, 3, 1, Looks.BALDING, Looks.LONG_BEARD, 2, 1, 2, 5, 0.5F, 0.6F, ""),
        new Who("Morwenna", false, 66, StationTask.ENCHANT, 7, 1, 1, 2, Looks.TIED, 0, 3, 3, 3, 4, -0.1F, -0.6F, "specs"),
        // the front row
        new Who("Pip", true, 6, StationTask.NONE, 8, 0, 1, 2, Looks.CURLS, 0, 1, 2, 1, 4, 0.0F, 0.0F, "growth2"),
        new Who("Nell", false, 9, StationTask.NONE, 1, 6, 4, 0, Looks.BOB, 0, 1, 2, 1, 4, 0.0F, 0.0F, "growth5 freckles"),
        new Who("Wren", false, 22, StationTask.FARM, 3, 9, 5, 0, Looks.BUN, 0, 4, 2, 5, 2, -0.5F, -0.2F, "sun freckles"),
        new Who("Jory", true, 24, StationTask.MINE, 7, 1, 0, 2, Looks.CROP, Looks.STUBBLE, 2, 1, 0, 1, 0.1F, 0.7F, ""),
        new Who("Edwin", true, 70, StationTask.RANCH, 2, 2, 6, 0, Looks.SHORT, Looks.GOATEE, 3, 3, 4, 1, 0.2F, 0.0F, "patch"),
        new Who("Agnes", false, 78, StationTask.SHOP, 5, 1, 0, 1, Looks.LONG, 0, 1, 2, 3, 4, -0.6F, -0.2F, ""),
        new Who("Silas", true, 86, StationTask.BEEKEEP, 6, 0, 2, 0, Looks.BALDING, Looks.LONG_BEARD, 3, 3, 3, 5, 0.4F, -0.4F, "stick"),
        new Who("Hester", false, 92, StationTask.BANK, 0, 7, 5, 1, Looks.BUN, 0, 4, 2, 3, 4, -0.8F, -0.6F, "stick specs"),
        new Who("Ambrose", true, 15, StationTask.NONE, 4, 3, 4, 1, Looks.WILD, 0, 0, 0, 1, 0, 0.0F, 0.0F, "growth7"),
    };

    /**
     * The stage at {@code at} (its middle, the lawn's top): VIEW lines for the smoke's pictures, and what was stood up.
     */
    public static List<String> stage(ServerLevel level, BlockPos at) {
        int x = at.getX(), y = at.getY(), z = at.getZ();
        // A lawn in clear air, a step at the back, lanterns at the corners: everybody in good light.
        for (int dx = -16; dx <= 26; dx++) {
            for (int dz = -6; dz <= 14; dz++) {
                level.setBlock(new BlockPos(x + dx, y - 1, z + dz), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int dy = 0; dy <= 5; dy++) level.setBlock(new BlockPos(x + dx, y + dy, z + dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        for (int dx = -7; dx <= 7; dx++) level.setBlock(new BlockPos(x + dx, y, z - 2), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        List<String> out = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < CROWD.length; i++) {
            Who w = CROWD[i];
            boolean back = i < 9;
            int k = back ? i : i - 9;
            double px = x - 6 + k * 1.5 + (back ? 0 : 0.75);
            double pz = back ? z - 1.5 : z + 0.5;
            double py = back ? y + 1 : y;
            VillageFolkEntity f = standUp(level, px, py, pz, w);
            if (f != null) names.add(w.name() + " (" + w.age() + ")");
        }
        out.add("STAGE individual " + x + " " + y + " " + z + ": " + String.join(", ", names));
        out.add("VIEW crowd " + x + " " + (y + 2) + " " + (z + 8) + " " + x + " " + (y + 1) + " " + (z - 1));
        out.add("VIEW crowd-left " + (x - 3) + " " + (y + 2) + " " + (z + 4) + " " + (x - 3) + " " + (y + 1) + " " + (z - 1));
        out.add("VIEW crowd-right " + (x + 4) + " " + (y + 2) + " " + (z + 4) + " " + (x + 4) + " " + (y + 1) + " " + (z - 1));
        out.addAll(family(level, x + 13, y, z));
        out.addAll(pipe(level, x + 21, y, z + 2));
        return out;
    }

    /** A family of five: two parents, then their children by the town's own sums (the eldest; twins, alike). */
    private static List<String> family(ServerLevel level, int x, int y, int z) {
        List<String> out = new ArrayList<>();
        VillageFolkEntity mum = standUp(level, x - 1.2, y, z - 1.0, new Who("Maud", false, 36, StationTask.FARM, 1, 5, 5, 0,
            Looks.LONG, 0, 4, 2, 1, 2, 0.1F, -0.2F, "freckles"));
        VillageFolkEntity dad = standUp(level, x + 1.2, y, z - 1.0, new Who("Hal", true, 38, StationTask.WOOD, 6, 0, 0, 2,
            Looks.SHORT, Looks.BEARD, 2, 1, 4, 1, 0.6F, 0.6F, ""));
        if (mum == null || dad == null) return out;
        RandomSource r = RandomSource.create(19L);
        VillageFolkEntity eldest = child(level, x, y, z + 0.6, "Robin", mum, dad, r, 6, 16);
        VillageFolkEntity twinA = child(level, x - 0.8, y, z + 1.6, "Ivy", mum, dad, r, 3, 7);
        VillageFolkEntity twinB = child(level, x + 0.8, y, z + 1.6, "Holly", mum, dad, r, 3, 7);
        if (twinA != null && twinB != null) Individual.twins(List.of(twinA, twinB));
        out.add("FAMILY Maud and Hal, " + (eldest == null ? "" : "Robin, ") + "and the twins Ivy and Holly");
        out.add("VIEW family " + x + " " + (y + 2) + " " + (z + 6) + " " + x + " " + (y + 1) + " " + z);
        return out;
    }

    @Nullable
    private static VillageFolkEntity child(ServerLevel level, double x, double y, double z, String name, VillageFolkEntity a,
                                           VillageFolkEntity b, RandomSource r, int growth, int ageDays) {
        VillageFolkEntity c = McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (c == null) return null;
        c.moveTo(x, y, z, 180.0F, 0.0F);
        c.makeShowcase(StationTask.NONE);
        c.setChild(true);
        c.rename(name);
        c.addTag("folk_lineup");
        Looks.child(c.individual().genes, a.individual().genes, b.individual().genes, r);
        c.individual().rolled = true;
        c.life().setParents(a.displayNameCap(), b.displayNameCap());
        c.bornDaysAgo(Math.round(growth * VillageFolkEntity.GROW_DAYS / 8.0F));
        if (!level.addFreshEntity(c)) return null;
        Manner.bareForStage(c);
        Individual.refreshLook(c);
        return c;
    }

    /** By a well, on the bench, at dusk: an old folk with its pipe going, its favourite place and its habit. */
    private static List<String> pipe(ServerLevel level, int x, int y, int z) {
        List<String> out = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(new BlockPos(x + dx, y, z + dz), dx == 0 && dz == 0 ? Blocks.WATER.defaultBlockState()
                    : Blocks.COBBLESTONE.defaultBlockState(), 3);
            }
        }
        level.setBlock(new BlockPos(x - 1, y + 1, z - 1), Blocks.OAK_FENCE.defaultBlockState(), 3);
        level.setBlock(new BlockPos(x + 1, y + 1, z - 1), Blocks.OAK_FENCE.defaultBlockState(), 3);
        level.setBlock(new BlockPos(x - 1, y + 2, z - 1), Blocks.OAK_SLAB.defaultBlockState(), 3);
        level.setBlock(new BlockPos(x, y + 2, z - 1), Blocks.OAK_SLAB.defaultBlockState(), 3);
        level.setBlock(new BlockPos(x + 1, y + 2, z - 1), Blocks.OAK_SLAB.defaultBlockState(), 3);
        level.setBlock(new BlockPos(x, y + 1, z - 1), Blocks.LANTERN.defaultBlockState().setValue(
            net.minecraft.world.level.block.LanternBlock.HANGING, true), 3);
        BlockPos seat = new BlockPos(x, y, z + 3);
        BlockState bench = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH);
        level.setBlock(seat, bench, 3);
        level.setBlock(seat.west(), bench, 3);
        level.setBlock(seat.east(), bench, 3);
        VillageFolkEntity f = standUp(level, x + 0.5, y + 0.5, z + 3.5 - 0.2, new Who("Bartholomew", true, 74, StationTask.HAUL,
            4, 2, 5, 0, Looks.CROP, Looks.MOUSTACHE, 3, 3, 3, 5, 0.3F, 0.3F, "pipe"));
        if (f != null) {
            f.setYRot(180.0F);
            f.setYHeadRot(180.0F);
            f.setYBodyRot(180.0F);
            f.setPose(Pose.SITTING);
            Individual.ensure(f);
            f.individual().habits.clear();
            f.individual().habits.add(Habits.Habit.PIPE);
            f.individual().place = Habits.Place.WELL;
            Manner.idle(f, Manner.PIPE, Integer.MAX_VALUE / 2);
            PIPERS.put(f.getUUID(), true);
        }
        out.add("VIEW pipe " + (x + 3) + " " + (y + 2) + " " + (z + 8) + " " + x + " " + (y + 1) + " " + (z + 3));
        return out;
    }

    @Nullable
    private static VillageFolkEntity standUp(ServerLevel level, double x, double y, double z, Who w) {
        VillageFolkEntity f = McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (f == null) return null;
        f.moveTo(x, y, z, 180.0F, 0.0F);
        f.makeShowcase(w.trade());
        boolean child = w.age() < 18;
        if (child) f.setChild(true);
        f.rename(w.name());
        f.addTag("folk_lineup");
        Looks.Genes g = f.individual().genes;
        g.male = w.male();
        g.tone = w.tone();
        g.eyeA = g.eyeB = w.eye();
        g.hairA = g.hairB = w.hair();
        g.curlA = g.curlB = w.curl();
        g.style = w.style();
        g.facial = w.facial();
        g.face = w.face();
        g.nose = w.nose();
        g.eyeShape = w.eyeShape();
        g.brows = w.brows();
        g.stature = w.stature();
        g.frame = w.frame();
        g.freckA = g.freckB = w.extra().contains("freckles");
        g.rosy = w.extra().contains("rosy");
        g.greying = 1;
        g.balding = w.style() == Looks.BALDING;
        g.rolled = true;
        Individual.Self s = f.individual();
        s.rolled = true;
        s.literate = true;
        if (w.extra().contains("scar")) { s.scar = 1; s.scarHow = "in the raid of day 14"; s.scarDay = 13; }
        if (w.extra().contains("patch")) { s.patch = 2; s.scarHow = "in the raid of day 9"; s.scarDay = 8; }
        if (!level.addFreshEntity(f)) return null;
        if (child) {
            int growth = w.extra().contains("growth7") ? 7 : w.extra().contains("growth5") ? 5 : 2;
            f.bornDaysAgo(Math.round(growth * VillageFolkEntity.GROW_DAYS / 8.0F));
        } else {
            f.setAgeForTests(w.age());
        }
        if (w.extra().contains("specs")) carry(f, new ItemStack(IndividualItems.SPECTACLES.get()), "specs", "'s spectacles");
        if (w.extra().contains("stick")) carry(f, new ItemStack(Items.STICK), "stick", "'s walking stick");
        int forced = 0;
        if (w.extra().contains("soot")) forced |= 1 << Individual.M_SOOT;
        if (w.extra().contains("sun")) forced |= 1 << Individual.M_SUN;
        if (forced != 0) FORCED.put(f.getUUID(), forced);
        Manner.bareForStage(f);
        Individual.refreshLook(f);
        return f;
    }

    private static void carry(VillageFolkEntity f, ItemStack s, String role, String name) {
        s.set(DataComponents.CUSTOM_NAME, Component.literal(f.displayNameCap() + name));
        Keepsakes.carry(s, f, role);
        f.insertGiven(s);
    }

    /** From a stood-up folk's tick (Individual.tick): the smoke from a pipe on the stage. */
    static void tick(VillageFolkEntity f) {
        if (!(f.level() instanceof ServerLevel level) || !PIPERS.containsKey(f.getUUID()) || f.tickCount % 25 != 0) return;
        double yaw = Math.toRadians(f.yHeadRot);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE, f.getX() - Math.sin(yaw) * 0.45 - Math.cos(yaw) * 0.12,
            f.getEyeY() - 0.62, f.getZ() + Math.cos(yaw) * 0.45 - Math.sin(yaw) * 0.12, 2, 0.02, 0.05, 0.02, 0.005);
    }

    /** /village individual card: the nearest stood-up (or real) folk's card, opened on the player's screen. */
    public static boolean card(ServerLevel level, ServerPlayer p) {
        VillageFolkEntity best = null;
        double near = 24.0 * 24.0;
        for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class, p.getBoundingBox().inflate(24.0))) {
            double d = f.distanceToSqr(p);
            if (d < near && !f.isBaby()) { near = d; best = f; }
        }
        if (best == null) return false;
        FolkTalk.open(best, p);                                    // the talk screen opened on it, then turned to its card
        FolkTalk.handle(best, p, TalkTopic.SAY, "Tell me about yourself, who you are");
        return true;
    }
}
