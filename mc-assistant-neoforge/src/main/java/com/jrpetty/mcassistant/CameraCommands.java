package com.jrpetty.mcassistant;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * [war-polish] The pictures' camera, kept out of the ground (tools/realworld/smoke.py asks before every shot):
 * <pre>
 *   /village camera &lt;x y z&gt; &lt;tx ty tz&gt;   (ops) "CAMERA x y z clear|moved|blocked|unloaded": where a camera
 *                                         whose feet stand at x y z (its eyes 1.62 above) should stand to see tx ty tz
 * </pre>
 * The spot is good when the camera's eyes are in open air (not in a block, leaves or water) and nothing solid stands
 * between them and the target (glass, bars and grass are seen through). Otherwise it steps back from the target and
 * up, a few blocks at a time; failing that (a view down a gallery underground), forward along its line to the first
 * open spot that sees it. A few dozen block looks and ray casts, once a shot.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class CameraCommands {

    private CameraCommands() {}

    /** A camera's eyes over its feet. */
    static final double EYES = 1.62;
    /** How far it steps back or up each time, and how many times before it tries along the line. */
    static final double STEP = 3.0;
    static final int TRIES = 8;
    /** A hit this near the target is the target's own ground or clutter: the view is clear. */
    static final double NEAR_TARGET = 1.5;
    /** How far round the eyes must be clear (the near edge of the picture). */
    static final double ROOM = 0.3;

    /** Where the camera stands and how it came there. */
    public record Spot(Vec3 feet, String how) {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("camera").requires(src -> src.hasPermission(2))
                .then(Commands.argument("x", DoubleArgumentType.doubleArg()).then(Commands.argument("y", DoubleArgumentType.doubleArg())
                    .then(Commands.argument("z", DoubleArgumentType.doubleArg()).then(Commands.argument("tx", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("ty", DoubleArgumentType.doubleArg()).then(Commands.argument("tz", DoubleArgumentType.doubleArg())
                            .executes(CameraCommands::camera)))))))));
    }

    private static int camera(CommandContext<CommandSourceStack> ctx) {
        Vec3 feet = new Vec3(DoubleArgumentType.getDouble(ctx, "x"), DoubleArgumentType.getDouble(ctx, "y"), DoubleArgumentType.getDouble(ctx, "z"));
        Vec3 target = new Vec3(DoubleArgumentType.getDouble(ctx, "tx"), DoubleArgumentType.getDouble(ctx, "ty"), DoubleArgumentType.getDouble(ctx, "tz"));
        Spot s = clearSpot(ctx.getSource().getLevel(), feet, target);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT, "CAMERA %.2f %.2f %.2f %s",
            s.feet().x, s.feet().y, s.feet().z, s.how())), false);
        return 1;
    }

    /** Where a camera meant to stand at {@code feet} should stand to see {@code target} (see the class). */
    public static Spot clearSpot(ServerLevel level, Vec3 feet, Vec3 target) {
        if (!level.isLoaded(BlockPos.containing(feet)) || !level.isLoaded(BlockPos.containing(target))) return new Spot(feet, "unloaded");
        if (sees(level, feet, target)) return new Spot(feet, "clear");
        Vec3 away = new Vec3(feet.x - target.x, 0, feet.z - target.z);
        away = away.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : away.normalize();
        for (int k = 1; k <= TRIES; k++) {
            Vec3 back = feet.add(away.scale(STEP * k)), up = feet.add(0, STEP * k, 0), both = back.add(0, STEP * k / 2, 0);
            for (Vec3 p : new Vec3[]{ up, back, both }) {
                if (level.isLoaded(BlockPos.containing(p)) && sees(level, p, target)) return new Spot(p, "moved");
            }
        }
        Vec3 eye = feet.add(0, EYES, 0), line = target.subtract(eye);
        double length = line.length();
        for (double d = 1.0; d < length - NEAR_TARGET; d += 1.0) {
            Vec3 p = eye.add(line.scale(d / length)).subtract(0, EYES, 0);
            if (sees(level, p, target)) return new Spot(p, "moved");
        }
        return new Spot(feet, "blocked");
    }

    /** Are the camera's eyes in open air, with nothing solid between them and the target? */
    public static boolean sees(ServerLevel level, Vec3 feet, Vec3 target) {
        Vec3 eye = feet.add(0, EYES, 0);
        for (double dx = -ROOM; dx <= ROOM; dx += ROOM) {
            for (double dz = -ROOM; dz <= ROOM; dz += ROOM) {
                if (!open(level, BlockPos.containing(eye.x + dx, eye.y, eye.z + dz))) return false;
            }
        }
        if (!open(level, BlockPos.containing(eye.x, eye.y + ROOM, eye.z))) return false;
        HitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(target) <= NEAR_TARGET;
    }

    /** Open air for a camera's eyes: nothing it would be inside (a block, leaves, water). */
    static boolean open(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        if (st.isAir()) return true;
        return st.getFluidState().isEmpty() && !st.is(BlockTags.LEAVES) && st.getCollisionShape(level, p).isEmpty();
    }
}
