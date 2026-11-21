package com.jrpetty.mcassistant.gametest;

import com.jrpetty.mcassistant.block.VillageFolkSpawnerBlock;
import com.jrpetty.mcassistant.entity.FolkTeleport;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.net.FolkTeleportPayload;
import com.jrpetty.mcassistant.net.TeleportNetwork;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * [teleport] Going straight to a folk from the town's books or its card (FolkTeleport, TeleportNetwork): a creative
 * player is set down on the ground beside the folk, in nothing and over no drop, facing it, even when every block
 * round the folk is stone or a pit; a survival player (and an adventurer, and a spectator) is turned away where it
 * stands, though the game tests' stand-in player answers "creative" whatever its mode; an id that is nobody, a cow,
 * the player itself or a folk now dead takes nobody anywhere; {@code /village tp <name>} does the same for an
 * operator and not for anyone else; and a folk asleep in an unloaded chunk is gone to where it was last seen.
 *
 * <p>Each on its own ground (x 940000 to 948000, z 66000), in a batch of its own. The test world is flat, three
 * blocks of earth over bedrock, so each stands a block of stone on it, eight high, to dig pits into.
 */
@GameTestHolder("mc_assistant")
@PrefixGameTestTemplate(false)
public class TeleportGameTests {

    private static final String EMPTY = "empty";
    private static final int Z = 66000;

    /** A block of stone on the flat world, {@code half} out each way and eight high, the air over it clear: its top's y. */
    private static int yard(ServerLevel level, int cx, int cz, int half) {
        int base = Kit.surface(level, cx, cz).getY();
        int top = base + 8;
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                for (int y = base; y < top + 5; y++) {
                    level.setBlock(new BlockPos(cx + dx, y, cz + dz), (y < top ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
                }
            }
        }
        return top;
    }

    /** A town founded at {@code heart}, and its founder stood still at {@code at}. */
    private static VillageFolkEntity folk(GameTestHelper helper, ServerLevel level, BlockPos heart, BlockPos at) {
        VillageFolkEntity f = VillageFolkSpawnerBlock.raise(level, heart, 0.0F);
        helper.assertTrue(f != null && f.ownerId() != null, "a folk, and its town");
        f.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0F, 0.0F);
        f.getNavigation().stop();
        return f;
    }

    /** The game tests' stand-in player, in this mode, standing at {@code from}. */
    private static ServerPlayer player(GameTestHelper helper, ServerLevel level, GameType mode, BlockPos from) {
        ServerPlayer p = helper.makeMockServerPlayerInLevel();
        p.setGameMode(mode);
        p.teleportTo(level, from.getX() + 0.5, from.getY(), from.getZ() + 0.5, Set.of(), 0.0F, 0.0F);
        return p;
    }

    private static String at(Vec3 v) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    /** How a player was set down by a folk: how far off, how high against the folk's feet, how far its look is off the folk, and what it stands in and on. */
    private record Landing(double flat, double rise, double lookOff, boolean feetFree, boolean headFree, BlockState under, boolean clear, boolean wet) {
        boolean safe() {
            return feetFree && headFree && clear && !wet && !under.isAir();
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%.2f off, %.2f up, looking %.1f° off it; feet free %s, head free %s, on %s, clear %s, wet %s",
                flat, rise, lookOff, feetFree, headFree, under.getBlock().getName().getString(), clear, wet);
        }
    }

    private static Landing landing(ServerLevel level, ServerPlayer p, Vec3 folk) {
        Vec3 to = p.position();
        BlockPos feet = BlockPos.containing(to);
        double yawTo = Mth.wrapDegrees(Math.toDegrees(Math.atan2(folk.z - to.z, folk.x - to.x)) - 90.0);
        return new Landing(Math.hypot(to.x - folk.x, to.z - folk.z), to.y - folk.y, Math.abs(Mth.wrapDegrees(yawTo - p.getYRot())),
            level.getBlockState(feet).getCollisionShape(level, feet).isEmpty(),
            level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty(),
            level.getBlockState(feet.below()), level.noCollision(p, p.getBoundingBox()), level.containsAnyLiquid(p.getBoundingBox()));
    }

    /** A command run as this source, and everything it said. */
    private static List<String> run(ServerLevel level, CommandSourceStack as, String text) {
        List<String> said = new ArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override public void sendSystemMessage(Component c) { said.add(c.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        level.getServer().getCommands().performPrefixedCommand(as.withSource(sink), text);
        return said;
    }

    // ================================================================== tp01: beside it

    /**
     * A folk penned in: a pillar of stone two high on each side of it and a pit six deep at each corner, so a step
     * any way from it is into stone or over a drop. A creative player thirty-odd blocks off presses "Teleport to"
     * (the payload's handler, as the server runs it): it is set down on the stone at the folk's own level, a little
     * way off, in nothing, over no pit, and turned to face the folk.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "tp01_beside_it")
    public static void tp01_beside_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 940000;
        Kit.hold(level, x, Z, 32);
        Kit.prepare(level, x, Z, 32);
        int top = yard(level, x, Z, 14);
        BlockPos spot = new BlockPos(x + 4, top, Z + 4);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            level.setBlock(spot.relative(d), Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(spot.relative(d).above(), Blocks.STONE.defaultBlockState(), 2);
        }
        for (int[] c : new int[][]{ { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 } }) {
            for (int k = 1; k <= 6; k++) level.setBlock(spot.offset(c[0], -k, c[1]), Blocks.AIR.defaultBlockState(), 2);
        }
        VillageFolkEntity f = folk(helper, level, new BlockPos(x - 8, top, Z - 8), spot);
        ServerPlayer p = player(helper, level, GameType.CREATIVE, new BlockPos(x - 20, top, Z + 12));
        Vec3 from = p.position(), folkAt = f.position();
        FolkTeleport.Outcome o = TeleportNetwork.handleForTests(p, new FolkTeleportPayload(f.getUUID()));
        Landing l = landing(level, p, folkAt);
        BlockPos feet = p.blockPosition();
        boolean inPen = Math.abs(feet.getX() - spot.getX()) <= 1 && Math.abs(feet.getZ() - spot.getZ()) <= 1;
        Kit.log("tp01 " + f.displayNameCap() + " penned in at " + at(folkAt) + "; the player from " + at(from) + " ("
            + String.format(Locale.ROOT, "%.1f", from.distanceTo(folkAt)) + " away) to " + at(p.position()) + ": " + l
            + "; in the pen " + inPen + "; said \"" + o.message() + "\"");
        helper.assertTrue(o.ok() && o.message().startsWith("Teleported to " + f.displayNameCap()), "it went: " + o.message());
        helper.assertTrue(p.level() == level && p.position().distanceTo(from) > 20, "it moved from where it was: " + at(p.position()));
        helper.assertTrue(l.safe(), "set down in nothing, on the ground: " + l);
        helper.assertTrue(l.under().is(Blocks.STONE) && Math.abs(l.rise()) < 0.01, "on the yard's stone at the folk's own level: " + l);
        helper.assertTrue(!inPen, "not on a pillar or in a pit round the folk: " + feet.toShortString());
        helper.assertTrue(l.flat() >= 1.5 && l.flat() <= FolkTeleport.REACH + 0.75, "beside the folk: " + l);
        helper.assertTrue(l.lookOff() < 5.0, "facing the folk: " + l);
        helper.succeed();
    }

    // ================================================================== tp02: not in survival

    /**
     * A survival player sends the payload all the same (an old client, or a doctored one): refused, and it stays
     * where it stands; an adventurer and a spectator too. The stand-in player says it is creative whatever its
     * mode, so this is the server reading the game mode itself.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "tp02_not_in_survival")
    public static void tp02_not_in_survival(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 942000;
        Kit.hold(level, x, Z, 32);
        Kit.prepare(level, x, Z, 32);
        int top = yard(level, x, Z, 14);
        VillageFolkEntity f = folk(helper, level, new BlockPos(x - 8, top, Z - 8), new BlockPos(x + 4, top, Z + 4));
        ServerPlayer p = player(helper, level, GameType.SURVIVAL, new BlockPos(x - 12, top, Z + 12));
        boolean saysCreative = p.isCreative();
        for (GameType mode : new GameType[]{ GameType.SURVIVAL, GameType.ADVENTURE, GameType.SPECTATOR }) {
            p.setGameMode(mode);
            Vec3 from = p.position();
            FolkTeleport.Outcome o = TeleportNetwork.handleForTests(p, new FolkTeleportPayload(f.getUUID()));
            double moved = p.position().distanceTo(from);
            Kit.log("tp02 " + mode.getName() + " (isCreative says " + saysCreative + ", allowed " + FolkTeleport.allowed(p) + "): went "
                + o.ok() + ", moved " + String.format(Locale.ROOT, "%.3f", moved) + " from " + at(from) + "; said \"" + o.message() + "\"");
            helper.assertTrue(!o.ok() && o.message().contains("creative"), mode.getName() + ": refused, and told why: " + o.message());
            helper.assertTrue(moved < 1.0E-6 && p.level() == level, mode.getName() + ": not moved: " + moved);
            helper.assertTrue(!FolkTeleport.allowed(p), mode.getName() + ": not allowed");
        }
        p.setGameMode(GameType.CREATIVE);
        helper.assertTrue(FolkTeleport.allowed(p), "in creative, in a world not on a dedicated server, it is allowed");
        helper.succeed();
    }

    // ================================================================== tp03: nobody by that id

    /**
     * Ids that are no folk: a made-up one, a cow's, the player's own, and a folk's that has died since the books were
     * sent. Each refused, and the creative player stays where it is.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "tp03_unknown_refused")
    public static void tp03_unknown_refused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 944000;
        Kit.hold(level, x, Z, 32);
        Kit.prepare(level, x, Z, 32);
        int top = yard(level, x, Z, 14);
        VillageFolkEntity f = folk(helper, level, new BlockPos(x - 8, top, Z - 8), new BlockPos(x + 4, top, Z + 4));
        Cow cow = EntityType.COW.create(level);
        helper.assertTrue(cow != null, "a cow");
        cow.moveTo(x + 6.5, top, Z - 3.5, 0.0F, 0.0F);
        level.addFreshEntity(cow);
        ServerPlayer p = player(helper, level, GameType.CREATIVE, new BlockPos(x - 12, top, Z + 12));
        UUID dead = f.getUUID();
        f.discard();                                        // gone since the books were sent
        String[] what = { "a made-up id", "a cow", "the player itself", "a folk gone" };
        UUID[] ids = { UUID.randomUUID(), cow.getUUID(), p.getUUID(), dead };
        for (int i = 0; i < ids.length; i++) {
            Vec3 from = p.position();
            FolkTeleport.Outcome o = TeleportNetwork.handleForTests(p, new FolkTeleportPayload(ids[i]));
            double moved = p.position().distanceTo(from);
            Kit.log("tp03 " + what[i] + ": went " + o.ok() + ", moved " + String.format(Locale.ROOT, "%.3f", moved) + "; said \"" + o.message() + "\"");
            helper.assertTrue(!o.ok(), what[i] + ": refused: " + o.message());
            helper.assertTrue(moved < 1.0E-6, what[i] + ": not moved: " + moved);
        }
        helper.succeed();
    }

    // ================================================================== tp04: the command

    /**
     * {@code /village tp <name>}: run as an operator (level two), a survival player goes to the folk by name, in any
     * case of the letters, set down safe beside it; at level nought the command is not there for it and it stays;
     * from the console, with nobody to send, it says how to name who goes.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "tp04_command")
    public static void tp04_command(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 946000;
        Kit.hold(level, x, Z, 32);
        Kit.prepare(level, x, Z, 32);
        int top = yard(level, x, Z, 14);
        VillageFolkEntity f = folk(helper, level, new BlockPos(x - 8, top, Z - 8), new BlockPos(x + 4, top, Z + 4));
        String name = f.displayNameCap();
        BlockPos start = new BlockPos(x - 12, top, Z + 12);
        ServerPlayer p = player(helper, level, GameType.SURVIVAL, start);
        Vec3 folkAt = f.position();

        List<String> plain = run(level, p.createCommandSourceStack().withPermission(0), "village tp " + name);
        double stayed = p.position().distanceTo(Vec3.atBottomCenterOf(start));
        Kit.log("tp04 at level 0: moved " + String.format(Locale.ROOT, "%.3f", stayed) + "; said " + plain);
        helper.assertTrue(stayed < 1.0E-6, "at level nought nobody goes anywhere: " + stayed);

        List<String> op = run(level, p.createCommandSourceStack().withPermission(2), "village tp " + name.toLowerCase(Locale.ROOT));
        Landing l = landing(level, p, folkAt);
        Kit.log("tp04 as an operator, \"" + name.toLowerCase(Locale.ROOT) + "\": to " + at(p.position()) + ": " + l + "; said " + op);
        helper.assertTrue(op.stream().anyMatch(s -> s.startsWith("Teleported to " + name)), "it went: " + op);
        helper.assertTrue(l.safe() && l.under().is(Blocks.STONE) && Math.abs(l.rise()) < 0.01, "set down safe on the stone: " + l);
        helper.assertTrue(l.flat() >= 0.9 && l.flat() <= FolkTeleport.REACH + 0.75 && l.lookOff() < 5.0, "beside it and facing it: " + l);

        List<String> console = Kit.command(level, "village tp " + name);
        Kit.log("tp04 from the console: " + console);
        helper.assertTrue(console.stream().anyMatch(s -> s.contains("execute as")), "the console is told how to name who goes: " + console);
        helper.succeed();
    }

    // ================================================================== tp05: asleep

    /**
     * A folk whose chunk is put away: where it stood is noted as it leaves the world, it is no longer to be found
     * there by its id, and a creative player who asks for it is set down beside where it was last seen.
     */
    @GameTest(template = EMPTY, timeoutTicks = 100, batch = "tp05_asleep")
    public static void tp05_asleep(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Kit.reset(level);
        level.setDayTime(2000);
        int x = 948000;
        Kit.hold(level, x, Z, 32);
        Kit.prepare(level, x, Z, 32);
        int top = yard(level, x, Z, 14);
        BlockPos spot = new BlockPos(x + 4, top, Z + 4);
        VillageFolkEntity f = folk(helper, level, new BlockPos(x - 8, top, Z - 8), spot);
        UUID id = f.getUUID();
        String name = f.displayNameCap();
        Vec3 was = f.position();
        f.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        BlockPos seen = FolkTeleport.seenForTests(id);
        boolean gone = level.getEntity(id) == null;
        ServerPlayer p = player(helper, level, GameType.CREATIVE, new BlockPos(x - 12, top, Z + 12));
        FolkTeleport.Outcome o = TeleportNetwork.handleForTests(p, new FolkTeleportPayload(id));
        Landing l = landing(level, p, was);
        Kit.log("tp05 " + name + " asleep at " + at(was) + ": noted at " + (seen == null ? "nowhere" : seen.toShortString()) + ", gone from the level "
            + gone + "; the player to " + at(p.position()) + ": " + l + "; said \"" + o.message() + "\"");
        helper.assertTrue(gone && spot.equals(seen), "noted where it stood as its chunk was put away: " + seen);
        helper.assertTrue(o.ok() && o.message().contains("last seen"), "gone to where it was last seen: " + o.message());
        helper.assertTrue(l.safe() && Math.abs(l.rise()) < 0.01 && l.flat() >= 0.9 && l.flat() <= FolkTeleport.REACH + 0.75,
            "set down safe beside the spot: " + l);
        helper.succeed();
    }
}
