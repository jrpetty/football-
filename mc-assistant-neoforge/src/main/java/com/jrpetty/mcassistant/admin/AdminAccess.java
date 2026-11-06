package com.jrpetty.mcassistant.admin;

import com.jrpetty.mcassistant.McAssistantMod;
import com.jrpetty.mcassistant.village.AdminSpec;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * [admin] Who may use the admin toolkit, area by area. The rule, in order:
 * <ol>
 * <li>the toolkit switched off in the server config, or the area in its disabledAreas: nobody;</li>
 * <li>the console, RCON, a command block or a function: its permission level against the area's level;</li>
 * <li>a player: the NeoForge permission node {@code mc_assistant.admin.<area>}, whose default (with no permissions mod)
 *     is the single-player owner, an operator at the area's level, or a player on allowedPlayers.</li>
 * </ol>
 * Destructive actions run at once only at destructiveLevel; below it they want a confirm token (AdminConfirm).
 */
@EventBusSubscriber(modid = McAssistantMod.MODID)
public final class AdminAccess {

    private AdminAccess() {}

    private static final Map<AdminSpec.Area, PermissionNode<Boolean>> NODES = new EnumMap<>(AdminSpec.Area.class);

    static {
        for (AdminSpec.Area a : AdminSpec.Area.values()) {
            NODES.put(a, new PermissionNode<>(McAssistantMod.MODID, "admin." + a.key(), PermissionTypes.BOOLEAN,
                (player, uuid, ctx) -> player != null && byDefault(player, a)));
        }
    }

    @SubscribeEvent
    public static void onGatherNodes(PermissionGatherEvent.Nodes event) {
        for (PermissionNode<Boolean> n : NODES.values()) event.addNodes(n);
    }

    /** What the node says when no permissions mod has a view: the owner, an operator at the area's level, or a listed player. */
    private static boolean byDefault(ServerPlayer p, AdminSpec.Area a) {
        return p.server.isSingleplayerOwner(p.getGameProfile()) || p.hasPermissions(AdminConfig.levelFor(a.key()))
            || AdminConfig.allowed(p.getGameProfile().getName(), p.getUUID().toString());
    }

    /** Why this source may not use this area, or null when it may. */
    @Nullable
    public static String refusal(CommandSourceStack src, AdminSpec.Area area) {
        if (!AdminConfig.enabled()) return "The admin tools are switched off on this server (adminTools in the server config).";
        if (AdminConfig.areaOff(area.key())) return "The " + area.title + " tools are switched off on this server (disabledAreas).";
        ServerPlayer p = src.getPlayer();
        if (p == null) {
            int want = AdminConfig.levelFor(area.key());
            return src.hasPermission(want) ? null : "The " + area.title + " tools want permission level " + want + ".";
        }
        return granted(p, area) ? null : "Only an operator may use the " + area.title + " admin tools.";
    }

    /** Does this player hold the area's node? */
    public static boolean granted(ServerPlayer p, AdminSpec.Area area) {
        if (!AdminConfig.enabled() || AdminConfig.areaOff(area.key())) return false;
        try {
            Boolean b = PermissionAPI.getPermission(p, NODES.get(area));
            return b != null && b;
        } catch (RuntimeException e) {
            return byDefault(p, area);           // the permission handler not up yet (a test, early start-up)
        }
    }

    /** May this player open the panel at all (any one area)? */
    public static boolean anyArea(ServerPlayer p) {
        for (AdminSpec.Area a : AdminSpec.Area.values()) if (granted(p, a)) return true;
        return false;
    }

    /** Do destructive actions run at once for this source, without a confirm token? */
    public static boolean mayDestroyNow(CommandSourceStack src) {
        return src.hasPermission(AdminConfig.destructiveLevel());
    }

    /** Who did it, for the log: the player's name, else the source's ("Server", "@"). */
    public static String who(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer();
        return p != null ? p.getGameProfile().getName() : src.getTextName();
    }

    /** The player behind a source, by id, for the confirm tokens (the console has none). */
    @Nullable
    public static UUID id(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer();
        return p == null ? null : p.getUUID();
    }
}
