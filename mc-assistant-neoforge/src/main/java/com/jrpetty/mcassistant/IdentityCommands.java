package com.jrpetty.mcassistant;

import com.jrpetty.mcassistant.entity.Ethos;
import com.jrpetty.mcassistant.entity.Fame;
import com.jrpetty.mcassistant.entity.Government;
import com.jrpetty.mcassistant.entity.Identity;
import com.jrpetty.mcassistant.entity.LawBook;
import com.jrpetty.mcassistant.entity.PlayerLeader;
import com.jrpetty.mcassistant.entity.TownTraits;
import com.jrpetty.mcassistant.entity.Villages;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * [identity] /village identity — what makes the nearest town itself (entity/Identity). Registered on its own
 * (Brigadier joins it to the rest of /village).
 *
 * <pre>
 *   /village identity                      its summary, axes, government, laws, traits, fame, renown and how it treats players
 *   /village identity books                the town's books open at the Identity page
 *   /village identity law &lt;law&gt; &lt;n&gt;       set a law (an operator, or the player who leads the town)
 *   /village identity morning              (ops) the town's morning look at its identity, now
 *   /village identity seed                 (ops) seed it again from its land and the folk it has now
 *   /village identity set &lt;axis&gt; &lt;v&gt;      (ops) an axis, -100 to 100
 *   /village identity gov &lt;form&gt;          (ops) its government: reeve, council, lord, guild, commune, chaplain
 *   /village identity trait &lt;trait&gt;       (ops) a trait earned now (its perk with it)
 *   /village identity fair                 (ops) its fame fair held now
 *   /village identity profile &lt;name&gt;     (ops) a whole character at once, for the pictures: port, hold, abbey, commune
 * </pre>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class IdentityCommands {

    private IdentityCommands() {}

    private static final List<String> PROFILES = Arrays.asList("port", "hold", "abbey", "commune");

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        List<String> axes = new ArrayList<>(), forms = new ArrayList<>(), laws = new ArrayList<>(), traits = new ArrayList<>();
        for (Ethos.Axis a : Ethos.Axis.values()) axes.add(a.name().toLowerCase(Locale.ROOT));
        for (Government.Form f : Government.Form.values()) forms.add(f.name().toLowerCase(Locale.ROOT));
        for (LawBook.Law l : LawBook.Law.values()) laws.add(l.name().toLowerCase(Locale.ROOT));
        for (TownTraits.Trait t : TownTraits.Trait.values()) traits.add(t.name().toLowerCase(Locale.ROOT));
        event.getDispatcher().register(Commands.literal("village")
            .then(Commands.literal("identity").executes(IdentityCommands::status)
                .then(Commands.literal("books").executes(IdentityCommands::books))
                .then(Commands.literal("law")
                    .then(Commands.argument("law", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(laws, b))
                        .then(Commands.argument("n", IntegerArgumentType.integer(0, 3))
                            .executes(ctx -> law(ctx, StringArgumentType.getString(ctx, "law"), IntegerArgumentType.getInteger(ctx, "n"))))))
                .then(Commands.literal("morning").requires(s -> s.hasPermission(2)).executes(IdentityCommands::morning))
                .then(Commands.literal("seed").requires(s -> s.hasPermission(2)).executes(IdentityCommands::seed))
                .then(Commands.literal("fair").requires(s -> s.hasPermission(2)).executes(IdentityCommands::fair))
                .then(Commands.literal("speak").requires(s -> s.hasPermission(2)).executes(IdentityCommands::speak))
                .then(Commands.literal("set").requires(s -> s.hasPermission(2))
                    .then(Commands.argument("axis", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(axes, b))
                        .then(Commands.argument("v", IntegerArgumentType.integer(-100, 100))
                            .executes(ctx -> set(ctx, StringArgumentType.getString(ctx, "axis"), IntegerArgumentType.getInteger(ctx, "v"))))))
                .then(Commands.literal("gov").requires(s -> s.hasPermission(2))
                    .then(Commands.argument("form", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(forms, b))
                        .executes(ctx -> gov(ctx, StringArgumentType.getString(ctx, "form")))))
                .then(Commands.literal("trait").requires(s -> s.hasPermission(2))
                    .then(Commands.argument("trait", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(traits, b))
                        .executes(ctx -> trait(ctx, StringArgumentType.getString(ctx, "trait")))))
                .then(Commands.literal("profile").requires(s -> s.hasPermission(2))
                    .then(Commands.argument("name", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(PROFILES, b))
                        .executes(ctx -> profile(ctx, StringArgumentType.getString(ctx, "name")))))));
    }

    private static Villages.Village near(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        BlockPos here = BlockPos.containing(ctx.getSource().getPosition());
        Villages.Village v = Villages.nearest(level, here, Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village yet."));
        return v;
    }

    private static int say(CommandContext<CommandSourceStack> ctx, String s) {
        ctx.getSource().sendSuccess(() -> Component.literal(s), false);
        return 1;
    }

    private static long day(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getLevel().getDayTime() / 24000L;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        return v == null ? 0 : say(ctx, Identity.status(ctx.getSource().getLevel(), v));
    }

    private static int books(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer p)) {
            ctx.getSource().sendFailure(Component.literal("The books open on a player's screen."));
            return 0;
        }
        net.minecraft.nbt.CompoundTag books = com.jrpetty.mcassistant.entity.Annals.snapshot(ctx.getSource().getLevel(), v);
        books.putString("page", "Identity");                    // the Identity page (client/CityScreen.TABS)
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new com.jrpetty.mcassistant.net.CityStatsPayload(books));
        return 1;
    }

    private static int law(CommandContext<CommandSourceStack> ctx, String name, int n) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        LawBook.Law l = LawBook.Law.named(name);
        if (l == null) return say(ctx, "No such law. The laws: " + Arrays.toString(LawBook.Law.values()).toLowerCase(Locale.ROOT));
        boolean op = ctx.getSource().hasPermission(2);
        ServerPlayer p = ctx.getSource().getPlayer();
        boolean leads = p != null && PlayerLeader.leads(v.id(), p.getUUID());
        if (!op && !leads) return say(ctx, "Only the town's leader sets its laws.");
        String by = leads ? capital(Government.title(v.id())) + " " + p.getName().getString() + " decreed it" : "by order";
        return say(ctx, "LAW " + LawBook.set(ctx.getSource().getLevel(), v, l, n, by));
    }

    private static int morning(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        Identity.morningForTests(ctx.getSource().getLevel(), v, day(ctx));
        return say(ctx, "MORNING " + Identity.summary(v.id()));
    }

    private static int seed(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        Identity.seedForTests(ctx.getSource().getLevel(), v);
        return say(ctx, "SEEDED " + Identity.summary(v.id()) + " Axes: " + Ethos.words(v.id()));
    }

    private static int fair(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        int coin = Fame.fairForTests(ctx.getSource().getLevel(), v);
        return say(ctx, "FAIR " + (coin > 0 ? coin + " coin taken" : "none held (not famous for anything, or too little in the stores)"));
    }

    /**
     * (ops, the pictures) The nearest grown folk of the town says out loud what its town is like, as it would to a
     * player who asked; held where it stands a while. Says where the camera's eyes go to see it say it:
     * "VIEW speaker x y z ax ay az" (the eyes three blocks in front of it, a little above; looking at its face).
     */
    private static int speak(CommandContext<CommandSourceStack> ctx) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.world.phys.Vec3 at = ctx.getSource().getPosition();
        com.jrpetty.mcassistant.entity.VillageFolkEntity best = null;
        double nearest = Double.MAX_VALUE;
        for (com.jrpetty.mcassistant.entity.AssistantEntity a : Villages.folkOf(v.id())) {
            if (!(a instanceof com.jrpetty.mcassistant.entity.VillageFolkEntity f) || f.isBaby() || f.isSleeping() || !f.isAlive()) continue;
            double d = f.position().distanceToSqr(at);
            if (d < nearest) { nearest = d; best = f; }
        }
        if (best == null) return say(ctx, "Nobody about to speak.");
        String words = Identity.describe(best);
        best.getNavigation().stop();
        com.jrpetty.mcassistant.entity.FolkTalk.speak(best, words);
        float yaw = best.getYRot() * ((float) Math.PI / 180F);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double ex = best.getX() + fx * 3.0, ey = best.getEyeY() + 0.4, ez = best.getZ() + fz * 3.0;
        return say(ctx, String.format(Locale.ROOT, "SPEAK %s: %s%nVIEW speaker %.1f %.1f %.1f %.1f %.1f %.1f", best.displayNameCap(), words,
            ex, ey, ez, best.getX(), best.getEyeY(), best.getZ()));
    }

    private static int set(CommandContext<CommandSourceStack> ctx, String axis, int value) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        Ethos.Axis a;
        try { a = Ethos.Axis.valueOf(axis.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return say(ctx, "No such axis."); }
        Ethos.setForTests(v.id(), a, value);
        return say(ctx, "AXIS " + Identity.summary(v.id()));
    }

    private static int gov(CommandContext<CommandSourceStack> ctx, String form) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        Government.Form f = Government.Form.named(form);
        if (f == null) return say(ctx, "No such government.");
        Government.setForTests(ctx.getSource().getLevel(), v, f);
        return say(ctx, "GOV " + Identity.summary(v.id()));
    }

    private static int trait(CommandContext<CommandSourceStack> ctx, String name) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        TownTraits.Trait t = TownTraits.Trait.named(name);
        if (t == null) return say(ctx, "No such trait.");
        TownTraits.awardForTests(ctx.getSource().getLevel(), v, t);
        return say(ctx, "TRAIT " + Identity.summary(v.id()));
    }

    /**
     * A whole character at once, for the pictures: a mercantile, open, worldly port under an elected leader; a martial,
     * closed, traditional hold under a lord; a devout abbey-town under its chaplain; an egalitarian commune. The laws
     * follow from it (its next morning's review, run now) and a trait or two to show as badges.
     */
    private static int profile(CommandContext<CommandSourceStack> ctx, String name) {
        Villages.Village v = near(ctx);
        if (v == null) return 0;
        ServerLevel level = ctx.getSource().getLevel();
        int[] a;
        Government.Form f;
        TownTraits.Trait[] t;
        switch (name) {
            case "port" -> { a = new int[]{ 70, -30, -45, 20, 65, -40, 10 }; f = Government.Form.REEVE;
                t = new TownTraits.Trait[]{ TownTraits.Trait.SEAFARERS, TownTraits.Trait.HOSPITABLE }; }
            case "hold" -> { a = new int[]{ -35, 75, 20, -45, -70, 55, -60 }; f = Government.Form.LORD;
                t = new TownTraits.Trait[]{ TownTraits.Trait.RAID_SCARRED, TownTraits.Trait.IRON_WILLED }; }
            case "abbey" -> { a = new int[]{ -20, -40, 80, 45, -10, 60, 30 }; f = Government.Form.CHAPLAIN;
                t = new TownTraits.Trait[]{ TownTraits.Trait.BOOKISH }; }
            case "commune" -> { a = new int[]{ -40, -50, -20, -30, 30, -45, 80 }; f = Government.Form.COMMUNE;
                t = new TownTraits.Trait[]{ TownTraits.Trait.WELL_WED, TownTraits.Trait.GOLDEN_FIELDS }; }
            default -> { return say(ctx, "No such profile: " + PROFILES); }
        }
        Identity.Rec r = Identity.recForTests(v.id());
        if (!r.seeded) Identity.seedForTests(level, v);
        for (Ethos.Axis x : Ethos.Axis.values()) Ethos.setForTests(v.id(), x, a[x.ordinal()]);
        Government.setForTests(level, v, f);
        LawBook.resetLawsForTests(level, v);
        for (TownTraits.Trait x : t) TownTraits.awardForTests(level, v, x);
        return say(ctx, "PROFILE " + Identity.summary(v.id()) + " Laws: " + LawBook.notice(v.id(), 6));
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
