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
    }

    /** The page this player last had open (tests). */
    public static Ctx ctx(ServerPlayer p) {
        return CTX.computeIfAbsent(p.getUUID(), u -> new Ctx());
    }

    /** Open a page for the player, with its arguments. */
    public static void open(ServerPlayer p, String page, @Nullable CompoundTag args) {
        Ctx c = ctx(p);
        if (!PAGES.containsKey(page)) page = "life";
        c.page = page;
        c.tab = args != null && args.contains("tab") ? args.getString("tab") : "";
        c.args = args == null ? new CompoundTag() : args.copy();
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

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        CTX.remove(e.getEntity().getUUID());
    }
}
