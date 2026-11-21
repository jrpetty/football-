package com.jrpetty.mcassistant.admin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.Util;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * [admin] Confirm tokens for destructive actions. The first call says what will happen and hands back an id; the same
 * person runs /village admin confirm id within the configured time (thirty seconds by default) to go ahead. A token is
 * good once, only for whoever asked (the console's only for the console), and forgotten when it runs out.
 */
public final class AdminConfirm {

    private AdminConfirm() {}

    private record Pending(String id, @Nullable UUID owner, String what, long until, Supplier<AdminRunner.Outcome> act) {}

    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();

    /** Hold this action for confirmation; returns its id. */
    public static String ask(CommandSourceStack src, String what, Supplier<AdminRunner.Outcome> act) {
        PENDING.values().removeIf(p -> p.until < Util.getMillis());
        String id = AdminLog.shortId();
        PENDING.put(id, new Pending(id, AdminAccess.id(src), what, Util.getMillis() + AdminConfig.confirmSeconds() * 1000L, act));
        return id;
    }

    /** Run the held action, or say why not. */
    public static AdminRunner.Outcome confirm(CommandSourceStack src, String id) {
        Pending p = PENDING.get(id);
        if (p == null) return AdminRunner.Outcome.fail("No action waits on confirm " + id + " (it ran, ran out, or was never asked for).");
        if (!Objects.equals(p.owner, AdminAccess.id(src))) return AdminRunner.Outcome.fail("Confirm " + id + " is somebody else's to give.");
        PENDING.remove(id);
        if (p.until < Util.getMillis()) return AdminRunner.Outcome.fail("Confirm " + id + " ran out; ask again.");
        return p.act.get();
    }

    /** Tests: a token run out now. */
    public static void expireForTests(String id) {
        Pending p = PENDING.get(id);
        if (p != null) PENDING.put(id, new Pending(p.id, p.owner, p.what, 0L, p.act));
    }

    public static void resetForTests() {
        PENDING.clear();
    }
}
