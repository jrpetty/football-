package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.Showcase;
import com.jrpetty.mcassistant.block.StorehouseBlock;
import com.jrpetty.mcassistant.entity.AssistantEntity.StationTask;
import com.jrpetty.mcassistant.entity.goal.BuildGoal;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * [redstone] The engineer's commands: /village redstone (the town's works, its machines and their numbers), and for an
 * operator, /village redstone now (the stores looked at, an engineer taken on, the next machine planned) and the
 * pictures' stage.
 *
 * <p><b>The stage</b> (/village redstone stage, run where it is wanted): the redstone workshop with the engineer before
 * its door; the cane farm with one of its pistons caught mid-fire; three lamp posts along a path; the hopper sorter laid
 * against a storehouse, its filters stocked; and a stretch of wall with the piston gate in it, open
 * (/village redstone stage gate throws its lever). Laid for nothing, from the engineer's own drawings, for the smoke
 * run's photographs; it prints where to stand for each.
 */
public final class RedstoneStage {

    private RedstoneStage() {}

    /** The staged gate's lever, for "stage gate". */
    @Nullable private static BlockPos LEVER;
    private static final String TAG = "redstone_stage";

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("redstone")
            .executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                List<String> lines = Engineers.report(ctx.getSource().getLevel(), v.id());
                ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
                return lines.size();
            })
            .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ctx -> {
                Villages.Village v = here(ctx);
                if (v == null) return 0;
                ServerLevel level = ctx.getSource().getLevel();
                List<String> said = new ArrayList<>();
                said.add(Engineers.lookAtStores(level, v) ? "The trade is open." : "Not yet: the Diamond Age, and "
                    + Engineers.REDSTONE_TO_OPEN + " redstone and " + Engineers.QUARTZ_TO_OPEN + " quartz in the stores.");
                VillageFolkEntity f = Engineers.appoint(level, v);
                if (f != null) said.add(f.displayNameCap() + " became the engineer.");
                List<VillageFolkEntity> all = Engineers.engineers(v.id());
                Engineers.Machine m = Engineers.next(level, v, Engineers.works(v.id()), all.isEmpty() ? null : all.get(0));
                said.add(m == null ? "Nothing to plan now." : "Next: " + m.kind.words + " at " + m.origin.toShortString() + ".");
                ctx.getSource().sendSuccess(() -> Component.literal("REDSTONE " + String.join(" ", said)), false);
                return said.size();
            }))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    List<String> out = stage(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()));
                    ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", out)), false);
                    return out.size();
                })
                .then(Commands.literal("gate").executes(ctx -> {
                    ServerLevel level = ctx.getSource().getLevel();
                    if (LEVER == null || !level.getBlockState(LEVER).is(Blocks.LEVER)) {
                        ctx.getSource().sendFailure(Component.literal("No staged gate."));
                        return 0;
                    }
                    ((LeverBlock) Blocks.LEVER).pull(level.getBlockState(LEVER), level, LEVER, null);
                    boolean shut = level.getBlockState(LEVER).getValue(LeverBlock.POWERED);
                    ctx.getSource().sendSuccess(() -> Component.literal("GATE " + (shut ? "shut" : "open")), false);
                    return 1;
                })));
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    /** The stage's own stone: stone bricks, cut to slabs and walls, the gate of the same; the stems melon. */
    private static final Function<String, BlockState> STONE = name -> switch (name) {
        case "casing", "door" -> Blocks.STONE_BRICKS.defaultBlockState();
        case "casing_slab" -> Blocks.STONE_BRICK_SLAB.defaultBlockState();
        case "casing_wall" -> Blocks.STONE_BRICK_WALL.defaultBlockState();
        case "stem" -> Blocks.MELON_STEM.defaultBlockState();
        default -> null;
    };

    /** The stage, east of where it is run. Prints "VIEW name x y z lookx looky lookz" for each picture. */
    static List<String> stage(ServerLevel level, BlockPos at) {
        List<Entity> old = new ArrayList<>();
        for (Entity en : level.getAllEntities()) if (en.getTags().contains(TAG)) old.add(en);
        for (Entity en : old) en.discard();
        int x0 = at.getX(), z0 = at.getZ();
        int g = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0 + 30, z0);
        // Level ground: grass over earth, and the air clear above it.
        for (int x = x0; x <= x0 + 66; x++) {
            for (int z = z0 - 22; z <= z0 + 26; z++) {
                for (int y = g - 5; y <= g - 2; y++) level.setBlock(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x, g - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int y = g; y <= g + 10; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        List<String> out = new ArrayList<>();
        // The workshop, its door to the west, and the engineer before it.
        BlockPos shop = new BlockPos(x0 + 8, g, z0 - 12);
        BuildGoal.stamp(level, Engineers.WORKSHOP, shop, Direction.WEST, 13, Showcase.painter(Showcase.SPRUCE));
        VillageFolkEntity eng = com.jrpetty.mcassistant.McAssistantMod.VILLAGE_FOLK.get().create(level);
        if (eng != null) {
            eng.moveTo(shop.getX() - 6.5, g, shop.getZ() + 1.5, 90.0F, 0.0F);
            eng.setYHeadRot(90.0F);
            eng.setYBodyRot(90.0F);
            eng.makeShowcase(StationTask.REDSTONE);
            eng.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.REDSTONE_TORCH));
            eng.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.REPEATER));
            eng.addTag(TAG);
            level.addFreshEntity(eng);
        }
        out.add(view("engineer", shop.getX() - 12, g + 2, shop.getZ() + 3, shop.getX() - 6, g + 1, shop.getZ() + 1));
        // The cane farm, its glass to the south, the cane grown and one piston caught as it fires.
        BlockPos cane = new BlockPos(x0 + 30, g, z0 - 14);
        List<Machines.Placement> farm = Machines.plan(Machines.drawing(Engineers.Kind.CANE.drawing), cane, Direction.NORTH, STONE);
        Machines.build(level, farm);
        int firing = 0;
        for (Machines.Placement p : farm) {
            if (!p.state().is(Blocks.SUGAR_CANE)) continue;
            level.setBlock(p.pos().above(), Blocks.SUGAR_CANE.defaultBlockState(), 2 | 16);
            if (firing++ == 3) {
                // The piston behind this cane, out: its head where the cane's second block was (broken, and on its way down).
                BlockPos piston = p.pos().above().relative(Direction.NORTH);
                level.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.SOUTH)
                    .setValue(PistonBaseBlock.EXTENDED, true), 2 | 16);
                level.setBlock(p.pos().above(), Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, Direction.SOUTH)
                    .setValue(PistonHeadBlock.TYPE, PistonType.DEFAULT), 2 | 16);
            } else {
                level.setBlock(p.pos().above(2), Blocks.SUGAR_CANE.defaultBlockState(), 2 | 16);
            }
        }
        out.add(view("cane", cane.getX() + 1, g + 4, cane.getZ() + 10, cane.getX(), g + 1, cane.getZ()));
        // Three lamp posts along a path.
        for (int i = 0; i < 3; i++) {
            BlockPos post = new BlockPos(x0 + 50, g, z0 - 16 + 7 * i);
            Machines.build(level, Machines.plan(Machines.drawing(Engineers.Kind.LAMP.drawing), post, Direction.NORTH, STONE));
        }
        for (int z = z0 - 18; z <= z0 + 2; z++) {
            for (int x = x0 + 51; x <= x0 + 53; x++) level.setBlock(new BlockPos(x, g - 1, z), Blocks.DIRT_PATH.defaultBlockState(), 2);
        }
        out.add(view("lamps", x0 + 57, g + 3, z0 + 6, x0 + 51, g + 2, z0 - 9));
        // The storehouse, and the sorter against it: its line runs east from the storehouse's east face.
        BlockPos o = new BlockPos(x0 + 6, g, z0 + 12);
        StorehouseBlock.hintFront(Direction.SOUTH);
        try {
            for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
                level.setBlock(o.offset(x, y, z), StorehouseBlock.loose(), 3);
            }
        } finally {
            StorehouseBlock.hintFront(null);
        }
        Machines.Drawing sd = Machines.drawing(Engineers.Kind.SORTER.drawing);
        Machines.Cell x = sd.of('X').get(0);
        Direction right = Direction.WEST, back = right.getCounterClockWise();
        BlockPos target = o.offset(2, 1, 0);
        BlockPos so = target.relative(right, -x.dx()).relative(back, -x.dz()).below(x.h());
        for (Machines.Placement p : Machines.plan(sd, so, back, STONE)) {
            if (level.getBlockState(p.pos()).getBlock() instanceof StorehouseBlock) continue;
            Machines.place(level, p);
        }
        net.minecraft.world.item.Item[] goods = { Items.COBBLESTONE, Items.OAK_LOG, Items.WHEAT, Items.CARROT, Items.POTATO, Items.COAL,
            Items.RAW_IRON, Items.SAND, Items.WHEAT_SEEDS, Items.BREAD };
        List<Machines.Cell> fs = new ArrayList<>(sd.of('f')), ks = new ArrayList<>(sd.of('K'));
        fs.sort(java.util.Comparator.comparingInt(Machines.Cell::dx));
        ks.sort(java.util.Comparator.comparingInt(Machines.Cell::dx));
        for (int i = 0; i < fs.size(); i++) {
            if (level.getBlockEntity(Machines.at(so, back, fs.get(i))) instanceof Container f) {
                f.setItem(0, new ItemStack(goods[i], 18));
                for (int s = 1; s < 5; s++) f.setItem(s, new ItemStack(goods[i], 1));
            }
            if (level.getBlockEntity(Machines.at(so, back, ks.get(i))) instanceof Container k) k.setItem(0, new ItemStack(goods[i], 32 + 3 * i));
        }
        BlockPos mid = Machines.at(so, back, 0, 1, 0);
        out.add(view("sorter", mid.getX() + 2, g + 6, mid.getZ() - 9, mid.getX() - 3, g, mid.getZ()));
        // A stretch of wall with its gate: the wall three high, pillars five, the gap five wide, the piston gate in it.
        int wz = z0 + 22, wx = x0 + 52;
        for (int along = -9; along <= 9; along++) {
            boolean pillar = Math.abs(along) == 3;
            if (Math.abs(along) <= 2) continue;
            for (int h = 0; h <= (pillar ? 4 : 2); h++) level.setBlock(new BlockPos(wx + along, g + h, wz), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            if (pillar) level.setBlock(new BlockPos(wx + along, g + 5, wz), Blocks.LANTERN.defaultBlockState(), 2);
            else if (Math.floorMod(along, 2) == 0) level.setBlock(new BlockPos(wx + along, g + 3, wz), Blocks.STONE_BRICK_SLAB.defaultBlockState(), 2);
        }
        // The gate's outside is the south; its right runs west, as the town wall's south gate does.
        Direction out_ = Direction.SOUTH;
        BlockPos gate = new BlockPos(wx, g, wz).relative(out_.getClockWise(), -1);
        Machines.build(level, Machines.plan(Machines.drawing(Engineers.Kind.GATE.drawing), gate, out_, STONE));
        LEVER = null;
        for (Machines.Cell c : Machines.drawing(Engineers.Kind.GATE.drawing).of('L')) LEVER = Machines.at(gate, out_, c);
        out.add(view("gate", wx, g + 3, wz + 10, wx, g + 1, wz));
        out.add(view("gate-inside", wx - 4, g + 3, wz - 7, wx - 1, g + 1, wz));
        return out;
    }

    private static String view(String name, int x, int y, int z, int ax, int ay, int az) {
        return "VIEW " + name + " " + x + " " + y + " " + z + " " + ax + " " + ay + " " + az;
    }
}
