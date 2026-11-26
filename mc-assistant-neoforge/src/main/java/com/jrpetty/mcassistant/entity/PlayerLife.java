package com.jrpetty.mcassistant.entity;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.net.LifeNetwork;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [player15] The player's own pages, and the one way their buttons come back (net/LifeNetwork, client/LifeScreen).
 * <ul>
 * <li><b>The pages.</b> "life" your life in town (LifeTown); "shop" your shop (PlayerShops); "start" starting a business
 *     (StartBusiness); "firm" your businesses (MyBusinesses); "jobs" the jobs board (PlayerJobs); "all" everything a
 *     player can do and where (InWorld). Each is written fresh on the server whenever it is opened or a button on it is
 *     pressed, and checked there: nothing the client says is believed.</li>
 * <li><b>No commands.</b> A server may ban commands for players. Every page acts through the same code the commands
 *     call, and the screens that used to send a command as chat (the business books, the civic screen, the knights'
 *     page) send it here instead ("cmd"): it is run on the server for the player at a player's own standing (never an
 *     operator's, so no operator command can be reached this way) and only if it is one of the mod's own commands,
 *     without going through chat or the command event a ban would stop. What it said comes back on the screen and in
 *     chat.</li>
 * </ul>
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class PlayerLife {

    private PlayerLife() {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

    /** A page: written for this player, and what its buttons do (the answer a message, "!" before a refusal). */
    public interface Page {
        LifePage build(ServerPlayer p, Ctx c);

        @Nullable
        String act(ServerPlayer p, Ctx c, String action, CompoundTag args);
    }

    /** Where the player is: the page, its tab and its arguments (the shop, the firm, the step), and the last word. */
    public static final class Ctx {
        public String page = "life", tab = "";
        public CompoundTag args = new CompoundTag();
        String msg = "";
        boolean ok = true;
        @Nullable CompoundTag marker;

        public String str(String k) {
            return args.getString(k);
        }
    }

    private static final Map<String, Page> PAGES = new LinkedHashMap<>();
    private static final Map<UUID, Ctx> CTX = new ConcurrentHashMap<>();
    /** The roots of the mod's own commands a page may run for a player. */
    static final Set<String> OURS = Set.of("village", "assistant");

    static {
        PAGES.put("life", new LifeTown());
        PAGES.put("shop", new ShopPage());
        PAGES.put("start", new StartBusiness());
        PAGES.put("firm", new MyBusinesses());
        PAGES.put("jobs", new PlayerJobs.Board());
        PAGES.put("all", new InWorld());
        PAGES.put("farm", new FarmPage());                                  // [wpb] a player's own farm as a business
    }

    /** The page this player last had open (tests). */
    public static Ctx ctx(ServerPlayer p) {
        return CTX.computeIfAbsent(p.getUUID(), u -> new Ctx());
    }

    /** Open a page for the player, with its arguments. */
    public static void open(ServerPlayer p, String page, @Nullable CompoundTag args) {
        ServicePoints.closed(p);                                            // a counter's screen it came from is done with
        Ctx c = ctx(p);
        if (!PAGES.containsKey(page)) page = "life";
        boolean resume = (args == null || args.isEmpty()) && c.page.equals(page) && !c.args.isEmpty();   // back where you were
        c.page = page;
        if (!resume) {
            c.tab = args != null && args.contains("tab") ? args.getString("tab") : "";
            c.args = args == null ? new CompoundTag() : args.copy();
        }
        if (page.equals("start") && c.args.getInt("step") >= 4) c.args = new CompoundTag();   // a business started: a fresh start
        c.msg = "";
        send(p, c);
    }

    /** A button on a page. */
    public static void act(ServerPlayer p, String page, String action, CompoundTag args) {
        if (page.equals("cmd")) {
            String said = command(p, args.getString("line"));
            Ctx c = CTX.get(p.getUUID());
            if (c != null && args.getBoolean("page")) {
                c.msg = said;
                send(p, c);
            }
            return;
        }
        Page pg = PAGES.get(page);
        if (pg == null) return;
        Ctx c = ctx(p);
        if (action.equals("open")) {
            open(p, page, args);
            return;
        }
        if (!c.page.equals(page)) {                                         // a stale screen: start it again
            c.page = page;
            c.args = new CompoundTag();
            c.tab = "";
        }
        if (action.equals("tab")) {
            c.tab = args.getString("tab");
            c.msg = "";
            send(p, c);
            return;
        }
        String said;
        try {
            said = pg.act(p, c, action, args);
        } catch (RuntimeException e) {
            LOG.warn("[player15] {} {} failed", page, action, e);
            said = "!That didn't work: " + e.getClass().getSimpleName();
        }
        if (said != null && said.equals("#close")) {
            CompoundTag t = new CompoundTag();
            t.putBoolean("close", true);
            LifeNetwork.send(p, t);
            return;
        }
        if (said != null && !said.isEmpty() && said.startsWith("#open:")) return;   // the page opened another itself
        c.ok = said == null || !said.startsWith("!");
        c.msg = said == null ? "" : c.ok ? said : said.substring(1);
        if (said != null && !said.isEmpty()) p.sendSystemMessage(Component.literal(c.msg));
        send(p, c);
    }

    /** Write the player's page and send it. */
    static void send(ServerPlayer p, Ctx c) {
        Page pg = PAGES.get(c.page);
        if (pg == null) return;
        LifePage page;
        try {
            page = pg.build(p, c);
        } catch (RuntimeException e) {
            LOG.warn("[player15] page {} failed", c.page, e);
            page = new LifePage(c.page, "Something went wrong", e.getClass().getSimpleName(), p.registryAccess());
        }
        page.message(c.msg, c.ok);
        CompoundTag t = page.done();
        if (!t.contains("key")) t.putString("key", c.page);
        if (c.marker != null) {
            t.put("marker", c.marker);
            c.marker = null;
        }
        LifeNetwork.send(p, t);
    }

    /** The page for tests, as it would be sent. */
    public static CompoundTag pageForTests(ServerPlayer p) {
        Ctx c = ctx(p);
        LifePage page = PAGES.get(c.page).build(p, c);
        page.message(c.msg, c.ok);
        return page.done();
    }

    /** The last word on the player's page (tests). */
    public static String messageForTests(ServerPlayer p) {
        Ctx c = ctx(p);
        return (c.ok ? "" : "!") + c.msg;
    }

    // ------------------------------------------------------------------ commands, without commands

    /** Test only: player commands refused as a server that bans them would (the command event cancelled). */
    public static volatile boolean BAN_COMMANDS_FOR_TESTS = false;

    @SubscribeEvent
    public static void onCommand(net.neoforged.neoforge.event.CommandEvent e) {
        if (BAN_COMMANDS_FOR_TESTS && e.getParseResults().getContext().getSource().getEntity() instanceof net.minecraft.world.entity.player.Player) {
            e.setCanceled(true);
        }
    }

    /**
     * One of the mod's own commands, run for the player: at a player's standing (permission 0, whatever the player's), only
     * under /village or /assistant, straight through the dispatcher (not through chat or the command event a ban would
     * stop). Returns what it said.
     */
    public static String command(ServerPlayer p, String line) {
        line = line == null ? "" : line.trim();
        if (line.startsWith("/")) line = line.substring(1);
        int sp = line.indexOf(' ');
        String root = sp < 0 ? line : line.substring(0, sp);
        if (!OURS.contains(root)) return "That isn't one of the town's.";
        List<String> said = new ArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override public void sendSystemMessage(Component c) {
                said.add(c.getString());
                p.sendSystemMessage(c);
            }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        CommandSourceStack src = p.createCommandSourceStack().withPermission(0).withSource(sink);
        var dispatcher = p.server.getCommands().getDispatcher();
        ParseResults<CommandSourceStack> parse = dispatcher.parse(line, src);
        try {
            dispatcher.execute(parse);
        } catch (CommandSyntaxException e) {
            String m = e.getRawMessage().getString();
            p.sendSystemMessage(Component.literal(m));
            return m;
        } catch (RuntimeException e) {
            LOG.warn("[player15] command {} failed", line, e);
            return "That didn't work.";
        }
        return said.isEmpty() ? "Done." : String.join(" ", said);
    }

    // ------------------------------------------------------------------ said to a folk

    /** The talk menu's phrases for the player's pages: the page opens, and the folk says a word. Null for anything else. */
    @Nullable
    public static String heard(VillageFolkEntity f, ServerPlayer p, String text) {
        String t = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT).trim();
        if (t.contains("village of my own") || t.contains("town of my own")) {   // [found23] the founding screen
            FoundingEasy.open(p, "talk");
            return "A place of your own? Good. Here's what the land round about looks like.";
        }
        String page = t.contains("my life in town") ? "life" : t.contains("start a business") ? "start" : t.contains("my businesses") ? "firm"
            : t.contains("jobs board") || t.contains("jobs for me") ? "jobs" : t.equals("my shop") || t.contains("my shop doing") ? "shop" : null;
        if (page == null) return null;
        open(p, page, null);
        return switch (page) {
            case "start" -> "A business of your own? Good for you. The charter's " + Enterprise.CHARTER_FEE + " coins; here's how it goes.";
            case "jobs" -> "Here's what's on the board.";
            case "shop" -> "Your shop? Let's see how it's doing.";
            default -> "Here, have a look.";
        };
    }

    // ------------------------------------------------------------------ small helpers for the pages

    /** A marker for the player on this place (the arrow and sparks of the Earnings page), sent with the next page. */
    public static void mark(ServerPlayer p, net.minecraft.core.BlockPos at, String label) {
        CompoundTag m = new CompoundTag();
        m.putIntArray("at", new int[]{ at.getX(), at.getY(), at.getZ() });
        m.putString("l", label);
        ctx(p).marker = m;
    }

    @Nullable
    static Villages.Village here(ServerPlayer p) {
        return Villages.nearest(p.serverLevel(), p.blockPosition(), 400);
    }

    static ServerLevel level(ServerPlayer p) {
        return p.serverLevel();
    }

    static String coins(long n) {
        return n == 1 ? "1 coin" : n + " coins";
    }

    /**
     * Operators only, for the camera and the smoke test: {@code /village playershops} lists the town's players' shops;
     * {@code visit} sends the nearest few folk to the nearest open one now (they walk in, look, queue and pay, or not).
     */
    @SubscribeEvent
    public static void onCommands(net.neoforged.neoforge.event.RegisterCommandsEvent e) {
        e.getDispatcher().register(net.minecraft.commands.Commands.literal("village").then(net.minecraft.commands.Commands.literal("playershops")
            .requires(s -> s.hasPermission(2))
            .executes(c -> {
                Villages.Village v = Villages.nearest(c.getSource().getLevel(), net.minecraft.core.BlockPos.containing(c.getSource().getPosition()), 400);
                StringBuilder sb = new StringBuilder();
                if (v != null) for (PlayerShops.Shop s : PlayerShops.all(v.id())) sb.append(s.name).append(" at ").append(s.anchor.toShortString()).append(": ")
                    .append(PlayerShops.stateWords(c.getSource().getLevel(), s)).append(", till ").append(s.till).append("; ");
                c.getSource().sendSuccess(() -> Component.literal(sb.length() == 0 ? "No players' shops here." : sb.toString()), false);
                return 1;
            })
            .then(net.minecraft.commands.Commands.literal("befriend").then(net.minecraft.commands.Commands.argument("who",
                    net.minecraft.commands.arguments.EntityArgument.player()).executes(c -> {
                ServerPlayer who = net.minecraft.commands.arguments.EntityArgument.getPlayer(c, "who");
                Villages.Village v = here(who);
                if (v == null) return 0;
                for (AssistantEntity a : Villages.folkOf(v.id())) {
                    if (a instanceof VillageFolkEntity f) f.persona().feelFor(who.getUUID(), who.getGameProfile().getName(), 60);
                }
                Standing.stir(v.id(), who.getUUID());
                c.getSource().sendSuccess(() -> Component.literal("BEFRIENDED " + Standing.of(v.id(), who.getUUID(), who.level().getGameTime()).title().words), false);
                return 1;
            })))
            .then(net.minecraft.commands.Commands.literal("visit").executes(c -> {
                ServerLevel level = c.getSource().getLevel();
                net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(c.getSource().getPosition());
                Villages.Village v = Villages.nearest(level, at, 400);
                if (v == null) return 0;
                PlayerShops.Shop best = null;
                for (PlayerShops.Shop s : PlayerShops.all(v.id())) if (best == null || s.anchor.distSqr(at) < best.anchor.distSqr(at)) best = s;
                if (best == null) return 0;
                int sent = 0;
                java.util.List<VillageFolkEntity> near = level.getEntitiesOfClass(VillageFolkEntity.class, new net.minecraft.world.phys.AABB(best.anchor).inflate(48));
                near.sort(java.util.Comparator.comparingDouble(f -> f.distanceToSqr(at.getX(), at.getY(), at.getZ())));
                for (VillageFolkEntity f : near) {
                    if (sent >= 3 || f.isBaby() || ShopCustomers.underWay(f) || f.purse() < 1) continue;
                    if (ShopCustomers.go(level, f, best, false)) sent++;
                }
                int n = sent;
                c.getSource().sendSuccess(() -> Component.literal("VISIT " + n + " folk set off for the shop"), false);
                return n;
            }))));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        CTX.remove(e.getEntity().getUUID());
    }
}
