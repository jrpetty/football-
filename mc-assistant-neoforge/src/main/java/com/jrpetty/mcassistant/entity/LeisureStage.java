package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.item.DyedShapedRecipe;
import com.jrpetty.mcassistant.item.LeisureItems;
import com.jrpetty.mcassistant.item.SlateItem;
import com.jrpetty.mcassistant.village.Ledger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [leisure] /village items leisure stage (operators; the client smoke's photographs): the seven things out at once, for
 * nothing, where the command is run.
 * <ul>
 * <li><b>The showcase</b>: a spruce wall with the seven in item frames along it and a row of paper lanterns along its top,
 *     one of every colour; before it a bed with a quilt on it, a draughts table with its benches, and the football;</li>
 * <li><b>in use</b>: two of the town sat down to draughts at the showcase's table and a few moves played; one asleep
 *     under the quilt; a lute in a folk's hands, playing (a busker of the town's, if it has one: Buskers.now); two
 *     children flying kites over the showcase; a child with its slate; the children's kickabout with the football on
 *     their green; the lanterns strung over the square; and, with a pitch, a league match with the leather ball.</li>
 * </ul>
 * Returns the lines of what was done, and "VIEW name ex ey ez ax ay az" for each picture (the eye, and where it looks).
 */
final class LeisureStage {

    private LeisureStage() {}

    static List<String> stage(ServerLevel level, Villages.Village v, BlockPos near) {
        List<String> out = new ArrayList<>();
        UUID id = v.id();
        long now = level.getGameTime(), day = level.getDayTime() / 24000L;
        int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, near.getX(), near.getZ());
        BlockPos o = new BlockPos(near.getX(), gy, near.getZ());
        // The ground made level and bare for the showcase: thirteen across, nine deep, open sky over it.
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -2; dz <= 8; dz++) {
                BlockPos c = o.offset(dx, 0, dz);
                for (int y = 0; y <= 8; y++) if (!level.getBlockState(c.above(y)).isAir()) level.setBlock(c.above(y), Blocks.AIR.defaultBlockState(), 2 | 16);
                level.setBlock(c.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 2 | 16);
                for (int y = 2; y <= 3; y++) if (!level.getBlockState(c.below(y)).isSolid()) level.setBlock(c.below(y), Blocks.DIRT.defaultBlockState(), 2 | 16);
            }
        }
        // The wall, its back to the north; the frames on its south face.
        for (int dx = -6; dx <= 6; dx++) {
            for (int y = 0; y <= 3; y++) {
                boolean post = dx == -6 || dx == 6;
                level.setBlock(o.offset(dx, y, 0), (post ? Blocks.STRIPPED_SPRUCE_LOG : Blocks.SPRUCE_PLANKS).defaultBlockState(), 3);
            }
        }
        List<ItemStack> show = new ArrayList<>();
        show.add(new ItemStack(LeisureItems.QUILT_ITEM.get()));
        show.add(new ItemStack(LeisureItems.LUTE.get()));
        show.add(new ItemStack(LeisureItems.DRAUGHTS_BOARD_ITEM.get()));
        show.add(kite(DyeColor.RED));
        show.add(new ItemStack(LeisureItems.LEATHER_FOOTBALL.get()));
        ItemStack slate = new ItemStack(LeisureItems.SLATE.get());
        SlateItem.chalk(slate, "Home and play!");
        show.add(slate);
        show.add(new ItemStack(LeisureItems.lantern(DyeColor.RED)));
        for (int i = 0; i < show.size(); i++) {
            BlockPos at = o.offset(-3 + i, 2, 1);
            ItemFrame f = new ItemFrame(level, at, Direction.SOUTH);
            if (!f.survives()) continue;
            f.setItem(show.get(i), false);
            f.setInvulnerable(true);
            level.addFreshEntity(f);
        }
        // A row of lanterns along the top of the wall, one of every colour.
        DyeColor[] cs = DyeColor.values();
        for (int i = 0; i < 13; i++) {
            BlockPos at = o.offset(-6 + i, 4, 0);
            level.setBlock(at, LeisureItems.LANTERNS.get(cs[i % cs.length]).get().defaultBlockState(), 3);
        }
        // Hung ones at each end, from the posts' tops, over the ground before the wall.
        for (int dx : new int[]{ -6, 6 }) {
            level.setBlock(o.offset(dx, 3, 1), Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
            level.setBlock(o.offset(dx, 2, 1), LeisureItems.LANTERNS.get(dx < 0 ? DyeColor.ORANGE : DyeColor.LIGHT_BLUE).get().defaultBlockState()
                .setValue(com.jrpetty.mcassistant.block.PaperLanternBlock.HANGING, true), 3);
        }
        // A bed before the wall, its head to the north, and a quilt on it.
        BlockPos head = o.offset(-4, 0, 2), foot = o.offset(-4, 0, 3);
        level.setBlock(head, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD), 3);
        level.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT), 3);
        Quilts.lay(level, foot);
        // A draughts table: a post with the board on it, a bench either side.
        BlockPos post = o.offset(1, 0, 3);
        level.setBlock(post, Blocks.SPRUCE_FENCE.defaultBlockState(), 3);
        level.setBlock(post.west(), Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST), 3);
        level.setBlock(post.east(), Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST), 3);
        BlockPos board = post.above();
        level.setBlock(board, LeisureItems.DRAUGHTS_BOARD.get().defaultBlockState()
            .setValue(com.jrpetty.mcassistant.block.DraughtsBoardBlock.FACING, Direction.WEST), 3);
        // The football on the grass.
        FootballEntity ball = FootballEntity.setDown(level, o.getX() + 4.5, o.getY() + 0.1, o.getZ() + 3.5,
            new ItemStack(LeisureItems.LEATHER_FOOTBALL.get()), null);
        out.add("SHOWCASE " + o.getX() + " " + o.getY() + " " + o.getZ() + (ball != null ? "; the ball down" : ""));
        out.add(view("showcase", o.getX() + 0.5, o.getY() + 2.6, o.getZ() + 10.5, o.getX() + 0.5, o.getY() + 1.6, o.getZ() + 1.0));
        // ---- in use
        List<VillageFolkEntity> grown = new ArrayList<>(), children = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(id)) {
            if (!(a instanceof VillageFolkEntity f) || !f.isAlive() || f.isShowcase() || f.isSleeping()) continue;
            (f.isBaby() ? children : grown).add(f);
        }
        grown.sort(java.util.Comparator.comparingDouble(f -> f.distanceToSqr(o.getX(), o.getY(), o.getZ())));
        children.sort(java.util.Comparator.comparingDouble(f -> f.distanceToSqr(o.getX(), o.getY(), o.getZ())));
        int g = 0;
        // Draughts at the showcase's table: two of the town sat down, a few moves played.
        if (grown.size() >= 2) {
            VillageFolkEntity red = grown.get(g++), black = grown.get(g++);
            red.teleportTo(post.getX() - 0.5, post.getY(), post.getZ() + 0.5);
            black.teleportTo(post.getX() + 1.5, post.getY(), post.getZ() + 0.5);
            String began = Draughts.beginForTests(level, board, red, black);
            if (began != null) {
                Draughts.playForTests(level, board, 14);
                out.add("DRAUGHTS " + began + ": " + java.util.Arrays.toString(Draughts.gameForTests(board)));
            }
            out.add(view("draughts", post.getX() + 0.5, post.getY() + 2.3, post.getZ() + 3.6, post.getX() + 0.5, post.getY() + 1.0, post.getZ() + 0.5));
        }
        // Asleep under the quilt (as long as it is let lie).
        if (g < grown.size()) {
            VillageFolkEntity sleeper = grown.get(g++);
            sleeper.teleportTo(foot.getX() + 0.5, foot.getY() + 0.6, foot.getZ() + 0.5);
            sleeper.startSleeping(head);
            out.add("QUILT " + sleeper.displayNameCap() + " asleep under it");
        }
        out.add(view("quilt", foot.getX() + 2.6, foot.getY() + 2.4, foot.getZ() + 2.4, foot.getX() + 0.5, foot.getY() + 0.5, foot.getZ()));
        // The lute: the town's buskers out with lutes (one lent out of the stores to each), or a folk by the wall playing one.
        for (int i = 0; i < 2; i++) Crafts.store(level, v, new ItemStack(LeisureItems.LUTE.get()));
        List<String> busk = Buskers.now(level, v);
        boolean busking = !busk.isEmpty() && !busk.get(0).startsWith("busk none");
        if (busking) {
            out.addAll(busk);
        } else if (g < grown.size()) {
            VillageFolkEntity player = grown.get(g++);
            player.teleportTo(o.getX() + 3.5, o.getY(), o.getZ() + 2.5);
            player.insertItem(new ItemStack(LeisureItems.LUTE.get()));
            Lutes.inHand(player);
            Lutes.stage(player, now + 1200L);
            out.add("LUTE " + player.displayNameCap() + " playing by the wall");
            out.add(view("lute", o.getX() + 3.5, o.getY() + 1.8, o.getZ() + 5.0, o.getX() + 3.5, o.getY() + 1.3, o.getZ() + 2.5));
        }
        // Kites over the showcase, flown by two children; a third with its slate.
        int c = 0;
        for (DyeColor colour : new DyeColor[]{ DyeColor.RED, DyeColor.YELLOW }) {
            if (c >= children.size()) break;
            VillageFolkEntity child = children.get(c++);
            child.teleportTo(o.getX() + (colour == DyeColor.RED ? -2.5 : 2.5), o.getY(), o.getZ() + 6.5);
            child.insertItem(kite(colour));
            Kites.stage(level, child, now + 1800L);
            out.add("KITE " + child.displayNameCap() + " flying a " + colour.getName() + " kite");
        }
        out.add(view("kites", o.getX() + 0.5, o.getY() + 1.2, o.getZ() + 14.0, o.getX() + 0.5, o.getY() + 9.0, o.getZ() + 2.0));
        if (c < children.size()) {
            VillageFolkEntity child = children.get(c++);
            child.teleportTo(o.getX() - 1.5, o.getY(), o.getZ() + 4.5);
            ItemStack s = new ItemStack(LeisureItems.SLATE.get());
            SlateItem.chalk(s, Slates.lesson(AssistantEntity.StationTask.FARM));
            child.insertItem(s);
            Slates.inHand(child);
            out.add("SLATE " + child.displayNameCap());
            out.add(view("slate", o.getX() - 1.5, o.getY() + 1.3, o.getZ() + 6.6, o.getX() - 1.5, o.getY() + 0.8, o.getZ() + 4.5));
        }
        // The children's kickabout on their green, with a football out of the stores.
        Crafts.store(level, v, new ItemStack(LeisureItems.LEATHER_FOOTBALL.get()));
        if (Kickabout.on(id)) Kickabout.endForTests(level, id);
        Pastimes.afternoonForTests(level, id, Pastimes.BALL);
        BlockPos ground = (BlockPos) Pastimes.playground(id, v)[0];
        for (int i = c; i < children.size(); i++) {
            VillageFolkEntity k = children.get(i);
            k.teleportTo(ground.getX() + 0.5 + (i % 3) * 2 - 2, ground.getY(), ground.getZ() + 0.5 + (i / 3) * 2 - 2);
            Kickabout.holdForTests(level, k);
        }
        out.add("KICKABOUT " + Kickabout.status(level, v));
        out.add(view("kickabout", ground.getX() + 9.5, ground.getY() + 6.0, ground.getZ() + 9.5, ground.getX() + 0.5, ground.getY() + 0.5,
            ground.getZ() + 0.5));
        // The lanterns strung across the square (their makings put in the stores for it).
        for (DyeColor colour : Lanterns.COLOURS) Crafts.store(level, v, new ItemStack(LeisureItems.lantern(colour)));
        Crafts.store(level, v, new ItemStack(Items.STRING, 20));
        Crafts.store(level, v, new ItemStack(Items.OAK_FENCE, 16));
        String lanterns = Lanterns.stringUp(level, v, day, Lanterns.tonight(id, day), true);
        List<BlockPos> hung = Lanterns.hungForTests(id);
        out.add("LANTERNS " + (lanterns == null ? hung.size() + " hung" : lanterns));
        if (!hung.isEmpty()) {
            BlockPos l = hung.get(0);
            out.add(view("lanterns", l.getX() + 6.5, l.getY() + 1.5, l.getZ() + 7.5, l.getX() + 0.5, l.getY(), l.getZ() + 0.5));
        }
        // A league match with the leather ball, if the town has its pitch.
        Ledger.Building pitch = Pitch.of(id);
        if (pitch != null) {
            Crafts.store(level, v, new ItemStack(LeisureItems.LEATHER_FOOTBALL.get()));
            String m = Football.fixtureForTests(level, v, day);
            out.add("MATCH " + (m == null ? "a match is on already" : m));
            BlockPos a = pitch.anchor();
            Direction r = pitch.facing().getClockWise();
            out.add(view("match", a.getX() + 0.5 + r.getStepX() * 11, a.getY() + 6.0, a.getZ() + 0.5 + r.getStepZ() * 11, a.getX() + 0.5, a.getY() + 0.5,
                a.getZ() + 0.5));
        }
        out.add(0, "STAGE leisure in " + Villages.name(id));
        return out;
    }

    /** A kite in a dye's colour (as its recipe makes it). */
    static ItemStack kite(DyeColor c) {
        ItemStack k = new ItemStack(LeisureItems.KITE.get());
        k.set(DataComponents.DYED_COLOR, new DyedItemColor(DyedShapedRecipe.rgb(c), true));
        return k;
    }

    static String view(String name, double x, double y, double z, double ax, double ay, double az) {
        return String.format(Locale.ROOT, "VIEW %s %.2f %.2f %.2f %.2f %.2f %.2f", name, x, y, z, ax, ay, az);
    }
}
