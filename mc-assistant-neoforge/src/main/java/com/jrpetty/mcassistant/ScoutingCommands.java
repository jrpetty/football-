package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Intel;
import com.jrpetty.mcassistant.entity.Pickets;
import com.jrpetty.mcassistant.entity.Spying;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.jrpetty.mcassistant.entity.WarMap;
import com.jrpetty.mcassistant.entity.Wars;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [war-scouting] /village war — the scouting side of a war: the war map, the reports, the pickets.
 *
 * <pre>
 *   /village war map            the nearest town's war map, in words: its rivals, the last report on each and
 *                               its age, what the leader believes and how the two stand, folk of theirs seen
 *                               on the approaches, the pickets, the spies out, the captives
 *   /village war map books      the town's books opened at the War map page
 *   /village war intel          a line a rival: the report, the belief, the balance (for scripts)
 *   /village war scout now      (ops) somebody sent at once to watch the nearest town's first rival, put down at
 *                               its vantage to watch; says where to look from ("VIEW name x y z at-x at-y at-z")
 *   /village war scout home     (ops) the nearest town's spies home now with what they have counted, reports filed
 *   /village war scout stage    (ops) the nearest town and the next nearest set at war, for the pictures
 *   /village war pickets now    (ops) the nearest town's pickets out at their posts now
 * </pre>
 */
public final class ScoutingCommands {

    private ScoutingCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("war")
            .then(Commands.literal("map").executes(ScoutingCommands::map)
                .then(Commands.literal("books").executes(ScoutingCommands::books)))
            .then(Commands.literal("intel").executes(ScoutingCommands::intel))
            .then(Commands.literal("scout")
                .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ScoutingCommands::scoutNow))
                .then(Commands.literal("home").requires(src -> src.hasPermission(2)).executes(ScoutingCommands::scoutHome))
                .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(ScoutingCommands::stage)))
            .then(Commands.literal("pickets")
                .then(Commands.literal("now").requires(src -> src.hasPermission(2)).executes(ScoutingCommands::picketsNow)));
    }

    @Nullable
    private static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), at, Villages.VILLAGE_RANGE * 4);
        if (v == null && ctx.getSource().getPlayer() == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village near enough."));
        return v;
    }

    private static int map(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        String text = Villages.name(v.id()) + " — the war map\n" + WarMap.page(ctx.getSource().getLevel(), v);
        ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int books(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) return map(ctx);
        net.minecraft.nbt.CompoundTag books = com.jrpetty.mcassistant.entity.Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "War map");
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    private static int intel(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        long today = level.getDayTime() / 24000L;
        List<String> lines = new ArrayList<>();
        for (UUID them : Spying.rivals(v.id())) {
            Intel.Report r = Intel.latest(v.id(), them);
            Intel.Reckoning k = Intel.strength(level, v.id(), them);
            lines.add("INTEL " + Villages.name(them) + " report " + (r == null ? "none" : r.guards() + "g " + r.armoured() + "a " + r.archers()
                + "b walls " + r.walls() + " gates " + r.gates() + " food " + r.foodDays() + " age " + Intel.age(r, today))
                + " believed " + k.theirs().guards() + "g (" + k.theirs().basis() + ") ours " + k.ours().guards() + "g balance "
                + String.format(java.util.Locale.ROOT, "%.2f", k.balance()) + " wanted " + Intel.guardsNeeded(level, v.id(), them, today));
        }
        String said = lines.isEmpty() ? "INTEL " + Villages.name(v.id()) + ": no rivals" : String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return lines.size();
    }

    /** The nearest town and the next nearest at war, for the pictures (Wars: the war-and-peace work's own way in). */
    private static int stage(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        Villages.Village other = null;
        double best = Double.MAX_VALUE;
        for (Villages.Village o : Villages.every()) {
            if (o.id().equals(v.id()) || !o.dim().equals(v.dim())) continue;
            double d = o.centre().distSqr(v.centre());
            if (d < best) {
                best = d;
                other = o;
            }
        }
        if (other == null) {
            ctx.getSource().sendFailure(Component.literal("There is no other town to be at war with."));
            return 0;
        }
        long day = ctx.getSource().getLevel().getDayTime() / 24000L;
        if (!Wars.atWar(v.id(), other.id())) Wars.begin(v.id(), other.id(), day);
        String said = "WAR " + Villages.name(v.id()) + " and " + Villages.name(other.id()) + " at war since day " + Wars.since(v.id(), other.id());
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return 1;
    }

    /** Somebody sent now to watch the first rival, put down at its vantage, with where to look from. */
    private static int scoutNow(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        List<UUID> rivals = Spying.rivals(v.id());
        if (rivals.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(Villages.name(v.id()) + " is at odds with nobody: nobody to watch."));
            return 0;
        }
        VillageFolkEntity spy = Spying.pick(v);
        if (spy == null) {
            ctx.getSource().sendFailure(Component.literal("Nobody in " + Villages.name(v.id()) + " free to go."));
            return 0;
        }
        long day = level.getDayTime() / 24000L;
        // (For the pictures: a loaf in its pack if the stores have none, so that it goes.)
        if (spy.countMatching(s -> s.is(Items.BREAD)) == 0) spy.insertItem(new ItemStack(Items.BREAD, 2));
        if (!Spying.send(level, spy, v, rivals.get(0), day)) {
            ctx.getSource().sendFailure(Component.literal(spy.displayNameCap() + " would not go."));
            return 0;
        }
        Spying.atVantageForTests(spy);
        Villages.Village them = Villages.get(rivals.get(0));
        BlockPos s = spy.blockPosition(), h = them == null ? s : them.centre();
        double dx = s.getX() - h.getX(), dz = s.getZ() - h.getZ(), len = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
        // Over its shoulder: four blocks behind it and a little above, looking down on the town's heart.
        int ex = (int) Math.round(s.getX() + dx / len * 4), ez = (int) Math.round(s.getZ() + dz / len * 4);
        Spying.Mission m = Spying.missionOf(spy);
        String said = "SCOUT " + spy.displayNameCap() + " of " + Villages.name(v.id()) + " watching " + Villages.name(rivals.get(0))
            + " from " + s.toShortString() + ", " + (int) len + " blocks from its heart"
            + (m == null ? "" : "; first look: " + m.guardsSeen() + " guards")
            + "\nVIEW s1-spy-ridge " + ex + " " + (s.getY() + 3) + " " + ez + " " + h.getX() + " " + (h.getY() + 1) + " " + h.getZ();
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return 1;
    }

    /** The town's spies out home now with what they have counted (as if they had walked back), their reports filed. */
    private static int scoutHome(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        // Only those who have lain at their vantage and looked come home: one still on the road has nothing to
        // tell. (The first run of this brought home a folk the town had sent of its own accord that morning,
        // halfway there, as well as the one the command sent, and each came home with "no report".)
        List<String> lines = new ArrayList<>();
        for (String s : Spying.homeNow(ctx.getSource().getLevel(), v)) lines.add("HOME " + s);
        String said = lines.isEmpty() ? "HOME nobody out watching" : String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return lines.size();
    }

    private static int picketsNow(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        List<VillageFolkEntity> on = Pickets.postNowForTests(ctx.getSource().getLevel(), v);
        List<String> lines = new ArrayList<>();
        for (VillageFolkEntity f : on) {
            Pickets.Post p = Pickets.postOf(f);
            if (p == null) continue;
            f.moveTo(p.at().getX() + 0.5, p.at().getY(), p.at().getZ() + 0.5, f.getYRot(), 0.0F);
            lines.add("PICKET " + f.displayNameCap() + " on " + p.road() + " at " + p.at().getX() + " " + p.at().getY() + " " + p.at().getZ());
        }
        String said = lines.isEmpty() ? "PICKETS none (" + Villages.name(v.id()) + " at peace, or nobody to spare)" : String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Component.literal(said), false);
        return lines.size();
    }
}
