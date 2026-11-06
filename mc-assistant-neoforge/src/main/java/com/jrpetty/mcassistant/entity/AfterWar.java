package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.ChestBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [war-after] After the war: prisoners of war and their ransom (Prisoners), the ruins of towns that are no more (Ruins),
 * and the players a town has made outlaws, with the hunters it sends after them (Outlaws). This is where they meet the
 * rest of the mod: the second's round of the towns, the day's, the game's events (a blow, a death, a log-off, a chest
 * opened), the talk, the spirits, and the commands ({@code /village afterwar}).
 *
 * <p>What it costs: with no prisoner held, no ruin standing and no outlaw posted, a look at three empty lists a second.
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class AfterWar {

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    private AfterWar() {}

    private static final Random RNG = new Random();
    /** The day each town's daily round was last done (the world's books keep the day too: "aw.day"). */
    private static final Map<UUID, Long> DAY = new ConcurrentHashMap<>();
    private static volatile long worldDay = -1;

    /** Between the tests: what is only memory forgotten; the ruins and the outlaws' books cleared; the decay and the hunts stood down. */
    public static void resetForTests() {
        DAY.clear();
        worldDay = -1;
        Prisoners.resetForTests();
        Ruins.resetForTests();
        Outlaws.resetForTests();
    }

    // ------------------------------------------------------------------ the rounds

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 11) return;
        com.jrpetty.mcassistant.Guard.run("after the war", () -> {
            for (ServerLevel level : event.getServer().getAllLevels()) second(level);
        });
    }

    /** A second of one world: each town's prisoners and outlaws, the ruins' decay and visitors, the hunts; once a day, the day's. */
    static void second(ServerLevel level) {
        long day = level.getDayTime() / 24000L;
        for (Villages.Village v : Villages.every()) {
            if (!v.dim().equals(level.dimension())) continue;
            Prisoners.round(level, v, RNG);
            if (level.getGameTime() % 200 < 20) Prisoners.homeward(level, v);     // (a look at the town's notes once in ten seconds)
            Outlaws.watch(level, v);
            Long done = DAY.get(v.id());
            if (done == null) {
                done = (long) WarBooks.num(com.jrpetty.mcassistant.village.Ledger.note(v.id(), "aw.day"), -1);
                DAY.put(v.id(), done);
            }
            if (done < day && Math.floorMod(level.getDayTime(), 24000L) >= 2000L) {
                DAY.put(v.id(), day);
                com.jrpetty.mcassistant.village.Ledger.note(v.id(), "aw.day", Long.toString(day));
                com.jrpetty.mcassistant.Guard.run("after the war, a town's day", () -> daily(level, v, day));
            }
        }
        Outlaws.hunts(level);
        Ruins.decay(level, RNG);
        Ruins.visitors(level);
        if (level == level.getServer().overworld() && worldDay < day) {
            worldDay = day;
            Outlaws.eases(level, day);
        }
        if (level.getGameTime() % 1200 == 11) Ruins.spoil(level, RNG);
    }

    /** A town's day: its ransoms sent when it has the coin; a town standing empty given up. */
    static void daily(ServerLevel level, Villages.Village v, long day) {
        Prisoners.ransomDay(level, v, day);
        Ruins.abandoned(level, v, day);
    }

    // ------------------------------------------------------------------ the game's events

    /** A blow at a player: a hunter's that would take its quarry low catches it instead. */
    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !(e.getSource().getEntity() instanceof VillageFolkEntity f)) return;
        if (Outlaws.blow(p, f, e.getAmount())) e.setCanceled(true);
    }

    /** A death: an outlaw's (caught, or a bounty to claim); a town's folk killed by a player (murder). */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            com.jrpetty.mcassistant.Guard.run("an outlaw's death", () -> Outlaws.died(p, e.getSource().getEntity()));
            return;
        }
        if (!(e.getEntity() instanceof VillageFolkEntity f) || !(f.level() instanceof ServerLevel level)) return;
        if (!(e.getSource().getEntity() instanceof ServerPlayer p) || f.isShowcase() || f.isHired()) return;
        if (Armies.fighter(f.getUUID()) != null) return;            // a fighter in the field: a death in battle, not a murder
        UUID town = f.ownerId();
        Villages.Village v = town == null ? null : Villages.get(town);
        if (v == null) return;
        com.jrpetty.mcassistant.Guard.run("a folk killed", () -> Outlaws.crime(level, v, p, Outlaws.Deed.KILL, "killing " + f.displayNameCap()));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        Outlaws.loggedOff(e.getEntity().getUUID());
    }

    /** A ruin's store chest opened: searched. */
    @SubscribeEvent
    public static void onOpen(PlayerInteractEvent.RightClickBlock e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !(e.getEntity() instanceof ServerPlayer p)) return;
        if (!(level.getBlockState(e.getPos()).getBlock() instanceof ChestBlock) && !level.getBlockState(e.getPos()).is(net.minecraft.world.level.block.Blocks.BARREL)
            && !(level.getBlockState(e.getPos()).getBlock() instanceof com.jrpetty.mcassistant.block.StorehouseBlock)) return;
        if (Ruins.all().isEmpty()) return;
        Ruins.searched(level, p, e.getPos());
    }

    // ------------------------------------------------------------------ the talk, the spirits

    /** [FolkTalk.answer] A prisoner, a guard on prisoners or bounties, an outlaw's dealings: the answer, or null. */
    @Nullable
    public static String talk(VillageFolkEntity f, net.minecraft.world.entity.player.Player player, TalkTopic topic, String text) {
        if (!(player instanceof ServerPlayer p)) return null;
        String pow = Prisoners.talk(f, p, topic == TalkTopic.OPEN ? "" : text);
        if (pow != null && (topic == TalkTopic.OPEN || topic == TalkTopic.SAY || !text.isEmpty())) return pow;
        return Outlaws.talk(f, p, topic, text);
    }

    /** [VillageFolkEntity] Its spirits: held prisoner, or its kin held. */
    public static int mood(VillageFolkEntity f, int m, List<Object[]> why) {
        return Prisoners.mood(f, m, why);
    }

    /** [FolkTalk] Its words for what is on its mind (the keys of mood). */
    public static String moodWords(VillageFolkEntity f, String key) {
        return Prisoners.moodWords(f, key);
    }

    /** [FolkTalk] The gossip: the ruins round about, the outlaws on the poster. */
    public static List<String> gossip(VillageFolkEntity f) {
        List<String> out = new ArrayList<>(Ruins.gossip(f));
        out.addAll(Outlaws.gossip(f));
        return out;
    }

    /** [FolkTalk.talkContext] " prisoner" (it is one), " prisoners" (its town holds some), " outlaw" (the player is one here). */
    public static String contextFlags(VillageFolkEntity f, ServerPlayer p) {
        StringBuilder sb = new StringBuilder();
        if (Prisoners.isPow(f.getUUID())) sb.append(" prisoner");
        UUID town = f.ownerId();
        if (town != null && !Prisoners.heldBy(town).isEmpty()) sb.append(" prisoners");
        if (town != null && Outlaws.isOutlaw(town, p.getUUID())) sb.append(" outlaw");
        return sb.toString();
    }

    /** [Crier.script] The crier's lines: an outlaw's poster, the prisoners we hold, ours held elsewhere. */
    public static List<String> crierLines(ServerLevel level, Villages.Village v, long day) {
        List<String> out = new ArrayList<>();
        for (net.minecraft.nbt.CompoundTag r : Outlaws.outlawsOf(v.id()).values()) {
            out.add("Hear ye! " + r.getString("name") + " is an outlaw of this town, for " + r.getString("why")
                + (r.getInt("bounty") > 0 ? " — " + r.getInt("bounty") + " coins on their head!" : "!"));
            break;
        }
        List<Prisoners.Pow> held = Prisoners.heldBy(v.id()), ours = Prisoners.ofTown(v.id());
        if (!held.isEmpty()) out.add("We hold " + held.size() + (held.size() == 1 ? " prisoner" : " prisoners") + " of war. The watch will sell a release to anybody who'll pay it.");
        if (!ours.isEmpty()) out.add("Remember " + ours.get(0).name() + ", held prisoner in " + Villages.name(ours.get(0).holder()) + ". Bring them home!");
        return out;
    }

    /** [Gazette] The paper's piece: the prisoners and their keep, ours held, the outlaws, the ruins round about; null if nothing. */
    @Nullable
    public static String gazette(ServerLevel level, UUID id, long day) {
        List<String> items = new ArrayList<>();
        List<Prisoners.Pow> held = Prisoners.heldBy(id), ours = Prisoners.ofTown(id);
        if (!held.isEmpty()) {
            int owed = 0;
            for (Prisoners.Pow p : held) if (p.forRansom()) owed += p.worth();
            items.add(held.size() + (held.size() == 1 ? " prisoner" : " prisoners") + " of war in our keeping" + (owed > 0 ? ", " + owed + " coins' ransom owed us" : "") + ".");
        }
        for (Prisoners.Pow p : ours) if (items.size() < 3) items.add(p.name() + " is still held in " + Villages.name(p.holder()) + ", since day " + p.day() + ".");
        for (net.minecraft.nbt.CompoundTag r : Outlaws.outlawsOf(id).values()) if (items.size() < 5) items.add("WANTED: " + r.getString("name") + ", outlaw, for " + r.getString("why") + ".");
        List<String> ruins = Ruins.reportLines(level, id);
        if (!ruins.isEmpty() && day % 7 == 0) items.add(Character.toUpperCase(ruins.get(0).charAt(0)) + ruins.get(0).substring(1) + ".");
        if (items.isEmpty()) return null;
        return "§lAfter the war§r\n" + String.join("\n", items);
    }

    /** [VillageBoards.compose] The town's lines: its prisoners, its outlaws' posters (and its allies'). */
    public static List<String> board(UUID id) {
        List<String> out = new ArrayList<>(Prisoners.board(id));
        out.addAll(Outlaws.board(id));
        return out;
    }

    // ------------------------------------------------------------------ the commands: /village afterwar

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("afterwar")
            .executes(ctx -> say(ctx, v -> String.join("\n", lines(ctx.getSource().getLevel(), v))))
            .then(Commands.literal("prisoners").requires(src -> src.hasPermission(2))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 8))
                    .executes(ctx -> say(ctx, v -> {
                        Villages.Village from = AdminTools.foeOf(v);
                        if (from == null) return "No other town to take prisoners from.";
                        int n = Prisoners.takeNow(ctx.getSource().getLevel(), v, from, IntegerArgumentType.getInteger(ctx, "count"));
                        return n + " of " + Villages.name(from.id()) + "'s folk taken prisoner by " + Villages.name(v.id());
                    }))))
            .then(Commands.literal("release").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, v -> Prisoners.releaseAll(ctx.getSource().getLevel(), v.id(), "by order") + " prisoners let go")))
            .then(Commands.literal("ruin").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, v -> Ruins.ruinNow(ctx.getSource().getLevel(), v))))
            .then(Commands.literal("decay").requires(src -> src.hasPermission(2))
                .then(Commands.argument("days", IntegerArgumentType.integer(1, 30))
                    .executes(ctx -> {
                        ServerLevel level = ctx.getSource().getLevel();
                        Ruins.Ruin r = Ruins.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), 400);
                        if (r == null) {
                            ctx.getSource().sendFailure(Component.literal("No ruin within 400 blocks."));
                            return 0;
                        }
                        int n = Ruins.decayNow(level, r.id(), IntegerArgumentType.getInteger(ctx, "days"));
                        ctx.getSource().sendSuccess(() -> Component.literal(r.title() + ": " + n + " blocks gone to ruin"), false);
                        return 1;
                    })))
            .then(Commands.literal("outlaw").requires(src -> src.hasPermission(2))
                .then(Commands.argument("player", StringArgumentType.word())
                    .executes(ctx -> say(ctx, v -> {
                        ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayerByName(StringArgumentType.getString(ctx, "player"));
                        return p == null ? "No such player." : outlawNow(ctx.getSource().getLevel(), v, p);
                    }))))
            .then(Commands.literal("pardon").requires(src -> src.hasPermission(2))
                .then(Commands.argument("player", StringArgumentType.word())
                    .executes(ctx -> say(ctx, v -> {
                        ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayerByName(StringArgumentType.getString(ctx, "player"));
                        return p == null ? "No such player." : pardonNow(ctx.getSource().getLevel(), v, p);
                    }))))
            .then(Commands.literal("stage").requires(src -> src.hasPermission(2))
                .executes(ctx -> say(ctx, v -> String.join("\n", AfterWarStage.stage(ctx.getSource().getLevel(), v, BlockPos.containing(ctx.getSource().getPosition()), null))))
                .then(Commands.argument("outlaw", StringArgumentType.word())
                    .executes(ctx -> say(ctx, v -> String.join("\n", AfterWarStage.stage(ctx.getSource().getLevel(), v, BlockPos.containing(ctx.getSource().getPosition()),
                        ctx.getSource().getServer().getPlayerList().getPlayerByName(StringArgumentType.getString(ctx, "outlaw"))))))))
            .then(Commands.literal("hunt").requires(src -> src.hasPermission(2))
                .then(Commands.argument("player", StringArgumentType.word())
                    .executes(ctx -> say(ctx, v -> {
                        ServerPlayer p = ctx.getSource().getServer().getPlayerList().getPlayerByName(StringArgumentType.getString(ctx, "player"));
                        if (p == null) return "No such player.";
                        if (!Outlaws.isOutlaw(v.id(), p.getUUID())) return p.getName().getString() + " is no outlaw of " + Villages.name(v.id()) + ".";
                        return Outlaws.ride(ctx.getSource().getLevel(), v, p, ctx.getSource().getLevel().getGameTime()) ? "The hunters ride out." : "No hunters free.";
                    }))));
    }

    /** [admin, command] A player declared an outlaw of this town now (wanted to the line, as for a crime seen). */
    public static String outlawNow(ServerLevel level, Villages.Village v, ServerPlayer p) {
        boolean was = Outlaws.liveNow();
        Outlaws.liveForTests(true);
        int need = Math.max(0, Outlaws.OUTLAW - Outlaws.wanted(v.id(), p.getUUID()));
        int w = Outlaws.crime(level, v, p, Outlaws.Deed.HARM, "defying the town's law, by decree");
        if (w < Outlaws.OUTLAW) {
            net.minecraft.nbt.CompoundTag r = Outlaws.record(v.id(), p.getUUID(), true);
            r.putInt("wanted", Outlaws.OUTLAW + 5);
            Outlaws.declare(level, v, p, r, level.getDayTime() / 24000L);
        }
        Outlaws.liveForTests(was);
        return p.getName().getString() + " is an outlaw of " + Villages.name(v.id()) + " (wanted " + Outlaws.wanted(v.id(), p.getUUID()) + ", bounty "
            + Outlaws.bounty(v.id(), p.getUUID()) + ")" + (need > 0 ? "" : "");
    }

    /** [admin, command] A player's account with this town settled by decree: pardoned. */
    public static String pardonNow(ServerLevel level, Villages.Village v, ServerPlayer p) {
        net.minecraft.nbt.CompoundTag r = Outlaws.record(v.id(), p.getUUID(), false);
        if (r == null || !r.getBoolean("outlaw")) return p.getName().getString() + " is no outlaw of " + Villages.name(v.id()) + ".";
        com.jrpetty.mcassistant.village.Ledger.addCoins(v.id(), r.getInt("bounty"));
        Outlaws.settle(v.id(), p.getUUID(), r, level.getDayTime() / 24000L, "pardoned by decree", 0);
        return p.getName().getString() + " is pardoned by " + Villages.name(v.id());
    }

    /** The after-the-war lines for a town: its prisoners, ours held, the outlaws, the ruins it knows. */
    static List<String> lines(ServerLevel level, Villages.Village v) {
        long day = level.getDayTime() / 24000L;
        List<String> out = new ArrayList<>();
        out.add("After the war, " + Villages.name(v.id()) + ":");
        List<String> held = Prisoners.report(v.id(), day), ours = Prisoners.oursHeld(v.id()), outlaws = Outlaws.report(v.id()), ruins = Ruins.reportLines(level, v.id());
        out.add("Prisoners held: " + (held.isEmpty() ? "none" : ""));
        for (String s : held) out.add("  " + s);
        out.add("Ours held elsewhere: " + (ours.isEmpty() ? "none" : ""));
        for (String s : ours) out.add("  " + s);
        out.add("Players on the books: " + (outlaws.isEmpty() ? "none" : ""));
        for (String s : outlaws) out.add("  " + s);
        out.add("Ruins known: " + (ruins.isEmpty() ? "none" : ""));
        for (String s : ruins) out.add("  " + s);
        return out;
    }

    @Nullable
    private static Villages.Village here(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        Villages.Village v = Villages.nearest(level, BlockPos.containing(ctx.getSource().getPosition()), Villages.VILLAGE_RANGE * 4);
        if (v == null && !Villages.every().isEmpty()) v = Villages.every().get(0);
        if (v == null) ctx.getSource().sendFailure(Component.literal("No village within reach."));
        return v;
    }

    private static int say(CommandContext<CommandSourceStack> ctx, java.util.function.Function<Villages.Village, String> what) {
        Villages.Village v = here(ctx);
        if (v == null) return 0;
        String out = what.apply(v);
        ctx.getSource().sendSuccess(() -> Component.literal(out), false);
        return 1;
    }
}
