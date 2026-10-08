package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The front of the museum (blueprints/museum.txt): its name over the door, on two signs side by side
 * under the entablature ("The Museum | of Ashford"), and a banner in the town's colours on the wall
 * either side of the door, between the end columns and the next, over the lanterns on their posts.
 *
 * <p>Put up out of the stores like the town's other small works, a piece at a time, by a hand sent to
 * the town's work (TownJobs): a sign, or the two planks it is made of (Crafts); a banner, or the wool,
 * the dye and the stick it is made of (Bench works the making out, and nothing is called for that the
 * stores cannot run to). The town's colours are its watch's (blue, red, green, purple, black, teal or
 * orange: the same as the tabards in FolkRenderer). The name is kept up to date if the town is renamed.
 * Free on the stage (/village museum stage). A museum built to the first drawing, which had no portico,
 * is let be.
 */
public final class MuseumFront {
    private MuseumFront() {}

    /** The two name signs, over the door's two leaves under the entablature, in the hall's terms (Museum.at). */
    static final int[][] NAME = { { 0, 3, -5 }, { 1, 3, -5 } };
    /** The two banners, on the wall between the end columns and the next, over the lanterns on their posts. */
    static final int[][] BANNERS = { { -3, 4, -5 }, { 4, 4, -5 } };
    /** A town's colours, in the order its watch's tabards are dyed in them (FolkRenderer.BANNERS). */
    private static final DyeColor[] COLOURS = { DyeColor.BLUE, DyeColor.RED, DyeColor.GREEN, DyeColor.PURPLE,
        DyeColor.BLACK, DyeColor.CYAN, DyeColor.ORANGE };

    /** When each town's museum front is next looked over: soon while there is work on it, else now and then. */
    private static final Map<UUID, Long> DUE = new ConcurrentHashMap<>();

    static void resetForTests() {
        DUE.clear();
    }

    /** Tests: every piece of the front the stores run to put up now, one after another (with the town's
     *  works done at once, TownJobs.instantForTests, the hand's walk is left out too). */
    public static void putForTests(ServerLevel level, Villages.Village v, Ledger.Building b) {
        Museum.whichHall(level, b);
        for (int i = 0; i < NAME.length + BANNERS.length + 1; i++) {
            if (!put(level, v, b, null, false)) break;
        }
    }

    /** The colour a town flies: the one its watch wears. */
    public static DyeColor colour(UUID village) {
        return COLOURS[Math.floorMod(Math.floorMod(village.hashCode(), 64), COLOURS.length)];
    }

    /** From the museum's beat (Museum): the front looked over, and the next piece of it put up. */
    static void tick(ServerLevel level, Villages.Village v, Ledger.Building b, @Nullable VillageFolkEntity curator, long now) {
        Long due = DUE.get(v.id());
        if (due != null && now < due && due - now <= 2400L) return;
        long t = level.getDayTime() % 24000L;
        boolean day = t < 12500L || t >= 23500L;                         // the town's works are done by day
        DUE.put(v.id(), now + (day && put(level, v, b, curator, false) ? 100L : 2400L));
    }

    /** All of it at once, out of nothing: the stage's. */
    static void put(ServerLevel level, Villages.Village v, Ledger.Building b, boolean free) {
        put(level, v, b, null, free);
    }

    /**
     * The name signs written (and kept right), and the next piece that is missing put up: a sign, then a
     * banner. True while a hand is on its way to it or there is more the stores can pay for.
     */
    private static boolean put(ServerLevel level, Villages.Village v, Ledger.Building b, @Nullable VillageFolkEntity curator,
                               boolean free) {
        if (!Museum.grand(b)) return false;
        Direction out = b.facing().getOpposite();
        String[][] name = nameLines(Villages.name(v.id()));
        boolean signs = free || signInStores(level, v);
        for (int i = 0; i < NAME.length; i++) {
            BlockPos at = Museum.at(b, NAME[i][0], NAME[i][1], NAME[i][2]);
            if (level.getBlockEntity(at) instanceof SignBlockEntity s) {
                TownLife.write(s, name[i]);
                continue;
            }
            BlockState sign = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, out);
            if (!signs || !level.getBlockState(at).canBeReplaced() || !sign.canSurvive(level, at)) continue;
            if (!free) {
                if (!TownJobs.atWork(level, v, "museum", at, "putting the museum's name up over its door")) return true;
                if (!Crafts.sign(level, v)) return false;
            }
            level.setBlock(at, sign, 3);
            if (level.getBlockEntity(at) instanceof SignBlockEntity s) TownLife.write(s, name[i]);
            if (!free) return true;
        }
        DyeColor colour = colour(v.id());
        Item banner = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(colour.getName() + "_banner"));
        Block wall = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(colour.getName() + "_wall_banner"));
        if (banner == Items.AIR || !(wall instanceof WallBannerBlock)) return false;
        for (int[] c : BANNERS) {
            BlockPos at = Museum.at(b, c[0], c[1], c[2]);
            if (level.getBlockState(at).getBlock() instanceof WallBannerBlock) continue;
            BlockState hung = wall.defaultBlockState().setValue(WallBannerBlock.FACING, out);
            if (!level.getBlockState(at).canBeReplaced() || !level.getBlockState(at.below()).canBeReplaced()
                    || !hung.canSurvive(level, at)) continue;
            if (!free) {
                Bench.Plan plan = Bench.plan(level, v, List.of(Bench.Want.of(banner, 1)), Bench.handOf(level, v, curator, "museum"));
                if (!plan.ok()) return false;                              // short of the makings: another day
                if (!TownJobs.atWork(level, v, "museum", at, "hanging the town's colours on the museum")) return true;
                if (!Bench.take(level, v, plan, curator)) return false;
            }
            level.setBlock(at, hung, 3);
            if (!free) return true;
        }
        return false;
    }

    /** A sign put by in the stores, or the planks (or a log) to make one. */
    private static boolean signInStores(ServerLevel level, Villages.Village v) {
        return Crafts.stock(level, v, s -> s.is(ItemTags.SIGNS)) > 0 || Crafts.stock(level, v, s -> s.is(ItemTags.PLANKS)) >= 2
            || Crafts.stock(level, v, s -> s.is(ItemTags.LOGS)) > 0;
    }

    /**
     * The museum's name on its two signs, read across: "The Museum" on the left, "of Ashford" on the right;
     * a name too long for that has "of" to itself and the name on the line under it.
     */
    static String[][] nameLines(String town) {
        String of = "of " + town;
        if (Archive.px(of) <= SIGN_PX) {
            return new String[][]{ { "", "The Museum", "", "" }, { "", of, "", "" } };
        }
        String fitted = town;
        while (Archive.px(fitted) > SIGN_PX && fitted.length() > 1) fitted = fitted.substring(0, fitted.length() - 1);
        return new String[][]{ { "", "The Museum", "", "" }, { "", "of", fitted, "" } };
    }

    /** As wide as a line of a sign will hold, in the font's pixels (as Museum's labels). */
    private static final int SIGN_PX = 88;
}
