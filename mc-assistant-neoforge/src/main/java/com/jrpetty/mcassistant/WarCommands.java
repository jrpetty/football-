package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Diplomacy;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WarAndPeace;
import com.jrpetty.mcassistant.entity.Wars;
import com.jrpetty.mcassistant.village.Ledger;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * /village war — war and peace between towns (entity/WarAndPeace).
 *
 * <pre>
 *   /village war            the nearest town: at peace, on its guard or at war; its strength; each war's
 *                           goal, cost, course and who is likelier to sue; its allies; each neighbour, what it
 *                           holds against it and whether its elder would go to war with it today, and why;
 *                           its treaties and past wars
 *   /village war books      the town's books, opened at the War page
 *   /village war council    (ops) the nearest town's council called to the hall now as a council of war over
 *                           its nearest neighbour (made a feud with a quarrel over the land, for the pictures);
 *                           prints where it sits, the side to look at it from and whether under a roof
 *                           ("AT x y z south outdoors": the middle of its ring, before the board's face)
 *   /village war declare    (ops) war declared now between the nearest town and its nearest neighbour: the bell,
 *                           the banners; prints where each banner hangs and which way its face looks
 *                           ("BANNER x y z north Oakford"; one on its own pole by the board adds the board's
 *                           foot, "BANNER x y z south Oakford BOARD x y z")
 *   /village war cloth      (ops) a red banner put into the nearest town's stores (for the pictures)
 *   /village war peace      (ops) the nearest town's war ended now, on the terms the balance of strength makes
 *   /village war memorial   (ops) the nearest town's newest war memorial put up now, whatever the hour and with no
 *                           walk for the hand, out of the stores as ever; prints where its post stands and which
 *                           way the sign on it looks ("MEMORIAL x y z south The war with / Oakford / ..."), or
 *                           "NO-MEMORIAL" and why
 * </pre>
 */
public final class WarCommands {

    private WarCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("war")
            .executes(WarCommands::status)
            .then(Commands.literal("books").executes(WarCommands::books))
            .then(Commands.literal("council").requires(src -> src.hasPermission(2)).executes(WarCommands::council))
            .then(Commands.literal("declare").requires(src -> src.hasPermission(2)).executes(WarCommands::declare))
            .then(Commands.literal("cloth").requires(src -> src.hasPermission(2)).executes(WarCommands::cloth))
            .then(Commands.literal("peace").requires(src -> src.hasPermission(2)).executes(WarCommands::peace))
            .then(Commands.literal("memorial").requires(src -> src.hasPermission(2)).executes(WarCommands::memorial));
    }

    @Nullable
    private static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, at, Villages.VILLAGE_RANGE * 4);
        if (v == null && ctx.getSource().getPlayer() == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    /** The town's nearest neighbour it knows, else the nearest other town in the world. */
    @Nullable
    private static Villages.Village other(CommandContext<CommandSourceStack> ctx, Villages.Village v) {
        for (Villages.Village o : Diplomacy.neighboursOf(v.id())) return o;
        Villages.Village best = null;
        double d = Double.MAX_VALUE;
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(v.id()) || !o.dim().equals(v.dim())) continue;
            double x = o.centre().distSqr(v.centre());
            if (x < d) { d = x; best = o; }
        }
        if (best == null) ctx.getSource().sendFailure(Component.literal(Villages.name(v.id()) + " has no neighbour to quarrel with."));
        return best;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        List<String> lines = WarAndPeace.lines(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }

    private static int books(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return status(ctx);
        net.minecraft.nbt.CompoundTag books = com.jrpetty.mcassistant.entity.Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "War");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    /** For the pictures: the two in a feud, with a quarrel over the land fresh in the books. */
    private static void feud(UUID a, UUID b, long day) {
        int r = Ledger.relation(a, b);
        if (r > -70) Ledger.relate(a, b, -70 - r);
        WarAndPeace.wrongForTests(a, b, day, WarAndPeace.Wrong.LAND, "a boundary stone between us was moved in the night");
    }

    private static int council(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        Villages.Village o = other(ctx, v);
        if (o == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        long day = level.getDayTime() / 24000L;
        feud(v.id(), o.id(), day);
        String said = WarAndPeace.councilForPictures(level, v, o);
        // Where it sits (the middle of its ring, as Assemblies seats it), the side to look at it from, and whether under a roof.
        WarAndPeace.CouncilSpot s = WarAndPeace.councilSpot(v.id());
        BlockPos at = s != null ? s.at() : v.centre();
        String where = "\nAT " + at.getX() + " " + at.getY() + " " + at.getZ() + " " + (s != null ? s.facing().getName() : "south")
            + " " + (s != null && s.indoors() ? "indoors" : "outdoors");
        ctx.getSource().sendSuccess(() -> Component.literal("COUNCIL " + Villages.name(v.id()) + " over " + Villages.name(o.id()) + ": " + said + where), false);
        return 1;
    }

    private static int declare(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        Villages.Village o = other(ctx, v);
        if (o == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        long day = level.getDayTime() / 24000L;
        feud(v.id(), o.id(), day);
        boolean done = WarAndPeace.declare(level, v.id(), o.id(), day, WarAndPeace.Goal.BORDER, 0,
            "a border where we say it runs, with stones to mark it");
        StringBuilder sb = new StringBuilder("DECLARE " + Villages.name(v.id()) + " on " + Villages.name(o.id()) + ": "
            + (done ? "war" : Wars.atWar(v.id(), o.id()) ? "at war already" : "no war (wars switched off?)"));
        for (UUID[] p : new UUID[][]{ { v.id(), o.id() }, { o.id(), v.id() } }) {
            BlockPos at = WarAndPeace.bannerAt(p[0], p[1]);
            if (at == null) continue;
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(at);
            // The way its face looks: a wall banner's FACING; a standing one's ROTATION (set square to the board's face).
            String facing = "up";
            boolean pole = false;
            if (st.hasProperty(net.minecraft.world.level.block.WallBannerBlock.FACING)) {
                facing = st.getValue(net.minecraft.world.level.block.WallBannerBlock.FACING).getName();
            } else if (st.hasProperty(net.minecraft.world.level.block.BannerBlock.ROTATION)) {
                pole = true;
                int rot = st.getValue(net.minecraft.world.level.block.BannerBlock.ROTATION);
                if (rot % 4 == 0) facing = net.minecraft.core.Direction.from2DDataValue(rot / 4).getName();
            }
            sb.append("\nBANNER ").append(at.getX()).append(' ').append(at.getY()).append(' ').append(at.getZ())
                .append(' ').append(facing).append(' ').append(Villages.name(p[0]));
            // On a pole by the board: where the board's foot is too, so a picture can take in the board, the pole and the square.
            BlockPos foot = pole ? com.jrpetty.mcassistant.entity.VillageBoards.lectern(p[0]) : null;
            if (foot != null) sb.append(" BOARD ").append(foot.getX()).append(' ').append(foot.getY()).append(' ').append(foot.getZ());
        }
        String said = sb.toString();
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return done ? 1 : 0;
    }

    /** For the pictures: a red banner put into the nearest town's stores, as a player might bring one. */
    private static int cloth(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        boolean in = WarAndPeace.clothForPictures(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal("CLOTH " + (in ? "a red banner put into the stores of " : "no room in the stores of ")
            + Villages.name(v.id())), false);
        return in ? 1 : 0;
    }

    private static int memorial(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        String said = WarAndPeace.memorialForPictures(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return said.startsWith("MEMORIAL ") ? 1 : 0;
    }

    private static int peace(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        List<UUID> foes = Wars.enemies(v.id());
        if (foes.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(Villages.name(v.id()) + " is at war with nobody."));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        long day = level.getDayTime() / 24000L;
        String text = WarAndPeace.makePeace(level, v.id(), foes.get(0), day, WarAndPeace.terms(level, v.id(), foes.get(0)), "by order", null);
        ctx.getSource().sendSuccess(() -> Component.literal("PEACE " + Villages.name(v.id()) + " and " + Villages.name(foes.get(0)) + ": " + text), false);
        return 1;
    }
}
