package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.AssistantEntity;
import com.jrpetty.mcassistant.entity.Individual;
import com.jrpetty.mcassistant.entity.IndividualStage;
import com.jrpetty.mcassistant.entity.Looks;
import com.jrpetty.mcassistant.entity.VillageFolkEntity;
import com.jrpetty.mcassistant.entity.Villages;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * [individual] /village individual — every folk its own person (entity/Individual, Looks, Manner, Fears, Habits,
 * Dreams, Keepsakes). Registered on its own: brigadier joins it to the rest of /village by name.
 *
 * <pre>
 *   /village individual              the nearest town's people in the round: how many faces it has (and whether any
 *                                    two are alike that are not twins), its heights, its fears, habits, favourite
 *                                    places, dreams, keepsakes carried, its readers and its left-handers
 *   /village individual folk NAME    one folk: its looks, its card's lines and its story
 *   /village individual card         (a player) the nearest folk's card opened on your screen, at its About page
 *   /village individual stage        (ops) the smoke's stage where you stand: a crowd of eighteen of every age, a
 *                                    family of five, an old folk on the bench by the well with its pipe
 * </pre>
 */
public final class IndividualCommands {

    private IndividualCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("individual").executes(IndividualCommands::town)
                .then(Commands.literal("folk").then(Commands.argument("name", StringArgumentType.greedyString())
                    .executes(IndividualCommands::folk)))
                .then(Commands.literal("card").executes(IndividualCommands::card))
                .then(Commands.literal("stage").requires(src -> src.hasPermission(2)).executes(IndividualCommands::stage))));
    }

    @Nullable
    private static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(ctx.getSource().getLevel(), at, Villages.VILLAGE_RANGE * 2);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village near enough."));
        return v;
    }

    private static int town(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        List<String> lines = summary(v);
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }

    /** The town's people in the round, a line a thing. */
    public static List<String> summary(Villages.Village v) {
        List<VillageFolkEntity> folk = new ArrayList<>();
        for (AssistantEntity a : Villages.folkOf(v.id())) if (a instanceof VillageFolkEntity f && f.isAlive()) folk.add(f);
        Set<Long> faces = new HashSet<>();
        Map<String, Integer> fears = new TreeMap<>(), habits = new TreeMap<>(), places = new TreeMap<>(), dreams = new TreeMap<>();
        float shortest = 9, tallest = 0;
        int keepsakes = 0, readers = 0, lefties = 0, specs = 0, scars = 0;
        for (VillageFolkEntity f : folk) {
            Individual.ensure(f);
            Individual.refreshLook(f);
            long look = f.clientLook();
            faces.add(look & ~(0b111111L << 38) & ~(0b111L << 44) & ~(0b11L << 47));   // the face itself, not its height or years
            if (!f.isBaby() && Looks.known(look)) {
                float h = Looks.heightOfStep(Looks.heightStepOf(look));
                shortest = Math.min(shortest, h);
                tallest = Math.max(tallest, h);
            }
            Individual.Self s = f.individual();
            for (var x : s.fears) fears.merge(x.word, 1, Integer::sum);
            for (var h : s.habits) habits.merge(h.word, 1, Integer::sum);
            places.merge(s.place.word, 1, Integer::sum);
            if (f.persona().rolled()) dreams.merge(f.persona().ambition().hope, 1, Integer::sum);
            if (s.keepsakeDay >= 0) keepsakes++;
            if (s.literate) readers++;
            if (s.genes.leftHanded) lefties++;
            if (s.scar > 0 || s.patch > 0) scars++;
            if (Individual.specsOf(f.clientMarks())) specs++;
        }
        List<String> out = new ArrayList<>();
        out.add("INDIVIDUAL " + Villages.name(v.id()) + ": " + folk.size() + " folk, " + faces.size() + " different faces"
            + (folk.isEmpty() ? "" : String.format("; grown heights %.2f to %.2f", shortest, tallest)));
        out.add("  fears: " + fears + "; habits: " + habits);
        out.add("  favourite places: " + places);
        out.add("  dreams: " + dreams);
        out.add("  keepsakes carried: " + keepsakes + "; can read: " + readers + "; left-handed: " + lefties
            + "; in spectacles: " + specs + "; scarred: " + scars);
        return out;
    }

    private static int folk(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name").trim();
        ServerLevel level = ctx.getSource().getLevel();
        VillageFolkEntity found = null;
        for (VillageFolkEntity f : level.getEntitiesOfClass(VillageFolkEntity.class,
                new net.minecraft.world.phys.AABB(BlockPos.containing(ctx.getSource().getPosition())).inflate(256.0))) {
            if (f.displayNameCap().equalsIgnoreCase(name)) { found = f; break; }
        }
        if (found == null) {
            ctx.getSource().sendFailure(Component.literal("Nobody called " + name + " near here."));
            return 0;
        }
        VillageFolkEntity f = found;
        List<String> lines = new ArrayList<>();
        lines.add("FOLK " + f.displayNameCap() + ", " + (f.isBaby() ? "a child" : f.ageYears() + " years") + ", voice pitch "
            + String.format("%.2f", com.jrpetty.mcassistant.entity.Manner.basePitch(f)) + " (" + com.jrpetty.mcassistant.entity.Manner.voice(f).name().toLowerCase() + ")");
        lines.add("  Looks: " + Individual.looksLine(f));
        for (String[] l : Individual.cardLines(f)) lines.add("  " + l[0] + ": " + l[1]);
        lines.add("  Story: " + Individual.about(f));
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return 1;
    }

    private static int card(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer p)) {
            ctx.getSource().sendFailure(Component.literal("Only a player can be shown a card."));
            return 0;
        }
        boolean shown = IndividualStage.card(ctx.getSource().getLevel(), p);
        if (!shown) ctx.getSource().sendFailure(Component.literal("Nobody near enough to talk to."));
        return shown ? 1 : 0;
    }

    private static int stage(CommandContext<CommandSourceStack> ctx) {
        List<String> lines = IndividualStage.stage(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()));
        ctx.getSource().sendSuccess(() -> Component.literal(String.join("\n", lines)), false);
        return lines.size();
    }
}
