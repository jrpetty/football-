package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [teleport] Going straight to a folk: the "Teleport to ..." button under a folk's name in the town's books and on
 * its card, and {@code /village tp <name>} for operators, consoles and scripts.
 *
 * <p>The button is the creative player's: in survival it is never drawn, and a payload from an old or a doctored
 * client is turned away here all the same. On a dedicated server the player must be an operator (level two) as
 * well, since a creative server is often a building server whose owner would not want every builder hopping
 * between towns and worlds; in your own world, or a LAN game you host, creative is enough. The command asks
 * only for level two, like vanilla's /tp: an operator may go anywhere already.
 *
 * <p>The folk is looked for by its id in every world that is loaded, the Nether included (the Nether Age sends
 * folk through portals), and the player goes across worlds if need be. A folk whose part of the world has gone to
 * sleep around it is not lost: where it stood when its chunk was put away is noted (on the way out of the level)
 * and the chunk is woken to go there. The player is set down on the ground beside it, never inside a block or
 * over a drop, nowhere that burns, freezes or carries you off through a portal, at its own level and in sight of it
 * where that can be had near by, and turned to face it.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class FolkTeleport {

    private FolkTeleport() {}

    /** How far round a folk a place to stand is looked for, and how far above or below its feet. */
    public static final int REACH = 4, RISE = 3;

    /** How it went: whether the player went, what to tell it, and where it was set down. */
    public record Outcome(boolean ok, String message, @Nullable Vec3 at) {
        static Outcome no(String why) {
            return new Outcome(false, why, null);
        }
    }

    /** A folk found: the world it is in, itself if it is awake (null if only where it was last seen is known), where, its name. */
    private record Found(ServerLevel level, @Nullable VillageFolkEntity folk, BlockPos at, String name) {}

    /** Where a folk stood when its part of the world was put away: its world, its feet, its name. */
    private record Seen(ResourceKey<Level> dim, BlockPos at, String name) {}

    private static final Map<UUID, Seen> SEEN = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ who may

    /** May this player go straight to a folk from the screens? In creative, and an operator on a dedicated server. */
    public static boolean allowed(ServerPlayer player) {
        // The game mode itself, not isCreative(): that can be anything a subclass likes (the game tests' stand-in
        // player answers yes whatever its mode is).
        if (player.gameMode.getGameModeForPlayer() != GameType.CREATIVE) return false;
        MinecraftServer server = player.getServer();
        return server == null || !server.isDedicatedServer() || player.hasPermissions(2);
    }

    /** The button: checked, found, and gone (or not, and why). */
    public static Outcome request(ServerPlayer player, UUID folk) {
        if (player.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
            return Outcome.no("Only a player in creative mode can go straight to a folk.");
        }
        if (!allowed(player)) return Outcome.no("On a server only an operator in creative mode can go straight to a folk.");
        Found f = find(player.server, folk);
        if (f == null) return Outcome.no("There is no such folk about: it may have died, or moved on.");
        return go(player, f);
    }

    // ------------------------------------------------------------------ finding it

    @Nullable
    private static Found find(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(id);
            if (e == null) continue;
            // Only ever a folk: an id that names a player, a horse or anything else takes nobody anywhere.
            if (!(e instanceof VillageFolkEntity f) || !f.isAlive()) return null;
            return new Found(level, f, f.blockPosition(), f.displayNameCap());
        }
        Seen s = SEEN.get(id);
        ServerLevel level = s == null ? null : server.getLevel(s.dim());
        if (level == null) return null;
        // Known, but asleep with its chunk: wake the chunk. The folk in it come back to the world a moment after
        // the ground does, so the player is set down where it was last seen and turned that way.
        level.getChunk(s.at().getX() >> 4, s.at().getZ() >> 4);
        VillageFolkEntity awake = level.getEntity(id) instanceof VillageFolkEntity f && f.isAlive() ? f : null;
        return new Found(level, awake, awake != null ? awake.blockPosition() : s.at(), s.name());
    }

    /** The awake folk of every town by this name, the nearest to {@code near} first; then any asleep by it. */
    @Nullable
    private static Found byName(MinecraftServer server, String name, Entity near) {
        Found best = null;
        double bestD = Double.MAX_VALUE;
        for (Villages.Village v : Villages.every()) {
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (!(a instanceof VillageFolkEntity f) || !(f.level() instanceof ServerLevel level)
                    || !f.displayNameCap().equalsIgnoreCase(name)) continue;
                double d = level == near.level() ? f.distanceToSqr(near) : 1.0E12;      // this world's first
                if (d < bestD) {
                    best = new Found(level, f, f.blockPosition(), f.displayNameCap());
                    bestD = d;
                }
            }
        }
        if (best != null) return best;
        for (Map.Entry<UUID, Seen> e : SEEN.entrySet()) {
            if (e.getValue().name().equalsIgnoreCase(name)) return find(server, e.getKey());
        }
        return null;
    }

    // ------------------------------------------------------------------ going

    private static Outcome go(ServerPlayer player, Found f) {
        Vec3 look = f.folk() != null ? f.folk().getEyePosition() : Vec3.atBottomCenterOf(f.at()).add(0.0, 1.6, 0.0);
        Vec3 spot = spot(f.level(), player, f.at(), look);
        if (spot == null) return Outcome.no("There is nowhere safe to stand by " + f.name() + " just now.");
        double dx = look.x - spot.x, dz = look.z - spot.z, dy = look.y - (spot.y + player.getEyeHeight(Pose.STANDING));
        float yaw = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F);
        float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI));
        boolean across = f.level() != player.serverLevel();
        // Off whatever it rides and out of bed, into the other world if the folk is in one, facing the folk.
        player.teleportTo(f.level(), spot.x, spot.y, spot.z, Set.of(), yaw, pitch);
        if (player.serverLevel() != f.level()) {
            // The crossing was stopped (another mod may forbid it): say so rather than claim it.
            return Outcome.no("Something kept you from crossing to " + worldName(f.level()) + " to " + f.name() + ".");
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        player.setOnGround(true);
        String said = (f.folk() != null ? "Teleported to " + f.name() : "Teleported to where " + f.name() + " was last seen")
            + (across ? ", in " + worldName(f.level()) : "") + ".";
        return new Outcome(true, said, spot);
    }

    private static String worldName(ServerLevel level) {
        if (level.dimension() == Level.NETHER) return "the Nether";
        if (level.dimension() == Level.END) return "the End";
        if (level.dimension() == Level.OVERWORLD) return "the Overworld";
        return level.dimension().location().getPath().replace('_', ' ');
    }

    /**
     * Somewhere to set a player down by a folk standing at {@code at}: good ground as near as may be, each block
     * away a point, each block up or down two (on top of the wall beside a folk is no place to arrive), and two
     * more where the folk cannot be seen from (so a player is set down in the room with a folk sooner than outside
     * its wall, though the wall is nearer). If there is nowhere at all round about, where the folk stands itself.
     * Null if not even that will do (a folk swimming in lava, say).
     */
    @Nullable
    static Vec3 spot(ServerLevel level, Player player, BlockPos at, Vec3 look) {
        List<int[]> around = new ArrayList<>();
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 == 0 || d2 > REACH * REACH) continue;
                for (int dy = -RISE; dy <= RISE; dy++) around.add(new int[]{ dx, dy, dz });
            }
        }
        around.sort(Comparator.comparingDouble(FolkTeleport::cost));
        Vec3 best = null;
        double bestCost = Double.MAX_VALUE;
        for (int[] c : around) {
            double cost = cost(c);
            if (cost >= bestCost) break;                                   // nothing further on can do better
            Vec3 v = standOn(level, player, at.offset(c[0], c[1], c[2]));
            if (v == null) continue;
            if (!sees(level, player, v, look)) cost += 2.0;
            if (cost < bestCost) {
                best = v;
                bestCost = cost;
            }
        }
        if (best != null) return best;
        for (int dy : new int[]{ 0, 1, -1 }) {
            Vec3 v = standOn(level, player, at.above(dy));
            if (v != null) return v;
        }
        return null;
    }

    /** What a place to stand costs before it is looked at: a point a block away, two a block up or down. */
    private static double cost(int[] c) {
        return Math.sqrt(c[0] * c[0] + c[2] * c[2]) + 2.0 * Math.abs(c[1]);
    }

    /** A player standing with its feet in this block: on what is under it, in nothing, harmed by nothing. Null if not. */
    @Nullable
    static Vec3 standOn(ServerLevel level, Player player, BlockPos feet) {
        if (level.isOutsideBuildHeight(feet.below()) || level.isOutsideBuildHeight(feet.above())) return null;
        BlockPos below = feet.below();
        BlockState ground = level.getBlockState(below);
        VoxelShape shape = ground.getCollisionShape(level, below, CollisionContext.of(player));
        if (shape.isEmpty() || sore(ground)) return null;            // air, water or grass underfoot: a drop
        double top = shape.max(Direction.Axis.Y);
        if (top < 0.5 || top > 1.0) return null;                       // a carpet's thickness over a hole, a fence's top
        Vec3 v = new Vec3(feet.getX() + 0.5, below.getY() + top, feet.getZ() + 0.5);
        AABB box = player.getDimensions(Pose.STANDING).makeBoundingBox(v);
        if (!level.noCollision(player, box) || level.containsAnyLiquid(box)) return null;
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (harmful(level.getBlockState(p))) return null;
        }
        return v;
    }

    /** Ground that hurts to stand on. */
    private static boolean sore(BlockState s) {
        return s.is(Blocks.MAGMA_BLOCK) || s.is(BlockTags.CAMPFIRES) || s.is(Blocks.CACTUS) || s.is(Blocks.POINTED_DRIPSTONE)
            || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW);
    }

    /** What has no shape to bump into but burns, freezes, snares or carries you off all the same. */
    private static boolean harmful(BlockState s) {
        return s.is(BlockTags.FIRE) || s.is(Blocks.COBWEB) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW)
            || s.is(Blocks.WITHER_ROSE) || s.is(Blocks.NETHER_PORTAL) || s.is(Blocks.END_PORTAL) || s.is(Blocks.END_GATEWAY);
    }

    /** From a player's eyes standing here, is there a clear line to the folk's? */
    private static boolean sees(ServerLevel level, Player player, Vec3 feet, Vec3 look) {
        Vec3 eye = feet.add(0.0, player.getEyeHeight(Pose.STANDING), 0.0);
        return level.clip(new ClipContext(eye, look, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
            .getType() == HitResult.Type.MISS;
    }

    // ------------------------------------------------------------------ the command

    /** {@code /village tp <name>}: an operator (or a script running as a player) to a folk by name. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("tp").requires(src -> src.hasPermission(2))
            .then(Commands.argument("name", StringArgumentType.greedyString())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(names(), b))
                .executes(ctx -> tp(ctx, StringArgumentType.getString(ctx, "name").trim())));
    }

    private static int tp(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("Somebody has to go: /execute as <player> run village tp " + name));
            return 0;
        }
        Found f = byName(src.getServer(), name, player);
        if (f == null) {
            src.sendFailure(Component.literal("No folk called " + name + " is about."));
            return 0;
        }
        Outcome o = go(player, f);
        if (!o.ok()) {
            src.sendFailure(Component.literal(o.message()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal(o.message()), false);
        return 1;
    }

    /** Every awake folk's name, for the command's suggestions. */
    private static Set<String> names() {
        Set<String> out = new TreeSet<>();
        for (Villages.Village v : Villages.every()) {
            for (AssistantEntity a : Villages.folkOf(v.id())) {
                if (a instanceof VillageFolkEntity f) out.add(f.displayNameCap());
                if (out.size() >= 400) return out;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the folk asleep

    /** A folk's chunk put away: note where it stood. Any other going (dead, gone to another world): forget it. */
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof VillageFolkEntity f)) return;
        if (f.getRemovalReason() == Entity.RemovalReason.UNLOADED_TO_CHUNK) {
            SEEN.put(f.getUUID(), new Seen(f.level().dimension(), f.blockPosition(), f.displayNameCap()));
        } else {
            SEEN.remove(f.getUUID());
        }
    }

    /** Awake again (or arrived in another world): found the ordinary way. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof VillageFolkEntity f) SEEN.remove(f.getUUID());
    }

    /** A world opened: nothing is known yet of where anybody stands (another world's folk may still be in memory). */
    @SubscribeEvent
    public static void onStarting(ServerStartingEvent event) {
        SEEN.clear();
    }

    /** Tests: is this folk noted as asleep, and where? */
    @Nullable
    public static BlockPos seenForTests(UUID folk) {
        Seen s = SEEN.get(folk);
        return s == null ? null : s.at();
    }
}
